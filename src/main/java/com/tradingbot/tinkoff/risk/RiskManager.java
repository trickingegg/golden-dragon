package com.tradingbot.tinkoff.risk;

import com.tradingbot.tinkoff.model.TradableInstrument;
import com.tradingbot.tinkoff.model.TradingSignal;
import com.tradingbot.tinkoff.api.TinkoffApiConnector;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Новые импорты для SDK v1.32
import ru.tinkoff.piapi.contract.v1.GetFuturesMarginResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Менеджер рисков, адаптированный для Tinkoff SDK v1.32
 * Работает с новыми типами данных Portfolio и Position
 */
public class RiskManager {
    private static final Logger logger = LoggerFactory.getLogger(RiskManager.class);
    private static final int MIN_SIGNAL_SCORE = 10;

    // Настройки риск-менеджмента
    @Getter @Setter
    private BigDecimal riskPercentage; // 1% от капитала на сделку

    // private final BigDecimal maxRiskPercentage = new BigDecimal("5.0"); // Максимальный риск
    private final BigDecimal minPositionSize; // Минимальный размер позиции в рублях
    private final BigDecimal maxPositionPercent; // Максимальный % капитала на позицию
    private final BigDecimal stopLossDistancePercent; // Расстояние стоп-лосса в процентах от цены входа
    private final BigDecimal minStopDistance;

    private final TinkoffApiConnector apiConnector;

    public RiskManager(BigDecimal riskPerTradePercent, BigDecimal minPositionSizeRub,
                       BigDecimal maxPositionPercent, BigDecimal stopLossDistancePercent,
                       BigDecimal minStopDistance, TinkoffApiConnector apiConnector) {
        this.riskPercentage = riskPerTradePercent;
        this.minPositionSize = minPositionSizeRub;
        this.maxPositionPercent = maxPositionPercent;
        this.stopLossDistancePercent = stopLossDistancePercent;
        this.minStopDistance = minStopDistance;
        this.apiConnector = apiConnector;
    }

    /**
     * Основной метод валидации торгового сигнала
     */
    public ValidationResult validateSignal(TradableInstrument instrument, TradingSignal signal, ru.tinkoff.piapi.core.models.Portfolio portfolio, Map<String, BigDecimal> availableBalances, ru.tinkoff.piapi.core.models.Position currentPosition) {
        logger.debug("🔍 Начало валидации сигнала: {}", signal != null ? signal.getSignalType() : "null");

        // Базовая валидация сигнала
        ValidationResult basicValidation = validateBasicSignal(signal);
        if (!basicValidation.isValid()) {
            return basicValidation;
        }

        // Валидация портфеля
        if (portfolio == null) {
            logger.warn("⚠️ Портфель не загружен");
            return ValidationResult.invalid("Данные портфеля недоступны");
        }

        BigDecimal initialCapital = calculateTotalPortfolioValue(portfolio);
        BigDecimal availableCurrencyBalance = availableBalances.getOrDefault(instrument.currency(), BigDecimal.ZERO);

        // Получаем текущую цену инструмента
        BigDecimal currentPrice = null; // <<-- ИЗМЕНЕНО: Инициализация null
        if (signal != null) { // <<-- ДОБАВЛЕНО: Защита от NullPointerException
            currentPrice = signal.getEntryPrice();
        }

        if (currentPrice == null) {
            return ValidationResult.invalid("Не удалось определить текущую цену инструмента.");
        }

        // Проверка соотношения риск/прибыль
        BigDecimal riskRewardRatio = calculateRiskRewardRatio(signal);
        if (riskRewardRatio.compareTo(new BigDecimal("1.5")) < 0) {
            logger.warn("[{}] Низкое соотношение риск/прибыль: 1:{}", instrument.name(), riskRewardRatio);
            return ValidationResult.invalid(String.format("Низкое соотношение риск/прибыль: 1:%.2f (требуется ≥1:1.5)", riskRewardRatio));
        }

        // Проверка концентрации риска
        ValidationResult concentrationCheck = checkRiskConcentration(signal, portfolio);
        if (!concentrationCheck.isValid()) {
            return concentrationCheck;
        }

        // Защита от "микро-стопов" (слишком близких стопов)
        BigDecimal actualStopDistance = currentPrice.multiply(stopLossDistancePercent.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP));
        if (actualStopDistance.compareTo(minStopDistance) < 0) {
            logger.warn("[{}] Сигнал отклонен: слишком короткое расстояние до стоп-лосса ({} < {}).",
                    instrument.name(), actualStopDistance.toPlainString(), minStopDistance.toPlainString());
            return ValidationResult.invalid("Слишком короткое расстояние до стоп-лосса.");
        }

        // Расчет размера позиции
        PositionSizeResult positionSizeResult = calculatePositionSize(
                instrument, signal, portfolio, availableBalances, currentPosition, initialCapital);

        if (!positionSizeResult.isValid()) {
            return ValidationResult.invalid(positionSizeResult.getDescription());
        }

        BigDecimal lotsToTrade = positionSizeResult.getPositionSize();
        BigDecimal tradeAmount = positionSizeResult.getPositionSizeInRubles();

        logger.debug("💰 Расчет требуемых средств: {} RUB", tradeAmount.toPlainString());

        // Учет маржинальных требований для фьючерсов
        if (instrument.type() == TradableInstrument.InstrumentType.FUTURE) {
            try {
                GetFuturesMarginResponse futuresMargin = apiConnector.getFuturesMarginResponse(instrument.identifier()).join();
                BigDecimal initialMargin = BigDecimal.ZERO;
                if (signal != null && signal.getSignalType() == TradingSignal.SignalType.BUY) {
                    initialMargin = TinkoffApiConnector.moneyValueToBigDecimal(futuresMargin.getInitialMarginOnBuy());
                } else if (signal != null && signal.getSignalType() == TradingSignal.SignalType.SELL) {
                    initialMargin = TinkoffApiConnector.moneyValueToBigDecimal(futuresMargin.getInitialMarginOnSell());
                }
                if (initialMargin.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal requiredMarginForTrade = initialMargin.multiply(lotsToTrade);
                    logger.debug("📊 [Фьючерс] Требуемая начальная маржа для {} лотов: {} {}",
                            lotsToTrade, requiredMarginForTrade.toPlainString(), instrument.currency().toUpperCase());

                    if (availableCurrencyBalance.compareTo(requiredMarginForTrade) < 0) {
                        return ValidationResult.invalid(String.format("Недостаточно маржи для фьючерса. Требуется: %s, Доступно: %s",
                                requiredMarginForTrade.toPlainString(), availableCurrencyBalance.toPlainString()));
                    }
                } else {
                    logger.warn("[{}] Не удалось получить маржинальные требования для фьючерса.", instrument.name());
                }

            } catch (Exception e) {
                logger.error("[{}] Ошибка при получении маржинальных требований для фьючерса: {}", instrument.name(), e.getMessage(), e);
                return ValidationResult.invalid("Ошибка при проверке маржинальных требований.");
            }
        } else { // Для акций проверяем просто доступный баланс
            if (availableCurrencyBalance.compareTo(tradeAmount) < 0) {
                return ValidationResult.invalid(String.format("Недостаточно средств. Требуется: %s, Доступно: %s",
                        tradeAmount.toPlainString(), availableCurrencyBalance.toPlainString()));
            }
        }

        logger.info("✅ Сигнал прошел валидацию. Рекомендуемый размер: {} лотов на сумму {} {}",
                lotsToTrade.toPlainString(), tradeAmount.toPlainString(), instrument.currency().toUpperCase());
        return ValidationResult.valid(lotsToTrade, tradeAmount, "Сигнал валиден.");
    }

    /**
     * Базовая валидация сигнала
     */
    private ValidationResult validateBasicSignal(TradingSignal signal) {
        if (signal == null) {
            logger.warn("❌ Сигнал равен null");
            return ValidationResult.invalid("Сигнал отсутствует");
        }

        if (signal.getSignalType() == TradingSignal.SignalType.HOLD) {
            logger.debug("ℹ️ Сигнал типа HOLD - действий не требуется");
            return ValidationResult.invalid("Сигнал не требует действий (HOLD)");
        }

        if (signal.getScore() < MIN_SIGNAL_SCORE) {
            logger.warn("Сигнал отклонен: низкая достоверность ({} < {})", signal.getScore(), MIN_SIGNAL_SCORE);
            return ValidationResult.invalid(
                    String.format("Низкая достоверность сигнала: %d < %d", signal.getScore(), MIN_SIGNAL_SCORE));
        }

        if (signal.getEntryPrice() == null || signal.getStopLoss() == null) {
            logger.warn("❌ Отсутствуют обязательные уровни цен");
            return ValidationResult.invalid("Отсутствуют цена входа или стоп-лосс");
        }

        return ValidationResult.valid(BigDecimal.ZERO, BigDecimal.ZERO, "Базовая валидация пройдена");
    }

    /**
     * Расчет размера позиции на основе данных портфеля SDK v1.32
     */
    private PositionSizeResult calculatePositionSize(com.tradingbot.tinkoff.model.TradableInstrument instrument,
                                                      TradingSignal signal,
                                                      ru.tinkoff.piapi.core.models.Portfolio portfolio,
                                                      Map<String, BigDecimal> availableBalances,
                                                      ru.tinkoff.piapi.core.models.Position currentPosition,
                                                      BigDecimal totalCapital) { // <<-- ДОБАВЛЕНО
        try {
            // Расчет общей стоимости портфеля
            // BigDecimal totalCapital = calculateTotalPortfolioValue(portfolio); // <<-- УДАЛЕНО

            logger.debug("💰 Общая стоимость портфеля: {} RUB", totalCapital);

            if (totalCapital.compareTo(BigDecimal.ZERO) <= 0) {
                return new PositionSizeResult(false, BigDecimal.ZERO,
                        "Нулевая или отрицательная стоимость портфеля", null, null, null);
            }

            BigDecimal entryPrice = signal.getEntryPrice();
            BigDecimal stopLoss = signal.getStopLoss();

            // Проверка минимальной стоп-дистанции для защиты от микро-стопов
            BigDecimal minStopDistance = entryPrice.multiply(new BigDecimal("0.001")); // Например, 0.1% от цены
            BigDecimal actualStopDistance = entryPrice.subtract(stopLoss).abs();
            if (actualStopDistance.compareTo(minStopDistance) < 0) {
                return new PositionSizeResult(false, BigDecimal.ZERO,
                        String.format("Стоп-дистанция (%.4f) слишком мала (мин. %.4f)", actualStopDistance, minStopDistance), null, null, null);
            }

            if (actualStopDistance.compareTo(BigDecimal.ZERO) == 0) {
                return new PositionSizeResult(false, BigDecimal.ZERO,
                        "Стоп-лосс равен цене входа", null, null, null);
            }

            // Расчет максимального риска на сделку
            BigDecimal maxRiskAmount = totalCapital
                    .multiply(riskPercentage)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

            // Базовый размер позиции в лотах, без учета текущей позиции
            // БЫЛО:
            // BigDecimal calculatedLots = maxRiskAmount.divide(actualStopDistance.multiply(entryPrice), 0, RoundingMode.DOWN);
            // СТАЛО (для акций, где 1 лот = 1 акция):
            BigDecimal calculatedLots = maxRiskAmount.divide(actualStopDistance, 0, RoundingMode.DOWN);

            // ========== ЛОГИКА НЕТТИНГА ПОЗИЦИЙ И ПРОВЕРКИ ДОСТУПНЫХ СРЕДСТВ ===========
            BigDecimal lotsToTrade = BigDecimal.ZERO;
            BigDecimal availableCurrencyBalance = availableBalances.getOrDefault(instrument.currency(), BigDecimal.ZERO);

            if (currentPosition != null && currentPosition.getQuantity().compareTo(BigDecimal.ZERO) != 0) {
                // Есть открытая позиция по данному инструменту
                boolean isLong = currentPosition.getQuantity().signum() > 0;

                if ((isLong && signal.getSignalType() == TradingSignal.SignalType.SELL) ||
                        (!isLong && signal.getSignalType() == TradingSignal.SignalType.BUY)) {
                    // Реверсный сигнал: сначала закрываем текущую позицию
                    BigDecimal currentLots = currentPosition.getQuantity();
                    lotsToTrade = currentLots.negate(); // Закрываем позицию
                    logger.info("🔄 Обнаружен реверсный сигнал. Закрываем текущую позицию {} лотов.", currentLots);

                    // После закрытия, возможно, открываем новую в другом направлении
                    // Рассчитываем лоты для новой позиции на оставшиеся средства
                    // Здесь нужна более сложная логика, но для MVP просто добавляем к закрытию
                    // TODO: Улучшить логику неттинга для открытия новой позиции после закрытия
                    lotsToTrade = lotsToTrade.add(calculatedLots); // пока просто добавляем
                } else {
                    // Сигнал в том же направлении, что и текущая позиция, просто увеличиваем
                    // TODO: Реализовать логику увеличения позиции с учетом доступных средств
                    lotsToTrade = calculatedLots;
                }
            } else {
                // Нет открытой позиции, просто открываем новую
                lotsToTrade = calculatedLots;
            }

            if (lotsToTrade.compareTo(BigDecimal.ZERO) <= 0) {
                return new PositionSizeResult(false, BigDecimal.ZERO, "Количество лотов для сделки <= 0", null, null, null);
            }

            // Проверка доступных средств/маржи (упрощенная)
            BigDecimal requiredFunds = lotsToTrade.multiply(entryPrice);
            if (availableCurrencyBalance.compareTo(requiredFunds) < 0) {
                // Недостаточно средств, уменьшаем количество лотов
                BigDecimal affordableLots = availableCurrencyBalance.divide(entryPrice, 0, RoundingMode.DOWN);
                if (affordableLots.compareTo(BigDecimal.ZERO) == 0) {
                    return new PositionSizeResult(false, BigDecimal.ZERO,
                            String.format("Недостаточно средств (%s %s) для покупки даже 1 лота по %s %s",
                                    availableCurrencyBalance, instrument.currency(), entryPrice, instrument.currency()), null, null, null);
                }
                lotsToTrade = affordableLots;
                logger.warn("⚠️ Недостаточно средств, уменьшаем заявку до {} лотов.", lotsToTrade);
            }

            // Корректировка на минимальный размер позиции и лотность
            if (lotsToTrade.multiply(entryPrice).compareTo(minPositionSize) < 0) { // <<-- ИСПРАВЛЕНО: используем minPositionSize
                return new PositionSizeResult(false, BigDecimal.ZERO,
                        String.format("Рассчитанный размер позиции (%.2f %s) меньше минимально допустимого (%.2f %s)",
                                lotsToTrade.multiply(entryPrice), instrument.currency(), minPositionSize, instrument.currency()), null, null, null);
            }

            // TODO: Учесть instrument.lot() и instrument.minPriceIncrement()

            BigDecimal positionSizeInRubles = lotsToTrade.multiply(entryPrice);
            BigDecimal positionPercentage = positionSizeInRubles
                    .divide(totalCapital, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

            if (positionPercentage.compareTo(maxPositionPercent) > 0) {
                logger.warn("⚠️ Рассчитанный размер позиции (%.1f%%) превышает максимально допустимый (%.1f%%). Корректировка...",
                        positionPercentage, maxPositionPercent);
                // Пересчитываем positionSizeInRubles, чтобы оно соответствовало maxPositionPercent
                positionSizeInRubles = totalCapital.multiply(maxPositionPercent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                // Пересчитываем lotsToTrade на основе скорректированного positionSizeInRubles
                if (entryPrice.compareTo(BigDecimal.ZERO) > 0) {
                    lotsToTrade = positionSizeInRubles.divide(entryPrice, 0, RoundingMode.DOWN);
                } else {
                    lotsToTrade = BigDecimal.ZERO; // Избегаем деления на ноль
                }

                // Дополнительная проверка на минимальный размер после корректировки
                if (lotsToTrade.multiply(entryPrice).compareTo(minPositionSize) < 0) {
                    return new PositionSizeResult(false, BigDecimal.ZERO,
                            String.format("Скорректированный размер позиции (%.2f %s) все еще меньше минимально допустимого (%.2f %s)",
                                    lotsToTrade.multiply(entryPrice), instrument.currency(), minPositionSize, instrument.currency()), null, null, null);
                }

                positionPercentage = maxPositionPercent; // Устанавливаем процент на максимальный
            }

            String description = String.format("Позиция: %.2f %s (%.1f%% капитала), %s лотов",
                    positionSizeInRubles, instrument.currency(), positionPercentage, lotsToTrade);

            logger.debug("📊 Расчет позиции: {} лотов, {} {} ({}% капитала)",
                    lotsToTrade, positionSizeInRubles, instrument.currency(), positionPercentage);

            return new PositionSizeResult(true, lotsToTrade, description,
                    positionSizeInRubles, maxRiskAmount, positionPercentage);

        } catch (Exception e) {
            logger.error("❌ Ошибка при расчете размера позиции", e);
            return new PositionSizeResult(false, BigDecimal.ZERO,
                    "Ошибка расчета: " + e.getMessage(), null, null, null);
        }
    }

    /**
     * Расчет общей стоимости портфеля с использованием SDK v1.32
     */
    // Файл: src/main/java/com/tradingbot/tinkoff/risk/RiskManager.java

    private BigDecimal calculateTotalPortfolioValue(ru.tinkoff.piapi.core.models.Portfolio portfolio) {
        if (portfolio == null) {
            logger.warn("Портфель не предоставлен для расчета стоимости, возвращен ноль.");
            return BigDecimal.ZERO;
        }

        // 1. Считаем стоимость всех ценных бумаг
        BigDecimal positionsValue = portfolio.getPositions().stream()
                .map(position -> position.getQuantity()
                        .multiply(TinkoffApiConnector.moneyToBigDecimal(position.getCurrentPrice())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 2. Считаем стоимость всех валютных остатков
        BigDecimal currenciesValue = TinkoffApiConnector.moneyToBigDecimal(portfolio.getTotalAmountCurrencies());

        BigDecimal totalValue = positionsValue.add(currenciesValue);
        logger.debug("Расчет стоимости портфеля: Позиции={} + Валюта={} = {}", positionsValue, currenciesValue, totalValue);

        return totalValue;
    }


    /**
     * Расчет соотношения риск/прибыль
     */
    private BigDecimal calculateRiskRewardRatio(TradingSignal signal) {
        try {
            BigDecimal entryPrice = signal.getEntryPrice();
            BigDecimal stopLoss = signal.getStopLoss();
            BigDecimal takeProfit = signal.getTakeProfit();

            if (entryPrice == null || stopLoss == null || takeProfit == null) {
                logger.warn("⚠️ Отсутствуют данные для расчета R/R");
                return BigDecimal.ZERO;
            }

            BigDecimal risk = entryPrice.subtract(stopLoss).abs();
            BigDecimal reward = takeProfit.subtract(entryPrice).abs();

            if (risk.compareTo(BigDecimal.ZERO) == 0) {
                logger.warn("⚠️ Нулевой риск при расчете R/R");
                return BigDecimal.ZERO;
            }

            BigDecimal ratio = reward.divide(risk, 2, RoundingMode.HALF_UP);
            logger.debug("📈 Соотношение риск/прибыль: 1:{}", ratio);

            return ratio;

        } catch (Exception e) {
            logger.error("❌ Ошибка расчета R/R", e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * Проверка концентрации риска
     */
    private ValidationResult checkRiskConcentration(TradingSignal signal, ru.tinkoff.piapi.core.models.Portfolio portfolio) {
        // Здесь можно добавить дополнительные проверки:
        // - Максимальное количество открытых позиций
        // - Концентрация в одном секторе
        // - Корреляция с существующими позициями
        // Пока возвращаем положительный результат

        return ValidationResult.valid(BigDecimal.ZERO, BigDecimal.ZERO, "Концентрация риска в норме");
    }

    // =============== РЕЗУЛЬТИРУЮЩИЕ КЛАССЫ ===============

    @Getter @Setter @AllArgsConstructor
    public static class PositionSizeResult {
        private final boolean valid;
        private final BigDecimal positionSize; // Количество лотов
        private final String description;
        private BigDecimal positionSizeInRubles; // Размер в валюте
        private BigDecimal riskAmount; // Размер риска
        private BigDecimal positionPercentage; // Процент от капитала
    }

    @Getter @AllArgsConstructor
    public static class ValidationResult {
        private final boolean valid;
        private final String message;
        private final BigDecimal lots;
        private final BigDecimal tradeAmount; // <<-- ДОБАВЛЕНО

        public ValidationResult(boolean valid, String message, BigDecimal lots) {
            this(valid, message, lots, BigDecimal.ZERO); // <<-- ИЗМЕНЕНО
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message, BigDecimal.ZERO, BigDecimal.ZERO); // <<-- ИЗМЕНЕНО
        }

        public static ValidationResult valid(BigDecimal lots, BigDecimal tradeAmount, String message) {
            return new ValidationResult(true, message, lots, tradeAmount); // <<-- ИЗМЕНЕНО
        }
    }
}

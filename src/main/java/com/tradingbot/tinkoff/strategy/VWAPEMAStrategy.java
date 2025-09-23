package com.tradingbot.tinkoff.strategy;

import com.tradingbot.tinkoff.model.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.num.DecimalNum;

// Новые импорты для SDK v1.32
import ru.tinkoff.piapi.contract.v1.OrderBook;
import ru.tinkoff.piapi.contract.v1.Order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VWAP и EMA стратегия, адаптированная для Tinkoff SDK v1.32
 * Использует технические индикаторы TA4J и новые типы данных API
 */
public class VWAPEMAStrategy {
    private static final Logger logger = LoggerFactory.getLogger(VWAPEMAStrategy.class);

    // Параметры индикаторов
    private static final int FAST_EMA_PERIOD = 9;
    private static final int SLOW_EMA_PERIOD = 21;
    private static final int ATR_PERIOD = 14;
    private static final int VWAP_PERIOD = 20;
    private static final int MIN_SIGNAL_SCORE = 70;

    // Индикаторы TA4J
    private final BarSeries barSeries;
    private final ClosePriceIndicator closePrice;
    private final VWAPIndicator vwap;
    private final EMAIndicator fastEMA;
    private final EMAIndicator slowEMA;
    private final ATRIndicator atr;

    // Состояние стратегии
    private final AtomicInteger signalCounter = new AtomicInteger(0);
    private volatile OrderBook lastOrderBook; // Новый тип из SDK v1.32

    /**
     * Конструктор стратегии
     */
    public VWAPEMAStrategy(BarSeries barSeries) {
        this.barSeries = barSeries;
        this.closePrice = new ClosePriceIndicator(barSeries);
        this.vwap = new VWAPIndicator(barSeries, VWAP_PERIOD);
        this.fastEMA = new EMAIndicator(closePrice, FAST_EMA_PERIOD);
        this.slowEMA = new EMAIndicator(closePrice, SLOW_EMA_PERIOD);
        this.atr = new ATRIndicator(barSeries, ATR_PERIOD);

        logger.info("✅ VWAPEMAStrategy инициализирована с параметрами: FastEMA={}, SlowEMA={}, ATR={}, VWAP={}",
                FAST_EMA_PERIOD, SLOW_EMA_PERIOD, ATR_PERIOD, VWAP_PERIOD);
    }

    /**
     * Минимальное количество данных для стабильной работы индикаторов
     */
    public int getUnstablePeriod() {
        return Math.max(SLOW_EMA_PERIOD, VWAP_PERIOD) + 2;
    }

    /**
     * Обновление данных стакана заявок (новый формат SDK v1.32)
     */
    public void updateOrderBook(OrderBook orderBook) {
        if (orderBook != null) {
            this.lastOrderBook = orderBook;
            logger.debug("📊 Обновлен стакан для {}: bids={}, asks={}",
                    orderBook.getFigi(),
                    orderBook.getBidsCount(),
                    orderBook.getAsksCount());
        }
    }

    /**
     * Главный метод анализа сигналов
     */
    public TradingSignal analyzeSignal(com.tradingbot.tinkoff.model.TradableInstrument instrument) {
        int lastIndex = barSeries.getEndIndex();

        // Проверяем достаточность данных
        if (lastIndex < getUnstablePeriod()) {
            logger.debug("⏳ Недостаточно данных для анализа. Текущий индекс: {}, требуется: {}",
                    lastIndex, getUnstablePeriod());
            return new TradingSignal(TradingSignal.SignalType.HOLD, 0,
                    "Накопление данных... (" + (lastIndex + 1) + "/" + getUnstablePeriod() + ")");
        }

        // Получаем значения индикаторов
        Num currentPrice = closePrice.getValue(lastIndex);
        Num currentVWAP = vwap.getValue(lastIndex);
        Num currentFastEMA = fastEMA.getValue(lastIndex);
        Num currentSlowEMA = slowEMA.getValue(lastIndex);
        Num prevFastEMA = fastEMA.getValue(lastIndex - 1);
        Num prevSlowEMA = slowEMA.getValue(lastIndex - 1);
        Num currentATR = atr.getValue(lastIndex);

        // Определяем тренд относительно VWAP
        TrendDirection trendDirection = currentPrice.isGreaterThan(currentVWAP) ?
                TrendDirection.BULLISH : TrendDirection.BEARISH;

        // Определяем пересечения EMA
        boolean emaCrossoverBullish = prevFastEMA.isLessThanOrEqual(prevSlowEMA) &&
                currentFastEMA.isGreaterThan(currentSlowEMA);
        boolean emaCrossoverBearish = prevFastEMA.isGreaterThanOrEqual(prevSlowEMA) &&
                currentFastEMA.isLessThan(currentSlowEMA);

        // Рассчитываем балл сигнала
        int signalScore = calculateSignalScore(trendDirection, emaCrossoverBullish, emaCrossoverBearish,
                currentPrice, currentVWAP, currentATR);

        logger.debug("📈 Анализ: Price={}, VWAP={}, Trend={}, Score={}",
                currentPrice.doubleValue(), currentVWAP.doubleValue(), trendDirection, signalScore);

        // Генерируем сигнал если балл достаточно высок
        if (signalScore >= MIN_SIGNAL_SCORE) {
            TradingSignal.SignalType signalType = TradingSignal.SignalType.HOLD;

            if (trendDirection == TrendDirection.BULLISH && emaCrossoverBullish) {
                signalType = TradingSignal.SignalType.BUY;
            } else if (trendDirection == TrendDirection.BEARISH && emaCrossoverBearish) {
                signalType = TradingSignal.SignalType.SELL;
            }

            if (signalType == TradingSignal.SignalType.HOLD) {
                return new TradingSignal(TradingSignal.SignalType.HOLD, signalScore,
                        "Высокий балл, но нет четкого сигнала");
            }

            // Создаем детальный сигнал
            return createTradingSignal(signalType, signalScore, trendDirection,
                    currentPrice, currentATR, instrument);
        }

        return new TradingSignal(TradingSignal.SignalType.HOLD, signalScore,
                "Слабый сигнал (балл < " + MIN_SIGNAL_SCORE + ")");
    }

    /**
     * Создание торгового сигнала с расчетом уровней
     */
    private TradingSignal createTradingSignal(TradingSignal.SignalType signalType,
                                              int signalScore,
                                              TrendDirection trendDirection,
                                              Num currentPrice,
                                              Num currentATR,
                                              com.tradingbot.tinkoff.model.TradableInstrument instrument) {

        String description = String.format("%s сигнал - %s тренд по VWAP. Балл: %d",
                signalType, trendDirection, signalScore);

        // Конвертируем в BigDecimal для расчетов
        BigDecimal entryPrice = ((DecimalNum) currentPrice).getDelegate();
        BigDecimal atrValue = ((DecimalNum) currentATR).getDelegate();

        // Рассчитываем уровни стоп-лосса и тейк-профита
        BigDecimal stopLoss = calculateStopLoss(entryPrice, atrValue, signalType);
        BigDecimal takeProfit = calculateTakeProfit(entryPrice, stopLoss, signalType,
                new BigDecimal("2.0"));

        // Создаем и настраиваем сигнал
        TradingSignal signal = new TradingSignal(signalType, signalScore, description);
        signal.setInstrument(instrument);
        signal.setEntryPrice(entryPrice);
        signal.setStopLoss(stopLoss);
        signal.setTakeProfit(takeProfit);
        signal.setTimestamp(ZonedDateTime.now());
        signal.setSignalId(signalCounter.incrementAndGet());

        logger.info("🎯 Сгенерирован сигнал #{}: {} на {} по цене {}, SL: {}, TP: {}",
                signal.getSignalId(), signalType, signal.getInstrument(),
                entryPrice, stopLoss, takeProfit);

        return signal;
    }

    /**
     * Расчет балла сигнала на основе различных факторов
     */
    private int calculateSignalScore(TrendDirection trend,
                                     boolean emaCrossoverBullish,
                                     boolean emaCrossoverBearish,
                                     Num currentPrice,
                                     Num currentVWAP,
                                     Num currentATR) {
        int score = 0;

        // Балл за пересечение EMA в направлении тренда (40 баллов)
        if ((trend == TrendDirection.BULLISH && emaCrossoverBullish) ||
                (trend == TrendDirection.BEARISH && emaCrossoverBearish)) {
            score += 40;
            logger.debug("📊 +40 баллов за пересечение EMA в направлении тренда");
        }

        // Балл за направление цены относительно VWAP (20 баллов)
        if ((trend == TrendDirection.BULLISH && currentPrice.isGreaterThan(currentVWAP)) ||
                (trend == TrendDirection.BEARISH && currentPrice.isLessThan(currentVWAP))) {
            score += 20;
            logger.debug("📊 +20 баллов за подтверждение тренда по VWAP");
        }

        // Анализ стакана заявок (15 баллов) - обновлено для SDK v1.32
        if (lastOrderBook != null && lastOrderBook.getBidsCount() > 0 && lastOrderBook.getAsksCount() > 0) {
            long totalBidVolume = lastOrderBook.getBidsList().stream()
                    .mapToLong(Order::getQuantity)
                    .sum();
            long totalAskVolume = lastOrderBook.getAsksList().stream()
                    .mapToLong(Order::getQuantity)
                    .sum();

            if ((trend == TrendDirection.BULLISH && totalBidVolume > totalAskVolume * 1.2) ||
                    (trend == TrendDirection.BEARISH && totalAskVolume > totalBidVolume * 1.2)) {
                score += 15;
                logger.debug("📊 +15 баллов за поддержку стакана (Bids: {}, Asks: {})",
                        totalBidVolume, totalAskVolume);
            }
        }

        // Анализ волатильности через ATR (10 баллов)
        BigDecimal atrValue = ((DecimalNum) currentATR).getDelegate();
        BigDecimal priceValue = ((DecimalNum) currentPrice).getDelegate();

        if (atrValue.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal minVolatility = priceValue.multiply(new BigDecimal("0.001")); // 0.1%
            if (atrValue.compareTo(minVolatility) > 0) {
                score += 10;
                logger.debug("📊 +10 баллов за достаточную волатильность (ATR: {})", atrValue);
            }
        }

        int finalScore = Math.min(score, 100);
        logger.debug("📊 Итоговый балл сигнала: {} из 100", finalScore);

        return finalScore;
    }

    /**
     * Расчет уровня стоп-лосса на основе ATR
     */
    private BigDecimal calculateStopLoss(BigDecimal entryPrice,
                                         BigDecimal atrValue,
                                         TradingSignal.SignalType signalType) {
        BigDecimal atrMultiplier = new BigDecimal("1.5");

        if (signalType == TradingSignal.SignalType.BUY) {
            return entryPrice.subtract(atrValue.multiply(atrMultiplier))
                    .setScale(4, RoundingMode.HALF_UP);
        } else {
            return entryPrice.add(atrValue.multiply(atrMultiplier))
                    .setScale(4, RoundingMode.HALF_UP);
        }
    }

    /**
     * Расчет уровня тейк-профита
     */
    private BigDecimal calculateTakeProfit(BigDecimal entryPrice,
                                           BigDecimal stopLoss,
                                           TradingSignal.SignalType signalType,
                                           BigDecimal riskRewardRatio) {
        BigDecimal riskAmount = entryPrice.subtract(stopLoss).abs();

        if (signalType == TradingSignal.SignalType.BUY) {
            return entryPrice.add(riskAmount.multiply(riskRewardRatio))
                    .setScale(4, RoundingMode.HALF_UP);
        } else {
            return entryPrice.subtract(riskAmount.multiply(riskRewardRatio))
                    .setScale(4, RoundingMode.HALF_UP);
        }
    }

    /**
     * Направление тренда
     */
    public enum TrendDirection {
        BULLISH("Бычий"),
        BEARISH("Медвежий");

        private final String displayName;

        TrendDirection(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}

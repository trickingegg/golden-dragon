package com.tradingbot.tinkoff.processor;

import com.tradingbot.tinkoff.api.TinkoffApiConnector;
import com.tradingbot.tinkoff.model.TradableInstrument;
import com.tradingbot.tinkoff.model.TradingSignal;
import com.tradingbot.tinkoff.risk.RiskManager;
import com.tradingbot.tinkoff.strategy.MultiStrategyManager;
import com.tradingbot.tinkoff.tracking.SignalTracker;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DecimalNum;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.stream.MarketDataSubscriptionService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.Map;
import java.util.HashMap;

/**
 * Инкапсулирует всю торговую логику для одного инструмента.
 * Отвечает за свою серию баров, стратегии, управление рисками и исполнение сделок.
 */
public class InstrumentProcessor {
    private static final Logger logger = LoggerFactory.getLogger(InstrumentProcessor.class);
    private static final long SIGNAL_COOLDOWN_SECONDS = 60;

    private final TradableInstrument instrument;
    private final TinkoffApiConnector apiConnector;
    private final SignalTracker signalTracker;
    private final BarSeries barSeries;
    private final MultiStrategyManager strategyManager;
    private final RiskManager riskManager;
    private final ExecutorService backgroundExecutor;

    // UI-зависимые компоненты, передаются извне
    private final Consumer<String> loggerCallback;
    private final ObservableList<TradingSignal> signalsList;

    private MarketDataSubscriptionService marketDataSubscription;
    private ScheduledExecutorService strategyExecutor;
    private Instant lastSignalTimestamp;

    public InstrumentProcessor(TradableInstrument instrument,
                               TinkoffApiConnector apiConnector,
                               SignalTracker signalTracker,
                               ExecutorService backgroundExecutor,
                               Consumer<String> loggerCallback,
                               ObservableList<TradingSignal> signalsList,
                               List<String> enabledStrategies) {
        this.instrument = instrument;
        this.apiConnector = apiConnector;
        this.signalTracker = signalTracker;
        this.backgroundExecutor = backgroundExecutor;
        this.loggerCallback = loggerCallback;
        this.signalsList = signalsList;

        this.barSeries = new BaseBarSeriesBuilder().withName(instrument.identifier()).build();
        this.strategyManager = new MultiStrategyManager(barSeries, enabledStrategies);
        this.riskManager = new RiskManager(
                new BigDecimal("1.0"), // 1% риска на сделку
                new BigDecimal("1000"), // Минимальный размер позиции 1000 RUB
                new BigDecimal("20.0"), // Максимальный % капитала на позицию 20%
                new BigDecimal("0.5"), // Расстояние стоп-лосса 0.5%
                new BigDecimal("0.001"), // Минимальное расстояние стоп-лосса (например, 0.1%)
                apiConnector // Передаем TinkoffApiConnector
        );
    }

    /**
     * Запускает процесс торговли для инструмента: загружает историю и подписывается на данные.
     * @param interval Интервал свечей.
     */
    public void start(CandleInterval interval, Duration barDuration) {
        log(String.format("🚀 [%s] Запуск процесса...", instrument.name()));
        this.strategyExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Processor-" + instrument.name());
            t.setDaemon(true);
            return t;
        });

        backgroundExecutor.submit(() -> {
            try {
                int requiredBars = strategyManager.getUnstablePeriod();
                log(String.format("⏳ [%s] Требуется %d баров для прогрева. Загрузка истории...", instrument.name(), requiredBars));

                List<HistoricCandle> historicCandles = apiConnector.getHistoricCandles(instrument.identifier(), 7, interval);
                historicCandles.forEach(candle -> addCandleToSeries(candle, barDuration));
                log(String.format("✅ [%s] История загружена. Баров в серии: %d.", instrument.name(), barSeries.getBarCount()));

                subscribeToMarketData(interval, barDuration);
                startStrategyAnalysisScheduler();
            } catch (Exception e) {
                handleCriticalError("Ошибка при загрузке исторических данных", e);
            }
        });
    }

    /**
     * Полностью останавливает всю активность по инструменту.
     */
    public void stop() {
        if (strategyExecutor != null) {
            strategyExecutor.shutdownNow();
        }
        if (marketDataSubscription != null) {
            marketDataSubscription.cancel();
        }
        log(String.format("🛑 [%s] Процесс остановлен.", instrument.name()));
    }

    private void subscribeToMarketData(CandleInterval interval, Duration barDuration) {
        this.marketDataSubscription = apiConnector.subscribeToMarketData(
                Collections.singletonList(instrument.identifier()),
                interval,
                candle -> processCandleEvent(candle, barDuration),
                null,
                0
        );
        log(String.format("📊 [%s] Подписка на рыночные данные активна.", instrument.name()));
    }

    private void startStrategyAnalysisScheduler() {
        strategyExecutor.scheduleAtFixedRate(this::runStrategyAnalysis, 15, 10, TimeUnit.SECONDS);
        log(String.format("🧠 [%s] Анализатор стратегий запущен.", instrument.name()));
    }

    private void processCandleEvent(Candle candle, Duration barDuration) {
        if (candle == null) return;
        try {
            ZonedDateTime zdt = ZonedDateTime.ofInstant(TinkoffApiConnector.timestampToInstant(candle.getTime()), ZoneId.systemDefault());
            BigDecimal close = TinkoffApiConnector.quotationToBigDecimal(candle.getClose());

            BaseBar bar = new BaseBar(barDuration, zdt.toInstant(),
                    DecimalNum.valueOf(TinkoffApiConnector.quotationToBigDecimal(candle.getOpen()).doubleValue()),
                    DecimalNum.valueOf(TinkoffApiConnector.quotationToBigDecimal(candle.getHigh()).doubleValue()),
                    DecimalNum.valueOf(TinkoffApiConnector.quotationToBigDecimal(candle.getLow()).doubleValue()),
                    DecimalNum.valueOf(close.doubleValue()),
                    DecimalNum.valueOf(BigDecimal.valueOf(candle.getVolume()).doubleValue()),
                    DecimalNum.valueOf(BigDecimal.valueOf(candle.getVolume()).doubleValue()),
                    0L);

            synchronized (barSeries) {
                if (barSeries.isEmpty() || zdt.toInstant().isAfter(barSeries.getLastBar().getEndTime())) {
                    barSeries.addBar(bar);
                } else if (zdt.toInstant().equals(barSeries.getLastBar().getEndTime())) {
                    barSeries.addBar(bar, true);
                }
            }

            signalTracker.updatePrice(instrument.identifier(), close);
            log(String.format("📈 [%s] Свеча: %s | Баров: %d", instrument.name(), close, barSeries.getBarCount()));
        } catch (Exception e) {
            logger.error(String.format("❌ [%s] Ошибка обработки свечи", instrument.name()), e);
        }
    }

    private void runStrategyAnalysis() {
        if (barSeries.getBarCount() < strategyManager.getUnstablePeriod()) {
            logger.debug("[{}] Недостаточно баров для анализа: {}/{}. Ожидание...",
                    instrument.name(), barSeries.getBarCount(), strategyManager.getUnstablePeriod());
            return;
        }

        if (lastSignalTimestamp != null && Duration.between(lastSignalTimestamp, Instant.now()).getSeconds() < SIGNAL_COOLDOWN_SECONDS) {
            logger.debug("Фильтр кулдауна для [{}]: сигналы игнорируются.", instrument.name());
            return;
        }

        List<TradingSignal> signals = strategyManager.analyzeAll(instrument); // Передаем инструмент для обогащения сигнала
        if (signals.isEmpty()) {
            return;
        }

        ru.tinkoff.piapi.core.models.Portfolio currentPortfolio = apiConnector.getPortfolio().join();

        // Получаем текущую позицию для данного инструмента
        ru.tinkoff.piapi.core.models.Position currentPosition = currentPortfolio.getPositions().stream()
                .filter(p -> p.getFigi().equals(instrument.identifier()))
                .findFirst()
                .orElse(null);

        // Собираем доступные балансы по валютам
        Map<String, BigDecimal> availableBalances = new HashMap<>();
        if (currentPortfolio.getTotalAmountCurrencies() != null) {
            availableBalances.put(instrument.currency(), TinkoffApiConnector.moneyToBigDecimal(currentPortfolio.getTotalAmountCurrencies()));
        }

        for (TradingSignal signal : signals) {
            // Проверка на "добор" позиции: если есть открытая позиция в том же направлении, игнорируем сигнал
            if (currentPosition != null && currentPosition.getQuantity().compareTo(BigDecimal.ZERO) != 0) {
                boolean isLongPosition = currentPosition.getQuantity().compareTo(BigDecimal.ZERO) > 0;
                boolean isBuySignal = signal.getSignalType() == TradingSignal.SignalType.BUY;

                if ((isLongPosition && isBuySignal) || (!isLongPosition && !isBuySignal)) {
                    logger.info("[{}] Сигнал {} проигнорирован: уже есть открытая позиция в том же направлении.",
                            instrument.name(), signal.getSignalType());
                    continue; // Пропускаем текущий сигнал
                }
            }

            RiskManager.ValidationResult validation = riskManager.validateSignal(
                    instrument, signal, currentPortfolio, availableBalances, currentPosition);
            if (validation.isValid()) {
                this.lastSignalTimestamp = Instant.now();
                signalTracker.trackSignal(signal);

                log("🎯 [" + instrument.name() + "] Сигнал: " + signal.getDescription());
                log("✅ [" + instrument.name() + "] " + validation.getMessage());

                signalsList.add(0, signal);
                executeTrade(signal, validation.getLots());
                break;
            } else {
                logger.warn("[{}] Сигнал отклонен риск-менеджером: {}", instrument.name(), validation.getMessage());
            }
        }
    }

    private void executeTrade(TradingSignal signal, BigDecimal quantity) {
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            log(String.format("❌ [%s] Объем для сделки равен нулю. Сделка отменена.", instrument.name()));
            return;
        }

        backgroundExecutor.submit(() -> {
            try {
                OrderDirection direction = signal.getSignalType() == TradingSignal.SignalType.BUY
                        ? OrderDirection.ORDER_DIRECTION_BUY
                        : OrderDirection.ORDER_DIRECTION_SELL;

                log(String.format("🚀 [%s] Отправка рыночного приказа: %s, %d лот(а)...", instrument.name(), direction, quantity.longValue()));
                PostOrderResponse response = apiConnector.postMarketOrder(instrument.identifier(), quantity.longValue(), direction);
                log(String.format("✅ [%s] Приказ отправлен! OrderID: %s", instrument.name(), response.getOrderId()));

                if (response.getExecutionReportStatus() == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL) {
                    if (signal.getStopLoss() != null && signal.getStopLoss().compareTo(BigDecimal.ZERO) > 0) {
                        placeStopOrder(signal, quantity);
                    }
                } else {
                    log(String.format("⚠️ [%s] Рыночный ордер не исполнился немедленно. Stop-Loss не выставлен.", instrument.name()));
                }
            } catch (Exception e) {
                handleCriticalError("Ошибка исполнения приказа", e);
            }
        });
    }

    private void placeStopOrder(TradingSignal signal, BigDecimal quantity) {
        try {
            StopOrderDirection stopDirection = (signal.getSignalType() == TradingSignal.SignalType.SELL)
                    ? StopOrderDirection.STOP_ORDER_DIRECTION_BUY
                    : StopOrderDirection.STOP_ORDER_DIRECTION_SELL;

            log(String.format("🛡️ [%s] Выставление Stop-Loss: %s %d лотов по цене %s",
                    instrument.name(), stopDirection, quantity.longValue(), signal.getStopLoss().toPlainString()));

            String stopOrderId = apiConnector.postStopOrder(
                    instrument.identifier(),
                    quantity.longValue(),
                    TinkoffApiConnector.bigDecimalToQuotation(signal.getStopLoss()),
                    stopDirection,
                    StopOrderType.STOP_ORDER_TYPE_STOP_LOSS
            );
            log(String.format("✅ [%s] Stop-Loss выставлен. OrderID: %s", instrument.name(), stopOrderId));
        } catch (Exception e) {
            handleCriticalError("Не удалось выставить Stop-Loss ордер", e);
        }
    }

    private void addCandleToSeries(HistoricCandle candle, Duration barDuration) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(TinkoffApiConnector.timestampToInstant(candle.getTime()), ZoneId.systemDefault());
        synchronized (barSeries) {
            BaseBar bar = new BaseBar(barDuration, zdt.toInstant(),
                    DecimalNum.valueOf(TinkoffApiConnector.quotationToBigDecimal(candle.getOpen()).doubleValue()),
                    DecimalNum.valueOf(TinkoffApiConnector.quotationToBigDecimal(candle.getHigh()).doubleValue()),
                    DecimalNum.valueOf(TinkoffApiConnector.quotationToBigDecimal(candle.getLow()).doubleValue()),
                    DecimalNum.valueOf(TinkoffApiConnector.quotationToBigDecimal(candle.getClose()).doubleValue()),
                    DecimalNum.valueOf(BigDecimal.valueOf(candle.getVolume()).doubleValue()),
                    DecimalNum.valueOf(BigDecimal.valueOf(candle.getVolume()).doubleValue()), 0L);

            if (barSeries.isEmpty() || zdt.toInstant().isAfter(barSeries.getLastBar().getEndTime())) {
                barSeries.addBar(bar);
            } else if (zdt.toInstant().equals(barSeries.getLastBar().getEndTime())) {
                barSeries.addBar(bar, true);
            }
        }
    }

    private void log(String message) {
        // Вызываем колбэк для логирования в UI из основного потока JavaFX
        javafx.application.Platform.runLater(() -> loggerCallback.accept(message));
        logger.info(message); // Также логируем в файл/консоль
    }

    private void handleCriticalError(String message, Throwable e) {
        String errorMessage = String.format("💥 [%s] Ошибка: %s. См. логи.", instrument.name(), message);
        logger.error(errorMessage, e);
        javafx.application.Platform.runLater(() -> loggerCallback.accept(errorMessage));
    }
}

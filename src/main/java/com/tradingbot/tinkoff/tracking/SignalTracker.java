package com.tradingbot.tinkoff.tracking;

import com.tradingbot.tinkoff.model.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Система отслеживания результатов торговых сигналов
 */
public class SignalTracker {
    private static final Logger logger = LoggerFactory.getLogger(SignalTracker.class);

    // Активные сигналы (ожидающие результата)
    private final ConcurrentHashMap<Integer, TrackedSignal> activeSignals = new ConcurrentHashMap<>();

    // Статистика
    private final AtomicInteger totalSignals = new AtomicInteger(0);
    private final AtomicInteger successfulSignals = new AtomicInteger(0);
    private final AtomicInteger failedSignals = new AtomicInteger(0);
    private final AtomicInteger expiredSignals = new AtomicInteger(0);

    public void trackSignal(TradingSignal signal) {
        TrackedSignal trackedSignal = new TrackedSignal(signal, ZonedDateTime.now());
        activeSignals.put(signal.getSignalId(), trackedSignal);
        totalSignals.incrementAndGet();

        logger.info("🎯 Начато отслеживание сигнала #{}: {} по цене {}",
                signal.getSignalId(), signal.getSignalType(), signal.getEntryPrice());
    }

    /**
     * Обновление текущей цены для проверки сигналов
     */
    public void updatePrice(String instrumentIdentifier, BigDecimal currentPrice) {
        // Проходимся только по сигналам, связанным с данным инструментом
        activeSignals.values().stream()
                .filter(trackedSignal -> trackedSignal.getSignal().getInstrument().identifier().equals(instrumentIdentifier))
                .forEach(trackedSignal -> {
                    SignalResult result = checkSignalResult(trackedSignal, currentPrice);

                    if (result != SignalResult.ACTIVE) {
                        completeSignal(trackedSignal, result, currentPrice);
                    }
                });
    }

    private SignalResult checkSignalResult(TrackedSignal tracked, BigDecimal currentPrice) {
        TradingSignal signal = tracked.getSignal();

        // Проверка истечения времени (24 часа)
        if (ZonedDateTime.now().isAfter(tracked.getStartTime().plusHours(24))) {
            return SignalResult.EXPIRED;
        }

        // Проверка достижения Take Profit
        if (signal.getSignalType() == TradingSignal.SignalType.BUY) {
            if (currentPrice.compareTo(signal.getTakeProfit()) >= 0) {
                return SignalResult.SUCCESS;
            }
            if (currentPrice.compareTo(signal.getStopLoss()) <= 0) {
                return SignalResult.FAILED;
            }
        } else if (signal.getSignalType() == TradingSignal.SignalType.SELL) {
            if (currentPrice.compareTo(signal.getTakeProfit()) <= 0) {
                return SignalResult.SUCCESS;
            }
            if (currentPrice.compareTo(signal.getStopLoss()) >= 0) {
                return SignalResult.FAILED;
            }
        }

        return SignalResult.ACTIVE;
    }

    private void completeSignal(TrackedSignal tracked, SignalResult result, BigDecimal finalPrice) {
        activeSignals.remove(tracked.getSignal().getSignalId());

        long durationMinutes = tracked.getStartTime().until(ZonedDateTime.now(), ChronoUnit.MINUTES);
        BigDecimal profit = calculateProfit(tracked.getSignal(), finalPrice);

        switch (result) {
            case SUCCESS:
                successfulSignals.incrementAndGet();
                logger.info("✅ Сигнал #{} УСПЕШЕН! Прибыль: {}. Время: {} мин",
                        tracked.getSignal().getSignalId(), profit, durationMinutes);
                break;
            case FAILED:
                failedSignals.incrementAndGet();
                logger.info("❌ Сигнал #{} ПРОВАЛЕН! Убыток: {}. Время: {} мин",
                        tracked.getSignal().getSignalId(), profit, durationMinutes);
                break;
            case EXPIRED:
                expiredSignals.incrementAndGet();
                logger.info("⏰ Сигнал #{} ИСТЕК! Результат: {}. Время: {} мин",
                        tracked.getSignal().getSignalId(), profit, durationMinutes);
                break;
            default:
                // ACTIVE сигналы не приводят к завершению, поэтому не обрабатываем их здесь
                break;
        }
    }

    private BigDecimal calculateProfit(TradingSignal signal, BigDecimal finalPrice) {
        BigDecimal entryPrice = signal.getEntryPrice();
        BigDecimal difference = finalPrice.subtract(entryPrice);

        if (signal.getSignalType() == TradingSignal.SignalType.SELL) {
            difference = difference.negate();
        }

        return difference.divide(entryPrice, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    }

    public void printStatistics() {
        int total = totalSignals.get();
        int success = successfulSignals.get();
        int failed = failedSignals.get();
        int expired = expiredSignals.get();
        int active = activeSignals.size();

        double successRate = total > 0 ? (success * 100.0) / total : 0;

        logger.info("=== 📊 СТАТИСТИКА СИГНАЛОВ ===");
        logger.info("Всего сигналов: {}", total);
        logger.info("Успешных: {} ({:.1f}%)", success, successRate);
        logger.info("Провальных: {}", failed);
        logger.info("Истекших: {}", expired);
        logger.info("Активных: {}", active);
        logger.info("============================");
    }

    // Вложенные классы
    public static class TrackedSignal {
        private final TradingSignal signal;
        private final ZonedDateTime startTime;
        private final BigDecimal stopLossPrice;
        private final BigDecimal takeProfitPrice;

        public TrackedSignal(TradingSignal signal, ZonedDateTime startTime) {
            this.signal = signal;
            this.startTime = startTime;
            this.stopLossPrice = signal.getStopLoss();
            this.takeProfitPrice = signal.getTakeProfit();
        }

        public TradingSignal getSignal() { return signal; }
        public ZonedDateTime getStartTime() { return startTime; }

        public BigDecimal getStopLossPrice() { return stopLossPrice; }
        public BigDecimal getTakeProfitPrice() { return takeProfitPrice; }
    }

    public TrackedSignal getTrackedSignal(String figi) {
        // Находим активный сигнал по FIGI инструмента
        return activeSignals.values().stream()
                .filter(ts -> ts.getSignal().getInstrument().identifier().equals(figi))
                .findFirst()
                .orElse(null);
    }

    public enum SignalResult {
        ACTIVE, SUCCESS, FAILED, EXPIRED
    }
}

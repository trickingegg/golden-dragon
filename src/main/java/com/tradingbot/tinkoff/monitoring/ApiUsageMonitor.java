package com.tradingbot.tinkoff.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Мониторинг использования Tinkoff API
 */
public class ApiUsageMonitor {
    private static final Logger logger = LoggerFactory.getLogger(ApiUsageMonitor.class);

    // Счетчики запросов
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong connectRequests = new AtomicLong(0);
    private final AtomicLong portfolioRequests = new AtomicLong(0);
    private final AtomicLong marketDataRequests = new AtomicLong(0);
    private final AtomicLong instrumentRequests = new AtomicLong(0);

    // Время последнего сброса счетчиков
    private final AtomicReference<LocalDateTime> lastReset = new AtomicReference<>(LocalDateTime.now());

    public void recordConnect() {
        connectRequests.incrementAndGet();
        totalRequests.incrementAndGet();
        logger.debug("API запрос: Connect. Всего: {}", totalRequests.get());
    }

    public void recordPortfolio() {
        portfolioRequests.incrementAndGet();
        totalRequests.incrementAndGet();
        logger.debug("API запрос: Portfolio. Всего: {}", totalRequests.get());
    }

    public void recordMarketData() {
        marketDataRequests.incrementAndGet();
        totalRequests.incrementAndGet();
        logger.debug("API запрос: MarketData. Всего: {}", totalRequests.get());
    }

    public void recordInstrument() {
        instrumentRequests.incrementAndGet();
        totalRequests.incrementAndGet();
        logger.debug("API запрос: Instrument. Всего: {}", totalRequests.get());
    }

    public void printUsageStatistics() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime resetTime = lastReset.get();
        long hoursElapsed = java.time.Duration.between(resetTime, now).toHours();

        logger.info("=== 📡 СТАТИСТИКА API ЗАПРОСОВ ===");
        logger.info("Всего запросов: {}", totalRequests.get());
        logger.info("Connect: {}", connectRequests.get());
        logger.info("Portfolio: {}", portfolioRequests.get());
        logger.info("MarketData: {}", marketDataRequests.get());
        logger.info("Instrument: {}", instrumentRequests.get());
        logger.info("Время работы: {} часов", hoursElapsed);

        if (hoursElapsed > 0) {
            logger.info("Запросов в час: {}", totalRequests.get() / hoursElapsed);
        }

        // Предупреждение о лимитах
        long requestsPerHour = hoursElapsed > 0 ? totalRequests.get() / hoursElapsed : totalRequests.get();
        if (requestsPerHour > 100) {
            logger.warn("⚠️ ВНИМАНИЕ: Высокая частота запросов ({}/час). Рекомендуется не более 100/час", requestsPerHour);
        }

        logger.info("================================");
    }

    public void reset() {
        totalRequests.set(0);
        connectRequests.set(0);
        portfolioRequests.set(0);
        marketDataRequests.set(0);
        instrumentRequests.set(0);
        lastReset.set(LocalDateTime.now());
        logger.info("🔄 Счетчики API запросов сброшены");
    }
}

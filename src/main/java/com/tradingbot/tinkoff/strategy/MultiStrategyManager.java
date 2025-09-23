package com.tradingbot.tinkoff.strategy;

import com.tradingbot.tinkoff.model.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import com.tradingbot.tinkoff.model.TradableInstrument;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Менеджер множественных стратегий для A/B тестирования
 */
public class MultiStrategyManager {
    private static final Logger logger = LoggerFactory.getLogger(MultiStrategyManager.class);

    private final Map<String, Object> strategies = new HashMap<>();
    private final Map<String, Integer> signalCounts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> strategyEnabled = new ConcurrentHashMap<>();

    public MultiStrategyManager(BarSeries barSeries) {
        // Инициализируем все стратегии
        strategies.put("SCALPING", new ScalpingMomentumStrategy(barSeries));
        strategies.put("MEAN_REVERSION_CONSERVATIVE", new MeanReversionStrategy(barSeries, StrategyConfig.getConservativeConfig()));
        strategies.put("MEAN_REVERSION_AGGRESSIVE", new MeanReversionStrategy(barSeries, StrategyConfig.getAggressiveConfig()));
        strategies.put("ADAPTIVE_TREND", new AdaptiveTrendStrategy(barSeries));

        // Включаем все по умолчанию
        strategies.keySet().forEach(name -> strategyEnabled.put(name, true));

        logger.info("🎯 MultiStrategyManager инициализирован с {} стратегиями", strategies.size());
    }

    public MultiStrategyManager(BarSeries barSeries, List<String> enabledStrategies) {
        // Инициализируем все стратегии (как в основном конструкторе)
        strategies.put("SCALPING", new ScalpingMomentumStrategy(barSeries));
        strategies.put("MEAN_REVERSION_CONSERVATIVE", new MeanReversionStrategy(barSeries, StrategyConfig.getConservativeConfig()));
        strategies.put("MEAN_REVERSION_AGGRESSIVE", new MeanReversionStrategy(barSeries, StrategyConfig.getAggressiveConfig()));
        strategies.put("ADAPTIVE_TREND", new AdaptiveTrendStrategy(barSeries));

        // Включаем только те стратегии, которые есть в списке enabledStrategies
        strategies.keySet().forEach(name -> strategyEnabled.put(name, enabledStrategies.contains(name)));

        logger.info("🎯 MultiStrategyManager инициализирован с {} стратегиями. Включено: {}", strategies.size(), enabledStrategies);
    }

    public Set<String> getStrategyNames() {
        return strategies.keySet();
    }

    public void enableStrategy(String name, boolean enabled) {
        strategyEnabled.put(name, enabled);
        logger.info("Стратегия {} {}", name, enabled ? "включена" : "отключена");
    }

    public List<String> getEnabledStrategyNames() {
        return strategyEnabled.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    // Файл: com/tradingbot/tinkoff/strategy/MultiStrategyManager.java

    public List<TradingSignal> analyzeAll(TradableInstrument instrument) {
        List<TradingSignal> finalSignals = new ArrayList<>();
        Map<String, TradingSignal> signalsByName = new HashMap<>();

        // 1. Собрать сигналы от всех активных стратегий
        strategies.forEach((name, strategy) -> {
            if (strategyEnabled.get(name)) {
                try {
                    TradingSignal signal = null;
                    // Определяем, какой метод analyze вызывать
                    if (strategy instanceof ScalpingMomentumStrategy) {
                        signal = ((ScalpingMomentumStrategy) strategy).analyzeSignal(instrument);
                    } else if (strategy instanceof MeanReversionStrategy) {
                        signal = ((MeanReversionStrategy) strategy).analyzeSignal(instrument);
                    } else if (strategy instanceof AdaptiveTrendStrategy) {
                        signal = ((AdaptiveTrendStrategy) strategy).analyzeSignal(instrument);
                    }

                    if (signal != null && signal.getSignalType() != TradingSignal.SignalType.HOLD) {
                        signal.setDescription(String.format("[%s] %s", name, signal.getDescription()));
                        signalsByName.put(name, signal);
                        // Статистику будем считать только для итоговых сигналов
                    } else {
                        logger.debug("🔹 Стратегия '{}' не нашла сигнала (HOLD) на текущем баре.", name);
                    }
                } catch (Exception e) {
                    logger.error("Ошибка в стратегии {}: {}", name, e.getMessage(), e);
                }
            }
        });

        // 2. Применить ансамблевую логику (фильтрация)
        TradingSignal meanReversionSignal = signalsByName.get("MEAN_REVERSION_AGGRESSIVE");
        TradingSignal adaptiveTrendSignal = signalsByName.get("ADAPTIVE_TREND");

        // ПРАВИЛО: Не шортить по "Mean Reversion", если глобальный тренд бычий
        // (Предполагается, что у сигнала от AdaptiveTrendStrategy есть метод getTrend())
        if (meanReversionSignal != null && meanReversionSignal.getSignalType() == TradingSignal.SignalType.SELL &&
                adaptiveTrendSignal != null && adaptiveTrendSignal.getTrend() == TradingSignal.Trend.BULL) {

            logger.warn("Фильтр ансамбля: Сигнал SELL от MEAN_REVERSION_AGGRESSIVE отклонен, т.к. ADAPTIVE_TREND показывает BULL.");
            signalsByName.remove("MEAN_REVERSION_AGGRESSIVE"); // Удаляем противоречащий сигнал
        }

        // ПРАВИЛО: Не покупать по "Mean Reversion", если глобальный тренд медвежий
        if (meanReversionSignal != null && meanReversionSignal.getSignalType() == TradingSignal.SignalType.BUY &&
                adaptiveTrendSignal != null && adaptiveTrendSignal.getTrend() == TradingSignal.Trend.BEAR) {

            logger.warn("Фильтр ансамбля: Сигнал BUY от MEAN_REVERSION_AGGRESSIVE отклонен, т.к. ADAPTIVE_TREND показывает BEAR.");
            signalsByName.remove("MEAN_REVERSION_AGGRESSIVE");
        }

        // Добавляем в итоговый список только те сигналы, что прошли фильтрацию
        if (!signalsByName.isEmpty()) {
            finalSignals.addAll(signalsByName.values());
            // Обновляем статистику только для прошедших фильтр сигналов
            signalsByName.keySet().forEach(name -> signalCounts.merge(name, 1, Integer::sum));
            finalSignals.forEach(s -> logger.info("✅ Итоговый сигнал после ансамбля: {} от {}", s.getSignalType(), s.getDescription()));
        }

        return finalSignals;
    }


    public int getUnstablePeriod() {
        return strategies.values().stream()
                .mapToInt(strategy -> {
                    if (strategy instanceof ScalpingMomentumStrategy) return ((ScalpingMomentumStrategy) strategy).getUnstablePeriod();
                    if (strategy instanceof MeanReversionStrategy) return ((MeanReversionStrategy) strategy).getUnstablePeriod();
                    if (strategy instanceof AdaptiveTrendStrategy) return ((AdaptiveTrendStrategy) strategy).getUnstablePeriod();
                    return 0;
                })
                .max()
                .orElse(25);
    }

    public void printStatistics() {
        logger.info("=== СТАТИСТИКА СТРАТЕГИЙ ===");
        signalCounts.forEach((name, count) ->
                logger.info("{}: {} сигналов", name, count));
    }
}
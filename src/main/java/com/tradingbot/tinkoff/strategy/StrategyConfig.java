// Создайте новый файл: src/main/java/com/tradingbot/tinkoff/strategy/StrategyConfig.java
package com.tradingbot.tinkoff.strategy;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StrategyConfig {
    // Параметры для MeanReversion
    private int bbPeriod;
    private double bbMultiplier;
    private int rsiPeriod;
    private double rsiLowerThreshold;
    private double rsiUpperThreshold;

    // Можно добавить параметры и для других стратегий...

    public static StrategyConfig getConservativeConfig() {
        return StrategyConfig.builder()
                .bbPeriod(20)
                .bbMultiplier(2.0)
                .rsiPeriod(14)
                .rsiLowerThreshold(30)
                .rsiUpperThreshold(70)
                .build();
    }

    public static StrategyConfig getAggressiveConfig() {
        return StrategyConfig.builder()
                .bbPeriod(15)       // Более короткий период
                .bbMultiplier(1.8)  // Более узкие полосы
                .rsiPeriod(10)      // Более чувствительный RSI
                .rsiLowerThreshold(35) // Менее строгий порог
                .rsiUpperThreshold(65) // Менее строгий порог
                .build();
    }
}

package com.tradingbot.tinkoff.strategy;

import com.tradingbot.tinkoff.model.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.num.DecimalNum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Адаптивная трендовая стратегия, которая подстраивается под волатильность
 */
public class AdaptiveTrendStrategy {
    private static final Logger logger = LoggerFactory.getLogger(AdaptiveTrendStrategy.class);

    private static final int FAST_EMA = 8;
    private static final int SLOW_EMA = 21;
    private static final int ATR_PERIOD = 14;
    private static final int ADX_PERIOD = 14;

    private final BarSeries barSeries;
    private final ClosePriceIndicator closePrice;
    private final EMAIndicator fastEma;
    private final EMAIndicator slowEma;
    private final ATRIndicator atr;
    private final ADXIndicator adx; // Для определения силы тренда
    private final AtomicInteger signalCounter = new AtomicInteger(0);

    public AdaptiveTrendStrategy(BarSeries barSeries) {
        this.barSeries = barSeries;
        this.closePrice = new ClosePriceIndicator(barSeries);
        this.fastEma = new EMAIndicator(closePrice, FAST_EMA);
        this.slowEma = new EMAIndicator(closePrice, SLOW_EMA);
        this.atr = new ATRIndicator(barSeries, ATR_PERIOD);
        this.adx = new ADXIndicator(barSeries, ADX_PERIOD);

        logger.info("🚀 AdaptiveTrendStrategy инициализирована");
    }

    public int getUnstablePeriod() { return Math.max(SLOW_EMA, ADX_PERIOD) + 2; }

    public TradingSignal analyzeSignal(com.tradingbot.tinkoff.model.TradableInstrument instrument) {
        int lastIndex = barSeries.getEndIndex();
        if (lastIndex < getUnstablePeriod()) {
            return new TradingSignal(TradingSignal.SignalType.HOLD, 0, "Накопление данных");
        }

        Num fastEmaValue = fastEma.getValue(lastIndex);
        Num slowEmaValue = slowEma.getValue(lastIndex);
        Num prevFastEma = fastEma.getValue(lastIndex - 1);
        Num prevSlowEma = slowEma.getValue(lastIndex - 1);
        Num adxValue = adx.getValue(lastIndex);

        // Проверяем силу тренда (ADX > 25 означает сильный тренд)
        boolean strongTrend = adxValue.doubleValue() > 25;

        // Пересечение EMA + сильный тренд
        boolean bullishCrossover = prevFastEma.isLessThanOrEqual(prevSlowEma) &&
                fastEmaValue.isGreaterThan(slowEmaValue) && strongTrend;

        boolean bearishCrossover = prevFastEma.isGreaterThanOrEqual(prevSlowEma) &&
                fastEmaValue.isLessThan(slowEmaValue) && strongTrend;

        TradingSignal.Trend currentTrend = TradingSignal.Trend.SIDEWAYS;
        if (bullishCrossover) {
            currentTrend = TradingSignal.Trend.BULL;
        } else if (bearishCrossover) {
            currentTrend = TradingSignal.Trend.BEAR;
        }

        if (bullishCrossover) {
            return createAdaptiveSignal(TradingSignal.SignalType.BUY, "Бычье пересечение EMA + сильный тренд", lastIndex, currentTrend, instrument);
        }

        if (bearishCrossover) {
            return createAdaptiveSignal(TradingSignal.SignalType.SELL, "Медвежье пересечение EMA + сильный тренд", lastIndex, currentTrend, instrument);
        }

        TradingSignal holdSignal = new TradingSignal(TradingSignal.SignalType.HOLD, 0, "Нет пересечения или слабый тренд");
        holdSignal.setTrend(currentTrend);
        return holdSignal;
    }

    private TradingSignal createAdaptiveSignal(TradingSignal.SignalType type, String reason, int index, TradingSignal.Trend trend, com.tradingbot.tinkoff.model.TradableInstrument instrument) {
        Num currentPrice = closePrice.getValue(index);
        Num atrValue = atr.getValue(index);

        BigDecimal entryPrice = ((DecimalNum) currentPrice).getDelegate();
        BigDecimal atrDecimal = ((DecimalNum) atrValue).getDelegate();

        // Устанавливаем минимально допустимый ATR как 0.1% от цены, чтобы избежать нулевых стопов
        BigDecimal minAtr = entryPrice.multiply(new BigDecimal("0.001"));
        if (atrDecimal.compareTo(minAtr) < 0) {
            logger.warn("ATR ({}) слишком низкий, используем минимальный порог: {}", atrDecimal, minAtr);
            atrDecimal = minAtr;
        }

        // Адаптивные уровни на основе ATR
        BigDecimal stopMultiplier = new BigDecimal("1.5");
        BigDecimal profitMultiplier = new BigDecimal("3.0"); // R:R = 1:2

        BigDecimal stopLoss, takeProfit;
        if (type == TradingSignal.SignalType.BUY) {
            stopLoss = entryPrice.subtract(atrDecimal.multiply(stopMultiplier)).setScale(4, RoundingMode.HALF_UP);
            takeProfit = entryPrice.add(atrDecimal.multiply(profitMultiplier)).setScale(4, RoundingMode.HALF_UP);
        } else {
            stopLoss = entryPrice.add(atrDecimal.multiply(stopMultiplier)).setScale(4, RoundingMode.HALF_UP);
            takeProfit = entryPrice.subtract(atrDecimal.multiply(profitMultiplier)).setScale(4, RoundingMode.HALF_UP);
        }

        TradingSignal signal = new TradingSignal(type, 90, reason);
        signal.setInstrument(instrument);
        signal.setEntryPrice(entryPrice);
        signal.setStopLoss(stopLoss);
        signal.setTakeProfit(takeProfit);
        signal.setTimestamp(ZonedDateTime.now());
        signal.setSignalId(signalCounter.incrementAndGet());
        signal.setTrend(trend); // Устанавливаем тренд

        logger.info("🎯 Adaptive Trend: {} по {} (ATR-based levels)", type, entryPrice);
        logger.info(
                "Создан сигнал Adaptive Trend: [Type: {}, Reason: {}, Index: {}] -> " +
                        "Entry: {}, ATR: {}, Stop: {}, TakeProfit: {}",
                type, reason, index,
                entryPrice.toPlainString(),
                atrDecimal.toPlainString(),
                stopLoss.toPlainString(),
                takeProfit.toPlainString()
        );
        return signal;
    }
}

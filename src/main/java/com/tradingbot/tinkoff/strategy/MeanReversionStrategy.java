package com.tradingbot.tinkoff.strategy;

import com.tradingbot.tinkoff.model.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.num.DecimalNum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Стратегия возврата к среднему с использованием Bollinger Bands
 * Торгует отскоки от границ полос
 */
public class MeanReversionStrategy {
    private static final Logger logger = LoggerFactory.getLogger(MeanReversionStrategy.class);

//    private static final int BB_PERIOD = 20;
//    private static final double BB_MULTIPLIER = 2.0;
//    private static final int RSI_PERIOD = 14;

    private final StrategyConfig config;

    private final BarSeries barSeries;
    private final ClosePriceIndicator closePrice;
    private final BollingerBandsMiddleIndicator bbMiddle;
    private final BollingerBandsUpperIndicator bbUpper;
    private final BollingerBandsLowerIndicator bbLower;
    private final RSIIndicator rsi;
    private final AtomicInteger signalCounter = new AtomicInteger(0);

    public MeanReversionStrategy(BarSeries barSeries, StrategyConfig config) {
        this.barSeries = barSeries;
        this.config = config; // <-- СОХРАНЯЕМ КОНФИГУРАЦИЮ

        this.closePrice = new ClosePriceIndicator(barSeries);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, config.getBbPeriod());
        EMAIndicator ema = new EMAIndicator(closePrice, config.getBbPeriod());

        this.bbMiddle = new BollingerBandsMiddleIndicator(ema);
        this.bbUpper = new BollingerBandsUpperIndicator(bbMiddle, sd, DecimalNum.valueOf(config.getBbMultiplier()));
        this.bbLower = new BollingerBandsLowerIndicator(bbMiddle, sd, DecimalNum.valueOf(config.getBbMultiplier()));
        this.rsi = new RSIIndicator(closePrice, config.getRsiPeriod());

        logger.info("MeanReversionStrategy инициализирована с периодом BB={} и RSI={}", config.getBbPeriod(), config.getRsiPeriod());
    }

    public int getUnstablePeriod() { return config.getBbPeriod() + 2; }

    public TradingSignal analyzeSignal(com.tradingbot.tinkoff.model.TradableInstrument instrument) {
        int lastIndex = barSeries.getEndIndex();
        if (lastIndex < getUnstablePeriod()) {
            return new TradingSignal(TradingSignal.SignalType.HOLD, 0, "Накопление данных");
        }

        Num currentPrice = closePrice.getValue(lastIndex);
        Num upperBand = bbUpper.getValue(lastIndex);
        Num lowerBand = bbLower.getValue(lastIndex);
        Num middleBand = bbMiddle.getValue(lastIndex);
        Num rsiValue = rsi.getValue(lastIndex);

        // Покупка у нижней границы (перепроданность)
        if (currentPrice.isLessThan(lowerBand) && rsiValue.doubleValue() < 30) {
            return createMeanReversionSignal(TradingSignal.SignalType.BUY, "Отскок от нижней BB",
                    currentPrice, middleBand, lastIndex, instrument);
        }

        // Продажа у верхней границы (перекупленность)
        if (currentPrice.isGreaterThan(upperBand) && rsiValue.doubleValue() > 70) {
            return createMeanReversionSignal(TradingSignal.SignalType.SELL, "Отскок от верхней BB",
                    currentPrice, middleBand, lastIndex, instrument);
        }

        return new TradingSignal(TradingSignal.SignalType.HOLD, 0, "Цена в пределах BB");
    }

    private TradingSignal createMeanReversionSignal(TradingSignal.SignalType type, String reason,
                                                    Num currentPrice, Num targetPrice, int index, com.tradingbot.tinkoff.model.TradableInstrument instrument) {
        BigDecimal entryPrice = ((DecimalNum) currentPrice).getDelegate();
        BigDecimal target = ((DecimalNum) targetPrice).getDelegate();

        BigDecimal stopDistance = entryPrice.subtract(target).abs().multiply(new BigDecimal("0.5"));

        BigDecimal stopLoss, takeProfit;
        if (type == TradingSignal.SignalType.BUY) {
            stopLoss = entryPrice.subtract(stopDistance).setScale(4, RoundingMode.HALF_UP);
            takeProfit = target.setScale(4, RoundingMode.HALF_UP); // Цель - средняя линия
        } else {
            stopLoss = entryPrice.add(stopDistance).setScale(4, RoundingMode.HALF_UP);
            takeProfit = target.setScale(4, RoundingMode.HALF_UP);
        }

        TradingSignal signal = new TradingSignal(type, 85, reason);
        signal.setInstrument(instrument);
        signal.setEntryPrice(entryPrice);
        signal.setStopLoss(stopLoss);
        signal.setTakeProfit(takeProfit);
        signal.setTimestamp(ZonedDateTime.now());
        signal.setSignalId(signalCounter.incrementAndGet());

        logger.info("🎯 Mean Reversion: {} по {} -> TP: {}, SL: {}", type, entryPrice, takeProfit, stopLoss);
        return signal;
    }
}

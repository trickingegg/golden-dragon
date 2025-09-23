package com.tradingbot.tinkoff.strategy;

import com.tradingbot.tinkoff.model.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.num.DecimalNum;
import ru.tinkoff.piapi.contract.v1.OrderBook;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Агрессивная скальпинговая стратегия на основе RSI и объема
 * Цель: множественные мелкие прибыльные сделки
 */
public class ScalpingMomentumStrategy {
    private static final Logger logger = LoggerFactory.getLogger(ScalpingMomentumStrategy.class);

    // Параметры стратегии
    private static final int RSI_PERIOD = 7;  // Быстрый RSI
    private static final int VWAP_PERIOD = 14; // Короткий VWAP
    private static final double RSI_OVERSOLD = 25;
    private static final double RSI_OVERBOUGHT = 75;

    private final BarSeries barSeries;
    private final ClosePriceIndicator closePrice;
    private final RSIIndicator rsi;
    private final VWAPIndicator vwap;
    private final AtomicInteger signalCounter = new AtomicInteger(0);

    public ScalpingMomentumStrategy(BarSeries barSeries) {
        this.barSeries = barSeries;
        this.closePrice = new ClosePriceIndicator(barSeries);
        this.rsi = new RSIIndicator(closePrice, RSI_PERIOD);
        this.vwap = new VWAPIndicator(barSeries, VWAP_PERIOD);

        logger.info("🎯 ScalpingMomentumStrategy инициализирована (RSI={}, VWAP={})", RSI_PERIOD, VWAP_PERIOD);
    }

    public int getUnstablePeriod() { return Math.max(RSI_PERIOD, VWAP_PERIOD) + 1; }

    public void updateOrderBook(OrderBook orderBook) { }

    public TradingSignal analyzeSignal(com.tradingbot.tinkoff.model.TradableInstrument instrument) {
        int lastIndex = barSeries.getEndIndex();
        if (lastIndex < getUnstablePeriod()) {
            return new TradingSignal(TradingSignal.SignalType.HOLD, 0, "Накопление данных");
        }

        Num currentPrice = closePrice.getValue(lastIndex);
        Num vwapValue = vwap.getValue(lastIndex);
        Num rsiValue = rsi.getValue(lastIndex);

        // Определяем общее направление по VWAP
        boolean bullishTrend = currentPrice.isGreaterThan(vwapValue);

        logger.debug("📊 Скальпинг анализ: Price={}, VWAP={}, RSI={}, Trend={}",
                currentPrice, vwapValue, rsiValue, bullishTrend ? "BULL" : "BEAR");

        // СИГНАЛ НА ПОКУПКУ: Бычий тренд + RSI в зоне перепроданности
        if (bullishTrend && rsiValue.doubleValue() < RSI_OVERSOLD) {
            return createScalpingSignal(TradingSignal.SignalType.BUY, "RSI перепродан в бычьем тренде", lastIndex, instrument);
        }

        // СИГНАЛ НА ПРОДАЖУ: Медвежий тренд + RSI в зоне перекупленности
        if (!bullishTrend && rsiValue.doubleValue() > RSI_OVERBOUGHT) {
            return createScalpingSignal(TradingSignal.SignalType.SELL, "RSI перекуплен в медвежьем тренде", lastIndex, instrument);
        }

        return new TradingSignal(TradingSignal.SignalType.HOLD, 0, "Ожидание скальпингового момента");
    }

    private TradingSignal createScalpingSignal(TradingSignal.SignalType type, String reason, int index, com.tradingbot.tinkoff.model.TradableInstrument instrument) {
        Num currentPrice = closePrice.getValue(index);
        BigDecimal entryPrice = ((DecimalNum) currentPrice).getDelegate();

        // Скальпинговые уровни: узкий стоп, быстрый профит
        BigDecimal pricePercent = entryPrice.multiply(new BigDecimal("0.003")); // 0.3%

        BigDecimal stopLoss, takeProfit;
        if (type == TradingSignal.SignalType.BUY) {
            stopLoss = entryPrice.subtract(pricePercent).setScale(4, RoundingMode.HALF_UP);
            takeProfit = entryPrice.add(pricePercent.multiply(new BigDecimal("2"))).setScale(4, RoundingMode.HALF_UP);
        } else {
            stopLoss = entryPrice.add(pricePercent).setScale(4, RoundingMode.HALF_UP);
            takeProfit = entryPrice.subtract(pricePercent.multiply(new BigDecimal("2"))).setScale(4, RoundingMode.HALF_UP);
        }

        TradingSignal signal = new TradingSignal(type, 88, reason);
        signal.setInstrument(instrument);
        signal.setEntryPrice(entryPrice);
        signal.setStopLoss(stopLoss);
        signal.setTakeProfit(takeProfit);
        signal.setTimestamp(ZonedDateTime.now());
        signal.setSignalId(signalCounter.incrementAndGet());

        logger.info("⚡ Скальпинг сигнал: {} по {} (SL: {}, TP: {})", type, entryPrice, stopLoss, takeProfit);
        return signal;
    }
}

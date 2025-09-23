
package com.tradingbot.tinkoff.model;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import lombok.Data;

/**
 * Модель торгового сигнала
 */
@Data
public class TradingSignal {

    public enum SignalType {
        BUY, SELL, HOLD
    }

    public enum Trend {
        BULL, BEAR, SIDEWAYS
    }

    private int signalId;
    private SignalType signalType;
    private Trend trend; // Добавлено поле для тренда
    private int score; // 0-100
    private String description;
    private BigDecimal entryPrice;
    private BigDecimal stopLoss;
    private BigDecimal takeProfit;
    private ZonedDateTime timestamp;
    private TradableInstrument instrument;

    // Lombok не генерирует сеттеры для измененных типов автоматически
    public void setInstrument(TradableInstrument instrument) {
        this.instrument = instrument;
    }
    public TradableInstrument getInstrument() {
        return instrument;
    }

    public TradingSignal(SignalType signalType, int score, String description) {
        this.signalType = signalType;
        this.score = score;
        this.description = description;
        this.timestamp = ZonedDateTime.now();
    }
}

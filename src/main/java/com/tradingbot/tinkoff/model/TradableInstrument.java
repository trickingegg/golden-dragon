package com.tradingbot.tinkoff.model;

public class TradableInstrument {
    private final String name;
    private final String figi;
    private final InstrumentType type;
    private final String currency;

    public TradableInstrument(String name, String figi, InstrumentType type, String currency) {
        this.name = name;
        this.figi = figi;
        this.type = type;
        this.currency = currency;
    }

    public String name() {
        return name;
    }

    public String ticker() {
        return name;
    }

    public String identifier() {
        return figi;
    }

    public InstrumentType type() {
        return type;
    }

    public String currency() {
        return currency;
    }

    public enum InstrumentType {
        STOCK,
        FUTURE
    }

    @Override
    public String toString() {
        return name; // Это важно для корректного отображения в ChoiceBox
    }
}

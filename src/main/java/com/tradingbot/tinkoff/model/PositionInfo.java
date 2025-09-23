package com.tradingbot.tinkoff.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class PositionInfo { // <<-- ИЗМЕНЕНО: на class
    private final String figi;
    private final String ticker;
    private final String instrumentType; // "Акция", "Фьючерс" и т.д.
    private final long quantity; // Количество в лотах
    private final BigDecimal averagePrice; // Средняя цена входа в позицию
    private final BigDecimal currentPrice; // Текущая рыночная цена
    private final String currency;

    public PositionInfo(String figi, String ticker, String instrumentType, long quantity, BigDecimal averagePrice, BigDecimal currentPrice, String currency) {
        this.figi = figi;
        this.ticker = ticker;
        this.instrumentType = instrumentType;
        this.quantity = quantity;
        this.averagePrice = averagePrice;
        this.currentPrice = currentPrice;
        this.currency = currency;
    }

    // Геттеры для полей
    public String figi() {
        return figi;
    }

    public String ticker() {
        return ticker;
    }

    public String instrumentType() {
        return instrumentType;
    }

    public long quantity() {
        return quantity;
    }

    public BigDecimal averagePrice() {
        return averagePrice;
    }

    public BigDecimal currentPrice() {
        return currentPrice;
    }

    public String currency() {
        return currency;
    }

    // Метод для расчета P/L в процентах
    public BigDecimal getProfitLossPercent() {
        if (averagePrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        // Формула: ((Текущая цена - Средняя цена) / Средняя цена) * 100
        BigDecimal pnl = currentPrice.subtract(averagePrice)
                .divide(averagePrice, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        // Для шорт-позиций инвертируем знак
        if (quantity < 0) {
            return pnl.negate();
        }
        return pnl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PositionInfo that = (PositionInfo) o;
        return quantity == that.quantity && Objects.equals(figi, that.figi) && Objects.equals(ticker, that.ticker) && Objects.equals(instrumentType, that.instrumentType) && Objects.equals(averagePrice, that.averagePrice) && Objects.equals(currentPrice, that.currentPrice) && Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(figi, ticker, instrumentType, quantity, averagePrice, currentPrice, currency);
    }

    @Override
    public String toString() {
        return "PositionInfo{" +
                "figi='" + figi + '\'' +
                ", ticker='" + ticker + '\'' +
                ", instrumentType='" + instrumentType + '\'' +
                ", quantity=" + quantity +
                ", averagePrice=" + averagePrice +
                ", currentPrice=" + currentPrice +
                ", currency='" + currency + '\'' +
                '}';
    }
}

package com.tradingbot.tinkoff.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

// Используем class для простоты и неизменяемости
public final class OrderInfo {
    private final String orderId;
    private final String instrumentTicker;
    private final String direction; // "BUY" или "SELL"
    private final long quantity; // Количество лотов
    private final BigDecimal averagePrice; // Средняя цена исполнения
    private final String status; // "Active", "Filled", "Cancelled"
    private final LocalDateTime timestamp;

    public OrderInfo(String orderId, String instrumentTicker, String direction, long quantity, BigDecimal averagePrice, String status, LocalDateTime timestamp) {
        this.orderId = Objects.requireNonNull(orderId);
        this.instrumentTicker = Objects.requireNonNull(instrumentTicker);
        this.direction = Objects.requireNonNull(direction);
        this.quantity = quantity;
        this.averagePrice = Objects.requireNonNull(averagePrice);
        this.status = Objects.requireNonNull(status);
        this.timestamp = Objects.requireNonNull(timestamp);
    }

    public String getOrderId() {
        return orderId;
    }

    public String getInstrumentTicker() {
        return instrumentTicker;
    }

    public String getDirection() {
        return direction;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getAveragePrice() {
        return averagePrice;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (OrderInfo) obj;
        return Objects.equals(this.orderId, that.orderId) &&
                Objects.equals(this.instrumentTicker, that.instrumentTicker) &&
                Objects.equals(this.direction, that.direction) &&
                this.quantity == that.quantity &&
                Objects.equals(this.averagePrice, that.averagePrice) &&
                Objects.equals(this.status, that.status) &&
                Objects.equals(this.timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, instrumentTicker, direction, quantity, averagePrice, status, timestamp);
    }

    @Override
    public String toString() {
        return "OrderInfo[" +
                "orderId=" + orderId + ", " +
                "instrumentTicker=" + instrumentTicker + ", " +
                "direction=" + direction + ", " +
                "quantity=" + quantity + ", " +
                "averagePrice=" + averagePrice + ", " +
                "status=" + status + ", " +
                "timestamp=" + timestamp +
                ']';
    }
}
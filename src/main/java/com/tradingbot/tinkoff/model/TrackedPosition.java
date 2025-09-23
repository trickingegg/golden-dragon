package com.tradingbot.tinkoff.model;

import ru.tinkoff.piapi.core.models.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
//import java.util.Objects; // Убираем неиспользуемый импорт
import com.tradingbot.tinkoff.api.TinkoffApiConnector;

public class TrackedPosition {
    private final Position basePosition;
    private final BigDecimal stopLossPrice;
    private final BigDecimal takeProfitPrice;
    private final String ticker; // Добавляем поле для тикера/названия
    private final String instrumentType; // Добавляем поле для типа инструмента

    public TrackedPosition(Position basePosition, BigDecimal stopLossPrice, BigDecimal takeProfitPrice, String ticker, String instrumentType) {
        this.basePosition = basePosition;
        this.stopLossPrice = stopLossPrice;
        this.takeProfitPrice = takeProfitPrice;
        this.ticker = ticker;
        this.instrumentType = instrumentType;
    }

    public Position getBasePosition() {
        return basePosition;
    }

    public BigDecimal getStopLossPrice() {
        return stopLossPrice;
    }

    public BigDecimal getTakeProfitPrice() {
        return takeProfitPrice;
    }

    // Методы-обертки для доступа к полям базовой позиции, если это необходимо
    public String getFigi() {
        return basePosition.getFigi();
    }

    public String getTicker() {
        return ticker; // Используем сохраненный тикер
    }

    public String instrumentType() {
        return instrumentType; // Возвращаем сохраненный тип инструмента
    }

    public long quantity() {
        return basePosition.getQuantityLots().longValue();
    }

    public BigDecimal averagePrice() {
        return TinkoffApiConnector.moneyToBigDecimal(basePosition.getAveragePositionPrice());
    }

    public BigDecimal currentPrice() {
        return TinkoffApiConnector.moneyToBigDecimal(basePosition.getCurrentPrice());
    }

    public BigDecimal getProfitLossPercent() {
        if (averagePrice().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal pnl = currentPrice().subtract(averagePrice())
                .divide(averagePrice(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        if (quantity() < 0) {
            return pnl.negate();
        }
        return pnl;
    }

    @Override
    public String toString() {
        return "TrackedPosition{" +
               "figi='" + getFigi() + '\'' +
               ", ticker='" + getTicker() + '\'' +
               ", instrumentType='" + instrumentType() + '\'' +
               ", quantity=" + quantity() +
               ", stopLossPrice=" + stopLossPrice +
               ", takeProfitPrice=" + takeProfitPrice +
               '}';
    }
}

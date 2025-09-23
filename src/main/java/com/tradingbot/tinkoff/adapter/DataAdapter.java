package com.tradingbot.tinkoff.adapter;

import ru.tinkoff.piapi.contract.v1.OrderBook;
// Старые импорты для совместимости со стратегией
// import com.github.galimru.tinkoff.json.streaming.OrderbookEvent;

/**
 * Адаптер для преобразования типов данных между SDK версиями
 */
public class DataAdapter {

    /**
     * Преобразование нового OrderBook в старый OrderbookEvent
     * Потребуется реализация для совместимости со стратегией
     */
    public static Object convertOrderBook(OrderBook newOrderBook) {
        // Здесь нужно будет создать объект совместимый со старой стратегией
        // Или обновить стратегию для работы с новыми типами
        return null; // Заглушка
    }
}

package com.tradingbot.tinkoff.api;

import com.tradingbot.tinkoff.model.TradableInstrument;
import com.tradingbot.tinkoff.model.OrderInfo;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.stream.MarketDataSubscriptionService;
import ru.tinkoff.piapi.core.stream.StreamProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.time.temporal.ChronoUnit;
import ru.tinkoff.piapi.contract.v1.GetFuturesMarginResponse;

import java.net.InetAddress;
import java.net.Socket;
import java.net.InetSocketAddress;

/**
 * Современный коннектор для Tinkoff Invest API v2 (SDK 1.32+)
 * Поддерживает подключение, стриминг данных и управление портфелем
 * С улучшенной обработкой сетевых ошибок
 */
public class TinkoffApiConnector {
    private static final Logger logger = LoggerFactory.getLogger(TinkoffApiConnector.class);

    private final InvestApi api;
    private final ExecutorService executorService;
    private final boolean sandboxMode;

    /**
     * -- GETTER --
     *  Проверка состояния подключения
     */
    @Getter
    private volatile boolean isConnected = false;

    /**
     * -- GETTER --
     *  Получение ID аккаунта
     */
    @Getter
    private String accountId;


    /**
     * Получает список всех активно торгуемых инструментов (акций и фьючерсов).
     * @return Список `TradableInstrument`, готовый для отображения в UI.
     */
    public List<TradableInstrument> getActiveInstruments() {
        logger.info("🔍 Загрузка списков активных акций и фьючерсов...");

        // Запрашиваем все акции
        Stream<TradableInstrument> sharesStream = api.getInstrumentsService().getShares(InstrumentStatus.INSTRUMENT_STATUS_BASE)
                .join().stream()
                .filter(s -> s.getTradingStatus() == SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING && s.getApiTradeAvailableFlag())
                .map(s -> new TradableInstrument(s.getName() + " (Акция)", s.getFigi(), TradableInstrument.InstrumentType.STOCK, s.getCurrency()));

        // Запрашиваем все фьючерсы (и срочные, и бессрочные)
        Stream<TradableInstrument> futuresStream = api.getInstrumentsService().getFutures(InstrumentStatus.INSTRUMENT_STATUS_ALL)
                .join().stream()
                .filter(f -> f.getTradingStatus() == SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING && f.getApiTradeAvailableFlag())
                .map(f -> new TradableInstrument(f.getName() + " (Фьючерс)", f.getFigi(), TradableInstrument.InstrumentType.FUTURE, f.getCurrency()));

        // Объединяем два потока в один общий список
        List<TradableInstrument> allInstruments = Stream.concat(sharesStream, futuresStream)
                .sorted(Comparator.comparing(TradableInstrument::name)) // Сортируем по имени для удобства
                .collect(Collectors.toList());

        logger.info("✅ Загружено {} активных инструментов.", allInstruments.size());
        return allInstruments;
    }
    public TinkoffApiConnector(String token, boolean sandboxMode) {
        this.sandboxMode = sandboxMode;

        try {
            // Принудительные системные настройки для устойчивого подключения
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("java.net.preferIPv6Addresses", "false");
            System.setProperty("io.grpc.netty.useCustomAllocator", "false");
            System.setProperty("io.grpc.internal.DnsNameResolverProvider.enable_grpclb", "false");
            System.setProperty("io.netty.resolver.dns.preferIPv4", "true");
            System.setProperty("io.netty.resolver.dns.preferIPv6", "false");
            System.setProperty("io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.maxInboundMessageSize", "1048576");

            logger.info("🔧 Применены системные настройки для стабильного подключения");

            // Создаем API клиент с правильной конфигурацией
            if (sandboxMode) {
                this.api = InvestApi.createSandbox(token);
            } else {
                this.api = InvestApi.create(token);
            }

            this.executorService = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("TinkoffAPI-" + Thread.currentThread().getName());
                return t;
            });

            logger.info("✅ TinkoffApiConnector инициализирован. Sandbox режим: {}", sandboxMode);

        } catch (Exception e) {
            logger.error("❌ Критическая ошибка инициализации API", e);
            throw new RuntimeException("Не удалось создать API коннектор: " + e.getMessage(), e);
        }
    }

    /**
     * Запрашивает исторические свечи для заданного инструмента, автоматически разбивая запрос на допустимые периоды.
     * @param figi FIGI инструмента.
     * @param days количество дней истории для загрузки.
     * @param interval интервал свечей.
     * @return Список исторических свечей.
     */
    public List<HistoricCandle> getHistoricCandles(String figi, int days, CandleInterval interval) {
        logger.info("🕯️ Запрос исторических свечей для FIGI {} за последние {} дней...", figi, days);
        List<HistoricCandle> allCandles = new ArrayList<>();
        Instant now = Instant.now();

        // Согласно документации API, для минутных свечей запрашиваем по одному дню
        for (int i = 0; i < days; i++) {
            Instant from = now.minus(i + 1, ChronoUnit.DAYS);
            Instant to = now.minus(i, ChronoUnit.DAYS);

            try {
                logger.debug("Запрос части истории с {} по {}", from, to);
                List<HistoricCandle> batch = api.getMarketDataService().getCandles(figi, from, to, interval).join();
                allCandles.addAll(batch);

                // Небольшая вежливая задержка, чтобы не превысить лимиты на частоту запросов (rate limiting)
                Thread.sleep(250); // 4 запроса в секунду - безопасно
            } catch (Exception e) {
                // Если для какого-то дня данных нет (например, выходной), просто логируем и продолжаем
                logger.warn("Не удалось загрузить часть истории ({} - {}): {}", from, to, e.getMessage());
            }
        }

        // Сортируем свечи по времени, так как они могли прийти не по порядку
        allCandles.sort(Comparator.comparing(c -> TinkoffApiConnector.timestampToInstant(c.getTime())));
        logger.info("✅ Всего загружено {} исторических свечей.", allCandles.size());
        return allCandles;
    }

    /**
     * Пополнение счета в песочнице на указанную сумму.
     * @param amount Сумма пополнения в рублях.
     */
    public void payInSandbox(BigDecimal amount) {
        if (!sandboxMode || accountId == null) {
            throw new IllegalStateException("Пополнение возможно только для активного счета в песочнице.");
        }
        api.getSandboxService().payInSync(accountId, MoneyValue.newBuilder()
                .setCurrency("rub")
                .setUnits(amount.longValue())
                //.setNano(amount.remainder(BigDecimal.ONE).multiply(new BigDecimal("1000000000")).intValue())
                .build());
    }

    public ru.tinkoff.piapi.contract.v1.PostOrderResponse postMarketOrder(String figi, long quantity, OrderDirection direction) {
        String orderId = UUID.randomUUID().toString(); // Идемпотентность

        // Создаем пустой Quotation для рыночного ордера, где цена не указывается
        Quotation emptyQuotation = Quotation.newBuilder().setUnits(0).setNano(0).build();

        // Для sandbox-счета используем сервис песочницы
        if (sandboxMode) {
            return api.getSandboxService().postOrderSync(figi, quantity, emptyQuotation, direction, accountId, OrderType.ORDER_TYPE_MARKET, orderId);
        } else {
            // Для боевого счета - реальный сервис
            return api.getOrdersService().postOrderSync(figi, quantity, emptyQuotation, direction, accountId, OrderType.ORDER_TYPE_MARKET, orderId);
        }
    }

    /**
     * Получает полную информацию об инструменте по его FIGI.
     * @param figi FIGI инструмента.
     * @return Объект Instrument.
     */
    public Instrument getInstrumentByFigiSync(String figi) {
        if (!isConnected()) {
            throw new IllegalStateException("API не подключено.");
        }
        return api.getInstrumentsService().getInstrumentByFigiSync(figi);
    }

    public String postStopOrder(String instrumentId, long quantity, Quotation stopPrice, StopOrderDirection direction, StopOrderType type) {
        // ID ордера
        //String stopOrderId = UUID.randomUUID().toString(); // <<-- УДАЛЕНО: неиспользуемая переменная

        // Дата истечения - 1 день от текущего момента
        Instant expireDate = Instant.now().plus(1, ChronoUnit.DAYS);

        if (sandboxMode) {
            // В SandboxService нет метода postStopOrderGoodTillDateSync. Временно отключаем.
            // return api.getSandboxService().postStopOrderGoodTillDateSync(instrumentId, quantity, stopPrice, stopPrice, direction, accountId, type, expireDate);
            return null; // Возвращаем null или генерируем исключение, если стоп-ордера не поддерживаются в песочнице
        } else {
            return api.getStopOrdersService().postStopOrderGoodTillDateSync(instrumentId, quantity, stopPrice, stopPrice, direction, accountId, type, expireDate);
        }
    }

    /**
     * Получает список активных ордеров для текущего аккаунта.
     * @return Список активных ордеров в формате {@link OrderInfo}.
     */
    public List<OrderInfo> getActiveOrders() {
        logger.info("📋 Запрос активных ордеров для аккаунта: {}", accountId);
        List<OrderState> apiOrders;
        if (sandboxMode) {
            apiOrders = api.getSandboxService().getOrders(accountId).join();
        } else {
            apiOrders = api.getOrdersService().getOrders(accountId).join();
        }

        List<OrderInfo> activeOrders = apiOrders.stream()
                .filter(order -> order.getExecutionReportStatus() == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW) // <<-- ИСПРАВЛЕНО: только новые ордера как активные
                .map(this::mapOrderStateToOrderInfo)
                .collect(Collectors.toList());

        logger.info("✅ Получено {} активных ордеров.", activeOrders.size());
        return activeOrders;
    }

    /**
     * Получает список исторических ордеров (исполненных, отмененных и т.д.) для текущего аккаунта.
     * На данный момент API Tinkoff не предоставляет прямого метода для получения истории ордеров
     * как таковых, поэтому мы будем использовать операции и фильтровать по типу "сделка".
     *
     * @param from Начальное время для запроса истории.
     * @param to Конечное время для запроса истории.
     * @return Список исторических ордеров в формате {@link OrderInfo}.
     */
    public List<OrderInfo> getHistoricalOrders(Instant from, Instant to) {
        logger.info("📜 Запрос исторических ордеров для аккаунта {} с {} по {}", accountId, from, to);
        List<ru.tinkoff.piapi.contract.v1.Operation> operations;
        if (sandboxMode) {
            operations = api.getSandboxService().getOperations(accountId, from, to, ru.tinkoff.piapi.contract.v1.OperationState.OPERATION_STATE_EXECUTED, null).join(); // Для SandboxService оставляем state и figi
        } else {
            operations = api.getOperationsService().getExecutedOperations(accountId, from, to).join(); // <<-- ИСПРАВЛЕНО: используем getExecutedOperations
        }

        List<OrderInfo> historicalOrders = operations.stream()
                .filter(op -> !op.getId().isEmpty()) // ИСПРАВЛЕНО: удален hasId(), используем getId().isEmpty()
                .map(op -> {
                    // Для исторических ордеров, приходится извлекать информацию из Operation
                    // Здесь мы создаем упрощенный OrderInfo на основе сделки.
                    String orderId = op.getId();
                    String instrumentTicker = op.getFigi();
                    String direction = op.getOperationType().name();
                    long quantity = op.getQuantity();
                    BigDecimal averagePrice = moneyValueToBigDecimal(op.getPrice());
                    String status = "FILLED";
                    LocalDateTime timestamp = timestampToLocalDateTime(op.getDate());

                    return new OrderInfo(orderId, instrumentTicker, direction, quantity, averagePrice, status, timestamp);
                })
                .collect(Collectors.toList());

        logger.info("✅ Получено {} исторических ордеров.", historicalOrders.size());
        return historicalOrders;
    }

    /**
     * Вспомогательный метод для маппинга OrderState в OrderInfo
     */
    private OrderInfo mapOrderStateToOrderInfo(OrderState orderState) {
        String instrumentTicker = orderState.getFigi(); // TODO: Разрешить FIGI в тикер инструмента
        String direction = orderState.getDirection() == OrderDirection.ORDER_DIRECTION_BUY ? "BUY" : "SELL";
        long quantity = orderState.getLotsRequested() - orderState.getLotsExecuted(); // Количество лотов, ожидающих исполнения
        BigDecimal averagePrice = moneyValueToBigDecimal(orderState.getAveragePositionPrice());
        String status = orderState.getExecutionReportStatus().name();
        LocalDateTime timestamp = timestampToLocalDateTime(orderState.getOrderDate());

        return new OrderInfo(orderState.getOrderId(), instrumentTicker, direction, quantity, averagePrice, status, timestamp);
    }

    /**
     * Подключение к API и получение аккаунта с повторными попытками
     */
    public CompletableFuture<Boolean> connect() {
        return CompletableFuture.supplyAsync(() -> {
            int maxAttempts = 5;
            long baseDelay = 2000; // 2 секунды

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    logger.info("🔗 Попытка подключения #{}/{} к Tinkoff API (sandbox={})...",
                            attempt, maxAttempts, sandboxMode);

                    // Проверяем сетевое подключение перед вызовом API
                    testNetworkConnectivity();

                    if (sandboxMode) {
                        // Получаем или создаем sandbox аккаунт
                        logger.info("📋 Получение sandbox аккаунтов...");
                        List<Account> accountsResponse = api.getSandboxService().getAccountsSync();

                        if (accountsResponse.isEmpty()) {
                            logger.info("📋 Создание нового sandbox аккаунта...");
                            this.accountId = api.getSandboxService().openAccountSync();
                            logger.info("✅ Sandbox аккаунт создан: {}", accountId);
                        } else {
                            this.accountId = accountsResponse.get(0).getId();
                            logger.info("✅ Используется существующий sandbox аккаунт: {}", accountId);
                        }

                        // Пополняем sandbox аккаунт для тестирования
//                        api.getSandboxService().payInSync(accountId,
//                                MapperUtils.bigDecimalToMoneyValue(BigDecimal.valueOf(1_000_000), "rub"));
//                        logger.info("💰 Sandbox аккаунт пополнен на 1,000,000 RUB");

                    } else {
                        // Получаем реальные аккаунты пользователя
                        logger.info("📋 Получение пользовательских аккаунтов...");
                        List<Account> accountsResponse = api.getUserService().getAccountsSync();

                        if (accountsResponse.isEmpty()) {
                            throw new RuntimeException("Нет доступных торговых счетов");
                        }

                        this.accountId = accountsResponse.get(0).getId();
                        logger.info("✅ Используется торговый счет: {}", accountId);
                    }

                    isConnected = true;
                    logger.info("🎉 Подключение к API успешно установлено на попытке #{}", attempt);
                    return true;

                } catch (Exception e) {
                    logger.error("❌ Попытка #{} неудачна: {} ({})",
                            attempt, e.getClass().getSimpleName(), e.getMessage());

                    if (e.getCause() instanceof java.nio.channels.UnsupportedAddressTypeException) {
                        logger.error("🌐 Проблема с сетевым подключением на уровне DNS/сокетов");
                    }

                    if (attempt == maxAttempts) {
                        logger.error("💥 Все попытки подключения исчерпаны");
                        isConnected = false;
                        return false;
                    }

                    // Экспоненциальная задержка между попытками
                    long delay = baseDelay * (long)Math.pow(2, attempt - 1);
                    logger.info("⏳ Ожидание {} мс перед следующей попыткой...", delay);

                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.error("❌ Прервано во время ожидания");
                        return false;
                    }
                }
            }

            return false;
        }, executorService);
    }

    /**
     * Находит самый актуальный (ближайший по дате экспирации) торгуемый фьючерс.
     * @param baseAssetTicker Тикер базового актива (например, "Si", "RI", "SBER").
     * @return FIGI актуального фьючерса.
     */
    public String findActiveFutureFigi(String baseAssetTicker) {
        logger.info("🔍 Поиск активного фьючерса для базового актива: {}", baseAssetTicker);
        List<Future> futures = api.getInstrumentsService().getFuturesSync(InstrumentStatus.INSTRUMENT_STATUS_ALL);

        return futures.stream()
                // Ищем по тикеру базового актива
                .filter(future -> future.getBasicAsset().equalsIgnoreCase(baseAssetTicker))
                // Выбираем только те, что сейчас торгуются
                .filter(future -> future.getTradingStatus() == SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING)
                // Сортируем, чтобы найти ближайший по дате экспирации
                .min(Comparator.comparing(future -> timestampToInstant(future.getExpirationDate())))
                .map(Future::getFigi) // Теперь это должно работать с Future
                .orElseThrow(() -> new RuntimeException("Не найден активный фьючерсный контракт для " + baseAssetTicker));
    }

    /**
     * Тестирование сетевого подключения
     */
    private void testNetworkConnectivity() {
        try {
            logger.info("🌐 Проверка сетевого подключения...");

            // Проверяем доступность DNS
            InetAddress.getByName("invest-public-api.tinkoff.ru");
            logger.info("✅ DNS резолвинг работает");

            // Проверяем доступность порта
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("invest-public-api.tinkoff.ru", 443), 5000);
                logger.info("✅ Порт 443 доступен");
            }

        } catch (Exception e) {
            logger.warn("⚠️ Проблема с сетевым подключением: {}", e.getMessage());
            throw new RuntimeException("Сеть недоступна: " + e.getMessage(), e);
        }
    }

    /**
     * Получение портфеля пользователя
     */
    public CompletableFuture<ru.tinkoff.piapi.core.models.Portfolio> getPortfolio() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isConnected || accountId == null) {
                    throw new RuntimeException("Не подключен к API или отсутствует ID аккаунта");
                }

                ru.tinkoff.piapi.core.models.Portfolio portfolio;
                if (sandboxMode) {
                    PortfolioResponse portfolioResponse = api.getSandboxService().getPortfolio(accountId).join();
                    portfolio = ru.tinkoff.piapi.core.models.Portfolio.fromResponse(portfolioResponse);
                } else {
                    portfolio = api.getOperationsService().getPortfolio(accountId).join();
                }

                logger.info("📊 Портфель получен. Позиций: {}", portfolio.getPositions().size());
                return portfolio;

            } catch (Exception e) {
                logger.error("❌ Ошибка получения портфеля", e);
                throw new RuntimeException("Не удалось получить портфель", e);
            }
        }, executorService);
    }

    /**
     * Получение размера гарантийного обеспечения по фьючерсам.
     *
     * @param figi FIGI фьючерса.
     * @return Ответ с информацией о гарантийном обеспечении.
     */
    public CompletableFuture<GetFuturesMarginResponse> getFuturesMarginResponse(String figi) {
        return api.getInstrumentsService().getFuturesMargin(figi);
    }

    /**
     * Подписка на рыночные данные
     */
    public MarketDataSubscriptionService subscribeToMarketData(List<String> figiList, CandleInterval interval,
                                                               Consumer<Candle> candleConsumer,
                                                               Consumer<OrderBook> orderBookConsumer,
                                                               int orderBookDepth) {
        if (!isConnected || accountId == null) {
            logger.error("❌ Не подключен к API или отсутствует ID аккаунта для подписки на рыночные данные");
            return null;
        }

        logger.info("📈 Подписка на рыночные данные для FIGI: {}", String.join(", ", figiList));

        // Создаем StreamProcessor для обработки всех типов MarketDataResponse
        StreamProcessor<MarketDataResponse> processor = response -> {
            if (response.hasCandle() && candleConsumer != null) {
                candleConsumer.accept(response.getCandle());
            } else if (response.hasOrderbook() && orderBookConsumer != null) {
                orderBookConsumer.accept(response.getOrderbook());
            }
            // Дополнительная обработка других типов ответов, если необходимо
        };

        Consumer<Throwable> onErrorCallback = throwable -> logger.error("❌ Ошибка в стриме рыночных данных", throwable);

        // Создаем новый стрим рыночных данных
        MarketDataSubscriptionService marketDataStream = api.getMarketDataStreamService().newStream(
                "market_data_stream_" + System.currentTimeMillis(),
                processor, onErrorCallback);

        // Подписка на свечи
        if (candleConsumer != null) {
            marketDataStream.subscribeCandles(figiList, mapCandleIntervalToSubscriptionInterval(interval)); // Используем восстановленный метод
        }

        // Подписка на стаканы
        if (orderBookConsumer != null) {
            marketDataStream.subscribeOrderbook(figiList, orderBookDepth);
        }

        logger.info("✅ Подписки активированы");
        return marketDataStream;
    }

    public PostOrderResponse closeMarketPosition(String figi, long quantity, OrderDirection direction) {
        if (!isConnected()) {
            throw new IllegalStateException("API не подключено.");
        }
        // Используем существующий метод postMarketOrder
        return postMarketOrder(figi, quantity, direction);
    }

    /**
     * Вспомогательный метод для преобразования CandleInterval в SubscriptionInterval
     */
    private SubscriptionInterval mapCandleIntervalToSubscriptionInterval(CandleInterval candleInterval) {
        switch (candleInterval) {
            case CANDLE_INTERVAL_1_MIN: return SubscriptionInterval.SUBSCRIPTION_INTERVAL_ONE_MINUTE;
            case CANDLE_INTERVAL_5_MIN: return SubscriptionInterval.SUBSCRIPTION_INTERVAL_FIVE_MINUTES;
            // Добавьте другие интервалы по мере необходимости
            default: return SubscriptionInterval.SUBSCRIPTION_INTERVAL_UNSPECIFIED;
        }
    }

    /**
     * Получение информации об инструменте
     */
    public CompletableFuture<Instrument> getInstrumentByFigi(String figi) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var instrument = api.getInstrumentsService().getInstrumentByFigiSync(figi);
                return instrument;
            } catch (Exception e) {
                logger.error("❌ Ошибка получения информации об инструменте {}", figi, e);
                throw new RuntimeException(e);
            }
        }, executorService);
    }

    /**
     * Отключение от API
     */
    public void disconnect() {
        try {
            logger.info("🔌 Отключение от API...");

            if (api != null) {
                api.destroy(3);
            }

            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdownNow();
            }

            isConnected = false;
            logger.info("✅ Отключение завершено");

        } catch (Exception e) {
            logger.error("❌ Ошибка при отключении", e);
        }
    }

    /**
     * Конвертация MoneyValue в BigDecimal
     */
    public static BigDecimal moneyValueToBigDecimal(MoneyValue moneyValue) {
        if (moneyValue == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(moneyValue.getUnits())
                .add(BigDecimal.valueOf(moneyValue.getNano(), 9));
    }

    /**
     * Конвертация Money (из core.models) в BigDecimal
     */
    public static BigDecimal moneyToBigDecimal(ru.tinkoff.piapi.core.models.Money money) {
        if (money == null) {
            return BigDecimal.ZERO;
        }
        return money.getValue();
    }

    /**
     * Конвертация Quotation в BigDecimal
     */
    public static BigDecimal quotationToBigDecimal(Quotation quotation) {
        if (quotation == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(quotation.getUnits())
                .add(BigDecimal.valueOf(quotation.getNano(), 9));
    }

    /**
     * Конвертация Timestamp в Instant
     */
    public static Instant timestampToInstant(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /**
     * Конвертация Timestamp в LocalDateTime
     */
    public static LocalDateTime timestampToLocalDateTime(com.google.protobuf.Timestamp timestamp) {
        return LocalDateTime.ofInstant(timestampToInstant(timestamp), ZoneOffset.UTC);
    }

    /**
     * Конвертация BigDecimal в Quotation
     */
    public static Quotation bigDecimalToQuotation(BigDecimal value) {
        if (value == null) {
            return Quotation.newBuilder().setUnits(0).setNano(0).build();
        }
        long units = value.longValue();
        int nano = value.remainder(BigDecimal.ONE).movePointRight(9).intValue();
        return Quotation.newBuilder().setUnits(units).setNano(nano).build();
    }
}
package com.tradingbot.tinkoff.controller;

import com.tradingbot.tinkoff.api.TinkoffApiConnector;
import com.tradingbot.tinkoff.model.OrderInfo;
import com.tradingbot.tinkoff.model.TradableInstrument;
import com.tradingbot.tinkoff.model.TradingSignal;
import com.tradingbot.tinkoff.monitoring.ApiUsageMonitor;
import com.tradingbot.tinkoff.processor.InstrumentProcessor;
import com.tradingbot.tinkoff.strategy.MultiStrategyManager;
import org.ta4j.core.BaseBarSeriesBuilder;
import com.tradingbot.tinkoff.tracking.SignalTracker;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.CandleInterval;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.core.models.Portfolio;
import ru.tinkoff.piapi.core.models.Position;
//import ru.tinkoff.piapi.core.models.Portfolio;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.time.Instant;
import com.tradingbot.tinkoff.model.TrackedPosition;
// import com.tradingbot.tinkoff.strategy.MultiStrategyManager; // Этот импорт уже есть
// import com.tradingbot.tinkoff.strategy.BaseBarSeriesBuilder; // Этот импорт уже есть

public class MainController {
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);

    @FXML private TextField tokenField;
    @FXML private ListView<TradableInstrument> instrumentListView; // <<-- ИЗМЕНЕНО: ChoiceBox -> ListView
    @FXML private ChoiceBox<CandleInterval> intervalChoiceBox;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Button connectButton;
    @FXML private Button depositButton;
    @FXML private TextArea logTextArea;
    @FXML private TableView<TradingSignal> signalsTable;
    @FXML private TableColumn<TradingSignal, String> signalInstrumentColumn; // <<-- ДОБАВЛЕНО
    @FXML private TableColumn<TradingSignal, String> signalTimeColumn;
    @FXML private TableColumn<TradingSignal, String> signalTypeColumn;
    @FXML private TableColumn<TradingSignal, String> signalScoreColumn;
    @FXML private TableColumn<TradingSignal, String> signalPriceColumn;
    @FXML private Label balanceLabel;
    @FXML private TextField depositAmountField;

    @FXML private TableView<OrderInfo> activeOrdersTable;
    @FXML private TableColumn<OrderInfo, String> activeOrderTimeColumn;
    @FXML private TableColumn<OrderInfo, String> activeOrderInstrumentColumn;
    @FXML private TableColumn<OrderInfo, String> activeOrderDirectionColumn;
    @FXML private TableColumn<OrderInfo, String> activeOrderQuantityColumn;
    @FXML private TableColumn<OrderInfo, String> activeOrderPriceColumn;
    @FXML private TableColumn<OrderInfo, String> activeOrderStatusColumn;

    @FXML private TableView<OrderInfo> historyOrdersTable;
    @FXML private TableColumn<OrderInfo, String> historyOrderTimeColumn;
    @FXML private TableColumn<OrderInfo, String> historyOrderInstrumentColumn;
    @FXML private TableColumn<OrderInfo, String> historyOrderDirectionColumn;
    @FXML private TableColumn<OrderInfo, String> historyOrderQuantityColumn;
    @FXML private TableColumn<OrderInfo, String> historyOrderPriceColumn;
    @FXML private TableColumn<OrderInfo, String> historyOrderStatusColumn;

    @FXML private TableView<TrackedPosition> positionsTable;
    @FXML private TableColumn<TrackedPosition, String> positionInstrumentColumn;
    @FXML private TableColumn<TrackedPosition, String> positionTypeColumn;
    @FXML private TableColumn<TrackedPosition, Long> positionQuantityColumn;
    @FXML private TableColumn<TrackedPosition, BigDecimal> positionEntryPriceColumn;
    @FXML private TableColumn<TrackedPosition, BigDecimal> positionCurrentPriceColumn;
    @FXML private TableColumn<TrackedPosition, BigDecimal> positionPnlPercentColumn;
    @FXML private TableColumn<TrackedPosition, BigDecimal> positionStopLossColumn;
    @FXML private TableColumn<TrackedPosition, BigDecimal> positionTakeProfitColumn;
    @FXML private TableColumn<TrackedPosition, Void> positionActionColumn;

    @FXML private TabPane instrumentTabPane; // <<-- ДОБАВЛЕНО
    @FXML private ListView<TradableInstrument> favoriteInstrumentListView; // <<-- ДОБАВЛЕНО
    @FXML private Button addToFavoritesButton; // <<-- ДОБАВЛЕНО

    @FXML private ListView<String> strategyListView; // ИЗМЕНЕНО: CheckListView -> ListView

    private final ObservableList<TrackedPosition> openPositions = FXCollections.observableArrayList();
    private final ObservableList<TradableInstrument> favoriteInstruments = FXCollections.observableArrayList(); // <<-- ДОБАВЛЕНО

    private ScheduledExecutorService positionUpdateScheduler;

    private TinkoffApiConnector apiConnector;
    private SignalTracker signalTracker;
    private ApiUsageMonitor apiMonitor;
    private MultiStrategyManager multiStrategyManager; // <<-- ДОБАВЛЕНО

    // Карта для хранения активных обработчиков инструментов
    private final Map<String, InstrumentProcessor> activeProcessors = new ConcurrentHashMap<>();
    private final ObservableList<TradingSignal> tradingSignals = FXCollections.observableArrayList();

    private final ObservableList<OrderInfo> activeOrders = FXCollections.observableArrayList();
    private final ObservableList<OrderInfo> historyOrders = FXCollections.observableArrayList();

    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Bot-Worker");
        t.setDaemon(true);
        return t;
    });

    private ScheduledExecutorService orderUpdateScheduler; // <<-- ДОБАВЛЕНО
    private ScheduledFuture<?> orderUpdateFuture; // <<-- ДОБАВЛЕНО

    @FXML
    public void initialize() {
        logger.info("🔧 Инициализация UI...");
        connectButton.setDisable(false);
        startButton.setDisable(true);
        stopButton.setDisable(true);
        depositButton.setDisable(true);

        intervalChoiceBox.setItems(FXCollections.observableArrayList(
                CandleInterval.CANDLE_INTERVAL_1_MIN,
                CandleInterval.CANDLE_INTERVAL_5_MIN
        ));
        intervalChoiceBox.setValue(CandleInterval.CANDLE_INTERVAL_1_MIN);

        // <<-- ВАЖНО: Разрешаем множественный выбор в ListView
        instrumentListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // <<-- ДОБАВЛЕНО: Настройка избранных инструментов
        favoriteInstrumentListView.setItems(favoriteInstruments);
        favoriteInstrumentListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // Инициализация CheckListView для стратегий
        // Примечание: MultiStrategyManager требует BarSeries, которую мы получим только при запуске InstrumentProcessor.
        // Поэтому пока будем использовать заглушку или инициализировать его позже.
        // Для получения имен стратегий без BarSeries, возможно, потребуется модифицировать MultiStrategyManager
        // Или передать пустую BarSeries для инициализации, если она не используется сразу.

        // Временно создадим MultiStrategyManager с пустой BarSeries для получения имен стратегий
        // Предполагается, что getStrategyNames() не зависит от фактических данных BarSeries.
        this.multiStrategyManager = new MultiStrategyManager(new BaseBarSeriesBuilder().withName("temp").build());
        strategyListView.setItems(FXCollections.observableArrayList(multiStrategyManager.getStrategyNames()));
        strategyListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        setupTableColumns();
        setupOrderTables();
        setupPositionsTable();
        orderUpdateScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Order-Updater");
            t.setDaemon(true);
            return t;
        });
        logger.info("✅ UI инициализирован.");
    }

    private void setupPositionsTable() {
        positionsTable.setItems(openPositions);

        positionInstrumentColumn.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getTicker()));
        positionTypeColumn.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().instrumentType()));
        positionQuantityColumn.setCellValueFactory(cellData -> new SimpleObjectProperty<>(cellData.getValue().quantity()));
        positionQuantityColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(String.valueOf(item));
                    if (item > 0) {
                        setStyle("-fx-text-fill: #2e7d32; -fx-font-weight: bold;"); // Зеленый для лонг
                    } else if (item < 0) {
                        setStyle("-fx-text-fill: #c62828; -fx-font-weight: bold;"); // Красный для шорт
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        positionEntryPriceColumn.setCellValueFactory(cellData -> new SimpleObjectProperty<>(cellData.getValue().averagePrice()));
        positionCurrentPriceColumn.setCellValueFactory(cellData -> new SimpleObjectProperty<>(cellData.getValue().currentPrice()));
        positionStopLossColumn.setCellValueFactory(cellData -> new SimpleObjectProperty<>(cellData.getValue().getStopLossPrice()));
        positionTakeProfitColumn.setCellValueFactory(cellData -> new SimpleObjectProperty<>(cellData.getValue().getTakeProfitPrice()));

        // Кастомная ячейка для P/L в процентах с цветовым кодированием
        positionPnlPercentColumn.setCellValueFactory(cellData -> new SimpleObjectProperty<>(cellData.getValue().getProfitLossPercent()));
        positionPnlPercentColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(String.format("%.2f%%", item));
                    if (item.compareTo(BigDecimal.ZERO) > 0) {
                        setStyle("-fx-text-fill: #2e7d32; -fx-font-weight: bold;"); // Зеленый
                    } else if (item.compareTo(BigDecimal.ZERO) < 0) {
                        setStyle("-fx-text-fill: #c62828; -fx-font-weight: bold;"); // Красный
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        // Кастомная ячейка для кнопки "Закрыть"
        positionActionColumn.setCellFactory(param -> new TableCell<>() {
            private final Button closeButton = new Button("Закрыть");
            {
                closeButton.setOnAction(event -> {
                    TrackedPosition position = getTableView().getItems().get(getIndex());
                    handleClosePosition(position);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    // Обертываем кнопку в HBox для центрирования
                    HBox box = new HBox(closeButton);
                    box.setAlignment(Pos.CENTER);
                    setGraphic(box);
                }
            }
        });
    }

    private void setupTableColumns() {
        signalsTable.setItems(tradingSignals);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        // <<-- ДОБАВЛЕНО: Отображение тикера инструмента в таблице
        signalInstrumentColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getInstrument().name()));
        signalTimeColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getTimestamp().format(formatter)));
        signalTypeColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getSignalType().name()));
        signalScoreColumn.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getScore())));
        signalPriceColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getEntryPrice() != null ? cell.getValue().getEntryPrice().toPlainString() : "N/A"));
    }

    private void setupOrderTables() {
        activeOrdersTable.setItems(activeOrders);
        historyOrdersTable.setItems(historyOrders);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        activeOrderTimeColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getTimestamp().format(formatter)));
        activeOrderInstrumentColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getInstrumentTicker()));
        activeOrderDirectionColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDirection()));
        activeOrderQuantityColumn.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getQuantity())));
        activeOrderPriceColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getAveragePrice().toPlainString()));
        activeOrderStatusColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getStatus()));

        historyOrderTimeColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getTimestamp().format(formatter)));
        historyOrderInstrumentColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getInstrumentTicker()));
        historyOrderDirectionColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDirection()));
        historyOrderQuantityColumn.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().getQuantity())));
        historyOrderPriceColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getAveragePrice().toPlainString()));
        historyOrderStatusColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getStatus()));
    }

    @FXML
    private void handleConnect() {
        String token = tokenField.getText();
        if (token == null || token.trim().isEmpty()) {
            log("❌ Введите токен для подключения.");
            return;
        }

        log("🔌 Подключение к API...");
        connectButton.setDisable(true);

        backgroundExecutor.submit(() -> {
            try {
                this.apiConnector = new TinkoffApiConnector(token, true);
                this.apiMonitor = new ApiUsageMonitor();
                this.signalTracker = new SignalTracker();
                apiMonitor.recordConnect();

                boolean connected = apiConnector.connect().join();
                if (connected) {
                    Platform.runLater(() -> {
                        log("✅ API успешно подключено!");
                        tokenField.setDisable(true);
                        startButton.setDisable(false);
                        depositButton.setDisable(false);
                        startOrderUpdates(); // <<-- ДОБАВЛЕНО
                        startPositionUpdates();

                    });
                    loadInstruments();
                    fetchPortfolio();
                } else {
                    handleConnectionFailure();
                }
            } catch (Exception e) {
                handleCriticalError("Ошибка при подключении", e);
                Platform.runLater(() -> connectButton.setDisable(false));
            }
        });
    }

    private void loadInstruments() {
        backgroundExecutor.submit(() -> {
            try {
                Platform.runLater(() -> log("⏳ Загрузка списка доступных инструментов..."));
                List<TradableInstrument> instruments = apiConnector.getActiveInstruments();
                Platform.runLater(() -> updateInstrumentList(instruments));
            } catch (Exception e) {
                handleCriticalError("Не удалось загрузить список инструментов", e);
            }
        });
    }

    private void updateInstrumentList(List<TradableInstrument> instruments) {
        if (instruments.isEmpty()) {
            log("⚠️ Не найдено ни одного доступного для торговли инструмента.");
            startButton.setDisable(true);
            return;
        }
        instrumentListView.setItems(FXCollections.observableArrayList(instruments));
        log("✅ Список инструментов обновлен. Выберите один или несколько и нажмите 'Запуск'.");
    }

    @FXML
    private void handleAddToFavorites() {
        List<TradableInstrument> selectedForFavorites = instrumentListView.getSelectionModel().getSelectedItems();
        if (selectedForFavorites.isEmpty()) {
            log("❌ Выберите инструменты в списке 'Инструменты', чтобы добавить их в избранное.");
            return;
        }

        int addedCount = 0;
        for (TradableInstrument instrument : selectedForFavorites) {
            if (!favoriteInstruments.contains(instrument)) {
                favoriteInstruments.add(instrument);
                addedCount++;
            }
        }
        if (addedCount > 0) {
            log(String.format("⭐️ Добавлено %d инструмент(ов) в избранное.", addedCount));
        } else {
            log("ℹ️ Выбранные инструменты уже находятся в избранном.");
        }
    }

    @FXML
    private void handleStart() {
        List<TradableInstrument> selectedInstruments;
        Tab selectedTab = instrumentTabPane.getSelectionModel().getSelectedItem();

        if ("Инструменты".equals(selectedTab.getText())) {
            selectedInstruments = instrumentListView.getSelectionModel().getSelectedItems();
        } else if ("Избранное".equals(selectedTab.getText())) {
            selectedInstruments = favoriteInstrumentListView.getSelectionModel().getSelectedItems();
        } else {
            log("❌ Не удалось определить активный список инструментов.");
            return;
        }

        if (selectedInstruments.isEmpty()) {
            log("❌ Выберите хотя бы один инструмент из активного списка.");
            return;
        }

        log(String.format("🚀 Запуск торговых стратегий для %d инструментов...", selectedInstruments.size()));
        setTradingState(true);

        CandleInterval interval = intervalChoiceBox.getValue();
        Duration barDuration = getDurationFromInterval();

        List<String> selectedStrategies = strategyListView.getSelectionModel().getSelectedItems();
        if (selectedStrategies.isEmpty()) {
            log("❌ Выберите хотя бы одну стратегию для запуска.");
            setTradingState(false);
            return;
        }

        for (TradableInstrument instrument : selectedInstruments) {
            // Создаем и запускаем процессор для каждого выбранного инструмента
            InstrumentProcessor processor = new InstrumentProcessor(
                    instrument,
                    apiConnector,
                    signalTracker,
                    backgroundExecutor,
                    this::log, // Передаем метод логирования
                    tradingSignals, // Передаем общий список для UI
                    selectedStrategies // Передаем выбранные стратегии
            );
            activeProcessors.put(instrument.identifier(), processor);
            processor.start(interval, barDuration);
        }
    }

    @FXML
    private void handleStop() {
        log("🛑 Остановка всех торговых стратегий...");
        activeProcessors.values().forEach(InstrumentProcessor::stop);
        activeProcessors.clear();
        stopOrderUpdates(); // <<-- ДОБАВЛЕНО
        stopPositionUpdates();
        setTradingState(false);
        log("✅ Все стратегии остановлены.");
    }

    private void startPositionUpdates() {
        positionUpdateScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Position-Updater");
            t.setDaemon(true);
            return t;
        });
        positionUpdateScheduler.scheduleAtFixedRate(this::updatePositions, 0, 15, TimeUnit.SECONDS);
        log("✅ Запущено автоматическое обновление позиций.");
    }

    private void stopPositionUpdates() {
        if (positionUpdateScheduler != null) {
            positionUpdateScheduler.shutdownNow();
        }
    }

    private void updatePositions() {
        if (apiConnector == null || !apiConnector.isConnected()) return;

        backgroundExecutor.submit(() -> {
            try {
                // Получаем портфель из API
                Portfolio portfolio = apiConnector.getPortfolio().join();

                List<TrackedPosition> positions = portfolio.getPositions().stream()
                        .map(this::convertApiPositionToTrackedPosition)
                        .filter(java.util.Objects::nonNull) // Отфильтровываем пустые результаты
                        .collect(Collectors.toList());

                Platform.runLater(() -> {
                    openPositions.setAll(positions);
                    log(String.format("🔄 Позиции обновлены. Открыто: %d", openPositions.size()));
                });
            } catch (Exception e) {
                handleCriticalError("Ошибка при обновлении позиций", e);
            }
        });
    }

    private TrackedPosition convertApiPositionToTrackedPosition(Position apiPosition) {
        try {
            logger.debug("🔍 Конвертация API Position для FIGI: {}", apiPosition.getFigi());
            // Получаем тикер из информации об инструменте
            var instrument = apiConnector.getInstrumentByFigiSync(apiPosition.getFigi());

            String figi = apiPosition.getFigi();
            String ticker = instrument.getName(); // Используем полное имя инструмента
            String instrumentType = convertInstrumentType(apiPosition.getInstrumentType()); // Преобразуем тип инструмента
            long quantity = apiPosition.getQuantityLots().longValue();
            BigDecimal averagePrice = TinkoffApiConnector.moneyToBigDecimal(apiPosition.getAveragePositionPrice());
            BigDecimal currentPrice = TinkoffApiConnector.moneyToBigDecimal(apiPosition.getCurrentPrice());
            String currency = apiPosition.getAveragePositionPrice().getCurrency().toUpperCase();

            // Получаем Stop Loss и Take Profit из SignalTracker
            SignalTracker.TrackedSignal trackedSignal = signalTracker.getTrackedSignal(figi);
            BigDecimal stopLoss = (trackedSignal != null) ? trackedSignal.getStopLossPrice() : BigDecimal.ZERO;
            BigDecimal takeProfit = (trackedSignal != null) ? trackedSignal.getTakeProfitPrice() : BigDecimal.ZERO;

            logger.debug("  FIGI: {}, Ticker: {}, Type: {}, Quantity: {}, AvgPrice: {}, CurrPrice: {}, Currency: {}, SL: {}, TP: {}",
                    figi, ticker, instrumentType, quantity, averagePrice, currentPrice, currency, stopLoss, takeProfit);

            return new TrackedPosition(
                    apiPosition,
                    stopLoss,
                    takeProfit,
                    ticker, // Передаем тикер
                    instrumentType // Передаем тип инструмента
            );
        } catch (Exception e) {
            logger.error("Не удалось получить информацию для FIGI: {}", apiPosition.getFigi(), e);
            return null; // Возвращаем null, если не удалось обработать позицию
        }
    }

    private void handleClosePosition(TrackedPosition position) {
        log(String.format("⏳ Закрытие позиции по %s...", position.getTicker()));

        backgroundExecutor.submit(() -> {
            try {
                // Определяем направление ордера для закрытия
                OrderDirection directionToClose = position.quantity() > 0 ? OrderDirection.ORDER_DIRECTION_SELL : OrderDirection.ORDER_DIRECTION_BUY;
                long quantityToClose = Math.abs(position.quantity());

                apiConnector.closeMarketPosition(position.getFigi(), quantityToClose, directionToClose);

                Platform.runLater(() -> {
                    log(String.format("✅ Ордер на закрытие позиции %s (%d лотов) отправлен.", position.getTicker(), quantityToClose));
                    fetchPortfolio(); // <<-- ДОБАВЛЕНО: Обновляем баланс после закрытия позиции
                    // Немедленно запускаем обновление, чтобы увидеть результат
                    updatePositions();
                });
            } catch (Exception e) {
                handleCriticalError("Ошибка закрытия позиции " + position.getTicker(), e);
            }
        });
    }

    private void startOrderUpdates() { // <<-- ДОБАВЛЕНО
        if (orderUpdateFuture != null && !orderUpdateFuture.isDone()) {
            orderUpdateFuture.cancel(true);
        }
        // Обновляем ордера каждые 5 секунд
        orderUpdateFuture = orderUpdateScheduler.scheduleAtFixedRate(this::updateOrders, 0, 5, TimeUnit.SECONDS);
        log("✅ Запущено автоматическое обновление ордеров.");
    }

    private void stopOrderUpdates() { // <<-- ДОБАВЛЕНО
        if (orderUpdateFuture != null) {
            orderUpdateFuture.cancel(true);
            orderUpdateFuture = null;
            log("🛑 Автоматическое обновление ордеров остановлено.");
        }
    }

    private void updateOrders() { // <<-- ДОБАВЛЕНО
        logger.info("🔄 Запущено обновление ордеров."); // <<-- ДОБАВЛЕНО
        if (apiConnector == null || !apiConnector.isConnected()) {
            log("❌ API не подключено для обновления ордеров.");
            return;
        }
        backgroundExecutor.submit(() -> {
            try {
                logger.info("🔄 Запрос активных ордеров через API."); // <<-- ДОБАВЛЕНО
                List<OrderInfo> currentActiveOrders = apiConnector.getActiveOrders();
                logger.info("✅ Получено {} активных ордеров от API.", currentActiveOrders.size()); // <<-- ДОБАВЛЕНО

                Platform.runLater(() -> {
                    // Перемещаем исполненные/отмененные ордера из активных в историю
                    // Создаем временный список для удаления
                    List<OrderInfo> toRemove = activeOrders.stream()
                            .filter(existingOrder -> currentActiveOrders.stream()
                                    .noneMatch(newOrder -> newOrder.getOrderId().equals(existingOrder.getOrderId())))
                            .collect(Collectors.toList());

                    for (OrderInfo removedOrder : toRemove) {
                        log("ℹ️ Ордер " + removedOrder.getOrderId() + " был " + removedOrder.getStatus() + ", перемещен в историю.");
                        activeOrders.remove(removedOrder);
                        if (!historyOrders.contains(removedOrder)) { // Избегаем дубликатов при сохранении
                            historyOrders.add(removedOrder);
                        }
                    }

                    // Обновляем список активных ордеров
                    activeOrders.setAll(currentActiveOrders);
                    log(String.format("📈 Обновлено активных ордеров: %d. Исторических: %d", activeOrders.size(), historyOrders.size()));
                });

                // Получаем исторические ордера за последний день (можно настроить период)
                // Для простоты, пока будем получать операции за последний день
                Instant now = Instant.now();
                logger.info("🔄 Запрос исторических ордеров через API."); // <<-- ДОБАВЛЕНО
                List<OrderInfo> newHistoricalOperations = apiConnector.getHistoricalOrders(now.minus(1, TimeUnit.DAYS.toChronoUnit()), now);
                logger.info("✅ Получено {} исторических ордеров от API.", newHistoricalOperations.size()); // <<-- ДОБАВЛЕНО
                Platform.runLater(() -> {
                    for (OrderInfo histOrder : newHistoricalOperations) {
                        if (!historyOrders.contains(histOrder) &&
                            activeOrders.stream().noneMatch(active -> active.getOrderId().equals(histOrder.getOrderId()))) {
                            historyOrders.add(histOrder);
                        }
                    }
                });

            } catch (Exception e) {
                handleCriticalError("Ошибка при обновлении ордеров", e);
            }
        });
    }

    // Остальные методы (handleDeposit, fetchPortfolio, и т.д.) остаются почти без изменений,
    // так как они управляют общими вещами (счет, баланс).

    @FXML
    private void handleDeposit() {
        // Код этого метода не меняется
        String amountText = depositAmountField.getText();
        try {
            BigDecimal amount = new BigDecimal(amountText);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                log("⚠️ Сумма должна быть положительной.");
                return;
            }

            log("⏳ Пополнение счета на " + amount.toPlainString() + " RUB...");
            depositButton.setDisable(true);
            backgroundExecutor.submit(() -> {
                try {
                    apiConnector.payInSandbox(amount);
                    log("✅ Счет успешно пополнен!");
                    fetchPortfolio();
                } catch (Exception e) {
                    handleCriticalError("Ошибка пополнения", e);
                } finally {
                    Platform.runLater(() -> depositButton.setDisable(false));
                }
            });
        } catch (NumberFormatException e) {
            log("❌ Неверный формат суммы.");
        }
    }

    private void fetchPortfolio() {
        if (apiConnector == null) return;
        apiMonitor.recordPortfolio();
        try {
            Portfolio portfolioResponse = apiConnector.getPortfolio().join();
            Platform.runLater(() -> {
                BigDecimal totalValue = calculatePortfolioTotalValue(portfolioResponse);
                balanceLabel.setText(String.format("%,.2f RUB", totalValue));
                log("💰 Баланс обновлен: " + balanceLabel.getText());
            });
        } catch (Exception e) {
            handleCriticalError("Ошибка получения портфеля", e);
        }
    }

    private BigDecimal calculatePortfolioTotalValue(Portfolio portfolio) {
        if (portfolio == null) return BigDecimal.ZERO;
        BigDecimal positionsValue = portfolio.getPositions().stream()
                .map(p -> p.getQuantity().multiply(p.getCurrentPrice().getValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return positionsValue.add(portfolio.getTotalAmountCurrencies().getValue());
    }

    private Duration getDurationFromInterval() {
        return Duration.ofMinutes(intervalChoiceBox.getValue().getNumber());
    }

    private void setTradingState(boolean isTrading) {
        Platform.runLater(() -> {
            startButton.setDisable(isTrading);
            stopButton.setDisable(!isTrading);
            depositButton.setDisable(isTrading);
            instrumentListView.setDisable(isTrading);
            intervalChoiceBox.setDisable(isTrading);
        });
    }

    private void handleConnectionFailure() {
        Platform.runLater(() -> {
            log("❌ Не удалось подключиться. Проверьте токен и интернет.");
            connectButton.setDisable(false);
        });
    }

    private void handleCriticalError(String message, Throwable e) {
        logger.error(message, e);
        Platform.runLater(() -> log("💥 Ошибка: " + message + ". См. логи."));
    }

    private String convertInstrumentType(String apiInstrumentType) {
        String result;
        switch (apiInstrumentType) {
            case "SHARE":
                result = "Акция";
                break;
            case "FUTURES":
                result = "Фьючерс";
                break;
            case "CURRENCY":
                result = "Валюта";
                break;
            case "ETF":
                result = "ETF";
                break;
            case "BOND":
                result = "Облигация";
                break;
            default:
                result = apiInstrumentType;
                break;
        }
        return result;
    }

    private void log(String message) {
        Platform.runLater(() -> {
            String timeStamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logTextArea.appendText(timeStamp + " - " + message + "\n");
        });
        if (!message.startsWith("📈")) { // Чтобы не засорять логи частыми обновлениями свечей
            logger.info(message);
        }
    }
}

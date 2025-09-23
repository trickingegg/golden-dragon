package com.tradingbot.tinkoff;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class TinkoffTradingBotApplication extends Application {

    private static final Logger logger = LoggerFactory.getLogger(TinkoffTradingBotApplication.class);

    @Override
    public void start(Stage primaryStage) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainWindow.fxml"));
            VBox root = loader.load();

            Scene scene = new Scene(root);
            primaryStage.setTitle("Tinkoff Trading Bot - Professional Edition");
            primaryStage.setScene(scene);
            primaryStage.show();

            logger.info("Приложение успешно запущено.");

        } catch (IOException e) {
            logger.error("Не удалось загрузить FXML. Убедитесь, что файл MainWindow.fxml находится в 'src/main/resources/fxml/'", e);
        }
    }

    @Override
    public void stop() {
        logger.info("Завершение работы приложения.");
        // Корректное завершение потоков будет в MainController
        System.exit(0);
    }

    public static void main(String[] args) {
        // ??????????????? ??????????
        System.out.println("=== ??????????????? ?????????? ===");
        System.out.println("Java ??????: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name"));
        System.out.println("IPv4 Stack: " + System.getProperty("java.net.preferIPv4Stack"));
        System.out.println("IPv6 Addresses: " + System.getProperty("java.net.preferIPv6Addresses"));
        System.out.println("=====================================");

        launch(args);
    }
}
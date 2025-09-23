@echo off
set "JAVA_HOME=C:\Program Files\Java\jdk-21"  REM Укажите путь к вашей JDK 21
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "APP_NAME=tinkoff-trading-bot-shaded.jar"
set "MODULE_PATH=target\dependency"
set "MAIN_CLASS=com.tradingbot.tinkoff.TinkoffTradingBotApplication"

java --module-path %MODULE_PATH% ^
     --add-modules javafx.controls,javafx.fxml ^
     -jar target\%APP_NAME%

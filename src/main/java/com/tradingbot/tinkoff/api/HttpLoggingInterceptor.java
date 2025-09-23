package com.tradingbot.tinkoff.api;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class HttpLoggingInterceptor implements Interceptor {
    private static final Logger logger = LoggerFactory.getLogger(HttpLoggingInterceptor.class);

    @Override
    public Response intercept(Interceptor.Chain chain) throws IOException {
        Request request = chain.request();

        // Логируем запрос
        logger.info("🌐 HTTP Запрос: {} {}", request.method(), request.url());
        logger.info("📝 Headers: {}", request.headers());

        // Выполняем запрос
        Response response = chain.proceed(request);

        // Логируем ответ
        logger.info("📡 HTTP Ответ: {} {} ({})",
                response.code(), response.message(), request.url());
        logger.info("📝 Response Headers: {}", response.headers());

        // Перехватываем тело ответа
        ResponseBody responseBody = response.body();
        if (responseBody != null) {
            String responseString = responseBody.string();
            logger.info("📄 Response Body (first 500 chars): {}",
                    responseString.length() > 500 ?
                            responseString.substring(0, 500) + "..." : responseString);

            // Воссоздаем ResponseBody для дальнейшего использования
            ResponseBody newResponseBody = ResponseBody.create(
                    responseString, responseBody.contentType());

            return response.newBuilder().body(newResponseBody).build();
        }

        return response;
    }
}

package com.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        String botToken = requireEnv("BOT_TOKEN");
        String botUsername = requireEnv("BOT_USERNAME");
        String apiBaseUrl = System.getenv("TELEGRAM_API_BASE_URL");

        try {
            DefaultBotOptions options = new DefaultBotOptions();
            if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
                options.setBaseUrl(apiBaseUrl);
                log.info("Using local API server: {}", apiBaseUrl);
            }

            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new VideoDownloaderBot(options, botToken, botUsername));
            log.info("Bot started successfully");
        } catch (TelegramApiException e) {
            log.error("Failed to start bot", e);
            System.exit(1);
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            log.error("Required environment variable {} is not set", name);
            System.exit(1);
        }
        return value;
    }
}

package com.bot;

import com.bot.downloader.YtDlpDownloader;
import com.bot.util.UrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;

public class VideoDownloaderBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(VideoDownloaderBot.class);

    private final String botUsername;
    private final YtDlpDownloader downloader = new YtDlpDownloader();

    public VideoDownloaderBot(DefaultBotOptions options, String botToken, String botUsername) {
        super(options, botToken);
        this.botUsername = botUsername;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String chatId = update.getMessage().getChatId().toString();
        String text = update.getMessage().getText().trim();

        if (text.equals("/start")) {
            sendText(chatId, """
                    Welcome! Send me a YouTube or TikTok link and I'll download the video for you.

                    Supported links:
                    - YouTube (youtube.com, youtu.be, shorts)
                    - TikTok (tiktok.com, vm.tiktok.com)""");
            return;
        }

        log.info("Received message: {}", text);
        String url = UrlValidator.extractUrl(text);
        if (url == null) {
            sendText(chatId, "Please send a valid YouTube or TikTok link.");
            return;
        }
        log.info("Extracted URL: {}", url);

        sendText(chatId, "Downloading your video...");

        File videoFile = null;
        try {
            videoFile = downloader.download(url);
            sendVideo(chatId, videoFile);
        } catch (Exception e) {
            log.error("Failed to download video: {}", url, e);
            sendText(chatId, "Failed to download the video. Please check the link and try again.");
        } finally {
            downloader.cleanup(videoFile);
        }
    }

    private void sendText(String chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send message", e);
        }
    }

    private void sendVideo(String chatId, File file) throws TelegramApiException {
        long sizeMb = file.length() / (1024 * 1024);
        log.info("Sending video: {} ({} MB)", file.getName(), sizeMb);

        try {
            execute(SendVideo.builder()
                    .chatId(chatId)
                    .video(new InputFile(file))
                    .build());
            log.info("Video sent successfully: {} ({} MB)", file.getName(), sizeMb);
        } catch (TelegramApiException e) {
            log.warn("First sendVideo attempt failed, retrying in 3s...", e);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw e;
            }
            execute(SendVideo.builder()
                    .chatId(chatId)
                    .video(new InputFile(file))
                    .build());
            log.info("Video sent successfully on retry: {} ({} MB)", file.getName(), sizeMb);
        }
    }
}

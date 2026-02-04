package com.bot;

import com.bot.downloader.YtDlpDownloader;
import com.bot.downloader.YtDlpDownloader.FormatInfo;
import com.bot.util.UrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class VideoDownloaderBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(VideoDownloaderBot.class);
    private static final long MAX_FILE_SIZE_MB = 2000;
    private static final long PENDING_EXPIRY_MS = 30 * 60 * 1000;

    private final String botUsername;
    private final YtDlpDownloader downloader = new YtDlpDownloader();

    private record PendingDownload(String url, int messageId, long createdAt) {}
    private final ConcurrentHashMap<String, PendingDownload> pendingDownloads = new ConcurrentHashMap<>();

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
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
            return;
        }
        if (update.hasMessage() && update.getMessage().hasText()) {
            handleTextMessage(update);
        }
    }

    private void handleTextMessage(Update update) {
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

        int probeMsgId = sendTextAndGetId(chatId, "Checking available qualities...");

        List<FormatInfo> allFormats = downloader.fetchAvailableFormats(url);

        if (allFormats.size() <= 1) {
            editMessage(chatId, probeMsgId, "Downloading your video...");
            downloadAndSend(chatId, probeMsgId, url, null);
            return;
        }

        List<FormatInfo> downloadable = allFormats.stream()
                .filter(f -> f.estimatedSizeMb() == 0 || f.estimatedSizeMb() < MAX_FILE_SIZE_MB)
                .collect(Collectors.toList());

        List<FormatInfo> tooLarge = allFormats.stream()
                .filter(f -> f.estimatedSizeMb() >= MAX_FILE_SIZE_MB)
                .collect(Collectors.toList());

        if (downloadable.isEmpty()) {
            editMessage(chatId, probeMsgId,
                    "This video is too large for Telegram (limit " + MAX_FILE_SIZE_MB + " MB) at all available qualities.");
            return;
        }

        if (downloadable.size() == 1 && tooLarge.isEmpty()) {
            editMessage(chatId, probeMsgId, "Downloading your video...");
            downloadAndSend(chatId, probeMsgId, url, null);
            return;
        }

        PendingDownload pending = new PendingDownload(url, probeMsgId, System.currentTimeMillis());
        pendingDownloads.put(chatId, pending);

        StringBuilder message = new StringBuilder("Select video quality:");
        if (!tooLarge.isEmpty()) {
            String skipped = tooLarge.stream()
                    .map(f -> f.height() + "p")
                    .collect(Collectors.joining(", "));
            message.append("\n\nUnavailable due to Telegram 2 GB limit: ").append(skipped);
        }

        InlineKeyboardMarkup keyboard = buildQualityKeyboard(downloadable);
        editMessageWithKeyboard(chatId, probeMsgId, message.toString(), keyboard);
    }

    private void handleCallbackQuery(CallbackQuery callback) {
        String callbackId = callback.getId();
        String chatId = callback.getMessage().getChatId().toString();
        String data = callback.getData();

        answerCallback(callbackId);

        PendingDownload pending = pendingDownloads.get(chatId);
        if (pending == null || System.currentTimeMillis() - pending.createdAt() > PENDING_EXPIRY_MS) {
            pendingDownloads.remove(chatId);
            editMessage(chatId, callback.getMessage().getMessageId(), "Selection expired. Please send the link again.");
            return;
        }

        if ("q:cancel".equals(data)) {
            pendingDownloads.remove(chatId);
            editMessage(chatId, pending.messageId(), "Download cancelled.");
            return;
        }

        String formatSelector;
        String qualityLabel;

        if ("q:best".equals(data)) {
            formatSelector = null;
            qualityLabel = "best quality";
        } else if (data.startsWith("q:")) {
            int height = Integer.parseInt(data.substring(2));
            formatSelector = YtDlpDownloader.buildFormatSelector(height);
            qualityLabel = height + "p";
        } else {
            return;
        }

        pendingDownloads.remove(chatId);
        editMessage(chatId, pending.messageId(), "Downloading in " + qualityLabel + "...");
        downloadAndSend(chatId, pending.messageId(), pending.url(), formatSelector);
    }

    private void downloadAndSend(String chatId, int statusMessageId, String url, String formatSelector) {
        File videoFile = null;
        try {
            videoFile = downloader.download(url, formatSelector);
            long sizeMb = videoFile.length() / (1024 * 1024);

            if (sizeMb >= MAX_FILE_SIZE_MB) {
                editMessage(chatId, statusMessageId,
                        "Video is " + sizeMb + " MB (limit " + MAX_FILE_SIZE_MB + " MB). Too large to send.");
                return;
            }

            sendVideo(chatId, videoFile);
            editMessage(chatId, statusMessageId, "Video sent (" + sizeMb + " MB).");
        } catch (Exception e) {
            log.error("Failed to download video: {}", url, e);
            editMessage(chatId, statusMessageId, "Failed to download the video. Please check the link and try again.");
        } finally {
            downloader.cleanup(videoFile);
        }
    }

    private InlineKeyboardMarkup buildQualityKeyboard(List<FormatInfo> formats) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> currentRow = new ArrayList<>();

        for (FormatInfo fmt : formats) {
            String label;
            if (fmt.estimatedSizeMb() > 0) {
                label = fmt.height() + "p (~" + fmt.estimatedSizeMb() + " MB)";
            } else {
                label = fmt.height() + "p";
            }
            currentRow.add(InlineKeyboardButton.builder()
                    .text(label)
                    .callbackData("q:" + fmt.height())
                    .build());
            if (currentRow.size() == 2) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        rows.add(List.of(InlineKeyboardButton.builder()
                .text("Best available quality")
                .callbackData("q:best")
                .build()));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void answerCallback(String callbackId) {
        try {
            execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback", e);
        }
    }

    private void editMessage(String chatId, int messageId, String text) {
        try {
            execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit message", e);
        }
    }

    private void editMessageWithKeyboard(String chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        try {
            execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit message with keyboard", e);
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

    private int sendTextAndGetId(String chatId, String text) {
        try {
            return execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build()).getMessageId();
        } catch (TelegramApiException e) {
            log.error("Failed to send message", e);
            return 0;
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

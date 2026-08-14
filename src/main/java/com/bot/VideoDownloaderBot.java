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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.Document;

public class VideoDownloaderBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(VideoDownloaderBot.class);
    private static final long MAX_FILE_SIZE_MB = 2000;
    private static final long PENDING_EXPIRY_MS = 30 * 60 * 1000;

    private final String botUsername;
    private final Set<Long> adminIds;
    private final YtDlpDownloader downloader = new YtDlpDownloader();

    private record PendingDownload(String url, int messageId, long createdAt) {}
    private final ConcurrentHashMap<String, PendingDownload> pendingDownloads = new ConcurrentHashMap<>();

    private record DownloadTask(String url, String formatSelector, int statusMessageId) {}

    private static class UserSession {
        final Queue<DownloadTask> queue = new ConcurrentLinkedQueue<>();
        final AtomicReference<Process> activeProcess = new AtomicReference<>();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile boolean processing = false;
    }

    private final ConcurrentHashMap<String, UserSession> userSessions = new ConcurrentHashMap<>();
    private final ExecutorService downloadExecutor = Executors.newCachedThreadPool();

    public VideoDownloaderBot(DefaultBotOptions options, String botToken, String botUsername) {
        super(options, botToken);
        this.botUsername = botUsername;
        this.adminIds = parseAdminIds(System.getenv("ADMIN_IDS"));
        log.info("Initialized VideoDownloaderBot with {} admin(s)", adminIds.size());
    }

    private static Set<Long> parseAdminIds(String env) {
        if (env == null || env.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(env.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid admin ID in ADMIN_IDS: {}", s);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean isAdmin(long userId) {
        return adminIds.contains(userId);
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
        if (update.hasMessage()) {
            if (update.getMessage().hasDocument()) {
                handleDocumentMessage(update);
                return;
            }
            if (update.getMessage().hasText()) {
                handleTextMessage(update);
            }
        }
    }

    private void handleDocumentMessage(Update update) {
        var message = update.getMessage();
        String chatId = message.getChatId().toString();
        long userId = message.getFrom() != null ? message.getFrom().getId() : 0;

        if (!isAdmin(userId)) {
            log.warn("Unauthorized document upload attempt from user ID: {}", userId);
            sendText(chatId, "⚠️ You are not authorized to update cookies. Your Telegram ID: " + userId);
            return;
        }

        Document document = message.getDocument();
        String fileName = document.getFileName();
        if (fileName == null) {
            fileName = "cookies.txt";
        }

        if (!fileName.toLowerCase().endsWith(".txt")) {
            sendText(chatId, "❌ Please send a .txt file containing the exported cookies (e.g. yt-cookies.txt).");
            return;
        }

        try {
            sendText(chatId, "⏳ Downloading and applying cookies file...");
            GetFile getFile = new GetFile(document.getFileId());
            org.telegram.telegrambots.meta.api.objects.File tgFile = execute(getFile);

            byte[] content;
            String filePath = tgFile.getFilePath();
            File localSourceFile = filePath != null ? new File(filePath) : null;

            if (localSourceFile != null && localSourceFile.exists() && localSourceFile.isFile()) {
                log.info("Reading uploaded file directly from local API storage: {}", filePath);
                content = Files.readAllBytes(localSourceFile.toPath());
            } else {
                log.info("Downloading uploaded file via API stream: {}", filePath);
                try (InputStream in = downloadFileAsStream(tgFile)) {
                    content = in.readAllBytes();
                }
            }

            if (content.length == 0) {
                sendText(chatId, "❌ The uploaded file is empty.");
                return;
            }

            Path cookiesPath = Path.of(YtDlpDownloader.COOKIES_PATH);
            if (cookiesPath.getParent() != null) {
                Files.createDirectories(cookiesPath.getParent());
            }

            Files.write(cookiesPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            log.info("Cookies updated by admin {}: {} bytes written to {}", userId, content.length, cookiesPath);

            String status = String.format("✅ Cookies updated successfully!\n\n📁 File: %s\n📊 Size: %.2f KB\n📍 Path: %s",
                    fileName, content.length / 1024.0, cookiesPath);
            sendText(chatId, status);
        } catch (Exception e) {
            log.error("Failed to update cookies file from admin {}", userId, e);
            sendText(chatId, "❌ Failed to update cookies: " + e.getMessage());
        }
    }

    private void handleTextMessage(Update update) {
        String chatId = update.getMessage().getChatId().toString();
        long userId = update.getMessage().getFrom() != null ? update.getMessage().getFrom().getId() : 0;
        String text = update.getMessage().getText().trim();

        if (text.equals("/start")) {
            sendText(chatId, """
                    Welcome! Send me a YouTube, TikTok, or Pinterest link and I'll download the video for you.

                    Supported links:
                    - YouTube (youtube.com, youtu.be, shorts)
                    - TikTok (tiktok.com, vm.tiktok.com)
                    - Pinterest (pinterest.com/pin/..., pin.it)""");
            return;
        }

        if (text.equals("/myid")) {
            sendText(chatId, "Your Telegram User ID: " + userId + (isAdmin(userId) ? " (Admin)" : ""));
            return;
        }

        if (text.equals("/cookies") || text.equals("/status")) {
            File file = new File(YtDlpDownloader.COOKIES_PATH);
            StringBuilder sb = new StringBuilder();
            sb.append("📊 Cookies Status:\n");
            if (file.exists() && file.isFile()) {
                sb.append("✅ Cookies file is present\n");
                sb.append("📁 Path: ").append(file.getAbsolutePath()).append("\n");
                sb.append("📏 Size: ").append(String.format("%.2f KB", file.length() / 1024.0)).append("\n");
                sb.append("🕒 Modified: ").append(new Date(file.lastModified())).append("\n");
            } else {
                sb.append("⚠️ No cookies file found at ").append(YtDlpDownloader.COOKIES_PATH).append("\n");
            }
            if (isAdmin(userId)) {
                sb.append("\n👑 You are an admin. Send a .txt cookie file to update it.");
            } else {
                sb.append("\nYour User ID: ").append(userId);
            }
            sendText(chatId, sb.toString());
            return;
        }

        log.info("Received message: {}", text);
        String url = UrlValidator.extractUrl(text);
        if (url == null) {
            sendText(chatId, "Please send a valid YouTube, TikTok, or Pinterest link.");
            return;
        }
        log.info("Extracted URL: {}", url);

        int probeMsgId = sendTextAndGetId(chatId, "Checking available qualities...");

        List<FormatInfo> allFormats = downloader.fetchAvailableFormats(url);

        if (allFormats.size() <= 1) {
            submitDownload(chatId, new DownloadTask(url, null, probeMsgId));
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
            submitDownload(chatId, new DownloadTask(url, null, probeMsgId));
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

        if ("dl:cancel".equals(data)) {
            handleCancelCallback(chatId);
            return;
        }

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
        submitDownload(chatId, new DownloadTask(pending.url(), formatSelector, pending.messageId()));
    }

    private void handleCancelCallback(String chatId) {
        UserSession session = userSessions.get(chatId);
        if (session == null) {
            return;
        }

        session.cancelled.set(true);

        Process process = session.activeProcess.get();
        if (process != null) {
            process.destroyForcibly();
        }

        // Cancel all queued tasks
        DownloadTask queued;
        while ((queued = session.queue.poll()) != null) {
            editMessage(chatId, queued.statusMessageId(), "Download cancelled.");
        }
    }

    private void submitDownload(String chatId, DownloadTask task) {
        UserSession session = userSessions.computeIfAbsent(chatId, k -> new UserSession());

        synchronized (session) {
            session.queue.add(task);

            int queueSize = session.queue.size();
            if (session.processing) {
                // Already processing — show queued status
                int ahead = queueSize - 1;
                String text = "Queued (" + ahead + " ahead)...";
                InlineKeyboardMarkup cancelKeyboard = buildCancelKeyboard(queueSize + 1);
                editMessageWithKeyboard(chatId, task.statusMessageId(), text, cancelKeyboard);
                return;
            }

            session.processing = true;
            session.cancelled.set(false);
        }

        downloadExecutor.submit(() -> processQueue(chatId, session));
    }

    private void processQueue(String chatId, UserSession session) {
        try {
            while (true) {
                DownloadTask task;
                synchronized (session) {
                    if (session.cancelled.get()) {
                        // Cancel any tasks that were added after the cancel signal
                        DownloadTask remaining;
                        while ((remaining = session.queue.poll()) != null) {
                            editMessage(chatId, remaining.statusMessageId(), "Download cancelled.");
                        }
                        return;
                    }
                    task = session.queue.poll();
                    if (task == null) {
                        return;
                    }
                }

                int totalTasks = session.queue.size() + 1;
                String statusText = "Downloading...";
                if (session.queue.size() > 0) {
                    statusText += " (" + session.queue.size() + " more in queue)";
                }
                InlineKeyboardMarkup cancelKeyboard = buildCancelKeyboard(totalTasks);
                editMessageWithKeyboard(chatId, task.statusMessageId(), statusText, cancelKeyboard);

                downloadAndSend(chatId, task.statusMessageId(), task.url(), task.formatSelector(), session);
            }
        } finally {
            synchronized (session) {
                session.processing = false;
            }
            session.activeProcess.set(null);
            session.cancelled.set(false);
        }
    }

    private void downloadAndSend(String chatId, int statusMessageId, String url,
                                  String formatSelector, UserSession session) {
        File videoFile = null;
        try {
            videoFile = downloader.download(url, formatSelector, session.activeProcess);

            if (session.cancelled.get()) {
                editMessage(chatId, statusMessageId, "Download cancelled.");
                return;
            }

            long sizeMb = videoFile.length() / (1024 * 1024);

            if (sizeMb >= MAX_FILE_SIZE_MB) {
                editMessage(chatId, statusMessageId,
                        "Video is " + sizeMb + " MB (limit " + MAX_FILE_SIZE_MB + " MB). Too large to send.");
                return;
            }

            sendVideo(chatId, videoFile);
            editMessage(chatId, statusMessageId, "Video sent (" + sizeMb + " MB).");
        } catch (Exception e) {
            if (session.cancelled.get()) {
                editMessage(chatId, statusMessageId, "Download cancelled.");
            } else {
                log.error("Failed to download video: {}", url, e);
                editMessage(chatId, statusMessageId, "Failed to download the video. Please check the link and try again.");
            }
        } finally {
            session.activeProcess.set(null);
            downloader.cleanup(videoFile);
        }
    }

    private InlineKeyboardMarkup buildCancelKeyboard(int totalTasks) {
        String label = totalTasks > 1 ? "Cancel all (" + totalTasks + ")" : "Cancel download";
        InlineKeyboardButton cancelButton = InlineKeyboardButton.builder()
                .text(label)
                .callbackData("dl:cancel")
                .build();
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(List.of(cancelButton)))
                .build();
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

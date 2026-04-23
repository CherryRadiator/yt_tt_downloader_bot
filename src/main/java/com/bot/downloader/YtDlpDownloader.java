package com.bot.downloader;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

public class YtDlpDownloader {

    private static final Logger log = LoggerFactory.getLogger(YtDlpDownloader.class);

    private static final Path SHARED_DIR = Path.of("/tmp/shared");
    private static final String DEFAULT_FORMAT = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best[ext=mp4]/best";

    // Path where GitHub Actions writes the decoded cookies file on the VPS.
    // Override by setting the YT_COOKIES_PATH environment variable.
    private static final String COOKIES_PATH = System.getenv().getOrDefault(
            "YT_COOKIES_PATH", "/opt/bot/yt-cookies.txt"
    );

    public record FormatInfo(int height, long estimatedSizeMb) {}

    /** Appends --cookies <path> to the command if the cookies file exists. */
    private void addCookiesIfPresent(List<String> command) {
        File cookiesFile = new File(COOKIES_PATH);
        if (cookiesFile.exists() && cookiesFile.isFile()) {
            command.add("--cookies");
            command.add(COOKIES_PATH);
            log.debug("Using cookies file: {}", COOKIES_PATH);
        } else {
            log.debug("No cookies file found at {}, proceeding without authentication", COOKIES_PATH);
        }
    }

    public List<FormatInfo> fetchAvailableFormats(String url) {
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    "yt-dlp",
                    "--dump-json",
                    "--no-playlist",
                    "--no-download",
                    "--remote-components", "ejs:github"
            ));
            addCookiesIfPresent(cmd);
            cmd.add(url);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);

            log.info("Probing formats for: {}", url);
            Process process = pb.start();

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String errLine;
                    while ((errLine = errReader.readLine()) != null) {
                        log.debug("yt-dlp probe stderr: {}", errLine);
                    }
                } catch (IOException ignored) {}
            });
            stderrThread.setDaemon(true);
            stderrThread.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            stderrThread.join(5000);

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("yt-dlp --dump-json exited with code {}", exitCode);
                return List.of();
            }

            JSONObject json = new JSONObject(output.toString());
            JSONArray formats = json.optJSONArray("formats");
            if (formats == null) {
                return List.of();
            }

            // Find the best audio size (we'll add it to each video estimate)
            long bestAudioBytes = 0;
            for (int i = 0; i < formats.length(); i++) {
                JSONObject fmt = formats.getJSONObject(i);
                String acodec = fmt.optString("acodec", "none");
                String vcodec = fmt.optString("vcodec", "none");
                if (!"none".equals(acodec) && "none".equals(vcodec)) {
                    long size = getFormatSize(fmt);
                    if (size > bestAudioBytes) {
                        bestAudioBytes = size;
                    }
                }
            }

            // For each height, keep the largest video stream size (best quality at that height)
            TreeMap<Integer, Long> heightToSize = new TreeMap<>();
            for (int i = 0; i < formats.length(); i++) {
                JSONObject fmt = formats.getJSONObject(i);
                String vcodec = fmt.optString("vcodec", "none");
                if ("none".equals(vcodec)) {
                    continue;
                }
                int height = fmt.optInt("height", 0);
                if (height <= 0) {
                    continue;
                }
                long size = getFormatSize(fmt);
                heightToSize.merge(height, size, Math::max);
            }

            List<FormatInfo> result = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : heightToSize.entrySet()) {
                long totalBytes = entry.getValue() + bestAudioBytes;
                long totalMb = totalBytes / (1024 * 1024);
                result.add(new FormatInfo(entry.getKey(), totalMb));
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to probe formats for: {}", url, e);
            return List.of();
        }
    }

    private long getFormatSize(JSONObject fmt) {
        long size = fmt.optLong("filesize", 0);
        if (size > 0) return size;
        return fmt.optLong("filesize_approx", 0);
    }

    @SuppressWarnings("unused") // public API convenience overload
    public File download(String url) throws IOException, InterruptedException {
        return download(url, null);
    }

    public File download(String url, String formatSelector) throws IOException, InterruptedException {
        return download(url, formatSelector, new AtomicReference<>());
    }

    public File download(String url, String formatSelector, AtomicReference<Process> processRef)
            throws IOException, InterruptedException {
        Files.createDirectories(SHARED_DIR);
        Path tempDir = Files.createTempDirectory(SHARED_DIR, "yt-dlp-");
        String outputTemplate = tempDir.resolve("%(title).80s.%(ext)s").toString();

        String format = formatSelector != null ? formatSelector : DEFAULT_FORMAT;

        List<String> cmd = new ArrayList<>(List.of(
                "yt-dlp",
                "-f", format,
                "--merge-output-format", "mp4",
                "--remote-components", "ejs:github",
                "--no-playlist"
        ));
        addCookiesIfPresent(cmd);
        cmd.addAll(List.of("-o", outputTemplate, url));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        log.info("Starting download: {} with format: {}", url, format);
        Process process = pb.start();
        processRef.set(process);

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("yt-dlp: {}", line);
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            deleteDirectory(tempDir);
            throw new IOException("yt-dlp exited with code " + exitCode + "\n" + output);
        }

        File[] files = tempDir.toFile().listFiles();
        if (files == null || files.length == 0) {
            deleteDirectory(tempDir);
            throw new IOException("yt-dlp produced no output files");
        }

        log.info("Download complete: {}", files[0].getName());
        return files[0];
    }

    public static String buildFormatSelector(int height) {
        return "bestvideo[height<=" + height + "][ext=mp4]+bestaudio[ext=m4a]"
                + "/bestvideo[height<=" + height + "]+bestaudio"
                + "/best[height<=" + height + "][ext=mp4]"
                + "/best[height<=" + height + "]";
    }

    public void cleanup(File file) {
        if (file == null) return;
        try {
            Path dir = file.getParentFile().toPath();
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("Failed to clean up temp files", e);
        }
    }

    private void deleteDirectory(Path dir) {
        try {
            File[] files = dir.toFile().listFiles();
            if (files != null) {
                for (File f : files) {
                    try {
                        Files.deleteIfExists(f.toPath());
                    } catch (IOException e) {
                        log.warn("Failed to delete file: {}", f, e);
                    }
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("Failed to delete temp directory: {}", dir, e);
        }
    }
}
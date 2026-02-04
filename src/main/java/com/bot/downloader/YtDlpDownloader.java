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

public class YtDlpDownloader {

    private static final Logger log = LoggerFactory.getLogger(YtDlpDownloader.class);

    private static final Path SHARED_DIR = Path.of("/tmp/shared");
    private static final String DEFAULT_FORMAT = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best";

    public record FormatInfo(int height, long estimatedSizeMb) {}

    public List<FormatInfo> fetchAvailableFormats(String url) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "--dump-json",
                    "--no-playlist",
                    "--no-download",
                    "--remote-components", "ejs:github",
                    url
            );
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

    public File download(String url) throws IOException, InterruptedException {
        return download(url, null);
    }

    public File download(String url, String formatSelector) throws IOException, InterruptedException {
        Files.createDirectories(SHARED_DIR);
        Path tempDir = Files.createTempDirectory(SHARED_DIR, "yt-dlp-");
        String outputTemplate = tempDir.resolve("%(title).80s.%(ext)s").toString();

        String format = formatSelector != null ? formatSelector : DEFAULT_FORMAT;

        ProcessBuilder pb = new ProcessBuilder(
                "yt-dlp",
                "-f", format,
                "--merge-output-format", "mp4",
                "--remote-components", "ejs:github",
                "--no-playlist",
                "-o", outputTemplate,
                url
        );
        pb.redirectErrorStream(true);

        log.info("Starting download: {} with format: {}", url, format);
        Process process = pb.start();

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
                + "/best[height<=" + height + "][ext=mp4]"
                + "/best[height<=" + height + "]";
    }

    public void cleanup(File file) {
        if (file == null) return;
        try {
            Path dir = file.getParentFile().toPath();
            file.delete();
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
                    f.delete();
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("Failed to delete temp directory: {}", dir, e);
        }
    }
}

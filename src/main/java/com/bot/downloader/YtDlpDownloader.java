package com.bot.downloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

public class YtDlpDownloader {

    private static final Logger log = LoggerFactory.getLogger(YtDlpDownloader.class);

    public File download(String url) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("yt-dlp-");
        String outputTemplate = tempDir.resolve("%(title).80s.%(ext)s").toString();

        ProcessBuilder pb = new ProcessBuilder(
                "yt-dlp",
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "--merge-output-format", "mp4",
                "--remote-components", "ejs:github",
                "-o", outputTemplate,
                "--no-playlist",
                url
        );
        pb.redirectErrorStream(true);

        log.info("Starting download: {}", url);
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

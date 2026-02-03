package com.bot.util;

import java.util.regex.Pattern;

public class UrlValidator {

    private static final Pattern YOUTUBE_PATTERN = Pattern.compile(
            "https?://(www\\.)?(youtube\\.com/(watch|shorts)|youtu\\.be/)[\\w?=&/.-]+"
    );

    private static final Pattern TIKTOK_PATTERN = Pattern.compile(
            "https?://(www\\.|vm\\.)?(tiktok\\.com/)[\\w@./?=&-]+"
    );

    public static boolean isYouTubeUrl(String url) {
        return YOUTUBE_PATTERN.matcher(url).find();
    }

    public static boolean isTikTokUrl(String url) {
        return TIKTOK_PATTERN.matcher(url).find();
    }

    public static boolean isSupportedUrl(String url) {
        return isYouTubeUrl(url) || isTikTokUrl(url);
    }

    public static String extractUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] words = text.trim().split("\\s+");
        for (String word : words) {
            if (isSupportedUrl(word)) {
                return word;
            }
        }
        return null;
    }
}

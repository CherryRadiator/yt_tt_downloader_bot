package com.bot.util;

import java.util.regex.Pattern;

public class UrlValidator {

    private static final Pattern YOUTUBE_PATTERN = Pattern.compile(
            "https?://(www\\.)?(youtube\\.com/(watch|shorts)|youtu\\.be/)[\\w?=&/.-]+"
    );

    private static final Pattern TIKTOK_PATTERN = Pattern.compile(
            "https?://(www\\.|vm\\.|vt\\.)?(tiktok\\.com/)[\\w@./?=&-]+"
    );

    private static final Pattern PINTEREST_PATTERN = Pattern.compile(
            "https?://(www\\.|[a-z]{2}\\.)?pinterest\\.(com|co\\.uk|ca|de|fr|it|es|jp|ru|com\\.au|at|ch|cl|com\\.mx|co\\.kr|nz|ie|pt|ph|se|dk|com\\.br)/pin/[\\w/?=&-]+"
    );

    private static final Pattern PINTEREST_SHORT_PATTERN = Pattern.compile(
            "https?://pin\\.it/[\\w-]+"
    );

    public static boolean isYouTubeUrl(String url) {
        return YOUTUBE_PATTERN.matcher(url).find();
    }

    public static boolean isTikTokUrl(String url) {
        return TIKTOK_PATTERN.matcher(url).find();
    }

    public static boolean isPinterestUrl(String url) {
        return PINTEREST_PATTERN.matcher(url).find() || PINTEREST_SHORT_PATTERN.matcher(url).find();
    }

    public static boolean isSupportedUrl(String url) {
        return isYouTubeUrl(url) || isTikTokUrl(url) || isPinterestUrl(url);
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

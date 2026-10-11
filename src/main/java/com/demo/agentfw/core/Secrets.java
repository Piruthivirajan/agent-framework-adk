package com.demo.agentfw.core;

import java.util.regex.Pattern;

/** Masks anything that looks like a Google API key before text goes to a log line or an API response. */
public final class Secrets {

    private static final Pattern GOOGLE_API_KEY = Pattern.compile("AIza[0-9A-Za-z_-]{20,}");

    private Secrets() {
    }

    public static String mask(String text) {
        return text == null ? null : GOOGLE_API_KEY.matcher(text).replaceAll("***");
    }
}

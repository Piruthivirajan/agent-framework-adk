package com.demo.agentfw.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Masks secrets before text goes to a log line, an API response, the trace or the language model.
 *
 * <p>Two kinds: anything that looks like a Google API key (by pattern), and the exact values that
 * were registered with {@link #track} (the credentials of the configured MCP servers). Registered values
 * are replaced wherever they occur, longest first, so a value that contains another one is removed whole.
 * A value shorter than {@value #MIN_KNOWN_CHARS} characters is not registered: replacing three
 * characters would damage ordinary text more than it protects.
 */
public final class Secrets {

    static final int MIN_KNOWN_CHARS = 4;
    private static final String MASK = "***";

    private static final Pattern GOOGLE_API_KEY = Pattern.compile("AIza[0-9A-Za-z_-]{20,}");

    /** Registered values per owner (weakly held, so a discarded registry takes its values with it). */
    private static final Map<Object, List<String>> TRACKED = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile String[] known = new String[0];     // all tracked values, longest first

    private Secrets() {
    }

    /** Registers the secret values of {@code owner}, replacing what it registered before. */
    public static void track(Object owner, Collection<String> values) {
        List<String> cleaned = values.stream()
                .filter(v -> v != null && v.strip().length() >= MIN_KNOWN_CHARS)
                .map(String::strip).distinct().toList();
        synchronized (TRACKED) {
            if (cleaned.isEmpty()) {
                TRACKED.remove(owner);
            } else {
                TRACKED.put(owner, cleaned);
            }
            known = TRACKED.values().stream().flatMap(List::stream).distinct()
                    .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                    .toArray(String[]::new);
        }
    }

    public static void untrack(Object owner) {
        track(owner, List.of());
    }

    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String out = GOOGLE_API_KEY.matcher(text).replaceAll(MASK);
        for (String secret : known) {
            if (out.contains(secret)) {
                out = out.replace(secret, MASK);
            }
        }
        return out;
    }

    /** {@link #mask} applied to every string in nested maps and lists (a tool result); other values as they are. */
    public static Object maskDeep(Object value) {
        if (value instanceof String s) {
            return mask(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(mask(String.valueOf(k)), maskDeep(v)));
            return out;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> out = new ArrayList<>();
            items.forEach(v -> out.add(maskDeep(v)));
            return out;
        }
        return value;
    }
}

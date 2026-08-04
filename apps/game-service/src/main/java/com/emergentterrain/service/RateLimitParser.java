package com.emergentterrain.service;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Phase 7 follow-on — provider-agnostic rate-limit recovery inference. Every
 * pool's {@code handleErrorResponse} consults this before falling back to a
 * provider-specific body shape, so any provider that *does* publish recovery
 * info via standard HTTP headers gets honoured automatically — even when its
 * documentation says otherwise.
 *
 * <p>Inspected in order:
 * <ol>
 *   <li>{@code Retry-After} — RFC 9110 §10.2.3: either delta-seconds or HTTP-date.
 *       Set by most rate-limited APIs (Groq's body equivalent, Anthropic's header,
 *       and increasingly Cerebras + NVIDIA on per-minute limits).</li>
 *   <li>{@code x-ratelimit-reset} — Unix-seconds-since-epoch at which the limit
 *       resets. Common on OpenAI-compatible APIs (Cerebras, NVIDIA NIM).</li>
 *   <li>{@code x-ratelimit-reset-after} — fractional seconds until reset. Used by
 *       a handful of providers (Discord, some OpenAI-compat shims).</li>
 * </ol>
 *
 * <p>Returns {@code OptionalLong.empty()} if no header gives a positive
 * recovery window — the caller then applies its provider-specific body fallback.
 */
public final class RateLimitParser {

    private RateLimitParser() {}

    /** Read order is canonical; later headers don't override earlier ones. */
    public static OptionalLong inferRetryAfterMs(HttpHeaders headers) {
        if (headers == null) return OptionalLong.empty();
        OptionalLong fromRetryAfter = fromRetryAfterHeader(headers);
        if (fromRetryAfter.isPresent()) return fromRetryAfter;
        OptionalLong fromReset = fromEpochResetHeader(headers);
        if (fromReset.isPresent()) return fromReset;
        return fromRelativeResetHeader(headers);
    }

    private static OptionalLong fromRetryAfterHeader(HttpHeaders headers) {
        Optional<String> v = firstHeaderCaseInsensitive(headers, "retry-after");
        if (v.isEmpty()) return OptionalLong.empty();
        String s = v.get().trim();
        // Form 1 — integer or fractional seconds.
        try {
            double secs = Double.parseDouble(s);
            if (secs > 0) return OptionalLong.of((long) (secs * 1000));
        } catch (NumberFormatException ignored) {}
        // Form 2 — RFC 1123 HTTP-date (e.g. "Wed, 21 Oct 2026 07:28:00 GMT").
        try {
            Instant when = DateTimeFormatter.RFC_1123_DATE_TIME.parse(s, Instant::from);
            long ms = when.toEpochMilli() - System.currentTimeMillis();
            if (ms > 0) return OptionalLong.of(ms);
        } catch (Exception ignored) {}
        return OptionalLong.empty();
    }

    private static OptionalLong fromEpochResetHeader(HttpHeaders headers) {
        Optional<String> v = firstHeaderCaseInsensitive(headers, "x-ratelimit-reset");
        if (v.isEmpty()) return OptionalLong.empty();
        try {
            // OpenAI-compat sends Unix-seconds (sometimes Unix-millis for newer shims).
            double raw = Double.parseDouble(v.get().trim());
            long candidateMs = raw > 1e12 ? (long) raw : (long) (raw * 1000);
            long ms = candidateMs - System.currentTimeMillis();
            if (ms > 0) return OptionalLong.of(ms);
        } catch (NumberFormatException ignored) {}
        return OptionalLong.empty();
    }

    private static OptionalLong fromRelativeResetHeader(HttpHeaders headers) {
        Optional<String> v = firstHeaderCaseInsensitive(headers, "x-ratelimit-reset-after");
        if (v.isEmpty()) return OptionalLong.empty();
        try {
            double secs = Double.parseDouble(v.get().trim());
            if (secs > 0) return OptionalLong.of((long) (secs * 1000));
        } catch (NumberFormatException ignored) {}
        return OptionalLong.empty();
    }

    /**
     * {@link HttpHeaders#firstValue(String)} is case-insensitive on JDK 17+, but
     * defensively scan both lower- and Mixed-case forms in case any shim returns
     * a case-sensitive map view.
     */
    private static Optional<String> firstHeaderCaseInsensitive(HttpHeaders headers, String name) {
        return headers.firstValue(name).or(() -> headers.firstValue(name.toLowerCase()));
    }
}

package de.cryptocraft;

import java.time.*;
import java.time.format.*;
import java.util.Optional;

final class RetryPolicy {
    static Optional<Long> retryAfter(String text, Instant now) {
        try {
            return Optional.of(Math.max(1, Long.parseLong(text.trim())));
        } catch (NumberFormatException ignored) {
            try {
                Instant at =
                        ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant();
                long millis = Duration.between(now, at).toMillis();
                return Optional.of(Math.max(1, (millis + 999) / 1000));
            } catch (DateTimeException | ArithmeticException invalid) {
                return Optional.empty();
            }
        }
    }

    static long delay(long backoff, long maximum, Optional<Long> retryAfter) {
        // The configured maximum limits our exponential delay, never the provider's Retry-After.
        return Math.max(1, Math.max(Math.min(backoff, maximum), retryAfter.orElse(1L)));
    }
}

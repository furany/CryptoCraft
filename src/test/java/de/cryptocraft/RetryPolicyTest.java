package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

class RetryPolicyTest {
    @Test
    void providerDelayIsNeverShortenedToConfiguredMaximum() {
        assertEquals(7200, RetryPolicy.delay(60, 3600, Optional.of(7200L)));
        assertEquals(3600, RetryPolicy.delay(7200, 3600, Optional.empty()));
    }

    @Test
    void parsesHttpDateAndRoundsUpRatherThanRetryingEarly() {
        Instant now = Instant.parse("2026-10-05T12:00:00.100Z");
        String date =
                DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        now.plusSeconds(60).atZone(ZoneOffset.UTC));
        assertEquals(60, RetryPolicy.retryAfter(date, now).orElseThrow());
        assertEquals(120, RetryPolicy.retryAfter("120", now).orElseThrow());
        assertTrue(RetryPolicy.retryAfter("invalid", now).isEmpty());
        assertEquals(1, RetryPolicy.retryAfter("-1", now).orElseThrow());
    }
}

package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

class PriceAlarmTest {
    private final Instant now = Instant.parse("2026-10-05T12:00:00Z");

    private PriceAlarm alarm(boolean above, boolean repeat) {
        return new PriceAlarm(
                "alarm",
                UUID.randomUUID(),
                new QuotePair("BTC", "bitcoin", "EUR"),
                above,
                new BigDecimal("100"),
                repeat,
                true,
                true,
                Instant.EPOCH,
                "");
    }

    @Test
    void oneShotDisablesAfterFirstMatchIncludingEquality() {
        var result = alarm(true, false).evaluate(new BigDecimal("100"), now, Duration.ofMinutes(5));
        assertTrue(result.fired());
        assertFalse(result.alarm().enabled());
        assertFalse(
                result.alarm()
                        .evaluate(
                                new BigDecimal("200"), now.plusSeconds(600), Duration.ofMinutes(5))
                        .fired());
    }

    @Test
    void repeatingAlertRequiresRearmAndCooldown() {
        var first = alarm(true, true).evaluate(new BigDecimal("101"), now, Duration.ofMinutes(5));
        assertTrue(first.fired());
        assertFalse(
                first.alarm()
                        .evaluate(
                                new BigDecimal("105"), now.plusSeconds(600), Duration.ofMinutes(5))
                        .fired());
        var rearmed =
                first.alarm()
                        .evaluate(new BigDecimal("99"), now.plusSeconds(10), Duration.ofMinutes(5));
        assertFalse(rearmed.fired());
        assertTrue(rearmed.alarm().armed());
        var tooSoon =
                rearmed.alarm()
                        .evaluate(
                                new BigDecimal("101"), now.plusSeconds(20), Duration.ofMinutes(5));
        assertFalse(tooSoon.fired());
        assertTrue(
                tooSoon.alarm()
                        .evaluate(
                                new BigDecimal("101"), now.plusSeconds(300), Duration.ofMinutes(5))
                        .fired());
    }

    @Test
    void belowMatchesInclusiveAndRearmsAbove() {
        var result = alarm(false, true).evaluate(new BigDecimal("100"), now, Duration.ZERO);
        assertTrue(result.fired());
        assertFalse(
                result.alarm()
                        .evaluate(new BigDecimal("99"), now.plusSeconds(1), Duration.ZERO)
                        .fired());
        assertTrue(
                result.alarm()
                        .evaluate(new BigDecimal("101"), now.plusSeconds(2), Duration.ZERO)
                        .alarm()
                        .armed());
    }

    @Test
    void invalidThresholdRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PriceAlarm(
                                "a",
                                UUID.randomUUID(),
                                new QuotePair("BTC", "bitcoin", "EUR"),
                                true,
                                BigDecimal.ZERO,
                                false,
                                true,
                                true,
                                Instant.EPOCH,
                                ""));
    }
}

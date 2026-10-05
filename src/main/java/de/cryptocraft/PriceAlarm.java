package de.cryptocraft;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

/** Repeating alerts rearm on the opposite side, rather than firing on every poll. */
public record PriceAlarm(
        String id,
        UUID owner,
        QuotePair pair,
        boolean above,
        BigDecimal threshold,
        boolean repeat,
        boolean armed,
        boolean enabled,
        Instant lastTriggered,
        String boardId) {
    public PriceAlarm {
        if (threshold.signum() <= 0
                || threshold.precision() > 32
                || Math.abs((long) threshold.scale()) > 18)
            throw new IllegalArgumentException(
                    "Threshold must be a positive decimal (max 18 decimal places)");
    }

    public boolean matches(BigDecimal price) {
        return above ? price.compareTo(threshold) >= 0 : price.compareTo(threshold) <= 0;
    }

    public Result evaluate(BigDecimal price, Instant now, Duration cooldown) {
        if (!enabled) return new Result(this, false);
        if (!matches(price))
            return new Result(
                    new PriceAlarm(
                            id,
                            owner,
                            pair,
                            above,
                            threshold,
                            repeat,
                            true,
                            true,
                            lastTriggered,
                            boardId),
                    false);
        if (!armed || now.isBefore(lastTriggered.plus(cooldown))) return new Result(this, false);
        return new Result(
                new PriceAlarm(
                        id, owner, pair, above, threshold, repeat, false, repeat, now, boardId),
                true);
    }

    public record Result(PriceAlarm alarm, boolean fired) {}
}

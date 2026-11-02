package de.cryptocraft;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Validated settings for the separate server-economy ledger. Fee rates are fractions, not percent. */
public record TradingSettings(
        Mode mode,
        String referenceCurrency,
        BigDecimal unitsPerReference,
        BigDecimal buyFeeRate,
        BigDecimal sellFeeRate,
        BigDecimal minimumOrderValue,
        BigDecimal maximumOrderValue,
        int moneyScale,
        Duration orderLifetime,
        Duration maximumQuoteAge,
        int maximumOpenOrdersPerPlayer) {
    public enum Mode {
        BROKER_UNLIMITED,
        BROKER_RESERVE,
        P2P;

        public static Mode parse(String text) {
            return switch (text.toLowerCase(Locale.ROOT).replace('_', '-')) {
                case "unlimited", "broker", "broker-unlimited" -> BROKER_UNLIMITED;
                case "reserve", "reserve-broker", "broker-reserve" -> BROKER_RESERVE;
                case "p2p" -> P2P;
                default -> throw new IllegalArgumentException("trading.mode: unlimited|reserve|p2p");
            };
        }
    }

    public TradingSettings {
        Objects.requireNonNull(mode, "mode");
        referenceCurrency = Objects.requireNonNull(referenceCurrency, "referenceCurrency")
                .toUpperCase(Locale.ROOT);
        if (!referenceCurrency.matches("[A-Z0-9]{2,12}"))
            throw new IllegalArgumentException("Invalid trading reference currency");
        positive(unitsPerReference, "unitsPerReference");
        fee(buyFeeRate, "buyFeeRate");
        fee(sellFeeRate, "sellFeeRate");
        positive(minimumOrderValue, "minimumOrderValue");
        positive(maximumOrderValue, "maximumOrderValue");
        if (maximumOrderValue.compareTo(minimumOrderValue) < 0)
            throw new IllegalArgumentException("maximumOrderValue must cover minimumOrderValue");
        if (moneyScale < 0 || moneyScale > 12)
            throw new IllegalArgumentException("moneyScale: 0..12");
        if (orderLifetime == null || orderLifetime.compareTo(Duration.ofSeconds(1)) < 0
                || orderLifetime.compareTo(Duration.ofDays(30)) > 0)
            throw new IllegalArgumentException("orderLifetime: 1 second..30 days");
        if (maximumQuoteAge == null || maximumQuoteAge.compareTo(Duration.ofSeconds(1)) < 0
                || maximumQuoteAge.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("maximumQuoteAge: 1 second..1 hour");
        if (maximumOpenOrdersPerPlayer < 1 || maximumOpenOrdersPerPlayer > 1000)
            throw new IllegalArgumentException("maximumOpenOrdersPerPlayer: 1..1000");
    }

    public static TradingSettings defaults() {
        return new TradingSettings(Mode.BROKER_UNLIMITED, "EUR", BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1"),
                new BigDecimal("1000000"), 2, Duration.ofMinutes(5), Duration.ofMinutes(2), 20);
    }

    private static void positive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0 || value.precision() > 28
                || Math.abs((long) value.scale()) > 12)
            throw new IllegalArgumentException(name + ": positive decimal with at most 12 decimal places");
    }

    private static void fee(BigDecimal value, String name) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0
                || Math.abs((long) value.scale()) > 8)
            throw new IllegalArgumentException(name + ": fraction >= 0 and < 1, at most 8 decimal places");
    }
}

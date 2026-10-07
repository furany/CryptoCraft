package de.cryptocraft;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;

final class ConfigValidator {
    static List<String> validate(org.bukkit.configuration.ConfigurationSection config) {
        List<String> errors = new ArrayList<>();
        range(config, errors, "prices.refresh-interval-seconds", 300, 5, 86400);
        range(config, errors, "prices.minimum-request-interval-seconds", 60, 1, 86400);
        range(config, errors, "prices.request-timeout-seconds", 20, 1, 120);
        range(config, errors, "prices.stale-after-minutes", 10, 1, 10080);
        range(config, errors, "prices.history-retention-days", 7, 1, 30);
        range(config, errors, "boards.default-limit-per-player", 1, 0, 10000);
        range(config, errors, "boards.display-height", 1.35, 0.5, 8);
        range(config, errors, "boards.chart-history-hours", 24, 1, 168);
        range(config, errors, "players.watchlist-limit", 20, 1, 100);
        range(config, errors, "players.alarm-limit", 20, 1, 100);
        range(config, errors, "players.alarm-cooldown-seconds", 300, 1, 86400);
        range(config, errors, "prices.backfill.days", 7, 1, 30);
        range(config, errors, "prices.backfill.maximum-requests-per-hour", 6, 1, 60);
        for (String prefix : List.of("prices.retry", "prices.retry.access-error")) {
            String initial =
                    prefix.equals("prices.retry")
                            ? prefix + ".initial-delay-seconds"
                            : "prices.retry.access-error-initial-delay-seconds";
            String maximum =
                    prefix.equals("prices.retry")
                            ? prefix + ".maximum-delay-seconds"
                            : "prices.retry.access-error-maximum-delay-seconds";
            range(config, errors, initial, prefix.equals("prices.retry") ? 60 : 900, 1, 86400);
            range(config, errors, maximum, 3600, 1, 86400);
            if (config.getLong(maximum, 3600) < config.getLong(initial, 60))
                errors.add(maximum + " must be >= " + initial);
        }
        String mode = config.getString("api.auth-mode", "demo");
        if (!Set.of("demo", "pro").contains(mode)) errors.add("api.auth-mode: demo|pro");
        if ("pro".equals(mode) && config.getString("api.pro-key", "").isBlank())
            errors.add("api.pro-key is required in pro mode");
        validateUrl(config.getString("api.url", ""), "api.url", errors);
        String historyUrl = config.getString("prices.backfill.url", "");
        if (!historyUrl.isBlank())
            validateUrl(historyUrl.replace("{id}", "bitcoin"), "prices.backfill.url", errors);
        var coins = config.getConfigurationSection("coins");
        if (coins == null || coins.getKeys(false).isEmpty())
            errors.add("coins must contain at least one coin");
        else
            for (String symbol : coins.getKeys(false)) {
                try {
                    new QuotePair(symbol, coins.getString(symbol, ""), "EUR");
                } catch (IllegalArgumentException error) {
                    errors.add("coins." + symbol + ": invalid symbol or API ID");
                }
                if (!symbol.equals(symbol.toUpperCase(Locale.ROOT)))
                    errors.add("coins." + symbol + ": symbol must be uppercase");
            }
        List<String> currencies =
                config.getStringList("currencies").stream()
                        .map(s -> s.toUpperCase(Locale.ROOT))
                        .toList();
        if (currencies.isEmpty() || currencies.stream().anyMatch(s -> !s.matches("[A-Z0-9]{2,12}")))
            errors.add("currencies: invalid or empty list");
        if (!currencies.contains(
                config.getString("default-currency", "EUR").toUpperCase(Locale.ROOT)))
            errors.add("default-currency must be configured");
        if (config.getBoolean("portfolio.enabled", false)
                && !currencies.contains(
                        config.getString("portfolio.currency", "EUR").toUpperCase(Locale.ROOT)))
            errors.add("portfolio.currency must be configured");
        try {
            BigDecimal cash = new BigDecimal(config.getString("portfolio.starting-cash", "10000"));
            if (cash.signum() <= 0
                    || cash.compareTo(new BigDecimal("1000000000000")) > 0
                    || cash.precision() > 28
                    || Math.abs((long) cash.scale()) > 12)
                errors.add(
                        "portfolio.starting-cash: positive decimal <= 1000000000000, max 12 decimal"
                                + " places");
        } catch (NumberFormatException error) {
            errors.add("portfolio.starting-cash must be a decimal");
        }
        var limits = config.getConfigurationSection("boards.permission-limits");
        if (limits != null)
            limits.getValues(true)
                    .forEach(
                            (key, value) -> {
                                if (value instanceof org.bukkit.configuration.ConfigurationSection)
                                    return;
                                if (!(value instanceof Number number)
                                        || number.doubleValue() < 0
                                        || number.doubleValue() != number.intValue())
                                    errors.add(
                                            "boards.permission-limits."
                                                    + key
                                                    + ": nonnegative integer required");
                            });
        for (String key :
                List.of(
                        "prices.backfill.enabled",
                        "portfolio.enabled",
                        "players.redstone-enabled",
                        "integrations.worldguard",
                        "integrations.placeholderapi",
                        "integrations.placeholderapi-preload"))
            if (config.contains(key) && !config.isBoolean(key))
                errors.add(key + ": boolean required");
        return List.copyOf(errors);
    }

    private static void range(
            org.bukkit.configuration.ConfigurationSection config,
            List<String> errors,
            String path,
            double fallback,
            double min,
            double max) {
        Object raw = config.get(path, fallback);
        if (!(raw instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < min
                || number.doubleValue() > max
                || (!path.equals("boards.display-height")
                        && number.doubleValue() != Math.rint(number.doubleValue())))
            errors.add(path + ": " + min + ".." + max);
    }

    private static void validateUrl(String value, String path, List<String> errors) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) errors.add(path + ": HTTPS URL required");
        } catch (RuntimeException error) {
            errors.add(path + ": invalid URL");
        }
    }
}

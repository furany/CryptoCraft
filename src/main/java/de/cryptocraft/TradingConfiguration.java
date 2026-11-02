package de.cryptocraft;

import org.bukkit.configuration.ConfigurationSection;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** One parser shared by startup, reload validation and the economy adapter. */
final class TradingConfiguration {
    private TradingConfiguration() {}

    static TradingSettings settings(ConfigurationSection config, int providerDigits) {
        int digits = providerDigits < 0 ? config.getInt("trading.money-decimals", 2) : providerDigits;
        return new TradingSettings(
                TradingSettings.Mode.parse(config.getString("trading.mode", "unlimited")),
                config.getString("trading.reference-currency", "EUR"),
                decimal(config, "trading.units-per-reference", "1"),
                decimal(config, "trading.buy-fee-percent", "0").movePointLeft(2),
                decimal(config, "trading.sell-fee-percent", "0").movePointLeft(2),
                decimal(config, "trading.minimum-order-value", "1"),
                decimal(config, "trading.maximum-order-value", "1000000"), digits,
                Duration.ofSeconds(config.getLong("trading.order-lifetime-seconds", 300)),
                Duration.ofSeconds(config.getLong("trading.maximum-quote-age-seconds", 120)),
                config.getInt("trading.maximum-open-orders", 20));
    }

    static BigDecimal decimal(ConfigurationSection config, String path, String fallback) {
        try {
            BigDecimal value = new BigDecimal(String.valueOf(config.get(path, fallback)));
            if (value.precision() > 28 || Math.abs((long) value.scale()) > 12)
                throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + ": invalid decimal");
        }
    }

    static void validate(ConfigurationSection config, List<String> errors) {
        for (String key : List.of("trading.order-lifetime-seconds", "trading.maximum-quote-age-seconds",
                "trading.maximum-open-orders", "trading.money-decimals")) {
            long maximum = switch (key) {
                case "trading.order-lifetime-seconds" -> 2592000;
                case "trading.maximum-quote-age-seconds" -> 3600;
                case "trading.maximum-open-orders" -> 1000;
                default -> 12;
            };
            long minimum = key.equals("trading.money-decimals") ? 0 : 1;
            if (config.contains(key) && (!(config.get(key) instanceof Number value)
                    || !Double.isFinite(value.doubleValue()) || value.doubleValue() != Math.rint(value.doubleValue())
                    || value.doubleValue() < minimum || value.doubleValue() > maximum))
                errors.add(key + ": integer required");
        }
        try { settings(config, -1); } catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
        for (var entry : Map.of(
                "trading.access", List.of("terminals", "anywhere"),
                "trading.terminal-style", List.of("computer", "labeled")).entrySet()) {
            String fallback = entry.getValue().getFirst();
            if (!entry.getValue().contains(config.getString(entry.getKey(), fallback)))
                errors.add(entry.getKey() + ": " + String.join("|", entry.getValue()));
        }
        double radius = config.getDouble("trading.terminal-radius", 6);
        if (!(config.get("trading.terminal-radius", 6) instanceof Number)
                || !Double.isFinite(radius) || radius < 1 || radius > 64)
            errors.add("trading.terminal-radius: 1..64");
        try {
            var tolerance = decimal(config, "trading.price-tolerance-percent", "1");
            if (tolerance.signum() < 0 || tolerance.compareTo(new BigDecimal("50")) > 0)
                errors.add("trading.price-tolerance-percent: 0..50");
        } catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
        if (config.contains("trading.enabled") && !config.isBoolean("trading.enabled"))
            errors.add("trading.enabled: boolean required");
        String currency = config.getString("trading.reference-currency", "EUR");
        if (config.getStringList("currencies").stream().noneMatch(c -> c.equalsIgnoreCase(currency)))
            errors.add("trading.reference-currency must be configured in currencies");
        for (String key : List.of("trading.disabled-coins", "trading.display-disabled-coins")) {
            if (config.contains(key) && !config.isList(key)) errors.add(key + ": list required");
            for (Object value : config.getList(key, List.of()))
                if (!(value instanceof String symbol) || !symbol.matches("[A-Z0-9]{1,12}"))
                    errors.add(key + ": uppercase coin symbols required");
        }
    }
}

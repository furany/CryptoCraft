package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;

public final class CryptoPriceService {
    private static final Pattern OBJECT = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{([^{}]*)\\}");

    private final JavaPlugin plugin;
    private final CryptoBoardService boards;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile Instant retryNotBefore = Instant.EPOCH;
    private volatile Instant lastRequestAt = Instant.EPOCH;
    private volatile long backoffSeconds = 60;

    public CryptoPriceService(JavaPlugin plugin, CryptoBoardService boards) {
        this.plugin = plugin;
        this.boards = boards;
        this.backoffSeconds = Math.max(1, plugin.getConfig().getLong("prices.retry.initial-delay-seconds", 60));
    }

    public Quote getQuote(String coinId, String currency) {
        return quotes.get(coinId.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT));
    }

    public String displayText(CryptoBoard board) {
        String title = board.symbol() + " / " + board.currency();
        Quote quote = getQuote(board.coinId(), board.currency());
        if (quote == null) {
            return title + "\nLoading price...";
        }

        NumberFormat number = NumberFormat.getNumberInstance(Locale.US);
        number.setMinimumFractionDigits(2);
        number.setMaximumFractionDigits(2);
        String result = title + "\n" + number.format(quote.price()) + " " + board.currency();

        if (quote.change24h() != null) {
            DecimalFormat changeFormat = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US));
            String sign = quote.change24h().signum() > 0 ? "+" : "";
            result += "\n24h " + sign + changeFormat.format(quote.change24h()) + "%";
        }

        long staleAfterMinutes = Math.max(0, plugin.getConfig().getLong("prices.stale-after-minutes", 10));
        if (Duration.between(quote.updatedAt(), Instant.now()).toMinutes() > staleAfterMinutes) {
            result += "\nPrice data is stale";
        }
        return result;
    }

    public void refresh() {
        Instant now = Instant.now();
        long minimumRequestInterval = Math.max(0,
                plugin.getConfig().getLong("prices.minimum-request-interval-seconds", 60));
        if (now.isBefore(retryNotBefore)
                || Duration.between(lastRequestAt, now).toSeconds() < minimumRequestInterval
                || !inFlight.compareAndSet(false, true)) {
            return;
        }

        var activeBoards = boards.getBoards();
        if (activeBoards.isEmpty()) {
            inFlight.set(false);
            return;
        }
        lastRequestAt = now;

        Set<String> ids = new LinkedHashSet<>();
        Set<String> currencies = new LinkedHashSet<>();
        activeBoards.forEach(board -> {
            ids.add(board.coinId());
            currencies.add(board.currency().toLowerCase(Locale.ROOT));
        });

        try {
            String baseUrl = plugin.getConfig().getString("api.url", "https://api.coingecko.com/api/v3/simple/price");
            ConfigurationSection queryConfig = plugin.getConfig().getConfigurationSection("api.query");
            String idsParameter = getQueryParameterName(queryConfig, "coin-ids-parameter", "ids");
            String currenciesParameter = getQueryParameterName(queryConfig, "currencies-parameter", "vs_currencies");
            Map<String, String> queryParameters = new LinkedHashMap<>();
            if (!idsParameter.isEmpty()) {
                queryParameters.put(idsParameter, String.join(",", ids));
            }
            if (!currenciesParameter.isEmpty()) {
                queryParameters.put(currenciesParameter, String.join(",", currencies));
            }
            if (plugin.getConfig().getBoolean("api.query.include-24hr-change", true)) {
                queryParameters.put("include_24hr_change", "true");
            }
            if (plugin.getConfig().getBoolean("api.query.include-last-updated-at", true)) {
                queryParameters.put("include_last_updated_at", "true");
            }
            ConfigurationSection additionalParameters = plugin.getConfig()
                .getConfigurationSection("api.query.additional-parameters");
            if (additionalParameters != null) {
                for (String name : additionalParameters.getKeys(false)) {
                    Object rawValue = additionalParameters.getValues(false).get(name);
                    String value = rawValue == null ? null : String.valueOf(rawValue);
                    if (!name.isBlank() && value != null) {
                        queryParameters.putIfAbsent(name, value);
                    }
                }
            }

            String url = appendQuery(baseUrl, queryParameters);
            long requestTimeoutSeconds = Math.max(1,
                    plugin.getConfig().getLong("prices.request-timeout-seconds", 20));
            long initialBackoffSeconds = Math.max(1,
                    plugin.getConfig().getLong("prices.retry.initial-delay-seconds", 60));
            long maximumBackoffSeconds = Math.max(initialBackoffSeconds,
                    plugin.getConfig().getLong("prices.retry.maximum-delay-seconds", 3600));
            backoffSeconds = Math.max(initialBackoffSeconds,
                    Math.min(backoffSeconds, maximumBackoffSeconds));

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Accept", "application/json")
                    .GET();
            String configuredKey = plugin.getConfig().getString("api.demo-key");
            String demoKey = configuredKey == null ? "" : configuredKey.trim();
            if (!demoKey.isEmpty()) {
                requestBuilder.header("x-cg-demo-api-key", demoKey);
            }

            http.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    try {
                        if (error != null) {
                            plugin.getLogger().log(Level.WARNING, "Price request failed: " + error.getMessage());
                            return;
                        }
                        if (response.statusCode() == 429) {
                            applyBackoff(response, initialBackoffSeconds, maximumBackoffSeconds);
                            plugin.getLogger().warning("CoinGecko rate limit reached; keeping the last cached prices.");
                            return;
                        }
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            plugin.getLogger().warning("Price API returned HTTP " + response.statusCode()
                                    + "; keeping the last cached prices.");
                            return;
                        }
                        parseAndStore(response.body(), ids, currencies);
                        backoffSeconds = initialBackoffSeconds;
                        retryNotBefore = Instant.EPOCH;
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(Level.WARNING, "Could not process the price response.", exception);
                    } finally {
                        inFlight.set(false);
                        if (plugin.isEnabled()) {
                            Bukkit.getScheduler().runTask(plugin, () -> boards.refreshLoadedDisplays(this));
                        }
                    }
                });
        } catch (RuntimeException exception) {
            inFlight.set(false);
            plugin.getLogger().log(Level.WARNING, "Could not start the price request.", exception);
        }
    }

    private void applyBackoff(HttpResponse<?> response, long initialBackoffSeconds, long maximumBackoffSeconds) {
        long retryAfterSeconds = response.headers().firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        return java.util.Optional.of(Long.parseLong(value));
                    } catch (NumberFormatException ignored) {
                        return java.util.Optional.empty();
                    }
                })
                .orElse(backoffSeconds);
        long delaySeconds = Math.max(1, Math.min(maximumBackoffSeconds, retryAfterSeconds));
        retryNotBefore = Instant.now().plusSeconds(delaySeconds);
        long nextBackoff = delaySeconds > maximumBackoffSeconds / 2
                ? maximumBackoffSeconds
                : delaySeconds * 2;
        backoffSeconds = Math.max(initialBackoffSeconds, Math.min(maximumBackoffSeconds, nextBackoff));
    }

    private String getQueryParameterName(ConfigurationSection queryConfig, String key, String fallback) {
        if (queryConfig == null) {
            return fallback;
        }
        String value = queryConfig.getString(key, fallback);
        return value == null ? fallback : value.trim();
    }

    private String appendQuery(String baseUrl, Map<String, String> queryParameters) {
        StringBuilder result = new StringBuilder(baseUrl);
        String separator = baseUrl.endsWith("?") || baseUrl.endsWith("&")
                ? ""
                : baseUrl.contains("?") ? "&" : "?";
        for (Map.Entry<String, String> parameter : queryParameters.entrySet()) {
            result.append(separator)
                    .append(encode(parameter.getKey()))
                    .append('=')
                    .append(encode(parameter.getValue()));
            separator = "&";
        }
        return result.toString();
    }

    private void parseAndStore(String json, Set<String> ids, Set<String> currencies) {
        Map<String, String> objects = new HashMap<>();
        Matcher objectsMatcher = OBJECT.matcher(json);
        while (objectsMatcher.find()) {
            objects.put(objectsMatcher.group(1), objectsMatcher.group(2));
        }

        for (String id : ids) {
            String values = objects.get(id);
            if (values == null) {
                continue;
            }
            for (String currency : currencies) {
                BigDecimal price = readNumber(values, currency);
                if (price == null) {
                    continue;
                }
                BigDecimal change = readNumber(values, currency + "_24h_change");
                BigDecimal timestamp = readNumber(values, "last_updated_at");
                Instant updated = timestamp == null
                        ? Instant.now()
                        : Instant.ofEpochSecond(timestamp.longValue());
                quotes.put(id.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT),
                        new Quote(price, change, updated));
            }
        }
    }

    private BigDecimal readNumber(String jsonObject, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
        Matcher matcher = pattern.matcher(jsonObject);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new BigDecimal(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record Quote(BigDecimal price, BigDecimal change24h, Instant updatedAt) {
    }
}

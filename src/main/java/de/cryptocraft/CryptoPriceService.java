package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final File historyFile;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();
    private final Map<String, List<PricePoint>> history = new ConcurrentHashMap<>();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile Instant retryNotBefore = Instant.EPOCH;
    private volatile Instant lastRequestAt = Instant.EPOCH;
    private volatile long backoffSeconds = 60;

    public CryptoPriceService(JavaPlugin plugin, CryptoBoardService boards) {
        this.plugin = plugin;
        this.boards = boards;
        this.historyFile = new File(plugin.getDataFolder(), "history.yml");
        this.backoffSeconds = Math.max(1, plugin.getConfig().getLong("prices.retry.initial-delay-seconds", 60));
        loadHistory();
    }

    public Quote getQuote(String coinId, String currency) {
        return quotes.get(coinId.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT));
    }

    public List<PricePoint> getHistory(String coinId, String currency) {
        return history.getOrDefault(coinId.toLowerCase(Locale.ROOT) + ":"
                + currency.toLowerCase(Locale.ROOT), List.of());
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
                    Map<String, Quote> fetchedQuotes = Map.of();
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
                        fetchedQuotes = parseAndStore(response.body(), ids, currencies);
                        quotes.putAll(fetchedQuotes);
                        backoffSeconds = initialBackoffSeconds;
                        retryNotBefore = Instant.EPOCH;
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(Level.WARNING, "Could not process the price response.", exception);
                    } finally {
                        inFlight.set(false);
                        if (plugin.isEnabled()) {
                            Map<String, Quote> successfulQuotes = fetchedQuotes;
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (!successfulQuotes.isEmpty()) {
                                    recordHistory(successfulQuotes);
                                    saveHistory();
                                }
                                boards.refreshLoadedDisplays(this);
                            });
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

    private Map<String, Quote> parseAndStore(String json, Set<String> ids, Set<String> currencies) {
        Map<String, String> objects = new HashMap<>();
        Matcher objectsMatcher = OBJECT.matcher(json);
        while (objectsMatcher.find()) {
            objects.put(objectsMatcher.group(1), objectsMatcher.group(2));
        }

        Map<String, Quote> fetchedQuotes = new HashMap<>();
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
                fetchedQuotes.put(id.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT),
                        new Quote(price, change, updated));
            }
        }
        return fetchedQuotes;
    }

    private void recordHistory(Map<String, Quote> fetchedQuotes) {
        Instant observedAt = Instant.now();
        long retentionDays = Math.max(1, Math.min(30,
                plugin.getConfig().getLong("prices.history-retention-days", 7)));
        Instant cutoff = observedAt.minus(Duration.ofDays(retentionDays));
        for (String quoteKey : history.keySet()) {
            history.computeIfPresent(quoteKey, (key, existing) -> {
                List<PricePoint> retained = existing.stream()
                        .filter(point -> !point.observedAt().isBefore(cutoff))
                        .toList();
                return retained.isEmpty() ? null : retained;
            });
        }
        for (Map.Entry<String, Quote> entry : fetchedQuotes.entrySet()) {
            history.compute(entry.getKey(), (key, existing) -> {
                List<PricePoint> updated = new ArrayList<>(existing == null ? List.of() : existing);
                updated.removeIf(point -> point.observedAt().isBefore(cutoff));
                updated.add(new PricePoint(observedAt, entry.getValue().price()));
                return List.copyOf(updated);
            });
        }
    }

    private void loadHistory() {
        if (!historyFile.exists()) {
            return;
        }
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(historyFile);
        ConfigurationSection section = saved.getConfigurationSection("history");
        if (section == null) {
            return;
        }

        long retentionDays = Math.max(1, Math.min(30,
                plugin.getConfig().getLong("prices.history-retention-days", 7)));
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        for (String entryId : section.getKeys(false)) {
            String base = "history." + entryId + ".";
            String coinId = saved.getString(base + "coin-id");
            String currency = saved.getString(base + "currency");
            if (coinId == null || currency == null) {
                continue;
            }

            List<PricePoint> points = new ArrayList<>();
            for (String encodedPoint : saved.getStringList(base + "points")) {
                int separator = encodedPoint.indexOf('|');
                if (separator < 1) {
                    continue;
                }
                try {
                    Instant observedAt = Instant.ofEpochMilli(Long.parseLong(encodedPoint.substring(0, separator)));
                    BigDecimal price = new BigDecimal(encodedPoint.substring(separator + 1));
                    if (!observedAt.isBefore(cutoff)) {
                        points.add(new PricePoint(observedAt, price));
                    }
                } catch (RuntimeException ignored) {
                    plugin.getLogger().warning("Skipping invalid saved price history point for " + coinId + ".");
                }
            }

            if (!points.isEmpty()) {
                points.sort((left, right) -> left.observedAt().compareTo(right.observedAt()));
                String quoteKey = coinId.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT);
                List<PricePoint> loadedPoints = List.copyOf(points);
                history.put(quoteKey, loadedPoints);
                PricePoint latest = loadedPoints.get(loadedPoints.size() - 1);
                quotes.put(quoteKey, new Quote(latest.price(), null, latest.observedAt()));
            }
        }
    }

    private void saveHistory() {
        YamlConfiguration saved = new YamlConfiguration();
        for (Map.Entry<String, List<PricePoint>> entry : history.entrySet()) {
            int separator = entry.getKey().lastIndexOf(':');
            if (separator < 1) {
                continue;
            }
            String coinId = entry.getKey().substring(0, separator);
            String currency = entry.getKey().substring(separator + 1);
            String entryId = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8));
            String base = "history." + entryId + ".";
            saved.set(base + "coin-id", coinId);
            saved.set(base + "currency", currency);
            saved.set(base + "points", entry.getValue().stream()
                    .map(point -> point.observedAt().toEpochMilli() + "|" + point.price().toPlainString())
                    .toList());
        }
        try {
            saved.save(historyFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save price history.", exception);
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

    public record PricePoint(Instant observedAt, BigDecimal price) {
    }
}

package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class CryptoPriceService {
    private static final String DEMO_ENDPOINT = "https://api.coingecko.com/api/v3/simple/price";
    private static final String PRO_ENDPOINT = "https://pro-api.coingecko.com/api/v3/simple/price";
    private static final int MAX_ERROR_DETAIL_LENGTH = 280;
    private static final Pattern SENSITIVE_QUERY_PARAMETER =
            Pattern.compile("(?i)([?&][^&=]*(?:key|token|secret)[^&=]*=)[^&\\s)]+");

    private final CryptoCraftPlugin plugin;
    private final CryptoBoardService boards;
    private final File historyFile;
    private final HttpClient http;
    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();
    private final Map<String, List<PricePoint>> history = new ConcurrentHashMap<>();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile Instant retryNotBefore = Instant.EPOCH;
    private volatile Instant lastRequestAt = Instant.EPOCH;
    private volatile long rateLimitBackoffSeconds = 60;
    private volatile long accessErrorBackoffSeconds = 900;
    private volatile long serverErrorBackoffSeconds = 60;
    private volatile String lastLoggedFailure;
    private volatile boolean apiConfigurationWarningLogged;
    private volatile Instant lastSuccessAt = Instant.EPOCH;
    private long historyRevision;

    public long historyRevision() {
        return historyRevision;
    }

    private volatile long staleMinutes = 10;
    private final Map<String, Instant> demand = new HashMap<>();
    private final Set<String> backfilled = new HashSet<>();
    private final Map<String, Instant> backfillRetry = new HashMap<>();
    private final Deque<Instant> backfillRequests = new ArrayDeque<>();
    private Instant lastPriceRequestAt = Instant.EPOCH;
    private java.util.concurrent.CompletableFuture<HttpResponse<String>> request;
    private int generation;
    private volatile boolean closed;

    public CryptoPriceService(CryptoCraftPlugin plugin, CryptoBoardService boards) {
        this(plugin, boards, HttpClient.newHttpClient());
    }

    CryptoPriceService(CryptoCraftPlugin plugin, CryptoBoardService boards, HttpClient http) {
        this.plugin = plugin;
        this.boards = boards;
        this.http = http;
        this.historyFile = new File(plugin.getDataFolder(), "history.yml");
        resetRetryState();
        try {
            loadHistory();
        } catch (RuntimeException error) {
            http.shutdownNow();
            throw error;
        }
    }

    public Quote getQuote(String coinId, String currency) {
        return quotes.get(
                coinId.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT));
    }

    public List<PricePoint> getHistory(String coinId, String currency) {
        return history.getOrDefault(
                coinId.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT),
                List.of());
    }

    public void refresh() {
        if (closed) return;
        Instant now = Instant.now();
        long minimumRequestInterval =
                Math.max(
                        0,
                        plugin.getConfig().getLong("prices.minimum-request-interval-seconds", 60));
        if (now.isBefore(retryNotBefore)) {
            return;
        }
        if (Duration.between(lastRequestAt, now).toSeconds() < minimumRequestInterval
                || !inFlight.compareAndSet(false, true)) {
            return;
        }

        Set<String> pairs = desiredPairs();
        if (pairs.isEmpty()) {
            inFlight.set(false);
            return;
        }

        String authMode = getAuthMode();
        String apiKey = getApiKey(authMode);
        if (!isValidAuthentication(authMode, apiKey)) {
            if (!apiConfigurationWarningLogged) {
                plugin.getLogger().warning(authenticationConfigurationError(authMode, apiKey));
                apiConfigurationWarningLogged = true;
            }
            inFlight.set(false);
            return;
        }
        apiConfigurationWarningLogged = false;
        lastRequestAt = now;

        Set<String> ids = new LinkedHashSet<>();
        Set<String> currencies = new LinkedHashSet<>();
        for (String pair : pairs) {
            int separator = pair.lastIndexOf(':');
            ids.add(pair.substring(0, separator));
            currencies.add(pair.substring(separator + 1));
        }
        lastPriceRequestAt = now;

        try {
            String baseUrl = getEndpoint(authMode);
            ConfigurationSection queryConfig =
                    plugin.getConfig().getConfigurationSection("api.query");
            String idsParameter = getQueryParameterName(queryConfig, "coin-ids-parameter", "ids");
            String currenciesParameter =
                    getQueryParameterName(queryConfig, "currencies-parameter", "vs_currencies");
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
            ConfigurationSection additionalParameters =
                    plugin.getConfig().getConfigurationSection("api.query.additional-parameters");
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
            long requestTimeoutSeconds =
                    Math.max(1, plugin.getConfig().getLong("prices.request-timeout-seconds", 20));
            long initialBackoffSeconds =
                    Math.max(
                            1,
                            plugin.getConfig().getLong("prices.retry.initial-delay-seconds", 60));
            long maximumBackoffSeconds =
                    Math.max(
                            initialBackoffSeconds,
                            plugin.getConfig().getLong("prices.retry.maximum-delay-seconds", 3600));
            HttpRequest.Builder requestBuilder =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                            .header("Accept", "application/json")
                            .header(
                                    "User-Agent",
                                    "CryptoCraft/"
                                            + plugin.getDescription().getVersion()
                                            + " (+https://github.com/furany/CryptoCraft)")
                            .GET();
            if (!apiKey.isEmpty()) {
                requestBuilder.header(
                        "pro".equals(authMode) ? "x-cg-pro-api-key" : "x-cg-demo-api-key", apiKey);
            }

            int version = generation;
            request = http.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            request.whenComplete(
                    (response, error) ->
                            onMain(
                                    () -> {
                                        if (version != generation || closed) return;
                                        try {
                                            if (!acceptResponse(
                                                    response,
                                                    error,
                                                    initialBackoffSeconds,
                                                    maximumBackoffSeconds)) return;
                                            Map<String, Quote> fetched =
                                                    QuoteCodec.prices(
                                                            response.body(),
                                                            ids,
                                                            currencies,
                                                            Instant.now());
                                            Map<String, Quote> accepted = new HashMap<>();
                                            fetched.forEach(
                                                    (key, quote) -> {
                                                        Quote current = quotes.get(key);
                                                        if (current == null
                                                                || !quote.updatedAt()
                                                                        .isBefore(
                                                                                current
                                                                                        .updatedAt())) {
                                                            quotes.put(key, quote);
                                                            accepted.put(key, quote);
                                                        }
                                                    });
                                            recordHistory(accepted);
                                            saveHistory();
                                            lastSuccessAt = Instant.now();
                                            recovered();
                                            if (plugin.features() != null)
                                                plugin.features().evaluate(accepted);
                                        } catch (RuntimeException exception) {
                                            long delay =
                                                    applyServerErrorBackoff(
                                                            initialBackoffSeconds,
                                                            maximumBackoffSeconds);
                                            logTransportFailure(exception, delay);
                                        } finally {
                                            inFlight.set(false);
                                            boards.refreshLoadedDisplays(this);
                                        }
                                    }));
        } catch (RuntimeException exception) {
            inFlight.set(false);
            plugin.getLogger().log(Level.WARNING, "Could not start the price request.", exception);
        }
    }

    public void resetRetryState() {
        generation++;
        if (request != null) request.cancel(true);
        inFlight.set(false);
        staleMinutes = Math.max(1, plugin.getConfig().getLong("prices.stale-after-minutes", 10));
        retryNotBefore = Instant.EPOCH;
        resetBackoffDelays();
        lastLoggedFailure = null;
        apiConfigurationWarningLogged = false;
    }

    private void resetBackoffDelays() {
        rateLimitBackoffSeconds =
                Math.max(1, plugin.getConfig().getLong("prices.retry.initial-delay-seconds", 60));
        accessErrorBackoffSeconds =
                Math.max(
                        1,
                        plugin.getConfig()
                                .getLong("prices.retry.access-error-initial-delay-seconds", 900));
        serverErrorBackoffSeconds =
                Math.max(1, plugin.getConfig().getLong("prices.retry.initial-delay-seconds", 60));
        retryNotBefore = Instant.EPOCH;
    }

    private long applyRateLimitBackoff(
            HttpResponse<?> response, long initialBackoffSeconds, long maximumBackoffSeconds) {
        var retryAfterSeconds =
                response.headers().firstValue("Retry-After").flatMap(this::parseRetryAfterSeconds);
        long delaySeconds =
                RetryPolicy.delay(
                        rateLimitBackoffSeconds, maximumBackoffSeconds, retryAfterSeconds);
        retryNotBefore = Instant.now().plusSeconds(delaySeconds);
        rateLimitBackoffSeconds =
                nextBackoff(delaySeconds, initialBackoffSeconds, maximumBackoffSeconds);
        return delaySeconds;
    }

    private long applyAccessErrorBackoff() {
        long initialDelay =
                Math.max(
                        1,
                        plugin.getConfig()
                                .getLong("prices.retry.access-error-initial-delay-seconds", 900));
        long maximumDelay =
                Math.max(
                        initialDelay,
                        plugin.getConfig()
                                .getLong("prices.retry.access-error-maximum-delay-seconds", 3600));
        long delaySeconds =
                Math.max(initialDelay, Math.min(accessErrorBackoffSeconds, maximumDelay));
        retryNotBefore = Instant.now().plusSeconds(delaySeconds);
        accessErrorBackoffSeconds = nextBackoff(delaySeconds, initialDelay, maximumDelay);
        return delaySeconds;
    }

    private long applyServerErrorBackoff(long initialDelay, long maximumDelay) {
        long delaySeconds =
                Math.max(initialDelay, Math.min(serverErrorBackoffSeconds, maximumDelay));
        retryNotBefore = Instant.now().plusSeconds(delaySeconds);
        serverErrorBackoffSeconds = nextBackoff(delaySeconds, initialDelay, maximumDelay);
        return delaySeconds;
    }

    private long nextBackoff(long delaySeconds, long initialDelay, long maximumDelay) {
        long nextDelay = delaySeconds > maximumDelay / 2 ? maximumDelay : delaySeconds * 2;
        return Math.max(initialDelay, Math.min(maximumDelay, nextDelay));
    }

    private java.util.Optional<Long> parseRetryAfterSeconds(String value) {
        return RetryPolicy.retryAfter(value, Instant.now());
    }

    private void logHttpFailure(HttpResponse<String> response, long delaySeconds) {
        int status = response.statusCode();
        String failureId = "HTTP " + status;
        if (Objects.equals(lastLoggedFailure, failureId)) {
            return;
        }
        lastLoggedFailure = failureId;

        StringBuilder message = new StringBuilder("Price API returned HTTP ").append(status);
        String detail = sanitizeErrorDetail(response.body());
        if (!detail.isBlank()) {
            message.append(" (response: ").append(detail).append(')');
        }
        response.headers()
                .firstValue("cf-ray")
                .or(() -> response.headers().firstValue("x-request-id"))
                .map(this::sanitizeHeaderValue)
                .filter(value -> !value.isBlank())
                .ifPresent(value -> message.append("; request id: ").append(value));
        if (status == 401 || status == 403) {
            message.append("; check API key type, endpoint, and server IP");
        }
        message.append("; keeping cached prices. Next attempt in ")
                .append(formatDelay(delaySeconds))
                .append('.');
        plugin.getLogger().warning(message.toString());
    }

    private void logTransportFailure(Throwable error, long delaySeconds) {
        if (Objects.equals(lastLoggedFailure, "network request failure")) {
            return;
        }
        lastLoggedFailure = "network request failure";
        String detail = sanitizeErrorDetail(error.getMessage());
        String message =
                "Price request failed"
                        + (detail.isBlank() ? "" : " (" + detail + ")")
                        + "; keeping cached prices. Next attempt in "
                        + formatDelay(delaySeconds)
                        + ".";
        plugin.getLogger().warning(message);
    }

    private String sanitizeErrorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String detail = body.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        detail = SENSITIVE_QUERY_PARAMETER.matcher(detail).replaceAll("$1[redacted]");
        for (String keyPath : List.of("api.demo-key", "api.pro-key")) {
            String secret = plugin.getConfig().getString(keyPath, "");
            if (secret != null && !secret.isBlank()) {
                detail = detail.replace(secret.trim(), "[redacted]");
            }
        }
        if (detail.length() > MAX_ERROR_DETAIL_LENGTH) {
            return detail.substring(0, MAX_ERROR_DETAIL_LENGTH) + "â€¦";
        }
        return detail;
    }

    private String sanitizeHeaderValue(String value) {
        String sanitized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return sanitized.length() > 80 ? sanitized.substring(0, 80) : sanitized;
    }

    private String formatDelay(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return Math.max(1, seconds / 60) + "m";
        }
        return Math.max(1, seconds / 3600) + "h";
    }

    private String getAuthMode() {
        String configuredMode = plugin.getConfig().getString("api.auth-mode", "demo");
        return configuredMode == null ? "demo" : configuredMode.trim().toLowerCase(Locale.ROOT);
    }

    private String getApiKey(String authMode) {
        String keyPath = "pro".equals(authMode) ? "api.pro-key" : "api.demo-key";
        String configuredKey = plugin.getConfig().getString(keyPath, "");
        return configuredKey == null ? "" : configuredKey.trim();
    }

    private boolean isValidAuthentication(String authMode, String apiKey) {
        return ("demo".equals(authMode) || "pro".equals(authMode))
                && (!"pro".equals(authMode) || !apiKey.isBlank());
    }

    private String authenticationConfigurationError(String authMode, String apiKey) {
        if (!"demo".equals(authMode) && !"pro".equals(authMode)) {
            return "Invalid api.auth-mode '"
                    + authMode
                    + "'; choose 'demo' or 'pro'. Price requests are disabled.";
        }
        if ("pro".equals(authMode) && apiKey.isBlank()) {
            return "CoinGecko Pro mode requires api.pro-key. Price requests are disabled until it"
                    + " is configured.";
        }
        return "Invalid CoinGecko API configuration. Price requests are disabled.";
    }

    private String getEndpoint(String authMode) {
        String configuredUrl = plugin.getConfig().getString("api.url", DEMO_ENDPOINT);
        String baseUrl = configuredUrl == null ? DEMO_ENDPOINT : configuredUrl.trim();
        if ("pro".equals(authMode)
                && DEMO_ENDPOINT.equalsIgnoreCase(baseUrl.replaceAll("/+$", ""))) {
            return PRO_ENDPOINT;
        }
        return baseUrl;
    }

    private String getQueryParameterName(
            ConfigurationSection queryConfig, String key, String fallback) {
        if (queryConfig == null) {
            return fallback;
        }
        String value = queryConfig.getString(key, fallback);
        return value == null ? fallback : value.trim();
    }

    private String appendQuery(String baseUrl, Map<String, String> queryParameters) {
        StringBuilder result = new StringBuilder(baseUrl);
        String separator =
                baseUrl.endsWith("?") || baseUrl.endsWith("&")
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

    private void recordHistory(Map<String, Quote> fetchedQuotes) {
        Instant observedAt = Instant.now();
        long retentionDays =
                Math.max(
                        1,
                        Math.min(
                                30,
                                plugin.getConfig().getLong("prices.history-retention-days", 7)));
        Instant cutoff = observedAt.minus(Duration.ofDays(retentionDays));
        for (String quoteKey : history.keySet()) {
            history.computeIfPresent(
                    quoteKey,
                    (key, existing) -> {
                        List<PricePoint> retained =
                                existing.stream()
                                        .filter(point -> !point.observedAt().isBefore(cutoff))
                                        .toList();
                        return retained.isEmpty() ? null : retained;
                    });
        }
        historyRevision++;
        for (Map.Entry<String, Quote> entry : fetchedQuotes.entrySet()) {
            if (isStale(entry.getValue())) continue;
            history.compute(
                    entry.getKey(),
                    (key, existing) -> {
                        List<PricePoint> updated =
                                new ArrayList<>(existing == null ? List.of() : existing);
                        updated.removeIf(point -> point.observedAt().isBefore(cutoff));
                        Instant sourceTime = entry.getValue().updatedAt();
                        if (updated.isEmpty() || sourceTime.isAfter(updated.getLast().observedAt()))
                            updated.add(new PricePoint(sourceTime, entry.getValue().price()));
                        return List.copyOf(updated);
                    });
        }
    }

    private void loadHistory() {
        if (!historyFile.exists()) {
            return;
        }
        YamlConfiguration saved = plugin.storage().load(historyFile);
        backfilled.addAll(saved.getStringList("backfilled"));
        ConfigurationSection section = saved.getConfigurationSection("history");
        if (section == null) {
            backfilled.clear();
            return;
        }

        long retentionDays =
                Math.max(
                        1,
                        Math.min(
                                30,
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
                    Instant observedAt =
                            Instant.ofEpochMilli(
                                    Long.parseLong(encodedPoint.substring(0, separator)));
                    BigDecimal price = new BigDecimal(encodedPoint.substring(separator + 1));
                    if (!observedAt.isBefore(cutoff)) {
                        points.add(new PricePoint(observedAt, price));
                    }
                } catch (RuntimeException ignored) {
                    plugin.getLogger()
                            .warning(
                                    "Skipping invalid saved price history point for "
                                            + coinId
                                            + ".");
                }
            }

            points.sort((left, right) -> left.observedAt().compareTo(right.observedAt()));
            String quoteKey =
                    coinId.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT);
            if (!points.isEmpty()) history.put(quoteKey, List.copyOf(points));
            String lastPrice = saved.getString(base + "last-price");
            if (lastPrice != null || !points.isEmpty()) {
                BigDecimal price =
                        lastPrice == null ? points.getLast().price() : new BigDecimal(lastPrice);
                Instant fallback = points.isEmpty() ? Instant.EPOCH : points.getLast().observedAt();
                Instant updatedAt =
                        Instant.ofEpochMilli(
                                saved.getLong(base + "updated-at", fallback.toEpochMilli()));
                String change = saved.getString(base + "change");
                if (price.signum() > 0)
                    quotes.put(
                            quoteKey,
                            new Quote(
                                    price,
                                    change == null ? null : new BigDecimal(change),
                                    updatedAt));
            }
        }
        backfilled.retainAll(history.keySet());
    }

    private void saveHistory() {
        Map<String, List<PricePoint>> copy = Map.copyOf(history);
        Map<String, Quote> quoteCopy = Map.copyOf(quotes);
        Set<String> backfilledSnapshot = Set.copyOf(backfilled);
        plugin.storage()
                .save(
                        historyFile,
                        () -> {
                            YamlConfiguration saved = new YamlConfiguration();
                            Set<String> keys = new LinkedHashSet<>(copy.keySet());
                            keys.addAll(quoteCopy.keySet());
                            keys.forEach(
                                    key -> {
                                        List<PricePoint> points = copy.getOrDefault(key, List.of());
                                        int separator = key.lastIndexOf(':');
                                        String entryId =
                                                Base64.getUrlEncoder()
                                                        .withoutPadding()
                                                        .encodeToString(
                                                                key.getBytes(
                                                                        StandardCharsets.UTF_8));
                                        String base = "history." + entryId + ".";
                                        saved.set(base + "coin-id", key.substring(0, separator));
                                        saved.set(base + "currency", key.substring(separator + 1));
                                        saved.set(
                                                base + "points",
                                                points.stream()
                                                        .map(
                                                                point ->
                                                                        point.observedAt()
                                                                                        .toEpochMilli()
                                                                                + "|"
                                                                                + point.price()
                                                                                        .toPlainString())
                                                        .toList());
                                        Quote quote = quoteCopy.get(key);
                                        if (quote != null) {
                                            saved.set(
                                                    base + "last-price",
                                                    quote.price().toPlainString());
                                            saved.set(
                                                    base + "updated-at",
                                                    quote.updatedAt().toEpochMilli());
                                            saved.set(
                                                    base + "change",
                                                    quote.change24h() == null
                                                            ? null
                                                            : quote.change24h().toPlainString());
                                        }
                                    });
                            saved.set("backfilled", List.copyOf(backfilledSnapshot));
                            return saved;
                        });
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public void requestPair(String coinId, String currency) {
        demand.put(
                coinId.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT),
                Instant.now().plusSeconds(600));
        refresh();
    }

    private Set<String> desiredPairs() {
        demand.entrySet().removeIf(e -> e.getValue().isBefore(Instant.now()));
        Set<String> pairs = new LinkedHashSet<>(demand.keySet());
        boards.getBoards().forEach(b -> pairs.add(b.quoteKey()));
        if (plugin.features() != null) pairs.addAll(plugin.features().quotePairs());
        if (plugin.getConfig().getBoolean("integrations.placeholderapi-preload", false)) {
            var coins = plugin.getConfig().getConfigurationSection("coins");
            if (coins != null)
                for (String symbol : coins.getKeys(false))
                    for (String currency : plugin.getConfig().getStringList("currencies"))
                        pairs.add(
                                coins.getString(symbol).toLowerCase(Locale.ROOT)
                                        + ":"
                                        + currency.toLowerCase(Locale.ROOT));
        }
        return pairs;
    }

    public boolean isStale(Quote quote) {
        return quote == null
                || quote.updatedAt().isAfter(Instant.now().plusSeconds(300))
                || Duration.between(quote.updatedAt(), Instant.now()).toMinutes() >= staleMinutes;
    }

    public Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    public Instant nextRequestAt() {
        return retryNotBefore.isAfter(
                        lastRequestAt.plusSeconds(
                                plugin.getConfig()
                                        .getLong("prices.minimum-request-interval-seconds", 60)))
                ? retryNotBefore
                : lastRequestAt.plusSeconds(
                        plugin.getConfig().getLong("prices.minimum-request-interval-seconds", 60));
    }

    public boolean isInFlight() {
        return inFlight.get();
    }

    public String failure() {
        return lastLoggedFailure;
    }

    public void tick() {
        if (closed) return;
        Instant now = Instant.now();
        if (lastLoggedFailure != null && !now.isBefore(nextRequestAt())) refresh();
        else if (Duration.between(lastPriceRequestAt, now).toSeconds()
                >= plugin.getConfig().getLong("prices.refresh-interval-seconds", 300)) refresh();
        else if (!demand.isEmpty()
                && demand.keySet().stream().anyMatch(key -> !quotes.containsKey(key))) refresh();
        if (!inFlight.get()) backfill();
    }

    private void backfill() {
        if (!plugin.getConfig().getBoolean("prices.backfill.enabled", false)) return;
        Instant now = Instant.now();
        if (now.isBefore(nextRequestAt())) return;
        backfillRequests.removeIf(at -> at.isBefore(now.minusSeconds(3600)));
        if (backfillRequests.size()
                >= plugin.getConfig().getInt("prices.backfill.maximum-requests-per-hour", 6))
            return;
        String pair =
                desiredPairs().stream()
                        .filter(key -> !backfilled.contains(key))
                        .filter(
                                key ->
                                        !now.isBefore(
                                                backfillRetry.getOrDefault(key, Instant.EPOCH)))
                        .findFirst()
                        .orElse(null);
        if (pair == null) return;
        String authMode = getAuthMode();
        String apiKey = getApiKey(authMode);
        if (!isValidAuthentication(authMode, apiKey)) return;
        String endpoint = plugin.getConfig().getString("prices.backfill.url", "");
        String priceEndpoint = getEndpoint(authMode);
        // Never send keys to an unrelated host. Custom price providers need an explicit history
        // URL.
        if (endpoint == null || endpoint.isBlank()) {
            if (!priceEndpoint.equals(DEMO_ENDPOINT) && !priceEndpoint.equals(PRO_ENDPOINT)) return;
            endpoint =
                    priceEndpoint.substring(0, priceEndpoint.length() - "simple/price".length())
                            + "coins/{id}/market_chart";
        }
        int separator = pair.lastIndexOf(':');
        String id = pair.substring(0, separator);
        String currency = pair.substring(separator + 1);
        int days =
                Math.min(
                        plugin.getConfig().getInt("prices.history-retention-days", 7),
                        plugin.getConfig().getInt("prices.backfill.days", 7));
        long initial = plugin.getConfig().getLong("prices.retry.initial-delay-seconds", 60);
        long maximum = plugin.getConfig().getLong("prices.retry.maximum-delay-seconds", 3600);
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            appendQuery(
                                                    endpoint.replace("{id}", encode(id)),
                                                    Map.of(
                                                            "vs_currency",
                                                            currency,
                                                            "days",
                                                            String.valueOf(days)))))
                            .timeout(
                                    Duration.ofSeconds(
                                            plugin.getConfig()
                                                    .getLong("prices.request-timeout-seconds", 20)))
                            .header("Accept", "application/json")
                            .header(
                                    "User-Agent",
                                    "CryptoCraft/" + plugin.getDescription().getVersion())
                            .GET();
            if (!apiKey.isEmpty())
                builder.header(
                        "pro".equals(authMode) ? "x-cg-pro-api-key" : "x-cg-demo-api-key", apiKey);
            if (!inFlight.compareAndSet(false, true)) return;
            lastRequestAt = now;
            backfillRequests.add(now);
            int version = generation;
            request = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
            request.whenComplete(
                    (response, error) ->
                            onMain(
                                    () -> {
                                        if (version != generation || closed) return;
                                        try {
                                            if (!acceptResponse(
                                                    response, error, initial, maximum)) {
                                                backfillRetry.put(
                                                        pair, Instant.now().plusSeconds(3600));
                                                return;
                                            }
                                            List<PricePoint> imported =
                                                    QuoteCodec.history(
                                                            response.body(),
                                                            Instant.now()
                                                                    .minus(Duration.ofDays(days)),
                                                            Instant.now());
                                            if (imported.isEmpty())
                                                throw new IllegalArgumentException(
                                                        "Empty historical response");
                                            NavigableMap<Instant, BigDecimal> merged =
                                                    new java.util.TreeMap<>();
                                            imported.forEach(
                                                    p -> merged.put(p.observedAt(), p.price()));
                                            history.getOrDefault(pair, List.of())
                                                    .forEach(
                                                            p ->
                                                                    merged.put(
                                                                            p.observedAt(),
                                                                            p.price()));
                                            history.put(
                                                    pair,
                                                    merged.entrySet().stream()
                                                            .map(
                                                                    e ->
                                                                            new PricePoint(
                                                                                    e.getKey(),
                                                                                    e.getValue()))
                                                            .toList());
                                            backfilled.add(pair);
                                            historyRevision++;
                                            saveHistory();
                                        } catch (RuntimeException exception) {
                                            backfillRetry.put(
                                                    pair, Instant.now().plusSeconds(3600));
                                            logTransportFailure(exception, 3600);
                                        } finally {
                                            inFlight.set(false);
                                            boards.refreshLoadedDisplays(this);
                                        }
                                    }));
        } catch (RuntimeException exception) {
            inFlight.set(false);
            backfillRetry.put(pair, now.plusSeconds(3600));
            logTransportFailure(exception, 3600);
        }
    }

    private boolean acceptResponse(
            HttpResponse<String> response, Throwable error, long initial, long maximum) {
        if (error != null) {
            logTransportFailure(error, applyServerErrorBackoff(initial, maximum));
            return false;
        }
        if (response.statusCode() == 429) {
            logHttpFailure(response, applyRateLimitBackoff(response, initial, maximum));
            return false;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            logHttpFailure(
                    response,
                    response.statusCode() == 401 || response.statusCode() == 403
                            ? applyAccessErrorBackoff()
                            : applyServerErrorBackoff(initial, maximum));
            return false;
        }
        return true;
    }

    private void recovered() {
        resetBackoffDelays();
        if (lastLoggedFailure != null)
            plugin.getLogger().info("Price API recovered after " + lastLoggedFailure + ".");
        lastLoggedFailure = null;
    }

    private void onMain(Runnable action) {
        if (closed || !plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            /* Plugin stopped between callback and scheduling. */
        }
    }

    public void close() {
        closed = true;
        generation++;
        if (request != null) request.cancel(true);
        http.shutdownNow();
        saveHistory();
    }

    public record Quote(BigDecimal price, BigDecimal change24h, Instant updatedAt) {}

    public record PricePoint(Instant observedAt, BigDecimal price) {}
}

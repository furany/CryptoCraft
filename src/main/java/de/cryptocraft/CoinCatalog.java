package de.cryptocraft;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit administrator searches only; never called by charts, polling or player commands. */
final class CoinCatalog {
    record Coin(String id, String symbol, String name) {}
    private static List<Coin> cached = List.of();
    private static Instant validUntil = Instant.EPOCH;
    private static Instant retryAfter = Instant.EPOCH;
    private static CompletableFuture<List<Coin>> inFlight;
    private CoinCatalog() {}
    static synchronized CompletableFuture<List<Coin>> search(String query) {
        String term = query.toLowerCase(Locale.ROOT).strip();
        CompletableFuture<List<Coin>> catalog;
        if (Instant.now().isBefore(validUntil)) catalog = CompletableFuture.completedFuture(cached);
        else if (inFlight != null) catalog = inFlight;
        else if (Instant.now().isBefore(retryAfter))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Coin catalog temporarily unavailable; configure provider IDs manually or try later."));
        else {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.coingecko.com/api/v3/coins/list"))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", "CryptoCraft/1.2.0").GET().build();
            inFlight = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                if (response.statusCode() != 200) throw new IllegalArgumentException("Coin catalog unavailable (HTTP " + response.statusCode() + ")");
                List<Coin> coins = new ArrayList<>();
                for (var value : JsonParser.parseString(response.body()).getAsJsonArray()) {
                    var row = value.getAsJsonObject();
                    coins.add(new Coin(row.get("id").getAsString(), row.get("symbol").getAsString(), row.get("name").getAsString()));
                }
                return List.copyOf(coins);
            });
            catalog = inFlight;
            inFlight.whenComplete((coins, error) -> {
                synchronized (CoinCatalog.class) {
                    if (error == null) { cached = coins; validUntil = Instant.now().plus(Duration.ofHours(24)); }
                    else retryAfter = Instant.now().plus(Duration.ofMinutes(15));
                    inFlight = null;
                }
                client.shutdown();
            });
        }
        return catalog.thenApply(coins -> coins.stream().filter(coin -> coin.id().contains(term)
                || coin.symbol().toLowerCase(Locale.ROOT).contains(term) || coin.name().toLowerCase(Locale.ROOT).contains(term))
                .sorted(Comparator.comparing((Coin coin) -> !coin.symbol().equalsIgnoreCase(term)).thenComparing(Coin::id))
                .limit(15).toList());
    }
}

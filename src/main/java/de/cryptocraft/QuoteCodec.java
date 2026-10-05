package de.cryptocraft;

import com.google.gson.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Strict JSON numbers: exponent notation is preserved without floating point conversion. */
final class QuoteCodec {
    static Map<String, CryptoPriceService.Quote> prices(
            String json, Set<String> ids, Set<String> currencies, Instant now) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, CryptoPriceService.Quote> quotes = new HashMap<>();
        for (String id : ids) {
            if (!root.has(id) || !root.get(id).isJsonObject()) continue;
            JsonObject object = root.getAsJsonObject(id);
            BigDecimal timestamp = number(object, "last_updated_at");
            Instant updated =
                    timestamp == null ? now : Instant.ofEpochSecond(timestamp.longValueExact());
            if (updated.isAfter(now.plusSeconds(300)) || updated.isBefore(Instant.EPOCH)) continue;
            for (String currency : currencies) {
                BigDecimal price = number(object, currency);
                if (price == null || price.signum() <= 0) continue;
                quotes.put(
                        id.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT),
                        new CryptoPriceService.Quote(
                                price, number(object, currency + "_24h_change"), updated));
            }
        }
        if (quotes.isEmpty())
            throw new JsonParseException("Response contains no requested valid prices");
        return Map.copyOf(quotes);
    }

    static List<CryptoPriceService.PricePoint> history(String json, Instant cutoff, Instant now) {
        JsonArray prices = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("prices");
        if (prices == null) throw new JsonParseException("Missing prices");
        NavigableMap<Instant, BigDecimal> sorted = new TreeMap<>();
        for (JsonElement row : prices) {
            JsonArray values = row.getAsJsonArray();
            if (values.size() != 2) throw new JsonParseException("Invalid history row");
            BigDecimal stamp = decimal(values.get(0));
            BigDecimal price = decimal(values.get(1));
            Instant at = Instant.ofEpochMilli(stamp.longValueExact());
            if (!at.isBefore(cutoff) && !at.isAfter(now) && price.signum() > 0)
                sorted.put(at, price);
        }
        return sorted.entrySet().stream()
                .map(e -> new CryptoPriceService.PricePoint(e.getKey(), e.getValue()))
                .toList();
    }

    private static BigDecimal number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : decimal(value);
    }

    private static BigDecimal decimal(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new JsonParseException("Expected JSON number");
        BigDecimal number = value.getAsBigDecimal();
        if (number.precision() > 64 || Math.abs((long) number.scale()) > 100)
            throw new JsonParseException("Number outside supported range");
        return number;
    }
}

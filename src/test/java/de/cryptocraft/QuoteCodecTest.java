package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

class QuoteCodecTest {
    private final Instant now = Instant.ofEpochSecond(1700000000);

    @Test
    void preservesExponentAndDecimalPrecision() {
        var quotes =
                QuoteCodec.prices(
                        "{\"bitcoin\":{\"eur\":1.23456789123456789e-7,\"eur_24h_change\":-2.5e1,\"last_updated_at\":1700000000}}",
                        Set.of("bitcoin"),
                        Set.of("eur"),
                        now);
        assertEquals(new BigDecimal("1.23456789123456789e-7"), quotes.get("bitcoin:eur").price());
        assertEquals(0, new BigDecimal("-25").compareTo(quotes.get("bitcoin:eur").change24h()));
        assertEquals(now, quotes.get("bitcoin:eur").updatedAt());
    }

    @Test
    void nullChangeAndMissingTimestampAreSupported() {
        var quote =
                QuoteCodec.prices(
                                "{\"bitcoin\":{\"eur\":123,\"eur_24h_change\":null}}",
                                Set.of("bitcoin"),
                                Set.of("eur"),
                                now)
                        .get("bitcoin:eur");
        assertNull(quote.change24h());
        assertEquals(now, quote.updatedAt());
    }

    @Test
    void rejectsErrorBodiesAndWrongNumericTypes() {
        for (String json :
                new String[] {
                    "{}",
                    "{\"error\":\"denied\"}",
                    "{\"bitcoin\":{\"eur\":\"100\"}}",
                    "{\"bitcoin\":{\"eur\":-1}}",
                    "{\"bitcoin\":{\"eur\":0}}",
                    "not json"
                })
            assertThrows(
                    RuntimeException.class,
                    () -> QuoteCodec.prices(json, Set.of("bitcoin"), Set.of("eur"), now),
                    json);
    }

    @Test
    void futureQuotesAndExtremeNumbersAreRejected() {
        assertThrows(
                RuntimeException.class,
                () ->
                        QuoteCodec.prices(
                                "{\"bitcoin\":{\"eur\":100,\"last_updated_at\":1700001000}}",
                                Set.of("bitcoin"),
                                Set.of("eur"),
                                now));
        assertThrows(
                RuntimeException.class,
                () ->
                        QuoteCodec.prices(
                                "{\"bitcoin\":{\"eur\":1e999999}}",
                                Set.of("bitcoin"),
                                Set.of("eur"),
                                now));
    }

    @Test
    void partialResponsesKeepOnlyRequestedPairs() {
        var result =
                QuoteCodec.prices(
                        "{\"bitcoin\":{\"eur\":1,\"usd\":2},\"other\":{\"eur\":3}}",
                        Set.of("bitcoin", "missing"),
                        Set.of("eur"),
                        now);
        assertEquals(Set.of("bitcoin:eur"), result.keySet());
    }

    @Test
    void importedHistoryIsSortedDeduplicatedAndBounded() {
        var result =
                QuoteCodec.history(
                        "{\"prices\":[[1700000000000,5],[1699999999000,2],[1699999999000,3],[1690000000000,1],[1700000001000,6],[1699999998000,-1]]}",
                        now.minusSeconds(10),
                        now);
        assertEquals(2, result.size());
        assertEquals(now.minusSeconds(1), result.getFirst().observedAt());
        assertEquals(new BigDecimal("3"), result.getFirst().price());
        assertEquals(now, result.getLast().observedAt());
    }
}

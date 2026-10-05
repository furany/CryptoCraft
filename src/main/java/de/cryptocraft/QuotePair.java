package de.cryptocraft;

import java.util.Locale;

public record QuotePair(String symbol, String coinId, String currency) {
    public QuotePair {
        symbol = symbol.toUpperCase(Locale.ROOT);
        coinId = coinId.toLowerCase(Locale.ROOT);
        currency = currency.toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9]{1,12}")
                || !coinId.matches("[a-z0-9_-]{1,100}")
                || !currency.matches("[A-Z0-9]{2,12}"))
            throw new IllegalArgumentException("Invalid coin/currency");
    }

    public String key() {
        return coinId + ":" + currency.toLowerCase(Locale.ROOT);
    }

    public String label() {
        return symbol + "/" + currency;
    }
}

package de.cryptocraft;

import org.bukkit.block.BlockFace;

import java.util.*;

public record CryptoBoard(
        String id,
        UUID ownerId,
        String ownerName,
        UUID worldId,
        String worldName,
        int x,
        int y,
        int z,
        String symbol,
        String coinId,
        String currency,
        BlockFace displayFacing,
        BoardSettings settings,
        List<QuotePair> marketPairs,
        boolean terminal) {
    public CryptoBoard {
        Objects.requireNonNull(settings);
        marketPairs = List.copyOf(marketPairs);
        currency = currency.toUpperCase(Locale.ROOT);
        if (!marketPairs.isEmpty()) {
            if (marketPairs.size() > 6) throw new IllegalArgumentException("Market boards support 1–6 coins");
            Set<String> symbols = new HashSet<>();
            Set<String> ids = new HashSet<>();
            for (QuotePair pair : marketPairs) {
                if (!pair.currency().equals(currency))
                    throw new IllegalArgumentException("All market rows must use the board currency");
                if (!symbols.add(pair.symbol()) || !ids.add(pair.coinId()))
                    throw new IllegalArgumentException("Market coins must be unique");
            }
            symbol = marketPairs.getFirst().symbol();
            coinId = marketPairs.getFirst().coinId();
        }
    }

    /** Source-compatible constructor for existing integrations and saved single-pair boards. */
    public CryptoBoard(String id, UUID ownerId, String ownerName, UUID worldId, String worldName,
            int x, int y, int z, String symbol, String coinId, String currency,
            BlockFace displayFacing, BoardSettings settings) {
        this(id, ownerId, ownerName, worldId, worldName, x, y, z, symbol, coinId, currency,
                displayFacing, settings, List.of(), false);
    }

    public boolean market() {
        return !marketPairs.isEmpty();
    }

    public List<QuotePair> quotePairs() {
        return market() ? marketPairs : List.of(new QuotePair(symbol, coinId, currency));
    }

    public String label() {
        return settings.name().isBlank() ? (market() ? "MARKET" : symbol) + "/" + currency : settings.name();
    }

    public CryptoBoard withTerminal(boolean enabled) {
        return new CryptoBoard(id, ownerId, ownerName, worldId, worldName, x, y, z,
                symbol, coinId, currency, displayFacing, settings, marketPairs, enabled);
    }

    public CryptoBoard withMarketPairs(List<QuotePair> pairs) {
        if (pairs.isEmpty()) throw new IllegalArgumentException("Market boards need at least one coin");
        return new CryptoBoard(id, ownerId, ownerName, worldId, worldName, x, y, z,
                symbol, coinId, currency, displayFacing, settings, pairs, terminal);
    }
    public String locationKey() {
        return worldId + ":" + x + ":" + y + ":" + z;
    }

    public String quoteKey() {
        return coinId.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT);
    }

    public CryptoBoard edited(
            String symbol,
            String coinId,
            String currency,
            BlockFace facing,
            BoardSettings settings) {
        return new CryptoBoard(
                id, ownerId, ownerName, worldId, worldName, x, y, z, symbol, coinId, currency,
                facing, settings,
                marketPairs.stream().map(pair -> new QuotePair(pair.symbol(), pair.coinId(), currency)).toList(),
                terminal);
    }
}

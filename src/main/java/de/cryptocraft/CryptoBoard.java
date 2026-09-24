package de.cryptocraft;

import java.util.Locale;
import java.util.UUID;

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
        String currency
) {
    public String locationKey() {
        return worldId + ":" + x + ":" + y + ":" + z;
    }

    public String quoteKey() {
        return coinId.toLowerCase(Locale.ROOT) + ":" + currency.toLowerCase(Locale.ROOT);
    }
}

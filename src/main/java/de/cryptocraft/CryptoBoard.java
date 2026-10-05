package de.cryptocraft;

import org.bukkit.block.BlockFace;

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
        String currency,
        BlockFace displayFacing,
        BoardSettings settings) {
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
                facing, settings);
    }
}

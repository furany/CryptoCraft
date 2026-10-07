package de.cryptocraft;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import org.bukkit.OfflinePlayer;

import java.time.*;
import java.util.Locale;

/** Cached reads only: placeholder evaluation never performs HTTP, disk or world operations. */
public final class CryptoPlaceholders extends PlaceholderExpansion {
    private final CryptoCraftPlugin plugin;

    public CryptoPlaceholders(CryptoCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "cryptocraft";
    }

    @Override
    public String getAuthor() {
        return "CryptoCraft";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String parameters) {
        if (parameters.equalsIgnoreCase("boards"))
            return player == null
                    ? "0"
                    : String.valueOf(plugin.boards().getBoardsOwnedBy(player.getUniqueId()).size());
        if (parameters.equalsIgnoreCase("virtual_cash")
                || parameters.equalsIgnoreCase("virtual_currency")) {
            PortfolioAccount account =
                    player == null ? null : plugin.features().peekAccount(player.getUniqueId());
            return account == null
                    ? ""
                    : parameters.equalsIgnoreCase("virtual_cash")
                            ? account.cash().toPlainString()
                            : account.currency();
        }
        String[] parts = parameters.split("_", -1);
        if (parts.length != 3) return null;
        QuotePair pair = plugin.configuredPair(parts[1], parts[2]);
        if (pair == null) return null;
        var quote = plugin.prices().getQuote(pair.coinId(), pair.currency());
        if (quote == null) return "";
        return switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "price" -> quote.price().toPlainString();
            case "change" -> quote.change24h() == null ? "" : quote.change24h().toPlainString();
            case "age" ->
                    String.valueOf(
                            Math.max(
                                    0,
                                    Duration.between(quote.updatedAt(), Instant.now())
                                            .toSeconds()));
            case "stale" -> String.valueOf(plugin.prices().isStale(quote));
            default -> null;
        };
    }
}

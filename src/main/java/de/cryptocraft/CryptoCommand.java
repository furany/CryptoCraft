package de.cryptocraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public final class CryptoCommand implements CommandExecutor {
    private static final Component PREFIX = Component.text("[CryptoCraft] ", NamedTextColor.GOLD);
    private final CryptoCraftPlugin plugin;

    public CryptoCommand(CryptoCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "place" -> place(sender, args);
            case "remove" -> remove(sender);
            case "list" -> list(sender);
            case "price" -> price(sender, args);
            case "reload" -> reload(sender);
            default -> help(sender);
        }
        return true;
    }

    private void place(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "Only players can place a board.");
            return;
        }
        if (!player.hasPermission("cryptocraft.place")) {
            send(sender, "You do not have permission to place boards.");
            return;
        }
        if (args.length < 2 || args.length > 3) {
            send(sender, "Usage: /crypto place <coin> [currency]");
            return;
        }

        String symbol = args[1].toUpperCase(Locale.ROOT);
        String coinId = plugin.getConfig().getString("coins." + symbol);
        if (coinId == null || coinId.isBlank()) {
            var configuredCoins = plugin.getConfig().getConfigurationSection("coins");
            String available = configuredCoins == null ? "none" : String.join(", ", configuredCoins.getKeys(false));
            send(sender, "That coin is not configured. Available: " + available);
            return;
        }

        String currency = (args.length == 3 ? args[2] : getDefaultCurrency()).toUpperCase(Locale.ROOT);
        List<String> configuredCurrencies = plugin.getConfig().getStringList("currencies").stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .toList();
        if (!configuredCurrencies.contains(currency)) {
            send(sender, "That currency is not configured. Available: " + String.join(", ", configuredCurrencies));
            return;
        }

        Block target = player.getTargetBlockExact(6);
        if (target == null || target.getType().isAir()) {
            send(sender, "Look at the vanilla block where the board should appear.");
            return;
        }
        if (plugin.boards().findAt(target) != null) {
            send(sender, "There is already a price board on that block.");
            return;
        }

        String unlimitedPermission = plugin.getConfig().getString(
                "boards.unlimited-permission", "cryptocraft.limit.unlimited");
        boolean unlimited = (unlimitedPermission != null && !unlimitedPermission.isBlank()
                && player.hasPermission(unlimitedPermission))
                || (plugin.getConfig().getBoolean("boards.admin-bypass-limit", true) && isAdmin(player));
        int limit = getBoardLimit(player);
        int current = plugin.boards().getBoardsOwnedBy(player.getUniqueId()).size();
        if (!unlimited && current >= limit) {
            send(sender, "You have reached your limit of " + limit + " board(s).");
            return;
        }

        plugin.boards().create(
                player.getUniqueId(),
                player.getName(),
                target,
                symbol,
                coinId.toLowerCase(Locale.ROOT),
                currency
        );
        plugin.boards().ensureBoardsInChunk(target.getChunk(), plugin.prices());
        plugin.prices().refresh();
        send(sender, "Showing " + symbol + " / " + currency + " above the block. Breaking the block also removes the board.");
    }

    private void remove(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "Only players can remove a board. Look at its anchor block.");
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null) {
            send(sender, "Look at the anchor block of the board you want to remove.");
            return;
        }
        CryptoBoard board = plugin.boards().findAt(target);
        if (board == null) {
            send(sender, "There is no price board on that block.");
            return;
        }
        if (!board.ownerId().equals(player.getUniqueId()) && !isAdmin(player)) {
            send(sender, "You can only remove your own boards.");
            return;
        }
        plugin.boards().remove(board);
        send(sender, "Price board removed. The block is unchanged.");
    }

    private void list(CommandSender sender) {
        List<CryptoBoard> visible;
        if (sender instanceof Player player && !isAdmin(player)) {
            visible = plugin.boards().getBoardsOwnedBy(player.getUniqueId());
        } else {
            visible = plugin.boards().getBoards();
        }
        if (visible.isEmpty()) {
            send(sender, "No price boards found.");
            return;
        }
        send(sender, "Boards: " + visible.size());
        for (CryptoBoard board : visible) {
            send(sender, board.symbol() + "/" + board.currency() + " at "
                    + board.worldName() + " " + board.x() + " " + board.y() + " " + board.z()
                    + " (owner " + board.ownerName() + ")");
        }
    }

    private void price(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            send(sender, "Usage: /crypto price <coin> [currency]");
            return;
        }
        String symbol = args[1].toUpperCase(Locale.ROOT);
        String coinId = plugin.getConfig().getString("coins." + symbol);
        if (coinId == null) {
            send(sender, "That coin is not configured.");
            return;
        }
        String currency = (args.length == 3 ? args[2] : getDefaultCurrency()).toUpperCase(Locale.ROOT);
        CryptoPriceService.Quote quote = plugin.prices().getQuote(coinId, currency);
        if (quote == null) {
            send(sender, "No price is cached yet. Check the API connection and try again after the next refresh.");
            return;
        }

        NumberFormat number = NumberFormat.getNumberInstance(Locale.US);
        number.setMinimumFractionDigits(2);
        number.setMaximumFractionDigits(2);
        send(sender, symbol + " / " + currency + ": " + number.format(quote.price()) + " " + currency);
        if (quote.change24h() != null) {
            String sign = quote.change24h().signum() > 0 ? "+" : "";
            send(sender, "24h " + sign + new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US))
                    .format(quote.change24h()) + "%");
        }
    }

    private void reload(CommandSender sender) {
        if (!isAdmin(sender)) {
            send(sender, "You do not have permission to reload CryptoCraft.");
            return;
        }
        plugin.reloadPluginConfiguration();
        send(sender, "Configuration reloaded.");
    }

    private void help(CommandSender sender) {
        send(sender, "/crypto place <coin> [currency] — use the block you are looking at as the anchor");
        send(sender, "/crypto remove — remove your board from the block you are looking at");
        send(sender, "/crypto list — list your boards");
        send(sender, "/crypto price <coin> [currency] — show the cached price");
        if (isAdmin(sender)) {
            send(sender, "/crypto reload — reload the configuration");
        }
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.GRAY)));
    }

    private int getBoardLimit(Player player) {
        int defaultLimit = Math.max(0, plugin.getConfig().getInt("boards.default-limit-per-player", 1));
        ConfigurationSection permissionLimits = plugin.getConfig().getConfigurationSection("boards.permission-limits");
        if (permissionLimits == null) {
            return defaultLimit;
        }

        Integer matchedLimit = null;
        for (String permission : permissionLimits.getKeys(false)) {
            if (!player.hasPermission(permission)) {
                continue;
            }
            Object configuredValue = permissionLimits.getValues(false).get(permission);
            int configuredLimit = configuredValue instanceof Number number
                    ? Math.max(0, number.intValue())
                    : defaultLimit;
            matchedLimit = matchedLimit == null ? configuredLimit : Math.max(matchedLimit, configuredLimit);
        }
        return matchedLimit == null ? defaultLimit : matchedLimit;
    }

    private boolean isAdmin(CommandSender sender) {
        String permission = plugin.getConfig().getString("boards.admin-permission", "cryptocraft.admin");
        return permission != null && !permission.isBlank() && sender.hasPermission(permission);
    }

    private String getDefaultCurrency() {
        return plugin.getConfig().getString("default-currency", "EUR");
    }
}

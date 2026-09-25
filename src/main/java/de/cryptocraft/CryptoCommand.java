package de.cryptocraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CryptoCommand implements CommandExecutor, TabCompleter {
    private static final Component PREFIX = Component.text("[CryptoCraft] ", NamedTextColor.GOLD);
    private final CryptoCraftPlugin plugin;

    public CryptoCommand(CryptoCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("cryptocraft.use")) {
            send(sender, "permissionUse");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "place" -> place(sender, args);
            case "remove" -> remove(sender);
            case "list" -> list(sender);
            case "price" -> price(sender, args);
            case "tp" -> teleport(sender, args);
            case "reload" -> reload(sender);
            default -> {
                send(sender, "helpUnknown");
                help(sender);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("cryptocraft.use")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> subcommands = new ArrayList<>(List.of("place", "remove", "list", "price", "tp"));
            if (isAdmin(sender)) {
                subcommands.add("reload");
            }
            return complete(subcommands, args[0]);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && subcommand.equals("place")) {
            return sender.hasPermission("cryptocraft.place")
                    ? complete(getConfiguredCoins(), args[1])
                    : List.of();
        }
        if (args.length == 2 && subcommand.equals("price")) {
            return complete(getConfiguredCoins(), args[1]);
        }
        if (args.length == 2 && subcommand.equals("tp") && sender instanceof Player player) {
            return complete(getVisibleBoards(player).stream().map(CryptoBoard::id).toList(), args[1]);
        }
        if (args.length == 3 && (subcommand.equals("place") || subcommand.equals("price"))) {
            return complete(getConfiguredCurrencies(), args[2]);
        }
        return List.of();
    }

    private void place(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "playerOnlyPlace");
            return;
        }
        if (!player.hasPermission("cryptocraft.place")) {
            send(sender, "permissionPlace");
            return;
        }
        if (args.length < 2 || args.length > 3) {
            send(sender, "usagePlace");
            return;
        }

        String symbol = args[1].toUpperCase(Locale.ROOT);
        String coinId = plugin.getConfig().getString("coins." + symbol);
        if (coinId == null || coinId.isBlank()) {
            send(sender, "coinUnknown", Map.of("coins", String.join(", ", getConfiguredCoins())));
            return;
        }

        String currency = (args.length == 3 ? args[2] : getDefaultCurrency()).toUpperCase(Locale.ROOT);
        List<String> configuredCurrencies = getConfiguredCurrencies();
        if (!configuredCurrencies.contains(currency)) {
            send(sender, "currencyUnknown", Map.of("currencies", String.join(", ", configuredCurrencies)));
            return;
        }

        Block target = player.getTargetBlockExact(6);
        if (target == null || target.getType().isAir()) {
            send(sender, "targetBlock");
            return;
        }
        if (plugin.boards().findAt(target) != null) {
            send(sender, "boardExists");
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
            send(sender, "boardLimit", Map.of("limit", String.valueOf(limit)));
            return;
        }

        plugin.boards().create(
                player.getUniqueId(),
                player.getName(),
                target,
                symbol,
                coinId.toLowerCase(Locale.ROOT),
                currency,
                player.getFacing().getOppositeFace()
        );
        plugin.boards().ensureBoardsInChunk(target.getChunk(), plugin.prices());
        plugin.prices().refresh();
        send(sender, "placeSuccess", Map.of("symbol", symbol, "currency", currency));
    }

    private void remove(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "playerOnlyRemove");
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null) {
            send(sender, "removeTarget");
            return;
        }
        CryptoBoard board = plugin.boards().findAt(target);
        if (board == null) {
            send(sender, "boardNotFound");
            return;
        }
        if (!board.ownerId().equals(player.getUniqueId()) && !isAdmin(player)) {
            send(sender, "removeOwnOnly");
            return;
        }
        plugin.boards().remove(board);
        send(sender, "removeSuccess");
    }

    private void list(CommandSender sender) {
        List<CryptoBoard> visible = sender instanceof Player player
                ? getVisibleBoards(player)
                : plugin.boards().getBoards();
        if (visible.isEmpty()) {
            send(sender, "listEmpty");
            return;
        }
        send(sender, "listHeader", Map.of("count", String.valueOf(visible.size())));
        for (CryptoBoard board : visible) {
            String entry = plugin.messages().get("listEntry", Map.of(
                    "symbol", board.symbol(),
                    "currency", board.currency(),
                    "world", board.worldName(),
                    "x", String.valueOf(board.x()),
                    "y", String.valueOf(board.y()),
                    "z", String.valueOf(board.z()),
                    "owner", board.ownerName()
            ));
            Component line = PREFIX.append(Component.text(entry, NamedTextColor.GRAY));
            if (sender instanceof Player) {
                line = line.clickEvent(ClickEvent.runCommand("/crypto tp " + board.id()))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                plugin.messages().get("listHover"), NamedTextColor.GOLD)));
            }
            sender.sendMessage(line);
        }
    }

    private void teleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "playerOnlyTeleport");
            return;
        }
        if (args.length != 2) {
            send(sender, "teleportUsage");
            return;
        }
        CryptoBoard board = plugin.boards().getBoard(args[1]);
        if (board == null) {
            send(sender, "boardIdUnknown");
            return;
        }
        if (!board.ownerId().equals(player.getUniqueId()) && !isAdmin(player)) {
            send(sender, "teleportOwnOnly");
            return;
        }
        World world = Bukkit.getWorld(board.worldId());
        if (world == null) {
            world = Bukkit.getWorld(board.worldName());
        }
        if (world == null) {
            send(sender, "teleportWorldUnloaded");
            return;
        }
        Location destination = findSafeLocation(board, world, player.getLocation());
        if (destination == null) {
            send(sender, "teleportNoSafeSpot");
            return;
        }
        if (!player.teleport(destination)) {
            send(sender, "teleportFailed");
            return;
        }
        send(sender, "teleportSuccess");
    }

    private Location findSafeLocation(CryptoBoard board, World world, Location currentLocation) {
        for (int radius = 0; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = 1; dy <= 5; dy++) {
                        int x = board.x() + dx;
                        int y = board.y() + dy;
                        int z = board.z() + dz;
                        if (y + 1 >= world.getMaxHeight() || y - 1 < world.getMinHeight()) {
                            continue;
                        }
                        Block floor = world.getBlockAt(x, y - 1, z);
                        Block feet = world.getBlockAt(x, y, z);
                        Block head = world.getBlockAt(x, y + 1, z);
                        if (!floor.isPassable() && feet.isPassable() && head.isPassable()) {
                            return new Location(world, x + 0.5, y, z + 0.5,
                                    currentLocation.getYaw(), currentLocation.getPitch());
                        }
                    }
                }
            }
        }
        return null;
    }

    private void price(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            send(sender, "priceUsage");
            return;
        }
        String symbol = args[1].toUpperCase(Locale.ROOT);
        String coinId = plugin.getConfig().getString("coins." + symbol);
        if (coinId == null) {
            send(sender, "priceCoinUnknown");
            return;
        }
        String currency = (args.length == 3 ? args[2] : getDefaultCurrency()).toUpperCase(Locale.ROOT);
        List<String> configuredCurrencies = getConfiguredCurrencies();
        if (!configuredCurrencies.contains(currency)) {
            send(sender, "priceCurrencyUnknown", Map.of("currencies", String.join(", ", configuredCurrencies)));
            return;
        }

        CryptoPriceService.Quote quote = plugin.prices().getQuote(coinId, currency);
        if (quote == null) {
            send(sender, "quoteUnavailable");
            return;
        }

        send(sender, "priceHeading", Map.of(
                "symbol", symbol,
                "currency", currency,
                "price", formatPrice(quote.price())
        ));
        if (quote.change24h() != null) {
            String sign = quote.change24h().signum() > 0 ? "+" : "";
            String change = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US))
                    .format(quote.change24h());
            send(sender, "priceChange", Map.of("change", sign + change));
        }
    }

    private void reload(CommandSender sender) {
        if (!isAdmin(sender)) {
            send(sender, "reloadDenied");
            return;
        }
        plugin.reloadPluginConfiguration();
        send(sender, "reloadSuccess");
    }

    private void help(CommandSender sender) {
        send(sender, "helpPlace");
        send(sender, "helpRemove");
        send(sender, "helpList");
        send(sender, "helpPrice");
        send(sender, "helpTeleport");
        if (isAdmin(sender)) {
            send(sender, "helpReload");
        }
    }

    private void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    private void send(CommandSender sender, String key, Map<String, String> values) {
        sender.sendMessage(PREFIX.append(Component.text(plugin.messages().get(key, values), NamedTextColor.GRAY)));
    }

    private List<CryptoBoard> getVisibleBoards(Player player) {
        return isAdmin(player)
                ? plugin.boards().getBoards()
                : plugin.boards().getBoardsOwnedBy(player.getUniqueId());
    }

    private List<String> getConfiguredCoins() {
        ConfigurationSection configuredCoins = plugin.getConfig().getConfigurationSection("coins");
        return configuredCoins == null
                ? List.of()
                : configuredCoins.getKeys(false).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> getConfiguredCurrencies() {
        return plugin.getConfig().getStringList("currencies").stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private List<String> complete(List<String> values, String prefix) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
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

    private String formatPrice(java.math.BigDecimal price) {
        int decimalPlaces = price.abs().compareTo(java.math.BigDecimal.ONE) >= 0
                ? 2
                : Math.max(2, Math.min(8, price.stripTrailingZeros().scale()));
        NumberFormat number = NumberFormat.getNumberInstance(Locale.US);
        number.setMinimumFractionDigits(Math.min(2, decimalPlaces));
        number.setMaximumFractionDigits(decimalPlaces);
        return number.format(price);
    }
}

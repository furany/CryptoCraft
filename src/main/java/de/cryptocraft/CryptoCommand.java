package de.cryptocraft;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CryptoCommand implements CommandExecutor, TabCompleter {
    private static final String PREFIX = "[CryptoCraft] ";
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

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "place" -> place(sender, args);
                case "remove" -> remove(sender, args);
                case "list" -> list(sender);
                case "price" -> price(sender, args);
                case "tp" -> teleport(sender, args);
                case "reload" -> reload(sender);
                case "edit" -> edit(sender, args);
                case "menu" -> plugin.menu().open(requirePlayer(sender), 0);
                case "status" -> status(sender);
                case "watch" -> watch(sender, args);
                case "compare" -> compare(sender, args);
                case "alert" -> alert(sender, args);
                case "signal" -> signal(sender, args);
                case "portfolio" -> portfolio(sender, args);
                default -> {
                    send(sender, "helpUnknown");
                    help(sender);
                }
            }
        } catch (IllegalArgumentException error) {
            send(
                    sender,
                    "inputError",
                    Map.of(
                            "error",
                            error.getMessage() == null ? "Invalid input" : error.getMessage()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("cryptocraft.use")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> subcommands =
                    new ArrayList<>(
                            List.of(
                                    "place", "remove", "list", "price", "edit", "menu", "watch",
                                    "compare"));
            if (canTeleport(sender)) {
                subcommands.add("tp");
            }
            if (sender.hasPermission("cryptocraft.alert")) subcommands.add("alert");
            if (sender.hasPermission("cryptocraft.signal")) subcommands.add("signal");
            if (sender.hasPermission("cryptocraft.portfolio")) subcommands.add("portfolio");
            if (isAdmin(sender)) {
                subcommands.add("status");
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
        if (args.length == 2
                && subcommand.equals("tp")
                && canTeleport(sender)
                && sender instanceof Player player) {
            return complete(
                    getVisibleBoards(player).stream().map(CryptoBoard::id).toList(), args[1]);
        }
        if (args.length == 3 && (subcommand.equals("place") || subcommand.equals("price"))) {
            return complete(getConfiguredCurrencies(), args[2]);
        }
        if (args.length == 2
                && (subcommand.equals("edit")
                        || subcommand.equals("remove")
                        || subcommand.equals("signal"))
                && sender instanceof Player player)
            return complete(
                    getVisibleBoards(player).stream().map(CryptoBoard::id).toList(), args[1]);
        if (args.length == 3 && subcommand.equals("edit"))
            return complete(
                    List.of(
                            "coin",
                            "currency",
                            "facing",
                            "name",
                            "height",
                            "hours",
                            "theme",
                            "style",
                            "size",
                            "range"),
                    args[2]);
        if (args.length == 4 && subcommand.equals("edit"))
            return complete(
                    switch (args[2].toLowerCase(Locale.ROOT)) {
                        case "coin" -> getConfiguredCoins();
                        case "currency" -> getConfiguredCurrencies();
                        case "facing" -> List.of("north", "east", "south", "west");
                        case "hours" -> List.of("1", "6", "24", "72", "168");
                        case "theme" -> List.of("dark", "light", "ocean");
                        case "style" -> List.of("chart", "price", "compact");
                        case "size" -> List.of("1", "2", "3");
                        case "range" -> List.of("true", "false");
                        default -> List.of();
                    },
                    args[3]);
        if (args.length == 2 && (subcommand.equals("watch") || subcommand.equals("alert")))
            return complete(List.of("add", "remove", "list"), args[1]);
        if (args.length == 2 && subcommand.equals("portfolio"))
            return complete(List.of("show", "buy", "sell", "history"), args[1]);
        if (args.length == 2 && subcommand.equals("compare"))
            return complete(getConfiguredCoins(), args[1]);
        if (args.length == 3
                && (subcommand.equals("watch")
                        || subcommand.equals("portfolio")
                        || subcommand.equals("compare")
                        || (subcommand.equals("alert") && args[1].equalsIgnoreCase("add"))))
            return complete(getConfiguredCoins(), args[2]);
        if (args.length == 3
                && subcommand.equals("alert")
                && args[1].equalsIgnoreCase("remove")
                && sender instanceof Player player)
            return complete(
                    plugin.features().alarms(player.getUniqueId()).stream()
                            .map(PriceAlarm::id)
                            .toList(),
                    args[2]);
        if (args.length == 4
                && (subcommand.equals("watch")
                        || subcommand.equals("compare")
                        || subcommand.equals("alert")))
            return complete(getConfiguredCurrencies(), args[3]);
        if ((args.length == 5 && subcommand.equals("alert"))
                || (args.length == 3 && subcommand.equals("signal")))
            return complete(List.of("above", "below"), args[args.length - 1]);
        if (args.length == 7 && subcommand.equals("alert"))
            return complete(List.of("once", "repeat"), args[6]);
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

        String currency =
                (args.length == 3 ? args[2] : getDefaultCurrency()).toUpperCase(Locale.ROOT);
        List<String> configuredCurrencies = getConfiguredCurrencies();
        if (!configuredCurrencies.contains(currency)) {
            send(
                    sender,
                    "currencyUnknown",
                    Map.of("currencies", String.join(", ", configuredCurrencies)));
            return;
        }

        Block target = player.getTargetBlockExact(6);
        if (target == null || target.getType().isAir()) {
            send(sender, "targetBlock");
            return;
        }
        if (!plugin.protection().canBuild(player, target)) {
            send(sender, "regionDenied");
            return;
        }
        if (plugin.boards().findAt(target) != null) {
            send(sender, "boardExists");
            return;
        }

        String unlimitedPermission =
                plugin.getConfig()
                        .getString("boards.unlimited-permission", "cryptocraft.limit.unlimited");
        boolean unlimited =
                (unlimitedPermission != null
                                && !unlimitedPermission.isBlank()
                                && player.hasPermission(unlimitedPermission))
                        || (plugin.getConfig().getBoolean("boards.admin-bypass-limit", true)
                                && isAdmin(player));
        int limit = getBoardLimit(player);
        int current = plugin.boards().getBoardsOwnedBy(player.getUniqueId()).size();
        if (!unlimited && current >= limit) {
            send(sender, "boardLimit", Map.of("limit", String.valueOf(limit)));
            return;
        }

        plugin.boards()
                .create(
                        player.getUniqueId(),
                        player.getName(),
                        target,
                        symbol,
                        coinId.toLowerCase(Locale.ROOT),
                        currency,
                        player.getFacing().getOppositeFace());
        plugin.boards().ensureBoardsInChunk(target.getChunk(), plugin.prices());
        plugin.prices().refresh();
        send(sender, "placeSuccess", Map.of("symbol", symbol, "currency", currency));
    }

    private void remove(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "playerOnlyRemove");
            return;
        }
        if (args.length > 2) throw new IllegalArgumentException("/crypto remove [board-id]");
        if (args.length == 2) {
            removeBoard(player, args[1]);
            send(sender, "removeSuccess");
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
        removeBoard(player, board.id());
        send(sender, "removeSuccess");
    }

    private void list(CommandSender sender) {
        List<CryptoBoard> visible =
                sender instanceof Player player
                        ? getVisibleBoards(player)
                        : plugin.boards().getBoards();
        if (visible.isEmpty()) {
            send(sender, "listEmpty");
            return;
        }
        send(sender, "listHeader", Map.of("count", String.valueOf(visible.size())));
        for (CryptoBoard board : visible) {
            String entry =
                    plugin.messages()
                            .get(
                                    "listEntry",
                                    Map.of(
                                            "symbol", board.symbol(),
                                            "currency", board.currency(),
                                            "world", board.worldName(),
                                            "x", String.valueOf(board.x()),
                                            "y", String.valueOf(board.y()),
                                            "z", String.valueOf(board.z()),
                                            "owner", board.ownerName()));
            BaseComponent line = createMessage(entry + " [" + board.id() + "]");
            if (sender instanceof Player && canTeleport(sender)) {
                line.setClickEvent(
                        new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/crypto tp " + board.id()));
                line.setHoverEvent(
                        new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                new ComponentBuilder(plugin.messages().get("listHover"))
                                        .color(ChatColor.GOLD)
                                        .create()));
            }
            sender.spigot().sendMessage(line);
        }
    }

    private void teleport(CommandSender sender, String[] args) {
        if (!canTeleport(sender)) {
            send(sender, "permissionTeleport");
            return;
        }
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
                        if (SafeTeleport.safe(floor, feet, head)
                                && world.getWorldBorder()
                                        .isInside(new Location(world, x + 0.5, y, z + 0.5))) {
                            return new Location(
                                    world,
                                    x + 0.5,
                                    y,
                                    z + 0.5,
                                    currentLocation.getYaw(),
                                    currentLocation.getPitch());
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
        String currency =
                (args.length == 3 ? args[2] : getDefaultCurrency()).toUpperCase(Locale.ROOT);
        List<String> configuredCurrencies = getConfiguredCurrencies();
        if (!configuredCurrencies.contains(currency)) {
            send(
                    sender,
                    "priceCurrencyUnknown",
                    Map.of("currencies", String.join(", ", configuredCurrencies)));
            return;
        }

        plugin.prices().requestPair(coinId, currency);
        CryptoPriceService.Quote quote = plugin.prices().getQuote(coinId, currency);
        if (quote == null) {
            send(sender, "quoteUnavailable");
            return;
        }

        send(
                sender,
                "priceHeading",
                Map.of(
                        "symbol", symbol,
                        "currency", currency,
                        "price", formatPrice(quote.price())));
        send(
                sender,
                "priceAge",
                Map.of(
                        "age",
                        String.valueOf(
                                Math.max(
                                        0,
                                        java.time.Duration.between(
                                                        quote.updatedAt(), java.time.Instant.now())
                                                .toSeconds())),
                        "time",
                        quote.updatedAt().toString(),
                        "state",
                        plugin.messages()
                                .get(
                                        plugin.prices().isStale(quote)
                                                ? "stateStale"
                                                : "stateFresh")));
        if (quote.change24h() != null) {
            String sign = quote.change24h().signum() > 0 ? "+" : "";
            String change =
                    new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US))
                            .format(quote.change24h());
            send(sender, "priceChange", Map.of("change", sign + change));
        }
    }

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player))
            throw new IllegalArgumentException(plugin.messages().get("playerOnlyFeature"));
        return player;
    }

    private void permission(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission) && !isAdmin(sender))
            throw new IllegalArgumentException(plugin.messages().get("permissionFeature"));
    }

    private CryptoBoard ownedBoard(Player player, String id) {
        CryptoBoard board = plugin.boards().getBoard(id);
        if (board == null)
            throw new IllegalArgumentException(plugin.messages().get("boardIdUnknown"));
        if (!board.ownerId().equals(player.getUniqueId()) && !isAdmin(player))
            throw new IllegalArgumentException(plugin.messages().get("removeOwnOnly"));
        return board;
    }

    private void editable(Player player, CryptoBoard board) {
        var world = plugin.boards().resolveWorld(board);
        if (world == null)
            throw new IllegalArgumentException(plugin.messages().get("teleportWorldUnloaded"));
        if (!plugin.protection()
                .canBuild(player, world.getBlockAt(board.x(), board.y(), board.z())))
            throw new IllegalArgumentException(plugin.messages().get("regionDenied"));
    }

    public void removeBoard(Player player, String id) {
        permission(player, "cryptocraft.use");
        CryptoBoard board = ownedBoard(player, id);
        editable(player, board);
        plugin.boards().remove(board);
    }

    public void editBoard(Player player, String id, String key, String value) {
        permission(player, "cryptocraft.use");
        permission(player, "cryptocraft.edit");
        CryptoBoard board = ownedBoard(player, id);
        editable(player, board);
        CryptoBoard updated =
                switch (key.toLowerCase(Locale.ROOT)) {
                    case "coin" -> {
                        QuotePair pair = plugin.features().pair(value, board.currency());
                        yield board.edited(
                                pair.symbol(),
                                pair.coinId(),
                                pair.currency(),
                                board.displayFacing(),
                                board.settings());
                    }
                    case "currency" -> {
                        QuotePair pair = plugin.features().pair(board.symbol(), value);
                        yield board.edited(
                                pair.symbol(),
                                pair.coinId(),
                                pair.currency(),
                                board.displayFacing(),
                                board.settings());
                    }
                    case "facing" ->
                            board.edited(
                                    board.symbol(),
                                    board.coinId(),
                                    board.currency(),
                                    CryptoBoardService.facing(value),
                                    board.settings());
                    default ->
                            board.edited(
                                    board.symbol(),
                                    board.coinId(),
                                    board.currency(),
                                    board.displayFacing(),
                                    board.settings().with(key, value));
                };
        // Check every occupied cell before growing/rotating the display across region boundaries.
        var world = plugin.boards().resolveWorld(updated);
        for (int tile = 0; tile < updated.settings().size() * updated.settings().size(); tile++) {
            Location at = CryptoBoardService.tileLocation(updated, world, tile);
            if (at.getY() >= world.getMaxHeight()
                    || at.getY() < world.getMinHeight()
                    || !plugin.protection().canBuild(player, at.getBlock()))
                throw new IllegalArgumentException(plugin.messages().get("regionDenied"));
        }
        plugin.boards().replace(updated);
        plugin.prices().requestPair(updated.coinId(), updated.currency());
    }

    private void edit(CommandSender sender, String[] args) {
        if (args.length < 4)
            throw new IllegalArgumentException(
                    "/crypto edit <board-id>"
                            + " <coin|currency|facing|name|height|hours|theme|style|size|range>"
                            + " <value>");
        editBoard(
                requirePlayer(sender),
                args[1],
                args[2],
                String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
        send(sender, "editSuccess");
    }

    private void status(CommandSender sender) {
        if (!isAdmin(sender))
            throw new IllegalArgumentException(plugin.messages().get("permissionFeature"));
        send(
                sender,
                "status",
                Map.of(
                        "boards",
                        String.valueOf(plugin.boards().getBoards().size()),
                        "success",
                        plugin.prices().lastSuccessAt().toString(),
                        "next",
                        plugin.prices().nextRequestAt().toString(),
                        "busy",
                        String.valueOf(plugin.prices().isInFlight()),
                        "error",
                        plugin.prices().failure() == null ? "-" : plugin.prices().failure(),
                        "storage",
                        plugin.storage().lastError() == null
                                ? "OK"
                                : plugin.storage().lastError()));
    }

    private void watch(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("list"))) {
            for (QuotePair pair : plugin.features().watchlist(player.getUniqueId()))
                price(sender, new String[] {"price", pair.symbol(), pair.currency()});
            if (plugin.features().watchlist(player.getUniqueId()).isEmpty())
                send(sender, "watchEmpty");
            return;
        }
        if (args.length < 3
                || args.length > 4
                || !List.of("add", "remove").contains(args[1].toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException(
                    "/crypto watch <add|remove> <coin> [currency] | /crypto watch list");
        plugin.features()
                .watch(
                        player.getUniqueId(),
                        plugin.features()
                                .pair(args[2], args.length == 4 ? args[3] : getDefaultCurrency()),
                        args[1].equalsIgnoreCase("add"));
        send(sender, "watchUpdated");
    }

    private void compare(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 5)
            throw new IllegalArgumentException("/crypto compare <coin> <coin> [currency] [hours]");
        String currency = args.length >= 4 ? args[3] : getDefaultCurrency();
        int hours = args.length == 5 ? Integer.parseInt(args[4]) : 24;
        if (hours < 1 || hours > 168) throw new IllegalArgumentException("hours: 1..168");
        for (String symbol : List.of(args[1], args[2])) {
            QuotePair pair = plugin.features().pair(symbol, currency);
            plugin.prices().requestPair(pair.coinId(), pair.currency());
            var quote = plugin.prices().getQuote(pair.coinId(), pair.currency());
            var history =
                    plugin.prices().getHistory(pair.coinId(), pair.currency()).stream()
                            .filter(
                                    p ->
                                            !p.observedAt()
                                                    .isBefore(
                                                            java.time.Instant.now()
                                                                    .minusSeconds(hours * 3600L)))
                            .toList();
            if (quote == null || history.size() < 2) {
                send(sender, "compareUnavailable", Map.of("pair", pair.label()));
                continue;
            }
            var first = history.getFirst();
            String percent =
                    quote.price()
                            .subtract(first.price())
                            .multiply(java.math.BigDecimal.valueOf(100))
                            .divide(first.price(), 2, java.math.RoundingMode.HALF_UP)
                            .toPlainString();
            send(
                    sender,
                    "compareResult",
                    Map.of(
                            "pair",
                            pair.label(),
                            "change",
                            percent,
                            "hours",
                            String.valueOf(hours),
                            "coverage",
                            String.valueOf(
                                    java.time.Duration.between(
                                                    first.observedAt(),
                                                    history.getLast().observedAt())
                                            .toMinutes()),
                            "state",
                            plugin.messages()
                                    .get(
                                            plugin.prices().isStale(quote)
                                                    ? "stateStale"
                                                    : "stateFresh")));
        }
    }

    private boolean above(String text) {
        if (!text.equalsIgnoreCase("above") && !text.equalsIgnoreCase("below"))
            throw new IllegalArgumentException("above|below");
        return text.equalsIgnoreCase("above");
    }

    private void alert(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        permission(sender, "cryptocraft.alert");
        if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("list"))) {
            for (PriceAlarm alarm : plugin.features().alarms(player.getUniqueId()))
                send(
                        sender,
                        "alarmEntry",
                        Map.of(
                                "id",
                                alarm.id(),
                                "pair",
                                alarm.pair().label(),
                                "direction",
                                alarm.above() ? "â‰¥" : "â‰¤",
                                "target",
                                alarm.threshold().toPlainString(),
                                "enabled",
                                String.valueOf(alarm.enabled())));
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            if (!plugin.features().removeAlarm(player.getUniqueId(), args[2]))
                throw new IllegalArgumentException(plugin.messages().get("alarmUnknown"));
            send(sender, "alarmRemoved");
            return;
        }
        if ((args.length == 6 || args.length == 7) && args[1].equalsIgnoreCase("add")) {
            if (args.length == 7
                    && !List.of("once", "repeat").contains(args[6].toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("once|repeat");
            PriceAlarm alarm =
                    plugin.features()
                            .addAlarm(
                                    player,
                                    plugin.features().pair(args[2], args[3]),
                                    above(args[4]),
                                    new java.math.BigDecimal(args[5]),
                                    args.length == 7 && args[6].equalsIgnoreCase("repeat"),
                                    null);
            send(sender, "alarmCreated", Map.of("id", alarm.id()));
            return;
        }
        throw new IllegalArgumentException(
                "/crypto alert add <coin> <currency> <above|below> <price> [once|repeat] | list |"
                        + " remove <id>");
    }

    private void signal(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        permission(sender, "cryptocraft.signal");
        if (args.length != 4)
            throw new IllegalArgumentException("/crypto signal <board-id> <above|below> <price>");
        CryptoBoard board = ownedBoard(player, args[1]);
        editable(player, board);
        PriceAlarm alarm =
                plugin.features()
                        .addAlarm(
                                player,
                                new QuotePair(board.symbol(), board.coinId(), board.currency()),
                                above(args[2]),
                                new java.math.BigDecimal(args[3]),
                                true,
                                board);
        plugin.features().updateSignals();
        send(sender, "alarmCreated", Map.of("id", alarm.id()));
    }

    private void portfolio(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        permission(sender, "cryptocraft.portfolio");
        PortfolioAccount account = plugin.features().account(player.getUniqueId());
        if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("show"))) {
            send(
                    sender,
                    "portfolioCash",
                    Map.of(
                            "cash",
                            formatPrice(account.cash()),
                            "currency",
                            account.currency(),
                            "realized",
                            formatPrice(account.realizedProfit())));
            java.math.BigDecimal total = account.cash();
            boolean complete = true;
            for (var entry : account.positions().entrySet()) {
                QuotePair pair = entry.getKey();
                plugin.prices().requestPair(pair.coinId(), pair.currency());
                var quote = plugin.prices().getQuote(pair.coinId(), pair.currency());
                if (plugin.prices().isStale(quote)) complete = false;
                if (quote != null)
                    total = total.add(entry.getValue().quantity().multiply(quote.price()));
                send(
                        sender,
                        "portfolioHolding",
                        Map.of(
                                "pair",
                                pair.label(),
                                "quantity",
                                entry.getValue().quantity().toPlainString(),
                                "value",
                                quote == null
                                        ? "?"
                                        : formatPrice(
                                                entry.getValue()
                                                        .quantity()
                                                        .multiply(quote.price()))));
            }
            send(
                    sender,
                    "portfolioTotal",
                    Map.of(
                            "value",
                            formatPrice(total),
                            "currency",
                            account.currency(),
                            "profit",
                            formatPrice(total.subtract(account.startingCash())),
                            "state",
                            plugin.messages().get(complete ? "stateFresh" : "stateStale")));
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("history")) {
            plugin.features()
                    .trades(player.getUniqueId())
                    .forEach(text -> sender.sendMessage(text));
            return;
        }
        if (args.length == 4 && List.of("buy", "sell").contains(args[1].toLowerCase(Locale.ROOT))) {
            boolean buy = args[1].equalsIgnoreCase("buy");
            QuotePair pair =
                    buy
                            ? plugin.features().pair(args[2], account.currency())
                            : account.positions().keySet().stream()
                                    .filter(held -> held.symbol().equalsIgnoreCase(args[2]))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "No holdings for " + args[2]));
            plugin.features()
                    .trade(player.getUniqueId(), pair, new java.math.BigDecimal(args[3]), buy);
            send(sender, "tradeSuccess");
            return;
        }
        throw new IllegalArgumentException(
                "/crypto portfolio [show|history] | /crypto portfolio <buy|sell> <coin>"
                        + " <quantity>");
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
        send(sender, "helpFeatures");
        if (canTeleport(sender)) {
            send(sender, "helpTeleport");
        }
        if (isAdmin(sender)) {
            send(sender, "helpReload");
        }
    }

    private void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    private void send(CommandSender sender, String key, Map<String, String> values) {
        sender.spigot().sendMessage(createMessage(plugin.messages().get(key, values)));
    }

    private BaseComponent createMessage(String content) {
        TextComponent line = new TextComponent(PREFIX);
        line.setColor(ChatColor.GOLD);
        TextComponent message = new TextComponent(content);
        message.setColor(ChatColor.GRAY);
        line.addExtra(message);
        return line;
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
                : configuredCoins.getKeys(false).stream()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
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

    int getBoardLimit(Player player) {
        int defaultLimit =
                Math.max(0, plugin.getConfig().getInt("boards.default-limit-per-player", 1));
        ConfigurationSection permissionLimits =
                plugin.getConfig().getConfigurationSection("boards.permission-limits");
        if (permissionLimits == null) {
            return defaultLimit;
        }

        Integer matchedLimit = null;
        Map<String, Object> values = permissionLimits.getValues(true);
        for (String permission : values.keySet()) {
            if (!player.hasPermission(permission)) {
                continue;
            }
            Object configuredValue = values.get(permission);
            if (configuredValue instanceof ConfigurationSection) continue;
            int configuredLimit =
                    configuredValue instanceof Number number
                            ? Math.max(0, number.intValue())
                            : defaultLimit;
            matchedLimit =
                    matchedLimit == null
                            ? configuredLimit
                            : Math.max(matchedLimit, configuredLimit);
        }
        return matchedLimit == null ? defaultLimit : matchedLimit;
    }

    private boolean isAdmin(CommandSender sender) {
        String permission =
                plugin.getConfig().getString("boards.admin-permission", "cryptocraft.admin");
        return permission != null && !permission.isBlank() && sender.hasPermission(permission);
    }

    private boolean canTeleport(CommandSender sender) {
        return sender.hasPermission("cryptocraft.teleport") || isAdmin(sender);
    }

    private String getDefaultCurrency() {
        return plugin.getConfig().getString("default-currency", "EUR");
    }

    private String formatPrice(java.math.BigDecimal price) {
        return CryptoFormat.price(
                price, plugin.messages().getLanguage().equals("de") ? Locale.GERMANY : Locale.US);
    }
}

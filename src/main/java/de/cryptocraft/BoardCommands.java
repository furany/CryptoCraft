package de.cryptocraft;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;

/** Shared, authoritative board operations used by command and inventory entry points. */
public final class BoardCommands {
    private final CryptoCraftPlugin plugin;

    public BoardCommands(CryptoCraftPlugin plugin) {
        this.plugin = plugin;
    }

    public void market(Player player, String[] args) {
        require(player, "cryptocraft.use");
        require(player, "cryptocraft.place");
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("/crypto market BTC,ETH,SOL [EUR]");
        String currency = args.length == 3 ? args[2] : plugin.getConfig().getString("default-currency", "EUR");
        List<QuotePair> pairs = parsePairs(args[1], currency);
        Block target = player.getTargetBlockExact(6);
        if (target == null || target.getType().isAir())
            throw new IllegalArgumentException(plugin.messages().get("targetBlock"));
        if (plugin.boards().findAt(target) != null)
            throw new IllegalArgumentException(plugin.messages().get("boardExists"));
        checkLimit(player);
        QuotePair first = pairs.getFirst();
        CryptoBoard candidate = new CryptoBoard("preview", player.getUniqueId(), player.getName(),
                target.getWorld().getUID(), target.getWorld().getName(), target.getX(), target.getY(), target.getZ(),
                first.symbol(), first.coinId(), first.currency(), player.getFacing().getOppositeFace(),
                new BoardSettings("", plugin.getConfig().getDouble("boards.display-height", 1.35),
                        plugin.getConfig().getInt("boards.chart-history-hours", 24), "dark", "chart", 2, false), pairs, false);
        validateFootprint(player, candidate);
        CryptoBoard created = plugin.boards().createMarket(player.getUniqueId(), player.getName(), target,
                pairs, candidate.displayFacing());
        plugin.boards().ensureBoardsInChunk(target.getChunk(), plugin.prices());
        plugin.prices().refresh();
        player.sendMessage("§6[CryptoCraft] §f" + local("Market board created: ", "Markttafel erstellt: ") + created.id());
    }

    public void terminal(Player player, String[] args) {
        require(player, "cryptocraft.use");
        terminalAdmin(player);
        if (args.length != 3 || !Set.of("enable", "disable", "on", "off", "true", "false").contains(args[2].toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("/crypto terminal <board-id> <enable|disable>");
        CryptoBoard board = plugin.boards().getBoard(args[1]);
        if (board == null) throw new IllegalArgumentException(plugin.messages().get("boardIdUnknown"));
        boolean enabled = args[2].equalsIgnoreCase("enable") || args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
        CryptoBoard updated = board.withTerminal(enabled);
        if (enabled && plugin.boards().terminalStyle().equals("computer")
                && updated.settings().height() < TerminalModel.MINIMUM_HEIGHT)
            updated = updated.edited(updated.symbol(), updated.coinId(), updated.currency(), updated.displayFacing(),
                    updated.settings().with("height", String.valueOf(TerminalModel.MINIMUM_HEIGHT)));
        checkProtectionFootprint(player, board);
        validateFootprint(player, updated);
        plugin.boards().replace(updated);
        player.sendMessage("§6[CryptoCraft] §f" + (enabled
                ? local("Trading terminal enabled. Right-click the monitor to open the exchange.",
                        "Handelsterminal aktiviert. Rechtsklick auf den Monitor öffnet die Börse.")
                : local("Trading terminal disabled.", "Handelsterminal deaktiviert.")));
    }

    public void editCoins(Player player, String id, String coins) {
        require(player, "cryptocraft.use");
        require(player, "cryptocraft.edit");
        CryptoBoard board = plugin.boards().getBoard(id);
        if (board == null) throw new IllegalArgumentException(plugin.messages().get("boardIdUnknown"));
        if (!board.ownerId().equals(player.getUniqueId()) && !plugin.isAdmin(player)
                && !(board.terminal() && player.hasPermission("cryptocraft.terminal.admin")))
            throw new IllegalArgumentException(plugin.messages().get("removeOwnOnly"));
        editable(player, board);
        if (!board.market()) throw new IllegalArgumentException(local(
                "Use 'coin' for a single-coin board.", "Bei Einzelcoin-Tafeln bitte 'coin' verwenden."));
        CryptoBoard updated = board.withMarketPairs(parsePairs(coins, board.currency()));
        validateFootprint(player, updated);
        plugin.boards().replace(updated);
        plugin.prices().refresh();
    }

    public void editable(Player player, CryptoBoard board) {
        if (board.terminal()) terminalAdmin(player);
        checkProtectionFootprint(player, board);
    }

    public void validateFootprint(Player player, CryptoBoard board) {
        if (board.terminal() && plugin.boards().terminalStyle().equals("computer")
                && board.settings().height() < TerminalModel.MINIMUM_HEIGHT)
            throw new IllegalArgumentException(local("Computer terminals need a display height of at least 2.0 for their stand and keyboard.",
                    "Computer-Terminals benötigen für Standfuß und Tastatur eine Anzeigehöhe von mindestens 2,0."));
        checkProtectionFootprint(player, board);
    }

    private void checkProtectionFootprint(Player player, CryptoBoard board) {
        var world = plugin.boards().resolveWorld(board);
        if (world == null) throw new IllegalArgumentException(plugin.messages().get("teleportWorldUnloaded"));
        for (Block block : plugin.boards().footprint(board)) {
            Location at = block.getLocation().add(.5, .5, .5);
            if (block.getY() < world.getMinHeight() || block.getY() >= world.getMaxHeight()
                    || !world.getWorldBorder().isInside(at) || !plugin.protection().canBuild(player, block))
                throw new IllegalArgumentException(plugin.messages().get("regionDenied"));
        }
    }

    private List<QuotePair> parsePairs(String csv, String currency) {
        String[] symbols = csv.split(",", -1);
        if (symbols.length < 1 || symbols.length > 6)
            throw new IllegalArgumentException(local("Choose 1–6 different coins.", "Bitte 1–6 unterschiedliche Coins auswählen."));
        List<QuotePair> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String symbol : symbols) {
            QuotePair pair = plugin.features().pair(symbol.trim(), currency);
            if (plugin.getConfig().getStringList("trading.display-disabled-coins").stream()
                    .anyMatch(value -> value.equalsIgnoreCase(pair.symbol()) || value.equalsIgnoreCase(pair.coinId())))
                throw new IllegalArgumentException(local("This coin is disabled for new displays.",
                        "Dieser Coin ist für neue Anzeigen deaktiviert."));
            if (!ids.add(pair.coinId())) throw new IllegalArgumentException(local("Each coin can appear only once.", "Jeder Coin darf nur einmal vorkommen."));
            result.add(pair);
        }
        return List.copyOf(result);
    }

    private void terminalAdmin(Player player) {
        if (!player.hasPermission("cryptocraft.terminal.admin"))
            throw new IllegalArgumentException(plugin.messages().get("permissionFeature"));
    }

    private void require(Player player, String permission) {
        if (!player.hasPermission(permission) && !plugin.isAdmin(player))
            throw new IllegalArgumentException(plugin.messages().get("permissionFeature"));
    }

    private void checkLimit(Player player) {
        String unlimited = plugin.getConfig().getString("boards.unlimited-permission", "cryptocraft.limit.unlimited");
        if ((!unlimited.isBlank() && player.hasPermission(unlimited))
                || (plugin.isAdmin(player) && plugin.getConfig().getBoolean("boards.admin-bypass-limit", true))) return;
        int limit = new CryptoCommand(plugin).getBoardLimit(player);
        if (plugin.boards().getBoardsOwnedBy(player.getUniqueId()).size() >= limit)
            throw new IllegalArgumentException(plugin.messages().get("boardLimit", Map.of("limit", String.valueOf(limit))));
    }

    private String local(String english, String german) {
        return plugin.messages().getLanguage().equals("de") ? german : english;
    }
}

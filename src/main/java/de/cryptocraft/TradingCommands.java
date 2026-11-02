package de.cryptocraft;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** Command entry points open the same confirmed flows as PC terminals. */
public final class TradingCommands {
    private final CryptoCraftPlugin plugin;
    public TradingCommands(CryptoCraftPlugin plugin) { this.plugin = plugin; }

    public boolean handle(CommandSender sender, String[] args) {
        if (args.length == 0 || !List.of("trade", "depot", "orders").contains(args[0].toLowerCase(Locale.ROOT))) return false;
        if (!(sender instanceof Player player)) throw new IllegalArgumentException(plugin.messages().get("playerOnlyFeature"));
        plugin.menu().requireUse(player);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "depot" -> {
                if (args.length == 2 && args[1].equalsIgnoreCase("history")) plugin.tradingMenu().history(player, 0);
                else if (args.length == 1) plugin.tradingMenu().depot(player, 0);
                else throw new IllegalArgumentException("/crypto depot [history]");
            }
            case "orders" -> {
                if (args.length == 1) plugin.tradingMenu().orders(player, 0);
                else if (args.length == 3 && args[1].equalsIgnoreCase("cancel")) plugin.tradingMenu().cancel(player, args[2], 0);
                else throw new IllegalArgumentException("/crypto orders [cancel <id>]");
            }
            case "trade" -> {
                if (args.length == 1) plugin.tradingMenu().open(player);
                else if (args.length == 2) plugin.tradingMenu().openCoin(player, args[1]);
                else if (args.length == 4 && List.of("buy", "buy-quantity", "sell").contains(args[1].toLowerCase(Locale.ROOT))) {
                    boolean buy = !args[1].equalsIgnoreCase("sell");
                    plugin.tradingMenu().preview(player, args[2], buy, plugin.menu().positive(args[3]), !args[1].equalsIgnoreCase("buy"));
                } else throw new IllegalArgumentException("/crypto trade [coin] | trade <buy|buy-quantity|sell> <coin> <amount>");
            }
        }
        return true;
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length < 2) return List.of();
        List<String> candidates = List.of();
        if (args[0].equalsIgnoreCase("orders") && args.length == 2) candidates = List.of("cancel");
        else if (args[0].equalsIgnoreCase("depot") && args.length == 2) candidates = List.of("history");
        else if (args[0].equalsIgnoreCase("trade")) {
            var section = plugin.getConfig().getConfigurationSection("coins");
            var values = new java.util.ArrayList<String>();
            if (section != null && (args.length == 2 || args.length == 3)) {
                List<String> disabled = plugin.getConfig().getStringList("trading.disabled-coins");
                values.addAll(section.getKeys(false).stream().filter(symbol -> disabled.stream().noneMatch(symbol::equalsIgnoreCase)).toList());
            }
            if (args.length == 2) values.addAll(List.of("buy", "buy-quantity", "sell"));
            candidates = values;
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}

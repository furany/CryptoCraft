package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Explicit administrative operations. Every money correction requires an audit reason. */
public final class EconomyAdminCommands {
    private final CryptoCraftPlugin plugin;
    public EconomyAdminCommands(CryptoCraftPlugin plugin) { this.plugin = plugin; }
    public boolean handle(CommandSender sender, String[] args) {
        if (args.length == 0 || !List.of("economy", "coin").contains(args[0].toLowerCase(Locale.ROOT))) return false;
        if (!plugin.isAdmin(sender) && !sender.hasPermission("cryptocraft.economy.admin"))
            throw new IllegalArgumentException(plugin.messages().get("permissionFeature"));
        if (args[0].equalsIgnoreCase("coin")) { coin(sender, args); return true; }
        TradingService service = plugin.trading();
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "status" -> {
                send(sender, service.health());
                result(sender, service.treasury(), balance -> send(sender, local("Reserve: ", "Reserve: ") + service.format(balance)));
                result(sender, service.unresolved(), rows -> {
                    for (var row : rows) send(sender, row.id() + " · " + row.owner() + " · " + row.direction()
                            + " · " + service.format(row.amount()) + " · " + row.state());
                    send(sender, local("Unresolved transfers: ", "Ungeklärte Buchungen: ") + rows.size());
                });
            }
            case "reserve" -> {
                if (!(sender instanceof Player player)) throw new IllegalArgumentException(plugin.messages().get("playerOnlyFeature"));
                if (args.length != 4 || !List.of("deposit", "withdraw").contains(args[2].toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("/crypto economy reserve <deposit|withdraw> <amount>");
                result(sender, service.reserve(player.getUniqueId(), new BigDecimal(args[3]), args[2].equalsIgnoreCase("deposit")),
                        ignored -> send(sender, local("Reserve transfer prepared; check status for completion.", "Reservebuchung vorbereitet; Status zeigt den Abschluss.")));
            }
            case "grant" -> {
                if (args.length < 6) throw new IllegalArgumentException("/crypto economy grant <player|uuid> <coin> <quantity> <reason>");
                UUID owner = playerId(args[2]);
                QuotePair pair = plugin.configuredPair(args[3], service.settings().referenceCurrency());
                if (pair == null) throw new IllegalArgumentException(plugin.messages().get("pairInvalid"));
                String reason = sender.getName() + ": " + String.join(" ", Arrays.copyOfRange(args, 5, args.length));
                result(sender, service.grant(owner, pair, new BigDecimal(args[4]), reason),
                        ignored -> send(sender, local("Coin grant recorded in the audit log.", "Coinvergabe im Buchungsjournal protokolliert.")));
            }
            case "resolve" -> {
                if (args.length < 5 || !List.of("applied", "rejected").contains(args[3].toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("/crypto economy resolve <transfer-id> <applied|rejected> <reason>");
                String reason = sender.getName() + ": " + String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                result(sender, service.reconcile(args[2], args[3].equalsIgnoreCase("applied"), reason),
                        ignored -> send(sender, local("Reconciliation recorded. No external transfer was repeated.", "Abgleich protokolliert. Es wurde keine externe Buchung wiederholt.")));
            }
            default -> throw new IllegalArgumentException("/crypto economy <status|reserve|grant|resolve>");
        }
        return true;
    }

    private void coin(CommandSender sender, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("/crypto coin <list|search|add|enable|disable|show|hide> ...");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            var coins = plugin.getConfig().getConfigurationSection("coins");
            if (coins != null) for (String symbol : coins.getKeys(false))
                send(sender, symbol + " → " + coins.getString(symbol)
                        + (plugin.getConfig().getStringList("trading.disabled-coins").contains(symbol) ? local(" (trading disabled)", " (Handel deaktiviert)") : ""));
            return;
        }
        if (action.equals("search")) {
            if (args.length < 3) throw new IllegalArgumentException("/crypto coin search <name|symbol|provider-id>");
            String query = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            result(sender, CoinCatalog.search(query), matches -> {
                if (matches.isEmpty()) send(sender, local("No matching coins.", "Keine passenden Coins."));
                for (var match : matches) send(sender, match.symbol().toUpperCase(Locale.ROOT) + " · " + match.name() + " · " + match.id());
                send(sender, "/crypto coin add <SYMBOL> <provider-id>");
            });
            return;
        }
        if (args.length < 3) throw new IllegalArgumentException("/crypto coin " + action + " <SYMBOL> [provider-id]");
        String symbol = args[2].toUpperCase(Locale.ROOT);
        YamlConfiguration candidate = new YamlConfiguration();
        try { candidate.loadFromString(plugin.getConfig().saveToString()); }
        catch (Exception e) { throw new IllegalStateException(e); }
        if (action.equals("add")) {
            if (args.length != 4) throw new IllegalArgumentException("/crypto coin add <SYMBOL> <provider-id>");
            String id = args[3].toLowerCase(Locale.ROOT);
            new QuotePair(symbol, id, plugin.getConfig().getString("default-currency", "EUR"));
            String old = candidate.getString("coins." + symbol);
            if (old != null && !old.equals(id)) throw new IllegalArgumentException(local(
                    "This symbol already identifies another coin. Use a different symbol.", "Dieses Symbol gehört bereits zu einem anderen Coin. Bitte anderes Symbol wählen."));
            var coins = candidate.getConfigurationSection("coins");
            if (coins != null && coins.getKeys(false).stream().anyMatch(s -> !s.equals(symbol) && id.equals(coins.getString(s))))
                throw new IllegalArgumentException(local("Provider ID is already configured.", "Diese Anbieter-ID ist bereits konfiguriert."));
            candidate.set("coins." + symbol, id);
            // New catalog additions are display-only until an administrator explicitly enables trading.
            List<String> disabled = new ArrayList<>(candidate.getStringList("trading.disabled-coins"));
            if (old == null && !disabled.contains(symbol)) disabled.add(symbol);
            candidate.set("trading.disabled-coins", disabled);
        } else {
            if (!List.of("enable", "disable", "show", "hide").contains(action) || args.length != 3)
                throw new IllegalArgumentException("/crypto coin <enable|disable|show|hide> <SYMBOL>");
            if (!candidate.contains("coins." + symbol)) throw new IllegalArgumentException(plugin.messages().get("priceCoinUnknown"));
            String key = List.of("show", "hide").contains(action) ? "trading.display-disabled-coins" : "trading.disabled-coins";
            List<String> values = new ArrayList<>(candidate.getStringList(key));
            if (List.of("disable", "hide").contains(action)) { if (!values.contains(symbol)) values.add(symbol); }
            else values.remove(symbol);
            candidate.set(key, values);
        }
        plugin.applyConfiguration(candidate);
        send(sender, local("Coin settings saved. Existing holdings are preserved.", "Coin-Einstellungen gespeichert. Bestehende Bestände bleiben erhalten."));
    }

    private UUID playerId(String text) {
        try { return UUID.fromString(text); } catch (IllegalArgumentException ignored) { }
        var player = Bukkit.getPlayerExact(text);
        if (player == null) throw new IllegalArgumentException(local("Unknown player; use their UUID.", "Unbekannter Spieler; bitte UUID verwenden."));
        return player.getUniqueId();
    }
    private <T> void result(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((value, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player player && !player.isOnline()) return;
                if (failure != null) send(sender, plugin.trading().error(failure)); else success.accept(value);
            });
        });
    }
    public List<String> complete(CommandSender sender, String[] args) {
        if (!plugin.isAdmin(sender) && !sender.hasPermission("cryptocraft.economy.admin")) return List.of();
        List<String> values = args.length == 2 ? args[0].equalsIgnoreCase("coin")
                ? List.of("list", "search", "add", "enable", "disable", "show", "hide")
                : List.of("status", "reserve", "grant", "resolve")
                : args.length == 3 && args[1].equalsIgnoreCase("reserve") ? List.of("deposit", "withdraw") : List.of();
        return values.stream().filter(value -> value.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))).toList();
    }
    private void send(CommandSender sender, String text) { sender.sendMessage("§6[CryptoCraft] §f" + text); }
    private String local(String en, String de) { return plugin.messages().getLanguage().equals("de") ? de : en; }
}

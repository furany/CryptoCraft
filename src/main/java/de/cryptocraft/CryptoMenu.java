package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Inventory views share one guarded event boundary; chat never mutates Bukkit state. */
public final class CryptoMenu implements Listener {
    private final CryptoCraftPlugin plugin;
    private final CryptoCommand commands;
    private final Map<UUID, Input> inputs = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> boardPages = new HashMap<>();

    public CryptoMenu(CryptoCraftPlugin plugin, CryptoCommand commands) {
        this.plugin = plugin;
        this.commands = commands;
    }

    public String text(String key) { return plugin.messages().get(key); }
    public String text(String key, String name, Object value) {
        return plugin.messages().get(key, Map.of(name, String.valueOf(value)));
    }
    public String number(BigDecimal value) {
        return CryptoFormat.price(value, plugin.messages().getLanguage().equals("de") ? Locale.GERMANY : Locale.US);
    }
    public void notify(Player player, String message) { player.sendMessage("§6[CryptoCraft] §f" + message); }
    public void error(Player player, Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        player.sendMessage("§6[CryptoCraft] §c" + (plugin.trading() != null ? plugin.trading().error(error)
                : cause.getMessage() == null ? text("menuOperationFailed") : cause.getMessage()));
    }
    public void requireUse(Player player) {
        if (!player.hasPermission("cryptocraft.use")) throw new IllegalArgumentException(text("permissionUse"));
    }
    private void permission(Player player, String node) {
        requireUse(player);
        if (!player.hasPermission(node) && !plugin.isAdmin(player)) throw new IllegalArgumentException(text("permissionFeature"));
    }

    public Screen screen(String title) { return new Screen(title); }
    public void open(Player player, int requestedPage) {
        requireUse(player);
        List<CryptoBoard> boards = plugin.isAdmin(player) || player.hasPermission("cryptocraft.terminal.admin")
                ? plugin.boards().getBoards() : plugin.boards().getBoardsOwnedBy(player.getUniqueId());
        int page = page(requestedPage, boards.size());
        boardPages.put(player.getUniqueId(), page);
        Screen view = screen(text("menuTitle"));
        if (boards.isEmpty()) view.item(22, Material.MAP, text("listEmpty"), List.of(text("menuCreateHint")));
        for (int i = page * 45; i < Math.min(boards.size(), (page + 1) * 45); i++) {
            CryptoBoard board = boards.get(i);
            view.button(i % 45, Material.FILLED_MAP, boardLabel(board), List.of(board.worldName() + " " + board.x() + ", " + board.y() + ", " + board.z(), text("menuEditHint")), p -> openBoard(p, board.id()));
        }
        pagination(view, page, boards.size(), (p, n) -> open(p, n));
        view.button(46, Material.CRAFTING_TABLE, text("menuCreate"), List.of(text("menuCreateHint")), this::create);
        view.button(47, Material.GOLD_INGOT, text("menuTrading"), List.of(), p -> p.performCommand("crypto trade"));
        view.button(48, Material.SPYGLASS, text("menuWatchlist"), List.of(), p -> watch(p, 0));
        view.button(49, Material.BELL, text("menuAlerts"), List.of(), p -> alerts(p, 0));
        view.button(50, Material.CHEST, text("menuDepot"), List.of(), p -> p.performCommand("crypto depot"));
        view.button(51, Material.BOOK, text("menuOrders"), List.of(), p -> p.performCommand("crypto orders"));
        view.button(52, Material.PAPER, text("menuSimulation"), List.of(text("menuSimulationHint")), p -> { p.closeInventory(); p.performCommand("crypto portfolio"); });
        view.show(player);
    }

    private String boardLabel(CryptoBoard board) {
        return board.settings().name().isBlank() ? (board.market() ? text("menuMarket") : board.symbol()) + " / " + board.currency() : board.settings().name();
    }
    public void openBoard(Player player, String id) {
        requireUse(player);
        CryptoBoard board = plugin.boards().getBoard(id);
        if (board == null) throw new IllegalArgumentException(text("boardIdUnknown"));
        Screen view = screen(boardLabel(board));
        int index = 0;
        for (QuotePair pair : board.quotePairs()) view.button(10 + index++, Material.GOLD_INGOT, pair.label(), quoteLore(pair), p -> pairDetails(p, pair, () -> openBoard(p, id)));
        if (canEdit(player, board)) view.button(40, Material.COMPARATOR, text("menuEdit"), List.of(), p -> edit(p, id));
        if (player.hasPermission("cryptocraft.terminal.admin")) view.button(42, Material.LECTERN, text("menuTerminal") + ": " + text(board.terminal() ? "menuYes" : "menuNo"), List.of(), p -> {
            CryptoBoard current = plugin.boards().getBoard(id);
            if (current == null) throw new IllegalArgumentException(text("boardIdUnknown"));
            new BoardCommands(plugin).terminal(p, new String[] {"terminal", id, current.terminal() ? "disable" : "enable"});
            openBoard(p, id);
        });
        view.button(49, Material.ARROW, text("menuBack"), List.of(), p -> open(p, boardPages.getOrDefault(p.getUniqueId(), 0)));
        view.show(player);
    }
    private CryptoBoard owned(Player player, String id) {
        requireUse(player);
        CryptoBoard board = plugin.boards().getBoard(id);
        if (board == null || !canEdit(player, board)) throw new IllegalArgumentException(text("boardIdUnknown"));
        return board;
    }
    private boolean canEdit(Player player, CryptoBoard board) {
        if (board.terminal()) return player.hasPermission("cryptocraft.terminal.admin");
        return board.ownerId().equals(player.getUniqueId()) || plugin.isAdmin(player);
    }

    private void edit(Player player, String id) {
        CryptoBoard board = owned(player, id);
        Screen view = screen(text("menuEdit"));
        view.button(10, Material.GOLD_INGOT, text("menuCoin") + ": " + (board.market() ? board.quotePairs().size() : board.symbol()), List.of(), p -> {
            if (board.market()) marketCoins(p, id);
            else selectCoin(p, "", 0, symbol -> update(p, id, "coin", symbol), () -> edit(p, id));
        });
        view.button(11, Material.EMERALD, text("menuCurrency") + ": " + board.currency(), List.of(), p -> choose(p, text("menuCurrency"), plugin.getConfig().getStringList("currencies"), value -> update(p, id, "currency", value), () -> edit(p, id)));
        if (!board.market()) view.button(12, Material.CLOCK, text("menuHours") + ": " + board.settings().hours() + "h", List.of(), p -> choose(p, text("menuHours"), List.of("1", "6", "24", "72", "168"), value -> update(p, id, "hours", value), () -> edit(p, id)));
        view.button(13, Material.COMPASS, text("menuFacing") + ": " + board.displayFacing(), List.of(), p -> choose(p, text("menuFacing"), List.of("north", "east", "south", "west"), value -> update(p, id, "facing", value), () -> edit(p, id)));
        view.button(14, Material.LADDER, text("menuHeight") + ": " + board.settings().height(), List.of(), p -> height(p, id));
        view.button(15, Material.PAINTING, text("menuTheme") + ": " + board.settings().theme(), List.of(), p -> choose(p, text("menuTheme"), List.of("dark", "light", "ocean"), value -> update(p, id, "theme", value), () -> edit(p, id)));
        if (!board.market()) view.button(16, Material.PAPER, text("menuStyle") + ": " + board.settings().style(), List.of(), p -> choose(p, text("menuStyle"), List.of("chart", "price", "compact"), value -> update(p, id, "style", value), () -> edit(p, id)));
        view.button(19, Material.MAP, text("menuSize") + ": " + board.settings().size(), List.of(), p -> choose(p, text("menuSize"), List.of("1", "2", "3"), value -> update(p, id, "size", value), () -> edit(p, id)));
        view.button(20, Material.NAME_TAG, text("menuName") + ": " + board.settings().name(), List.of(), p -> input(p, text("menuRenamePrompt"), value -> update(p, id, "name", value.equals("-") ? "" : value), () -> edit(p, id)));
        if (!board.market()) view.button(21, Material.REDSTONE, text("menuRange") + ": " + text(board.settings().showRange() ? "menuYes" : "menuNo"), List.of(), p -> update(p, id, "range", String.valueOf(!owned(p, id).settings().showRange())));
        if (player.hasPermission("cryptocraft.terminal.admin")) view.button(23, Material.LECTERN, text("menuTerminal") + ": " + text(board.terminal() ? "menuYes" : "menuNo"), List.of(), p -> {
            new BoardCommands(plugin).terminal(p, new String[] {"terminal", id, owned(p, id).terminal() ? "disable" : "enable"});
            openBoard(p, id);
        });
        view.button(45, Material.ARROW, text("menuBack"), List.of(), p -> open(p, boardPages.getOrDefault(p.getUniqueId(), 0)));
        view.button(49, Material.ENDER_PEARL, text("menuTeleport"), List.of(), p -> { p.closeInventory(); p.performCommand("crypto tp " + id); });
        view.button(53, Material.BARRIER, text("menuRemove"), List.of(), p -> confirm(p, text("menuConfirm"), List.of(boardLabel(owned(p, id))), () -> { commands.removeBoard(p, id); open(p, boardPages.getOrDefault(p.getUniqueId(), 0)); }, () -> edit(p, id)));
        view.show(player);
    }
    private void update(Player player, String id, String setting, String value) { commands.editBoard(player, id, setting, value); edit(player, id); }

    private void height(Player player, String id) {
        CryptoBoard board = owned(player, id);
        Screen view = screen(text("menuHeight"));
        double[] changes = {-0.5, -0.1, 0.1, 0.5};
        for (int i = 0; i < changes.length; i++) {
            double delta = changes[i];
            view.button(20 + i * 2, delta < 0 ? Material.RED_DYE : Material.LIME_DYE, (delta > 0 ? "+" : "") + delta, List.of(text("menuHeight") + ": " + board.settings().height()), p -> {
                double value = Math.max(0.5, Math.min(8, Math.round((owned(p, id).settings().height() + delta) * 100) / 100.0));
                commands.editBoard(p, id, "height", String.valueOf(value)); height(p, id);
            });
        }
        view.button(49, Material.ARROW, text("menuBack"), List.of(), p -> edit(p, id)); view.show(player);
    }

    private void marketCoins(Player player, String id) {
        CryptoBoard board = owned(player, id);
        List<String> symbols = board.quotePairs().stream().map(QuotePair::symbol).toList();
        Screen view = screen(text("menuMarketCoins"));
        for (int i = 0; i < symbols.size(); i++) {
            int index = i;
            String symbol = symbols.get(i);
            view.button(10 + i, Material.GOLD_INGOT, (i + 1) + ". " + symbol, List.of(text("menuMarketRowHint")), p -> {
                Screen row = screen(symbol);
                if (symbols.size() > 1) row.button(22, Material.BARRIER, text("menuDelete"), List.of(), who -> {
                    List<String> next = marketSymbols(who, id); next.remove(symbol); commands.editBoard(who, id, "coins", String.join(",", next)); marketCoins(who, id);
                });
                if (index > 0) row.button(20, Material.ARROW, text("menuMoveLeft"), List.of(), who -> {
                    List<String> next = marketSymbols(who, id); int current = next.indexOf(symbol); if (current > 0) Collections.swap(next, current, current - 1);
                    commands.editBoard(who, id, "coins", String.join(",", next)); marketCoins(who, id);
                });
                if (index + 1 < symbols.size()) row.button(24, Material.ARROW, text("menuMoveRight"), List.of(), who -> {
                    List<String> next = marketSymbols(who, id); int current = next.indexOf(symbol); if (current >= 0 && current + 1 < next.size()) Collections.swap(next, current, current + 1);
                    commands.editBoard(who, id, "coins", String.join(",", next)); marketCoins(who, id);
                });
                row.button(49, Material.ARROW, text("menuBack"), List.of(), who -> marketCoins(who, id)); row.show(p);
            });
        }
        if (symbols.size() < 6) view.button(40, Material.LIME_DYE, text("menuAdd"), List.of(), p -> selectCoin(p, "", 0, symbol -> {
            List<String> next = marketSymbols(p, id); if (!next.contains(symbol)) next.add(symbol);
            commands.editBoard(p, id, "coins", String.join(",", next)); marketCoins(p, id);
        }, () -> marketCoins(p, id)));
        view.button(49, Material.ARROW, text("menuBack"), List.of(), p -> edit(p, id)); view.show(player);
    }
    private List<String> marketSymbols(Player player, String id) { return new ArrayList<>(owned(player, id).quotePairs().stream().map(QuotePair::symbol).toList()); }

    private void create(Player player) {
        permission(player, "cryptocraft.place");
        Screen view = screen(text("menuCreate"));
        view.button(20, Material.FILLED_MAP, text("menuSingleBoard"), List.of(text("menuCreateHint")), p -> selectCoin(p, "", 0, symbol -> chooseCurrency(p, currency -> {
            p.closeInventory(); p.performCommand("crypto place " + symbol + " " + currency);
        }, () -> create(p)), () -> create(p)));
        view.button(24, Material.ITEM_FRAME, text("menuMarket"), List.of(text("menuCreateHint")), p -> input(p, text("menuMarketPrompt"), value -> chooseCurrency(p, currency -> {
            p.closeInventory(); p.performCommand("crypto market " + value.replace(" ", "") + " " + currency);
        }, () -> create(p)), () -> create(p)));
        view.button(49, Material.ARROW, text("menuBack"), List.of(), p -> open(p, 0)); view.show(player);
    }

    public void watch(Player player, int requestedPage) {
        requireUse(player);
        List<QuotePair> pairs = plugin.features().watchlist(player.getUniqueId());
        int page = page(requestedPage, pairs.size());
        Screen view = screen(text("menuWatchlist"));
        if (pairs.isEmpty()) view.item(22, Material.SPYGLASS, text("menuEmpty"), List.of());
        for (int i = page * 45; i < Math.min(pairs.size(), (page + 1) * 45); i++) {
            QuotePair pair = pairs.get(i); view.button(i % 45, Material.GOLD_INGOT, pair.label(), quoteLore(pair), p -> pairDetails(p, pair, () -> watch(p, page)));
        }
        pagination(view, page, pairs.size(), this::watch);
        view.button(48, Material.LIME_DYE, text("menuAdd"), List.of(), p -> selectPair(p, pair -> { requireUse(p); plugin.features().watch(p.getUniqueId(), pair, true); watch(p, page); }, () -> watch(p, page)));
        view.button(49, Material.ARROW, text("menuBack"), List.of(), p -> open(p, boardPages.getOrDefault(p.getUniqueId(), 0))); view.show(player);
    }

    public List<String> quoteLore(QuotePair pair) {
        var quote = plugin.prices().getQuote(pair.coinId(), pair.currency());
        if (quote == null) return List.of(text("quoteUnavailable"));
        List<String> lore = new ArrayList<>(List.of(number(quote.price()) + " " + pair.currency(), text("menuQuoteTime", "time", quote.updatedAt()), text(plugin.prices().isStale(quote) ? "stateStale" : "stateFresh")));
        if (!quote.sourceVerified()) lore.add(text("menuQuoteUnverified"));
        return lore;
    }
    private void pairDetails(Player player, QuotePair pair, Runnable back) {
        Screen view = screen(pair.label());
        view.item(13, Material.GOLD_INGOT, pair.label(), quoteLore(pair));
        boolean watched = plugin.features().watchlist(player.getUniqueId()).contains(pair);
        view.button(20, Material.SPYGLASS, text(watched ? "menuWatchRemove" : "menuWatchAdd"), List.of(), p -> {
            requireUse(p);
            if (watched) confirm(p, text("menuDeleteConfirm"), List.of(pair.label()), () -> { requireUse(p); plugin.features().watch(p.getUniqueId(), pair, false); back.run(); }, back);
            else {
                if (!displayEnabled(pair.symbol())) throw new IllegalArgumentException(text("menuDisplayDisabled"));
                plugin.features().watch(p.getUniqueId(), pair, true); pairDetails(p, pair, back);
            }
        });
        view.button(22, Material.BELL, text("menuAlertCreate"), List.of(), p -> alertWizard(p, pair, true, false, back));
        view.button(24, Material.EMERALD, text("menuTrading"), List.of(), p -> p.performCommand("crypto trade " + pair.symbol()));
        view.button(49, Material.ARROW, text("menuBack"), List.of(), p -> back.run()); view.show(player);
    }

    public void alerts(Player player, int requestedPage) {
        permission(player, "cryptocraft.alert");
        List<PriceAlarm> alarms = plugin.features().alarms(player.getUniqueId());
        int page = page(requestedPage, alarms.size());
        Screen view = screen(text("menuAlerts"));
        if (alarms.isEmpty()) view.item(22, Material.BELL, text("menuEmpty"), List.of());
        for (int i = page * 45; i < Math.min(alarms.size(), (page + 1) * 45); i++) {
            PriceAlarm alarm = alarms.get(i);
            view.button(i % 45, alarm.enabled() ? Material.BELL : Material.GRAY_DYE, alarm.pair().label(), List.of((alarm.above() ? "≥ " : "≤ ") + number(alarm.threshold()), text(alarm.repeat() ? "menuRepeat" : "menuOnce"), text(alarm.enabled() ? "menuActive" : "menuCompleted"), text("menuDeleteHint")), p -> confirm(p, text("menuDeleteConfirm"), List.of(alarm.pair().label()), () -> {
                permission(p, "cryptocraft.alert");
                if (!plugin.features().removeAlarm(p.getUniqueId(), alarm.id())) throw new IllegalArgumentException(text("alarmUnknown"));
                alerts(p, page);
            }, () -> alerts(p, page)));
        }
        pagination(view, page, alarms.size(), this::alerts);
        view.button(48, Material.LIME_DYE, text("menuAlertCreate"), List.of(), p -> selectPair(p, pair -> alertWizard(p, pair, true, false, () -> alerts(p, page)), () -> alerts(p, page)));
        view.button(49, Material.ARROW, text("menuBack"), List.of(), p -> open(p, boardPages.getOrDefault(p.getUniqueId(), 0))); view.show(player);
    }
    private void alertWizard(Player player, QuotePair pair, boolean above, boolean repeat, Runnable back) {
        permission(player, "cryptocraft.alert");
        Screen view = screen(text("menuAlertCreate"));
        view.item(13, Material.GOLD_INGOT, pair.label(), quoteLore(pair));
        view.button(20, Material.COMPARATOR, text(above ? "menuAbove" : "menuBelow"), List.of(), p -> alertWizard(p, pair, !above, repeat, back));
        view.button(24, Material.CLOCK, text(repeat ? "menuRepeat" : "menuOnce"), List.of(), p -> alertWizard(p, pair, above, !repeat, back));
        view.button(31, Material.NAME_TAG, text("menuEnterThreshold"), List.of(), p -> input(p, text("menuThresholdPrompt"), value -> {
            BigDecimal threshold = positive(value);
            confirm(p, text("menuAlertCreate"), List.of(pair.label(), (above ? "≥ " : "≤ ") + number(threshold), text(repeat ? "menuRepeat" : "menuOnce")), () -> {
                permission(p, "cryptocraft.alert");
                if (!displayEnabled(pair.symbol())) throw new IllegalArgumentException(text("menuDisplayDisabled"));
                plugin.features().addAlarm(p, pair, above, threshold, repeat, null); alerts(p, 0);
            }, () -> alertWizard(p, pair, above, repeat, back));
        }, () -> alertWizard(p, pair, above, repeat, back)));
        view.button(49, Material.ARROW, text("menuBack"), List.of(), p -> back.run()); view.show(player);
    }

    public void selectPair(Player player, Consumer<QuotePair> selected, Runnable back) {
        selectCoin(player, "", 0, symbol -> chooseCurrency(player, currency -> selected.accept(plugin.features().pair(symbol, currency)), back), back);
    }
    public void selectCoin(Player player, String search, int requestedPage, Consumer<String> selected, Runnable back) {
        requireUse(player);
        var section = plugin.getConfig().getConfigurationSection("coins");
        List<String> coins = section == null ? List.of() : section.getKeys(false).stream().filter(this::displayEnabled).filter(c -> c.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)) || section.getString(c, "").toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))).sorted().toList();
        int page = page(requestedPage, coins.size());
        Screen view = screen(text("menuCoin"));
        if (coins.isEmpty()) view.item(22, Material.BARRIER, text("menuNoResults"), List.of(search));
        for (int i = page * 45; i < Math.min(coins.size(), (page + 1) * 45); i++) {
            String symbol = coins.get(i); view.button(i % 45, Material.GOLD_INGOT, symbol, List.of(section.getString(symbol, "")), p -> {
                if (!displayEnabled(symbol)) throw new IllegalArgumentException(text("menuDisplayDisabled"));
                selected.accept(symbol);
            });
        }
        pagination(view, page, coins.size(), (p, next) -> selectCoin(p, search, next, selected, back));
        view.button(48, Material.SPYGLASS, text("menuSearch"), List.of(search), p -> input(p, text("menuSearchPrompt"), value -> selectCoin(p, value.equals("-") ? "" : value, 0, selected, back), () -> selectCoin(p, search, page, selected, back)));
        view.button(49, Material.ARROW, text("menuBack"), List.of(), p -> back.run()); view.show(player);
    }
    public void chooseCurrency(Player player, Consumer<String> selected, Runnable back) { choose(player, text("menuCurrency"), plugin.getConfig().getStringList("currencies"), selected, back); }
    boolean displayEnabled(String symbol) {
        return plugin.getConfig().getStringList("trading.display-disabled-coins").stream().noneMatch(symbol::equalsIgnoreCase);
    }
    public void choose(Player player, String title, List<String> values, Consumer<String> selected, Runnable back) { choose(player, title, values, 0, selected, back); }
    private void choose(Player player, String title, List<String> values, int requestedPage, Consumer<String> selected, Runnable back) {
        int page = page(requestedPage, values.size()); Screen view = screen(title);
        for (int i = page * 45; i < Math.min(values.size(), (page + 1) * 45); i++) { String value = values.get(i); view.button(i % 45, Material.PAPER, value, List.of(), p -> selected.accept(value)); }
        pagination(view, page, values.size(), (p, n) -> choose(p, title, values, n, selected, back));
        view.button(49, Material.ARROW, text("menuBack"), List.of(), p -> back.run()); view.show(player);
    }
    public void confirm(Player player, String title, List<String> details, Runnable accepted, Runnable cancelled) {
        Screen view = screen(title);
        view.item(13, Material.PAPER, title, details);
        view.button(20, Material.LIME_CONCRETE, text("menuConfirmAction"), List.of(), p -> {
            view.consumed = true;
            try { accepted.run(); }
            finally {
                // Never replay an accepted action after an exception: it may already have committed.
                if (view.current(p)) {
                    p.closeInventory();
                    cancelled.run();
                }
            }
        });
        view.button(24, Material.RED_CONCRETE, text("menuCancel"), List.of(), p -> cancelled.run()); view.show(player);
    }
    public void input(Player player, String prompt, Consumer<String> accepted, Runnable cancelled) {
        requireUse(player);
        Input input = new Input(Instant.now().plusSeconds(60), accepted, cancelled);
        inputs.put(player.getUniqueId(), input); player.closeInventory(); notify(player, prompt);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (inputs.remove(player.getUniqueId(), input) && player.isOnline()) { notify(player, text("menuInputExpired")); cancelled.run(); }
        }, 1200);
    }
    public BigDecimal positive(String input) {
        try {
            String normalized = input.strip();
            if (normalized.contains(",") && !normalized.contains(".")) normalized = normalized.replace(',', '.');
            BigDecimal value = new BigDecimal(normalized);
            if (value.signum() <= 0 || value.precision() > 28 || Math.abs((long) value.scale()) > 12) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) { throw new IllegalArgumentException(text("menuPositiveNumber")); }
    }
    public static int page(int page, int count) { return Math.max(0, Math.min(page, Math.max(0, (count - 1) / 45))); }
    public void pagination(Screen screen, int page, int count, BiConsumer<Player, Integer> next) {
        if (page > 0) screen.button(45, Material.ARROW, text("menuPrevious"), List.of(), p -> next.accept(p, page - 1));
        if ((page + 1) * 45 < count) screen.button(53, Material.ARROW, text("menuNext"), List.of(), p -> next.accept(p, page + 1));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Screen view)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= view.inventory.getSize() || view.pending || view.consumed) return;
        Consumer<Player> action = view.actions.get(event.getRawSlot());
        if (action == null) return;
        view.pending = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { if (!view.current(player) || view.consumed) return; requireUse(player); action.accept(player); }
            catch (RuntimeException error) { error(player, error); }
            finally { view.pending = false; }
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Screen && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void chat(AsyncPlayerChatEvent event) {
        Input input = inputs.remove(event.getPlayer().getUniqueId());
        if (input == null) return;
        event.setCancelled(true);
        String value = event.getMessage().strip();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer(); if (!player.isOnline()) return;
            try {
                requireUse(player);
                if (Instant.now().isAfter(input.expires)) { notify(player, text("menuInputExpired")); input.cancelled.run(); }
                else if (value.equalsIgnoreCase("cancel") || value.equalsIgnoreCase("abbrechen")) input.cancelled.run();
                else input.accepted.accept(value);
            } catch (RuntimeException error) {
                error(player, error);
                if (player.hasPermission("cryptocraft.use")) {
                    try { input.cancelled.run(); }
                    catch (RuntimeException navigationError) { player.closeInventory(); error(player, navigationError); }
                }
            }
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event) { inputs.remove(event.getPlayer().getUniqueId()); boardPages.remove(event.getPlayer().getUniqueId()); }
    public void closeAll() {
        inputs.clear(); boardPages.clear();
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder() instanceof Screen) player.closeInventory();
    }
    private record Input(Instant expires, Consumer<String> accepted, Runnable cancelled) {}

    public final class Screen implements InventoryHolder {
        private final Inventory inventory;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        private boolean pending;
        private boolean consumed;
        private Screen(String title) { inventory = Bukkit.createInventory(this, 54, title); }
        public Screen item(int slot, Material material, String title, List<String> lore) {
            ItemStack item = new ItemStack(material);
            var meta = item.getItemMeta(); meta.setDisplayName("§6" + title); meta.setLore(lore.stream().map(line -> "§7" + line).toList()); item.setItemMeta(meta); inventory.setItem(slot, item); return this;
        }
        public Screen button(int slot, Material material, String title, List<String> lore, Consumer<Player> action) { item(slot, material, title, lore); actions.put(slot, action); return this; }
        public void show(Player player) { requireUse(player); inputs.remove(player.getUniqueId()); player.openInventory(inventory); }
        public boolean current(Player player) { return player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == this; }
        public void consume() { consumed = true; }
        @Override public Inventory getInventory() { return inventory; }
    }
}

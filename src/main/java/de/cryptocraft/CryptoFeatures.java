package de.cryptocraft;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Powerable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Persistent player features. Mutations run on the server thread. */
public final class CryptoFeatures {
    private final CryptoCraftPlugin plugin;
    private final File file;
    private final Map<UUID, List<QuotePair>> watchlists = new HashMap<>();
    private final Map<UUID, PortfolioAccount> portfolios =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, List<String>> trades = new HashMap<>();
    private final Map<String, PriceAlarm> alarms = new LinkedHashMap<>();
    private final Set<String> powered = new HashSet<>();
    private final Map<UUID, List<String>> notifications = new HashMap<>();

    public CryptoFeatures(CryptoCraftPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "players.yml");
        load();
    }

    public QuotePair pair(String symbol, String currency) {
        symbol = symbol.toUpperCase(Locale.ROOT);
        currency = currency.toUpperCase(Locale.ROOT);
        String id = plugin.getConfig().getString("coins." + symbol);
        if (id == null
                || plugin.getConfig().getStringList("currencies").stream()
                        .noneMatch(currency::equalsIgnoreCase))
            throw new IllegalArgumentException(plugin.messages().get("pairInvalid"));
        return new QuotePair(symbol, id, currency);
    }

    public Set<String> quotePairs() {
        Set<String> pairs = new LinkedHashSet<>();
        watchlists.values().forEach(list -> list.forEach(p -> pairs.add(p.key())));
        if (plugin.getConfig().getBoolean("portfolio.enabled", false))
            portfolios
                    .values()
                    .forEach(
                            account ->
                                    account.positions().keySet().forEach(p -> pairs.add(p.key())));
        alarms.values().stream()
                .filter(PriceAlarm::enabled)
                .forEach(a -> pairs.add(a.pair().key()));
        return pairs;
    }

    public List<QuotePair> watchlist(UUID player) {
        return watchlists.getOrDefault(player, List.of());
    }

    public void watch(UUID player, QuotePair pair, boolean add) {
        List<QuotePair> list = new ArrayList<>(watchlist(player));
        if (add && !list.contains(pair)) {
            if (list.size() >= plugin.getConfig().getInt("players.watchlist-limit", 20))
                throw new IllegalArgumentException(plugin.messages().get("featureLimit"));
            list.add(pair);
        } else if (!add) list.remove(pair);
        watchlists.put(player, List.copyOf(list));
        save();
        if (add) plugin.prices().requestPair(pair.coinId(), pair.currency());
    }

    public List<PriceAlarm> alarms(UUID player) {
        return alarms.values().stream().filter(a -> a.owner().equals(player)).toList();
    }

    public PriceAlarm addAlarm(
            Player owner,
            QuotePair pair,
            boolean above,
            BigDecimal threshold,
            boolean repeat,
            CryptoBoard board) {
        if (alarms(owner.getUniqueId()).size()
                >= plugin.getConfig().getInt("players.alarm-limit", 20))
            throw new IllegalArgumentException(plugin.messages().get("featureLimit"));
        PriceAlarm alarm =
                new PriceAlarm(
                        UUID.randomUUID().toString(),
                        owner.getUniqueId(),
                        pair,
                        above,
                        threshold,
                        repeat,
                        true,
                        true,
                        Instant.EPOCH,
                        board == null ? "" : board.id());
        if (board != null) {
            World world = plugin.boards().resolveWorld(board);
            Block lever =
                    world == null ? null : world.getBlockAt(board.x(), board.y() + 1, board.z());
            if (!plugin.getConfig().getBoolean("players.redstone-enabled", true)
                    || lever == null
                    || lever.getType() != Material.LEVER
                    || !plugin.protection().canBuild(owner, lever))
                throw new IllegalArgumentException(plugin.messages().get("signalLever"));
        }
        alarms.put(alarm.id(), alarm);
        save();
        plugin.prices().requestPair(pair.coinId(), pair.currency());
        return alarm;
    }

    public boolean removeAlarm(UUID owner, String id) {
        PriceAlarm alarm = alarms.get(id);
        if (alarm == null || !alarm.owner().equals(owner)) return false;
        alarms.remove(id);
        updateSignals();
        save();
        return true;
    }

    public void removeBoardSignals(String boardId) {
        if (powered.contains(boardId)) setSignal(boardId, false);
        alarms.values().removeIf(alarm -> alarm.boardId().equals(boardId));
        save();
    }

    public void evaluate(Map<String, CryptoPriceService.Quote> quotes) {
        boolean changed = false;
        for (PriceAlarm alarm : List.copyOf(alarms.values())) {
            var quote = quotes.get(alarm.pair().key());
            if (quote == null || plugin.prices().isStale(quote)) continue;
            PriceAlarm.Result result =
                    alarm.evaluate(
                            quote.price(),
                            Instant.now(),
                            Duration.ofSeconds(
                                    plugin.getConfig()
                                            .getLong("players.alarm-cooldown-seconds", 300)));
            if (!alarm.equals(result.alarm())) {
                alarms.put(alarm.id(), result.alarm());
                changed = true;
            }
            if (result.fired()) {
                String message =
                        plugin.messages()
                                .get(
                                        "alarmFired",
                                        Map.of(
                                                "pair",
                                                alarm.pair().label(),
                                                "price",
                                                quote.price().toPlainString(),
                                                "target",
                                                alarm.threshold().toPlainString()));
                Player player = Bukkit.getPlayer(alarm.owner());
                if (player != null) player.sendMessage("Â§6[CryptoCraft] Â§f" + message);
                else {
                    List<String> queued =
                            new ArrayList<>(notifications.getOrDefault(alarm.owner(), List.of()));
                    if (queued.size() == 20) queued.removeFirst();
                    queued.add(message);
                    notifications.put(alarm.owner(), List.copyOf(queued));
                }
            }
        }
        updateSignals();
        if (changed) save();
    }

    public void notifyJoin(Player player) {
        List<String> queued = notifications.remove(player.getUniqueId());
        if (queued != null) {
            queued.forEach(text -> player.sendMessage("Â§6[CryptoCraft] Â§f" + text));
            save();
        }
    }

    /**
     * Level signal, combined with OR when multiple rules target the same lever. Stale prices switch
     * it off.
     */
    public void updateSignals() {
        Set<String> on = new HashSet<>();
        for (PriceAlarm alarm : alarms.values()) {
            if (alarm.boardId().isBlank() || !alarm.enabled()) continue;
            var quote = plugin.prices().getQuote(alarm.pair().coinId(), alarm.pair().currency());
            if (plugin.getConfig().getBoolean("players.redstone-enabled", true)
                    && quote != null
                    && !plugin.prices().isStale(quote)
                    && alarm.matches(quote.price())) on.add(alarm.boardId());
        }
        Set<String> affected = new HashSet<>(powered);
        affected.addAll(on);
        alarms.values().stream()
                .filter(a -> !a.boardId().isBlank())
                .forEach(a -> affected.add(a.boardId()));
        for (String board : affected) setSignal(board, on.contains(board));
    }

    private void setSignal(String id, boolean on) {
        CryptoBoard board = plugin.boards().getBoard(id);
        if (board == null) {
            powered.remove(id);
            return;
        }
        World world = plugin.boards().resolveWorld(board);
        if (world == null || !world.isChunkLoaded(board.x() >> 4, board.z() >> 4)) {
            if (on) powered.add(id);
            return;
        }
        Block lever = world.getBlockAt(board.x(), board.y() + 1, board.z());
        // The linked lever belongs to the board owner. Never create or replace a block.
        if (lever.getType() == Material.LEVER && lever.getBlockData() instanceof Powerable power) {
            if (power.isPowered() != on) {
                power.setPowered(on);
                lever.setBlockData(power, true);
            }
        }
        if (on) powered.add(id);
        else powered.remove(id);
    }

    public PortfolioAccount peekAccount(UUID player) {
        return portfolios.get(player);
    }

    public PortfolioAccount account(UUID player) {
        if (!plugin.getConfig().getBoolean("portfolio.enabled", false))
            throw new IllegalArgumentException(plugin.messages().get("portfolioDisabled"));
        PortfolioAccount account = portfolios.get(player);
        if (account == null) {
            account =
                    new PortfolioAccount(
                            plugin.getConfig()
                                    .getString("portfolio.currency", "EUR")
                                    .toUpperCase(Locale.ROOT),
                            new BigDecimal(
                                    plugin.getConfig()
                                            .getString("portfolio.starting-cash", "10000")),
                            new BigDecimal(
                                    plugin.getConfig()
                                            .getString("portfolio.starting-cash", "10000")),
                            BigDecimal.ZERO,
                            Map.of());
            portfolios.put(player, account);
            save();
        }
        return account;
    }

    public void trade(UUID player, QuotePair pair, BigDecimal quantity, boolean buy) {
        var quote = plugin.prices().getQuote(pair.coinId(), pair.currency());
        if (quote == null || plugin.prices().isStale(quote)) {
            plugin.prices().requestPair(pair.coinId(), pair.currency());
            throw new IllegalArgumentException(plugin.messages().get("tradeStale"));
        }
        PortfolioAccount updated = account(player).trade(pair, quantity, quote.price(), buy);
        portfolios.put(player, updated);
        List<String> log = new ArrayList<>(trades.getOrDefault(player, List.of()));
        if (log.size() == 100) log.removeFirst();
        log.add(
                Instant.now()
                        + " "
                        + (buy ? "BUY " : "SELL ")
                        + quantity.toPlainString()
                        + " "
                        + pair.label()
                        + " @ "
                        + quote.price().toPlainString());
        trades.put(player, List.copyOf(log));
        save();
    }

    public List<String> trades(UUID player) {
        return trades.getOrDefault(player, List.of());
    }

    private void load() {
        YamlConfiguration data = plugin.storage().load(file);
        var users = data.getConfigurationSection("players");
        if (users != null)
            for (String id : users.getKeys(false)) {
                UUID player = UUID.fromString(id);
                String base = "players." + id + ".";
                watchlists.put(
                        player,
                        data.getStringList(base + "watchlist").stream()
                                .map(CryptoFeatures::decodePair)
                                .toList());
                notifications.put(player, List.copyOf(data.getStringList(base + "notifications")));
                trades.put(player, List.copyOf(data.getStringList(base + "trades")));
                if (data.contains(base + "cash")) {
                    Map<QuotePair, PortfolioAccount.Position> holdings = new HashMap<>();
                    for (String encoded : data.getStringList(base + "positions")) {
                        String[] parts = encoded.split("\\|", -1);
                        holdings.put(
                                decodePair(parts[0]),
                                new PortfolioAccount.Position(
                                        new BigDecimal(parts[1]), new BigDecimal(parts[2])));
                    }
                    portfolios.put(
                            player,
                            new PortfolioAccount(
                                    data.getString(base + "currency"),
                                    new BigDecimal(data.getString(base + "cash")),
                                    new BigDecimal(data.getString(base + "starting-cash")),
                                    new BigDecimal(data.getString(base + "realized", "0")),
                                    holdings));
                }
            }
        var section = data.getConfigurationSection("alarms");
        if (section != null)
            for (String id : section.getKeys(false)) {
                String base = "alarms." + id + ".";
                alarms.put(
                        id,
                        new PriceAlarm(
                                id,
                                UUID.fromString(data.getString(base + "owner")),
                                decodePair(data.getString(base + "pair")),
                                data.getBoolean(base + "above"),
                                new BigDecimal(data.getString(base + "threshold")),
                                data.getBoolean(base + "repeat"),
                                data.getBoolean(base + "armed", true),
                                data.getBoolean(base + "enabled", true),
                                Instant.ofEpochMilli(data.getLong(base + "last-triggered", 0)),
                                data.getString(base + "board", "")));
            }
    }

    private static String encodePair(QuotePair pair) {
        return pair.symbol() + ":" + pair.coinId() + ":" + pair.currency();
    }

    private static QuotePair decodePair(String text) {
        String[] parts = text.split(":", -1);
        return new QuotePair(parts[0], parts[1], parts[2]);
    }

    private void save() {
        var watches = Map.copyOf(watchlists);
        var accounts = Map.copyOf(portfolios);
        var tradeLog = Map.copyOf(trades);
        var alerts = Map.copyOf(alarms);
        var messages = Map.copyOf(notifications);
        plugin.storage()
                .save(
                        file,
                        () -> {
                            YamlConfiguration data = new YamlConfiguration();
                            Set<UUID> users = new HashSet<>(watches.keySet());
                            users.addAll(accounts.keySet());
                            users.addAll(messages.keySet());
                            for (UUID id : users) {
                                String base = "players." + id + ".";
                                data.set(
                                        base + "watchlist",
                                        watches.getOrDefault(id, List.of()).stream()
                                                .map(CryptoFeatures::encodePair)
                                                .toList());
                                data.set(
                                        base + "notifications",
                                        messages.getOrDefault(id, List.of()));
                                data.set(base + "trades", tradeLog.getOrDefault(id, List.of()));
                                PortfolioAccount account = accounts.get(id);
                                if (account != null) {
                                    data.set(base + "currency", account.currency());
                                    data.set(base + "cash", account.cash().toPlainString());
                                    data.set(
                                            base + "starting-cash",
                                            account.startingCash().toPlainString());
                                    data.set(
                                            base + "realized",
                                            account.realizedProfit().toPlainString());
                                    data.set(
                                            base + "positions",
                                            account.positions().entrySet().stream()
                                                    .map(
                                                            e ->
                                                                    encodePair(e.getKey())
                                                                            + "|"
                                                                            + e.getValue()
                                                                                    .quantity()
                                                                                    .toPlainString()
                                                                            + "|"
                                                                            + e.getValue()
                                                                                    .cost()
                                                                                    .toPlainString())
                                                    .toList());
                                }
                            }
                            alerts.values()
                                    .forEach(
                                            a -> {
                                                String base = "alarms." + a.id() + ".";
                                                data.set(base + "owner", a.owner().toString());
                                                data.set(base + "pair", encodePair(a.pair()));
                                                data.set(base + "above", a.above());
                                                data.set(
                                                        base + "threshold",
                                                        a.threshold().toPlainString());
                                                data.set(base + "repeat", a.repeat());
                                                data.set(base + "armed", a.armed());
                                                data.set(base + "enabled", a.enabled());
                                                data.set(
                                                        base + "last-triggered",
                                                        a.lastTriggered().toEpochMilli());
                                                data.set(base + "board", a.boardId());
                                            });
                            return data;
                        });
    }

    public void close() {
        for (String id : Set.copyOf(powered)) setSignal(id, false);
        save();
    }
}

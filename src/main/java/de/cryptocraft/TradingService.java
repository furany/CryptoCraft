package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Server-thread permission/provider boundary around one serial, durable ledger worker. */
public final class TradingService implements AutoCloseable {
    public record Preview(UUID id, UUID owner, String symbol, String coinId, boolean buy,
            BigDecimal input, boolean quantityMode, BigDecimal referencePrice, BigDecimal unitPrice,
            BigDecimal feeRate, BigDecimal budget, BigDecimal quantity, BigDecimal minimumPrice,
            BigDecimal maximumPrice, Instant sourceTime, Instant expiresAt) {}

    private final CryptoCraftPlugin plugin;
    private final Consumer<Runnable> scheduleMain;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CryptoCraft-economy");
        thread.setDaemon(true); return thread;
    });
    private final Map<UUID, Preview> previews = new HashMap<>();
    private final Map<UUID, Instant> payoutRetry = new HashMap<>(); // worker only
    private volatile Set<QuotePair> demand = Set.of();
    private volatile TradingSettings settings;
    private volatile VaultBridge provider;
    private volatile boolean healthy = true;
    private volatile boolean closing;
    private volatile boolean providerBound;
    private boolean tickPending;
    private TradingEngine engine;
    private String providerProblem = "";
    private long generation;

    public TradingService(CryptoCraftPlugin plugin) {
        this(plugin, action -> Bukkit.getScheduler().runTask(plugin, action));
    }

    TradingService(CryptoCraftPlugin plugin, Consumer<Runnable> scheduleMain) {
        this.plugin = plugin;
        this.scheduleMain = scheduleMain;
        try { provider = VaultBridge.discover(); }
        catch (RuntimeException failure) { providerProblem = Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()); }
        settings = TradingConfiguration.settings(plugin.getConfig(), provider == null ? -1 : provider.digits());
        CoinPolicy coinPolicy = configuredCoins(plugin.getConfig());
        try {
            work(() -> {
                TradingSettings providerSettings = settings;
                engine = new TradingEngine(plugin.getDataFolder().toPath().resolve("economy.db"), settings, Instant.now(), true);
                settings = engine.settings();
                cancelUnavailableOrders(coinPolicy, Instant.now());
                if (provider != null) {
                    try {
                        engine.checkSettings(providerSettings);
                        engine.bindProvider(provider.identity());
                        engine.updateSettings(providerSettings, Instant.now());
                        settings = providerSettings;
                        providerBound = true;
                    }
                    catch (IllegalArgumentException failure) {
                        providerProblem = failure.getMessage();
                        plugin.getLogger().warning("Trading paused: " + providerProblem);
                    }
                }
                updateDemand();
                return null;
            }).join();
        } catch (CompletionException failure) {
            worker.shutdown();
            throw new IllegalStateException("Cannot initialize the economy ledger", failure.getCause());
        }
    }

    public TradingSettings settings() { return settings; }
    public boolean available() {
        return !closing && healthy && provider != null && providerBound
                && plugin.getConfig().getBoolean("trading.enabled", true);
    }
    public String providerName() { return provider == null ? "—" : provider.name(); }
    public String currency() { return provider == null ? text("Server money", "Servergeld") : provider.currency(); }
    public String format(BigDecimal amount) {
        return provider == null ? CryptoFormat.price(amount, plugin.messages().getLanguage().equals("de")
                ? Locale.GERMANY : Locale.US) + " " + currency() : provider.format(amount);
    }
    public BigDecimal balance(Player player) {
        if (provider == null) return BigDecimal.ZERO;
        return provider.balance(player.getUniqueId());
    }
    public String modeLabel() {
        return switch (settings.mode()) {
            case BROKER_UNLIMITED -> text("Server exchange", "Serverbörse");
            case BROKER_RESERVE -> text("Reserve exchange", "Börse mit begrenzter Kasse");
            case P2P -> text("Player trading at reference prices", "Spielerhandel zum Referenzkurs");
        };
    }
    public String health() {
        if (!healthy) return text("Storage error: trading paused", "Speicherfehler: Handel pausiert");
        if (!providerProblem.isEmpty()) return error(new IllegalArgumentException(providerProblem));
        if (!available()) return text("Trading disabled or Vault economy unavailable", "Handel deaktiviert oder Vault-Economy nicht verfügbar");
        return providerName() + " / " + modeLabel();
    }
    public Set<QuotePair> demandedPairs() { return demand; }

    public void checkAccess(Player player) {
        if (!player.hasPermission("cryptocraft.use") || !player.hasPermission("cryptocraft.trade"))
            throw new IllegalArgumentException(text("You cannot trade here.", "Du hast keine Handelsberechtigung."));
        if (!available()) throw new IllegalArgumentException(health());
        if (!plugin.getConfig().getString("trading.access", "terminals").equals("anywhere")
                && plugin.boards().nearestTerminal(player, plugin.getConfig().getDouble("trading.terminal-radius", 6)) == null)
            throw new IllegalArgumentException(text("Use an approved trading terminal within reach.",
                    "Benutze ein freigegebenes Börsenterminal in deiner Nähe."));
    }

    public Preview preview(Player player, String symbol, boolean buy, BigDecimal amount, boolean quantityMode) {
        checkAccess(player);
        validateInput(amount);
        QuotePair pair = tradablePair(symbol);
        var quote = plugin.prices().getQuote(pair.coinId(), pair.currency());
        Instant now = Instant.now();
        if (!tradeQuote(quote, now)) {
            plugin.prices().requestPair(pair.coinId(), pair.currency());
            throw new IllegalArgumentException(text("Waiting for a verified fresh provider quote. Please try again shortly.",
                    "Ein bestätigter aktueller Anbieterpreis wird abgerufen. Bitte gleich erneut versuchen."));
        }
        BigDecimal price = quote.price().multiply(settings.unitsPerReference());
        BigDecimal tolerance = TradingConfiguration.decimal(plugin.getConfig(), "trading.price-tolerance-percent", "1").movePointLeft(2);
        BigDecimal fee = buy ? settings.buyFeeRate() : settings.sellFeeRate();
        BigDecimal minimum = buy ? null : price.multiply(BigDecimal.ONE.subtract(tolerance));
        BigDecimal maximum = buy ? price.multiply(BigDecimal.ONE.add(tolerance)) : null;
        BigDecimal quantity = buy && !quantityMode
                ? TradingEngine.previewBudgetQuantity(amount, price, fee) : amount;
        if (quantity.signum() <= 0) throw new IllegalArgumentException(text("Amount is too small.", "Der Betrag ist zu klein."));
        BigDecimal budget = buy ? quantityMode
                ? quantity.multiply(maximum).multiply(BigDecimal.ONE.add(fee)).setScale(settings.moneyScale(), RoundingMode.CEILING)
                : amount : BigDecimal.ZERO;
        if (buy) {
            try { budget = budget.setScale(settings.moneyScale(), RoundingMode.UNNECESSARY); }
            catch (ArithmeticException failure) {
                throw new IllegalArgumentException(text("Amount exceeds economy currency precision", "Der Betrag hat mehr Nachkommastellen als die Serverwährung erlaubt."));
            }
        }
        BigDecimal value = buy ? budget : quantity.multiply(price);
        if (value.compareTo(settings.minimumOrderValue()) < 0 || value.compareTo(settings.maximumOrderValue()) > 0)
            throw new IllegalArgumentException(text("Order value must be between ", "Auftragswert muss zwischen ")
                    + format(settings.minimumOrderValue()) + " – " + format(settings.maximumOrderValue()));
        Preview result = new Preview(UUID.randomUUID(), player.getUniqueId(), pair.symbol(), pair.coinId(), buy,
                amount, quantityMode, quote.price(), price, fee, budget, quantity, minimum, maximum,
                quote.updatedAt(), now.plusSeconds(60));
        previews.values().removeIf(p -> p.owner().equals(player.getUniqueId()) || p.expiresAt().isBefore(now));
        previews.put(result.id(), result);
        return result;
    }

    public CompletableFuture<TradingEngine.OrderView> submit(Player player, Preview preview) {
        checkAccess(player);
        if (!player.getUniqueId().equals(preview.owner()) || previews.remove(preview.id()) != preview
                || !Instant.now().isBefore(preview.expiresAt()))
            throw new IllegalArgumentException(text("Preview expired. Open a new preview.", "Vorschau abgelaufen. Bitte erneut öffnen."));
        QuotePair pair = tradablePair(preview.symbol());
        if (!pair.coinId().equals(preview.coinId()))
            throw new IllegalArgumentException(text("Coin configuration changed. Open a new preview.", "Coin-Konfiguration geändert. Bitte neue Vorschau öffnen."));
        var quote = plugin.prices().getQuote(pair.coinId(), pair.currency());
        if (!tradeQuote(quote, Instant.now()))
            throw new IllegalArgumentException(text("The provider quote is no longer fresh.", "Der Anbieterpreis ist nicht mehr aktuell."));
        // The guard and fees were confirmed; configuration reload invalidates all previews.
        TradingEngine.PriceGuard guard = new TradingEngine.PriceGuard(preview.minimumPrice(), preview.maximumPrice());
        TradingEngine.MarketQuote market = new TradingEngine.MarketQuote(pair.coinId(), quote.price(), quote.updatedAt(), quote.sourceVerified());
        return work(() -> {
            TradingEngine.OrderView result = preview.buy()
                    ? engine.placeBuy(preview.owner(), preview.symbol(), preview.coinId(), preview.budget(),
                        preview.quantityMode() ? preview.quantity() : null, guard, market, Instant.now())
                    : engine.placeSell(preview.owner(), preview.symbol(), preview.coinId(), preview.quantity(), guard, market, Instant.now());
            updateDemand();
            return result;
        });
    }

    private QuotePair tradablePair(String symbol) {
        String normalized = symbol.toUpperCase(Locale.ROOT);
        if (plugin.getConfig().getStringList("trading.disabled-coins").contains(normalized))
            throw new IllegalArgumentException(text("Trading is disabled for this coin.", "Der Handel mit diesem Coin ist deaktiviert."));
        QuotePair pair = plugin.configuredPair(normalized, settings.referenceCurrency());
        if (pair == null) throw new IllegalArgumentException(text("Unknown coin.", "Unbekannter Coin."));
        return pair;
    }
    private boolean tradeQuote(CryptoPriceService.Quote quote, Instant now) {
        return quote != null && quote.sourceVerified() && !quote.updatedAt().isAfter(now)
                && Duration.between(quote.updatedAt(), now).compareTo(settings.maximumQuoteAge()) <= 0;
    }
    private static void validateInput(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.precision() > 28 || Math.abs((long) amount.scale()) > 12)
            throw new IllegalArgumentException("Positive amount required; maximum 12 decimal places.");
    }

    public CompletableFuture<TradingEngine.AccountView> view(UUID owner) { return work(() -> engine.account(owner)); }
    public CompletableFuture<List<TradingEngine.OrderView>> orders(UUID owner) { return work(() -> engine.orders(owner)); }
    public CompletableFuture<List<TradingEngine.TradeView>> history(UUID owner) { return work(() -> engine.history(owner, 100)); }
    public CompletableFuture<Void> cancel(Player player, String id) {
        if (!player.hasPermission("cryptocraft.use")) throw new IllegalArgumentException(plugin.messages().get("permissionUse"));
        UUID owner = player.getUniqueId();
        return work(() -> { engine.cancelOrder(owner, id, Instant.now()); updateDemand(); return null; });
    }

    public void onQuotes(Map<String, CryptoPriceService.Quote> quotes) {
        if (!available()) return;
        String suffix = ":" + settings.referenceCurrency().toLowerCase(Locale.ROOT);
        List<TradingEngine.MarketQuote> snapshots = new ArrayList<>();
        quotes.forEach((key, quote) -> {
            if (key.endsWith(suffix)) snapshots.add(new TradingEngine.MarketQuote(
                    key.substring(0, key.length() - suffix.length()), quote.price(), quote.updatedAt(), quote.sourceVerified()));
        });
        if (snapshots.isEmpty()) return;
        work(() -> {
            for (var quote : snapshots) {
                List<TradingEngine.TradeView> fills = engine.processQuote(quote, Instant.now());
                for (var fill : fills) main(() -> notifyTrade(fill));
            }
            updateDemand(); return null;
        }).exceptionally(failure -> { report(failure); return null; });
    }

    public void tick() {
        if (closing || tickPending || !healthy) return;
        tickPending = true;
        VaultBridge selected = providerBound ? provider : null;
        long selectedGeneration = generation;
        work(() -> {
            Instant now = Instant.now();
            engine.expireOrders(now);
            if (selected != null) {
                Set<UUID> dispatched = new HashSet<>();
                for (UUID owner : engine.owners()) {
                    if (!engine.account(owner).frozen() && !now.isBefore(payoutRetry.getOrDefault(owner, Instant.EPOCH)))
                        engine.preparePayout(owner, now);
                }
                for (var transfer : engine.preparedTransfers()) {
                    if (engine.account(transfer.owner()).frozen() || dispatched.contains(transfer.owner())
                            || engine.transfers(transfer.owner()).stream().anyMatch(t -> t.state() == TradingEngine.TransferState.CALLING)) continue;
                    var calling = engine.markCalling(transfer.id(), now);
                    dispatched.add(transfer.owner());
                    main(() -> performTransfer(selected, selectedGeneration, calling));
                    if (dispatched.size() >= 20) break;
                }
            }
            updateDemand(); return null;
        }).whenComplete((result, failure) -> main(() -> {
            tickPending = false;
            if (failure != null) report(failure);
        }));
    }

    private void performTransfer(VaultBridge selected, long selectedGeneration, TradingEngine.TransferView transfer) {
        if (closing) return; // CALLING is recovered as uncertain on restart; never blindly repeated.
        VaultBridge.Result result;
        if (provider != selected || generation != selectedGeneration || !providerBound)
            result = new VaultBridge.Result(TradingEngine.TransferOutcome.REJECTED, "Provider changed before invocation; no call made");
        else result = selected.transfer(transfer.owner(), transfer.direction() == TradingEngine.TransferDirection.WITHDRAW, transfer.amount());
        var outcome = result;
        work(() -> {
            engine.completeTransfer(transfer.id(), outcome.outcome(), outcome.detail(), Instant.now());
            if (outcome.outcome() == TradingEngine.TransferOutcome.REJECTED)
                payoutRetry.put(transfer.owner(), Instant.now().plusSeconds(60));
            if (outcome.outcome() == TradingEngine.TransferOutcome.UNCERTAIN)
                plugin.getLogger().severe("Economy transfer " + transfer.id() + " needs administrator reconciliation: " + outcome.detail());
            updateDemand(); return null;
        }).exceptionally(failure -> { report(failure); return null; });
    }

    public void refreshProvider() {
        if (closing) return;
        VaultBridge next;
        try { next = VaultBridge.discover(); }
        catch (RuntimeException e) { providerProblem = Objects.toString(e.getMessage(), e.getClass().getSimpleName()); next = null; }
        if (next != null && provider != null && next.sameProvider(provider) && providerBound
                && next.identity().equals(provider.identity()) && next.digits() == provider.digits()) {
            providerProblem = "";
            return;
        }
        provider = next; providerBound = false; generation++;
        if (next == null) return;
        VaultBridge candidate = next;
        TradingSettings candidateSettings = TradingConfiguration.settings(plugin.getConfig(), candidate.digits());
        work(() -> {
            engine.bindProvider(candidate.identity());
            engine.checkSettings(candidateSettings);
            engine.updateSettings(candidateSettings, Instant.now());
            return null;
        }).whenComplete((ignored, failure) -> main(() -> {
            if (provider != candidate) return;
            if (failure == null) { settings = candidateSettings; providerBound = true; providerProblem = ""; }
            else { providerProblem = error(failure); plugin.getLogger().warning(providerProblem); }
        }));
    }

    public void validateReload(ConfigurationSection config) {
        TradingSettings candidate = TradingConfiguration.settings(config, provider == null ? settings.moneyScale() : provider.digits());
        work(() -> { engine.checkSettings(candidate); return null; }).join();
    }
    public void reload() {
        previews.clear();
        TradingSettings candidate = TradingConfiguration.settings(plugin.getConfig(), provider == null ? settings.moneyScale() : provider.digits());
        CoinPolicy coinPolicy = configuredCoins(plugin.getConfig());
        work(() -> {
            engine.updateSettings(candidate, Instant.now());
            settings = candidate;
            cancelUnavailableOrders(coinPolicy, Instant.now());
            updateDemand(); return null;
        }).join();
        refreshProvider();
    }

    private record CoinPolicy(boolean enabled, Set<String> disabled, Map<String, String> ids) {}
    private static CoinPolicy configuredCoins(ConfigurationSection config) {
        Map<String, String> ids = new HashMap<>();
        ConfigurationSection coins = config.getConfigurationSection("coins");
        if (coins != null) for (String symbol : coins.getKeys(false))
            ids.put(symbol.toUpperCase(Locale.ROOT), coins.getString(symbol, "").toLowerCase(Locale.ROOT));
        return new CoinPolicy(config.getBoolean("trading.enabled", true),
                Set.copyOf(config.getStringList("trading.disabled-coins")), Map.copyOf(ids));
    }
    private void cancelUnavailableOrders(CoinPolicy policy, Instant now) {
        for (var order : engine.activeOrders())
            if (!policy.enabled() || policy.disabled().contains(order.symbol())
                    || !order.coinId().equals(policy.ids().get(order.symbol())))
                engine.cancelOrder(order.owner(), order.id(), now, "Trading disabled or coin configuration changed");
    }

    public CompletableFuture<BigDecimal> treasury() { return work(engine::treasuryBalance); }
    public CompletableFuture<List<TradingEngine.TransferView>> unresolved() { return work(engine::unresolvedTransfers); }
    public CompletableFuture<List<TradingEngine.TransferView>> transfers(UUID owner) { return work(() -> engine.transfers(owner)); }
    public CompletableFuture<Void> reserve(UUID owner, BigDecimal amount, boolean deposit) {
        if (!available()) throw new IllegalArgumentException(health());
        validateInput(amount);
        return work(() -> { if (deposit) engine.depositReserve(owner, amount, Instant.now());
            else engine.withdrawReserve(owner, amount, Instant.now()); return null; });
    }
    public CompletableFuture<Void> reconcile(String id, boolean applied, String reason) {
        return work(() -> { engine.reconcileTransfer(id, applied, reason, Instant.now()); updateDemand(); return null; });
    }
    public CompletableFuture<Void> grant(UUID owner, QuotePair pair, BigDecimal quantity, String reason) {
        validateInput(quantity);
        return work(() -> { engine.grantCoins(owner, pair.symbol(), pair.coinId(), quantity, reason, Instant.now()); return null; });
    }

    private void updateDemand() {
        Set<QuotePair> pairs = new LinkedHashSet<>();
        for (String id : engine.marketCoinIds()) {
            var orders = engine.openOrders(id);
            if (!orders.isEmpty()) pairs.add(new QuotePair(orders.getFirst().symbol(), id, settings.referenceCurrency()));
        }
        demand = Set.copyOf(pairs);
    }
    private void notifyTrade(TradingEngine.TradeView trade) {
        for (UUID id : new UUID[] {trade.buyer(), trade.seller()}) {
            if (id == null) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage("§6[CryptoCraft] §a" + text("Trade executed: ", "Handel ausgeführt: ")
                    + trade.quantity().stripTrailingZeros().toPlainString() + " " + trade.symbol() + " @ " + format(trade.unitPrice()));
        }
    }
    public void joined(Player player) {
        UUID owner = player.getUniqueId();
        view(owner).thenAccept(account -> main(() -> {
            if (!player.isOnline()) return;
            if (account.frozen()) player.sendMessage("§6[CryptoCraft] §c" + text("Your economy account needs an administrator review.",
                    "Dein Handelskonto benötigt einen administrativen Buchungsabgleich."));
            if (account.cashAvailable().signum() > 0) player.sendMessage("§6[CryptoCraft] §e" + text("Pending credit: ", "Offene Gutschrift: ") + format(account.cashAvailable()));
        })).exceptionally(failure -> null);
    }
    public String error(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (!(failure instanceof IllegalArgumentException)) {
            plugin.getLogger().log(Level.WARNING, "Trading operation failed", failure);
            return text("The operation could not be completed. Please contact an administrator.",
                    "Der Vorgang konnte nicht abgeschlossen werden. Bitte wende dich an einen Administrator.");
        }
        if (!plugin.messages().getLanguage().equals("de")) return message;
        return switch (message) {
            case "Insufficient available or reserved balance" -> "Nicht genügend verfügbares oder reserviertes Guthaben beziehungsweise Coins.";
            case "Unknown trading order" -> "Unbekannter Handelsauftrag.";
            case "Unknown Vault transfer" -> "Unbekannte Vault-Buchung.";
            case "Cannot cancel another player's order" -> "Dieser Auftrag gehört einem anderen Spieler.";
            case "Account has a pending or uncertain Vault transaction" -> "Dein Handelskonto ist wegen einer laufenden oder ungeklärten Vault-Buchung gesperrt.";
            case "A fresh quote with a verified provider timestamp is required" -> "Ein aktueller Kurs mit bestätigtem Anbieterzeitstempel ist erforderlich.";
            case "Buy budget is outside the configured order limits", "Sell order is outside the configured order limits" -> "Der Auftragswert liegt außerhalb der konfigurierten Grenzen.";
            case "Buy order is below the minimum executable value" -> "Die kaufbare Coinmenge unterschreitet den Mindestauftragswert.";
            case "Maximum number of open trading orders reached" -> "Du hast die maximale Anzahl offener Handelsaufträge erreicht.";
            case "Amount exceeds economy currency precision" -> "Der Betrag hat mehr Nachkommastellen als die Serverwährung erlaubt.";
            case "Positive amount required; maximum 12 decimal places.", "Quantity must be positive, with at most 12 decimal places", "Invalid money amount" -> "Positiver Betrag mit höchstens 12 Nachkommastellen erforderlich.";
            case "Cancel or complete all open orders before changing trading mode" -> "Vor dem Moduswechsel müssen alle offenen Aufträge abgeschlossen oder storniert werden.";
            case "Resolve pending or uncertain Vault calls before changing trading mode" -> "Vor dem Moduswechsel müssen laufende oder ungeklärte Vault-Buchungen abgeschlossen werden.";
            case "Trading currency, conversion factor and money precision require an explicit migration after economic activity" -> "Währung, Umrechnungsfaktor und Geldpräzision sind nach Betriebsbeginn gesperrt und benötigen eine ausdrückliche Migration.";
            case "Economy provider changed; existing ledger requires explicit migration" -> "Der Economy-Anbieter hat sich geändert. Das bestehende Handelsbuch benötigt eine ausdrückliche Migration.";
            case "Coin symbol is already bound to another held coin ID" -> "Dieses Kürzel gehört bereits zu einer anderen Coin-ID mit Beständen oder offenen Aufträgen.";
            case "Only uncertain Vault transfers may be reconciled" -> "Nur ungeklärte Vault-Buchungen können abgeglichen werden.";
            case "A reason is required" -> "Eine Begründung ist erforderlich.";
            case "No economy provider bound to the ledger" -> "Für das Handelsbuch ist kein Economy-Anbieter verbunden.";
            case "Funding order is no longer active" -> "Der zu finanzierende Auftrag ist nicht mehr aktiv.";
            case "Cancelled by owner" -> "Vom Besitzer storniert.";
            case "Trading disabled or coin configuration changed" -> "Der Handel wurde deaktiviert oder die Coin-Konfiguration geändert; die Reservierung wurde freigegeben.";
            case "Order expired" -> "Die Laufzeit des Auftrags ist abgelaufen.";
            case "Funding completed after expiry" -> "Die Finanzierung wurde erst nach Ablauf bestätigt; das Geld wird freigegeben.";
            case "Vault funding rejected" -> "Der Economy-Anbieter hat die Abbuchung abgelehnt.";
            case "Server reserve cannot cover this sale" -> "Die Serverreserve reicht für diesen Verkauf nicht aus.";
            case "Remaining order is below the minimum executable value" -> "Der verbleibende Auftragswert unterschreitet den Mindestwert; die Reservierung wurde freigegeben.";
            case "Executed at the next fresh external price" -> "Zum nächsten gültigen externen Kurs ausgeführt.";
            case "Order completely filled" -> "Der Auftrag wurde vollständig ausgeführt.";
            case "Small unfilled remainder released" -> "Der kleine, nicht ausgeführte Rest wurde freigegeben.";
            case "Maximum supported coin quantity reached; remaining budget released" -> "Die maximale unterstützte Coinmenge ist erreicht; übriges Budget wurde freigegeben.";
            default -> message;
        };
    }
    private String text(String en, String de) { return plugin.messages().getLanguage().equals("de") ? de : en; }
    private <T> CompletableFuture<T> work(Supplier<T> operation) {
        if (closing) return CompletableFuture.failedFuture(new IllegalStateException("Trading service is stopping"));
        return CompletableFuture.supplyAsync(operation, worker);
    }
    private void main(Runnable action) {
        if (!closing && plugin.isEnabled()) scheduleMain.accept(action);
    }
    private void report(Throwable failure) {
        healthy = false;
        plugin.getLogger().log(Level.SEVERE, "Economy processing paused; ledger must be inspected", failure);
    }
    @Override public void close() {
        if (closing) return;
        closing = true; previews.clear();
        worker.execute(() -> { if (engine != null) engine.close(); });
        worker.shutdown();
        try { if (!worker.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().severe("Economy worker is still draining at shutdown"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

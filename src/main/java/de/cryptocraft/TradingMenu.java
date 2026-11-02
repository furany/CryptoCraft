package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The GUI never performs settlement itself; every preview and submission goes through TradingService. */
public final class TradingMenu implements Listener {
    private final CryptoCraftPlugin plugin;
    public TradingMenu(CryptoCraftPlugin plugin) { this.plugin = plugin; }
    private CryptoMenu ui() { return plugin.menu(); }
    private TradingService trading() {
        if (plugin.trading() == null) throw new IllegalArgumentException(ui().text("menuTradingUnavailable"));
        return plugin.trading();
    }
    private String text(String key) { return ui().text(key); }
    private String field(String key, Object value) { return ui().text(key, "value", value); }
    private String money(BigDecimal value) { return trading().format(value); }
    private String quantity(BigDecimal value) { return value == null ? "—" : value.stripTrailingZeros().toPlainString(); }

    public void open(Player player) { open(player, "", 0); }
    private void open(Player player, String search, int requestedPage) {
        ui().requireUse(player);
        TradingService service = trading();
        var section = plugin.getConfig().getConfigurationSection("coins");
        List<String> coins = section == null ? List.of() : section.getKeys(false).stream().filter(s -> s.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)) || section.getString(s, "").toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))).sorted().toList();
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, (coins.size() - 1) / 36)));
        CryptoMenu.Screen screen = ui().screen(text("menuTrading"));
        for (int i = page * 36; i < Math.min(coins.size(), page * 36 + 36); i++) {
            String symbol = coins.get(i);
            QuotePair pair = plugin.configuredPair(symbol, service.settings().referenceCurrency());
            List<String> lore = new ArrayList<>(pair == null ? List.of(text("pairInvalid")) : ui().quoteLore(pair));
            boolean enabled = coinEnabled(symbol);
            if (!enabled) lore.add(text("menuTradingCoinDisabled"));
            screen.button(i % 36, enabled ? Material.GOLD_INGOT : Material.GRAY_DYE, symbol, lore, p -> openCoin(p, symbol));
        }
        if (coins.isEmpty()) screen.item(22, Material.BARRIER, text("menuNoResults"), List.of(search));
        screen.item(40, service.available() ? Material.LIME_DYE : Material.RED_DYE, service.modeLabel(), List.of(service.available() ? field("menuProvider", service.providerName()) : service.health(), text("menuExternalExecution")));
        if (page > 0) screen.button(45, Material.ARROW, text("menuPrevious"), List.of(), p -> open(p, search, page - 1));
        if ((page + 1) * 36 < coins.size()) screen.button(53, Material.ARROW, text("menuNext"), List.of(), p -> open(p, search, page + 1));
        screen.button(47, Material.SPYGLASS, text("menuSearch"), List.of(search), p -> ui().input(p, text("menuSearchPrompt"), value -> open(p, value.equals("-") ? "" : value, 0), () -> open(p, search, page)));
        screen.button(48, Material.CHEST, text("menuDepot"), List.of(), p -> depot(p, 0));
        screen.button(49, Material.ARROW, text("menuBack"), List.of(), p -> ui().open(p, 0));
        screen.button(50, Material.BOOK, text("menuOrders"), List.of(), p -> orders(p, 0));
        screen.show(player);
    }

    public void openCoin(Player player, String symbol) {
        ui().requireUse(player);
        TradingService service = trading();
        QuotePair pair = plugin.configuredPair(symbol, service.settings().referenceCurrency());
        if (pair == null) throw new IllegalArgumentException(text("pairInvalid"));
        plugin.prices().requestPair(pair.coinId(), pair.currency());
        CryptoMenu.Screen screen = ui().screen(text("menuTrading") + " · " + pair.symbol());
        screen.item(13, Material.GOLD_INGOT, pair.label(), ui().quoteLore(pair));
        String unavailable = tradingProblem(player, pair.symbol());
        if (unavailable == null) {
        screen.button(19, Material.EMERALD, text("menuBuyBudget"), List.of(text("menuBudgetIncludesFees")), p -> ui().input(p, text("menuBudgetPrompt"), amount -> preview(p, symbol, true, ui().positive(amount), false), () -> openCoin(p, symbol)));
        screen.button(21, Material.GOLD_NUGGET, text("menuBuyQuantity"), List.of(text("menuQuantityBudgetHint")), p -> ui().input(p, text("menuQuantityPrompt"), amount -> preview(p, symbol, true, ui().positive(amount), true), () -> openCoin(p, symbol)));
        screen.button(23, Material.REDSTONE, text("menuSellQuantity"), List.of(), p -> ui().input(p, text("menuQuantityPrompt"), amount -> preview(p, symbol, false, ui().positive(amount), true), () -> openCoin(p, symbol)));
        screen.button(25, Material.CHEST, text("menuSellShare"), List.of(), p -> sellShare(p, symbol));
        } else {
            screen.item(19, Material.GRAY_DYE, text("menuBuyBudget"), List.of(unavailable));
            screen.item(21, Material.GRAY_DYE, text("menuBuyQuantity"), List.of(unavailable));
            screen.item(23, Material.GRAY_DYE, text("menuSellQuantity"), List.of(unavailable));
            screen.item(25, Material.GRAY_DYE, text("menuSellShare"), List.of(unavailable));
        }
        screen.item(31, Material.CLOCK, service.modeLabel(), List.of(text("menuExternalExecution"), field("menuOrderLifetime", service.settings().orderLifetime().toSeconds()), field("menuExecutionCurrency", service.currency())));
        screen.button(45, Material.BOOK, text("menuOrders"), List.of(), p -> orders(p, 0));
        screen.button(48, Material.CLOCK, text("menuRefresh"), List.of(), p -> openCoin(p, symbol));
        screen.button(49, Material.ARROW, text("menuBack"), List.of(), this::open);
        screen.button(53, Material.CHEST, text("menuDepot"), List.of(), p -> depot(p, 0));
        screen.show(player);
    }
    private boolean coinEnabled(String symbol) {
        return plugin.getConfig().getStringList("trading.disabled-coins").stream().noneMatch(symbol::equalsIgnoreCase);
    }
    private String tradingProblem(Player player, String symbol) {
        if (!coinEnabled(symbol)) return text("menuTradingCoinDisabled");
        try { trading().checkAccess(player); return null; }
        catch (IllegalArgumentException error) { return trading().error(error); }
    }

    private void sellShare(Player player, String symbol) {
        ui().choose(player, text("menuSellShare"), List.of("25 %", "50 %", "100 %"), share -> load(player, text("menuDepot"), () -> trading().view(player.getUniqueId()), account -> {
            var balance = account.coins().values().stream().filter(c -> c.symbol().equalsIgnoreCase(symbol)).findFirst().orElseThrow(() -> new IllegalArgumentException(text("menuNoHoldings")));
            BigDecimal fraction = new BigDecimal(share.replace(" %", "")).movePointLeft(2);
            BigDecimal amount = balance.available().multiply(fraction).setScale(12, RoundingMode.DOWN).stripTrailingZeros();
            if (amount.signum() <= 0) throw new IllegalArgumentException(text("menuNoHoldings"));
            preview(player, symbol, false, amount, true);
        }, () -> openCoin(player, symbol)), () -> openCoin(player, symbol));
    }

    public void preview(Player player, String symbol, boolean buy, BigDecimal amount, boolean quantityMode) {
        TradingService.Preview preview = trading().preview(player, symbol, buy, amount, quantityMode);
        List<String> details = new ArrayList<>();
        details.add((buy ? text("menuBuy") : text("menuSell")) + " " + preview.symbol());
        details.add(field("menuReferencePrice", ui().number(preview.referencePrice()) + " " + trading().settings().referenceCurrency()));
        details.add(field("menuQuoteAge", Math.max(0, Duration.between(preview.sourceTime(), Instant.now()).toSeconds())));
        details.add(field("menuServerUnitPrice", money(preview.unitPrice())));
        details.add(field("menuEstimatedQuantity", quantity(preview.quantity())));
        details.add(field("menuFeeRate", preview.feeRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()));
        details.add(field("menuEstimatedFee", money(preview.quantity().multiply(preview.unitPrice()).multiply(preview.feeRate()))));
        if (buy) {
            details.add(field("menuMaximumDebit", money(preview.budget())));
            details.add(field("menuMaximumPrice", money(preview.maximumPrice())));
        } else {
            details.add(field("menuMinimumPrice", money(preview.minimumPrice())));
            BigDecimal estimated = preview.quantity().multiply(preview.unitPrice()).multiply(BigDecimal.ONE.subtract(preview.feeRate()));
            details.add(field("menuEstimatedProceeds", money(estimated)));
            details.add(field("menuMinimumProceeds", money(preview.quantity().multiply(preview.minimumPrice()).multiply(BigDecimal.ONE.subtract(preview.feeRate())))));
        }
        details.add(field("menuPreviewExpires", preview.expiresAt()));
        details.add(text("menuExternalExecution"));
        details.add(text("menuReservationsHint"));
        ui().confirm(player, text("menuTradeConfirm"), details, () -> {
            CryptoMenu.Screen pending = ui().screen(text("menuSubmitting"));
            pending.item(22, Material.CLOCK, text("menuSubmitting"), List.of(text("menuReservationsHint")));
            pending.button(49, Material.ARROW, text("menuBack"), List.of(), this::open); pending.show(player);
            CompletableFuture<TradingEngine.OrderView> submitted;
            try { submitted = trading().submit(player, preview); }
            catch (RuntimeException error) { ui().error(player, error); returnTo(player, () -> openCoin(player, symbol)); return; }
            submitted.whenComplete((order, error) -> onMain(() -> {
                if (!player.isOnline()) return;
                if (error != null) { ui().error(player, error); if (pending.current(player)) returnTo(player, () -> openCoin(player, symbol)); }
                else {
                    ui().notify(player, field("menuOrderAccepted", order.id()));
                    if (pending.current(player)) returnTo(player, () -> orders(player, 0));
                }
            }));
        }, () -> openCoin(player, symbol));
    }

    public void depot(Player player, int requestedPage) {
        load(player, text("menuDepot"), () -> trading().view(player.getUniqueId()), account -> {
            List<TradingEngine.CoinBalance> coins = account.coins().values().stream().sorted(Comparator.comparing(TradingEngine.CoinBalance::symbol).thenComparing(TradingEngine.CoinBalance::coinId)).toList();
            int page = Math.max(0, Math.min(requestedPage, Math.max(0, (coins.size() - 1) / 36)));
            CryptoMenu.Screen screen = ui().screen(text("menuDepot"));
            if (coins.isEmpty()) screen.item(22, Material.CHEST, text("menuNoHoldings"), List.of());
            for (int i = page * 36; i < Math.min(coins.size(), (page + 1) * 36); i++) {
                var balance = coins.get(i);
                List<String> lore = new ArrayList<>(List.of(field("menuAvailableCoins", quantity(balance.available())), field("menuReservedCoins", quantity(balance.reserved()))));
                var quote = plugin.prices().getQuote(balance.coinId(), trading().settings().referenceCurrency());
                if (quote != null) {
                    BigDecimal value = balance.available().add(balance.reserved()).multiply(quote.price()).multiply(trading().settings().unitsPerReference());
                    lore.add(field("menuReferenceValue", money(value)));
                    lore.add(text(plugin.prices().isStale(quote) ? "stateStale" : "stateFresh"));
                } else lore.add(text("quoteUnavailable"));
                QuotePair configured = plugin.configuredPair(balance.symbol(), trading().settings().referenceCurrency());
                if (configured != null && configured.coinId().equals(balance.coinId())) {
                    if (!coinEnabled(balance.symbol())) lore.add(text("menuTradingCoinDisabled"));
                    screen.button(i % 36, coinEnabled(balance.symbol()) ? Material.GOLD_INGOT : Material.GRAY_DYE, balance.symbol(), lore, p -> openCoin(p, balance.symbol()));
                }
                else { lore.add(text("menuCoinUnavailable")); screen.item(i % 36, Material.GRAY_DYE, balance.symbol(), lore); }
            }
            List<String> credits = new ArrayList<>(List.of(field("menuAvailableCredit", money(account.cashAvailable())), field("menuReservedMoney", money(account.cashReserved())), text("menuPayoutHint")));
            if (trading().available()) credits.add(trading().currency() + ": " + money(trading().balance(player)));
            screen.item(39, Material.EMERALD, text("menuSettlementCredit"), credits);
            screen.item(41, account.frozen() ? Material.RED_DYE : Material.LIME_DYE, text(account.frozen() ? "menuAccountFrozen" : "menuAccountReady"), List.of(account.frozen() ? text("menuReconciliationHint") : trading().modeLabel()));
            if (page > 0) screen.button(45, Material.ARROW, text("menuPrevious"), List.of(), p -> depot(p, page - 1));
            if ((page + 1) * 36 < coins.size()) screen.button(53, Material.ARROW, text("menuNext"), List.of(), p -> depot(p, page + 1));
            screen.button(47, Material.GOLD_INGOT, text("menuTrading"), List.of(), this::open);
            screen.button(48, Material.BOOK, text("menuOrders"), List.of(), p -> orders(p, 0));
            screen.button(49, Material.ARROW, text("menuBack"), List.of(), p -> ui().open(p, 0));
            screen.button(50, Material.WRITABLE_BOOK, text("menuTradeHistory"), List.of(), p -> history(p, 0));
            screen.button(51, Material.CLOCK, text("menuRefresh"), List.of(), p -> depot(p, page));
            screen.show(player);
        }, () -> ui().open(player, 0));
    }

    public void orders(Player player, int requestedPage) {
        load(player, text("menuOrders"), () -> trading().orders(player.getUniqueId()), orders -> {
            List<TradingEngine.OrderView> sorted = orders.stream().sorted(Comparator.comparing(TradingEngine.OrderView::active).reversed().thenComparing(TradingEngine.OrderView::sequence, Comparator.reverseOrder())).toList();
            int page = CryptoMenu.page(requestedPage, sorted.size());
            CryptoMenu.Screen screen = ui().screen(text("menuOrders"));
            if (sorted.isEmpty()) screen.item(22, Material.BOOK, text("menuEmpty"), List.of());
            for (int i = page * 45; i < Math.min(sorted.size(), (page + 1) * 45); i++) {
                var order = sorted.get(i);
                List<String> lore = new ArrayList<>();
                lore.add(order.id());
                lore.add(field("menuOrderStatus", status(order.status())));
                lore.add(field("menuFilledQuantity", quantity(order.filledQuantity())));
                lore.add(field("menuFilledValue", money(order.filledValue())));
                if (order.side() == TradingEngine.Side.BUY) lore.add(field("menuReservedMoney", money(order.remainingBudget())));
                else lore.add(field("menuReservedCoins", quantity(order.remainingQuantity())));
                lore.add(field("menuOrderExpires", order.expiresAt()));
                if (order.reason() != null && !order.reason().isBlank()) lore.add(field("menuOrderReason", trading().error(new IllegalArgumentException(order.reason()))));
                if (order.active()) {
                    lore.add(text("menuWaitingHint")); lore.add(text("menuCancelOrderHint"));
                    screen.button(i % 45, Material.CLOCK, (order.side() == TradingEngine.Side.BUY ? text("menuBuy") : text("menuSell")) + " " + order.symbol(), lore, p -> cancel(p, order.id(), page));
                } else screen.item(i % 45, order.status() == TradingEngine.OrderStatus.FILLED ? Material.LIME_DYE : Material.GRAY_DYE, order.symbol(), lore);
            }
            ui().pagination(screen, page, sorted.size(), this::orders);
            screen.button(48, Material.CLOCK, text("menuRefresh"), List.of(), p -> orders(p, page));
            screen.button(49, Material.ARROW, text("menuBack"), List.of(), p -> depot(p, 0));
            screen.button(50, Material.GOLD_INGOT, text("menuTrading"), List.of(), this::open); screen.show(player);
        }, () -> ui().open(player, 0));
    }

    public void cancel(Player player, String orderId, int page) {
        ui().confirm(player, text("menuCancelOrder"), List.of(orderId, text("menuCancelOrderHint")), () -> load(player, text("menuCancelling"), () -> trading().cancel(player, orderId), ignored -> orders(player, page), () -> orders(player, page)), () -> orders(player, page));
    }

    public void history(Player player, int requestedPage) {
        load(player, text("menuTradeHistory"), () -> trading().history(player.getUniqueId()), trades -> {
            int page = CryptoMenu.page(requestedPage, trades.size());
            CryptoMenu.Screen screen = ui().screen(text("menuTradeHistory"));
            if (trades.isEmpty()) screen.item(22, Material.BOOK, text("menuEmpty"), List.of());
            for (int i = page * 45; i < Math.min(trades.size(), (page + 1) * 45); i++) {
                var trade = trades.get(i); boolean buy = player.getUniqueId().equals(trade.buyer());
                screen.item(i % 45, buy ? Material.LIME_DYE : Material.RED_DYE, (buy ? text("menuBuy") : text("menuSell")) + " " + trade.symbol(), List.of(field("menuFilledQuantity", quantity(trade.quantity())), field("menuServerUnitPrice", money(trade.unitPrice())), field("menuFilledValue", money(trade.notional())), field("menuFeeAmount", money(buy ? trade.buyFee() : trade.sellFee())), field("menuExecutionTime", trade.executedAt()), field("menuQuoteTimeValue", trade.sourceTime())));
            }
            ui().pagination(screen, page, trades.size(), this::history);
            screen.button(49, Material.ARROW, text("menuBack"), List.of(), p -> depot(p, 0)); screen.show(player);
        }, () -> depot(player, 0));
    }

    private String status(TradingEngine.OrderStatus status) {
        return text(switch (status) {
            case FUNDING -> "menuOrderFunding";
            case OPEN -> "menuOrderOpen";
            case FILLED -> "menuOrderFilled";
            case CANCELLED -> "menuOrderCancelled";
            case EXPIRED -> "menuOrderExpired";
            case REJECTED -> "menuOrderRejected";
        });
    }

    private <T> void load(Player player, String title, Supplier<CompletableFuture<T>> supplier, Consumer<T> accepted, Runnable back) {
        ui().requireUse(player);
        CryptoMenu.Screen pending = ui().screen(title);
        pending.item(22, Material.CLOCK, text("menuLoading"), List.of());
        pending.button(49, Material.ARROW, text("menuBack"), List.of(), p -> back.run()); pending.show(player);
        CompletableFuture<T> future;
        try { future = supplier.get(); }
        catch (RuntimeException error) { ui().error(player, error); if (pending.current(player)) returnTo(player, back); return; }
        future.whenComplete((value, error) -> onMain(() -> {
            if (!pending.current(player)) return;
            if (error != null) { ui().error(player, error); returnTo(player, back); return; }
            try { ui().requireUse(player); accepted.accept(value); }
            catch (RuntimeException failure) { ui().error(player, failure); if (pending.current(player)) returnTo(player, back); }
        }));
    }
    private void returnTo(Player player, Runnable back) {
        if (!player.isOnline()) return;
        if (!player.hasPermission("cryptocraft.use")) { player.closeInventory(); return; }
        try { back.run(); }
        catch (RuntimeException failure) { player.closeInventory(); ui().error(player, failure); }
    }
    private void onMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        try { Bukkit.getScheduler().runTask(plugin, action); }
        catch (IllegalPluginAccessException ignored) { /* shutdown: leave durable operations to the service */ }
    }
    public void closeAll() { /* All screens and pending input belong to CryptoMenu. */ }
}

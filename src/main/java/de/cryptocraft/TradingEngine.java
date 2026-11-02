package de.cryptocraft;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Durable, Bukkit-independent trading ledger. All calls, including reads and close, belong on one
 * serial worker. Vault calls deliberately happen outside this class and outside SQL transactions.
 * No operation reads or imports the unrelated paper-trading account.
 */
public final class TradingEngine implements AutoCloseable {
    public enum Side { BUY, SELL }
    public enum OrderStatus { FUNDING, OPEN, FILLED, CANCELLED, EXPIRED, REJECTED }
    /** The action to invoke on the player's Vault account. */
    public enum TransferDirection { WITHDRAW, DEPOSIT }
    public enum TransferPurpose { BUY_FUNDING, PAYOUT, RESERVE_DEPOSIT, RESERVE_WITHDRAWAL }
    public enum TransferState { PREPARED, CALLING, APPLIED, REJECTED, UNCERTAIN, CANCELLED }
    public enum TransferOutcome { APPLIED, REJECTED, UNCERTAIN }

    public record MarketQuote(String coinId, BigDecimal referencePrice, Instant sourceTime,
                              boolean verified) {
        public MarketQuote {
            coinId = normalizeCoin(coinId);
            Objects.requireNonNull(referencePrice, "referencePrice");
            Objects.requireNonNull(sourceTime, "sourceTime");
            if (referencePrice.signum() <= 0 || referencePrice.precision() > 64
                    || Math.abs((long) referencePrice.scale()) > 100)
                throw new IllegalArgumentException("Invalid reference price");
        }
    }

    public record PriceGuard(BigDecimal minimum, BigDecimal maximum) {
        public PriceGuard {
            if (minimum != null && minimum.signum() <= 0
                    || maximum != null && maximum.signum() <= 0
                    || minimum != null && maximum != null && minimum.compareTo(maximum) > 0)
                throw new IllegalArgumentException("Invalid execution price bounds");
            for (BigDecimal bound : new BigDecimal[] {minimum, maximum})
                if (bound != null && (bound.precision() > 80 || Math.abs((long) bound.scale()) > 112))
                    throw new IllegalArgumentException("Execution price bound is too large");
        }
        public static PriceGuard unbounded() { return new PriceGuard(null, null); }
        public boolean accepts(BigDecimal price) {
            return (minimum == null || price.compareTo(minimum) >= 0)
                    && (maximum == null || price.compareTo(maximum) <= 0);
        }
    }

    public record CoinBalance(String symbol, String coinId, BigDecimal available,
                              BigDecimal reserved) {}
    public record AccountView(UUID owner, BigDecimal cashAvailable, BigDecimal cashReserved,
                              Map<String, CoinBalance> coins, boolean frozen) {
        public AccountView { coins = Map.copyOf(coins); }
    }
    public record OrderView(String id, UUID owner, Side side, String symbol, String coinId,
                            OrderStatus status, TradingSettings.Mode mode, BigDecimal budget,
                            BigDecimal quantity, BigDecimal maximumQuantity,
                            BigDecimal remainingBudget, BigDecimal remainingQuantity,
                            BigDecimal filledQuantity, BigDecimal filledValue, BigDecimal feeRate,
                            PriceGuard guard, Instant acceptedAt, Instant expiresAt,
                            long sequence, String reason) {
        public boolean active() { return status == OrderStatus.FUNDING || status == OrderStatus.OPEN; }
    }
    public record TransferView(String id, UUID owner, TransferDirection direction,
                               TransferPurpose purpose, BigDecimal amount, TransferState state,
                               String orderId, String reason, Instant createdAt, Instant updatedAt) {}
    public record TradeView(String id, String coinId, String symbol, BigDecimal quantity,
                            BigDecimal unitPrice, BigDecimal notional, BigDecimal buyFee,
                            BigDecimal sellFee, UUID buyer, UUID seller, Instant executedAt,
                            Instant sourceTime, TradingSettings.Mode mode) {}

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int QUANTITY_SCALE = 12;
    static final BigDecimal MAXIMUM_BUDGET_QUANTITY = new BigDecimal("9999999999999999.999999999999");
    private static final String TREASURY = "treasury:available";
    private static final String TREASURY_RESERVED = "treasury:reserved";
    private static final String VAULT = "system:vault";
    private static final String ISSUANCE = "system:issuance";
    private final Connection db;
    private TradingSettings settings;

    public TradingEngine(Path file, TradingSettings settings, Instant now) {
        this(file, settings, now, false);
    }

    /** A missing Vault provider must not silently change the persisted money precision. */
    TradingEngine(Path file, TradingSettings settings, Instant now, boolean retainStoredPrecision) {
        Objects.requireNonNull(file, "file");
        this.settings = Objects.requireNonNull(settings, "settings");
        Connection opened = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Class.forName("org.sqlite.JDBC");
            opened = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            this.db = opened;
            execute("PRAGMA foreign_keys=ON");
            execute("PRAGMA journal_mode=WAL");
            execute("PRAGMA synchronous=FULL");
            execute("PRAGMA busy_timeout=5000");
            createSchema();
            transaction(() -> {
                String version = meta("schema_version");
                if (version != null && !version.equals("1"))
                    throw new IllegalStateException("Unsupported trading database version " + version);
                setMeta("schema_version", "1");
                if (meta("mode") == null) persistSettings(settings);
                else {
                    TradingSettings previous = persistedSettings(settings);
                    this.settings = previous;
                    TradingSettings candidate = retainStoredPrecision ? withMoneyScale(settings, previous.moneyScale()) : settings;
                    applySettings(candidate, now);
                    this.settings = candidate;
                }
                execute("UPDATE transfers SET state='UNCERTAIN', reason=?, updated_at=? WHERE state='CALLING'",
                        "Server stopped while a Vault call was in progress; administrator reconciliation required", now);
                expireInternal(now);
                return null;
            });
        } catch (Exception failure) {
            if (opened != null) try { opened.close(); } catch (SQLException ignored) { }
            throw failure instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Cannot open trading database", failure);
        }
    }

    public TradingSettings settings() { return settings; }

    private static TradingSettings withMoneyScale(TradingSettings value, int scale) {
        return new TradingSettings(value.mode(), value.referenceCurrency(), value.unitsPerReference(),
                value.buyFeeRate(), value.sellFeeRate(), value.minimumOrderValue(), value.maximumOrderValue(),
                scale, value.orderLifetime(), value.maximumQuoteAge(), value.maximumOpenOrdersPerPlayer());
    }

    private void createSchema() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        execute("CREATE TABLE IF NOT EXISTS balances (account TEXT NOT NULL, asset TEXT NOT NULL, amount TEXT NOT NULL, PRIMARY KEY(account,asset))");
        execute("CREATE TABLE IF NOT EXISTS coins (coin_id TEXT PRIMARY KEY, symbol TEXT NOT NULL)");
        execute("""
                CREATE TABLE IF NOT EXISTS orders (
                  id TEXT PRIMARY KEY, owner TEXT NOT NULL, side TEXT NOT NULL,
                  symbol TEXT NOT NULL, coin_id TEXT NOT NULL, status TEXT NOT NULL, mode TEXT NOT NULL,
                  budget TEXT NOT NULL, quantity TEXT, max_quantity TEXT, remaining_budget TEXT NOT NULL,
                  remaining_quantity TEXT, filled_quantity TEXT NOT NULL, filled_value TEXT NOT NULL,
                  fee_rate TEXT NOT NULL, min_price TEXT, max_price TEXT, accepted_at INTEGER,
                  expires_at INTEGER NOT NULL, seq INTEGER UNIQUE, reason TEXT NOT NULL, created_at INTEGER NOT NULL)
                """);
        execute("CREATE INDEX IF NOT EXISTS orders_market ON orders(coin_id,status,seq)");
        execute("CREATE INDEX IF NOT EXISTS orders_owner ON orders(owner,status)");
        execute("""
                CREATE TABLE IF NOT EXISTS transfers (
                  id TEXT PRIMARY KEY, owner TEXT NOT NULL, direction TEXT NOT NULL, purpose TEXT NOT NULL,
                  amount TEXT NOT NULL, state TEXT NOT NULL, order_id TEXT, reason TEXT NOT NULL,
                  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
                """);
        execute("CREATE INDEX IF NOT EXISTS transfers_state ON transfers(state,owner)");
        execute("""
                CREATE TABLE IF NOT EXISTS trades (
                  id TEXT PRIMARY KEY, coin_id TEXT NOT NULL, symbol TEXT NOT NULL, quantity TEXT NOT NULL,
                  unit_price TEXT NOT NULL, notional TEXT NOT NULL, buy_fee TEXT NOT NULL,
                  sell_fee TEXT NOT NULL, buyer TEXT, seller TEXT, executed_at INTEGER NOT NULL,
                  source_time INTEGER NOT NULL, mode TEXT NOT NULL, buy_order TEXT, sell_order TEXT)
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS journal (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, transaction_id TEXT NOT NULL, account TEXT NOT NULL,
                  asset TEXT NOT NULL, delta TEXT NOT NULL, kind TEXT NOT NULL, reason TEXT NOT NULL,
                  created_at INTEGER NOT NULL)
                """);
        execute("CREATE INDEX IF NOT EXISTS journal_transaction ON journal(transaction_id)");
        execute("CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, action TEXT NOT NULL, subject TEXT NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)");
    }

    public void checkSettings(TradingSettings next) {
        Objects.requireNonNull(next, "next");
        read(() -> { checkSettingsInternal(next); return null; });
    }

    public void updateSettings(TradingSettings next, Instant now) {
        transaction(() -> { applySettings(next, now); return null; });
        settings = next;
    }

    private void checkSettingsInternal(TradingSettings next) throws SQLException {
        boolean identityChanged = !settings.referenceCurrency().equals(next.referenceCurrency())
                || settings.unitsPerReference().compareTo(next.unitsPerReference()) != 0
                || settings.moneyScale() != next.moneyScale();
        if (identityChanged && hasActivity())
            throw new IllegalArgumentException("Trading currency, conversion factor and money precision require an explicit migration after economic activity");
        if (settings.mode() != next.mode()) {
            if (exists("SELECT 1 FROM orders WHERE status IN ('OPEN','FUNDING') LIMIT 1"))
                throw new IllegalArgumentException("Cancel or complete all open orders before changing trading mode");
            if (exists("SELECT 1 FROM transfers WHERE state IN ('CALLING','UNCERTAIN') LIMIT 1"))
                throw new IllegalArgumentException("Resolve pending or uncertain Vault calls before changing trading mode");
        }
    }

    private void applySettings(TradingSettings next, Instant now) throws SQLException {
        checkSettingsInternal(next);
        if (settings.mode() != next.mode()) {
            audit("MODE_CHANGE", next.mode().name(), settings.mode() + " -> " + next.mode(), now);
        }
        persistSettings(next);
    }

    private boolean hasActivity() throws SQLException {
        return exists("SELECT 1 FROM journal LIMIT 1") || exists("SELECT 1 FROM orders LIMIT 1")
                || exists("SELECT 1 FROM transfers LIMIT 1");
    }

    private void persistSettings(TradingSettings next) throws SQLException {
        setMeta("mode", next.mode().name());
        setMeta("reference_currency", next.referenceCurrency());
        setMeta("units_per_reference", next.unitsPerReference().toPlainString());
        setMeta("money_scale", Integer.toString(next.moneyScale()));
    }

    private TradingSettings persistedSettings(TradingSettings fallback) throws SQLException {
        return new TradingSettings(TradingSettings.Mode.valueOf(meta("mode")), meta("reference_currency"),
                new BigDecimal(meta("units_per_reference")), fallback.buyFeeRate(), fallback.sellFeeRate(),
                fallback.minimumOrderValue(), fallback.maximumOrderValue(), Integer.parseInt(meta("money_scale")),
                fallback.orderLifetime(), fallback.maximumQuoteAge(), fallback.maximumOpenOrdersPerPlayer());
    }

    /** Bind to a stable provider identity before any external transfer is started. */
    public void bindProvider(String identity) {
        String verifiedIdentity = reason(identity);
        transaction(() -> {
            String previous = meta("provider");
            if (previous != null && !previous.equals(verifiedIdentity) && hasActivity())
                throw new IllegalArgumentException("Economy provider changed; existing ledger requires explicit migration");
            setMeta("provider", verifiedIdentity);
            return null;
        });
    }

    public String providerIdentity() { return read(() -> meta("provider")); }

    public AccountView account(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        return read(() -> {
            Map<String, CoinBalance> coins = new LinkedHashMap<>();
            try (PreparedStatement statement = statement("SELECT coin_id,symbol FROM coins ORDER BY symbol");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String coin = rows.getString("coin_id");
                    BigDecimal free = balance(available(owner), coinAsset(coin));
                    BigDecimal held = balance(reserved(owner), coinAsset(coin));
                    if (free.signum() != 0 || held.signum() != 0)
                        coins.put(coin, new CoinBalance(rows.getString("symbol"), coin, free, held));
                }
            }
            return new AccountView(owner, balance(available(owner), cashAsset()),
                    balance(reserved(owner), cashAsset()), coins, frozen(owner));
        });
    }

    public Set<UUID> owners() {
        return read(() -> {
            Set<UUID> owners = new LinkedHashSet<>();
            try (PreparedStatement statement = statement("SELECT owner FROM orders UNION SELECT owner FROM transfers");
                 ResultSet rows = statement.executeQuery()) { while (rows.next()) owners.add(uuid(rows.getString(1))); }
            try (PreparedStatement statement = statement("SELECT DISTINCT account FROM balances WHERE account LIKE 'player:%'");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) owners.add(UUID.fromString(rows.getString(1).substring(7, 43)));
            }
            return Set.copyOf(owners);
        });
    }

    public Set<String> marketCoinIds() {
        return read(() -> {
            Set<String> coins = new LinkedHashSet<>();
            try (PreparedStatement statement = statement("SELECT DISTINCT coin_id FROM orders WHERE status IN ('OPEN','FUNDING')");
                 ResultSet rows = statement.executeQuery()) { while (rows.next()) coins.add(rows.getString(1)); }
            return Set.copyOf(coins);
        });
    }

    public List<OrderView> orders(UUID owner) {
        return read(() -> queryOrders("SELECT * FROM orders WHERE owner=? AND (status IN ('OPEN','FUNDING') OR id IN (SELECT id FROM orders WHERE owner=? AND status NOT IN ('OPEN','FUNDING') ORDER BY created_at DESC,id DESC LIMIT 200)) ORDER BY CASE WHEN status IN ('OPEN','FUNDING') THEN 0 ELSE 1 END, created_at DESC,id DESC", owner, owner)
                .stream().map(O::view).toList());
    }
    public List<OrderView> activeOrders() {
        return read(() -> queryOrders("SELECT * FROM orders WHERE status IN ('OPEN','FUNDING') ORDER BY created_at,id")
                .stream().map(O::view).toList());
    }
    public List<OrderView> openOrders(String coinId) {
        return read(() -> queryOrders("SELECT * FROM orders WHERE coin_id=? AND status='OPEN' ORDER BY seq", normalizeCoin(coinId))
                .stream().map(O::view).toList());
    }
    /** Returns null for an unknown order. */
    public OrderView order(String id) { return read(() -> { O order = loadOrder(id); return order == null ? null : order.view(); }); }
    public List<TransferView> preparedTransfers() {
        return read(() -> queryTransfers("SELECT * FROM transfers WHERE state='PREPARED' ORDER BY created_at,id"));
    }
    public List<TransferView> unresolvedTransfers() {
        return read(() -> queryTransfers("SELECT * FROM transfers WHERE state IN ('CALLING','UNCERTAIN') ORDER BY created_at,id"));
    }
    public List<TransferView> transfers(UUID owner) {
        return read(() -> queryTransfers("SELECT * FROM transfers WHERE owner=? ORDER BY created_at DESC,id DESC LIMIT 200", owner));
    }
    /** Returns null for an unknown transfer. */
    public TransferView transfer(String id) { return read(() -> loadTransfer(id)); }
    public BigDecimal treasuryBalance() { return read(() -> balance(TREASURY, cashAsset())); }
    public BigDecimal treasuryReserved() { return read(() -> balance(TREASURY_RESERVED, cashAsset())); }

    public List<TradeView> history(UUID owner, int limit) {
        return read(() -> {
            List<TradeView> trades = new ArrayList<>();
            try (PreparedStatement statement = statement("SELECT * FROM trades WHERE buyer=? OR seller=? ORDER BY executed_at DESC,rowid DESC LIMIT ?",
                    owner, owner, Math.max(1, Math.min(1000, limit))); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) trades.add(tradeView(rows));
            }
            return List.copyOf(trades);
        });
    }

    private boolean frozen(UUID owner) throws SQLException {
        return exists("SELECT 1 FROM transfers WHERE owner=? AND state IN ('CALLING','UNCERTAIN') LIMIT 1", owner);
    }
    private void requireUnfrozen(UUID owner) throws SQLException {
        if (frozen(owner)) throw new IllegalArgumentException("Account has a pending or uncertain Vault transaction");
    }

    private static final class O {
        String id, symbol, coinId, reason;
        UUID owner;
        Side side;
        OrderStatus status;
        TradingSettings.Mode mode;
        BigDecimal budget, quantity, maximumQuantity, remainingBudget, remainingQuantity,
                filledQuantity, filledValue, feeRate;
        PriceGuard guard;
        Instant acceptedAt, expiresAt, createdAt;
        Long sequence;
        OrderView view() {
            return new OrderView(id, owner, side, symbol, coinId, status, mode, budget, quantity,
                    maximumQuantity, remainingBudget, remainingQuantity, filledQuantity,
                    filledValue, feeRate, guard, acceptedAt, expiresAt, sequence == null ? 0 : sequence, reason);
        }
    }
    private O orderData(ResultSet rows) throws SQLException {
        O order = new O();
        order.id = rows.getString("id"); order.owner = uuid(rows.getString("owner"));
        order.symbol = rows.getString("symbol"); order.coinId = rows.getString("coin_id");
        order.reason = rows.getString("reason"); order.side = Side.valueOf(rows.getString("side"));
        order.status = OrderStatus.valueOf(rows.getString("status"));
        order.mode = TradingSettings.Mode.valueOf(rows.getString("mode"));
        order.budget = decimal(rows, "budget"); order.quantity = decimal(rows, "quantity");
        order.maximumQuantity = decimal(rows, "max_quantity");
        order.remainingBudget = decimal(rows, "remaining_budget");
        order.remainingQuantity = decimal(rows, "remaining_quantity");
        order.filledQuantity = decimal(rows, "filled_quantity"); order.filledValue = decimal(rows, "filled_value");
        order.feeRate = decimal(rows, "fee_rate");
        order.guard = new PriceGuard(decimal(rows, "min_price"), decimal(rows, "max_price"));
        order.acceptedAt = instant(rows, "accepted_at"); order.expiresAt = instant(rows, "expires_at");
        order.createdAt = instant(rows, "created_at");
        long sequence = rows.getLong("seq"); order.sequence = rows.wasNull() ? null : sequence;
        return order;
    }
    private List<O> queryOrders(String sql, Object... parameters) throws SQLException {
        List<O> orders = new ArrayList<>();
        try (PreparedStatement statement = statement(sql, parameters); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) orders.add(orderData(rows));
        }
        return orders;
    }
    private O loadOrder(String id) throws SQLException {
        List<O> orders = queryOrders("SELECT * FROM orders WHERE id=?", id);
        return orders.isEmpty() ? null : orders.getFirst();
    }
    private O requireOrder(String id) throws SQLException {
        O order = loadOrder(id);
        if (order == null) throw new IllegalArgumentException("Unknown trading order");
        return order;
    }
    private void insert(O order) throws SQLException {
        execute("INSERT INTO orders(id,owner,side,symbol,coin_id,status,mode,budget,quantity,max_quantity,remaining_budget,remaining_quantity,filled_quantity,filled_value,fee_rate,min_price,max_price,accepted_at,expires_at,seq,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                order.id, order.owner, order.side, order.symbol, order.coinId, order.status, order.mode,
                order.budget, order.quantity, order.maximumQuantity, order.remainingBudget, order.remainingQuantity,
                order.filledQuantity, order.filledValue, order.feeRate, order.guard.minimum(), order.guard.maximum(),
                order.acceptedAt, order.expiresAt, order.sequence, order.reason, order.createdAt);
    }
    private void save(O order) throws SQLException {
        execute("UPDATE orders SET status=?,remaining_budget=?,remaining_quantity=?,filled_quantity=?,filled_value=?,accepted_at=?,seq=?,reason=? WHERE id=?",
                order.status, order.remainingBudget, order.remainingQuantity, order.filledQuantity,
                order.filledValue, order.acceptedAt, order.sequence, order.reason, order.id);
    }
    private List<TransferView> queryTransfers(String sql, Object... parameters) throws SQLException {
        List<TransferView> result = new ArrayList<>();
        try (PreparedStatement statement = statement(sql, parameters); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new TransferView(rows.getString("id"), uuid(rows.getString("owner")),
                    TransferDirection.valueOf(rows.getString("direction")), TransferPurpose.valueOf(rows.getString("purpose")),
                    decimal(rows, "amount"), TransferState.valueOf(rows.getString("state")), rows.getString("order_id"),
                    rows.getString("reason"), instant(rows, "created_at"), instant(rows, "updated_at")));
        }
        return List.copyOf(result);
    }
    private TransferView loadTransfer(String id) throws SQLException {
        List<TransferView> transfers = queryTransfers("SELECT * FROM transfers WHERE id=?", id);
        return transfers.isEmpty() ? null : transfers.getFirst();
    }
    private TransferView requireTransfer(String id) throws SQLException {
        TransferView transfer = loadTransfer(id);
        if (transfer == null) throw new IllegalArgumentException("Unknown Vault transfer");
        return transfer;
    }
    private TradeView tradeView(ResultSet rows) throws SQLException {
        return new TradeView(rows.getString("id"), rows.getString("coin_id"), rows.getString("symbol"),
                decimal(rows,"quantity"), decimal(rows,"unit_price"), decimal(rows,"notional"),
                decimal(rows,"buy_fee"), decimal(rows,"sell_fee"), uuid(rows.getString("buyer")),
                uuid(rows.getString("seller")), instant(rows,"executed_at"), instant(rows,"source_time"),
                TradingSettings.Mode.valueOf(rows.getString("mode")));
    }

    public OrderView placeBuy(UUID owner, String symbol, String coinId, BigDecimal budget,
                              PriceGuard guard, MarketQuote preview, Instant now) {
        return placeBuy(owner, symbol, coinId, budget, null, guard, preview, now);
    }

    /** maximumQuantity is optional: null spends the budget; a value never buys more than that quantity. */
    public OrderView placeBuy(UUID owner, String symbol, String coinId, BigDecimal budget,
                              BigDecimal maximumQuantity, PriceGuard guard, MarketQuote preview, Instant now) {
        positiveAmount(budget);
        exactMoney(budget); // Never round a player's confirmed maximum debit upwards.
        if (maximumQuantity != null) quantity(maximumQuantity);
        String coin = normalizeCoin(coinId);
        return transaction(() -> {
            validateOrder(owner, coin, preview, now);
            if (budget.compareTo(settings.minimumOrderValue()) < 0
                    || budget.compareTo(settings.maximumOrderValue()) > 0)
                throw new IllegalArgumentException("Buy budget is outside the configured order limits");
            BigDecimal unit = unitPrice(preview);
            BigDecimal executable = maximumQuantity == null ? previewBudgetQuantity(budget, unit, settings.buyFeeRate())
                    : budget.divide(unit.multiply(BigDecimal.ONE.add(settings.buyFeeRate())), QUANTITY_SCALE, RoundingMode.DOWN);
            if (maximumQuantity != null) executable = executable.min(maximumQuantity);
            if (executable.signum() == 0 || maximumQuantity != null
                    && maximumQuantity.multiply(unit).multiply(BigDecimal.ONE.add(settings.buyFeeRate()))
                        .compareTo(settings.minimumOrderValue()) < 0)
                throw new IllegalArgumentException("Buy order is below the minimum executable value");
            O order = newOrder(owner, symbol, coin, Side.BUY, guard, now);
            order.budget = budget; order.maximumQuantity = maximumQuantity;
            order.remainingQuantity = maximumQuantity;
            order.remainingBudget = balance(available(owner), cashAsset()).min(budget);
            move(order.id, available(owner), reserved(owner), cashAsset(), order.remainingBudget,
                    "BUY_RESERVE", "Reserve existing credit for buy order", now);
            if (order.remainingBudget.compareTo(budget) == 0) activate(order, now);
            else order.status = OrderStatus.FUNDING;
            insert(order);
            if (order.status == OrderStatus.FUNDING) {
                BigDecimal needed = budget.subtract(order.remainingBudget).setScale(settings.moneyScale(), RoundingMode.UP);
                createTransfer(owner, TransferDirection.WITHDRAW, TransferPurpose.BUY_FUNDING,
                        needed, order.id, "Fund buy order", now);
            }
            return order.view();
        });
    }

    public OrderView placeSell(UUID owner, String symbol, String coinId, BigDecimal amount,
                               PriceGuard guard, MarketQuote preview, Instant now) {
        quantity(amount);
        String coin = normalizeCoin(coinId);
        return transaction(() -> {
            validateOrder(owner, coin, preview, now);
            BigDecimal value = amount.multiply(unitPrice(preview));
            if (value.compareTo(settings.minimumOrderValue()) < 0
                    || value.compareTo(settings.maximumOrderValue()) > 0)
                throw new IllegalArgumentException("Sell order is outside the configured order limits");
            O order = newOrder(owner, symbol, coin, Side.SELL, guard, now);
            order.quantity = amount; order.remainingQuantity = amount;
            move(order.id, available(owner), reserved(owner), coinAsset(coin), amount,
                    "SELL_RESERVE", "Reserve coins for sell order", now);
            activate(order, now);
            insert(order);
            return order.view();
        });
    }

    private void validateOrder(UUID owner, String coin, MarketQuote preview, Instant now) throws SQLException {
        Objects.requireNonNull(owner, "owner");
        requireUnfrozen(owner);
        expireInternal(now);
        if (preview == null || !preview.coinId().equals(coin) || !fresh(preview, now))
            throw new IllegalArgumentException("A fresh quote with a verified provider timestamp is required");
        try (PreparedStatement statement = statement("SELECT COUNT(*) FROM orders WHERE owner=? AND status IN ('OPEN','FUNDING')", owner);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            if (rows.getInt(1) >= settings.maximumOpenOrdersPerPlayer())
                throw new IllegalArgumentException("Maximum number of open trading orders reached");
        }
    }

    private O newOrder(UUID owner, String symbol, String coinId, Side side, PriceGuard guard, Instant now) throws SQLException {
        registerCoin(symbol, coinId);
        O order = new O();
        order.id = UUID.randomUUID().toString(); order.owner = owner; order.symbol = symbol.toUpperCase(Locale.ROOT);
        order.coinId = coinId; order.side = side; order.mode = settings.mode();
        order.budget = ZERO; order.remainingBudget = ZERO; order.filledQuantity = ZERO; order.filledValue = ZERO;
        order.feeRate = side == Side.BUY ? settings.buyFeeRate() : settings.sellFeeRate();
        order.guard = Objects.requireNonNullElseGet(guard, PriceGuard::unbounded);
        order.createdAt = now; order.expiresAt = now.plus(settings.orderLifetime()); order.reason = "";
        return order;
    }

    private void registerCoin(String symbol, String coinId) throws SQLException {
        String normalized = Objects.requireNonNull(symbol, "symbol").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{1,12}")) throw new IllegalArgumentException("Invalid coin symbol");
        try (PreparedStatement statement = statement("SELECT coin_id FROM coins WHERE symbol=? AND coin_id<>?", normalized, coinId);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String previous = rows.getString(1);
                if (exists("SELECT 1 FROM balances WHERE asset=? AND account LIKE 'player:%' AND amount NOT IN ('0','0.0','0.00') LIMIT 1", coinAsset(previous))
                        || exists("SELECT 1 FROM orders WHERE coin_id=? AND status IN ('OPEN','FUNDING') LIMIT 1", previous))
                    throw new IllegalArgumentException("Coin symbol is already bound to another held coin ID");
            }
        }
        execute("INSERT INTO coins(coin_id,symbol) VALUES(?,?) ON CONFLICT(coin_id) DO UPDATE SET symbol=excluded.symbol", coinId, normalized);
    }

    private void activate(O order, Instant now) throws SQLException {
        order.status = OrderStatus.OPEN; order.acceptedAt = now; order.sequence = nextSequence();
    }
    private boolean fresh(MarketQuote quote, Instant now) {
        return quote.verified() && !quote.sourceTime().isAfter(now)
                && !quote.sourceTime().isBefore(now.minus(settings.maximumQuoteAge()));
    }
    private BigDecimal unitPrice(MarketQuote quote) { return quote.referencePrice().multiply(settings.unitsPerReference()); }

    public OrderView cancelOrder(UUID owner, String id, Instant now) {
        return cancelOrder(owner, id, now, "Cancelled by owner");
    }
    OrderView cancelOrder(UUID owner, String id, Instant now, String explanation) {
        String detail = reason(explanation);
        return transaction(() -> {
            O order = requireOrder(id);
            if (!order.owner.equals(owner)) throw new IllegalArgumentException("Cannot cancel another player's order");
            if (order.view().active()) finish(order, OrderStatus.CANCELLED, detail, now);
            return order.view();
        });
    }
    public List<OrderView> expireOrders(Instant now) { return transaction(() -> expireInternal(now)); }
    private List<OrderView> expireInternal(Instant now) throws SQLException {
        List<OrderView> expired = new ArrayList<>();
        for (O order : queryOrders("SELECT * FROM orders WHERE status IN ('OPEN','FUNDING') AND expires_at<=?", now)) {
            finish(order, OrderStatus.EXPIRED, "Order expired", now);
            expired.add(order.view());
        }
        return List.copyOf(expired);
    }
    private void finish(O order, OrderStatus status, String explanation, Instant now) throws SQLException {
        if (!order.view().active()) return;
        if (order.side == Side.BUY) {
            move(order.id, reserved(order.owner), available(order.owner), cashAsset(), order.remainingBudget,
                    "ORDER_RELEASE", explanation, now);
            order.remainingBudget = ZERO;
        } else {
            move(order.id, reserved(order.owner), available(order.owner), coinAsset(order.coinId), order.remainingQuantity,
                    "ORDER_RELEASE", explanation, now);
            order.remainingQuantity = ZERO;
        }
        order.status = status; order.reason = explanation;
        execute("UPDATE transfers SET state='CANCELLED', reason=?, updated_at=? WHERE order_id=? AND state='PREPARED'",
                explanation, now, order.id);
        save(order);
    }

    public Optional<TransferView> preparePayout(UUID owner, Instant now) {
        return transaction(() -> {
            if (frozen(owner) || exists("SELECT 1 FROM transfers WHERE owner=? AND purpose='PAYOUT' AND state='PREPARED' LIMIT 1", owner))
                return Optional.empty();
            BigDecimal amount = balance(available(owner), cashAsset()).setScale(settings.moneyScale(), RoundingMode.DOWN);
            if (amount.signum() == 0) return Optional.empty();
            TransferView transfer = createTransfer(owner, TransferDirection.DEPOSIT, TransferPurpose.PAYOUT,
                    amount, null, "Pay available trading credit", now);
            move(transfer.id(), available(owner), reserved(owner), cashAsset(), amount,
                    "PAYOUT_RESERVE", "Reserve trading credit for payout", now);
            return Optional.of(transfer);
        });
    }

    public TransferView depositReserve(UUID owner, BigDecimal amount, Instant now) {
        positiveAmount(amount);
        return transaction(() -> {
            requireUnfrozen(owner);
            return createTransfer(owner, TransferDirection.WITHDRAW, TransferPurpose.RESERVE_DEPOSIT,
                    exactMoney(amount), null, "Administrator funds server reserve", now);
        });
    }
    public TransferView withdrawReserve(UUID owner, BigDecimal amount, Instant now) {
        positiveAmount(amount);
        return transaction(() -> {
            requireUnfrozen(owner);
            BigDecimal quantized = exactMoney(amount);
            TransferView transfer = createTransfer(owner, TransferDirection.DEPOSIT, TransferPurpose.RESERVE_WITHDRAWAL,
                    quantized, null, "Administrator withdraws server reserve", now);
            move(transfer.id(), TREASURY, TREASURY_RESERVED, cashAsset(), quantized,
                    "RESERVE_WITHDRAWAL_RESERVE", "Reserve treasury withdrawal", now);
            return transfer;
        });
    }
    private BigDecimal exactMoney(BigDecimal amount) {
        try { return amount.setScale(settings.moneyScale(), RoundingMode.UNNECESSARY); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("Amount exceeds economy currency precision", error); }
    }
    public void grantCoins(UUID owner, String symbol, String coinId, BigDecimal amount, String explanation, Instant now) {
        quantity(amount);
        String coin = normalizeCoin(coinId);
        String detail = reason(explanation);
        transaction(() -> {
            requireUnfrozen(owner); registerCoin(symbol, coin);
            String id = UUID.randomUUID().toString();
            move(id, ISSUANCE, available(owner), coinAsset(coin), amount, "ADMIN_COIN_GRANT", detail, now);
            audit("COIN_GRANT", owner.toString(), amount.toPlainString() + " " + coin + ": " + detail, now);
            return null;
        });
    }

    private TransferView createTransfer(UUID owner, TransferDirection direction, TransferPurpose purpose,
                                        BigDecimal amount, String orderId, String explanation, Instant now) throws SQLException {
        if (amount.signum() <= 0) throw new IllegalArgumentException("External transfer amount must be positive");
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO transfers(id,owner,direction,purpose,amount,state,order_id,reason,created_at,updated_at) VALUES(?,?,?,?,?,'PREPARED',?,?,?,?)",
                id, owner, direction, purpose, amount, orderId, explanation, now, now);
        return requireTransfer(id);
    }

    public TransferView markCalling(String id, Instant now) {
        return transaction(() -> {
            TransferView transfer = requireTransfer(id);
            if (transfer.state() != TransferState.PREPARED) throw new IllegalArgumentException("Transfer is not prepared");
            if (meta("provider") == null) throw new IllegalArgumentException("No economy provider bound to the ledger");
            requireUnfrozen(transfer.owner());
            if (transfer.orderId() != null) {
                O order = requireOrder(transfer.orderId());
                if (order.status != OrderStatus.FUNDING || !now.isBefore(order.expiresAt))
                    throw new IllegalArgumentException("Funding order is no longer active");
            }
            execute("UPDATE transfers SET state='CALLING', updated_at=? WHERE id=?", now, id);
            return requireTransfer(id);
        });
    }

    public TransferView completeTransfer(String id, TransferOutcome outcome, String detail, Instant now) {
        Objects.requireNonNull(outcome, "outcome");
        return transaction(() -> {
            TransferView transfer = requireTransfer(id);
            if (transfer.state().name().equals(outcome.name())) return transfer;
            if (transfer.state() != TransferState.CALLING)
                throw new IllegalArgumentException("Only an in-progress Vault call may be completed");
            finishTransfer(transfer, outcome, Objects.requireNonNullElse(detail, ""), now);
            return requireTransfer(id);
        });
    }

    public TransferView reconcileTransfer(String id, boolean applied, String explanation, Instant now) {
        String detail = reason(explanation);
        return transaction(() -> {
            TransferView transfer = requireTransfer(id);
            if (transfer.state() != TransferState.UNCERTAIN)
                throw new IllegalArgumentException("Only uncertain Vault transfers may be reconciled");
            finishTransfer(transfer, applied ? TransferOutcome.APPLIED : TransferOutcome.REJECTED, detail, now);
            audit("RECONCILE", id, (applied ? "Applied: " : "Not applied: ") + detail, now);
            return requireTransfer(id);
        });
    }

    private void finishTransfer(TransferView transfer, TransferOutcome outcome, String detail, Instant now) throws SQLException {
        if (detail.length() > 1000) detail = detail.substring(0, 1000);
        if (outcome == TransferOutcome.APPLIED) {
            switch (transfer.purpose()) {
                case BUY_FUNDING -> {
                    move(transfer.id(), VAULT, available(transfer.owner()), cashAsset(), transfer.amount(),
                            "VAULT_DEPOSIT", "Vault debit confirmed", now);
                    O order = requireOrder(transfer.orderId());
                    if (order.status == OrderStatus.FUNDING && now.isBefore(order.expiresAt)
                            && order.mode == settings.mode()) {
                        BigDecimal needed = order.budget.subtract(order.remainingBudget);
                        move(transfer.id(), available(order.owner), reserved(order.owner), cashAsset(), needed,
                                "BUY_RESERVE", "Funded buy order", now);
                        order.remainingBudget = order.budget; activate(order, now); save(order);
                    } else if (order.view().active()) finish(order, OrderStatus.EXPIRED, "Funding completed after expiry", now);
                }
                case PAYOUT -> move(transfer.id(), reserved(transfer.owner()), VAULT, cashAsset(), transfer.amount(),
                        "VAULT_PAYOUT", "Vault credit confirmed", now);
                case RESERVE_DEPOSIT -> move(transfer.id(), VAULT, TREASURY, cashAsset(), transfer.amount(),
                        "RESERVE_DEPOSIT", "Administrator reserve deposit confirmed", now);
                case RESERVE_WITHDRAWAL -> move(transfer.id(), TREASURY_RESERVED, VAULT, cashAsset(), transfer.amount(),
                        "RESERVE_WITHDRAWAL", "Administrator reserve withdrawal confirmed", now);
            }
        } else if (outcome == TransferOutcome.REJECTED) {
            switch (transfer.purpose()) {
                case BUY_FUNDING -> {
                    O order = requireOrder(transfer.orderId());
                    if (order.view().active()) finish(order, OrderStatus.REJECTED, "Vault funding rejected", now);
                }
                case PAYOUT -> move(transfer.id(), reserved(transfer.owner()), available(transfer.owner()), cashAsset(),
                        transfer.amount(), "PAYOUT_RELEASE", "Vault payout rejected", now);
                case RESERVE_WITHDRAWAL -> move(transfer.id(), TREASURY_RESERVED, TREASURY, cashAsset(), transfer.amount(),
                        "RESERVE_RELEASE", "Vault reserve withdrawal rejected", now);
                case RESERVE_DEPOSIT -> { }
            }
        }
        execute("UPDATE transfers SET state=?, reason=?, updated_at=? WHERE id=?", outcome, detail, now, transfer.id());
    }

    /** Execute against one immutable external-price snapshot. Never uses a player-set trade price. */
    public List<TradeView> processQuote(MarketQuote quote, Instant now) {
        Objects.requireNonNull(quote, "quote");
        return transaction(() -> {
            expireInternal(now);
            if (!fresh(quote, now)) return List.of();
            BigDecimal price = unitPrice(quote);
            List<O> eligible = new ArrayList<>();
            for (O order : queryOrders("SELECT * FROM orders WHERE coin_id=? AND status='OPEN' ORDER BY seq", quote.coinId())) {
                if (order.mode != settings.mode() || order.acceptedAt == null
                        || !quote.sourceTime().isAfter(order.acceptedAt) || frozen(order.owner)
                        || !order.guard.accepts(price)) continue;
                // A rising price cannot turn one admitted sell order into unlimited proceeds.
                // Keep its priority and reserved coins until the entire remainder fits again.
                if (order.side == Side.SELL && order.filledValue.add(order.remainingQuantity.multiply(price))
                        .compareTo(settings.maximumOrderValue()) > 0) continue;
                BigDecimal possible = executableQuantity(order, price);
                if (possible.signum() == 0 || remainingValue(order, possible, price).compareTo(settings.minimumOrderValue()) < 0) {
                    finish(order, OrderStatus.CANCELLED, "Remaining order is below the minimum executable value", now);
                    continue;
                }
                eligible.add(order);
            }
            List<TradeView> results = new ArrayList<>();
            if (settings.mode() != TradingSettings.Mode.P2P) {
                for (O order : eligible) {
                    TradeView trade = executeBroker(order, price, quote.sourceTime(), now);
                    if (trade != null) results.add(trade);
                }
            } else {
                List<O> buys = eligible.stream().filter(order -> order.side == Side.BUY).toList();
                List<O> sells = eligible.stream().filter(order -> order.side == Side.SELL).toList();
                for (O buy : buys) {
                    for (O sell : sells) {
                        if (buy.status != OrderStatus.OPEN) break;
                        if (sell.status != OrderStatus.OPEN || sell.owner.equals(buy.owner)) continue;
                        BigDecimal amount = executableQuantity(buy, price).min(sell.remainingQuantity);
                        if (amount.signum() == 0) break;
                        results.add(executePeer(buy, sell, amount, price, quote.sourceTime(), now));
                        finishDust(buy, price, now);
                        finishDust(sell, price, now);
                    }
                }
            }
            return List.copyOf(results);
        });
    }

    private BigDecimal executableQuantity(O order, BigDecimal price) {
        if (order.side == Side.SELL) return order.remainingQuantity;
        BigDecimal quantity = order.remainingBudget.divide(price.multiply(BigDecimal.ONE.add(order.feeRate)),
                QUANTITY_SCALE, RoundingMode.DOWN);
        return order.remainingQuantity == null
                ? quantity.min(MAXIMUM_BUDGET_QUANTITY.subtract(order.filledQuantity).max(ZERO))
                : quantity.min(order.remainingQuantity);
    }

    static BigDecimal previewBudgetQuantity(BigDecimal budget, BigDecimal price, BigDecimal feeRate) {
        return budget.divide(price.multiply(BigDecimal.ONE.add(feeRate)), QUANTITY_SCALE, RoundingMode.DOWN)
                .min(MAXIMUM_BUDGET_QUANTITY);
    }

    private BigDecimal remainingValue(O order, BigDecimal quantity, BigDecimal price) {
        // Buy limits include fees. Decimal quantity rounding must not reject a minimum-size budget.
        return order.side == Side.BUY ? order.remainingBudget : quantity.multiply(price);
    }

    private TradeView executeBroker(O order, BigDecimal price, Instant sourceTime, Instant now) throws SQLException {
        BigDecimal quantity = executableQuantity(order, price);
        BigDecimal notional = quantity.multiply(price);
        BigDecimal fee = notional.multiply(order.feeRate);
        String id = UUID.randomUUID().toString();
        if (order.side == Side.BUY) {
            BigDecimal cost = notional.add(fee);
            move(id, reserved(order.owner), TREASURY, cashAsset(), cost,
                    "BROKER_BUY", "Buy coins at external reference price", now);
            move(id, ISSUANCE, available(order.owner), coinAsset(order.coinId), quantity,
                    "BROKER_BUY", "Broker issues purchased virtual coins", now);
            order.remainingBudget = order.remainingBudget.subtract(cost);
            if (order.remainingQuantity != null) order.remainingQuantity = order.remainingQuantity.subtract(quantity);
        } else {
            BigDecimal proceeds = notional.subtract(fee);
            BigDecimal liquid = balance(TREASURY, cashAsset());
            if (order.mode == TradingSettings.Mode.BROKER_RESERVE && liquid.compareTo(proceeds) < 0) {
                finish(order, OrderStatus.REJECTED, "Server reserve cannot cover this sale", now);
                return null;
            }
            BigDecimal treasuryPart = liquid.min(proceeds);
            move(id, TREASURY, available(order.owner), cashAsset(), treasuryPart,
                    "BROKER_SELL", "Server reserve funds sale proceeds", now);
            move(id, ISSUANCE, available(order.owner), cashAsset(), proceeds.subtract(treasuryPart),
                    "UNLIMITED_BROKER_ISSUANCE", "Unlimited broker funds remaining sale proceeds", now);
            move(id, reserved(order.owner), ISSUANCE, coinAsset(order.coinId), quantity,
                    "BROKER_SELL", "Broker retires sold virtual coins", now);
            order.remainingQuantity = order.remainingQuantity.subtract(quantity);
        }
        order.filledQuantity = order.filledQuantity.add(quantity);
        order.filledValue = order.filledValue.add(notional);
        TradeView result = new TradeView(id, order.coinId, order.symbol, quantity, price, notional,
                order.side == Side.BUY ? fee : ZERO, order.side == Side.SELL ? fee : ZERO,
                order.side == Side.BUY ? order.owner : null, order.side == Side.SELL ? order.owner : null,
                now, sourceTime, order.mode);
        recordTrade(result, order.side == Side.BUY ? order.id : null, order.side == Side.SELL ? order.id : null);
        finish(order, OrderStatus.FILLED, "Executed at the next fresh external price", now);
        return result;
    }

    private TradeView executePeer(O buy, O sell, BigDecimal quantity, BigDecimal price,
                                   Instant sourceTime, Instant now) throws SQLException {
        String id = UUID.randomUUID().toString();
        BigDecimal notional = quantity.multiply(price);
        BigDecimal buyFee = notional.multiply(buy.feeRate);
        BigDecimal sellFee = notional.multiply(sell.feeRate);
        move(id, reserved(buy.owner), available(sell.owner), cashAsset(), notional.subtract(sellFee),
                "P2P_FILL", "Seller proceeds at external reference price", now);
        move(id, reserved(buy.owner), TREASURY, cashAsset(), buyFee.add(sellFee),
                "P2P_FEES", "Trade fees fixed when orders were accepted", now);
        move(id, reserved(sell.owner), available(buy.owner), coinAsset(buy.coinId), quantity,
                "P2P_FILL", "Transfer reserved coins to buyer", now);
        buy.remainingBudget = buy.remainingBudget.subtract(notional.add(buyFee));
        if (buy.remainingQuantity != null) buy.remainingQuantity = buy.remainingQuantity.subtract(quantity);
        sell.remainingQuantity = sell.remainingQuantity.subtract(quantity);
        for (O order : List.of(buy, sell)) {
            order.filledQuantity = order.filledQuantity.add(quantity);
            order.filledValue = order.filledValue.add(notional);
            save(order);
        }
        TradeView result = new TradeView(id, buy.coinId, buy.symbol, quantity, price, notional,
                buyFee, sellFee, buy.owner, sell.owner, now, sourceTime, TradingSettings.Mode.P2P);
        recordTrade(result, buy.id, sell.id);
        return result;
    }

    private void finishDust(O order, BigDecimal price, Instant now) throws SQLException {
        if (order.side == Side.BUY && order.maximumQuantity == null
                && order.filledQuantity.compareTo(MAXIMUM_BUDGET_QUANTITY) >= 0) {
            finish(order, OrderStatus.FILLED, "Maximum supported coin quantity reached; remaining budget released", now);
            return;
        }
        if (order.remainingQuantity != null && order.remainingQuantity.signum() == 0
                || order.side == Side.BUY && order.remainingBudget.signum() == 0) {
            finish(order, OrderStatus.FILLED, "Order completely filled", now);
            return;
        }
        BigDecimal possible = executableQuantity(order, price);
        if (possible.signum() == 0 || remainingValue(order, possible, price).compareTo(settings.minimumOrderValue()) < 0)
            finish(order, OrderStatus.CANCELLED, "Small unfilled remainder released", now);
    }

    private void recordTrade(TradeView trade, String buyOrder, String sellOrder) throws SQLException {
        execute("INSERT INTO trades(id,coin_id,symbol,quantity,unit_price,notional,buy_fee,sell_fee,buyer,seller,executed_at,source_time,mode,buy_order,sell_order) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                trade.id(), trade.coinId(), trade.symbol(), trade.quantity(), trade.unitPrice(), trade.notional(),
                trade.buyFee(), trade.sellFee(), trade.buyer(), trade.seller(), trade.executedAt(), trade.sourceTime(),
                trade.mode(), buyOrder, sellOrder);
    }

    private String cashAsset() { return "CASH:" + settings.referenceCurrency(); }
    private static String coinAsset(String coinId) { return "COIN:" + coinId; }
    private static String available(UUID owner) { return "player:" + owner + ":available"; }
    private static String reserved(UUID owner) { return "player:" + owner + ":reserved"; }
    private static String normalizeCoin(String coinId) {
        String result = Objects.requireNonNull(coinId, "coinId").toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid coin ID");
        return result;
    }
    private static BigDecimal decimal(ResultSet row, String column) throws SQLException {
        String text = row.getString(column);
        return text == null ? null : new BigDecimal(text);
    }
    private static UUID uuid(String text) { return text == null ? null : UUID.fromString(text); }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : Instant.ofEpochMilli(value);
    }
    private static void quantity(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.precision() > 28
                || Math.abs((long) amount.scale()) > QUANTITY_SCALE)
            throw new IllegalArgumentException("Quantity must be positive, with at most 12 decimal places");
    }
    private static void positiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.precision() > 28
                || Math.abs((long) amount.scale()) > 12)
            throw new IllegalArgumentException("Invalid money amount");
    }
    private static String reason(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("A reason is required");
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
    private String meta(String key) throws SQLException {
        try (PreparedStatement statement = statement("SELECT value FROM meta WHERE key=?", key);
             ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
    }
    private void setMeta(String key, String value) throws SQLException {
        execute("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value);
    }
    private long nextSequence() throws SQLException {
        String old = meta("sequence");
        long next = old == null ? 1 : Math.addExact(Long.parseLong(old), 1);
        setMeta("sequence", Long.toString(next));
        return next;
    }
    private BigDecimal balance(String account, String asset) throws SQLException {
        try (PreparedStatement statement = statement("SELECT amount FROM balances WHERE account=? AND asset=?", account, asset);
             ResultSet rows = statement.executeQuery()) { return rows.next() ? new BigDecimal(rows.getString(1)) : ZERO; }
    }
    private void move(String transactionId, String from, String to, String asset, BigDecimal amount,
                      String kind, String explanation, Instant now) throws SQLException {
        if (amount.signum() < 0) throw new IllegalStateException("Negative ledger transfer");
        if (amount.signum() == 0) return;
        post(transactionId, from, asset, amount.negate(), kind, explanation, now);
        post(transactionId, to, asset, amount, kind, explanation, now);
    }
    private void post(String transactionId, String account, String asset, BigDecimal delta,
                      String kind, String explanation, Instant now) throws SQLException {
        BigDecimal next = balance(account, asset).add(delta);
        if (next.signum() < 0 && !account.startsWith("system:"))
            throw new IllegalArgumentException("Insufficient available or reserved balance");
        execute("INSERT INTO balances(account,asset,amount) VALUES(?,?,?) ON CONFLICT(account,asset) DO UPDATE SET amount=excluded.amount",
                account, asset, next);
        execute("INSERT INTO journal(transaction_id,account,asset,delta,kind,reason,created_at) VALUES(?,?,?,?,?,?,?)",
                transactionId, account, asset, delta, kind, explanation, now);
    }
    private void audit(String action, String subject, String detail, Instant now) throws SQLException {
        execute("INSERT INTO audit(action,subject,detail,created_at) VALUES(?,?,?,?)", action, subject, detail, now);
    }
    private boolean exists(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = statement(sql, parameters); ResultSet rows = statement.executeQuery()) {
            return rows.next();
        }
    }
    private PreparedStatement statement(String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = db.prepareStatement(sql);
        for (int i = 0; i < parameters.length; i++) {
            Object value = parameters[i];
            if (value instanceof BigDecimal amount) value = amount.toPlainString();
            else if (value instanceof Instant at) value = at.toEpochMilli();
            else if (value instanceof UUID || value instanceof Enum<?>) value = value.toString();
            statement.setObject(i + 1, value);
        }
        return statement;
    }
    private void execute(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = statement(sql, parameters)) { statement.execute(); }
    }
    @FunctionalInterface private interface Work<T> { T run() throws SQLException; }
    private <T> T read(Work<T> work) {
        try { return work.run(); }
        catch (SQLException error) { throw new IllegalStateException("Trading storage failed", error); }
    }
    private <T> T transaction(Work<T> work) {
        try {
            db.setAutoCommit(false);
            try {
                T result = work.run();
                db.commit();
                return result;
            } catch (RuntimeException | SQLException error) {
                try { db.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                throw error;
            } finally { db.setAutoCommit(true); }
        } catch (SQLException error) { throw new IllegalStateException("Trading storage failed", error); }
    }
    @Override public void close() {
        try { db.close(); }
        catch (SQLException error) { throw new IllegalStateException("Cannot close trading database", error); }
    }
}

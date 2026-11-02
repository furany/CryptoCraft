package de.cryptocraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static de.cryptocraft.TradingEngine.*;
import static de.cryptocraft.TradingSettings.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class TradingEngineTest {
    @TempDir Path directory;
    private static final Instant START = Instant.parse("2026-11-02T10:00:00Z");
    private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private Path file() { return directory.resolve("economy.sqlite"); }
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static Instant at(long second) { return START.plusSeconds(second); }
    private static MarketQuote quote(String value, long second) {
        return new MarketQuote("bitcoin", d(value), at(second), true);
    }
    private static TradingSettings settings(TradingSettings.Mode mode) { return settings(mode, "0", "0"); }
    private static TradingSettings settings(TradingSettings.Mode mode, String buyFee, String sellFee) {
        var defaults = TradingSettings.defaults();
        return new TradingSettings(mode, "EUR", BigDecimal.ONE, d(buyFee), d(sellFee),
                defaults.minimumOrderValue(), defaults.maximumOrderValue(), 2,
                defaults.orderLifetime(), defaults.maximumQuoteAge(), 20);
    }
    private TradingEngine open(TradingSettings settings) {
        var engine = new TradingEngine(file(), settings, at(0));
        engine.bindProvider("test-economy:global:coins");
        return engine;
    }
    private static OrderView buy(TradingEngine engine, UUID owner, String budget, long time) {
        return engine.placeBuy(owner, "BTC", "bitcoin", d(budget), PriceGuard.unbounded(), quote("10", time - 1), at(time));
    }
    private static void grant(TradingEngine engine, UUID owner, String amount) {
        engine.grantCoins(owner, "BTC", "bitcoin", d(amount), "Explicit test grant", at(0));
    }
    private static OrderView sell(TradingEngine engine, UUID owner, String quantity, long time) {
        return engine.placeSell(owner, "BTC", "bitcoin", d(quantity), PriceGuard.unbounded(), quote("10", time - 1), at(time));
    }
    private static TransferView funding(TradingEngine engine, OrderView order) {
        return engine.preparedTransfers().stream().filter(t -> order.id().equals(t.orderId())).findFirst().orElseThrow();
    }
    private static OrderView fund(TradingEngine engine, OrderView order, long time) {
        var transfer = funding(engine, order);
        engine.markCalling(transfer.id(), at(time));
        engine.completeTransfer(transfer.id(), TransferOutcome.APPLIED, "Economy debit succeeded", at(time + 1));
        return engine.order(order.id());
    }
    private static void money(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), () -> "Expected " + expected + ", got " + actual);
    }
    private void balancedJournal() throws Exception {
        Map<String, BigDecimal> totals = new HashMap<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file());
             var statement = connection.prepareStatement("SELECT asset,delta FROM journal");
             var rows = statement.executeQuery()) {
            while (rows.next()) totals.merge(rows.getString(1), d(rows.getString(2)), BigDecimal::add);
        }
        totals.forEach((asset, sum) -> assertEquals(0, sum.signum(), "Unbalanced asset " + asset));
    }

    @Test void fundingAndExecutionNeedSeparateVerifiedNewMarketObservations() throws Exception {
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            var order = buy(engine, BUYER, "100", 0);
            assertEquals(OrderStatus.FUNDING, order.status());
            assertNull(order.acceptedAt());
            assertTrue(engine.account(BUYER).coins().isEmpty());
            order = fund(engine, order, 1);
            assertEquals(OrderStatus.OPEN, order.status());
            money("100", engine.account(BUYER).cashReserved());
            assertTrue(engine.processQuote(quote("10", 2), at(3)).isEmpty()); // Equal source time is insufficient.
            assertTrue(engine.processQuote(new MarketQuote("bitcoin", d("10"), at(3), false), at(3)).isEmpty());
            assertTrue(engine.processQuote(quote("10", 4), at(3)).isEmpty()); // Future source timestamp.
            var fills = engine.processQuote(quote("10", 3), at(3));
            assertEquals(1, fills.size());
            money("10", engine.account(BUYER).coins().get("bitcoin").available());
            money("100", engine.treasuryBalance());
            assertEquals(OrderStatus.FILLED, engine.order(order.id()).status());
            assertTrue(engine.processQuote(quote("10", 3), at(3)).isEmpty());
        }
        balancedJournal();
    }

    @Test void desiredQuantityCapsPurchaseEvenWhenPriceFalls() {
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            var order = engine.placeBuy(BUYER, "BTC", "bitcoin", d("100"), d("2"),
                    PriceGuard.unbounded(), quote("10", -1), at(0));
            fund(engine, order, 1);
            engine.processQuote(quote("5", 3), at(3));
            money("2", engine.account(BUYER).coins().get("bitcoin").available());
            money("90", engine.account(BUYER).cashAvailable());
            money("0", engine.account(BUYER).cashReserved());
        }
    }

    @Test void fractionalMoneyBudgetCannotBeRoundedAboveTheConfirmedMaximum() {
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            assertThrows(IllegalArgumentException.class, () -> buy(engine, BUYER, "100.001", 0));
            assertTrue(engine.orders(BUYER).isEmpty());
            assertTrue(engine.preparedTransfers().isEmpty());
            var order = buy(engine, BUYER, "100.00", 0);
            money("100.00", funding(engine, order).amount());
        }
    }

    @Test void minimumBudgetIncludesFeesAndSurvivesRepeatingDecimalQuantity() {
        try (var engine = open(settings(BROKER_UNLIMITED, "0.01", "0"))) {
            var order = engine.placeBuy(BUYER, "BTC", "bitcoin", d("1"), PriceGuard.unbounded(), quote("3", -1), at(0));
            fund(engine, order, 1);
            var fills = engine.processQuote(quote("3", 3), at(3));
            assertEquals(1, fills.size());
            var fill = fills.getFirst();
            assertTrue(fill.notional().add(fill.buyFee()).compareTo(d("1")) <= 0);
            assertEquals(OrderStatus.FILLED, engine.order(order.id()).status());
            money("1", fill.notional().add(fill.buyFee()).add(engine.account(BUYER).cashAvailable()));
        }
    }

    @Test void favorableTinyPriceCannotCreateAnUnsellableCoinQuantity() {
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            var order = fund(engine, buy(engine, BUYER, "100", 0), 1);
            var fills = engine.processQuote(quote("0.000000000000000000000000000001", 3), at(3));
            assertEquals(1, fills.size());
            BigDecimal quantity = fills.getFirst().quantity();
            assertEquals(0, TradingEngine.MAXIMUM_BUDGET_QUANTITY.compareTo(quantity));
            assertTrue(quantity.precision() <= 28);
            money("100", engine.account(BUYER).cashAvailable().add(fills.getFirst().notional()));
            assertEquals(OrderStatus.FILLED, engine.order(order.id()).status());
            assertDoesNotThrow(() -> engine.placeSell(BUYER, "BTC", "bitcoin", quantity,
                    PriceGuard.unbounded(), quote("0.0000000001", 4), at(4)));
        }
    }

    @Test void peerPartialFillsShareOneCumulativeBudgetQuantityCap() {
        try (var engine = open(settings(P2P))) {
            for (UUID seller : List.of(SELLER, OTHER)) {
                grant(engine, seller, "5000000000000000");
                engine.placeSell(seller, "BTC", "bitcoin", d("5000000000000000"), PriceGuard.unbounded(),
                        quote("0.0000000001", -1), at(0));
            }
            var buy = fund(engine, buy(engine, BUYER, "1000000", 0), 1);
            var fills = engine.processQuote(quote("0.0000000001", 3), at(3));
            assertEquals(2, fills.size());
            assertEquals(0, TradingEngine.MAXIMUM_BUDGET_QUANTITY.compareTo(
                    fills.stream().map(TradeView::quantity).reduce(BigDecimal.ZERO, BigDecimal::add)));
            assertEquals(OrderStatus.FILLED, engine.order(buy.id()).status());
            money("0", engine.account(BUYER).cashReserved());
            assertTrue(engine.account(BUYER).cashAvailable().signum() > 0);
        }
    }

    @Test void risingPricesCannotExceedAggregateSellLimitAcrossPartialFills() {
        var base = settings(P2P);
        var capped = new TradingSettings(base.mode(), base.referenceCurrency(), base.unitsPerReference(),
                base.buyFeeRate(), base.sellFeeRate(), base.minimumOrderValue(), d("100"),
                base.moneyScale(), base.orderLifetime(), base.maximumQuoteAge(), base.maximumOpenOrdersPerPlayer());
        try (var engine = open(capped)) {
            grant(engine, SELLER, "10");
            var sale = sell(engine, SELLER, "10", 0);
            fund(engine, buy(engine, BUYER, "40", 0), 1);
            assertEquals(1, engine.processQuote(quote("10", 3), at(3)).size());
            money("40", engine.order(sale.id()).filledValue());
            long priority = engine.order(sale.id()).sequence();
            fund(engine, buy(engine, OTHER, "60", 4), 5);
            assertTrue(engine.processQuote(quote("20", 7), at(7)).isEmpty());
            assertEquals(OrderStatus.OPEN, engine.order(sale.id()).status());
            money("6", engine.account(SELLER).coins().get("bitcoin").reserved());
            assertEquals(priority, engine.order(sale.id()).sequence());
            assertEquals(1, engine.processQuote(quote("10", 8), at(8)).size());
            money("100", engine.order(sale.id()).filledValue());
            assertEquals(OrderStatus.FILLED, engine.order(sale.id()).status());
        }
    }

    @Test void missingProviderKeepsStoredPrecisionWithoutChangingBalances() {
        var base = settings(BROKER_UNLIMITED);
        var threeDigits = new TradingSettings(base.mode(), base.referenceCurrency(), base.unitsPerReference(),
                base.buyFeeRate(), base.sellFeeRate(), base.minimumOrderValue(), base.maximumOrderValue(),
                3, base.orderLifetime(), base.maximumQuoteAge(), base.maximumOpenOrdersPerPlayer());
        try (var engine = open(threeDigits)) {
            grant(engine, SELLER, "0.123");
        }
        try (var engine = new TradingEngine(file(), base, at(2), true)) {
            assertEquals(3, engine.settings().moneyScale());
            money("0.123", engine.account(SELLER).coins().get("bitcoin").available());
            assertThrows(IllegalArgumentException.class, () -> engine.checkSettings(base));
        }
    }

    @Test void rejectedFundingCannotCreateCoinsAndCancelledPreparedFundingCannotBeCalled() {
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            var order = buy(engine, BUYER, "100", 0);
            var transfer = funding(engine, order);
            engine.markCalling(transfer.id(), at(1));
            engine.completeTransfer(transfer.id(), TransferOutcome.REJECTED, "Insufficient funds", at(2));
            assertEquals(OrderStatus.REJECTED, engine.order(order.id()).status());
            money("0", engine.account(BUYER).cashAvailable());
            assertTrue(engine.account(BUYER).coins().isEmpty());
            var other = buy(engine, BUYER, "100", 3);
            var pending = funding(engine, other);
            engine.cancelOrder(BUYER, other.id(), at(4));
            assertEquals(TransferState.CANCELLED, engine.transfer(pending.id()).state());
            assertThrows(IllegalArgumentException.class, () -> engine.markCalling(pending.id(), at(5)));
        }
    }

    @Test void cancellationDuringExternalCallReturnsLateFundingAsCredit() {
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            var order = buy(engine, BUYER, "100", 0);
            var transfer = funding(engine, order);
            engine.markCalling(transfer.id(), at(1));
            engine.cancelOrder(BUYER, order.id(), at(2));
            engine.completeTransfer(transfer.id(), TransferOutcome.APPLIED, "Late success", at(3));
            assertEquals(OrderStatus.CANCELLED, engine.order(order.id()).status());
            money("100", engine.account(BUYER).cashAvailable());
            money("0", engine.account(BUYER).cashReserved());
            assertTrue(engine.processQuote(quote("10", 4), at(4)).isEmpty());
            var next = buy(engine, BUYER, "50", 5);
            assertEquals(OrderStatus.OPEN, next.status()); // Reuses credit, no second withdrawal.
            assertTrue(engine.preparedTransfers().isEmpty());
            money("50", engine.account(BUYER).cashAvailable());
            money("50", engine.account(BUYER).cashReserved());
        }
    }

    @Test void p2pIsFifoPartialAndUsesExternalPriceRatherThanPreviewPrice() throws Exception {
        try (var engine = open(settings(P2P))) {
            grant(engine, SELLER, "8");
            var sell = sell(engine, SELLER, "8", 0);
            var first = fund(engine, buy(engine, BUYER, "100", 0), 1);
            var second = fund(engine, buy(engine, OTHER, "100", 0), 3);
            var fills = engine.processQuote(quote("20", 5), at(5));
            assertEquals(2, fills.size());
            assertEquals(BUYER, fills.getFirst().buyer());
            money("5", fills.getFirst().quantity());
            money("3", fills.getLast().quantity());
            money("20", fills.getFirst().unitPrice());
            assertEquals(OrderStatus.FILLED, engine.order(first.id()).status());
            assertEquals(OrderStatus.FILLED, engine.order(sell.id()).status());
            assertEquals(OrderStatus.OPEN, engine.order(second.id()).status());
            money("40", engine.account(OTHER).cashReserved());
            money("160", engine.account(SELLER).cashAvailable());
            engine.cancelOrder(OTHER, second.id(), at(6));
            money("40", engine.account(OTHER).cashAvailable());
            money("0", engine.account(OTHER).cashReserved());
        }
        balancedJournal();
    }

    @Test void quoteMustPostdateBothCounterpartiesAndNeverFallsBackToBroker() {
        try (var engine = open(settings(P2P))) {
            var order = fund(engine, buy(engine, BUYER, "100", 0), 1);
            assertTrue(engine.processQuote(quote("10", 3), at(3)).isEmpty());
            grant(engine, SELLER, "10");
            sell(engine, SELLER, "10", 4);
            assertTrue(engine.processQuote(quote("10", 3), at(4)).isEmpty());
            assertTrue(engine.processQuote(quote("10", 4), at(4)).isEmpty());
            assertEquals(OrderStatus.OPEN, engine.order(order.id()).status());
            assertEquals(1, engine.processQuote(quote("10", 5), at(5)).size());
        }
    }

    @Test void ownOrdersNeverMatchButOtherCounterpartiesCan() {
        try (var engine = open(settings(P2P))) {
            grant(engine, BUYER, "10");
            sell(engine, BUYER, "10", 0);
            fund(engine, buy(engine, BUYER, "100", 0), 1);
            assertTrue(engine.processQuote(quote("10", 3), at(3)).isEmpty());
            grant(engine, SELLER, "10");
            sell(engine, SELLER, "10", 4);
            var fills = engine.processQuote(quote("10", 5), at(5));
            assertEquals(1, fills.size());
            assertEquals(SELLER, fills.getFirst().seller());
            assertEquals(BUYER, fills.getFirst().buyer());
            money("10", engine.account(BUYER).coins().get("bitcoin").reserved());
        }
    }

    @Test void perOrderFeesSurviveSettingsChangesAndMoneyIsConserved() throws Exception {
        try (var engine = open(settings(P2P, "0.01", "0.02"))) {
            grant(engine, SELLER, "10");
            sell(engine, SELLER, "10", 0);
            fund(engine, buy(engine, BUYER, "101", 0), 1);
            engine.updateSettings(settings(P2P, "0.20", "0.30"), at(3));
            var fills = engine.processQuote(quote("10", 4), at(4));
            money("1", fills.getFirst().buyFee());
            money("2", fills.getFirst().sellFee());
            money("98", engine.account(SELLER).cashAvailable());
            money("3", engine.treasuryBalance());
            money("10", engine.account(BUYER).coins().get("bitcoin").available());
            money("0", engine.account(BUYER).cashReserved());
        }
        balancedJournal();
    }

    @Test void guardsAreEligibilityChecksNotExecutionPricesAndStaleQuotesPauseTrading() {
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            var order = engine.placeBuy(BUYER, "BTC", "bitcoin", d("110"),
                    new PriceGuard(null, d("12")), quote("10", -1), at(0));
            fund(engine, order, 1);
            assertTrue(engine.processQuote(quote("15", 3), at(3)).isEmpty());
            assertTrue(engine.processQuote(quote("11", 3), at(124)).isEmpty());
            var fills = engine.processQuote(quote("11", 125), at(125));
            money("11", fills.getFirst().unitPrice());
            money("10", fills.getFirst().quantity());
        }
    }

    @Test void expiryReleasesBothKindsOfReservationsAndCancelsUnstartedFunding() {
        var base = settings(P2P);
        var shortLived = new TradingSettings(base.mode(), base.referenceCurrency(), base.unitsPerReference(),
                base.buyFeeRate(), base.sellFeeRate(), base.minimumOrderValue(), base.maximumOrderValue(),
                2, Duration.ofSeconds(5), base.maximumQuoteAge(), 20);
        try (var engine = open(shortLived)) {
            grant(engine, SELLER, "10");
            var sale = sell(engine, SELLER, "10", 0);
            var funded = fund(engine, buy(engine, BUYER, "100", 0), 1);
            var pending = buy(engine, OTHER, "100", 0);
            var transfer = funding(engine, pending);
            assertEquals(3, engine.expireOrders(at(5)).size());
            assertEquals(OrderStatus.EXPIRED, engine.order(sale.id()).status());
            assertEquals(OrderStatus.EXPIRED, engine.order(funded.id()).status());
            assertEquals(TransferState.CANCELLED, engine.transfer(transfer.id()).state());
            money("10", engine.account(SELLER).coins().get("bitcoin").available());
            money("100", engine.account(BUYER).cashAvailable());
            assertTrue(engine.marketCoinIds().isEmpty());
        }
    }

    @Test void payoutRoundsOnlyAtVaultBoundaryAndRejectedPayoutRemainsClaimable() {
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            grant(engine, SELLER, "1.2345");
            sell(engine, SELLER, "1.2345", 0);
            engine.processQuote(quote("10", 1), at(1));
            var payout = engine.preparePayout(SELLER, at(2)).orElseThrow();
            money("12.34", payout.amount());
            money("0.005", engine.account(SELLER).cashAvailable());
            assertTrue(engine.preparePayout(SELLER, at(2)).isEmpty());
            engine.markCalling(payout.id(), at(3));
            engine.completeTransfer(payout.id(), TransferOutcome.REJECTED, "Provider unavailable", at(4));
            money("12.345", engine.account(SELLER).cashAvailable());
            var retry = engine.preparePayout(SELLER, at(5)).orElseThrow();
            assertNotEquals(payout.id(), retry.id());
            engine.markCalling(retry.id(), at(6));
            engine.completeTransfer(retry.id(), TransferOutcome.APPLIED, "Paid", at(7));
            engine.completeTransfer(retry.id(), TransferOutcome.APPLIED, "Duplicate callback", at(8));
            money("0.005", engine.account(SELLER).cashAvailable());
            money("0", engine.account(SELLER).cashReserved());
        }
    }

    @Test void crashDuringVaultCallQuarantinesUntilExplicitReconciliation() {
        String transferId;
        String orderId;
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            var order = buy(engine, BUYER, "100", 0);
            orderId = order.id();
            transferId = funding(engine, order).id();
            engine.markCalling(transferId, at(1));
        }
        try (var engine = new TradingEngine(file(), settings(BROKER_UNLIMITED), at(3))) {
            assertEquals(TransferState.UNCERTAIN, engine.transfer(transferId).state());
            assertTrue(engine.account(BUYER).frozen());
            assertTrue(engine.preparedTransfers().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> buy(engine, BUYER, "100", 3));
            engine.reconcileTransfer(transferId, true, "Confirmed debit in economy records", at(4));
            assertFalse(engine.account(BUYER).frozen());
            assertEquals(OrderStatus.OPEN, engine.order(orderId).status());
            money("100", engine.account(BUYER).cashReserved());
            assertThrows(IllegalArgumentException.class,
                    () -> engine.reconcileTransfer(transferId, true, "Repeated admin click", at(5)));
            engine.completeTransfer(transferId, TransferOutcome.APPLIED, "Delayed duplicate callback", at(6));
            money("100", engine.account(BUYER).cashReserved());
        }
    }

    @Test void preparedTransfersSurviveRestartWithoutInventingAnAmbiguousCall() {
        String id;
        try (var engine = open(settings(BROKER_UNLIMITED))) { id = funding(engine, buy(engine, BUYER, "100", 0)).id(); }
        try (var engine = new TradingEngine(file(), settings(BROKER_UNLIMITED), at(1))) {
            assertEquals(TransferState.PREPARED, engine.transfer(id).state());
            assertFalse(engine.account(BUYER).frozen());
            engine.markCalling(id, at(2));
            engine.completeTransfer(id, TransferOutcome.APPLIED, "Debited", at(3));
        }
        try (var engine = new TradingEngine(file(), settings(BROKER_UNLIMITED), at(4))) {
            assertEquals(TransferState.APPLIED, engine.transfer(id).state());
            money("100", engine.account(BUYER).cashReserved());
            assertTrue(engine.preparedTransfers().isEmpty());
        }
    }

    @Test void explicitUncertaintyPreservesReservedPayoutAndNegativeReconcileReleasesIt() {
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            grant(engine, SELLER, "10"); sell(engine, SELLER, "10", 0);
            engine.processQuote(quote("10", 1), at(1));
            var payout = engine.preparePayout(SELLER, at(2)).orElseThrow();
            engine.markCalling(payout.id(), at(3));
            engine.completeTransfer(payout.id(), TransferOutcome.UNCERTAIN, "Exception after provider invocation", at(4));
            money("100", engine.account(SELLER).cashReserved());
            assertTrue(engine.preparePayout(SELLER, at(5)).isEmpty());
            engine.reconcileTransfer(payout.id(), false, "Provider confirms no credit occurred", at(6));
            money("100", engine.account(SELLER).cashAvailable());
            money("0", engine.account(SELLER).cashReserved());
        }
    }

    @Test void reserveBrokerRequiresLiquidityAndReserveWithdrawalsAreReserved() throws Exception {
        try (var engine = open(settings(BROKER_RESERVE))) {
            grant(engine, SELLER, "10");
            var rejected = sell(engine, SELLER, "10", 0);
            assertTrue(engine.processQuote(quote("10", 1), at(1)).isEmpty());
            assertEquals(OrderStatus.REJECTED, engine.order(rejected.id()).status());
            money("10", engine.account(SELLER).coins().get("bitcoin").available());
            var deposit = engine.depositReserve(OTHER, d("100"), at(2));
            assertEquals(TransferDirection.WITHDRAW, deposit.direction());
            engine.markCalling(deposit.id(), at(3));
            engine.completeTransfer(deposit.id(), TransferOutcome.APPLIED, "Funded treasury", at(4));
            var withdrawal = engine.withdrawReserve(OTHER, d("40"), at(5));
            money("60", engine.treasuryBalance()); money("40", engine.treasuryReserved());
            engine.markCalling(withdrawal.id(), at(6));
            engine.completeTransfer(withdrawal.id(), TransferOutcome.REJECTED, "Provider refuses deposit", at(7));
            money("100", engine.treasuryBalance()); money("0", engine.treasuryReserved());
            sell(engine, SELLER, "10", 8);
            assertEquals(1, engine.processQuote(quote("10", 9), at(9)).size());
            money("100", engine.account(SELLER).cashAvailable());
            money("0", engine.treasuryBalance());
        }
        balancedJournal();
    }

    @Test void modeSwitchRequiresNoOpenOrdersAndKeepsAssetsAfterExplicitCancellation() {
        try (var engine = open(settings(P2P))) {
            grant(engine, SELLER, "10");
            var sale = sell(engine, SELLER, "10", 0);
            var purchase = buy(engine, BUYER, "100", 0);
            var transfer = funding(engine, purchase);
            engine.markCalling(transfer.id(), at(1));
            assertThrows(IllegalArgumentException.class, () -> engine.checkSettings(settings(BROKER_UNLIMITED)));
            assertEquals(P2P, engine.settings().mode());
            engine.completeTransfer(transfer.id(), TransferOutcome.APPLIED, "Debited", at(2));
            assertThrows(IllegalArgumentException.class, () -> engine.updateSettings(settings(BROKER_UNLIMITED), at(3)));
            assertEquals(OrderStatus.OPEN, engine.order(sale.id()).status());
            assertEquals(OrderStatus.OPEN, engine.order(purchase.id()).status());
            engine.cancelOrder(SELLER, sale.id(), at(3));
            engine.cancelOrder(BUYER, purchase.id(), at(3));
            engine.updateSettings(settings(BROKER_UNLIMITED), at(3));
            assertEquals(OrderStatus.CANCELLED, engine.order(sale.id()).status());
            assertEquals(OrderStatus.CANCELLED, engine.order(purchase.id()).status());
            money("100", engine.account(BUYER).cashAvailable());
            money("10", engine.account(SELLER).coins().get("bitcoin").available());
            assertTrue(engine.processQuote(quote("10", 4), at(4)).isEmpty());
        }
    }

    @Test void providerCurrencyFactorAndPrecisionChangesRequireMigrationAfterActivity() {
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            grant(engine, SELLER, "1");
            assertThrows(IllegalArgumentException.class, () -> engine.bindProvider("another-provider"));
            var base = settings(BROKER_UNLIMITED);
            var changed = new TradingSettings(base.mode(), "USD", d("2"), base.buyFeeRate(), base.sellFeeRate(),
                    base.minimumOrderValue(), base.maximumOrderValue(), 3, base.orderLifetime(), base.maximumQuoteAge(), 20);
            assertThrows(IllegalArgumentException.class, () -> engine.checkSettings(changed));
            assertThrows(IllegalArgumentException.class, () -> engine.updateSettings(changed, at(1)));
            assertEquals("EUR", engine.settings().referenceCurrency());
            engine.bindProvider("test-economy:global:coins");
        }
    }

    @Test void failedSqlTradeRollsBackBalancesOrdersAndHistoryTogether() throws Exception {
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            var order = fund(engine, buy(engine, BUYER, "100", 0), 1);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER fail_trade BEFORE INSERT ON trades BEGIN SELECT RAISE(FAIL,'injected failure'); END");
            }
            assertThrows(IllegalStateException.class, () -> engine.processQuote(quote("10", 3), at(3)));
            money("100", engine.account(BUYER).cashReserved());
            money("0", engine.treasuryBalance());
            assertTrue(engine.account(BUYER).coins().isEmpty());
            assertTrue(engine.history(BUYER, 10).isEmpty());
            assertEquals(OrderStatus.OPEN, engine.order(order.id()).status());
        }
        balancedJournal();
    }

    @Test void failureRecordingVaultSuccessLeavesCallingForRestartQuarantine() throws Exception {
        String id;
        try (var engine = open(settings(BROKER_UNLIMITED))) {
            id = funding(engine, buy(engine, BUYER, "100", 0)).id();
            engine.markCalling(id, at(1));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER fail_posting BEFORE INSERT ON journal BEGIN SELECT RAISE(FAIL,'disk write unavailable'); END");
            }
            assertThrows(IllegalStateException.class,
                    () -> engine.completeTransfer(id, TransferOutcome.APPLIED, "Provider succeeded", at(2)));
            assertEquals(TransferState.CALLING, engine.transfer(id).state());
            money("0", engine.account(BUYER).cashReserved());
        }
        try (var engine = new TradingEngine(file(), settings(BROKER_UNLIMITED), at(3))) {
            assertEquals(TransferState.UNCERTAIN, engine.transfer(id).state());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER fail_posting");
            }
            engine.reconcileTransfer(id, true, "Verified external debit after restoring storage", at(4));
            money("100", engine.account(BUYER).cashReserved());
        }
        balancedJournal();
    }

    @Test void insufficientCoinsAndOwnershipChecksNeverAlterSomeoneElsesOrder() {
        try (var engine = open(settings(P2P))) {
            assertThrows(IllegalArgumentException.class, () -> sell(engine, SELLER, "1", 0));
            assertTrue(engine.orders(SELLER).isEmpty());
            grant(engine, SELLER, "2");
            var order = sell(engine, SELLER, "2", 0);
            assertThrows(IllegalArgumentException.class, () -> engine.cancelOrder(BUYER, order.id(), at(1)));
            money("2", engine.account(SELLER).coins().get("bitcoin").reserved());
            engine.cancelOrder(SELLER, order.id(), at(1));
            engine.cancelOrder(SELLER, order.id(), at(2));
            money("2", engine.account(SELLER).coins().get("bitcoin").available());
        }
    }

    @Test void recentCancelledOrdersCannotHideAnOlderActiveOrder() {
        try (var engine = open(settings(P2P))) {
            grant(engine, SELLER, "1");
            var active = sell(engine, SELLER, "1", 0);
            for (int i = 1; i <= 205; i++) {
                var cancelled = buy(engine, SELLER, "1", i);
                engine.cancelOrder(SELLER, cancelled.id(), at(i));
            }
            assertEquals(active.id(), engine.orders(SELLER).getFirst().id());
            assertEquals(List.of(active.id()), engine.activeOrders().stream().map(OrderView::id).toList());
            money("1", engine.account(SELLER).coins().get("bitcoin").reserved());
        }
    }

    @Test void allActiveOrdersRemainVisibleWhenConfiguredLimitExceedsTwoHundredOrIsLowered() {
        var base = settings(P2P);
        var expanded = new TradingSettings(base.mode(), base.referenceCurrency(), base.unitsPerReference(),
                base.buyFeeRate(), base.sellFeeRate(), base.minimumOrderValue(), base.maximumOrderValue(),
                base.moneyScale(), base.orderLifetime(), base.maximumQuoteAge(), 300);
        try (var engine = open(expanded)) {
            for (int i = 0; i < 205; i++) buy(engine, BUYER, "1", 0);
            engine.updateSettings(base, at(1));
            assertEquals(205, engine.orders(BUYER).size());
            assertTrue(engine.orders(BUYER).stream().allMatch(OrderView::active));
        }
    }
}

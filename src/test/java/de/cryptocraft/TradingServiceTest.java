package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradingServiceTest {
    @TempDir Path directory;
    private final UUID owner = UUID.randomUUID();
    private final Queue<Runnable> mainTasks = new ConcurrentLinkedQueue<>();
    private CryptoCraftPlugin plugin;
    private CryptoBoardService boards;
    private CryptoPriceService prices;
    private Player player;
    private VaultBridge provider;
    private TradingService service;
    private MockedStatic<VaultBridge> discovery;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach void setup() {
        plugin = mock(CryptoCraftPlugin.class);
        boards = mock(CryptoBoardService.class);
        prices = mock(CryptoPriceService.class);
        player = mock(Player.class);
        provider = mock(VaultBridge.class);
        var config = YamlConfiguration.loadConfiguration(new java.io.File("src/main/resources/config.yml"));
        var messages = mock(CryptoMessages.class);
        when(messages.getLanguage()).thenReturn("en");
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.boards()).thenReturn(boards);
        when(plugin.prices()).thenReturn(prices);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.configuredPair("BTC", "EUR")).thenReturn(new QuotePair("BTC", "bitcoin", "EUR"));
        when(player.getUniqueId()).thenReturn(owner);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(boards.nearestTerminal(player, 6)).thenReturn(mock(CryptoBoard.class));
        when(prices.getQuote("bitcoin", "EUR")).thenAnswer(call ->
                new CryptoPriceService.Quote(new BigDecimal("10"), BigDecimal.ZERO, Instant.now().minusSeconds(1), true));
        when(provider.identity()).thenReturn("test-provider");
        when(provider.name()).thenReturn("Test Economy");
        when(provider.currency()).thenReturn("Coins");
        when(provider.digits()).thenReturn(2);
        when(provider.transfer(eq(owner), anyBoolean(), any())).thenReturn(
                new VaultBridge.Result(TradingEngine.TransferOutcome.APPLIED, "Test success"));
        discovery = mockStatic(VaultBridge.class);
        discovery.when(VaultBridge::discover).thenReturn(provider);
        bukkit = mockStatic(Bukkit.class);
    }

    @AfterEach void cleanup() {
        if (service != null) service.close();
        discovery.close();
        bukkit.close();
    }

    private void start() { service = new TradingService(plugin, mainTasks::add); }
    private void flushWorker() { service.view(owner).join(); }
    private void drainMain() {
        Runnable task;
        while ((task = mainTasks.poll()) != null) task.run();
        flushWorker();
    }
    private TradingService.Preview preview() { return service.preview(player, "BTC", true, new BigDecimal("100"), false); }

    @Test void confirmationRechecksTerminalPermissionAndCannotBeReused() {
        start();
        var preview = preview();
        when(boards.nearestTerminal(player, 6)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.submit(player, preview));
        when(boards.nearestTerminal(player, 6)).thenReturn(mock(CryptoBoard.class));
        when(player.hasPermission("cryptocraft.trade")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.submit(player, preview));
        when(player.hasPermission("cryptocraft.trade")).thenReturn(true);
        var order = service.submit(player, preview).join();
        assertEquals(TradingEngine.OrderStatus.FUNDING, order.status());
        assertThrows(IllegalArgumentException.class, () -> service.submit(player, preview));
        assertEquals(1, service.orders(owner).join().size());
    }

    @Test void unverifiedQuoteAndUnsupportedBudgetPrecisionAreRejectedBeforeFunding() {
        start();
        assertThrows(IllegalArgumentException.class,
                () -> service.preview(player, "BTC", true, new BigDecimal("100.001"), false));
        when(prices.getQuote("bitcoin", "EUR")).thenReturn(
                new CryptoPriceService.Quote(BigDecimal.TEN, BigDecimal.ZERO, Instant.now().minusSeconds(1), false));
        assertThrows(IllegalArgumentException.class, this::preview);
        assertTrue(service.orders(owner).join().isEmpty());
        assertTrue(service.transfers(owner).join().isEmpty());
    }

    @Test void tinyPricePreviewUsesTheSameSellableQuantityCapAsExecution() {
        start();
        when(prices.getQuote("bitcoin", "EUR")).thenReturn(new CryptoPriceService.Quote(
                new BigDecimal("0.000000000000000000000000000001"), BigDecimal.ZERO, Instant.now().minusSeconds(1), true));
        assertEquals(0, TradingEngine.MAXIMUM_BUDGET_QUANTITY.compareTo(preview().quantity()));
    }

    @Test void onlyOneVaultCallPerPlayerIsDispatchedAtATime() {
        start();
        service.submit(player, preview()).join();
        service.submit(player, preview()).join();
        service.tick();
        flushWorker();
        var transfers = service.transfers(owner).join();
        assertEquals(1, transfers.stream().filter(t -> t.state() == TradingEngine.TransferState.CALLING).count());
        assertEquals(1, transfers.stream().filter(t -> t.state() == TradingEngine.TransferState.PREPARED).count());
        verify(provider, never()).transfer(any(), anyBoolean(), any());
        drainMain();
        service.tick();
        flushWorker();
        drainMain();
        verify(provider, times(2)).transfer(eq(owner), eq(true), eq(new BigDecimal("100.00")));
        assertTrue(service.orders(owner).join().stream().allMatch(o -> o.status() == TradingEngine.OrderStatus.OPEN));
    }

    @Test void providerReplacementBeforeQueuedInvocationMakesNoExternalCall() {
        start();
        service.submit(player, preview()).join();
        service.tick();
        flushWorker();
        VaultBridge replacement = mock(VaultBridge.class);
        when(replacement.identity()).thenReturn("replacement-provider");
        when(replacement.digits()).thenReturn(2);
        discovery.when(VaultBridge::discover).thenReturn(replacement);
        service.refreshProvider();
        flushWorker();
        drainMain();
        assertFalse(service.available());
        verify(provider, never()).transfer(any(), anyBoolean(), any());
        verify(replacement, never()).transfer(any(), anyBoolean(), any());
        assertEquals(TradingEngine.OrderStatus.REJECTED, service.orders(owner).join().getFirst().status());
        assertFalse(service.view(owner).join().frozen());
    }

    @Test void manyPreparedTransfersOfAFrozenOwnerCannotStarveHealthyPlayers() {
        UUID frozen = UUID.randomUUID();
        Instant earlier = Instant.now().minusSeconds(60);
        try (var engine = new TradingEngine(directory.resolve("economy.db"), TradingSettings.defaults(), earlier)) {
            engine.bindProvider("test-provider");
            for (int i = 0; i < 21; i++) engine.depositReserve(frozen, BigDecimal.ONE, earlier);
            engine.markCalling(engine.preparedTransfers().getFirst().id(), earlier);
        }
        start();
        service.submit(player, preview()).join();
        service.tick();
        flushWorker();
        assertTrue(service.view(frozen).join().frozen());
        assertEquals(TradingEngine.TransferState.CALLING, service.transfers(owner).join().getFirst().state());
        drainMain();
        assertEquals(TradingEngine.OrderStatus.OPEN, service.orders(owner).join().getFirst().status());
        verify(provider, times(1)).transfer(eq(owner), eq(true), any());
        verify(provider, never()).transfer(eq(frozen), anyBoolean(), any());
    }

    @Test void changedProviderPausesTradingWhileDepotRemainsReadable() {
        try (var engine = new TradingEngine(directory.resolve("economy.db"), TradingSettings.defaults(), Instant.now())) {
            engine.bindProvider("old-provider");
            engine.grantCoins(owner, "BTC", "bitcoin", BigDecimal.ONE, "Test inventory", Instant.now());
        }
        start();
        assertFalse(service.available());
        assertEquals(0, service.view(owner).join().coins().get("bitcoin").available().compareTo(BigDecimal.ONE));
        assertTrue(service.health().contains("provider changed"));
    }

    @Test void missingProviderPreservesPreviousPrecisionAndHoldings() {
        var base = TradingSettings.defaults();
        var settings = new TradingSettings(base.mode(), base.referenceCurrency(), base.unitsPerReference(),
                base.buyFeeRate(), base.sellFeeRate(), base.minimumOrderValue(), base.maximumOrderValue(),
                3, base.orderLifetime(), base.maximumQuoteAge(), base.maximumOpenOrdersPerPlayer());
        try (var engine = new TradingEngine(directory.resolve("economy.db"), settings, Instant.now())) {
            engine.bindProvider("test-provider");
            engine.grantCoins(owner, "BTC", "bitcoin", BigDecimal.ONE, "Test inventory", Instant.now());
        }
        discovery.when(VaultBridge::discover).thenReturn(null);
        start();
        assertFalse(service.available());
        assertEquals(3, service.settings().moneyScale());
        assertEquals(1, service.view(owner).join().coins().size());
        assertDoesNotThrow(() -> service.validateReload(plugin.getConfig()));
    }

    @Test void disabledCoinsOnStartupReleasePersistedOrdersButKeepInventory() {
        String orderId;
        try (var engine = new TradingEngine(directory.resolve("economy.db"), TradingSettings.defaults(), Instant.now())) {
            engine.bindProvider("test-provider");
            engine.grantCoins(owner, "BTC", "bitcoin", BigDecimal.ONE, "Test inventory", Instant.now());
            orderId = engine.placeSell(owner, "BTC", "bitcoin", BigDecimal.ONE,
                    TradingEngine.PriceGuard.unbounded(), new TradingEngine.MarketQuote("bitcoin", BigDecimal.TEN,
                            Instant.now().minusSeconds(1), true), Instant.now()).id();
        }
        plugin.getConfig().set("trading.disabled-coins", List.of("BTC"));
        start();
        assertEquals(TradingEngine.OrderStatus.CANCELLED, service.orders(owner).join().getFirst().status());
        assertEquals(orderId, service.orders(owner).join().getFirst().id());
        assertEquals(0, service.view(owner).join().coins().get("bitcoin").available().compareTo(BigDecimal.ONE));
        assertEquals(0, service.view(owner).join().coins().get("bitcoin").reserved().signum());
    }

    @Test void removedCoinOnReloadCancelsOrdersAndPreservesHoldings() { assertUnavailableCoinReleasesOrder(null); }

    @Test void remappedCoinOnReloadCancelsOrdersAndPreservesOriginalId() { assertUnavailableCoinReleasesOrder("new-bitcoin-id"); }

    private void assertUnavailableCoinReleasesOrder(String replacement) {
        start();
        service.grant(owner, new QuotePair("BTC", "bitcoin", "EUR"), BigDecimal.ONE, "Test inventory").join();
        service.submit(player, service.preview(player, "BTC", false, BigDecimal.ONE, true)).join();
        plugin.getConfig().set("coins.BTC", replacement);
        service.reload();
        flushWorker();
        assertEquals(TradingEngine.OrderStatus.CANCELLED, service.orders(owner).join().getFirst().status());
        var coins = service.view(owner).join().coins();
        assertEquals(1, coins.size());
        assertEquals(0, coins.get("bitcoin").available().compareTo(BigDecimal.ONE));
        assertEquals(0, coins.get("bitcoin").reserved().signum());
    }
}

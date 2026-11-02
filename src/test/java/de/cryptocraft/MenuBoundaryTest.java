package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MenuBoundaryTest {
    private CryptoCraftPlugin plugin;
    private Player player;
    private CryptoMenu menu;
    private YamlConfiguration config;
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<ItemStack> items;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final AtomicReference<Inventory> current = new AtomicReference<>();

    @BeforeEach
    void setup() {
        plugin = mock(CryptoCraftPlugin.class);
        config = new YamlConfiguration();
        config.set("coins.BTC", "bitcoin");
        config.set("coins.ETH", "ethereum");
        when(plugin.getConfig()).thenReturn(config);
        var messages = mock(CryptoMessages.class);
        when(messages.get(anyString())).thenAnswer(call -> call.getArgument(0));
        when(plugin.messages()).thenReturn(messages);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission("cryptocraft.use")).thenReturn(true);
        when(player.hasPermission("cryptocraft.place")).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        var view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenAnswer(call -> current.get());
        current.set(mock(Inventory.class));
        when(player.openInventory(any(Inventory.class))).thenAnswer(call -> {
            current.set(call.getArgument(0));
            return view;
        });
        doAnswer(call -> { current.set(mock(Inventory.class)); return null; }).when(player).closeInventory();
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), anyString())).thenAnswer(call -> {
            Inventory inventory = mock(Inventory.class);
            when(inventory.getHolder()).thenReturn(call.getArgument(0));
            when(inventory.getSize()).thenReturn(54);
            return inventory;
        });
        var scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        items = mockConstruction(ItemStack.class, (item, context) -> when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)));
        menu = new CryptoMenu(plugin, new CryptoCommand(plugin));
    }

    @AfterEach
    void close() {
        items.close();
        bukkit.close();
    }

    private InventoryClickEvent click(int slot) {
        var event = mock(InventoryClickEvent.class);
        InventoryView view = player.getOpenInventory();
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(slot);
        menu.click(event);
        verify(event).setCancelled(true);
        return event;
    }

    @Test
    void rapidConfirmationClicksExecuteOnlyOnceAndFailedActionCannotBeReplayed() {
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger returned = new AtomicInteger();
        menu.confirm(player, "Confirm", List.of(), () -> {
            accepted.incrementAndGet();
            throw new IllegalArgumentException("failed after action started");
        }, returned::incrementAndGet);
        Inventory confirmation = current.get();
        click(20);
        click(20);
        assertEquals(1, tasks.size());
        tasks.remove().run();
        assertEquals(1, accepted.get());
        assertEquals(1, returned.get());
        assertNotSame(confirmation, current.get());
        current.set(confirmation); // Even a stale client click must never repeat the action.
        click(20);
        assertTrue(tasks.isEmpty());
        assertEquals(1, accepted.get());
    }

    @Test
    void permissionRevokedWhileInventoryIsOpenPreventsConfirmation() {
        AtomicInteger accepted = new AtomicInteger();
        menu.confirm(player, "Confirm", List.of(), accepted::incrementAndGet, () -> {});
        click(20);
        when(player.hasPermission("cryptocraft.use")).thenReturn(false);
        tasks.remove().run();
        assertEquals(0, accepted.get());
    }

    @Test
    void displayAndTradingCoinSelectionsHaveIndependentFlags() {
        config.set("trading.display-disabled-coins", List.of("BTC"));
        config.set("trading.disabled-coins", List.of("ETH"));
        var command = new CryptoCommand(plugin);
        assertEquals(List.of("ETH"), command.onTabComplete(player, null, "crypto", new String[] {"place", ""}));
        assertTrue(new TradingCommands(plugin).complete(player, new String[] {"trade", "B"}).contains("BTC"));
        assertFalse(new TradingCommands(plugin).complete(player, new String[] {"trade", "E"}).contains("ETH"));
        assertFalse(menu.displayEnabled("btc"));
        assertTrue(menu.displayEnabled("ETH"));
    }
}

package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.UUID;
import java.util.List;
import java.util.logging.Logger;

class BoardMigrationTest {
    @TempDir Path directory;

    @Test
    void legacyBoardGetsDefaultsAndEditedSettingsSurviveSave() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        Files.writeString(
                directory.resolve("boards.yml"),
                "boards:\n  board:\n    owner-id: "
                        + owner
                        + "\n    owner-name: Test\n    world-id: "
                        + world
                        + "\n"
                        + "    world-name: world\n"
                        + "    x: 1\n"
                        + "    y: 64\n"
                        + "    z: 2\n"
                        + "    symbol: BTC\n"
                        + "    coin-id: bitcoin\n"
                        + "    currency: EUR\n"
                        + "    display-facing: SOUTH\n");
        var plugin = mock(CryptoCraftPlugin.class);
        when(plugin.getName()).thenReturn("CryptoCraft");
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig())
                .thenReturn(
                        YamlConfiguration.loadConfiguration(
                                new java.io.File("src/main/resources/config.yml")));
        try (var storage = new AtomicYamlStore(Logger.getAnonymousLogger());
                var bukkit = mockStatic(Bukkit.class)) {
            when(plugin.storage()).thenReturn(storage);
            var boards = new CryptoBoardService(plugin);
            boards.load();
            var board = boards.getBoard("board");
            assertNotNull(board);
            assertEquals(1.35, board.settings().height());
            assertEquals(24, board.settings().hours());
            assertEquals(1, boards.getBoardsOwnedBy(owner).size());
            assertFalse(board.market());
            assertFalse(board.terminal());
            assertEquals(List.of(new QuotePair("BTC", "bitcoin", "EUR")), board.quotePairs());
            boards.replace(
                    board.edited(
                            board.symbol(),
                            board.coinId(),
                            board.currency(),
                            board.displayFacing(),
                            board.settings().with("hours", "168")));
            boards.close();
        }
        var saved = YamlConfiguration.loadConfiguration(directory.resolve("boards.yml").toFile());
        assertEquals(168, saved.getInt("boards.board.hours"));
        assertEquals(owner.toString(), saved.getString("boards.board.owner-id"));
    }

    @Test
    void marketOrderCurrencyAndTerminalFlagSurviveRestart() throws Exception {
        var plugin = mock(CryptoCraftPlugin.class);
        when(plugin.getName()).thenReturn("CryptoCraft");
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        var world = mock(org.bukkit.World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("world");
        var block = mock(org.bukkit.block.Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getY()).thenReturn(64);
        String id;
        List<QuotePair> pairs = List.of(new QuotePair("SOL", "solana", "EUR"),
                new QuotePair("BTC", "bitcoin", "EUR"), new QuotePair("ETH", "ethereum", "EUR"));
        try (var storage = new AtomicYamlStore(Logger.getAnonymousLogger());
                var bukkit = mockStatic(Bukkit.class)) {
            when(plugin.storage()).thenReturn(storage);
            var boards = new CryptoBoardService(plugin);
            var board = boards.createMarket(UUID.randomUUID(), "Owner", block, pairs,
                    org.bukkit.block.BlockFace.WEST);
            id = board.id();
            assertEquals(2, board.settings().size());
            boards.replace(board.withTerminal(true));
            boards.close();
        }
        try (var storage = new AtomicYamlStore(Logger.getAnonymousLogger());
                var bukkit = mockStatic(Bukkit.class)) {
            when(plugin.storage()).thenReturn(storage);
            var boards = new CryptoBoardService(plugin);
            boards.load();
            var restored = boards.getBoard(id);
            assertTrue(restored.market());
            assertTrue(restored.terminal());
            assertEquals(pairs, restored.quotePairs());
            assertEquals("SOL", restored.symbol());
            assertEquals(2, restored.settings().size());
            assertEquals(org.bukkit.block.BlockFace.WEST, restored.displayFacing());
            assertEquals(List.of("USD", "USD", "USD"), restored.edited(restored.symbol(), restored.coinId(),
                    "USD", restored.displayFacing(), restored.settings()).quotePairs().stream().map(QuotePair::currency).toList());
            boards.close();
        }
    }
}

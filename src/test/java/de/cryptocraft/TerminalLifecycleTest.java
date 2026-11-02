package de.cryptocraft;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TerminalLifecycleTest {
    @TempDir Path directory;
    final UUID worldId = UUID.randomUUID();

    CryptoCraftPlugin plugin() throws Exception {
        Files.writeString(directory.resolve("boards.yml"), """
                boards:
                  terminal:
                    owner-id: %s
                    owner-name: Owner
                    world-id: %s
                    world-name: world
                    x: 15
                    y: 64
                    z: 15
                    symbol: BTC
                    coin-id: bitcoin
                    currency: EUR
                    display-facing: SOUTH
                    height: 2.0
                    size: 3
                    terminal: true
                """.formatted(UUID.randomUUID(), worldId));
        var plugin = mock(CryptoCraftPlugin.class);
        when(plugin.getName()).thenReturn("CryptoCraft");
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        return plugin;
    }

    Chunk chunk(UUID world, Entity... entities) {
        var bukkitWorld = mock(World.class);
        when(bukkitWorld.getUID()).thenReturn(world);
        var chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(bukkitWorld);
        when(chunk.getEntities()).thenReturn(entities);
        return chunk;
    }

    BlockDisplay component(String board, String part, UUID world) {
        var entity = mock(BlockDisplay.class);
        var data = mock(PersistentDataContainer.class);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> switch (((NamespacedKey) call.getArgument(0)).getKey()) {
            case "board_id" -> board;
            case "component" -> part;
            default -> null;
        });
        var entityWorld = mock(World.class);
        when(entityWorld.getUID()).thenReturn(world);
        // The indicator for this south-facing 3x3 board is in chunk (1, 0).
        when(entity.getLocation()).thenReturn(new Location(entityWorld, 16.87, 65.935, 15.0688125));
        return entity;
    }

    @Test
    void removesOrphanInvalidAndForeignWorldComponentsWithoutLoadingChunks() throws Exception {
        var plugin = plugin();
        try (var storage = new AtomicYamlStore(Logger.getAnonymousLogger()); var bukkit = mockStatic(Bukkit.class)) {
            when(plugin.storage()).thenReturn(storage);
            var boards = new CryptoBoardService(plugin);
            boards.load();
            var orphan = component("removed-board", "indicator", worldId);
            var unknownPart = component("terminal", "not-a-part", worldId);
            var foreign = component("terminal", "indicator", UUID.randomUUID());
            var normalChunk = chunk(worldId, orphan, unknownPart);
            var foreignChunk = chunk(UUID.randomUUID(), foreign);
            boards.ensureBoardsInChunk(normalChunk, null);
            boards.ensureBoardsInChunk(foreignChunk, null);
            verify(orphan).remove();
            verify(unknownPart).remove();
            verify(foreign).remove();
            verify(normalChunk.getWorld(), never()).getChunkAt(anyInt(), anyInt());
            boards.close();
        }
    }

    @Test
    void duplicateComponentsAndComponentsOfRemovedTerminalAreCleanedOnChunkLoad() throws Exception {
        var plugin = plugin();
        try (var storage = new AtomicYamlStore(Logger.getAnonymousLogger()); var bukkit = mockStatic(Bukkit.class)) {
            when(plugin.storage()).thenReturn(storage);
            var boards = new CryptoBoardService(plugin);
            boards.load();
            var original = component("terminal", "indicator", worldId);
            var duplicate = component("terminal", "indicator", worldId);
            bukkit.when(() -> Bukkit.getEntity(original.getUniqueId())).thenReturn(original);
            var chunk = chunk(worldId, original, duplicate);
            when(chunk.getX()).thenReturn(1);
            boards.ensureBoardsInChunk(chunk, null);
            verify(original, never()).remove();
            verify(duplicate).remove();
            boards.unload(chunk);
            boards.remove(boards.getBoard("terminal"));
            // Deleting while a neighbor chunk is unloaded must be completed when that chunk loads.
            boards.ensureBoardsInChunk(chunk, null);
            verify(original).remove();
            boards.close();
        }
    }

    @Test
    void accessRechecksRangeWorldAnchorAndAdministrativeRevocation() throws Exception {
        var plugin = plugin();
        try (var storage = new AtomicYamlStore(Logger.getAnonymousLogger()); var bukkit = mockStatic(Bukkit.class)) {
            when(plugin.storage()).thenReturn(storage);
            var boards = new CryptoBoardService(plugin);
            boards.load();
            var board = boards.getBoard("terminal");
            var player = mock(Player.class);
            var world = mock(World.class);
            var anchor = mock(Block.class);
            // Material.isAir delegates to live registries on modern Bukkit; isolate that boundary.
            var solid = mock(Material.class);
            var air = mock(Material.class);
            when(air.isAir()).thenReturn(true);
            when(anchor.getType()).thenReturn(solid);
            when(world.getUID()).thenReturn(worldId);
            when(world.isChunkLoaded(0, 0)).thenReturn(true);
            when(world.getBlockAt(15, 64, 15)).thenReturn(anchor);
            when(player.getWorld()).thenReturn(world);
            when(player.getLocation()).thenReturn(new Location(world, 15.5, 66.0, 21.5));
            assertTrue(boards.terminalInRange(player, board, 6));
            when(player.getLocation()).thenReturn(new Location(world, 15.5, 66.0, 21.5001));
            assertFalse(boards.terminalInRange(player, board, 6));
            when(player.getLocation()).thenReturn(new Location(world, 15.5, 66.0, 15.5));
            when(world.getUID()).thenReturn(UUID.randomUUID());
            assertFalse(boards.terminalInRange(player, board, 6));
            when(world.getUID()).thenReturn(worldId);
            when(anchor.getType()).thenReturn(air);
            assertFalse(boards.terminalInRange(player, board, 6));
            when(anchor.getType()).thenReturn(solid);
            boards.replace(board.withTerminal(false));
            assertFalse(boards.terminalInRange(player, board, 6), "An old preview must not keep terminal access");
            boards.close();
        }
    }

    @Test
    void computerStyleReloadRejectsTerminalsTooLowForKeyboard() throws Exception {
        var plugin = plugin();
        plugin.getConfig().set("trading.terminal-style", "labeled");
        try (var storage = new AtomicYamlStore(Logger.getAnonymousLogger()); var bukkit = mockStatic(Bukkit.class)) {
            when(plugin.storage()).thenReturn(storage);
            var boards = new CryptoBoardService(plugin);
            boards.load();
            var board = boards.getBoard("terminal");
            boards.replace(board.edited(board.symbol(), board.coinId(), board.currency(), board.displayFacing(),
                    board.settings().with("height", "1.35")));
            assertDoesNotThrow(() -> boards.validateConfiguration(plugin.getConfig()));
            var candidate = new YamlConfiguration();
            candidate.set("trading.terminal-style", "computer");
            assertTrue(assertThrows(IllegalArgumentException.class, () -> boards.validateConfiguration(candidate)).getMessage().contains("terminal"));
            boards.close();
        }
    }
}

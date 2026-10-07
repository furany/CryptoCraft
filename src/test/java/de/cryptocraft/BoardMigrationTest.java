package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.UUID;
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
}

package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.logging.Logger;

class AtomicYamlStoreTest {
    @TempDir Path directory;

    @Test
    void closeFlushesLatestSnapshotAndLeavesNoTemporaryFiles() throws Exception {
        Path file = directory.resolve("boards.yml");
        try (var store = new AtomicYamlStore(Logger.getAnonymousLogger())) {
            store.save(
                    file.toFile(),
                    () -> {
                        var yaml = new YamlConfiguration();
                        yaml.set("revision", 1);
                        return yaml;
                    });
            store.save(
                    file.toFile(),
                    () -> {
                        var yaml = new YamlConfiguration();
                        yaml.set("revision", 2);
                        return yaml;
                    });
        }
        assertEquals(2, YamlConfiguration.loadConfiguration(file.toFile()).getInt("revision"));
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")));
        }
    }

    @Test
    void replacementRetainsPreviousVersionAsBackup() throws Exception {
        Path file = directory.resolve("history.yml");
        AtomicYamlStore.write(file, "value: 1\n");
        AtomicYamlStore.write(file, "value: 2\n");
        assertEquals("value: 1\n", Files.readString(Path.of(file + ".bak")));
        assertEquals("value: 2\n", Files.readString(file));
    }

    @Test
    void corruptFileIsRecoveredWithoutDiscardingDamagedInput() throws Exception {
        Path file = directory.resolve("players.yml");
        Files.writeString(file, "broken: [\n");
        Files.writeString(Path.of(file + ".bak"), "cash: 123\n");
        try (var store = new AtomicYamlStore(Logger.getAnonymousLogger())) {
            assertEquals(123, store.load(file.toFile()).getInt("cash"));
        }
        assertEquals("broken: [\n", Files.readString(Path.of(file + ".damaged")));
    }

    @Test
    void corruptDataWithoutBackupFailsWithoutOverwriting() throws Exception {
        Path file = directory.resolve("boards.yml");
        Files.writeString(file, "broken: [\n");
        try (var store = new AtomicYamlStore(Logger.getAnonymousLogger())) {
            assertThrows(IllegalStateException.class, () -> store.load(file.toFile()));
        }
        assertEquals("broken: [\n", Files.readString(file));
    }

    @Test
    void missingPrimaryIsRestoredFromBackup() throws Exception {
        Path file = directory.resolve("boards.yml");
        Files.writeString(Path.of(file + ".bak"), "revision: 7\n");
        try (var store = new AtomicYamlStore(Logger.getAnonymousLogger())) {
            assertEquals(7, store.load(file.toFile()).getInt("revision"));
        }
        assertEquals("revision: 7\n", Files.readString(file));
    }
}

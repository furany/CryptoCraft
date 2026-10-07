package de.cryptocraft;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** One ordered writer, coalesced immutable snapshots, and recoverable atomic replacement. */
public final class AtomicYamlStore implements AutoCloseable {
    private final Logger logger;
    private final Map<Path, Supplier<YamlConfiguration>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService writer =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread thread = new Thread(r, "CryptoCraft-storage");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final ScheduledFuture<?> flush;
    private final Map<Path, String> errors = new ConcurrentHashMap<>();
    private boolean closed;

    public AtomicYamlStore(Logger logger) {
        this.logger = logger;
        flush = writer.scheduleWithFixedDelay(this::drain, 2, 2, TimeUnit.SECONDS);
    }

    public synchronized void save(File file, Supplier<YamlConfiguration> snapshot) {
        if (closed) throw new IllegalStateException("Storage is closed");
        pending.put(file.toPath(), snapshot);
    }

    public String lastError() {
        return errors.isEmpty() ? null : String.join("; ", errors.values());
    }

    public YamlConfiguration load(File file) {
        if (!file.exists() && !new File(file + ".bak").exists()) return new YamlConfiguration();
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file);
            return yaml;
        } catch (Exception error) {
            File backup = new File(file + ".bak");
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(backup);
                logger.warning(
                        "Recovered " + file.getName() + " from backup; preserving damaged file.");
                if (file.exists())
                    Files.copy(
                            file.toPath(),
                            Path.of(file + ".damaged"),
                            StandardCopyOption.REPLACE_EXISTING);
                Files.copy(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return yaml;
            } catch (Exception backupError) {
                throw new IllegalStateException(
                        "Cannot load " + file.getName() + " or its backup", error);
            }
        }
    }

    private void drain() {
        for (Path file : pending.keySet()) {
            Supplier<YamlConfiguration> snapshot = pending.remove(file);
            if (snapshot == null) continue;
            try {
                write(file, snapshot.get().saveToString());
                errors.remove(file);
            } catch (Exception error) {
                String failure = file.getFileName() + ": " + error.getClass().getSimpleName();
                if (!failure.equals(errors.put(file, failure)))
                    logger.log(Level.SEVERE, "Could not save " + file.getFileName(), error);
                pending.putIfAbsent(file, snapshot);
            }
        }
    }

    static void write(Path file, String text) throws java.io.IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary =
                Files.createTempFile(
                        file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try (var channel =
                    java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.exists(file))
                Files.copy(file, Path.of(file + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        flush.cancel(false);
        writer.execute(this::drain);
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS))
                logger.severe("Storage flush timed out.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        if (!pending.isEmpty())
            logger.severe("Some CryptoCraft data could not be saved; see storage errors above.");
    }
}

package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.regex.Pattern;

class ResourceContractTest {
    @TempDir Path directory;

    @Test
    void allCommandAndMenuMessagesHaveBothTranslations() throws Exception {
        var yaml =
                YamlConfiguration.loadConfiguration(
                        new java.io.File("src/main/resources/messages.yml"));
        var en = yaml.getConfigurationSection("languages.en");
        var de = yaml.getConfigurationSection("languages.de");
        assertNotNull(en);
        assertNotNull(de);
        assertEquals(en.getKeys(false), de.getKeys(false));
        Pattern keys =
                Pattern.compile(
                        "\"((?:chart|menu|permission|help|price|portfolio|trade|alarm|watch|compare|state|region|pair|feature|input|edit|playerOnly)[A-Z][a-zA-Z]*)\"");
        try (var files = Files.list(Path.of("src/main/java/de/cryptocraft"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var matcher = keys.matcher(Files.readString(file));
                while (matcher.find())
                    assertTrue(
                            en.isString(matcher.group(1)),
                            file + " missing message " + matcher.group(1));
            }
        }
    }

    @Test
    void existingCustomMessagesSurviveAndNewKeysUseBundledDefaults() throws Exception {
        Files.writeString(
                directory.resolve("messages.yml"),
                "language: de\nlanguages:\n  de:\n    editSuccess: Mein eigener Text\n");
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getResource("messages.yml"))
                .thenAnswer(
                        call -> Files.newInputStream(Path.of("src/main/resources/messages.yml")));
        var messages = new CryptoMessages(plugin);
        assertEquals("Mein eigener Text", messages.get("editSuccess"));
        assertEquals("Watchlist", messages.get("menuWatchlist"));
    }
}

package de.cryptocraft;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;
import java.util.Map;

public final class CryptoMessages {
    private final File file;
    private YamlConfiguration messages;
    private final JavaPlugin plugin;
    private long revision;

    public CryptoMessages(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    public void reload() {
        try {
            YamlConfiguration candidate = new YamlConfiguration();
            candidate.load(file);
            try (var stream = plugin.getResource("messages.yml")) {
                if (stream != null)
                    candidate.setDefaults(
                            YamlConfiguration.loadConfiguration(
                                    new java.io.InputStreamReader(
                                            stream, java.nio.charset.StandardCharsets.UTF_8)));
            }
            messages = candidate;
            revision++;
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "Invalid messages.yml: " + error.getMessage(), error);
        }
    }

    public String get(String key) {
        return get(key, Map.of());
    }

    public String get(String key, Map<String, String> values) {
        String path = "languages." + getLanguage() + "." + key;
        String text = messages.getString(path);
        if (text == null) text = messages.getString("languages.en." + key);
        if (text == null) text = key;
        for (Map.Entry<String, String> value : values.entrySet()) {
            text = text.replace("%" + value.getKey() + "%", value.getValue());
        }
        return text;
    }

    public long revision() {
        return revision;
    }

    public String getLanguage() {
        String configuredLanguage = messages.getString("language", "en");
        return configuredLanguage == null || configuredLanguage.isBlank()
                ? "en"
                : configuredLanguage.trim().toLowerCase(Locale.ROOT);
    }
}

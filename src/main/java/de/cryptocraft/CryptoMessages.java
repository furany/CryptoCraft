package de.cryptocraft;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;
import java.util.Map;

public final class CryptoMessages {
    private final File file;
    private YamlConfiguration messages;

    public CryptoMessages(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    public void reload() {
        messages = YamlConfiguration.loadConfiguration(file);
    }

    public String get(String key) {
        return get(key, Map.of());
    }

    public String get(String key, Map<String, String> values) {
        String configuredLanguage = messages.getString("language", "en");
        String language = configuredLanguage == null || configuredLanguage.isBlank()
                ? "en"
                : configuredLanguage.trim().toLowerCase(Locale.ROOT);
        String path = "languages." + language + "." + key;
        String text = messages.getString(path, messages.getString("languages.en." + key, key));
        for (Map.Entry<String, String> value : values.entrySet()) {
            text = text.replace("%" + value.getKey() + "%", value.getValue());
        }
        return text;
    }
}

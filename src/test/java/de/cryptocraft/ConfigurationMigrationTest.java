package de.cryptocraft;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigurationMigrationTest {
    @TempDir Path directory;

    private YamlConfiguration legacyUsd() {
        var config = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        config.set("trading", null);
        config.set("coins", null);
        config.set("coins.BTC", "bitcoin");
        config.set("currencies", List.of("USD"));
        config.set("default-currency", "USD");
        return config;
    }

    private TradingSettings usdSettings() {
        var base = TradingSettings.defaults();
        return new TradingSettings(base.mode(), "USD", base.unitsPerReference(), base.buyFeeRate(),
                base.sellFeeRate(), base.minimumOrderValue(), base.maximumOrderValue(), base.moneyScale(),
                base.orderLifetime(), base.maximumQuoteAge(), base.maximumOpenOrdersPerPlayer());
    }

    private void applyDefaults(YamlConfiguration config, TradingSettings active) throws Exception {
        var plugin = mock(CryptoCraftPlugin.class);
        when(plugin.getResource("config.yml")).thenAnswer(call ->
                Files.newInputStream(Path.of("src/main/resources/config.yml")));
        if (active != null) {
            var service = mock(TradingService.class);
            when(service.settings()).thenReturn(active);
            var field = CryptoCraftPlugin.class.getDeclaredField("trading");
            field.setAccessible(true);
            field.set(plugin, service);
        }
        var method = CryptoCraftPlugin.class.getDeclaredMethod("applyDefaults", YamlConfiguration.class);
        method.setAccessible(true);
        method.invoke(plugin, config);
    }

    @Test void usdOnlyLegacyConfigAdoptsAndPersistsItsCurrencyWithoutAddingCoins() throws Exception {
        var config = legacyUsd();
        String original = config.saveToString();
        applyDefaults(config, null);
        assertTrue(ConfigValidator.validate(config).isEmpty(), ConfigValidator.validate(config).toString());
        assertEquals("USD", TradingConfiguration.settings(config, -1).referenceCurrency());
        assertEquals(Set.of("BTC"), config.getConfigurationSection("coins").getKeys(false));
        Path file = directory.resolve("config.yml");
        Files.writeString(file, original);
        AtomicYamlStore.write(file, config.saveToString());
        var restored = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals("USD", restored.getString("trading.reference-currency"));
        assertEquals(original, Files.readString(Path.of(file + ".bak")));
        restored.set("default-currency", "EUR");
        restored.set("currencies", List.of("EUR", "USD"));
        applyDefaults(restored, null);
        assertEquals("USD", restored.getString("trading.reference-currency"));
    }

    @Test void freshInstallAndExplicitReferenceKeepEur() throws Exception {
        var config = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        config.set("default-currency", "USD");
        applyDefaults(config, null);
        assertEquals("EUR", TradingConfiguration.settings(config, -1).referenceCurrency());
        assertEquals(10, config.getConfigurationSection("coins").getKeys(false).size());
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void omittedReferenceDuringReloadRetainsActiveCurrencyInsteadOfDisplayDefault() throws Exception {
        var config = legacyUsd();
        config.set("default-currency", "EUR");
        config.set("currencies", List.of("EUR", "USD"));
        applyDefaults(config, usdSettings());
        assertEquals("USD", TradingConfiguration.settings(config, -1).referenceCurrency());
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void explicitCurrencyChangeStillNeedsMigrationAfterEconomicActivity() throws Exception {
        var config = legacyUsd();
        config.set("currencies", List.of("EUR", "USD"));
        config.set("trading.reference-currency", "EUR");
        applyDefaults(config, usdSettings());
        assertEquals("EUR", TradingConfiguration.settings(config, -1).referenceCurrency());
        try (var engine = new TradingEngine(directory.resolve("economy.db"), usdSettings(), Instant.now())) {
            engine.grantCoins(UUID.randomUUID(), "BTC", "bitcoin", BigDecimal.ONE, "Migration fixture", Instant.now());
            assertThrows(IllegalArgumentException.class,
                    () -> engine.checkSettings(TradingConfiguration.settings(config, -1)));
            assertEquals("USD", engine.settings().referenceCurrency());
        }
    }
}

package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

class ConfigValidatorTest {
    private YamlConfiguration config() {
        return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
    }

    @Test
    void shippedConfigurationIsValid() {
        assertTrue(
                ConfigValidator.validate(config()).isEmpty(),
                ConfigValidator.validate(config()).toString());
    }

    @Test
    void rejectsInvalidIntervalsUnsafeUrlsAndMissingCurrency() {
        var config = config();
        config.set("prices.refresh-interval-seconds", -1);
        config.set("api.url", "http://example.com");
        config.set("default-currency", "XXX");
        var errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("prices.refresh-interval-seconds")));
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("api.url")));
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("default-currency")));
    }

    @Test
    void proModeRequiresKeyAndRetryMaximumMustCoverInitial() {
        var config = config();
        config.set("api.auth-mode", "pro");
        config.set("prices.retry.initial-delay-seconds", 5000);
        var errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("api.pro-key")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("must be >=")));
    }

    @Test
    void oldConfigsCanUseNewBundledDefaults() {
        var legacy = config();
        legacy.set("players", null);
        legacy.set("portfolio", null);
        legacy.set("integrations", null);
        legacy.set("prices.backfill", null);
        assertTrue(
                ConfigValidator.validate(legacy).isEmpty(),
                ConfigValidator.validate(legacy).toString());
    }
}

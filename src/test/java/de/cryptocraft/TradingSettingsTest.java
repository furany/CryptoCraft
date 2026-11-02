package de.cryptocraft;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class TradingSettingsTest {
    @Test void defaultsAndModeNamesMatchTheApprovedConfiguration() {
        var settings = TradingSettings.defaults();
        assertEquals(TradingSettings.Mode.BROKER_UNLIMITED, settings.mode());
        assertEquals(Duration.ofMinutes(5), settings.orderLifetime());
        assertEquals(Duration.ofMinutes(2), settings.maximumQuoteAge());
        assertEquals(TradingSettings.Mode.BROKER_RESERVE, TradingSettings.Mode.parse("reserve"));
        assertEquals(TradingSettings.Mode.P2P, TradingSettings.Mode.parse("p2p"));
        assertThrows(IllegalArgumentException.class, () -> TradingSettings.Mode.parse("automatic"));
    }

    @Test void rejectsFeesThatCouldProduceNegativeProceedsAndInvalidLimits() {
        var defaults = TradingSettings.defaults();
        assertThrows(IllegalArgumentException.class, () -> new TradingSettings(defaults.mode(), "EUR",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN,
                2, Duration.ofMinutes(5), Duration.ofMinutes(2), 20));
        assertThrows(IllegalArgumentException.class, () -> new TradingSettings(defaults.mode(), "EUR",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ONE,
                2, Duration.ofMinutes(5), Duration.ofMinutes(2), 20));
        assertThrows(IllegalArgumentException.class, () -> new TradingEngine.PriceGuard(BigDecimal.TEN, BigDecimal.ONE));
    }
}

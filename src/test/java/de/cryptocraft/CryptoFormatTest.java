package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

class CryptoFormatTest {
    @Test
    void tinyPricesNeverBecomeZeroAndLocaleIsRespected() {
        assertEquals("1.234E-10", CryptoFormat.price(new BigDecimal("0.0000000001234"), Locale.US));
        assertEquals("1.234,56", CryptoFormat.price(new BigDecimal("1234.56"), Locale.GERMANY));
        assertEquals("1,234.56", CryptoFormat.price(new BigDecimal("1234.56"), Locale.US));
    }
}

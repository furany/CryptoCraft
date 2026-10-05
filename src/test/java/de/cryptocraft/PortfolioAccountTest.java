package de.cryptocraft;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

class PortfolioAccountTest {
    private final QuotePair btc = new QuotePair("BTC", "bitcoin", "EUR");

    private PortfolioAccount account() {
        return new PortfolioAccount(
                "EUR", new BigDecimal("1000"), new BigDecimal("1000"), BigDecimal.ZERO, Map.of());
    }

    private BigDecimal n(String text) {
        return new BigDecimal(text);
    }

    private void equal(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual));
    }

    @Test
    void buyAndPartialSellUseAverageCostAndRealizedProfit() {
        var bought =
                account().trade(btc, n("2"), n("100"), true).trade(btc, n("1"), n("250"), true);
        equal("550", bought.cash());
        equal("450", bought.positions().get(btc).cost());
        var sold = bought.trade(btc, n("1"), n("200"), false);
        equal("750", sold.cash());
        equal("50", sold.realizedProfit());
        equal("2", sold.positions().get(btc).quantity());
        equal("300", sold.positions().get(btc).cost());
        // Prior immutable snapshots remain unchanged for asynchronous persistence.
        equal("3", bought.positions().get(btc).quantity());
    }

    @Test
    void sellingWholePositionRemovesItAndBooksLoss() {
        var sold = account().trade(btc, n("1"), n("100"), true).trade(btc, n("1"), n("80"), false);
        equal("980", sold.cash());
        equal("-20", sold.realizedProfit());
        assertTrue(sold.positions().isEmpty());
    }

    @Test
    void refusesOverspendingShortSellingAndInvalidAmounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> account().trade(btc, n("11"), n("100"), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> account().trade(btc, n("1"), n("100"), false));
        for (String quantity : new String[] {"0", "-1", "1e-13", "1e1000"})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> account().trade(btc, n(quantity), n("100"), true));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        account()
                                .trade(
                                        new QuotePair("BTC", "bitcoin", "USD"),
                                        n("1"),
                                        n("100"),
                                        true));
    }

    @Test
    void tinyCoinValuesKeepPrecision() {
        var bought = account().trade(btc, n("1000000"), n("0.0000000123456789"), true);
        equal("999.9876543211", bought.cash());
        equal("0.0123456789", bought.positions().get(btc).cost());
    }
}

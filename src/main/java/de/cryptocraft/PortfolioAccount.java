package de.cryptocraft;

import java.math.*;
import java.util.*;

/** Pure paper-trading ledger. No connection to real wallets or server economy balances. */
public record PortfolioAccount(
        String currency,
        BigDecimal cash,
        BigDecimal startingCash,
        BigDecimal realizedProfit,
        Map<QuotePair, Position> positions) {
    private static final MathContext MATH = MathContext.DECIMAL128;

    public PortfolioAccount {
        positions = Map.copyOf(positions);
        if (cash.signum() < 0 || startingCash.signum() <= 0)
            throw new IllegalArgumentException("Invalid cash balance");
        if (positions.size() > 100
                || positions.entrySet().stream()
                        .anyMatch(
                                e ->
                                        !e.getKey().currency().equals(currency)
                                                || e.getValue().quantity().signum() <= 0
                                                || e.getValue().cost().signum() < 0))
            throw new IllegalArgumentException("Invalid portfolio positions");
    }

    public PortfolioAccount trade(
            QuotePair pair, BigDecimal quantity, BigDecimal price, boolean buy) {
        if (!pair.currency().equals(currency))
            throw new IllegalArgumentException("Portfolio currency is " + currency);
        if (quantity.signum() <= 0
                || quantity.precision() > 28
                || Math.abs((long) quantity.scale()) > 12
                || price.signum() <= 0)
            throw new IllegalArgumentException(
                    "Quantity must be positive, with at most 12 decimal places");
        BigDecimal total = quantity.multiply(price, MATH);
        Map<QuotePair, Position> updated = new HashMap<>(positions);
        Position old = updated.getOrDefault(pair, new Position(BigDecimal.ZERO, BigDecimal.ZERO));
        if (buy) {
            if (positions.keySet().stream()
                    .anyMatch(
                            held ->
                                    held.symbol().equals(pair.symbol())
                                            && !held.coinId().equals(pair.coinId())))
                throw new IllegalArgumentException(
                        "Close the existing position before changing its coin ID");
            if (cash.compareTo(total) < 0)
                throw new IllegalArgumentException("Insufficient virtual cash");
            updated.put(pair, new Position(old.quantity().add(quantity), old.cost().add(total)));
            return new PortfolioAccount(
                    currency, cash.subtract(total), startingCash, realizedProfit, updated);
        }
        if (old.quantity().compareTo(quantity) < 0)
            throw new IllegalArgumentException("Insufficient holdings");
        BigDecimal remaining = old.quantity().subtract(quantity);
        BigDecimal basis =
                remaining.signum() == 0
                        ? old.cost()
                        : old.cost().multiply(quantity, MATH).divide(old.quantity(), MATH);
        if (remaining.signum() == 0) updated.remove(pair);
        else updated.put(pair, new Position(remaining, old.cost().subtract(basis)));
        return new PortfolioAccount(
                currency,
                cash.add(total),
                startingCash,
                realizedProfit.add(total.subtract(basis)),
                updated);
    }

    public record Position(BigDecimal quantity, BigDecimal cost) {}
}

package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.UUID;

/** Optional linkage: no Vault classes are loaded when Vault is absent. Calls stay on server thread. */
final class VaultBridge {
    private final Object provider;
    private final Class<?> api;
    private final String name;
    private final String identity;
    private final int digits;

    private VaultBridge(Object provider, Class<?> api) throws ReflectiveOperationException {
        this.provider = provider;
        this.api = api;
        this.name = String.valueOf(invoke("getName"));
        this.digits = ((Number) invoke("fractionalDigits")).intValue();
        this.identity = provider.getClass().getName() + ":" + name + ":" + currency();
        if (digits > 12) throw new IllegalArgumentException("Unsupported economy precision: " + digits);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static VaultBridge discover() {
        var vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) return null;
        try {
            Class api = Class.forName("net.milkbowl.vault.economy.Economy", false,
                    vault.getClass().getClassLoader());
            var registration = Bukkit.getServicesManager().getRegistration(api);
            if (registration == null) return null;
            VaultBridge bridge = new VaultBridge(registration.getProvider(), api);
            return Boolean.TRUE.equals(bridge.invoke("isEnabled")) ? bridge : null;
        } catch (ReflectiveOperationException | LinkageError e) { return null; }
    }

    String name() { return name; }
    boolean sameProvider(VaultBridge other) { return other != null && provider == other.provider; }
    String identity() { return identity; }
    int digits() { return digits; }
    String currency() {
        try {
            String value = String.valueOf(invoke("currencyNamePlural"));
            return value.isBlank() ? name == null ? "Server money" : name : value;
        } catch (ReflectiveOperationException e) { return "Server money"; }
    }
    String format(BigDecimal amount) {
        try { return String.valueOf(api.getMethod("format", double.class).invoke(provider, amount.doubleValue())); }
        catch (ReflectiveOperationException e) { return amount.toPlainString() + " " + currency(); }
    }
    BigDecimal balance(UUID owner) {
        try {
            double value = ((Number) api.getMethod("getBalance", OfflinePlayer.class)
                    .invoke(provider, Bukkit.getOfflinePlayer(owner))).doubleValue();
            if (!Double.isFinite(value)) throw new IllegalStateException("Invalid economy balance");
            return BigDecimal.valueOf(value);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Economy balance unavailable", e); }
    }
    Result transfer(UUID owner, boolean withdraw, BigDecimal amount) {
        double value = amount.doubleValue();
        if (!Double.isFinite(value) || value <= 0 || BigDecimal.valueOf(value).compareTo(amount) != 0)
            return new Result(TradingEngine.TransferOutcome.REJECTED, "Invalid amount");
        try {
            Object response = api.getMethod(withdraw ? "withdrawPlayer" : "depositPlayer",
                    OfflinePlayer.class, double.class).invoke(provider, Bukkit.getOfflinePlayer(owner), value);
            if (response == null) return new Result(TradingEngine.TransferOutcome.UNCERTAIN, "Null provider response");
            boolean success = Boolean.TRUE.equals(response.getClass().getMethod("transactionSuccess").invoke(response));
            if (success) {
                double actual = ((Number) response.getClass().getField("amount").get(response)).doubleValue();
                if (!Double.isFinite(actual) || BigDecimal.valueOf(actual).compareTo(amount) != 0)
                    return new Result(TradingEngine.TransferOutcome.UNCERTAIN, "Provider returned an unexpected transferred amount");
                return new Result(TradingEngine.TransferOutcome.APPLIED, "Confirmed by " + name);
            }
            Object error = response.getClass().getField("errorMessage").get(response);
            return new Result(TradingEngine.TransferOutcome.REJECTED, String.valueOf(error));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new Result(TradingEngine.TransferOutcome.UNCERTAIN,
                    "Provider call outcome unknown: " + e.getClass().getSimpleName());
        }
    }
    private Object invoke(String method) throws ReflectiveOperationException {
        return api.getMethod(method).invoke(provider);
    }
    record Result(TradingEngine.TransferOutcome outcome, String detail) {}
}

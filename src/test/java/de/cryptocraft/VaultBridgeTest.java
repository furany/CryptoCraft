package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static de.cryptocraft.TradingEngine.TransferOutcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class VaultBridgeTest {
    private static final UUID OWNER = UUID.randomUUID();

    public static final class Response {
        public double amount;
        public String errorMessage = "Rejected by test provider";
        public boolean success;
        public boolean transactionSuccess() { return success; }
        Response(double amount, boolean success) { this.amount = amount; this.success = success; }
    }

    public static final class Economy {
        int calls;
        Response response;
        boolean failAfterCall;
        public String getName() { return "Fixture Economy"; }
        public int fractionalDigits() { return 2; }
        public String currencyNamePlural() { return "Coins"; }
        public Response withdrawPlayer(OfflinePlayer player, double amount) { return transfer(); }
        public Response depositPlayer(OfflinePlayer player, double amount) { return transfer(); }
        private Response transfer() {
            calls++;
            if (failAfterCall) throw new IllegalStateException("Connection lost after posting");
            return response;
        }
    }

    private VaultBridge bridge(Economy economy) throws Exception {
        var constructor = VaultBridge.class.getDeclaredConstructor(Object.class, Class.class);
        constructor.setAccessible(true);
        return constructor.newInstance(economy, Economy.class);
    }

    @Test void exactSuccessAndExplicitRejectionHaveKnownOutcomes() throws Exception {
        var economy = new Economy();
        var bridge = bridge(economy);
        try (var ignored = mockStatic(Bukkit.class)) {
            economy.response = new Response(12.34, true);
            assertEquals(APPLIED, bridge.transfer(OWNER, true, new BigDecimal("12.34")).outcome());
            economy.response = new Response(0, false);
            assertEquals(REJECTED, bridge.transfer(OWNER, false, new BigDecimal("12.34")).outcome());
            assertEquals(2, economy.calls);
        }
    }

    @Test void unrepresentableDoubleAmountIsRejectedBeforeCallingProvider() throws Exception {
        var economy = new Economy();
        var bridge = bridge(economy);
        assertEquals(REJECTED, bridge.transfer(OWNER, true, new BigDecimal("9007199254740993")).outcome());
        assertEquals(0, economy.calls);
    }

    @Test void changedAmountNullResponseAndExceptionNeedReconciliation() throws Exception {
        var economy = new Economy();
        var bridge = bridge(economy);
        try (var ignored = mockStatic(Bukkit.class)) {
            economy.response = new Response(12, true);
            assertEquals(UNCERTAIN, bridge.transfer(OWNER, true, new BigDecimal("12.34")).outcome());
            economy.response = null;
            assertEquals(UNCERTAIN, bridge.transfer(OWNER, true, new BigDecimal("12.34")).outcome());
            economy.failAfterCall = true;
            assertEquals(UNCERTAIN, bridge.transfer(OWNER, false, new BigDecimal("12.34")).outcome());
            assertEquals(3, economy.calls);
        }
    }
}

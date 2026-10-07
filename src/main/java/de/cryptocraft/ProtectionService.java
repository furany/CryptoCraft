package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class ProtectionService {
    private final CryptoCraftPlugin plugin;
    private boolean warned;

    public ProtectionService(CryptoCraftPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean canBuild(Player player, Block block) {
        if (!plugin.getConfig().getBoolean("integrations.worldguard", true)
                || !Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return true;
        try {
            return WorldGuardHook.canBuild(player, block);
        } catch (RuntimeException | LinkageError error) {
            if (!warned) {
                plugin.getLogger()
                        .warning(
                                "WorldGuard query failed; denying board changes: "
                                        + error.getClass().getSimpleName());
                warned = true;
            }
            return false;
        }
    }

    /** Loaded only when the optional WorldGuard dependency is present. */
    private static final class WorldGuardHook {
        static boolean canBuild(Player player, Block block) {
            var worldGuard = com.sk89q.worldguard.WorldGuard.getInstance();
            var localPlayer =
                    com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(player);
            var world = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(block.getWorld());
            if (worldGuard.getPlatform().getSessionManager().hasBypass(localPlayer, world))
                return true;
            return worldGuard
                    .getPlatform()
                    .getRegionContainer()
                    .createQuery()
                    .testState(
                            com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(block.getLocation()),
                            localPlayer,
                            com.sk89q.worldguard.protection.flags.Flags.BUILD);
        }
    }
}

package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class CryptoCraftPlugin extends JavaPlugin implements Listener {
    private CryptoBoardService boardService;
    private CryptoPriceService priceService;
    private BukkitTask refreshTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        boardService = new CryptoBoardService(this);
        boardService.load();
        priceService = new CryptoPriceService(this, boardService);

        PluginCommand command = getCommand("crypto");
        if (command == null) {
            getLogger().severe("The crypto command is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(new CryptoCommand(this));
        getServer().getPluginManager().registerEvents(this, this);

        boardService.ensureLoadedChunks(priceService);
        scheduleRefresh();
        String demoKey = getConfig().getString("api.demo-key");
        if (demoKey == null || demoKey.isBlank()) {
            getLogger().warning("Using CoinGecko's keyless API. A Demo API key may make regular polling more reliable.");
        }
        priceService.refresh();
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
    }

    public CryptoBoardService boards() {
        return boardService;
    }

    public CryptoPriceService prices() {
        return priceService;
    }

    public void reloadPluginConfiguration() {
        reloadConfig();
        scheduleRefresh();
        boardService.refreshLoadedDisplays(priceService);
        priceService.refresh();
    }

    private void scheduleRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        long seconds = Math.max(5L, getConfig().getLong("prices.refresh-interval-seconds", 300));
        long period = seconds * 20L;
        refreshTask = Bukkit.getScheduler().runTaskTimer(this, priceService::refresh, 20L, period);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        CryptoBoard board = boardService.findAt(event.getBlock());
        if (board == null) {
            return;
        }
        Block block = event.getBlock();
        Bukkit.getScheduler().runTask(this, () -> {
            if (block.getType().isAir()) {
                boardService.remove(board);
            }
        });
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        boardService.ensureBoardsInChunk(event.getChunk(), priceService);
    }
}

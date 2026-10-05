package de.cryptocraft;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

public final class CryptoCraftPlugin extends JavaPlugin implements Listener {
    private CryptoBoardService boardService;
    private CryptoPriceService priceService;
    private CryptoMessages messages;
    private AtomicYamlStore storage;
    private CryptoFeatures features;
    private ProtectionService protection;
    private BukkitTask refreshTask;
    private BukkitTask maintenanceTask;
    private CryptoMenu menu;
    private Runnable unregisterPlaceholders;
    private volatile Map<String, QuotePair> configuredPairs = Map.of();

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            YamlConfiguration configured = new YamlConfiguration();
            configured.load(new File(getDataFolder(), "config.yml"));
            configured.setDefaults(getConfig().getDefaults());
            validate(configured);
            getConfig().loadFromString(configured.saveToString());
            if (!new File(getDataFolder(), "messages.yml").exists())
                saveResource("messages.yml", false);
            messages = new CryptoMessages(this);
            storage = new AtomicYamlStore(getLogger());
            protection = new ProtectionService(this);
            boardService = new CryptoBoardService(this);
            boardService.load();
            features = new CryptoFeatures(this);
            priceService = new CryptoPriceService(this, boardService);
            updateConfiguredPairs();
            var command =
                    Objects.requireNonNull(
                            getCommand("crypto"), "crypto command missing from plugin.yml");
            CryptoCommand executor = new CryptoCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            menu = new CryptoMenu(this, executor);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(menu, this);
            boardService.ensureLoadedChunks(priceService);
            features.updateSignals();
            refreshTask = Bukkit.getScheduler().runTaskTimer(this, priceService::tick, 20, 20);
            maintenanceTask =
                    Bukkit.getScheduler()
                            .runTaskTimer(
                                    this,
                                    () -> {
                                        boardService.validateAnchors();
                                        features.updateSignals();
                                    },
                                    1200,
                                    1200);
            syncPlaceholders();
            priceService.refresh();
        } catch (Exception | LinkageError error) {
            getLogger()
                    .log(
                            Level.SEVERE,
                            "CryptoCraft could not start; fix configuration/data before"
                                    + " restarting.",
                            error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) refreshTask.cancel();
        if (maintenanceTask != null) maintenanceTask.cancel();
        if (unregisterPlaceholders != null) unregisterPlaceholders.run();
        if (menu != null) menu.closeAll();
        if (priceService != null) priceService.close();
        if (features != null) features.close();
        if (boardService != null) boardService.close();
        if (storage != null) storage.close();
    }

    public CryptoBoardService boards() {
        return boardService;
    }

    public CryptoPriceService prices() {
        return priceService;
    }

    public CryptoMessages messages() {
        return messages;
    }

    public AtomicYamlStore storage() {
        return storage;
    }

    public CryptoFeatures features() {
        return features;
    }

    public ProtectionService protection() {
        return protection;
    }

    public CryptoMenu menu() {
        return menu;
    }

    public boolean isAdmin(org.bukkit.command.CommandSender sender) {
        String permission = getConfig().getString("boards.admin-permission", "cryptocraft.admin");
        return permission != null && !permission.isBlank() && sender.hasPermission(permission);
    }

    public void reloadPluginConfiguration() {
        try {
            YamlConfiguration candidate = new YamlConfiguration();
            candidate.load(new File(getDataFolder(), "config.yml"));
            candidate.setDefaults(getConfig().getDefaults());
            validate(candidate);
            // Validate both files before changing the active state.
            YamlConfiguration messageCandidate = new YamlConfiguration();
            messageCandidate.load(new File(getDataFolder(), "messages.yml"));
            getConfig().loadFromString(candidate.saveToString());
            messages.reload();
            updateConfiguredPairs();
            priceService.resetRetryState();
            syncPlaceholders();
            menu.closeAll();
            boardService.refreshLoadedDisplays(priceService);
            features.updateSignals();
            priceService.refresh();
        } catch (Exception error) {
            throw new IllegalArgumentException("Reload rejected: " + error.getMessage(), error);
        }
    }

    private void validate(org.bukkit.configuration.file.FileConfiguration config) {
        List<String> errors = ConfigValidator.validate(config);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
    }

    private void syncPlaceholders() {
        if (unregisterPlaceholders != null) {
            unregisterPlaceholders.run();
            unregisterPlaceholders = null;
        }
        if (getConfig().getBoolean("integrations.placeholderapi", true)
                && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                unregisterPlaceholders = PlaceholderHook.register(this);
            } catch (RuntimeException | LinkageError error) {
                getLogger()
                        .warning(
                                "PlaceholderAPI integration unavailable: "
                                        + error.getClass().getSimpleName());
            }
        }
    }

    private void updateConfiguredPairs() {
        Map<String, QuotePair> pairs = new HashMap<>();
        var coins = getConfig().getConfigurationSection("coins");
        if (coins != null)
            for (String symbol : coins.getKeys(false))
                for (String currency : getConfig().getStringList("currencies")) {
                    QuotePair pair = new QuotePair(symbol, coins.getString(symbol), currency);
                    pairs.put(pair.symbol() + ":" + pair.currency(), pair);
                }
        configuredPairs = Map.copyOf(pairs);
    }

    public QuotePair configuredPair(String symbol, String currency) {
        return configuredPairs.get(
                symbol.toUpperCase(Locale.ROOT) + ":" + currency.toUpperCase(Locale.ROOT));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        CryptoBoard board = boards().findAt(event.getBlock());
        if (board != null)
            Bukkit.getScheduler()
                    .runTask(
                            this,
                            () -> {
                                if (event.getBlock().getType().isAir()) boards().remove(board);
                            });
    }

    private void removedBlocks(List<Block> blocks) {
        List<CryptoBoard> affected =
                blocks.stream().map(boards()::findAt).filter(Objects::nonNull).toList();
        if (!affected.isEmpty())
            Bukkit.getScheduler().runTask(this, () -> affected.forEach(boards()::remove));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        removedBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(BlockExplodeEvent event) {
        removedBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        removedBlocks(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent event) {
        removedBlocks(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrameBreak(HangingBreakEvent event) {
        if (boards().isDisplay(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrameDamage(EntityDamageEvent event) {
        if (boards().isDisplay(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (boards().isDisplay(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        boards().ensureBoardsInChunk(event.getChunk(), prices());
        features.updateSignals();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        boards().unload(event.getChunk());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        boards().ensureLoadedChunks(prices());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        features.notifyJoin(event.getPlayer());
    }
}

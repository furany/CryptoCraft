package de.cryptocraft;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class CryptoBoardService {
    private final JavaPlugin plugin;
    private final Map<String, CryptoBoard> boards = new LinkedHashMap<>();
    private final Map<String, CryptoBoard> byLocation = new LinkedHashMap<>();
    private final File file;
    private final YamlConfiguration data = new YamlConfiguration();
    private final org.bukkit.NamespacedKey boardKey;

    public CryptoBoardService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "boards.yml");
        this.boardKey = new org.bukkit.NamespacedKey(plugin, "board_id");
    }

    public void load() {
        boards.clear();
        byLocation.clear();
        if (!file.exists()) {
            save();
            return;
        }

        try {
            data.load(file);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load boards.yml.", exception);
            return;
        }

        ConfigurationSection section = data.getConfigurationSection("boards");
        if (section == null) {
            return;
        }

        for (String id : section.getKeys(false)) {
            String base = "boards." + id + ".";
            try {
                CryptoBoard board = new CryptoBoard(
                        id,
                        UUID.fromString(data.getString(base + "owner-id")),
                        data.getString(base + "owner-name", "Unknown"),
                        UUID.fromString(data.getString(base + "world-id")),
                        data.getString(base + "world-name", "world"),
                        data.getInt(base + "x"),
                        data.getInt(base + "y"),
                        data.getInt(base + "z"),
                        data.getString(base + "symbol", "BTC"),
                        data.getString(base + "coin-id", "bitcoin"),
                        data.getString(base + "currency", "EUR").toUpperCase(),
                        getDisplayFacing(base + "display-facing")
                );
                boards.put(id, board);
                byLocation.put(board.locationKey(), board);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Skipping invalid saved crypto board " + id + ".");
            }
        }
    }

    public CryptoBoard create(UUID ownerId, String ownerName, Block block, String symbol, String coinId,
                              String currency, BlockFace displayFacing) {
        String id = UUID.randomUUID().toString();
        CryptoBoard board = new CryptoBoard(
                id,
                ownerId,
                ownerName,
                block.getWorld().getUID(),
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ(),
                symbol,
                coinId,
                currency,
                displayFacing
        );
        boards.put(id, board);
        byLocation.put(board.locationKey(), board);
        save();
        return board;
    }

    public CryptoBoard findAt(Block block) {
        return byLocation.get(block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ());
    }

    public List<CryptoBoard> getBoards() {
        return List.copyOf(boards.values());
    }

    public CryptoBoard getBoard(String id) {
        return boards.get(id);
    }

    private BlockFace getDisplayFacing(String path) {
        String configuredFacing = data.getString(path, "SOUTH");
        if (configuredFacing == null || configuredFacing.isBlank()) {
            return BlockFace.SOUTH;
        }
        BlockFace facing;
        try {
            facing = BlockFace.valueOf(configuredFacing.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return BlockFace.SOUTH;
        }
        return switch (facing) {
            case NORTH, EAST, SOUTH, WEST -> facing;
            default -> BlockFace.SOUTH;
        };
    }

    public List<CryptoBoard> getBoardsOwnedBy(UUID ownerId) {
        return boards.values().stream().filter(board -> board.ownerId().equals(ownerId)).toList();
    }

    public boolean remove(CryptoBoard board) {
        if (boards.remove(board.id()) == null) {
            return false;
        }
        byLocation.remove(board.locationKey());
        removeDisplayIfLoaded(board);
        save();
        return true;
    }

    public void ensureLoadedChunks(CryptoPriceService prices) {
        for (CryptoBoard board : new ArrayList<>(boards.values())) {
            World world = resolveWorld(board);
            if (world == null) {
                continue;
            }
            int chunkX = board.x() >> 4;
            int chunkZ = board.z() >> 4;
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                ensureDisplay(board, world.getChunkAt(chunkX, chunkZ), prices);
            }
        }
    }

    public void ensureBoardsInChunk(Chunk chunk, CryptoPriceService prices) {
        List<CryptoBoard> inChunk = boards.values().stream()
                .filter(board -> board.worldId().equals(chunk.getWorld().getUID()))
                .filter(board -> (board.x() >> 4) == chunk.getX() && (board.z() >> 4) == chunk.getZ())
                .toList();

        for (CryptoBoard board : inChunk) {
            Block anchor = chunk.getWorld().getBlockAt(board.x(), board.y(), board.z());
            if (anchor.getType().isAir()) {
                remove(board);
            } else {
                ensureDisplay(board, chunk, prices);
            }
        }

        removeOrphanedDisplays(chunk);
    }

    public void refreshLoadedDisplays(CryptoPriceService prices) {
        for (CryptoBoard board : new ArrayList<>(boards.values())) {
            World world = resolveWorld(board);
            if (world == null) {
                continue;
            }
            int chunkX = board.x() >> 4;
            int chunkZ = board.z() >> 4;
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                ensureDisplay(board, world.getChunkAt(chunkX, chunkZ), prices);
            }
        }
    }

    private void ensureDisplay(CryptoBoard board, Chunk chunk, CryptoPriceService prices) {
        Block anchor = chunk.getWorld().getBlockAt(board.x(), board.y(), board.z());
        if (anchor.getType().isAir()) {
            remove(board);
            return;
        }

        double height = plugin.getConfig().getDouble("boards.display-height", 1.35);
        Location location = anchor.getLocation().add(0.5, height, 0.5);
        removeLegacyTextDisplays(board, chunk);
        ItemFrame display = findDisplay(board, chunk);
        if (display == null) {
            display = chunk.getWorld().spawn(location, ItemFrame.class, created -> {
                created.setPersistent(true);
                created.setInvulnerable(true);
                created.setFacingDirection(board.displayFacing(), true);
                created.setVisible(false);
                created.setFixed(true);
                created.setItemDropChance(0.0f);
                created.getPersistentDataContainer().set(boardKey, PersistentDataType.STRING, board.id());
            });
        } else if (display.getLocation().distanceSquared(location) > 0.0001) {
            display.teleport(location);
        }
        updateChartDisplay(display, board, prices);
    }

    private void updateChartDisplay(ItemFrame display, CryptoBoard board, CryptoPriceService prices) {
        MapView mapView = null;
        ItemStack displayedItem = display.getItem();
        if (displayedItem.getType() == Material.FILLED_MAP
                && displayedItem.getItemMeta() instanceof MapMeta mapMeta) {
            mapView = mapMeta.getMapView();
        }

        if (mapView == null) {
            mapView = Bukkit.createMap(display.getWorld());
            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
            MapMeta mapMeta = (MapMeta) mapItem.getItemMeta();
            mapMeta.setMapView(mapView);
            mapItem.setItemMeta(mapMeta);
            display.setItem(mapItem, false);
        }
        display.setFixed(true);
        display.setVisible(false);
        display.setItemDropChance(0.0f);

        CryptoChartRenderer renderer = null;
        for (MapRenderer existing : List.copyOf(mapView.getRenderers())) {
            if (existing instanceof CryptoChartRenderer chart && chart.boardId().equals(board.id())) {
                renderer = chart;
            } else {
                mapView.removeRenderer(existing);
            }
        }
        if (renderer == null) {
            mapView.addRenderer(new CryptoChartRenderer(plugin, board, prices));
        } else {
            renderer.update();
        }
        mapView.setTrackingPosition(false);
        mapView.setLocked(true);
    }

    private ItemFrame findDisplay(CryptoBoard board, Chunk chunk) {
        ItemFrame found = null;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemFrame candidate)) {
                continue;
            }
            String id = candidate.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
            if (!board.id().equals(id)) {
                continue;
            }
            if (found == null) {
                found = candidate;
            } else {
                removeChartRenderer(candidate, board.id());
                candidate.remove();
            }
        }
        return found;
    }

    private void removeLegacyTextDisplays(CryptoBoard board, Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof TextDisplay display
                    && board.id().equals(display.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING))) {
                display.remove();
            }
        }
    }

    private void removeOrphanedDisplays(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            String id = entity.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
            if (id != null && !boards.containsKey(id)) {
                removeChartRenderer(entity, id);
                entity.remove();
            }
        }
    }

    private void removeDisplayIfLoaded(CryptoBoard board) {
        World world = resolveWorld(board);
        if (world == null) {
            return;
        }
        int chunkX = board.x() >> 4;
        int chunkZ = board.z() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        removeDisplayById(world.getChunkAt(chunkX, chunkZ), board.id());
    }

    private void removeDisplayById(Chunk chunk, String id) {
        for (Entity entity : chunk.getEntities()) {
            String foundId = entity.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
            if (id.equals(foundId)) {
                removeChartRenderer(entity, id);
                entity.remove();
            }
        }
    }

    private void removeChartRenderer(Entity entity, String boardId) {
        if (!(entity instanceof ItemFrame display)) {
            return;
        }
        ItemStack item = display.getItem();
        if (item.getType() != Material.FILLED_MAP || !(item.getItemMeta() instanceof MapMeta mapMeta)) {
            return;
        }
        MapView mapView = mapMeta.getMapView();
        if (mapView == null) {
            return;
        }
        for (MapRenderer renderer : List.copyOf(mapView.getRenderers())) {
            if (renderer instanceof CryptoChartRenderer chart && chart.boardId().equals(boardId)) {
                mapView.removeRenderer(renderer);
            }
        }
    }

    private World resolveWorld(CryptoBoard board) {
        World world = Bukkit.getWorld(board.worldId());
        return world != null ? world : Bukkit.getWorld(board.worldName());
    }

    private void save() {
        data.set("boards", null);
        for (CryptoBoard board : boards.values()) {
            String base = "boards." + board.id() + ".";
            data.set(base + "owner-id", board.ownerId().toString());
            data.set(base + "owner-name", board.ownerName());
            data.set(base + "world-id", board.worldId().toString());
            data.set(base + "world-name", board.worldName());
            data.set(base + "x", board.x());
            data.set(base + "y", board.y());
            data.set(base + "z", board.z());
            data.set(base + "symbol", board.symbol());
            data.set(base + "coin-id", board.coinId());
            data.set(base + "currency", board.currency());
            data.set(base + "display-facing", board.displayFacing().name());
        }
        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save boards.yml.", exception);
        }
    }
}

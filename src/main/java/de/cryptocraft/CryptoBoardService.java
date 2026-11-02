package de.cryptocraft;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Matrix4f;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/** World access stays on the main thread; indexes avoid repeated full entity scans. */
public final class CryptoBoardService {
    // Vanilla hanging entities snap to a block and place their center 15/32 behind its center.
    private static final double ITEM_FRAME_CENTER_OFFSET = 15.0 / 32;
    // Invisible map rendering adds 15/32 - 1/2 + 1/128 along the frame facing.
    private static final double MAP_SURFACE_OFFSET = -3.0 / 128;
    private final CryptoCraftPlugin plugin;
    private final Map<String, CryptoBoard> boards = new LinkedHashMap<>();
    private volatile List<CryptoBoard> snapshot = List.of();
    private final Map<String, CryptoBoard> byLocation = new HashMap<>();
    private final Map<ChunkKey, Set<String>> byChunk = new HashMap<>();
    private final Map<String, UUID> displays = new HashMap<>();
    private final Map<String, java.util.concurrent.CompletableFuture<BufferedImage>> images =
            new LinkedHashMap<>(32, 0.75f, true);
    private final java.util.concurrent.ExecutorService rasterWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "CryptoCraft-charts");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final File file;
    private final NamespacedKey boardKey;
    private final NamespacedKey tileKey;
    private final NamespacedKey componentKey;
    private String indexedTerminalStyle;

    public CryptoBoardService(CryptoCraftPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "boards.yml");
        boardKey = new NamespacedKey(plugin, "board_id");
        tileKey = new NamespacedKey(plugin, "tile");
        componentKey = new NamespacedKey(plugin, "component");
        indexedTerminalStyle = terminalStyle();
    }

    public void load() {
        YamlConfiguration data = plugin.storage().load(file);
        var section = data.getConfigurationSection("boards");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            String base = "boards." + id + ".";
            try {
                String type = data.getString(base + "type", "chart");
                if (!Set.of("chart", "market").contains(type))
                    throw new IllegalArgumentException("Unknown board type: " + type);
                String currency = data.getString(base + "currency", "EUR").toUpperCase(Locale.ROOT);
                List<QuotePair> pairs = new ArrayList<>();
                if (type.equals("market")) {
                    for (Map<?, ?> row : data.getMapList(base + "market-coins"))
                        pairs.add(new QuotePair(Objects.toString(row.get("symbol"), ""),
                                Objects.toString(row.get("coin-id"), ""), currency));
                    if (pairs.isEmpty()) throw new IllegalArgumentException("Empty market board");
                }
                put(
                        new CryptoBoard(
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
                                currency,
                                facing(data.getString(base + "display-facing", "SOUTH")),
                                new BoardSettings(
                                        data.getString(base + "name", ""),
                                        data.getDouble(base + "height", defaultHeight()),
                                        data.getInt(base + "hours", defaultHours()),
                                        data.getString(base + "theme", "dark"),
                                        data.getString(base + "style", "chart"),
                                        data.getInt(base + "size", 1),
                                        data.getBoolean(base + "range", false)),
                                pairs,
                                data.getBoolean(base + "terminal", false)));
            } catch (RuntimeException error) {
                throw new IllegalStateException(
                        "Invalid saved board " + id + "; refusing to overwrite boards.yml", error);
            }
        }
    }

    public static BlockFace facing(String value) {
        BlockFace face = BlockFace.valueOf(value.toUpperCase(Locale.ROOT));
        if (!Set.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
                .contains(face))
            throw new IllegalArgumentException("facing: north|east|south|west");
        return face;
    }

    public CryptoBoard create(
            UUID owner,
            String ownerName,
            Block block,
            String symbol,
            String coinId,
            String currency,
            BlockFace face) {
        CryptoBoard board =
                new CryptoBoard(
                        UUID.randomUUID().toString(),
                        owner,
                        ownerName,
                        block.getWorld().getUID(),
                        block.getWorld().getName(),
                        block.getX(),
                        block.getY(),
                        block.getZ(),
                        symbol,
                        coinId,
                        currency,
                        face,
                        new BoardSettings(
                                "", defaultHeight(), defaultHours(), "dark", "chart", 1, false));
        put(board);
        save();
        return board;
    }

    private double defaultHeight() {
        return plugin.getConfig().getDouble("boards.display-height", 1.35);
    }

    public CryptoBoard createMarket(UUID owner, String ownerName, Block block,
            List<QuotePair> pairs, BlockFace face) {
        if (pairs.isEmpty()) throw new IllegalArgumentException("Market boards need 1–6 coins");
        QuotePair first = pairs.getFirst();
        CryptoBoard board = new CryptoBoard(UUID.randomUUID().toString(), owner, ownerName,
                block.getWorld().getUID(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                first.symbol(), first.coinId(), first.currency(), face,
                new BoardSettings("", defaultHeight(), defaultHours(), "dark", "chart", 2, false), pairs, false);
        put(board);
        save();
        return board;
    }

    public String terminalStyle() {
        String style = plugin.getConfig().getString("trading.terminal-style", "computer");
        return "labeled".equalsIgnoreCase(style) || "board".equalsIgnoreCase(style) ? "labeled" : "computer";
    }

    /** Reload must not turn a valid labeled board into an overlapping computer model. */
    public void validateConfiguration(org.bukkit.configuration.file.FileConfiguration candidate) {
        String style = candidate.getString("trading.terminal-style", "computer");
        if (!style.equalsIgnoreCase("computer")) return;
        List<String> tooLow = snapshot.stream().filter(CryptoBoard::terminal)
                .filter(board -> board.settings().height() < TerminalModel.MINIMUM_HEIGHT)
                .map(CryptoBoard::id).toList();
        if (!tooLow.isEmpty()) throw new IllegalArgumentException(
                "Raise these trading terminals to height " + TerminalModel.MINIMUM_HEIGHT
                        + " before selecting computer style: " + String.join(", ", tooLow));
    }

    private List<TerminalModel.Part> components(CryptoBoard board) {
        return board.terminal() && terminalStyle().equals("computer") ? TerminalModel.parts(board) : List.of();
    }

    private int defaultHours() {
        return plugin.getConfig().getInt("boards.chart-history-hours", 24);
    }

    private void put(CryptoBoard board) {
        if (byLocation.containsKey(board.locationKey()))
            throw new IllegalArgumentException("Duplicate board anchor: " + board.locationKey());
        boards.put(board.id(), board);
        byLocation.put(board.locationKey(), board);
        for (ChunkKey chunk : chunks(board))
            byChunk.computeIfAbsent(chunk, key -> new LinkedHashSet<>()).add(board.id());
        snapshot = List.copyOf(boards.values());
    }

    public CryptoBoard findAt(Block block) {
        return byLocation.get(
                block.getWorld().getUID()
                        + ":"
                        + block.getX()
                        + ":"
                        + block.getY()
                        + ":"
                        + block.getZ());
    }

    public List<CryptoBoard> getBoards() {
        return snapshot;
    }

    public CryptoBoard getBoard(String id) {
        return boards.get(id);
    }

    public CryptoBoard getBoard(Entity entity) {
        String id = entity.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
        return id == null ? null : boards.get(id);
    }

    public CryptoBoard nearestTerminal(Player player, double radius) {
        return snapshot.stream().filter(CryptoBoard::terminal)
                .filter(board -> terminalInRange(player, board, radius))
                .min(java.util.Comparator.comparingDouble(board -> terminalDistanceSquared(player, board))).orElse(null);
    }

    public boolean terminalInRange(Player player, CryptoBoard board, double radius) {
        if (board == null || !Double.isFinite(radius) || radius < 0) return false;
        CryptoBoard current = boards.get(board.id());
        if (current == null || !current.terminal() || !current.worldId().equals(player.getWorld().getUID())) return false;
        World world = player.getWorld();
        return world.isChunkLoaded(current.x() >> 4, current.z() >> 4)
                && !world.getBlockAt(current.x(), current.y(), current.z()).getType().isAir()
                && terminalDistanceSquared(player, current) <= radius * radius;
    }

    private double terminalDistanceSquared(Player player, CryptoBoard board) {
        return player.getLocation().distanceSquared(new Location(player.getWorld(), board.x() + .5,
                board.y() + board.settings().height(), board.z() + .5));
    }

    public List<CryptoBoard> getBoardsOwnedBy(UUID owner) {
        return snapshot.stream().filter(b -> b.ownerId().equals(owner)).toList();
    }

    public void replace(CryptoBoard board) {
        CryptoBoard previous = boards.get(board.id());
        if (previous == null) throw new IllegalArgumentException("Board no longer exists");
        removeDisplays(previous);
        unindex(previous);
        put(board);
        save();
        refreshLoadedDisplays(plugin.prices());
    }

    private void unindex(CryptoBoard board) {
        boards.remove(board.id());
        byLocation.remove(board.locationKey());
        for (ChunkKey key : chunks(board)) {
            Set<String> ids = byChunk.get(key);
            if (ids != null && ids.remove(board.id()) && ids.isEmpty()) byChunk.remove(key);
        }
        snapshot = List.copyOf(boards.values());
    }

    public boolean remove(CryptoBoard board) {
        board = boards.get(board.id());
        if (board == null) return false;
        if (plugin.features() != null) plugin.features().removeBoardSignals(board.id());
        removeDisplays(board);
        unindex(board);
        save();
        return true;
    }

    public void ensureLoadedChunks(CryptoPriceService prices) {
        refreshComponentIndex();
        // Include loaded chunks with orphaned displays but no surviving board at startup.
        for (World world : Bukkit.getWorlds())
            for (Chunk chunk : world.getLoadedChunks()) ensureBoardsInChunk(chunk, prices);
    }

    public void ensureBoardsInChunk(Chunk chunk, CryptoPriceService prices) {
        refreshComponentIndex();
        indexEntities(chunk);
        for (String id : List.copyOf(byChunk.getOrDefault(ChunkKey.of(chunk), Set.of()))) {
            CryptoBoard board = boards.get(id);
            if (board != null) ensureDisplay(board, prices);
        }
    }

    private void indexEntities(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            String id =
                    entity.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
            if (id == null) continue;
            CryptoBoard board = boards.get(id);
            // Persistent tags copied into another world must never claim this board's display slot.
            if (board != null && !board.worldId().equals(chunk.getWorld().getUID())) {
                discard(entity);
                continue;
            }
            String component = entity.getPersistentDataContainer().get(componentKey, PersistentDataType.STRING);
            Integer tile = entity.getPersistentDataContainer().get(tileKey, PersistentDataType.INTEGER);
            int index = tile == null ? 0 : tile;
            TerminalModel.Part part = board == null || component == null ? null : components(board).stream()
                    .filter(value -> value.id().equals(component)).findFirst().orElse(null);
            boolean validMap = component == null && entity instanceof ItemFrame && board != null
                    && index >= 0 && index < board.settings().size() * board.settings().size();
            boolean validPart = component != null && entity instanceof BlockDisplay && part != null;
            if (!validMap && !validPart) {
                discard(entity);
                continue;
            }
            Location expected = validPart ? part.location(board, chunk.getWorld()) : tileLocation(board, chunk.getWorld(), index);
            if ((entity.getLocation().getBlockX() >> 4) != (expected.getBlockX() >> 4)
                    || (entity.getLocation().getBlockZ() >> 4) != (expected.getBlockZ() >> 4)) {
                discard(entity);
                continue;
            }
            String key = validPart ? componentDisplayKey(id, component) : displayKey(id, index);
            UUID old = displays.putIfAbsent(key, entity.getUniqueId());
            if (old != null && !old.equals(entity.getUniqueId())) {
                if (Bukkit.getEntity(old) != null) discard(entity);
                else displays.put(key, entity.getUniqueId());
            }
        }
    }

    public void unload(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            String id =
                    entity.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
            if (id != null) {
                Integer tile =
                        entity.getPersistentDataContainer()
                                .get(tileKey, PersistentDataType.INTEGER);
                String component = entity.getPersistentDataContainer().get(componentKey, PersistentDataType.STRING);
                displays.remove(component == null ? displayKey(id, tile == null ? 0 : tile)
                        : componentDisplayKey(id, component), entity.getUniqueId());
                detachRenderer(entity);
            }
        }
    }

    public void refreshLoadedDisplays(CryptoPriceService prices) {
        refreshComponentIndex();
        for (CryptoBoard board : snapshot) ensureDisplay(board, prices);
    }

    private void refreshComponentIndex() {
        String style = terminalStyle();
        if (style.equals(indexedTerminalStyle)) return;
        // Index the previous geometry as well until stale decorations in loaded chunks are removed.
        for (UUID id : List.copyOf(displays.values())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof BlockDisplay && entity.getPersistentDataContainer().has(componentKey, PersistentDataType.STRING)) {
                discard(entity);
                displays.values().remove(id);
            }
        }
        indexedTerminalStyle = style;
        byChunk.clear();
        for (CryptoBoard board : snapshot)
            for (ChunkKey chunk : chunks(board))
                byChunk.computeIfAbsent(chunk, key -> new LinkedHashSet<>()).add(board.id());
    }

    java.util.concurrent.CompletableFuture<BufferedImage> sharedImage(
            String key, java.util.function.Supplier<BufferedImage> draw) {
        var image =
                images.computeIfAbsent(
                        key,
                        unused ->
                                java.util.concurrent.CompletableFuture.supplyAsync(
                                                draw, rasterWorker)
                                        .whenComplete(
                                                (result, error) -> {
                                                    if (error != null && !rasterWorker.isShutdown())
                                                        plugin.getLogger()
                                                                .warning(
                                                                        "Could not render a chart: "
                                                                                + error.getClass()
                                                                                        .getSimpleName());
                                                }));
        if (images.size() > 256) images.remove(images.keySet().iterator().next());
        return image;
    }

    private void ensureDisplay(CryptoBoard board, CryptoPriceService prices) {
        World world = resolveWorld(board);
        if (world == null || !world.isChunkLoaded(board.x() >> 4, board.z() >> 4)) return;
        if (world.getBlockAt(board.x(), board.y(), board.z()).getType().isAir()) {
            remove(board);
            return;
        }
        for (int tile = 0; tile < board.settings().size() * board.settings().size(); tile++) {
            Location at = tileLocation(board, world, tile);
            if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            String key = displayKey(board.id(), tile);
            Entity entity = displays.containsKey(key) ? Bukkit.getEntity(displays.get(key)) : null;
            ItemFrame display;
            if (entity instanceof ItemFrame existing && existing.isValid()) display = existing;
            else {
                int index = tile;
                display =
                        world.spawn(
                                at,
                                ItemFrame.class,
                                created -> {
                                    created.setPersistent(true);
                                    created.setInvulnerable(true);
                                    created.setFacingDirection(board.displayFacing(), true);
                                    created.setVisible(false);
                                    created.setFixed(true);
                                    created.setItemDropChance(0);
                                    created.getPersistentDataContainer()
                                            .set(boardKey, PersistentDataType.STRING, board.id());
                                    created.getPersistentDataContainer()
                                            .set(tileKey, PersistentDataType.INTEGER, index);
                                });
                displays.put(key, display.getUniqueId());
            }
            if (display.getFacing() != board.displayFacing())
                display.setFacingDirection(board.displayFacing(), true);
            if (display.getLocation().distanceSquared(at) > 0.0001) display.teleport(at);
            display.setFixed(true);
            display.setVisible(false);
            display.setInvulnerable(true);
            display.setItemDropChance(0);
            updateMap(display, board, prices, tile);
        }
        for (TerminalModel.Part part : components(board)) ensureComponent(board, world, part);
    }

    private void ensureComponent(CryptoBoard board, World world, TerminalModel.Part part) {
        Location at = part.location(board, world);
        if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        String key = componentDisplayKey(board.id(), part.id());
        Entity existing = displays.containsKey(key) ? Bukkit.getEntity(displays.get(key)) : null;
        BlockDisplay display;
        if (existing instanceof BlockDisplay value && value.isValid()) display = value;
        else {
            display = world.spawn(at, BlockDisplay.class, created -> {
                created.setPersistent(true);
                created.setInvulnerable(true);
                created.setGravity(false);
                created.getPersistentDataContainer().set(boardKey, PersistentDataType.STRING, board.id());
                created.getPersistentDataContainer().set(componentKey, PersistentDataType.STRING, part.id());
            });
            displays.put(key, display.getUniqueId());
        }
        if (display.getLocation().distanceSquared(at) > .0001) display.teleport(at);
        Material material = part.id().equals("indicator") && (plugin.trading() == null || !plugin.trading().available())
                ? Material.ORANGE_CONCRETE : part.material();
        display.setBlock(material.createBlockData());
        display.setTransformationMatrix(new Matrix4f().scaling((float) part.width(), (float) part.height(), (float) part.depth()));
        display.setDisplayWidth((float) Math.max(part.width(), part.depth()));
        display.setDisplayHeight((float) part.height());
        display.setViewRange(1.0f);
    }

    private void updateMap(
            ItemFrame display, CryptoBoard board, CryptoPriceService prices, int tile) {
        MapView view =
                display.getItem().getItemMeta() instanceof MapMeta meta ? meta.getMapView() : null;
        if (view == null) {
            view = Bukkit.createMap(display.getWorld());
            ItemStack item = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) item.getItemMeta();
            meta.setMapView(view);
            item.setItemMeta(meta);
            display.setItem(item, false);
        }
        CryptoChartRenderer found = null;
        for (MapRenderer renderer : List.copyOf(view.getRenderers())) {
            if (renderer instanceof CryptoChartRenderer chart
                    && chart.boardId().equals(board.id())
                    && chart.tile() == tile) found = chart;
            else view.removeRenderer(renderer);
        }
        if (found == null) view.addRenderer(new CryptoChartRenderer(plugin, board, prices, tile));
        else found.update();
        view.setTrackingPosition(false);
        view.setLocked(true);
    }

    public boolean isDisplay(Entity entity) {
        return entity.getPersistentDataContainer().has(boardKey, PersistentDataType.STRING);
    }

    private String displayKey(String id, int tile) {
        return id + ":" + tile;
    }

    private String componentDisplayKey(String id, String component) {
        return id + ":component:" + component;
    }

    private void removeDisplays(CryptoBoard board) {
        World world = resolveWorld(board);
        for (ChunkKey key : world == null ? Set.<ChunkKey>of() : chunks(board)) {
            if (!world.isChunkLoaded(key.x(), key.z())) continue;
            for (Entity entity : world.getChunkAt(key.x(), key.z()).getEntities())
                if (board.id()
                        .equals(
                                entity.getPersistentDataContainer()
                                        .get(boardKey, PersistentDataType.STRING))) discard(entity);
        }
        displays.keySet().removeIf(key -> key.startsWith(board.id() + ":"));
    }

    private void discard(Entity entity) {
        detachRenderer(entity);
        entity.remove();
    }

    private void detachRenderer(Entity entity) {
        if (entity instanceof ItemFrame frame
                && frame.getItem().getItemMeta() instanceof MapMeta meta
                && meta.getMapView() != null)
            for (MapRenderer renderer : List.copyOf(meta.getMapView().getRenderers()))
                if (renderer instanceof CryptoChartRenderer)
                    meta.getMapView().removeRenderer(renderer);
    }

    public static Location tileLocation(CryptoBoard board, World world, int tile) {
        int size = board.settings().size();
        double horizontal = tile % size - (size - 1) / 2.0;
        int rightX = board.displayFacing().getModZ();
        int rightZ = -board.displayFacing().getModX();
        return new Location(
                world,
                Math.floor(board.x() + 0.5 + horizontal * rightX) + .5
                        - board.displayFacing().getModX() * ITEM_FRAME_CENTER_OFFSET,
                Math.floor(board.y() + board.settings().height()) + .5 + size - 1 - tile / size,
                Math.floor(board.z() + 0.5 + horizontal * rightZ) + .5
                        - board.displayFacing().getModZ() * ITEM_FRAME_CENTER_OFFSET);
    }

    /** Visible map plane, distinct from the hanging entity's hitbox center. */
    static Location mapSurfaceLocation(CryptoBoard board, World world, int tile) {
        return tileLocation(board, world, tile).add(board.displayFacing().getModX() * MAP_SURFACE_OFFSET,
                0, board.displayFacing().getModZ() * MAP_SURFACE_OFFSET);
    }

    private Set<ChunkKey> chunks(CryptoBoard board) {
        Set<ChunkKey> keys = new HashSet<>();
        keys.add(new ChunkKey(board.worldId(), board.x() >> 4, board.z() >> 4));
        for (int tile = 0; tile < board.settings().size() * board.settings().size(); tile++) {
            Location at = tileLocation(board, null, tile);
            keys.add(new ChunkKey(board.worldId(), at.getBlockX() >> 4, at.getBlockZ() >> 4));
        }
        for (TerminalModel.Part part : components(board)) {
            Location at = part.location(board, null);
            keys.add(new ChunkKey(board.worldId(), at.getBlockX() >> 4, at.getBlockZ() >> 4));
        }
        for (Cell cell : footprintCells(board, components(board)))
            keys.add(new ChunkKey(board.worldId(), cell.x() >> 4, cell.z() >> 4));
        return keys;
    }

    /** Every block occupied by either the screen or a decorative component, including their extents. */
    public List<Block> footprint(CryptoBoard board) {
        World world = resolveWorld(board);
        if (world == null) return List.of();
        Set<Cell> cells = footprintCells(board, components(board));
        return cells.stream().map(cell -> world.getBlockAt(cell.x(), cell.y(), cell.z())).toList();
    }

    static Set<Cell> footprintCells(CryptoBoard board, List<TerminalModel.Part> components) {
        Set<Cell> cells = new LinkedHashSet<>();
        cells.add(new Cell(board.x(), board.y(), board.z()));
        boolean eastWest = board.displayFacing().getModX() != 0;
        for (int tile = 0; tile < board.settings().size() * board.settings().size(); tile++) {
            Location at = mapSurfaceLocation(board, null, tile);
            addCells(cells, at.getX() - (eastWest ? .08 : .5), at.getY() - .5,
                    at.getZ() - (eastWest ? .5 : .08), eastWest ? .16 : 1, 1, eastWest ? 1 : .16);
        }
        for (TerminalModel.Part part : components) {
            Location at = part.location(board, null);
            addCells(cells, at.getX(), at.getY(), at.getZ(), part.width(), part.height(), part.depth());
        }
        return Set.copyOf(cells);
    }

    private static void addCells(Set<Cell> cells, double x, double y, double z, double width, double height, double depth) {
        for (int bx = (int) Math.floor(x); bx <= (int) Math.floor(x + width - 1e-7); bx++)
            for (int by = (int) Math.floor(y); by <= (int) Math.floor(y + height - 1e-7); by++)
                for (int bz = (int) Math.floor(z); bz <= (int) Math.floor(z + depth - 1e-7); bz++)
                    cells.add(new Cell(bx, by, bz));
    }

    record Cell(int x, int y, int z) {}

    public World resolveWorld(CryptoBoard board) {
        World world = Bukkit.getWorld(board.worldId());
        return world == null ? Bukkit.getWorld(board.worldName()) : world;
    }

    public void validateAnchors() {
        refreshLoadedDisplays(plugin.prices());
    }

    public void close() {
        for (UUID id : displays.values()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) detachRenderer(entity);
        }
        displays.clear();
        images.clear();
        rasterWorker.shutdownNow();
    }

    private void save() {
        List<CryptoBoard> copy = snapshot;
        plugin.storage()
                .save(
                        file,
                        () -> {
                            YamlConfiguration data = new YamlConfiguration();
                            for (CryptoBoard board : copy) {
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
                                data.set(base + "type", board.market() ? "market" : "chart");
                                data.set(base + "terminal", board.terminal());
                                if (board.market()) data.set(base + "market-coins", board.quotePairs().stream()
                                        .map(pair -> Map.of("symbol", pair.symbol(), "coin-id", pair.coinId())).toList());
                                data.set(base + "display-facing", board.displayFacing().name());
                                BoardSettings options = board.settings();
                                data.set(base + "name", options.name());
                                data.set(base + "height", options.height());
                                data.set(base + "hours", options.hours());
                                data.set(base + "theme", options.theme());
                                data.set(base + "style", options.style());
                                data.set(base + "size", options.size());
                                data.set(base + "range", options.showRange());
                            }
                            return data;
                        });
    }

    private record ChunkKey(UUID world, int x, int z) {
        static ChunkKey of(Chunk chunk) {
            return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }
}

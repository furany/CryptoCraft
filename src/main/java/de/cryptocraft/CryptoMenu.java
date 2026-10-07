package de.cryptocraft;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Holder identity, cancelled transfers, paginated lists, and authoritative permission checks. */
public final class CryptoMenu implements Listener {
    private final CryptoCraftPlugin plugin;
    private final CryptoCommand commands;
    private final Map<UUID, Rename> renames = new ConcurrentHashMap<>();

    public CryptoMenu(CryptoCraftPlugin plugin, CryptoCommand commands) {
        this.plugin = plugin;
        this.commands = commands;
    }

    public void open(Player player, int page) {
        requireUse(player);
        renames.remove(player.getUniqueId());
        List<CryptoBoard> boards =
                plugin.isAdmin(player)
                        ? plugin.boards().getBoards()
                        : plugin.boards().getBoardsOwnedBy(player.getUniqueId());
        page = Math.max(0, Math.min(page, Math.max(0, (boards.size() - 1) / 45)));
        Holder holder = new Holder("list", "", page);
        Inventory inventory = inventory(holder, 54, "menuTitle");
        for (int index = page * 45; index < Math.min(boards.size(), (page + 1) * 45); index++) {
            CryptoBoard board = boards.get(index);
            int slot = index % 45;
            item(
                    inventory,
                    slot,
                    Material.FILLED_MAP,
                    board.settings().name().isBlank()
                            ? board.symbol() + "/" + board.currency()
                            : board.settings().name(),
                    List.of(
                            board.worldName()
                                    + " "
                                    + board.x()
                                    + ", "
                                    + board.y()
                                    + ", "
                                    + board.z(),
                            board.settings().hours() + "h | " + board.settings().theme(),
                            plugin.messages().get("menuEditHint")));
            holder.entries.put(slot, board.id());
        }
        if (page > 0)
            item(inventory, 45, Material.ARROW, plugin.messages().get("menuPrevious"), List.of());
        if ((page + 1) * 45 < boards.size())
            item(inventory, 53, Material.ARROW, plugin.messages().get("menuNext"), List.of());
        item(
                inventory,
                48,
                Material.SPYGLASS,
                plugin.messages().get("menuWatchlist"),
                List.of("/crypto watch add <coin> [currency]"));
        item(
                inventory,
                49,
                Material.BELL,
                plugin.messages().get("menuAlerts"),
                List.of("/crypto alert add <coin> <currency> above <price>"));
        item(
                inventory,
                50,
                Material.EMERALD,
                plugin.messages().get("menuPortfolio"),
                List.of("/crypto portfolio"));
        player.openInventory(inventory);
    }

    private CryptoBoard board(Player player, String id) {
        requireUse(player);
        CryptoBoard board = plugin.boards().getBoard(id);
        if (board == null
                || (!board.ownerId().equals(player.getUniqueId()) && !plugin.isAdmin(player)))
            throw new IllegalArgumentException(plugin.messages().get("boardIdUnknown"));
        return board;
    }

    private void edit(Player player, String id) {
        CryptoBoard board = board(player, id);
        Holder holder = new Holder("edit", id, 0);
        Inventory inventory = inventory(holder, 27, "menuEdit");
        item(inventory, 0, Material.GOLD_INGOT, "Coin: " + board.symbol(), List.of());
        item(
                inventory,
                1,
                Material.EMERALD,
                plugin.messages().get("menuCurrency") + ": " + board.currency(),
                List.of());
        item(
                inventory,
                2,
                Material.CLOCK,
                plugin.messages().get("menuHours") + ": " + board.settings().hours() + "h",
                List.of());
        item(
                inventory,
                3,
                Material.COMPASS,
                plugin.messages().get("menuFacing") + ": " + board.displayFacing(),
                List.of());
        item(
                inventory,
                4,
                Material.LADDER,
                plugin.messages().get("menuHeight") + ": " + board.settings().height(),
                List.of());
        item(
                inventory,
                5,
                Material.PAINTING,
                plugin.messages().get("menuTheme") + ": " + board.settings().theme(),
                List.of());
        item(
                inventory,
                6,
                Material.PAPER,
                plugin.messages().get("menuStyle") + ": " + board.settings().style(),
                List.of());
        item(
                inventory,
                7,
                Material.MAP,
                plugin.messages().get("menuSize") + ": " + board.settings().size(),
                List.of());
        item(
                inventory,
                8,
                Material.NAME_TAG,
                plugin.messages().get("menuName") + ": " + board.settings().name(),
                List.of());
        item(
                inventory,
                10,
                Material.REDSTONE,
                plugin.messages().get("menuRange") + ": " + board.settings().showRange(),
                List.of());
        item(inventory, 18, Material.ARROW, plugin.messages().get("menuBack"), List.of());
        item(inventory, 22, Material.ENDER_PEARL, plugin.messages().get("menuTeleport"), List.of());
        item(inventory, 26, Material.BARRIER, plugin.messages().get("menuRemove"), List.of());
        player.openInventory(inventory);
    }

    private void confirm(Player player, String id) {
        board(player, id);
        Holder holder = new Holder("delete", id, 0);
        Inventory inventory = inventory(holder, 27, "menuConfirm");
        item(inventory, 11, Material.LIME_CONCRETE, plugin.messages().get("menuRemove"), List.of());
        item(inventory, 15, Material.RED_CONCRETE, plugin.messages().get("menuBack"), List.of());
        player.openInventory(inventory);
    }

    private Inventory inventory(Holder holder, int size, String title) {
        holder.inventory = Bukkit.createInventory(holder, size, plugin.messages().get(title));
        return holder.inventory;
    }

    private void item(
            Inventory inventory, int slot, Material material, String title, List<String> lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.setDisplayName("§6" + title);
        meta.setLore(lore.stream().map(text -> "§7" + text).toList());
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        int slot = event.getRawSlot();
        Bukkit.getScheduler()
                .runTask(
                        plugin,
                        () -> {
                            if (!player.isOnline()
                                    || player.getOpenInventory().getTopInventory().getHolder()
                                            != holder) return;
                            try {
                                action(player, holder, slot);
                            } catch (IllegalArgumentException error) {
                                player.sendMessage("§6[CryptoCraft] §c" + error.getMessage());
                            }
                        });
    }

    private void action(Player player, Holder holder, int slot) {
        requireUse(player);
        if (holder.screen.equals("list")) {
            if (holder.entries.containsKey(slot)) edit(player, holder.entries.get(slot));
            else if (slot == 45) open(player, holder.page - 1);
            else if (slot == 53) open(player, holder.page + 1);
            else if (slot == 48 || slot == 49 || slot == 50) {
                player.closeInventory();
                player.performCommand(
                        slot == 48
                                ? "crypto watch list"
                                : slot == 49 ? "crypto alert list" : "crypto portfolio");
            }
            return;
        }
        CryptoBoard board = board(player, holder.id);
        if (holder.screen.equals("delete")) {
            if (slot == 11) {
                commands.removeBoard(player, holder.id);
                open(player, 0);
            } else if (slot == 15) edit(player, holder.id);
            return;
        }
        String key;
        String value;
        switch (slot) {
            case 0 -> {
                key = "coin";
                value =
                        next(
                                new ArrayList<>(
                                        Objects.requireNonNull(
                                                        plugin.getConfig()
                                                                .getConfigurationSection("coins"))
                                                .getKeys(false)),
                                board.symbol());
            }
            case 1 -> {
                key = "currency";
                value = next(plugin.getConfig().getStringList("currencies"), board.currency());
            }
            case 2 -> {
                key = "hours";
                value =
                        next(
                                List.of("1", "6", "24", "72", "168"),
                                String.valueOf(board.settings().hours()));
            }
            case 3 -> {
                key = "facing";
                value =
                        next(
                                List.of("NORTH", "EAST", "SOUTH", "WEST"),
                                board.displayFacing().name());
            }
            case 4 -> {
                key = "height";
                value =
                        String.valueOf(
                                board.settings().height() >= 4
                                        ? 0.5
                                        : Math.round((board.settings().height() + 0.5) * 100)
                                                / 100.0);
            }
            case 5 -> {
                key = "theme";
                value = next(List.of("dark", "light", "ocean"), board.settings().theme());
            }
            case 6 -> {
                key = "style";
                value = next(List.of("chart", "price", "compact"), board.settings().style());
            }
            case 7 -> {
                key = "size";
                value = String.valueOf(board.settings().size() % 3 + 1);
            }
            case 8 -> {
                renames.put(
                        player.getUniqueId(),
                        new Rename(board.id(), Instant.now().plusSeconds(60)));
                player.closeInventory();
                player.sendMessage(
                        "§6[CryptoCraft] §f" + plugin.messages().get("menuRenamePrompt"));
                return;
            }
            case 10 -> {
                key = "range";
                value = String.valueOf(!board.settings().showRange());
            }
            case 18 -> {
                open(player, 0);
                return;
            }
            case 22 -> {
                player.closeInventory();
                player.performCommand("crypto tp " + board.id());
                return;
            }
            case 26 -> {
                confirm(player, holder.id);
                return;
            }
            default -> {
                return;
            }
        }
        commands.editBoard(player, board.id(), key, value);
        edit(player, holder.id);
    }

    private String next(List<String> values, String current) {
        return values.get((values.indexOf(current) + 1) % values.size());
    }

    private void requireUse(Player player) {
        if (!player.hasPermission("cryptocraft.use"))
            throw new IllegalArgumentException(plugin.messages().get("permissionUse"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder
                && event.getRawSlots().stream()
                        .anyMatch(slot -> slot < event.getView().getTopInventory().getSize()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void chat(AsyncPlayerChatEvent event) {
        Rename rename = renames.remove(event.getPlayer().getUniqueId());
        if (rename == null || Instant.now().isAfter(rename.expires)) return;
        event.setCancelled(true);
        String text = event.getMessage().strip();
        Bukkit.getScheduler()
                .runTask(
                        plugin,
                        () -> {
                            if (!event.getPlayer().isOnline()) return;
                            try {
                                if (!text.equalsIgnoreCase("cancel"))
                                    commands.editBoard(
                                            event.getPlayer(),
                                            rename.id,
                                            "name",
                                            text.equals("-") ? "" : text);
                                edit(event.getPlayer(), rename.id);
                            } catch (IllegalArgumentException error) {
                                event.getPlayer()
                                        .sendMessage("§6[CryptoCraft] §c" + error.getMessage());
                            }
                        });
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        renames.remove(event.getPlayer().getUniqueId());
    }

    public void closeAll() {
        renames.clear();
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder)
                player.closeInventory();
    }

    private record Rename(String id, Instant expires) {}

    private static final class Holder implements InventoryHolder {
        final String screen;
        final String id;
        final int page;
        final Map<Integer, String> entries = new HashMap<>();
        Inventory inventory;

        Holder(String screen, String id, int page) {
            this.screen = screen;
            this.id = id;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}

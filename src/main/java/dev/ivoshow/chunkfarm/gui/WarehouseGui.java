package dev.ivoshow.chunkfarm.gui;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import dev.ivoshow.chunkfarm.config.Messages;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.util.Text;
import dev.ivoshow.chunkfarm.warehouse.ItemKey;
import dev.ivoshow.chunkfarm.warehouse.Warehouse;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The warehouse, shown as a chest even though it is nothing of the sort.
 *
 * <p>Each slot is one stored item <em>type</em>; the real amount lives in the lore
 * because it routinely exceeds what a stack can express. Withdrawing converts counters
 * back into stacks on the way out, and anything that does not fit in the player's
 * inventory goes straight back into the counter.
 */
public final class WarehouseGui extends FarmGui {

    private static final int CONTENT_SLOTS = 45;

    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_SETTINGS = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_TAKE_ALL = 50;
    private static final int SLOT_NEXT = 53;

    private final Messages messages;
    private int page;
    private List<Warehouse.Entry> visible = List.of();

    public WarehouseGui(ChunkFarmPlugin plugin, ChunkFarm farm) {
        super(plugin, farm, plugin.messages().plain("gui.warehouse.title",
                "chunk", farm.pos().toString()));
        this.messages = plugin.messages();
    }

    @Override
    public void redraw() {
        this.inventory.clear();

        List<Warehouse.Entry> all = this.farm.warehouse().snapshot();
        int pages = Math.max(1, (all.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
        this.page = Math.max(0, Math.min(this.page, pages - 1));

        int from = this.page * CONTENT_SLOTS;
        int to = Math.min(all.size(), from + CONTENT_SLOTS);
        this.visible = all.subList(from, to);

        for (int i = 0; i < this.visible.size(); i++) {
            this.inventory.setItem(i, render(this.visible.get(i)));
        }

        fillBottomRow(Material.BLACK_STAINED_GLASS_PANE);

        if (this.page > 0) {
            this.inventory.setItem(SLOT_PREVIOUS, icon(Material.ARROW,
                    this.messages.item("gui.warehouse.previous"), List.of()));
        }
        if (this.page < pages - 1) {
            this.inventory.setItem(SLOT_NEXT, icon(Material.ARROW,
                    this.messages.item("gui.warehouse.next"), List.of()));
        }

        Warehouse warehouse = this.farm.warehouse();
        this.inventory.setItem(SLOT_INFO, icon(Material.CHEST,
                this.messages.item("gui.warehouse.info.name"),
                this.messages.itemList("gui.warehouse.info.lore",
                        "total", Text.number(warehouse.total()),
                        "types", warehouse.typeCount(),
                        "max_types", warehouse.maxTypes(),
                        "page", this.page + 1,
                        "pages", pages,
                        "harvested", Text.number(this.farm.harvestedTotal()))));

        this.inventory.setItem(SLOT_SETTINGS, icon(Material.COMPARATOR,
                this.messages.item("gui.warehouse.settings.name"),
                this.messages.itemList("gui.warehouse.settings.lore")));

        this.inventory.setItem(SLOT_TAKE_ALL, icon(Material.HOPPER,
                this.messages.item("gui.warehouse.take-all.name"),
                this.messages.itemList("gui.warehouse.take-all.lore")));
    }

    private ItemStack render(Warehouse.Entry entry) {
        ItemKey key = entry.key();

        // The client caps the number badge at the item's stack size, so it can only
        // ever say "64" no matter how much is stored -- a number that reads as the
        // total and is not. Minecraft draws no badge at all for a single item, so by
        // default the slot stays clean and the real total lives in the item's name.
        int perClick = (int) Math.min(key.maxStackSize(), entry.amount());
        int badge = this.plugin.config().guiStackBadge() ? Math.max(1, perClick) : 1;
        ItemStack item = key.toItemStack(badge);

        ItemMeta meta = item.getItemMeta();
        meta.displayName(label(key, entry.amount(), meta));

        List<Component> lore = new ArrayList<>();
        lore.add(this.messages.item("gui.warehouse.entry.amount",
                "amount", Text.number(entry.amount()),
                "short", Text.compact(entry.amount())));
        lore.add(this.messages.item("gui.warehouse.entry.per-click", "amount", perClick));
        lore.addAll(this.messages.itemList("gui.warehouse.entry.actions"));
        meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    /** {@code Trigo  x1.234.567} -- the stored total, always on screen. */
    private static Component label(ItemKey key, long amount, ItemMeta meta) {
        Component name = meta.hasDisplayName()
                ? meta.displayName()
                : Component.translatable(key.material().getItemTranslationKey());

        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(name.colorIfAbsent(NamedTextColor.WHITE))
                .append(Component.text("  \u00d7", NamedTextColor.DARK_GRAY))
                .append(Component.text(Text.number(amount), NamedTextColor.YELLOW));
    }

    @Override
    public void onClick(Player player, InventoryClickEvent event) {
        int slot = event.getRawSlot();

        if (slot == SLOT_PREVIOUS) {
            this.page--;
            redraw();
            return;
        }
        if (slot == SLOT_NEXT) {
            this.page++;
            redraw();
            return;
        }
        if (slot == SLOT_SETTINGS) {
            if (!this.plugin.farmService().canConfigure(player, this.farm)) {
                player.sendMessage(this.messages.chat("farm.need-role",
                        "role", this.messages.raw("role.manager")));
                return;
            }
            new SettingsGui(this.plugin, this.farm).openLater(player);
            return;
        }
        if (slot == SLOT_TAKE_ALL) {
            if (!requireWithdraw(player)) {
                return;
            }
            takeAll(player);
            redraw();
            return;
        }
        if (slot < 0 || slot >= CONTENT_SLOTS || slot >= this.visible.size()) {
            return;
        }
        if (!requireWithdraw(player)) {
            return;
        }

        ItemKey key = this.visible.get(slot).key();
        ClickType click = event.getClick();

        long requested;
        if (click.isShiftClick()) {
            requested = freeSpaceFor(player, key);
        } else if (click.isRightClick()) {
            requested = 1;
        } else {
            requested = key.maxStackSize();
        }

        withdraw(player, key, requested);
        redraw();
    }

    /** A member with only ACCESS can look but not take. */
    private boolean requireWithdraw(Player player) {
        if (this.plugin.farmService().canWithdraw(player, this.farm)) {
            return true;
        }
        player.sendMessage(this.messages.chat("farm.need-role",
                "role", this.messages.raw("role.withdraw")));
        return false;
    }

    /** Pulls one stack of every stored type until the player runs out of room. */
    private void takeAll(Player player) {
        for (Warehouse.Entry entry : this.farm.warehouse().snapshot()) {
            if (freeSpaceFor(player, entry.key()) <= 0) {
                continue;
            }
            withdraw(player, entry.key(), entry.key().maxStackSize());
        }
    }

    /**
     * Takes {@code requested} out of the counter, hands the player as many stacks as
     * fit, and returns the remainder to the counter -- so a full inventory can never
     * delete items.
     */
    private void withdraw(Player player, ItemKey key, long requested) {
        if (requested <= 0) {
            return;
        }
        long before = this.farm.warehouse().get(key);
        long taken = this.farm.warehouse().remove(key, requested);
        if (taken <= 0) {
            return;
        }

        int stackSize = Math.max(1, key.maxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        long remaining = taken;
        while (remaining > 0) {
            int size = (int) Math.min(stackSize, remaining);
            stacks.add(key.toItemStack(size));
            remaining -= size;
        }

        Map<Integer, ItemStack> rejected = player.getInventory().addItem(stacks.toArray(new ItemStack[0]));
        long returned = 0;
        for (ItemStack leftover : rejected.values()) {
            returned += leftover.getAmount();
        }
        if (returned > 0) {
            long restored = this.farm.warehouse().add(key, returned);
            if (restored < returned) {
                // The warehouse refused part of what came back -- a per-type cap that
                // shrank on reload, say. Put the difference on the floor rather than
                // let it evaporate.
                spill(player, key, returned - restored);
            }
        }
        this.farm.markDirty();
        this.plugin.withdrawalLog().record(player, this.farm, key, taken - returned, before);
        this.plugin.requestSave();
    }

    /** Last resort so a refused return is never silently destroyed. */
    private void spill(Player player, ItemKey key, long amount) {
        int stackSize = Math.max(1, key.maxStackSize());
        long remaining = amount;
        while (remaining > 0) {
            int size = (int) Math.min(stackSize, remaining);
            player.getWorld().dropItemNaturally(player.getLocation(), key.toItemStack(size));
            remaining -= size;
        }
    }

    /** How many of {@code key} the player's main inventory could still take. */
    private static long freeSpaceFor(Player player, ItemKey key) {
        PlayerInventory inventory = player.getInventory();
        ItemStack sample = key.toItemStack(1);
        int stackSize = Math.max(1, sample.getMaxStackSize());

        long space = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType().isAir()) {
                space += stackSize;
            } else if (current.isSimilar(sample)) {
                space += Math.max(0, stackSize - current.getAmount());
            }
        }
        return space;
    }
}

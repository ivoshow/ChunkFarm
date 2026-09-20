package dev.ivoshow.chunkfarm.gui;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Base for the plugin's menus. Being the inventory's holder is what lets the click
 * listener tell our menus apart from every other open chest on the server.
 */
public abstract class FarmGui implements InventoryHolder {

    protected static final int ROWS = 6;
    protected static final int SIZE = ROWS * 9;

    protected final ChunkFarmPlugin plugin;
    protected final ChunkFarm farm;
    protected final Inventory inventory;

    protected FarmGui(ChunkFarmPlugin plugin, ChunkFarm farm, Component title) {
        this.plugin = plugin;
        this.farm = farm;
        this.inventory = plugin.getServer().createInventory(this, SIZE, title);
    }

    @Override
    public final Inventory getInventory() {
        return this.inventory;
    }

    public final ChunkFarm farm() {
        return this.farm;
    }

    public final void open(Player player) {
        redraw();
        player.openInventory(this.inventory);
    }

    /**
     * Opens on the next tick. Swapping inventories from inside an
     * {@code InventoryClickEvent} handler is not supported by Bukkit -- the client and
     * the server disagree about what is open -- so menu-to-menu navigation defers.
     */
    public final void openLater(Player player) {
        this.plugin.getServer().getGlobalRegionScheduler()
                .execute(this.plugin, () -> open(player));
    }

    /** Rebuilds every slot from the farm's current state. */
    public abstract void redraw();

    /** The event is already cancelled when this runs. */
    public abstract void onClick(Player player, InventoryClickEvent event);

    protected static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    protected static ItemStack icon(Material material, String miniMessageName, List<Component> lore) {
        return icon(material, Text.item(miniMessageName), lore);
    }

    protected void fillBottomRow(Material filler) {
        ItemStack pane = icon(filler, Component.empty(), List.of());
        for (int slot = SIZE - 9; slot < SIZE; slot++) {
            this.inventory.setItem(slot, pane);
        }
    }
}

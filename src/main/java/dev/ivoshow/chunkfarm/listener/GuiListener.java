package dev.ivoshow.chunkfarm.listener;

import dev.ivoshow.chunkfarm.gui.FarmGui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Routes clicks to whichever {@link FarmGui} owns the inventory. Every click inside our
 * menus is cancelled first: the slots are a rendering of counters, not real items, so
 * letting vanilla move them would create duplicates out of thin air.
 */
public final class GuiListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof FarmGui gui)) {
            return;
        }
        // Shift-clicking from the player's own inventory would push items into the
        // fake slots, so it is blocked wherever the click landed.
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            gui.onClick(player, event);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof FarmGui) {
            event.setCancelled(true);
        }
    }
}

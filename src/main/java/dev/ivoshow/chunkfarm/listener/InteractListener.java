package dev.ivoshow.chunkfarm.listener;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.FarmService;
import dev.ivoshow.chunkfarm.gui.WarehouseGui;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Right-clicking a controller opens its warehouse. */
public final class InteractListener implements Listener {

    private final ChunkFarmPlugin plugin;

    public InteractListener(ChunkFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        ChunkFarm farm = this.plugin.farms().controllerAt(block);
        if (farm == null) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!this.plugin.farmService().canOpen(player, farm)) {
            player.sendMessage(this.plugin.messages().chat("farm.not-owner", "owner", farm.ownerName()));
            return;
        }
        new WarehouseGui(this.plugin, farm).open(player);
    }
}

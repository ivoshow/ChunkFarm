package dev.ivoshow.chunkfarm.listener;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.FarmService;
import dev.ivoshow.chunkfarm.util.Text;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;

/** Placing, breaking and protecting controllers, plus keeping the scan cache honest. */
public final class BlockListener implements Listener {

    private final ChunkFarmPlugin plugin;

    public BlockListener(ChunkFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();

        if (!this.plugin.controllerItem().isController(event.getItemInHand())) {
            invalidateIfCrop(block);
            return;
        }

        Player player = event.getPlayer();
        FarmService service = this.plugin.farmService();

        FarmService.Placement verdict = service.canPlace(player, block);
        if (verdict != FarmService.Placement.OK) {
            event.setCancelled(true);
            player.sendMessage(switch (verdict) {
                case WORLD_BLOCKED -> this.plugin.messages().chat("farm.place.world-blocked");
                case NO_PERMISSION -> this.plugin.messages().chat("farm.place.no-permission");
                case LIMIT_REACHED -> this.plugin.messages().chat("farm.place.limit",
                        "limit", this.plugin.config().maxFarmsPerPlayer());
                case PROTECTED -> this.plugin.messages().chat("farm.place.protected");
                case CHUNK_TAKEN -> this.plugin.messages().chat("farm.place.chunk-taken");
                case OK -> this.plugin.messages().chat("farm.place.created");
            });
            return;
        }

        FarmService.Created created = service.place(player, block);
        ChunkFarm farm = created.farm();
        player.sendMessage(created.reclaimed()
                ? this.plugin.messages().chat("farm.place.reclaimed",
                        "total", Text.number(farm.warehouse().total()))
                : this.plugin.messages().chat("farm.place.created"));
        player.sendMessage(this.plugin.messages().chat("farm.place.bounds",
                "min", farm.settings().minY(), "max", farm.settings().maxY()));
        this.plugin.applyChunkTickets();
        this.plugin.requestSave();
    }

    /**
     * Gate only. Refuses the break and gets out of the way -- the farm is not touched
     * here, because a plugin running later could still cancel this event.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBreakCheck(BlockBreakEvent event) {
        ChunkFarm farm = this.plugin.farms().controllerAt(event.getBlock());
        if (farm == null) {
            invalidateIfCrop(event.getBlock());
            return;
        }
        Player player = event.getPlayer();
        if (!this.plugin.farmService().canConfigure(player, farm)) {
            event.setCancelled(true);
            player.sendMessage(this.plugin.messages().chat("farm.not-owner", "owner", farm.ownerName()));
        }
    }

    /**
     * The block really is coming out now, so retire the farm and hand the controller
     * back.
     *
     * <p>At MONITOR because everything here is irreversible: doing it at NORMAL meant a
     * protection plugin cancelling the break afterwards would leave the controller
     * standing while the farm had already gone dormant and paid out an item.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakApply(BlockBreakEvent event) {
        Block block = event.getBlock();
        ChunkFarm farm = this.plugin.farms().controllerAt(block);
        if (farm == null) {
            return;
        }

        Player player = event.getPlayer();
        event.setDropItems(false);
        this.plugin.farmService().returnController(player, block.getLocation());

        long stored = farm.warehouse().total();
        this.plugin.farmService().retire(farm);
        this.plugin.applyChunkTickets();
        this.plugin.requestSave();

        player.sendMessage(stored == 0
                ? this.plugin.messages().chat("farm.break.removed")
                : this.plugin.messages().chat("farm.break.dormant",
                        "total", Text.number(stored),
                        "days", this.plugin.config().orphanRetentionDays()));
    }

    /** A creeper must not be able to delete somebody's stored millions. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protect(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protect(event.blockList());
    }

    private void protect(List<Block> blocks) {
        blocks.removeIf(block -> this.plugin.farms().controllerAt(block) != null);
    }

    /**
     * A crop added or removed by hand inside a farm's layers makes the cached positions
     * wrong, so the farm rescans next cycle instead of waiting out the refresh timer.
     */
    private void invalidateIfCrop(Block block) {
        if (this.plugin.cropRegistry().lookup(block.getType()) == null) {
            return;
        }
        ChunkFarm farm = this.plugin.farms().at(block);
        if (farm == null) {
            return;
        }
        int y = block.getY();
        if (y >= farm.settings().minY() && y <= farm.settings().maxY()) {
            farm.invalidateCache();
        }
    }
}

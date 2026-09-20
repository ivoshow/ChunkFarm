package dev.ivoshow.chunkfarm.crop;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;

/**
 * Melons and pumpkins. The stem regrows on its own, so there is nothing to replant --
 * the fruit is simply removed.
 *
 * <p>Only a fruit that an attached stem is actually pointing at is harvested, so the
 * decorative pumpkins somebody put on their roof inside the farm chunk are left alone.
 */
public final class StemFruitHandler implements CropHandler {

    private static final BlockFace[] HORIZONTAL = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
    };

    private final Material attachedStem;

    public StemFruitHandler(Material attachedStem) {
        this.attachedStem = attachedStem;
    }

    @Override
    public boolean isReady(Block block, BlockData data) {
        for (BlockFace face : HORIZONTAL) {
            Block neighbour = block.getRelative(face);
            if (neighbour.getType() != this.attachedStem) {
                continue;
            }
            if (neighbour.getBlockData() instanceof Directional directional
                    && directional.getFacing() == face.getOppositeFace()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void harvest(Block block, BlockData data, HarvestSink sink) {
        ItemStack tool = sink.harvestTool();
        Collection<ItemStack> drops = tool == null ? block.getDrops() : block.getDrops(tool);
        for (ItemStack drop : drops) {
            if (drop != null && drop.getAmount() > 0) {
                sink.deposit(drop);
            }
        }
        block.setType(Material.AIR, false);
    }
}

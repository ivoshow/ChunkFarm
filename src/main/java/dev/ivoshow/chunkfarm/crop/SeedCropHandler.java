package dev.ivoshow.chunkfarm.crop;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;

/**
 * Crops that grow in place through {@link Ageable} stages and are replanted by putting
 * their own seed back in the ground: wheat, carrots, potatoes, beetroot, nether wart
 * and cocoa.
 *
 * <p>The replant is paid for out of the harvest itself -- one seed is taken off the drop
 * list before anything reaches the warehouse -- so the farm is seed-neutral rather than
 * a seed printer.
 */
public final class SeedCropHandler implements CropHandler {

    private final Material seed;

    public SeedCropHandler(Material seed) {
        this.seed = seed;
    }

    @Override
    public boolean isReady(Block block, BlockData data) {
        return data instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge();
    }

    @Override
    public void harvest(Block block, BlockData data, HarvestSink sink) {
        ItemStack tool = sink.harvestTool();
        Collection<ItemStack> drops = tool == null ? block.getDrops() : block.getDrops(tool);

        boolean replant = sink.autoReplant();
        if (replant && !takeSeedFrom(drops) && !sink.payForSeed(this.seed)) {
            replant = false;
        }

        for (ItemStack drop : drops) {
            if (drop != null && drop.getAmount() > 0) {
                sink.deposit(drop);
            }
        }

        if (replant) {
            Ageable ageable = (Ageable) data;
            ageable.setAge(0);
            // applyPhysics=false: the crop stays on the same farmland, and skipping the
            // neighbour update is what makes a chunk-wide harvest cheap.
            block.setBlockData(ageable, false);
        } else {
            block.setType(Material.AIR, false);
        }
    }

    /** Removes one seed from the drop list. @return true if a seed was there to take */
    private boolean takeSeedFrom(Collection<ItemStack> drops) {
        for (ItemStack drop : drops) {
            if (drop != null && drop.getType() == this.seed && drop.getAmount() > 0) {
                drop.setAmount(drop.getAmount() - 1);
                return true;
            }
        }
        return false;
    }
}

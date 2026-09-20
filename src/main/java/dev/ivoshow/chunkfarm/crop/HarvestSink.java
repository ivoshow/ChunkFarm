package dev.ivoshow.chunkfarm.crop;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * What a {@link CropHandler} is allowed to do with what it harvests. Handlers never
 * touch the warehouse directly: they buffer drops here and the harvester flushes them,
 * which keeps the overflow policy in one place.
 */
public interface HarvestSink {

    /** Buffers a drop. The stack is consumed; do not reuse it afterwards. */
    void deposit(ItemStack stack);

    /** Whether this farm replants instead of clearing the block. */
    boolean autoReplant();

    /**
     * Pays for a replant out of the warehouse when the crop's own drop roll produced no
     * seed (wheat can drop zero seeds).
     *
     * @return true when the seed was paid for, or when the farm is configured to replant
     *         for free rather than stall
     */
    boolean payForSeed(Material seed);

    /** The tool used for loot rolls -- carries the configured Fortune level, or null. */
    ItemStack harvestTool();

    int randomInt(int boundExclusive);
}

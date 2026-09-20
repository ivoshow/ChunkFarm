package dev.ivoshow.chunkfarm.crop;

import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Knows how to recognise one shape of crop and how to harvest it.
 *
 * <p>Both methods run on the region thread that owns the block, so they may read and
 * write the world directly.
 */
public interface CropHandler {

    /**
     * @param data the block's current data, read once by the caller so handlers do not
     *             each pay for {@link Block#getBlockData()}
     */
    boolean isReady(Block block, BlockData data);

    /** Mutates the world and hands every drop to {@code sink}. Only called when {@link #isReady} passed. */
    void harvest(Block block, BlockData data, HarvestSink sink);
}

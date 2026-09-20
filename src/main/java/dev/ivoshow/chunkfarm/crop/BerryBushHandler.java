package dev.ivoshow.chunkfarm.crop;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/**
 * Sweet berry bushes are picked, not broken: the bush drops back to age 1 and keeps
 * growing, exactly like a player right-clicking it. No seed is consumed.
 */
public final class BerryBushHandler implements CropHandler {

    @Override
    public boolean isReady(Block block, BlockData data) {
        return data instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge();
    }

    @Override
    public void harvest(Block block, BlockData data, HarvestSink sink) {
        sink.deposit(new ItemStack(Material.SWEET_BERRIES, 2 + sink.randomInt(2)));

        Ageable ageable = (Ageable) data;
        ageable.setAge(1);
        block.setBlockData(ageable, false);
    }
}

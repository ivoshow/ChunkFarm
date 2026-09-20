package dev.ivoshow.chunkfarm.crop;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.CaveVinesPlant;
import org.bukkit.inventory.ItemStack;

/**
 * Glow berries: the vine keeps hanging, only the berries are taken.
 */
public final class CaveVinesHandler implements CropHandler {

    @Override
    public boolean isReady(Block block, BlockData data) {
        return data instanceof CaveVinesPlant vines && vines.hasBerries();
    }

    @Override
    public void harvest(Block block, BlockData data, HarvestSink sink) {
        sink.deposit(new ItemStack(Material.GLOW_BERRIES, 1));

        CaveVinesPlant vines = (CaveVinesPlant) data;
        vines.setBerries(false);
        block.setBlockData(vines, false);
    }
}

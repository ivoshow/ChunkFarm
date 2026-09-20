package dev.ivoshow.chunkfarm.crop;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Set;

/**
 * Crops that grow as a vertical column and regrow from what is left standing: sugar
 * cane, cactus, bamboo and kelp.
 *
 * <p>There is no seed to put back -- the base block <em>is</em> the seed -- so the
 * handler cuts everything above {@code keepBlocks} and leaves the stub to regrow.
 */
public final class ColumnCropHandler implements CropHandler {

    /** Guards against a runaway walk if the world hands back something unexpected. */
    private static final int MAX_COLUMN = 64;

    private final Set<Material> family;
    private final Material replacement;
    private final @Nullable Material tip;
    private final int keepBlocks;

    /**
     * @param family      every material the column can be made of (kelp is two: the body
     *                    and the growing tip)
     * @param replacement what a cut segment becomes -- air on land, water for kelp
     * @param tip         the material the topmost surviving block must end up as for the
     *                    column to keep growing, or null when the family has no distinct
     *                    tip block
     * @param keepBlocks  how many blocks stay standing at the bottom so the crop regrows
     */
    public ColumnCropHandler(Set<Material> family, Material replacement,
                             @Nullable Material tip, int keepBlocks) {
        this.family = family;
        this.replacement = replacement;
        this.tip = tip;
        this.keepBlocks = Math.max(1, keepBlocks);
    }

    @Override
    public boolean isReady(Block block, BlockData data) {
        // Only the bottom of a column does the work; the segments above it are in the
        // scan cache too and fall out here for the price of one block read.
        if (this.family.contains(block.getRelative(0, -1, 0).getType())) {
            return false;
        }
        return this.family.contains(block.getRelative(0, this.keepBlocks, 0).getType());
    }

    @Override
    public void harvest(Block block, BlockData data, HarvestSink sink) {
        ItemStack tool = sink.harvestTool();

        int height = 1;
        while (height < MAX_COLUMN && this.family.contains(block.getRelative(0, height, 0).getType())) {
            height++;
        }

        // Top down, so a segment is never left floating for a tick.
        for (int offset = height - 1; offset >= this.keepBlocks; offset--) {
            Block segment = block.getRelative(0, offset, 0);
            Collection<ItemStack> drops = tool == null ? segment.getDrops() : segment.getDrops(tool);
            for (ItemStack drop : drops) {
                if (drop != null && drop.getAmount() > 0) {
                    sink.deposit(drop);
                }
            }
            segment.setType(this.replacement, false);
        }

        // Kelp only grows from its tip block. Cutting with applyPhysics=false means the
        // server never converts the new top from body to tip, so the column would be
        // harvested exactly once and then sit there dead.
        if (this.tip != null) {
            Block top = block.getRelative(0, this.keepBlocks - 1, 0);
            if (this.family.contains(top.getType()) && top.getType() != this.tip) {
                top.setType(this.tip, false);
            }
        }
    }
}

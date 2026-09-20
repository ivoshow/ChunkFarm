package dev.ivoshow.chunkfarm.harvest;

import dev.ivoshow.chunkfarm.crop.HarvestSink;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.DeliveryMode;
import dev.ivoshow.chunkfarm.farm.OverflowPolicy;
import dev.ivoshow.chunkfarm.warehouse.ItemKey;
import dev.ivoshow.chunkfarm.warehouse.Warehouse;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Collects one block's drops, then moves them into the warehouse in a single step.
 *
 * <p>Buffering matters because a handler mutates the world before the caller knows
 * whether everything fit; keeping the deposit in one place means the overflow policy is
 * decided once, not scattered across the handlers.
 *
 * <p>Reused across every block of a cycle, so a harvest costs no allocations beyond the
 * drops themselves.
 */
public final class HarvestSession implements HarvestSink {

    /** Ceiling on item entities spawned per block, so a full farm cannot flood a chunk. */
    private static final int MAX_OVERFLOW_STACKS = 8;

    private final List<ItemStack> pending = new ArrayList<>(6);

    private ChunkFarm farm;
    private World world;
    private @Nullable ItemStack tool;
    private boolean allowFreeSeed;

    public void configure(@Nullable ItemStack tool, boolean allowFreeSeed) {
        this.tool = tool;
        this.allowFreeSeed = allowFreeSeed;
    }

    public void begin(ChunkFarm farm, World world) {
        this.farm = farm;
        this.world = world;
        this.pending.clear();
    }

    @Override
    public void deposit(ItemStack stack) {
        this.pending.add(stack);
    }

    @Override
    public boolean autoReplant() {
        return this.farm.settings().autoReplant();
    }

    @Override
    public boolean payForSeed(Material seed) {
        if (this.farm.warehouse().remove(ItemKey.of(seed), 1) == 1) {
            return true;
        }
        // Nothing in store: either replant for free or leave the block unplanted.
        return this.allowFreeSeed;
    }

    @Override
    public @Nullable ItemStack harvestTool() {
        return this.tool;
    }

    @Override
    public int randomInt(int boundExclusive) {
        return ThreadLocalRandom.current().nextInt(boundExclusive);
    }

    /**
     * Hands the buffered drops to wherever this farm sends its yield.
     *
     * @param source the block that was just harvested -- where the items land in
     *               {@link DeliveryMode#DROP}
     * @return how many items went into the warehouse (zero when dropping)
     */
    public long commit(Block source) {
        if (this.pending.isEmpty()) {
            return 0;
        }
        if (this.farm.settings().delivery() == DeliveryMode.DROP) {
            return dropOnGround(source);
        }

        Warehouse warehouse = this.farm.warehouse();
        long stored = 0;
        for (ItemStack stack : this.pending) {
            ItemKey key = ItemKey.of(stack);
            long accepted = warehouse.add(key, stack.getAmount());
            stored += accepted;

            long leftover = stack.getAmount() - accepted;
            if (leftover > 0) {
                spill(key, leftover);
            }
        }
        this.pending.clear();

        if (stored > 0) {
            this.farm.addHarvested(stored);
            this.farm.markDirty();
        }
        return stored;
    }

    /**
     * Spawns the yield where the crop stood, so the usual vanilla collection tricks
     * work. The count still goes on the farm's running total.
     */
    private long dropOnGround(Block source) {
        Location at = source.getLocation().add(0.5, 0.25, 0.5);
        long dropped = 0;
        for (ItemStack stack : this.pending) {
            dropped += stack.getAmount();
            this.world.dropItem(at, stack);
        }
        this.pending.clear();

        if (dropped > 0) {
            this.farm.addHarvested(dropped);
            this.farm.markDirty();
        }
        return 0;
    }

    /**
     * Handles what the warehouse refused. {@link OverflowPolicy#VOID} throws it away;
     * the other two drop it by the controller so the loss is visible -- under
     * {@code PAUSE} the farm also stops on the next cycle, once the warehouse reports
     * itself full.
     */
    private void spill(ItemKey key, long amount) {
        if (this.farm.settings().overflow() == OverflowPolicy.VOID) {
            return;
        }
        Location drop = new Location(this.world,
                this.farm.blockX() + 0.5, this.farm.blockY() + 1.0, this.farm.blockZ() + 0.5);

        int stackSize = Math.max(1, key.maxStackSize());
        long remaining = amount;
        for (int i = 0; i < MAX_OVERFLOW_STACKS && remaining > 0; i++) {
            int size = (int) Math.min(stackSize, remaining);
            this.world.dropItem(drop, key.toItemStack(size));
            remaining -= size;
        }
    }
}

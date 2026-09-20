package dev.ivoshow.chunkfarm.harvest;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import dev.ivoshow.chunkfarm.config.PluginConfig;
import dev.ivoshow.chunkfarm.crop.CropRegistry;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.ChunkReach;
import dev.ivoshow.chunkfarm.farm.FarmManager;
import dev.ivoshow.chunkfarm.farm.FarmSettings;
import dev.ivoshow.chunkfarm.farm.OverflowPolicy;
import dev.ivoshow.chunkfarm.util.BlockPos;
import dev.ivoshow.chunkfarm.util.ChunkPos;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The engine. Runs on a fixed interval and spends a bounded block budget per cycle,
 * split across a round-robin slice of the farms.
 *
 * <p>Two things keep this affordable on a server producing millions of items a day:
 *
 * <ul>
 *   <li>crop positions are scanned once every {@code cache-refresh-seconds} and the
 *       scan itself is resumable, so a deep farm never scans a whole chunk in one tick;
 *   <li>a harvest writes a counter in the farm's warehouse instead of spawning item
 *       entities, so yield costs nothing in entity ticking or hopper traffic.
 * </ul>
 */
public final class HarvestTask implements Consumer<ScheduledTask> {

    /** Rough cost of harvesting one block versus merely looking at it. */
    private static final int HARVEST_WEIGHT = 8;
    /** Blocks examined per scanned layer (16 x 16). */
    private static final int LAYER_COST = 256;

    private final ChunkFarmPlugin plugin;
    private final FarmManager farms;
    private final HarvestSession session = new HarvestSession();

    private PluginConfig config;
    private CropRegistry registry;

    private long cyclesRun;
    private long blocksHarvested;

    // ---- cost metrics, surfaced by /cf stats -------------------------------------
    private long totalNanos;
    private long lastCycleNanos;
    private long peakCycleNanos;
    private int lastFarmsVisited;
    private int lastFarmsSkipped;

    public HarvestTask(ChunkFarmPlugin plugin, FarmManager farms) {
        this.plugin = plugin;
        this.farms = farms;
    }

    /** Applied on startup and on every {@code /chunkfarm reload}. */
    public void refresh(PluginConfig config, CropRegistry registry) {
        this.config = config;
        this.registry = registry;
        this.session.configure(buildTool(config.fortuneLevel()), config.allowFreeSeed());
    }

    private static @Nullable ItemStack buildTool(int fortuneLevel) {
        if (fortuneLevel <= 0) {
            return null;
        }
        ItemStack tool = new ItemStack(Material.DIAMOND_HOE);
        tool.addUnsafeEnchantment(Enchantment.FORTUNE, fortuneLevel);
        return tool;
    }

    @Override
    public void accept(ScheduledTask task) {
        run();
    }

    public void run() {
        this.cyclesRun++;
        long started = System.nanoTime();
        long now = System.currentTimeMillis();

        int budget = this.config.blocksPerCycle();
        int perFarmCap = this.config.blocksPerFarmCycle();
        int visited = 0;
        int skipped = 0;

        for (ChunkFarm farm : this.farms.nextBatch(this.config.farmsPerCycle())) {
            if (budget <= 0) {
                break;
            }
            try {
                int spent = process(farm, now, Math.min(budget, perFarmCap));
                budget -= spent;
                if (spent > 0) {
                    visited++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException ex) {
                this.plugin.getLogger().log(Level.SEVERE,
                        "Erro ao processar a fazenda em " + farm.pos(), ex);
                // Do not let one broken farm take the whole cycle down.
                farm.settings().setActive(false);
                farm.markDirty();
            }
        }

        this.lastCycleNanos = System.nanoTime() - started;
        this.totalNanos += this.lastCycleNanos;
        this.peakCycleNanos = Math.max(this.peakCycleNanos, this.lastCycleNanos);
        this.lastFarmsVisited = visited;
        this.lastFarmsSkipped = skipped;
    }

    /** @return the budget consumed */
    private int process(ChunkFarm farm, long now, int budget) {
        if (!farm.isRunnable()) {
            return 0;
        }
        World world = farm.world();
        if (world == null) {
            return 0;
        }
        ChunkPos pos = farm.pos();
        if (!withinReach(world, pos)) {
            return 0;
        }

        Chunk chunk = world.getChunkAt(pos.x(), pos.z());

        if (farm.needsRescan(now, this.config.cacheRefreshMillis())) {
            return scan(farm, world, chunk, now, budget);
        }
        return harvest(farm, world, chunk, budget);
    }

    /**
     * Whether this farm is close enough to anything to be worth working on.
     *
     * <p>Every mode still requires the chunk to be in memory -- the plugin never loads
     * a chunk just to harvest it, except in {@link ChunkReach#ALWAYS}, where it holds a
     * ticket so the chunk is loaded anyway.
     */
    private boolean withinReach(World world, ChunkPos pos) {
        if (!world.isChunkLoaded(pos.x(), pos.z())) {
            return false;
        }
        if (this.config.chunkReach() != ChunkReach.PLAYER_RADIUS) {
            return true;
        }

        int radius = this.config.playerRadiusChunks();
        for (Player player : world.getPlayers()) {
            int dx = Math.abs((player.getLocation().getBlockX() >> 4) - pos.x());
            int dz = Math.abs((player.getLocation().getBlockZ() >> 4) - pos.z());
            if (dx <= radius && dz <= radius) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the farm's Y range one layer at a time, remembering where it stopped so the
     * next cycle continues instead of starting over.
     *
     * <p>Reads through a {@link ChunkSnapshot} rather than {@code chunk.getBlock}: the
     * snapshot hands back a {@link Material} with no object allocated per block, and it
     * knows which 16-block sections are pure air. A farm configured for the full world
     * height is mostly empty sky, and those sections cost nothing here.
     */
    private int scan(ChunkFarm farm, World world, Chunk chunk, long now, int budget) {
        // A rescan is also when we notice the controller was removed by something that
        // does not fire block events (world edits, other plugins).
        if (chunk.getBlock(farm.blockX() & 0xF, farm.blockY(), farm.blockZ() & 0xF).getType()
                != this.config.controllerMaterial()) {
            this.plugin.farmService().makeDormant(farm);
            return LAYER_COST;
        }

        FarmSettings settings = farm.settings();
        int worldMin = world.getMinHeight();
        int minY = Math.max(worldMin, settings.minY());
        int maxY = Math.min(world.getMaxHeight() - 1, settings.maxY());

        int y = Math.max(farm.beginScan(), minY);
        int used = 0;

        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);

        while (y <= maxY && used + LAYER_COST <= budget) {
            if (isSectionEmpty(snapshot, worldMin, y)) {
                // Jump to the first layer of the next section instead of walking 16
                // layers of air. Costs one budget unit so the loop still terminates.
                y = nextSectionStart(worldMin, y);
                used++;
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    CropRegistry.Binding binding = this.registry.lookup(snapshot.getBlockType(x, y, z));
                    if (binding != null && settings.isEnabled(binding.type())) {
                        farm.recordScanHit(BlockPos.pack(x, y, z));
                    }
                }
            }
            used += LAYER_COST;
            y++;
        }

        farm.setScanY(y);
        if (y > maxY) {
            farm.finishScan(now);
        }
        // Always report at least one layer so a zero-height range cannot spin.
        return Math.max(used, LAYER_COST);
    }

    private static boolean isSectionEmpty(ChunkSnapshot snapshot, int worldMin, int y) {
        int section = (y - worldMin) >> 4;
        try {
            return snapshot.isSectionEmpty(section);
        } catch (IndexOutOfBoundsException ex) {
            // Out of range means we cannot prove it is empty; scan it.
            return false;
        }
    }

    /** First Y of the section above the one containing {@code y}. */
    private static int nextSectionStart(int worldMin, int y) {
        int section = (y - worldMin) >> 4;
        return worldMin + ((section + 1) << 4);
    }

    private int harvest(ChunkFarm farm, World world, Chunk chunk, int budget) {
        LongArrayList crops = farm.cachedCrops();
        int size = crops.size();
        if (size == 0) {
            return 0;
        }

        FarmSettings settings = farm.settings();
        // Only a farm that actually stores things can be stopped by a full warehouse.
        if (settings.usesWarehouse()
                && settings.overflow() == OverflowPolicy.PAUSE
                && farm.warehouse().isFull()) {
            return 0;
        }

        this.session.begin(farm, world);

        int cursor = farm.harvestCursor();
        int visited = 0;
        int used = 0;

        while (visited < size && used < budget) {
            if (cursor >= size) {
                cursor = 0;
            }
            long packed = crops.getLong(cursor);
            cursor++;
            visited++;
            used++;

            Block block = chunk.getBlock(BlockPos.localX(packed), BlockPos.y(packed), BlockPos.localZ(packed));
            CropRegistry.Binding binding = this.registry.lookup(block.getType());
            if (binding == null || !settings.isEnabled(binding.type())) {
                // Stale cache entry -- the next rescan drops it.
                continue;
            }

            BlockData data = block.getBlockData();
            if (!binding.handler().isReady(block, data)) {
                continue;
            }

            binding.handler().harvest(block, data, this.session);
            this.session.commit(block);
            this.blocksHarvested++;
            used += HARVEST_WEIGHT;
        }

        farm.setHarvestCursor(cursor);
        return used;
    }

    public long cyclesRun() {
        return this.cyclesRun;
    }

    public long blocksHarvested() {
        return this.blocksHarvested;
    }

    /** Milliseconds the last cycle spent on the server thread. */
    public double lastCycleMillis() {
        return this.lastCycleNanos / 1_000_000.0D;
    }

    public double averageCycleMillis() {
        return this.cyclesRun == 0 ? 0 : (this.totalNanos / (double) this.cyclesRun) / 1_000_000.0D;
    }

    public double peakCycleMillis() {
        return this.peakCycleNanos / 1_000_000.0D;
    }

    /** Farms that actually did work on the last cycle. */
    public int lastFarmsVisited() {
        return this.lastFarmsVisited;
    }

    /** Farms skipped on the last cycle -- out of reach, paused or nothing ripe. */
    public int lastFarmsSkipped() {
        return this.lastFarmsSkipped;
    }

    public void resetMetrics() {
        this.totalNanos = 0;
        this.peakCycleNanos = 0;
        this.cyclesRun = 0;
        this.blocksHarvested = 0;
    }

    /** Runs a full immediate pass over one farm, ignoring budgets. Used by commands. */
    public void forceOnce(ChunkFarm farm) {
        World world = farm.world();
        if (world == null || !world.isChunkLoaded(farm.pos().x(), farm.pos().z())) {
            return;
        }
        farm.invalidateCache();
        Chunk chunk = world.getChunkAt(farm.pos().x(), farm.pos().z());
        long now = System.currentTimeMillis();
        // Bounded: scan() can bail out early (a missing controller), and this must not spin.
        for (int attempt = 0; attempt < 4 && farm.needsRescan(now, this.config.cacheRefreshMillis()); attempt++) {
            scan(farm, world, chunk, now, Integer.MAX_VALUE / 2);
        }
        if (farm.isRunnable()) {
            harvest(farm, world, chunk, Integer.MAX_VALUE / 2);
        }
    }
}

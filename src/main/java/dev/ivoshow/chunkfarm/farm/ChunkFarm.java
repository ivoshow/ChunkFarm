package dev.ivoshow.chunkfarm.farm;

import dev.ivoshow.chunkfarm.util.ChunkPos;
import dev.ivoshow.chunkfarm.warehouse.Warehouse;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One chunk's worth of automated farming: the controller block that owns it, what it is
 * allowed to harvest, and the warehouse the yield goes into.
 *
 * <p>Also carries the scan state. Finding crops is the expensive half of the job, so the
 * positions are cached in a primitive array and the rescan is resumable -- the harvest
 * task hands the farm a block budget each cycle and the farm picks up where it stopped.
 */
public final class ChunkFarm {

    private final ChunkPos pos;
    private final int blockX;
    private final int blockY;
    private final int blockZ;

    private UUID owner;
    private String ownerName;

    private final FarmSettings settings;
    private final Warehouse warehouse;

    /**
     * Players the owner has trusted, and at what level. Kept ordered so the members
     * menu does not reshuffle between openings. Names are cached alongside so the list
     * is readable for players who have never joined this session.
     */
    private final Map<UUID, FarmRole> members = new LinkedHashMap<>();
    private final Map<UUID, String> memberNames = new LinkedHashMap<>();

    // ---- persistence bookkeeping -------------------------------------------------
    /**
     * Bumped on every change worth saving. Compared against {@link #savedRevision}
     * instead of a boolean flag, because a boolean cleared after a save would also
     * swallow a change that landed while that save was being written.
     */
    private final java.util.concurrent.atomic.AtomicLong revision =
            new java.util.concurrent.atomic.AtomicLong(1);
    private volatile long savedRevision;
    /** Set when the controller block is gone but the stored items are being kept. */
    private boolean dormant;
    private long dormantSince;
    private long harvestedTotal;

    // ---- runtime scan state (region thread only) ---------------------------------
    private LongArrayList crops = new LongArrayList();
    private @Nullable LongArrayList scanBuffer;
    private int scanY;
    private long cacheStamp;
    private long scanStartedAt;
    private long lastScanMillis;
    private boolean cacheDirty = true;
    private int harvestCursor;

    public ChunkFarm(ChunkPos pos, int blockX, int blockY, int blockZ,
                     UUID owner, String ownerName,
                     FarmSettings settings, Warehouse warehouse) {
        this.pos = pos;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.owner = owner;
        this.ownerName = ownerName;
        this.settings = settings;
        this.warehouse = warehouse;
    }

    public ChunkPos pos() {
        return this.pos;
    }

    public int blockX() {
        return this.blockX;
    }

    public int blockY() {
        return this.blockY;
    }

    public int blockZ() {
        return this.blockZ;
    }

    public @Nullable World world() {
        return Bukkit.getWorld(this.pos.world());
    }

    /** @return null when the world is not loaded */
    public @Nullable Location controllerLocation() {
        World world = world();
        return world == null ? null : new Location(world, this.blockX, this.blockY, this.blockZ);
    }

    public UUID owner() {
        return this.owner;
    }

    public String ownerName() {
        return this.ownerName;
    }

    public void setOwner(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
        markDirty();
    }

    public FarmSettings settings() {
        return this.settings;
    }

    public Warehouse warehouse() {
        return this.warehouse;
    }

    /** @return the member's role, or null when they are not on the list */
    public synchronized @Nullable FarmRole roleOf(UUID player) {
        return this.members.get(player);
    }

    public synchronized void setMember(UUID player, String name, FarmRole role) {
        this.members.put(player, role);
        this.memberNames.put(player, name);
        markDirty();
    }

    public synchronized boolean removeMember(UUID player) {
        this.memberNames.remove(player);
        boolean removed = this.members.remove(player) != null;
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public synchronized String memberName(UUID player) {
        return this.memberNames.getOrDefault(player, player.toString().substring(0, 8));
    }

    public synchronized int memberCount() {
        return this.members.size();
    }

    /** Ordered snapshot, safe to iterate while the menu is open. */
    public synchronized java.util.List<Member> members() {
        java.util.List<Member> out = new java.util.ArrayList<>(this.members.size());
        for (Map.Entry<UUID, FarmRole> entry : this.members.entrySet()) {
            out.add(new Member(entry.getKey(), memberName(entry.getKey()), entry.getValue()));
        }
        return out;
    }

    public record Member(UUID uuid, String name, FarmRole role) {
    }

    public boolean isDormant() {
        return this.dormant;
    }

    public long dormantSince() {
        return this.dormantSince;
    }

    public void setDormant(boolean dormant, long since) {
        this.dormant = dormant;
        this.dormantSince = since;
        markDirty();
    }

    public long harvestedTotal() {
        return this.harvestedTotal;
    }

    public void addHarvested(long amount) {
        this.harvestedTotal += amount;
    }

    public void setHarvestedTotal(long harvestedTotal) {
        this.harvestedTotal = harvestedTotal;
    }

    public boolean isDirty() {
        return this.revision.get() != this.savedRevision;
    }

    public void markDirty() {
        this.revision.incrementAndGet();
    }

    /** The revision a save is about to persist. Pass it back to {@link #markSaved}. */
    public long revision() {
        return this.revision.get();
    }

    /** Only clears the dirty state up to {@code revision}; later changes stay pending. */
    public void markSaved(long revision) {
        this.savedRevision = revision;
    }

    public void clearDirty() {
        markSaved(this.revision.get());
    }

    /** True when the farm should be visited by the harvest task at all. */
    public boolean isRunnable() {
        return !this.dormant && this.settings.active() && this.settings.hasAnyCrop();
    }

    // ---- scan cache --------------------------------------------------------------

    public LongArrayList cachedCrops() {
        return this.crops;
    }

    /** Forces a rescan on the next cycle -- called when blocks change inside the chunk. */
    public void invalidateCache() {
        this.cacheDirty = true;
    }

    /**
     * Whether it is time to look for crops again.
     *
     * <p>The interval adapts to what the last scan actually cost. A farm spanning two
     * hundred layers can take longer to scan than the configured refresh interval, and
     * a fixed timer would put it straight back into scanning the moment it finished --
     * permanently burning the cycle budget that should be going into harvesting. So a
     * slow farm waits at least twice its own scan time before going again.
     */
    public boolean needsRescan(long now, long refreshMillis) {
        if (this.cacheDirty || this.scanBuffer != null) {
            return true;
        }
        return (now - this.cacheStamp) >= Math.max(refreshMillis, this.lastScanMillis * 2);
    }

    /** Starts a resumable rescan, or returns the layer the previous one stopped at. */
    public int beginScan() {
        if (this.scanBuffer == null) {
            this.scanBuffer = new LongArrayList(Math.max(64, this.crops.size()));
            this.scanY = this.settings.minY();
            this.scanStartedAt = System.currentTimeMillis();
        }
        return this.scanY;
    }

    public void recordScanHit(long packed) {
        LongArrayList buffer = this.scanBuffer;
        if (buffer != null) {
            buffer.add(packed);
        }
    }

    public void setScanY(int scanY) {
        this.scanY = scanY;
    }

    /** Swaps the freshly scanned positions in and restarts the harvest cursor. */
    public void finishScan(long now) {
        LongArrayList buffer = this.scanBuffer;
        if (buffer != null) {
            this.crops = buffer;
            this.scanBuffer = null;
        }
        if (this.scanStartedAt > 0) {
            this.lastScanMillis = Math.max(0, now - this.scanStartedAt);
            this.scanStartedAt = 0;
        }
        this.cacheStamp = now;
        this.cacheDirty = false;
        this.harvestCursor = 0;
    }

    /** Throws away a partial scan, e.g. when the bounds changed mid-scan. */
    public void abortScan() {
        this.scanBuffer = null;
        this.cacheDirty = true;
    }

    /** Wall-clock time the last full scan took, spread across cycles. */
    public long lastScanMillis() {
        return this.lastScanMillis;
    }

    public int harvestCursor() {
        return this.harvestCursor;
    }

    public void setHarvestCursor(int harvestCursor) {
        this.harvestCursor = harvestCursor;
    }
}

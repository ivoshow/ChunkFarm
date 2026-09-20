package dev.ivoshow.chunkfarm.farm;

import dev.ivoshow.chunkfarm.storage.FarmStorage;
import dev.ivoshow.chunkfarm.util.ChunkPos;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry of every farm on the server.
 *
 * <p>Lookups happen from region threads while placements and commands mutate from the
 * main thread, so the map is concurrent and the harvest task iterates a snapshot array
 * that is only rebuilt when a farm is added or removed -- no per-cycle allocation.
 */
public final class FarmManager {

    private final Map<ChunkPos, ChunkFarm> farms = new ConcurrentHashMap<>();
    private final FarmStorage storage;

    private volatile ChunkFarm[] rotation = new ChunkFarm[0];
    /** Round-robin cursor so a large server does not always start from the same farm. */
    private int rotationCursor;

    public FarmManager(FarmStorage storage) {
        this.storage = storage;
    }

    public void load() {
        this.farms.clear();
        for (ChunkFarm farm : this.storage.loadAll()) {
            this.farms.put(farm.pos(), farm);
        }
        rebuildRotation();
    }

    /** Takes a snapshot on the calling thread; the write itself may happen async. */
    public List<ChunkFarm> snapshot() {
        return new ArrayList<>(this.farms.values());
    }

    /**
     * Writes the given farms out and marks them clean only up to the revision they were
     * at when the write started -- anything changed mid-write stays pending for the
     * next save instead of being quietly dropped.
     */
    public void save(Collection<ChunkFarm> farms) {
        Map<ChunkFarm, Long> revisions = new java.util.IdentityHashMap<>();
        for (ChunkFarm farm : farms) {
            revisions.put(farm, farm.revision());
        }
        this.storage.saveAll(farms);
        revisions.forEach(ChunkFarm::markSaved);
    }

    public void register(ChunkFarm farm) {
        this.farms.put(farm.pos(), farm);
        rebuildRotation();
    }

    public @Nullable ChunkFarm remove(ChunkPos pos) {
        ChunkFarm removed = this.farms.remove(pos);
        if (removed != null) {
            rebuildRotation();
        }
        return removed;
    }

    public @Nullable ChunkFarm get(ChunkPos pos) {
        return this.farms.get(pos);
    }

    public @Nullable ChunkFarm at(World world, int chunkX, int chunkZ) {
        return this.farms.get(ChunkPos.of(world, chunkX, chunkZ));
    }

    public @Nullable ChunkFarm at(Chunk chunk) {
        return this.farms.get(ChunkPos.of(chunk));
    }

    /** The farm whose chunk contains this location -- not necessarily its controller. */
    public @Nullable ChunkFarm at(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        return at(world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public @Nullable ChunkFarm at(Block block) {
        return at(block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
    }

    /** @return the farm whose controller block is exactly this block, if any */
    public @Nullable ChunkFarm controllerAt(Block block) {
        ChunkFarm farm = at(block);
        if (farm == null) {
            return null;
        }
        boolean isController = farm.blockX() == block.getX()
                && farm.blockY() == block.getY()
                && farm.blockZ() == block.getZ();
        return isController ? farm : null;
    }

    public List<ChunkFarm> ownedBy(UUID owner) {
        List<ChunkFarm> out = new ArrayList<>();
        for (ChunkFarm farm : this.farms.values()) {
            if (farm.owner().equals(owner)) {
                out.add(farm);
            }
        }
        return out;
    }

    public int countOwnedBy(UUID owner) {
        int count = 0;
        for (ChunkFarm farm : this.farms.values()) {
            if (farm.owner().equals(owner)) {
                count++;
            }
        }
        return count;
    }

    public int size() {
        return this.farms.size();
    }

    /**
     * Hands out up to {@code limit} farms, continuing from where the previous cycle
     * stopped so every farm gets its turn even when the server is over budget.
     */
    public List<ChunkFarm> nextBatch(int limit) {
        ChunkFarm[] snapshot = this.rotation;
        if (snapshot.length == 0) {
            return List.of();
        }
        int take = limit <= 0 ? snapshot.length : Math.min(limit, snapshot.length);
        List<ChunkFarm> batch = new ArrayList<>(take);
        int cursor = this.rotationCursor;
        for (int i = 0; i < take; i++) {
            batch.add(snapshot[(cursor + i) % snapshot.length]);
        }
        this.rotationCursor = (cursor + take) % snapshot.length;
        return batch;
    }

    private void rebuildRotation() {
        this.rotation = this.farms.values().toArray(new ChunkFarm[0]);
        this.rotationCursor = 0;
    }
}

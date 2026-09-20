package dev.ivoshow.chunkfarm.util;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.UUID;

/**
 * Identity of a chunk across worlds. The {@code key} uses the same packing as
 * {@link Chunk#getChunkKey(int, int)} so it can be handed straight to the Bukkit API.
 */
public record ChunkPos(UUID world, long key) {

    public static ChunkPos of(World world, int chunkX, int chunkZ) {
        return new ChunkPos(world.getUID(), Chunk.getChunkKey(chunkX, chunkZ));
    }

    public static ChunkPos of(Chunk chunk) {
        return new ChunkPos(chunk.getWorld().getUID(), chunk.getChunkKey());
    }

    public int x() {
        return (int) key;
    }

    public int z() {
        return (int) (key >> 32);
    }

    /** World-space X of the chunk's north-west corner. */
    public int originX() {
        return x() << 4;
    }

    /** World-space Z of the chunk's north-west corner. */
    public int originZ() {
        return z() << 4;
    }

    @Override
    public String toString() {
        return x() + "," + z();
    }
}

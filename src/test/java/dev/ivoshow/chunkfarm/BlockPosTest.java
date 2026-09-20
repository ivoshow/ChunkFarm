package dev.ivoshow.chunkfarm;

import dev.ivoshow.chunkfarm.util.BlockPos;
import dev.ivoshow.chunkfarm.util.ChunkPos;
import org.bukkit.Chunk;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockPosTest {

    @Test
    void packingSurvivesTheWholeLegalHeightRange() {
        // -64..319 is the overworld; the packing has headroom well past that.
        for (int y = -2048; y <= 2047; y += 7) {
            long packed = BlockPos.pack(13, y, 4);
            assertEquals(13, BlockPos.localX(packed));
            assertEquals(y, BlockPos.y(packed));
            assertEquals(4, BlockPos.localZ(packed));
        }
    }

    @Test
    void everyPositionInAChunkLayerIsDistinct() {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                assertEquals(true, seen.add(BlockPos.pack(x, -64, z)), "colisao em " + x + "," + z);
            }
        }
        assertEquals(256, seen.size());
    }

    @Test
    void chunkPosMatchesBukkitPackingIncludingNegativeCoordinates() {
        UUID world = UUID.randomUUID();
        for (int[] pair : new int[][]{{0, 0}, {5, -9}, {-1, -1}, {-3_000_000, 2_000_000}}) {
            ChunkPos pos = new ChunkPos(world, Chunk.getChunkKey(pair[0], pair[1]));
            assertEquals(pair[0], pos.x());
            assertEquals(pair[1], pos.z());
            assertEquals(pair[0] << 4, pos.originX());
        }
    }
}

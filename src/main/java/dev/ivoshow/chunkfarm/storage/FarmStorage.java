package dev.ivoshow.chunkfarm.storage;

import dev.ivoshow.chunkfarm.farm.ChunkFarm;

import java.util.Collection;
import java.util.List;

/**
 * Persistence for farms. Implementations must tolerate being called from an async
 * thread with a snapshot of the farms.
 */
public interface FarmStorage {

    List<ChunkFarm> loadAll();

    void saveAll(Collection<ChunkFarm> farms);
}

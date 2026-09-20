package dev.ivoshow.chunkfarm.farm;

/**
 * How far the plugin reaches — which farms get a turn on any given cycle.
 *
 * <p>A farm only ever works on a chunk the server has in memory. This decides what
 * "in memory" means for the plugin, and it is the single biggest lever on what the
 * plugin costs a busy server.
 */
public enum ChunkReach {

    /**
     * Whatever the server already has loaded, for any reason: a player nearby, a chunk
     * ticket from another plugin, the world spawn. The plugin loads nothing itself, so
     * it costs nothing for farms nobody is near. This is the default.
     */
    LOADED,

    /**
     * Only farms within {@code chunks.player-radius} chunks of a player. Stricter than
     * {@link #LOADED}: spawn chunks and chunks another plugin keeps alive are skipped,
     * so the plugin's work scales with how many players are actually out farming.
     */
    PLAYER_RADIUS,

    /**
     * The plugin keeps every farm chunk loaded with its own chunk ticket, so farms run
     * with nobody online.
     *
     * <p>This makes the server tick those chunks — mobs, redstone, fluids, everything —
     * not just the farm. It is the expensive mode, and it is capped by
     * {@code chunks.max-always-loaded}.
     */
    ALWAYS
}

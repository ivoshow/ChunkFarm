package dev.ivoshow.chunkfarm.farm;

/** Where a farm puts what it harvests. */
public enum DeliveryMode {

    /**
     * Into the farm's warehouse, as a counter. Creates no entities, so this is the mode
     * that survives a farm producing millions of items a day.
     */
    WAREHOUSE,

    /**
     * On the ground, where the crop stood, as ordinary item entities -- so hoppers,
     * water streams and minecart collectors can pick them up like a vanilla farm.
     *
     * <p>This is the expensive mode by definition: every harvest spawns entities the
     * server then has to tick, and they despawn after five minutes if nothing collects
     * them. It exists because some builds want the items in the world, not in a menu.
     */
    DROP
}

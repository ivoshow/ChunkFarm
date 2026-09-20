package dev.ivoshow.chunkfarm.farm;

/** What a farm does with drops that no longer fit in its warehouse. */
public enum OverflowPolicy {

    /** Stop harvesting. Nothing is lost and the crops stay standing until space is made. */
    PAUSE,

    /** Keep harvesting and drop the excess on the ground next to the controller. */
    DROP,

    /** Keep harvesting and destroy the excess. */
    VOID
}

package dev.ivoshow.chunkfarm.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Bridge to whatever land-protection plugin the server runs.
 *
 * <p>Implementations must only touch their plugin's classes inside their own bodies, so
 * the class is never loaded on a server that does not have that plugin installed.
 */
public interface ProtectionHook {

    /** Name shown in {@code /cf stats}, e.g. {@code GriefPrevention}. */
    String name();

    /**
     * Whether the land at {@code location} <em>grants</em> {@code player} the right to
     * open the farm and take from its warehouse.
     *
     * <p>This is a grant, not a veto: it is only ever asked after the farm's own owner
     * check has already said no. Unclaimed land grants nothing, so it must answer
     * {@code false} there -- answering {@code true} would hand every farm outside a
     * claim to anybody who walked up to it.
     */
    boolean canOpen(Player player, Location location);

    /** Whether {@code player} may place or break a controller at {@code location}. */
    boolean canBuild(Player player, Location location);
}

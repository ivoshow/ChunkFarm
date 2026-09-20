package dev.ivoshow.chunkfarm.protection;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * GriefPrevention: the farm follows the claim.
 *
 * <p>Whoever the claim trusts at the configured level can open the warehouse, which is
 * what makes a farm shared by two players work without handing out
 * {@code chunkfarm.admin}. A farm standing on unclaimed land stays owner-only.
 */
public final class GriefPreventionHook implements ProtectionHook {

    private final ClaimPermission openPermission;

    public GriefPreventionHook(ClaimPermission openPermission) {
        this.openPermission = openPermission;
    }

    @Override
    public String name() {
        return "GriefPrevention";
    }

    @Override
    public boolean canOpen(Player player, Location location) {
        Claim claim = claimAt(location);
        if (claim == null) {
            // No claim, nothing to grant. The caller has already established that this
            // player does not own the farm, so unclaimed land must not open it.
            return false;
        }
        // checkPermission returns the denial message, or null when allowed.
        Supplier<String> denial = claim.checkPermission(player, this.openPermission, null);
        return denial == null;
    }

    @Override
    public boolean canBuild(Player player, Location location) {
        Claim claim = claimAt(location);
        if (claim == null) {
            return true;
        }
        return claim.checkPermission(player, ClaimPermission.Build, null) == null;
    }

    private static Claim claimAt(Location location) {
        GriefPrevention gp = GriefPrevention.instance;
        if (gp == null || gp.dataStore == null || !gp.claimsEnabledForWorld(location.getWorld())) {
            return null;
        }
        return gp.dataStore.getClaimAt(location, true, null);
    }
}

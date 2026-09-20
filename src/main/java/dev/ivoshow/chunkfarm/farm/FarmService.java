package dev.ivoshow.chunkfarm.farm;

import dev.ivoshow.chunkfarm.config.PluginConfig;
import dev.ivoshow.chunkfarm.protection.ProtectionHook;
import dev.ivoshow.chunkfarm.protection.ProtectionHooks;
import dev.ivoshow.chunkfarm.util.ChunkPos;
import dev.ivoshow.chunkfarm.warehouse.Warehouse;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/** Everything that creates, revives or retires a farm. Main thread only. */
public final class FarmService {

    public static final String PERMISSION_ADMIN = "chunkfarm.admin";
    public static final String PERMISSION_PLACE = "chunkfarm.place";
    public static final String PERMISSION_BYPASS_LIMIT = "chunkfarm.limit.bypass";

    private final FarmManager farms;
    private final PluginConfig config;
    private ProtectionHook protection = ProtectionHooks.NONE;
    private ControllerItem controllerItem;

    public FarmService(FarmManager farms, PluginConfig config) {
        this.farms = farms;
        this.config = config;
    }

    public void setControllerItem(ControllerItem controllerItem) {
        this.controllerItem = controllerItem;
    }

    public void setProtection(ProtectionHook protection) {
        this.protection = protection;
    }

    public ProtectionHook protection() {
        return this.protection;
    }

    /**
     * Whether {@code player} may do something on this farm that needs {@code required}.
     *
     * <p>Three ways in, checked in order:
     *
     * <ol>
     *   <li>the owner, and anyone with {@code chunkfarm.admin};
     *   <li>the farm's own member list, which the owner manages with
     *       {@code /cf confiar} — this is what makes the plugin work on its own, with
     *       no land-protection plugin installed at all;
     *   <li>trust on the land the controller stands on, when a protection plugin is
     *       hooked up. Land trust maps to {@link FarmRole#WITHDRAW}: it is meant for
     *       teammates, not for handing over the farm's settings.
     * </ol>
     */
    public boolean can(Player player, ChunkFarm farm, FarmRole required) {
        if (farm.owner().equals(player.getUniqueId()) || player.hasPermission(PERMISSION_ADMIN)) {
            return true;
        }

        FarmRole member = farm.roleOf(player.getUniqueId());
        if (member != null && member.allows(required)) {
            return true;
        }

        if (required.allows(FarmRole.MANAGER)) {
            return false;
        }
        Location controller = farm.controllerLocation();
        return controller != null && this.protection.canOpen(player, controller);
    }

    /** Opening the menu at all. */
    public boolean canOpen(Player player, ChunkFarm farm) {
        return can(player, farm, FarmRole.ACCESS);
    }

    /** Taking items out of the warehouse. */
    public boolean canWithdraw(Player player, ChunkFarm farm) {
        return can(player, farm, FarmRole.WITHDRAW);
    }

    /** Changing crops, layers, delivery -- and breaking the controller. */
    public boolean canConfigure(Player player, ChunkFarm farm) {
        return can(player, farm, FarmRole.MANAGER);
    }

    /** Only the owner (or an admin) edits the member list. */
    public boolean canManageMembers(Player player, ChunkFarm farm) {
        return farm.owner().equals(player.getUniqueId()) || player.hasPermission(PERMISSION_ADMIN);
    }

    /** Why a placement was refused, or {@link #OK}. */
    public enum Placement {
        OK,
        WORLD_BLOCKED,
        NO_PERMISSION,
        /** Refused by the land-protection plugin. */
        PROTECTED,
        LIMIT_REACHED,
        CHUNK_TAKEN
    }

    public Placement canPlace(Player player, Block block) {
        if (!this.config.isWorldAllowed(block.getWorld().getName())) {
            return Placement.WORLD_BLOCKED;
        }
        if (!player.hasPermission(PERMISSION_PLACE)) {
            return Placement.NO_PERMISSION;
        }
        // The protection plugin normally cancels the place event before we see it; this
        // is here so a farm can never end up registered on land the player cannot build on.
        if (!this.protection.canBuild(player, block.getLocation())) {
            return Placement.PROTECTED;
        }

        ChunkFarm existing = this.farms.at(block);
        if (existing != null) {
            // A dormant farm belonging to this player is a reclaim, not a clash.
            boolean reclaimable = existing.isDormant()
                    && (existing.owner().equals(player.getUniqueId()) || player.hasPermission(PERMISSION_ADMIN));
            return reclaimable ? Placement.OK : Placement.CHUNK_TAKEN;
        }

        int limit = this.config.maxFarmsPerPlayer();
        if (limit > 0 && !player.hasPermission(PERMISSION_BYPASS_LIMIT)
                && this.farms.countOwnedBy(player.getUniqueId()) >= limit) {
            return Placement.LIMIT_REACHED;
        }
        return Placement.OK;
    }

    /**
     * Creates the farm, or wakes the dormant one already stored for this chunk so the
     * player gets their warehouse back untouched.
     *
     * @return the live farm, and whether it was a reclaim
     */
    public Created place(Player player, Block block) {
        ChunkFarm existing = this.farms.at(block);
        if (existing != null) {
            existing.setDormant(false, 0L);
            existing.setOwner(player.getUniqueId(), player.getName());
            existing.settings().setActive(true);
            existing.invalidateCache();
            return new Created(relocate(existing, block), true);
        }

        World world = block.getWorld();
        int minY = clampToWorld(world, block.getY() + this.config.defaultMinOffset());
        int maxY = clampToWorld(world, block.getY() + this.config.defaultMaxOffset());

        FarmSettings settings = new FarmSettings(minY, maxY, this.config.defaultCrops());
        settings.setOverflow(this.config.defaultOverflow());
        settings.setDelivery(this.config.defaultDelivery());

        Warehouse warehouse = new Warehouse(this.config.warehouseMaxTypes(), this.config.warehouseMaxPerType());

        ChunkFarm farm = new ChunkFarm(ChunkPos.of(block.getChunk()),
                block.getX(), block.getY(), block.getZ(),
                player.getUniqueId(), player.getName(),
                settings, warehouse);
        this.farms.register(farm);
        return new Created(farm, false);
    }

    /**
     * The controller may come back at a different spot in the same chunk, so the stored
     * coordinates follow it. The Y range moves with it too, keeping the same thickness.
     */
    private ChunkFarm relocate(ChunkFarm old, Block block) {
        if (old.blockX() == block.getX() && old.blockY() == block.getY() && old.blockZ() == block.getZ()) {
            return old;
        }
        int shift = block.getY() - old.blockY();
        FarmSettings settings = old.settings();

        ChunkFarm moved = new ChunkFarm(old.pos(), block.getX(), block.getY(), block.getZ(),
                old.owner(), old.ownerName(), settings, old.warehouse());
        moved.setHarvestedTotal(old.harvestedTotal());
        settings.setBounds(
                clampToWorld(block.getWorld(), settings.minY() + shift),
                clampToWorld(block.getWorld(), settings.maxY() + shift));

        this.farms.register(moved);
        return moved;
    }

    /**
     * The controller is gone but the stored items are not: the farm stops working and
     * waits to be reclaimed. Data older than {@code storage.orphan-retention-days} is
     * cleaned up by {@link #purgeExpiredOrphans()}.
     */
    public void makeDormant(ChunkFarm farm) {
        if (farm.isDormant()) {
            return;
        }
        farm.setDormant(true, System.currentTimeMillis());
        farm.abortScan();
    }

    /** Deletes the farm outright, warehouse included. */
    public @Nullable ChunkFarm delete(ChunkFarm farm) {
        return this.farms.remove(farm.pos());
    }

    /**
     * The controller is being taken out: drop the farm when there is nothing to
     * protect, otherwise park it so the stored items can be reclaimed later.
     */
    public void retire(ChunkFarm farm) {
        if (farm.warehouse().isEmpty()) {
            delete(farm);
        } else {
            makeDormant(farm);
        }
    }

    /**
     * Hands a controller item back to the player.
     *
     * <p>Straight into the inventory rather than as an item entity on the ground: a
     * dropped entity can spawn inside the block that is still being removed, go
     * unnoticed in creative, or simply despawn. Only the overflow hits the floor.
     */
    public void returnController(Player player, Location where) {
        ItemStack controller = this.controllerItem.create(1);
        for (ItemStack leftover : player.getInventory().addItem(controller).values()) {
            where.getWorld().dropItemNaturally(where.clone().add(0.5, 0.5, 0.5), leftover);
        }
    }

    /**
     * Drops dormant farms whose grace period ran out. An empty one goes immediately --
     * there is nothing to protect.
     *
     * @return how many were removed
     */
    public int purgeExpiredOrphans() {
        long retention = TimeUnit.DAYS.toMillis(this.config.orphanRetentionDays());
        long now = System.currentTimeMillis();
        int removed = 0;

        for (Iterator<ChunkFarm> it = this.farms.snapshot().iterator(); it.hasNext(); ) {
            ChunkFarm farm = it.next();
            if (!farm.isDormant()) {
                continue;
            }
            boolean expired = retention > 0 && (now - farm.dormantSince()) >= retention;
            if (farm.warehouse().isEmpty() || expired) {
                this.farms.remove(farm.pos());
                removed++;
            }
        }
        return removed;
    }

    public int clampToWorld(World world, int y) {
        return Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, y));
    }

    /**
     * Applies new bounds, clamped to the world and to {@code area.max-layers}.
     *
     * @return true when the request had to be trimmed
     */
    public boolean setBounds(ChunkFarm farm, int minY, int maxY) {
        World world = farm.world();
        if (world == null) {
            return false;
        }
        int low = clampToWorld(world, Math.min(minY, maxY));
        int high = clampToWorld(world, Math.max(minY, maxY));

        boolean trimmed = false;
        int span = high - low + 1;
        if (span > this.config.maxLayerSpan()) {
            high = low + this.config.maxLayerSpan() - 1;
            trimmed = true;
        }

        farm.settings().setBounds(low, high);
        farm.abortScan();
        farm.markDirty();
        return trimmed;
    }

    public boolean isControllerBlock(Block block) {
        return block.getType() == this.config.controllerMaterial()
                && this.farms.controllerAt(block) != null;
    }

    public Material controllerMaterial() {
        return this.config.controllerMaterial();
    }

    public record Created(ChunkFarm farm, boolean reclaimed) {
    }
}

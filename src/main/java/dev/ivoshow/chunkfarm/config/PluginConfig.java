package dev.ivoshow.chunkfarm.config;

import dev.ivoshow.chunkfarm.crop.CropType;
import dev.ivoshow.chunkfarm.farm.ChunkReach;
import dev.ivoshow.chunkfarm.farm.DeliveryMode;
import dev.ivoshow.chunkfarm.farm.OverflowPolicy;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Typed, validated view over config.yml. Re-created from scratch on every reload. */
public final class PluginConfig {

    private final Logger logger;

    private Material controllerMaterial = Material.LODESTONE;
    private String controllerName = "<gradient:#8fdc63:#3fa34d><bold>Controlador de Fazenda</bold></gradient>";
    private int maxFarmsPerPlayer = 3;
    private Set<String> allowedWorlds = Set.of();
    private Set<String> blockedWorlds = Set.of();

    private int defaultMinOffset = -3;
    private int defaultMaxOffset = 3;
    private int maxLayerSpan = 384;
    private Set<CropType> defaultCrops = EnumSet.allOf(CropType.class);

    private int intervalTicks = 20;
    private int farmsPerCycle = 250;
    private int blocksPerCycle = 25_000;
    private int blocksPerFarmCycle = 16_384;
    private long cacheRefreshMillis = 30_000L;
    private int fortuneLevel = 0;

    private boolean allowFreeSeed = true;

    private int warehouseMaxTypes = 90;
    private long warehouseMaxPerType = 0L;
    private OverflowPolicy defaultOverflow = OverflowPolicy.PAUSE;
    private DeliveryMode defaultDelivery = DeliveryMode.WAREHOUSE;

    private final Map<CropType, Integer> columnKeepBlocks = new EnumMap<>(CropType.class);

    private ChunkReach chunkReach = ChunkReach.LOADED;
    private int playerRadiusChunks = 8;
    private int maxAlwaysLoaded = 200;

    private int maxMembersPerFarm = 10;
    private boolean guiStackBadge = false;
    private boolean useLandTrust = true;
    private String requiredTrust = "Inventory";

    private int saveIntervalSeconds = 300;
    private int flushDelaySeconds = 3;
    private boolean logWithdrawals = true;
    private int backupsToKeep = 5;
    private int backupIntervalMinutes = 60;
    private int orphanRetentionDays = 7;

    public PluginConfig(Logger logger) {
        this.logger = logger;
    }

    public void load(FileConfiguration config) {
        this.controllerMaterial = material(config.getString("controller.material"), Material.LODESTONE);
        this.controllerName = config.getString("controller.name", this.controllerName);
        this.maxFarmsPerPlayer = config.getInt("controller.max-per-player", 3);

        this.allowedWorlds = new HashSet<>(config.getStringList("worlds.only"));
        this.blockedWorlds = new HashSet<>(config.getStringList("worlds.blocked"));

        this.defaultMinOffset = config.getInt("area.default-min-offset", -3);
        this.defaultMaxOffset = config.getInt("area.default-max-offset", 3);
        this.maxLayerSpan = Math.max(1, config.getInt("area.max-layers", 384));

        this.defaultCrops = cropSet(config.getStringList("area.default-crops"));

        this.intervalTicks = Math.max(1, config.getInt("harvest.interval-ticks", 20));
        this.farmsPerCycle = config.getInt("harvest.farms-per-cycle", 250);
        this.blocksPerCycle = Math.max(256, config.getInt("harvest.blocks-per-cycle", 25_000));
        this.blocksPerFarmCycle = Math.max(64, config.getInt("harvest.blocks-per-farm-cycle", 16_384));
        this.cacheRefreshMillis = Math.max(1, config.getInt("harvest.cache-refresh-seconds", 30)) * 1000L;
        this.fortuneLevel = Math.max(0, Math.min(10, config.getInt("harvest.fortune-level", 0)));

        this.allowFreeSeed = config.getBoolean("replant.allow-free-seed", true);

        this.warehouseMaxTypes = Math.max(1, config.getInt("warehouse.max-types", 90));
        this.warehouseMaxPerType = Math.max(0L, config.getLong("warehouse.max-per-type", 0L));
        this.defaultOverflow = overflow(config.getString("warehouse.when-full"));
        this.defaultDelivery = delivery(config.getString("warehouse.delivery"));

        this.columnKeepBlocks.clear();
        ConfigurationSection keep = config.getConfigurationSection("crops.column-keep-blocks");
        if (keep != null) {
            for (String key : keep.getKeys(false)) {
                CropType type = CropType.byId(key);
                if (type == null) {
                    this.logger.warning("crops.column-keep-blocks: cultura desconhecida '" + key + "'");
                    continue;
                }
                this.columnKeepBlocks.put(type, Math.max(1, keep.getInt(key, 1)));
            }
        }

        this.maxMembersPerFarm = Math.max(0, config.getInt("protection.max-members-per-farm", 10));
        this.guiStackBadge = config.getBoolean("warehouse.gui-stack-badge", false);
        this.useLandTrust = config.getBoolean("protection.use-land-trust", true);
        this.requiredTrust = config.getString("protection.required-trust", "Inventory");

        this.chunkReach = reach(config.getString("chunks.mode"));
        this.playerRadiusChunks = Math.max(0, config.getInt("chunks.player-radius", 8));
        this.maxAlwaysLoaded = Math.max(0, config.getInt("chunks.max-always-loaded", 200));

        this.saveIntervalSeconds = Math.max(30, config.getInt("storage.save-interval-seconds", 300));
        this.flushDelaySeconds = Math.max(1, config.getInt("storage.flush-delay-seconds", 3));
        this.logWithdrawals = config.getBoolean("storage.log-withdrawals", true);
        this.backupsToKeep = Math.max(0, config.getInt("storage.backups", 5));
        this.backupIntervalMinutes = Math.max(1, config.getInt("storage.backup-interval-minutes", 60));
        this.orphanRetentionDays = Math.max(0, config.getInt("storage.orphan-retention-days", 7));

        if (this.defaultMinOffset > this.defaultMaxOffset) {
            int swap = this.defaultMinOffset;
            this.defaultMinOffset = this.defaultMaxOffset;
            this.defaultMaxOffset = swap;
        }
        int defaultSpan = this.defaultMaxOffset - this.defaultMinOffset + 1;
        if (defaultSpan > this.maxLayerSpan) {
            this.logger.warning("area.default-* abrange " + defaultSpan
                    + " camadas, acima de area.max-layers (" + this.maxLayerSpan + "). Reduzindo.");
            this.defaultMaxOffset = this.defaultMinOffset + this.maxLayerSpan - 1;
        }
    }

    private Material material(String raw, Material fallback) {
        if (raw == null) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw);
        if (material == null || !material.isBlock() || material.isAir()) {
            this.logger.warning("controller.material invalido ('" + raw + "'), usando " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private Set<CropType> cropSet(java.util.List<String> raw) {
        if (raw.isEmpty()) {
            return EnumSet.allOf(CropType.class);
        }
        EnumSet<CropType> out = EnumSet.noneOf(CropType.class);
        for (String entry : raw) {
            if ("*".equals(entry) || "all".equalsIgnoreCase(entry)) {
                return EnumSet.allOf(CropType.class);
            }
            CropType type = CropType.byId(entry);
            if (type == null) {
                this.logger.warning("area.default-crops: cultura desconhecida '" + entry + "'");
                continue;
            }
            out.add(type);
        }
        return out;
    }

    private OverflowPolicy overflow(String raw) {
        if (raw == null) {
            return OverflowPolicy.PAUSE;
        }
        try {
            return OverflowPolicy.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            this.logger.warning("warehouse.when-full invalido ('" + raw + "'), usando PAUSE.");
            return OverflowPolicy.PAUSE;
        }
    }

    private DeliveryMode delivery(String raw) {
        if (raw == null) {
            return DeliveryMode.WAREHOUSE;
        }
        try {
            return DeliveryMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            this.logger.warning("warehouse.delivery invalido ('" + raw + "'), usando WAREHOUSE.");
            return DeliveryMode.WAREHOUSE;
        }
    }

    private ChunkReach reach(String raw) {
        if (raw == null) {
            return ChunkReach.LOADED;
        }
        try {
            return ChunkReach.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            this.logger.warning("chunks.mode invalido ('" + raw + "'), usando LOADED.");
            return ChunkReach.LOADED;
        }
    }

    public boolean isWorldAllowed(String worldName) {
        if (this.blockedWorlds.contains(worldName)) {
            return false;
        }
        return this.allowedWorlds.isEmpty() || this.allowedWorlds.contains(worldName);
    }

    public Material controllerMaterial() {
        return this.controllerMaterial;
    }

    public String controllerName() {
        return this.controllerName;
    }

    public int maxFarmsPerPlayer() {
        return this.maxFarmsPerPlayer;
    }

    public int defaultMinOffset() {
        return this.defaultMinOffset;
    }

    public int defaultMaxOffset() {
        return this.defaultMaxOffset;
    }

    public int maxLayerSpan() {
        return this.maxLayerSpan;
    }

    public Set<CropType> defaultCrops() {
        return this.defaultCrops;
    }

    public int intervalTicks() {
        return this.intervalTicks;
    }

    public int farmsPerCycle() {
        return this.farmsPerCycle;
    }

    /** Total block budget shared by every farm in one cycle -- the TPS safety valve. */
    public int blocksPerCycle() {
        return this.blocksPerCycle;
    }

    /** Ceiling on what a single farm may take out of {@link #blocksPerCycle()}. */
    public int blocksPerFarmCycle() {
        return this.blocksPerFarmCycle;
    }

    public long cacheRefreshMillis() {
        return this.cacheRefreshMillis;
    }

    public int fortuneLevel() {
        return this.fortuneLevel;
    }

    public boolean allowFreeSeed() {
        return this.allowFreeSeed;
    }

    public int warehouseMaxTypes() {
        return this.warehouseMaxTypes;
    }

    /** {@code 0} means "no per-type cap" -- only the absolute long-overflow guard applies. */
    public long warehouseMaxPerType() {
        return this.warehouseMaxPerType;
    }

    public OverflowPolicy defaultOverflow() {
        return this.defaultOverflow;
    }

    public DeliveryMode defaultDelivery() {
        return this.defaultDelivery;
    }

    public Map<CropType, Integer> columnKeepBlocks() {
        return this.columnKeepBlocks;
    }

    /** How many players an owner may trust on one farm. 0 means no limit. */
    public int maxMembersPerFarm() {
        return this.maxMembersPerFarm;
    }

    /**
     * Whether the warehouse icons carry the vanilla stack-count badge. Off by default:
     * the badge cannot exceed one stack, so it reads as the total and is not.
     */
    public boolean guiStackBadge() {
        return this.guiStackBadge;
    }

    /** Whether a land-protection plugin may grant access to somebody who is not the owner. */
    public boolean useLandTrust() {
        return this.useLandTrust;
    }

    public String requiredTrust() {
        return this.requiredTrust;
    }

    public ChunkReach chunkReach() {
        return this.chunkReach;
    }

    /** Radius in chunks around a player, for {@link ChunkReach#PLAYER_RADIUS}. */
    public int playerRadiusChunks() {
        return this.playerRadiusChunks;
    }

    /** Ceiling on how many chunks the plugin will hold open in {@link ChunkReach#ALWAYS}. */
    public int maxAlwaysLoaded() {
        return this.maxAlwaysLoaded;
    }

    /** How long a withdrawal waits before it is forced to disk. */
    public int flushDelaySeconds() {
        return this.flushDelaySeconds;
    }

    /** Whether every withdrawal is appended to saques.log. */
    public boolean logWithdrawals() {
        return this.logWithdrawals;
    }

    public int backupsToKeep() {
        return this.backupsToKeep;
    }

    public int backupIntervalMinutes() {
        return this.backupIntervalMinutes;
    }

    public int saveIntervalSeconds() {
        return this.saveIntervalSeconds;
    }

    public int orphanRetentionDays() {
        return this.orphanRetentionDays;
    }
}

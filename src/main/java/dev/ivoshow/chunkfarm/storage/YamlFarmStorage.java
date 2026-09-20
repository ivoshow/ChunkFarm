package dev.ivoshow.chunkfarm.storage;

import dev.ivoshow.chunkfarm.crop.CropType;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.DeliveryMode;
import dev.ivoshow.chunkfarm.farm.FarmRole;
import dev.ivoshow.chunkfarm.farm.FarmSettings;
import dev.ivoshow.chunkfarm.farm.OverflowPolicy;
import dev.ivoshow.chunkfarm.util.ChunkPos;
import dev.ivoshow.chunkfarm.warehouse.ItemKey;
import dev.ivoshow.chunkfarm.warehouse.Warehouse;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stores every farm in one readable YAML file, written through a temp file so a crash
 * mid-save cannot truncate it.
 *
 * <p>The warehouse is the reason this stays cheap no matter how big the farms get: a
 * farm holding ten million items serialises to one {@code "WHEAT=10000000"} line.
 */
public final class YamlFarmStorage implements FarmStorage {

    private final Path file;
    private final Logger logger;
    /** Farms present in the last successful load or save, for the wipe guard. */
    private volatile int lastKnownFarmCount = -1;
    private volatile long lastBackupAt;
    private int backupsToKeep = 5;
    private long backupIntervalMillis = 3_600_000L;
    /** Supplies the warehouse limits from the live config when loading. */
    private final IntSupplier warehouseTypeLimit;
    private final LongSupplier warehousePerTypeLimit;

    public YamlFarmStorage(Path file, Logger logger,
                           IntSupplier warehouseTypeLimit,
                           LongSupplier warehousePerTypeLimit) {
        this.file = file;
        this.logger = logger;
        this.warehouseTypeLimit = warehouseTypeLimit;
        this.warehousePerTypeLimit = warehousePerTypeLimit;
    }

    @Override
    public List<ChunkFarm> loadAll() {
        List<ChunkFarm> farms = new ArrayList<>();
        File handle = this.file.toFile();
        if (!handle.isFile()) {
            return farms;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(handle);
        ConfigurationSection root = yaml.getConfigurationSection("farms");
        if (root == null) {
            return farms;
        }

        int maxTypes = this.warehouseTypeLimit.getAsInt();
        long maxPerType = this.warehousePerTypeLimit.getAsLong();

        for (String worldId : root.getKeys(false)) {
            ConfigurationSection worldSection = root.getConfigurationSection(worldId);
            if (worldSection == null) {
                continue;
            }
            UUID world;
            try {
                world = UUID.fromString(worldId);
            } catch (IllegalArgumentException ex) {
                this.logger.warning("Ignorando mundo invalido em farms.yml: " + worldId);
                continue;
            }

            for (String chunkId : worldSection.getKeys(false)) {
                ConfigurationSection section = worldSection.getConfigurationSection(chunkId);
                if (section == null) {
                    continue;
                }
                try {
                    farms.add(read(new ChunkPos(world, Long.parseLong(chunkId)), section, maxTypes, maxPerType));
                } catch (RuntimeException ex) {
                    this.logger.log(Level.WARNING, "Falha ao carregar a fazenda " + worldId + "/" + chunkId, ex);
                }
            }
        }
        this.lastKnownFarmCount = farms.size();
        return farms;
    }

    public void configureBackups(int keep, int intervalMinutes) {
        this.backupsToKeep = Math.max(0, keep);
        this.backupIntervalMillis = Math.max(1, intervalMinutes) * 60_000L;
    }

    private ChunkFarm read(ChunkPos pos, ConfigurationSection section, int maxTypes, long maxPerType) {
        EnumSet<CropType> crops = EnumSet.noneOf(CropType.class);
        for (String raw : section.getStringList("crops")) {
            CropType type = CropType.byId(raw);
            if (type != null) {
                crops.add(type);
            }
        }

        FarmSettings settings = new FarmSettings(
                section.getInt("min-y"), section.getInt("max-y"), crops);
        settings.setAutoReplant(section.getBoolean("auto-replant", true));
        settings.setActive(section.getBoolean("active", true));
        settings.setOverflow(parseOverflow(section.getString("overflow")));
        settings.setDelivery(parseDelivery(section.getString("delivery")));

        Warehouse warehouse = new Warehouse(maxTypes, maxPerType);
        for (String entry : section.getStringList("warehouse")) {
            int split = entry.lastIndexOf('=');
            if (split <= 0) {
                continue;
            }
            ItemKey key = ItemKey.deserialize(entry.substring(0, split));
            if (key == null) {
                this.logger.warning("Item desconhecido no armazem, descartado: " + entry);
                continue;
            }
            try {
                warehouse.add(key, Long.parseLong(entry.substring(split + 1)));
            } catch (NumberFormatException ignored) {
                // A hand-edited file should not take the whole farm down with it.
            }
        }

        String ownerRaw = section.getString("owner");
        UUID owner = ownerRaw == null ? new UUID(0, 0) : UUID.fromString(ownerRaw);

        ChunkFarm farm = new ChunkFarm(pos,
                section.getInt("x"), section.getInt("y"), section.getInt("z"),
                owner, section.getString("owner-name", "?"),
                settings, warehouse);
        // "<uuid>|<nome>=<papel>" -- one line per member, same shape as the warehouse.
        for (String entry : section.getStringList("members")) {
            int split = entry.lastIndexOf('=');
            if (split <= 0) {
                continue;
            }
            FarmRole role = FarmRole.parse(entry.substring(split + 1));
            String identity = entry.substring(0, split);
            int namePart = identity.indexOf('|');
            try {
                UUID uuid = UUID.fromString(namePart == -1 ? identity : identity.substring(0, namePart));
                farm.setMember(uuid,
                        namePart == -1 ? "?" : identity.substring(namePart + 1),
                        role == null ? FarmRole.WITHDRAW : role);
            } catch (IllegalArgumentException ignored) {
                this.logger.warning("Membro invalido descartado: " + entry);
            }
        }

        farm.setDormant(section.getBoolean("dormant", false), section.getLong("dormant-since"));
        farm.setHarvestedTotal(section.getLong("harvested-total"));
        farm.clearDirty();
        return farm;
    }

    private static OverflowPolicy parseOverflow(String raw) {
        if (raw == null) {
            return OverflowPolicy.PAUSE;
        }
        try {
            return OverflowPolicy.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return OverflowPolicy.PAUSE;
        }
    }

    private static DeliveryMode parseDelivery(String raw) {
        if (raw == null) {
            return DeliveryMode.WAREHOUSE;
        }
        try {
            return DeliveryMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return DeliveryMode.WAREHOUSE;
        }
    }

    @Override
    public void saveAll(Collection<ChunkFarm> farms) {
        // A save that drops every farm at once is far more likely to be a bug in here
        // than the truth. Refusing costs one stale save; being wrong the other way
        // costs everybody's warehouse.
        int known = this.lastKnownFarmCount;
        if (farms.isEmpty() && known > 0) {
            this.logger.severe("Salvamento abortado: seriam gravadas 0 fazendas, mas "
                    + known + " estavam carregadas. O arquivo anterior foi preservado."
                    + " Isso e um bug -- por favor reporte.");
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("farms");
        for (ChunkFarm farm : farms) {
            write(farm, root.createSection(farm.pos().world() + "." + farm.pos().key()));
        }

        try {
            Files.createDirectories(this.file.getParent());
            rotateBackup();

            Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            Files.writeString(temp, yaml.saveToString());
            try {
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
            this.lastKnownFarmCount = farms.size();
        } catch (IOException ex) {
            this.logger.log(Level.SEVERE, "Nao foi possivel salvar as fazendas em " + this.file, ex);
        }
    }

    /**
     * Keeps a few timestamped copies alongside the live file.
     *
     * <p>The temp-file-and-move dance already stops a crash from truncating the save,
     * but it cannot help against a bad value that gets written correctly. A copy from
     * an hour ago can.
     */
    private void rotateBackup() {
        if (this.backupsToKeep == 0 || !Files.isRegularFile(this.file)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastBackupAt < this.backupIntervalMillis) {
            return;
        }
        this.lastBackupAt = now;

        try {
            Path dir = this.file.resolveSibling("backups");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Files.copy(this.file, dir.resolve("farms-" + stamp + ".yml"),
                    StandardCopyOption.REPLACE_EXISTING);

            try (var existing = Files.list(dir)) {
                List<Path> old = existing
                        .filter(path -> path.getFileName().toString().startsWith("farms-"))
                        .sorted()
                        .toList();
                for (int i = 0; i < old.size() - this.backupsToKeep; i++) {
                    Files.deleteIfExists(old.get(i));
                }
            }
        } catch (IOException ex) {
            // A missing backup must never stop the real save.
            this.logger.warning("Nao foi possivel gravar o backup: " + ex.getMessage());
        }
    }

    private static void write(ChunkFarm farm, ConfigurationSection section) {
        section.set("x", farm.blockX());
        section.set("y", farm.blockY());
        section.set("z", farm.blockZ());
        section.set("owner", farm.owner().toString());
        section.set("owner-name", farm.ownerName());
        section.set("min-y", farm.settings().minY());
        section.set("max-y", farm.settings().maxY());
        section.set("auto-replant", farm.settings().autoReplant());
        section.set("active", farm.settings().active());
        section.set("overflow", farm.settings().overflow().name());
        section.set("delivery", farm.settings().delivery().name());
        section.set("dormant", farm.isDormant());
        section.set("dormant-since", farm.dormantSince());
        section.set("harvested-total", farm.harvestedTotal());

        List<String> crops = new ArrayList<>();
        for (CropType type : farm.settings().enabledCrops()) {
            crops.add(type.name());
        }
        section.set("crops", crops);

        List<String> members = new ArrayList<>();
        for (ChunkFarm.Member member : farm.members()) {
            members.add(member.uuid() + "|" + member.name() + "=" + member.role().name());
        }
        section.set("members", members);

        List<Warehouse.Entry> contents = farm.warehouse().snapshot();
        List<String> warehouse = new ArrayList<>(contents.size());
        for (Warehouse.Entry entry : contents) {
            warehouse.add(entry.key().serialize() + "=" + entry.amount());
        }
        section.set("warehouse", warehouse);
    }
}

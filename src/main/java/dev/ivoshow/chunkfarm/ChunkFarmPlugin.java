package dev.ivoshow.chunkfarm;

import dev.ivoshow.chunkfarm.command.ChunkFarmCommand;
import dev.ivoshow.chunkfarm.config.Messages;
import dev.ivoshow.chunkfarm.config.PluginConfig;
import dev.ivoshow.chunkfarm.crop.CropRegistry;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.ChunkReach;
import dev.ivoshow.chunkfarm.farm.ControllerItem;
import dev.ivoshow.chunkfarm.farm.FarmManager;
import dev.ivoshow.chunkfarm.farm.FarmService;
import dev.ivoshow.chunkfarm.gui.FarmGui;
import dev.ivoshow.chunkfarm.harvest.HarvestTask;
import dev.ivoshow.chunkfarm.listener.BlockListener;
import dev.ivoshow.chunkfarm.listener.GuiListener;
import dev.ivoshow.chunkfarm.listener.InteractListener;
import dev.ivoshow.chunkfarm.protection.ProtectionHooks;
import dev.ivoshow.chunkfarm.storage.FarmStorage;
import dev.ivoshow.chunkfarm.storage.WithdrawalLog;
import dev.ivoshow.chunkfarm.storage.YamlFarmStorage;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.World;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChunkFarmPlugin extends JavaPlugin {

    private PluginConfig config;
    private Messages messages;
    private CropRegistry cropRegistry;

    private FarmManager farms;
    private FarmService farmService;
    private ControllerItem controllerItem;
    private HarvestTask harvestTask;
    private WithdrawalLog withdrawalLog;
    private YamlFarmStorage yamlStorage;

    private @Nullable ScheduledTask harvestHandle;
    private @Nullable ScheduledTask saveHandle;
    private @Nullable ScheduledTask guiHandle;
    private final AtomicBoolean savePending = new AtomicBoolean();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // saveResource logs a warning every boot once the file exists, so ask first.
        if (!getDataFolder().toPath().resolve("messages.yml").toFile().isFile()) {
            saveResource("messages.yml", false);
        }

        this.config = new PluginConfig(getLogger());
        this.messages = new Messages();
        loadConfiguration();

        Path dataFile = getDataFolder().toPath().resolve("data").resolve("farms.yml");
        this.yamlStorage = new YamlFarmStorage(dataFile, getLogger(),
                () -> this.config.warehouseMaxTypes(),
                () -> this.config.warehouseMaxPerType());
        this.yamlStorage.configureBackups(this.config.backupsToKeep(), this.config.backupIntervalMinutes());
        FarmStorage storage = this.yamlStorage;

        this.withdrawalLog = new WithdrawalLog(
                getDataFolder().toPath().resolve("saques.log"), getLogger());
        this.withdrawalLog.setEnabled(this.config.logWithdrawals());

        this.farms = new FarmManager(storage);
        this.farms.load();

        this.farmService = new FarmService(this.farms, this.config);
        this.farmService.setProtection(ProtectionHooks.detect(
                getLogger(), this.config.useLandTrust(), this.config.requiredTrust()));
        this.controllerItem = new ControllerItem(this, this.config, this.messages);
        this.farmService.setControllerItem(this.controllerItem);
        this.harvestTask = new HarvestTask(this, this.farms);
        this.harvestTask.refresh(this.config, this.cropRegistry);

        int purged = this.farmService.purgeExpiredOrphans();
        getLogger().info("Carregadas " + this.farms.size() + " fazendas"
                + (purged > 0 ? " (" + purged + " orfas removidas)" : "") + ".");

        getServer().getPluginManager().registerEvents(new BlockListener(this), this);
        getServer().getPluginManager().registerEvents(new InteractListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register("chunkfarm",
                    "Gerencia as fazendas automaticas por chunk",
                    List.of("cf", "fazenda"),
                    new ChunkFarmCommand(this));
        });

        applyChunkTickets();
        schedule();
    }

    @Override
    public void onDisable() {
        cancelTasks();
        releaseChunkTickets();

        // The GUI slots are a rendering of counters, not items; closing avoids a player
        // clicking a dead menu after a reload.
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof FarmGui) {
                player.closeInventory();
            }
        }

        if (this.farms != null) {
            this.farms.save(this.farms.snapshot());
            getLogger().info("Fazendas salvas.");
        }
    }

    /**
     * Writes the farms to disk soon, off the main thread.
     *
     * <p>The periodic autosave is fine for harvest totals: losing a few minutes of
     * wheat to a crash costs nothing anybody can tell. Moving items <em>out</em> of a
     * warehouse is different — the player's inventory is saved by the server on its
     * own schedule, so a crash between the withdrawal and our next autosave leaves the
     * items in the inventory <em>and</em> back in the warehouse. That is a duplication
     * bug, so anything that takes items out asks for a flush here.
     *
     * <p>Debounced: a burst of clicks collapses into one write.
     */
    public void requestSave() {
        if (this.farms == null || !this.savePending.compareAndSet(false, true)) {
            return;
        }
        getServer().getAsyncScheduler().runDelayed(this, task -> {
            this.savePending.set(false);
            List<ChunkFarm> snapshot = this.farms.snapshot();
            this.farms.save(snapshot);
        }, Math.max(1, this.config.flushDelaySeconds()), TimeUnit.SECONDS);
    }

    /** Re-reads config.yml and messages.yml and re-applies them to everything live. */
    public void reloadEverything() {
        cancelTasks();
        reloadConfig();
        loadConfiguration();

        for (ChunkFarm farm : this.farms.snapshot()) {
            farm.warehouse().resize(this.config.warehouseMaxTypes(), this.config.warehouseMaxPerType());
            // Bounds, crop set and controller material may all have changed.
            farm.abortScan();
        }

        this.harvestTask.refresh(this.config, this.cropRegistry);
        this.yamlStorage.configureBackups(this.config.backupsToKeep(), this.config.backupIntervalMinutes());
        this.withdrawalLog.setEnabled(this.config.logWithdrawals());
        this.farmService.setProtection(ProtectionHooks.detect(
                getLogger(), this.config.useLandTrust(), this.config.requiredTrust()));
        applyChunkTickets();
        closeOpenGuis();
        schedule();
    }

    /**
     * In {@link ChunkReach#ALWAYS} the plugin holds a chunk ticket per farm so they run
     * with nobody online. Every other mode releases them, so switching back in config
     * actually gives the chunks up.
     */
    public void applyChunkTickets() {
        releaseChunkTickets();
        if (this.config.chunkReach() != ChunkReach.ALWAYS) {
            return;
        }

        int cap = this.config.maxAlwaysLoaded();
        int held = 0;
        for (ChunkFarm farm : this.farms.snapshot()) {
            if (!farm.isRunnable()) {
                continue;
            }
            if (held >= cap) {
                getLogger().warning("chunks.mode=ALWAYS: limite de " + cap
                        + " chunks atingido; as fazendas restantes so rodam quando o chunk"
                        + " estiver carregado por outro motivo. Ajuste chunks.max-always-loaded.");
                break;
            }
            World world = farm.world();
            if (world == null) {
                continue;
            }
            world.addPluginChunkTicket(farm.pos().x(), farm.pos().z(), this);
            held++;
        }
        if (held > 0) {
            getLogger().info("Mantendo " + held + " chunk(s) carregado(s) para as fazendas.");
        }
    }

    private void releaseChunkTickets() {
        for (World world : getServer().getWorlds()) {
            world.removePluginChunkTickets(this);
        }
    }

    private void loadConfiguration() {
        this.config.load(getConfig());
        this.cropRegistry = new CropRegistry(this.config.columnKeepBlocks());

        try (InputStream defaults = getResource("messages.yml")) {
            this.messages.load(getDataFolder().toPath().resolve("messages.yml").toFile(), defaults);
        } catch (Exception ex) {
            getLogger().warning("Nao foi possivel ler messages.yml: " + ex.getMessage());
            this.messages.load(getDataFolder().toPath().resolve("messages.yml").toFile(), null);
        }
    }

    private void closeOpenGuis() {
        List<HumanEntity> viewers = new ArrayList<>();
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof FarmGui) {
                viewers.add(player);
            }
        }
        viewers.forEach(HumanEntity::closeInventory);
    }

    private void schedule() {
        int interval = this.config.intervalTicks();
        this.harvestHandle = getServer().getGlobalRegionScheduler()
                .runAtFixedRate(this, this.harvestTask, interval, interval);

        long seconds = this.config.saveIntervalSeconds();
        this.saveHandle = getServer().getAsyncScheduler().runAtFixedRate(this, task -> {
            List<ChunkFarm> snapshot = this.farms.snapshot();
            boolean anyDirty = false;
            for (ChunkFarm farm : snapshot) {
                if (farm.isDirty()) {
                    anyDirty = true;
                    break;
                }
            }
            if (anyDirty) {
                this.farms.save(snapshot);
            }
        }, seconds, seconds, TimeUnit.SECONDS);

        // The warehouse is a live counter; a menu that never moves while a farm runs
        // would look broken. Only players actually looking at one are touched.
        this.guiHandle = getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            for (Player player : getServer().getOnlinePlayers()) {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof FarmGui gui) {
                    gui.redraw();
                }
            }
        }, 20L, 20L);
    }

    private void cancelTasks() {
        if (this.harvestHandle != null) {
            this.harvestHandle.cancel();
            this.harvestHandle = null;
        }
        if (this.saveHandle != null) {
            this.saveHandle.cancel();
            this.saveHandle = null;
        }
        if (this.guiHandle != null) {
            this.guiHandle.cancel();
            this.guiHandle = null;
        }
    }

    public PluginConfig config() {
        return this.config;
    }

    public Messages messages() {
        return this.messages;
    }

    public CropRegistry cropRegistry() {
        return this.cropRegistry;
    }

    public FarmManager farms() {
        return this.farms;
    }

    public FarmService farmService() {
        return this.farmService;
    }

    public ControllerItem controllerItem() {
        return this.controllerItem;
    }

    public HarvestTask harvestTask() {
        return this.harvestTask;
    }

    public WithdrawalLog withdrawalLog() {
        return this.withdrawalLog;
    }
}

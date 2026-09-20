package dev.ivoshow.stress;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import dev.ivoshow.chunkfarm.crop.CropType;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.FarmSettings;
import dev.ivoshow.chunkfarm.util.ChunkPos;
import dev.ivoshow.chunkfarm.warehouse.Warehouse;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Load test, not part of the plugin.
 *
 * <p>Builds {@value #FARMS} farms whose layer range is Y 0 to Y 200 and then watches
 * what the harvest engine costs: its own time on the server thread, and what the
 * server's TPS does around it. Also demonstrates what the chunk-reach setting controls,
 * by measuring with and without the chunks being loaded.
 */
public final class StressPlugin extends JavaPlugin {

    private static final int FARMS = 50;
    /** Crop floors, chosen to put non-empty sections across the whole Y 0..200 range. */
    private static final int[] FLOORS = {100, 150, 195};
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 200;
    private static final UUID TESTER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private ChunkFarmPlugin cf;
    private World world;
    private final List<ChunkFarm> farms = new ArrayList<>();
    private final List<Chunk> chunks = new ArrayList<>();

    private int cropsPlanted;
    private long phaseStart;
    private double worstTps = Double.MAX_VALUE;
    private double worstCycleMs;
    private int samples;
    private double tpsSum;
    private double cycleMsSum;

    @Override
    public void onEnable() {
        this.cf = (ChunkFarmPlugin) Bukkit.getPluginManager().getPlugin("ChunkFarm");
        getServer().getGlobalRegionScheduler().runDelayed(this, t -> start(), 60L);
    }

    private void log(String line) {
        getLogger().info("[STRESS] " + line);
    }

    private void start() {
        this.world = Bukkit.getWorlds().getFirst();
        log("==========================================================");
        log("TESTE DE EFICIENCIA -- " + FARMS + " fazendas, faixa Y " + MIN_Y + ".." + MAX_Y
                + " (" + (MAX_Y - MIN_Y + 1) + " camadas cada)");
        log("mundo " + this.world.getName() + " (" + this.world.getEnvironment() + ")");
        log("==========================================================");
        log("TPS antes de tudo: " + fmt(Bukkit.getServer().getTPS()[0]));

        // Spread the build over ticks; 38k crops in one tick would trip the watchdog.
        buildBatch(0);
    }

    private void buildBatch(int index) {
        int end = Math.min(FARMS, index + 5);
        for (int i = index; i < end; i++) {
            buildFarm(i);
        }
        if (end < FARMS) {
            getServer().getGlobalRegionScheduler().runDelayed(this, t -> buildBatch(end), 1L);
            return;
        }

        log("plantados " + this.cropsPlanted + " cultivos em " + FARMS + " chunks"
                + " (" + (this.cropsPlanted / FARMS) + " por chunk, em " + FLOORS.length + " andares)");
        log("os chunks estao presos por ticket DESTE plugin de teste, entao o ChunkFarm");
        log("continua em modo LOADED e mede so o proprio custo");

        // Generating and populating 50 chunks is a big one-off hit, and the server's
        // TPS is a rolling one-minute average. Measuring right now would blame the
        // plugin for world generation, so settle first and re-ripen everything.
        log("aguardando 70s para a media de TPS limpar o custo de gerar os chunks...");
        getServer().getGlobalRegionScheduler().runDelayed(this, t -> {
            log("TPS ja estabilizada: " + fmt(Bukkit.getServer().getTPS()[0])
                    + " | tick medio " + fmt(Bukkit.getServer().getAverageTickTime()) + " ms");
            int ripened = ripenAll();
            log("re-amadurecidos " + ripened + " cultivos para o pior caso");

            this.cf.harvestTask().resetMetrics();
            this.phaseStart = System.currentTimeMillis();
            log("--- fase 1: tudo maduro de uma vez (pior caso) ---");
            sample(0, 60, "fase 1");
        }, 1400L);
    }

    /** Puts every planted crop back to full growth. */
    private int ripenAll() {
        int count = 0;
        for (int i = 0; i < FARMS; i++) {
            int originX = (100 + i) << 4;
            int originZ = 100 << 4;
            for (int y : FLOORS) {
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        Block crop = this.world.getBlockAt(originX + dx, y, originZ + dz);
                        if (crop.getType() != Material.WHEAT) {
                            continue;
                        }
                        BlockData data = crop.getBlockData();
                        ((Ageable) data).setAge(((Ageable) data).getMaximumAge());
                        crop.setBlockData(data, false);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private void buildFarm(int index) {
        // A straight line of chunks well away from spawn.
        int chunkX = 100 + index;
        int chunkZ = 100;
        Chunk chunk = this.world.getChunkAt(chunkX, chunkZ);
        chunk.addPluginChunkTicket(this);
        this.chunks.add(chunk);

        int originX = chunkX << 4;
        int originZ = chunkZ << 4;
        for (int y : FLOORS) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    this.world.getBlockAt(originX + dx, y - 1, originZ + dz)
                            .setType(Material.FARMLAND, false);
                    Block crop = this.world.getBlockAt(originX + dx, y, originZ + dz);
                    crop.setType(Material.WHEAT, false);
                    BlockData data = crop.getBlockData();
                    ((Ageable) data).setAge(((Ageable) data).getMaximumAge());
                    crop.setBlockData(data, false);
                    this.cropsPlanted++;
                }
            }
        }

        Block controller = this.world.getBlockAt(originX + 8, FLOORS[0] + 4, originZ + 8);
        controller.setType(this.cf.config().controllerMaterial(), false);

        FarmSettings settings = new FarmSettings(MIN_Y, MAX_Y, CropType.ALL);
        ChunkFarm farm = new ChunkFarm(ChunkPos.of(chunk),
                controller.getX(), controller.getY(), controller.getZ(),
                TESTER, "Stress", settings,
                new Warehouse(this.cf.config().warehouseMaxTypes(), this.cf.config().warehouseMaxPerType()));
        this.cf.farms().register(farm);
        this.farms.add(farm);
    }

    /** Samples once a second for {@code ticksLeft/20} seconds, then reports. */
    private void sample(int taken, int total, String phase) {
        double tps = Bukkit.getServer().getTPS()[0];
        double cycleMs = this.cf.harvestTask().lastCycleMillis();

        this.worstTps = Math.min(this.worstTps, tps);
        this.worstCycleMs = Math.max(this.worstCycleMs, cycleMs);
        this.tpsSum += tps;
        this.cycleMsSum += cycleMs;
        this.samples++;

        if (taken < total) {
            getServer().getGlobalRegionScheduler()
                    .runDelayed(this, t -> sample(taken + 1, total, phase), 20L);
            return;
        }
        report(phase);
    }

    private void report(String phase) {
        var task = this.cf.harvestTask();
        long stored = 0;
        int scanned = 0;
        for (ChunkFarm farm : this.farms) {
            stored += farm.warehouse().total();
            scanned += farm.cachedCrops().size();
        }

        log("");
        log("=== RESULTADO " + phase.toUpperCase(Locale.ROOT) + " ===");
        log("duracao                  " + ((System.currentTimeMillis() - this.phaseStart) / 1000) + "s");
        log("fazendas                 " + FARMS + " x " + (MAX_Y - MIN_Y + 1) + " camadas");
        log("cultivos mapeados        " + scanned + " (de " + this.cropsPlanted + " plantados)");
        log("itens guardados          " + stored);
        log("");
        log("CUSTO DO PLUGIN (tempo dentro da propria tarefa, por ciclo de 1s)");
        log("   media                 " + fmt(task.averageCycleMillis()) + " ms");
        log("   pico                  " + fmt(task.peakCycleMillis()) + " ms");
        log("   pior amostra          " + fmt(this.worstCycleMs) + " ms");
        log("   ciclos                " + task.cyclesRun());
        log("   ultimo ciclo          " + task.lastFarmsVisited() + " fazendas trabalhadas, "
                + task.lastFarmsSkipped() + " puladas");
        log("");
        log("SERVIDOR");
        log("   TPS media             " + fmt(this.tpsSum / this.samples));
        log("   TPS minima            " + fmt(this.worstTps));
        log("   tick medio            " + fmt(Bukkit.getServer().getAverageTickTime()) + " ms");
        log("");

        if (phase.equals("fase 1")) {
            resetWindow();
            log("--- fase 2: regime, so o que rebrota no ritmo do vanilla ---");
            this.cf.harvestTask().resetMetrics();
            sample(0, 45, "fase 2");
            return;
        }
        if (phase.equals("fase 2")) {
            reachTest();
            return;
        }
        getServer().getGlobalRegionScheduler().runDelayed(this, t -> Bukkit.shutdown(), 60L);
    }

    /**
     * Shows what the reach setting buys: drop the tickets and nothing is loaded, so
     * with nobody online the plugin does no work at all.
     */
    private void reachTest() {
        log("--- alcance: soltando os chunks (ninguem online, nada carregado) ---");
        for (Chunk chunk : this.chunks) {
            chunk.removePluginChunkTicket(this);
        }
        this.cf.harvestTask().resetMetrics();
        resetWindow();

        getServer().getGlobalRegionScheduler().runDelayed(this, t -> {
            int loaded = 0;
            for (ChunkFarm farm : this.farms) {
                if (this.world.isChunkLoaded(farm.pos().x(), farm.pos().z())) {
                    loaded++;
                }
            }
            var task = this.cf.harvestTask();
            log("");
            log("=== RESULTADO ALCANCE ===");
            log("chunks de fazenda ainda carregados   " + loaded + " de " + FARMS);
            log("custo do plugin por ciclo            " + fmt(task.averageCycleMillis()) + " ms");
            log("fazendas trabalhadas no ultimo ciclo " + task.lastFarmsVisited());
            log("fazendas puladas no ultimo ciclo     " + task.lastFarmsSkipped());
            log("TPS                                  " + fmt(Bukkit.getServer().getTPS()[0]));
            log("");
            log("Ou seja: fazenda em chunk descarregado custa uma checagem e nada mais.");
            log("==========================================================");
            getServer().getGlobalRegionScheduler().runDelayed(this, t2 -> Bukkit.shutdown(), 60L);
        }, 400L);
    }

    private void resetWindow() {
        this.worstTps = Double.MAX_VALUE;
        this.worstCycleMs = 0;
        this.tpsSum = 0;
        this.cycleMsSum = 0;
        this.samples = 0;
        this.phaseStart = System.currentTimeMillis();
    }

    private static String fmt(double value) {
        return String.format(Locale.of("pt", "BR"), "%.2f", value);
    }
}

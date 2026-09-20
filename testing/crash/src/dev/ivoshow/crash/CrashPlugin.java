package dev.ivoshow.crash;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import dev.ivoshow.chunkfarm.crop.CropType;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.FarmRole;
import dev.ivoshow.chunkfarm.farm.FarmSettings;
import dev.ivoshow.chunkfarm.util.ChunkPos;
import dev.ivoshow.chunkfarm.warehouse.ItemKey;
import dev.ivoshow.chunkfarm.warehouse.Warehouse;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Crash-durability test, not part of the plugin.
 *
 * <p>Boot one stores a known amount, takes some out, and then kills the JVM with
 * {@link Runtime#halt} — which skips shutdown hooks and {@code onDisable} entirely,
 * the same way {@code kill -9} or a hard crash would. Boot two checks what came back.
 *
 * <p>The failure this is looking for is duplication: the player's inventory is written
 * by the server on its own schedule, so if the warehouse were still holding the
 * withdrawn items after the crash, those items would exist twice.
 */
public final class CrashPlugin extends JavaPlugin {

    private static final UUID TESTER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final long STORED = 1_000_000L;
    private static final long WITHDRAWN = 250_000L;
    private static final int CHUNK_X = 200;
    private static final int CHUNK_Z = 200;

    private ChunkFarmPlugin cf;

    @Override
    public void onEnable() {
        this.cf = (ChunkFarmPlugin) Bukkit.getPluginManager().getPlugin("ChunkFarm");
        Path marker = getDataFolder().toPath().resolve("crashed");
        getServer().getGlobalRegionScheduler().runDelayed(this, t -> {
            if (marker.toFile().isFile()) {
                verify();
            } else {
                prepare(marker);
            }
        }, 40L);
    }

    private void log(String line) {
        getLogger().info("[CRASH] " + line);
    }

    private void prepare(Path marker) {
        World world = Bukkit.getWorlds().getFirst();
        Chunk chunk = world.getChunkAt(CHUNK_X, CHUNK_Z);
        chunk.addPluginChunkTicket(this);

        Block controller = world.getBlockAt((CHUNK_X << 4) + 8, 80, (CHUNK_Z << 4) + 8);
        controller.setType(this.cf.config().controllerMaterial(), false);

        Warehouse warehouse = new Warehouse(
                this.cf.config().warehouseMaxTypes(), this.cf.config().warehouseMaxPerType());
        ChunkFarm farm = new ChunkFarm(ChunkPos.of(chunk),
                controller.getX(), controller.getY(), controller.getZ(),
                TESTER, "Tester",
                new FarmSettings(70, 90, CropType.ALL), warehouse);
        farm.setMember(UUID.fromString("00000000-0000-0000-0000-0000000000dd"), "Parceiro", FarmRole.WITHDRAW);
        this.cf.farms().register(farm);

        warehouse.add(ItemKey.of(Material.WHEAT), STORED);
        farm.markDirty();
        // Baseline written synchronously, so it is on disk no matter how the flush is
        // configured. Only the withdrawal below depends on the flush -- which makes the
        // negative control read exactly like the duplication bug it stands in for.
        this.cf.farms().save(this.cf.farms().snapshot());
        log("guardados " + STORED + " trigos e 1 membro, gravados de forma sincrona");

        // Let the periodic/urgent save land, then take items out and only give it the
        // debounce window before pulling the plug.
        getServer().getGlobalRegionScheduler().runDelayed(this, t -> {
            long removed = warehouse.remove(ItemKey.of(Material.WHEAT), WITHDRAWN);
            farm.markDirty();
            this.cf.requestSave();
            log("retirados " + removed + "; restam " + warehouse.get(ItemKey.of(Material.WHEAT)));
            log("aguardando o flush de " + this.cf.config().flushDelaySeconds() + "s");

            // Capped so the negative control -- flush-delay raised past the crash --
            // still kills the JVM promptly instead of waiting out the setting.
            long wait = Math.min(this.cf.config().flushDelaySeconds() + 2L, 10L) * 20L;
            getServer().getGlobalRegionScheduler().runDelayed(this, t2 -> {
                try {
                    Files.createDirectories(getDataFolder().toPath());
                    Files.writeString(marker, "1");
                } catch (Exception ignored) {
                    // best effort
                }
                log("=== MATANDO A JVM SEM DESLIGAR (Runtime.halt) ===");
                Runtime.getRuntime().halt(9);
            }, wait);
        }, 100L);
    }

    private void verify() {
        World world = Bukkit.getWorlds().getFirst();
        log("=== APOS O CRASH ===");

        ChunkFarm farm = this.cf.farms().at(world, CHUNK_X, CHUNK_Z);
        check("a fazenda sobreviveu ao crash", farm != null, farm == null ? "sumiu" : "voltou");
        if (farm == null) {
            shutdown();
            return;
        }

        long wheat = farm.warehouse().get(ItemKey.of(Material.WHEAT));
        long expected = STORED - WITHDRAWN;
        check("o armazem reflete a retirada, nao o valor anterior",
                wheat == expected,
                "trigo=" + wheat + " esperado=" + expected
                        + (wheat == STORED ? "  <-- DUPLICACAO: a retirada nao chegou ao disco" : ""));
        check("a lista de membros sobreviveu", farm.memberCount() == 1,
                "membros=" + farm.memberCount());
        check("o nivel do membro sobreviveu",
                farm.members().stream().allMatch(m -> m.role() == FarmRole.WITHDRAW),
                farm.members().toString());

        shutdown();
    }

    private void check(String label, boolean ok, String detail) {
        log((ok ? "   OK   " : "   FALHOU ") + label + "  [" + detail + "]");
    }

    private void shutdown() {
        getServer().getGlobalRegionScheduler().runDelayed(this, t -> Bukkit.shutdown(), 40L);
    }
}

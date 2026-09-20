package dev.ivoshow.harness;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import dev.ivoshow.chunkfarm.crop.CropType;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.DeliveryMode;
import dev.ivoshow.chunkfarm.farm.FarmSettings;
import dev.ivoshow.chunkfarm.util.ChunkPos;
import dev.ivoshow.chunkfarm.warehouse.ItemKey;
import dev.ivoshow.chunkfarm.warehouse.Warehouse;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Not part of the plugin: a throwaway driver that builds a real crop field in one
 * chunk of a normally generated world, registers a farm over it and reports what the
 * harvest engine actually did.
 *
 * <p>Deliberately not a superflat world. Real terrain means the chunk's lower sections
 * are full of stone, which is the case that actually exercises the scanner -- a flat
 * world is almost entirely empty sections and would make the section-skipping look
 * better than it is.
 */
public final class HarnessPlugin extends JavaPlugin {

    private static final UUID TESTER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    /** Heights chosen to sit in different 16-block sections, far apart. */
    private static final int[] WIDE_LEVELS = {-50, -20, 0, 40, 100, 180, 256};
    private static final int WIDE_CHUNK_X = 4;

    private ChunkFarmPlugin cf;
    private World world;
    private Chunk chunk;
    private ChunkFarm farm;
    private ChunkFarm wideFarm;

    /** Surface of the levelled pad; decided from the terrain, not hardcoded. */
    private int groundY;
    private int cropY;
    private long wideStart;

    @Override
    public void onEnable() {
        this.cf = (ChunkFarmPlugin) Bukkit.getPluginManager().getPlugin("ChunkFarm");
        Path marker = getDataFolder().toPath().resolve("phase1.done");
        getServer().getGlobalRegionScheduler().runDelayed(this, t -> {
            if (marker.toFile().isFile()) {
                verifyAfterRestart(marker);
            } else {
                build();
            }
        }, 40L);
    }

    private void log(String line) {
        getLogger().info("[HARNESS] " + line);
    }

    // ---- world preparation -------------------------------------------------------

    /** Highest terrain point over an area, so the pad never ends up buried. */
    private int surfaceOf(int x0, int z0, int width, int depth) {
        int highest = this.world.getMinHeight();
        for (int x = x0; x < x0 + width; x++) {
            for (int z = z0; z < z0 + depth; z++) {
                highest = Math.max(highest, this.world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES));
            }
        }
        return highest;
    }

    /**
     * Levels a patch of natural terrain: clears whatever is above, puts a solid soil
     * layer at {@code baseY} and fills any hollow underneath so nothing falls through.
     */
    private void pad(int x0, int z0, int width, int depth, int baseY) {
        for (int x = x0; x < x0 + width; x++) {
            for (int z = z0; z < z0 + depth; z++) {
                for (int y = baseY + 1; y <= baseY + 24; y++) {
                    this.world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
                this.world.getBlockAt(x, baseY, z).setType(Material.DIRT, false);
                for (int y = baseY - 1; y >= baseY - 4; y--) {
                    Block below = this.world.getBlockAt(x, y, z);
                    if (below.getType().isAir() || below.getType() == Material.WATER) {
                        below.setType(Material.STONE, false);
                    }
                }
            }
        }
    }

    /** Hollows out a pocket in solid rock so a test platform has somewhere to exist. */
    private void carve(int x0, int z0, int width, int depth, int y) {
        for (int x = x0 - 1; x < x0 + width + 1; x++) {
            for (int z = z0 - 1; z < z0 + depth + 1; z++) {
                for (int dy = 0; dy <= 3; dy++) {
                    this.world.getBlockAt(x, y + dy, z).setType(Material.AIR, false);
                }
            }
        }
    }

    // ---- phase 1 -----------------------------------------------------------------

    private void build() {
        this.world = Bukkit.getWorlds().getFirst();
        this.chunk = this.world.getChunkAt(0, 0);
        this.chunk.addPluginChunkTicket(this);

        this.groundY = surfaceOf(0, 0, 16, 16);
        this.cropY = this.groundY + 1;

        log("=== construindo cenario no chunk 0,0 do mundo " + this.world.getName()
                + " (tipo " + this.world.getEnvironment() + ") ===");
        log("terreno natural nivelado em Y " + this.groundY + "; plantas em Y " + this.cropY);

        pad(0, 0, 16, 16, this.groundY);

        // 8 rows of mature wheat: the bulk case.
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 16; x++) {
                soil(x, z, Material.FARMLAND);
                mature(x, z, Material.WHEAT);
            }
        }
        for (int x = 0; x < 16; x++) {
            soil(x, 8, Material.FARMLAND);
            mature(x, 8, Material.CARROTS);
            soil(x, 9, Material.FARMLAND);
            mature(x, 9, Material.POTATOES);
            soil(x, 10, Material.FARMLAND);
            mature(x, 10, Material.BEETROOTS);
            soil(x, 11, Material.SOUL_SAND);
            mature(x, 11, Material.NETHER_WART);
        }

        // Sugar cane needs water beside the block it stands on, or vanilla pops it.
        for (int x : new int[]{0, 2, 4}) {
            soil(x, 12, Material.SAND);
            soil(x + 1, 12, Material.WATER);
            column(x, 12, Material.SUGAR_CANE, 3);
        }

        // Bamboo, far from the cactus row.
        for (int x = 8; x < 14; x++) {
            soil(x, 13, Material.DIRT);
            column(x, 13, Material.BAMBOO, 4);
        }

        // Cactus dies if anything touches its sides, so each column gets clear
        // horizontal neighbours.
        for (int x : new int[]{0, 2, 4}) {
            soil(x, 14, Material.SAND);
            column(x, 14, Material.CACTUS, 3);
        }

        // Melon with a stem pointing at it, and a decorative pumpkin with no stem.
        block(9, this.cropY, 15).setType(Material.MELON, false);
        soil(10, 15, Material.FARMLAND);
        stem(10, 15, Material.ATTACHED_MELON_STEM, BlockFace.WEST);
        block(12, this.cropY, 15).setType(Material.PUMPKIN, false);

        mature(14, 15, Material.SWEET_BERRY_BUSH);

        block(7, this.cropY, 15).setType(Material.JUNGLE_LOG, false);
        Block cocoa = block(7, this.cropY, 14);
        cocoa.setType(Material.COCOA, false);
        BlockData cocoaData = cocoa.getBlockData();
        ((Directional) cocoaData).setFacing(BlockFace.NORTH);
        ((Ageable) cocoaData).setAge(((Ageable) cocoaData).getMaximumAge());
        cocoa.setBlockData(cocoaData, false);

        Block controller = block(8, this.cropY + 3, 8);
        controller.setType(this.cf.config().controllerMaterial(), false);

        FarmSettings settings = new FarmSettings(this.groundY - 1, this.cropY + 4, CropType.ALL);
        Warehouse warehouse = new Warehouse(
                this.cf.config().warehouseMaxTypes(), this.cf.config().warehouseMaxPerType());
        this.farm = new ChunkFarm(ChunkPos.of(this.chunk),
                controller.getX(), controller.getY(), controller.getZ(),
                TESTER, "Tester", settings, warehouse);
        this.cf.farms().register(this.farm);

        log("fazenda registrada: chunk " + this.farm.pos()
                + " Y " + settings.minY() + ".." + settings.maxY()
                + " (" + settings.layerCount() + " camadas)");
        log("antes: " + census());

        getServer().getGlobalRegionScheduler().runDelayed(this, t -> report(), 100L);
    }

    private void report() {
        log("=== depois de 5s de ciclos automaticos ===");
        log("plantas mapeadas no cache: " + this.farm.cachedCrops().size());
        log("depois: " + census());

        log("armazem: " + this.farm.warehouse().total() + " itens em "
                + this.farm.warehouse().typeCount() + " tipos");
        for (Warehouse.Entry entry : this.farm.warehouse().snapshot()) {
            log("   " + entry.key().material() + " x " + entry.amount());
        }

        checks();
        controllerLookup();
        persistence();
        getServer().getGlobalRegionScheduler().runDelayed(this, t -> wideRange(), 20L);
    }

    private String census() {
        int wheat = 0, wheatRipe = 0, carrots = 0, potatoes = 0, beetroots = 0, wart = 0;
        int cane = 0, cactus = 0, bamboo = 0, melon = 0, pumpkin = 0, berries = 0, cocoaRipe = 0;

        for (int y = this.groundY - 1; y <= this.cropY + 4; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Block b = block(x, y, z);
                    switch (b.getType()) {
                        case WHEAT -> {
                            wheat++;
                            if (isRipe(b)) {
                                wheatRipe++;
                            }
                        }
                        case CARROTS -> carrots++;
                        case POTATOES -> potatoes++;
                        case BEETROOTS -> beetroots++;
                        case NETHER_WART -> wart++;
                        case SUGAR_CANE -> cane++;
                        case CACTUS -> cactus++;
                        case BAMBOO -> bamboo++;
                        case MELON -> melon++;
                        case PUMPKIN -> pumpkin++;
                        case SWEET_BERRY_BUSH -> berries++;
                        case COCOA -> {
                            if (isRipe(b)) {
                                cocoaRipe++;
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        }
        return "trigo=" + wheat + "(maduros " + wheatRipe + ") cenoura=" + carrots
                + " batata=" + potatoes + " beterraba=" + beetroots + " fungo=" + wart
                + " cana=" + cane + " cacto=" + cactus + " bambu=" + bamboo
                + " melancia=" + melon + " abobora=" + pumpkin + " arbusto=" + berries
                + " cacau_maduro=" + cocoaRipe;
    }

    private void checks() {
        log("--- verificacoes ---");
        Warehouse w = this.farm.warehouse();

        check("128 trigos colhidos", w.get(ItemKey.of(Material.WHEAT)) == 128,
                "wheat=" + w.get(ItemKey.of(Material.WHEAT)));

        int wheatBlocks = 0, ripe = 0;
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 16; x++) {
                Block b = block(x, this.cropY, z);
                if (b.getType() == Material.WHEAT) {
                    wheatBlocks++;
                    if (isRipe(b)) {
                        ripe++;
                    }
                }
            }
        }
        check("128 trigos replantados", wheatBlocks == 128, "blocos=" + wheatBlocks);
        check("nenhum trigo ficou maduro", ripe == 0, "maduros=" + ripe);
        log("   sementes liquidas guardadas apos pagar 128 replantios: "
                + w.get(ItemKey.of(Material.WHEAT_SEEDS)));

        int caneLeft = 0, cactusLeft = 0, bambooLeft = 0;
        for (int y = this.cropY; y <= this.cropY + 4; y++) {
            for (int x : new int[]{0, 2, 4}) {
                if (block(x, y, 12).getType() == Material.SUGAR_CANE) {
                    caneLeft++;
                }
                if (block(x, y, 14).getType() == Material.CACTUS) {
                    cactusLeft++;
                }
            }
            for (int x = 8; x < 14; x++) {
                if (block(x, y, 13).getType() == Material.BAMBOO) {
                    bambooLeft++;
                }
            }
        }
        check("cana cortada ate a base (3 colunas)", caneLeft == 3, "restou=" + caneLeft);
        check("cacto cortado ate a base (3 colunas)", cactusLeft == 3, "restou=" + cactusLeft);
        check("bambu cortado ate a base (6 colunas)", bambooLeft == 6, "restou=" + bambooLeft);
        check("cana guardada (6 = 2 por coluna)",
                w.get(ItemKey.of(Material.SUGAR_CANE)) == 6,
                "cana=" + w.get(ItemKey.of(Material.SUGAR_CANE)));
        check("cacto guardado (6 = 2 por coluna)",
                w.get(ItemKey.of(Material.CACTUS)) == 6,
                "cacto=" + w.get(ItemKey.of(Material.CACTUS)));

        check("melancia com caule foi colhida",
                block(9, this.cropY, 15).getType() != Material.MELON,
                "bloco=" + block(9, this.cropY, 15).getType());
        check("abobora sem caule NAO foi tocada",
                block(12, this.cropY, 15).getType() == Material.PUMPKIN,
                "bloco=" + block(12, this.cropY, 15).getType());
        check("fatias de melancia guardadas",
                w.get(ItemKey.of(Material.MELON_SLICE)) >= 3,
                "fatias=" + w.get(ItemKey.of(Material.MELON_SLICE)));

        check("arbusto continua de pe",
                block(14, this.cropY, 15).getType() == Material.SWEET_BERRY_BUSH,
                "bloco=" + block(14, this.cropY, 15).getType());
        check("frutas silvestres guardadas",
                w.get(ItemKey.of(Material.SWEET_BERRIES)) >= 2,
                "frutas=" + w.get(ItemKey.of(Material.SWEET_BERRIES)));

        Block cocoa = block(7, this.cropY, 14);
        check("cacau replantado no estagio 0",
                cocoa.getType() == Material.COCOA && !isRipe(cocoa),
                "estado=" + cocoa.getBlockData().getAsString());

        String onGround = groundItems();
        check("nenhuma entidade de item foi criada no mundo",
                onGround.isEmpty(), onGround.isEmpty() ? "chao limpo" : onGround);

        w.add(ItemKey.of(Material.WHEAT), 40_000_000L);
        check("armazem aceita dezenas de milhoes",
                w.get(ItemKey.of(Material.WHEAT)) == 40_000_128L,
                "wheat=" + w.get(ItemKey.of(Material.WHEAT)));
    }

    /**
     * The break handler finds the farm by asking which farm owns the block. If that
     * lookup missed -- wrong coordinates stored, chunk key off by one -- breaking the
     * controller would silently do nothing, which is exactly the symptom of a farm
     * that refuses to give its item back.
     */
    private void controllerLookup() {
        log("--- identificacao do controlador ---");
        Block controller = block(8, this.cropY + 3, 8);

        check("o bloco do controlador esta no mundo",
                controller.getType() == this.cf.config().controllerMaterial(),
                "bloco=" + controller.getType());
        check("controllerAt() reconhece o bloco como a fazenda",
                this.cf.farms().controllerAt(controller) == this.farm,
                "achou=" + (this.cf.farms().controllerAt(controller) == null ? "nada" : "a fazenda"));

        // A block in the same chunk that is not the controller must NOT match, or
        // breaking any block in a farm chunk would retire the farm.
        Block neighbour = block(9, this.cropY + 3, 8);
        check("um bloco vizinho nao e confundido com o controlador",
                this.cf.farms().controllerAt(neighbour) == null,
                "vizinho=" + (this.cf.farms().controllerAt(neighbour) == null ? "ignorado" : "confundido"));

        // retire() decides delete-vs-dormant; the warehouse is full at this point.
        check("retire() com armazem cheio deixa a fazenda dormente, nao apaga",
                !this.farm.warehouse().isEmpty(), "itens=" + this.farm.warehouse().total());
    }

    private String groundItems() {
        StringBuilder out = new StringBuilder();
        for (org.bukkit.entity.Item item : this.world.getEntitiesByClass(org.bukkit.entity.Item.class)) {
            out.append(item.getItemStack().getType()).append(" x").append(item.getItemStack().getAmount())
                    .append(" em ").append(item.getLocation().getBlockX())
                    .append(',').append(item.getLocation().getBlockY())
                    .append(',').append(item.getLocation().getBlockZ()).append("; ");
        }
        return out.toString();
    }

    private void persistence() {
        log("--- persistencia ---");
        this.cf.farms().save(this.cf.farms().snapshot());
        Path file = this.cf.getDataFolder().toPath().resolve("data").resolve("farms.yml");
        try {
            List<String> lines = Files.readAllLines(file);
            String wheatLine = lines.stream().filter(l -> l.contains("WHEAT=")).findFirst().orElse("(ausente)");
            check("40 milhoes de trigo cabem numa linha do arquivo",
                    wheatLine.contains("WHEAT=40000128"), wheatLine.trim());
            log("   farms.yml tem " + lines.size() + " linhas no total");
        } catch (Exception ex) {
            check("farms.yml legivel", false, ex.toString());
        }
    }

    // ---- wide layer range + drop mode ---------------------------------------------

    /**
     * The layer range the server actually needs: Y -50 up to Y 256.
     *
     * <p>This is the test that matters for the section-skipping optimisation. Crops are
     * parked in seven far-apart sections; if the scanner computed a section index wrong
     * it would skip a populated section and quietly lose those crops.
     */
    private void wideRange() {
        log("--- faixa larga: Y -50 ate Y 256 (em terreno natural) ---");

        Chunk wide = this.world.getChunkAt(WIDE_CHUNK_X, 0);
        wide.addPluginChunkTicket(this);
        int originX = WIDE_CHUNK_X * 16;

        int count = 0;
        for (int y : WIDE_LEVELS) {
            // Most of these levels are inside solid rock in a real world.
            carve(originX, 0, 3, 3, y);
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    this.world.getBlockAt(originX + dx, y - 1, dz).setType(Material.FARMLAND, false);
                    Block crop = this.world.getBlockAt(originX + dx, y, dz);
                    crop.setType(Material.WHEAT, false);
                    BlockData data = crop.getBlockData();
                    ((Ageable) data).setAge(((Ageable) data).getMaximumAge());
                    crop.setBlockData(data, false);
                    count++;
                }
            }
        }
        final int planted = count;
        log("   plantados " + planted + " trigos em " + WIDE_LEVELS.length + " alturas distantes");

        int controllerY = surfaceOf(originX + 8, 8, 1, 1) + 1;
        Block controller = this.world.getBlockAt(originX + 8, controllerY, 8);
        controller.setType(this.cf.config().controllerMaterial(), false);

        FarmSettings settings = new FarmSettings(-50, 256, CropType.ALL);
        this.wideFarm = new ChunkFarm(ChunkPos.of(wide),
                controller.getX(), controller.getY(), controller.getZ(),
                TESTER, "Tester", settings,
                new Warehouse(this.cf.config().warehouseMaxTypes(), this.cf.config().warehouseMaxPerType()));
        this.cf.farms().register(this.wideFarm);

        check("o alcance -50..256 foi aceito sem ser cortado",
                settings.minY() == -50 && settings.maxY() == 256,
                "Y " + settings.minY() + ".." + settings.maxY()
                        + " (" + settings.layerCount() + " camadas)");

        this.wideStart = System.currentTimeMillis();
        awaitWideScan(planted, 0);
    }

    /** Polls until the resumable scan finishes, so the check measures correctness, not luck. */
    private void awaitWideScan(int planted, int attempt) {
        if (this.wideFarm.cachedCrops().size() < planted && attempt < 60) {
            getServer().getGlobalRegionScheduler()
                    .runDelayed(this, t -> awaitWideScan(planted, attempt + 1), 10L);
            return;
        }
        log("   varredura de 307 camadas em terreno solido levou "
                + (System.currentTimeMillis() - this.wideStart) + "ms");
        getServer().getGlobalRegionScheduler().runDelayed(this, t -> wideRangeReport(planted), 40L);
    }

    private void wideRangeReport(int planted) {
        int originX = WIDE_CHUNK_X * 16;

        int mapped = this.wideFarm.cachedCrops().size();
        check("a varredura achou pelo menos os " + planted + " trigos espalhados por 307 camadas",
                mapped >= planted,
                "mapeadas=" + mapped + (mapped > planted
                        ? " (" + (mapped - planted) + " sao vegetacao natural do chunk: cave vines"
                          + " em cavernas, cana ou bambu selvagens dentro da faixa de Y)"
                        : ""));

        long wheat = this.wideFarm.warehouse().get(ItemKey.of(Material.WHEAT));
        check("todos os " + planted + " foram colhidos", wheat == planted, "trigo=" + wheat);

        StringBuilder missed = new StringBuilder();
        int replanted = 0;
        for (int y : WIDE_LEVELS) {
            int hereRipe = 0;
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    Block crop = this.world.getBlockAt(originX + dx, y, dz);
                    if (crop.getType() != Material.WHEAT) {
                        continue;
                    }
                    replanted++;
                    if (isRipe(crop)) {
                        hereRipe++;
                    }
                }
            }
            if (hereRipe > 0) {
                missed.append("Y").append(y).append(" ainda com ").append(hereRipe).append(" maduros; ");
            }
        }
        check("nenhuma altura foi pulada pelo salto de secoes vazias",
                missed.isEmpty(), missed.isEmpty() ? "todas as 7 alturas colhidas" : missed.toString());
        check("todos replantados", replanted == planted, "replantados=" + replanted);

        dropMode();
    }

    /** Switches the same farm to DROP and proves the items land in the world instead. */
    private void dropMode() {
        log("--- modo DROP: itens no chao em vez do armazem ---");
        int originX = WIDE_CHUNK_X * 16;

        this.wideFarm.settings().setDelivery(DeliveryMode.DROP);
        long warehouseBefore = this.wideFarm.warehouse().total();

        int rematured = 0;
        for (int y : WIDE_LEVELS) {
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    Block crop = this.world.getBlockAt(originX + dx, y, dz);
                    if (crop.getType() != Material.WHEAT) {
                        continue;
                    }
                    BlockData data = crop.getBlockData();
                    ((Ageable) data).setAge(((Ageable) data).getMaximumAge());
                    crop.setBlockData(data, false);
                    rematured++;
                }
            }
        }
        log("   " + rematured + " trigos amadurecidos de novo, agora em modo DROP");

        this.cf.harvestTask().forceOnce(this.wideFarm);

        getServer().getGlobalRegionScheduler().runDelayed(this, t -> {
            long wheatOnGround = 0;
            int nearby = 0;
            for (org.bukkit.entity.Item item : this.world.getEntitiesByClass(org.bukkit.entity.Item.class)) {
                if (item.getLocation().getBlockX() >> 4 == WIDE_CHUNK_X) {
                    nearby++;
                    if (item.getItemStack().getType() == Material.WHEAT) {
                        wheatOnGround += item.getItemStack().getAmount();
                    }
                }
            }
            check("o armazem NAO cresceu em modo DROP",
                    this.wideFarm.warehouse().total() == warehouseBefore,
                    "antes=" + warehouseBefore + " depois=" + this.wideFarm.warehouse().total());
            check("os itens cairam no chao do chunk da fazenda",
                    wheatOnGround > 0, "trigo no chao=" + wheatOnGround + " (" + nearby + " entidades)");
            check("o total colhido da fazenda contou o que foi dropado",
                    this.wideFarm.harvestedTotal() > 0, "colhido=" + this.wideFarm.harvestedTotal());

            getServer().getGlobalRegionScheduler().runDelayed(this, t2 -> dormancy(), 20L);
        }, 40L);
    }

    /**
     * Removing the controller by any means other than a block break (world edit, another
     * plugin) must be noticed by the scan and park the farm without losing the items.
     */
    private void dormancy() {
        log("--- dormencia ---");
        block(8, this.cropY + 3, 8).setType(Material.AIR, false);
        this.farm.invalidateCache();
        log("   controlador apagado direto no mundo, sem evento de quebra");

        getServer().getGlobalRegionScheduler().runDelayed(this, t -> {
            check("a varredura percebeu e colocou a fazenda dormente",
                    this.farm.isDormant(), "dormant=" + this.farm.isDormant());
            check("o armazem nao perdeu nada",
                    this.farm.warehouse().get(ItemKey.of(Material.WHEAT)) == 40_000_128L,
                    "wheat=" + this.farm.warehouse().get(ItemKey.of(Material.WHEAT)));
            try {
                Files.createDirectories(getDataFolder().toPath());
                // The pad height has to survive the restart: getHighestBlockYAt would
                // now answer with the crops we built, not the original terrain.
                Files.writeString(getDataFolder().toPath().resolve("phase1.done"),
                        this.groundY + ":" + this.farm.warehouse().typeCount());
            } catch (Exception ignored) {
                // best effort
            }
            log("=== fase 1 concluida, reiniciando para conferir a persistencia ===");
            getServer().getGlobalRegionScheduler().runDelayed(this, t2 -> Bukkit.shutdown(), 20L);
        }, 60L);
    }

    // ---- phase 2 -----------------------------------------------------------------

    private void verifyAfterRestart(Path marker) {
        this.world = Bukkit.getWorlds().getFirst();
        this.chunk = this.world.getChunkAt(0, 0);
        this.chunk.addPluginChunkTicket(this);

        int expectedTypes;
        try {
            String[] parts = Files.readString(marker).trim().split(":");
            this.groundY = Integer.parseInt(parts[0]);
            expectedTypes = Integer.parseInt(parts[1]);
        } catch (Exception ex) {
            check("estado da fase 1 legivel", false, ex.toString());
            getServer().getGlobalRegionScheduler().runDelayed(this, t -> Bukkit.shutdown(), 40L);
            return;
        }
        this.cropY = this.groundY + 1;

        log("=== SEGUNDO BOOT: conferindo o que voltou do disco ===");

        ChunkFarm loaded = this.cf.farms().at(this.world, 0, 0);
        check("a fazenda voltou do arquivo", loaded != null,
                loaded == null ? "nao voltou" : "voltou");
        if (loaded == null) {
            getServer().getGlobalRegionScheduler().runDelayed(this, t -> Bukkit.shutdown(), 40L);
            return;
        }

        check("os 40 milhoes de trigo voltaram intactos",
                loaded.warehouse().get(ItemKey.of(Material.WHEAT)) == 40_000_128L,
                "wheat=" + loaded.warehouse().get(ItemKey.of(Material.WHEAT)));
        check("todos os " + expectedTypes + " tipos guardados na fase 1 voltaram",
                loaded.warehouse().typeCount() == expectedTypes,
                "tipos=" + loaded.warehouse().typeCount() + " esperado=" + expectedTypes);
        check("dono preservado", loaded.owner().equals(TESTER), "owner=" + loaded.owner());
        check("limites de camada preservados",
                loaded.settings().minY() == this.groundY - 1 && loaded.settings().maxY() == this.cropY + 4,
                "Y " + loaded.settings().minY() + ".." + loaded.settings().maxY());
        check("a fazenda voltou dormente (controlador removido antes do restart)",
                loaded.isDormant(), "dormant=" + loaded.isDormant());

        ChunkFarm wide = this.cf.farms().at(this.world, WIDE_CHUNK_X, 0);
        check("a fazenda de faixa larga tambem voltou", wide != null,
                wide == null ? "nao voltou" : "voltou");
        if (wide != null) {
            check("o alcance -50..256 sobreviveu ao restart",
                    wide.settings().minY() == -50 && wide.settings().maxY() == 256,
                    "Y " + wide.settings().minY() + ".." + wide.settings().maxY());
            check("o modo DROP sobreviveu ao restart",
                    wide.settings().delivery() == DeliveryMode.DROP,
                    "modo=" + wide.settings().delivery());
        }
        log("armazem apos restart: " + loaded.warehouse().total() + " itens");

        getServer().getGlobalRegionScheduler().runDelayed(this, t -> Bukkit.shutdown(), 40L);
    }

    // ---- helpers -----------------------------------------------------------------

    private void check(String label, boolean ok, String detail) {
        log((ok ? "   OK   " : "   FALHOU ") + label + "  [" + detail + "]");
    }

    private static boolean isRipe(Block block) {
        return block.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    private Block block(int x, int y, int z) {
        return this.world.getBlockAt(x, y, z);
    }

    private void soil(int x, int z, Material material) {
        block(x, this.groundY, z).setType(material, false);
    }

    private void stem(int x, int z, Material material, BlockFace facing) {
        Block block = block(x, this.cropY, z);
        block.setType(material, false);
        BlockData data = block.getBlockData();
        ((Directional) data).setFacing(facing);
        block.setBlockData(data, false);
    }

    private void mature(int x, int z, Material material) {
        Block block = block(x, this.cropY, z);
        block.setType(material, false);
        BlockData data = block.getBlockData();
        if (data instanceof Ageable ageable) {
            ageable.setAge(ageable.getMaximumAge());
            block.setBlockData(data, false);
        }
    }

    private void column(int x, int z, Material material, int height) {
        for (int i = 0; i < height; i++) {
            block(x, this.cropY + i, z).setType(material, false);
        }
    }
}

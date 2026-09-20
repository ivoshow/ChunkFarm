package dev.ivoshow.playground;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sandbox for hands-on testing, on a normally generated world: levels a patch of real
 * terrain, plants a field of ready crops on it, and sets every joining player up with
 * creative, op and a handful of controllers.
 */
public final class PlaygroundPlugin extends JavaPlugin implements Listener {

    /** Heights for the wide-range demo chunk, spread across far-apart sections. */
    private static final int[] WIDE_LEVELS = {-50, 0, 60, 120, 200, 256};

    private World world;
    private int ground;
    private int crop;
    /** Only the first player is opped; an op would bypass claims and admin checks. */
    private boolean firstJoinTaken;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getGlobalRegionScheduler().runDelayed(this, t -> buildWorld(), 20L);
    }

    private void buildWorld() {
        this.world = Bukkit.getWorlds().getFirst();

        this.world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        this.world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        this.world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        // Crops regrow fast enough to watch the warehouse count climb live.
        this.world.setGameRule(GameRule.RANDOM_TICK_SPEED, 60);
        this.world.setTime(6000);

        // One plateau height for the whole demo area, taken from the tallest natural
        // point so nothing ends up buried.
        this.ground = Math.max(this.world.getSeaLevel() + 1, surfaceOf(0, 0, 48, 48));
        this.crop = this.ground + 1;
        getLogger().info("Terreno natural nivelado em Y " + this.ground);

        pad(0, 0, 48, 32);          // the four crop chunks plus the layered one
        pad(24, 40, 8, 8);          // spawn platform

        // Chunk 0,0 -- wheat, the bulk case.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                soil(x, z, Material.FARMLAND);
                mature(x, z, Material.WHEAT);
            }
        }

        // Chunk 1,0 -- three crops side by side.
        for (int x = 16; x < 32; x++) {
            for (int z = 0; z < 16; z++) {
                soil(x, z, Material.FARMLAND);
                mature(x, z, z < 5 ? Material.CARROTS : z < 11 ? Material.POTATOES : Material.BEETROOTS);
            }
        }

        // Chunk 0,1 -- column crops, each with the room vanilla requires.
        for (int x = 0; x < 16; x += 4) {
            soil(x, 17, Material.SAND);
            soil(x + 1, 17, Material.WATER);
            column(x, 17, Material.SUGAR_CANE, 3);
            soil(x, 21, Material.SAND);
            column(x, 21, Material.CACTUS, 3);
        }
        for (int x = 0; x < 16; x += 2) {
            column(x, 25, Material.BAMBOO, 4);
        }

        // Chunk 1,1 -- the odd ones out.
        for (int x = 16; x < 30; x += 4) {
            block(x, this.crop, 18).setType(Material.MELON, false);
            stem(x + 1, 18, Material.ATTACHED_MELON_STEM, BlockFace.WEST);
            block(x, this.crop, 21).setType(Material.PUMPKIN, false);
            stem(x + 1, 21, Material.ATTACHED_PUMPKIN_STEM, BlockFace.WEST);
        }
        for (int x = 16; x < 32; x++) {
            soil(x, 24, Material.SOUL_SAND);
            mature(x, 24, Material.NETHER_WART);
            mature(x, 27, Material.SWEET_BERRY_BUSH);
        }
        for (int x = 16; x < 32; x += 2) {
            block(x, this.crop, 30).setType(Material.JUNGLE_LOG, false);
            Block cocoa = block(x, this.crop, 29);
            cocoa.setType(Material.COCOA, false);
            BlockData data = cocoa.getBlockData();
            ((Directional) data).setFacing(BlockFace.NORTH);
            ((Ageable) data).setAge(((Ageable) data).getMaximumAge());
            cocoa.setBlockData(data, false);
        }

        // Chunk 2,0 -- three wheat floors close together, for the short layer range.
        for (int level : new int[]{0, 4, 8}) {
            for (int x = 32; x < 48; x++) {
                for (int z = 0; z < 16; z++) {
                    block(x, this.ground + level, z).setType(Material.FARMLAND, false);
                    ripen(block(x, this.crop + level, z), Material.WHEAT);
                }
            }
        }

        // Chunk 3,0 -- wheat parked at wildly separated heights, inside real rock.
        // This is the one for `/cf camadas -50 256`.
        for (int y : WIDE_LEVELS) {
            carve(48, 0, 8, 8, y);
            for (int x = 48; x < 56; x++) {
                for (int z = 0; z < 8; z++) {
                    block(x, y - 1, z).setType(Material.FARMLAND, false);
                    ripen(block(x, y, z), Material.WHEAT);
                }
            }
            // A lit marker so the platforms are findable by eye.
            block(47, y, 0).setType(Material.GLOWSTONE, false);
        }

        for (int x = 24; x < 32; x++) {
            for (int z = 40; z < 48; z++) {
                block(x, this.ground, z).setType(Material.SMOOTH_STONE, false);
            }
        }
        this.world.setSpawnLocation(28, this.crop, 44);

        getLogger().info("Campo de testes pronto. Spawn em 28," + this.crop + ",44");
        census();
    }

    // ---- terrain shaping ---------------------------------------------------------

    private int surfaceOf(int x0, int z0, int width, int depth) {
        int highest = this.world.getMinHeight();
        for (int x = x0; x < x0 + width; x++) {
            for (int z = z0; z < z0 + depth; z++) {
                highest = Math.max(highest,
                        this.world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES));
            }
        }
        return highest;
    }

    /** Clears everything above the plateau and fills any hollow underneath. */
    private void pad(int x0, int z0, int width, int depth) {
        for (int x = x0; x < x0 + width; x++) {
            for (int z = z0; z < z0 + depth; z++) {
                for (int y = this.ground + 1; y <= this.ground + 16; y++) {
                    block(x, y, z).setType(Material.AIR, false);
                }
                block(x, this.ground, z).setType(Material.DIRT, false);
                for (int y = this.ground - 1; y >= this.ground - 6; y--) {
                    Block below = block(x, y, z);
                    if (below.getType().isAir() || below.getType() == Material.WATER) {
                        below.setType(Material.STONE, false);
                    }
                }
            }
        }
    }

    /** Hollows out a pocket in solid rock so a demo platform has somewhere to exist. */
    private void carve(int x0, int z0, int width, int depth, int y) {
        for (int x = x0 - 1; x <= x0 + width; x++) {
            for (int z = z0 - 1; z <= z0 + depth; z++) {
                for (int dy = 0; dy <= 4; dy++) {
                    block(x, y + dy, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private void census() {
        java.util.Map<Material, Integer> counts = new java.util.EnumMap<>(Material.class);
        for (int x = -8; x < 64; x++) {
            for (int z = -8; z < 56; z++) {
                for (int y = this.ground; y <= this.ground + 12; y++) {
                    tally(counts, block(x, y, z).getType());
                }
                for (int y : WIDE_LEVELS) {
                    tally(counts, block(x, y, z).getType());
                }
            }
        }
        StringBuilder out = new StringBuilder("censo do campo:");
        counts.forEach((material, count) -> out.append(' ').append(material).append('=').append(count));
        getLogger().info(out.toString());
    }

    private static void tally(java.util.Map<Material, Integer> counts, Material type) {
        switch (type) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, SUGAR_CANE, CACTUS,
                 BAMBOO, MELON, PUMPKIN, SWEET_BERRY_BUSH, COCOA, SMOOTH_STONE ->
                    counts.merge(type, 1, Integer::sum);
            default -> {
            }
        }
    }

    // ---- player setup ------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ChunkFarmPlugin cf = (ChunkFarmPlugin) Bukkit.getPluginManager().getPlugin("ChunkFarm");

        // Only the first player gets op. A second op would hold chunkfarm.admin and
        // bypass GriefPrevention claims, which makes the shared-farm test meaningless.
        boolean owner = !this.firstJoinTaken;
        this.firstJoinTaken = true;
        player.setOp(owner);
        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.teleport(new Location(this.world, 28.5, this.crop, 44.5));
        player.getInventory().addItem(cf.controllerItem().create(8));
        player.getInventory().addItem(new ItemStack(Material.WHEAT_SEEDS, 64));
        player.getInventory().addItem(new ItemStack(Material.BONE_MEAL, 64));

        MiniMessage mm = MiniMessage.miniMessage();
        for (String line : new String[]{
                "",
                "<gradient:#8fdc63:#3fa34d><bold>Campo de testes do ChunkFarm</bold></gradient>",
                "<gray>Mundo normal, com terreno de verdade. Criativo, 8 Controladores</gray>",
                "<gray>de Fazenda. A plataforma fica em Y " + this.crop + ".</gray>",
                owner
                        ? "<green>Voce entrou primeiro, entao esta OP (dono dos testes).</green>"
                        : "<yellow>Voce NAO esta op de proposito: e assim que da para testar</yellow>",
                "",
                "<yellow>O que tem ao norte:</yellow>",
                "<gray>  chunk 0,0   - campo cheio de trigo</gray>",
                "<gray>  chunk 1,0   - cenoura, batata e beterraba</gray>",
                "<gray>  chunk 0,1   - cana, cacto e bambu</gray>",
                "<gray>  chunk 1,1   - melancia, abobora, fungo, arbusto e cacau</gray>",
                "<gray>  chunk 2,0   <white>3 andares de trigo</white> proximos - camadas curtas</gray>",
                "<gray>  chunk 3,0   <white>trigo em Y -50, 0, 60, 120, 200 e 256</white>, dentro da rocha</gray>",
                "<gray>              (as colunas de glowstone marcam as alturas)</gray>",
                "",
                "<yellow>Como testar:</yellow>",
                "<gray>  1. Coloque um Controlador em qualquer lugar de um chunk</gray>",
                "<gray>  2. Clique direito nele para abrir o armazem</gray>",
                "<gray>  3. O total real fica no <white>nome</white> do item; o numero no canto</gray>",
                "<gray>     do icone e so quanto sai por clique</gray>",
                "<gray>  4. Botao de configuracoes: liga/desliga culturas e ajusta Y</gray>",
                "<gray>  5. No chunk 3,0 coloque um controlador e rode</gray>",
                "<yellow>     /cf camadas -50 256</yellow> <gray>- 307 camadas de uma vez</gray>",
                "<gray>  6. No menu, o botao do barril troca entre guardar no armazem</gray>",
                "<gray>     e <white>dropar no chao</white></gray>",
                "<gray>  7. Quebre o controlador com itens dentro e recoloque: tudo volta</gray>",
                "",
                "<yellow>Teste de dupla com GriefPrevention:</yellow>",
                "<gray>  O dono reivindica o terreno (pa dourada ou <white>/claim</white>) e coloca</gray>",
                "<gray>  o controlador dentro da claim.</gray>",
                "<gray>  Sem confianca, o segundo jogador leva <white>nega</white> ao clicar.</gray>",
                "<gray>  Com </gray><yellow>/containertrust [jogador]</yellow><gray>, ele abre o armazem.</gray>",
                "<gray>  Fora de claim, a fazenda continua so do dono.</gray>",
                "<yellow>  /cf stats</yellow> <gray>mostra qual protecao o plugin encontrou.</gray>",
                "",
                "<gray>randomTickSpeed esta em 60, entao as plantas rebrotam rapido.</gray>",
                "<yellow>/cf info</yellow> <dark_gray>-</dark_gray> <gray>estado da fazenda do chunk atual</gray>",
                ""}) {
            player.sendMessage(line.isEmpty() ? Component.empty() : mm.deserialize(line));
        }
    }

    // ---- helpers -----------------------------------------------------------------

    private Block block(int x, int y, int z) {
        return this.world.getBlockAt(x, y, z);
    }

    private void soil(int x, int z, Material material) {
        block(x, this.ground, z).setType(material, false);
    }

    private void stem(int x, int z, Material material, BlockFace facing) {
        Block block = block(x, this.crop, z);
        block.setType(material, false);
        BlockData data = block.getBlockData();
        ((Directional) data).setFacing(facing);
        block.setBlockData(data, false);
    }

    private void mature(int x, int z, Material material) {
        ripen(block(x, this.crop, z), material);
    }

    private static void ripen(Block block, Material material) {
        block.setType(material, false);
        BlockData data = block.getBlockData();
        if (data instanceof Ageable ageable) {
            ageable.setAge(ageable.getMaximumAge());
            block.setBlockData(data, false);
        }
    }

    private void column(int x, int z, Material material, int height) {
        for (int i = 0; i < height; i++) {
            block(x, this.crop + i, z).setType(material, false);
        }
    }
}

package dev.ivoshow.chunkfarm.crop;

import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Maps a block material to the crop type it belongs to and the handler that knows how
 * to harvest it. Backed by an {@link EnumMap}, so the chunk scanner's per-block lookup
 * is an array index.
 */
public final class CropRegistry {

    private final Map<Material, Binding> byBlock = new EnumMap<>(Material.class);

    /**
     * @param columnKeepBlocks how many blocks to leave standing per column crop
     *                         (sugar cane, cactus, bamboo, kelp)
     */
    public CropRegistry(Map<CropType, Integer> columnKeepBlocks) {
        bind(CropType.WHEAT, new SeedCropHandler(Material.WHEAT_SEEDS));
        bind(CropType.CARROTS, new SeedCropHandler(Material.CARROT));
        bind(CropType.POTATOES, new SeedCropHandler(Material.POTATO));
        bind(CropType.BEETROOTS, new SeedCropHandler(Material.BEETROOT_SEEDS));
        bind(CropType.NETHER_WART, new SeedCropHandler(Material.NETHER_WART));
        bind(CropType.COCOA, new SeedCropHandler(Material.COCOA_BEANS));

        bind(CropType.SWEET_BERRIES, new BerryBushHandler());
        bind(CropType.GLOW_BERRIES, new CaveVinesHandler());

        bind(CropType.MELON, new StemFruitHandler(Material.ATTACHED_MELON_STEM));
        bind(CropType.PUMPKIN, new StemFruitHandler(Material.ATTACHED_PUMPKIN_STEM));

        bindColumn(CropType.SUGAR_CANE, Material.AIR, null, columnKeepBlocks);
        bindColumn(CropType.CACTUS, Material.AIR, null, columnKeepBlocks);
        bindColumn(CropType.BAMBOO, Material.AIR, null, columnKeepBlocks);
        bindColumn(CropType.KELP, Material.WATER, Material.KELP, columnKeepBlocks);
    }

    private void bindColumn(CropType type, Material replacement, @Nullable Material tip,
                            Map<CropType, Integer> keepBlocks) {
        int keep = keepBlocks.getOrDefault(type, 1);
        bind(type, new ColumnCropHandler(type.blocks(), replacement, tip, keep));
    }

    private void bind(CropType type, CropHandler handler) {
        Binding binding = new Binding(type, handler);
        for (Material block : type.blocks()) {
            this.byBlock.put(block, binding);
        }
    }

    public @Nullable Binding lookup(Material blockMaterial) {
        return this.byBlock.get(blockMaterial);
    }

    /** Every block material the scanner should keep an eye on. */
    public Set<Material> trackedBlocks() {
        return this.byBlock.keySet();
    }

    public record Binding(CropType type, CropHandler handler) {
    }
}

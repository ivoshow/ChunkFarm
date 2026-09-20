package dev.ivoshow.chunkfarm.crop;

import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The unit a player turns on and off in the farm's settings. One crop type can cover
 * several block materials (kelp and cave vines each have a body and a tip block).
 */
public enum CropType {

    WHEAT(Material.WHEAT_SEEDS, Material.WHEAT),
    CARROTS(Material.CARROT, Material.CARROTS),
    POTATOES(Material.POTATO, Material.POTATOES),
    BEETROOTS(Material.BEETROOT_SEEDS, Material.BEETROOTS),
    NETHER_WART(Material.NETHER_WART, Material.NETHER_WART),
    COCOA(Material.COCOA_BEANS, Material.COCOA),
    SWEET_BERRIES(Material.SWEET_BERRIES, Material.SWEET_BERRY_BUSH),
    GLOW_BERRIES(Material.GLOW_BERRIES, Material.CAVE_VINES, Material.CAVE_VINES_PLANT),
    SUGAR_CANE(Material.SUGAR_CANE, Material.SUGAR_CANE),
    CACTUS(Material.CACTUS, Material.CACTUS),
    BAMBOO(Material.BAMBOO, Material.BAMBOO),
    KELP(Material.KELP, Material.KELP, Material.KELP_PLANT),
    MELON(Material.MELON_SLICE, Material.MELON),
    PUMPKIN(Material.PUMPKIN, Material.PUMPKIN);

    public static final Set<CropType> ALL = Collections.unmodifiableSet(EnumSet.allOf(CropType.class));

    private final Material icon;
    private final Set<Material> blocks;

    CropType(Material icon, Material... blocks) {
        this.icon = icon;
        this.blocks = Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(blocks)));
    }

    /** The item shown for this crop in the settings GUI. */
    public Material icon() {
        return this.icon;
    }

    /** Every block material that belongs to this crop. */
    public Set<Material> blocks() {
        return this.blocks;
    }

    /** Lower-case id used in config files and command arguments. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable CropType byId(String id) {
        for (CropType type : values()) {
            if (type.name().equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }
}

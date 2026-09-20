package dev.ivoshow.chunkfarm.warehouse;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Base64;

/**
 * Identity of a stored item type. The whole point of the warehouse is that it counts
 * item <em>types</em> and keeps a {@code long} amount per type, so a farm can hold
 * billions of wheat without ever allocating an {@link ItemStack} per 64 items.
 *
 * <p>Plain items (everything a crop drops) are keyed by {@link Material} alone. Items
 * that carry metadata are keyed by their serialized form so custom drops from other
 * plugins are never silently merged into the vanilla item.
 */
public record ItemKey(Material material, @Nullable String meta) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public static ItemKey of(Material material) {
        return new ItemKey(material, null);
    }

    public static ItemKey of(ItemStack stack) {
        ItemStack single = stack.asQuantity(1);
        if (!single.hasItemMeta()) {
            return new ItemKey(single.getType(), null);
        }
        return new ItemKey(single.getType(), ENCODER.encodeToString(single.serializeAsBytes()));
    }

    /**
     * @param amount must be within {@code 1..}{@link #maxStackSize()}; larger values are
     *               rejected by the client, so callers split the total themselves.
     */
    public ItemStack toItemStack(int amount) {
        if (this.meta == null) {
            return new ItemStack(this.material, amount);
        }
        ItemStack stack = ItemStack.deserializeBytes(DECODER.decode(this.meta));
        stack.setAmount(amount);
        return stack;
    }

    public int maxStackSize() {
        if (this.meta == null) {
            return this.material.getMaxStackSize();
        }
        return toItemStack(1).getMaxStackSize();
    }

    /** Round-trips through {@link #deserialize(String)}. Contains no {@code =} or {@code .}. */
    public String serialize() {
        return this.meta == null ? this.material.name() : this.material.name() + "|" + this.meta;
    }

    public static @Nullable ItemKey deserialize(String raw) {
        int split = raw.indexOf('|');
        String name = split == -1 ? raw : raw.substring(0, split);
        Material material = Material.matchMaterial(name);
        if (material == null) {
            return null;
        }
        return new ItemKey(material, split == -1 ? null : raw.substring(split + 1));
    }
}

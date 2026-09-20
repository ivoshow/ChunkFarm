package dev.ivoshow.chunkfarm.farm;

import dev.ivoshow.chunkfarm.config.Messages;
import dev.ivoshow.chunkfarm.config.PluginConfig;
import dev.ivoshow.chunkfarm.util.Text;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The item a player places to turn a chunk into a farm.
 *
 * <p>Tagged in the item's persistent data rather than recognised by material, so a
 * lodestone someone mined elsewhere is still just a lodestone.
 */
public final class ControllerItem {

    private final NamespacedKey marker;
    private final PluginConfig config;
    private final Messages messages;

    public ControllerItem(Plugin plugin, PluginConfig config, Messages messages) {
        this.marker = new NamespacedKey(plugin, "controller");
        this.config = config;
        this.messages = messages;
    }

    public ItemStack create(int amount) {
        ItemStack item = new ItemStack(this.config.controllerMaterial(), amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.item(this.config.controllerName()));
        meta.lore(this.messages.itemList("controller.lore"));
        meta.getPersistentDataContainer().set(this.marker, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isController(@Nullable ItemStack item) {
        if (item == null || item.getType() != this.config.controllerMaterial() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(this.marker, PersistentDataType.BYTE);
    }
}

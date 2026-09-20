package dev.ivoshow.chunkfarm.gui;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import dev.ivoshow.chunkfarm.config.Messages;
import dev.ivoshow.chunkfarm.crop.CropType;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.DeliveryMode;
import dev.ivoshow.chunkfarm.farm.FarmSettings;
import dev.ivoshow.chunkfarm.farm.OverflowPolicy;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks which crops this chunk harvests and how deep it reaches.
 *
 * <p>The layer range is the knob that decides what the farm costs the server: it is
 * shown here in blocks and in layers, and the plugin refuses to go past
 * {@code area.max-layers}.
 */
public final class SettingsGui extends FarmGui {

    private static final int SLOT_REPLANT = 45;
    private static final int SLOT_ACTIVE = 46;
    private static final int SLOT_OVERFLOW = 47;
    private static final int SLOT_DELIVERY = 48;
    private static final int SLOT_MIN_Y = 50;
    private static final int SLOT_MAX_Y = 51;
    private static final int SLOT_MEMBERS = 52;
    private static final int SLOT_BACK = 53;

    private final Messages messages;
    /** Slot -> crop, rebuilt on every redraw so the layout can change with the config. */
    private final Map<Integer, CropType> cropSlots = new HashMap<>();

    public SettingsGui(ChunkFarmPlugin plugin, ChunkFarm farm) {
        super(plugin, farm, plugin.messages().plain("gui.settings.title",
                "chunk", farm.pos().toString()));
        this.messages = plugin.messages();
    }

    @Override
    public void redraw() {
        this.inventory.clear();
        this.cropSlots.clear();

        FarmSettings settings = this.farm.settings();

        int slot = 10;
        for (CropType type : CropType.ALL) {
            // Skip the frame columns so the crops sit in a tidy 7-wide block.
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= 44) {
                break;
            }
            this.cropSlots.put(slot, type);

            boolean on = settings.isEnabled(type);
            List<Component> lore = new ArrayList<>(this.messages.itemList(
                    on ? "gui.settings.crop.enabled" : "gui.settings.crop.disabled"));
            this.inventory.setItem(slot, icon(type.icon(),
                    this.messages.item("gui.settings.crop.name", "crop", this.messages.cropName(type)),
                    lore));
            slot++;
        }

        fillBottomRow(Material.BLACK_STAINED_GLASS_PANE);

        this.inventory.setItem(SLOT_REPLANT, icon(Material.WHEAT_SEEDS,
                this.messages.item("gui.settings.replant.name"),
                this.messages.itemList(settings.autoReplant()
                        ? "gui.settings.replant.enabled" : "gui.settings.replant.disabled")));

        this.inventory.setItem(SLOT_ACTIVE, icon(settings.active() ? Material.LIME_DYE : Material.GRAY_DYE,
                this.messages.item("gui.settings.active.name"),
                this.messages.itemList(settings.active()
                        ? "gui.settings.active.enabled" : "gui.settings.active.disabled")));

        this.inventory.setItem(SLOT_OVERFLOW, icon(Material.BUCKET,
                this.messages.item("gui.settings.overflow.name"),
                this.messages.itemList("gui.settings.overflow." + settings.overflow().name().toLowerCase())));

        this.inventory.setItem(SLOT_DELIVERY,
                icon(settings.usesWarehouse() ? Material.BARREL : Material.HOPPER_MINECART,
                        this.messages.item("gui.settings.delivery.name"),
                        this.messages.itemList(settings.usesWarehouse()
                                ? "gui.settings.delivery.warehouse" : "gui.settings.delivery.ground")));

        this.inventory.setItem(SLOT_MIN_Y, icon(Material.RED_CONCRETE,
                this.messages.item("gui.settings.min-y.name", "y", settings.minY()),
                this.messages.itemList("gui.settings.bounds.lore",
                        "layers", settings.layerCount(),
                        "max_layers", this.plugin.config().maxLayerSpan())));

        this.inventory.setItem(SLOT_MAX_Y, icon(Material.LIME_CONCRETE,
                this.messages.item("gui.settings.max-y.name", "y", settings.maxY()),
                this.messages.itemList("gui.settings.bounds.lore",
                        "layers", settings.layerCount(),
                        "max_layers", this.plugin.config().maxLayerSpan())));

        this.inventory.setItem(SLOT_MEMBERS, icon(Material.PLAYER_HEAD,
                this.messages.item("gui.settings.members.name"),
                this.messages.itemList("gui.settings.members.lore",
                        "count", this.farm.memberCount())));

        this.inventory.setItem(SLOT_BACK, icon(Material.CHEST,
                this.messages.item("gui.settings.back"), List.of()));
    }

    @Override
    public void onClick(Player player, InventoryClickEvent event) {
        if (!this.plugin.farmService().canConfigure(player, this.farm)) {
            player.sendMessage(this.messages.chat("farm.need-role",
                    "role", this.messages.raw("role.manager")));
            return;
        }
        int slot = event.getRawSlot();
        FarmSettings settings = this.farm.settings();
        ClickType click = event.getClick();

        CropType crop = this.cropSlots.get(slot);
        if (crop != null) {
            settings.toggle(crop);
            // The scan cache is keyed on the enabled set, so it has to go.
            this.farm.invalidateCache();
            this.farm.markDirty();
            redraw();
            return;
        }

        switch (slot) {
            case SLOT_REPLANT -> {
                settings.setAutoReplant(!settings.autoReplant());
                this.farm.markDirty();
            }
            case SLOT_ACTIVE -> {
                settings.setActive(!settings.active());
                this.farm.markDirty();
            }
            case SLOT_OVERFLOW -> {
                OverflowPolicy[] values = OverflowPolicy.values();
                settings.setOverflow(values[(settings.overflow().ordinal() + 1) % values.length]);
                this.farm.markDirty();
            }
            case SLOT_DELIVERY -> {
                settings.setDelivery(settings.usesWarehouse()
                        ? DeliveryMode.DROP : DeliveryMode.WAREHOUSE);
                this.farm.markDirty();
            }
            case SLOT_MIN_Y -> adjust(player, click, true);
            case SLOT_MAX_Y -> adjust(player, click, false);
            case SLOT_MEMBERS -> {
                new MembersGui(this.plugin, this.farm).openLater(player);
                return;
            }
            case SLOT_BACK -> {
                new WarehouseGui(this.plugin, this.farm).openLater(player);
                return;
            }
            default -> {
                return;
            }
        }
        redraw();
    }

    private void adjust(Player player, ClickType click, boolean minimum) {
        int step = click.isShiftClick() ? 16 : 1;
        if (click.isRightClick()) {
            step = -step;
        }

        FarmSettings settings = this.farm.settings();
        int minY = minimum ? settings.minY() + step : settings.minY();
        int maxY = minimum ? settings.maxY() : settings.maxY() + step;

        if (this.plugin.farmService().setBounds(this.farm, minY, maxY)) {
            player.sendMessage(this.messages.chat("farm.bounds-trimmed",
                    "max_layers", this.plugin.config().maxLayerSpan()));
        }
    }
}

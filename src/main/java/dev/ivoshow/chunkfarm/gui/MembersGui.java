package dev.ivoshow.chunkfarm.gui;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import dev.ivoshow.chunkfarm.config.Messages;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.FarmRole;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who else may use this farm, and at what level.
 *
 * <p>Adding somebody needs their name, so that is a command ({@code /cf confiar}).
 * Everything you can do to an existing member — change the level, drop them — is here.
 */
public final class MembersGui extends FarmGui {

    private static final int CONTENT_SLOTS = 45;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_BACK = 53;

    private final Messages messages;
    private final Map<Integer, ChunkFarm.Member> slots = new HashMap<>();

    public MembersGui(ChunkFarmPlugin plugin, ChunkFarm farm) {
        super(plugin, farm, plugin.messages().plain("gui.members.title",
                "chunk", farm.pos().toString()));
        this.messages = plugin.messages();
    }

    @Override
    public void redraw() {
        this.inventory.clear();
        this.slots.clear();

        List<ChunkFarm.Member> members = this.farm.members();
        for (int i = 0; i < members.size() && i < CONTENT_SLOTS; i++) {
            ChunkFarm.Member member = members.get(i);
            this.slots.put(i, member);
            this.inventory.setItem(i, head(member));
        }

        fillBottomRow(Material.BLACK_STAINED_GLASS_PANE);

        this.inventory.setItem(SLOT_INFO, icon(Material.OAK_SIGN,
                this.messages.item("gui.members.info.name"),
                this.messages.itemList("gui.members.info.lore",
                        "owner", this.farm.ownerName(),
                        "count", members.size(),
                        "max", this.plugin.config().maxMembersPerFarm())));

        this.inventory.setItem(SLOT_BACK, icon(Material.COMPARATOR,
                this.messages.item("gui.members.back"), List.of()));
    }

    private ItemStack head(ChunkFarm.Member member) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(member.uuid()));
        }
        meta.displayName(this.messages.item("gui.members.entry.name", "player", member.name()));

        List<Component> lore = new ArrayList<>();
        lore.add(this.messages.item("gui.members.entry.role",
                "role", this.messages.raw("role." + member.role().id())));
        lore.addAll(this.messages.itemList("gui.members.entry.actions"));
        meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void onClick(Player player, InventoryClickEvent event) {
        if (!this.plugin.farmService().canManageMembers(player, this.farm)) {
            player.sendMessage(this.messages.chat("farm.members.owner-only"));
            return;
        }

        int slot = event.getRawSlot();
        if (slot == SLOT_BACK) {
            new SettingsGui(this.plugin, this.farm).openLater(player);
            return;
        }

        ChunkFarm.Member member = this.slots.get(slot);
        if (member == null) {
            return;
        }

        if (event.getClick().isShiftClick()) {
            this.farm.removeMember(member.uuid());
            player.sendMessage(this.messages.chat("farm.members.removed", "player", member.name()));
        } else {
            FarmRole[] roles = FarmRole.values();
            FarmRole next = roles[(member.role().ordinal() + 1) % roles.length];
            this.farm.setMember(member.uuid(), member.name(), next);
            player.sendMessage(this.messages.chat("farm.members.role-changed",
                    "player", member.name(),
                    "role", this.messages.raw("role." + next.id())));
        }
        this.plugin.requestSave();
        redraw();
    }
}

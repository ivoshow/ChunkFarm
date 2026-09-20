package dev.ivoshow.chunkfarm.command;

import dev.ivoshow.chunkfarm.ChunkFarmPlugin;
import dev.ivoshow.chunkfarm.config.Messages;
import dev.ivoshow.chunkfarm.crop.CropType;
import dev.ivoshow.chunkfarm.farm.ChunkFarm;
import dev.ivoshow.chunkfarm.farm.FarmRole;
import dev.ivoshow.chunkfarm.farm.FarmService;
import dev.ivoshow.chunkfarm.gui.WarehouseGui;
import dev.ivoshow.chunkfarm.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/** {@code /chunkfarm}. Subcommands answer to both Portuguese and English names. */
public final class ChunkFarmCommand implements BasicCommand {

    private final ChunkFarmPlugin plugin;

    public ChunkFarmCommand(ChunkFarmPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Messages messages = this.plugin.messages();

        if (args.length == 0) {
            help(sender);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "ajuda", "help" -> help(sender);
            case "abrir", "open" -> open(sender);
            case "info" -> info(sender);
            case "lista", "list" -> list(sender);
            case "colher", "harvest" -> harvest(sender);
            case "camadas", "layers" -> layers(sender, args);
            case "confiar", "trust" -> trust(sender, args);
            case "desconfiar", "untrust" -> untrust(sender, args);
            case "membros", "members" -> members(sender);
            case "recolher", "pickup" -> pickup(sender);
            case "entregar", "give" -> give(sender, args);
            case "remover", "remove" -> remove(sender);
            case "recarregar", "reload" -> reload(sender);
            case "stats" -> stats(sender);
            default -> sender.sendMessage(messages.chat("command.unknown", "arg", args[0]));
        }
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission("chunkfarm.use");
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String option : subcommandsFor(source.getSender())) {
                if (option.startsWith(partial)) {
                    out.add(option);
                }
            }
            return out;
        }
        if (args.length == 2 && (isGive(args[0]) || isTrust(args[0]))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        return List.of();
    }

    private static boolean isGive(String arg) {
        return "entregar".equalsIgnoreCase(arg) || "give".equalsIgnoreCase(arg);
    }

    private static boolean isTrust(String arg) {
        return "confiar".equalsIgnoreCase(arg) || "trust".equalsIgnoreCase(arg)
                || "desconfiar".equalsIgnoreCase(arg) || "untrust".equalsIgnoreCase(arg);
    }

    private List<String> subcommandsFor(CommandSender sender) {
        List<String> out = new ArrayList<>(List.of("ajuda", "abrir", "info", "lista", "colher",
                "camadas", "confiar", "desconfiar", "membros", "recolher"));
        if (sender.hasPermission(FarmService.PERMISSION_ADMIN)) {
            out.addAll(List.of("entregar", "remover", "recarregar", "stats"));
        }
        return out;
    }

    private void help(CommandSender sender) {
        for (var line : this.plugin.messages().itemList("command.help")) {
            sender.sendMessage(line);
        }
    }

    private void open(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        ChunkFarm farm = requireFarm(player);
        if (farm == null) {
            return;
        }
        if (!canOpen(player, farm)) {
            return;
        }
        new WarehouseGui(this.plugin, farm).open(player);
    }

    private void info(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        ChunkFarm farm = requireFarm(player);
        if (farm == null) {
            return;
        }

        StringJoiner crops = new StringJoiner(", ");
        for (CropType type : farm.settings().enabledCrops()) {
            crops.add(this.plugin.messages().cropName(type));
        }

        Messages messages = this.plugin.messages();
        sender.sendMessage(messages.chat("command.info.header", "chunk", farm.pos().toString()));
        sender.sendMessage(messages.plain("command.info.owner", "owner", farm.ownerName()));
        sender.sendMessage(messages.plain("command.info.bounds",
                "min", farm.settings().minY(),
                "max", farm.settings().maxY(),
                "layers", farm.settings().layerCount()));
        sender.sendMessage(messages.plain("command.info.crops",
                "crops", crops.length() == 0 ? "-" : crops.toString()));
        sender.sendMessage(messages.plain("command.info.warehouse",
                "total", Text.number(farm.warehouse().total()),
                "types", farm.warehouse().typeCount(),
                "max_types", farm.warehouse().maxTypes()));
        sender.sendMessage(messages.plain("command.info.delivery",
                "delivery", messages.raw(farm.settings().usesWarehouse()
                        ? "delivery.warehouse" : "delivery.ground")));
        sender.sendMessage(messages.plain("command.info.state",
                "state", messages.raw(farm.isDormant() ? "state.dormant"
                        : farm.settings().active() ? "state.active" : "state.paused"),
                "cached", farm.cachedCrops().size(),
                "harvested", Text.number(farm.harvestedTotal())));
        sender.sendMessage(messages.plain("command.info.scan",
                "scan", farm.lastScanMillis()));
    }

    private void list(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        List<ChunkFarm> owned = this.plugin.farms().ownedBy(player.getUniqueId());
        if (owned.isEmpty()) {
            sender.sendMessage(this.plugin.messages().chat("command.list.empty"));
            return;
        }
        sender.sendMessage(this.plugin.messages().chat("command.list.header", "count", owned.size()));
        for (ChunkFarm farm : owned) {
            sender.sendMessage(this.plugin.messages().plain("command.list.entry",
                    "chunk", farm.pos().toString(),
                    "x", farm.blockX(), "y", farm.blockY(), "z", farm.blockZ(),
                    "total", Text.compact(farm.warehouse().total())));
        }
    }

    private void harvest(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        ChunkFarm farm = requireFarm(player);
        if (farm == null || !canConfigure(player, farm)) {
            return;
        }
        long before = farm.warehouse().total();
        this.plugin.harvestTask().forceOnce(farm);
        sender.sendMessage(this.plugin.messages().chat("command.harvest.done",
                "amount", Text.number(farm.warehouse().total() - before)));
    }

    /**
     * Sets the layer range directly. Clicking through hundreds of layers in the menu is
     * not a workflow, so a farm that spans the world is configured here.
     */
    private void layers(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        ChunkFarm farm = requireFarm(player);
        if (farm == null || !canConfigure(player, farm)) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(this.plugin.messages().chat("command.layers.usage"));
            return;
        }

        int minY;
        int maxY;
        try {
            minY = Integer.parseInt(args[1]);
            maxY = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(this.plugin.messages().chat("command.not-a-number",
                    "arg", args[1] + " / " + args[2]));
            return;
        }

        boolean trimmed = this.plugin.farmService().setBounds(farm, minY, maxY);
        sender.sendMessage(this.plugin.messages().chat("command.layers.done",
                "min", farm.settings().minY(),
                "max", farm.settings().maxY(),
                "layers", farm.settings().layerCount()));
        if (trimmed) {
            sender.sendMessage(this.plugin.messages().chat("farm.bounds-trimmed",
                    "max_layers", this.plugin.config().maxLayerSpan()));
        }
        if (farm.settings().layerCount() > 64) {
            sender.sendMessage(this.plugin.messages().chat("command.layers.expensive",
                    "layers", farm.settings().layerCount(),
                    "blocks", Text.number(farm.settings().layerCount() * 256L)));
        }
    }

    /**
     * Takes the farm down and gives the controller back, without going anywhere near a
     * block-break event. This is the guaranteed path: the owner always gets the item,
     * even if the controller block was already destroyed by something else.
     */
    private void pickup(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        ChunkFarm farm = requireFarm(player);
        if (farm == null) {
            return;
        }
        if (!this.plugin.farmService().canManageMembers(player, farm)) {
            sender.sendMessage(this.plugin.messages().chat("command.pickup.owner-only",
                    "owner", farm.ownerName()));
            return;
        }

        Location controller = farm.controllerLocation();
        boolean blockWasThere = controller != null
                && controller.getBlock().getType() == this.plugin.config().controllerMaterial();

        // The controller item is paid out exactly once per standing block. Without
        // this, running the command again on an already-retired farm would mint a new
        // controller every time -- and a controller is a new farm.
        if (!blockWasThere && farm.isDormant()) {
            sender.sendMessage(this.plugin.messages().chat("command.pickup.already-taken"));
            return;
        }

        if (blockWasThere) {
            controller.getBlock().setType(Material.AIR, false);
        }
        this.plugin.farmService().returnController(player, controller != null
                ? controller : player.getLocation());

        long stored = farm.warehouse().total();
        this.plugin.farmService().retire(farm);
        this.plugin.applyChunkTickets();
        this.plugin.requestSave();

        sender.sendMessage(this.plugin.messages().chat("command.pickup.done",
                "chunk", farm.pos().toString(),
                "where", blockWasThere ? this.plugin.messages().raw("command.pickup.from-world")
                        : this.plugin.messages().raw("command.pickup.no-block")));
        sender.sendMessage(stored == 0
                ? this.plugin.messages().chat("farm.break.removed")
                : this.plugin.messages().chat("farm.break.dormant",
                        "total", Text.number(stored),
                        "days", this.plugin.config().orphanRetentionDays()));
    }

    /** {@code /cf confiar <jogador> [acesso|retirar|gerente]} */
    private void trust(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        ChunkFarm farm = requireFarm(player);
        if (farm == null) {
            return;
        }
        if (!this.plugin.farmService().canManageMembers(player, farm)) {
            sender.sendMessage(this.plugin.messages().chat("farm.members.owner-only"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(this.plugin.messages().chat("command.trust.usage"));
            return;
        }

        FarmRole role = FarmRole.WITHDRAW;
        if (args.length >= 3) {
            role = FarmRole.parse(args[2]);
            if (role == null) {
                sender.sendMessage(this.plugin.messages().chat("command.trust.bad-role", "arg", args[2]));
                return;
            }
        }

        OfflinePlayer target = resolve(args[1]);
        if (target == null || target.getName() == null) {
            sender.sendMessage(this.plugin.messages().chat("command.trust.unknown-player", "player", args[1]));
            return;
        }
        if (target.getUniqueId().equals(farm.owner())) {
            sender.sendMessage(this.plugin.messages().chat("command.trust.is-owner"));
            return;
        }

        int limit = this.plugin.config().maxMembersPerFarm();
        boolean newMember = farm.roleOf(target.getUniqueId()) == null;
        if (newMember && limit > 0 && farm.memberCount() >= limit) {
            sender.sendMessage(this.plugin.messages().chat("command.trust.limit", "limit", limit));
            return;
        }

        farm.setMember(target.getUniqueId(), target.getName(), role);
        this.plugin.requestSave();
        sender.sendMessage(this.plugin.messages().chat("command.trust.done",
                "player", target.getName(),
                "role", this.plugin.messages().raw("role." + role.id())));

        if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().sendMessage(this.plugin.messages().chat("command.trust.notified",
                    "owner", player.getName(),
                    "chunk", farm.pos().toString(),
                    "role", this.plugin.messages().raw("role." + role.id())));
        }
    }

    private void untrust(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        ChunkFarm farm = requireFarm(player);
        if (farm == null) {
            return;
        }
        if (!this.plugin.farmService().canManageMembers(player, farm)) {
            sender.sendMessage(this.plugin.messages().chat("farm.members.owner-only"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(this.plugin.messages().chat("command.untrust.usage"));
            return;
        }

        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            sender.sendMessage(this.plugin.messages().chat("command.trust.unknown-player", "player", args[1]));
            return;
        }
        if (farm.removeMember(target.getUniqueId())) {
            this.plugin.requestSave();
            sender.sendMessage(this.plugin.messages().chat("farm.members.removed",
                    "player", target.getName() == null ? args[1] : target.getName()));
        } else {
            sender.sendMessage(this.plugin.messages().chat("command.untrust.not-member", "player", args[1]));
        }
    }

    private void members(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        ChunkFarm farm = requireFarm(player);
        if (farm == null || !canOpen(player, farm)) {
            return;
        }

        sender.sendMessage(this.plugin.messages().chat("command.members.header",
                "owner", farm.ownerName(), "count", farm.memberCount()));
        if (farm.memberCount() == 0) {
            sender.sendMessage(this.plugin.messages().plain("command.members.empty"));
            return;
        }
        for (ChunkFarm.Member member : farm.members()) {
            sender.sendMessage(this.plugin.messages().plain("command.members.entry",
                    "player", member.name(),
                    "role", this.plugin.messages().raw("role." + member.role().id())));
        }
    }

    /**
     * Resolves a name without a blocking web lookup: the profile cache first, then
     * anyone online. A player who has never joined this server cannot be trusted yet.
     */
    private static @Nullable OfflinePlayer resolve(String name) {
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            return cached;
        }
        return Bukkit.getPlayerExact(name);
    }

    private void give(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(this.plugin.messages().chat("command.give.usage"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(this.plugin.messages().chat("command.player-offline", "player", args[1]));
            return;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException ex) {
                sender.sendMessage(this.plugin.messages().chat("command.not-a-number", "arg", args[2]));
                return;
            }
        }

        ItemStack controller = this.plugin.controllerItem().create(amount);
        for (ItemStack leftover : target.getInventory().addItem(controller).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover);
        }
        sender.sendMessage(this.plugin.messages().chat("command.give.sent",
                "amount", amount, "player", target.getName()));
        target.sendMessage(this.plugin.messages().chat("command.give.received", "amount", amount));
    }

    private void remove(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        ChunkFarm farm = requireFarm(player);
        if (farm == null) {
            return;
        }
        this.plugin.farmService().delete(farm);
        this.plugin.requestSave();
        sender.sendMessage(this.plugin.messages().chat("command.remove.done",
                "chunk", farm.pos().toString(),
                "total", Text.number(farm.warehouse().total())));
    }

    private void reload(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        this.plugin.reloadEverything();
        sender.sendMessage(this.plugin.messages().chat("command.reload.done"));
    }

    private void stats(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        long stored = 0;
        int dormant = 0;
        for (ChunkFarm farm : this.plugin.farms().snapshot()) {
            stored += farm.warehouse().total();
            if (farm.isDormant()) {
                dormant++;
            }
        }
        var task = this.plugin.harvestTask();
        double[] tps = Bukkit.getServer().getTPS();

        sender.sendMessage(this.plugin.messages().chat("command.stats",
                "farms", this.plugin.farms().size(),
                "dormant", dormant,
                "stored", Text.number(stored),
                "cycles", Text.number(task.cyclesRun()),
                "blocks", Text.number(task.blocksHarvested())));
        sender.sendMessage(this.plugin.messages().plain("command.stats-cost",
                "last", String.format(java.util.Locale.of("pt", "BR"), "%.2f", task.lastCycleMillis()),
                "avg", String.format(java.util.Locale.of("pt", "BR"), "%.2f", task.averageCycleMillis()),
                "peak", String.format(java.util.Locale.of("pt", "BR"), "%.2f", task.peakCycleMillis()),
                "visited", task.lastFarmsVisited(),
                "skipped", task.lastFarmsSkipped()));
        sender.sendMessage(this.plugin.messages().plain("command.stats-server",
                "tps", String.format(java.util.Locale.of("pt", "BR"), "%.2f", tps[0]),
                "tick", String.format(java.util.Locale.of("pt", "BR"), "%.2f",
                        Bukkit.getServer().getAverageTickTime()),
                "reach", this.plugin.config().chunkReach().name()));
        sender.sendMessage(this.plugin.messages().plain("command.stats-protection",
                "hook", this.plugin.farmService().protection().name(),
                "trust", this.plugin.config().requiredTrust()));
    }

    private @Nullable Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(this.plugin.messages().chat("command.players-only"));
        return null;
    }

    private @Nullable ChunkFarm requireFarm(Player player) {
        ChunkFarm farm = this.plugin.farms().at(player.getLocation());
        if (farm == null) {
            player.sendMessage(this.plugin.messages().chat("command.no-farm-here"));
        }
        return farm;
    }

    private boolean canOpen(Player player, ChunkFarm farm) {
        if (this.plugin.farmService().canOpen(player, farm)) {
            return true;
        }
        player.sendMessage(this.plugin.messages().chat("farm.not-owner", "owner", farm.ownerName()));
        return false;
    }

    private boolean canConfigure(Player player, ChunkFarm farm) {
        if (this.plugin.farmService().canConfigure(player, farm)) {
            return true;
        }
        player.sendMessage(this.plugin.messages().chat("farm.need-role",
                "role", this.plugin.messages().raw("role.manager")));
        return false;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(FarmService.PERMISSION_ADMIN)) {
            return true;
        }
        sender.sendMessage(this.plugin.messages().chat("command.no-permission"));
        return false;
    }
}

package dev.ivoshow.chunkfarm.protection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.logging.Logger;

/** Picks a {@link ProtectionHook} based on what is actually installed. */
public final class ProtectionHooks {

    /** Used when no protection plugin is present, or the integration is switched off. */
    public static final ProtectionHook NONE = new ProtectionHook() {
        @Override
        public String name() {
            return "nenhum";
        }

        @Override
        public boolean canOpen(Player player, Location location) {
            // No land data to grant anything; the farm's owner check is the only word.
            return false;
        }

        @Override
        public boolean canBuild(Player player, Location location) {
            return true;
        }
    };

    private ProtectionHooks() {
    }

    /**
     * @param trustLevel name of the GriefPrevention {@code ClaimPermission} that grants
     *                   access to the warehouse
     */
    public static ProtectionHook detect(Logger logger, boolean enabled, String trustLevel) {
        if (!enabled) {
            return NONE;
        }
        if (Bukkit.getPluginManager().getPlugin("GriefPrevention") == null) {
            return NONE;
        }
        try {
            // Referenced only here, so the class never loads without GriefPrevention.
            var permission = me.ryanhamshire.GriefPrevention.ClaimPermission.valueOf(
                    normalise(trustLevel));
            logger.info("GriefPrevention detectado: quem tiver confianca '"
                    + permission.name() + "' no terreno pode abrir a fazenda.");
            return new GriefPreventionHook(permission);
        } catch (IllegalArgumentException ex) {
            logger.warning("protection.required-trust invalido ('" + trustLevel
                    + "'); use Access, Inventory, Build, Manage ou Edit. Integracao desligada.");
            return NONE;
        } catch (Throwable ex) {
            logger.warning("Nao foi possivel ligar na API do GriefPrevention: " + ex);
            return NONE;
        }
    }

    /** {@code container} and {@code inventory} both mean chest-level trust. */
    private static String normalise(String raw) {
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return switch (trimmed) {
            case "container", "inventory" -> "Inventory";
            case "access" -> "Access";
            case "build" -> "Build";
            case "manage" -> "Manage";
            case "edit" -> "Edit";
            default -> raw.trim();
        };
    }

    public static @Nullable String describe(ProtectionHook hook) {
        return hook == NONE ? null : hook.name();
    }
}

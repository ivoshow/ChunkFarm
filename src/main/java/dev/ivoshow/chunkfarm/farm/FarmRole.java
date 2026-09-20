package dev.ivoshow.chunkfarm.farm;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * What a member is allowed to do on a farm they do not own.
 *
 * <p>The levels are cumulative and ordered, so a check is a comparison rather than a
 * set of flags — {@code MANAGER} can do everything {@code WITHDRAW} can.
 */
public enum FarmRole {

    /** Opens the menu and sees what is stored. Takes nothing. */
    ACCESS,

    /** Opens and takes from the warehouse. The level most teammates want. */
    WITHDRAW,

    /**
     * Also changes the farm's settings — crops, layer range, delivery — and may break
     * the controller. Stops short of managing the member list, which stays with the
     * owner.
     */
    MANAGER;

    public boolean allows(FarmRole required) {
        return ordinal() >= required.ordinal();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Accepts both the English enum names and the Portuguese words players will type. */
    public static @Nullable FarmRole parse(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "access", "acesso", "ver" -> ACCESS;
            case "withdraw", "retirar", "retirada" -> WITHDRAW;
            case "manager", "gerente", "gerenciar" -> MANAGER;
            default -> null;
        };
    }
}

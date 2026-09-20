package dev.ivoshow.chunkfarm.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.text.NumberFormat;
import java.util.Locale;

public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final NumberFormat NUMBERS = NumberFormat.getInstance(Locale.of("pt", "BR"));

    private Text() {
    }

    public static Component parse(String miniMessage) {
        return MINI.deserialize(miniMessage);
    }

    /**
     * Parses and strips the italics that vanilla applies to item names and lore by default.
     */
    public static Component item(String miniMessage) {
        return parse(miniMessage).decoration(TextDecoration.ITALIC, false);
    }

    /** {@code 1234567 -> "1.234.567"} */
    public static String number(long value) {
        return NUMBERS.format(value);
    }

    /** {@code 1234567 -> "1,2M"} -- for places where the full number does not fit. */
    public static String compact(long value) {
        if (value < 1_000) {
            return Long.toString(value);
        }
        if (value < 1_000_000) {
            return trim(value / 1_000D) + "K";
        }
        if (value < 1_000_000_000) {
            return trim(value / 1_000_000D) + "M";
        }
        if (value < 1_000_000_000_000L) {
            return trim(value / 1_000_000_000D) + "B";
        }
        return trim(value / 1_000_000_000_000D) + "T";
    }

    private static String trim(double value) {
        return String.format(Locale.of("pt", "BR"), "%.1f", value).replace(",0", "");
    }

    /** {@code WHEAT_SEEDS -> "Wheat Seeds"} */
    public static String prettify(String constant) {
        String[] words = constant.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder(constant.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return out.toString();
    }
}

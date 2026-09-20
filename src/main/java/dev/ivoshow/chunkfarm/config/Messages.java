package dev.ivoshow.chunkfarm.config;

import dev.ivoshow.chunkfarm.crop.CropType;
import dev.ivoshow.chunkfarm.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * messages.yml, parsed as MiniMessage. Placeholders are written {@code %name%} so the
 * file stays readable for server owners who are not going to learn a template language.
 */
public final class Messages {

    private final Map<String, String> lines = new HashMap<>();
    private final Map<String, List<String>> blocks = new HashMap<>();
    private String prefix = "";

    public void load(File file, InputStream defaults) {
        this.lines.clear();
        this.blocks.clear();

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (defaults != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            // Keys added by a plugin update resolve against the jar instead of vanishing.
            config.options().copyDefaults(true);
        }

        for (String key : config.getKeys(true)) {
            Object value = config.get(key);
            if (value instanceof String string) {
                this.lines.put(key, string);
            } else if (value instanceof List<?> list) {
                List<String> block = new ArrayList<>(list.size());
                for (Object element : list) {
                    block.add(String.valueOf(element));
                }
                this.blocks.put(key, block);
            }
        }
        this.prefix = this.lines.getOrDefault("prefix", "");
    }

    /** Raw string with placeholders substituted, before MiniMessage parsing. */
    public String raw(String key, Object... placeholders) {
        return fill(this.lines.getOrDefault(key, key), placeholders);
    }

    /** A chat message, prefixed. */
    public Component chat(String key, Object... placeholders) {
        return Text.parse(this.prefix + raw(key, placeholders));
    }

    /** A message without the prefix, e.g. inside a GUI or a book. */
    public Component plain(String key, Object... placeholders) {
        return Text.parse(raw(key, placeholders));
    }

    /** An item name or lore line: unprefixed and with vanilla italics removed. */
    public Component item(String key, Object... placeholders) {
        return Text.item(raw(key, placeholders));
    }

    public List<Component> itemList(String key, Object... placeholders) {
        List<String> block = this.blocks.get(key);
        if (block == null) {
            return List.of();
        }
        List<Component> out = new ArrayList<>(block.size());
        for (String line : block) {
            out.add(Text.item(fill(line, placeholders)));
        }
        return out;
    }

    /** Display name for a crop, falling back to a prettified enum name. */
    public String cropName(CropType type) {
        String key = "crops." + type.id();
        String value = this.lines.get(key);
        return value != null ? value : Text.prettify(type.name());
    }

    private static String fill(String template, Object... placeholders) {
        if (placeholders.length == 0) {
            return template;
        }
        String out = template;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            out = out.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
        }
        return out;
    }
}

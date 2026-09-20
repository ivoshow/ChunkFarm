package dev.ivoshow.chunkfarm;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps the bundled YAML honest.
 *
 * <p>Both resource files are edited far more often than they are exercised, and a
 * mistake in them fails at runtime as a missing message or a silently ignored setting
 * rather than at build time. These checks are cheap and catch the two ways that
 * actually happens: broken indentation, and a key the code asks for that nobody added.
 */
class MessageKeysTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Path SOURCES = Path.of("src", "main", "java");

    /** Every {@code messages().something("some.key"} in the plugin source. */
    private static final Pattern LOOKUP = Pattern.compile(
            "messages\\(\\)\\.(?:chat|plain|item|itemList|raw)\\(\\s*\"([a-z0-9.\\-]+)\"");
    /** Same, but inside the GUI classes where the field is called {@code messages}. */
    private static final Pattern FIELD_LOOKUP = Pattern.compile(
            "\\bthis\\.messages\\.(?:chat|plain|item|itemList|raw)\\(\\s*\"([a-z0-9.\\-]+)\"");

    @Test
    void bundledYamlParses() throws IOException {
        for (String file : List.of("messages.yml", "config.yml", "plugin.yml")) {
            Path path = RESOURCES.resolve(file);
            String raw = Files.readString(path);
            if (file.equals("plugin.yml")) {
                // Gradle substitutes these at build time; snakeyaml would choke.
                raw = raw.replace("${version}", "1.0.0").replace("${apiVersion}", "26.2");
            }
            try {
                Object parsed = new Yaml().load(raw);
                assertTrue(parsed instanceof Map, file + " deveria ser um mapa");
            } catch (RuntimeException ex) {
                fail(file + " nao e YAML valido: " + ex.getMessage());
            }
        }
    }

    /**
     * Bukkit splits configuration paths on {@code .}, so a key containing a dot can
     * never be read back -- it would be looked up as a nested section that does not
     * exist.
     */
    @Test
    void noKeyContainsAPathSeparator() throws IOException {
        for (String file : List.of("messages.yml", "config.yml")) {
            Object parsed = new Yaml().load(Files.readString(RESOURCES.resolve(file)));
            List<String> offenders = new ArrayList<>();
            collectDottedKeys(parsed, "", offenders);
            assertEquals(List.of(), offenders, file + " tem chave(s) com ponto no nome");
        }
    }

    /**
     * YAML 1.1 -- which SnakeYAML and therefore Bukkit use -- resolves a bare
     * {@code on}/{@code off}/{@code yes}/{@code no} key to a boolean, so the key name
     * never survives the round trip.
     */
    @Test
    void noKeyIsAccidentallyABoolean() throws IOException {
        for (String file : List.of("messages.yml", "config.yml")) {
            Object parsed = new Yaml().load(Files.readString(RESOURCES.resolve(file)));
            List<String> offenders = new ArrayList<>();
            collectBooleanKeys(parsed, "", offenders);
            assertEquals(List.of(), offenders, file + " tem chave que o YAML leu como booleano");
        }
    }

    @Test
    void everyMessageTheCodeAsksForExists() throws IOException {
        Object parsed = new Yaml().load(Files.readString(RESOURCES.resolve("messages.yml")));
        Set<String> present = new LinkedHashSet<>();
        collectPaths(parsed, "", present);

        Set<String> requested = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                addMatches(LOOKUP.matcher(source), requested);
                addMatches(FIELD_LOOKUP.matcher(source), requested);
            }
        }

        assertTrue(requested.size() > 30, "o scanner deveria achar dezenas de chaves, achou " + requested.size());

        List<String> missing = requested.stream()
                // Keys built at runtime from an enum name, e.g. "role." + role.id().
                .filter(key -> !key.endsWith("."))
                .filter(key -> !present.contains(key))
                .sorted()
                .toList();
        assertEquals(List.of(), missing, "chaves pedidas pelo codigo e ausentes em messages.yml");
    }

    private static void addMatches(Matcher matcher, Set<String> into) {
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectPaths(Object node, String prefix, Set<String> into) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : ((Map<Object, Object>) map).entrySet()) {
            String path = prefix.isEmpty() ? String.valueOf(entry.getKey())
                    : prefix + "." + entry.getKey();
            into.add(path);
            collectPaths(entry.getValue(), path, into);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectDottedKeys(Object node, String prefix, List<String> into) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : ((Map<Object, Object>) map).entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.indexOf('.') >= 0) {
                into.add(prefix + key);
            }
            collectDottedKeys(entry.getValue(), prefix + key + ".", into);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectBooleanKeys(Object node, String prefix, List<String> into) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : ((Map<Object, Object>) map).entrySet()) {
            if (entry.getKey() instanceof Boolean) {
                into.add(prefix + entry.getKey());
            }
            collectBooleanKeys(entry.getValue(), prefix + entry.getKey() + ".", into);
        }
    }
}

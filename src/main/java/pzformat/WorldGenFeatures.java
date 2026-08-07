package pzformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The WorldGen feature vocabulary, read from the game's own Lua.
 *
 * `media/lua/server/WorldGen/features/<CATEGORY>/<name>.lua` files are small
 * declarative tables — a list of tile names and a registration line:
 *
 *     local medium_grass = {
 *         main = {
 *             "blends_natural_01_32", "blends_natural_01_37",
 *             "blends_natural_01_38", "blends_natural_01_39"
 *         },
 *     }
 *     worldgen.features.GROUND["medium_grass"] = medium_grass
 *
 * Categories observed: BUSH, GROUND, NONE, ORE, PLANT, TREE.
 *
 * Reading these rather than hardcoding measured constants is what makes
 * generated ground match whatever it is placed next to. An earlier version of
 * this project carried a table of tile weights measured from Muldraugh, which
 * described one hand-authored town rather than the generator, and would not
 * have adapted to a different biome or a game update.
 *
 * This is a targeted parser, not a Lua interpreter. It brace-matches
 * `local <name> = { ... }` blocks and pulls quoted strings out of them. It
 * would not survive computed table keys or string concatenation, neither of
 * which appears in these files.
 */
public final class WorldGenFeatures {

    /** category -> feature name -> tile names. */
    public final Map<String, Map<String, List<String>>> byCategory = new TreeMap<>();

    public int filesRead, featuresFound;

    public static WorldGenFeatures load(Path mediaDir) throws IOException {
        Path base = mediaDir.resolve("lua/server/WorldGen/features");
        WorldGenFeatures out = new WorldGenFeatures();
        if (!Files.isDirectory(base)) {
            throw new IOException("no WorldGen features directory at " + base);
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".lua"))
                    .sorted()
                    .forEach(files::add);
        }

        for (Path f : files) {
            String src = stripComments(Files.readString(f, StandardCharsets.ISO_8859_1));
            Map<String, String> blocks = localBlocks(src);
            out.filesRead++;

            // worldgen.features.GROUND["medium_grass"] = medium_grass
            int from = 0;
            while (true) {
                int at = src.indexOf("worldgen.features.", from);
                if (at < 0) {
                    break;
                }
                from = at + 1;

                int open = src.indexOf('[', at);
                int eq = src.indexOf('=', at);
                if (open < 0 || eq < 0 || open > eq) {
                    continue;
                }
                String category = src.substring(at + "worldgen.features.".length(), open).trim();
                String name = quotedBetween(src, open, eq);
                String var = src.substring(eq + 1).trim();
                int cut = 0;
                while (cut < var.length()
                        && (Character.isLetterOrDigit(var.charAt(cut)) || var.charAt(cut) == '_')) {
                    cut++;
                }
                var = var.substring(0, cut);

                if (category.isEmpty() || name == null || var.isEmpty()) {
                    continue;
                }
                String block = blocks.get(var);
                if (block == null) {
                    continue;
                }
                List<String> tiles = quotedStrings(block);
                if (tiles.isEmpty()) {
                    continue;
                }
                out.byCategory.computeIfAbsent(category, k -> new TreeMap<>())
                        .put(name, tiles);
                out.featuresFound++;
            }
        }
        return out;
    }

    public List<String> tiles(String category, String feature) {
        Map<String, List<String>> m = byCategory.get(category);
        return m == null ? null : m.get(feature);
    }

    // ---------------------------------------------------------------- parsing

    /** Removes `--` comments without eating them inside string literals. */
    static String stripComments(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        boolean inString = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                sb.append(c);
                continue;
            }
            if (c == '-' && i + 1 < src.length() && src.charAt(i + 1) == '-') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                sb.append('\n');
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** `local name = { ... }` blocks, brace-matched, keyed by variable name. */
    static Map<String, String> localBlocks(String src) {
        Map<String, String> out = new LinkedHashMap<>();
        int from = 0;
        while (true) {
            int at = src.indexOf("local ", from);
            if (at < 0) {
                break;
            }
            from = at + 6;
            int eq = src.indexOf('=', at);
            if (eq < 0) {
                continue;
            }
            String name = src.substring(at + 6, eq).trim();
            if (name.isEmpty() || name.contains("\n") || name.contains(" ")) {
                continue;
            }
            int open = src.indexOf('{', eq);
            if (open < 0) {
                continue;
            }
            // Only if the brace really is this assignment's value.
            if (src.substring(eq + 1, open).trim().length() > 0) {
                continue;
            }
            int close = matchBrace(src, open);
            if (close < 0) {
                continue;
            }
            out.put(name, src.substring(open, close + 1));
            from = close;
        }
        return out;
    }

    static int matchBrace(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    static List<String> quotedStrings(String block) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int a = block.indexOf('"', i);
            if (a < 0) {
                break;
            }
            int b = block.indexOf('"', a + 1);
            if (b < 0) {
                break;
            }
            out.add(block.substring(a + 1, b));
            i = b + 1;
        }
        return out;
    }

    static String quotedBetween(String src, int from, int to) {
        int a = src.indexOf('"', from);
        if (a < 0 || a > to) {
            return null;
        }
        int b = src.indexOf('"', a + 1);
        if (b < 0 || b > to) {
            return null;
        }
        return src.substring(a + 1, b);
    }

    // ------------------------------------------------------------------- dump

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: WorldGenFeatures <mediadir> [category]");
            return;
        }
        WorldGenFeatures f = load(Path.of(args[0]));
        System.out.println("files read: " + f.filesRead
                + "   features: " + f.featuresFound);
        for (Map.Entry<String, Map<String, List<String>>> e : f.byCategory.entrySet()) {
            if (args.length > 1 && !args[1].equalsIgnoreCase(e.getKey())) {
                continue;
            }
            System.out.println("\n== " + e.getKey() + "  (" + e.getValue().size() + ")");
            for (Map.Entry<String, List<String>> g : e.getValue().entrySet()) {
                List<String> t = g.getValue();
                System.out.printf("   %-26s %2d tiles  %s%n",
                        g.getKey(), t.size(),
                        t.size() <= 4 ? String.join(", ", t)
                                : t.get(0) + " ... " + t.get(t.size() - 1));
            }
        }
    }
}

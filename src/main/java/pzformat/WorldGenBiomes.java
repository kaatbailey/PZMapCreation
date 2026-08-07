package pzformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * WorldGen biome definitions, read from the game's own Lua.
 *
 * `media/lua/server/WorldGen/biomes/worldgen/<name>.lua` declares what a biome
 * places and at what rate:
 *
 *     features = {
 *         GROUND = { { f = worldgen.features.GROUND.medium_grass, p = 1.0 } },
 *         PLANT  = { { f = worldgen.features.PLANT.grass_medium,  p = 0.3 },
 *                    { f = worldgen.features.PLANT.grass_high,    p = 0.3 } },
 *         BUSH   = { { f = worldgen.features.BUSH.bush_regular,   p = 0.01 } },
 *         TREE   = { ... },
 *     }
 *
 * Each file also declares derived biomes carrying only `parent = "<name>"` and
 * an ore level, so inheritance has to be resolved.
 *
 * Every `f = worldgen.features.CAT.name` reference names its own category, so
 * the nesting does not need to be tracked — the references can be read flat out
 * of each biome's block.
 *
 * These are the biomes `WorldGenOverride.lua` selects from. They are NOT the
 * `biomes/map/` set (dirt, townhouse, the forest map biomes), which the
 * override does not read.
 */
public final class WorldGenBiomes {

    /** One `{ f = worldgen.features.CAT.name, p = 0.3 }` entry. */
    public record Entry(String category, String feature, double p) { }

    public static final class Biome {
        public String name;
        public String parent;
        public final List<Entry> entries = new ArrayList<>();

        @Override public String toString() {
            return name + (parent != null ? " (parent " + parent + ")" : "")
                    + ", " + entries.size() + " feature entries";
        }
    }

    public final Map<String, Biome> byName = new TreeMap<>();
    public int filesRead;

    public static WorldGenBiomes load(Path mediaDir) throws IOException {
        Path base = mediaDir.resolve("lua/server/WorldGen/biomes/worldgen");
        WorldGenBiomes out = new WorldGenBiomes();
        if (!Files.isDirectory(base)) {
            throw new IOException("no WorldGen biomes directory at " + base);
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.list(base)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".lua"))
                    .sorted()
                    .forEach(files::add);
        }

        for (Path f : files) {
            String src = WorldGenFeatures.stripComments(
                    Files.readString(f, StandardCharsets.ISO_8859_1));
            Map<String, String> blocks = WorldGenFeatures.localBlocks(src);
            out.filesRead++;

            int from = 0;
            while (true) {
                int at = src.indexOf("worldgen.biomes[", from);
                if (at < 0) {
                    break;
                }
                from = at + 1;
                int open = at + "worldgen.biomes".length();
                int eq = src.indexOf('=', at);
                if (eq < 0) {
                    continue;
                }
                String name = WorldGenFeatures.quotedBetween(src, open, eq);
                String var = src.substring(eq + 1).trim();
                int cut = 0;
                while (cut < var.length()
                        && (Character.isLetterOrDigit(var.charAt(cut)) || var.charAt(cut) == '_')) {
                    cut++;
                }
                var = var.substring(0, cut);
                if (name == null || var.isEmpty()) {
                    continue;
                }
                String block = blocks.get(var);
                if (block == null) {
                    continue;
                }

                Biome b = new Biome();
                b.name = name;
                b.parent = parentOf(block);
                b.entries.addAll(entriesOf(block));
                out.byName.put(name, b);
            }
        }
        return out;
    }

    /** Entries for a biome, with its parent's merged in for categories it omits. */
    public List<Entry> resolved(String biomeName) {
        Biome b = byName.get(biomeName);
        if (b == null) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>(b.entries);
        String p = b.parent;
        int guard = 0;
        while (p != null && guard++ < 8) {
            Biome parent = byName.get(p);
            if (parent == null) {
                break;
            }
            for (Entry e : parent.entries) {
                boolean haveCategory = out.stream()
                        .anyMatch(x -> x.category().equals(e.category()));
                if (!haveCategory) {
                    out.add(e);
                }
            }
            p = parent.parent;
        }
        return out;
    }

    public List<Entry> resolved(String biomeName, String category) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : resolved(biomeName)) {
            if (e.category().equals(category)) {
                out.add(e);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- parsing

    static String parentOf(String block) {
        int at = block.indexOf("parent");
        if (at < 0) {
            return null;
        }
        int eq = block.indexOf('=', at);
        if (eq < 0) {
            return null;
        }
        int a = block.indexOf('"', eq);
        if (a < 0) {
            return null;
        }
        int b = block.indexOf('"', a + 1);
        return b < 0 ? null : block.substring(a + 1, b);
    }

    static List<Entry> entriesOf(String block) {
        List<Entry> out = new ArrayList<>();
        final String marker = "worldgen.features.";
        int from = 0;
        while (true) {
            int at = block.indexOf(marker, from);
            if (at < 0) {
                break;
            }
            from = at + marker.length();

            int i = from;
            while (i < block.length() && block.charAt(i) != '.') {
                i++;
            }
            if (i >= block.length()) {
                break;
            }
            String category = block.substring(from, i).trim();

            int j = i + 1;
            while (j < block.length()
                    && (Character.isLetterOrDigit(block.charAt(j)) || block.charAt(j) == '_')) {
                j++;
            }
            String feature = block.substring(i + 1, j);

            // p = <number>, on the same table entry
            double p = 1.0;
            int pAt = block.indexOf("p", j);
            int brace = block.indexOf('}', j);
            if (pAt > 0 && (brace < 0 || pAt < brace)) {
                int eq = block.indexOf('=', pAt);
                if (eq > 0) {
                    int k = eq + 1;
                    while (k < block.length() && Character.isWhitespace(block.charAt(k))) {
                        k++;
                    }
                    int s = k;
                    while (k < block.length()
                            && (Character.isDigit(block.charAt(k)) || block.charAt(k) == '.')) {
                        k++;
                    }
                    if (k > s) {
                        try {
                            p = Double.parseDouble(block.substring(s, k));
                        } catch (NumberFormatException ignored) {
                            p = 1.0;
                        }
                    }
                }
            }

            if (!category.isEmpty() && !feature.isEmpty()) {
                out.add(new Entry(category, feature, p));
            }
            from = j;
        }
        return out;
    }

    // ------------------------------------------------------------------- dump

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: WorldGenBiomes <mediadir> [biome]");
            return;
        }
        WorldGenBiomes b = load(Path.of(args[0]));
        System.out.println("files read: " + b.filesRead
                + "   biomes: " + b.byName.size());

        if (args.length > 1) {
            System.out.println("\n== " + args[1]);
            for (Entry e : b.resolved(args[1])) {
                System.out.printf("   %-8s %-24s p=%s%n",
                        e.category(), e.feature(), e.p());
            }
            return;
        }
        for (Biome bio : b.byName.values()) {
            System.out.println("   " + bio);
        }
    }
}

package pzformat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Map the tree vocabulary: which tilesets carry which `tree` size class, and
 * which species each belongs to.
 *
 * The size classes are spread across several .tiles.txt files under different
 * tileset names — jumbo_trees.tiles.txt holds one class as *JUMBO_1, with the
 * rest in jumbo_trees_big.tiles.txt and tiledefinitions_erosion.tiles.txt. The
 * WorldGen feature files name species in short form (maple, birch) while the
 * tiledefs use full common names (redmaple, riverbirch), so species have to be
 * matched by substring rather than equality.
 *
 * Needed before density bands can vary size AND species by distance from
 * structures, rather than picking a random subset and hoping it isn't all
 * spindly ones.
 *
 *   java -cp out pzformat.TreeSurvey "$PZ/media"
 */
public final class TreeSurvey {

    /** Short species names, as used by the WorldGen feature files. */
    static final String[] SPECIES = {
            "maple", "birch", "dogwood", "pine", "holly", "redbud",
            "linden", "yellowwood", "hemlock", "hawthorn", "silverbell"
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: TreeSurvey <mediadir>");
            return;
        }
        Path media = Path.of(args[0]);
        TileIndex ti = TileIndex.load(media);
        Set<String> sprites = SpriteNames.load(media.resolve("texturepacks"));
        System.out.println("tiledefs: " + ti.byName.size() + "   sprites: " + sprites.size());
        System.out.println();

        // size -> tileset -> [total, withSprite]
        Map<Integer, Map<String, int[]>> bySize = new TreeMap<>();

        for (String n : ti.byName.keySet()) {
            TileDefs.Tile t = ti.get(n);
            if (t == null) {
                continue;
            }
            String v = t.props.get("tree");
            if (v == null || v.isEmpty()) {
                continue;
            }
            int size;
            try {
                size = Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            int[] c = bySize.computeIfAbsent(size, k -> new TreeMap<>())
                    .computeIfAbsent(t.tileset, k -> new int[2]);
            c[0]++;
            if (sprites.contains(n)) {
                c[1]++;
            }
        }

        System.out.printf("%-6s %-36s %8s %8s %-14s%n",
                "SIZE", "TILESET", "TILES", "WITHSPR", "SPECIES");
        for (Map.Entry<Integer, Map<String, int[]>> e : bySize.entrySet()) {
            for (Map.Entry<String, int[]> f : e.getValue().entrySet()) {
                System.out.printf("%-6d %-36s %8d %8d %-14s%n",
                        e.getKey(), f.getKey(), f.getValue()[0], f.getValue()[1],
                        speciesOf(f.getKey()));
            }
        }

        // Which sizes does each species actually have, with sprites?
        System.out.println("\nspecies coverage (sizes with at least one usable tile):");
        Map<String, TreeSet<Integer>> coverage = new LinkedHashMap<>();
        for (String s : SPECIES) {
            coverage.put(s, new TreeSet<>());
        }
        coverage.put("(unmatched)", new TreeSet<>());
        for (Map.Entry<Integer, Map<String, int[]>> e : bySize.entrySet()) {
            for (Map.Entry<String, int[]> f : e.getValue().entrySet()) {
                if (f.getValue()[1] > 0) {
                    coverage.get(speciesOf(f.getKey())).add(e.getKey());
                }
            }
        }
        for (Map.Entry<String, TreeSet<Integer>> e : coverage.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            System.out.printf("   %-14s %s%n", e.getKey(), e.getValue());
        }

        // Tilesets that carry tree tiles but match no known species name.
        List<String> unmatched = new ArrayList<>();
        for (Map<String, int[]> m : bySize.values()) {
            for (String ts : m.keySet()) {
                if (speciesOf(ts).equals("(unmatched)") && !unmatched.contains(ts)) {
                    unmatched.add(ts);
                }
            }
        }
        if (!unmatched.isEmpty()) {
            unmatched.sort(Comparator.naturalOrder());
            System.out.println("\ntilesets matching no known species name:");
            for (String ts : unmatched) {
                System.out.println("   " + ts);
            }
        }
    }

    /** Substring match: e_redmapleJUMBO_1 -> maple, e_riverbirchJUMBO_1 -> birch. */
    static String speciesOf(String tileset) {
        String low = tileset.toLowerCase();
        for (String s : SPECIES) {
            if (low.contains(s)) {
                return s;
            }
        }
        return "(unmatched)";
    }
}

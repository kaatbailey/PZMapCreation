package pzformat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Inspect tile definitions so palette entries can be chosen on evidence rather
 * than on name ordering.
 *
 * TilePalette currently picks the alphabetically first FLOOR-classified tile
 * under a name prefix. That is how blends_natural_01_101 became "grass" while
 * actually rendering as dirt, and how the interior floor ended up as a tile
 * that draws a checkerboard. The tiledefs carry CustomName, Material and
 * MoveType; those are the fields worth selecting on.
 *
 *   java -cp out pzformat.PaletteScan "$PZ/media" blends_natural_01
 *   java -cp out pzformat.PaletteScan "$PZ/media" --prop CustomName
 *   java -cp out pzformat.PaletteScan "$PZ/media" --find grass
 */
public final class PaletteScan {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage:");
            System.out.println("  PaletteScan <mediadir> <name-prefix>   table of tiles under a prefix");
            System.out.println("  PaletteScan <mediadir> --prop <key>    distinct values of a property");
            System.out.println("  PaletteScan <mediadir> --find <text>   tiles whose CustomName contains text");
            return;
        }

        Path media = Path.of(args[0]);
        TileIndex ti = TileIndex.load(media);
        Set<String> sprites = SpriteNames.load(media.resolve("texturepacks"));
        System.out.println("tiledefs: " + ti.byName.size() + "   sprites: " + sprites.size());
        System.out.println();

        switch (args[1]) {
            case "--prop" -> {
                if (args.length < 3) {
                    System.out.println("--prop needs a key");
                    return;
                }
                distinctValues(ti, sprites, args[2]);
            }
            case "--find" -> {
                if (args.length < 3) {
                    System.out.println("--find needs some text");
                    return;
                }
                findByName(ti, sprites, args[2]);
            }
            default -> table(ti, sprites, args[1]);
        }
    }

    /** Every tile under a prefix, in index order, with the fields worth choosing on. */
    static void table(TileIndex ti, Set<String> sprites, String prefix) {
        List<String> names = new ArrayList<>();
        for (String n : ti.byName.keySet()) {
            if (n.startsWith(prefix)) {
                names.add(n);
            }
        }
        if (names.isEmpty()) {
            System.out.println("nothing matches prefix " + prefix);
            System.out.println("try --find, or a shorter prefix");
            return;
        }
        names.sort(Comparator.comparingInt(PaletteScan::indexOf));

        System.out.printf("%-6s %-4s %-11s %-30s %-14s %-14s %s%n",
                "INDEX", "SPR", "KIND", "CustomName", "Material", "MoveType", "flags");

        for (String n : names) {
            TileDefs.Tile t = ti.get(n);
            if (t == null) {
                continue;
            }
            System.out.printf("%-6d %-4s %-11s %-30s %-14s %-14s %s%n",
                    indexOf(n),
                    sprites.contains(n) ? "yes" : "NO",
                    ti.kindOf(n),
                    trim(t.props.get("CustomName"), 30),
                    trim(t.props.get("Material"), 14),
                    trim(t.props.get("MoveType"), 14),
                    flags(t));
        }
        System.out.println("\n" + names.size() + " tiles under " + prefix);
    }

    /** How often each value of a property key occurs, across every tile. */
    static void distinctValues(TileIndex ti, Set<String> sprites, String key) {
        Map<String, int[]> counts = new TreeMap<>();
        for (String n : ti.byName.keySet()) {
            TileDefs.Tile t = ti.get(n);
            if (t == null) {
                continue;
            }
            String v = t.props.get(key);
            if (v == null) {
                continue;
            }
            if (v.isEmpty()) {
                v = "(bare flag)";
            }
            int[] c = counts.computeIfAbsent(v, k -> new int[2]);
            c[0]++;
            if (sprites.contains(n)) {
                c[1]++;
            }
        }
        if (counts.isEmpty()) {
            System.out.println("no tile carries the property " + key);
            return;
        }
        List<Map.Entry<String, int[]>> rows = new ArrayList<>(counts.entrySet());
        rows.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);

        System.out.printf("%-40s %8s %8s%n", key, "tiles", "with spr");
        for (Map.Entry<String, int[]> e : rows) {
            System.out.printf("%-40s %8d %8d%n",
                    trim(e.getKey(), 40), e.getValue()[0], e.getValue()[1]);
        }
    }

    /** Tiles whose CustomName mentions something, grouped by sheet. */
    static void findByName(TileIndex ti, Set<String> sprites, String needle) {
        String want = needle.toLowerCase();
        Map<String, List<String>> bySheet = new LinkedHashMap<>();

        for (String n : ti.byName.keySet()) {
            TileDefs.Tile t = ti.get(n);
            if (t == null) {
                continue;
            }
            String cn = t.props.get("CustomName");
            if (cn == null || !cn.toLowerCase().contains(want)) {
                continue;
            }
            if (!sprites.contains(n)) {
                continue;
            }
            bySheet.computeIfAbsent(t.tileset, k -> new ArrayList<>()).add(n);
        }

        if (bySheet.isEmpty()) {
            System.out.println("no tile with a sprite has \"" + needle + "\" in CustomName");
            return;
        }
        for (Map.Entry<String, List<String>> e : bySheet.entrySet()) {
            List<String> ns = e.getValue();
            ns.sort(Comparator.comparingInt(PaletteScan::indexOf));
            System.out.println(e.getKey() + "  (" + ns.size() + ")");
            for (String n : ns.subList(0, Math.min(12, ns.size()))) {
                TileDefs.Tile t = ti.get(n);
                System.out.printf("    %-6d %-11s %-30s %s%n",
                        indexOf(n), ti.kindOf(n),
                        trim(t.props.get("CustomName"), 30), flags(t));
            }
            if (ns.size() > 12) {
                System.out.println("    ... +" + (ns.size() - 12) + " more");
            }
        }
    }

    static String flags(TileDefs.Tile t) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : t.props.entrySet()) {
            if (e.getValue().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(e.getKey());
            }
        }
        return sb.toString();
    }

    static String trim(String s, int n) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "\u2026";
    }

    static int indexOf(String name) {
        int i = name.lastIndexOf('_');
        if (i < 0 || i == name.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(i + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

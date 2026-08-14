package pzformat;

import java.nio.file.Path;
import java.util.*;

/**
 * Does the mask rule reproduce vanilla, at corpus scale? Read-only.
 *
 * STATE §26 established the rule on a contiguous 9x5 rectangle of 42_40 — 21
 * masks, all explained. Two clauses rest on very little:
 *
 *   |S| = 3   ONE observation, (111,199), which chose two corner tiles sharing
 *             the middle direction over corner-plus-side.
 *   |S| = 4   ONE observation, (112,200), an isolated square.
 *
 * A rule inferred from a single vanilla square is the failure this project has
 * hit three times. This settles it over whole cells.
 *
 * METHOD. For every square, take its solid FloorMaterial and its four
 * orthogonal neighbours' solid materials. For each neighbouring material X,
 * S = the directions holding X. Then read the mask tiles actually present that
 * belong to X's block, and record which encoding vanilla used.
 *
 * Offsets within a block: 1-4 corners (NW, ES, SW, EN), 8-15 sides
 * (N, W, E, S in two variant sets).
 *
 * Also reports two failure modes the rule would not survive:
 *
 *   UNEXPLAINED  a mask whose material is on none of the four sides
 *   MISSING      an outranking neighbour with no mask written for it
 *
 * Both are expected at the noise floor §27 measured (1 in 3,000 for natural
 * ground) — hand-edits. Far above that means the rule is wrong.
 *
 * Usage:
 *   java -cp out pzformat.MaskAudit MEDIA_DIR MAP_DIR CELL [CELL...]
 */
public final class MaskAudit {

    /** encoding histogram, keyed by "geometry -> sorted offsets". */
    static final Map<String, Map<String, Long>> enc = new TreeMap<>();
    static final Map<String, String> example = new HashMap<>();
    static long unexplained = 0, missing = 0, squares = 0, maskTotal = 0;
    static final Map<String, Long> unexplainedBy = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: MaskAudit MEDIA_DIR MAP_DIR CELL [CELL...]");
            return;
        }
        TileIndex ti = TileIndex.load(Path.of(args[0]));
        Path mapDir = Path.of(args[1]);
        System.out.println("tile definitions: " + ti.byName.size());

        int cells = 0;
        for (int ci = 2; ci < args.length; ci++) {
            try {
                audit(ti, mapDir, args[ci]);
            } catch (Exception e) {
                System.out.println("  skipped " + args[ci] + ": " + e.getMessage());
                continue;
            }
            if (++cells % 250 == 0) System.out.println("  ... " + cells + " cells");
        }
        System.out.println("cells: " + cells + "   squares with masks: " + squares
                + "   masks: " + maskTotal + "\n");

        for (Map.Entry<String, Map<String, Long>> e : enc.entrySet()) {
            long tot = 0;
            for (long v : e.getValue().values()) tot += v;
            final long total = tot;
            System.out.println("=".repeat(64));
            System.out.println(e.getKey() + "   n=" + tot);
            System.out.println("=".repeat(64));
            e.getValue().entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(8)
                    .forEach(x -> System.out.printf("  %-26s %9d  (%5.1f%%)  eg %s%n",
                            x.getKey(), x.getValue(), 100.0 * x.getValue() / total,
                            example.getOrDefault(e.getKey() + x.getKey(), "-")));
        }

        System.out.println("\n" + "=".repeat(64));
        System.out.println("FAILURE MODES");
        System.out.println("=".repeat(64));
        System.out.printf("  unexplained masks (material on no side): %d  (%.3f%% of masks)%n",
                unexplained, maskTotal == 0 ? 0 : 100.0 * unexplained / maskTotal);
        unexplainedBy.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(6)
                .forEach(x -> System.out.printf("      %-34s %8d  eg %s%n", x.getKey(),
                        x.getValue(), example.getOrDefault("UNEXPLAINED" + x.getKey(), "-")));
        System.out.printf("  outranking neighbour with no mask:       %d%n", missing);
        System.out.println("  §27 noise floor is ~1 in 3,000 for natural ground.");
        System.out.println("  Far above that means the rule is wrong, not vanilla.");
    }

    static void audit(TileIndex ti, Path mapDir, String cellName) throws Exception {
        Path lh = mapDir.resolve(cellName + ".lotheader");
        Path lp = mapDir.resolve("world_" + cellName + ".lotpack");
        CellData c = CellData.load(lp, lh);
        int n = c.cellSize;

        String[][] solid = new String[n][n];
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++) {
                String[] names = c.tileNamesAt(x, y, 0);
                if (names == null) continue;
                for (String name : names) {
                    TileDefs.Tile t = ti.get(name);
                    if (t == null) continue;
                    if (t.props.containsKey("FloorOverlay")) continue;
                    if (!t.props.containsKey("solidfloor")) continue;
                    String m = t.props.get("FloorMaterial");
                    if (m != null && !m.isEmpty()) solid[x][y] = m;
                    break;
                }
            }

        for (int x = 1; x < n - 1; x++)
            for (int y = 1; y < n - 1; y++) {
                String[] names = c.tileNamesAt(x, y, 0);
                if (names == null || solid[x][y] == null) continue;

                // masks present, grouped by their material
                Map<String, List<Integer>> byMat = new TreeMap<>();
                for (String name : names) {
                    TileDefs.Tile t = ti.get(name);
                    if (t == null) continue;
                    Map<String, String> p = t.props;
                    if (!p.containsKey("FloorOverlay")) continue;
                    String m = p.get("FloorMaterial");
                    if (m == null || m.isEmpty()) continue;      // decal, not a mask
                    if (!hasAttachment(p)) continue;
                    int u = name.lastIndexOf('_');
                    int idx;
                    try { idx = Integer.parseInt(name.substring(u + 1)); } catch (Exception e) { continue; }
                    byMat.computeIfAbsent(m, k -> new ArrayList<>()).add(idx % 16);
                }
                if (byMat.isEmpty()) continue;
                squares++;

                // neighbour materials
                Map<String, TreeSet<String>> sides = new TreeMap<>();
                addSide(sides, solid[x][y - 1], "N");
                addSide(sides, solid[x - 1][y], "W");
                addSide(sides, solid[x + 1][y], "E");
                addSide(sides, solid[x][y + 1], "S");
                sides.remove(solid[x][y]);

                for (Map.Entry<String, List<Integer>> e : byMat.entrySet()) {
                    maskTotal += e.getValue().size();
                    TreeSet<String> s = sides.get(e.getKey());
                    if (s == null) {
                        unexplained += e.getValue().size();
                        String uk = e.getKey() + " on " + solid[x][y];
                        unexplainedBy.merge(uk, 1L, Long::sum);
                        example.putIfAbsent("UNEXPLAINED" + uk,
                                cellName + " (" + x + "," + y + ")");
                        continue;
                    }
                    List<Integer> offs = new ArrayList<>(e.getValue());
                    Collections.sort(offs);
                    String key = geometry(s);
                    String val = describe(offs);
                    enc.computeIfAbsent(key, k -> new TreeMap<>()).merge(val, 1L, Long::sum);
                    example.putIfAbsent(key + val, cellName + " (" + x + "," + y + ")");
                }
                for (String m : sides.keySet())
                    if (!byMat.containsKey(m)) missing++;
            }
    }

    static void addSide(Map<String, TreeSet<String>> sides, String mat, String dir) {
        if (mat != null) sides.computeIfAbsent(mat, k -> new TreeSet<>()).add(dir);
    }

    static boolean hasAttachment(Map<String, String> p) {
        return p.containsKey("FloorAttachmentN") || p.containsKey("FloorAttachmentS")
                || p.containsKey("FloorAttachmentE") || p.containsKey("FloorAttachmentW");
    }

    /** "|S|=2 adjacent N,W" and so on — the geometry the rule keys on. */
    static String geometry(TreeSet<String> s) {
        if (s.size() == 2) {
            boolean opposite = (s.contains("N") && s.contains("S"))
                    || (s.contains("E") && s.contains("W"));
            return "|S|=2 " + (opposite ? "opposite" : "adjacent") + " " + s;
        }
        return "|S|=" + s.size() + " " + s;
    }

    /** Offsets as corners and sides, so the encoding is readable at a glance. */
    static String describe(List<Integer> offs) {
        int corners = 0, sides = 0, other = 0;
        for (int o : offs) {
            if (o >= 1 && o <= 4) corners++;
            else if (o >= 8 && o <= 15) sides++;
            else other++;
        }
        return corners + "c " + sides + "s" + (other > 0 ? " " + other + "?" : "")
                + "  " + offs;
    }
}

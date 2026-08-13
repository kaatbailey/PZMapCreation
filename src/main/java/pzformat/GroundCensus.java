package pzformat;

import java.nio.file.Path;
import java.util.*;

/**
 * Whole-cell ground census. Read-only measurement for E7; writes nothing.
 *
 * `Probe findprop` caps at 3 hits per cell, which cannot measure a boundary —
 * a 3-square sample can never produce a run longer than 1, so it reported
 * "100% width 1" for every cell including one measured by hand to be dithered.
 * A test that cannot fail proves nothing.
 *
 * This walks every square at z=0 and answers:
 *
 *   Q1  material priority     — the mask's material won, the solid's lost
 *   Q2  is priority total     — any pair seen masking in both directions?
 *   Q3  cross-tileset masks   — does a road square carry a grass mask?
 *   Q4  dither vs clean edges — region component sizes, and how many
 *                               orthogonal neighbours differ per boundary square
 *   Q5  multi-material squares— masks from two different blocks on one square
 *
 * Plus the FloorMaterial of every mask index seen, to settle whether the
 * 16-tile block contract holds past index 111.
 *
 * Usage:
 *   java -cp out pzformat.GroundCensus MEDIA_DIR MAP_DIR CELL [CELL...]
 */
public final class GroundCensus {

    /** Solid ground material per square, or null where there is no solid floor. */
    static String[][] grid;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: GroundCensus MEDIA_DIR MAP_DIR CELL [CELL...]");
            return;
        }
        Path mediaDir = Path.of(args[0]);
        Path mapDir = Path.of(args[1]);

        TileIndex ti = TileIndex.load(mediaDir);
        System.out.println("tile definitions: " + ti.byName.size() + "\n");

        Map<String, Integer> pairs = new TreeMap<>();          // "winner>loser" -> n
        Map<String, String> pairExample = new HashMap<>();
        Map<String, String> tileMaterial = new TreeMap<>();    // mask tile -> material
        List<String> crossSheet = new ArrayList<>();
        List<String> multiMaterial = new ArrayList<>();

        for (int ci = 2; ci < args.length; ci++) {
            String cellName = args[ci];
            Path lh = mapDir.resolve(cellName + ".lotheader");
            Path lp = mapDir.resolve("world_" + cellName + ".lotpack");
            CellData c = CellData.load(lp, lh);
            int n = c.cellSize;

            grid = new String[n][n];
            int masked = 0, solids = 0;

            for (int x = 0; x < n; x++) {
                for (int y = 0; y < n; y++) {
                    String[] names = c.tileNamesAt(x, y, 0);
                    if (names == null) continue;

                    String solidName = null, loser = null;
                    List<String> maskNames = new ArrayList<>();
                    Set<String> winners = new TreeSet<>();

                    for (String name : names) {
                        TileDefs.Tile t = ti.get(name);
                        if (t == null) continue;
                        Map<String, String> p = t.props;
                        if (p.containsKey("FloorOverlay")) {
                            maskNames.add(name);
                            String m = p.get("FloorMaterial");
                            if (m != null && !m.isEmpty()) {
                                winners.add(m);
                                tileMaterial.put(name, m);
                            }
                        } else if (p.containsKey("solidfloor") && solidName == null) {
                            solidName = name;
                            loser = p.get("FloorMaterial");
                        }
                    }

                    if (solidName != null && loser != null && !loser.isEmpty()) {
                        grid[x][y] = loser;
                        solids++;
                    }
                    if (maskNames.isEmpty()) continue;
                    masked++;

                    if (winners.size() > 1 && multiMaterial.size() < 20)
                        multiMaterial.add("  " + cellName + " (" + x + "," + y + ")  solid "
                                + loser + "  masks from " + winners);

                    if (loser == null) continue;
                    for (String w : winners) {
                        if (w.equals(loser)) continue;
                        String key = w + " > " + loser;
                        pairs.merge(key, 1, Integer::sum);
                        pairExample.putIfAbsent(key, cellName + " (" + x + "," + y + ")");
                    }
                    if (solidName != null)
                        for (String m : maskNames)
                            if (!sheetOf(m).equals(sheetOf(solidName)) && crossSheet.size() < 20)
                                crossSheet.add("  " + cellName + " (" + x + "," + y + ")  solid "
                                        + solidName + "  mask " + m);
                }
            }

            System.out.println("=== " + cellName + " ===");
            System.out.println("  squares with a solid ground tile : " + solids
                    + " of " + (n * n));
            System.out.println("  squares carrying at least one mask: " + masked
                    + String.format("  (%.1f%%)", solids == 0 ? 0 : 100.0 * masked / solids));
            reportRegions(n);
            reportBoundary(n);
            System.out.println();
        }

        line("Q1 — PRIORITY: winner masks onto loser");
        for (Map.Entry<String, Integer> e : sortedByValue(pairs))
            System.out.printf("  %-34s n=%-8d eg %s%n",
                    e.getKey(), e.getValue(), pairExample.get(e.getKey()));

        line("Q2 — CONTRADICTIONS (both directions seen)");
        boolean any = false;
        for (String k : pairs.keySet()) {
            String[] ab = k.split(" > ");
            String rev = ab[1] + " > " + ab[0];
            if (pairs.containsKey(rev) && ab[0].compareTo(ab[1]) < 0) {
                any = true;
                System.out.printf("  %s (n=%d)  vs  %s (n=%d)%n",
                        k, pairs.get(k), rev, pairs.get(rev));
                System.out.println("      eg " + pairExample.get(k)
                        + " and " + pairExample.get(rev));
            }
        }
        if (!any) System.out.println("  none — total order over the pairs observed");

        line("Q3 — CROSS-TILESET MASKS");
        if (crossSheet.isEmpty())
            System.out.println("  none — boundaries between tilesets are hard");
        else crossSheet.forEach(System.out::println);

        line("Q5 — MULTI-MATERIAL SQUARES");
        if (multiMaterial.isEmpty())
            System.out.println("  none — the multi-material case does not occur here");
        else multiMaterial.forEach(System.out::println);

        line("BLOCK CONTRACT — FloorMaterial by mask tile");
        Map<String, TreeSet<Integer>> byMat = new TreeMap<>();
        for (Map.Entry<String, String> e : tileMaterial.entrySet()) {
            String name = e.getKey();
            int u = name.lastIndexOf('_');
            String sheet = name.substring(0, u);
            int idx;
            try { idx = Integer.parseInt(name.substring(u + 1)); } catch (Exception ex) { continue; }
            byMat.computeIfAbsent(sheet + "  " + e.getValue(), k -> new TreeSet<>()).add(idx);
        }
        byMat.forEach((k, v) -> System.out.printf("  %-38s %s%n", k, v));
    }

    /**
     * Connected components of identical solid material, 4-connected.
     * Clean regions give few large components. Dither gives many tiny ones.
     */
    static void reportRegions(int n) {
        boolean[][] seen = new boolean[n][n];
        List<Integer> sizes = new ArrayList<>();
        int[] qx = new int[n * n], qy = new int[n * n];

        for (int sx = 0; sx < n; sx++)
            for (int sy = 0; sy < n; sy++) {
                if (seen[sx][sy] || grid[sx][sy] == null) continue;
                String mat = grid[sx][sy];
                int head = 0, tail = 0, size = 0;
                qx[tail] = sx; qy[tail] = sy; tail++;
                seen[sx][sy] = true;
                while (head < tail) {
                    int x = qx[head], y = qy[head]; head++;
                    size++;
                    for (int d = 0; d < 4; d++) {
                        int nx = x + (d == 2 ? -1 : d == 3 ? 1 : 0);
                        int ny = y + (d == 0 ? -1 : d == 1 ? 1 : 0);
                        if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
                        if (seen[nx][ny] || !mat.equals(grid[nx][ny])) continue;
                        seen[nx][ny] = true;
                        qx[tail] = nx; qy[tail] = ny; tail++;
                    }
                }
                sizes.add(size);
            }

        sizes.sort(Comparator.reverseOrder());
        int singles = 0, small = 0;
        for (int s : sizes) { if (s == 1) singles++; if (s <= 4) small++; }
        System.out.println("  material regions (4-connected components): " + sizes.size());
        System.out.println("    largest: " + sizes.subList(0, Math.min(5, sizes.size())));
        System.out.printf("    single-square islands: %d  (%.1f%% of components)%n",
                singles, sizes.isEmpty() ? 0 : 100.0 * singles / sizes.size());
        System.out.printf("    components of 4 or fewer squares: %d  (%.1f%%)%n",
                small, sizes.isEmpty() ? 0 : 100.0 * small / sizes.size());
    }

    /**
     * For every square with at least one differing orthogonal neighbour, how
     * many of its four differ. A clean straight edge is mostly 1 (2 at corners).
     * Dither produces 2s and 3s, and 4s are isolated islands.
     */
    static void reportBoundary(int n) {
        int[] hist = new int[5];
        int boundary = 0, interior = 0;
        for (int x = 1; x < n - 1; x++)
            for (int y = 1; y < n - 1; y++) {
                String m = grid[x][y];
                if (m == null) continue;
                int diff = 0;
                if (grid[x][y - 1] != null && !m.equals(grid[x][y - 1])) diff++;
                if (grid[x][y + 1] != null && !m.equals(grid[x][y + 1])) diff++;
                if (grid[x - 1][y] != null && !m.equals(grid[x - 1][y])) diff++;
                if (grid[x + 1][y] != null && !m.equals(grid[x + 1][y])) diff++;
                if (diff == 0) { interior++; continue; }
                boundary++;
                hist[diff]++;
            }
        System.out.printf("  boundary squares: %d   pure interior: %d%n", boundary, interior);
        for (int d = 1; d <= 4; d++)
            System.out.printf("    %d differing neighbour%s: %6d  (%.1f%%)%n",
                    d, d == 1 ? " " : "s", hist[d],
                    boundary == 0 ? 0 : 100.0 * hist[d] / boundary);
        System.out.println("    mostly 1 => clean edges.  many 2-3, any 4 => dithered.");
    }

    static String sheetOf(String tile) {
        int u = tile.lastIndexOf('_');
        return u < 0 ? tile : tile.substring(0, u);
    }

    static List<Map.Entry<String, Integer>> sortedByValue(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        return l;
    }

    static void line(String title) {
        System.out.println("\n" + "=".repeat(62));
        System.out.println(title);
        System.out.println("=".repeat(62));
    }
}

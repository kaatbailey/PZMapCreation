package pzformat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Measure how vanilla actually composes outdoor ground squares.
 *
 * Hand sampling has already produced two wrong conclusions: six squares in
 * cell 27_27 all carried a grass overlay, which looked like "every square gets
 * one", while cell 35_35's first three squares carry none and use a different
 * base tile entirely. Six samples is not a distribution.
 *
 * Reports, across every z=0 square of the given cells:
 *   - which base ground tiles appear, and in what proportion
 *   - what fraction of ground squares carry an overlay from
 *     blends_grassoverlays_01, and which overlays
 *   - whether overlay presence depends on the base tile
 *   - the distribution of stack depths
 *
 * That is what the generator needs in order to produce ground that does not
 * read as a flat rectangle against procedurally generated neighbours.
 *
 *   java -cp out pzformat.GroundSurvey "$MAPS/Muldraugh, KY" 27_27 35_35
 */
public final class GroundSurvey {

    static final String OVERLAY_SHEET = "blends_grassoverlays_01";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: GroundSurvey <mapdir> <cell> [cell ...]");
            System.out.println("  e.g. GroundSurvey \"$MAPS/Muldraugh, KY\" 27_27 35_35");
            return;
        }
        Path mapDir = Path.of(args[0]);

        Map<String, Integer> baseCount = new TreeMap<>();
        Map<String, Integer> overlayCount = new TreeMap<>();
        Map<String, int[]> overlayByBase = new TreeMap<>();   // base -> [squares, withOverlay]
        Map<Integer, Integer> depth = new TreeMap<>();
        long squares = 0, empty = 0, withOverlay = 0, multiOverlay = 0;

        for (int a = 1; a < args.length; a++) {
            String cellName = args[a];
            CellData cell = CellData.load(
                    mapDir.resolve("world_" + cellName + ".lotpack"),
                    mapDir.resolve(cellName + ".lotheader"));

            for (int x = 0; x < cell.cellSize; x++) {
                for (int y = 0; y < cell.cellSize; y++) {
                    String[] names = cell.tileNamesAt(x, y, 0);
                    squares++;
                    if (names == null || names.length == 0) {
                        empty++;
                        continue;
                    }
                    depth.merge(names.length, 1, Integer::sum);

                    String base = null;
                    List<String> overlays = new ArrayList<>();
                    for (String n : names) {
                        if (n.startsWith(OVERLAY_SHEET)) {
                            overlays.add(n);
                        } else if (base == null && n.startsWith("blends_natural_")) {
                            base = n;
                        }
                    }
                    if (base == null) {
                        continue;            // road, building interior, etc.
                    }

                    baseCount.merge(base, 1, Integer::sum);
                    int[] bo = overlayByBase.computeIfAbsent(base, k -> new int[2]);
                    bo[0]++;
                    if (!overlays.isEmpty()) {
                        bo[1]++;
                        withOverlay++;
                        if (overlays.size() > 1) {
                            multiOverlay++;
                        }
                        for (String o : overlays) {
                            overlayCount.merge(o, 1, Integer::sum);
                        }
                    }
                }
            }
            System.out.println("read " + cellName);
        }

        long ground = 0;
        for (int n : baseCount.values()) {
            ground += n;
        }

        System.out.println("\nsquares scanned: " + squares
                + "   empty: " + empty
                + "   with a blends_natural base: " + ground);

        System.out.println("\nbase ground tiles:");
        printSorted(baseCount, ground);

        System.out.printf("%nsquares carrying a grass overlay: %d / %d  (%.1f%%)"
                        + "   more than one: %d%n",
                withOverlay, ground, ground == 0 ? 0.0 : 100.0 * withOverlay / ground,
                multiOverlay);

        System.out.println("\noverlay presence by base tile:");
        for (Map.Entry<String, int[]> e : overlayByBase.entrySet()) {
            int[] v = e.getValue();
            System.out.printf("   %-30s %7d squares  %6.1f%% overlaid%n",
                    e.getKey(), v[0], v[0] == 0 ? 0.0 : 100.0 * v[1] / v[0]);
        }

        System.out.println("\noverlay tiles used:");
        long overlayTotal = 0;
        for (int n : overlayCount.values()) {
            overlayTotal += n;
        }
        printSorted(overlayCount, overlayTotal);

        System.out.println("\nstack depth (tiles per non-empty square):");
        for (Map.Entry<Integer, Integer> e : depth.entrySet()) {
            System.out.printf("   %2d tiles  %8d%n", e.getKey(), e.getValue());
        }
    }

    static void printSorted(Map<String, Integer> counts, long total) {
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(counts.entrySet());
        rows.sort((p, q) -> q.getValue() - p.getValue());
        for (Map.Entry<String, Integer> e : rows) {
            System.out.printf("   %-34s %8d  %5.1f%%%n",
                    e.getKey(), e.getValue(),
                    total == 0 ? 0.0 : 100.0 * e.getValue() / total);
        }
        if (rows.isEmpty()) {
            System.out.println("   (none)");
        }
    }

    private GroundSurvey() { }
}

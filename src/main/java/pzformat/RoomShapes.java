package pzformat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What shapes do vanilla rooms actually take? Read-only; writes nothing.
 *
 * WHY. STATE §10: a room is a union of int32 x,y,w,h rectangles. There is no
 * rotation field and no polygon, so every wall runs due north-south or
 * east-west. Shape is free — L-shapes, T-shapes, wings, courtyards are all
 * representable — but EDGE DIRECTION is not. An axis-aligned edge lands exactly
 * on the tile grid, which is why an aligned building has no steps at all;
 * jaggedness is the symptom of an off-axis edge, not a defect in its own right.
 *
 * So the target orientation is known in advance: 0 degrees, because the format
 * admits nothing else. There is no dominant grid to discover. FootprintAngles
 * confirmed there isn't one in the source data anyway — 7 rural footprints at
 * 37/61/65/71/76/80 degrees, best +/-3 window holding 33.3%, which is scattered
 * farmsteads each oriented to its own driveway rather than a street grid.
 *
 * THE REMAINING QUESTION, which this answers. GIS gives us where a building is,
 * how big, and what type. We must author an axis-aligned shape in its place.
 * How complex should that shape be? A plain oriented bounding box is a shoebox;
 * a union of several rectangles can read as a real building. Vanilla is the
 * reference for how much complexity is normal.
 *
 * REPORTS, per map:
 *   rects per room          the shape vocabulary we may draw from
 *   min(w,h) == 1 fraction  the staircase signature — near zero in vanilla
 *   fill ratio              summed rect area over bounding-box area.
 *                           1.0 is a plain rectangle, ~0.75 an L, ~0.5 a
 *                           diagonal staircase
 *   diagonal runs           successive rects offset by ~1 on BOTH axes with a
 *                           near-1 dimension — §17's unverified check 1,
 *                           verbatim. Zero across the corpus means the
 *                           constraint is hard and a snap may refuse outright;
 *                           nonzero means it is a strong default with an
 *                           override, and one such room must be read.
 *   bounding box sizes      what a vanilla building actually measures, to
 *                           compare against the GIS footprints
 *
 * Run it on Muldraugh and on our own map with the same instrument.
 *
 * Usage:
 *   java -cp out pzformat.RoomShapes MAP_DIR CELL [CELL...]
 */
public final class RoomShapes {

    static final Map<Integer, Long> rectsPerRoom = new TreeMap<>();
    static final Map<String, Long> sizeHist = new TreeMap<>();
    static final Map<String, Long> byName = new TreeMap<>();
    static final List<double[]> fills = new ArrayList<>();
    static long rooms = 0, rects = 0, oneWide = 0, diagonalRuns = 0;
    static final List<String> diagExamples = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: RoomShapes MAP_DIR CELL [CELL...]");
            return;
        }
        Path mapDir = Path.of(args[0]);

        int cells = 0;
        for (int i = 1; i < args.length; i++) {
            try {
                scan(LotHeader.read(mapDir.resolve(args[i] + ".lotheader")), args[i]);
            } catch (Exception e) {
                continue;
            }
            if (++cells % 500 == 0) System.out.println("  ... " + cells + " cells");
        }

        System.out.println("cells: " + cells + "   rooms: " + rooms
                + "   rects: " + rects + "\n");

        System.out.println("=".repeat(60));
        System.out.println("RECTS PER ROOM — the shape vocabulary");
        System.out.println("=".repeat(60));
        long shown = 0;
        for (Map.Entry<Integer, Long> e : rectsPerRoom.entrySet()) {
            if (e.getKey() > 12) continue;
            shown += e.getValue();
            System.out.printf("  %2d rect%s %9d  (%5.1f%%)  %s%n",
                    e.getKey(), e.getKey() == 1 ? " " : "s", e.getValue(),
                    100.0 * e.getValue() / rooms,
                    "#".repeat((int) Math.min(40, 40 * e.getValue() / Math.max(1, rooms))));
        }
        if (rooms > shown)
            System.out.printf("  >12 rects %9d  (%5.1f%%)%n", rooms - shown,
                    100.0 * (rooms - shown) / rooms);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("JAGGEDNESS SIGNATURES");
        System.out.println("=".repeat(60));
        System.out.printf("  rects with min(w,h) == 1 : %d of %d  (%.2f%%)%n",
                oneWide, rects, rects == 0 ? 0 : 100.0 * oneWide / rects);
        System.out.printf("  diagonal runs (§17 check 1): %d%n", diagonalRuns);
        for (String s : diagExamples) System.out.println("      " + s);
        if (diagonalRuns == 0)
            System.out.println("      none — the constraint is HARD, a snap may refuse outright");
        else
            System.out.println("      nonzero — strong default with an override; read one before deciding");

        Collections.sort(fills, (a, b) -> Double.compare(a[0], b[0]));
        System.out.println("\n" + "=".repeat(60));
        System.out.println("FILL RATIO — summed rect area / bounding box area");
        System.out.println("=".repeat(60));
        if (!fills.isEmpty()) {
            System.out.printf("  median %.3f   p10 %.3f   p90 %.3f%n",
                    pct(fills, 50), pct(fills, 10), pct(fills, 90));
            long exact = 0;
            for (double[] f : fills) if (f[0] > 0.999) exact++;
            System.out.printf("  exactly 1.000 (a plain rectangle): %d of %d  (%.1f%%)%n",
                    exact, fills.size(), 100.0 * exact / fills.size());
            System.out.println("  1.0 plain rectangle · ~0.75 an L · ~0.5 a diagonal staircase");
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("BOUNDING BOX SIZE — what a vanilla room measures, in squares");
        System.out.println("=".repeat(60));
        sizeHist.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(e -> System.out.printf("  %-12s %8d%n", e.getKey(), e.getValue()));

        System.out.println("\n" + "=".repeat(60));
        System.out.println("ROOM NAMES — the type vocabulary");
        System.out.println("=".repeat(60));
        byName.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(20)
                .forEach(e -> System.out.printf("  %-22s %8d%n", e.getKey(), e.getValue()));
    }

    static void scan(LotHeader h, String cell) {
        for (LotHeader.Room r : h.rooms) {
            if (r.rects == null || r.rects.isEmpty()) continue;
            rooms++;
            rects += r.rects.size();
            rectsPerRoom.merge(r.rects.size(), 1L, Long::sum);
            byName.merge(r.name == null ? "(none)" : r.name, 1L, Long::sum);

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            long area = 0;
            for (int[] q : r.rects) {
                if (Math.min(q[2], q[3]) <= 1) oneWide++;
                area += (long) q[2] * q[3];
                minX = Math.min(minX, q[0]);
                minY = Math.min(minY, q[1]);
                maxX = Math.max(maxX, q[0] + q[2]);
                maxY = Math.max(maxY, q[1] + q[3]);
            }
            long box = (long) (maxX - minX) * (maxY - minY);
            if (box > 0) fills.add(new double[]{Math.min(1.0, (double) area / box)});

            int w = maxX - minX, ht = maxY - minY;
            sizeHist.merge(bucket(w) + " x " + bucket(ht), 1L, Long::sum);

            // §17 check 1: successive rects offset by ~1 on BOTH axes, with a
            // near-1 dimension. That is what a diagonal wall would have to
            // become if the format allowed one to be authored.
            for (int i = 0; i + 1 < r.rects.size(); i++) {
                int[] a = r.rects.get(i), b = r.rects.get(i + 1);
                int dx = Math.abs(b[0] - a[0]), dy = Math.abs(b[1] - a[1]);
                boolean thin = Math.min(a[2], a[3]) <= 1 && Math.min(b[2], b[3]) <= 1;
                if (thin && dx >= 1 && dx <= 2 && dy >= 1 && dy <= 2) {
                    diagonalRuns++;
                    if (diagExamples.size() < 6)
                        diagExamples.add(cell + " room '" + r.name + "' rects "
                                + fmt(a) + " then " + fmt(b));
                }
            }
        }
    }

    static String fmt(int[] q) {
        return "[" + q[0] + "," + q[1] + " " + q[2] + "x" + q[3] + "]";
    }

    /** Coarse buckets so the size histogram is readable. */
    static String bucket(int v) {
        if (v <= 4) return "1-4";
        if (v <= 8) return "5-8";
        if (v <= 12) return "9-12";
        if (v <= 16) return "13-16";
        if (v <= 24) return "17-24";
        if (v <= 32) return "25-32";
        return "33+";
    }

    static double pct(List<double[]> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.round((p / 100.0) * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)))[0];
    }
}

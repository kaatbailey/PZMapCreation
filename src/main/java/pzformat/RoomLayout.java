package pzformat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * How does vanilla arrange rooms inside a building? Read-only; writes nothing.
 *
 * E13's recipe says WHICH rooms (STATE §33). It does not say where. Two
 * questions, and both change the algorithm rather than a constant.
 *
 * 1. IS IT A BSP? A binary space partition leaves a signature: every internal
 *    wall spans the full width or height of the region it divides. If a
 *    building's rooms can be produced by recursive splitting, then a recursive
 *    splitter reproduces vanilla exactly. If rooms meet in T-junctions and
 *    pinwheels, a naive BSP will look subtly wrong in a way that is hard to
 *    name and easy to feel.
 *
 *    Tested by trying to split: take the building's bounding box, look for a
 *    full-span horizontal or vertical line that no room crosses, recurse on
 *    both halves, and see whether every leaf ends up holding exactly one room.
 *    That is the definition, applied directly.
 *
 * 2. WHAT ARE THE AREA RATIOS BY TYPE? A bathroom is small, a livingroom
 *    large. If the ratios are stable, the subdivider can allocate area by type
 *    rather than splitting evenly and labelling afterwards — the difference
 *    between a plausible house and a grid of equal boxes. Reported as a
 *    multiple of the building's own mean room area, so a mansion and a cottage
 *    are comparable.
 *
 * PREDICTIONS, written before the first run. Area ratios stable and useful:
 * bathroom around 0.5x, livingroom 1.5-2x. BSP cleanliness 60-75%, NOT 95% —
 * hand-authored buildings will pinwheel where a hall wraps a corner, and §30
 * found 35% of rooms are multi-rect, which strict BSP cannot produce. Under
 * 50% would mean recursive splitting is the wrong algorithm entirely.
 *
 * Usage:
 *   java -cp out pzformat.RoomLayout MAP_DIR CELL [CELL...]
 */
public final class RoomLayout {

    static final Set<String> NOT_INTERIOR = Set.of("emptyoutside");

    /** area / (building's mean room area), per type. */
    static final Map<String, List<Double>> ratios = new TreeMap<>();
    static final Map<String, List<Integer>> absolute = new TreeMap<>();

    static long buildings = 0, bspClean = 0, bspFailed = 0;
    static final Map<Integer, long[]> bspByRooms = new TreeMap<>();
    static final List<String> failures = new ArrayList<>();

    /** Rooms whose rects do not form one solid rectangle. */
    static long multiRect = 0, roomsSeen = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: RoomLayout MAP_DIR CELL [CELL...]");
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

        System.out.println("\ncells: " + cells + "   multi-room buildings: " + buildings + "\n");
        if (buildings == 0) return;

        line("IS THE LAYOUT A BSP?");
        System.out.printf("  recursively splittable : %d  (%.1f%%)%n",
                bspClean, 100.0 * bspClean / buildings);
        System.out.printf("  not splittable        : %d  (%.1f%%)%n",
                bspFailed, 100.0 * bspFailed / buildings);
        System.out.println("\n  by room count:");
        System.out.printf("  %-8s %8s %8s %8s%n", "rooms", "total", "bsp", "rate");
        bspByRooms.forEach((k, v) -> {
            long tot = v[0] + v[1];
            if (tot < 20) return;
            System.out.printf("  %-8d %8d %8d %7.1f%%%n", k, tot, v[1], 100.0 * v[1] / tot);
        });
        System.out.println("\n  A high rate means a recursive splitter reproduces vanilla.");
        System.out.println("  Falling with room count means big buildings pinwheel.");
        if (!failures.isEmpty()) {
            System.out.println("\n  examples that would not split:");
            failures.forEach(f -> System.out.println("     " + f));
        }

        line("ROOM AREA BY TYPE");
        System.out.printf("  %-20s %7s %10s %10s %10s%n",
                "type", "n", "x mean", "median sq", "p90 sq");
        ratios.entrySet().stream()
                .filter(e -> e.getValue().size() >= 50)
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(20)
                .forEach(e -> {
                    List<Double> r = new ArrayList<>(e.getValue());
                    Collections.sort(r);
                    List<Integer> ab = new ArrayList<>(absolute.get(e.getKey()));
                    Collections.sort(ab);
                    System.out.printf("  %-20s %7d %10.2f %10d %10d%n", e.getKey(), r.size(),
                            r.get(r.size() / 2), ab.get(ab.size() / 2),
                            ab.get(ab.size() * 9 / 10));
                });
        System.out.println("\n  'x mean' is the room's area over its OWN building's mean room");
        System.out.println("  area, so a mansion and a cottage are comparable.");

        line("ROOM SHAPE");
        System.out.printf("  rooms that are NOT one solid rectangle: %d of %d  (%.1f%%)%n",
                multiRect, roomsSeen, 100.0 * multiRect / Math.max(1, roomsSeen));
        System.out.println("  A strict BSP cannot produce these, so this bounds the BSP rate.");
    }

    static void scan(LotHeader h, String cell) {
        int n = h.rooms.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        int[][] box = new int[n][];
        boolean[] use = new boolean[n];

        for (int i = 0; i < n; i++) {
            LotHeader.Room r = h.rooms.get(i);
            if (r.rects == null || r.rects.isEmpty()) continue;
            if (r.name != null && NOT_INTERIOR.contains(r.name)) continue;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            long area = 0;
            for (int[] q : r.rects) {
                minX = Math.min(minX, q[0]); minY = Math.min(minY, q[1]);
                maxX = Math.max(maxX, q[0] + q[2]); maxY = Math.max(maxY, q[1] + q[3]);
                area += (long) q[2] * q[3];
            }
            box[i] = new int[]{minX, minY, maxX, maxY};
            use[i] = true;
            roomsSeen++;
            if (area != (long) (maxX - minX) * (maxY - minY)) multiRect++;
        }

        for (int i = 0; i < n; i++) {
            if (!use[i]) continue;
            for (int j = i + 1; j < n; j++) {
                if (!use[j]) continue;
                if (h.rooms.get(i).floor != h.rooms.get(j).floor) continue;
                if (touching(box[i], box[j])) union(parent, i, j);
            }
        }

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++)
            if (use[i]) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);

        for (List<Integer> g : groups.values()) analyse(h, g, box, cell);
    }

    static void analyse(LotHeader h, List<Integer> g, int[][] box, String cell) {
        if (g.size() < 2 || g.size() > 24) return;
        buildings++;

        // Area ratios, against this building's own mean room area.
        double mean = 0;
        for (int i : g) mean += area(box[i]);
        mean /= g.size();
        if (mean > 0)
            for (int i : g) {
                String nm = h.rooms.get(i).name == null ? "(none)" : h.rooms.get(i).name;
                ratios.computeIfAbsent(nm, k -> new ArrayList<>()).add(area(box[i]) / mean);
                absolute.computeIfAbsent(nm, k -> new ArrayList<>()).add((int) area(box[i]));
            }

        // Split on RECTS, tagged by which room they belong to. A room's
        // bounding box is not its shape: an L-shaped livingroom's box swallows
        // whatever sits in the notch, so boxes overlap where the rooms do not.
        List<int[]> rects = new ArrayList<>();     // x0, y0, x1, y1, roomIndex
        for (int i : g)
            for (int[] q : h.rooms.get(i).rects)
                rects.add(new int[]{q[0], q[1], q[0] + q[2], q[1] + q[3], i});

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] b : rects) {
            minX = Math.min(minX, b[0]); minY = Math.min(minY, b[1]);
            maxX = Math.max(maxX, b[2]); maxY = Math.max(maxY, b[3]);
        }

        boolean ok = splittable(rects, minX, minY, maxX, maxY);
        long[] c = bspByRooms.computeIfAbsent(g.size(), k -> new long[2]);
        if (ok) { bspClean++; c[1]++; }
        else {
            bspFailed++; c[0]++;
            if (failures.size() < 6 && g.size() <= 6) {
                StringBuilder sb = new StringBuilder(cell + " " + g.size() + " rooms: ");
                for (int i : g) {
                    sb.append(h.rooms.get(i).name);
                    for (int[] q : h.rooms.get(i).rects)
                        sb.append('[').append(q[0]).append(',').append(q[1]).append(' ')
                          .append(q[2]).append('x').append(q[3]).append(']');
                    sb.append(' ');
                }
                failures.add(sb.toString().trim());
            }
        }
    }

    /**
     * Can this set of rooms be produced by recursive splitting?
     *
     * Look for a full-span horizontal or vertical cut that no room straddles.
     * If one exists, recurse on both sides. One room left in a region is a
     * leaf. No cut and more than one room means the arrangement pinwheels and
     * a BSP could not have built it.
     */
    static boolean splittable(List<int[]> rects, int x0, int y0, int x1, int y1) {
        List<int[]> in = new ArrayList<>();
        for (int[] r : rects)
            if (r[0] >= x0 && r[1] >= y0 && r[2] <= x1 && r[3] <= y1) in.add(r);
        if (in.isEmpty()) return true;

        // A leaf is a region holding rects of ONE room, however many. An
        // L-shaped room is two or three rects and is still a leaf.
        int room = in.get(0)[4];
        boolean single = true;
        for (int[] r : in) if (r[4] != room) { single = false; break; }
        if (single) return true;

        for (int cut = x0 + 1; cut < x1; cut++) {
            boolean straddles = false;
            for (int[] r : in) if (r[0] < cut && r[2] > cut) { straddles = true; break; }
            if (straddles) continue;
            boolean left = false, right = false;
            for (int[] r : in) { if (r[2] <= cut) left = true; else right = true; }
            if (left && right)
                return splittable(in, x0, y0, cut, y1) && splittable(in, cut, y0, x1, y1);
        }
        for (int cut = y0 + 1; cut < y1; cut++) {
            boolean straddles = false;
            for (int[] r : in) if (r[1] < cut && r[3] > cut) { straddles = true; break; }
            if (straddles) continue;
            boolean up = false, down = false;
            for (int[] r : in) { if (r[3] <= cut) up = true; else down = true; }
            if (up && down)
                return splittable(in, x0, y0, x1, cut) && splittable(in, x0, cut, x1, y1);
        }
        return false;
    }

    static double area(int[] b) { return (double) (b[2] - b[0]) * (b[3] - b[1]); }

    static boolean touching(int[] a, int[] b) {
        return a[0] - 1 <= b[2] && b[0] - 1 <= a[2]
            && a[1] - 1 <= b[3] && b[1] - 1 <= a[3];
    }

    static int find(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }

    static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[ra] = rb;
    }

    static void line(String title) {
        System.out.println("\n" + "=".repeat(64));
        System.out.println(title);
        System.out.println("=".repeat(64));
    }
}

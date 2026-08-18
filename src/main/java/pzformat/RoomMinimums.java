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
 * How small will vanilla build a room, and at what house size does each room
 * start appearing? Read-only; writes nothing.
 *
 * WHY. The generator has been choosing a room list from footprint area and
 * then forcing it to fit, which is backwards and is why a 30x6 building got a
 * 30x2 livingroom. The owner's rule inverts it:
 *
 *   * livingroom, kitchen, bathroom and one bedroom are REQUIRED
 *   * each has a MINIMUM SIZE and the core two do not shrink with the house
 *   * further bedrooms are added until the space runs out
 *   * closets, laundry and storage are the LEFTOVER, not peers with targets
 *   * if a room falls just short of its minimum, take the slack from a
 *     neighbour rather than shipping it undersized
 *
 * A small house is therefore mostly livingroom and kitchen — 50% core rather
 * than 25% — because those two hold their size while the bedroom count falls
 * to one. The percentage was a symptom; the minimum is the rule.
 *
 * §34 gave MEDIANS — livingroom 42, kitchen 24, bedroom 16, bathroom 6. A
 * median is not a minimum. This measures:
 *
 *   1. the p5 and p10 of each room's area and short side — the smallest
 *      vanilla is willing to build
 *   2. the smallest HOUSE that still contains each room type, which is the
 *      threshold below which the generator should stop adding it
 *   3. how the core share of floor area varies with house size, to check the
 *      "50% in a small house, 25% in a large one" prediction
 *
 * Usage:
 *   java -cp out pzformat.RoomMinimums MAP_DIR CELL [CELL...]
 */
public final class RoomMinimums {

    static final Set<String> NOT_INTERIOR = Set.of("emptyoutside");
    static final Set<String> CORE = Set.of("livingroom", "kitchen");

    /** area, short side, per type. */
    static final Map<String, List<Integer>> areas = new TreeMap<>();
    static final Map<String, List<Integer>> shorts = new TreeMap<>();

    /** Smallest house containing this type, and how many houses had it. */
    static final Map<String, int[]> smallestHouse = new TreeMap<>();

    /** Core share of floor area, bucketed by house size. */
    static final Map<String, List<Double>> coreShare = new TreeMap<>();
    static final Map<String, List<Integer>> bedroomCount = new TreeMap<>();

    static long houses = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: RoomMinimums MAP_DIR CELL [CELL...]");
            return;
        }
        Path mapDir = Path.of(args[0]);

        int cells = 0;
        for (int i = 1; i < args.length; i++) {
            try {
                scan(LotHeader.read(mapDir.resolve(args[i] + ".lotheader")));
            } catch (Exception e) {
                continue;
            }
            if (++cells % 500 == 0) System.out.println("  ... " + cells + " cells");
        }

        System.out.println("\ncells: " + cells + "   houses: " + houses + "\n");
        if (houses == 0) return;

        line("HOW SMALL WILL VANILLA BUILD A ROOM?");
        System.out.println("  a median is not a minimum — these are the small tail");
        System.out.printf("  %-14s %7s %8s %8s %8s %9s %9s%n",
                "type", "n", "p5 area", "p10", "median", "p5 short", "p10 short");
        for (String t : areas.keySet()) {
            List<Integer> a = areas.get(t);
            if (a.size() < 100) continue;
            List<Integer> s = shorts.get(t);
            Collections.sort(a); Collections.sort(s);
            System.out.printf("  %-14s %7d %8d %8d %8d %9d %9d%n", t, a.size(),
                    pct(a, 5), pct(a, 10), pct(a, 50), pct(s, 5), pct(s, 10));
        }

        line("THE SMALLEST HOUSE THAT STILL HAS EACH ROOM");
        System.out.println("  below this, the generator should stop adding the type");
        System.out.printf("  %-14s %10s %10s%n", "type", "smallest", "in n houses");
        smallestHouse.entrySet().stream()
                .filter(e -> e.getValue()[1] >= 50)
                .sorted((a, b) -> Integer.compare(a.getValue()[0], b.getValue()[0]))
                .forEach(e -> System.out.printf("  %-14s %10d %10d%n",
                        e.getKey(), e.getValue()[0], e.getValue()[1]));

        line("CORE SHARE — is a small house mostly livingroom and kitchen?");
        System.out.println("  predicted: ~50% in a small house, ~25% in a large one");
        System.out.printf("  %-14s %8s %10s %10s %10s%n",
                "house size", "n", "core %", "bedrooms", "median");
        for (String b : coreShare.keySet()) {
            List<Double> c = new ArrayList<>(coreShare.get(b));
            List<Integer> bd = new ArrayList<>(bedroomCount.get(b));
            if (c.size() < 20) continue;
            Collections.sort(c); Collections.sort(bd);
            System.out.printf("  %-14s %8d %9.1f%% %10.1f %10d%n", b, c.size(),
                    100 * c.get(c.size() / 2),
                    bd.stream().mapToInt(Integer::intValue).average().orElse(0),
                    bd.get(bd.size() / 2));
        }
    }

    static void scan(LotHeader h) {
        int n = h.rooms.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        int[][] box = new int[n][];
        int[] area = new int[n];
        boolean[] use = new boolean[n];

        for (int i = 0; i < n; i++) {
            LotHeader.Room r = h.rooms.get(i);
            if (r.rects == null || r.rects.isEmpty()) continue;
            if (r.name != null && NOT_INTERIOR.contains(r.name)) continue;
            if (r.floor != 0) continue;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            int a = 0;
            for (int[] q : r.rects) {
                minX = Math.min(minX, q[0]); minY = Math.min(minY, q[1]);
                maxX = Math.max(maxX, q[0] + q[2]); maxY = Math.max(maxY, q[1] + q[3]);
                a += q[2] * q[3];
            }
            box[i] = new int[]{minX, minY, maxX, maxY};
            area[i] = a;
            use[i] = true;
        }

        for (int i = 0; i < n; i++) {
            if (!use[i]) continue;
            for (int j = i + 1; j < n; j++)
                if (use[j] && touching(box[i], box[j])) union(parent, i, j);
        }

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++)
            if (use[i]) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);

        for (List<Integer> g : groups.values()) {
            boolean isHouse = false;
            for (int i : g) if (name(h, i).equals("livingroom")) isHouse = true;
            if (!isHouse || g.size() < 2) continue;
            houses++;

            int total = 0, core = 0, beds = 0;
            for (int i : g) {
                String t = name(h, i);
                total += area[i];
                if (CORE.contains(t)) core += area[i];
                if (t.equals("bedroom") || t.equals("kidsbedroom")) beds++;

                areas.computeIfAbsent(t, k -> new ArrayList<>()).add(area[i]);
                shorts.computeIfAbsent(t, k -> new ArrayList<>())
                      .add(Math.min(box[i][2] - box[i][0], box[i][3] - box[i][1]));
            }
            if (total <= 0) continue;

            for (int i : g) {
                String t = name(h, i);
                int[] rec = smallestHouse.computeIfAbsent(t, k -> new int[]{Integer.MAX_VALUE, 0});
                rec[0] = Math.min(rec[0], total);
                rec[1]++;
            }

            String bucket = sizeBucket(total);
            coreShare.computeIfAbsent(bucket, k -> new ArrayList<>()).add(core / (double) total);
            bedroomCount.computeIfAbsent(bucket, k -> new ArrayList<>()).add(beds);
        }
    }

    static String sizeBucket(int a) {
        if (a <= 50) return "A   <=50";
        if (a <= 90) return "B  51-90";
        if (a <= 140) return "C 91-140";
        if (a <= 220) return "D 141-220";
        if (a <= 350) return "E 221-350";
        return "F   >350";
    }

    static int pct(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.round((p / 100.0) * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    static String name(LotHeader h, int i) {
        String s = h.rooms.get(i).name;
        return s == null ? "(none)" : s;
    }

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
        System.out.println("\n" + "=".repeat(70));
        System.out.println(title);
        System.out.println("=".repeat(70));
    }
}

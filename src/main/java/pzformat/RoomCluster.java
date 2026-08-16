package pzformat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * How many rooms does a vanilla building have, and which types go together?
 * Read-only; writes nothing.
 *
 * WHY. STATE §30: we write one open box per building where vanilla writes a
 * cluster — bathroom, bedroom, livingroom, kitchen, closet. That is what makes
 * a building read as a building, and it is what "drop a real building of the
 * right type on the footprint" actually requires. The type vocabulary is
 * already measured; the RECIPE is not.
 *
 * THE PROBLEM. `LotHeader` rooms carry no building id. A room knows its name,
 * its floor and its rects, and nothing about what it belongs to.
 *
 * THE METHOD, and its limits. Union rooms whose rects touch or sit one square
 * apart — one square because a wall lives on the shared edge, so neighbouring
 * rooms are adjacent rather than overlapping. Union across floors too, since a
 * two-storey house is one building.
 *
 * This is approximate and the ways it is wrong are worth stating:
 *
 *   - A terrace or strip mall merges into one "building". Real, and it will
 *     inflate the tail of the rooms-per-building histogram.
 *   - A building straddling a cell boundary is counted as two, because
 *     lotheaders are per-cell. Also inflates small clusters.
 *   - `emptyoutside` is a YARD, not an interior (§26 Q4, §30). Yards touch
 *     several buildings and would bridge them into one blob, so they are
 *     excluded from clustering and counted separately.
 *
 * If the recipe turns out strongly patterned despite all that, it is good
 * enough to build from and `objects.lua` (A7) can come later on its own
 * merits. If it looks like noise, the noise may be these limits rather than
 * vanilla, and A7 becomes necessary rather than optional.
 *
 * Usage:
 *   java -cp out pzformat.RoomCluster MAP_DIR CELL [CELL...]
 */
public final class RoomCluster {

    /** Rooms one square apart are neighbours: a wall sits on the shared edge. */
    static final int GAP = 1;

    /** Yards, not interiors. They would bridge separate buildings. */
    static final Set<String> NOT_INTERIOR = Set.of("emptyoutside");

    static final Map<Integer, Long> roomsPer = new TreeMap<>();
    static final Map<Integer, Long> floorsPer = new TreeMap<>();
    static final Map<String, Long> combos = new HashMap<>();
    static final Map<String, Long> typeInBuildings = new TreeMap<>();
    static final Map<String, long[]> typeCountByName = new TreeMap<>();
    static final List<int[]> sizes = new ArrayList<>();
    static long buildings = 0, roomsUsed = 0, yards = 0;

    /** Type set by footprint-area bucket: "bucket|types" -> count. */
    static final Map<String, Long> bySize = new TreeMap<>();
    static final Map<String, Long> sizeTotals = new TreeMap<>();

    /** Every building placed this cell, for the parcel and distance passes. */
    static final class Placed {
        final int cx, cy, area, rooms;
        final String primary;
        final TreeSet<String> types;
        Placed(int cx, int cy, int area, int rooms, String primary, TreeSet<String> types) {
            this.cx = cx; this.cy = cy; this.area = area;
            this.rooms = rooms; this.primary = primary; this.types = types;
        }
    }

    /** Distance from each single-room outbuilding to its nearest dwelling. */
    static final List<Integer> hostDist = new ArrayList<>();
    static final Map<String, long[]> hostDistByType = new TreeMap<>();

    /** How many outbuildings sit within HOST_RANGE of a dwelling. */
    static final Map<String, long[]> parcelCompanions = new TreeMap<>();
    static long dwellings = 0, dwellingsWithCompanion = 0;

    /** Beyond this a building is its own parcel, not an outbuilding of one. */
    static final int HOST_RANGE = 40;

    /** A building with these is somewhere people live — a host for outbuildings. */
    static final Set<String> DWELLING = Set.of("bedroom", "kidsbedroom", "livingroom");

    static String areaBucket(int a) {
        if (a <= 24) return "A  <=24";
        if (a <= 60) return "B  25-60";
        if (a <= 120) return "C  61-120";
        if (a <= 240) return "D 121-240";
        if (a <= 480) return "E 241-480";
        return "F   >480";
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: RoomCluster MAP_DIR CELL [CELL...]");
            return;
        }
        Path mapDir = Path.of(args[0]);

        int cells = 0;
        for (int i = 1; i < args.length; i++) {
            try {
                cluster(LotHeader.read(mapDir.resolve(args[i] + ".lotheader")));
            } catch (Exception e) {
                continue;
            }
            if (++cells % 500 == 0) System.out.println("  ... " + cells + " cells");
        }

        System.out.println("cells: " + cells + "   buildings: " + buildings
                + "   interior rooms: " + roomsUsed + "   yards skipped: " + yards + "\n");
        if (buildings == 0) return;

        line("ROOMS PER BUILDING");
        long shown = 0;
        for (Map.Entry<Integer, Long> e : roomsPer.entrySet()) {
            if (e.getKey() > 16) continue;
            shown += e.getValue();
            System.out.printf("  %2d room%s %8d  (%5.1f%%)  %s%n",
                    e.getKey(), e.getKey() == 1 ? " " : "s", e.getValue(),
                    100.0 * e.getValue() / buildings,
                    "#".repeat((int) Math.min(40, 40 * e.getValue() / Math.max(1, buildings))));
        }
        if (buildings > shown)
            System.out.printf("  >16 rooms %8d  (%5.1f%%)%n", buildings - shown,
                    100.0 * (buildings - shown) / buildings);

        line("FLOORS PER BUILDING");
        floorsPer.forEach((k, v) -> System.out.printf("  %d floor%s %8d  (%5.1f%%)%n",
                k, k == 1 ? " " : "s", v, 100.0 * v / buildings));

        line("WHICH TYPES APPEAR, and how often per building when present");
        System.out.println("  type                  in % of buildings   mean count when present");
        typeInBuildings.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(20)
                .forEach(e -> {
                    long[] c = typeCountByName.get(e.getKey());
                    System.out.printf("  %-22s %8.1f%%  %18.2f%n", e.getKey(),
                            100.0 * e.getValue() / buildings,
                            c == null || c[1] == 0 ? 0 : (double) c[0] / c[1]);
                });

        line("MOST COMMON TYPE SETS — the recipe, if there is one");
        combos.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(e -> System.out.printf("  %6d  (%4.1f%%)  %s%n", e.getValue(),
                        100.0 * e.getValue() / buildings, e.getKey()));

        line("WHAT A BUILDING OF A GIVEN SIZE NORMALLY IS");
        System.out.println("  sampling the global mix unconditioned gives 80-square garages");
        for (Map.Entry<String, Long> t : sizeTotals.entrySet()) {
            System.out.printf("%n  %s squares — %d buildings%n", t.getKey(), t.getValue());
            bySize.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(t.getKey() + "|"))
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .forEach(e -> System.out.printf("     %5d (%4.1f%%)  %s%n", e.getValue(),
                            100.0 * e.getValue() / t.getValue(),
                            e.getKey().substring(e.getKey().indexOf('|') + 1)));
        }

        line("HOW FAR AN OUTBUILDING SITS FROM ITS DWELLING, in squares");
        if (!hostDist.isEmpty()) {
            Collections.sort(hostDist);
            System.out.printf("  n=%d   p10 %d   median %d   p90 %d   max %d%n",
                    hostDist.size(), hostDist.get(hostDist.size() / 10),
                    hostDist.get(hostDist.size() / 2),
                    hostDist.get(hostDist.size() * 9 / 10),
                    hostDist.get(hostDist.size() - 1));
            System.out.println("  Chebyshev distance between centres. Per cell, so a host");
            System.out.println("  across a boundary is missed — biases SHORT, never long.");
            System.out.println("\n  type                    n   mean    within " + HOST_RANGE);
            hostDistByType.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                    .limit(12)
                    .forEach(e -> {
                        long[] c = e.getValue();
                        System.out.printf("  %-20s %5d %6.1f %9.1f%%%n", e.getKey(), c[1],
                                (double) c[0] / c[1], 100.0 * c[2] / c[1]);
                    });
        }

        line("WHAT ACCOMPANIES A DWELLING — what our footprints may be missing");
        System.out.printf("  dwellings: %d, of which %d (%.1f%%) have an outbuilding within %d%n",
                dwellings, dwellingsWithCompanion,
                dwellings == 0 ? 0 : 100.0 * dwellingsWithCompanion / dwellings, HOST_RANGE);
        parcelCompanions.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(12)
                .forEach(e -> System.out.printf("  %-20s %6d  (%4.1f%% of dwellings)%n",
                        e.getKey(), e.getValue()[0],
                        dwellings == 0 ? 0 : 100.0 * e.getValue()[0] / dwellings));

        Collections.sort(sizes, (a, b) -> Integer.compare(a[0] * a[1], b[0] * b[1]));
        line("BUILDING FOOTPRINT, in squares");
        if (!sizes.isEmpty()) {
            int[] p10 = sizes.get(sizes.size() / 10);
            int[] p50 = sizes.get(sizes.size() / 2);
            int[] p90 = sizes.get(sizes.size() * 9 / 10);
            System.out.printf("  p10 %2dx%-2d = %5d     median %2dx%-2d = %5d     p90 %2dx%-2d = %5d%n",
                    p10[0], p10[1], p10[0] * p10[1], p50[0], p50[1], p50[0] * p50[1],
                    p90[0], p90[1], p90[0] * p90[1]);
            System.out.println("  our GIS footprints are 76-341 m2, one square per metre");
        }
    }

    static final List<Placed> placed = new ArrayList<>();

    static void cluster(LotHeader h) {
        placed.clear();
        int n = h.rooms.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        int[][] box = new int[n][];      // minX, minY, maxX, maxY per room
        boolean[] use = new boolean[n];
        for (int i = 0; i < n; i++) {
            LotHeader.Room r = h.rooms.get(i);
            if (r.rects == null || r.rects.isEmpty()) continue;
            if (r.name != null && NOT_INTERIOR.contains(r.name)) { yards++; continue; }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (int[] q : r.rects) {
                minX = Math.min(minX, q[0]);
                minY = Math.min(minY, q[1]);
                maxX = Math.max(maxX, q[0] + q[2]);
                maxY = Math.max(maxY, q[1] + q[3]);
            }
            box[i] = new int[]{minX, minY, maxX, maxY};
            use[i] = true;
        }

        for (int i = 0; i < n; i++) {
            if (!use[i]) continue;
            for (int j = i + 1; j < n; j++) {
                if (!use[j]) continue;
                // A two-storey house is one building, so allow one floor apart.
                if (Math.abs(h.rooms.get(i).floor - h.rooms.get(j).floor) > 1) continue;
                if (touching(box[i], box[j])) union(parent, i, j);
            }
        }

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++)
            if (use[i]) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);

        for (List<Integer> g : groups.values()) {
            buildings++;
            roomsUsed += g.size();
            roomsPer.merge(g.size(), 1L, Long::sum);

            TreeSet<String> types = new TreeSet<>();
            TreeSet<Integer> floors = new TreeSet<>();
            Map<String, Integer> perBuilding = new HashMap<>();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

            for (int i : g) {
                LotHeader.Room r = h.rooms.get(i);
                String nm = r.name == null ? "(none)" : r.name;
                types.add(nm);
                perBuilding.merge(nm, 1, Integer::sum);
                floors.add(r.floor);
                minX = Math.min(minX, box[i][0]); minY = Math.min(minY, box[i][1]);
                maxX = Math.max(maxX, box[i][2]); maxY = Math.max(maxY, box[i][3]);
            }

            floorsPer.merge(floors.size(), 1L, Long::sum);
            int bw = maxX - minX, bh = maxY - minY;
            sizes.add(new int[]{bw, bh});
            combos.merge(String.join(" + ", types), 1L, Long::sum);

            // Size-conditioned: what a building of this footprint normally is.
            String bucket = areaBucket(bw * bh);
            sizeTotals.merge(bucket, 1L, Long::sum);
            bySize.merge(bucket + "|" + String.join(" + ", types), 1L, Long::sum);

            // The primary type: the most numerous room, ties broken by name so
            // the label is stable across runs.
            String primary = "(none)";
            int bestN = -1;
            for (Map.Entry<String, Integer> e : perBuilding.entrySet())
                if (e.getValue() > bestN
                        || (e.getValue() == bestN && e.getKey().compareTo(primary) < 0)) {
                    bestN = e.getValue(); primary = e.getKey();
                }
            placed.add(new Placed((minX + maxX) / 2, (minY + maxY) / 2,
                    bw * bh, g.size(), primary, types));
            for (Map.Entry<String, Integer> e : perBuilding.entrySet()) {
                typeInBuildings.merge(e.getKey(), 1L, Long::sum);
                long[] c = typeCountByName.computeIfAbsent(e.getKey(), k -> new long[2]);
                c[0] += e.getValue();
                c[1]++;
            }
        }

        parcels();
    }

    /**
     * Which outbuildings sit near a dwelling, and how near.
     *
     * Run per cell, so a host across a cell boundary is missed — the same
     * limit that splits large buildings. It biases the distance distribution
     * SHORT, never long, because a missed host is simply not counted.
     */
    static void parcels() {
        for (Placed p : placed) {
            boolean dwelling = false;
            for (String t : p.types) if (DWELLING.contains(t)) dwelling = true;
            if (dwelling) dwellings++;
        }

        for (Placed p : placed) {
            // An outbuilding: one room, and not somewhere people live.
            if (p.rooms != 1) continue;
            boolean dwelling = false;
            for (String t : p.types) if (DWELLING.contains(t)) dwelling = true;
            if (dwelling) continue;

            int best = Integer.MAX_VALUE;
            for (Placed q : placed) {
                if (q == p || q.rooms < 2) continue;
                boolean host = false;
                for (String t : q.types) if (DWELLING.contains(t)) host = true;
                if (!host) continue;
                int d = Math.max(Math.abs(p.cx - q.cx), Math.abs(p.cy - q.cy));
                if (d < best) best = d;
            }
            if (best == Integer.MAX_VALUE) continue;

            hostDist.add(best);
            long[] c = hostDistByType.computeIfAbsent(p.primary, k -> new long[3]);
            c[0] += best; c[1]++;
            if (best <= HOST_RANGE) c[2]++;
        }

        // Which outbuilding types accompany a dwelling within HOST_RANGE.
        for (Placed q : placed) {
            boolean host = false;
            for (String t : q.types) if (DWELLING.contains(t)) host = true;
            if (!host || q.rooms < 2) continue;
            boolean any = false;
            TreeSet<String> seen = new TreeSet<>();
            for (Placed p : placed) {
                if (p == q || p.rooms != 1) continue;
                boolean pd = false;
                for (String t : p.types) if (DWELLING.contains(t)) pd = true;
                if (pd) continue;
                int d = Math.max(Math.abs(p.cx - q.cx), Math.abs(p.cy - q.cy));
                if (d <= HOST_RANGE) { seen.add(p.primary); any = true; }
            }
            if (any) dwellingsWithCompanion++;
            for (String s : seen)
                parcelCompanions.computeIfAbsent(s, k -> new long[1])[0]++;
        }
    }

    /** True when the two boxes touch or sit at most GAP squares apart. */
    static boolean touching(int[] a, int[] b) {
        return a[0] - GAP <= b[2] && b[0] - GAP <= a[2]
            && a[1] - GAP <= b[3] && b[1] - GAP <= a[3];
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

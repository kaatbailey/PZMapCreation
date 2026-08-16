package pzformat;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * When does vanilla give a building a hallway, and when does it not?
 * Read-only; writes nothing.
 *
 * WHY THIS, RATHER THAN A RULE FROM FIRST PRINCIPLES. `hall` appears in 17.4%
 * of Muldraugh buildings, so 82.6% connect their rooms door-to-door with no
 * corridor at all. A subdivider that always cuts a hallway would be wrong five
 * times in six; one that never does would strand rooms. The question is what
 * vanilla's authors were actually deciding on.
 *
 * The honest way to ask is comparative: split every building into hall-having
 * and hall-less, and measure five candidate discriminators at once so the data
 * picks rather than the author picking and then confirming.
 *
 *   room count        not the deciding factor by argument, but if hall-less
 *                     buildings are all <=4 rooms it is the answer regardless
 *   footprint area    and area per room
 *   aspect ratio      a long thin building may need a spine where a square
 *                     one does not
 *   private fraction  share of rooms that are bedroom / bathroom / closet,
 *                     which is what creates routing problems
 *   PRIVATE ROUTING   the hypothesis: without a hall, is some room reachable
 *                     from the entrance ONLY by passing through a private
 *                     room? Walking through a bedroom to reach another bedroom
 *                     is what a corridor exists to prevent.
 *
 * Adjacency comes from shared edges between room rects, not from doors. Two
 * rooms sharing a wall can have a door cut through it; the layout question is
 * which rooms COULD connect, and PZ authors put doors where the plan needs
 * them.
 *
 * IF NOTHING SEPARATES THEM, that is the finding — hallways are a stylistic
 * choice per building and there is no rule to imitate. Worth knowing before
 * spending a chunk imitating one.
 *
 * Reads lotpacks, so it is slower than the lotheader passes. A few hundred
 * cells is plenty for a first look.
 *
 * Usage:
 *   java -cp out pzformat.HallRule MEDIA_DIR MAP_DIR CELL [CELL...]
 */
public final class HallRule {

    /** Rooms you should not have to walk through to reach somewhere else. */
    static final Set<String> PRIVATE = Set.of(
            "bedroom", "kidsbedroom", "bathroom", "closet", "toilet");

    /** Circulation space. Its presence is what we are trying to explain. */
    static final Set<String> CIRCULATION = Set.of("hall", "hallway", "lobby", "corridor");

    static final Set<String> NOT_INTERIOR = Set.of("emptyoutside");

    /** Two accumulators, one per group, so every stat is a direct comparison. */
    static final class Stats {
        long n;
        final List<Integer> rooms = new ArrayList<>();
        final List<Integer> area = new ArrayList<>();
        final List<Double> aspect = new ArrayList<>();
        final List<Double> privateFrac = new ArrayList<>();
        long wouldRoutePrivate, disconnected;
    }

    static final Stats withHall = new Stats();
    static final Stats without = new Stats();
    static final Map<String, long[]> byRoomCount = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: HallRule MEDIA_DIR MAP_DIR CELL [CELL...]");
            return;
        }
        TileIndex ti = TileIndex.load(Path.of(args[0]));
        Path mapDir = Path.of(args[1]);

        int cells = 0;
        for (int i = 2; i < args.length; i++) {
            try {
                scan(ti, mapDir, args[i]);
            } catch (Exception e) {
                continue;
            }
            if (++cells % 100 == 0) System.out.println("  ... " + cells + " cells");
        }

        System.out.println("\ncells: " + cells
                + "   with a hall: " + withHall.n + "   without: " + without.n + "\n");
        if (withHall.n == 0 || without.n == 0) {
            System.out.println("one group is empty — nothing to compare");
            return;
        }

        line("THE FIVE CANDIDATE DISCRIMINATORS");
        System.out.printf("  %-22s %14s %14s%n", "", "with hall", "without");
        cmp("rooms, median", withHall.rooms, without.rooms);
        cmp("area, median", withHall.area, without.area);
        cmpD("aspect (long/short)", withHall.aspect, without.aspect);
        cmpD("private room fraction", withHall.privateFrac, without.privateFrac);

        System.out.printf("%n  %-22s %13.1f%% %13.1f%%%n", "would route private",
                100.0 * withHall.wouldRoutePrivate / withHall.n,
                100.0 * without.wouldRoutePrivate / without.n);
        System.out.printf("  %-22s %13.1f%% %13.1f%%%n", "adjacency disconnected",
                100.0 * withHall.disconnected / withHall.n,
                100.0 * without.disconnected / without.n);

        System.out.println("\n  A discriminator that separates shows a LARGE gap between the");
        System.out.println("  two columns. Similar columns mean it explains nothing.");

        line("HALL RATE BY ROOM COUNT — does size alone decide it?");
        System.out.printf("  %-8s %8s %8s %8s%n", "rooms", "total", "w/ hall", "rate");
        byRoomCount.forEach((k, v) -> {
            long tot = v[0] + v[1];
            if (tot < 20) return;
            System.out.printf("  %-8s %8d %8d %7.1f%%%n", k, tot, v[1], 100.0 * v[1] / tot);
        });
        System.out.println("  A clean threshold would show near-0% below it and near-100% above.");
    }

    static void cmp(String label, List<Integer> a, List<Integer> b) {
        Collections.sort(a); Collections.sort(b);
        System.out.printf("  %-22s %14d %14d%n", label,
                a.isEmpty() ? 0 : a.get(a.size() / 2), b.isEmpty() ? 0 : b.get(b.size() / 2));
    }

    static void cmpD(String label, List<Double> a, List<Double> b) {
        Collections.sort(a); Collections.sort(b);
        System.out.printf("  %-22s %14.2f %14.2f%n", label,
                a.isEmpty() ? 0 : a.get(a.size() / 2), b.isEmpty() ? 0 : b.get(b.size() / 2));
    }

    static void scan(TileIndex ti, Path mapDir, String cellName) throws Exception {
        Path lh = mapDir.resolve(cellName + ".lotheader");
        Path lp = mapDir.resolve("world_" + cellName + ".lotpack");
        LotHeader h = LotHeader.read(lh);
        CellData c = CellData.load(lp, lh);

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
            for (int[] q : r.rects) {
                minX = Math.min(minX, q[0]); minY = Math.min(minY, q[1]);
                maxX = Math.max(maxX, q[0] + q[2]); maxY = Math.max(maxY, q[1] + q[3]);
            }
            box[i] = new int[]{minX, minY, maxX, maxY};
            use[i] = true;
        }

        for (int i = 0; i < n; i++) {
            if (!use[i]) continue;
            for (int j = i + 1; j < n; j++) {
                if (!use[j]) continue;
                if (Math.abs(h.rooms.get(i).floor - h.rooms.get(j).floor) > 1) continue;
                if (touching(box[i], box[j])) union(parent, i, j);
            }
        }

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++)
            if (use[i]) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);

        for (List<Integer> g : groups.values()) analyse(h, g, box);
    }

    static void analyse(LotHeader h, List<Integer> g, int[][] box) {
        if (g.size() < 2) return;          // a one-room building cannot need a hall

        boolean hall = false;
        int privates = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i : g) {
            String nm = name(h, i);
            if (CIRCULATION.contains(nm)) hall = true;
            if (PRIVATE.contains(nm)) privates++;
            minX = Math.min(minX, box[i][0]); minY = Math.min(minY, box[i][1]);
            maxX = Math.max(maxX, box[i][2]); maxY = Math.max(maxY, box[i][3]);
        }

        int w = maxX - minX, ht = maxY - minY;
        Stats s = hall ? withHall : without;
        s.n++;
        s.rooms.add(g.size());
        s.area.add(w * ht);
        s.aspect.add(Math.max(w, ht) / (double) Math.max(1, Math.min(w, ht)));
        s.privateFrac.add(privates / (double) g.size());

        long[] rc = byRoomCount.computeIfAbsent(bucket(g.size()), k -> new long[2]);
        if (hall) rc[1]++; else rc[0]++;

        // The hypothesis. Remove circulation rooms, then ask whether every
        // remaining room is reachable from a non-private room without passing
        // THROUGH a private one. Start from every non-private room, walk only
        // into rooms that are themselves non-private, and see what is missed.
        List<Integer> rooms = new ArrayList<>();
        for (int i : g) if (!CIRCULATION.contains(name(h, i))) rooms.add(i);
        if (rooms.size() < 2) return;

        Set<Integer> reached = new HashSet<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i : rooms)
            if (!PRIVATE.contains(name(h, i))) { reached.add(i); q.add(i); }
        if (reached.isEmpty()) { s.wouldRoutePrivate++; return; }

        while (!q.isEmpty()) {
            int a = q.poll();
            for (int b : rooms) {
                if (reached.contains(b)) continue;
                if (!touching(box[a], box[b])) continue;
                reached.add(b);
                // Only continue THROUGH b if b is not private.
                if (!PRIVATE.contains(name(h, b))) q.add(b);
            }
        }
        if (reached.size() < rooms.size()) s.wouldRoutePrivate++;

        // Plain connectivity, ignoring privacy — a sanity check on adjacency.
        Set<Integer> all = new HashSet<>();
        ArrayDeque<Integer> q2 = new ArrayDeque<>();
        all.add(rooms.get(0)); q2.add(rooms.get(0));
        while (!q2.isEmpty()) {
            int a = q2.poll();
            for (int b : rooms)
                if (!all.contains(b) && touching(box[a], box[b])) { all.add(b); q2.add(b); }
        }
        if (all.size() < rooms.size()) s.disconnected++;
    }

    static String name(LotHeader h, int i) {
        String s = h.rooms.get(i).name;
        return s == null ? "(none)" : s;
    }

    static String bucket(int rooms) {
        if (rooms <= 3) return "2-3";
        if (rooms <= 5) return "4-5";
        if (rooms <= 7) return "6-7";
        if (rooms <= 10) return "8-10";
        if (rooms <= 15) return "11-15";
        return "16+";
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
        System.out.println("\n" + "=".repeat(64));
        System.out.println(title);
        System.out.println("=".repeat(64));
    }
}

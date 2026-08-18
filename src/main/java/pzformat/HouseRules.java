package pzformat;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Does vanilla obey the house grammar? Read-only; writes nothing.
 *
 * The rules, stated by the owner 2026-08-14 as architecture rather than as a
 * distribution. Each is testable against Muldraugh, and the point of testing
 * them is that a rule confirmed at scale can be built on, while one that fails
 * tells us what vanilla does instead.
 *
 *   R1  The LIVINGROOM is the front of the house — the side facing the road.
 *   R2  The KITCHEN is the back, opposite the livingroom.
 *   R3  The exterior door enters the livingroom or the kitchen.
 *       **You never walk from outside directly into a bedroom.**
 *   R4  A bathroom may attach to the livingroom/kitchen core, and the master
 *       bedroom may have its own. Smaller bedrooms share one.
 *   R5  The livingroom and kitchen may or may not have a divider wall.
 *
 * R1 and R2 are measured against the nearest ROAD tile, which is how "front"
 * is defined — vanilla's own streets, not an assumption about compass
 * direction.
 *
 * R3 is the sharpest test: find every exterior door on a building's outer wall
 * and record which room it opens into. If bedrooms turn up at more than a
 * noise-floor rate, the rule is softer than stated.
 *
 * R5 is measured as: what fraction of livingroom/kitchen boundaries carry a
 * wall along their whole shared edge, versus being open.
 *
 * Reads lotpacks for the doors and the road raster, so it is slower than the
 * lotheader passes. A few hundred cells is plenty.
 *
 * Usage:
 *   java -cp out pzformat.HouseRules MEDIA_DIR MAP_DIR CELL [CELL...]
 */
public final class HouseRules {

    static final Set<String> NOT_INTERIOR = Set.of("emptyoutside");
    static final Set<String> PRIVATE = Set.of(
            "bedroom", "kidsbedroom", "bathroom", "closet");

    /** How far to look for a road before giving up on "front". */
    static final int ROAD_RANGE = 60;

    static long houses = 0;
    static long r1Pass = 0, r1Fail = 0, r1NoRoad = 0;
    static long r2Pass = 0, r2Fail = 0;
    static final Map<String, Long> doorInto = new TreeMap<>();
    static long doorsFound = 0;
    static long lkAdjacent = 0, lkWalled = 0, lkOpen = 0, lkPartial = 0;
    static final Map<String, Long> bathNeighbour = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: HouseRules MEDIA_DIR MAP_DIR CELL [CELL...]");
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

        System.out.println("\ncells: " + cells + "   houses: " + houses + "\n");
        if (houses == 0) return;

        line("R1 — is the LIVINGROOM the side facing the road?");
        long r1n = r1Pass + r1Fail;
        System.out.printf("  livingroom nearer the road : %d  (%.1f%%)%n",
                r1Pass, r1n == 0 ? 0 : 100.0 * r1Pass / r1n);
        System.out.printf("  kitchen nearer             : %d  (%.1f%%)%n",
                r1Fail, r1n == 0 ? 0 : 100.0 * r1Fail / r1n);
        System.out.printf("  no road within %d squares   : %d%n", ROAD_RANGE, r1NoRoad);

        line("R2 — is the KITCHEN opposite the livingroom?");
        long r2n = r2Pass + r2Fail;
        System.out.printf("  on the far side            : %d  (%.1f%%)%n",
                r2Pass, r2n == 0 ? 0 : 100.0 * r2Pass / r2n);
        System.out.printf("  on the same side or beside : %d  (%.1f%%)%n",
                r2Fail, r2n == 0 ? 0 : 100.0 * r2Fail / r2n);

        line("R3 — what room does an EXTERIOR DOOR open into?");
        System.out.println("  the sharpest test: bedrooms should be near zero");
        doorInto.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(14)
                .forEach(e -> System.out.printf("  %-20s %7d  (%5.1f%%)%n", e.getKey(),
                        e.getValue(), 100.0 * e.getValue() / Math.max(1, doorsFound)));
        System.out.println("  total exterior doors: " + doorsFound);

        line("R5 — is there a wall between livingroom and kitchen?");
        System.out.printf("  adjacent pairs   : %d%n", lkAdjacent);
        if (lkAdjacent > 0) {
            System.out.printf("  fully walled     : %d  (%.1f%%)%n",
                    lkWalled, 100.0 * lkWalled / lkAdjacent);
            System.out.printf("  partly open      : %d  (%.1f%%)%n",
                    lkPartial, 100.0 * lkPartial / lkAdjacent);
            System.out.printf("  fully open       : %d  (%.1f%%)%n",
                    lkOpen, 100.0 * lkOpen / lkAdjacent);
        }

        line("R4 — what does a BATHROOM touch?");
        System.out.println("  a bathroom off the core is a family bath; one touching");
        System.out.println("  a single bedroom is an ensuite");
        bathNeighbour.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> System.out.printf("  %-24s %7d%n", e.getKey(), e.getValue()));
    }

    static void scan(TileIndex ti, Path mapDir, String cellName) throws Exception {
        Path lh = mapDir.resolve(cellName + ".lotheader");
        Path lp = mapDir.resolve("world_" + cellName + ".lotpack");
        LotHeader h = LotHeader.read(lh);
        CellData c = CellData.load(lp, lh);
        int n = h.rooms.size();

        // Road squares, for "front".
        boolean[][] road = new boolean[c.cellSize][c.cellSize];
        for (int x = 0; x < c.cellSize; x++)
            for (int y = 0; y < c.cellSize; y++) {
                String[] names = c.tileNamesAt(x, y, 0);
                if (names == null) continue;
                for (String nm : names) {
                    TileDefs.Tile t = ti.get(nm);
                    if (t == null) continue;
                    String m = t.props.get("FloorMaterial");
                    if (m != null && m.startsWith("Road")) { road[x][y] = true; break; }
                }
            }

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        int[][] box = new int[n][];
        boolean[] use = new boolean[n];

        for (int i = 0; i < n; i++) {
            LotHeader.Room r = h.rooms.get(i);
            if (r.rects == null || r.rects.isEmpty()) continue;
            if (r.name != null && NOT_INTERIOR.contains(r.name)) continue;
            if (r.floor != 0) continue;              // ground floor only
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
            for (int j = i + 1; j < n; j++)
                if (use[j] && touching(box[i], box[j])) union(parent, i, j);
        }

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++)
            if (use[i]) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);

        for (List<Integer> g : groups.values()) analyse(ti, c, h, g, box, road);
    }

    static void analyse(TileIndex ti, CellData c, LotHeader h, List<Integer> g,
                        int[][] box, boolean[][] road) {
        int living = -1, kitchen = -1;
        for (int i : g) {
            String nm = name(h, i);
            if (nm.equals("livingroom") && living < 0) living = i;
            if (nm.equals("kitchen") && kitchen < 0) kitchen = i;
        }
        if (living < 0) return;                      // not a house
        houses++;

        // R1 and R2, against the nearest road square to the building.
        int bMinX = Integer.MAX_VALUE, bMinY = Integer.MAX_VALUE;
        int bMaxX = Integer.MIN_VALUE, bMaxY = Integer.MIN_VALUE;
        for (int i : g) {
            bMinX = Math.min(bMinX, box[i][0]); bMinY = Math.min(bMinY, box[i][1]);
            bMaxX = Math.max(bMaxX, box[i][2]); bMaxY = Math.max(bMaxY, box[i][3]);
        }
        int[] nearest = nearestRoad(road, (bMinX + bMaxX) / 2, (bMinY + bMaxY) / 2);

        if (nearest == null) {
            r1NoRoad++;
        } else if (kitchen >= 0) {
            double dl = dist(box[living], nearest);
            double dk = dist(box[kitchen], nearest);
            if (dl <= dk) r1Pass++; else r1Fail++;

            // R2: is the kitchen on the far side? Project both centres onto the
            // building-to-road axis; the kitchen should sit further along it.
            double ax = nearest[0] - (bMinX + bMaxX) / 2.0;
            double ay = nearest[1] - (bMinY + bMaxY) / 2.0;
            double len = Math.hypot(ax, ay);
            if (len > 0.01) {
                ax /= len; ay /= len;
                double pl = proj(box[living], bMinX, bMinY, bMaxX, bMaxY, ax, ay);
                double pk = proj(box[kitchen], bMinX, bMinY, bMaxX, bMaxY, ax, ay);
                if (pl > pk) r2Pass++; else r2Fail++;
            }
        }

        // R3: exterior doors. A door on a square whose outward neighbour is
        // OUTSIDE every room of this building is an exterior door.
        for (int i : g) {
            for (int[] q : h.rooms.get(i).rects)
                for (int x = q[0]; x < q[0] + q[2]; x++)
                    for (int y = q[1]; y < q[1] + q[3]; y++) {
                        String[] names = c.tileNamesAt(x, y, 0);
                        if (names == null) continue;
                        for (String nm : names) {
                            TileDefs.Tile t = ti.get(nm);
                            if (t == null) continue;
                            boolean dn = t.props.containsKey("DoorWallN");
                            boolean dw = t.props.containsKey("DoorWallW");
                            if (!dn && !dw) continue;
                            int ox = dw ? x - 1 : x, oy = dn ? y - 1 : y;
                            if (inAnyRoom(g, box, ox, oy)) continue;   // interior door
                            doorsFound++;
                            doorInto.merge(name(h, i), 1L, Long::sum);
                        }
                    }
        }

        // R5: the livingroom/kitchen boundary.
        if (kitchen >= 0 && touching(box[living], box[kitchen])) {
            lkAdjacent++;
            int shared = 0, walled = 0;
            for (int[] q : h.rooms.get(living).rects)
                for (int x = q[0]; x < q[0] + q[2]; x++)
                    for (int y = q[1]; y < q[1] + q[3]; y++) {
                        for (int d = 0; d < 4; d++) {
                            int nx = x + DX[d], ny = y + DY[d];
                            if (!inRoom(h, kitchen, nx, ny)) continue;
                            shared++;
                            if (hasWallBetween(ti, c, x, y, nx, ny)) walled++;
                        }
                    }
            if (shared > 0) {
                if (walled == 0) lkOpen++;
                else if (walled >= shared) lkWalled++;
                else lkPartial++;
            }
        }

        // R4: what each bathroom touches.
        for (int i : g) {
            if (!name(h, i).equals("bathroom")) continue;
            boolean core = false;
            int bedrooms = 0;
            for (int j : g) {
                if (j == i || !touching(box[i], box[j])) continue;
                String nm = name(h, j);
                if (nm.equals("livingroom") || nm.equals("kitchen") || nm.equals("hall"))
                    core = true;
                if (nm.equals("bedroom") || nm.equals("kidsbedroom")) bedrooms++;
            }
            String verdict = core ? (bedrooms > 0 ? "core + bedroom" : "off the core")
                    : bedrooms == 1 ? "ensuite (one bedroom)"
                    : bedrooms > 1 ? "shared between bedrooms" : "isolated";
            bathNeighbour.merge(verdict, 1L, Long::sum);
        }
    }

    static double proj(int[] b, int minX, int minY, int maxX, int maxY,
                       double ax, double ay) {
        double cx = (b[0] + b[2]) / 2.0 - (minX + maxX) / 2.0;
        double cy = (b[1] + b[3]) / 2.0 - (minY + maxY) / 2.0;
        return cx * ax + cy * ay;
    }

    static boolean hasWallBetween(TileIndex ti, CellData c, int x, int y, int nx, int ny) {
        // A wall lives on the north or west edge of a square (§18), so the
        // wall between (x,y) and its east/south neighbour sits on the NEIGHBOUR.
        int wx = nx > x ? nx : x, wy = ny > y ? ny : y;
        boolean vertical = nx != x;
        String[] names = c.tileNamesAt(wx, wy, 0);
        if (names == null) return false;
        for (String nm : names) {
            TileDefs.Tile t = ti.get(nm);
            if (t == null) continue;
            if (vertical && (t.props.containsKey("WallW") || t.props.containsKey("DoorWallW")))
                return true;
            if (!vertical && (t.props.containsKey("WallN") || t.props.containsKey("DoorWallN")))
                return true;
        }
        return false;
    }

    static boolean inRoom(LotHeader h, int room, int x, int y) {
        for (int[] q : h.rooms.get(room).rects)
            if (x >= q[0] && x < q[0] + q[2] && y >= q[1] && y < q[1] + q[3]) return true;
        return false;
    }

    static boolean inAnyRoom(List<Integer> g, int[][] box, int x, int y) {
        for (int i : g) {
            int[] b = box[i];
            if (x >= b[0] && x < b[2] && y >= b[1] && y < b[3]) return true;
        }
        return false;
    }

    static int[] nearestRoad(boolean[][] road, int cx, int cy) {
        int n = road.length;
        for (int r = 1; r <= ROAD_RANGE; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.abs(dx) != r && Math.abs(dy) != r) continue;
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= n || y >= n) continue;
                    if (road[x][y]) return new int[]{x, y};
                }
        return null;
    }

    static double dist(int[] b, int[] p) {
        double cx = (b[0] + b[2]) / 2.0, cy = (b[1] + b[3]) / 2.0;
        return Math.hypot(cx - p[0], cy - p[1]);
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

    static final int[] DX = {0, 0, -1, 1};
    static final int[] DY = {-1, 1, 0, 0};

    static void line(String title) {
        System.out.println("\n" + "=".repeat(64));
        System.out.println(title);
        System.out.println("=".repeat(64));
    }
}

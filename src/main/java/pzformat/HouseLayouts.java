package pzformat;

import java.nio.file.Path;
import java.util.*;

/**
 * Measure vanilla house layouts: what rooms, what sizes, what adjacencies,
 * at each footprint size. This is the data the layout engine should reproduce.
 *
 * For each dwelling (building with a livingroom), reports:
 *   - footprint area and aspect
 *   - room types, count, and individual areas
 *   - room area as fraction of building area
 *   - which rooms are adjacent (touch or 1 square apart)
 *   - room position: front half or back half of the building
 *
 * Output is grouped by footprint size bucket so we can read the recipe
 * for "a 120m² house" and "a 250m² house" separately.
 *
 * Usage:
 *   java -cp out pzformat.HouseLayouts MAP_DIR CELL [CELL...]
 *
 * Example:
 *   set cells (for f in "$MAPS/Muldraugh, KY"/*.lotheader; basename $f .lotheader; end)
 *   java -cp out pzformat.HouseLayouts "$MAPS/Muldraugh, KY" $cells > ~/Downloads/layouts.txt
 */
public final class HouseLayouts {

    static final Set<String> NOT_INTERIOR = Set.of("emptyoutside");
    static final Set<String> DWELLING_MARKER = Set.of("livingroom");

    // Size buckets matching our GIS footprints
    static final int[][] BUCKETS = {
            {0, 60, 'A'},       // tiny
            {61, 120, 'B'},     // small house
            {121, 200, 'C'},    // medium house
            {201, 350, 'D'},    // large house
            {351, 600, 'E'},    // very large
    };

    static final Map<String, List<HouseData>> byBucket = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: HouseLayouts MAP_DIR CELL [CELL...]");
            return;
        }
        Path mapDir = Path.of(args[0]);

        int cells = 0;
        for (int i = 1; i < args.length; i++) {
            try {
                LotHeader h = LotHeader.read(mapDir.resolve(args[i] + ".lotheader"));
                analyse(h);
                cells++;
            } catch (Exception e) { continue; }
            if (cells % 500 == 0) System.err.println("  ... " + cells + " cells");
        }

        System.out.println("cells: " + cells + "\n");

        for (Map.Entry<String, List<HouseData>> e : byBucket.entrySet()) {
            List<HouseData> houses = e.getValue();
            System.out.println("=".repeat(70));
            System.out.println("BUCKET " + e.getKey() + "   (" + houses.size() + " dwellings)");
            System.out.println("=".repeat(70));

            if (houses.isEmpty()) continue;

            // Summary stats
            Map<String, int[]> typeSummary = new TreeMap<>(); // [count, totalArea]
            int[] roomCounts = new int[20];
            for (HouseData hd : houses) {
                roomCounts[Math.min(hd.rooms.size(), 19)]++;
                for (RoomInfo ri : hd.rooms) {
                    int[] s = typeSummary.computeIfAbsent(ri.type, k -> new int[3]);
                    s[0]++;        // occurrence count
                    s[1] += ri.area; // total area
                    s[2]++;
                }
            }

            System.out.println("\nRoom count distribution:");
            for (int i = 1; i < 20; i++)
                if (roomCounts[i] > 0)
                    System.out.printf("  %2d rooms: %4d  (%4.1f%%)%n", i, roomCounts[i],
                            100.0 * roomCounts[i] / houses.size());

            System.out.println("\nRoom types (across all dwellings in this bucket):");
            System.out.printf("  %-20s %6s %8s %8s%n", "type", "count", "mean", "median");
            for (Map.Entry<String, int[]> te : typeSummary.entrySet()) {
                int[] s = te.getValue();
                System.out.printf("  %-20s %6d %8.1f     --%n",
                        te.getKey(), s[0], (double) s[1] / s[2]);
            }

            System.out.println("\nArea ratios (room area / building area), first 5 dwellings:");
            int shown = 0;
            for (HouseData hd : houses) {
                if (shown++ >= 5) break;
                System.out.printf("\n  footprint %dx%d = %d sq  (%d rooms)%n",
                        hd.w, hd.h, hd.area, hd.rooms.size());
                for (RoomInfo ri : hd.rooms) {
                    System.out.printf("    %-16s %3dx%-3d = %4d sq  (%4.1f%%)  adj: %s%n",
                            ri.type, ri.rw, ri.rh, ri.area,
                            100.0 * ri.area / hd.area,
                            ri.adjacent);
                }
            }
        }
    }

    static void analyse(LotHeader h) {
        // Cluster rooms into buildings (same logic as RoomCluster)
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
                if (Math.abs(h.rooms.get(i).floor - h.rooms.get(j).floor) > 1) continue;
                if (touching(box[i], box[j])) union(parent, i, j);
            }
        }

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++)
            if (use[i]) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);

        for (List<Integer> g : groups.values()) {
            // Only dwellings (buildings with a livingroom)
            boolean isDwelling = false;
            for (int i : g)
                if (DWELLING_MARKER.contains(name(h, i))) isDwelling = true;
            if (!isDwelling) continue;
            // Only ground floor for now
            boolean hasGroundFloor = false;
            for (int i : g) if (h.rooms.get(i).floor == 0) hasGroundFloor = true;
            if (!hasGroundFloor) continue;

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (int i : g) {
                if (h.rooms.get(i).floor != 0) continue;
                minX = Math.min(minX, box[i][0]); minY = Math.min(minY, box[i][1]);
                maxX = Math.max(maxX, box[i][2]); maxY = Math.max(maxY, box[i][3]);
            }

            int bw = maxX - minX, bh = maxY - minY;
            HouseData hd = new HouseData();
            hd.w = bw; hd.h = bh; hd.area = bw * bh;

            for (int i : g) {
                if (h.rooms.get(i).floor != 0) continue;
                RoomInfo ri = new RoomInfo();
                ri.type = name(h, i);
                ri.rw = box[i][2] - box[i][0];
                ri.rh = box[i][3] - box[i][1];
                ri.area = ri.rw * ri.rh;

                // Adjacency: which other room types does this room touch?
                List<String> adj = new ArrayList<>();
                for (int j : g) {
                    if (j == i || h.rooms.get(j).floor != 0) continue;
                    if (touching(box[i], box[j]))
                        adj.add(name(h, j));
                }
                Collections.sort(adj);
                ri.adjacent = String.join(",", adj);
                hd.rooms.add(ri);
            }

            // Sort rooms by area descending for readability
            hd.rooms.sort((a, b) -> b.area - a.area);

            // Bucket by area
            String bucket = "F 600+";
            for (int[] bkt : BUCKETS)
                if (hd.area >= bkt[0] && hd.area <= bkt[1])
                    bucket = (char) bkt[2] + " " + bkt[0] + "-" + bkt[1];
            byBucket.computeIfAbsent(bucket, k -> new ArrayList<>()).add(hd);
        }
    }

    static class HouseData {
        int w, h, area;
        List<RoomInfo> rooms = new ArrayList<>();
    }

    static class RoomInfo {
        String type;
        int rw, rh, area;
        String adjacent = "";
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
}

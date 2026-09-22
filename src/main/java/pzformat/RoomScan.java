package pzformat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Batch room scanner: one JVM, tile definitions loaded once, cells cached.
 *
 * Replaces the fish loop that spawned one JVM per square (~13,000 JVM starts).
 *
 * Input file format (plain text, exactly as written by hand):
 *
 *     Residential kitchens
 *      x:13615,y:2127,layer:0
 *      x:13687,y:2127,layer:0
 *
 *     Residential garage [building]
 *      x:13441,y:2004,layer:0
 *
 * Any line without an x:/y: coordinate is a section label. A label containing
 * "[building]" scans every room in the building the coordinate belongs to,
 * not just the one room.
 *
 * For each coordinate:
 *   - inside a room  -> the whole room (all its rects) is scanned
 *   - outside a room -> a square of the given radius around the point
 *
 * Output per room: an ASCII layout grid with a legend, then every object
 * with its offset from the room's top-left corner, the room edges it touches
 * (N/S/E/W), and its CustomName / GroupName / Facing / container.
 *
 * Usage:
 *   java -cp out pzformat.RoomScan <mediaDir> <mapDir> <coordsFile> [radius]
 */
public final class RoomScan {

    static final Pattern COORD = Pattern.compile(
            "x\\s*:\\s*(-?\\d+)\\s*,\\s*y\\s*:\\s*(-?\\d+)(?:\\s*,\\s*layer\\s*:\\s*(-?\\d+))?");

    static final String SYMBOLS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@$%&*=+";

    /** Tile kinds that are structure, not furniture. */
    static final Set<String> STRUCTURE_KINDS = Set.of("WALL", "DOOR", "WINDOW", "FLOOR");

    /** Outdoor noise: grass tufts, blends, ground decals, trees. */
    static final String[] NOISE_PREFIXES = {
            "e_", "blends_", "d_", "vegetation_", "floors_exterior_street"
    };

    /** Keep at most this many cells in memory; they are large. */
    static final int CELL_CACHE = 6;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: RoomScan <mediaDir> <mapDir> <coordsFile> [radius]");
            System.exit(1);
        }
        Path media  = Paths.get(args[0]);
        Path mapDir = Paths.get(args[1]);
        Path coords = Paths.get(args[2]);
        int radius  = args.length > 3 ? Integer.parseInt(args[3]) : 8;

        long t0 = System.currentTimeMillis();
        TileIndex ti = TileIndex.load(media);
        System.out.println("tile definitions loaded: " + ti.byName.size());

        Map<String, CellData> cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CellData> e) {
                return size() > CELL_CACHE;
            }
        };

        String label = "(unlabelled)";
        int count = 0;
        for (String raw : Files.readAllLines(coords)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = COORD.matcher(line);
            if (!m.find()) {
                label = line.replaceFirst("^#+\\s*", "");
                System.out.println("\n\n############################################################");
                System.out.println("## " + label);
                System.out.println("############################################################");
                continue;
            }
            int wx = Integer.parseInt(m.group(1));
            int wy = Integer.parseInt(m.group(2));
            int z  = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            count++;
            try {
                scan(ti, mapDir, cache, label, wx, wy, z, radius);
            } catch (Exception e) {
                System.out.println("   ERROR scanning " + wx + "," + wy + ": " + e);
            }
        }

        System.out.printf("%n=== SCAN COMPLETE: %d coordinates in %.1f s ===%n",
                count, (System.currentTimeMillis() - t0) / 1000.0);
    }

    // ------------------------------------------------------------------

    static void scan(TileIndex ti, Path mapDir, Map<String, CellData> cache,
                     String label, int wx, int wy, int z, int radius) {
        int cx = Math.floorDiv(wx, 256), cy = Math.floorDiv(wy, 256);
        int lx = wx - cx * 256,          ly = wy - cy * 256;
        String cellName = cx + "_" + cy;

        System.out.println("\n=== COORD " + wx + "," + wy + " z" + z
                + "   CELL " + cellName + "   LOCAL " + lx + "," + ly + " ===");

        CellData c = cell(mapDir, cache, cellName);
        if (c == null) { System.out.println("   cell " + cellName + " not found"); return; }
        if (z < c.minLevel || z > c.maxLevel) {
            System.out.println("   z" + z + " outside cell levels " + c.minLevel + ".." + c.maxLevel);
            return;
        }
        LotHeader h = c.header;

        int roomIdx = roomContaining(h, lx, ly, z);
        if (roomIdx < 0) {
            System.out.println("   not inside any room — scanning radius " + radius + " around the point");
            int[] rect = {lx - radius, ly - radius, 2 * radius + 1, 2 * radius + 1};
            dumpArea(ti, c, List.of(rect), z, rect, true);
            return;
        }

        List<Integer> toScan = new ArrayList<>();
        if (label.toLowerCase().contains("[building]")) {
            int b = buildingOf(h, roomIdx);
            if (b >= 0) {
                for (int ri : h.buildings.get(b)) toScan.add(ri);
                System.out.println("   building " + b + ": " + toScan.size() + " rooms");
            } else {
                System.out.println("   room " + roomIdx + " belongs to no building; scanning the room only");
                toScan.add(roomIdx);
            }
        } else {
            toScan.add(roomIdx);
        }

        for (int ri : toScan) {
            LotHeader.Room r = h.rooms.get(ri);
            if (r.floor < c.minLevel || r.floor > c.maxLevel) continue;
            int[] bb = bbox(r.rects);
            StringBuilder rs = new StringBuilder();
            for (int[] q : r.rects)
                rs.append(" [").append(q[0]).append(',').append(q[1]).append(' ')
                  .append(q[2]).append('x').append(q[3]).append(']');
            System.out.println("\n   --- room " + ri + " '" + r.name + "'  floor " + r.floor
                    + "  size " + bb[2] + "x" + bb[3] + "  rects" + rs);
            dumpArea(ti, c, r.rects, r.floor, bb, false);
        }
    }

    /**
     * Print a layout grid, a legend and an object list for the squares of
     * {@code rects}, framed by bounding box {@code bb} = {x, y, w, h}.
     */
    static void dumpArea(TileIndex ti, CellData c, List<int[]> rects, int z,
                         int[] bb, boolean outdoor) {
        int bx = bb[0], by = bb[1], bw = bb[2], bh = bb[3];
        char[][] grid = new char[bh][bw];
        for (char[] row : grid) Arrays.fill(row, ' ');

        Map<String, Character> legend = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();

        for (int y = by; y < by + bh; y++) {
            for (int x = bx; x < bx + bw; x++) {
                if (!inAny(rects, x, y)) continue;
                int gx = x - bx, gy = y - by;
                if (x < 0 || y < 0 || x >= c.cellSize || y >= c.cellSize) {
                    grid[gy][gx] = '?';          // room spills into a neighbouring cell
                    continue;
                }
                grid[gy][gx] = '.';
                String[] names = c.tileNamesAt(x, y, z);
                if (names == null) continue;

                String edges = outdoor ? "" : edgeTag(rects, x, y);
                boolean first = true;
                for (String name : names) {
                    if (STRUCTURE_KINDS.contains(String.valueOf(ti.kindOf(name)))) continue;
                    if (ti.isOverlay(name)) continue;
                    if (isNoise(name)) continue;

                    TileDefs.Tile t = ti.get(name);
                    String cn = prop(t, "CustomName");
                    String gn = prop(t, "GroupName");
                    String key = cn != null ? (gn != null ? gn + " " + cn : cn) : name;

                    Character sym = legend.get(key);
                    if (sym == null) {
                        int i = legend.size();
                        sym = i < SYMBOLS.length() ? SYMBOLS.charAt(i) : '#';
                        legend.put(key, sym);
                    }
                    if (first) { grid[gy][gx] = sym; first = false; }

                    lines.add(String.format("      %c (%2d,%2d) %-4s %-42s %s",
                            sym, gx, gy, edges, name, describe(t)));
                }
            }
        }

        if (lines.isEmpty()) {
            System.out.println("      (no furniture / objects)");
            return;
        }

        if (bw <= 90) {
            // Column ruler (units digit) so offsets can be read off the grid.
            StringBuilder ruler = new StringBuilder("         ");
            for (int x = 0; x < bw; x++) ruler.append(x % 10);
            System.out.println(ruler);
            for (int y = 0; y < bh; y++)
                System.out.printf("      %2d %s%n", y, new String(grid[y]));
        } else {
            System.out.println("      (grid omitted: " + bw + " wide)");
        }

        System.out.println("      legend:");
        for (Map.Entry<String, Character> e : legend.entrySet())
            System.out.println("        " + e.getValue() + " = " + e.getKey());

        System.out.println("      objects:  sym (dx,dy) edge tile  props");
        for (String l : lines) System.out.println(l);
    }

    // ------------------------------------------------------------------

    static CellData cell(Path mapDir, Map<String, CellData> cache, String name) {
        if (cache.containsKey(name)) return cache.get(name);
        CellData c = null;
        Path lh = mapDir.resolve(name + ".lotheader");
        Path lp = mapDir.resolve("world_" + name + ".lotpack");
        if (Files.exists(lh) && Files.exists(lp)) {
            try {
                c = CellData.load(lp, lh);
            } catch (Exception e) {
                System.out.println("   failed to load cell " + name + ": " + e.getMessage());
            }
        }
        cache.put(name, c);
        return c;
    }

    static int roomContaining(LotHeader h, int x, int y, int z) {
        for (int i = 0; i < h.rooms.size(); i++) {
            LotHeader.Room r = h.rooms.get(i);
            if (r.floor != z) continue;
            if (inAny(r.rects, x, y)) return i;
        }
        return -1;
    }

    static int buildingOf(LotHeader h, int roomIdx) {
        for (int b = 0; b < h.buildings.size(); b++)
            for (int ri : h.buildings.get(b))
                if (ri == roomIdx) return b;
        return -1;
    }

    static boolean inAny(List<int[]> rects, int x, int y) {
        for (int[] r : rects)
            if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) return true;
        return false;
    }

    static int[] bbox(List<int[]> rects) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (int[] r : rects) {
            x0 = Math.min(x0, r[0]);        y0 = Math.min(y0, r[1]);
            x1 = Math.max(x1, r[0] + r[2]); y1 = Math.max(y1, r[1] + r[3]);
        }
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    /** Which room edges a square touches: N/S/E/W where the neighbour is outside the room. */
    static String edgeTag(List<int[]> rects, int x, int y) {
        StringBuilder s = new StringBuilder();
        if (!inAny(rects, x, y - 1)) s.append('N');
        if (!inAny(rects, x, y + 1)) s.append('S');
        if (!inAny(rects, x + 1, y)) s.append('E');
        if (!inAny(rects, x - 1, y)) s.append('W');
        return s.length() == 0 ? "-" : s.toString();
    }

    static boolean isNoise(String name) {
        for (String p : NOISE_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    static String prop(TileDefs.Tile t, String key) {
        if (t == null) return null;
        String v = t.props.get(key);
        return v == null || v.isEmpty() ? null : v;
    }

    static String describe(TileDefs.Tile t) {
        if (t == null) return "(no tile definition)";
        StringBuilder s = new StringBuilder();
        String cn = prop(t, "CustomName");
        String gn = prop(t, "GroupName");
        String fc = prop(t, "Facing");
        String ct = prop(t, "container");
        if (gn != null || cn != null)
            s.append('"').append(gn != null ? gn + " " : "").append(cn != null ? cn : "").append('"');
        if (fc != null) s.append("  Facing=").append(fc);
        if (ct != null) s.append("  container=").append(ct);
        for (String flag : new String[]{"attachedN", "attachedW", "attachedE", "attachedS",
                "IsTableTop", "IsGridExtensionTile", "IsMoveAble"})
            if (t.props.containsKey(flag)) s.append("  ").append(flag);
        return s.toString();
    }
}

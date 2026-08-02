package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Answers "what is actually at this world coordinate?" without launching the game.
 *
 * Written because a painted marker failed to appear in-game across 12 spawns
 * while the bytes verified clean, which points at the world -> cell mapping
 * rather than the writers. Spawn points sit inside houses, so the check is:
 * does the predicted cell have a ROOM covering the predicted local square?
 * If not, the mapping is wrong.
 */
public final class Locate {

    public static void run(Path mapDir, int worldX, int worldY) throws Exception {
        int cellX = Math.floorDiv(worldX, 256), cellY = Math.floorDiv(worldY, 256);
        int lx = Math.floorMod(worldX, 256), ly = Math.floorMod(worldY, 256);
        System.out.println("world (" + worldX + ", " + worldY + ")");
        System.out.println("  assuming cellSize 256: cell " + cellX + "_" + cellY
                + ", local (" + lx + ", " + ly + ")");

        report(mapDir, cellX, cellY, lx, ly, worldX, worldY);

        orientationTest(mapDir, cellX, cellY);
    }

    /**
     * Decides whether our chunk indexing transposes x and y.
     *
     * Room rectangles live in the lotheader and are independent of how we index
     * the lotpack. So: take every room rect, look at the tiles underneath it
     * both as (x,y) and as (y,x), and count how many squares carry an interior
     * floor. Rooms are indoors; the orientation that finds floors is correct.
     *
     * Byte round-tripping cannot catch this error, because the same
     * transposition is applied on read and on write.
     */
    static void orientationTest(Path mapDir, int cellX, int cellY) throws Exception {
        String cell = cellX + "_" + cellY;
        Path lh = mapDir.resolve(cell + ".lotheader");
        Path lp = mapDir.resolve("world_" + cell + ".lotpack");
        if (!Files.exists(lh) || !Files.exists(lp)) return;

        LotHeader h = LotHeader.read(lh);
        CellData c = CellData.load(lp, lh);
        System.out.println("\n=== orientation test: do room rects sit over indoor floors? ===");

        int normalIndoor = 0, normalTotal = 0, swapIndoor = 0, swapTotal = 0;
        for (LotHeader.Room r : h.rooms) {
            for (int[] rect : r.rects) {
                for (int x = rect[0]; x < rect[0] + rect[2]; x++)
                    for (int y = rect[1]; y < rect[1] + rect[3]; y++) {
                        if (x < 0 || y < 0 || x >= c.cellSize || y >= c.cellSize) continue;
                        int z = r.floor;
                        if (z < c.minLevel || z > c.maxLevel) continue;

                        String[] a = c.tileNamesAt(x, y, z);
                        normalTotal++;
                        if (isIndoorFloor(a)) normalIndoor++;

                        String[] b = c.tileNamesAt(y, x, z);
                        swapTotal++;
                        if (isIndoorFloor(b)) swapIndoor++;
                    }
            }
        }
        System.out.printf("   as (x,y): %d / %d room squares have an interior floor  (%.1f%%)%n",
                normalIndoor, normalTotal, pct(normalIndoor, normalTotal));
        System.out.printf("   as (y,x): %d / %d room squares have an interior floor  (%.1f%%)%n",
                swapIndoor, swapTotal, pct(swapIndoor, swapTotal));
        if (normalIndoor > swapIndoor * 2)
            System.out.println("   => (x,y) is correct; indexing is fine");
        else if (swapIndoor > normalIndoor * 2)
            System.out.println("   => (y,x) wins: OUR CHUNK INDEXING TRANSPOSES X AND Y");
        else
            System.out.println("   => inconclusive; neither orientation lands on floors");
    }

    static double pct(int a, int b) { return b == 0 ? 0 : 100.0 * a / b; }

    static boolean isIndoorFloor(String[] names) {
        if (names == null) return false;
        for (String n : names)
            if (n.startsWith("floors_interior") || n.contains("carpet")
                    || n.startsWith("floors_rug") || n.contains("_tiles_")
                    || n.startsWith("floors_burnt"))
                return true;
        return false;
    }

    static void report(Path mapDir, int cellX, int cellY, int lx, int ly,
                       int worldX, int worldY) throws Exception {
        String cell = cellX + "_" + cellY;
        Path lh = mapDir.resolve(cell + ".lotheader");
        Path lp = mapDir.resolve("world_" + cell + ".lotpack");
        if (!Files.exists(lh)) { System.out.println("  cell " + cell + " does not exist"); return; }

        LotHeader h = LotHeader.read(lh);
        System.out.println("\ncell " + cell + ": " + h.tileNames.size() + " tile names, "
                + h.rooms.size() + " rooms, " + h.buildings.size() + " buildings, z "
                + h.minLevel + ".." + h.maxLevel());

        List<String> hits = new ArrayList<>();
        for (int i = 0; i < h.rooms.size(); i++) {
            LotHeader.Room r = h.rooms.get(i);
            for (int[] rect : r.rects)
                if (lx >= rect[0] && lx < rect[0] + rect[2]
                        && ly >= rect[1] && ly < rect[1] + rect[3])
                    hits.add("room " + i + " '" + r.name + "' floor=" + r.floor
                            + " rect [" + rect[0] + "," + rect[1] + " "
                            + rect[2] + "x" + rect[3] + "]");
        }
        System.out.println("rooms covering local (" + lx + "," + ly + "): "
                + (hits.isEmpty() ? "NONE  <-- not inside a building" : ""));
        for (String s : hits) System.out.println("   " + s);

        CellData c = CellData.load(lp, lh);
        System.out.println("\ntiles at that square:");
        for (int z = h.minLevel; z <= Math.min(h.maxLevel(), h.minLevel + 3); z++) {
            String[] names = c.tileNamesAt(lx, ly, z);
            System.out.printf("   z=%-3d %s%n", z, names == null ? "(empty)" : Arrays.toString(names));
        }

        // A house floor is a strong signal we are in the right place.
        String[] ground = c.tileNamesAt(lx, ly, 0);
        boolean indoors = ground != null && Arrays.stream(ground)
                .anyMatch(n -> n.contains("floors_interior") || n.contains("carpet")
                        || n.contains("wood") || n.contains("tiles"));
        System.out.println("looks like a building interior: " + indoors);
    }

    static void quiet(Path mapDir, int cellX, int cellY, int lx, int ly) throws Exception {
        String cell = cellX + "_" + cellY;
        Path lh = mapDir.resolve(cell + ".lotheader");
        if (!Files.exists(lh)) return;
        LotHeader h = LotHeader.read(lh);
        int hits = 0;
        String name = null;
        for (LotHeader.Room r : h.rooms)
            for (int[] rect : r.rects)
                if (lx >= rect[0] && lx < rect[0] + rect[2]
                        && ly >= rect[1] && ly < rect[1] + rect[3]) {
                    hits++;
                    if (name == null) name = r.name;
                }
        System.out.printf("   cell %-8s rooms at that local square: %d%s%n",
                cell, hits, name == null ? "" : "  (" + name + ")");
    }
}

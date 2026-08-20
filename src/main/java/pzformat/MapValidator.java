package pzformat;

import java.nio.file.Path;
import java.util.*;

/**
 * A4: Validate a map against the rules that make a building playable.
 *
 * Charter §1: "TileZed lets you paint an invalid map. This must not."
 *
 *   1. ROOM WITH NO EXIT — no doorway on any perimeter edge.
 *   2. DOORWAY WITH NO ADJACENT FLOOR — door onto void.
 *   3. WALL GAP THAT ISN'T A DOOR — hole in perimeter (warning, not error;
 *      the livingroom/kitchen open boundary is a valid gap).
 *   4. ROOM WITH NO FLOOR — square inside a room rect has no floor tile.
 *   5. ROOM MEMBERSHIP MISMATCH — square inside a room rect has room id -1.
 *
 * Usage:
 *   java -cp out pzformat.MapValidator MEDIA_DIR MAP_DIR CELL [CELL...]
 */
public final class MapValidator {

    static final Set<String> NOT_INTERIOR = Set.of("emptyoutside");
    static int errors, warnings, roomsChecked;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: MapValidator MEDIA_DIR MAP_DIR CELL [CELL...]");
            return;
        }
        TileIndex ti = TileIndex.load(Path.of(args[0]));
        Path mapDir = Path.of(args[1]);

        int cells = 0;
        for (int i = 2; i < args.length; i++) {
            String cellName = args[i];
            try {
                Path lh = mapDir.resolve(cellName + ".lotheader");
                CellData c = CellData.load(
                        mapDir.resolve("world_" + cellName + ".lotpack"), lh);
                validateCell(ti, c, cellName);
                cells++;
            } catch (Exception e) {
                System.out.println("  skipped " + cellName + ": " + e.getMessage());
            }
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.printf("TOTAL: %d cells, %d rooms checked, %d errors, %d warnings%n",
                cells, roomsChecked, errors, warnings);
        if (errors == 0 && warnings == 0)
            System.out.println("All rooms pass validation.");
        else if (errors == 0)
            System.out.println("No errors. Warnings may be intentional (open-plan boundaries).");
        System.out.println("=".repeat(60));
    }

    static void validateCell(TileIndex ti, CellData c, String cellName) {
        LotHeader h = c.header;
        System.out.println("\n--- " + cellName + ": " + h.rooms.size() + " rooms ---");

        for (int ri = 0; ri < h.rooms.size(); ri++) {
            LotHeader.Room room = h.rooms.get(ri);
            if (room.name != null && NOT_INTERIOR.contains(room.name)) continue;
            if (room.rects == null || room.rects.isEmpty()) continue;
            roomsChecked++;

            String label = (room.name == null ? "room" : room.name)
                    + " #" + ri + " z=" + room.floor;

            // Rule 3: wall gaps on perimeter
            int gaps = 0;
            for (int[] rect : room.rects) {
                int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];
                for (int x = rx; x < rx + rw; x++)
                    if (!hasEdge(ti, c, x, ry, room.floor, true)
                            && !insideRoom(room, x, ry - 1)) gaps++;
                for (int x = rx; x < rx + rw; x++)
                    if (!hasEdge(ti, c, x, ry + rh, room.floor, true)
                            && !insideRoom(room, x, ry + rh)) gaps++;
                for (int y = ry; y < ry + rh; y++)
                    if (!hasEdge(ti, c, rx, y, room.floor, false)
                            && !insideRoom(room, rx - 1, y)) gaps++;
                for (int y = ry; y < ry + rh; y++)
                    if (!hasEdge(ti, c, rx + rw, y, room.floor, false)
                            && !insideRoom(room, rx + rw, y)) gaps++;
            }
            if (gaps > 0) warn(label, gaps + " wall gap(s) on perimeter");

            // Rule 1: at least one door on the perimeter
            int doorCount = 0;
            List<int[]> doorSquares = new ArrayList<>();
            for (int[] rect : room.rects) {
                int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];
                // North edge: DoorWallN or doorN at y=ry
                for (int x = rx; x < rx + rw; x++)
                    if (hasDoorOnEdge(ti, c, x, ry, room.floor, true)) {
                        doorCount++; doorSquares.add(new int[]{x, ry, 1});
                    }
                // South edge: DoorWallN or doorN at y=ry+rh
                for (int x = rx; x < rx + rw; x++)
                    if (hasDoorOnEdge(ti, c, x, ry + rh, room.floor, true)) {
                        doorCount++; doorSquares.add(new int[]{x, ry + rh, 1});
                    }
                // West edge: DoorWallW or doorW at x=rx
                for (int y = ry; y < ry + rh; y++)
                    if (hasDoorOnEdge(ti, c, rx, y, room.floor, false)) {
                        doorCount++; doorSquares.add(new int[]{rx, y, 0});
                    }
                // East edge: DoorWallW or doorW at x=rx+rw
                for (int y = ry; y < ry + rh; y++)
                    if (hasDoorOnEdge(ti, c, rx + rw, y, room.floor, false)) {
                        doorCount++; doorSquares.add(new int[]{rx + rw, y, 0});
                    }
            }
            if (doorCount == 0 && gaps == 0) {
                // Truly sealed: no door AND no wall gap. A wall gap is a valid
                // exit (open-plan boundary), even without a door tile.
                if (room.floor == 0)
                    error(label, "sealed — no door and no wall gap on any perimeter edge");
                else
                    warn(label, "no door on perimeter (z=" + room.floor
                            + " — may connect by stairs)");
            } else if (doorCount == 0 && gaps > 0) {
                // Has wall gaps but no door — accessible but no closeable entrance.
                // This is normal for open-plan rooms (livingroom/kitchen pairs).
            }

            // Rule 2: every door must have floor on both sides
            for (int[] ds : doorSquares) {
                int dx = ds[0], dy = ds[1];
                boolean north = ds[2] == 1;
                int ox = north ? dx : dx - 1;
                int oy = north ? dy - 1 : dy;
                boolean f1 = hasFloor(ti, c, dx, dy, room.floor);
                boolean f2 = hasFloor(ti, c, ox, oy, room.floor);
                if (!f1 || !f2) {
                    warn(label, "doorway at (" + dx + "," + dy + ") "
                            + (north ? "north" : "west")
                            + " edge — missing floor on "
                            + (!f1 && !f2 ? "both sides" : "one side"));
                }
            }



            // Rule 4: floor coverage
            int noFloor = 0;
            for (int[] rect : room.rects) {
                int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];
                for (int y = ry; y < ry + rh; y++)
                    for (int x = rx; x < rx + rw; x++)
                        if (!hasFloor(ti, c, x, y, room.floor)) noFloor++;
            }
            if (noFloor > 0) warn(label, noFloor + " interior square(s) with no floor");

            // Rule 5: room membership
            int noMember = 0;
            for (int[] rect : room.rects) {
                int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];
                for (int y = ry; y < ry + rh; y++)
                    for (int x = rx; x < rx + rw; x++) {
                        if (x < 0 || y < 0 || x >= 256 || y >= 256) continue;
                        if (c.roomAt(x, y, room.floor) < 0) noMember++;
                    }
            }
            if (noMember > 0) warn(label, noMember + " interior square(s) not stamped with room id");
        }
    }

    /** Does this square have a door (structural or fixture) on the given edge? */
    static boolean hasDoorOnEdge(TileIndex ti, CellData c, int x, int y, int z, boolean north) {
        if (north)
            return hasTileProp(ti, c, x, y, z, "DoorWallN")
                    || hasTileProp(ti, c, x, y, z, "doorN");
        else
            return hasTileProp(ti, c, x, y, z, "DoorWallW")
                    || hasTileProp(ti, c, x, y, z, "doorW");
    }

    /** Does any tile on this square carry the given property? */
    static boolean hasTileProp(TileIndex ti, CellData c, int x, int y, int z, String prop) {
        if (x < 0 || y < 0 || x >= 256 || y >= 256) return false;
        String[] names = c.tileNamesAt(x, y, z);
        if (names == null) return false;
        for (String name : names) {
            TileDefs.Tile t = ti.get(name);
            if (t != null && t.props.containsKey(prop)) return true;
        }
        return false;
    }

    /** Does this square have a wall or door on the given edge? */
    static boolean hasEdge(TileIndex ti, CellData c, int x, int y, int z, boolean north) {
        if (north)
            return hasTileProp(ti, c, x, y, z, "WallN")
                    || hasTileProp(ti, c, x, y, z, "DoorWallN")
                    || hasTileProp(ti, c, x, y, z, "WallNW");
        else
            return hasTileProp(ti, c, x, y, z, "WallW")
                    || hasTileProp(ti, c, x, y, z, "DoorWallW")
                    || hasTileProp(ti, c, x, y, z, "WallNW");
    }

    /** Does this square have a floor tile? */
    static boolean hasFloor(TileIndex ti, CellData c, int x, int y, int z) {
        if (x < 0 || y < 0 || x >= 256 || y >= 256) return false;
        String[] names = c.tileNamesAt(x, y, z);
        if (names == null) return false;
        for (String name : names)
            if (ti.kindOf(name) == TileIndex.Kind.FLOOR) return true;
        return false;
    }

    /** Is (x,y) inside any of the room's rects? */
    static boolean insideRoom(LotHeader.Room room, int x, int y) {
        for (int[] rect : room.rects) {
            int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];
            if (x >= rx && x < rx + rw && y >= ry && y < ry + rh) return true;
        }
        return false;
    }

    static void error(String room, String msg) {
        System.out.println("  ERROR  " + room + ": " + msg);
        errors++;
    }
    static void warn(String room, String msg) {
        System.out.println("  WARN   " + room + ": " + msg);
        warnings++;
    }
}

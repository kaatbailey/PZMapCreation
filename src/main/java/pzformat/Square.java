package pzformat;

import java.util.*;

/**
 * A semantic view of one square: what is on it, not which sprite indices.
 *
 * This is the layer an editor works against. "Replace the floor" should leave
 * the walls alone; "place a door" needs to know which edge a wall sits on.
 * CellData holds raw tile indices; this interprets them.
 *
 * Walls in Project Zomboid are EDGE-based: a wall belongs to the north or west
 * edge of a square, so one square can carry both. Confirmed against room
 * geometry at 99.5% / 100%.
 */
public final class Square {

    public final int x, y, z;
    public final List<String> tiles = new ArrayList<>();

    public String floor;
    public String northWall, westWall;
    /** Door leaves and window panes mounted in this square's walls. */
    public final List<String> fixtures = new ArrayList<>();
    public boolean northIsDoorway, westIsDoorway, northIsWindow, westIsWindow;
    /** Decoration painted on the structure: grime, blood, rust. Kept separate. */
    public final List<String> overlays = new ArrayList<>();
    public final List<String> objects = new ArrayList<>();
    public final List<String> vegetation = new ArrayList<>();
    public boolean hasDoor, hasWindow, blocksMovement;
    public String containerType;
    public int roomId = -1;

    private Square(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

    public static Square at(CellData cell, TileIndex ti, int x, int y, int z) {
        Square s = new Square(x, y, z);
        String[] names = cell.tileNamesAt(x, y, z);
        s.roomId = cell.roomAt(x, y, z);
        if (names == null) return s;
        s.tiles.addAll(Arrays.asList(names));

        // Two passes: structural tiles first, so decoration painted onto a wall
        // or floor never wins the slot from the thing it is painted on.
        for (int pass = 0; pass < 2; pass++) {
            boolean wantOverlay = pass == 1;
            for (String n : names) {
                if (ti.isOverlay(n) != wantOverlay) continue;
                TileIndex.Kind k = ti.kindOf(n);
                TileIndex.Edge e = ti.edgeOf(n);
                if (ti.blocksMovement(n)) s.blocksMovement = true;
                String ct = ti.containerType(n);
                if (ct != null && s.containerType == null) s.containerType = ct;

                if (wantOverlay) { s.overlays.add(n); continue; }

                // Fixtures (door leaf, glass pane) mount in a wall but are not
                // the wall; decoration merely hangs on it. Only structure gets
                // the edge slot.
                if (ti.isWallFixture(n)) {
                    s.fixtures.add(n);
                    if (k == TileIndex.Kind.DOOR) s.hasDoor = true;
                    if (k == TileIndex.Kind.WINDOW) s.hasWindow = true;
                    continue;
                }
                if ((k == TileIndex.Kind.WALL || k == TileIndex.Kind.DOOR
                        || k == TileIndex.Kind.WINDOW) && !ti.isStructuralWall(n)) {
                    s.objects.add(n);
                    continue;
                }

                switch (k) {
                    case FLOOR -> { if (s.floor == null) s.floor = n; }
                    case WALL, DOOR, WINDOW -> {
                        boolean doorway = ti.isDoorway(n), windowWall = ti.isWindowWall(n);
                        if (doorway) s.hasDoor = true;
                        if (windowWall) s.hasWindow = true;
                        switch (e) {
                            case NORTH -> {
                                if (s.northWall == null) s.northWall = n;
                                s.northIsDoorway |= doorway;
                                s.northIsWindow |= windowWall;
                            }
                            case WEST -> {
                                if (s.westWall == null) s.westWall = n;
                                s.westIsDoorway |= doorway;
                                s.westIsWindow |= windowWall;
                            }
                            case BOTH -> {
                                if (s.northWall == null) s.northWall = n;
                                if (s.westWall == null) s.westWall = n;
                                s.northIsDoorway |= doorway; s.westIsDoorway |= doorway;
                                s.northIsWindow |= windowWall; s.westIsWindow |= windowWall;
                            }
                            case NONE -> s.objects.add(n);
                        }
                    }
                    case VEGETATION -> s.vegetation.add(n);
                    default -> s.objects.add(n);
                }
            }
        }
        return s;
    }

    public boolean isEmpty() { return tiles.isEmpty(); }
    public boolean hasWall() { return northWall != null || westWall != null; }
    public boolean indoors() { return roomId >= 0; }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("(" + x + "," + y + ",z" + z + ")");
        if (roomId >= 0) sb.append(" room=").append(roomId);
        if (floor != null) sb.append(" floor=").append(floor);
        if (northWall != null) sb.append(" N=").append(northWall)
                .append(northIsDoorway ? "[door]" : northIsWindow ? "[window]" : "");
        if (westWall != null) sb.append(" W=").append(westWall)
                .append(westIsDoorway ? "[door]" : westIsWindow ? "[window]" : "");
        if (!fixtures.isEmpty()) sb.append(" fixtures=").append(fixtures.size());
        if (containerType != null) sb.append(" container=").append(containerType);
        if (hasDoor) sb.append(" DOOR");
        if (hasWindow) sb.append(" WINDOW");
        if (!overlays.isEmpty()) sb.append(" overlays=").append(overlays.size());
        if (!objects.isEmpty()) sb.append(" objects=").append(objects.size());
        return sb.toString();
    }
}

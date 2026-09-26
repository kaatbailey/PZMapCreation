package pzformat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;

import pzformat.FurnitureProfile.Activity;
import pzformat.FurnitureProfile.Density;
import pzformat.FurnitureProfile.Rel;
import pzformat.FurnitureProfile.Satellite;
import pzformat.FurnitureProfile.Strategy;
import pzformat.FurnitureProfile.Zone;

/**
 * Turns a FurnitureProfile into objects on squares.
 *
 * Two hard constraints, both about the engine rather than about looks:
 *
 *   Door approach squares stay clear. The square inside each door is never
 *   occupied, or the room cannot be entered.
 *
 *   Every free square stays reachable from the door. Zombies path through
 *   these rooms; a sealed corner is worse than an empty one. After placement
 *   a flood fill from the door checks this, and anything that cut the room in
 *   two is removed.
 *
 * Furniture goes one square INSIDE the wall, not on the wall square. An
 * attachedN object leans against the wall to its north, so it sits at ry+1
 * for the north wall. Confirmed against vanilla kitchens 2026-09-20.
 */
public final class FurniturePlacer {

    /** Square states in the room's local occupancy grid. */
    private static final byte FREE = 0;
    private static final byte TAKEN = 1;
    private static final byte KEEP_CLEAR = 2;   // door approach: never fill
    private static final byte SURFACE = 3;      // taken, but can hold ON_TOP

    private final CellData cell;
    private final TilePalette pal;
    private final Random rng;
    private final Set<String> doorSquares;
    private final Set<String> windowSquares;

    // Current room, in cell-local coordinates.
    private int rx, ry, rw, rh, roomId;
    private boolean tinyRoom;   // room < 5x5: cap activities to avoid overcrowding
    private byte[][] grid;
    /** What was placed where, so the circulation check can undo the last thing. */
    private final List<int[]> placed = new ArrayList<>();

    /**
     * One tile per role, locked for the room.
     *
     * Drawing independently per object gives a storeroom with one of every
     * shelf in the game, which reads as a jumble rather than a room somebody
     * fitted out. Locking a variant per role and letting ~20% drift keeps the
     * set coherent while stopping it looking stamped.
     */
    private final java.util.Map<String, String> lock = new java.util.HashMap<>();
    private static final double LOCK_DRIFT = 0.2;

    /**
     * Fixture classes already supplied to this room (see FurnitureSet.provides).
     *
     * The stamper walks four independent wall passes, and before this existed
     * each pass picked its own set without knowing what the others had done — a
     * kitchen came out with a counter run on the north wall, a second run on the
     * west wall and a standalone fridge to finish, giving three sinks and two
     * ovens. A set whose class is already claimed is now never attempted.
     */
    private final Set<String> claimed = new java.util.HashSet<>();

    /**
     * Roles whose objects a person walks up to and uses. These must have a
     * free square in front of them — a chair facing a wall cannot be sat in,
     * two shelves facing each other cannot both be reached, and a sink in a
     * corner is decoration. Anything not listed here (crates, plants, rugs)
     * has no front and is placed anywhere.
     */
    private static final Set<String> APPROACHABLE = Set.of(
            "chair", "chair_office", "chair_dining", "chair_soft",
            "couch", "bed", "desk", "counter", "sink", "toilet", "shower",
            "bath", "fridge", "oven", "oven_ind", "shelves", "shelves_office",
            "shelves_retail", "locker", "wardrobe", "drawers", "table",
            "workbench", "bench", "watercooler", "television");

    private FurniturePlacer(CellData cell, TilePalette pal, Random rng,
                            Set<String> doorSquares, Set<String> windowSquares) {
        this.cell = cell;
        this.pal = pal;
        this.rng = rng;
        this.doorSquares = doorSquares;
        this.windowSquares = windowSquares;
    }

    /**
     * Furnish every room of one building.
     *
     * @param planned       the room rectangles, cell-local
     * @param idx           room index list, parallel to planned
     * @param doorSquares   "x,y" keys of squares carrying a door
     * @param windowSquares "x,y" keys of squares carrying a window
     * @param bc            building class, chooses domestic vs commercial fit-out
     * @param rng           seeded per building
     */
    public static void place(CellData cell, TilePalette pal,
                             List<BuildingPlan.Room> planned, List<Integer> idx,
                             Set<String> doorSquares, Set<String> windowSquares,
                             BuildingClass bc, Random rng) {

        FurniturePlacer fp = new FurniturePlacer(cell, pal, rng, doorSquares, windowSquares);

        // One tier per building; each room shifts at most one step from it, so
        // a building reads as a single establishment rather than a jumble.
        Density buildingTier = Density.roll(rng);

        for (int i = 0; i < planned.size(); i++) {
            BuildingPlan.Room r = planned.get(i);
            int ri = GisCells.roomIndexOf(idx, i);
            if (ri < 0) continue;

            FurnitureProfile profile = FurnitureProfile.forRoom(r.type(), bc);
            if (profile == null) continue;

            fp.furnish(r, ri, profile, buildingTier.jitter(rng));
        }
    }

    // ---------------------------------------------------------------
    // One room
    // ---------------------------------------------------------------

    private void furnish(BuildingPlan.Room r, int ri,
                         FurnitureProfile profile, Density density) {
        rx = r.x(); ry = r.y(); rw = r.w(); rh = r.h(); roomId = ri;
        if (rw < 3 || rh < 3) return;   // too small to hold anything with clearance
        tinyRoom = rw * rh < 25;

        grid = new byte[rw][rh];
        placed.clear();
        lock.clear();
        claimed.clear();
        markDoorApproaches();

        switch (profile.strategy) {
            case PERIMETER -> perimeter(profile.activities, density);
            case GRID      -> gridLayout(profile, density);
            case ROWS      -> rows(profile.activities, density);
            case CENTRE    -> centre(profile.activities, density);
            case ZONED     -> zoned(profile, density);
            case STALLS    -> stalls(profile.activities, density);
            case STAMP     -> stampLayout(profile, density);
            case AUTHORED  -> authoredLayout(profile, density);
        }

        if (!tinyRoom) decor(profile.decorBudget, density);
        enforceCirculation();
    }

    /**
     * The square inside each door, and the one past it, are never filled.
     * A door that opens onto a filing cabinet is worse than an empty room.
     */
    private void markDoorApproaches() {
        for (int lx = 0; lx < rw; lx++) {
            for (int ly = 0; ly < rh; ly++) {
                int wx = rx + lx, wy = ry + ly;
                if (!doorSquares.contains(wx + "," + wy)) continue;
                mark(lx, ly, KEEP_CLEAR);
                // Clearance around the door: 2 squares in large rooms so
                // furniture cannot block the entrance; 1 square in tiny rooms
                // so approach marking doesn't consume the entire perimeter.
                int clearance = tinyRoom ? 1 : 2;
                for (int[] d : new int[][]{{0,1},{0,-1},{1,0},{-1,0}}) {
                    mark(lx + d[0], ly + d[1], KEEP_CLEAR);
                    if (clearance > 1)
                        mark(lx + d[0]*2, ly + d[1]*2, KEEP_CLEAR);
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Strategies
    // ---------------------------------------------------------------

    /**
     * Anchors against the walls, the middle left clear.
     *
     * Required activities (chance == 1.0) FILL their assigned wall line —
     * the anchor is repeated with spacing until the line is exhausted. That
     * is what stops a large room getting one desk and nothing else. Optional
     * activities land once if they fire at all.
     */
    private void perimeter(List<Activity> activities, Density density) {
        // Each line: {startX, startY, stepX, stepY, facing}
        // Objects sit ON the wall square (ly=0 for N wall, ly=rh-1 for S wall).
        // Facing is INWARD so the object faces the centre of the room:
        //   N wall -> facing S (object backs the north wall, faces south)
        //   S wall -> facing N
        //   W wall -> facing E
        //   E wall -> facing W
        int[][] lines = {
                {1, 0,      1, 0, 'S'},   // N wall, facing south (inward)
                {1, rh - 1, 1, 0, 'N'},   // S wall, facing north (inward)
                {0, 1,      0, 1, 'E'},   // W wall, facing east  (inward)
                {rw - 1, 1, 0, 1, 'W'},   // E wall, facing west  (inward)
        };

        int line = rng.nextInt(4);
        int activityCount = 0;
        for (Activity a : activities) {
            if (tinyRoom && activityCount >= 2) break;
            if (!fires(a, density)) continue;
            activityCount++;

            boolean done = false;
            for (int attempt = 0; attempt < 4 && !done; attempt++) {
                int[] L = lines[(line + attempt) % 4];
                if (a.chance() >= 1.0 && !tinyRoom) {
                    done = fillLine(a, L, density);
                } else {
                    done = placeAlongLine(a, L, density);
                }
            }
            line = (line + 1) % 4;
        }
    }

    /**
     * Fill a wall line by repeating the anchor with density-driven spacing.
     *
     * This is the fix for large empty rooms: a required activity (desk,
     * shelves, counter) runs the full length of its wall at the density
     * tier's cadence rather than landing once and stopping.
     */
    private boolean fillLine(Activity a, int[] L, Density density) {
        int sx = L[0], sy = L[1], dx = L[2], dy = L[3];
        char facing = (char) L[4];
        int len = dx != 0 ? rw - 1 : rh - 1;
        if (len < 1) return false;

        // Spacing between anchors: 1 for dense, 2 for normal, 3 for sparse.
        int step = Math.max(1, density.aisle + 1);
        boolean placed = false;

        for (int off = 0; off < len - (a.runLength() - 1); off += step) {
            if (rng.nextDouble() < density.skip) continue;
            int lx = sx + dx * off, ly = sy + dy * off;
            if (!runFits(lx, ly, dx, dy, a.runLength())) continue;

            for (int k = 0; k < a.runLength(); k++) {
                int ax = lx + dx * k, ay = ly + dy * k;
                String tile = resolve(a.anchorRole(), facing);
                if (tile == null) break;
                roleFacing = facing;
                if (put(ax, ay, tile, isSurface(a.anchorRole()), a.anchorRole())) {
                    satellites(a, ax, ay, facing, density);
                    placed = true;
                }
            }
        }
        return placed;
    }

    /**
     * Place one activity along one interior wall line.
     * Returns true when the anchor (and its run) landed.
     */
    private boolean placeAlongLine(Activity a, int[] L, Density density) {
        int sx = L[0], sy = L[1], dx = L[2], dy = L[3];
        char facing = (char) L[4];
        int len = dx != 0 ? rw - 1 : rh - 1;
        if (len < a.runLength()) return false;

        int start = rng.nextInt(Math.max(1, len - a.runLength() + 1));
        for (int off = 0; off < len - a.runLength() + 1; off++) {
            int i = (start + off) % Math.max(1, len - a.runLength() + 1);
            int lx = sx + dx * i, ly = sy + dy * i;

            if (!runFits(lx, ly, dx, dy, a.runLength())) continue;

            for (int k = 0; k < a.runLength(); k++) {
                int ax = lx + dx * k, ay = ly + dy * k;
                if (rng.nextDouble() < density.skip && k > 0) continue;
                String tile = resolve(a.anchorRole(), facing);
                if (tile == null) return false;
                roleFacing = facing;
                if (!put(ax, ay, tile, isSurface(a.anchorRole()), a.anchorRole()))
                    continue;
                satellites(a, ax, ay, facing, density);
            }
            return true;
        }
        return false;
    }

    /**
     * A unit block repeated across the floor with aisles — the cubicle farm.
     *
     * Small rooms (< 8 tiles either dimension): everything goes against walls.
     * Large rooms: perimeter fills the walls first, then grid units float in
     * the centre. This is what a real open-plan office looks like — desks in
     * clusters in the middle, shelves and extras around the edge.
     *
     * The grid starts 3 tiles from each wall so the perimeter strip always
     * has room to exist independently.
     */
    private static final int GRID_ROOM_MIN = 8;
    private static final int GRID_MARGIN   = 3;

    private void gridLayout(FurnitureProfile profile, Density density) {
        // Always fill the walls first — extras (shelves, water cooler) belong
        // on the perimeter regardless of room size.
        perimeter(profile.activities, density);

        if (profile.gridUnit == null) return;

        if (rw < GRID_ROOM_MIN || rh < GRID_ROOM_MIN) {
            // Small room: grid unit also goes on the perimeter.
            perimeter(List.of(profile.gridUnit), density);
            return;
        }

        // Large room: clusters in the centre.
        int step = density.gridUnit + density.aisle;
        int startX = GRID_MARGIN, startY = GRID_MARGIN;
        int endX   = rw - GRID_MARGIN, endY = rh - GRID_MARGIN;

        for (int uy = startY; uy + density.gridUnit <= endY; uy += step) {
            for (int ux = startX; ux + density.gridUnit <= endX; ux += step) {
                if (rng.nextDouble() < density.skip) continue;
                char facing = 'N';
                int ax = ux, ay = uy;
                if (!free(ax, ay)) continue;
                String tile = resolve(profile.gridUnit.anchorRole(), facing);
                if (tile == null) continue;
                roleFacing = facing;
                if (!put(ax, ay, tile, isSurface(profile.gridUnit.anchorRole()),
                        profile.gridUnit.anchorRole())) continue;
                satellites(profile.gridUnit, ax, ay, facing, density);
            }
        }
    }

    /**
     * Parallel runs along the room's long axis with aisles between.
     *
     * This is what makes a warehouse look like a warehouse: racking down the
     * middle in rows, not hugging the walls. Row spacing is the density tier,
     * so the same profile gives a near-empty depot or wall-to-wall racking.
     */
    private void rows(List<Activity> activities, Density density) {
        if (activities.isEmpty()) return;
        Activity a = activities.get(0);
        if (!fires(a, density)) return;

        boolean alongX = rw >= rh;
        int gap = density.rowGap;

        // Rows alternate which way they face so that a row and its neighbour
        // look INTO the aisle between them, never at each other's backs. Two
        // shelving units face to face with no gap is the thing that read as
        // wrong in game; this is the rule that prevents it.
        if (alongX) {
            int rowNo = 0;
            for (int ly = 2; ly < rh - 2; ly += gap, rowNo++) {
                char f = (rowNo % 2 == 0) ? 'S' : 'N';
                for (int lx = 2; lx < rw - 2; lx++) {
                    if (rng.nextDouble() < density.skip) continue;
                    if (!free(lx, ly)) continue;
                    String tile = resolve(a.anchorRole(), f);
                    if (tile == null) return;
                    roleFacing = f;
                    put(lx, ly, tile, false, a.anchorRole());
                }
            }
        } else {
            int rowNo = 0;
            for (int lx = 2; lx < rw - 2; lx += gap, rowNo++) {
                char f = (rowNo % 2 == 0) ? 'E' : 'W';
                for (int ly = 2; ly < rh - 2; ly++) {
                    if (rng.nextDouble() < density.skip) continue;
                    if (!free(lx, ly)) continue;
                    String tile = resolve(a.anchorRole(), f);
                    if (tile == null) return;
                    roleFacing = f;
                    put(lx, ly, tile, false, a.anchorRole());
                }
            }
        }

        // Crates, boxes and pallets scatter across whatever floor the racking
        // left, rather than lining up against the walls — a storeroom has
        // stock stacked in the gaps, not a tidy perimeter.
        if (activities.size() > 1)
            scatter(activities.subList(1, activities.size()), density);
    }

    /**
     * Drop loose objects on free floor.
     *
     * For things with no front and no wall affinity: crates, pallets, boxes.
     * They cluster slightly by preferring a square next to something already
     * placed, because stock gets stacked against stock.
     */
    private void scatter(List<Activity> activities, Density density) {
        for (Activity a : activities) {
            if (!fires(a, density)) continue;
            int want = 1 + rng.nextInt(3 + (density == Density.DENSE ? 3 : 0));
            for (int n = 0; n < want; n++) {
                String tile = resolve(a.anchorRole(), 'N');
                if (tile == null) break;
                int[] spot = scatterSpot();
                if (spot == null) break;
                roleFacing = 'N';
                put(spot[0], spot[1], tile, false, a.anchorRole());
            }
        }
    }

    /** A free square, preferring one already touching something. */
    private int[] scatterSpot() {
        List<int[]> beside = new ArrayList<>();
        List<int[]> open = new ArrayList<>();
        for (int lx = 1; lx < rw - 1; lx++)
            for (int ly = 1; ly < rh - 1; ly++) {
                if (!free(lx, ly)) continue;
                if (touchesPlaced(lx, ly)) beside.add(new int[]{lx, ly});
                else open.add(new int[]{lx, ly});
            }
        List<int[]> pool = (!beside.isEmpty() && rng.nextDouble() < 0.7)
                ? beside : (open.isEmpty() ? beside : open);
        if (pool.isEmpty()) return null;
        return pool.get(rng.nextInt(pool.size()));
    }

    private boolean touchesPlaced(int lx, int ly) {
        for (int[] d : new int[][]{{0,1},{0,-1},{1,0},{-1,0}}) {
            int nx = lx + d[0], ny = ly + d[1];
            if (!inRoom(nx, ny)) continue;
            if (grid[nx][ny] == TAKEN || grid[nx][ny] == SURFACE) return true;
        }
        return false;
    }

    /**
     * A row of toilet cubicles.
     *
     * Measured from vanilla at McCoy Logging (cell 40_36, room 14): a stall is
     * ONE square. The toilet sits against the back wall, a WallN divider
     * separates it from the next stall, and a DoorWallW plus door closes the
     * open side. Nobody wants to watch anybody else use the toilet, so the
     * dividers are the point — without them the row is just toilets in a line.
     *
     * The stalls run along the room's longest wall, back to that wall, with
     * the doors opening into the room.
     */
    private void stalls(List<Activity> activities, Density density) {
        if (activities.isEmpty()) return;

        boolean alongX = rw >= rh;
        char back = alongX ? 'N' : 'W';

        String divider     = pal.pickFrom(alongX ? "stall_wall_W"     : "stall_wall_N",     rng);
        String doorWall    = pal.pickFrom(alongX ? "stall_doorwall_N" : "stall_doorwall_W", rng);
        String door        = pal.pickFrom(alongX ? "stall_door_N"     : "stall_door_W",     rng);
        // Always use the plastic stall toilet from fixtures_bathroom_02.
        // Never use the activity anchor role here — that could resolve to
        // a regular toilet or a urinal from fixtures_bathroom_01.
        String stallToilet = pal.pickFrom("stall_toilet", rng);

        int n = 0;
        if (alongX) {
            int sy = 1;
            for (int lx = 1; lx < rw - 1; lx++) {
                if (!free(lx, sy)) continue;
                if (stallToilet == null) break;
                roleFacing = back; currentRole = "stall_toilet";
                if (!put(lx, sy, stallToilet, false, "stall_toilet")) continue;
                if (n > 0 && divider != null) stack(lx, sy, divider);
                if (doorWall != null)         stack(lx, sy, doorWall);
                if (door != null)             stack(lx, sy, door);
                n++;
            }
        } else {
            int sx = 1;
            for (int ly = 1; ly < rh - 1; ly++) {
                if (!free(sx, ly)) continue;
                if (stallToilet == null) break;
                roleFacing = back; currentRole = "stall_toilet";
                if (!put(sx, ly, stallToilet, false, "stall_toilet")) continue;
                if (n > 0 && divider != null) stack(sx, ly, divider);
                if (doorWall != null)         stack(sx, ly, doorWall);
                if (door != null)             stack(sx, ly, door);
                n++;
            }
        }
    }

    /** One focal object in the middle with clearance — a dining table. */
    private void centre(List<Activity> activities, Density density) {
        if (activities.isEmpty()) return;
        Activity a = activities.get(0);
        int cx = rw / 2, cy = rh / 2;
        if (free(cx, cy)) {
            String tile = resolve(a.anchorRole(), 'N');
            if (tile != null) {
                roleFacing = 'N';
                put(cx, cy, tile, isSurface(a.anchorRole()), a.anchorRole());
                satellites(a, cx, cy, 'N', density);
            }
        }
        if (activities.size() > 1)
            perimeter(activities.subList(1, activities.size()), density);
    }

    /**
     * Regions with their own strategies.
     *
     * The room splits along its long axis by floorShare. A breakroom is
     * perimeter along one end for the kitchen fittings, grid in the rest for
     * tables — which is what a real breakroom looks like.
     */
    private void zoned(FurnitureProfile profile, Density density) {
        boolean splitX = rw >= rh;
        int cursor = 0;
        int total = splitX ? rw : rh;

        for (Zone z : profile.zones) {
            int span = Math.max(3, (int) Math.round(total * z.floorShare()));
            if (cursor + span > total) span = total - cursor;
            if (span < 3) break;

            int savedX = rx, savedY = ry, savedW = rw, savedH = rh;
            byte[][] savedGrid = grid;

            // Re-window this placer onto the zone, reusing the parent grid so
            // occupancy is shared across zones.
            if (splitX) { rx = savedX + cursor; rw = span; }
            else        { ry = savedY + cursor; rh = span; }
            grid = subGrid(savedGrid, splitX, cursor, span);

            switch (z.strategy()) {
                case PERIMETER -> perimeter(z.activities(), density);
                case GRID -> {
                    // A zone's grid unit is its first activity.
                    if (!z.activities().isEmpty()) {
                        FurnitureProfile sub = FurnitureProfile.grid(
                                z.activities().get(0), 0);
                        gridLayout(sub, density);
                    }
                }
                case ROWS   -> rows(z.activities(), density);
                case CENTRE -> centre(z.activities(), density);
                case STALLS -> stalls(z.activities(), density);
                case ZONED  -> { /* no nesting */ }
            }

            writeBack(savedGrid, grid, splitX, cursor, span);
            rx = savedX; ry = savedY; rw = savedW; rh = savedH;
            grid = savedGrid;
            cursor += span;
        }
    }

    private byte[][] subGrid(byte[][] parent, boolean splitX, int cursor, int span) {
        byte[][] sub = splitX ? new byte[span][rh] : new byte[rw][span];
        for (int x = 0; x < sub.length; x++)
            for (int y = 0; y < sub[0].length; y++)
                sub[x][y] = splitX ? parent[cursor + x][y] : parent[x][cursor + y];
        return sub;
    }

    private void writeBack(byte[][] parent, byte[][] sub, boolean splitX,
                           int cursor, int span) {
        for (int x = 0; x < sub.length; x++)
            for (int y = 0; y < sub[0].length; y++) {
                if (splitX) parent[cursor + x][y] = sub[x][y];
                else        parent[x][cursor + y] = sub[x][y];
            }
    }

    // ---------------------------------------------------------------
    // Satellites
    // ---------------------------------------------------------------

    /**
     * Place the things that come with an anchor.
     *
     * This is where a room stops being a warehouse of identical objects. A
     * desk with a chair pulled up to it and drawers beside it reads as
     * somebody's workspace; the same desk alone reads as stock.
     */
    private void satellites(Activity a, int ax, int ay, char facing, Density density) {
        for (Satellite s : a.satellites()) {
            // Required satellites (chance == 1.0) are never skipped by density.
            // A desk without a chair is not a desk; a table without chairs is not a table.
            boolean required = s.chance() >= 1.0;
            if (!required && rng.nextDouble() > s.chance() * density.optionalScale) continue;

            switch (s.rel()) {
                case ON_TOP -> {
                    // Same square, stacked. Mark the square as a surface so
                    // the decor pass can find somewhere to put a small plant.
                    String tile = resolve(s.role(), facing);
                    if (tile != null) {
                        stack(ax, ay, tile);
                        if (inRoom(ax, ay) && grid[ax][ay] == TAKEN)
                            grid[ax][ay] = SURFACE;
                    }
                }
                case ABOVE_ON_WALL -> {
                    // The wall square the anchor leans against.
                    int[] w = wallSquareFor(ax, ay, facing);
                    if (w != null) {
                        String tile = resolve(s.role(), facing);
                        if (tile != null) stackWorld(w[0], w[1], tile);
                    }
                }
                case IN_FRONT -> {
                    // A chair pulled up to a desk faces BACK toward it — and
                    // the chair itself must have clear floor behind, or nobody
                    // can walk up and sit down.
                    int[] f = frontOf(ax, ay, facing);
                    if (f != null && free(f[0], f[1])) {
                        char back = opposite(facing);
                        String tile = resolve(s.role(), back);
                        if (tile != null) {
                            roleFacing = back;
                            put(f[0], f[1], tile, false, s.role());
                        }
                    }
                }
                case BESIDE -> {
                    int[] b = besideOf(ax, ay, facing);
                    if (b != null && free(b[0], b[1])) {
                        String tile = resolve(s.role(), facing);
                        if (tile != null) {
                            roleFacing = facing;
                            put(b[0], b[1], tile, false, s.role());
                        }
                    }
                }
                case ADJACENT -> {
                    int[] n = anyFreeNeighbour(ax, ay);
                    if (n != null) {
                        String tile = resolve(s.role(), facing);
                        if (tile != null) {
                            roleFacing = facing;
                            put(n[0], n[1], tile, false, s.role());
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Decor
    // ---------------------------------------------------------------

    /**
     * The non-functional things that say a human lived here.
     *
     * Decor is placed by PREDICATE over the room state, not by activity: a
     * plant belongs wherever there is light and floor, not to any particular
     * task. The predicates cluster things naturally — plants to windows,
     * clutter to surfaces, rugs to open middle floor — which is what stops
     * decor reading as noise.
     */
    private void decor(int budget, Density density) {
        int n = (int) Math.round(budget * density.optionalScale);
        for (int i = 0; i < n; i++) {
            switch (rng.nextInt(3)) {
                case 0 -> plantNearWindow();
                case 1 -> rugOnOpenFloor();
                case 2 -> plantOnSurface();
            }
        }
    }

    /** Potted plants want light, so they go within two squares of a window. */
    private void plantNearWindow() {
        if (rw * rh < 30) return;   // room too small — every square is "near a window"
        String tile = pal.pickFrom("plant_floor", rng);
        if (tile == null) return;

        // Plants want light, so a square near a window wins. If the room has
        // no window within reach, a corner still beats no plant at all.
        List<int[]> candidates = new ArrayList<>();
        List<int[]> fallback = new ArrayList<>();
        for (int lx = 1; lx < rw - 1; lx++)
            for (int ly = 1; ly < rh - 1; ly++) {
                if (!free(lx, ly)) continue;
                if (nearWindow(lx, ly, 3)) candidates.add(new int[]{lx, ly});
                else fallback.add(new int[]{lx, ly});
            }
        if (candidates.isEmpty()) candidates = fallback;
        if (candidates.isEmpty()) return;
        int[] c = candidates.get(rng.nextInt(candidates.size()));
        put(c[0], c[1], tile, false, null);
    }

    /** Rugs go on open floor toward the middle. */
    private void rugOnOpenFloor() {
        String tile = pal.pickFrom("rug", rng);
        if (tile == null) return;
        int cx = rw / 2, cy = rh / 2;
        for (int attempt = 0; attempt < 8; attempt++) {
            int lx = clamp(cx + rng.nextInt(5) - 2, 1, rw - 2);
            int ly = clamp(cy + rng.nextInt(5) - 2, 1, rh - 2);
            if (!free(lx, ly)) continue;
            // A rug is a FLOOR overlay, not an object: it does not block and
            // it does not take the square. Append and leave the grid alone.
            GisCells.appendTile(cell, rx + lx, ry + ly, cell.tileIndex(tile), roomId);
            return;
        }
    }

    /** Small plants sit on desks and counters. */
    private void plantOnSurface() {
        String tile = pal.pickFrom("plant_table", rng);
        if (tile == null) return;
        List<int[]> surfaces = new ArrayList<>();
        for (int lx = 0; lx < rw; lx++)
            for (int ly = 0; ly < rh; ly++)
                if (grid[lx][ly] == SURFACE) surfaces.add(new int[]{lx, ly});
        if (surfaces.isEmpty()) return;
        int[] s = surfaces.get(rng.nextInt(surfaces.size()));
        stack(s[0], s[1], tile);
    }

    private boolean nearWindow(int lx, int ly, int radius) {
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++) {
                int wx = rx + lx + dx, wy = ry + ly + dy;
                if (windowSquares.contains(wx + "," + wy)) return true;
            }
        return false;
    }

    // ---------------------------------------------------------------
    // Circulation
    // ---------------------------------------------------------------

    /**
     * Every free square must be reachable from a door.
     *
     * PZ has real pathing and zombies have to navigate these rooms. A sealed
     * corner is a bug, not a quirk — so after placement, flood fill from the
     * door approach squares and clear whatever is cutting the room in two.
     */
    private void enforceCirculation() {
        int[] start = firstKeepClear();
        if (start == null) return;   // no door in this room; nothing to check

        boolean[][] seen = new boolean[rw][rh];
        Deque<int[]> q = new ArrayDeque<>();
        q.add(start);
        seen[start[0]][start[1]] = true;

        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : new int[][]{{0,1},{0,-1},{1,0},{-1,0}}) {
                int nx = c[0] + d[0], ny = c[1] + d[1];
                if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue;
                if (seen[nx][ny]) continue;
                if (grid[nx][ny] == TAKEN || grid[nx][ny] == SURFACE) continue;
                seen[nx][ny] = true;
                q.add(new int[]{nx, ny});
            }
        }

        // Anything placed that borders an unreachable free square is the
        // thing sealing it in. Remove the most recent such placement.
        for (int i = placed.size() - 1; i >= 0; i--) {
            int[] p = placed.get(i);
            if (!sealsRegion(p[0], p[1], seen)) continue;
            grid[p[0]][p[1]] = FREE;
            // The tile itself stays written — removing it from the square
            // would mean tracking stack positions. Clearing the grid entry is
            // enough for the next pass's reachability, and a single stray
            // object is a smaller defect than a sealed room.
            return;
        }
    }

    private boolean sealsRegion(int lx, int ly, boolean[][] seen) {
        for (int[] d : new int[][]{{0,1},{0,-1},{1,0},{-1,0}}) {
            int nx = lx + d[0], ny = ly + d[1];
            if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue;
            if (grid[nx][ny] == FREE && !seen[nx][ny]) return true;
        }
        return false;
    }

    private int[] firstKeepClear() {
        for (int lx = 0; lx < rw; lx++)
            for (int ly = 0; ly < rh; ly++)
                if (grid[lx][ly] == KEEP_CLEAR) return new int[]{lx, ly};
        return null;
    }

    // ---------------------------------------------------------------
    // Tile resolution
    // ---------------------------------------------------------------

    /**
     * Directional tile overrides for roles the palette doesn't split by facing.
     *
     * The build log shows `television=12` with no television_N/S/E/W sub-groups,
     * so resolve() always falls back to the plain "television" pool and picks a
     * random direction. We short-circuit that with hardcoded sprites here.
     *
     * Each sheet was decoded by cross-referencing every facing label already
     * recorded in FurnitureSet against the others. Where a label disagreed with
     * a pattern that several independent labels supported, the pattern won.
     *
     * appliances_television_01 — order S, E, N, W at multiples of 4; the
     *   variant used here is the group at base 4.
     *
     *   _6 = NORTH and _7 = WEST are established by inversion, which is a
     *   sounder measurement than reading a 32-pixel sprite by eye. The seat's
     *   facing comes from the palette's own couch_/chair_soft_ direction groups
     *   and is therefore authoritative; livingroomLayout puts the television and
     *   the seat on opposite walls pointing at each other by construction. So
     *   when a build shows the screen aimed EXACTLY away from the seat, the
     *   requested facing was right and the sprite emitted for it renders as the
     *   opposite direction. Two builds did exactly that: _6, asked for south,
     *   drew north; _7, asked for east, drew west.
     *
     *   Two earlier tables here were wrong. The first trusted a note claiming
     *   _7 was FacingS, which had been inferred from the NAME of the old set
     *   LIVINGROOM_TV_N rather than from a picture. The second replaced it with
     *   a reading taken off a screenshot, which came out backwards.
     *
     *   _6 and _7 are confirmed; _4 (S) and _5 (E) complete the group and are
     *   inferred from the S, E, N, W order that appliances_refrigeration_01
     *   uses. livingroomLayout prefers to stand the television against the
     *   SOUTH or EAST wall, where it faces north or west and so uses only the
     *   confirmed pair. The inferred pair is a fallback that in practice never
     *   comes up — see the sprite-mix count in the test harness.
     *
     * appliances_cooking_01 — 4-cycle W, N, E, S; variants at base 10, 18, 26
     *   (every index congruent to 2 mod 4). Supported by _10 FacingW
     *   (HEAVY_KITCHEN), _21 FacingS and _27 FacingN (LARGE_APPLIANCE_BANK),
     *   which together predict _13 FacingS — matching KITCHEN_COUNTER_RUN_N.
     *
     * fixtures_sinks_01 — 4-cycle N, E, S, W; chrome group at base 7.
     *   _7 FacingN (SINK_MIRROR_UNIT), _9 FacingS and _10 FacingW (counter runs).
     *
     * fixtures_counters_01 — EIGHT sprites per group, not four: each facing is a
     *   (corner, straight) pair, in facing order N, E, S, W. So the straight
     *   counter sits at base+1 (N), +3 (E), +5 (S), +7 (W). Supported by _40
     *   corner FacingN with _41 straight FacingN and _47 straight FacingW
     *   (base 40); _53 FacingS and _55 FacingW (oak, base 48); _33 FacingN,
     *   _35 FacingE and _37 FacingS (steel, base 32). Oak group used here.
     *
     * furniture_shelving_01 — order S, E, W, N at multiples of 4.
     *   This sheet is the reason "shelves" needs an override at all. The
     *   palette's shelves_N/S/E/W groups hold only five tiles each and MIX
     *   free-standing bookcases with wall-mounted planks — _20 "Corner A" and
     *   _21 "Middle" are wall pieces. Resolving the role could therefore hand
     *   back a wall sprite, which placed on an ordinary floor square draws as a
     *   plank hanging in mid-air with nothing behind it. Only the floor-standing
     *   bookcases at _40.._47 are named here.
     *   Order established by four recorded labels across two groups: _40 FacingS
     *   and _41 FacingE (Oakwood, LIVINGROOM_SHELVES_N and POSH_OFFICE), _44
     *   south-facing and _47 north-facing (LIBRARY_SHELVES, where the two stand
     *   back to back so their facings are fixed by the geometry). Offsets 0 and
     *   1 are S and E, offset 3 is N, leaving W at offset 2.
     *   S, E and N are taken from confirmed sprites — mixing the Oakwood and
     *   Library models across facings is invisible because a room only ever
     *   gets one shelf. W (_42) is inferred, so placeShelfAgainstWall tries the
     *   east wall (the only one that faces west) last.
     *
     * appliances_refrigeration_01 — order S, E, N, W at multiples of 4.
     *   All seven recorded labels agree: base+1 is EAST (_1, _9, _49), base+3
     *   is WEST (_3, _51, _31) and base+0 is SOUTH (_28), leaving base+2 NORTH.
     *   An earlier pass here assumed base+0 was north instead of deriving it
     *   from _28, which swapped N and S and stood every fridge on a north or
     *   south wall with its back to the room. _28 FacingS was right all along.
     *
     * Array order: [N, S, E, W]  — matches facingIdx().
     */
    private static final java.util.Map<String, String[]> DIRECTIONAL_OVERRIDES =
            java.util.Map.of(
                "television", new String[]{
                    "appliances_television_01_6",   // N = base 4 + 2 (confirmed)
                    "appliances_television_01_4",   // S = base 4 + 0 (inferred)
                    "appliances_television_01_5",   // E = base 4 + 1 (inferred)
                    "appliances_television_01_7"    // W = base 4 + 3 (confirmed)
                },
                "oven", new String[]{
                    "appliances_cooking_01_11",     // N
                    "appliances_cooking_01_13",     // S (confirmed)
                    "appliances_cooking_01_12",     // E
                    "appliances_cooking_01_10"      // W (confirmed)
                },
                "counter", new String[]{
                    "fixtures_counters_01_49",      // N = base 48 + 1
                    "fixtures_counters_01_53",      // S = base 48 + 5 (confirmed)
                    "fixtures_counters_01_51",      // E = base 48 + 3
                    "fixtures_counters_01_55"       // W = base 48 + 7 (confirmed)
                },
                "sink", new String[]{
                    "fixtures_sinks_01_7",          // N (confirmed)
                    "fixtures_sinks_01_9",          // S (confirmed)
                    "fixtures_sinks_01_8",          // E
                    "fixtures_sinks_01_10"          // W (confirmed)
                },
                "shelves", new String[]{
                    "furniture_shelving_01_47",     // N (confirmed)
                    "furniture_shelving_01_40",     // S (confirmed)
                    "furniture_shelving_01_41",     // E (confirmed)
                    "furniture_shelving_01_42"      // W (inferred)
                },
                "fridge", new String[]{
                    "appliances_refrigeration_01_30",   // N = base 28 + 2
                    "appliances_refrigeration_01_28",   // S = base 28 + 0 (confirmed)
                    "appliances_refrigeration_01_29",   // E = base 28 + 1
                    "appliances_refrigeration_01_31"    // W = base 28 + 3 (confirmed)
                }
            );

    private static int facingIdx(char f) {
        return switch (f) { case 'N' -> 0; case 'S' -> 1; case 'E' -> 2; default -> 3; };
    }

    /**
     * A role plus a facing to a concrete tile name.
     *
     * Roles that come in facing variants are stored as "role_N" and so on, so
     * a bare role name is tried with the facing suffix first and then plain.
     * A role with no tiles resolves to null and the object is skipped — one
     * missing object, not a broken room.
     */
    private String resolve(String role, char facing) {
        String group = groupFor(role, facing);
        if (group == null) return null;

        // The lock is keyed on the ROLE FAMILY, not the facing, so a room's
        // shelves stay one family while still turning to face the right way.
        String family = familyOf(role);
        String locked = lock.get(family);

        if (locked != null && rng.nextDouble() > LOCK_DRIFT) {
            // Same visual family, correct facing: swap the locked tile's
            // facing suffix for this one where the group offers it.
            String sameFamily = matchFamily(group, locked);
            if (sameFamily != null) return sameFamily;
        }

        String t = pal.pickFrom(group, rng);
        if (t != null && locked == null) lock.put(family, t);
        return t;
    }

    /** The populated group for a role at a facing, or null. */
    private String groupFor(String role, char facing) {
        // Role already names a facing (the profile said chair_office_N).
        if (role.length() > 2 && role.charAt(role.length() - 2) == '_'
                && "NSEW".indexOf(role.charAt(role.length() - 1)) >= 0) {
            String base = role.substring(0, role.length() - 2);
            if (!pal.group(base + "_" + facing).isEmpty()) return base + "_" + facing;
            if (!pal.group(role).isEmpty()) return role;
            return null;
        }
        if (!pal.group(role + "_" + facing).isEmpty()) return role + "_" + facing;
        if (!pal.group(role).isEmpty()) return role;
        return null;
    }

    /** Role without its facing suffix — the key the lock uses. */
    private static String familyOf(String role) {
        if (role.length() > 2 && role.charAt(role.length() - 2) == '_'
                && "NSEW".indexOf(role.charAt(role.length() - 1)) >= 0)
            return role.substring(0, role.length() - 2);
        return role;
    }

    /**
     * A tile from `group` drawn from the same sheet as `locked`, so the room
     * keeps one furniture family across facings. Null when the group has
     * nothing from that sheet.
     */
    private String matchFamily(String group, String locked) {
        String sheet = sheetOf(locked);
        List<String> candidates = new ArrayList<>();
        for (String n : pal.group(group))
            if (sheet.equals(sheetOf(n))) candidates.add(n);
        if (candidates.isEmpty()) return null;
        return candidates.get(rng.nextInt(candidates.size()));
    }

    /** Tile name without its trailing index — the sheet it came from. */
    private static String sheetOf(String tile) {
        int us = tile.lastIndexOf('_');
        return us < 0 ? tile : tile.substring(0, us);
    }

    /** Roles whose objects can carry things on top. */
    private boolean isSurface(String role) {
        return role.startsWith("counter") || role.startsWith("desk")
                || role.startsWith("table") || role.startsWith("workbench")
                || role.startsWith("drawers");
    }

    // ---------------------------------------------------------------
    // Stamp layout
    // ---------------------------------------------------------------

    /**
     * Fill the room by stamping measured vanilla sets repeatedly across the
     * floor with density-driven aisle gaps between stamps.
     *
     * Algorithm:
     *   1. Pick a primary set for this room type. If the room is large enough,
     *      attempt a secondary complementary set in whatever space remains.
     *   2. Walk a grid of origin points across the room interior, spaced by
     *      (set.w + aisle) × (set.h + aisle). At each origin attempt a stamp;
     *      skip if the density roll fires or the origin is blocked.
     *   3. Run the normal perimeter pass for any profile activities (wall extras:
     *      shelves, watercooler, television) after the stamps are placed.
     *   4. Decor runs as normal after this method returns.
     */
    private void stampLayout(FurnitureProfile profile, Density density) {
        // A living room has one rule that the generic wall-run passes cannot
        // express: the television and the seat must face each other. Placing
        // them independently on whichever walls happened to be free is what put
        // a chair at 90 degrees to the screen. Author the pair instead.
        if ("livingroom".equals(profile.stampRoomType)) {
            livingroomLayout(density);
            return;
        }

        // All STAMP rooms: separate wall-run sets from floor sets.
        List<FurnitureSet> wallRunSets = new ArrayList<>();
        List<FurnitureSet> floorSets   = new ArrayList<>();
        for (FurnitureSet s : FurnitureSet.forRoom(profile.stampRoomType, rw, rh)) {
            if (s.wallRun) wallRunSets.add(s);
            else           floorSets.add(s);
        }

        if (!wallRunSets.isEmpty()) {
            // Separate sets by orientation so we never place a horizontal
            // set (w>h) on a W/E wall or a vertical set (h>w) on a N/S wall.
            // Square sets (w==h, e.g. KITCHEN_FRIDGE 1×1) go on any wall.
            List<FurnitureSet> hSets = new ArrayList<>(); // horizontal → N or S wall
            List<FurnitureSet> vSets = new ArrayList<>(); // vertical   → W or E wall
            List<FurnitureSet> sSets = new ArrayList<>(); // square     → any wall
            for (FurnitureSet s : wallRunSets) {
                if      (s.w > s.h) hSets.add(s);
                else if (s.h > s.w) vSets.add(s);
                else                sSets.add(s);
            }
            // Sort each bucket largest-first so the most complete run goes first.
            hSets.sort((a, b) -> (b.w * b.h) - (a.w * a.h));
            vSets.sort((a, b) -> (b.w * b.h) - (a.w * a.h));

            char primaryWall = ' ';
            char vSetWall    = ' ';  // which wall the vSet landed on (for step 4 guard)

            // Sort wall candidates: door-free walls first, door walls as fallback.
            // This prevents a counter run landing directly against a doorway.
            java.util.function.Function<char[], char[]> sortWalls = walls -> {
                char[] noDoor = new char[4]; int ni = 0;
                char[] door   = new char[4]; int di = 0;
                for (char w : walls) { if (wallHasDoor(w)) door[di++] = w; else noDoor[ni++] = w; }
                char[] out = new char[ni + di];
                System.arraycopy(noDoor, 0, out, 0, ni);
                System.arraycopy(door,   0, out, ni, di);
                return java.util.Arrays.copyOf(out, ni + di);
            };

            // Every pass below goes through stampWallRunExclusive, so a fixture
            // class claimed by an earlier pass blocks every later one. That is
            // what keeps a kitchen to a single counter run and a single fridge
            // no matter how many of these passes find a free wall.

            // 1. Try to place the largest horizontal run on N/S (door-free first).
            if (!hSets.isEmpty()) {
                FurnitureSet hs = hSets.get(0);
                for (char wall : sortWalls.apply(new char[]{'N', 'S'})) {
                    if (stampWallRunExclusive(hs, wall, density)) { primaryWall = wall; break; }
                }
            }
            // 1b. Place remaining hSets on unused N/S walls.
            // Keep trying walls until one takes it — the old version broke out of
            // the loop after the first candidate whether or not it succeeded.
            if (hSets.size() > 1) {
                char[] nsWalls = sortWalls.apply(new char[]{'N', 'S'});
                for (int i = 1; i < hSets.size(); i++) {
                    FurnitureSet hs = hSets.get(i);
                    for (char wall : nsWalls) {
                        if (wall == primaryWall) continue;  // already used
                        if (stampWallRunExclusive(hs, wall, density)) break;
                    }
                }
            }
            // 2. Try to place the largest vertical run on W/E (door-free first).
            if (!vSets.isEmpty()) {
                FurnitureSet vs = vSets.get(0);
                for (char wall : sortWalls.apply(new char[]{'W', 'E'})) {
                    if (stampWallRunExclusive(vs, wall, density)) {
                        if (primaryWall == ' ') primaryWall = wall;
                        vSetWall = wall;
                        break;
                    }
                }
            }
            // 3. If nothing landed yet, try square sets on any wall (door-free first).
            if (primaryWall == ' ' && !sSets.isEmpty()) {
                FurnitureSet sq = sSets.get(0);
                for (char wall : sortWalls.apply(new char[]{'N', 'W', 'S', 'E'})) {
                    if (stampWallRunExclusive(sq, wall, density)) { primaryWall = wall; break; }
                }
            }
            // 4. Place remaining square sets, perpendicular wall first.
            //
            // Perpendicular keeps a standalone appliance clear of the main run,
            // but it is a preference, not a requirement. In a narrow kitchen the
            // counter run occupies the whole of that wall's usable length, and
            // offering only that one wall meant the standalone oven had nowhere
            // to stand and was dropped — the kitchen came out with a sink and a
            // fridge and nothing to cook on. Try the other walls before giving
            // up. Duplicates are still impossible: stampWallRunExclusive checks
            // the room's claims, so a fixture class that already landed is
            // refused on every wall.
            if (primaryWall != ' ' && !sSets.isEmpty()) {
                char perpWall = perpendicularWall(primaryWall);
                char[] order = {perpWall, opposite(perpWall),
                                opposite(primaryWall), primaryWall};
                for (FurnitureSet sq : sSets) {
                    for (char wall : order) {
                        if (wall == vSetWall) continue;   // the vertical run owns it
                        if (stampWallRunExclusive(sq, wall, density)) break;
                    }
                }
            }
        }

        // No floor sets → leave room empty rather than falling back to perimeter.
        // Caller must add matching sets to FurnitureSet or accept an empty room.
        if (floorSets.isEmpty()) return;

        FurnitureSet primary = weightedPick(floorSets);
        int aisle  = density.aisle + 1;
        int stepX  = primary.w + aisle;
        int stepY  = primary.h + aisle;
        int margin = 2;

        for (int uy = margin; uy + primary.h <= rh - margin; uy += stepY) {
            for (int ux = margin; ux + primary.w <= rw - margin; ux += stepX) {
                if (rng.nextDouble() < density.skip) continue;
                stampSet(primary, ux, uy, 'S');   // floor sets — facing irrelevant (no role tiles)
            }
        }

        if ((rw >= 12 || rh >= 12) && floorSets.size() > 1) {
            FurnitureSet secondary = weightedPick(floorSets);
            if (secondary != primary) {
                for (int attempt = 0; attempt < 8; attempt++) {
                    int ux = margin + rng.nextInt(Math.max(1, rw - margin * 2 - secondary.w));
                    int uy = margin + rng.nextInt(Math.max(1, rh - margin * 2 - secondary.h));
                    if (stampFits(secondary, ux, uy)) {
                        stampSet(secondary, ux, uy, 'S');
                        break;
                    }
                }
            }
        }

        if (!profile.activities.isEmpty()) {
            perimeter(profile.activities, density);
        }
    }

    /**
     * Authored commercial bathroom layout.
     *
     * Delegates toilet placement to the proven stalls() method which correctly
     * stacks stall_toilet + stall_wall + stall_doorwall + stall_door on each
     * square. stalls() already picks the longest wall (alongX = rw>=rh).
     *
     * We add: hanging sinks on the opposite wall, bin + dispenser in corner,
     * and blower from profile activities.
     */
    private void authoredLayout(FurnitureProfile profile, Density density) {
        // Step 1: Toilet stalls — delegate entirely to proven stalls() method.
        // stalls() runs along the longest wall automatically and correctly
        // stacks all stall tiles (toilet + walls + door) on each floor square.
        List<Activity> stallActs = List.of(Activity.of("toilet_N"));
        stalls(stallActs, density);

        // Step 2: Hanging sinks on the opposite wall.
        // Wide room (rw>=rh): stalls on N wall → sinks on S wall.
        // Tall room (rh>rw):  stalls on W wall → sinks on E wall.
        boolean wide = rw >= rh;
        if (wide) {
            int sinkY = rh - 2;
            if (sinkY >= 3) {
                for (int sx = 1; sx < rw - 1; sx += 2) {
                    if (hasKeepClear(sx, sinkY, 1, 1) || !free(sx, sinkY)) continue;
                    roleFacing = 'N'; currentRole = "sink";
                    if (put(sx, sinkY, "fixtures_sinks_01_30", false, "sink"))
                        stackWorld(rx + sx, ry + sinkY + 1, "fixtures_bathroom_01_28");
                }
            }
        } else {
            int sinkX = rw - 2;
            if (sinkX >= 3) {
                for (int sy = 1; sy < rh - 1; sy += 2) {
                    if (hasKeepClear(sinkX, sy, 1, 1) || !free(sinkX, sy)) continue;
                    roleFacing = 'W'; currentRole = "sink";
                    if (put(sinkX, sy, "fixtures_sinks_01_31", false, "sink"))
                        stackWorld(rx + sinkX + 1, ry + sy, "fixtures_bathroom_01_29");
                }
            }
        }

        // Step 3: Bin + dispenser in SE corner.
        int cx = rw - 2, cy = rh - 2;
        if (free(cx,     cy)) put(cx,     cy, "trashcontainers_01_20",                  false, null);
        if (free(cx - 1, cy)) put(cx - 1, cy, "location_business_office_generic_01_48", false, null);

        // Step 4: Perimeter extras (blower) from profile activities.
        if (!profile.activities.isEmpty()) perimeter(profile.activities, density);
    }
    /**
     * stampWallRun, refusing any set whose fixture class the room already has.
     *
     * This is the single choke point that keeps a room to one of each thing.
     * On success the set's classes are claimed, so every later pass sees them.
     */
    private boolean stampWallRunExclusive(FurnitureSet set, char wall, Density density) {
        for (String c : set.provides)
            if (claimed.contains(c)) return false;
        if (!stampWallRun(set, wall, density)) return false;
        claimed.addAll(set.provides);
        return true;
    }

    // ---------------------------------------------------------------
    // Living room — authored TV/seat pair
    // ---------------------------------------------------------------

    /** The low table the television stands on. */
    private static final String TV_TABLE = "furniture_tables_low_01_17";

    /**
     * A living room is a television and something to sit on, pointed at each
     * other. Everything else is optional.
     *
     * The generic stamper could not express this. It sorted sets by shape —
     * horizontal sets to the N/S walls, square sets to the perpendicular wall —
     * so a one-tile armchair was structurally incapable of ending up opposite
     * the screen. Here the two pieces are placed as one decision: pick a pair of
     * opposite walls, then walk offsets along them until a column (or row) is
     * free at BOTH ends, and commit the television and the seat to that same
     * column facing inward. If no column works the room stays empty rather than
     * falling back to something that does not face.
     */
    private void livingroomLayout(Density density) {
        // Candidate {tvWall, seatWall} pairs. Prefer walls without doors, then
        // the pairing with the most floor between them — a longer sightline
        // reads better and leaves room to walk past.
        List<char[]> pairs = new ArrayList<>(List.of(
                new char[]{'N', 'S'}, new char[]{'S', 'N'},
                new char[]{'W', 'E'}, new char[]{'E', 'W'}));
        pairs.sort((a, b) -> {
            // A television on the south or east wall faces north or west, and
            // those are the two sprites confirmed by inversion. South and east
            // sprites are only inferred, so prefer the arrangements that never
            // need them. Ordering only sets the order of attempts — a pair that
            // cannot be placed still falls through to the next.
            int measuredA = (a[0] == 'S' || a[0] == 'E') ? 0 : 1;
            int measuredB = (b[0] == 'S' || b[0] == 'E') ? 0 : 1;
            if (measuredA != measuredB) return measuredA - measuredB;
            int doorsA = (wallHasDoor(a[0]) ? 2 : 0) + (wallHasDoor(a[1]) ? 1 : 0);
            int doorsB = (wallHasDoor(b[0]) ? 2 : 0) + (wallHasDoor(b[1]) ? 1 : 0);
            if (doorsA != doorsB) return doorsA - doorsB;
            int gapA = (a[0] == 'N' || a[0] == 'S') ? rh : rw;
            int gapB = (b[0] == 'N' || b[0] == 'S') ? rh : rw;
            return gapB - gapA;
        });

        boolean paired = false;
        for (char[] p : pairs) {
            if (placeTvAndSeat(p[0], p[1])) { paired = true; break; }
        }

        // Shelves are filler and only earn a place once the pair has landed.
        // A room holding nothing but a bookcase is not a living room.
        if (!paired) return;
        placeShelfAgainstWall();
    }

    /**
     * Put the television on tvWall and the seat directly opposite on seatWall,
     * both on the same column (N/S pairing) or row (W/E pairing).
     *
     * Offsets are tried from the middle of the wall outward so the arrangement
     * sits centred rather than jammed into whichever corner came first.
     */
    private boolean placeTvAndSeat(char tvWall, char seatWall) {
        int tvLine   = interiorLine(tvWall);
        int seatLine = interiorLine(seatWall);
        if (tvLine < 0 || seatLine < 0) return false;
        // Need at least one square of floor between them or nobody can walk.
        if (Math.abs(tvLine - seatLine) < 2) return false;

        boolean ns = (tvWall == 'N' || tvWall == 'S');
        char tvFacing   = opposite(tvWall);    // N wall -> faces S, into the room
        char seatFacing = opposite(seatWall);

        int span = ns ? rw : rh;
        int mid  = span / 2;

        for (int step = 0; step < span; step++) {
            for (int sign : new int[]{1, -1}) {
                if (step == 0 && sign == -1) continue;
                int off = mid + step * sign;
                if (off < 1 || off > span - 2) continue;
                if (commitTvAndSeat(ns, tvLine, seatLine, off, tvFacing, seatFacing))
                    return true;
            }
        }
        return false;
    }

    /**
     * Validate both ends of the sightline, then write them.
     *
     * Nothing is written until every square is known good: a tile appended to
     * the cell cannot be taken back, so a television placed before discovering
     * the seat will not fit would leave a screen pointed at an empty wall.
     */
    private boolean commitTvAndSeat(boolean ns, int tvLine, int seatLine, int off,
                                    char tvFacing, char seatFacing) {
        int tvx = ns ? off : tvLine,   tvy = ns ? tvLine : off;
        int sx  = ns ? off : seatLine, sy  = ns ? seatLine : off;

        if (!canPlace(tvx, tvy, tvFacing, "table")) return false;

        // A whole couch, or an armchair. Never half a couch.
        String seatTile = fullCouchFor(sx, sy, seatFacing);
        String seatRole = "couch";
        if (seatTile == null) {
            String chair = resolve("chair_soft", seatFacing);
            if (chair != null && canPlace(sx, sy, seatFacing, "chair_soft")) {
                seatTile = chair; seatRole = "chair_soft";
            }
        }
        if (seatTile == null) return false;

        // Both ends check out — commit.
        roleFacing = tvFacing;
        if (!put(tvx, tvy, TV_TABLE, true, "table")) return false;
        stack(tvx, tvy, DIRECTIONAL_OVERRIDES.get("television")[facingIdx(tvFacing)]);

        roleFacing = seatFacing;
        put(sx, sy, seatTile, false, seatRole);
        claimed.add("television");
        claimed.add("seat");
        return true;
    }

    /**
     * A couch tile for this facing that will land WHOLE at (lx, ly), or null.
     *
     * A couch is a two-tile sprite: an anchor plus the partner at the next
     * index. put() places the partner only when it exists in the atlas and its
     * square is free, and it cannot un-write the anchor if it does not — which
     * is how a room ends up with half a couch sitting against a doorway.
     *
     * So everything is checked before anything is chosen, and rather than
     * testing one random draw from the group, every candidate is tried. A couch
     * whose partner sprite happens to be missing should cost us that couch, not
     * demote the room to an armchair while nine others would have worked.
     */
    private String fullCouchFor(int lx, int ly, char facing) {
        if (!canPlace(lx, ly, facing, "couch")) return null;

        int[] d = pairedExtDir(facing, "couch");
        int ex = lx + d[0], ey = ly + d[1];
        // free() excludes KEEP_CLEAR, so a door approach square rules the couch
        // out here rather than truncating it later.
        if (!free(ex, ey)) return null;

        List<String> group = pal.group("couch_" + facing);
        if (group.isEmpty()) group = pal.group("couch");
        if (group.isEmpty()) return null;

        List<String> candidates = new ArrayList<>(group);
        java.util.Collections.shuffle(candidates, rng);
        for (String c : candidates) {
            String partner = neighbourIndex(c, 1);
            if (partner != null && pal.all.contains(partner)) return c;
        }
        return null;
    }

    /**
     * True when the wall square behind a piece at (lx, ly) carries a door or a
     * window. Standing a bookcase in front of either is the thing that makes a
     * room look machine-filled rather than lived in.
     */
    private boolean wallBehindHasOpening(int lx, int ly, char wall) {
        int wx = rx + lx, wy = ry + ly;
        switch (wall) {
            case 'N' -> wy -= 1;
            case 'S' -> wy += 1;
            case 'W' -> wx -= 1;
            case 'E' -> wx += 1;
            default  -> { return false; }
        }
        String key = wx + "," + wy;
        return windowSquares.contains(key) || doorSquares.contains(key);
    }

    /**
     * Stand the shelves flat against any wall, facing into the room.
     *
     * They used to be a decorative tile dropped beside the television with a
     * hardcoded sprite and no checks at all, which left a bookcase stranded
     * mid-floor beside the screen. Now they take any wall that has a square
     * with no door or window behind it and open floor in front, and the sprite
     * is resolved from the wall direction like everything else.
     */
    private boolean placeShelfAgainstWall() {
        // N wall faces S, W wall faces E, S wall faces N — all confirmed
        // sprites. The east wall faces west, the one inferred entry, so it goes
        // last and is reached only when nothing else has a clear square.
        for (char wall : new char[]{'N', 'W', 'S', 'E'}) {
            int line = interiorLine(wall);
            if (line < 1) continue;
            boolean ns = (wall == 'N' || wall == 'S');
            char facing = opposite(wall);          // into the room
            // Named sprite, never resolve(): the shelves role mixes wall planks
            // in with the bookcases and a plank on a floor square floats.
            String tile = DIRECTIONAL_OVERRIDES.get("shelves")[facingIdx(facing)];
            if (tile == null) continue;

            int span = ns ? rw : rh;
            int mid  = span / 2;
            for (int step = 0; step < span; step++) {
                for (int sign : new int[]{1, -1}) {
                    if (step == 0 && sign == -1) continue;
                    int off = mid + step * sign;
                    if (off < 1 || off > span - 2) continue;

                    int lx = ns ? off : line, ly = ns ? line : off;
                    if (!free(lx, ly)) continue;
                    if (wallBehindHasOpening(lx, ly, wall)) continue;

                    // Something has to be able to walk up to it.
                    int[] f = dirOf(facing);
                    int fx = lx + f[0], fy = ly + f[1];
                    if (!inRoom(fx, fy)) continue;
                    if (grid[fx][fy] != FREE && grid[fx][fy] != KEEP_CLEAR) continue;

                    GisCells.appendTile(cell, rx + lx, ry + ly,
                            cell.tileIndex(tile), roomId);
                    grid[lx][ly] = TAKEN;
                    placed.add(new int[]{lx, ly});
                    claimed.add("shelves");
                    return true;
                }
            }
        }
        return false;
    }

    /** The interior row or column running alongside the given wall. */
    private int interiorLine(char wall) {
        return switch (wall) {
            case 'N' -> 1;
            case 'S' -> rh - 2;
            case 'W' -> 1;
            case 'E' -> rw - 2;
            default  -> -1;
        };
    }

    /**
     * Whether put() would accept this square, without writing anything.
     * Mirrors put's guards so a placement can be validated before committing.
     */
    private boolean canPlace(int lx, int ly, char facing, String role) {
        if (!free(lx, ly)) return false;
        if (!needsApproach(role)) return true;
        String savedRole = currentRole;
        char   savedFace = roleFacing;
        currentRole = role;
        roleFacing  = facing;
        boolean ok = facingClear(lx, ly, facing);
        currentRole = savedRole;
        roleFacing  = savedFace;
        return ok;
    }

    /**
     * Place a wall-run set along the given wall.
     * Returns true if at least one unit was placed.
     *
     * When set.unique is true the loop stops after the first successful
     * placement — hero pieces like a TV or couch appear once per wall,
     * not repeated across the whole run.
     */
    private boolean stampWallRun(FurnitureSet set, char wall, Density density) {
        int aisle = density.aisle + 1;
        boolean placed = false;

        // stampSet reports whether it actually wrote anything. Trusting the
        // attempt instead of the result used to drop a unique set silently: the
        // loop marked success and broke even when every square was already
        // taken, so a second square set (an oven behind a fridge) vanished and
        // the room still claimed to have one.
        //
        // density.skip thins a REPEATING run so a wall does not come out solid
        // with furniture. A unique set has a single placement, so the same roll
        // does not thin it — it deletes it outright, which is how kitchens were
        // ending up with a sink and a fridge but nothing to cook on. Hero pieces
        // and one-off appliances ignore the roll.
        //
        // The aisle stride has the same problem. It exists to space REPEATED
        // copies apart; with a single copy there is nothing to space, and
        // striding past the one reachable square on a cramped wall just loses
        // the object. Unique sets scan every position instead.
        boolean thin = !set.unique;
        switch (wall) {
            case 'N' -> {
                // N wall → furniture faces south into the room
                int step = set.unique ? 1 : set.w + aisle;
                for (int ux = 1; ux + set.w <= rw - 1; ux += step) {
                    if (thin && rng.nextDouble() < density.skip) continue;
                    if (hasKeepClear(ux, 1, set.w, set.h)) continue;
                    if (stampSet(set, ux, 1, 'S')) {
                        placed = true;
                        if (set.unique) break;
                    }
                }
            }
            case 'S' -> {
                // S wall → furniture faces north into the room
                int step = set.unique ? 1 : set.w + aisle;
                int ly = rh - 1 - set.h;
                if (ly < 1) return false;
                for (int ux = 1; ux + set.w <= rw - 1; ux += step) {
                    if (thin && rng.nextDouble() < density.skip) continue;
                    if (hasKeepClear(ux, ly, set.w, set.h)) continue;
                    if (stampSet(set, ux, ly, 'N')) {
                        placed = true;
                        if (set.unique) break;
                    }
                }
            }
            case 'W' -> {
                // W wall → furniture faces east into the room
                int step = set.unique ? 1 : set.h + aisle;
                for (int uy = 1; uy + set.h <= rh - 1; uy += step) {
                    if (thin && rng.nextDouble() < density.skip) continue;
                    if (hasKeepClear(1, uy, set.w, set.h)) continue;
                    if (stampSet(set, 1, uy, 'E')) {
                        placed = true;
                        if (set.unique) break;
                    }
                }
            }
            case 'E' -> {
                // E wall → furniture faces west into the room
                int step = set.unique ? 1 : set.h + aisle;
                int lx = rw - 1 - set.w;
                if (lx < 1) return false;
                for (int uy = 1; uy + set.h <= rh - 1; uy += step) {
                    if (thin && rng.nextDouble() < density.skip) continue;
                    if (hasKeepClear(lx, uy, set.w, set.h)) continue;
                    if (stampSet(set, lx, uy, 'W')) {
                        placed = true;
                        if (set.unique) break;
                    }
                }
            }
        }
        return placed;
    }

    /** The wall perpendicular to the given primary wall (for a second appliance run). */
    private static char perpendicularWall(char wall) {
        return switch (wall) {
            case 'N', 'S' -> 'W';
            default        -> 'N';
        };
    }

    /**
     * True if the first interior row/column for the given wall has any KEEP_CLEAR
     * square — meaning a door opens onto that wall. Used to prefer walls without
     * doors for the main counter run.
     */
    private boolean wallHasDoor(char wall) {
        switch (wall) {
            case 'N' -> { for (int x = 0; x < rw; x++) if (inRoom(x,1) && grid[x][1] == KEEP_CLEAR) return true; }
            case 'S' -> { int y = rh-2; for (int x = 0; x < rw; x++) if (inRoom(x,y) && grid[x][y] == KEEP_CLEAR) return true; }
            case 'W' -> { for (int y = 0; y < rh; y++) if (inRoom(1,y) && grid[1][y] == KEEP_CLEAR) return true; }
            case 'E' -> { int x = rw-2; for (int y = 0; y < rh; y++) if (inRoom(x,y) && grid[x][y] == KEEP_CLEAR) return true; }
        }
        return false;
    }

    /** True if any square in the footprint (ux..ux+w, uy..uy+h) is KEEP_CLEAR. */
    private boolean hasKeepClear(int ux, int uy, int w, int h) {
        for (int dx = 0; dx < w; dx++)
            for (int dy = 0; dy < h; dy++) {
                int lx = ux + dx, ly = uy + dy;
                if (inRoom(lx, ly) && grid[lx][ly] == KEEP_CLEAR) return true;
            }
        return false;
    }

    private FurnitureSet weightedPick(List<FurnitureSet> sets) {
        int total = 0;
        for (FurnitureSet s : sets) total += s.w * s.h;
        int roll = rng.nextInt(Math.max(1, total));
        int cum = 0;
        for (FurnitureSet s : sets) {
            cum += s.w * s.h;
            if (roll < cum) return s;
        }
        return sets.get(sets.size() - 1);
    }

    /**
     * Place all tiles of a set with its top-left corner at room-local (ux, uy).
     *
     * @param wallFacing  the direction the set faces INTO the room — opposite of the
     *                    wall it sits against (N wall → 'S', S wall → 'N', etc.).
     *                    Used to pick the correct directional sprite for role tiles.
     *
     * Floor tiles go through the normal grid-occupancy path so the circulation
     * check can see them. Wall-mounted tiles go to stackWorld (the wall square
     * just north of the tile position). On-top tiles stack on an already-placed
     * SURFACE square — if that square is not yet a surface (e.g. the floor tile
     * came first in a different stamp pass) the top tile is silently skipped
     * rather than failing the whole stamp.
     *
     * Role tiles (t.role() != null) delegate to resolve()+put() so the palette
     * selects the correct directional sprite automatically. put() also handles
     * paired objects (couches, beds) by extending the second tile in the right
     * direction based on wallFacing.
     */
    private boolean stampSet(FurnitureSet set, int ux, int uy, char wallFacing) {
        boolean any = false;
        for (FurnitureSet.Tile t : set.tiles) {
            int lx = ux + t.dx();
            int ly = uy + t.dy();

            // --- Role tiles: delegate facing to the palette system ---
            if (t.role() != null) {
                // Check hardcoded directional overrides first (e.g. television,
                // whose palette has no _N/_S/_E/_W sub-groups).
                String[] override = DIRECTIONAL_OVERRIDES.get(t.role());
                String tile;
                if (override != null) {
                    tile = override[facingIdx(wallFacing)];
                } else {
                    tile = resolve(t.role(), wallFacing);
                }

                if (t.onTop()) {
                    // On-top role tile (e.g. television): stack on an existing SURFACE.
                    if (inRoom(lx, ly) && grid[lx][ly] == SURFACE && tile != null) {
                        stack(lx, ly, tile);
                        any = true;
                    }
                } else {
                    // Floor role tile (e.g. couch): resolve + put handles pairing.
                    if (tile == null) continue;
                    roleFacing = wallFacing;
                    if (put(lx, ly, tile, isSurface(t.role()), t.role())) any = true;
                }
                continue;
            }

            if (t.wallMounted()) {
                // Place on the wall square adjacent to this tile's room position.
                // wallDir tells us which wall the tile attaches to:
                //   N → wall square is one row north  (wy-1)
                //   S → wall square is one row south  (wy+1)
                //   W → wall square is one col west   (wx-1)
                //   E → wall square is one col east   (wx+1)
                int wx = rx + lx, wy = ry + ly;
                int[] wq = switch (t.wallDir()) {
                    case 'N' -> new int[]{wx,     wy - 1};
                    case 'S' -> new int[]{wx,     wy + 1};
                    case 'W' -> new int[]{wx - 1, wy    };
                    case 'E' -> new int[]{wx + 1, wy    };
                    default  -> new int[]{wx,     wy - 1}; // fallback N
                };
                stackWorld(wq[0], wq[1], t.tile());
                any = true;

            } else if (t.onTop()) {
                // Stack only if the floor tile below is already a SURFACE.
                if (inRoom(lx, ly) && grid[lx][ly] == SURFACE) {
                    stack(lx, ly, t.tile());
                    any = true;
                }

            } else {
                // Normal floor tile — skip if occupied or out of bounds.
                if (!inRoom(lx, ly) || !free(lx, ly)) continue;
                boolean surface = isSurface(surfaceRoleFor(t.tile()));
                GisCells.appendTile(cell, rx + lx, ry + ly,
                        cell.tileIndex(t.tile()), roomId);
                grid[lx][ly] = surface ? SURFACE : TAKEN;
                placed.add(new int[]{lx, ly});
                any = true;
            }
        }
        return any;
    }

    /**
     * True when every floor tile of the set fits at room-local (ux, uy).
     * Wall and on-top tiles are not checked — they never need free floor.
     */
    private boolean stampFits(FurnitureSet set, int ux, int uy) {
        for (FurnitureSet.Tile t : set.tiles) {
            if (t.wallMounted() || t.onTop()) continue;
            if (!free(ux + t.dx(), uy + t.dy())) return false;
        }
        return true;
    }

    /**
     * Infer a role name from a raw tile name so isSurface() can mark the
     * correct grid state. Only the prefixes that matter for on-top placement
     * need covering — everything else is TAKEN, which is fine.
     */
    private static String surfaceRoleFor(String tile) {
        if (tile.startsWith("fixtures_counters"))    return "counter";
        if (tile.startsWith("furniture_tables"))     return "table";
        if (tile.startsWith("location_business_office_generic_01_1")
         || tile.startsWith("location_business_office_generic_01_4")) return "desk";
        return tile;   // will not match isSurface() — grid cell stays TAKEN
    }

    // ---------------------------------------------------------------
    // Grid and writing
    // ---------------------------------------------------------------

    /**
     * Place an object, refusing if it would be unreachable.
     *
     * The approachability invariant: anything a person interacts with needs a
     * free square in front of it. This is what stops chairs facing walls,
     * shelves facing each other, and sinks wedged into corners — one rule
     * rather than a special case per object type.
     */
    private boolean put(int lx, int ly, String tile, boolean surface, String role) {
        if (!free(lx, ly)) return false;
        currentRole = role;
        if (role != null && needsApproach(role) && !facingClear(lx, ly, roleFacing))
            return false;
        GisCells.appendTile(cell, rx + lx, ry + ly, cell.tileIndex(tile), roomId);
        grid[lx][ly] = surface ? SURFACE : TAKEN;
        placed.add(new int[]{lx, ly});

        // Paired objects (couches, beds, desks) have a second tile at index+1
        // that must be placed beside the anchor or the sprite is cut in half.
        // The extension tile goes to the side of the anchor based on its facing:
        //   couch_S / couch_N → extends east (dx+1)
        //   couch_E / couch_W → extends south (dy+1)
        //   bed               → extends east (dx+1)
        if (isPairedRole(role)) {
            String ext = neighbourIndex(tile, 1);
            if (ext != null && pal.all.contains(ext)) {
                int[] extDir = pairedExtDir(roleFacing, role);
                int ex = lx + extDir[0], ey = ly + extDir[1];
                if (free(ex, ey)) {
                    GisCells.appendTile(cell, rx + ex, ry + ey,
                            cell.tileIndex(ext), roomId);
                    grid[ex][ey] = surface ? SURFACE : TAKEN;
                    placed.add(new int[]{ex, ey});
                }
            }
        }
        return true;
    }

    /** Roles that are 2-tile paired sprites requiring an extension tile. */
    private static boolean isPairedRole(String role) {
        if (role == null) return false;
        String f = familyOf(role);
        return f.equals("couch") || f.equals("bed") || f.equals("bed_home");
    }

    /**
     * Direction to place the extension tile based on the anchor's facing and role.
     * Couches: S/N facing → extend east; E/W facing → extend south.
     * Beds: always extend east.
     */
    private static int[] pairedExtDir(char facing, String role) {
        String f = familyOf(role);
        if (f.startsWith("bed")) return new int[]{1, 0};
        return (facing == 'S' || facing == 'N') ? new int[]{1, 0} : new int[]{0, 1};
    }

    /** Returns the tile name with its trailing index offset by delta, or null. */
    private static String neighbourIndex(String tile, int delta) {
        int us = tile.lastIndexOf('_');
        if (us < 0) return null;
        try {
            int idx = Integer.parseInt(tile.substring(us + 1));
            if (idx + delta < 0) return null;
            return tile.substring(0, us + 1) + (idx + delta);
        } catch (NumberFormatException e) { return null; }
    }

    /** Facing of the object currently being placed, for the approach check. */
    private char roleFacing = 'N';
    /** Role of the object currently being placed, for the wall-attach check. */
    private String currentRole = null;

    private static boolean needsApproach(String role) {
        return APPROACHABLE.contains(familyOf(role));
    }

    /**
     * True when the square this object faces is inside the room and not
     * already occupied.
     *
     * For wall-attached objects (shelves, toilets, sinks with attachedN) the
     * "facing" is the wall they lean on, but the APPROACH direction is the
     * opposite — a shelf on the north wall (attachedN) is approached from the
     * south. So we check the square in the opposite direction of the wall.
     *
     * A door approach square counts as clear — it is free floor.
     */
    private boolean facingClear(int lx, int ly, char facing) {
        char approach = (currentRole != null && isWallAttached(currentRole))
                ? opposite(facing) : facing;
        int[] d = dirOf(approach);
        int nx = lx + d[0], ny = ly + d[1];
        if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) return false;
        byte st = grid[nx][ny];
        return st == FREE || st == KEEP_CLEAR;
    }

    /**
     * Roles whose objects are attached to a wall and approached from the
     * OPPOSITE side. A shelf on the north wall (facing = 'N') is approached
     * from the south. A freestanding desk (facing = 'N') faces north and is
     * approached from the north. The distinction matters for facingClear.
     */
    private static final Set<String> WALL_ATTACHED = Set.of(
            "shelves", "shelves_N", "shelves_S", "shelves_E", "shelves_W",
            "shelves_office", "shelves_retail",
            "toilet", "toilet_stall", "toilet_N", "toilet_S", "toilet_E", "toilet_W",
            "sink", "shower", "bath", "locker",
            "overhead_N", "overhead_S", "overhead_E", "overhead_W",
            "blower_N", "blower_S", "blower_E", "blower_W",
            "mirror_N", "mirror_W", "urinal_N", "urinal_W", "urinal_S");

    private static boolean isWallAttached(String role) {
        return WALL_ATTACHED.contains(familyOf(role));
    }

    /** Add to an already-occupied square (ON_TOP). */
    private void stack(int lx, int ly, String tile) {
        if (lx < 0 || ly < 0 || lx >= rw || ly >= rh) return;
        GisCells.appendTile(cell, rx + lx, ry + ly, cell.tileIndex(tile), roomId);
    }

    /** Stack at a cell-local coordinate outside the room rect (wall squares). */
    private void stackWorld(int wx, int wy, String tile) {
        if (wx < 0 || wy < 0 || wx >= 256 || wy >= 256) return;
        GisCells.appendTile(cell, wx, wy, cell.tileIndex(tile), roomId);
    }

    private boolean inRoom(int lx, int ly) {
        return lx >= 0 && ly >= 0 && lx < rw && ly < rh;
    }

    private boolean free(int lx, int ly) {
        return lx >= 0 && ly >= 0 && lx < rw && ly < rh && grid[lx][ly] == FREE;
    }

    private void mark(int lx, int ly, byte state) {
        if (lx >= 0 && ly >= 0 && lx < rw && ly < rh) grid[lx][ly] = state;
    }

    private boolean runFits(int lx, int ly, int dx, int dy, int len) {
        for (int k = 0; k < len; k++)
            if (!free(lx + dx * k, ly + dy * k)) return false;
        return true;
    }

    /** The square an object at (lx,ly) facing `facing` looks at. */
    private int[] frontOf(int lx, int ly, char facing) {
        int[] d = dirOf(facing);
        int nx = lx + d[0], ny = ly + d[1];
        return (nx >= 0 && ny >= 0 && nx < rw && ny < rh) ? new int[]{nx, ny} : null;
    }

    /** A square to the side of an object, along its wall. */
    private int[] besideOf(int lx, int ly, char facing) {
        int[] d = dirOf(facing);
        // Perpendicular to the facing.
        int px = d[1], py = d[0];
        for (int sign : new int[]{1, -1}) {
            int nx = lx + px * sign, ny = ly + py * sign;
            if (nx >= 0 && ny >= 0 && nx < rw && ny < rh && free(nx, ny))
                return new int[]{nx, ny};
        }
        return null;
    }

    private int[] anyFreeNeighbour(int lx, int ly) {
        for (int[] d : new int[][]{{0,1},{0,-1},{1,0},{-1,0}}) {
            int nx = lx + d[0], ny = ly + d[1];
            if (nx >= 0 && ny >= 0 && nx < rw && ny < rh && free(nx, ny))
                return new int[]{nx, ny};
        }
        return null;
    }

    /**
     * The wall square an object leans against, in cell-local coordinates.
     * An attachedN object at ry+1 leans on the wall at ry.
     */
    private int[] wallSquareFor(int lx, int ly, char facing) {
        return switch (facing) {
            case 'N' -> new int[]{rx + lx, ry + ly - 1};
            case 'S' -> new int[]{rx + lx, ry + ly + 1};
            case 'W' -> new int[]{rx + lx - 1, ry + ly};
            case 'E' -> new int[]{rx + lx + 1, ry + ly};
            default  -> null;
        };
    }

    private static int[] dirOf(char facing) {
        return switch (facing) {
            case 'N' -> new int[]{0, -1};
            case 'S' -> new int[]{0, 1};
            case 'W' -> new int[]{-1, 0};
            default  -> new int[]{1, 0};
        };
    }

    private static char opposite(char facing) {
        return switch (facing) {
            case 'N' -> 'S';
            case 'S' -> 'N';
            case 'W' -> 'E';
            default  -> 'W';
        };
    }

    /** Fires this activity at all, given its chance and the density tier. */
    private boolean fires(Activity a, Density density) {
        if (a.chance() >= 1.0) return true;
        return rng.nextDouble() < a.chance() * density.optionalScale;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

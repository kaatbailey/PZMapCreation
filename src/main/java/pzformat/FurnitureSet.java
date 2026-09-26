package pzformat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A template of objects at fixed relative offsets, extracted from vanilla
 * measurements. A room picks 1–2 appropriate sets and stamps them across the
 * available floor with aisle gaps between stamps.
 *
 * This is how vanilla rooms were actually made — not individual objects placed
 * independently, but recognisable arrangements repeated until the room is full.
 *
 * Coordinate convention:
 *   dx/dy are offsets from the stamp origin in room-local space.
 *   Positive dx = east (increasing x), positive dy = south (increasing y).
 *   This matches the PZ tile coordinate system used in FurniturePlacer.
 *
 * Wall-mounted objects (WallObject tiles) have WALL_MOUNTED = true. The
 * stamper in FurniturePlacer handles these by calling stackWorld on the wall
 * square rather than put on the room floor.
 *
 * ON_TOP tiles (appliances on counters, computers on desks) are placed via
 * stack, same as satellites in the existing system.
 *
 * Sets A–H come from office_sets.txt (session 1).
 * Sets I–Y come from office_sets2.txt (session 2, 2026-09-20).
 */
public final class FurnitureSet {

    // ------------------------------------------------------------------
    // Data model
    // ------------------------------------------------------------------

    /**
     * One tile within a set.
     *
     * @param dx          east offset from stamp origin
     * @param dy          south offset from stamp origin
     * @param tile        exact tile name, e.g. "furniture_tables_high_01_17"
     * @param wallMounted goes on the wall square adjacent to this position, not the floor
     * @param wallDir     which wall to attach to: 'N', 'S', 'E', 'W'. Only used when wallMounted=true.
     *                    'N' → wall square is one row north (wy-1). 'S' → wy+1. 'W' → wx-1. 'E' → wx+1.
     * @param onTop       stacks on an already-occupied square (stack)
     */
    /**
     * @param role        palette role resolved at placement time (e.g. "couch", "television").
     *                    When non-null, stampSet calls resolve(role, wallFacing) + put() so
     *                    the correct directional sprite is picked automatically for whichever
     *                    wall the stamp lands on. Mutually exclusive with an explicit tile name.
     */
    public record Tile(int dx, int dy, String tile, String role,
                       boolean wallMounted, char wallDir, boolean onTop) {

        /** Floor object with an explicit tile name. */
        public static Tile floor(int dx, int dy, String tile) {
            return new Tile(dx, dy, tile, null, false, ' ', false);
        }

        /**
         * Floor object resolved from a palette role at stamp-time.
         * The wall direction passed to stampSet becomes the facing, so a couch
         * placed on the S wall automatically gets its FacingN sprite.
         */
        public static Tile role(int dx, int dy, String role) {
            return new Tile(dx, dy, null, role, false, ' ', false);
        }

        /**
         * On-top object resolved from a palette role at stamp-time.
         * Use for a TV on a table: the correct facing variant is picked automatically.
         */
        public static Tile roleTop(int dx, int dy, String role) {
            return new Tile(dx, dy, null, role, false, ' ', true);
        }

        /** Object mounted on the NORTH wall (tile sits at ry+1, wall square at ry). */
        public static Tile wallN(int dx, int dy, String tile) {
            return new Tile(dx, dy, tile, null, true, 'N', false);
        }

        /** Object mounted on the SOUTH wall (tile sits at ry-1, wall square at ry). */
        public static Tile wallS(int dx, int dy, String tile) {
            return new Tile(dx, dy, tile, null, true, 'S', false);
        }

        /** Object mounted on the WEST wall (tile sits at rx+1, wall square at rx). */
        public static Tile wallW(int dx, int dy, String tile) {
            return new Tile(dx, dy, tile, null, true, 'W', false);
        }

        /** Object mounted on the EAST wall (tile sits at rx-1, wall square at rx). */
        public static Tile wallE(int dx, int dy, String tile) {
            return new Tile(dx, dy, tile, null, true, 'E', false);
        }

        /** Object stacked on an already-placed floor tile (IsTableTop). */
        public static Tile top(int dx, int dy, String tile) {
            return new Tile(dx, dy, tile, null, false, ' ', true);
        }
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    /** Human-readable label — used in logs and for debugging. */
    public final String name;

    /** Tiles in this set, in placement order (back-to-front, wall before floor). */
    public final List<Tile> tiles;

    /**
     * Bounding box of the floor footprint.
     * Wall-mounted tiles do not count toward this — they land on the wall, not
     * the floor, so they do not need clearance from the stamp's bounding box.
     */
    public final int w, h;

    /**
     * Room types this set is appropriate for.
     * Matches the strings in BuildingPlan / FurnitureProfile room-type switches.
     */
    public final List<String> roomTypes;

    /**
     * Minimum room dimension (both axes) for this set to be used.
     * Prevents a conference table being stamped into a 4×4 closet.
     */
    public final int minRoomDim;

    private FurnitureSet(String name, int w, int h, int minRoomDim, boolean wallRun,
                         boolean unique, List<String> provides,
                         List<String> roomTypes, List<Tile> tiles) {
        this.name       = name;
        this.w          = w;
        this.h          = h;
        this.minRoomDim = minRoomDim;
        this.wallRun    = wallRun;
        this.unique     = unique;
        this.provides   = List.copyOf(provides);
        this.roomTypes  = roomTypes;
        this.tiles      = List.copyOf(tiles);
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    /**
     * When true, this set is a wall-run (sinks, mirrors, toilets in a row)
     * and stampLayout places it along one wall rather than tiling it across
     * the room floor. The stamper aligns the set's 'back' to the chosen wall
     * and repeats it along that wall's length.
     */
    public final boolean wallRun;

    /**
     * When true, stampWallRun places this set at most ONCE per wall call,
     * even if the wall is wide enough to fit it several times. Use for
     * living-room hero pieces (TV, couch) that should appear once, not as
     * a repeating run.
     */
    public final boolean unique;

    /**
     * Fixture classes this set supplies to the room.
     *
     * A room may hold at most ONE set for each class. This is what stops a
     * kitchen collecting a counter run on the north wall, a second counter run
     * on the west wall and a standalone fridge on top of both — the bug that
     * produced three sinks and two ovens in one room.
     *
     * unique() constrains repetition ALONG ONE WALL. provides() constrains
     * duplication ACROSS the whole room. They are different problems and a set
     * usually wants both.
     *
     * Empty means the set is unconstrained and may coexist with anything
     * (shelves, paintings, decorative pieces).
     */
    public final List<String> provides;

    public static final class Builder {
        private final String name;
        private final int w, h, minRoomDim;
        private final List<String> roomTypes;
        private final List<Tile> tiles = new ArrayList<>();
        private final List<String> provides = new ArrayList<>();
        private boolean wallRun = false;
        private boolean unique  = false;

        public Builder(String name, int w, int h, int minRoomDim,
                       String... roomTypes) {
            this.name       = name;
            this.w          = w;
            this.h          = h;
            this.minRoomDim = minRoomDim;
            this.roomTypes  = List.of(roomTypes);
        }

        /** Mark this set as a wall-run: place along one wall, not tiled across floor. */
        public Builder wallRun() { this.wallRun = true; return this; }

        /**
         * Mark this set as unique: place at most once per wall even when the
         * wall is wide enough for multiple copies. Use for hero pieces (TV, couch).
         */
        public Builder unique()  { this.unique  = true; return this; }

        /**
         * Declare the fixture classes this set supplies. Once a set providing
         * "counter_run" has landed, no other counter_run set is attempted in
         * that room — one kitchen per kitchen.
         */
        public Builder provides(String... classes) {
            for (String c : classes) this.provides.add(c);
            return this;
        }

        public Builder add(Tile t)                      { tiles.add(t); return this; }
        public Builder floor  (int dx, int dy, String t) { return add(Tile.floor  (dx, dy, t)); }
        public Builder wallN  (int dx, int dy, String t) { return add(Tile.wallN  (dx, dy, t)); }
        public Builder wallS  (int dx, int dy, String t) { return add(Tile.wallS  (dx, dy, t)); }
        public Builder wallW  (int dx, int dy, String t) { return add(Tile.wallW  (dx, dy, t)); }
        public Builder wallE  (int dx, int dy, String t) { return add(Tile.wallE  (dx, dy, t)); }
        public Builder top    (int dx, int dy, String t) { return add(Tile.top    (dx, dy, t)); }
        /** Floor object resolved from palette at stamp-time (e.g. couch). */
        public Builder role   (int dx, int dy, String r) { return add(Tile.role   (dx, dy, r)); }
        /** On-top object resolved from palette at stamp-time (e.g. television). */
        public Builder roleTop(int dx, int dy, String r) { return add(Tile.roleTop(dx, dy, r)); }

        public FurnitureSet build() {
            return new FurnitureSet(name, w, h, minRoomDim, wallRun, unique,
                    provides, roomTypes, tiles);
        }
    }

    // ==================================================================
    // SET CATALOGUE
    // Measured from vanilla. Offsets are from the stamp origin (0,0)
    // which is always the top-left tile of the set's floor footprint.
    // ==================================================================

    // ------------------------------------------------------------------
    // LARGE OFFICE SETS  (from office_sets.txt, session 1)
    // ------------------------------------------------------------------

    /**
     * SET A — Conference table with chairs on both sides.
     * Source: coord 12924,2044, cell 50_7.
     * Layout: column of high table tiles at dx=1, chairs at dx=0 and dx=2.
     * Bounding box: 3 wide × 6 tall.
     */
    public static final FurnitureSet CONF_TABLE = new Builder(
            "conference_table", 3, 6, 8,
            "office", "lobby", "conferenceroom")
        // Table column (alternating pair, 6 rows)
        .floor(1, 0, "furniture_tables_high_01_17")
        .floor(1, 1, "furniture_tables_high_01_18")
        .floor(1, 2, "furniture_tables_high_01_17")
        .floor(1, 3, "furniture_tables_high_01_18")
        .floor(1, 4, "furniture_tables_high_01_17")
        .floor(1, 5, "furniture_tables_high_01_18")
        // Chairs west side (facing E — toward table)
        .floor(0, 1, "furniture_seating_indoor_01_49")
        .floor(0, 3, "furniture_seating_indoor_01_49")
        .floor(0, 5, "furniture_seating_indoor_01_49")
        // Chairs east side (facing W — toward table)
        .floor(2, 0, "furniture_seating_indoor_01_48")
        .floor(2, 2, "furniture_seating_indoor_01_48")
        .floor(2, 4, "furniture_seating_indoor_01_48")
        .build();

    /**
     * SET B — L-desk workstation.
     * Source: coord 12785,1715.
     * Bounding box: ~4×4.
     */
    public static final FurnitureSet L_DESK = new Builder(
            "l_desk", 4, 4, 6,
            "office")
        .floor(0, 0, "location_business_office_generic_01_40")
        .floor(1, 0, "location_business_office_generic_01_41")
        .floor(0, 1, "location_business_office_generic_01_42")
        .floor(1, 1, "location_business_office_generic_01_43")
        // Chair
        .floor(2, 1, "furniture_seating_indoor_01_52")
        // Extended arm
        .floor(0, 2, "location_business_office_generic_01_136")
        .floor(0, 3, "location_business_office_generic_01_137")
        .build();

    /**
     * SET C — Cubicle with partition walls.
     * Source: coord 12773,1715.
     * Bounding box: ~4×4.
     */
    public static final FurnitureSet CUBICLE = new Builder(
            "cubicle", 4, 4, 8,
            "office")
        // Partition walls
        .floor(0, 0, "location_business_office_generic_01_44")
        .floor(1, 0, "location_business_office_generic_01_45")
        .floor(0, 1, "location_business_office_generic_01_46")
        .floor(1, 1, "location_business_office_generic_01_47")
        // Desk inside cubicle
        .floor(2, 1, "location_business_office_generic_01_18")
        .floor(3, 1, "location_business_office_generic_01_19")
        // Chair
        .floor(2, 2, "furniture_seating_indoor_01_51")
        .build();

    /**
     * SET D — Single desk + chair + side table.
     * Source: coord 12788,1699.
     * Bounding box: ~4×4.
     */
    public static final FurnitureSet SINGLE_DESK = new Builder(
            "single_desk", 4, 4, 5,
            "office", "security", "police")
        .floor(0, 0, "location_business_office_generic_01_33")
        .floor(0, 1, "location_business_office_generic_01_33")
        .floor(1, 1, "furniture_seating_indoor_03_58")
        .floor(2, 1, "furniture_seating_indoor_03_59")
        .floor(3, 0, "furniture_tables_high_01_30")
        .floor(3, 1, "furniture_tables_high_01_31")
        .build();

    /**
     * SET E — Desk + vintage lamp + filing cabinet.
     * Source: coord 12820,1694.
     * Bounding box: ~3×5.
     */
    public static final FurnitureSet DESK_LAMP_FILING = new Builder(
            "desk_lamp_filing", 3, 5, 5,
            "office")
        .floor(0, 0, "location_business_office_generic_01_42")
        .floor(1, 0, "location_business_office_generic_01_43")
        .floor(0, 1, "location_business_office_generic_01_15")
        .floor(0, 2, "furniture_storage_02_2")
        .floor(1, 2, "furniture_seating_indoor_01_49")
        // Lamp on desk surface (IsTableTop)
        .top (0, 0, "location_community_school_01_16")
        .top (0, 1, "location_community_school_01_17")
        .build();

    /**
     * SET F — Back-to-back desk row with computers.
     * Source: coord 12783,1626.
     * Bounding box: ~6×6 (two facing rows).
     */
    public static final FurnitureSet DESK_ROW_COMPUTERS = new Builder(
            "desk_row_computers", 6, 6, 10,
            "office")
        // Row 1 (facing S)
        .floor(0, 0, "location_business_office_generic_01_40")
        .floor(1, 0, "location_business_office_generic_01_41")
        .floor(2, 0, "location_business_office_generic_01_42")
        .floor(3, 0, "location_business_office_generic_01_43")
        .floor(1, 1, "furniture_seating_indoor_01_50")
        .floor(3, 1, "furniture_seating_indoor_01_50")
        .top (1, 0, "location_shop_mall_01_4")
        .top (3, 0, "location_shop_mall_01_4")
        // Row 2 (facing N, back-to-back)
        .floor(0, 3, "location_business_office_generic_01_44")
        .floor(1, 3, "location_business_office_generic_01_45")
        .floor(2, 3, "location_business_office_generic_01_40")
        .floor(3, 3, "location_business_office_generic_01_41")
        .floor(1, 2, "furniture_seating_indoor_01_48")
        .floor(3, 2, "furniture_seating_indoor_01_48")
        .top (1, 3, "location_shop_mall_01_4")
        .top (3, 3, "location_shop_mall_01_4")
        .build();

    /**
     * SET G — Partitioned office cluster.
     * Source: coord 12772,1535.
     * Bounding box: ~6×4.
     */
    public static final FurnitureSet PARTITION_CLUSTER = new Builder(
            "partition_cluster", 6, 4, 8,
            "office")
        .floor(0, 0, "location_business_office_generic_01_44")
        .floor(1, 0, "location_business_office_generic_01_45")
        .floor(2, 0, "location_business_office_generic_01_46")
        .floor(0, 1, "location_business_office_generic_01_18")
        .floor(1, 1, "location_business_office_generic_01_19")
        .floor(3, 0, "location_business_office_generic_01_44")
        .floor(4, 0, "location_business_office_generic_01_45")
        .floor(3, 1, "location_business_office_generic_01_18")
        .floor(4, 1, "location_business_office_generic_01_19")
        .floor(1, 2, "furniture_seating_indoor_01_50")
        .floor(4, 2, "furniture_seating_indoor_01_50")
        .build();

    /**
     * SET H — Back-to-back library shelves.
     * Source: coord 12568,1471.
     * Repeating unit: 4 wide × 2 tall (N-facing row above, S-facing row below).
     * Stamp with 1-tile aisle between units.
     */
    public static final FurnitureSet LIBRARY_SHELVES = new Builder(
            "library_shelves", 4, 2, 5,
            "storage", "archive", "library", "garagestorage")
        // South-facing shelf (the one you approach from the south)
        .floor(0, 1, "furniture_shelving_01_44")
        .floor(1, 1, "furniture_shelving_01_44")
        .floor(2, 1, "furniture_shelving_01_44")
        .floor(3, 1, "furniture_shelving_01_44")
        // North-facing shelf (approached from the north — back-to-back)
        .floor(0, 0, "furniture_shelving_01_47")
        .floor(1, 0, "furniture_shelving_01_47")
        .floor(2, 0, "furniture_shelving_01_47")
        .floor(3, 0, "furniture_shelving_01_47")
        .build();

    // ------------------------------------------------------------------
    // SMALL OFFICE SETS  (from office_sets2.txt, session 2)
    // ------------------------------------------------------------------

    /**
     * SET I — Desk row with whiteboard + chairs.
     * Source: coord 12754,1516, cell 49_5.
     * A column of desks (high_01 _28/_29 pair) running S, chairs on the N side,
     * whiteboard (3-tile) on the W wall, accent table at the S end.
     * Bounding box: 5 wide × 5 tall.
     */
    public static final FurnitureSet DESK_ROW_WHITEBOARD = new Builder(
            "desk_row_whiteboard", 5, 5, 7,
            "office")
        // Whiteboard — 3-tile vertical, on W wall (wall-mounted, FacingE)
        .wallW(0, 0, "location_business_office_generic_01_52")
        .wallW(0, 1, "location_business_office_generic_01_51")
        .wallW(0, 2, "location_business_office_generic_01_50")
        // Desk column (alternating _28/_29, FacingW) — 1 tile in from whiteboard
        .floor(1, 0, "furniture_tables_high_01_28")
        .floor(1, 1, "furniture_tables_high_01_29")
        .floor(1, 2, "furniture_tables_high_01_28")
        .floor(1, 3, "furniture_tables_high_01_29")
        // Corner/return desks (_30/_31, FacingN)
        .floor(2, 0, "furniture_tables_high_01_30")
        .floor(3, 0, "furniture_tables_high_01_31")
        // Chairs (FacingS — behind the desks)
        .floor(2, 4, "furniture_seating_indoor_01_54")
        .floor(3, 4, "furniture_seating_indoor_01_54")
        // Chair at the west end (FacingE)
        .floor(0, 3, "furniture_seating_indoor_01_53")
        // Accent table at south end (2-tile, FacingS)
        .floor(3, 3, "furniture_tables_high_02_23")
        .floor(4, 3, "furniture_tables_high_02_22")
        .build();

    /**
     * SET J — Posh single office: couch zone + L-desk + computer.
     * Source: coord 12736,1524, cell 49_5.
     * Two sub-zones: lounge (couch + low table + chair) and workspace (L-desk + computer).
     * Bounding box: 6 wide × 6 tall.
     */
    public static final FurnitureSet POSH_OFFICE = new Builder(
            "posh_office", 6, 6, 7,
            "office")
        // Lounge zone — couch + lazy chair + glass coffee table
        .floor(0, 0, "furniture_seating_indoor_02_48")  // Couch FacingS (Brown Lazy)
        .floor(1, 0, "furniture_seating_indoor_02_45")  // Lazy Chair FacingE
        .floor(0, 2, "furniture_tables_low_01_3")       // Coffee Table FacingS (Fancy)
        // Workspace zone — L-desk (Office 3 group) + computer + chair
        .floor(3, 3, "location_business_office_generic_01_170")  // Desk FacingE
        .floor(3, 4, "location_business_office_generic_01_171")  // Desk FacingE
        .floor(4, 3, "location_business_office_generic_01_172")  // Desk FacingE (corner)
        .top (4, 3, "appliances_com_01_72")             // Desktop Computer (IsTableTop)
        .floor(4, 5, "furniture_seating_indoor_01_39")  // Chair FacingN (Fancy White)
        // Shelves on east wall + mirror above
        .floor(5, 3, "furniture_shelving_01_41")        // Shelves FacingE (Oakwood)
        .floor(5, 4, "furniture_tables_high_01_5")      // Mirror FacingE (Long)
        .build();

    /**
     * SET K — Filing cabinet + vintage lamp desk.
     * Source: coord 12635,1453, cell 49_5.
     * Compact single-person: filing cabinet pair, desk with green lamp, chair, clock, plant.
     * Bounding box: 4 wide × 3 tall.
     */
    public static final FurnitureSet FILING_LAMP_DESK = new Builder(
            "filing_lamp_desk", 4, 3, 5,
            "office", "security", "police")
        // Filing cabinet (2-tile vertical, FacingE — White File)
        .floor(0, 0, "location_business_office_generic_01_33")
        .floor(0, 1, "location_business_office_generic_01_33")
        // Desk pair (FacingN)
        .floor(2, 1, "location_business_office_generic_01_8")
        .floor(3, 1, "location_business_office_generic_01_10")
        // Green vintage lamp on desk (IsTableTop)
        .top (3, 1, "lighting_indoor_02_35")
        // Chair (FacingS — in front of desk)
        .floor(2, 0, "furniture_seating_indoor_01_50")
        // Wall clock (WallObject, FacingS — on N wall)
        .wallN(0, 0, "location_community_school_01_32")
        // Bin
        .floor(1, 2, "trashcontainers_01_20")
        .build();

    /**
     * SET L — Office wall painting (2-tile accent).
     * Source: coord 12543,1415, cell 48_5.
     * A 2-tile painting pair for the N wall of any office.
     * Bounding box: 2 wide × 1 tall (wall-mounted; no floor footprint).
     */
    public static final FurnitureSet OFFICE_PAINTING = new Builder(
            "office_painting", 2, 1, 4,
            "office", "lobby")
        // Both tiles are wall-mounted (FacingS = on N wall)
        .wallN(0, 0, "location_entertainment_gallery_01_24")
        .wallN(1, 0, "location_entertainment_gallery_01_25")
        .build();

    // ------------------------------------------------------------------
    // BREAKROOM SETS  (from office_sets2.txt, session 2)
    // ------------------------------------------------------------------

    /**
     * SET N — Breakroom: folding-chair tables + counter run + clock.
     * Source: coord 12834,1688, cell 50_6.
     * Birchwood counter with fridge and sink, rectangular tables with folding chairs.
     * Bounding box: 7 wide × 7 tall.
     */
    public static final FurnitureSet BREAKROOM_COUNTER_TABLES = new Builder(
            "breakroom_counter_tables", 7, 7, 10,
            "cafeteriakitchen")
        .wallRun()
        // Counter run: fridge + counter×2 + sink + counter + corner cap
        .floor(0, 0, "appliances_refrigeration_01_3")    // Fridge FacingW (White)
        .floor(0, 1, "fixtures_counters_01_47")          // Counter FacingW (Birchwood)
        .floor(0, 2, "fixtures_counters_01_47")          // Counter FacingW
        .floor(0, 3, "fixtures_counters_01_47")          // Counter FacingW + sink below
        .top (0, 3, "fixtures_sinks_01_10")              // Sink FacingW (Chrome, on counter)
        .floor(0, 4, "fixtures_counters_01_47")          // Counter FacingW
        .floor(0, 5, "fixtures_counters_01_40")          // Corner cap FacingN (Birchwood Corner)
        .floor(1, 5, "fixtures_counters_01_41")          // Counter FacingN (Birchwood)
        // Wall clock (WallObject, FacingE — on W wall above counter)
        .wallN(0, 2, "location_community_school_01_33")
        // Tables (3-tile column, alternating _48/_49 pair, FacingE)
        .floor(2, 3, "furniture_tables_high_01_49")
        .floor(2, 4, "furniture_tables_high_01_48")
        .floor(2, 5, "furniture_tables_high_01_49")
        // Folding chairs: S side (FacingS = _60), W side (FacingW = _63)
        .floor(2, 6, "furniture_seating_indoor_01_60")   // Chair FacingS
        .floor(3, 6, "furniture_seating_indoor_01_60")
        .floor(1, 3, "furniture_seating_indoor_01_61")   // Chair FacingE (at counter end)
        .floor(1, 4, "furniture_seating_indoor_01_61")
        .floor(3, 3, "furniture_seating_indoor_01_63")   // Chair FacingW (far side of table)
        .build();

    /**
     * SET O — Breakroom: round tables + chalkboard + corner shelves.
     * Source: coord 12822,1689, cell 50_6.
     * Casual breakroom: oak round tables, blue plastic chairs, 3-tile chalkboard, shelves + clock.
     * Bounding box: 6 wide × 7 tall.
     */
    public static final FurnitureSet BREAKROOM_ROUND_TABLES = new Builder(
            "breakroom_round_tables", 6, 7, 8,
            "breakroom")
        // Chalkboard: 3-tile vertical (FacingE — on W wall)
        .wallW(0, 3, "location_community_school_01_18")   // Board FacingE (top)
        .wallW(0, 4, "location_community_school_01_17")   // Board FacingE (mid)
        .wallW(0, 5, "location_community_school_01_16")   // Board FacingE (bottom)
        // Corner shelves on N part of the W wall
        .floor(0, 0, "furniture_shelving_01_21")         // Shelves FacingE (Middle)
        .floor(0, 1, "furniture_shelving_01_20")         // Shelves FacingE (Corner A)
        // Oak round tables (single-tile, no facing)
        .floor(2, 1, "furniture_tables_high_01_6")
        .floor(2, 3, "furniture_tables_high_01_6")
        .floor(4, 1, "furniture_tables_high_01_6")
        .floor(4, 3, "furniture_tables_high_01_6")
        // Blue plastic chairs: E=_12, S=_13, W=_14, N=_15
        .floor(1, 1, "furniture_seating_indoor_02_12")   // FacingE (left of table)
        .floor(3, 1, "furniture_seating_indoor_02_14")   // FacingW (right)
        .floor(2, 0, "furniture_seating_indoor_02_13")   // FacingS (above)
        .floor(2, 2, "furniture_seating_indoor_02_15")   // FacingN (below)
        .floor(1, 3, "furniture_seating_indoor_02_12")
        .floor(3, 3, "furniture_seating_indoor_02_14")
        .floor(4, 0, "furniture_seating_indoor_02_13")
        .floor(4, 2, "furniture_seating_indoor_02_15")
        .build();

    /**
     * SET P — Vending machine alcove.
     * Source: coord 12872,1701, cell 50_6.
     * Soda + snack machines, metal shelves, wooden bench, bin.
     * Wall-aligned cluster — not a repeating stamp.
     * Bounding box: 5 wide × 3 tall.
     */
    public static final FurnitureSet VENDING_ALCOVE = new Builder(
            "vending_alcove", 5, 3, 6,
            "breakroom")
        .wallRun()
        // Metal shelves (2-tile vertical, FacingE)
        .floor(0, 0, "furniture_shelving_01_25")         // Top
        .floor(0, 1, "furniture_shelving_01_24")         // Bottom
        // Metal shelves (2-tile horizontal, FacingS)
        .floor(2, 0, "furniture_shelving_01_26")
        .floor(3, 0, "furniture_shelving_01_27")
        // Vending machines (FacingS)
        .floor(2, 2, "location_shop_accessories_01_17")  // Large vending
        .floor(3, 2, "location_shop_accessories_01_19")  // Small soda
        // Bin
        .floor(4, 2, "trashcontainers_01_17")            // Green Garbage Bin
        // Wooden bench (FacingN)
        .floor(1, 2, "furniture_seating_indoor_03_67")
        .build();

    /**
     * SET Q — Large breakroom rectangular tables (repeating unit).
     * Source: coord 12775,1631, cell 49_6.
     * 4-tile table block (high_02 _22/_23/_30/_31) with white simple chairs.
     * This is the core repeating stamp for large breakrooms.
     * Bounding box: 4 wide × 2 tall. Stamp with 1-tile aisle between units.
     */
    public static final FurnitureSet BREAKROOM_RECT_TABLE = new Builder(
            "breakroom_rect_table", 4, 2, 5,
            "breakroom", "cafeteria", "diningroom")
        // 2×2 table block (two 2-tile pairs side by side, all FacingS)
        .floor(0, 0, "furniture_tables_high_02_23")      // col A, top
        .floor(0, 1, "furniture_tables_high_02_22")      // col A, bottom
        .floor(1, 0, "furniture_tables_high_02_31")      // col B, top
        .floor(1, 1, "furniture_tables_high_02_30")      // col B, bottom
        // Chairs: S end (_57 FacingS), N end (_59 FacingN), W side (_58 FacingW)
        .floor(0, 3, "furniture_seating_indoor_03_57")   // South
        .floor(1, 3, "furniture_seating_indoor_03_57")
        .floor(0, 4, "furniture_seating_indoor_03_59")   // North (offset row)
        .floor(1, 4, "furniture_seating_indoor_03_59")
        .build();

    /**
     * SET R — Breakroom kitchen wall: counter + sink + fridge + espresso + water.
     * Source: coord 12766,1539, cell 49_6.
     * The standard breakroom appliance run: Seahorse coffee counter, fridge, water dispenser.
     * Bounding box: 4 wide × 2 tall.
     */
    public static final FurnitureSet BREAKROOM_KITCHEN_WALL = new Builder(
            "breakroom_kitchen_wall", 4, 2, 5,
            "breakroom", "cafeteriakitchen")
        .wallRun()
        // Seahorse coffee counter run (3 tiles, FacingS)
        .floor(0, 0, "location_restaurant_seahorse_01_44")  // Coffee corner counter
        .floor(1, 0, "location_restaurant_seahorse_01_45")  // Counter + sink
        .top (1, 0, "fixtures_sinks_01_9")                  // Sink (Chrome, on counter)
        .floor(2, 0, "location_restaurant_seahorse_01_45")  // Counter + espresso
        .top (2, 0, "appliances_cooking_01_56")             // Espresso machine (IsTableTop)
        // Fridge beside counter (FacingE)
        .floor(3, 0, "appliances_refrigeration_01_1")       // Fridge FacingE (White)
        // Water dispenser (FacingE, free-standing)
        .floor(3, 1, "location_business_office_generic_01_48")  // Water Dispenser
        .build();

    /**
     * SET S — Large industrial appliance bank (cafeteria/restaurant).
     * Source: coord 12431,1374, cell 48_5.
     * Chest freezers, mini fridges, red fridges, microwaves on tables, soda fountain.
     * Bounding box: 5 wide × 4 tall. Wall-run cluster.
     */
    public static final FurnitureSet LARGE_APPLIANCE_BANK = new Builder(
            "large_appliance_bank", 5, 4, 8,
            "cafeteriakitchen", "restaurant", "breakroom")
        // Fridges along back wall (FacingS)
        .floor(0, 0, "appliances_refrigeration_01_32")   // Red Fridge
        .floor(1, 0, "appliances_refrigeration_01_32")   // Red Fridge
        .floor(2, 0, "appliances_refrigeration_01_25")   // Mini Fridge
        .floor(3, 0, "appliances_refrigeration_01_25")   // Mini Fridge
        .floor(4, 0, "appliances_cooking_01_21")         // Industrial Oven FacingS
        // Chest freezer pair (FacingW/FacingE)
        .floor(0, 1, "appliances_refrigeration_01_51")   // Freezer FacingW
        .floor(1, 1, "appliances_refrigeration_01_49")   // Freezer FacingE (pair)
        // Microwaves on tables (IsTableTop)
        .floor(2, 2, "furniture_tables_high_01_16")
        .top (2, 2, "appliances_cooking_01_27")          // Microwave FacingN
        .floor(3, 2, "furniture_tables_high_01_16")
        .top (3, 2, "appliances_cooking_01_27")
        // Soda fountain on table (IsTableTop)
        .floor(2, 3, "furniture_tables_high_01_16")
        .top (2, 3, "location_shop_accessories_01_9")    // Soda Fountain
        // Red ovens along side wall (FacingW)
        .floor(4, 2, "appliances_cooking_01_10")
        .floor(4, 3, "appliances_cooking_01_10")
        .build();

    /**
     * SET T — Entertainment / arcade zone.
     * Source: coord 12382,1319, cell 48_5.
     * Arcade machines and pinball for large breakroom lounges.
     * Bounding box: 5 wide × 4 tall.
     */
    public static final FurnitureSet ARCADE_ZONE = new Builder(
            "arcade_zone", 5, 4, 8,
            "breakroom", "lobby")
        // Dr. Oids arcade machine (2-tile vertical, FacingE)
        .floor(0, 0, "recreational_01_17")
        .floor(0, 1, "recreational_01_17")
        // PAWS Pinball (2-tile vertical, FacingE: _26 top, _27 bottom)
        .floor(1, 0, "recreational_01_26")
        .floor(1, 1, "recreational_01_27")
        .floor(2, 0, "recreational_01_26")
        .floor(2, 1, "recreational_01_27")
        // Back-to-back arcade (Dr. Oids FacingN, Kaboom FacingS)
        .floor(3, 2, "recreational_01_19")               // FacingN
        .floor(3, 3, "recreational_01_20")               // FacingS (back-to-back)
        .floor(4, 2, "recreational_01_19")
        .floor(4, 3, "recreational_01_20")
        .build();

    /**
     * SET U — Heavy-duty professional kitchen.
     * Source: coord 12330,1257, cell 48_4.
     * Steel counters, industrial sink, steel fridges, large modern ovens, espresso, bake-o-matic.
     * Bounding box: 6 wide × 4 tall.
     */
    public static final FurnitureSet HEAVY_KITCHEN = new Builder(
            "heavy_kitchen", 6, 4, 8,
            "cafeteriakitchen", "restaurant")
        // Steel fridges (FacingE)
        .floor(0, 0, "appliances_refrigeration_01_9")    // Steel Fridge
        .floor(0, 1, "appliances_refrigeration_01_9")    // Steel Fridge
        // Steel counter + industrial sink
        .floor(0, 2, "fixtures_counters_01_35")
        .top (0, 2, "fixtures_sinks_01_16")              // Dark Industrial Sink (on counter)
        .floor(0, 3, "fixtures_counters_01_35")          // Counter FacingE
        // Bake-O-Matic industrial oven (FacingS)
        .floor(1, 0, "appliances_cooking_01_65")
        // Steel counter run (FacingS, south side)
        .floor(2, 0, "fixtures_counters_01_37")
        .floor(3, 0, "fixtures_counters_01_37")
        // Deluxe espresso on counter (IsTableTop)
        .top (3, 0, "appliances_cooking_01_61")
        // Large modern ovens (pair, FacingS)
        .floor(4, 0, "appliances_cooking_01_42")
        .floor(5, 0, "appliances_cooking_01_43")
        // Back counter island (FacingN, north side)
        .floor(2, 2, "fixtures_counters_01_33")
        .floor(3, 2, "fixtures_counters_01_33")
        .floor(4, 2, "fixtures_counters_01_33")
        // Counter FacingS below island
        .floor(2, 3, "fixtures_counters_01_37")
        .floor(3, 3, "fixtures_counters_01_37")
        .floor(4, 3, "fixtures_counters_01_37")
        // Bin
        .floor(1, 3, "trashcontainers_01_20")
        .build();

    // ------------------------------------------------------------------
    // BATHROOM SETS  (from office_sets2.txt, session 2)
    // ------------------------------------------------------------------

    /**
     * SET V — Sink + mirror vanity run (repeating unit × 4).
     * Source: coord 12861,1715, cell 50_6.
     * Each unit: vanity counter base (FacingN) + mirror above (FacingS, wall-mounted).
     * Stamp this 2–4 times along the N wall.
     * Bounding box: 1 wide × 2 tall per unit.
     */
    public static final FurnitureSet SINK_MIRROR_UNIT = new Builder(
            "sink_mirror_unit", 1, 1, 4,
            "bathroom")
        .wallRun()
        // Counter + sink sits 1 tile from N wall (floor tile).
        // Mirror goes on the N wall square above it.
        .floor(0, 0, "location_hospitality_sunstarmotel_02_20")  // Counter FacingN (Low Motel)
        .top  (0, 0, "fixtures_sinks_01_7")                      // Sink FacingN (on counter)
        .wallN(0, 0, "location_hospitality_sunstarmotel_02_22")  // Mirror on N wall above
        .build();

    /**
     * BATHROOM_STALL_BLOCK_N — 3-stall block for the NORTH wall.
     *
     * Toilets back against N wall (attachedN, FacingS = faces south into room).
     * Back wall: _02_1 WallN stacked on toilet square.
     * Divider:   _02_0 WallW stacked (between stalls).
     * Door:      _02_11 DoorWallN + _02_17 doorN — opens toward south.
     * Bounding box: 3 wide × 2 tall.
     */
    public static final FurnitureSet BATHROOM_STALL_BLOCK_N = new Builder(
            "bathroom_stall_block_n", 3, 2, 5,
            "bathroom")
        // Stall 0
        .floor(0, 0, "fixtures_bathroom_01_0")   // Toilet attachedN FacingS
        .floor(0, 0, "fixtures_bathroom_02_1")   // WallN back
        .floor(0, 1, "fixtures_bathroom_02_11")  // DoorWallN (opens S)
        .floor(0, 1, "fixtures_bathroom_02_17")  // DoorN
        // Stall 1
        .floor(1, 0, "fixtures_bathroom_01_0")
        .floor(1, 0, "fixtures_bathroom_02_1")   // WallN back
        .floor(1, 0, "fixtures_bathroom_02_0")   // WallW divider
        .floor(1, 1, "fixtures_bathroom_02_11")
        .floor(1, 1, "fixtures_bathroom_02_17")
        // Stall 2
        .floor(2, 0, "fixtures_bathroom_01_0")
        .floor(2, 0, "fixtures_bathroom_02_1")   // WallN back
        .floor(2, 0, "fixtures_bathroom_02_0")   // WallW divider
        .floor(2, 1, "fixtures_bathroom_02_11")
        .floor(2, 1, "fixtures_bathroom_02_17")
        .build();

    /**
     * BATHROOM_STALL_BLOCK_W — 3-stall block for the WEST wall.
     *
     * Toilets back against W wall (attachedW, FacingE = faces east into room).
     * Back wall: _02_0 WallW stacked on toilet square.
     * Divider:   _02_1 WallN stacked (between stalls).
     * Door:      _02_10 DoorWallW + _02_16 doorW — opens toward east.
     * Bounding box: 2 wide × 3 tall.
     */
    public static final FurnitureSet BATHROOM_STALL_BLOCK_W = new Builder(
            "bathroom_stall_block_w", 2, 3, 5,
            "bathroom")
        // Stall 0
        .floor(0, 0, "fixtures_bathroom_01_1")   // Toilet attachedW FacingE
        .floor(0, 0, "fixtures_bathroom_02_0")   // WallW back
        .floor(1, 0, "fixtures_bathroom_02_10")  // DoorWallW (opens E)
        .floor(1, 0, "fixtures_bathroom_02_16")  // DoorW
        // Stall 1
        .floor(0, 1, "fixtures_bathroom_01_1")
        .floor(0, 1, "fixtures_bathroom_02_0")   // WallW back
        .floor(0, 1, "fixtures_bathroom_02_1")   // WallN divider
        .floor(1, 1, "fixtures_bathroom_02_10")
        .floor(1, 1, "fixtures_bathroom_02_16")
        // Stall 2
        .floor(0, 2, "fixtures_bathroom_01_1")
        .floor(0, 2, "fixtures_bathroom_02_0")   // WallW back
        .floor(0, 2, "fixtures_bathroom_02_1")   // WallN divider
        .floor(1, 2, "fixtures_bathroom_02_10")
        .floor(1, 2, "fixtures_bathroom_02_16")
        .build();

    /**
     * SET Y — Commercial bathroom: steel counter+sink + metal shelving.
     * Storage/vanity corner placed alongside the stall block.
     * Bounding box: 3 wide × 4 tall.
     */
    public static final FurnitureSet FULL_COMMERCIAL_BATHROOM = new Builder(
            "full_commercial_bathroom", 3, 4, 6,
            "bathroom")
        // Steel counter + sink
        .floor(0, 0, "fixtures_counters_01_35")
        .top  (0, 0, "fixtures_sinks_01_16")
        .floor(0, 1, "fixtures_counters_01_35")
        // Metal shelving (2-tile vertical, for storage)
        .floor(0, 2, "furniture_shelving_01_25")
        .floor(0, 3, "furniture_shelving_01_24")
        // Bin
        .floor(2, 3, "trashcontainers_01_18")
        .build();

    // ------------------------------------------------------------------
    // RESIDENTIAL KITCHEN SETS  (from room_scans.txt, session 3)
    // ------------------------------------------------------------------

    /**
     * SET RK1 — Residential kitchen: N-wall counter run (FacingS).
     * Source: coord 13615,2127 and 13651,2082 (5×4 rooms), room_scans.txt.
     * Pattern seen repeatedly in vanilla: counter + oven + counter+sink + fridge
     * all along the north wall, facing south into the room.
     * Bounding box: 4 wide × 1 tall. wallRun → placed along N or S wall.
     */
    public static final FurnitureSet KITCHEN_COUNTER_RUN_N = new Builder(
            "kitchen_counter_run_n", 4, 1, 4,
            "kitchen")
        .wallRun()
        .unique()                                        // no repeat along the wall
        .provides("counter_run", "sink", "oven", "fridge")  // and nothing else supplies these
        // Every piece is role-resolved. The stamper prefers door-free walls, so
        // this run lands on the SOUTH wall as often as the north — with the old
        // hardcoded FacingS sprites the whole kitchen then faced into the wall.
        .role   (0, 0, "counter")
        .role   (1, 0, "oven")
        .role   (2, 0, "counter")
        .roleTop(2, 0, "sink")                           // rides on the counter below
        .role   (3, 0, "fridge")
        .build();

    /**
     * SET RK2 — Residential kitchen: W-wall counter run (FacingW).
     * Source: coord 13442,2109 large scan (lines 706-724), room_scans.txt.
     * Pattern: counter+sink stacked vertically against the W wall (FacingW = faces east).
     * Then fridge at the bottom. The stamper places this along the W wall.
     * Bounding box: 1 wide × 3 tall. wallRun → placed along W wall (same N-wall logic,
     * stampWallRun handles alignment).
     */
    public static final FurnitureSet KITCHEN_COUNTER_RUN_W = new Builder(
            "kitchen_counter_run_w", 1, 3, 4,
            "kitchen")
        .wallRun()
        .unique()
        .provides("counter_run", "sink", "fridge")       // no oven on this run
        .role   (0, 0, "counter")
        .roleTop(0, 0, "sink")                           // rides on the counter below
        .role   (0, 1, "counter")
        .role   (0, 2, "fridge")
        .build();

    /**
     * SET RK3 — Residential kitchen: minimal 3-unit N-wall run (tiny room safe).
     * Source: coord 13651,2082 (4×3 kitchen), room_scans.txt.
     * For very small kitchens: counter + oven + counter (no fridge on this run).
     * minRoomDim=3 so it fits in the smallest rooms.
     * Bounding box: 3 wide × 1 tall. wallRun.
     */
    public static final FurnitureSet KITCHEN_COUNTER_MINI = new Builder(
            "kitchen_counter_mini", 3, 1, 3,
            "kitchen")
        .wallRun()
        .unique()
        .provides("counter_run", "sink", "oven")         // no fridge on this run
        .role   (0, 0, "counter")
        .roleTop(0, 0, "sink")                           // rides on the counter below
        .role   (1, 0, "oven")
        .role   (2, 0, "counter")
        .build();

    /**
     * SET RK6 — Residential kitchen: single counter with a sink (any wall).
     *
     * A 4x4 kitchen has a 2x2 usable interior once the wall margins are taken,
     * and no counter run in this catalogue fits that. Without this set those
     * rooms came out with a fridge and a stove but nowhere to wash up. One
     * counter tile with the sink on top is the smallest thing that still reads
     * as a kitchen.
     *
     * Claims only "sink", so it is refused whenever a real counter run has
     * already landed — the runs are tried first and carry their own sink.
     * minRoomDim=3. Bounding box: 1 wide × 1 tall. wallRun.
     */
    public static final FurnitureSet KITCHEN_SINK = new Builder(
            "kitchen_sink", 1, 1, 3,
            "kitchen")
        .wallRun()
        .unique()
        .provides("sink")
        .role   (0, 0, "counter")
        .roleTop(0, 0, "sink")
        .build();

    /**
     * SET RK5 — Residential kitchen: standalone oven (any wall).
     *
     * A narrow kitchen only has room for KITCHEN_COUNTER_RUN_W, which carries a
     * sink and a fridge but no stove. Every kitchen needs somewhere to cook, so
     * this fills that gap the same way KITCHEN_FRIDGE fills the missing fridge
     * after KITCHEN_COUNTER_MINI.
     *
     * The tile is resolved through the "oven" role so it faces into the room
     * from whichever wall it lands on — see DIRECTIONAL_OVERRIDES in
     * FurniturePlacer, since the palette has no oven_N/_S/_E/_W split.
     * minRoomDim=3. Bounding box: 1 wide × 1 tall. wallRun.
     */
    public static final FurnitureSet KITCHEN_OVEN = new Builder(
            "kitchen_oven", 1, 1, 3,
            "kitchen")
        .wallRun()
        .unique()
        // Only lands when the chosen counter run did not already bring an oven.
        .provides("oven")
        .role(0, 0, "oven")
        .build();

    /**
     * SET RK4 — Residential kitchen: standalone fridge (any wall).
     * Used when the room is too small for a counter run, or as a second wall object.
     * minRoomDim=3. Bounding box: 1 wide × 1 tall. wallRun.
     */
    public static final FurnitureSet KITCHEN_FRIDGE = new Builder(
            "kitchen_fridge", 1, 1, 3,
            "kitchen")
        .wallRun()
        .unique()
        // Only lands when the chosen counter run did not already bring a fridge
        // (i.e. after KITCHEN_COUNTER_MINI). Never a second fridge.
        .provides("fridge")
        .role(0, 0, "fridge")   // facing resolved from wall direction automatically
        .build();

    // ------------------------------------------------------------------
    // RESIDENTIAL LIVINGROOM SETS  (from room_scans.txt, session 3)
    //
    // NOTE: the TV and the seat are no longer placed through the generic
    // wall-run stamper. That machinery put horizontal sets on N/S walls and
    // square sets on the PERPENDICULAR wall, so a chair could only ever end up
    // at 90 degrees to the television. FurniturePlacer.livingroomLayout() now
    // places the pair directly on opposite walls, aligned on the same axis, so
    // somebody sitting down is looking at the screen. LIVINGROOM_COUCH,
    // LIVINGROOM_TV and LIVINGROOM_CHAIR below are kept as the measured
    // reference for those pieces; only LIVINGROOM_SHELVES_N is still stamped.
    // ------------------------------------------------------------------

    /**
     * SET LR1 — Couch (direction-agnostic, palette-resolved).
     * Source: coord 13442,2109 (g sym) and 13374,2082 (h sym), room_scans.txt.
     * Bounding box: 2 wide × 1 tall. wallRun → placed on N or S wall (hSet, w > h).
     * unique → placed once (hero piece, not a repeating run).
     *
     * At stamp-time stampSet passes the wall's inward facing to resolve("couch", facing):
     *   S wall → facing 'N'  →  couch_N group (back against S wall, seats face north)
     *   N wall → facing 'S'  →  couch_S group (back against N wall, seats face south)
     * put() detects isPairedRole("couch") and extends the second tile east (N/S) or
     * south (E/W) automatically — no hardcoded tile indices needed.
     */
    public static final FurnitureSet LIVINGROOM_COUCH = new Builder(
            "livingroom_couch", 2, 1, 4,
            "livingroom")
        .wallRun()
        .unique()
        .role(0, 0, "couch")
        .build();

    /**
     * SET LR2 — TV on low table (direction-agnostic, palette-resolved TV).
     * Source: coord 13442,2109 (b/c sym) and 12887,1822 (d/e sym), room_scans.txt.
     * Low table + television on top + decorative shelf beside it.
     * Bounding box: 2 wide × 1 tall. wallRun → N or S wall (hSet, w > h).
     * unique → TV appears once.
     *
     * The low table is hardcoded (minor cosmetic variance is fine).
     * The television is resolved from the palette at stamp-time so it faces into
     * the room automatically (facing = opposite of the wall it's on):
     *   N wall → facing 'S'  →  television_S sprite
     *   S wall → facing 'N'  →  television_N sprite
     * roleTop stacks on the SURFACE square the floor(0,0) table creates.
     */
    public static final FurnitureSet LIVINGROOM_TV = new Builder(
            "livingroom_tv", 2, 1, 4,
            "livingroom")
        .wallRun()
        .unique()
        .floor  (0, 0, "furniture_tables_low_01_17")  // Light Low Table (surface for TV)
        .roleTop(0, 0, "television")                   // TV facing auto-selected from wall direction
        .floor  (1, 0, "furniture_shelving_01_44")     // White Fancy Shelves
        .build();

    /**
     * SET LR3 — Shelves on N wall (single-tile, FacingS).
     * Source: coord 13374,2082 (g sym), room_scans.txt.
     * Oakwood shelves for variety. Horizontal: w==h square, goes on any wall.
     * Bounding box: 1 wide × 1 tall. wallRun.
     */
    public static final FurnitureSet LIVINGROOM_SHELVES_N = new Builder(
            "livingroom_shelves_n", 1, 1, 3,
            "livingroom")
        .wallRun()
        .unique()
        .provides("shelves")
        // NOT the "shelves" role: that palette group mixes wall-mounted planks
        // in with the floor bookcases, and a wall sprite dropped on a floor
        // square hangs in mid-air. Named floor bookcase only.
        // Superseded anyway — FurniturePlacer.placeShelfAgainstWall() places the
        // living-room shelf now, so this set is reference, not live.
        .floor(0, 0, "furniture_shelving_01_40")   // Oakwood Shelves FacingS
        .build();

    /**
     * SET LR4 — Soft chair (single-tile, square, any wall).
     * Source: coord 12887,1822 (g sym) and 13442,2109 (h sym), room_scans.txt.
     * Blue Comfy Chair FacingS — placed on N wall facing into room.
     * Bounding box: 1 wide × 1 tall. wallRun.
     * unique → one chair per room, not a row of chairs.
     */
    public static final FurnitureSet LIVINGROOM_CHAIR = new Builder(
            "livingroom_chair", 1, 1, 3,
            "livingroom")
        .wallRun()
        .unique()
        .role(0, 0, "chair_soft")   // facing resolved from wall direction automatically
        .build();

    // ==================================================================
    // CATALOGUE LOOKUP
    // ==================================================================

    /** Every set that exists, in definition order. */
    public static final List<FurnitureSet> ALL = List.of(
            // Large office
            CONF_TABLE, L_DESK, CUBICLE, SINGLE_DESK, DESK_LAMP_FILING,
            DESK_ROW_COMPUTERS, PARTITION_CLUSTER, LIBRARY_SHELVES,
            // Small office
            DESK_ROW_WHITEBOARD, POSH_OFFICE, FILING_LAMP_DESK,
            OFFICE_PAINTING,
            // Breakroom
            BREAKROOM_COUNTER_TABLES, BREAKROOM_ROUND_TABLES,
            VENDING_ALCOVE, BREAKROOM_RECT_TABLE, BREAKROOM_KITCHEN_WALL,
            LARGE_APPLIANCE_BANK, ARCADE_ZONE, HEAVY_KITCHEN,
            // Bathroom
            BATHROOM_STALL_BLOCK_N, BATHROOM_STALL_BLOCK_W,
            SINK_MIRROR_UNIT, FULL_COMMERCIAL_BATHROOM,
            // Residential kitchen
            KITCHEN_COUNTER_RUN_N, KITCHEN_COUNTER_RUN_W,
            KITCHEN_COUNTER_MINI, KITCHEN_FRIDGE, KITCHEN_OVEN, KITCHEN_SINK,
            // Residential livingroom
            LIVINGROOM_COUCH, LIVINGROOM_TV,
            LIVINGROOM_SHELVES_N, LIVINGROOM_CHAIR
    );

    /**
     * All sets appropriate for a room type, that fit inside the given room.
     *
     * @param roomType    the room type string from BuildingPlan
     * @param roomW       room width in tiles
     * @param roomH       room height in tiles
     */
    public static List<FurnitureSet> forRoom(String roomType, int roomW, int roomH) {
        List<FurnitureSet> out = new ArrayList<>();
        int minDim = Math.min(roomW, roomH);
        for (FurnitureSet s : ALL) {
            if (!s.roomTypes.contains(roomType)) continue;
            if (minDim < s.minRoomDim) continue;
            // Set must fit at least once with a 1-tile margin each side.
            if (s.w > roomW - 2 || s.h > roomH - 2) continue;
            out.add(s);
        }
        return out;
    }

    /**
     * Pick one set at random from those that fit, preferring larger sets
     * (a bigger room should get a more elaborate arrangement).
     *
     * Returns null when nothing fits.
     */
    public static FurnitureSet pickFor(String roomType, int roomW, int roomH, Random rng) {
        List<FurnitureSet> candidates = forRoom(roomType, roomW, roomH);
        if (candidates.isEmpty()) return null;
        // Weight by bounding box area — larger sets are more interesting
        int totalWeight = 0;
        int[] weights = new int[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            weights[i] = candidates.get(i).w * candidates.get(i).h;
            totalWeight += weights[i];
        }
        int roll = rng.nextInt(totalWeight);
        int cum = 0;
        for (int i = 0; i < candidates.size(); i++) {
            cum += weights[i];
            if (roll < cum) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
    }
}

package pzformat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Picks concrete vanilla tiles for generated map features.
 *
 * Selection is by PROPERTY, never by hardcoded name — a hardcoded name that
 * does not exist in the player's build renders as nothing, and an invisible
 * tile is indistinguishable from a failed write. Name prefixes are only a
 * preference among tiles that already qualify.
 *
 * A candidate must also exist in the .pack atlases. Tiledefs and sprite
 * atlases are independent sets: 61,418 tiles carry properties but only 45,028
 * have pixels, so a tile can satisfy every semantic filter and still draw
 * nothing.
 *
 * Two selection bugs this replaces, both caused by taking the alphabetically
 * first FLOOR under a prefix:
 *
 *   - grass resolved to blends_natural_01_101, a legal exterior floor that
 *     carries no `grassFloor` flag, so the ground rendered as bare dirt. A
 *     hand-maintained exclusion list dodged the first two dirt blocks and
 *     landed in the third. The flag makes the list unnecessary.
 *
 *   - interior floor resolved to floors_interior_tilesandwood_01_0, which is
 *     "Grey Diagonal Tiles" — a real bathroom tile, correctly rendered, and
 *     wrong for every room in a house. Material=Wood gives hardwood instead.
 */
public final class TilePalette {

    public String floorInterior, floorRoad, floorGrass, floorWater;
    public String windowWallNorth, windowWallWest;
    public String windowObjectNorth, windowObjectWest;
    public String doorFrameNorth, doorFrameWest;
    public String doorObjectNorth, doorObjectWest;
    public String wallNorth, wallWest;
    public String doorWallNorth, doorWallWest;

    /** Corner (WallNW) and pillar (WallSE) — the joined variants. */
    public String wallNW, wallSE;

    /** Partitions between rooms. The exterior sheet reads wrong indoors. */
    public String interiorWallNorth, interiorWallWest;
    public String interiorDoorNorth, interiorDoorWest;
    public String interiorWallNW, interiorWallSE;

    /**
     * Furniture / container tiles, placed by the furniture pass.
     *
     * All tile names verified against sprite atlas 2026-09-20:
     *   counter   — fixtures_counters_01_0        (kitchen, breakroom)
     *   desk      — location_business_office_generic_01_0  (office)
     *   shelves   — furniture_shelving_01_1 (N wall) / _2 (W wall)
     *   fridge    — appliances_refrigeration_01_0  (kitchen, breakroom)
     *   wardrobe  — furniture_storage_01_0         (bedroom)
     */
    public String furnitureCounter;      // against north wall, Facing S
    public String furnitureDeskN;        // against north wall
    public String furnitureShelvesN;     // attachedN — north wall
    public String furnitureShelvesW;     // attachedW — west wall
    public String furnitureFridge;       // kitchen / breakroom
    public String furnitureWardrobe;     // bedroom (two-tile, use sparingly)
    public String furnitureDrawers;      // bedroom sidetable (single-tile, measured)

    /**
     * Roof tiles — placed at z=1 over every building footprint square.
     *
     * Two tiles per square, measured from vanilla 42_36 kitchen at z=1:
     *
     *   ceilingFloor  — the floor tile visible from above and as the ceiling
     *                   from z=0. Property: attachedFloor + solidfloor +
     *                   diamondFloor, WITHOUT exterior (that flag appears on
     *                   industrial roof tiles, not residential ceilings).
     *                   Vanilla: ceilings_01_0.
     *
     *   roofObject    — the pitched-roof overhang object that sits on top of
     *                   the ceiling tile. Property: WestRoofT.
     *                   Vanilla: roofs_30_02_48.
     *
     * Both go on every square inside the building footprint at z=1 with
     * room id -1 (no room membership at z=1).
     */
    public String ceilingFloor;

    /**
     * Roof slope tiles that have BOTH a .tiles definition and a sprite,
     * sorted by their numeric index.
     *
     * The roof pass used to build names by string concatenation
     * ("roofs_30_02_" + n), which skipped the sprite check that every
     * other palette entry goes through. Tiles with a definition but no
     * sprite render as a red question mark in game, which is exactly what
     * happened. Collect the usable ones here instead.
     */
    public final Set<String> roofSlopeNames = new java.util.HashSet<>();

    /**
     * Gable-end wall tiles, sprite-verified, from walls_exterior_roofs_30_03.
     *
     * These carry WallW and draw the triangle that closes the end of a
     * pitched roof. Measured on vanilla 42_36: the square one past the east
     * end of the building runs 61,60,59,58,57 down the far face and
     * 49,50,51,52 down the near one.
     */
    public final Set<String> roofGableNames = new java.util.HashSet<>();

    /**
     * Gable trim objects from roofs_accents_30_01.
     *
     * Vanilla 42_36 puts one of these on the SAME square as the gable wall
     * — the wall alone was written first and did not read as a closed end.
     * Its index tracks the wall's: wall _61 sat with accent _13.
     */
    public final Set<String> roofAccentNames = new java.util.HashSet<>();

    /**
     * Flat / industrial roof tiles from roofs_03_*.
     *
     * These carry the {@code exterior} flag (which the residential ceiling
     * sheet deliberately excludes).  Used for Commercial, Government,
     * Assembly, Education, Industrial buildings, and any building whose
     * HEIGHT implies more than one storey.
     *
     * Not yet measured on a full vanilla column — for now the pass writes
     * only the ceiling tile for flat-roof buildings (same tile as pitched),
     * and this set is reserved for a future object layer once we have
     * measured what vanilla places on top of a warehouse or store.
     */
    public final Set<String> roofFlatNames = new java.util.HashSet<>();

    public final List<String> all = new ArrayList<>();

    /** Candidates that had the right properties but no sprite. */
    public int droppedNoSprite = 0;

    private Set<String> sprites = Set.of();
    private TileIndex ti;

    public static TilePalette pick(TileIndex ti, Set<String> sprites) {
        TilePalette p = new TilePalette();
        p.sprites = sprites;
        p.ti = ti;

        // Ground. `grassFloor` is a bare flag and is the only thing separating
        // grass from dirt in blends_natural_01 — CustomName and Material are
        // both absent on every tile in that sheet. `solidfloor` excludes the
        // FloorOverlay edge-blend variants, which are corner pieces rather
        // than standalone ground.
        p.floorGrass = p.first(n -> flag(ti, n, "grassFloor")
                        && flag(ti, n, "solidfloor")
                        && !flag(ti, n, "FloorOverlay")
                        && !ti.isOverlay(n),
                "blends_natural_01_", "blends_grassoverlays_01_", "blends_");

        // Water. blends_natural_02 carries the `water` flag on its solid tiles.
        // The edge-blend tiles (FloorAttachment*) are separate and handled
        // the same way road edges are — later, not here.
        p.floorWater = p.first(n -> flag(ti, n, "water")
                        && flag(ti, n, "solidfloor")
                        && !flag(ti, n, "FloorOverlay")
                        && !ti.isOverlay(n),
                "blends_natural_02_", "blends_");

        // Road surface. Same overlay exclusion; the street sheet has no
        // grass/nature flags to key off.
        p.floorRoad = p.first(n -> ti.kindOf(n) == TileIndex.Kind.FLOOR
                        && flag(ti, n, "solidfloor")
                        && !flag(ti, n, "FloorOverlay")
                        && !ti.isOverlay(n),
                "blends_street_01_", "floors_exterior_street_01_", "blends_");

        // Interior floor. Wood reads as a house; Brick is bathroom and kitchen
        // tiling. Excluding the nature and exterior flags keeps outdoor ground
        // out of the running when the prefix falls through.
        p.floorInterior = p.first(n -> ti.kindOf(n) == TileIndex.Kind.FLOOR
                        && flag(ti, n, "solidfloor")
                        && !flag(ti, n, "FloorOverlay")
                        && !flag(ti, n, "natureFloor")
                        && !flag(ti, n, "grassFloor")
                        && !flag(ti, n, "exterior")
                        && "Wood".equals(prop(ti, n, "Material"))
                        && !ti.isOverlay(n) && !ti.isStructuralWall(n),
                "floors_interior_tilesandwood_01_", "floors_interior_", "floors_");

        p.wallNorth = p.first(n -> flag(ti, n, "WallN") && !ti.isOverlay(n)
                        && !flag(ti, n, "DoorWallN") && !flag(ti, n, "WindowN"),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");
        p.wallWest = p.first(n -> flag(ti, n, "WallW") && !ti.isOverlay(n)
                        && !flag(ti, n, "DoorWallW") && !flag(ti, n, "WindowW"),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");
        p.doorWallNorth = p.first(n -> flag(ti, n, "DoorWallN") && !ti.isOverlay(n),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");
        p.doorWallWest = p.first(n -> flag(ti, n, "DoorWallW") && !ti.isOverlay(n),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");

        // Window wall tiles — carry WindowN/WindowW alongside WallN/WallW.
        // Used as fallback when the per-building skin has no window variant.
        p.windowWallNorth = p.first(n -> flag(ti, n, "WindowN") && !ti.isOverlay(n),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");
        p.windowWallWest = p.first(n -> flag(ti, n, "WindowW") && !ti.isOverlay(n),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");

        // Window object tiles — the openable frame that sits on the wall.
        // Measured from vanilla 42_38: fixtures_windows_01_25 (north-facing,
        // attachedN) and fixtures_windows_01_16 (west-facing, attachedW).
        p.windowObjectNorth = p.first(n -> flag(ti, n, "windowN") && !ti.isOverlay(n),
                "fixtures_windows_01_", "fixtures_windows_");
        p.windowObjectWest = p.first(n -> flag(ti, n, "windowW") && !ti.isOverlay(n),
                "fixtures_windows_01_", "fixtures_windows_");

        // Door frame objects — sit on the same square as the DoorWall tile.
        // Measured from vanilla 42_38: fixtures_doors_frames_01_1 (north,
        // attachedN + doorFrN) and the west equivalent.
        p.doorFrameNorth = p.first(n -> flag(ti, n, "doorFrN") && !ti.isOverlay(n),
                "fixtures_doors_frames_01_", "fixtures_doors_frames_");
        p.doorFrameWest = p.first(n -> flag(ti, n, "doorFrW") && !ti.isOverlay(n),
                "fixtures_doors_frames_01_", "fixtures_doors_frames_");

        // Door objects — the actual openable door on the same square.
        // Measured from vanilla 42_38: fixtures_doors_01_1 (north, attachedN +
        // doorN). Prefer solid wood doors (no doorTrans).
        p.doorObjectNorth = p.first(n -> flag(ti, n, "doorN") && !ti.isOverlay(n)
                        && !flag(ti, n, "doorTrans"),
                "fixtures_doors_01_", "fixtures_doors_");
        p.doorObjectWest = p.first(n -> flag(ti, n, "doorW") && !ti.isOverlay(n)
                        && !flag(ti, n, "doorTrans"),
                "fixtures_doors_01_", "fixtures_doors_");

        // Interior partitions and their doors. Same property tests as the
        // exterior pair, preferring the interior sheet.
        p.interiorWallNorth = p.first(n -> flag(ti, n, "WallN") && !ti.isOverlay(n)
                        && !flag(ti, n, "DoorWallN") && !flag(ti, n, "WindowN"),
                "walls_interior_house_01_", "walls_interior_", "walls_");
        p.interiorWallWest = p.first(n -> flag(ti, n, "WallW") && !ti.isOverlay(n)
                        && !flag(ti, n, "DoorWallW") && !flag(ti, n, "WindowW"),
                "walls_interior_house_01_", "walls_interior_", "walls_");
        p.interiorDoorNorth = p.first(n -> flag(ti, n, "DoorWallN") && !ti.isOverlay(n),
                "walls_interior_house_01_", "walls_interior_", "walls_");
        p.interiorDoorWest = p.first(n -> flag(ti, n, "DoorWallW") && !ti.isOverlay(n),
                "walls_interior_house_01_", "walls_interior_", "walls_");

        // Corner and pillar variants for wall-joining (A3).
        p.wallNW = p.first(n -> flag(ti, n, "WallNW") && !ti.isOverlay(n),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");
        p.wallSE = p.first(n -> flag(ti, n, "WallSE") && !ti.isOverlay(n),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");

        // Furniture / container tiles.
        // Counter: fixtures_counters_01_0 — kitchen/breakroom, against north wall.
        p.furnitureCounter = p.first(n -> "counter".equals(prop(ti, n, "container"))
                        && !ti.isOverlay(n) && sprites.contains(n),
                "fixtures_counters_01_", "fixtures_counters_");
        // Desk: location_business_office_generic_01_0 — office, against north wall.
        p.furnitureDeskN = p.first(n -> "desk".equals(prop(ti, n, "container"))
                        && !ti.isOverlay(n) && sprites.contains(n),
                "location_business_office_generic_01_", "location_business_");
        // Shelves: furniture_shelving_01_1 (attachedN) and _2 (attachedW).
        p.furnitureShelvesN = p.first(n -> "shelves".equals(prop(ti, n, "container"))
                        && flag(ti, n, "attachedN") && !ti.isOverlay(n) && sprites.contains(n),
                "furniture_shelving_01_", "furniture_shelving_");
        p.furnitureShelvesW = p.first(n -> "shelves".equals(prop(ti, n, "container"))
                        && flag(ti, n, "attachedW") && !ti.isOverlay(n) && sprites.contains(n),
                "furniture_shelving_01_", "furniture_shelving_");
        // Fridge: appliances_refrigeration_01_0 — kitchen/breakroom.
        p.furnitureFridge = p.first(n -> "fridge".equals(prop(ti, n, "container"))
                        && !ti.isOverlay(n) && sprites.contains(n),
                "appliances_refrigeration_01_", "appliances_refrigeration_");
        // Wardrobe: furniture_storage_01_0 — bedroom.
        p.furnitureWardrobe = p.first(n -> "wardrobe".equals(prop(ti, n, "container"))
                        && !ti.isOverlay(n) && sprites.contains(n),
                "furniture_storage_01_", "furniture_storage_");
        // Drawers / sidetable: furniture_storage_01_49 — bedroom, measured from
        // vanilla 42_38. Single-tile object, container=sidetable.
        p.furnitureDrawers = p.first(n -> "sidetable".equals(prop(ti, n, "container"))
                        && !ti.isOverlay(n) && sprites.contains(n),
                "furniture_storage_01_", "furniture_storage_");
        // Ceiling floor tile — covers every building square at z=1.
        // Measured from vanilla 42_36: ceilings_01_0 carries attachedFloor +
        // solidfloor + diamondFloor without the exterior flag. The exterior
        // flag is what industrial roof tiles carry (roofs_03_*) — excluding
        // it keeps us on the residential ceiling sheet.
        p.ceilingFloor = p.first(n -> flag(ti, n, "attachedFloor")
                        && flag(ti, n, "solidfloor")
                        && flag(ti, n, "diamondFloor")
                        && !flag(ti, n, "exterior")
                        && !ti.isOverlay(n),
                "ceilings_01_", "ceilings_");

        // Roof slope tiles. Selected by the WestRoofT property and kept only
        // when a sprite exists — a tile with a .tiles definition but no sprite
        // renders as a red question mark, which is what "missing tile
        // roofs_30_02" in the console was.
        //
        // Stored as a name set rather than an ordered list because the two
        // faces of a pitched roof are DIFFERENT tile ranges, not mirror images
        // of one another: measured on vanilla 42_36 the far face runs 29,28,
        // 27,26,25 from its eave and the near face runs 1,2,3,4 from its own.
        // Reusing one index on both sides points the slope art the wrong way
        // on one of them. GisCells computes the vanilla index per face and
        // checks it against this set.
        for (String n : ti.byName.keySet()) {
            if (!flag(ti, n, "WestRoofT") || ti.isOverlay(n)) continue;
            if (!n.startsWith("roofs_30_02_")) continue;
            if (!sprites.contains(n)) { p.droppedNoSprite++; continue; }
            p.roofSlopeNames.add(n);
        }

        // Gable ends. Same sprite check as the slopes.
        for (String n : ti.byName.keySet()) {
            if (!flag(ti, n, "WallW") || ti.isOverlay(n)) continue;
            if (!n.startsWith("walls_exterior_roofs_30_03_")) continue;
            if (!sprites.contains(n)) { p.droppedNoSprite++; continue; }
            p.roofGableNames.add(n);
        }

        // Gable trim. Name-prefix only: these carry no shared property
        // flag, so the sprite check is what keeps them honest.
        for (String n : ti.byName.keySet()) {
            if (ti.isOverlay(n)) continue;
            if (!n.startsWith("roofs_accents_30_01_")) continue;
            if (!sprites.contains(n)) { p.droppedNoSprite++; continue; }
            p.roofAccentNames.add(n);
        }

        // Flat / industrial roof tiles (roofs_03_*).
        // These carry the exterior flag — the inverse of the residential
        // ceiling check above.  Collected here for future use; the roof pass
        // currently writes only the ceiling tile for flat-roof buildings.
        for (String n : ti.byName.keySet()) {
            if (ti.isOverlay(n)) continue;
            if (!n.startsWith("roofs_03_")) continue;
            if (!sprites.contains(n)) { p.droppedNoSprite++; continue; }
            p.roofFlatNames.add(n);
        }

        p.interiorWallNW = p.first(n -> flag(ti, n, "WallNW") && !ti.isOverlay(n),
                "walls_interior_house_01_", "walls_interior_", "walls_");
        p.interiorWallSE = p.first(n -> flag(ti, n, "WallSE") && !ti.isOverlay(n),
                "walls_interior_house_01_", "walls_interior_", "walls_");

        for (String s : new String[]{p.floorInterior, p.floorRoad, p.floorGrass, p.floorWater,
                p.wallNorth, p.wallWest, p.doorWallNorth, p.doorWallWest,
                p.wallNW, p.wallSE,
                p.interiorWallNorth, p.interiorWallWest,
                p.interiorDoorNorth, p.interiorDoorWest,
                p.interiorWallNW, p.interiorWallSE,
                p.ceilingFloor}) {
            if (s != null && !p.all.contains(s)) {
                p.all.add(s);
            }
        }
        return p;
    }

    /** Sorted numeric indices of the usable gable tiles, for diagnosis. */
    String gableIndices() {
        List<Integer> ix = new ArrayList<>();
        for (String n : roofGableNames) { int i = idxOf(n); if (i >= 0) ix.add(i); }
        Collections.sort(ix);
        return ix.toString();
    }

    /** Trailing numeric index of a tile name, or -1. */
    static int idxOf(String name) {
        int u = name.lastIndexOf('_');
        if (u < 0 || u == name.length() - 1) return -1;
        try { return Integer.parseInt(name.substring(u + 1)); }
        catch (NumberFormatException e) { return -1; }
    }

    /** True if the tile carries {@code prop}, as a bare flag or with a value. */
    static boolean flag(TileIndex ti, String name, String prop) {
        TileDefs.Tile t = ti.get(name);
        return t != null && t.props.containsKey(prop);
    }

    static String prop(TileIndex ti, String name, String key) {
        TileDefs.Tile t = ti.get(name);
        return t == null ? null : t.props.get(key);
    }

    /**
     * A complete exterior wall skin for one building.
     *
     * winN / winW may be null when the skin sheet has no window wall tiles —
     * the window pass falls back to the palette defaults in that case.
     */
    public record WallSkin(String wallN, String wallW, String wallNW, String wallSE,
                           String doorN, String doorW,
                           String winN, String winW) {
        public String label() {
            return wallN.substring(0, wallN.lastIndexOf('_'));
        }
    }

    private static final String[][] SKIN_PREFIXES = {
            {"walls_exterior_house_01_", "walls_exterior_house_"},
            {"walls_exterior_house_02_", "walls_exterior_house_"},
            {"walls_exterior_wooden_01_", "walls_exterior_wooden_"},
            {"walls_exterior_wooden_02_", "walls_exterior_wooden_"},
            // walls_exterior_house_low_01_ is intentionally excluded — low walls
            // can be vaulted by the player, making building exteriors penetrable
            // without a door. Only full-height skins are appropriate here.
    };

    public static List<WallSkin> discoverSkins(TileIndex ti, Set<String> sprites) {
        List<WallSkin> skins = new ArrayList<>();
        TilePalette tmp = new TilePalette();
        tmp.sprites = sprites;
        tmp.ti = ti;
        for (String[] prefixes : SKIN_PREFIXES) {
            String wn = tmp.first(n -> flag(ti, n, "WallN") && !ti.isOverlay(n)
                            && !flag(ti, n, "DoorWallN") && !flag(ti, n, "WindowN"), prefixes);
            String ww = tmp.first(n -> flag(ti, n, "WallW") && !ti.isOverlay(n)
                            && !flag(ti, n, "DoorWallW") && !flag(ti, n, "WindowW"), prefixes);
            String nw = tmp.first(n -> flag(ti, n, "WallNW") && !ti.isOverlay(n), prefixes);
            String se = tmp.first(n -> flag(ti, n, "WallSE") && !ti.isOverlay(n), prefixes);
            String dn = tmp.first(n -> flag(ti, n, "DoorWallN") && !ti.isOverlay(n), prefixes);
            String dw = tmp.first(n -> flag(ti, n, "DoorWallW") && !ti.isOverlay(n), prefixes);
            if (wn != null && ww != null && nw != null && se != null && dn != null && dw != null) {
                // Window wall tiles — same skin sheet, optional. Null when the
                // sheet has no WindowN/WindowW tiles; the window pass falls back
                // to the palette defaults.
                String winn = tmp.first(n -> flag(ti, n, "WindowN") && !ti.isOverlay(n),
                                prefixes);
                String winw = tmp.first(n -> flag(ti, n, "WindowW") && !ti.isOverlay(n),
                                prefixes);
                skins.add(new WallSkin(wn, ww, nw, se, dn, dw, winn, winw));
            }
        }
        return skins;
    }

    /**
     * Resolve the wall configuration on a square to a single tile index.
     * Replaces the old pattern of stacking two straight walls on a corner.
     *
     * @param north true if this square has a wall on its north edge
     * @param west  true if this square has a wall on its west edge
     * @param interior true for interior partition walls
     * @return the tile name, or null if no wall on this square
     */
    public String wallJoin(boolean north, boolean west, boolean interior) {
        if (north && west)  return interior ? interiorWallNW : wallNW;
        if (north)          return interior ? interiorWallNorth : wallNorth;
        if (west)           return interior ? interiorWallWest : wallWest;
        return null;
    }

    /**
     * First qualifying tile, preferring the earliest matching prefix.
     * A tile qualifies only if it also has a sprite.
     */
    String first(Predicate<String> ok, String... prefixes) {
        for (String prefix : prefixes) {
            List<String> hits = new ArrayList<>();
            for (String n : ti.byName.keySet()) {
                if (!n.startsWith(prefix) || !ok.test(n)) {
                    continue;
                }
                if (!sprites.contains(n)) {
                    droppedNoSprite++;
                    continue;
                }
                hits.add(n);
            }
            if (!hits.isEmpty()) {
                Collections.sort(hits);
                return hits.get(0);
            }
        }
        for (String n : new TreeSet<>(ti.byName.keySet())) {
            if (ok.test(n) && sprites.contains(n)) {
                return n;
            }
        }
        return null;
    }

    public boolean complete() {
        return floorInterior != null && floorRoad != null && floorGrass != null
                && wallNorth != null && wallWest != null;
    }

    /**
     * Fail before generating anything. A bad palette entry costs a full
     * regeneration plus an in-game load to notice.
     */
    public void verify() {
        List<String> missing = new ArrayList<>();
        if (floorInterior == null) missing.add("floorInterior");
        if (floorRoad == null) missing.add("floorRoad");
        if (floorGrass == null) missing.add("floorGrass");
        if (floorWater == null) missing.add("floorWater");
        if (wallNorth == null) missing.add("wallNorth");
        if (wallWest == null) missing.add("wallWest");
        if (doorWallNorth == null) missing.add("doorWallNorth");
        if (doorWallWest == null) missing.add("doorWallWest");
        if (interiorWallNorth == null) missing.add("interiorWallNorth");
        if (interiorWallWest == null) missing.add("interiorWallWest");
        if (interiorDoorNorth == null) missing.add("interiorDoorNorth");
        if (interiorDoorWest == null) missing.add("interiorDoorWest");
        if (wallNW == null) missing.add("wallNW");
        if (wallSE == null) missing.add("wallSE");
        if (interiorWallNW == null) missing.add("interiorWallNW");
        if (interiorWallSE == null) missing.add("interiorWallSE");
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "TilePalette: no tile has both the required properties and a sprite for: "
                            + String.join(", ", missing));
        }
    }

    /** Name plus whatever the tiledefs say about it, so the log is legible. */
    private String describe(String name) {
        if (name == null) {
            return "null";
        }
        TileDefs.Tile t = ti.get(name);
        if (t == null) {
            return name;
        }
        String custom = t.props.get("CustomName");
        String material = t.props.get("Material");
        if (custom != null && !custom.isEmpty()) {
            return name + "  (\"" + custom + "\""
                    + (material != null && !material.isEmpty() ? ", " + material : "")
                    + ")";
        }
        List<String> flags = new ArrayList<>();
        for (Map.Entry<String, String> e : t.props.entrySet()) {
            if (e.getValue().isEmpty()) {
                flags.add(e.getKey());
            }
        }
        return name + "  [" + String.join(" ", flags) + "]";
    }

    @Override public String toString() {
        return "floor=" + describe(floorInterior)
                + "\n   road=" + describe(floorRoad)
                + "\n   grass=" + describe(floorGrass)
                + "\n   water=" + describe(floorWater)
                + "\n   wallN=" + describe(wallNorth)
                + "\n   wallW=" + describe(wallWest)
                + "\n   doorN=" + describe(doorWallNorth)
                + "\n   doorW=" + describe(doorWallWest)
                + "\n   doorFrameN=" + describe(doorFrameNorth)
                + "\n   doorFrameW=" + describe(doorFrameWest)
                + "\n   doorObjN=" + describe(doorObjectNorth)
                + "\n   doorObjW=" + describe(doorObjectWest)
                + "\n   winWallN=" + describe(windowWallNorth)
                + "\n   winWallW=" + describe(windowWallWest)
                + "\n   winObjN=" + describe(windowObjectNorth)
                + "\n   winObjW=" + describe(windowObjectWest)
                + "\n   intWallN=" + describe(interiorWallNorth)
                + "\n   intWallW=" + describe(interiorWallWest)
                + "\n   intDoorN=" + describe(interiorDoorNorth)
                + "\n   intDoorW=" + describe(interiorDoorWest)
                + "\n   extNW=" + describe(wallNW)
                + "\n   extSE=" + describe(wallSE)
                + "\n   intNW=" + describe(interiorWallNW)
                + "\n   intSE=" + describe(interiorWallSE)
                + "\n   ceiling=" + describe(ceilingFloor)
                + "\n   roofSlopes=" + roofSlopeNames.size() + " usable"
                + "\n   roofGables=" + roofGableNames.size() + " usable " + gableIndices()
                + "\n   roofAccents=" + roofAccentNames.size() + " usable"
                + "\n   roofFlat=" + roofFlatNames.size() + " usable (roofs_03_*)"
                + "\n   dropped (properties but no sprite): " + droppedNoSprite
                + "\n   counter=" + describe(furnitureCounter)
                + "\n   desk=" + describe(furnitureDeskN)
                + "\n   shelvesN=" + describe(furnitureShelvesN)
                + "\n   shelvesW=" + describe(furnitureShelvesW)
                + "\n   fridge=" + describe(furnitureFridge)
                + "\n   wardrobe=" + describe(furnitureWardrobe)
                + "\n   drawers=" + describe(furnitureDrawers);
    }
}

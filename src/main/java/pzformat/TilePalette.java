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

    public String floorInterior, floorRoad, floorGrass;
    public String wallNorth, wallWest;
    public String doorWallNorth, doorWallWest;

    /** Corner (WallNW) and pillar (WallSE) — the joined variants. */
    public String wallNW, wallSE;

    /** Partitions between rooms. The exterior sheet reads wrong indoors. */
    public String interiorWallNorth, interiorWallWest;
    public String interiorDoorNorth, interiorDoorWest;
    public String interiorWallNW, interiorWallSE;
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
        p.interiorWallNW = p.first(n -> flag(ti, n, "WallNW") && !ti.isOverlay(n),
                "walls_interior_house_01_", "walls_interior_", "walls_");
        p.interiorWallSE = p.first(n -> flag(ti, n, "WallSE") && !ti.isOverlay(n),
                "walls_interior_house_01_", "walls_interior_", "walls_");

        for (String s : new String[]{p.floorInterior, p.floorRoad, p.floorGrass,
                p.wallNorth, p.wallWest, p.doorWallNorth, p.doorWallWest,
                p.wallNW, p.wallSE,
                p.interiorWallNorth, p.interiorWallWest,
                p.interiorDoorNorth, p.interiorDoorWest,
                p.interiorWallNW, p.interiorWallSE}) {
            if (s != null && !p.all.contains(s)) {
                p.all.add(s);
            }
        }
        return p;
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
                + "\n   wallN=" + describe(wallNorth)
                + "\n   wallW=" + describe(wallWest)
                + "\n   doorN=" + describe(doorWallNorth)
                + "\n   doorW=" + describe(doorWallWest)
                + "\n   intWallN=" + describe(interiorWallNorth)
                + "\n   intWallW=" + describe(interiorWallWest)
                + "\n   intDoorN=" + describe(interiorDoorNorth)
                + "\n   intDoorW=" + describe(interiorDoorWest)
                + "\n   extNW=" + describe(wallNW)
                + "\n   extSE=" + describe(wallSE)
                + "\n   intNW=" + describe(interiorWallNW)
                + "\n   intSE=" + describe(interiorWallSE)
                + "\n   dropped (properties but no sprite): " + droppedNoSprite;
    }
}

package pzformat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Picks concrete vanilla tiles for generated map features.
 *
 * Tiles are selected by PROPERTY from the loaded definitions, not hardcoded by
 * name — a hardcoded name that does not exist in the player's build renders as
 * nothing, and an invisible tile is indistinguishable from a failed write.
 * Name prefixes are only a preference among tiles that already qualify.
 *
 * A candidate must ALSO exist in the .pack atlases. Property definitions and
 * sprite atlases are independent sets: a tile can carry every property this
 * class filters on and still have no pixels, in which case it renders as a
 * missing-texture checkerboard. Selecting on properties alone is what put
 * floors_interior_tilesandwood_01_0 in the palette.
 */
public final class TilePalette {

    public String floorInterior, floorRoad, floorGrass;
    public String wallNorth, wallWest;
    public String doorWallNorth, doorWallWest;
    public final List<String> all = new ArrayList<>();

    /** Candidates that had the right properties but no sprite. */
    public int droppedNoSprite = 0;

    private Set<String> sprites = Set.of();

    public static TilePalette pick(TileIndex ti, Set<String> sprites) {
        TilePalette p = new TilePalette();
        p.sprites = sprites;

        p.floorInterior = p.first(ti, n -> ti.kindOf(n) == TileIndex.Kind.FLOOR
                        && !ti.isOverlay(n) && !ti.isStructuralWall(n),
                "floors_interior_tilesandwood_01_", "floors_interior_carpet_01_", "floors_");
        p.floorRoad = p.first(ti, n -> ti.kindOf(n) == TileIndex.Kind.FLOOR && !ti.isOverlay(n),
                "blends_street_01_", "floors_exterior_street_01_", "blends_");

        // The worldgen biomes explicitly exclude blends_natural_01_0/5/6/7 and
        // 64/69/70/71 from placement — picking the first name alphabetically
        // landed on 01_0, which renders as bare dirt rather than grass.
        Set<String> excluded = Set.of(
                "blends_natural_01_0", "blends_natural_01_5",
                "blends_natural_01_6", "blends_natural_01_7",
                "blends_natural_01_64", "blends_natural_01_69",
                "blends_natural_01_70", "blends_natural_01_71");
        p.floorGrass = p.first(ti, n -> ti.kindOf(n) == TileIndex.Kind.FLOOR
                        && !ti.isOverlay(n) && !excluded.contains(n),
                "blends_natural_01_", "blends_grassoverlays_01_", "blends_");

        p.wallNorth = p.first(ti, n -> has(ti, n, "WallN") && !ti.isOverlay(n)
                        && !has(ti, n, "DoorWallN") && !has(ti, n, "WindowN"),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");
        p.wallWest = p.first(ti, n -> has(ti, n, "WallW") && !ti.isOverlay(n)
                        && !has(ti, n, "DoorWallW") && !has(ti, n, "WindowW"),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");
        p.doorWallNorth = p.first(ti, n -> has(ti, n, "DoorWallN") && !ti.isOverlay(n),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");
        p.doorWallWest = p.first(ti, n -> has(ti, n, "DoorWallW") && !ti.isOverlay(n),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");

        for (String s : new String[]{p.floorInterior, p.floorRoad, p.floorGrass,
                p.wallNorth, p.wallWest, p.doorWallNorth, p.doorWallWest}) {
            if (s != null && !p.all.contains(s)) {
                p.all.add(s);
            }
        }
        return p;
    }

    static boolean has(TileIndex ti, String name, String prop) {
        TileDefs.Tile t = ti.get(name);
        return t != null && t.props.containsKey(prop);
    }

    /**
     * First qualifying tile, preferring the earliest matching prefix.
     * A tile qualifies only if it also has a sprite.
     */
    String first(TileIndex ti, Predicate<String> ok, String... prefixes) {
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
     * regeneration plus an in-game load to notice, and the in-game symptom is
     * easy to misread as a WorldGen problem.
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
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "TilePalette: no tile has both the required properties and a sprite for: "
                            + String.join(", ", missing));
        }
    }

    @Override public String toString() {
        return "floor=" + floorInterior + "\n   road=" + floorRoad + "\n   grass=" + floorGrass
                + "\n   wallN=" + wallNorth + "\n   wallW=" + wallWest
                + "\n   doorN=" + doorWallNorth + "\n   doorW=" + doorWallWest
                + "\n   dropped (properties but no sprite): " + droppedNoSprite;
    }
}

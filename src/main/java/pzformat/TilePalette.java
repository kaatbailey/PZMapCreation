package pzformat;

import java.util.*;

/**
 * Picks concrete vanilla tiles for generated map features.
 *
 * Tiles are selected by PROPERTY from the loaded definitions, not hardcoded by
 * name — a hardcoded name that does not exist in the player's build renders as
 * nothing, and an invisible tile is indistinguishable from a failed write.
 * Name prefixes are only a preference among tiles that already qualify.
 */
public final class TilePalette {

    public String floorInterior, floorRoad, floorGrass;
    public String wallNorth, wallWest;
    public String doorWallNorth, doorWallWest;
    public final List<String> all = new ArrayList<>();

    public static TilePalette pick(TileIndex ti) {
        TilePalette p = new TilePalette();

        p.floorInterior = first(ti, n -> ti.kindOf(n) == TileIndex.Kind.FLOOR
                        && !ti.isOverlay(n) && !ti.isStructuralWall(n),
                "floors_interior_tilesandwood_01_", "floors_interior_carpet_01_", "floors_");
        p.floorRoad = first(ti, n -> ti.kindOf(n) == TileIndex.Kind.FLOOR && !ti.isOverlay(n),
                "blends_street_01_", "floors_exterior_street_01_", "blends_");
        p.floorGrass = first(ti, n -> ti.kindOf(n) == TileIndex.Kind.FLOOR && !ti.isOverlay(n),
                "blends_natural_01_", "blends_grassoverlays_01_", "blends_");

        p.wallNorth = first(ti, n -> has(ti, n, "WallN") && !ti.isOverlay(n)
                        && !has(ti, n, "DoorWallN") && !has(ti, n, "WindowN"),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");
        p.wallWest = first(ti, n -> has(ti, n, "WallW") && !ti.isOverlay(n)
                        && !has(ti, n, "DoorWallW") && !has(ti, n, "WindowW"),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");
        p.doorWallNorth = first(ti, n -> has(ti, n, "DoorWallN") && !ti.isOverlay(n),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");
        p.doorWallWest = first(ti, n -> has(ti, n, "DoorWallW") && !ti.isOverlay(n),
                "walls_exterior_house_01_", "walls_exterior_", "walls_");

        for (String s : new String[]{p.floorInterior, p.floorRoad, p.floorGrass,
                p.wallNorth, p.wallWest, p.doorWallNorth, p.doorWallWest})
            if (s != null && !p.all.contains(s)) p.all.add(s);
        return p;
    }

    static boolean has(TileIndex ti, String name, String prop) {
        TileDefs.Tile t = ti.get(name);
        return t != null && t.props.containsKey(prop);
    }

    /** First qualifying tile, preferring the earliest matching prefix. */
    static String first(TileIndex ti, java.util.function.Predicate<String> ok, String... prefixes) {
        for (String prefix : prefixes) {
            List<String> hits = new ArrayList<>();
            for (String n : ti.byName.keySet())
                if (n.startsWith(prefix) && ok.test(n)) hits.add(n);
            if (!hits.isEmpty()) { Collections.sort(hits); return hits.get(0); }
        }
        for (String n : new TreeSet<>(ti.byName.keySet())) if (ok.test(n)) return n;
        return null;
    }

    public boolean complete() {
        return floorInterior != null && floorRoad != null && floorGrass != null
                && wallNorth != null && wallWest != null;
    }

    @Override public String toString() {
        return "floor=" + floorInterior + "\n   road=" + floorRoad + "\n   grass=" + floorGrass
                + "\n   wallN=" + wallNorth + "\n   wallW=" + wallWest
                + "\n   doorN=" + doorWallNorth + "\n   doorW=" + doorWallWest;
    }
}

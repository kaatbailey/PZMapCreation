package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Tile semantics: what a tile IS, not which sprite it draws.
 *
 * Loads every binary .tiles in a media directory (binary rather than the .txt
 * siblings, because mods ship only the binary) and exposes classification over
 * the result.
 *
 * The classification here is a HYPOTHESIS about what the property vocabulary
 * means. `validate` tests it against real map data: walls should lie on room
 * perimeters, floors should cover room interiors. If the numbers disagree, the
 * hypothesis is wrong — the same approach that caught the x/y transposition.
 */
public final class TileIndex {

    public final Map<String, TileDefs.Tile> byName = new HashMap<>();
    public int tilesetCount, fileCount;

    public static TileIndex load(Path mediaDir) throws Exception {
        TileIndex ti = new TileIndex();
        List<Path> bins = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mediaDir, "*.tiles")) {
            for (Path p : ds) bins.add(p);
        }
        Collections.sort(bins);
        for (Path b : bins) {
            try {
                TileBin tb = TileBin.read(b, TileBin.TileShape.COUNT_ONLY, 0);
                ti.fileCount++;
                ti.tilesetCount += tb.tilesets.size();
                for (Map.Entry<String, TileDefs.Tile> e : tb.byName.entrySet())
                    ti.byName.putIfAbsent(e.getKey(), e.getValue());
            } catch (Exception ignored) { }
        }
        return ti;
    }

    public TileDefs.Tile get(String name) { return byName.get(name); }

    // ---------------- classification (hypothesis) ----------------

    public enum Kind { FLOOR, WALL, DOOR, WINDOW, OBJECT, VEGETATION, UNKNOWN }

    /** Which edge of a square a wall occupies. PZ walls are edge-based. */
    public enum Edge { NORTH, WEST, BOTH, NONE }

    public Kind kindOf(String tileName) {
        TileDefs.Tile t = byName.get(tileName);
        if (t == null) return Kind.UNKNOWN;
        if (t.props.containsKey("doorN") || t.props.containsKey("doorW")
                || t.props.containsKey("DoorWallN") || t.props.containsKey("DoorWallW"))
            return Kind.DOOR;
        if (t.props.containsKey("WindowShape")) return Kind.WINDOW;
        if (t.props.containsKey("wall") || t.props.containsKey("WallOverlay")) return Kind.WALL;
        if (t.props.containsKey("tree") || t.props.containsKey("bush")
                || t.props.containsKey("MoveWithWind")) return Kind.VEGETATION;
        if (t.props.containsKey("attachedFloor") || t.props.containsKey("FloorOverlay")
                || tileName.startsWith("floors_") || tileName.startsWith("blends_"))
            return Kind.FLOOR;
        return Kind.OBJECT;
    }

    /**
     * Which edge of a square a wall or wall fixture occupies.
     *
     * The vocabulary, read from real tiles rather than inferred. Case matters:
     *
     *   STRUCTURE (the wall itself)
     *     WallN / WallW / WallNW      plain wall
     *     DoorWallN / DoorWallW       wall containing a door frame
     *     WindowN / WindowW           wall containing a window opening
     *
     *   FIXTURES (mounted in the wall)
     *     doorN / doorW               the door leaf
     *     windowN / windowW           the glass pane
     *
     *   ATTACHED (decoration hung on the wall — NOT a wall)
     *     attachedN / attachedW       grime, trim, signage
     *
     * `wall` is a bare flag meaning "wall-like", with no orientation.
     *
     * An earlier version keyed off attachedN/attachedW and appeared to validate
     * at 99.5% against room geometry. That was a correlated proxy: decoration
     * hangs on walls, so it sits in the same squares. Passing a test is not the
     * same as passing it for the right reason.
     */
    public Edge edgeOf(String tileName) {
        TileDefs.Tile t = byName.get(tileName);
        if (t == null) return Edge.NONE;
        Map<String, String> p = t.props;

        if (p.containsKey("WallNW")) return Edge.BOTH;
        boolean n = p.containsKey("WallN") || p.containsKey("DoorWallN")
                || p.containsKey("WindowN") || p.containsKey("doorN")
                || p.containsKey("windowN");
        boolean w = p.containsKey("WallW") || p.containsKey("DoorWallW")
                || p.containsKey("WindowW") || p.containsKey("doorW")
                || p.containsKey("windowW");
        if (n && w) return Edge.BOTH;
        if (n) return Edge.NORTH;
        if (w) return Edge.WEST;

        boolean an = p.containsKey("attachedN"), aw = p.containsKey("attachedW");
        if (an && aw) return Edge.BOTH;
        if (an) return Edge.NORTH;
        if (aw) return Edge.WEST;
        return Edge.NONE;
    }

    /** The wall itself, as opposed to a fixture in it or decoration on it. */
    public boolean isStructuralWall(String tileName) {
        TileDefs.Tile t = byName.get(tileName);
        if (t == null || isOverlay(tileName)) return false;
        Map<String, String> p = t.props;
        return p.containsKey("WallN") || p.containsKey("WallW") || p.containsKey("WallNW")
                || p.containsKey("DoorWallN") || p.containsKey("DoorWallW")
                || p.containsKey("WindowN") || p.containsKey("WindowW");
    }

    /** A door leaf or window pane mounted in a wall. */
    public boolean isWallFixture(String tileName) {
        TileDefs.Tile t = byName.get(tileName);
        if (t == null) return false;
        Map<String, String> p = t.props;
        return p.containsKey("doorN") || p.containsKey("doorW")
                || p.containsKey("windowN") || p.containsKey("windowW");
    }

    /** Does this wall segment contain a door opening? */
    public boolean isDoorway(String tileName) {
        TileDefs.Tile t = byName.get(tileName);
        return t != null && (t.props.containsKey("DoorWallN") || t.props.containsKey("DoorWallW"));
    }

    /** Does this wall segment contain a window opening? */
    public boolean isWindowWall(String tileName) {
        TileDefs.Tile t = byName.get(tileName);
        return t != null && (t.props.containsKey("WindowN") || t.props.containsKey("WindowW"));
    }

    /**
     * Decoration painted ON a wall or floor rather than the structure itself:
     * grime, blood, rust, moss. These carry attachedN/attachedW like real walls,
     * so without this check they win the "first match" race and an editor would
     * replace the dirt instead of the wall.
     */
    public boolean isOverlay(String tileName) {
        TileDefs.Tile t = byName.get(tileName);
        if (t != null && (t.props.containsKey("WallOverlay")
                || t.props.containsKey("FloorOverlay"))) return true;
        return tileName.startsWith("overlay_");
    }

    /** Container category: counter, shelves, fridge, wardrobe, stove, ... */
    public String containerType(String tileName) {
        TileDefs.Tile t = byName.get(tileName);
        return t == null ? null : t.props.get("container");
    }

    /** Blocks movement. `solid` is opaque, `solidtrans` is see-through (fences). */
    public boolean blocksMovement(String tileName) {
        TileDefs.Tile t = byName.get(tileName);
        return t != null && (t.props.containsKey("solid") || t.props.containsKey("solidtrans"));
    }

    /** Object rotation, not wall orientation. N/S/E/W or null. */
    public String facing(String tileName) {
        TileDefs.Tile t = byName.get(tileName);
        return t == null ? null : t.props.get("Facing");
    }

    // ---------------- vocabulary report ----------------

    public void reportVocabulary(String... keys) {
        for (String key : keys) {
            Map<String, Integer> values = new TreeMap<>();
            int flagOnly = 0;
            for (TileDefs.Tile t : byName.values()) {
                String v = t.props.get(key);
                if (v == null) continue;
                if (v.isEmpty()) flagOnly++;
                else values.merge(v, 1, Integer::sum);
            }
            int total = flagOnly + values.values().stream().mapToInt(Integer::intValue).sum();
            if (total == 0) { System.out.printf("   %-18s absent%n", key); continue; }
            System.out.printf("   %-18s %d tiles", key, total);
            if (flagOnly > 0) System.out.print("   (" + flagOnly + " as a bare flag)");
            System.out.println();
            values.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .forEach(e -> System.out.printf("        %-24s %d%n", e.getKey(), e.getValue()));
        }
    }
}

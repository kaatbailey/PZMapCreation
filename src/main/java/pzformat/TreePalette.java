package pzformat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tree tiles, bucketed by size class and species.
 *
 * The `tree` property is a size class 1-8, matching the WorldGen feature files
 * in media/lua/server/WorldGen/features/tree/ — sapling, plain, jumbo,
 * jumbo_xl, jumbo_xxl across ten species. Classes 5-8 are fully sprite-covered;
 * 1-4 have a handful of tiles with no atlas entry.
 *
 * Species is taken from the tileset name (e_americanhollyJUMBO_1,
 * e_riverbirchJUMBO_1, ...) so a scatter can keep a grove to one species
 * instead of mixing every tree in the game at random.
 *
 * These tiles only became selectable once the legacy .pack layout parsed —
 * JumboTrees1x.pack and JumboTrees2x.pack are both legacy, so before that every
 * tree in the game looked like it had no sprite.
 */
public final class TreePalette {

    /** Small trees for the band close to buildings. */
    public static final int YARD_MIN = 3, YARD_MAX = 4;
    /** Full-grown but not the 192x256 giants. */
    public static final int CANOPY_MIN = 5, CANOPY_MAX = 6;

    public final Map<String, List<String>> canopyBySpecies = new TreeMap<>();
    public final Map<String, List<String>> yardBySpecies = new TreeMap<>();
    /** Every tile name chosen, so the cell header can declare them. */
    public final List<String> all = new ArrayList<>();

    public static TreePalette pick(TileIndex ti, Set<String> sprites) {
        TreePalette p = new TreePalette();

        for (String n : ti.byName.keySet()) {
            TileDefs.Tile t = ti.get(n);
            if (t == null) {
                continue;
            }
            String v = t.props.get("tree");
            if (v == null || v.isEmpty()) {
                continue;
            }
            if (!sprites.contains(n)) {
                continue;
            }
            if (ti.kindOf(n) != TileIndex.Kind.VEGETATION) {
                continue;
            }
            int size;
            try {
                size = Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                continue;
            }

            Map<String, List<String>> bucket;
            if (size >= CANOPY_MIN && size <= CANOPY_MAX) {
                bucket = p.canopyBySpecies;
            } else if (size >= YARD_MIN && size <= YARD_MAX) {
                bucket = p.yardBySpecies;
            } else {
                continue;
            }
            bucket.computeIfAbsent(t.tileset, k -> new ArrayList<>()).add(n);
            p.all.add(n);
        }
        return p;
    }

    public boolean usable() {
        return !canopyBySpecies.isEmpty();
    }

    /** Species names for a band, falling back to canopy when yard is empty. */
    public List<String> species(boolean canopy) {
        Map<String, List<String>> m = canopy || yardBySpecies.isEmpty()
                ? canopyBySpecies : yardBySpecies;
        return new ArrayList<>(m.keySet());
    }

    public List<String> variants(boolean canopy, String species) {
        Map<String, List<String>> m = canopy || yardBySpecies.isEmpty()
                ? canopyBySpecies : yardBySpecies;
        List<String> v = m.get(species);
        if (v == null || v.isEmpty()) {
            v = canopyBySpecies.get(species);
        }
        return v;
    }

    @Override public String toString() {
        int canopyTiles = canopyBySpecies.values().stream().mapToInt(List::size).sum();
        int yardTiles = yardBySpecies.values().stream().mapToInt(List::size).sum();
        return canopyBySpecies.size() + " canopy species (" + canopyTiles + " tiles), "
                + yardBySpecies.size() + " yard species (" + yardTiles + " tiles)";
    }
}

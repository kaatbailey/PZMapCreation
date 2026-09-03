package pzformat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Trees as map data actually stores them.
 *
 * CONFIRMED against vanilla Muldraugh cell 35_35, which authors exactly four
 * tree tiles: vegetation_trees_01_8 through _11. No species tiles appear in
 * any vanilla lotheader.
 *
 * The point that took three wrong iterations to find: the e_redmapleJUMBO_1 /
 * e_virginiapineJUMBOXXL_1 sheets are RENDER-TIME art, not map data. The
 * engine substitutes species and mature size at runtime, driven by the biome.
 * What a map authors is only "a tree of size N here" — which is why these
 * generic tiles carry a bare `tree` value, `solid`, `attachedFloor` and
 * nothing else, and why they have no sprite in any atlas.
 *
 * Consequence worth remembering: species mix and mature size CANNOT be
 * controlled from the lotpack. That belongs in WorldGenOverride.lua biome
 * selection. Placement controls where trees are and how big they start.
 *
 * Sprites are deliberately NOT required here. The correct tiles have none —
 * requiring them is what made this sheet look unusable and sent the earlier
 * attempts off into the species art. The renderer therefore cannot preview
 * trees; only the game can draw them.
 */
public final class TreePalette {

    /** The sheet vanilla authors trees from. */
    public static final String SHEET = "vegetation_trees_01";

    /** A felled tree. One tile, from WorldGen's stumps feature. Has a sprite. */
    public static final String STUMP = "crafted_02_86";

    /** size class -> tile names. Only 1 (sapling) and 2 (tree) exist here. */
    private final Map<Integer, List<String>> bySize = new TreeMap<>();

    /** Every tile name chosen, so the cell header can declare them. */
    public final List<String> all = new ArrayList<>();

    public boolean hasStump;

    public static TreePalette pick(TileIndex ti, Set<String> sprites) {
        TreePalette p = new TreePalette();

        // SORTED, NOT RAW. `TileIndex.byName` is a HashMap, so its iteration
        // order is a function of String.hashCode, table capacity and insertion
        // history. It is stable for a given JDK and input set — which is why
        // the generator measures DETERMINISTIC — but it is not a specification,
        // and it is the order that decides `bySize`'s list order, which decides
        // which tile TreeScatter puts on each of ~7,700 squares.
        //
        // STATE §41 proved this hazard dormant because nothing consumed raw
        // byName order. The A2-gate resolving on 2026-09-02 made TreeScatter
        // live and this call site with it.
        //
        // Reproducing HashMap order in C++ would mean cloning Java's bucket
        // layout and resize behaviour to buy byte-identity with an ARBITRARY
        // ordering that nothing depends on and that a JDK upgrade could change
        // under both trees at once. Sorting removes the dependency instead.
        // TilePalette.first already does exactly this (Collections.sort before
        // taking hits.get(0)), so this matches existing practice rather than
        // inventing a convention.
        //
        // Tile names are ASCII, so Java's String.compareTo (UTF-16 code units)
        // and C++'s std::string operator< (unsigned byte compare) agree. The
        // oracle asserts that rather than assuming it.
        //
        // THIS CHANGES GENERATED MOD OUTPUT. The pre-2026-09-02 baseline is
        // superseded; regenerate before comparing anything.
        List<String> names = new ArrayList<>(ti.byName.keySet());
        Collections.sort(names);

        for (String n : names) {
            TileDefs.Tile t = ti.get(n);
            if (t == null || !SHEET.equals(t.tileset)) {
                continue;
            }
            if (ti.kindOf(n) != TileIndex.Kind.VEGETATION) {
                continue;
            }
            String v = t.props.get("tree");
            if (v == null || v.isEmpty()) {
                continue;
            }
            // `solid` separates trunks from ground cover on this sheet: the
            // non-solid VEGETATION entries are bushes and grass clumps.
            if (!t.props.containsKey("solid")) {
                continue;
            }
            int size;
            try {
                size = Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            p.bySize.computeIfAbsent(size, k -> new ArrayList<>()).add(n);
            p.all.add(n);
        }

        if (sprites.contains(STUMP)) {
            p.hasStump = true;
            p.all.add(STUMP);
        }
        return p;
    }

    /** Tiles at a size class, falling back to the nearest available. */
    public List<String> tilesNear(int size) {
        List<String> exact = bySize.get(size);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }
        for (int d = 1; d <= 8; d++) {
            List<String> lo = bySize.get(size - d);
            if (lo != null && !lo.isEmpty()) {
                return lo;
            }
            List<String> hi = bySize.get(size + d);
            if (hi != null && !hi.isEmpty()) {
                return hi;
            }
        }
        return null;
    }

    public boolean usable() {
        return !bySize.isEmpty();
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, List<String>> e : bySize.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("size ").append(e.getKey()).append(": ")
                    .append(e.getValue().size()).append(" tiles");
        }
        return sb + (hasStump ? ", stumps available" : ", NO stump tile")
                + "  (no sprites by design; the engine substitutes species art)";
    }
}

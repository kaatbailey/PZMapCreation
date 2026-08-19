package pzformat;

import java.nio.file.Path;
import java.util.*;

/**
 * A3-pre2: Does the wall variant cycle hold across tilesets?
 *
 * The observed pattern in `walls_exterior_house_01`:
 *   _0  WallW       (west wall)
 *   _1  WallN       (north wall)
 *   _2  WallNW      (corner, both edges)
 *   _3  WallSE      (pillar/post, no edge)
 *   ... repeats every 4 ...
 *
 * With door variants (DoorWallW, DoorWallN) and window variants appearing
 * at offsets 8–11 or similar within the same tileset.
 *
 * If this is a CONVENTION across all wall tilesets, A3 can index into the
 * cycle arithmetically: given a wall tile, its partners are at known offsets.
 * If it's per-tileset, A3 needs a lookup table or must read the properties
 * every time.
 *
 * This tool scans every tile in the index, groups by tileset (the prefix
 * before the final _N suffix), and for each wall tileset reports the flag
 * pattern at each position mod 4.
 *
 * Usage:
 *   java -cp out pzformat.WallCycle "$PZ/media"
 */
public final class WallCycle {

    /** Which wall flag(s) a tile carries, as a short label. */
    static String wallType(TileIndex ti, String name) {
        TileDefs.Tile t = ti.get(name);
        if (t == null) return "-";
        Map<String, String> p = t.props;

        List<String> flags = new ArrayList<>();
        if (p.containsKey("WallNW"))     flags.add("NW");
        if (p.containsKey("WallN"))      flags.add("WN");
        if (p.containsKey("WallW"))      flags.add("WW");
        if (p.containsKey("WallSE"))     flags.add("SE");
        if (p.containsKey("DoorWallN"))  flags.add("DN");
        if (p.containsKey("DoorWallW"))  flags.add("DW");
        if (p.containsKey("WindowN"))    flags.add("WiN");
        if (p.containsKey("WindowW"))    flags.add("WiW");

        if (flags.isEmpty()) {
            // Not a wall tile — could be trim, curtain, etc.
            if (p.containsKey("WallOverlay")) return "ovl";
            if (p.containsKey("attachedN") || p.containsKey("attachedW")) return "att";
            return "-";
        }
        return String.join("+", flags);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: WallCycle MEDIA_DIR");
            return;
        }
        TileIndex ti = TileIndex.load(Path.of(args[0]));

        // Group tiles by tileset prefix. A tile named "walls_exterior_house_01_42"
        // has prefix "walls_exterior_house_01" and index 42.
        Map<String, TreeMap<Integer, String>> tilesets = new TreeMap<>();
        for (String name : ti.byName.keySet()) {
            if (!name.startsWith("walls_")) continue;
            int lastUnderscore = name.lastIndexOf('_');
            if (lastUnderscore < 0) continue;
            String prefix = name.substring(0, lastUnderscore);
            String suffix = name.substring(lastUnderscore + 1);
            int idx;
            try { idx = Integer.parseInt(suffix); }
            catch (NumberFormatException e) { continue; }
            String wt = wallType(ti, name);
            tilesets.computeIfAbsent(prefix, k -> new TreeMap<>()).put(idx, wt);
        }

        // The hypothesis: position mod 4 determines the type.
        //   0 → WW (WallW)     or DW (DoorWallW)   or WiW (WindowW)
        //   1 → WN (WallN)     or DN (DoorWallN)    or WiN (WindowN)
        //   2 → NW (WallNW corner)
        //   3 → SE (WallSE pillar)
        //
        // Count how often each mod-4 position matches its expected type.

        System.out.printf("%-40s %5s  mod4 pattern (first 20 tiles)%n", "tileset", "tiles");
        System.out.println("=".repeat(100));

        int conforming = 0, nonConforming = 0;
        Map<Integer, Map<String, Integer>> globalMod4 = new TreeMap<>();
        for (int i = 0; i < 4; i++) globalMod4.put(i, new TreeMap<>());

        for (Map.Entry<String, TreeMap<Integer, String>> e : tilesets.entrySet()) {
            String prefix = e.getKey();
            TreeMap<Integer, String> tiles = e.getValue();

            // Only care about tilesets that have actual wall tiles
            boolean hasWall = false;
            for (String wt : tiles.values()) {
                if (wt.contains("W") || wt.contains("N") || wt.contains("SE")) {
                    hasWall = true; break;
                }
            }
            if (!hasWall) continue;

            // Build the mod-4 pattern for the first 20 tiles
            StringBuilder pattern = new StringBuilder();
            boolean followsCycle = true;
            int checked = 0;
            for (Map.Entry<Integer, String> te : tiles.entrySet()) {
                int idx = te.getKey();
                String wt = te.getValue();
                int mod = idx % 4;

                globalMod4.get(mod).merge(wt, 1, Integer::sum);

                if (idx < 20) {
                    pattern.append(String.format("%2d:%-5s ", idx, wt));
                }

                // Check against hypothesis (only for actual wall tiles)
                if (!wt.equals("-") && !wt.equals("ovl") && !wt.equals("att")) {
                    checked++;
                    boolean ok = switch (mod) {
                        case 0 -> wt.contains("WW") || wt.contains("DW") || wt.contains("WiW");
                        case 1 -> wt.contains("WN") || wt.contains("DN") || wt.contains("WiN");
                        case 2 -> wt.contains("NW");
                        case 3 -> wt.contains("SE");
                        default -> false;
                    };
                    if (!ok) followsCycle = false;
                }
            }

            if (checked > 0) {
                if (followsCycle) conforming++; else nonConforming++;
            }
            System.out.printf("%-40s %5d  %s%s%n", prefix, tiles.size(),
                    pattern.toString().trim(),
                    checked > 0 ? (followsCycle ? "  ✓" : "  ✗ BREAKS CYCLE") : "");
        }

        System.out.println("\n" + "=".repeat(100));
        System.out.println("SUMMARY");
        System.out.printf("  tilesets following the cycle: %d%n", conforming);
        System.out.printf("  tilesets breaking the cycle:  %d%n", nonConforming);
        System.out.printf("  rate: %.1f%%%n",
                (conforming + nonConforming) > 0
                        ? 100.0 * conforming / (conforming + nonConforming) : 0);

        System.out.println("\nGLOBAL mod-4 distribution (across all wall tilesets):");
        String[] expect = {"WallW/DoorW/WinW", "WallN/DoorN/WinN", "WallNW corner", "WallSE pillar"};
        for (int mod = 0; mod < 4; mod++) {
            System.out.printf("  mod %d (expect %s):%n", mod, expect[mod]);
            globalMod4.get(mod).entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(6)
                    .forEach(me -> System.out.printf("    %-12s %d%n", me.getKey(), me.getValue()));
        }
    }
}

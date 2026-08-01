package pzformat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Tile property definitions -- the semantic layer.
 *
 * Parsed from the plaintext `*.tiles.txt` files shipped alongside the binary
 * `.tiles`. This is what tells an editor that a tile IS a wall facing south,
 * or a door, or a container -- as opposed to just a sprite index.
 *
 * Format:
 *   version = 1
 *   tileset {
 *       file = advertising_01
 *       size = 8,16          (width, height in tiles)
 *       id   = 88
 *       // advertising_01_0  <- authoritative name, used here to verify indexing
 *       tile {
 *           xy = 0,0
 *           Facing = S       <- key/value
 *           solid  =         <- valueless key: a boolean flag
 *       }
 *   }
 *
 * Tile name is `file + "_" + (y * width + x)`. That formula is checked against
 * the `//` comment for every tile rather than assumed.
 */
public final class TileDefs {

    public final Map<String, Tile> byName = new LinkedHashMap<>();
    public final List<Tileset> tilesets = new ArrayList<>();
    public final Map<String, Tileset> tilesetByFile = new LinkedHashMap<>();
    public int nameMismatches = 0;
    public final List<String> mismatchSamples = new ArrayList<>();

    public static final class Tileset {
        public String file;
        public int width, height, id = -1;
        public final List<Tile> tiles = new ArrayList<>();
    }

    public static final class Tile {
        public String name, tileset;
        public int x, y, index;
        public final Map<String, String> props = new LinkedHashMap<>();

        public boolean flag(String k) { return props.containsKey(k); }
        public String get(String k) { return props.get(k); }
        /** Wall/object facing: N, S, E, W, or null. */
        public String facing() { return props.get("Facing"); }
        public boolean solid() { return props.containsKey("solid"); }

        @Override public String toString() { return name + " " + props; }
    }

    public static TileDefs readAll(Path mediaDir) throws IOException {
        TileDefs td = new TileDefs();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mediaDir, "*.tiles.txt")) {
            for (Path p : ds) files.add(p);
        }
        Collections.sort(files);
        for (Path f : files) td.parse(f);
        return td;
    }

    public void parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        Tileset ts = null;
        Tile tile = null;
        String pendingName = null;
        int depth = 0;
        String blockKind = null;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("//")) {
                pendingName = line.substring(2).trim();
                continue;
            }
            if (line.equals("tileset")) { blockKind = "tileset"; continue; }
            if (line.equals("tile"))    { blockKind = "tile";    continue; }
            if (line.equals("{")) {
                depth++;
                if ("tileset".equals(blockKind)) { ts = new Tileset(); tilesets.add(ts); }
                else if ("tile".equals(blockKind)) { tile = new Tile(); }
                blockKind = null;
                continue;
            }
            if (line.equals("}")) {
                depth--;
                if (tile != null) { finishTile(ts, tile, pendingName); tile = null; pendingName = null; }
                else if (depth == 0) ts = null;
                continue;
            }

            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();

            if (tile != null) {
                if (key.equals("xy")) {
                    String[] p = val.split(",");
                    tile.x = Integer.parseInt(p[0].trim());
                    tile.y = Integer.parseInt(p[1].trim());
                } else {
                    tile.props.put(key, val);
                }
            } else if (ts != null) {
                switch (key) {
                    case "file" -> { ts.file = val; tilesetByFile.put(val, ts); }
                    case "id"   -> ts.id = Integer.parseInt(val.trim());
                    case "size" -> {
                        String[] p = val.split(",");
                        ts.width = Integer.parseInt(p[0].trim());
                        ts.height = Integer.parseInt(p[1].trim());
                    }
                    default -> { }
                }
            }
        }
    }

    private void finishTile(Tileset ts, Tile tile, String commentName) {
        if (ts == null) return;
        tile.tileset = ts.file;
        tile.index = tile.y * ts.width + tile.x;
        tile.name = ts.file + "_" + tile.index;
        if (commentName != null && !commentName.equals(tile.name)) {
            nameMismatches++;
            if (mismatchSamples.size() < 8)
                mismatchSamples.add("computed " + tile.name + " but comment says " + commentName);
        }
        ts.tiles.add(tile);
        byName.put(tile.name, tile);
    }

    // ------------------------------------------------------------------

    public static void run(Path mediaDir, Path lotheader) throws Exception {
        System.out.println("== tile definitions from " + mediaDir);
        TileDefs td = readAll(mediaDir);
        System.out.println("tilesets: " + td.tilesets.size() + "   tiles: " + td.byName.size());
        System.out.println("name check (computed vs // comment): "
                + (td.nameMismatches == 0 ? "ALL MATCH — index formula confirmed"
                                          : td.nameMismatches + " mismatches"));
        for (String m : td.mismatchSamples) System.out.println("   " + m);

        Map<String, Integer> keys = new HashMap<>();
        int flagCount = 0, valued = 0;
        for (Tile t : td.byName.values())
            for (Map.Entry<String, String> e : t.props.entrySet()) {
                keys.merge(e.getKey(), 1, Integer::sum);
                if (e.getValue().isEmpty()) flagCount++; else valued++;
            }
        System.out.println("property assignments: " + (flagCount + valued)
                + "  (" + flagCount + " boolean flags, " + valued + " with values)");

        List<Map.Entry<String, Integer>> top = new ArrayList<>(keys.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        System.out.println("\ndistinct property keys: " + keys.size() + " — top 30:");
        for (int i = 0; i < Math.min(30, top.size()); i++)
            System.out.printf("   %-28s %d%n", top.get(i).getKey(), top.get(i).getValue());

        System.out.println("\neditor-relevant keys present:");
        for (String k : new String[]{"wall", "WallOverlay", "Facing", "attachedN", "attachedW",
                                     "doorN", "doorW", "DoorWallN", "DoorWallW", "WindowShape",
                                     "container", "ContainerCapacity", "solid", "solidtrans",
                                     "BlocksPlacement", "attachedFloor", "IsMoveAble", "CanScrap"}) {
            Integer c = keys.get(k);
            System.out.printf("   %-20s %s%n", k, c == null ? "(absent)" : c.toString());
        }

        // The join that matters: do the tile names in a real cell resolve?
        if (lotheader != null) {
            LotHeader h = LotHeader.read(lotheader);
            int found = 0;
            List<String> missing = new ArrayList<>();
            for (String n : h.tileNames) {
                if (td.byName.containsKey(n)) found++;
                else if (missing.size() < 10) missing.add(n);
            }
            System.out.println("\n=== join against " + lotheader.getFileName() + " ===");
            System.out.printf("   %d / %d tile names resolved  (%.2f%%)%n",
                    found, h.tileNames.size(), 100.0 * found / h.tileNames.size());
            // Are the unresolved names inside a known tileset's grid? If so the
            // tile exists in the atlas and simply carries no properties, which
            // is why the text dump omits it.
            int insideGrid = 0, unknownTileset = 0, outsideGrid = 0;
            List<String> oddities = new ArrayList<>();
            for (String n : h.tileNames) {
                if (td.byName.containsKey(n)) continue;
                int us = n.lastIndexOf('_');
                String tsName = us < 0 ? n : n.substring(0, us);
                int idx;
                try { idx = Integer.parseInt(n.substring(us + 1)); }
                catch (Exception e) { oddities.add(n + " (unparseable index)"); continue; }
                Tileset ts = td.tilesetByFile.get(tsName);
                if (ts == null) { unknownTileset++; if (oddities.size() < 6) oddities.add(n + " (no tileset '" + tsName + "')"); }
                else if (idx < ts.width * ts.height) insideGrid++;
                else { outsideGrid++; if (oddities.size() < 6) oddities.add(n + " (index " + idx + " >= " + (ts.width * ts.height) + ")"); }
            }
            int unresolved = h.tileNames.size() - found;
            System.out.println("\n   unresolved breakdown (" + unresolved + " names):");
            System.out.println("      inside tileset grid, no properties : " + insideGrid);
            System.out.println("      tileset not in any .tiles.txt      : " + unknownTileset);
            System.out.println("      index beyond tileset grid          : " + outsideGrid);
            for (String o : oddities) System.out.println("         " + o);
            if (insideGrid == unresolved)
                System.out.println("      => all unresolved tiles exist but carry no properties (benign)");

            System.out.println("\n   sample resolved tiles with properties:");
            int shown = 0;
            for (String n : h.tileNames) {
                Tile t = td.byName.get(n);
                if (t == null || t.props.isEmpty()) continue;
                System.out.printf("      %-34s facing=%-4s solid=%-5s %s%n",
                        t.name, t.facing() == null ? "-" : t.facing(), t.solid(),
                        firstFew(t.props, 4));
                if (++shown >= 8) break;
            }
        }
    }

    static String firstFew(Map<String, String> m, int n) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey());
            if (!e.getValue().isEmpty()) sb.append("=").append(e.getValue());
            sb.append(" ");
            if (++i >= n) break;
        }
        return sb.toString().trim();
    }
}

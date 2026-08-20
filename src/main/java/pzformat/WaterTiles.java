package pzformat;

import java.nio.file.Path;
import java.util.*;

/**
 * Find water-related tiles in PZ's tile index.
 * Usage: java -cp out pzformat.WaterTiles MEDIA_DIR
 */
public final class WaterTiles {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.out.println("usage: WaterTiles MEDIA_DIR"); return; }
        TileIndex ti = TileIndex.load(Path.of(args[0]));
        Set<String> sprites = SpriteNames.load(Path.of(args[0]).resolve("texturepacks"));

        System.out.println("=== tiles with 'water' in name or properties ===");
        int count = 0;
        Map<String, List<String>> byPrefix = new TreeMap<>();
        for (Map.Entry<String, TileDefs.Tile> e : ti.byName.entrySet()) {
            String name = e.getKey();
            TileDefs.Tile t = e.getValue();
            boolean match = name.toLowerCase().contains("water");
            if (!match) {
                for (Map.Entry<String, String> p : t.props.entrySet()) {
                    if (p.getKey().toLowerCase().contains("water")
                            || p.getValue().toLowerCase().contains("water")) {
                        match = true; break;
                    }
                }
            }
            if (match) {
                String prefix = name.contains("_") ?
                        name.substring(0, name.lastIndexOf('_')) : name;
                byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(name);
                count++;
            }
        }
        System.out.println("total: " + count);
        for (Map.Entry<String, List<String>> e : byPrefix.entrySet()) {
            List<String> tiles = e.getValue();
            String first = tiles.get(0);
            boolean hasSprite = sprites.contains(first);
            TileDefs.Tile t = ti.get(first);
            System.out.printf("  %-40s %3d tiles  sprite=%s  props=%s%n",
                    e.getKey(), tiles.size(), hasSprite,
                    t != null ? t.props.keySet() : "?");
        }

        // Also check for blends_natural water-related tiles
        System.out.println("\n=== blends_natural tiles with water/river/stream props ===");
        for (String name : new TreeSet<>(ti.byName.keySet())) {
            if (!name.startsWith("blends_natural")) continue;
            TileDefs.Tile t = ti.get(name);
            for (String key : t.props.keySet()) {
                if (key.toLowerCase().contains("water") || key.toLowerCase().contains("river")) {
                    System.out.printf("  %-50s %s  sprite=%s%n", name, t.props.keySet(), sprites.contains(name));
                    break;
                }
            }
        }

        // Check for floor tiles with Water material
        System.out.println("\n=== floor tiles with Material=Water ===");
        int waterFloors = 0;
        for (Map.Entry<String, TileDefs.Tile> e : ti.byName.entrySet()) {
            if ("Water".equals(e.getValue().props.get("Material"))) {
                if (waterFloors < 10)
                    System.out.printf("  %-50s sprite=%s%n", e.getKey(), sprites.contains(e.getKey()));
                waterFloors++;
            }
        }
        System.out.println("  total Material=Water: " + waterFloors);
    }
}

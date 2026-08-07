package pzformat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Composes an outdoor square the way a WorldGen biome composes one.
 *
 * Replaces a table of tile weights measured from Muldraugh. That table
 * described one hand-authored town, not the generator, so it could not blend
 * into procedurally generated surroundings and would not adapt to a different
 * biome or a game update. This reads the same definitions the engine reads, so
 * naming the biome that surrounds an import produces matching ground by
 * construction.
 *
 * Layers, per the biome definition:
 *
 *   GROUND   every square, one tile chosen uniformly from the feature's list
 *   PLANT    at the stated p, e.g. grass_medium at 0.3
 *   BUSH     at the stated p, e.g. bush_regular at 0.01
 *
 * TREE is deliberately excluded. Authored map data cannot select species or
 * mature size — the engine substitutes those at runtime — so trees are placed
 * by TreeScatter using the generic vegetation_trees_01 tiles instead.
 *
 * At most one PLANT and at most one BUSH per square. Vanilla ground carried at
 * most one decoration layer in 257,703 sampled squares, so stacking several
 * would not match anything observed.
 *
 * A feature whose tiles have no tile definition is dropped and reported: it
 * cannot be authored, only generated.
 */
public final class BiomePalette {

    public record Layer(String category, String feature, double p, List<String> tiles) { }

    public final String biome;
    public final List<Layer> ground = new ArrayList<>();
    public final List<Layer> plants = new ArrayList<>();
    public final List<Layer> bushes = new ArrayList<>();
    public final List<String> all = new ArrayList<>();
    public final List<String> droppedFeatures = new ArrayList<>();
    public int tilesWithoutSprite;

    private BiomePalette(String biome) {
        this.biome = biome;
    }

    public static BiomePalette of(String biomeName,
                                  WorldGenBiomes biomes,
                                  WorldGenFeatures features,
                                  TileIndex ti,
                                  Set<String> sprites) {
        BiomePalette p = new BiomePalette(biomeName);
        if (!biomes.byName.containsKey(biomeName)) {
            throw new IllegalArgumentException("unknown biome '" + biomeName
                    + "'. Known: " + biomes.byName.keySet());
        }

        for (WorldGenBiomes.Entry e : biomes.resolved(biomeName)) {
            List<String> declared = features.tiles(e.category(), e.feature());
            if (declared == null) {
                p.droppedFeatures.add(e.category() + "." + e.feature() + " (no such feature)");
                continue;
            }
            List<String> usable = new ArrayList<>();
            for (String n : declared) {
                if (ti.get(n) == null) {
                    continue;                       // cannot be authored at all
                }
                if (!sprites.contains(n)) {
                    p.tilesWithoutSprite++;
                }
                usable.add(n);
            }
            if (usable.isEmpty()) {
                p.droppedFeatures.add(e.category() + "." + e.feature()
                        + " (" + declared.size() + " tiles, none have tiledefs)");
                continue;
            }

            Layer layer = new Layer(e.category(), e.feature(), e.p(), usable);
            switch (e.category()) {
                case "GROUND" -> p.ground.add(layer);
                case "PLANT" -> p.plants.add(layer);
                case "BUSH" -> p.bushes.add(layer);
                default -> { }                      // TREE, ORE: not authored here
            }
            p.all.addAll(usable);
        }

        if (p.ground.isEmpty()) {
            throw new IllegalStateException("biome '" + biomeName
                    + "' yielded no usable GROUND feature. Dropped: " + p.droppedFeatures);
        }
        return p;
    }

    /** Tile names for one square, ground first. Never empty. */
    public List<String> roll(Random rng) {
        List<String> out = new ArrayList<>(2);
        out.add(pick(rng, ground));

        String plant = maybe(rng, plants);
        if (plant != null) {
            out.add(plant);
        } else {
            String bush = maybe(rng, bushes);
            if (bush != null) {
                out.add(bush);
            }
        }
        return out;
    }

    /** Weighted choice of layer, then a uniform tile from it. */
    private static String pick(Random rng, List<Layer> layers) {
        double total = 0;
        for (Layer l : layers) {
            total += l.p();
        }
        double r = rng.nextDouble() * (total <= 0 ? 1 : total);
        double run = 0;
        for (Layer l : layers) {
            run += l.p();
            if (r <= run) {
                return l.tiles().get(rng.nextInt(l.tiles().size()));
            }
        }
        Layer last = layers.get(layers.size() - 1);
        return last.tiles().get(rng.nextInt(last.tiles().size()));
    }

    /** At most one entry, each tested at its own stated probability. */
    private static String maybe(Random rng, List<Layer> layers) {
        for (Layer l : layers) {
            if (rng.nextDouble() < l.p()) {
                return l.tiles().get(rng.nextInt(l.tiles().size()));
            }
        }
        return null;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder(biome);
        sb.append("\n      GROUND ");
        for (Layer l : ground) {
            sb.append(l.feature()).append(" (").append(l.tiles().size()).append(" tiles) ");
        }
        if (!plants.isEmpty()) {
            sb.append("\n      PLANT  ");
            for (Layer l : plants) {
                sb.append(l.feature()).append(" p=").append(l.p())
                        .append(" (").append(l.tiles().size()).append(") ");
            }
        }
        if (!bushes.isEmpty()) {
            sb.append("\n      BUSH   ");
            for (Layer l : bushes) {
                sb.append(l.feature()).append(" p=").append(l.p())
                        .append(" (").append(l.tiles().size()).append(") ");
            }
        }
        if (tilesWithoutSprite > 0) {
            sb.append("\n      ").append(tilesWithoutSprite)
                    .append(" tiles have no sprite (renderer cannot preview those)");
        }
        for (String d : droppedFeatures) {
            sb.append("\n      dropped ").append(d);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------- dump

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: BiomePalette <mediadir> [biome] [samples]");
            return;
        }
        java.nio.file.Path media = java.nio.file.Path.of(args[0]);
        String name = args.length > 1 ? args[1] : "grass_plain";
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 12;

        TileIndex ti = TileIndex.load(media);
        Set<String> sprites = SpriteNames.load(media.resolve("texturepacks"));
        WorldGenFeatures feats = WorldGenFeatures.load(media);
        WorldGenBiomes biomes = WorldGenBiomes.load(media);

        System.out.println("features: " + feats.featuresFound
                + "   biomes: " + biomes.byName.size());
        BiomePalette p = of(name, biomes, feats, ti, sprites);
        System.out.println("\n" + p);

        System.out.println("\nsample squares:");
        Random rng = new Random(1);
        for (int i = 0; i < samples; i++) {
            System.out.println("   " + String.join(" + ", p.roll(rng)));
        }
    }
}

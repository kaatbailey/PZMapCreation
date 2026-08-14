package pzformat;

/**
 * The natural ground materials, their tile blocks, and their blend priority.
 *
 * Priority is MEASURED, not derived — STATE §27, over 4,065 cells of the retail
 * Muldraugh map. The higher-priority material supplies the mask tile; the
 * lower-priority square carries it.
 *
 *   Grass_Dark > Grass_Medium > Grass_Light > Sand > Dirt_Grass > Dirt > Clay
 *
 * It is not block-index order (0, 16, 32, 48, 64, 80, 96 against a priority
 * order of 16, 32, 48, 0, 80, 64, 96) and it is not brightness — dark grass
 * outranks light grass, but pale Sand outranks dark Dirt. Do not try to
 * compute it. Do not re-measure it.
 *
 * Every pair shows reversals in vanilla, at rates from 1 in 3,000 to 1 in
 * 36,000 for the natural materials. That is a noise floor of hand-edits, not a
 * rule — STATE §27. We author from the strict table.
 *
 * Block layout, for E9's mask pass (STATE §26, §27):
 *   B+0, B+5, B+6, B+7    solid variants, interchangeable
 *   B+1..B+4              corner masks NW, ES, SW, EN
 *   B+8..B+15             side masks N, W, E, S in two variant sets
 *
 * Clay is the exception: 28 mask indices spanning 97-127 where the block
 * predicts 97-100 and 104-111. Emit only the predicted range.
 */
public enum GroundMaterial {

    GRASS_DARK   ("Grass_Dark",   16, 0),
    GRASS_MEDIUM ("Grass_Medium", 32, 1),
    GRASS_LIGHT  ("Grass_Light",  48, 2),
    SAND         ("Sand",          0, 3),
    DIRT_GRASS   ("Dirt_Grass",   80, 4),
    DIRT         ("Dirt",         64, 5),
    CLAY         ("Clay",         96, 6);

    /** The FloorMaterial property value, as it appears in the tiledefs. */
    public final String floorMaterial;

    /** First index of this material's 16-tile block in blends_natural_01. */
    public final int block;

    /** 0 is highest priority. Lower rank masks onto higher rank. */
    public final int rank;

    GroundMaterial(String floorMaterial, int block, int rank) {
        this.floorMaterial = floorMaterial;
        this.block = block;
        this.rank = rank;
    }

    /** The four interchangeable solid variants for this material. */
    public int[] solidIndices() {
        return new int[]{block, block + 5, block + 6, block + 7};
    }

    /** Tile name of one solid variant, chosen uniformly — STATE §21. */
    public String solid(java.util.Random rng) {
        int[] v = solidIndices();
        return GroundPalette.BASE_SHEET + v[rng.nextInt(v.length)];
    }

    /** True when this material's masks are drawn onto {@code other}. */
    public boolean outranks(GroundMaterial other) {
        return other != null && this.rank < other.rank;
    }

    public static GroundMaterial byFloorMaterial(String s) {
        for (GroundMaterial m : values())
            if (m.floorMaterial.equals(s)) return m;
        return null;
    }
}

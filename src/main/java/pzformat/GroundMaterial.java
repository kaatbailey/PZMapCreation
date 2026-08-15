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

    //            FloorMaterial   sheet     block  solid variants        sets rank
    GRASS_DARK   ("Grass_Dark",   Sheet.NATURAL,  16, new int[]{16,21,22,23},   2,  0),
    GRASS_MEDIUM ("Grass_Medium", Sheet.NATURAL,  32, new int[]{32,37,38,39},   2,  1),
    GRASS_LIGHT  ("Grass_Light",  Sheet.NATURAL,  48, new int[]{48,53,54,55},   2,  2),
    SAND         ("Sand",         Sheet.NATURAL,   0, new int[]{0,5,6,7},       2,  3),
    DIRT_GRASS   ("Dirt_Grass",   Sheet.NATURAL,  80, new int[]{80,85,86,87},   2,  4),
    DIRT         ("Dirt",         Sheet.NATURAL,  64, new int[]{64,69,70,71},   2,  5),
    CLAY         ("Clay",         Sheet.NATURAL,  96, new int[]{96,101,102,103},2,  6),

    // Roads. Road_01 and Road_02 have only TWO solid variants — B+6 and B+7
    // are spriteless in blends_street_01. One mask variant set, not two.
    ROAD_01      ("Road_01",      Sheet.STREET,    0, new int[]{0,5},           1, 10),
    ROAD_02      ("Road_02",      Sheet.STREET,   16, new int[]{16,21},         1, 11),
    ROAD_04      ("Road_04",      Sheet.STREET,   48, new int[]{48,53,54,55},   1, 12),
    ROAD_03      ("Road_03",      Sheet.STREET,   32, new int[]{32,37,38,39},   1, 13),
    ROAD_05      ("Road_05",      Sheet.STREET,   64, new int[]{64,69,70,71},   1, 14),
    ROAD_07      ("Road_07",      Sheet.STREET,   96, new int[]{96,101,102,103},1, 15),
    ROAD_06      ("Road_06",      Sheet.STREET, 80, new int[]{80,85,86,87}, 1, 16);

    /**
     * Sheet names live in a nested class because Java forbids an enum
     * constant's arguments from referencing a static field of the same enum —
     * the constants are initialised before any static field exists. A nested
     * type is initialised on first use, so this is legal and keeps the names
     * in one place.
     */
    static final class Sheet {
        static final String NATURAL = "blends_natural_01_";
        static final String STREET = "blends_street_01_";
        private Sheet() { }
    }

    public static final String NATURAL = Sheet.NATURAL;
    public static final String STREET = Sheet.STREET;

    /** The FloorMaterial property value, as it appears in the tiledefs. */
    public final String floorMaterial;

    /** Tile name prefix. Masks key on FloorMaterial, never on sheet (§27). */
    public final String sheet;

    /** First index of this material's block. */
    public final int block;

    /** The interchangeable solid variants. NOT uniform across blocks. */
    private final int[] solids;

    /** 2 for blends_natural_01, 1 for blends_street_01 (§27). */
    public final int variantSets;

    /** 0 is highest priority. Lower rank masks onto higher rank. */
    public final int rank;

    GroundMaterial(String floorMaterial, String sheet, int block,
                   int[] solids, int variantSets, int rank) {
        this.floorMaterial = floorMaterial;
        this.sheet = sheet;
        this.block = block;
        this.solids = solids;
        this.variantSets = variantSets;
        this.rank = rank;
    }

    /** The interchangeable solid variants for this material. */
    public int[] solidIndices() {
        return solids.clone();
    }

    /** Tile name of one solid variant, chosen uniformly — STATE §21. */
    public String solid(java.util.Random rng) {
        return sheet + solids[rng.nextInt(solids.length)];
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

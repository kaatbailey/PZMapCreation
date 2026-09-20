package pzformat;

/**
 * Roof and layout classification for a building, derived from GIS attributes.
 *
 * This is the single place that maps OCC_CLS / PRIM_OCC / HEIGHT / area onto
 * a treatment category.  All other code (roof pass, wall skin selection, room
 * recipe) consumes this enum rather than re-implementing the taxonomy.
 *
 * Categories
 * ----------
 * RESIDENTIAL   — single/multi-family dwellings. Pitched roof, house skin,
 *                 living-room recipe.
 * AGRICULTURE   — farms, barns. Pitched roof, house skin, barn recipe.
 * OUTBUILDING   — sheds, garages (OUTBLDG flag). Pitched roof, small recipe.
 * FLAT_ROOF     — commercial, government, assembly, education, industrial, or
 *                 any building whose height implies more than one storey.
 *                 Flat roof (ceiling tile only), appropriate skin and recipe
 *                 to be added in later passes.
 */
public enum BuildingClass {

    RESIDENTIAL,
    AGRICULTURE,
    OUTBUILDING,
    FLAT_ROOF;

    /**
     * Minimum building height (metres) that forces FLAT_ROOF regardless of
     * OCC_CLS.  A typical single-storey residential building is 3–4 m to the
     * eave; anything at or above two storeys (~6 m) should have a flat or
     * low-pitch roof in PZ terms.
     */
    private static final double FLAT_ROOF_HEIGHT_M = 6.0;

    /**
     * Classify a building from its GIS attributes.
     *
     * @param occ         OCC_CLS value, may be null or empty
     * @param outbuilding OUTBLDG flag from the dataset
     * @param heightM     HEIGHT in metres, 0 or negative if absent/null
     */
    public static BuildingClass of(String occ, boolean outbuilding, double heightM) {

        // Outbuilding flag overrides OCC_CLS — a barn annex tagged Residential
        // is still a shed.
        if (outbuilding) return OUTBUILDING;

        // Height override: anything tall enough to be multi-storey gets a flat
        // roof regardless of occupancy class.  Null/0 heights are ignored.
        if (heightM >= FLAT_ROOF_HEIGHT_M) return FLAT_ROOF;

        if (occ == null || occ.isEmpty()) return RESIDENTIAL;

        return switch (occ) {
            case "Residential"   -> RESIDENTIAL;
            case "Agriculture"   -> AGRICULTURE;
            case "Commercial",
                 "Government",
                 "Assembly",
                 "Education",
                 "Industrial"    -> FLAT_ROOF;
            // "Unclassified" and anything unknown defaults to residential so
            // small unknown buildings still get a pitched roof.
            default              -> RESIDENTIAL;
        };
    }

    /** Convenience: derive from a {@link GisImport.Building}. */
    public static BuildingClass of(GisImport.Building b) {
        return of(b.occ(), b.outbuilding(), b.heightM());
    }
}

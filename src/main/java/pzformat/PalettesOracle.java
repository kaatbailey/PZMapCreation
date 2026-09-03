package pzformat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Random;
import java.util.Set;

/**
 * Cross-language oracle for the palette layer (Pattern B — canonical text
 * digest). Track F port step 5.
 *
 * WHY THIS EXISTS. §40 measured the general case: ten deliberate mutations of
 * BuildingPlan left its own self-test byte-identical, because a wrong result
 * that is still internally consistent prints the same summary. A test that
 * cannot fail proves nothing (CHARTER §4). This digest emits every field of
 * every constant and every draw of every seed, so a divergence localises to one
 * value rather than to "the palettes".
 *
 * CORPUS RULE (STATE §39). Built from arithmetic and java.util.Random ONLY. No
 * transcendentals — step 3's first measurement was contaminated because its
 * generator used Math.cos, so the INPUTS differed between trees before the unit
 * under test ran. It reported 81 divergences; the real number was 122.
 *
 * SECTIONS PRESENT. GroundMaterial only, at time of writing. GroundPalette,
 * TilePalette and MaskRule append their own sections; the section tags are
 * prefixed so a later addition cannot reorder an earlier one. TreePalette is
 * DEFERRED on the A2-gate (STATE §25) and has no section here — that is
 * deliberate, not an omission. See FINDINGS_F4.
 *
 * WHAT IS DELIBERATELY NOT DIGESTED. Java's byFloorMaterial(null) returns null
 * (String.equals(null) is false, it does not throw). C++ has no null
 * string_view equivalent to pass, so the case is untestable across trees and is
 * asserted on the Java side only, below. Same for outranks(null), which IS
 * cross-testable because C++ can pass nullptr, and so is digested.
 *
 * Usage:  java pzformat.PalettesOracle <out-path> [seeds]
 */
public final class PalettesOracle {

    /** Escape control characters so a value can never split a digest line. */
    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (c == '\t') b.append("\\t");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c < 0x20) b.append(String.format("\\x%02x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    /** Raw IEEE-754 bits, so a double is compared exactly rather than printed. */
    static String bits(double d) {
        return String.format("%016x", Double.doubleToRawLongBits(d));
    }

    static String ints(int[] v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) b.append(',');
            b.append(v[i]);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- GM ---

    /**
     * GroundMaterial. Six sub-sections, each testing something the others
     * cannot:
     *
     *   GM      the constant table in DECLARATION order. Declaration order is
     *           the contract — values() returns it and ROAD_04 precedes
     *           ROAD_03 despite ranking after it, so a table sorted by rank
     *           diverges here and only here.
     *   GMRANK  the same constants in rank order. Catches a rank typo that
     *           happens to leave declaration order intact.
     *   GMOUT   the full outranks matrix, 14x14 plus the null column.
     *   GMBFM   byFloorMaterial over hits and deliberate misses.
     *   GMSOLID solid() with a FRESH Random per (seed, material).
     *   GMSEQ   solid() over ONE SHARED stream. This is the section that
     *           catches a wrong DRAW COUNT: a port drawing twice per call
     *           produces identical GMSOLID lines and diverges here from the
     *           second call onward.
     */
    static void groundMaterial(List<String> out, int seeds) {
        for (GroundMaterial m : GroundMaterial.values()) {
            out.add("GM\t" + m.ordinal() + "\t" + m.name()
                    + "\t" + esc(m.floorMaterial)
                    + "\t" + esc(m.sheet)
                    + "\t" + m.block
                    + "\t" + ints(m.solidIndices())
                    + "\t" + m.variantSets
                    + "\t" + m.rank);
        }

        GroundMaterial[] byRank = GroundMaterial.values().clone();
        // Insertion sort on rank: no library comparator, so both trees run the
        // same algorithm on the same data. Ranks are distinct, so the sort is
        // total and stability is not load-bearing.
        for (int i = 1; i < byRank.length; i++) {
            GroundMaterial key = byRank[i];
            int j = i - 1;
            while (j >= 0 && byRank[j].rank > key.rank) {
                byRank[j + 1] = byRank[j];
                j--;
            }
            byRank[j + 1] = key;
        }
        for (GroundMaterial m : byRank) {
            out.add("GMRANK\t" + m.rank + "\t" + m.name() + "\t" + m.ordinal());
        }

        for (GroundMaterial a : GroundMaterial.values()) {
            for (GroundMaterial b : GroundMaterial.values()) {
                out.add("GMOUT\t" + a.name() + "\t" + b.name()
                        + "\t" + (a.outranks(b) ? 1 : 0));
            }
            out.add("GMOUT\t" + a.name() + "\tNULL\t" + (a.outranks(null) ? 1 : 0));
        }

        // Hits, then misses the real tiledefs would never produce: case
        // variants, near-misses, empty, and a road index that does not exist.
        List<String> probes = new ArrayList<>();
        for (GroundMaterial m : GroundMaterial.values()) probes.add(m.floorMaterial);
        probes.add("");
        probes.add(" ");
        probes.add("grass_dark");
        probes.add("GRASS_DARK");
        probes.add("Grass_Dark ");
        probes.add(" Grass_Dark");
        probes.add("Grass_Darkk");
        probes.add("Grass_Dar");
        probes.add("Road_08");
        probes.add("Road_00");
        probes.add("blends_natural_01_");
        probes.add("Water");
        for (String p : probes) {
            GroundMaterial m = GroundMaterial.byFloorMaterial(p);
            out.add("GMBFM\t" + esc(p) + "\t" + (m == null ? "NULL" : m.name()));
        }

        for (int s = 0; s < seeds; s++) {
            for (GroundMaterial m : GroundMaterial.values()) {
                Random rng = new Random(s);
                out.add("GMSOLID\t" + s + "\t" + m.name() + "\t" + esc(m.solid(rng)));
            }
        }

        // One stream, drawn across materials in a fixed rotation. Draw count is
        // part of the contract; this is where a wrong one shows up.
        Random shared = new Random(20260901L);
        GroundMaterial[] vs = GroundMaterial.values();
        for (int i = 0; i < seeds * 4; i++) {
            GroundMaterial m = vs[i % vs.length];
            out.add("GMSEQ\t" + i + "\t" + m.name() + "\t" + esc(m.solid(shared)));
        }

        // Clone independence: Java returns solids.clone(), so mutating the
        // result must not touch the constant. A port returning a reference
        // diverges here — and nowhere else, because no caller in either tree
        // mutates it. See FINDINGS_F4 on why that is kept anyway.
        for (GroundMaterial m : GroundMaterial.values()) {
            int[] a = m.solidIndices();
            for (int i = 0; i < a.length; i++) a[i] = -999;
            out.add("GMCLONE\t" + m.name() + "\t" + ints(m.solidIndices()));
        }
    }

    // ---------------------------------------------------------------- MR ---

    /** Blocks the real sheets use, plus three the install would never produce. */
    static final int[] MR_BLOCKS = {0, 16, 32, 48, 64, 80, 96, 240, -16};

    static MaskRule.Dir[] dirsOf(int bits) {
        MaskRule.Dir[] all = MaskRule.Dir.values();
        List<MaskRule.Dir> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) if (((bits >> i) & 1) != 0) out.add(all[i]);
        return out.toArray(new MaskRule.Dir[0]);
    }

    static java.util.EnumSet<MaskRule.Dir> setOf(int bits) {
        java.util.EnumSet<MaskRule.Dir> s = java.util.EnumSet.noneOf(MaskRule.Dir.class);
        for (MaskRule.Dir d : dirsOf(bits)) s.add(d);
        return s;
    }

    /**
     * MaskRule. The self-test is NOT the oracle — §29 records it passing while
     * N and W were transposed, and §40 measured ten mutations surviving
     * BuildingPlan's. Four sub-sections:
     *
     *   MRDIR    the direction table and opposite(), the thing §29's bug lived in.
     *   MRCORNER corner() over every ADJACENT ordered pair. Both orders of each
     *            pair, because corner() claims to be unordered — if it is not,
     *            this is where that shows.
     *   MRSET    masks() EXHAUSTIVELY over all 16 direction sets, nine blocks
     *            and both variantSets values, fresh Random per call. The real
     *            generator reaches maybe six of the sixteen; the |S|=3 cases and
     *            the empty set are the ones no corpus drawn from upstream would
     *            produce (§40's lesson).
     *   MRSEQ    the same over ONE SHARED stream. side() draws ONLY when
     *            variantSets > 1, so the draw count is BRANCH-DEPENDENT: an
     *            opposite pair draws twice, a corner draws none. A port that
     *            draws unconditionally matches every MRSET line and diverges
     *            here.
     */
    static void maskRule(List<String> out, int seeds) {
        for (MaskRule.Dir d : MaskRule.Dir.values()) {
            out.add("MRDIR\t" + d.name() + "\t" + d.ord + "\t" + d.dx + "\t" + d.dy
                    + "\t" + d.opposite().name());
        }

        for (int block : MR_BLOCKS) {
            for (MaskRule.Dir a : MaskRule.Dir.values()) {
                for (MaskRule.Dir b : MaskRule.Dir.values()) {
                    if (a == b || a.opposite() == b) continue;   // not adjacent
                    out.add("MRCORNER\t" + block + "\t" + a.name() + "\t" + b.name()
                            + "\t" + MaskRule.corner(block, a, b));
                }
            }
        }

        int reps = seeds / 10;
        for (int s = 0; s < reps; s++) {
            for (int bits = 0; bits < 16; bits++) {
                for (int block : MR_BLOCKS) {
                    for (int vs = 1; vs <= 2; vs++) {
                        Random rng = new Random(s);
                        int[] m = MaskRule.masks(block, setOf(bits), vs, rng);
                        out.add("MRSET\t" + s + "\t" + bits + "\t" + block + "\t" + vs
                                + "\t" + ints(m));
                    }
                }
            }
        }

        Random shared = new Random(20260901L);
        for (int i = 0; i < seeds * 4; i++) {
            int bits = i % 16;
            int block = MR_BLOCKS[(i / 16) % MR_BLOCKS.length];
            int vs = 1 + (i % 2);
            int[] m = MaskRule.masks(block, setOf(bits), vs, shared);
            out.add("MRSEQ\t" + i + "\t" + bits + "\t" + block + "\t" + vs
                    + "\t" + ints(m));
        }
    }

    // ---------------------------------------------------------------- GP ---

    /**
     * The candidate tile universe for the synthetic corpus, in a FIXED order
     * that both trees must generate identically.
     *
     * Deliberately wider than the real sheets:
     *   - the 12 base tiles of the three kept groups
     *   - the 8 DIRT / DIRT_GRASS tiles, which pick() must ignore entirely
     *   - tuft rows 0..9 x cols 0..7 — one row past TUFT_ROW_WEIGHT.length and
     *     two columns past TUFT_USABLE_COLS, so the corpus contains names the
     *     unit must decline to use, and names whose rowWeightOf falls through
     *     to the 0.1 default.
     */
    static List<String> gpCandidates() {
        List<String> c = new ArrayList<>();
        for (GroundPalette.BaseGroup g : GroundPalette.GROUPS)
            for (int idx : g.indices()) c.add(GroundPalette.BASE_SHEET + idx);
        for (int idx : GroundPalette.DIRT) c.add(GroundPalette.BASE_SHEET + idx);
        for (int idx : GroundPalette.DIRT_GRASS) c.add(GroundPalette.BASE_SHEET + idx);
        for (int row = 0; row < 10; row++)
            for (int col = 0; col < 8; col++)
                c.add(GroundPalette.TUFT_SHEET + (row * 8 + col));
        return c;
    }

    /**
     * Inclusion percentage per candidate, by corpus mode. Chosen so that each
     * branch of pick() and roll() is reached by hundreds of cases:
     *
     *   0  everything present            -> three groups kept, all tufts
     *   1  bases present, NO tufts       -> the short-circuit path in roll(),
     *                                       where the tuft nextDouble() is
     *                                       never drawn
     *   2  bases nearly all present      -> a group dropped WHOLE for one
     *                                       missing variant
     *   3  bases thinned                 -> one or two groups surviving
     *   4  no bases at all               -> pick() throws
     *   5  bases likely, tufts rare      -> mixed, many zero-tuft cases
     *   6  bases present, tufts thinned  -> short tuft lists, exercising the
     *                                       cumulative walk near its ends
     *   7  everything coin-flipped       -> the original uniform case, kept
     */
    static int gpThreshold(int mode, boolean isTuft) {
        switch (mode) {
            case 0: return 100;
            case 1: return isTuft ? 0 : 100;
            case 2: return isTuft ? 100 : 95;
            case 3: return isTuft ? 50 : 75;
            case 4: return isTuft ? 100 : 0;
            case 5: return isTuft ? 10 : 90;
            case 6: return isTuft ? 20 : 100;
            default: return 50;
        }
    }

    static TileDefs.Tile mkTile(String name) {
        TileDefs.Tile t = new TileDefs.Tile();
        t.name = name;
        return t;
    }

    static String groundStr(GroundPalette.Ground g) {
        return esc(g.base()) + "\t" + (g.tuft() == null ? "NULL" : esc(g.tuft()));
    }

    /**
     * GroundPalette over a synthetic corpus.
     *
     * WHY SYNTHETIC (STATE §40). A corpus drawn from the unit's own upstream
     * cannot reach every branch — step 4's digest missed two of ten mutations
     * because `recipe` always emits `bathroom` first. Here the real install
     * exercises exactly one path: everything present, all three groups kept.
     * The interesting branches are the failure paths, and none of them occur on
     * a healthy install:
     *
     *   - a group missing ONE variant, which must drop the group WHOLE
     *   - every group dropped, which must THROW
     *   - zero surviving tufts, which changes roll()'s DRAW COUNT because Java
     *     short-circuits `!tufts.isEmpty() && rng.nextDouble() < rate`
     *   - a name present in the tiledefs but absent from sprites, and vice
     *     versa — the two halves of pick()'s test, which a real install always
     *     satisfies together
     *
     * Each case draws a 4-way choice per candidate: absent / tiledefs-only /
     * sprites-only / both.
     */
    static void groundPalette(List<String> out, int seeds) {
        List<String> cand = gpCandidates();

        for (int c = 0; c < seeds; c++) {
            // MODE, not uniform randomness. The first version of this corpus
            // drew a flat 4-way choice per candidate; a group survives only if
            // all FOUR of its base tiles land in both sets, p = (1/4)^4 = 1/256,
            // so 4,914 of 5,000 cases threw, ZERO kept three groups and ZERO
            // reached the empty-tuft path. It was byte-identical anyway, which
            // is precisely how a weak corpus hides (STATE §40). Modes fix the
            // inclusion rates so every branch is reached at scale.
            //
            // Two draws per candidate ALWAYS, whatever the mode, so the rng
            // stream stays aligned across modes and a mode change cannot be
            // confused with a draw-count change.
            int mode = c % 8;
            Random rng = new Random(c);
            TileIndex ti = new TileIndex();
            java.util.HashSet<String> sprites = new java.util.HashSet<>();
            for (String n : cand) {
                int a = rng.nextInt(100);
                int b = rng.nextInt(100);
                boolean isTuft = n.startsWith(GroundPalette.TUFT_SHEET);
                int p = gpThreshold(mode, isTuft);
                if (a < p) ti.byName.putIfAbsent(n, mkTile(n));
                if (b < p) sprites.add(n);
            }

            GroundPalette gp;
            try {
                gp = GroundPalette.pick(ti, sprites);
            } catch (IllegalStateException e) {
                // The MESSAGE, not just the fact. It carries the dropped list,
                // which is the only observable `dropped` has. Digesting only
                // "it threw" let a mutation that stops listing after the first
                // missing variant pass unnoticed — see FINDINGS_F4 M20.
                out.add("GPTHROW\t" + c + "\t" + esc(e.getMessage()));
                continue;
            }

            StringBuilder allJoin = new StringBuilder();
            for (int i = 0; i < gp.all.size(); i++) {
                if (i > 0) allJoin.append(',');
                allJoin.append(esc(gp.all.get(i)));
            }
            out.add("GPALL\t" + c + "\t" + gp.all.size() + "\t" + allJoin);
            out.add("GPTOSTR\t" + c + "\t" + esc(gp.toString()));

            Random rr = new Random(c * 7919L + 1);
            for (int i = 0; i < 20; i++) {
                out.add("GPROLL\t" + c + "\t" + i + "\t" + groundStr(gp.roll(rr)));
            }
        }

        // Everything present: the one case a real install produces. Rolled over
        // ONE SHARED stream, so a wrong draw count shows here and not in GPROLL.
        TileIndex full = new TileIndex();
        java.util.HashSet<String> fullSprites = new java.util.HashSet<>();
        for (String n : cand) { full.byName.putIfAbsent(n, mkTile(n)); fullSprites.add(n); }
        GroundPalette gpFull = GroundPalette.pick(full, fullSprites);
        out.add("GPFULL\t" + gpFull.all.size() + "\t" + esc(gpFull.toString()));
        Random shared = new Random(20260901L);
        for (int i = 0; i < seeds * 4; i++) {
            out.add("GPSEQ\t" + i + "\t" + groundStr(gpFull.roll(shared)));
        }

        // rowWeightOf over every candidate tuft name, including row 9 which
        // falls through to the 0.1 default.
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 8; col++) {
                String n = GroundPalette.TUFT_SHEET + (row * 8 + col);
                out.add("GPROWW\t" + esc(n) + "\t"
                        + bits(GroundPalette.rowWeightOf(n)));
            }
        }
    }

    // ---------------------------------------------------------------- TP ---

    /**
     * The name pool for TilePalette's corpus, in a FIXED order both trees must
     * reproduce. Chosen to exercise prefix behaviour rather than to look like a
     * real install:
     *
     *   - every prefix TilePalette and discoverSkins actually name
     *   - `zz_misc_` and `overlay_` names matching NO prefix, so the TreeSet
     *     fallback at TilePalette:238 is reachable
     *   - `overlay_` specifically, because isOverlay() keys on that PREFIX and
     *     not only on a property
     */
    static final String[] TP_PREFIXES = {
        "blends_natural_01_", "blends_natural_02_", "blends_grassoverlays_01_",
        "blends_street_01_", "floors_exterior_street_01_",
        "floors_interior_tilesandwood_01_", "floors_interior_wood_01_",
        "floors_misc_",
        "walls_exterior_house_01_", "walls_exterior_house_02_",
        "walls_exterior_wooden_01_", "walls_exterior_wooden_02_",
        "walls_exterior_house_low_01_", "walls_interior_house_01_",
        "walls_interior_partition_01_", "walls_misc_",
        "overlay_wall_", "zz_misc_",
    };

    /** Property vocabulary: what TilePalette tests, plus what TileIndex
     *  classification keys on (kindOf, isOverlay, isStructuralWall). */
    static final String[] TP_FLAGS = {
        "grassFloor", "solidfloor", "FloorOverlay", "water", "natureFloor",
        "exterior", "WallN", "WallW", "WallNW", "WallSE", "DoorWallN",
        "DoorWallW", "WindowN", "WindowW", "wall", "WallOverlay", "WindowShape",
        "attachedFloor", "tree", "MoveWithWind", "doorN",
    };

    static List<String> tpNames() {
        List<String> out = new ArrayList<>();
        for (String pre : TP_PREFIXES)
            for (int i = 0; i < 6; i++) out.add(pre + i);
        return out;
    }

    /**
     * PLANTED corpus, then ablated per mode.
     *
     * The first version generated random property sets and measured ZERO cases
     * where complete() was true, zero where verify() passed, and zero reaching
     * describe()'s CustomName branch — because TilePalette's sixteen fields are
     * CONJUNCTIVE predicates (floorInterior alone needs solidfloor AND
     * Material=Wood AND kindOf==FLOOR AND four negated flags) and random flag
     * soup essentially never satisfies them. It was byte-identical anyway.
     * That is the third time in this chunk a byte-identical digest turned out
     * to cover almost nothing (see FINDINGS_F4 §K).
     *
     * So: plant tiles designed to satisfy each field, guaranteeing the happy
     * path, then remove things deliberately.
     *
     *   0  planted, intact          -> complete()==true, verify() OK, all[]==16,
     *                                  discoverSkins finds all five
     *   1  first-choice prefixes have NO SPRITE -> droppedNoSprite, then
     *                                  fallthrough to a later prefix
     *   2  no sprites at all        -> every field null, verify() throws with
     *                                  all sixteen names
     *   3  tiledefs empty           -> first() returns null by another route
     *   4  first-choice prefixes absent from tiledefs -> prefix fallthrough
     *   5  planted tiles renamed zz_misc_ -> NO prefix matches, so the TreeSet
     *                                  fallback at TilePalette:238 is the only
     *                                  path. THIS MODE FOUND A SEGFAULT (§M).
     *   6  planted + CustomName/Material -> describe()'s first branch
     *   7  each planted tile independently dropped at 50% -> partial palettes,
     *                                  verify() throwing with varied subsets
     */
    static final String[][] TP_PLANT = {
        // name suffix within sheet, flags (space separated), material or ""
        {"blends_natural_01_0",                "grassFloor solidfloor", ""},
        {"blends_natural_02_0",                "water solidfloor",      ""},
        {"blends_street_01_0",                 "solidfloor",            ""},
        {"floors_interior_tilesandwood_01_0",  "solidfloor",            "Wood"},
        {"walls_exterior_house_01_0",          "WallN",                 ""},
        {"walls_exterior_house_01_1",          "WallW",                 ""},
        {"walls_exterior_house_01_2",          "WallNW",                ""},
        {"walls_exterior_house_01_3",          "WallSE",                ""},
        {"walls_exterior_house_01_4",          "DoorWallN",             ""},
        {"walls_exterior_house_01_5",          "DoorWallW",             ""},
        {"walls_exterior_house_02_0",          "WallN",                 ""},
        {"walls_exterior_house_02_1",          "WallW",                 ""},
        {"walls_exterior_house_02_2",          "WallNW",                ""},
        {"walls_exterior_house_02_3",          "WallSE",                ""},
        {"walls_exterior_house_02_4",          "DoorWallN",             ""},
        {"walls_exterior_house_02_5",          "DoorWallW",             ""},
        {"walls_exterior_wooden_01_0",         "WallN",                 ""},
        {"walls_exterior_wooden_01_1",         "WallW",                 ""},
        {"walls_exterior_wooden_01_2",         "WallNW",                ""},
        {"walls_exterior_wooden_01_3",         "WallSE",                ""},
        {"walls_exterior_wooden_01_4",         "DoorWallN",             ""},
        {"walls_exterior_wooden_01_5",         "DoorWallW",             ""},
        {"walls_exterior_wooden_02_0",         "WallN",                 ""},
        {"walls_exterior_wooden_02_1",         "WallW",                 ""},
        {"walls_exterior_wooden_02_2",         "WallNW",                ""},
        {"walls_exterior_wooden_02_3",         "WallSE",                ""},
        {"walls_exterior_wooden_02_4",         "DoorWallN",             ""},
        {"walls_exterior_wooden_02_5",         "DoorWallW",             ""},
        {"walls_exterior_house_low_01_0",      "WallN",                 ""},
        {"walls_exterior_house_low_01_1",      "WallW",                 ""},
        {"walls_exterior_house_low_01_2",      "WallNW",                ""},
        {"walls_exterior_house_low_01_3",      "WallSE",                ""},
        {"walls_exterior_house_low_01_4",      "DoorWallN",             ""},
        {"walls_exterior_house_low_01_5",      "DoorWallW",             ""},
        {"walls_interior_house_01_0",          "WallN",                 ""},
        {"walls_interior_house_01_1",          "WallW",                 ""},
        {"walls_interior_house_01_2",          "WallNW",                ""},
        {"walls_interior_house_01_3",          "WallSE",                ""},
        {"walls_interior_house_01_4",          "DoorWallN",             ""},
        {"walls_interior_house_01_5",          "DoorWallW",             ""},
    };

    /** Prefixes each field prefers FIRST — ablated by modes 1 and 4. */
    static boolean tpIsFirstChoice(String n) {
        return n.startsWith("blends_natural_01_")
            || n.startsWith("blends_natural_02_")
            || n.startsWith("blends_street_01_")
            || n.startsWith("floors_interior_tilesandwood_01_")
            || n.startsWith("walls_exterior_house_01_")
            || n.startsWith("walls_interior_house_01_");
    }

    static void tilePalette(List<String> out, int seeds) {
        for (int c = 0; c < seeds; c++) {
            int mode = c % 8;
            Random rng = new Random(c);
            TileIndex ti = new TileIndex();
            java.util.HashSet<String> sprites = new java.util.HashSet<>();

            for (String[] spec : TP_PLANT) {
                // One draw per planted tile in EVERY mode, so the stream stays
                // aligned and a mode change cannot look like a draw-count change.
                int keep = rng.nextInt(100);

                String name = spec[0];
                if (mode == 5) name = "zz_misc_" + name;   // no prefix matches

                boolean inTi = true, inSpr = true;
                switch (mode) {
                    case 1: inSpr = !tpIsFirstChoice(name); break;
                    case 2: inSpr = false; break;
                    case 3: inTi = false; break;
                    case 4: inTi = !tpIsFirstChoice(name); break;
                    case 7: inTi = keep < 50; break;
                    default: break;
                }
                if (!inTi) continue;

                TileDefs.Tile t = new TileDefs.Tile();
                t.name = name;
                for (String f : spec[1].split(" ")) t.props.put(f, "");
                if (!spec[2].isEmpty()) t.props.put("Material", spec[2]);
                if (mode == 6) {
                    t.props.put("CustomName", "Planted " + (c % 7));
                    if (spec[2].isEmpty()) t.props.put("Material", "Brick");
                }
                ti.byName.putIfAbsent(name, t);
                if (inSpr) sprites.add(name);
            }

            // Noise: tiles that qualify for nothing, plus overlay_ names, so
            // the scan has to reject as well as accept.
            for (int i = 0; i < 12; i++) {
                int k = rng.nextInt(100);
                String n = (i % 3 == 0 ? "overlay_wall_" : "floors_misc_") + i;
                TileDefs.Tile t = new TileDefs.Tile();
                t.name = n;
                t.props.put(k < 50 ? "WallOverlay" : "FloorOverlay", "");
                ti.byName.putIfAbsent(n, t);
                if (k < 70) sprites.add(n);
            }

            TilePalette p = TilePalette.pick(ti, sprites);
            out.add("TPPICK\t" + c + "\t" + mode + "\t" + p.complete()
                    + "\t" + p.droppedNoSprite + "\t" + p.all.size());
            out.add("TPTOSTR\t" + c + "\t" + esc(p.toString()));

            StringBuilder allJoin = new StringBuilder();
            for (int i = 0; i < p.all.size(); i++) {
                if (i > 0) allJoin.append(',');
                allJoin.append(esc(p.all.get(i)));
            }
            out.add("TPALL\t" + c + "\t" + allJoin);

            try {
                p.verify();
                out.add("TPVERIFY\t" + c + "\tOK");
            } catch (IllegalStateException e) {
                out.add("TPVERIFY\t" + c + "\t" + esc(e.getMessage()));
            }

            for (int k = 0; k < 8; k++) {
                boolean nn = (k & 1) != 0, ww = (k & 2) != 0, in = (k & 4) != 0;
                String r = p.wallJoin(nn, ww, in);
                out.add("TPJOIN\t" + c + "\t" + k + "\t" + (r == null ? "NULL" : esc(r)));
            }

            List<TilePalette.WallSkin> skins = TilePalette.discoverSkins(ti, sprites);
            out.add("TPSKINN\t" + c + "\t" + skins.size());
            for (int i = 0; i < skins.size(); i++) {
                TilePalette.WallSkin sk = skins.get(i);
                out.add("TPSKIN\t" + c + "\t" + i + "\t" + esc(sk.label())
                        + "\t" + esc(sk.wallN()) + "\t" + esc(sk.wallW())
                        + "\t" + esc(sk.wallNW()) + "\t" + esc(sk.wallSE())
                        + "\t" + esc(sk.doorN()) + "\t" + esc(sk.doorW()));
            }
        }
    }

    // ----------------------------------------------------------- VANILLA ---

    /** FNV-1a 64. Deterministic across both trees; used to fingerprint a name
     *  set without emitting 60,000 lines of it. */
    static String fnv1a(List<String> sortedNames) {
        long h = 0xcbf29ce484222325L;
        for (String n : sortedNames) {
            for (byte b : n.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                h ^= (b & 0xffL);
                h *= 0x100000001b3L;
            }
            h ^= '\n';
            h *= 0x100000001b3L;
        }
        return String.format("%016x", h);
    }

    /**
     * The vanilla-install leg. Everything else in this oracle is synthetic;
     * this is the half that checks the units against the data they actually
     * ship against.
     *
     * DIAGNOSTIC ORDER MATTERS. The first two lines fingerprint the INPUTS —
     * tile count, sprite count, and a hash of each sorted name set. If those
     * diverge, the fault is in TileIndex.load or PackFile, NOT in the palette
     * units, and no amount of staring at TilePalette will find it. Everything
     * after them is the units' own output.
     *
     * These counts are EXPIRING facts (CHARTER §4): they change when the game
     * updates. Stamp them with the build they were measured against.
     */
    static void vanilla(List<String> out, String mediaDir) throws Exception {
        java.nio.file.Path media = Paths.get(mediaDir);
        TileIndex ti = TileIndex.load(media);
        Set<String> sprites = SpriteNames.load(media.resolve("texturepacks"));

        List<String> tn = new ArrayList<>(ti.byName.keySet());
        Collections.sort(tn);
        List<String> sn = new ArrayList<>(sprites);
        Collections.sort(sn);

        out.add("VIN\ttiles\t" + tn.size() + "\t" + fnv1a(tn));
        out.add("VIN\tsprites\t" + sn.size() + "\t" + fnv1a(sn));

        // GroundPalette on the real install.
        GroundPalette gp = GroundPalette.pick(ti, sprites);
        StringBuilder gpAll = new StringBuilder();
        for (int i = 0; i < gp.all.size(); i++) {
            if (i > 0) gpAll.append(',');
            gpAll.append(esc(gp.all.get(i)));
        }
        out.add("VGP\t" + gp.all.size() + "\t" + gpAll);
        out.add("VGPSTR\t" + esc(gp.toString()));
        Random grng = new Random(20260901L);
        for (int i = 0; i < 20000; i++) {
            out.add("VGPROLL\t" + i + "\t" + groundStr(gp.roll(grng)));
        }

        // TilePalette on the real install.
        TilePalette tp = TilePalette.pick(ti, sprites);
        out.add("VTP\t" + tp.complete() + "\t" + tp.droppedNoSprite + "\t" + tp.all.size());
        out.add("VTPSTR\t" + esc(tp.toString()));
        StringBuilder tpAll = new StringBuilder();
        for (int i = 0; i < tp.all.size(); i++) {
            if (i > 0) tpAll.append(',');
            tpAll.append(esc(tp.all.get(i)));
        }
        out.add("VTPALL\t" + tpAll);
        try {
            tp.verify();
            out.add("VTPVERIFY\tOK");
        } catch (IllegalStateException e) {
            out.add("VTPVERIFY\t" + esc(e.getMessage()));
        }
        for (int k = 0; k < 8; k++) {
            String r = tp.wallJoin((k & 1) != 0, (k & 2) != 0, (k & 4) != 0);
            out.add("VTPJOIN\t" + k + "\t" + (r == null ? "NULL" : esc(r)));
        }
        List<TilePalette.WallSkin> skins = TilePalette.discoverSkins(ti, sprites);
        out.add("VTPSKINN\t" + skins.size());
        for (int i = 0; i < skins.size(); i++) {
            TilePalette.WallSkin sk = skins.get(i);
            out.add("VTPSKIN\t" + i + "\t" + esc(sk.label())
                    + "\t" + esc(sk.wallN()) + "\t" + esc(sk.wallW())
                    + "\t" + esc(sk.wallNW()) + "\t" + esc(sk.wallSE())
                    + "\t" + esc(sk.doorN()) + "\t" + esc(sk.doorW()));
        }

        // GroundMaterial against the real sheets: does every material's block
        // actually exist, and does every solid variant have a sprite?
        for (GroundMaterial m : GroundMaterial.values()) {
            StringBuilder present = new StringBuilder();
            for (int idx : m.solidIndices()) {
                String n = m.sheet + idx;
                present.append(ti.get(n) != null ? 'T' : '-');
                present.append(sprites.contains(n) ? 'S' : '-');
                present.append(' ');
            }
            out.add("VGM\t" + m.name() + "\t" + esc(present.toString().trim()));
        }
    }

    // -------------------------------------------------------------- main ---

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: PalettesOracle <out-path> [seeds]");
            System.exit(2);
        }
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        // Java-only assertion, not digested: see class comment.
        if (GroundMaterial.byFloorMaterial(null) != null) {
            System.err.println("byFloorMaterial(null) did not return null");
            System.exit(3);
        }

        List<String> out = new ArrayList<>();

        if (args.length > 2) {
            // Vanilla leg ONLY. Kept separate from the synthetic digest so the
            // synthetic md5 stays stable and the two can be compared and
            // re-measured independently.
            vanilla(out, args[2]);
            StringBuilder vb = new StringBuilder();
            for (String line : out) vb.append(line).append('\n');
            Files.write(Paths.get(args[0]), vb.toString().getBytes("UTF-8"));
            System.out.println("PalettesOracle java VANILLA: " + out.size() + " lines, "
                    + vb.length() + " bytes -> " + args[0]);
            return;
        }

        groundMaterial(out, seeds);
        maskRule(out, seeds);
        groundPalette(out, seeds);
        tilePalette(out, seeds);

        StringBuilder b = new StringBuilder();
        for (String line : out) b.append(line).append('\n');
        Path p = Paths.get(args[0]);
        Files.write(p, b.toString().getBytes("UTF-8"));
        System.out.println("PalettesOracle java: " + out.size() + " lines, "
                + b.length() + " bytes -> " + p);
    }
}

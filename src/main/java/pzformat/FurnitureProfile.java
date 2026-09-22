package pzformat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What a room contains, and how it is arranged.
 *
 * The model is: a room exists so a person can DO things in it. Each activity
 * needs one anchor object, and each anchor pulls in satellites that only make
 * sense beside it — a desk wants a chair pulled up to it and a bin at its side.
 * A desk alone is furniture; a desk with its satellites reads as somebody's
 * workspace. That is the whole idea.
 *
 * Nothing here names a tile. Activities name ROLES, and TilePalette.groups
 * resolves a role to the set of sprite-verified tiles that fill it. That
 * indirection is what stops every shelf in the map being the same shelf.
 *
 * Adding a room type means answering "what does a person do here", not
 * "which tile indices go where".
 */
public final class FurnitureProfile {

    // ---------------------------------------------------------------
    // Placement vocabulary
    // ---------------------------------------------------------------

    /** How a room organises its floor space. */
    public enum Strategy {
        /** Anchors against the walls, middle left clear. Kitchens, bedrooms. */
        PERIMETER,
        /** A small unit block repeated with aisles. Open-plan offices. */
        GRID,
        /** Parallel runs along the long axis. Warehouse racking, pews. */
        ROWS,
        /** One focal object with clearance. Dining and conference tables. */
        CENTRE,
        /** Regions with their own strategies. Breakrooms, shops. */
        ZONED,
        /**
         * Toilet cubicles in a row: each stall one square, divider walls
         * between them, a door on the open side. Measured at McCoy Logging.
         */
        STALLS,
        /**
         * Pick 1-2 FurnitureSets for this room and tile them across the floor
         * with density-driven aisle gaps. A perimeter pass still runs for any
         * activities listed in the profile (wall extras, watercooler, etc.).
         * See FurniturePlacer.stampLayout().
         */
        STAMP,
        /**
         * Explicitly authored layout: place named FurnitureSets at specific
         * positions rather than tiling or zoning. Used for commercial bathrooms
         * where the exact placement of stall blocks, sink runs, and storage
         * must be controlled precisely.
         */
        AUTHORED
    }

    /** Where a satellite sits relative to its anchor. */
    public enum Rel {
        /** The square the anchor faces — a chair pulled up to a desk. */
        IN_FRONT,
        /** Either side along the wall — a bin beside a desk. */
        BESIDE,
        /** The same square, stacked. A microwave on a counter. */
        ON_TOP,
        /** Any free orthogonal neighbour. */
        ADJACENT,
        /** The wall square above the anchor. Overhead cabinets. */
        ABOVE_ON_WALL
    }

    /**
     * One thing a person does here.
     *
     * @param anchorRole  the role of the main object
     * @param chance      probability this activity appears at all
     * @param runLength   consecutive anchors to place (counter runs); 1 for most
     * @param satellites  what comes with it
     */
    public record Activity(String anchorRole, double chance, int runLength,
                           List<Satellite> satellites) {

        public static Activity of(String role, Satellite... sats) {
            return new Activity(role, 1.0, 1, List.of(sats));
        }

        public static Activity maybe(String role, double chance, Satellite... sats) {
            return new Activity(role, chance, 1, List.of(sats));
        }

        public static Activity run(String role, int len, Satellite... sats) {
            return new Activity(role, 1.0, len, List.of(sats));
        }
    }

    /**
     * Something that only makes sense next to its anchor.
     *
     * @param role    the role of the satellite object
     * @param rel     where it goes relative to the anchor
     * @param chance  probability it appears
     */
    public record Satellite(String role, Rel rel, double chance) {
        public static Satellite of(String role, Rel rel) {
            return new Satellite(role, rel, 1.0);
        }
        public static Satellite maybe(String role, Rel rel, double chance) {
            return new Satellite(role, rel, chance);
        }
    }

    /**
     * How densely a room is furnished.
     *
     * The tier changes SPACING, not only fill rate — a sparse office has
     * genuinely bigger gaps between desks, not the same grid with holes in it.
     * That is the difference between "a business that just started" and "a
     * business with missing furniture".
     */
    public enum Density {
        SPARSE(6, 2, 5, 0.35, 0.4),
        NORMAL(4, 1, 3, 0.10, 0.7),
        DENSE (3, 1, 2, 0.00, 1.0);

        /** Side of the repeating unit block in GRID layouts. */
        public final int gridUnit;
        /** Clear tiles between grid units and between rows. */
        public final int aisle;
        /** Tiles between parallel runs in ROWS layouts. */
        public final int rowGap;
        /** Chance a given slot is left empty anyway. */
        public final double skip;
        /** Fraction of optional activities that fire. */
        public final double optionalScale;

        Density(int gridUnit, int aisle, int rowGap, double skip, double optionalScale) {
            this.gridUnit = gridUnit;
            this.aisle = aisle;
            this.rowGap = rowGap;
            this.skip = skip;
            this.optionalScale = optionalScale;
        }

        /**
         * A building rolls one base tier and each room shifts by at most one
         * step from it. A struggling business is struggling throughout — pure
         * per-room randomness gives a packed cubicle farm beside an empty one
         * in the same company, which reads as noise rather than character.
         */
        public Density jitter(Random rng) {
            double r = rng.nextDouble();
            if (r < 0.2 && ordinal() > 0) return values()[ordinal() - 1];
            if (r > 0.8 && ordinal() < values().length - 1) return values()[ordinal() + 1];
            return this;
        }

        public static Density roll(Random rng) {
            double r = rng.nextDouble();
            if (r < 0.25) return SPARSE;
            if (r < 0.75) return NORMAL;
            return DENSE;
        }
    }

    /** A region of a ZONED room with its own strategy and activities. */
    public record Zone(Strategy strategy, double floorShare, List<Activity> activities) { }

    // ---------------------------------------------------------------
    // The profile itself
    // ---------------------------------------------------------------

    public final Strategy strategy;
    public final List<Activity> activities;
    public final List<Zone> zones;
    /** Roles used for the GRID unit block: anchor plus its own satellites. */
    public final Activity gridUnit;
    /** How many decor items to attempt, before density scaling. */
    public final int decorBudget;
    /**
     * The room-type string passed to FurnitureSet.pickFor() in STAMP layouts.
     * Null for all non-STAMP strategies.
     */
    public final String stampRoomType;

    private FurnitureProfile(Strategy strategy, List<Activity> activities,
                             List<Zone> zones, Activity gridUnit, int decorBudget,
                             String stampRoomType) {
        this.strategy      = strategy;
        this.activities    = activities;
        this.zones         = zones;
        this.gridUnit      = gridUnit;
        this.decorBudget   = decorBudget;
        this.stampRoomType = stampRoomType;
    }

    static FurnitureProfile perimeter(int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.PERIMETER, List.of(acts), List.of(), null, decor, null);
    }

    static FurnitureProfile grid(Activity unit, int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.GRID, List.of(acts), List.of(), unit, decor, null);
    }

    static FurnitureProfile rows(int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.ROWS, List.of(acts), List.of(), null, decor, null);
    }

    static FurnitureProfile centre(int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.CENTRE, List.of(acts), List.of(), null, decor, null);
    }

    static FurnitureProfile zoned(int decor, Zone... zs) {
        return new FurnitureProfile(Strategy.ZONED, List.of(), List.of(zs), null, decor, null);
    }

    static FurnitureProfile stalls(int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.STALLS, List.of(acts), List.of(), null, decor, null);
    }

    /**
     * A room filled by stamping measured vanilla furniture sets across the
     * floor. Activities are optional perimeter extras (watercooler, shelves,
     * television) that run after the stamps are placed.
     */
    static FurnitureProfile stamp(String roomType, int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.STAMP, List.of(acts),
                List.of(), null, decor, roomType);
    }

    /**
     * A room whose layout is authored explicitly in FurniturePlacer.authoredLayout().
     * Activities are perimeter extras placed after the authored pieces.
     */
    static FurnitureProfile authored(int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.AUTHORED, List.of(acts),
                List.of(), null, decor, null);
    }

    // ---------------------------------------------------------------
    // The table: room type -> profile
    // ---------------------------------------------------------------

    /**
     * The profile for a room, given its type and the class of building it sits
     * in. BuildingClass matters because the same room name means different
     * things in different buildings — an office kitchen is domestic, a
     * restaurant kitchen is industrial.
     *
     * Returns null for room types that get no furniture.
     */
    public static FurnitureProfile forRoom(String roomType, BuildingClass bc) {
        boolean commercial = bc == BuildingClass.FLAT_ROOF;

        return switch (roomType) {

            // ---- Kitchens ----
            // A counter RUN, not scattered counters: vanilla kitchens have
            // three to five consecutive counter tiles with the sink set into
            // them and small appliances on top.
            // Studio — tiny single-room dwelling. Kitchen along one wall,
            // one chair representing the living area. No subdivision needed.
            case "studio" -> perimeter(0,
                    Activity.run("counter", 3,
                            Satellite.maybe("sink", Rel.ON_TOP, 0.9),
                            Satellite.maybe("microwave", Rel.ON_TOP, 0.5)),
                    Activity.of("oven"),
                    Activity.of("fridge"),
                    Activity.maybe("chair_soft_N", 0.8),
                    Activity.maybe("toilet_N", 0.6));

            case "kitchen" -> perimeter(2,
                    Activity.run("counter", 4,
                            Satellite.maybe("sink", Rel.ON_TOP, 0.9),
                            Satellite.maybe("microwave", Rel.ON_TOP, 0.5),
                            Satellite.maybe("toaster", Rel.ON_TOP, 0.4),
                            Satellite.maybe("overhead_N", Rel.ABOVE_ON_WALL, 0.7)),
                    Activity.of("oven"),       // domestic oven only
                    Activity.of("fridge"),
                    Activity.maybe("table", 0.4,
                            Satellite.of("chair_dining_N", Rel.IN_FRONT),
                            Satellite.of("chair_dining_S", Rel.BESIDE)));

            case "cafeteriakitchen", "restaurant" -> perimeter(1,
                    Activity.run("counter", 5,
                            Satellite.maybe("sink", Rel.ON_TOP, 0.9)),
                    Activity.of("oven_ind"),
                    Activity.of("fridge"),
                    Activity.maybe("fridge", 0.6));

            // ---- Breakroom: a small kitchen plus tables ----
            case "breakroom" -> stamp("breakroom", 2,
                    // Guaranteed table+chairs as perimeter fallback when no set fits.
                    Activity.of("table",
                            Satellite.of("chair_dining_N", Rel.IN_FRONT),
                            Satellite.of("chair_dining_S", Rel.BESIDE)),
                    Activity.maybe("fridge", 0.7),
                    Activity.maybe("watercooler", 0.5),
                    Activity.maybe("television", 0.3));

            // ---- Office ----
            // Small offices get one desk against a wall. Large ones get the
            // cubicle treatment: a unit block tiled across the floor.
            // Swivel chairs at the desks in the middle; anything softer is
            // sparse and against a wall, which is how a real office reads.
            // Desk is the dominant required object. Shelves are extras that
            // go on the perimeter — one or two, not a wall full of them.
            case "office" -> stamp("office", 2,
                    // Guaranteed desk+chair when no set fits. Chair is required (chance=1.0).
                    Activity.of("desk",
                            Satellite.of("chair_office_N", Rel.IN_FRONT),
                            Satellite.maybe("drawers", Rel.BESIDE, 0.4)),
                    Activity.maybe("shelves_N", 0.3),
                    Activity.maybe("chair_soft_N", 0.2),
                    Activity.maybe("watercooler", 0.3));

            // ---- Storage and retail ----
            // Racking down the middle plus crates and boxes — a storeroom is
            // not just shelving, and the office shelving sheet keeps grocery
            // loot out of it.
            case "storage", "farmstorage", "haystorage" -> rows(0,
                    Activity.of("shelves_office"),
                    Activity.maybe("crate", 0.8),
                    Activity.maybe("box", 0.6),
                    Activity.maybe("pallet", 0.4));

            // Vanilla residential garages: metal wall shelves + cardboard boxes.
            // Measured at 4 garage buildings — all small standalone garagestorage rooms.
            case "garagestorage" -> perimeter(0,
                    Activity.of("shelves_wall_metal"),
                    Activity.maybe("shelves_wall_metal", 0.6),
                    Activity.maybe("workbench", 0.5),
                    Activity.maybe("crate", 0.7),
                    Activity.maybe("box", 0.8),
                    Activity.maybe("shelves_metal", 0.4));

            // Large warehouse / factory floor: heavy free-standing metal racking
            // in rows, pallets between them. Measured at factory 39x23.
            case "warehouse", "factory", "grocerystorage" -> rows(0,
                    Activity.of("shelves_metal"),
                    Activity.maybe("pallet", 0.9),
                    Activity.maybe("crate", 0.6),
                    Activity.maybe("box", 0.5));

            // Storage unit strip mall: small 5x5 self-storage rooms.
            // Vanilla: wall metal shelves + cardboard boxes.
            case "storageunit" -> perimeter(0,
                    Activity.of("shelves_wall_metal"),
                    Activity.maybe("box", 0.9),
                    Activity.maybe("crate", 0.6));

            // Car supply / auto parts store.
            case "carsupply" -> rows(0,
                    Activity.of("shelves_metal"),
                    Activity.maybe("crate", 0.5),
                    Activity.maybe("workbench", 0.4));

            // Army surplus / military shop. Measured: large shop shelves on walls,
            // military lockers and crates mid-floor, clothes racks.
            case "armystorage", "armysurplus" -> zoned(1,
                    new Zone(Strategy.ROWS, 0.6, List.of(
                            Activity.of("shelves_retail"),
                            Activity.maybe("locker_military", 0.7))),
                    new Zone(Strategy.PERIMETER, 0.4, List.of(
                            Activity.of("counter",
                                    Satellite.of("chair_office_N", Rel.IN_FRONT)),
                            Activity.maybe("crate_military", 0.8),
                            Activity.maybe("locker_military", 0.6))));

            // Gun store. Measured: glass/dark display counters along the walls
            // forming a U, magazine stand and wall shelves at the front.
            case "gunstore" -> zoned(1,
                    new Zone(Strategy.PERIMETER, 0.7, List.of(
                            Activity.run("counter", 3))),
                    new Zone(Strategy.ROWS, 0.3, List.of(
                            Activity.of("shelves_retail"),
                            Activity.maybe("locker_military", 0.4))));

            // Grocery store. Measured: fridges along the back wall, retail
            // shelving in rows, display stands mid-floor, checkout counter near exit.
            case "grocery", "gas2go" -> zoned(1,
                    new Zone(Strategy.ROWS, 0.65, List.of(
                            Activity.of("shelves_retail"),
                            Activity.maybe("display_stand", 0.5))),
                    new Zone(Strategy.PERIMETER, 0.35, List.of(
                            Activity.of("fridge"),
                            Activity.maybe("fridge", 0.7),
                            Activity.run("counter", 2))));

            case "shop", "electronicsstore", "tobaccostore", "toolstore" -> zoned(1,
                    new Zone(Strategy.ROWS, 0.7, List.of(
                            Activity.of("shelves_retail"))),
                    new Zone(Strategy.PERIMETER, 0.3, List.of(
                            Activity.run("counter", 2))));

            case "paintershop" -> rows(0,
                    Activity.of("shelves_office"),
                    Activity.maybe("workbench", 0.6));

            // ---- Living spaces ----
            case "livingroom" -> perimeter(4,
                    Activity.of("couch_S",
                            Satellite.maybe("table", Rel.IN_FRONT, 0.7)),
                    Activity.maybe("chair_soft_E", 0.6),
                    Activity.maybe("television", 0.8),
                    Activity.maybe("chair_soft_E", 0.6),
                    Activity.maybe("shelves_N", 0.4));

            case "bedroom", "kidsbedroom" -> perimeter(2,
                    Activity.of("bed_home",
                            Satellite.maybe("drawers", Rel.BESIDE, 0.8)),
                    Activity.maybe("wardrobe", 0.7),
                    Activity.maybe("toilet_N", 0.4),
                    Activity.maybe("chair_dining_E", 0.2));

            case "diningroom" -> centre(3,
                    Activity.of("table",
                            // All chairs are required so a dining table is
                            // never surrounded by empty air.
                            Satellite.of("chair_dining_N", Rel.IN_FRONT),
                            Satellite.of("chair_dining_S", Rel.BESIDE),
                            Satellite.of("chair_dining_E", Rel.ADJACENT)));

            // ---- Bathrooms ----
            // Residential gets a bath; commercial gets showers and a hand
            // dryer and more than one toilet.
            // Commercial bathroom: counter run with sinks set into it along
            // one wall, mirrors above; toilets along the opposite wall spaced
            // so there is always a clear square between them. No vanilla stall
            // walls exist so spacing is the only privacy we can give.
            // Residential: toilet + bath + sink on counter.
            // Sink is IsTableTop and must go ON_TOP of a counter in both cases.
            case "bathroom" -> commercial
                    ? authored(0,
                        // Perimeter extras after the authored pieces are placed.
                        Activity.maybe("blower_N", 0.6),
                        Activity.maybe("bin", 0.8))
                    : perimeter(1,
                        Activity.of("toilet_N"),
                        Activity.of("counter",
                                Satellite.of("sink", Rel.ON_TOP),
                                Satellite.maybe("mirror_N", Rel.ABOVE_ON_WALL, 0.8)),
                        Activity.maybe("bath", 0.7),
                        Activity.maybe("bin", 0.5));

            case "changeroom" -> zoned(0,
                    new Zone(Strategy.PERIMETER, 0.6, List.of(
                            Activity.of("locker"),
                            Activity.maybe("bench", 0.8),
                            Activity.maybe("bin", 0.6))),
                    new Zone(Strategy.STALLS, 0.4, List.of(
                            Activity.of("shower"))));

            // ---- Public and institutional ----
            // Halls get minimal perimeter furniture so they feel like corridors.
            // A completely empty hall reads as a bug; a bench and a bin reads as intentional.
            case "hall" -> perimeter(1,
                    Activity.maybe("bench", 0.7),
                    Activity.maybe("bin", 0.5),
                    Activity.maybe("plant_floor", 0.4));

            case "lobby" -> perimeter(3,
                    // At least one couch is required — a lobby with only plants is not a lobby.
                    Activity.of("couch_S",
                            Satellite.maybe("table", Rel.IN_FRONT, 0.6)),
                    Activity.maybe("chair_soft_E", 0.6),
                    Activity.maybe("chair_soft_E", 0.5),
                    Activity.maybe("watercooler", 0.5));

            case "school" -> grid(
                    Activity.of("desk",
                            Satellite.of("chair_dining_N", Rel.IN_FRONT)),   // required
                    1,
                    Activity.maybe("shelves_N", 0.6));

            // Church. Measured: rows of Dark Wooden Chairs facing N (the altar),
            // lectern stand at the front, nothing else. The altar tiles are
            // part of the building structure (church_small_01_93-95), not furniture.
            case "church" -> rows(2,
                    Activity.of("chair_pew_N"),
                    Activity.maybe("lectern", 0.9));

            // Hospital room. Measured: Large Medical Beds along the north wall,
            // mobile tool counters and IV/bloodbag stands beside each bed,
            // drawers at the head of each bed. medclinic is smaller (exam room).
            case "hospitalroom", "medclinic", "medical" -> perimeter(1,
                    Activity.of("bed_medical",
                            Satellite.maybe("drawers", Rel.BESIDE, 0.8)),
                    Activity.maybe("shelves_N", 0.5),
                    Activity.maybe("sink", 0.7),
                    Activity.maybe("bin", 0.6));

            case "archive" -> rows(0,
                    Activity.of("shelves_office"),
                    Activity.maybe("box", 0.7));

            case "security", "police" -> perimeter(1,
                    Activity.of("desk",
                            Satellite.of("chair_office_N", Rel.IN_FRONT),    // required
                            Satellite.maybe("drawers", Rel.BESIDE, 0.5)),
                    Activity.maybe("locker", 0.7));

            case "laundry" -> perimeter(0,
                    Activity.of("counter"),
                    Activity.maybe("sink", 0.8));

            case "barn" -> rows(0,
                    Activity.maybe("crate", 0.7),
                    Activity.maybe("pallet", 0.5));

            // Janitor closet — tiny utility room. Shelves on a wall, bin, crate.
            case "janitor" -> perimeter(0,
                    Activity.of("shelves_wall_metal"),
                    Activity.maybe("bin", 0.9),
                    Activity.maybe("crate", 0.6));

            // Shed — small outbuilding. Like a mini garagestorage.
            case "shed" -> perimeter(0,
                    Activity.of("shelves_wall_metal"),
                    Activity.maybe("workbench", 0.5),
                    Activity.maybe("crate", 0.7),
                    Activity.maybe("box", 0.6));

            // Closet — tiny residential room. Wardrobe only.
            case "closet" -> perimeter(0,
                    Activity.of("wardrobe"));

            // Closets, cemeteries and anything unlisted stay empty.
            default -> null;
        };
    }

    /** All activities for a profile, flattened across zones. */
    public List<Activity> allActivities() {
        if (strategy != Strategy.ZONED) return activities;
        List<Activity> out = new ArrayList<>();
        for (Zone z : zones) out.addAll(z.activities());
        return out;
    }
}

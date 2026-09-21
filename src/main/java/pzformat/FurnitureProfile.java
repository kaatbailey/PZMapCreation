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
        STALLS
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

    private FurnitureProfile(Strategy strategy, List<Activity> activities,
                             List<Zone> zones, Activity gridUnit, int decorBudget) {
        this.strategy = strategy;
        this.activities = activities;
        this.zones = zones;
        this.gridUnit = gridUnit;
        this.decorBudget = decorBudget;
    }

    static FurnitureProfile perimeter(int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.PERIMETER, List.of(acts), List.of(), null, decor);
    }

    static FurnitureProfile grid(Activity unit, int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.GRID, List.of(acts), List.of(), unit, decor);
    }

    static FurnitureProfile rows(int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.ROWS, List.of(acts), List.of(), null, decor);
    }

    static FurnitureProfile centre(int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.CENTRE, List.of(acts), List.of(), null, decor);
    }

    static FurnitureProfile zoned(int decor, Zone... zs) {
        return new FurnitureProfile(Strategy.ZONED, List.of(), List.of(zs), null, decor);
    }

    static FurnitureProfile stalls(int decor, Activity... acts) {
        return new FurnitureProfile(Strategy.STALLS, List.of(acts), List.of(), null, decor);
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
            case "kitchen" -> perimeter(2,
                    Activity.run("counter", 4,
                            Satellite.maybe("sink", Rel.ON_TOP, 0.9),
                            Satellite.maybe("microwave", Rel.ON_TOP, 0.5),
                            Satellite.maybe("toaster", Rel.ON_TOP, 0.4),
                            Satellite.maybe("overhead_N", Rel.ABOVE_ON_WALL, 0.7)),
                    Activity.of("oven"),
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
            case "breakroom" -> zoned(2,
                    new Zone(Strategy.PERIMETER, 0.4, List.of(
                            Activity.run("counter", 3,
                                    Satellite.maybe("sink", Rel.ON_TOP, 0.8),
                                    Satellite.maybe("microwave", Rel.ON_TOP, 0.8)),
                            Activity.maybe("fridge", 0.8),
                            Activity.maybe("watercooler", 0.6),
                            Activity.maybe("television", 0.3))),
                    new Zone(Strategy.GRID, 0.6, List.of(
                            Activity.of("table",
                                    Satellite.of("chair_dining_N", Rel.IN_FRONT),
                                    Satellite.of("chair_dining_S", Rel.BESIDE)))));

            // ---- Office ----
            // Small offices get one desk against a wall. Large ones get the
            // cubicle treatment: a unit block tiled across the floor.
            // Swivel chairs at the desks in the middle; anything softer is
            // sparse and against a wall, which is how a real office reads.
            // Desk is the dominant required object. Shelves are extras that
            // go on the perimeter — one or two, not a wall full of them.
            case "office" -> grid(
                    Activity.of("desk",
                            Satellite.of("chair_office_N", Rel.IN_FRONT),
                            Satellite.maybe("drawers", Rel.BESIDE, 0.5)),
                    2,
                    Activity.maybe("shelves_N", 0.3),
                    Activity.maybe("chair_soft_N", 0.2),
                    Activity.maybe("watercooler", 0.3));

            // ---- Storage and retail ----
            // Racking down the middle plus crates and boxes — a storeroom is
            // not just shelving, and the office shelving sheet keeps grocery
            // loot out of it.
            case "storage", "garagestorage", "armystorage",
                 "farmstorage", "haystorage" -> rows(0,
                    Activity.of("shelves_office"),
                    Activity.maybe("crate", 0.8),
                    Activity.maybe("box", 0.6),
                    Activity.maybe("pallet", 0.4));

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
                    ? zoned(0,
                        new Zone(Strategy.PERIMETER, 0.45, List.of(
                                // Counter run with sinks set into the top and
                                // mirrors on the wall above — the vanilla
                                // arrangement at McCoy Logging.
                                Activity.of("counter",
                                        Satellite.of("sink", Rel.ON_TOP),
                                        Satellite.maybe("mirror_N",
                                                Rel.ABOVE_ON_WALL, 0.9)),
                                Activity.maybe("blower_N", 0.7),
                                Activity.maybe("bin", 0.8))),
                        new Zone(Strategy.STALLS, 0.55, List.of(
                                // Anchor role is ignored by stalls() — it always
                                // uses stall_toilet from fixtures_bathroom_02.
                                Activity.of("toilet_N"))))
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
            case "lobby" -> perimeter(4,
                    Activity.maybe("couch_S", 0.7),
                    Activity.maybe("chair_soft_E", 0.6),
                    Activity.maybe("table", 0.5,
                            Satellite.of("chair_soft_N", Rel.IN_FRONT),
                            Satellite.of("chair_soft_S", Rel.BESIDE)),
                    Activity.maybe("watercooler", 0.5));

            case "school" -> grid(
                    Activity.of("desk",
                            Satellite.of("chair_dining_N", Rel.IN_FRONT)),
                    1,
                    Activity.maybe("shelves_N", 0.6));

            case "church" -> rows(1,
                    Activity.of("bench"));

            case "medical" -> perimeter(1,
                    Activity.of("shelves_N"),
                    Activity.of("bed"),
                    Activity.maybe("sink", 0.6));

            case "archive" -> rows(0,
                    Activity.of("shelves_office"),
                    Activity.maybe("box", 0.7));

            case "security", "police" -> perimeter(1,
                    Activity.of("desk",
                            Satellite.of("chair_office_N", Rel.IN_FRONT)),
                    Activity.maybe("locker", 0.7));

            case "laundry" -> perimeter(0,
                    Activity.of("counter"),
                    Activity.maybe("sink", 0.8));

            case "barn" -> rows(0,
                    Activity.maybe("crate", 0.7),
                    Activity.maybe("pallet", 0.5));

            // Halls, closets, cemeteries and anything unlisted stay empty.
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

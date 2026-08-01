package pzformat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * X_Y.lotheader -- per-cell tile-name table plus trailing metadata.
 *
 * TWO KNOWN VARIANTS:
 *
 * B42 (version 1) -- CONFIRMED against 42.20.x retail files:
 *     char[4]        "LOTH" magic
 *     int32          version            (1)
 *     int32          tileCount
 *     names          tileCount entries, '\n'-separated ASCII
 *     ...            trailer, layout not yet identified
 *
 *   NOTE: B42 does NOT store width/height/levels here. A full-file scan of a
 *   retail 42.20 lotheader finds no 300/256 cell-size triple anywhere. Cell
 *   geometry must be global or defined in map.info.
 *
 * B41 (no magic) -- legacy, per PZwiki "File formats":
 *     int32          version
 *     int32          tileCount
 *     names          NUL-terminated
 *     int32          width, height, levels
 *     ...            rooms, buildings, zombie density
 *
 * The trailer is deliberately left unparsed and exposed as raw bytes. Guessing
 * its layout would produce plausible garbage; Probe dumps it for analysis instead.
 */
public final class LotHeader {

    public static final byte[] LOTH_MAGIC = {'L', 'O', 'T', 'H'};

    public boolean b42;
    public int version;
    public final List<String> tileNames = new ArrayList<>();

    /** B41 only. -1 when absent (all B42 files). */
    public int width = -1, height = -1, levels = -1;

    public final List<Room> rooms = new ArrayList<>();
    public final List<int[]> buildings = new ArrayList<>();
    public byte[] zombieDensity;

    /** Offset where the tile-name table ended and unidentified data begins. */
    public int trailerOffset = -1;
    /** Raw unparsed bytes after the tile table (B42). */
    public byte[] trailer = new byte[0];

    public int padBytesSkipped = -1;
    public final List<String> warnings = new ArrayList<>();

    public static final class Room {
        public String name;
        public int floor;
        public final List<int[]> rects = new ArrayList<>();
        public final List<int[]> objects = new ArrayList<>();
    }

    public static LotHeader read(Path file) throws IOException {
        return read(LE.of(file));
    }

    public static LotHeader read(LE r) {
        LotHeader h = new LotHeader();
        byte[] magic = r.bytes(4);
        h.b42 = magic[0] == 'L' && magic[1] == 'O' && magic[2] == 'T' && magic[3] == 'H';
        if (h.b42) {
            h.version = r.i32();
            readNameTable(r, h);
            h.trailerOffset = r.pos();
            readB42Meta(r, h);
            return h;
        }
        // B41: the 4 bytes we consumed were the version field.
        r.seek(0);
        h.version = r.i32();
        readNameTable(r, h);
        h.trailerOffset = r.pos();
        readB41Meta(r, h);
        return h;
    }

    private static void readNameTable(LE r, LotHeader h) {
        int tileCount = r.i32();
        if (tileCount < 0 || tileCount > 500_000) {
            throw new LE.ParseException("implausible tile count " + tileCount
                    + " at offset " + (r.pos() - 4));
        }
        for (int i = 0; i < tileCount; i++) h.tileNames.add(r.cString());
    }

    private static void readB41Meta(LE r, LotHeader h) {
        int found = -1;
        for (int pad = 0; pad <= 8; pad++) {
            int probe = h.trailerOffset + pad;
            if (probe + 12 > r.length()) break;
            r.seek(probe);
            int w = r.i32(), ht = r.i32(), lv = r.i32();
            if (isKnownCellSize(w) && w == ht && lv > 0 && lv <= 64) {
                found = pad; h.width = w; h.height = ht; h.levels = lv; break;
            }
        }
        if (found < 0) {
            r.seek(h.trailerOffset);
            h.trailer = r.bytes(r.remaining());
            h.warnings.add("no width/height/levels found after tile table; trailer left raw");
            return;
        }
        h.padBytesSkipped = found;
        try {
            int roomCount = r.i32();
            if (roomCount < 0 || roomCount > 200_000) {
                h.warnings.add("implausible roomCount " + roomCount); return;
            }
            for (int i = 0; i < roomCount; i++) {
                Room room = new Room();
                room.name = r.cString();
                room.floor = r.i32();
                int rectCount = r.i32();
                if (rectCount < 0 || rectCount > 10_000) {
                    h.warnings.add("room '" + room.name + "': bad rectCount " + rectCount); return;
                }
                for (int j = 0; j < rectCount; j++)
                    room.rects.add(new int[]{r.i32(), r.i32(), r.i32(), r.i32()});
                int objCount = r.i32();
                if (objCount < 0 || objCount > 100_000) {
                    h.warnings.add("room '" + room.name + "': bad objectCount " + objCount); return;
                }
                for (int j = 0; j < objCount; j++)
                    room.objects.add(new int[]{r.i32(), r.i32(), r.i32()});
                h.rooms.add(room);
            }
            int buildingCount = r.i32();
            if (buildingCount < 0 || buildingCount > 200_000) {
                h.warnings.add("implausible buildingCount " + buildingCount); return;
            }
            for (int i = 0; i < buildingCount; i++) {
                int n = r.i32();
                if (n < 0 || n > 10_000) { h.warnings.add("bad building room count " + n); return; }
                int[] idx = new int[n];
                for (int j = 0; j < n; j++) idx[j] = r.i32();
                h.buildings.add(idx);
            }
            int expected = (h.width / 10) * (h.height / 10);
            if (r.remaining() >= expected) {
                h.zombieDensity = r.bytes(expected);
                if (r.remaining() != 0)
                    h.warnings.add(r.remaining() + " trailing bytes after density grid");
            } else {
                h.warnings.add("expected " + expected + " density bytes, "
                        + r.remaining() + " remain");
            }
        } catch (LE.ParseException e) {
            h.warnings.add("metadata section failed: " + e.getMessage());
        }
    }


    // ---------------- B42 ----------------

    /** Per-chunk grid: 32x32 for a 256-tile cell, i.e. 8-tile chunks. */
    public static final int GRID_SIDE = 32;
    public static final int GRID_BYTES = GRID_SIDE * GRID_SIDE;

    public int levelsAbove = -1, levelsBelow = -1, minLevel = 0, unknown12 = 0;
    public byte[] chunkGrid;
    public boolean fullyConsumed;

    /**
     * B42 trailer, confirmed by fitting leftover = 1 + buildings + roomRefs
     * across 4064 retail cells:
     *
     *   int32   levelsAbove      (8 in every cell observed)
     *   int32   levelsBelow      (8 in every cell observed)
     *   int32   minLevel         (0 mostly; negative in ~70 cells)
     *   int32   unknown12        (meaning not yet identified)
     *   int32   roomCount
     *   room[]  name '\n', floor, rectCount, rect[]{x,y,w,h}, objCount, obj[]{a,b,c}
     *   int32   buildingCount
     *   building[] int32 roomCount, int32[] roomIndices
     *   byte[1024] per-chunk grid
     */
    private static void readB42Meta(LE r, LotHeader h) {
        h.levelsAbove = r.i32();
        h.levelsBelow = r.i32();
        h.minLevel    = r.i32();
        h.unknown12   = r.i32();
        int roomCount = r.i32();
        if (roomCount < 0 || roomCount > 200_000)
            throw new LE.ParseException("roomCount " + roomCount + " at " + (r.pos() - 4));

        for (int i = 0; i < roomCount; i++) {
            Room room = new Room();
            room.name = r.cString();
            room.floor = r.i32();
            int rectCount = r.i32();
            if (rectCount < 0 || rectCount > 5000)
                throw new LE.ParseException("room " + i + " rectCount " + rectCount);
            for (int j = 0; j < rectCount; j++)
                room.rects.add(new int[]{r.i32(), r.i32(), r.i32(), r.i32()});
            int objCount = r.i32();
            if (objCount < 0 || objCount > 50_000)
                throw new LE.ParseException("room " + i + " objCount " + objCount);
            for (int j = 0; j < objCount; j++)
                room.objects.add(new int[]{r.i32(), r.i32(), r.i32()});
            h.rooms.add(room);
        }

        int buildingCount = r.i32();
        if (buildingCount < 0 || buildingCount > 200_000)
            throw new LE.ParseException("buildingCount " + buildingCount + " at " + (r.pos() - 4));
        for (int i = 0; i < buildingCount; i++) {
            int n = r.i32();
            if (n < 0 || n > 50_000)
                throw new LE.ParseException("building " + i + " roomCount " + n);
            int[] idx = new int[n];
            for (int j = 0; j < n; j++) {
                idx[j] = r.i32();
                if (idx[j] < 0 || idx[j] >= roomCount)
                    throw new LE.ParseException("building " + i + " room index " + idx[j]
                            + " out of range (roomCount=" + roomCount + ")");
            }
            h.buildings.add(idx);
        }

        if (r.remaining() != GRID_BYTES)
            throw new LE.ParseException("expected exactly " + GRID_BYTES
                    + " grid bytes after buildings, found " + r.remaining());
        h.chunkGrid = r.bytes(GRID_BYTES);
        h.fullyConsumed = r.eof();
    }

    /**
     * Serialise a B42 lotheader. Field order and terminators mirror readB42Meta
     * exactly; round-tripping a retail cell should reproduce it byte for byte.
     */
    public byte[] write() {
        if (!b42) throw new IllegalStateException("writer supports B42 only");
        LEW w = new LEW();
        w.ascii("LOTH");
        w.i32(version);
        w.i32(tileNames.size());
        for (String t : tileNames) w.nlString(t);
        w.i32(levelsAbove);
        w.i32(levelsBelow);
        w.i32(minLevel);
        w.i32(unknown12);
        w.i32(rooms.size());
        for (Room room : rooms) {
            w.nlString(room.name);
            w.i32(room.floor);
            w.i32(room.rects.size());
            for (int[] r : room.rects) { w.i32(r[0]); w.i32(r[1]); w.i32(r[2]); w.i32(r[3]); }
            w.i32(room.objects.size());
            for (int[] ob : room.objects) { w.i32(ob[0]); w.i32(ob[1]); w.i32(ob[2]); }
        }
        w.i32(buildings.size());
        for (int[] b : buildings) {
            w.i32(b.length);
            for (int idx : b) w.i32(idx);
        }
        w.bytes(chunkGrid);
        return w.toByteArray();
    }

    /** Highest actual z containing data. Stored at trailer+12. */
    public int maxLevel() { return unknown12; }

    /** Number of z-levels encoded: maxLevel - minLevel + 1. */
    public int levelCount() { return unknown12 - minLevel + 1; }

    /** Total room references across all buildings. */
    public int roomRefs() {
        int n = 0;
        for (int[] b : buildings) n += b.length;
        return n;
    }

    static boolean isKnownCellSize(int v) { return v == 300 || v == 256; }
}

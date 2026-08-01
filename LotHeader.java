package pzformat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * X_Y.lotheader -- per-cell tile-name table plus room/building metadata.
 *
 * CONFIDENCE: MEDIUM. The leading fields (version, tile count, null-terminated
 * tile-name table) are documented on PZwiki "File formats". Everything after
 * the tile table is reconstructed from community tooling behaviour and is
 * MARKED AS INFERRED BELOW. Do not trust it until Probe confirms it against
 * your own game files.
 *
 * Assumed layout:
 *   int32           version            (0 in old builds; B41/B42 differ -- CHECK)
 *   int32           tileCount
 *   cString * n     tile names         ("gravel_1_0" etc), index = tile id used by .lotpack
 *   u8              separator/pad      [INFERRED -- may not exist; Probe auto-detects]
 *   int32           width              (300 on B41, 256 on B42)
 *   int32           height
 *   int32           levels             (8 on B41; larger on B42 due to basements)
 *   int32           roomCount
 *   room * n:
 *       cString     name
 *       int32       floor (z level)
 *       int32       rectCount
 *       rect * n:   int32 x, y, w, h
 *       int32       objectCount
 *       object * n: int32 type, int32 x, int32 y      [INFERRED -- field count unverified]
 *   int32           buildingCount
 *   building * n:   int32 roomCount, int32 roomIndex * n
 *   u8 * (w/10 * h/10)  zombie density grid           [INFERRED]
 *
 * The self-check that matters: after reading the tile table, width/height MUST
 * land on 300 or 256. If it doesn't, the separator byte assumption is wrong.
 * readTolerant() brute-forces the alignment and tells you which one worked.
 */
public final class LotHeader {

    public int version;
    public final List<String> tileNames = new ArrayList<>();
    public int width, height, levels;
    public final List<Room> rooms = new ArrayList<>();
    public final List<int[]> buildings = new ArrayList<>();
    public byte[] zombieDensity;

    /** Byte offset where the post-tile-table section began. Diagnostic. */
    public int metaOffset = -1;
    /** How many pad bytes were skipped between tile table and width. Diagnostic. */
    public int padBytesSkipped = -1;
    /** Anything that parsed suspiciously. */
    public final List<String> warnings = new ArrayList<>();

    public static final class Room {
        public String name;
        public int floor;
        public final List<int[]> rects = new ArrayList<>();   // {x, y, w, h}
        public final List<int[]> objects = new ArrayList<>(); // {type, x, y}
    }

    public static LotHeader read(Path file) throws IOException {
        return readTolerant(LE.of(file));
    }

    /**
     * Reads the header, brute-forcing the unknown padding between the tile-name
     * table and the width field. Records which offset worked.
     */
    public static LotHeader readTolerant(LE r) {
        LotHeader h = new LotHeader();
        h.version = r.i32();
        int tileCount = r.i32();
        if (tileCount < 0 || tileCount > 500_000) {
            throw new LE.ParseException("implausible tile count " + tileCount
                    + " (version field read as " + h.version + ") -- not a .lotheader, "
                    + "or the header layout changed in this build");
        }
        for (int i = 0; i < tileCount; i++) h.tileNames.add(r.cString());
        h.metaOffset = r.pos();

        // Brute-force the alignment: try 0..8 pad bytes and accept the first
        // offset where width/height are a known cell size.
        int found = -1;
        for (int pad = 0; pad <= 8; pad++) {
            int probe = h.metaOffset + pad;
            if (probe + 12 > r.length()) break;
            r.seek(probe);
            int w = r.i32(), ht = r.i32(), lv = r.i32();
            if (isKnownCellSize(w) && w == ht && lv > 0 && lv <= 64) {
                found = pad;
                h.width = w; h.height = ht; h.levels = lv;
                break;
            }
        }
        if (found < 0) {
            r.seek(h.metaOffset);
            throw new LE.ParseException(
                    "could not locate width/height/levels after the tile table (offset "
                    + h.metaOffset + "). Expected a cell size of 300 (B41) or 256 (B42).\n"
                    + "Bytes at that offset:\n" + r.hexDump(h.metaOffset, 64)
                    + "\nThis means the tile-name table terminator differs in your build. "
                    + "Run: Probe lotheader <file> --scan   to search the whole file for the size marker.");
        }
        h.padBytesSkipped = found;

        // --- everything below here is INFERRED; failures are non-fatal ---
        try {
            int roomCount = r.i32();
            if (roomCount < 0 || roomCount > 200_000) {
                h.warnings.add("implausible roomCount " + roomCount + " at offset " + (r.pos() - 4)
                        + "; stopped parsing metadata");
                return h;
            }
            for (int i = 0; i < roomCount; i++) {
                Room room = new Room();
                room.name = r.cString();
                room.floor = r.i32();
                int rectCount = r.i32();
                if (rectCount < 0 || rectCount > 10_000) {
                    h.warnings.add("room '" + room.name + "': implausible rectCount " + rectCount
                            + " at offset " + (r.pos() - 4) + "; stopped parsing metadata");
                    return h;
                }
                for (int j = 0; j < rectCount; j++) {
                    room.rects.add(new int[]{r.i32(), r.i32(), r.i32(), r.i32()});
                }
                int objCount = r.i32();
                if (objCount < 0 || objCount > 100_000) {
                    h.warnings.add("room '" + room.name + "': implausible objectCount " + objCount
                            + " at offset " + (r.pos() - 4) + "; stopped parsing metadata");
                    return h;
                }
                for (int j = 0; j < objCount; j++) {
                    room.objects.add(new int[]{r.i32(), r.i32(), r.i32()});
                }
                h.rooms.add(room);
            }

            int buildingCount = r.i32();
            if (buildingCount < 0 || buildingCount > 200_000) {
                h.warnings.add("implausible buildingCount " + buildingCount + "; stopped");
                return h;
            }
            for (int i = 0; i < buildingCount; i++) {
                int n = r.i32();
                if (n < 0 || n > 10_000) {
                    h.warnings.add("building " + i + ": implausible room count " + n + "; stopped");
                    return h;
                }
                int[] idx = new int[n];
                for (int j = 0; j < n; j++) idx[j] = r.i32();
                h.buildings.add(idx);
            }

            int expected = (h.width / 10) * (h.height / 10);
            if (r.remaining() >= expected) {
                h.zombieDensity = r.bytes(expected);
                if (r.remaining() != 0) {
                    h.warnings.add(r.remaining() + " trailing bytes after zombie density grid "
                            + "-- density grid size assumption may be wrong");
                }
            } else {
                h.warnings.add("expected " + expected + " zombie-density bytes but only "
                        + r.remaining() + " remain");
            }
        } catch (LE.ParseException e) {
            h.warnings.add("metadata section failed: " + e.getMessage());
        }
        return h;
    }

    static boolean isKnownCellSize(int v) { return v == 300 || v == 256; }
}

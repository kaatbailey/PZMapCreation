package pzformat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * world_X_Y.lotpack -- Build 42 tile data for one 256x256 cell.
 *
 * FORMAT CONFIRMED against retail 42.20:
 *
 *   char[4]  "LOTP"
 *   int32    version        (1)
 *   int32    chunkCount     (1024 = 32x32 chunks of 8x8 tiles)
 *   int64    offset[chunkCount]        64-bit, unlike B41's 32-bit table
 *   chunk bodies at those offsets
 *
 * Header size is 12 + 8*chunkCount, which equals offset[0] exactly.
 *
 * Chunk body, iterating z (16 levels), then x, then y over the 8x8 chunk:
 *   int32 count
 *     count == -1 -> int32 run : that many consecutive empty squares
 *     else        -> int32 roomId (-1 = none), then count-1 tile indices
 *                    into LotHeader.tileNames
 *
 * Verified by requiring every chunk body to end exactly where the next begins.
 */
public final class LotPack {

    public static final String MAGIC = "LOTP";
    public static final int CHUNK_SIZE = 8;
    /**
     * Nominal level count (levelsAbove + levelsBelow from the lotheader).
     * NOT a hard cap: 4 of 4065 retail cells encode more, up to 30 levels,
     * and those are the deep-basement cells. MAX_LEVELS is a generous ceiling
     * used for allocation; actual depth is reported per chunk.
     */
    public static final int LEVELS = 16;
    public static final int MAX_LEVELS = 64;

    public int version, chunkCount, chunksPerSide, cellSize;
    /** Actual z of chunk z-index 0 (LotHeader.minLevel). actualZ = index + minLevel. */
    public int minLevel;
    /** Highest actual z with data (LotHeader field at +12). */
    public int maxLevel;
    /** Number of z-levels encoded: maxLevel - minLevel + 1. */
    public int levelCount;
    public long[] offsets;

    private byte[] data;
    private LotHeader header;

    public static LotPack read(Path packFile, LotHeader header) throws IOException {
        LotPack lp = new LotPack();
        lp.data = Files.readAllBytes(packFile);
        lp.header = header;
        LE r = new LE(lp.data);
        String magic = new String(r.bytes(4), java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!MAGIC.equals(magic))
            throw new LE.ParseException("expected \"LOTP\" magic, found \"" + magic + "\"");
        lp.version = r.i32();
        lp.chunkCount = r.i32();
        if (lp.chunkCount <= 0 || lp.chunkCount > 1 << 20)
            throw new LE.ParseException("chunkCount " + lp.chunkCount);
        lp.chunksPerSide = (int) Math.round(Math.sqrt(lp.chunkCount));
        if (lp.chunksPerSide * lp.chunksPerSide != lp.chunkCount)
            throw new LE.ParseException("chunkCount " + lp.chunkCount + " is not square");
        lp.cellSize = lp.chunksPerSide * CHUNK_SIZE;
        lp.minLevel = header.minLevel;
        lp.maxLevel = header.unknown12;
        lp.levelCount = lp.maxLevel - lp.minLevel + 1;

        int headerSize = 12 + lp.chunkCount * 8;
        lp.offsets = new long[lp.chunkCount];
        for (int i = 0; i < lp.chunkCount; i++) lp.offsets[i] = r.i64();
        if (lp.offsets[0] != headerSize)
            throw new LE.ParseException("first offset " + lp.offsets[0]
                    + " != header size " + headerSize);
        for (int i = 1; i < lp.chunkCount; i++)
            if (lp.offsets[i] <= lp.offsets[i - 1] || lp.offsets[i] >= lp.data.length)
                throw new LE.ParseException("offset " + i + " = " + lp.offsets[i] + " invalid");
        return lp;
    }

    public int chunkEnd(int chunkIndex) {
        return chunkIndex + 1 < chunkCount ? (int) offsets[chunkIndex + 1] : data.length;
    }

    /**
     * Parse one chunk.
     *
     * The body is driven by file position, not by a fixed level count: trailing
     * empty levels are omitted from the file entirely, so chunk bodies vary in
     * length and a fixed z-loop overruns into the next chunk. Squares are filled
     * in z, x, y order until the body's byte range is exhausted.
     */
    /**
     * Chunk offset table index. COLUMN-MAJOR: cx varies slowest.
     *
     * This was originally row-major, which transposed every coordinate in the
     * cell. Byte round-tripping did not catch it — read and write shared the
     * same wrong formula, so files matched perfectly while the map was mirrored
     * about its diagonal. Found by checking lotheader room rectangles against
     * the tiles beneath them: rooms are indoors, and under the wrong
     * orientation only 5.4% of room squares sat on an interior floor versus
     * 30.6% under the right one.
     */
    public int chunkIndex(int cx, int cy) { return cx * chunksPerSide + cy; }

    public Chunk chunk(int cx, int cy) {
        int idx = chunkIndex(cx, cy);
        int start = (int) offsets[idx], end = chunkEnd(idx);
        LE r = new LE(data);
        r.seek(start);
        Chunk c = new Chunk();
        int square = 0;                       // linear index over z*CHUNK*CHUNK
        int total = MAX_LEVELS * CHUNK_SIZE * CHUNK_SIZE;
        while (r.pos() < end) {
            int count = r.i32();
            if (count == -1) {
                int run = r.i32();
                if (run < 1) throw new LE.ParseException("chunk(" + cx + "," + cy
                        + ") skip run " + run + " at " + (r.pos() - 4));
                square += run;
                if (square > total) throw new LE.ParseException("chunk(" + cx + "," + cy
                        + ") skip run overshoots: " + square + " > " + total);
                continue;
            }
            if (count < 1 || count > 256)
                throw new LE.ParseException("chunk(" + cx + "," + cy + ") count " + count
                        + " at offset " + (r.pos() - 4));
            if (square >= total) throw new LE.ParseException("chunk(" + cx + "," + cy
                    + ") more squares than " + total);
            int z = square / (CHUNK_SIZE * CHUNK_SIZE);
            int rem = square % (CHUNK_SIZE * CHUNK_SIZE);
            int x = rem / CHUNK_SIZE, y = rem % CHUNK_SIZE;
            c.room[z][x][y] = r.i32();
            int[] tiles = new int[count - 1];
            for (int i = 0; i < tiles.length; i++) tiles[i] = r.i32();
            c.tiles[z][x][y] = tiles;
            if (z > c.maxZ) c.maxZ = z;
            square++;
        }
        if (r.pos() != end)
            throw new LE.ParseException("chunk(" + cx + "," + cy + ") ended at " + r.pos()
                    + ", next chunk begins at " + end);
        c.squaresCovered = square;
        c.levelsEncoded = (square + CHUNK_SIZE * CHUNK_SIZE - 1) / (CHUNK_SIZE * CHUNK_SIZE);
        return c;
    }

    // ---------------- writing ----------------

    /**
     * Chunk-body encoding policy. Reading tells us runs of empty squares are
     * written as (-1, count) and that trailing empty levels are omitted, but
     * NOT the encoder's exact choices. These are the plausible variants; the
     * round-trip harness decides which reproduces retail bytes.
     */
    public enum Policy {
        /** Runs may span level boundaries; each chunk encodes only the levels it needs. */
        SPAN_LEVELS_MINIMAL(true, true),
        /** Runs break at level boundaries; each chunk encodes only the levels it needs. */
        BREAK_AT_LEVEL_MINIMAL(false, true),
        /** Runs may span levels; every chunk encodes the cell's full level count. */
        SPAN_LEVELS_FULL(true, false),
        /** Runs break at level boundaries; every chunk encodes the full level count. */
        BREAK_AT_LEVEL_FULL(false, false);

        public final boolean runsSpanLevels;
        public final boolean minimalLevels;
        Policy(boolean span, boolean minimal) { runsSpanLevels = span; minimalLevels = minimal; }
    }

    public static final int SQUARES_PER_LEVEL = CHUNK_SIZE * CHUNK_SIZE;

    /** Original bytes of a chunk body, for byte-comparison against a re-encode. */
    public byte[] rawChunk(int chunkIndex) {
        int start = (int) offsets[chunkIndex], end = chunkEnd(chunkIndex);
        byte[] out = new byte[end - start];
        System.arraycopy(data, start, out, 0, out.length);
        return out;
    }

    public byte[] rawFile() { return data; }

    /** Re-encode a chunk body under the given policy. */
    public byte[] encodeChunk(Chunk c, Policy p) {
        int levels;
        if (p.minimalLevels) {
            int last = -1;
            for (int z = 0; z < MAX_LEVELS; z++)
                for (int x = 0; x < CHUNK_SIZE; x++)
                    for (int y = 0; y < CHUNK_SIZE; y++)
                        if (c.tiles[z][x][y] != null)
                            last = Math.max(last, z * SQUARES_PER_LEVEL + x * CHUNK_SIZE + y);
            levels = last < 0 ? 1 : (last / SQUARES_PER_LEVEL) + 1;
        } else {
            levels = Math.max(1, levelCount);
        }

        LEW w = new LEW();
        int run = 0;
        for (int sq = 0; sq < levels * SQUARES_PER_LEVEL; sq++) {
            int z = sq / SQUARES_PER_LEVEL;
            int rem = sq % SQUARES_PER_LEVEL;
            int x = rem / CHUNK_SIZE, y = rem % CHUNK_SIZE;

            if (!p.runsSpanLevels && rem == 0 && run > 0) { w.i32(-1).i32(run); run = 0; }

            int[] tiles = z < MAX_LEVELS ? c.tiles[z][x][y] : null;
            if (tiles == null) { run++; continue; }
            if (run > 0) { w.i32(-1).i32(run); run = 0; }
            w.i32(tiles.length + 1);
            w.i32(c.room[z][x][y]);
            for (int t : tiles) w.i32(t);
        }
        if (run > 0) w.i32(-1).i32(run);
        return w.toByteArray();
    }

    /** Rebuild the whole file: header, recomputed int64 offset table, chunk bodies. */
    public byte[] write(Policy p) {
        byte[][] bodies = new byte[chunkCount][];
        for (int cy = 0; cy < chunksPerSide; cy++)
            for (int cx = 0; cx < chunksPerSide; cx++) {
                bodies[chunkIndex(cx, cy)] = encodeChunk(chunk(cx, cy), p);
            }
        int headerSize = 12 + chunkCount * 8;
        LEW w = new LEW();
        w.ascii(MAGIC);
        w.i32(version);
        w.i32(chunkCount);
        long off = headerSize;
        for (int i = 0; i < chunkCount; i++) { w.i64(off); off += bodies[i].length; }
        for (byte[] b : bodies) w.bytes(b);
        return w.toByteArray();
    }

    public static final class Chunk {
        public int squaresCovered, levelsEncoded, maxZ = -1;
        public final int[][][][] tiles = new int[MAX_LEVELS][CHUNK_SIZE][CHUNK_SIZE][];
        public final int[][][] room = new int[MAX_LEVELS][CHUNK_SIZE][CHUNK_SIZE];
        Chunk() {
            for (int z = 0; z < MAX_LEVELS; z++)
                for (int x = 0; x < CHUNK_SIZE; x++)
                    java.util.Arrays.fill(room[z][x], -1);
        }
    }

    /** Convert an actual z-level to a chunk array index. */
    public int zIndex(int actualZ) { return actualZ - minLevel; }
    /** Convert a chunk array index to an actual z-level. */
    public int actualZ(int index) { return index + minLevel; }

    /** Tile names for one square, z given as a chunk array index. */
    public String[] tileNamesAt(int cellX, int cellY, int z) {
        Chunk c = chunk(cellX / CHUNK_SIZE, cellY / CHUNK_SIZE);
        int[] t = c.tiles[z][cellX % CHUNK_SIZE][cellY % CHUNK_SIZE];
        if (t == null) return null;
        String[] out = new String[t.length];
        for (int i = 0; i < t.length; i++)
            out[i] = t[i] < header.tileNames.size() ? header.tileNames.get(t[i]) : "?" + t[i];
        return out;
    }
}

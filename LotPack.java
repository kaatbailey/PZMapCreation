package pzformat;

import java.io.IOException;
import java.nio.file.Path;

/**
 * world_X_Y.lotpack -- the actual tile data for one cell.
 *
 * CONFIDENCE: MEDIUM-LOW. This is the least publicly documented format and the
 * one most likely to have changed in Build 42 (cells went 300x300 -> 256x256,
 * and negative z-levels were added for basements). Treat this class as a
 * hypothesis with instrumentation, not as a spec.
 *
 * Assumed layout:
 *   int32                    version
 *   int32 * (cw * ch)        chunk offsets into the file (cw = width / CHUNK)
 *   ... chunk data at those offsets ...
 *
 * Assumed per-chunk body, iterating z, then x, then y within the chunk:
 *   int32 count
 *     count == -1  -> int32 skip : that many consecutive empty squares follow
 *     count >= 1   -> int32 room id, then (count - 1) int32 tile indices
 *                     (indices address LotHeader.tileNames)
 *
 * The offset table is the useful anchor: offsets must be strictly increasing,
 * inside the file, and the first one must equal the header size. validate()
 * checks all three, so you get a hard signal before trusting any tile data.
 */
public final class LotPack {

    /** B41 chunks are 10x10 squares. B42 chunk size is UNCONFIRMED -- probe it. */
    public static final int CHUNK_B41 = 10;

    public int version;
    public int cellWidth, cellHeight, levels, chunkSize;
    public int[] chunkOffsets;

    /** [z][x][y] -> tile index list for that square, or null if empty. */
    public int[][][][] squares;
    /** [z][x][y] -> room id, or -1. */
    public int[][][] roomIds;

    public final java.util.List<String> warnings = new java.util.ArrayList<>();

    private LE r;

    public static LotPack read(Path file, LotHeader header) throws IOException {
        return read(file, header.width, header.height, header.levels, CHUNK_B41);
    }

    public static LotPack read(Path file, int cellW, int cellH, int levels, int chunkSize)
            throws IOException {
        LotPack lp = new LotPack();
        lp.r = LE.of(file);
        lp.cellWidth = cellW; lp.cellHeight = cellH;
        lp.levels = levels; lp.chunkSize = chunkSize;

        if (cellW % chunkSize != 0) {
            lp.warnings.add("cell width " + cellW + " is not divisible by chunk size " + chunkSize
                    + " -- chunk size assumption is almost certainly wrong for this build");
        }

        lp.version = lp.r.i32();
        int cw = cellW / chunkSize, ch = cellH / chunkSize;
        lp.chunkOffsets = new int[cw * ch];
        for (int i = 0; i < lp.chunkOffsets.length; i++) lp.chunkOffsets[i] = lp.r.i32();
        lp.validateOffsets();
        return lp;
    }

    /**
     * Structural sanity check on the chunk offset table. If this passes, the
     * header size and chunk count assumptions are right, which is most of the
     * battle. If it fails, everything downstream is noise.
     */
    public void validateOffsets() {
        int headerSize = 4 + chunkOffsets.length * 4;
        int prev = -1;
        int bad = 0;
        for (int i = 0; i < chunkOffsets.length; i++) {
            int o = chunkOffsets[i];
            if (o < headerSize || o >= r.length()) {
                if (bad++ < 5) {
                    warnings.add("chunk " + i + " offset " + o + " outside valid range ["
                            + headerSize + ", " + r.length() + ")");
                }
            }
            if (o <= prev) {
                if (bad++ < 5) {
                    warnings.add("chunk " + i + " offset " + o + " not greater than previous " + prev);
                }
            }
            prev = o;
        }
        if (bad == 0 && chunkOffsets.length > 0 && chunkOffsets[0] != headerSize) {
            warnings.add("first chunk offset is " + chunkOffsets[0] + " but header size is "
                    + headerSize + " -- there may be extra header fields "
                    + "(chunk count? level count?) before the offset table");
        }
        if (bad > 5) warnings.add("... and " + (bad - 5) + " more offset problems");
    }

    public boolean offsetsLookValid() {
        return warnings.isEmpty();
    }

    /** Parse one chunk's squares. Throws on structural nonsense so you notice. */
    public Chunk readChunk(int cx, int cy) {
        int cw = cellWidth / chunkSize;
        int idx = cy * cw + cx;
        r.seek(chunkOffsets[idx]);
        Chunk c = new Chunk(chunkSize, levels);

        int skip = 0;
        for (int z = 0; z < levels; z++) {
            for (int x = 0; x < chunkSize; x++) {
                for (int y = 0; y < chunkSize; y++) {
                    if (skip > 0) { skip--; continue; }
                    int count = r.i32();
                    if (count == -1) {
                        skip = r.i32() - 1;
                        continue;
                    }
                    if (count < 1 || count > 256) {
                        throw new LE.ParseException("chunk(" + cx + "," + cy + ") z=" + z
                                + " x=" + x + " y=" + y + ": implausible tile count " + count
                                + " at offset " + (r.pos() - 4)
                                + " -- chunk body layout assumption is wrong");
                    }
                    c.room[z][x][y] = r.i32();
                    int[] tiles = new int[count - 1];
                    for (int i = 0; i < tiles.length; i++) tiles[i] = r.i32();
                    c.tiles[z][x][y] = tiles;
                }
            }
        }
        return c;
    }

    public static final class Chunk {
        public final int[][][][] tiles;
        public final int[][][] room;
        Chunk(int size, int levels) {
            tiles = new int[levels][size][size][];
            room = new int[levels][size][size];
            for (int z = 0; z < levels; z++)
                for (int x = 0; x < size; x++)
                    java.util.Arrays.fill(room[z][x], -1);
        }
    }

    public String hexAt(int offset, int n) { return r.hexDump(offset, n); }
    public int fileLength() { return r.length(); }
}

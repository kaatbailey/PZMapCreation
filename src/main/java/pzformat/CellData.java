package pzformat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * A whole cell held in memory and editable, decoupled from file layout.
 *
 * The parsers hand back fresh objects per call, which is right for reading and
 * useless for editing. This owns the data: load once, mutate, write back.
 *
 * Coordinates are cell-local (0..255 on each axis). z is an ACTUAL level, so
 * basements are negative; the chunk array index is z - minLevel.
 */
public final class CellData {

    public final LotHeader header;
    public final int cellSize, chunksPerSide, levelCount, minLevel, maxLevel;

    /** [zIndex][x][y] -> tile indices into header.tileNames, or null if empty. */
    private final int[][][][] tiles;
    /** [zIndex][x][y] -> room id, -1 for none. */
    private final int[][][] rooms;

    public int version = 1;

    private CellData(LotHeader h, int chunksPerSide, int levelCount) {
        this.header = h;
        this.chunksPerSide = chunksPerSide;
        this.cellSize = chunksPerSide * LotPack.CHUNK_SIZE;
        this.levelCount = levelCount;
        this.minLevel = h.minLevel;
        this.maxLevel = h.maxLevel();
        tiles = new int[levelCount][cellSize][cellSize][];
        rooms = new int[levelCount][cellSize][cellSize];
        for (int z = 0; z < levelCount; z++)
            for (int x = 0; x < cellSize; x++)
                Arrays.fill(rooms[z][x], -1);
    }

    /**
     * An empty cell built from nothing, for generated maps.
     * The header must already carry its tile names and level range.
     */
    public static CellData blank(LotHeader h, int chunksPerSide) {
        return new CellData(h, chunksPerSide, h.maxLevel() - h.minLevel + 1);
    }

    /** A header for a generated cell: B42 layout, no rooms or buildings yet. */
    public static LotHeader newHeader(List<String> tileNames, int minLevel, int maxLevel) {
        LotHeader h = new LotHeader();
        h.b42 = true;
        h.version = 1;
        h.levelsAbove = 8;
        h.levelsBelow = 8;
        h.minLevel = minLevel;
        h.unknown12 = maxLevel;
        h.tileNames.addAll(tileNames);
        h.chunkGrid = new byte[LotHeader.GRID_BYTES];
        h.fullyConsumed = true;
        return h;
    }

    public static CellData load(Path lotpack, Path lotheader) throws IOException {
        LotHeader h = LotHeader.read(lotheader);
        LotPack lp = LotPack.read(lotpack, h);
        CellData c = new CellData(h, lp.chunksPerSide, lp.levelCount);
        c.version = lp.version;
        for (int cy = 0; cy < lp.chunksPerSide; cy++)
            for (int cx = 0; cx < lp.chunksPerSide; cx++) {
                LotPack.Chunk ch = lp.chunk(cx, cy);
                for (int z = 0; z < c.levelCount; z++)
                    for (int x = 0; x < LotPack.CHUNK_SIZE; x++)
                        for (int y = 0; y < LotPack.CHUNK_SIZE; y++) {
                            int gx = cx * LotPack.CHUNK_SIZE + x;
                            int gy = cy * LotPack.CHUNK_SIZE + y;
                            c.tiles[z][gx][gy] = ch.tiles[z][x][y];
                            c.rooms[z][gx][gy] = ch.room[z][x][y];
                        }
            }
        return c;
    }

    // ---------------- accessors ----------------

    public int zIndex(int actualZ) { return actualZ - minLevel; }

    /** Column-major, matching LotPack. See the note there. */
    public int chunkIndex(int cx, int cy) { return cx * chunksPerSide + cy; }

    private void checkZ(int zi) {
        if (zi < 0 || zi >= levelCount)
            throw new IllegalArgumentException("z index " + zi + " outside 0.." + (levelCount - 1)
                    + " (cell covers actual z " + minLevel + ".." + maxLevel + ")");
    }

    /** Tile indices at a square, or null if the square is empty. */
    public int[] tilesAt(int x, int y, int actualZ) {
        int zi = zIndex(actualZ); checkZ(zi);
        return tiles[zi][x][y];
    }

    public String[] tileNamesAt(int x, int y, int actualZ) {
        int[] t = tilesAt(x, y, actualZ);
        if (t == null) return null;
        String[] out = new String[t.length];
        for (int i = 0; i < t.length; i++)
            out[i] = t[i] < header.tileNames.size() ? header.tileNames.get(t[i]) : "?" + t[i];
        return out;
    }

    public int roomAt(int x, int y, int actualZ) {
        int zi = zIndex(actualZ); checkZ(zi);
        return rooms[zi][x][y];
    }

    public void setSquare(int x, int y, int actualZ, int[] tileIndices, int roomId) {
        int zi = zIndex(actualZ); checkZ(zi);
        tiles[zi][x][y] = tileIndices;
        rooms[zi][x][y] = roomId;
    }

    /**
     * Index of a tile name, appending to the header's table if absent.
     * Appending is safe: existing indices are unchanged.
     */
    public int tileIndex(String name) {
        int i = header.tileNames.indexOf(name);
        if (i >= 0) return i;
        header.tileNames.add(name);
        return header.tileNames.size() - 1;
    }

    /** Replace the tiles on a rectangle with a single tile. Returns squares changed. */
    public int fill(String tileName, int x0, int y0, int w, int h, int actualZ) {
        int idx = tileIndex(tileName);
        int zi = zIndex(actualZ); checkZ(zi);
        int changed = 0;
        for (int x = x0; x < x0 + w; x++)
            for (int y = y0; y < y0 + h; y++) {
                if (x < 0 || y < 0 || x >= cellSize || y >= cellSize) continue;
                int[] want = {idx};
                if (!Arrays.equals(tiles[zi][x][y], want)) changed++;
                tiles[zi][x][y] = want;
            }
        return changed;
    }

    // ---------------- writing ----------------

    /**
     * Serialise to lotpack bytes using the confirmed encoder policy:
     * every chunk covers the cell's full levelCount, runs of empty squares
     * span level boundaries.
     */
    public byte[] writeLotPack() {
        int chunkCount = chunksPerSide * chunksPerSide;
        byte[][] bodies = new byte[chunkCount][];
        for (int cy = 0; cy < chunksPerSide; cy++)
            for (int cx = 0; cx < chunksPerSide; cx++)
                bodies[chunkIndex(cx, cy)] = encodeChunk(cx, cy);

        int headerSize = 12 + chunkCount * 8;
        LEW w = new LEW();
        w.ascii(LotPack.MAGIC);
        w.i32(version);
        w.i32(chunkCount);
        long off = headerSize;
        for (byte[] b : bodies) { w.i64(off); off += b.length; }
        for (byte[] b : bodies) w.bytes(b);
        return w.toByteArray();
    }

    private byte[] encodeChunk(int cx, int cy) {
        LEW w = new LEW();
        int run = 0;
        int cs = LotPack.CHUNK_SIZE;
        for (int z = 0; z < levelCount; z++)
            for (int x = 0; x < cs; x++)
                for (int y = 0; y < cs; y++) {
                    int gx = cx * cs + x, gy = cy * cs + y;
                    int[] t = tiles[z][gx][gy];
                    if (t == null) { run++; continue; }
                    if (run > 0) { w.i32(-1).i32(run); run = 0; }
                    w.i32(t.length + 1);
                    w.i32(rooms[z][gx][gy]);
                    for (int v : t) w.i32(v);
                }
        if (run > 0) w.i32(-1).i32(run);
        return w.toByteArray();
    }

    public byte[] writeLotHeader() { return header.write(); }

    // ---------------- diffing ----------------

    public static final class Diff {
        public int squaresChanged, squaresAdded, squaresRemoved;
        public final List<String> samples = new ArrayList<>();
        public boolean isEmpty() { return squaresChanged + squaresAdded + squaresRemoved == 0; }
        @Override public String toString() {
            return squaresChanged + " changed, " + squaresAdded + " added, "
                    + squaresRemoved + " removed";
        }
    }

    /** Square-by-square comparison, for proving an edit was surgical. */
    public static Diff diff(CellData a, CellData b) {
        Diff d = new Diff();
        int levels = Math.min(a.levelCount, b.levelCount);
        for (int z = 0; z < levels; z++)
            for (int x = 0; x < Math.min(a.cellSize, b.cellSize); x++)
                for (int y = 0; y < Math.min(a.cellSize, b.cellSize); y++) {
                    int[] ta = a.tiles[z][x][y], tb = b.tiles[z][x][y];
                    if (ta == null && tb == null) continue;
                    if (ta == null) { d.squaresAdded++; note(d, a, x, y, z, ta, tb); continue; }
                    if (tb == null) { d.squaresRemoved++; note(d, a, x, y, z, ta, tb); continue; }
                    if (!Arrays.equals(ta, tb) || a.rooms[z][x][y] != b.rooms[z][x][y]) {
                        d.squaresChanged++;
                        note(d, a, x, y, z, ta, tb);
                    }
                }
        return d;
    }

    private static void note(Diff d, CellData a, int x, int y, int zi, int[] ta, int[] tb) {
        if (d.samples.size() >= 6) return;
        d.samples.add("(" + x + "," + y + ",z" + (zi + a.minLevel) + ") "
                + Arrays.toString(ta) + " -> " + Arrays.toString(tb));
    }

    public long nonEmptySquares() {
        long n = 0;
        for (int z = 0; z < levelCount; z++)
            for (int x = 0; x < cellSize; x++)
                for (int y = 0; y < cellSize; y++)
                    if (tiles[z][x][y] != null) n++;
        return n;
    }
}

package pzformat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Java side of the port-step-7 oracle for {@link BiomeMapWriter} and the PNG
 * encoder. Harness Pattern B (STATE §38): a canonical text digest that must be
 * BYTE-IDENTICAL to biomemap_oracle.cpp's.
 *
 * ----------------------------------------------------------------------
 * WHY THE PNG SWEEP IS IN HERE
 * ----------------------------------------------------------------------
 *
 * `biomemap_X_Y.png` is SHIPPED MOD CONTENT — the engine reads it through
 * BiomeMap.getRaster and BiomeMapConfig.lua — so step 7's byte-identical
 * mod-output oracle covers it whether we like it or not.
 *
 * Qt's QImage does not reproduce ImageIO and cannot be made to (measured
 * 2026-09-02: pHYs chunk, per-row filter choice, zlib level 785e vs 789c, IDAT
 * chunking 32768 vs 8192 — four independent differences, though decoded pixels
 * match in every case). `pzpng` reproduces ImageIO exactly instead.
 *
 * THAT AGREEMENT IS BETWEEN TWO IMPLEMENTATIONS, NOT A STANDARD. Java's
 * Deflater IS zlib, so the match is real rather than lucky, but zlib's output
 * is not formally guaranteed stable across versions, and the level (4) is
 * measured rather than documented — the zlib header only narrows it to 2..5.
 *
 * The PNG section below IS the standing falsifier: 200 varied buffers, encoded
 * both ways, compared as bytes. Re-run it whenever either toolchain moves.
 * VERIFY.md §5 points here.
 *
 * ----------------------------------------------------------------------
 * SECTIONS
 * ----------------------------------------------------------------------
 *
 * VIN  The eight biome_map_config indices and the three radii. These are a
 *      DESIGN CHOICE rather than a measurement — nothing in the game says a
 *      GIS footprint should be a TownZone — but changing them during a port
 *      would break the only oracle (§40), so they are pinned here.
 *
 * PNG  200 buffers: uniform, blocked, gradient, pure noise, and the four-band
 *      shape BiomeMapWriter actually produces. Pure noise matters most — an
 *      adaptive filter writer would certainly diverge there, and Java's does
 *      not, using filter None on all 256 rows even then.
 *
 * BM   `cellPixels` over synthetic rasters, digested as pixels. Includes a
 *      raster SMALLER than one cell, so the `gx >= g.width` beyond-raster
 *      branch fires; a raster larger than the cell grid; and the no-structure
 *      case where every distance is Integer.MAX_VALUE and every pixel lands in
 *      the last band.
 *
 * BMP  The same cells encoded, digested as PNG BYTES. This is the check the
 *      synthetic PNG section cannot make: that the encoder still matches on a
 *      buffer this pipeline actually produces.
 *
 * BMW  `write` end to end: image count, the filenames it chose, and the log
 *      lines it prints. Cell naming is `originCellX + cx`, which is easy to
 *      transpose and would ship a correct map under wrong filenames.
 *
 *   java -cp out pzformat.BiomeMapOracle /tmp/bm.java.txt
 */
public final class BiomeMapOracle {

    private BiomeMapOracle() { }

    private static final StringBuilder OUT = new StringBuilder();

    private static void line(String s) { OUT.append(s).append('\n'); }

    private static long fnv(long h, long v) {
        for (int i = 0; i < 8; i++) { h ^= (v >>> (i * 8)) & 0xffL; h *= 0x100000001b3L; }
        return h;
    }

    private static final long FNV_INIT = 0xcbf29ce484222325L;

    private static long fnvBytes(byte[] b) {
        long h = FNV_INIT;
        for (byte x : b) h = fnv(h, x & 0xff);
        return h;
    }

    private static String hex(long v) { return String.format("%016x", v); }

    // ------------------------------------------------------------------

    private static void vin() {
        line("VIN\tTOWN\t" + BiomeMapWriter.TOWN);
        line("VIN\tFARM\t" + BiomeMapWriter.FARM);
        line("VIN\tFARMLAND\t" + BiomeMapWriter.FARMLAND);
        line("VIN\tFARM_FOREST\t" + BiomeMapWriter.FARM_FOREST);
        line("VIN\tPH_FOREST\t" + BiomeMapWriter.PH_FOREST);
        line("VIN\tBIRCH_FOREST\t" + BiomeMapWriter.BIRCH_FOREST);
        line("VIN\tDEEP_FOREST\t" + BiomeMapWriter.DEEP_FOREST);
        line("VIN\tDIRT\t" + BiomeMapWriter.DIRT);
        line("VIN\tTOWN_RADIUS\t" + BiomeMapWriter.TOWN_RADIUS);
        line("VIN\tEDGE_RADIUS\t" + BiomeMapWriter.EDGE_RADIUS);
        line("VIN\tFOREST_RADIUS\t" + BiomeMapWriter.FOREST_RADIUS);
    }

    // ------------------------------------------------------------------
    // PNG — the standing falsifier
    // ------------------------------------------------------------------

    /** 200 varied 256x256 buffers, identical on both sides by construction. */
    private static byte[] sweepBuffer(Random r, int mode) {
        byte[] buf = new byte[256 * 256 * 3];
        int c1 = r.nextInt(256), c2 = r.nextInt(256), c3 = r.nextInt(256);
        for (int y = 0; y < 256; y++)
            for (int x = 0; x < 256; x++) {
                int v;
                switch (mode) {
                    case 0: v = c1; break;                                   // uniform
                    case 1: v = ((x / 16 + y / 16) % 2 == 0) ? c1 : c2; break; // blocks
                    case 2: v = (x * c1 + y * c2) & 0xff; break;             // gradient
                    case 3: v = r.nextInt(256); break;                       // noise
                    default: {                                               // bands
                        int d = Math.max(Math.abs(x - c1), Math.abs(y - c2));
                        v = d <= 10 ? c1 : d <= 28 ? c2 : d <= 70 ? c3 : 255;
                    }
                }
                int i = (y * 256 + x) * 3;
                buf[i] = (byte) v; buf[i + 1] = (byte) v; buf[i + 2] = (byte) v;
            }
        return buf;
    }

    private static byte[] encode(byte[] rgb, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 3;
                img.setRGB(x, y, ((rgb[i] & 0xff) << 16) | ((rgb[i + 1] & 0xff) << 8)
                                 | (rgb[i + 2] & 0xff));
            }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static void pngSection() throws Exception {
        Random r = new Random(4242L);
        for (int k = 0; k < 200; k++) {
            byte[] rgb = sweepBuffer(r, k % 5);
            byte[] png = encode(rgb, 256, 256);
            line("PNG\t" + k + "\t" + png.length + "\t" + hex(fnvBytes(png)));
        }
    }

    // ------------------------------------------------------------------

    private static GisImport raster(int kind, int w, int h) {
        GisImport g = new GisImport();
        g.width = w; g.height = h;
        g.cover = new GisImport.Cover[w][h];
        g.northWall = new boolean[w][h];
        g.westWall = new boolean[w][h];
        for (GisImport.Cover[] col : g.cover) Arrays.fill(col, GisImport.Cover.NONE);
        switch (kind) {
            case 0: break;                                  // no structure at all
            case 1:
                for (int x = 0; x < w; x++)
                    for (int y = h / 2 - 1; y <= h / 2 + 1; y++)
                        if (y >= 0 && y < h) g.cover[x][y] = GisImport.Cover.ROAD;
                break;
            case 2:
                for (int x = w / 4; x < w / 4 + 8 && x < w; x++)
                    for (int y = h / 4; y < h / 4 + 8 && y < h; y++)
                        g.cover[x][y] = GisImport.Cover.BUILDING;
                break;
            case 3:
                g.cover[0][0] = GisImport.Cover.BUILDING;
                break;
            default: {
                Random r = new Random(7L);
                for (int x = 0; x < w; x++)
                    for (int y = 0; y < h; y++) {
                        int v = r.nextInt(400);
                        if (v == 0) g.cover[x][y] = GisImport.Cover.BUILDING;
                        else if (v == 1) g.cover[x][y] = GisImport.Cover.ROAD;
                    }
                break;
            }
        }
        return g;
    }

    /** w x h, cells across, cells down. A raster SMALLER than one cell is the point. */
    private static final int[][] CASES = {
        {200, 150, 1, 1},   // smaller than one cell: beyond-raster fires hard
        {512, 512, 2, 2},   // exactly the cell grid
        {600, 400, 3, 2},   // wider than tall, ragged on both axes
        {400, 600, 2, 3},   // taller than wide
        {256, 256, 1, 1},   // exactly one cell, no beyond-raster at all
        {300, 300, 2, 2},   // grid larger than the raster
    };

    private static void biomeSection() throws Exception {
        for (int ci = 0; ci < CASES.length; ci++) {
            int[] c = CASES[ci];
            for (int kind = 0; kind <= 4; kind++) {
                GisImport g = raster(kind, c[0], c[1]);
                int[][] dist = TreeScatter.distanceToStructure(g);

                for (int cy = 0; cy < c[3]; cy++)
                    for (int cx = 0; cx < c[2]; cx++) {
                        byte[] rgb = cellPixels(g, dist, cx, cy);
                        long ph = fnvBytes(rgb);
                        long[] hist = new long[256];
                        for (int i = 0; i < rgb.length; i += 3) hist[rgb[i] & 0xff]++;
                        StringBuilder hs = new StringBuilder();
                        for (int v : new int[]{BiomeMapWriter.TOWN, BiomeMapWriter.FARM_FOREST,
                                               BiomeMapWriter.PH_FOREST,
                                               BiomeMapWriter.DEEP_FOREST}) {
                            if (hs.length() > 0) hs.append(',');
                            hs.append(hist[v]);
                        }
                        line("BM\t" + ci + "\t" + kind + "\t" + cx + "\t" + cy + "\t"
                             + hex(ph) + "\t" + hs);
                        byte[] png = encode(rgb, 256, 256);
                        line("BMP\t" + ci + "\t" + kind + "\t" + cx + "\t" + cy + "\t"
                             + png.length + "\t" + hex(fnvBytes(png)));
                    }
            }
        }
    }

    /** Mirrors BiomeMapWriter's inner loop; the C++ side calls cellPixels directly. */
    private static byte[] cellPixels(GisImport g, int[][] dist, int cx, int cy) {
        byte[] rgb = new byte[256 * 256 * 3];
        int ox = cx * 256, oy = cy * 256;
        for (int x = 0; x < 256; x++)
            for (int y = 0; y < 256; y++) {
                int gx = ox + x, gy = oy + y;
                int value;
                if (gx >= g.width || gy >= g.height) {
                    value = BiomeMapWriter.DEEP_FOREST;
                } else {
                    int d = dist[gx][gy];
                    if (d <= BiomeMapWriter.TOWN_RADIUS) value = BiomeMapWriter.TOWN;
                    else if (d <= BiomeMapWriter.EDGE_RADIUS) value = BiomeMapWriter.FARM_FOREST;
                    else if (d <= BiomeMapWriter.FOREST_RADIUS) value = BiomeMapWriter.PH_FOREST;
                    else value = BiomeMapWriter.DEEP_FOREST;
                }
                int i = (y * 256 + x) * 3;
                rgb[i] = (byte) value; rgb[i + 1] = (byte) value; rgb[i + 2] = (byte) value;
            }
        return rgb;
    }

    // ------------------------------------------------------------------

    private static void writeSection() throws Exception {
        int c = 0;
        for (int[] cs : CASES)
            for (int kind = 0; kind <= 4; kind += 2)
                for (int[] origin : new int[][]{{200, 200}, {0, 0}, {17, 993}}) {
                    GisImport g = raster(kind, cs[0], cs[1]);
                    Path dir = Files.createTempDirectory("bmo");
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    java.io.PrintStream old = System.out;
                    System.setOut(new java.io.PrintStream(buf, true,
                                  java.nio.charset.StandardCharsets.UTF_8));
                    int n;
                    try {
                        n = BiomeMapWriter.write(g, dir, cs[2], cs[3], origin[0], origin[1]);
                    } finally {
                        System.setOut(old);
                    }
                    List<String> names = new ArrayList<>();
                    try (var s = Files.list(dir.resolve("maps"))) {
                        s.forEach(p -> names.add(p.getFileName().toString()));
                    }
                    names.sort(null);
                    long fh = FNV_INIT;
                    for (String nm : names) {
                        byte[] b = Files.readAllBytes(dir.resolve("maps").resolve(nm));
                        fh = fnv(fh, fnvBytes(b));
                    }
                    line("BMW\t" + c + "\t" + n + "\t" + origin[0] + "_" + origin[1]
                         + "\t" + String.join(",", names) + "\t" + hex(fh));
                    for (String s2 : buf.toString(java.nio.charset.StandardCharsets.UTF_8)
                                        .split("\n", -1))
                        if (!s2.isEmpty()) line("BMWLOG\t" + c + "\t" + s2);
                    deleteTree(dir);
                    c++;
                }
    }

    private static void deleteTree(Path p) throws Exception {
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
                try { Files.delete(q); } catch (Exception ignored) { }
            });
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java pzformat.BiomeMapOracle <out-path>");
            System.exit(2);
        }
        vin();
        pngSection();
        biomeSection();
        writeSection();

        byte[] b = OUT.toString().getBytes("UTF-8");
        Files.write(Paths.get(args[0]), b);
        System.out.println("BiomeMapOracle java: "
                + OUT.chars().filter(ch -> ch == '\n').count()
                + " lines, " + b.length + " bytes -> " + args[0]);
    }
}

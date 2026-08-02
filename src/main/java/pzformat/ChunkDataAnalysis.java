package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Works out the layout of chunkdata_X_Y.bin.
 *
 * This is the file we do not generate, and the likely reason WorldGen paves
 * over authored terrain: something has to mark a chunk as hand-made so
 * generation skips it.
 *
 * First question is whether the size is CONSTANT across cells. A fixed size
 * means a flat per-chunk grid and the layout falls out of arithmetic; a varying
 * size means it is compressed or structured, and correlating size against cell
 * content tells us against what.
 *
 * A cell is 256x256 with 8x8 chunks, so 1024 chunks. One sample (Maplewood,
 * 2114 bytes) fits 2 + 64 + 1024*2 — a 66-byte header over a 2-byte-per-chunk
 * grid. Thousands of vanilla files will confirm or kill that.
 */
public final class ChunkDataAnalysis {

    public static void run(Path mapDir) throws Exception {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mapDir, "chunkdata_*.bin")) {
            for (Path p : ds) files.add(p);
        }
        Collections.sort(files);
        System.out.println("chunkdata files: " + files.size());
        if (files.isEmpty()) { System.out.println("none found in " + mapDir); return; }

        Map<Long, Integer> sizes = new TreeMap<>();
        for (Path f : files) sizes.merge(Files.size(f), 1, Integer::sum);
        System.out.println("\ndistinct sizes: " + sizes.size());
        sizes.entrySet().stream().limit(12).forEach(e ->
                System.out.printf("   %8d bytes  x%d%n", e.getKey(), e.getValue()));
        if (sizes.size() > 12) System.out.println("   ... and " + (sizes.size() - 12) + " more");

        boolean constant = sizes.size() == 1;
        System.out.println(constant
                ? "\n=> constant size: a flat per-chunk grid"
                : "\n=> size varies with content: compressed or variable-length");

        // Header candidates: for a flat grid, (size - header) must divide evenly
        // by the chunk count for EVERY file. One counterexample kills a candidate.
        System.out.println("\ntesting flat-grid layouts (1024 chunks per cell):");
        for (int bytesPerChunk : new int[]{1, 2, 4}) {
            for (int header = 0; header <= 128; header++) {
                boolean all = true;
                for (Long sz : sizes.keySet())
                    if (sz - header != (long) 1024 * bytesPerChunk) { all = false; break; }
                if (all)
                    System.out.printf("   FITS: %d-byte header + %d bytes/chunk%n",
                            header, bytesPerChunk);
            }
        }

        // Value distribution across a sample.
        Map<Integer, Long> hist = new TreeMap<>();
        long total = 0;
        int sampled = 0;
        for (Path f : files) {
            byte[] d = Files.readAllBytes(f);
            for (int i = 2; i < d.length; i++) { hist.merge(d[i] & 0xFF, 1L, Long::sum); total++; }
            if (++sampled >= 200) break;
        }
        final long totalBytes = total;
        System.out.println("\nbyte values across " + sampled + " files (" + total + " bytes):");
        hist.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(12)
            .forEach(e -> System.out.printf("   %3d -> %d  (%.1f%%)%n",
                    e.getKey(), e.getValue(), 100.0 * e.getValue() / totalBytes));

        // First bytes of a few files: a header should look consistent.
        System.out.println("\nfirst 24 bytes of several files:");
        for (int i = 0; i < Math.min(6, files.size()); i++) {
            Path f = files.get(i);
            byte[] d = Files.readAllBytes(f);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < Math.min(24, d.length); j++)
                sb.append(String.format("%02X ", d[j] & 0xFF));
            System.out.printf("   %-24s %s%n", f.getFileName(), sb.toString().trim());
        }

        // Does size track how much of the cell is built up? If it does, the file
        // describes content rather than a fixed grid.
        if (!constant) correlate(mapDir, files);

        blockHypothesis(files);
    }

    /**
     * Hypothesis from the size distribution: int16 version, then a 1024-byte
     * per-chunk grid (32x32 chunks), then N blocks of 64 bytes.
     *
     * 64 bytes is one byte per tile in an 8x8 chunk, so the blocks are probably
     * per-tile detail for chunks that are not uniform. If so, the number of
     * blocks should equal the count of some marker value in the grid — and that
     * is an exact prediction, testable on every file.
     */
    static void blockHypothesis(List<Path> files) throws Exception {
        System.out.println("\n=== hypothesis: 2 + 1024 grid + N x 64 blocks ===");
        int ok = 0, bad = 0;
        Map<Integer, Integer> blockCounts = new TreeMap<>();
        for (Path f : files) {
            long sz = Files.size(f);
            long rest = sz - 2 - 1024;
            if (rest < 0 || rest % 64 != 0) { bad++; continue; }
            ok++;
            blockCounts.merge((int) (rest / 64), 1, Integer::sum);
        }
        System.out.printf("   sizes fitting the shape: %d / %d%n", ok, ok + bad);
        if (bad > 0) { System.out.println("   => shape is wrong"); return; }
        System.out.println("   block counts (blocks -> files):");
        blockCounts.entrySet().stream().limit(8).forEach(e ->
                System.out.printf("      %4d -> %d%n", e.getKey(), e.getValue()));

        // Which grid value predicts the block count? Test every candidate.
        System.out.println("\n   does a grid value predict the number of blocks?");
        int[] matches = new int[256];
        int tested = 0;
        for (Path f : files) {
            byte[] d = Files.readAllBytes(f);
            int blocks = (d.length - 2 - 1024) / 64;
            int[] counts = new int[256];
            for (int i = 2; i < 2 + 1024; i++) counts[d[i] & 0xFF]++;
            for (int v = 0; v < 256; v++) if (counts[v] == blocks) matches[v]++;
            // also: count of values with the high bit set, a common "has detail" flag
            if (++tested >= 500) break;
        }
        final int n = tested;
        List<Integer> best = new ArrayList<>();
        for (int v = 0; v < 256; v++) if (matches[v] == n) best.add(v);
        if (best.isEmpty()) {
            System.out.println("      no single value matches in all " + n + " files");
            for (int v = 0; v < 256; v++)
                if (matches[v] > n * 0.8)
                    System.out.printf("      value %3d matches in %d / %d (%.0f%%)%n",
                            v, matches[v], n, 100.0 * matches[v] / n);
        } else {
            for (int v : best)
                System.out.println("      value " + v + " count == block count in ALL "
                        + n + " files");
        }

        smallestCases(files);

        verifyMarkerValue(files);
    }

    /**
     * The four single-block files all had exactly one chunk valued 2, and one
     * of them carried a block whose bytes were the OTHER grid values present in
     * that cell. So value 2 marks a chunk as mixed, and its 64-byte block gives
     * per-tile values for the 8x8 chunk.
     *
     * Checked here against every file: count of 2s must equal the block count,
     * exactly, with no exceptions.
     */
    static void verifyMarkerValue(List<Path> files) throws Exception {
        System.out.println("\n=== verifying: grid value 2 marks a chunk with a detail block ===");
        int exact = 0, off = 0;
        List<String> misses = new ArrayList<>();
        Map<Integer, Integer> blockByteHist = new TreeMap<>();
        long blockBytes = 0;

        for (Path f : files) {
            byte[] d = Files.readAllBytes(f);
            int blocks = (d.length - 2 - 1024) / 64;
            int twos = 0;
            for (int i = 2; i < 2 + 1024; i++) if ((d[i] & 0xFF) == 2) twos++;
            if (twos == blocks) exact++;
            else {
                off++;
                if (misses.size() < 6)
                    misses.add(f.getFileName() + ": " + twos + " twos vs " + blocks + " blocks");
            }
            for (int i = 2 + 1024; i < d.length; i++) {
                blockByteHist.merge(d[i] & 0xFF, 1, Integer::sum);
                blockBytes++;
            }
        }
        System.out.printf("   count of value 2 == block count in %d / %d files  (%.2f%%)%n",
                exact, exact + off, 100.0 * exact / (exact + off));
        for (String m : misses) System.out.println("      " + m);
        if (off == 0)
            System.out.println("   => CONFIRMED across every cell");

        final long bb = blockBytes;
        System.out.println("\n   values inside detail blocks (" + blockBytes + " bytes):");
        blockByteHist.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .forEach(e -> System.out.printf("      %3d -> %d  (%.1f%%)%n",
                        e.getKey(), e.getValue(), 100.0 * e.getValue() / bb));
        System.out.println("\n   note: 2 never appears inside a block if blocks are"
                + " leaf data (no recursion)");
        System.out.println("   value 2 inside blocks: "
                + blockByteHist.getOrDefault(2, 0));
    }

    static void correlate(Path mapDir, List<Path> files) throws Exception {
        System.out.println("\ndoes size track cell content?");
        System.out.printf("   %-12s %8s %10s %10s%n", "cell", "bytes", "occupied", "tileNames");
        int shown = 0;
        List<long[]> pairs = new ArrayList<>();
        for (Path f : files) {
            String cell = f.getFileName().toString()
                    .replace("chunkdata_", "").replace(".bin", "");
            Path lh = mapDir.resolve(cell + ".lotheader");
            Path lp = mapDir.resolve("world_" + cell + ".lotpack");
            if (!Files.exists(lh) || !Files.exists(lp)) continue;
            try {
                LotHeader h = LotHeader.read(lh);
                CellData c = CellData.load(lp, lh);
                long occupied = c.nonEmptySquares();
                long size = Files.size(f);
                pairs.add(new long[]{size, occupied});
                if (shown < 15) {
                    System.out.printf("   %-12s %8d %10d %10d%n",
                            cell, size, occupied, h.tileNames.size());
                    shown++;
                }
            } catch (Exception ignored) { }
            if (pairs.size() >= 300) break;
        }
        if (pairs.size() > 3) {
            double r = pearson(pairs);
            System.out.printf("%n   correlation between size and occupied squares: %.3f%n", r);
            System.out.println(Math.abs(r) > 0.7
                    ? "   => size tracks how built-up the cell is"
                    : "   => size does not track occupancy; it encodes something else");
        }
    }

    /**
     * Look at the simplest non-trivial files directly.
     *
     * Two hypotheses about what triggers a detail block have now failed, so
     * stop testing theories in bulk and read a file with exactly ONE block:
     * whatever is special about that cell has to be visible.
     */
    static void smallestCases(List<Path> files) throws Exception {
        System.out.println("\n=== files with exactly one 64-byte block ===");
        int shown = 0;
        for (Path f : files) {
            byte[] d = Files.readAllBytes(f);
            int blocks = (d.length - 2 - 1024) / 64;
            if (blocks != 1) continue;

            int[] counts = new int[256];
            for (int i = 2; i < 2 + 1024; i++) counts[d[i] & 0xFF]++;
            StringBuilder vals = new StringBuilder();
            for (int v = 0; v < 256; v++)
                if (counts[v] > 0) vals.append(v).append("x").append(counts[v]).append("  ");

            System.out.println("\n   " + f.getFileName() + "  (" + d.length + " bytes)");
            System.out.println("      grid values: " + vals.toString().trim());

            // Where do rare values sit? A lone odd chunk is the obvious suspect.
            for (int v = 0; v < 256; v++) {
                if (counts[v] == 0 || counts[v] > 4) continue;
                StringBuilder where = new StringBuilder();
                for (int i = 0; i < 1024; i++)
                    if ((d[2 + i] & 0xFF) == v)
                        where.append("(").append(i % 32).append(",").append(i / 32).append(") ");
                System.out.println("      value " + v + " at chunk " + where.toString().trim());
            }

            StringBuilder blk = new StringBuilder();
            for (int i = 0; i < 64; i++)
                blk.append(String.format("%02X ", d[2 + 1024 + i] & 0xFF));
            System.out.println("      block bytes: " + blk.toString().trim());

            if (++shown >= 4) break;
        }

        // How many DISTINCT values appear, versus block count? A block per
        // distinct value would mean the blocks are a palette, not per-chunk data.
        System.out.println("\n=== is the block count the number of distinct grid values? ===");
        int agree = 0, checked = 0;
        for (Path f : files) {
            byte[] d = Files.readAllBytes(f);
            int blocks = (d.length - 2 - 1024) / 64;
            Set<Integer> distinct = new HashSet<>();
            for (int i = 2; i < 2 + 1024; i++) distinct.add(d[i] & 0xFF);
            if (distinct.size() - 1 == blocks || distinct.size() == blocks) agree++;
            if (++checked >= 500) break;
        }
        System.out.printf("   distinct-value count matches block count in %d / %d files%n",
                agree, checked);

        // Or: blocks for chunks whose value exceeds some threshold.
        System.out.println("\n=== is it chunks with a value at or above a threshold? ===");
        for (int t = 1; t <= 64; t++) {
            int all = 0, ck = 0;
            for (Path f : files) {
                byte[] d = Files.readAllBytes(f);
                int blocks = (d.length - 2 - 1024) / 64;
                int over = 0;
                for (int i = 2; i < 2 + 1024; i++) if ((d[i] & 0xFF) >= t) over++;
                if (over == blocks) all++;
                if (++ck >= 300) break;
            }
            if (all > ck * 0.9)
                System.out.printf("   threshold >= %d matches in %d / %d files%n", t, all, ck);
        }
    }

    static double pearson(List<long[]> pairs) {
        double n = pairs.size(), sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (long[] p : pairs) {
            sx += p[0]; sy += p[1];
            sxx += (double) p[0] * p[0];
            syy += (double) p[1] * p[1];
            sxy += (double) p[0] * p[1];
        }
        double num = n * sxy - sx * sy;
        double den = Math.sqrt((n * sxx - sx * sx) * (n * syy - sy * sy));
        return den == 0 ? 0 : num / den;
    }
}

package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Read -> write -> byte-compare against the original file.
 *
 * Parsing without error only proves we consumed every byte. Round-tripping
 * proves we RETAINED every byte, which is a much stronger claim and the real
 * test of whether the read model is complete. Any field we silently dropped,
 * any ordering we guessed wrong, shows up here as a concrete byte offset.
 *
 * For lotpacks the chunk-body encoding policy is not known from reading, so
 * every candidate policy is tried and scored. A policy that reproduces retail
 * bytes across thousands of chunks is the encoder's actual policy.
 */
public final class RoundTrip {

    public static void run(Path dir, int limit) throws Exception {
        List<Path> headers = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.lotheader")) {
            for (Path p : ds) headers.add(p);
        }
        Collections.sort(headers);
        if (limit > 0 && headers.size() > limit) headers = headers.subList(0, limit);
        System.out.println("cells to test: " + headers.size() + "\n");

        headerRoundTrip(headers);
        lotpackRoundTrip(dir, headers);
    }

    // ---------------- lotheader ----------------

    static void headerRoundTrip(List<Path> headers) throws Exception {
        System.out.println("=== .lotheader round trip ===");
        int identical = 0, differing = 0, errored = 0;
        List<String> diffs = new ArrayList<>();
        Map<Integer, Integer> lengthDelta = new TreeMap<>();

        for (Path f : headers) {
            try {
                byte[] original = Files.readAllBytes(f);
                LotHeader h = LotHeader.read(f);
                byte[] rewritten = h.write();
                if (Arrays.equals(original, rewritten)) { identical++; continue; }
                differing++;
                lengthDelta.merge(rewritten.length - original.length, 1, Integer::sum);
                if (diffs.size() < 8) {
                    int at = firstDiff(original, rewritten);
                    diffs.add(f.getFileName() + ": first diff at byte " + at
                            + " (orig " + original.length + " B, rewritten " + rewritten.length + " B)"
                            + "\n         orig: " + hexAround(original, at)
                            + "\n         ours: " + hexAround(rewritten, at));
                }
            } catch (Exception e) {
                errored++;
                if (diffs.size() < 8) diffs.add(f.getFileName() + ": " + e);
            }
        }

        System.out.printf("  byte-identical : %d / %d  (%.2f%%)%n",
                identical, headers.size(), 100.0 * identical / headers.size());
        System.out.println("  differing      : " + differing);
        System.out.println("  errored        : " + errored);
        if (!lengthDelta.isEmpty())
            System.out.println("  length deltas (rewritten - original): " + lengthDelta);
        for (String d : diffs) System.out.println("     " + d);
        if (identical == headers.size())
            System.out.println("  => read model is COMPLETE: nothing dropped, nothing reordered");
        System.out.println();
    }

    // ---------------- lotpack ----------------

    static void lotpackRoundTrip(Path dir, List<Path> headers) throws Exception {
        System.out.println("=== .lotpack chunk encoding policy ===");
        LotPack.Policy[] policies = LotPack.Policy.values();
        long[] chunkExact = new long[policies.length];
        long chunksTotal = 0;
        int[] fileExact = new int[policies.length];
        int cells = 0, errored = 0;
        List<String> notes = new ArrayList<>();
        Map<String, String> firstMismatchShape = new LinkedHashMap<>();

        for (Path hf : headers) {
            String name = hf.getFileName().toString().replace(".lotheader", "");
            Path pf = dir.resolve("world_" + name + ".lotpack");
            if (!Files.exists(pf)) continue;
            try {
                LotHeader h = LotHeader.read(hf);
                LotPack lp = LotPack.read(pf, h);
                byte[] original = lp.rawFile();
                cells++;

                for (int ci = 0; ci < lp.chunkCount; ci++) {
                    int cx = ci % lp.chunksPerSide, cy = ci / lp.chunksPerSide;
                    LotPack.Chunk c = lp.chunk(cx, cy);
                    byte[] raw = lp.rawChunk(ci);
                    chunksTotal++;
                    for (int p = 0; p < policies.length; p++) {
                        byte[] enc = lp.encodeChunk(c, policies[p]);
                        if (Arrays.equals(raw, enc)) chunkExact[p]++;
                        else if (p == 0 && firstMismatchShape.size() < 6 && ci == 0) {
                            firstMismatchShape.put(name + " chunk0",
                                    "orig " + raw.length + " B, ours " + enc.length
                                    + " B, first diff @" + firstDiff(raw, enc));
                        }
                    }
                }

                for (int p = 0; p < policies.length; p++) {
                    if (Arrays.equals(original, lp.write(policies[p]))) fileExact[p]++;
                }
            } catch (Exception e) {
                errored++;
                if (notes.size() < 6) notes.add(name + ": " + e);
            }
        }

        System.out.println("  cells: " + cells + "   chunks: " + chunksTotal
                + "   errored: " + errored);
        System.out.println("\n  per-policy chunk body reproduction:");
        for (int p = 0; p < policies.length; p++)
            System.out.printf("     %-24s %d / %d chunks  (%.2f%%)   whole files: %d/%d%n",
                    policies[p], chunkExact[p], chunksTotal,
                    chunksTotal == 0 ? 0.0 : 100.0 * chunkExact[p] / chunksTotal,
                    fileExact[p], cells);

        int best = 0;
        for (int p = 1; p < policies.length; p++) if (chunkExact[p] > chunkExact[best]) best = p;
        if (chunksTotal > 0 && chunkExact[best] == chunksTotal)
            System.out.println("\n  => ENCODER POLICY CONFIRMED: " + policies[best]);
        else {
            System.out.println("\n  no policy reproduces every chunk; best is " + policies[best]);
            for (Map.Entry<String, String> e : firstMismatchShape.entrySet())
                System.out.println("     " + e.getKey() + ": " + e.getValue());
        }
        for (String n : notes) System.out.println("     " + n);
    }

    // ---------------- helpers ----------------

    static int firstDiff(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) if (a[i] != b[i]) return i;
        return n;
    }

    static String hexAround(byte[] a, int at) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, at - 4), to = Math.min(a.length, at + 12);
        for (int i = from; i < to; i++) {
            if (i == at) sb.append("[");
            sb.append(String.format("%02X", a[i] & 0xFF));
            if (i == at) sb.append("]");
            sb.append(' ');
        }
        return sb.toString().trim();
    }
}

package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Verification pass: parse every lotheader with the full B42 parser and require
 * exact byte consumption -- rooms, then buildings, then exactly 1024 grid bytes,
 * with zero bytes left over and every room index in range.
 *
 * This is the falsifiable test. A layout that is merely plausible will fail on
 * some cell out of thousands; one that consumes 100% is correct.
 */
public final class Survey {

    public static void run(Path dir) throws Exception {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.lotheader")) {
            for (Path p : ds) files.add(p);
        }
        Collections.sort(files);
        System.out.println("lotheaders: " + files.size() + "\n");
        if (files.isEmpty()) return;

        int ok = 0, failed = 0;
        long totalRooms = 0, totalBuildings = 0, totalRefs = 0;
        int refsEqualRooms = 0;
        Map<Integer, Integer> above = new TreeMap<>(), below = new TreeMap<>(),
                              minLv = new TreeMap<>(), unk12 = new TreeMap<>();
        Map<Integer, Integer> gridVals = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        int unk12EqDistinctNames = 0, unk12EqMaxFloor = 0, withRooms = 0;

        for (Path f : files) {
            try {
                LotHeader h = LotHeader.read(f);
                if (!h.b42) { failed++; failures.add(f.getFileName() + ": not B42"); continue; }
                ok++;
                totalRooms += h.rooms.size();
                totalBuildings += h.buildings.size();
                totalRefs += h.roomRefs();
                if (h.roomRefs() == h.rooms.size()) refsEqualRooms++;
                above.merge(h.levelsAbove, 1, Integer::sum);
                below.merge(h.levelsBelow, 1, Integer::sum);
                minLv.merge(h.minLevel, 1, Integer::sum);
                unk12.merge(h.unknown12, 1, Integer::sum);
                for (byte b : h.chunkGrid) gridVals.merge(b & 0xFF, 1, Integer::sum);

                if (!h.rooms.isEmpty()) {
                    withRooms++;
                    Set<String> names = new HashSet<>();
                    int maxFloor = 0;
                    for (LotHeader.Room r : h.rooms) {
                        names.add(r.name);
                        maxFloor = Math.max(maxFloor, r.floor);
                    }
                    if (h.unknown12 == names.size()) unk12EqDistinctNames++;
                    if (h.unknown12 == maxFloor + 1) unk12EqMaxFloor++;
                }
            } catch (Exception e) {
                failed++;
                if (failures.size() < 12)
                    failures.add(f.getFileName() + ": " + e.getMessage());
            }
        }

        System.out.println("=== full parse, exact consumption required ===");
        System.out.printf("  parsed cleanly : %d / %d  (%.2f%%)%n",
                ok, files.size(), 100.0 * ok / files.size());
        System.out.println("  failed         : " + failed);
        for (String s : failures) System.out.println("      " + s);

        System.out.println("\n=== totals ===");
        System.out.println("  rooms      " + totalRooms);
        System.out.println("  buildings  " + totalBuildings);
        System.out.println("  room refs  " + totalRefs
                + (totalRefs == totalRooms ? "   (every room belongs to exactly one building)" : ""));
        System.out.println("  cells where refs == rooms: " + refsEqualRooms + " / " + ok);

        System.out.println("\n=== header fields ===");
        System.out.println("  +0  levelsAbove : " + trim(above));
        System.out.println("  +4  levelsBelow : " + trim(below));
        System.out.println("  +8  minLevel    : " + trim(minLv));
        System.out.println("  +12 unknown     : " + trim(unk12));

        System.out.println("\n=== what is +12? ===");
        System.out.println("  cells with rooms                : " + withRooms);
        System.out.println("  +12 == distinct room name count : " + unk12EqDistinctNames);
        System.out.println("  +12 == max floor + 1            : " + unk12EqMaxFloor);

        System.out.println("\n=== chunk grid byte values ===");
        System.out.println("  " + trim(gridVals));

        verifyLotPacks(dir, files);
    }

    /** Parse every chunk of every lotpack, requiring exact chunk boundaries. */
    static void verifyLotPacks(Path dir, List<Path> headers) {
        System.out.println("\n=== lotpack verification (every chunk, exact boundaries) ===");
        int cells = 0, cellsFailed = 0;
        long chunksOk = 0;
        long[] levelUse = new long[LotPack.MAX_LEVELS];
        long basementSquares = 0, basementCells = 0;
        List<String> failures = new ArrayList<>();
        Map<Integer,Integer> coverage = new TreeMap<>();
        Map<Integer,Integer> deepCells = new TreeMap<>();
        List<String> deepDetail = new ArrayList<>();
        List<String> unk12Mismatch = new ArrayList<>();
        int unk12EqMaxZ = 0, levelCountOk = 0;

        for (Path hf : headers) {
            String name = hf.getFileName().toString().replace(".lotheader", "");
            Path pf = dir.resolve("world_" + name + ".lotpack");
            if (!Files.exists(pf)) continue;
            try {
                LotHeader h = LotHeader.read(hf);
                LotPack lp = LotPack.read(pf, h);
                boolean anyBasement = false;
                int cellMaxLevels = 0, cellMaxZ = -1;
                for (int cy = 0; cy < lp.chunksPerSide; cy++)
                    for (int cx = 0; cx < lp.chunksPerSide; cx++) {
                        LotPack.Chunk c = lp.chunk(cx, cy);
                        chunksOk++;
                        coverage.merge(c.squaresCovered, 1, Integer::sum);
                        cellMaxLevels = Math.max(cellMaxLevels, c.levelsEncoded);
                        cellMaxZ = Math.max(cellMaxZ, c.maxZ);
                        for (int z = 0; z < LotPack.MAX_LEVELS; z++)
                            for (int x = 0; x < LotPack.CHUNK_SIZE; x++)
                                for (int y = 0; y < LotPack.CHUNK_SIZE; y++)
                                    if (c.tiles[z][x][y] != null) {
                                        levelUse[z]++;
                                        if (z >= 8) { basementSquares++; anyBasement = true; }
                                    }
                    }
                if (anyBasement) basementCells++;
                deepCells.merge(cellMaxLevels, 1, Integer::sum);
                // Confirmed rule: actualZ = index + minLevel, and +12 is the
                // highest actual z with data.
                if (h.unknown12 == cellMaxZ + h.minLevel) unk12EqMaxZ++;
                else if (unk12Mismatch.size() < 10)
                    unk12Mismatch.add(name + ": +12=" + h.unknown12 + " maxZindex=" + cellMaxZ
                            + " minLevel=" + h.minLevel
                            + " => actual " + (cellMaxZ + h.minLevel));
                if (cellMaxLevels == h.unknown12 - h.minLevel + 1) levelCountOk++;
                if (cellMaxLevels > 16)
                    deepDetail.add(name + ": levels=" + cellMaxLevels
                            + " minLevel=" + h.minLevel + " +12=" + h.unknown12);
                cells++;
            } catch (Exception e) {
                cellsFailed++;
                if (failures.size() < 8) failures.add(name + ": " + e.getMessage());
            }
        }

        System.out.printf("  cells parsed  : %d   failed: %d%n", cells, cellsFailed);
        System.out.println("  chunks parsed : " + chunksOk);
        for (String f : failures) System.out.println("      " + f);
        System.out.println("\n  non-empty squares per z index:");
        for (int z = 0; z < LotPack.MAX_LEVELS; z++)
            if (levelUse[z] > 0) System.out.printf("     z=%-3d %d%n", z, levelUse[z]);
        System.out.println("  squares at z>=8: " + basementSquares
                + " across " + basementCells + " cells");
        System.out.println("\n  squares covered per chunk (value -> chunk count):");
        System.out.println("     " + trim(coverage));
        System.out.println("\n  === z-LEVEL MODEL: actualZ = index + minLevel ===");
        System.out.printf("     +12 == maxZindex + minLevel        : %d / %d  (%.2f%%)%n",
                unk12EqMaxZ, cells, 100.0 * unk12EqMaxZ / Math.max(1, cells));
        System.out.printf("     levelsEncoded == +12 - minLevel + 1 : %d / %d  (%.2f%%)%n",
                levelCountOk, cells, 100.0 * levelCountOk / Math.max(1, cells));
        if (!unk12Mismatch.isEmpty()) {
            System.out.println("     mismatches:");
            for (String m : unk12Mismatch) System.out.println("       " + m);
        }

        System.out.println("\n  max levels encoded per cell (levels -> cell count):");
        System.out.println("     " + trim(deepCells));
        if (!deepDetail.isEmpty()) {
            System.out.println("\n  cells encoding more than 16 levels:");
            for (int i = 0; i < Math.min(15, deepDetail.size()); i++)
                System.out.println("     " + deepDetail.get(i));
            System.out.println("     total: " + deepDetail.size());
        }
    }

    static String trim(Map<Integer, Integer> m) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Map.Entry<Integer, Integer> e : m.entrySet()) {
            sb.append(e.getKey()).append("->").append(e.getValue()).append("  ");
            if (++i >= 10) { sb.append("(").append(m.size() - 10).append(" more)"); break; }
        }
        return sb.toString();
    }
}

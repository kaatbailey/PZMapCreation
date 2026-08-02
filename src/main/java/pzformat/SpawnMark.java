package pzformat;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Paints a marker at EVERY spawn point in spawnpoints.lua.
 *
 * Testing one spawn means re-rolling a new character until the game happens to
 * pick that house — slow and easy to misread as failure. Marking all of them
 * makes the first spawn conclusive either way.
 *
 * Writes edited cells to an output directory and generates install/restore
 * scripts. Never touches the game files itself.
 */
public final class SpawnMark {

    static final Pattern POINT = Pattern.compile(
            "posX\\s*=\\s*(-?\\d+)\\s*,\\s*posY\\s*=\\s*(-?\\d+)\\s*,\\s*posZ\\s*=\\s*(-?\\d+)");

    public static void run(Path mapDir, Path mediaDir, Path outDir, int size) throws Exception {
        Path spawnFile = mapDir.resolve("spawnpoints.lua");
        if (!Files.exists(spawnFile))
            throw new IllegalArgumentException("no spawnpoints.lua in " + mapDir);

        // Parse, dropping duplicates (the lua reuses points across professions).
        LinkedHashSet<List<Integer>> points = new LinkedHashSet<>();
        Matcher m = POINT.matcher(Files.readString(spawnFile));
        while (m.find())
            points.add(List.of(Integer.parseInt(m.group(1)),
                               Integer.parseInt(m.group(2)),
                               Integer.parseInt(m.group(3))));
        System.out.println("distinct spawn points: " + points.size());

        if (mediaDir != null) MakeTestMod.verifyMarkerExists(mediaDir);

        // Group by cell so each cell is loaded and written once.
        Map<String, List<int[]>> byCell = new LinkedHashMap<>();
        for (List<Integer> p : points) {
            int wx = p.get(0), wy = p.get(1), wz = p.get(2);
            String cell = Math.floorDiv(wx, 256) + "_" + Math.floorDiv(wy, 256);
            byCell.computeIfAbsent(cell, k -> new ArrayList<>())
                  .add(new int[]{Math.floorMod(wx, 256), Math.floorMod(wy, 256), wz, wx, wy});
        }
        System.out.println("cells affected: " + byCell.size() + "  " + byCell.keySet() + "\n");

        Files.createDirectories(outDir);
        List<String> written = new ArrayList<>();
        int totalSquares = 0, failures = 0;

        for (Map.Entry<String, List<int[]>> e : byCell.entrySet()) {
            String cell = e.getKey();
            Path lh = mapDir.resolve(cell + ".lotheader");
            Path lp = mapDir.resolve("world_" + cell + ".lotpack");
            if (!Files.exists(lh) || !Files.exists(lp)) {
                System.out.println("   cell " + cell + " missing; skipped");
                failures++;
                continue;
            }

            CellData before = CellData.load(lp, lh);
            CellData after = CellData.load(lp, lh);
            int changed = 0;

            for (int[] pt : e.getValue()) {
                int lx = pt[0], ly = pt[1], z = pt[2];
                if (z < after.minLevel || z > after.maxLevel) {
                    System.out.println("   spawn z=" + z + " outside cell range; skipped");
                    continue;
                }
                int x0 = Math.max(0, Math.min(after.cellSize - size, lx - size / 2));
                int y0 = Math.max(0, Math.min(after.cellSize - size, ly - size / 2));
                int n = after.fill(MakeTestMod.MARKER_TILE, x0, y0, size, size, z);
                changed += n;
                String[] was = before.tileNamesAt(lx, ly, z);
                System.out.printf("   %s  world(%d,%d) local(%d,%d) z=%d  %d squares   was: %s%n",
                        cell, pt[3], pt[4], lx, ly, z, n,
                        was == null ? "(empty)" : Arrays.toString(was));
            }

            CellData.Diff d = CellData.diff(before, after);
            boolean ok = d.squaresChanged + d.squaresAdded == changed && d.squaresRemoved == 0;
            if (!ok) {
                System.out.println("   VERIFY FAILED for " + cell + ": " + d);
                failures++;
                continue;
            }

            Path oh = outDir.resolve(cell + ".lotheader");
            Path op = outDir.resolve("world_" + cell + ".lotpack");
            Files.write(oh, after.writeLotHeader());
            Files.write(op, after.writeLotPack());

            CellData reread = CellData.load(op, oh);
            if (!CellData.diff(after, reread).isEmpty()) {
                System.out.println("   REREAD MISMATCH for " + cell);
                failures++;
                continue;
            }
            written.add(cell);
            totalSquares += changed;
        }

        System.out.println("\ncells written: " + written.size() + "   squares painted: "
                + totalSquares + "   failures: " + failures);
        if (written.isEmpty()) return;

        // Scripts, so the install swap is one command and always reversible.
        Path backup = outDir.resolve("backup");
        StringBuilder install = new StringBuilder("#!/usr/bin/env fish\n"
                + "set MK \"" + mapDir + "\"\n"
                + "mkdir -p " + backup + "\n");
        StringBuilder restore = new StringBuilder("#!/usr/bin/env fish\n"
                + "set MK \"" + mapDir + "\"\n");
        for (String cell : written) {
            install.append("cp \"$MK/").append(cell).append(".lotheader\" ")
                   .append(backup).append("/\n");
            install.append("cp \"$MK/world_").append(cell).append(".lotpack\" ")
                   .append(backup).append("/\n");
            install.append("cp ").append(outDir).append('/').append(cell)
                   .append(".lotheader \"$MK/\"\n");
            install.append("cp ").append(outDir).append("/world_").append(cell)
                   .append(".lotpack \"$MK/\"\n");
            restore.append("cp ").append(backup).append('/').append(cell)
                   .append(".lotheader \"$MK/\"\n");
            restore.append("cp ").append(backup).append("/world_").append(cell)
                   .append(".lotpack \"$MK/\"\n");
        }
        install.append("echo \"installed ").append(written.size())
               .append(" edited cells; originals backed up to ").append(backup).append("\"\n");
        restore.append("echo \"restored ").append(written.size()).append(" cells\"\n");

        Path ins = outDir.resolve("install.fish"), res = outDir.resolve("restore.fish");
        Files.writeString(ins, install.toString());
        Files.writeString(res, restore.toString());
        ins.toFile().setExecutable(true);
        res.toFile().setExecutable(true);

        System.out.println("\n  install:  fish " + ins);
        System.out.println("  restore:  fish " + res);
        System.out.println("\nEvery spawn point is marked, so the first new character"
                + " settles it either way.");
    }
}

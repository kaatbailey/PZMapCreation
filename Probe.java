package pzformat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Point this at your own Project Zomboid install and it tells you which of the
 * assumed layouts survive contact with real data.
 *
 *   java -cp out pzformat.Probe pack     <file.pack> [--extract outdir]
 *   java -cp out pzformat.Probe lotheader <file.lotheader> [--scan]
 *   java -cp out pzformat.Probe lotpack   <world_X_Y.lotpack> <X_Y.lotheader>
 *   java -cp out pzformat.Probe mapdir    <media/maps/MapName>
 */
public final class Probe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { usage(); System.exit(2); }
        switch (args[0]) {
            case "pack"      -> pack(Path.of(args[1]), args);
            case "lotheader" -> lotheader(Path.of(args[1]), has(args, "--scan"));
            case "lotpack"   -> lotpack(Path.of(args[1]), Path.of(args[2]));
            case "mapdir"    -> mapdir(Path.of(args[1]));
            default          -> { usage(); System.exit(2); }
        }
    }

    static void usage() {
        System.out.println("""
            pzformat probe

              pack      <file.pack> [--extract <dir>]   parse atlas, verify PNG anchors
              lotheader <file.lotheader> [--scan]       parse header, report alignment
              lotpack   <world_X_Y.lotpack> <X_Y.lotheader>
              mapdir    <media/maps/MapName>            summarise a whole map folder

            Typical starting point:
              java -cp out pzformat.Probe mapdir ~/.steam/steam/steamapps/common/ProjectZomboid/media/maps/Muldraugh,\\ KY
            """);
    }

    static boolean has(String[] a, String flag) {
        for (String s : a) if (s.equals(flag)) return true;
        return false;
    }

    static String arg(String[] a, String flag) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(flag)) return a[i + 1];
        return null;
    }

    // ------------------------------------------------------------------

    static void pack(Path file, String[] args) throws Exception {
        System.out.println("== .pack: " + file.getFileName()
                + "  (" + Files.size(file) + " bytes)");
        PackFile p = PackFile.read(file);
        System.out.println("pages: " + p.pages.size());
        int totalEntries = 0;
        for (PackFile.Page page : p.pages) totalEntries += page.entries.size();
        System.out.println("entries: " + totalEntries);
        System.out.println("ALL PNG ANCHORS MATCHED -> entry table layout is confirmed correct.\n");

        int shown = 0;
        for (PackFile.Page page : p.pages) {
            System.out.printf("  page '%s'  %d entries  atlas %dx%d  png %d bytes%n",
                    page.name, page.entries.size(), page.pngWidth(), page.pngHeight(),
                    page.png.length);
            for (int i = 0; i < Math.min(3, page.entries.size()); i++) {
                System.out.println("      " + page.entries.get(i));
            }
            if (++shown >= 8) {
                System.out.println("  ... " + (p.pages.size() - shown) + " more pages");
                break;
            }
        }

        // Round-trip check: re-serialise and compare bytes.
        byte[] original = Files.readAllBytes(file);
        byte[] rewritten = p.write();
        boolean identical = java.util.Arrays.equals(original, rewritten);
        System.out.println("\nround-trip byte-identical: " + identical
                + (identical ? "  -> writer is safe to use" :
                   "  -> writer diverges; diff at byte " + firstDiff(original, rewritten)));

        String out = arg(args, "--extract");
        if (out != null) {
            p.extractPages(Path.of(out));
            System.out.println("extracted " + p.pages.size() + " page atlases to " + out);
        }
    }

    static int firstDiff(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) if (a[i] != b[i]) return i;
        return n;
    }

    // ------------------------------------------------------------------

    static void lotheader(Path file, boolean scan) throws Exception {
        System.out.println("== .lotheader: " + file.getFileName()
                + "  (" + Files.size(file) + " bytes)");
        if (scan) { scanForCellSize(file); return; }
        try {
            LotHeader h = LotHeader.read(file);
            System.out.println("version:        " + h.version);
            System.out.println("tile names:     " + h.tileNames.size());
            System.out.println("cell:           " + h.width + "x" + h.height
                    + "  levels=" + h.levels
                    + "   -> " + (h.width == 300 ? "Build 41 layout" : "Build 42 layout"));
            System.out.println("pad bytes after tile table: " + h.padBytesSkipped
                    + "   (metaOffset=" + h.metaOffset + ")");
            System.out.println("rooms:          " + h.rooms.size());
            System.out.println("buildings:      " + h.buildings.size());
            System.out.println("density grid:   "
                    + (h.zombieDensity == null ? "not read" : h.zombieDensity.length + " bytes"));
            System.out.println("\nfirst tile names:");
            for (int i = 0; i < Math.min(8, h.tileNames.size()); i++) {
                System.out.println("   [" + i + "] " + h.tileNames.get(i));
            }
            if (!h.warnings.isEmpty()) {
                System.out.println("\nWARNINGS (inferred sections that didn't hold up):");
                for (String w : h.warnings) System.out.println("  ! " + w);
            } else {
                System.out.println("\nno warnings -- inferred metadata layout held for this file.");
            }
        } catch (LE.ParseException e) {
            System.out.println("PARSE FAILED: " + e.getMessage());
        }
    }

    /** Brute-force search for the 300/256 cell-size marker anywhere in the file. */
    static void scanForCellSize(Path file) throws Exception {
        LE r = LE.of(file);
        System.out.println("scanning for width/height/levels triples...");
        int hits = 0;
        for (int off = 0; off + 12 <= r.length(); off++) {
            r.seek(off);
            int w = r.i32(), h = r.i32(), lv = r.i32();
            if (LotHeader.isKnownCellSize(w) && w == h && lv > 0 && lv <= 64) {
                System.out.printf("  offset %d: %dx%d levels=%d%n", off, w, h, lv);
                if (++hits >= 10) { System.out.println("  ... stopping at 10 hits"); break; }
            }
        }
        if (hits == 0) {
            System.out.println("  no candidates found -- cell size is neither 300 nor 256, "
                    + "or the fields are not consecutive int32s. First 256 bytes:");
            System.out.println(r.hexDump(0, 256));
        }
    }

    // ------------------------------------------------------------------

    static void lotpack(Path packFile, Path headerFile) throws Exception {
        LotHeader h = LotHeader.read(headerFile);
        System.out.println("== .lotpack: " + packFile.getFileName()
                + "  (" + Files.size(packFile) + " bytes)");
        System.out.println("using header: " + h.width + "x" + h.height + " levels=" + h.levels);

        for (int chunkSize : new int[]{10, 8, 16, 32}) {
            if (h.width % chunkSize != 0) continue;
            System.out.println("\n-- trying chunk size " + chunkSize + " ("
                    + (h.width / chunkSize) + "x" + (h.height / chunkSize) + " chunks)");
            try {
                LotPack lp = LotPack.read(packFile, h.width, h.height, h.levels, chunkSize);
                if (lp.warnings.isEmpty()) {
                    System.out.println("   offset table VALID (version=" + lp.version + ")");
                    try {
                        LotPack.Chunk c = lp.readChunk(0, 0);
                        int nonEmpty = 0;
                        for (int z = 0; z < h.levels; z++)
                            for (int x = 0; x < chunkSize; x++)
                                for (int y = 0; y < chunkSize; y++)
                                    if (c.tiles[z][x][y] != null) nonEmpty++;
                        System.out.println("   chunk(0,0) parsed: " + nonEmpty + " non-empty squares");
                        printSample(c, h, chunkSize);
                        System.out.println("   >>> CHUNK SIZE " + chunkSize + " IS CORRECT <<<");
                        return;
                    } catch (LE.ParseException e) {
                        System.out.println("   offsets valid but chunk body failed: " + e.getMessage());
                    }
                } else {
                    for (String w : lp.warnings) System.out.println("   ! " + w);
                }
            } catch (LE.ParseException e) {
                System.out.println("   failed: " + e.getMessage());
            }
        }
        System.out.println("\nNo chunk size worked. Dump the header region and eyeball it:");
        LE r = LE.of(packFile);
        System.out.println(r.hexDump(0, 128));
    }

    static void printSample(LotPack.Chunk c, LotHeader h, int chunkSize) {
        System.out.println("   sample squares (z=0):");
        int shown = 0;
        for (int x = 0; x < chunkSize && shown < 4; x++) {
            for (int y = 0; y < chunkSize && shown < 4; y++) {
                int[] t = c.tiles[0][x][y];
                if (t == null || t.length == 0) continue;
                StringBuilder sb = new StringBuilder();
                for (int idx : t) {
                    sb.append(idx >= 0 && idx < h.tileNames.size()
                            ? h.tileNames.get(idx) : ("?" + idx)).append(" ");
                }
                System.out.printf("      (%d,%d) room=%d : %s%n", x, y, c.room[0][x][y], sb.toString().trim());
                shown++;
            }
        }
    }

    // ------------------------------------------------------------------

    static void mapdir(Path dir) throws Exception {
        System.out.println("== map folder: " + dir);
        if (!Files.isDirectory(dir)) { System.out.println("not a directory"); return; }
        List<Path> headers = Files.list(dir)
                .filter(p -> p.toString().endsWith(".lotheader")).sorted().toList();
        List<Path> packs = Files.list(dir)
                .filter(p -> p.toString().endsWith(".lotpack")).sorted().toList();
        System.out.println("lotheaders: " + headers.size() + "   lotpacks: " + packs.size());
        for (String f : new String[]{"map.info", "objects.lua", "spawnpoints.lua",
                                     "worldmap.xml", "worldmap-forest.xml", "thumb.png"}) {
            System.out.println((Files.exists(dir.resolve(f)) ? "  present  " : "  missing  ") + f);
        }
        if (headers.isEmpty()) return;

        System.out.println("\n-- parsing all headers to find layout variance --");
        int ok = 0, failed = 0;
        java.util.Map<String, Integer> shapes = new java.util.TreeMap<>();
        java.util.Set<String> allTiles = new java.util.HashSet<>();
        for (Path p : headers) {
            try {
                LotHeader h = LotHeader.read(p);
                shapes.merge("v" + h.version + " " + h.width + "x" + h.height
                        + " lv" + h.levels + " pad" + h.padBytesSkipped, 1, Integer::sum);
                allTiles.addAll(h.tileNames);
                if (h.warnings.isEmpty()) ok++; else failed++;
            } catch (Exception e) {
                shapes.merge("PARSE FAILED", 1, Integer::sum);
                failed++;
            }
        }
        System.out.println("clean: " + ok + "   with warnings/failures: " + failed);
        System.out.println("distinct tile names across cell: " + allTiles.size());
        System.out.println("header shapes seen:");
        shapes.forEach((k, v) -> System.out.println("   " + v + "x  " + k));
    }
}

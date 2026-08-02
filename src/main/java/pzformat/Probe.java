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
            case "lotpack"   -> LotPackAnalysis.run(Path.of(args[1]), Path.of(args[2]));
            case "trailer"   -> TrailerAnalysis.run(Path.of(args[1]));
            case "survey"    -> Survey.run(Path.of(args[1]));
            case "roundtrip" -> RoundTrip.run(Path.of(args[1]),
                                    args.length > 2 ? Integer.parseInt(args[2]) : 0);
            case "gisimport" -> GisImport.run(Path.of(args[1]), Path.of(args[2]),
                                    args.length > 4 ? Path.of(args[3]) : null,
                                    Path.of(args.length > 4 ? args[4] : args[3]),
                                    args.length > 5 ? Integer.parseInt(args[5]) : 2048);
            case "roomgeom"  -> RoomGeometry.run(Path.of(args[1]), Path.of(args[2]), args[3]);
            case "editdemo"  -> EditDemo.run(Path.of(args[1]), Path.of(args[2]),
                                    args[3], Integer.parseInt(args[4]),
                                    Integer.parseInt(args[5]), Integer.parseInt(args[6]),
                                    args.length > 7 ? Path.of(args[7]) : null);
            case "findprop"  -> PropsProbe.find(Path.of(args[1]), Path.of(args[2]),
                                    args[3], args[4]);
            case "square"    -> PropsProbe.dump(Path.of(args[1]), Path.of(args[2]),
                                    args[3], Integer.parseInt(args[4]),
                                    Integer.parseInt(args[5]), Integer.parseInt(args[6]));
            case "props"     -> PropsProbe.run(Path.of(args[1]),
                                    args.length > 2 ? Path.of(args[2]) : null,
                                    args.length > 3 ? args[3] : null);
            case "render"    -> CellRenderer.run(
                                    Path.of(args[1]), Path.of(args[2]), args[3],
                                    Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                                    Integer.parseInt(args[6]),
                                    args.length > 8 ? Integer.parseInt(args[7]) : 0,
                                    args.length > 8 ? Integer.parseInt(args[8]) : 2,
                                    Path.of(args.length > 9 ? args[9] : "cell.png"));
            case "packinfo"  -> PackAnalysis.run(Path.of(args[1]));
            case "sprites"   -> SpriteJoin.run(Path.of(args[1]),
                                    args.length > 2 ? Path.of(args[2]) : null);
            case "spawnmark" -> SpawnMark.run(Path.of(args[1]), Path.of(args[2]),
                                    Path.of(args[3]),
                                    args.length > 4 ? Integer.parseInt(args[4]) : 12);
            case "locate"    -> Locate.run(Path.of(args[1]),
                                    Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "testmodat" -> MakeTestMod.runAtWorld(
                                    Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                                    args[4], Integer.parseInt(args[5]),
                                    Integer.parseInt(args[6]),
                                    args.length > 7 ? Integer.parseInt(args[7]) : 12);
            case "testmod"   -> MakeTestMod.run(
                                    Path.of(args[1]), args[2], Path.of(args[3]),
                                    args.length > 4 ? args[4] : "PZFormatTest",
                                    args.length > 7 ? Integer.parseInt(args[5]) : 120,
                                    args.length > 7 ? Integer.parseInt(args[6]) : 120,
                                    args.length > 7 ? Integer.parseInt(args[7]) : 16);
            case "tilesolve" -> TileBin.solve(Path.of(args[1]),
                                    args.length > 2 ? Path.of(args[2]) : null);
            case "tileall"   -> TileBin.solveAll(Path.of(args[1]));
            case "tilebin"   -> TileBinAnalysis.run(Path.of(args[1]),
                                    args.length > 2 ? Path.of(args[2]) : null);
            case "tiles"     -> TileDefs.run(Path.of(args[1]),
                                    args.length > 2 ? Path.of(args[2]) : null);
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
              trailer   <file.lotheader>                decide the B42 trailer layout
              survey    <media/maps/MapName>            verify every cell in a map
              tiles     <media dir> [file.lotheader]    parse tile properties, join to a cell
              roundtrip <media/maps/MapName> [limit]    read->write->byte-compare
              tilebin   <file.tiles> [file.tiles.txt]   analyse binary tile definitions
              tilesolve <file.tiles> [file.tiles.txt]   solve + verify one binary file
              tileall   <media dir>                     parse every binary .tiles
              testmod   <mapdir> <X_Y> <modsdir> [name] [x y size]
              locate    <mapdir> <worldX> <worldY>       what is at this world coordinate?
              spawnmark <mapdir> <mediadir> <outdir> [size]  paint every spawn point
              sprites   <texturepacks dir> [lotheader]  do tile names resolve to sprites?
              packinfo  <file.pack | dir>               structural analysis of atlases
              render    <mapdir> <texturepacks> <X_Y> <x> <y> <size> [zFrom zTo] [out.png]
              props     <mediadir> [mapdir] [X_Y]        tile semantics + validation
              square    <mediadir> <mapdir> <X_Y> <x> <y> <z>   dump every tile + properties
              findprop  <mediadir> <mapdir> <X_Y> <prop>  find + dump squares having a property
              gisimport <buildings.geojson> <roads.geojson> [area.geojson] <outdir> [maxTiles]
                                                        GIS -> PZ geometry, schematic PNG
              roomgeom  <mediadir> <mapdir> <X_Y>         where do room walls actually sit?
              editdemo  <mediadir> <mapdir> <X_Y> <x> <y> <size> [outdir]
                                                        layer-aware edit vs destructive fill
              testmodat <mapdir> <mediadir> <modsdir> <name> <worldX> <worldY> [size]
                                                        edit at a world coordinate
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
            System.out.println("variant:      " + (h.b42 ? "B42 (LOTH magic)" : "B41 (legacy)"));
            System.out.println("version:      " + h.version);
            System.out.println("tile names:   " + h.tileNames.size());
            if (h.width > 0) {
                System.out.println("cell:         " + h.width + "x" + h.height + " levels=" + h.levels);
            } else {
                System.out.println("cell:         NOT STORED IN THIS FILE");
            }
            System.out.println("trailer at:   " + h.trailerOffset
                    + "   (" + h.trailer.length + " unidentified bytes)");
            System.out.println("\nfirst/last tile names:");
            for (int i = 0; i < Math.min(4, h.tileNames.size()); i++)
                System.out.println("   [" + i + "] " + h.tileNames.get(i));
            if (h.tileNames.size() > 4)
                System.out.println("   [" + (h.tileNames.size() - 1) + "] "
                        + h.tileNames.get(h.tileNames.size() - 1));

            if (h.trailer.length > 0) {
                LE t = new LE(h.trailer);
                System.out.println("\n--- trailer as int32 (offset relative to trailer start) ---");
                int n = Math.min(24, h.trailer.length / 4);
                for (int i = 0; i < n; i++) {
                    int v = t.i32();
                    System.out.printf("   +%-4d  %-12d  0x%08X%n", i * 4, v, v);
                }
                System.out.println("\n--- trailer hex (first 256 bytes) ---");
                System.out.println(t.hexDump(0, 256));
                if (h.trailer.length > 256) {
                    System.out.println("--- trailer hex (last 128 bytes) ---");
                    System.out.println(t.hexDump(Math.max(0, h.trailer.length - 128), 128));
                }
            }
            if (!h.warnings.isEmpty()) {
                System.out.println("WARNINGS:");
                for (String w : h.warnings) System.out.println("  ! " + w);
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

package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Structural analysis of .pack texture atlases.
 *
 * The reader in PackFile was written from B41-era public documentation and
 * verified only against fixtures this project generated itself — which proves
 * the reader agrees with the writer and nothing more. Against retail 42.20
 * files every pack fails, and the leading int32 decodes as 0x4B434150, i.e. the
 * ASCII bytes "PACK": B42 added a magic header, as it did for LOTH and LOTP.
 *
 * The failures vary between files, so more than one layout is likely in play.
 * This dumps enough structure to tell them apart.
 */
public final class PackAnalysis {

    public static void run(Path dirOrFile) throws Exception {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dirOrFile)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dirOrFile, "*.pack")) {
                for (Path p : ds) files.add(p);
            }
            Collections.sort(files);
        } else files.add(dirOrFile);

        Map<String, List<String>> byShape = new LinkedHashMap<>();

        for (Path f : files) {
            byte[] d = Files.readAllBytes(f);
            LE r = new LE(d);
            String magic = d.length >= 4
                    ? new String(d, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1) : "";
            boolean textMagic = magic.chars().allMatch(c -> c >= 32 && c < 127);
            String shape = textMagic ? "magic \"" + magic + "\"" : "no text magic";
            byShape.computeIfAbsent(shape, k -> new ArrayList<>()).add(f.getFileName().toString());

            if (files.size() > 1) continue;   // directory mode: summarise only

            System.out.println("== " + f.getFileName() + "  (" + d.length + " bytes)");
            System.out.println("first 4 bytes as text: " + (textMagic ? "\"" + magic + "\"" : "(binary)"));
            System.out.println("\nfirst 96 bytes:");
            System.out.println(r.hexDump(0, 96));
            System.out.println("leading int32s:");
            for (int i = 0; i < 10; i++) {
                r.seek(i * 4);
                System.out.printf("   [%d] @%-3d %-14d%n", i, i * 4, r.i32());
            }

            // Try parsing with an assumed magic+version prelude of varying length.
            System.out.println("\ntrying prelude lengths (bytes skipped before page count):");
            for (int skip : new int[]{0, 4, 8, 12, 16}) {
                try {
                    describe(d, skip);
                } catch (LE.ParseException e) {
                    System.out.printf("   skip=%-3d rejected: %s%n", skip, e.getMessage());
                }
            }

            // Where does the first PNG live? Everything before it is header plus
            // the first page's entry table, which bounds the layout.
            int png = indexOf(d, PackFile.PNG_MAGIC, 0);
            System.out.println("\nfirst PNG magic at offset: " + (png < 0 ? "not found" : png));
            if (png > 0) {
                System.out.println("bytes just before it (likely the PNG length int32):");
                System.out.println(r.hexDump(Math.max(0, png - 16), 24));
            }
        }

        if (files.size() > 1) {
            System.out.println("== " + files.size() + " pack files grouped by leading bytes ==\n");
            byShape.forEach((shape, names) -> {
                System.out.println(shape + "   (" + names.size() + " files)");
                for (String n : names) System.out.println("      " + n);
                System.out.println();
            });
            System.out.println("Run this against a single file for a full structural dump.");
        }
    }

    /** Attempt a parse skipping `skip` header bytes; report what it finds. */
    static void describe(byte[] d, int skip) {
        LE r = new LE(d);
        r.seek(skip);
        int pages = r.i32();
        if (pages < 0 || pages > 10_000)
            throw new LE.ParseException("page count " + pages);
        String firstName = r.lenString();
        if (firstName.isEmpty() || !printable(firstName))
            throw new LE.ParseException("bad first page name");
        int entries = r.i32();
        if (entries < 0 || entries > 1_000_000)
            throw new LE.ParseException("entry count " + entries);
        System.out.printf("   skip=%-3d pages=%-5d firstPage='%s' entries=%d%n",
                skip, pages, firstName, entries);
        // Peek the first entry to see whether the 8-int rect layout still holds.
        String e0 = r.lenString();
        if (printable(e0)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(r.i32()).append(' ');
            System.out.println("             first entry '" + e0 + "' ints: " + sb.toString().trim());
        }
    }

    static boolean printable(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++)
                if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}

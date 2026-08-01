package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Reverse-engineers the binary `.tiles` format.
 *
 * Unusually favourable conditions: the plaintext `*.tiles.txt` siblings give us
 * exact ground truth (tileset names, tile counts, property keys) BEFORE writing
 * a parser. So rather than guessing a layout and hoping the output looks
 * plausible, every hypothesis is scored against known-correct values.
 *
 * Candidate string encodings, both seen elsewhere in PZ formats:
 *   LEN  - int32 length prefix, then that many bytes   (.pack uses this)
 *   NL   - bytes terminated by '\n'                    (B42 lotheader uses this)
 *   NUL  - bytes terminated by 0                       (B41 lotheader used this)
 */
public final class TileBinAnalysis {

    enum StrEnc { LEN, NL, NUL }

    public static void run(Path binFile, Path textFile) throws Exception {
        byte[] data = Files.readAllBytes(binFile);
        LE r = new LE(data);
        System.out.println("== binary .tiles: " + binFile.getFileName()
                + "  (" + data.length + " bytes)");

        // Ground truth from the text sibling, if present.
        TileDefs truth = null;
        if (textFile != null && Files.exists(textFile)) {
            truth = new TileDefs();
            truth.parse(textFile);
            System.out.println("ground truth from " + textFile.getFileName() + ": "
                    + truth.tilesets.size() + " tilesets, " + truth.byName.size() + " tiles");
            int shown = 0;
            for (TileDefs.Tileset ts : truth.tilesets) {
                System.out.printf("   expect tileset '%s'  %dx%d  id=%d  %d tiles with props%n",
                        ts.file, ts.width, ts.height, ts.id, ts.tiles.size());
                if (++shown >= 5) break;
            }
        } else {
            System.out.println("(no text sibling — structural analysis only)");
        }

        System.out.println("\nfirst 96 bytes:");
        System.out.println(r.hexDump(0, 96));

        System.out.println("leading int32s:");
        for (int i = 0; i < 8; i++) {
            r.seek(i * 4);
            System.out.printf("   [%d] @%-3d %-12d%n", i, i * 4, r.i32());
        }

        // Does the file open with a text-ish magic?
        String lead4 = new String(data, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (lead4.chars().allMatch(c -> c >= 32 && c < 127))
            System.out.println("leading 4 bytes as text: \"" + lead4 + "\"");

        if (truth == null) return;

        // Hypothesis: some header ints, then a tileset count, then tileset records
        // beginning with a name. Search for the first expected tileset name and
        // see how it is encoded and what precedes it.
        String firstName = truth.tilesets.get(0).file;
        int at = indexOf(data, firstName.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0);
        System.out.println("\nfirst expected tileset name '" + firstName + "'");
        if (at < 0) {
            System.out.println("   NOT FOUND as raw bytes — names may be interned, indexed, or compressed");
            return;
        }
        System.out.println("   found at offset " + at);
        System.out.println("   preceding 16 bytes:");
        System.out.println(r.hexDump(Math.max(0, at - 16), 32));

        StrEnc enc = null;
        LE probe = new LE(data);
        if (at >= 4) {
            probe.seek(at - 4);
            if (probe.i32() == firstName.length()) enc = StrEnc.LEN;
        }
        if (enc == null) {
            int end = at + firstName.length();
            if (end < data.length) {
                if (data[end] == '\n') enc = StrEnc.NL;
                else if (data[end] == 0) enc = StrEnc.NUL;
            }
        }
        System.out.println("   string encoding: " + (enc == null ? "UNDETERMINED" : enc));

        // Where do all the expected names live? Their spacing reveals record size.
        System.out.println("\nlocating every expected tileset name:");
        int found = 0, missing = 0;
        List<Integer> offsets = new ArrayList<>();
        for (TileDefs.Tileset ts : truth.tilesets) {
            int off = indexOf(data, ts.file.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0);
            if (off >= 0) { found++; offsets.add(off); } else missing++;
        }
        System.out.println("   found " + found + " / " + truth.tilesets.size()
                + " expected tileset names   missing " + missing);
        Collections.sort(offsets);
        if (offsets.size() >= 3) {
            System.out.println("   first name offsets: " + offsets.subList(0, Math.min(6, offsets.size())));
            System.out.println("   header region before first name: " + offsets.get(0) + " bytes");
        }

        // If names are in file order, the ints between consecutive names describe
        // one whole tileset record.
        if (enc != null && !offsets.isEmpty()) {
            int start = offsets.get(0) - (enc == StrEnc.LEN ? 4 : 0);
            System.out.println("\nbytes from first tileset record (offset " + start + "):");
            System.out.println(r.hexDump(start, 128));
            System.out.println("as int32s from there:");
            LE t = new LE(data);
            t.seek(start);
            for (int i = 0; i < 16; i++) System.out.printf("   +%-3d %d%n", i * 4, t.i32());
        }

        // Property keys are the other thing we know. Are they stored as strings?
        System.out.println("\nare property key names present as raw bytes?");
        String[] probes = {"BlocksPlacement", "Facing", "solid", "WindowShape", "attachedN"};
        for (String k : probes) {
            int off = indexOf(data, k.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0);
            System.out.printf("   %-18s %s%n", k, off < 0 ? "absent" : "at " + off);
        }
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

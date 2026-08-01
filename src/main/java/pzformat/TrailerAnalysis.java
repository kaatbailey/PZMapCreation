package pzformat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Determines the B42 lotheader trailer layout empirically.
 *
 * CONFIRMED so far against retail 42.20 (cell 49_6):
 *   trailer +0   int32  8      unknown (8 in every cell seen)
 *   trailer +4   int32  8      unknown
 *   trailer +8   int32  0
 *   trailer +12  int32  buildingCount
 *   trailer +16  int32  roomCount
 *   room[roomCount]:
 *       name '\n'-terminated, int32 floor, int32 rectCount,
 *       rect[]{x,y,w,h}, int32 objectCount, object[]{a,b,c}
 *   building[buildingCount]:   shape under investigation
 *   grid:                      trailing per-chunk bytes
 *
 * Hypotheses are accepted only on exact byte consumption with every field in
 * range. Anything that merely looks plausible is rejected.
 */
public final class TrailerAnalysis {

    public static final class Hypothesis {
        public final String label;
        public int roomsParsed, endOffset, tailBytes;
        public boolean ok;
        public String rejectReason = "";
        public final List<LotHeader.Room> rooms = new ArrayList<>();
        Hypothesis(String label) { this.label = label; }
    }

    public static void run(Path file) throws Exception {
        LotHeader h = LotHeader.read(file);
        if (!h.b42) { System.out.println("not a B42 lotheader"); return; }

        System.out.println("== trailer analysis: " + file.getFileName());
        System.out.println("tile names: " + h.tileNames.size()
                + "   trailer: " + h.trailer.length + " bytes\n");

        LE pre = new LE(h.trailer);
        int[] lead = new int[5];
        for (int i = 0; i < 5; i++) lead[i] = pre.i32();
        System.out.println("leading int32s: " + java.util.Arrays.toString(lead) + "\n");

        List<Hypothesis> results = new ArrayList<>();
        for (int off : new int[]{8, 12, 16})
            for (boolean objs : new boolean[]{false, true})
                results.add(testRooms(h.trailer, off, objs));

        for (Hypothesis r : results)
            System.out.printf("%-46s %s%n", r.label,
                    r.ok ? "PARSED  rooms=" + r.roomsParsed + "  ends@" + r.endOffset
                         + "  tail=" + r.tailBytes : "rejected: " + r.rejectReason);

        Hypothesis best = null;
        for (Hypothesis r : results)
            if (r.ok && (best == null || r.roomsParsed > best.roomsParsed)) best = r;
        if (best == null) { System.out.println("\nNo room hypothesis survived."); return; }

        System.out.println("\n=== rooms: " + best.label + " ===");
        int shown = 0;
        for (LotHeader.Room room : best.rooms) {
            if (room.rects.isEmpty()) continue;
            int[] r0 = room.rects.get(0);
            System.out.printf("   %-22s floor=%d rects=%-3d objs=%-3d first=[%d,%d %dx%d]%n",
                    room.name, room.floor, room.rects.size(), room.objects.size(),
                    r0[0], r0[1], r0[2], r0[3]);
            if (++shown >= 8) break;
        }

        analyseTail(h.trailer, best.endOffset, lead[3], best.roomsParsed);
    }

    static Hypothesis testRooms(byte[] trailer, int countOffset, boolean hasObjectCount) {
        Hypothesis hyp = new Hypothesis("roomCount@+" + countOffset
                + (hasObjectCount ? ", with objectCount" : ", no objectCount"));
        try {
            LE r = new LE(trailer);
            r.seek(countOffset);
            int roomCount = r.i32();
            if (roomCount < 0 || roomCount > 100_000) {
                hyp.rejectReason = "room count " + roomCount; return hyp;
            }
            for (int i = 0; i < roomCount; i++) {
                LotHeader.Room room = new LotHeader.Room();
                room.name = r.cString();
                if (room.name.isEmpty() || !printable(room.name)) {
                    hyp.rejectReason = "room " + i + " non-printable name at " + r.pos(); return hyp;
                }
                room.floor = r.i32();
                if (room.floor < -32 || room.floor > 32) {
                    hyp.rejectReason = "room " + i + " floor=" + room.floor; return hyp;
                }
                int rectCount = r.i32();
                if (rectCount < 0 || rectCount > 5000) {
                    hyp.rejectReason = "room " + i + " rectCount=" + rectCount; return hyp;
                }
                for (int j = 0; j < rectCount; j++) {
                    int x = r.i32(), y = r.i32(), w = r.i32(), hh = r.i32();
                    if (x < -1024 || x > 1024 || y < -1024 || y > 1024
                            || w < 0 || w > 1024 || hh < 0 || hh > 1024) {
                        hyp.rejectReason = "room " + i + " rect " + j
                                + "=[" + x + "," + y + " " + w + "x" + hh + "]"; return hyp;
                    }
                    room.rects.add(new int[]{x, y, w, hh});
                }
                if (hasObjectCount) {
                    int objCount = r.i32();
                    if (objCount < 0 || objCount > 50_000) {
                        hyp.rejectReason = "room " + i + " objectCount=" + objCount; return hyp;
                    }
                    for (int j = 0; j < objCount; j++)
                        room.objects.add(new int[]{r.i32(), r.i32(), r.i32()});
                }
                hyp.rooms.add(room);
            }
            hyp.roomsParsed = roomCount;
            hyp.endOffset = r.pos();
            hyp.tailBytes = trailer.length - r.pos();
            hyp.ok = true;
        } catch (LE.ParseException e) { hyp.rejectReason = e.getMessage(); }
        return hyp;
    }

    static boolean printable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    static final class BuildResult {
        boolean exact; int buildings, totalRefs; String why = "";
    }

    /**
     * Parse building records over [from,end): prefixInts leading int32s of
     * unknown meaning, then an int32 room count, then that many room indices.
     * Every index must be a valid room, and the region must be consumed exactly.
     */
    static BuildResult parseBuildings(byte[] trailer, int from, int end,
                                      int prefixInts, int roomCount) {
        BuildResult res = new BuildResult();
        try {
            LE r = new LE(trailer);
            r.seek(from);
            while (r.pos() < end) {
                if (r.pos() + (prefixInts + 1) * 4 > end) { res.why = "truncated"; return res; }
                for (int i = 0; i < prefixInts; i++) r.i32();
                int cnt = r.i32();
                if (cnt < 0 || cnt > 20_000) { res.why = "count " + cnt; return res; }
                if (r.pos() + cnt * 4 > end) { res.why = "overruns"; return res; }
                for (int i = 0; i < cnt; i++) {
                    int idx = r.i32();
                    if (idx < 0 || idx >= roomCount) { res.why = "room index " + idx; return res; }
                }
                res.buildings++;
                res.totalRefs += cnt;
                if (res.buildings > 200_000) { res.why = "runaway"; return res; }
            }
            res.exact = r.pos() == end;
            if (!res.exact) res.why = "ends at " + r.pos();
        } catch (LE.ParseException e) { res.why = e.getMessage(); }
        return res;
    }

    static void analyseTail(byte[] trailer, int from, int buildingCount, int roomCount) {
        int n = trailer.length - from;
        System.out.println("\n=== tail: " + n + " bytes from trailer+" + from + " ===");
        if (n <= 0) { System.out.println("(nothing left)"); return; }
        System.out.println("buildingCount (trailer+12): " + buildingCount
                + "    roomCount: " + roomCount);

        final int GRID_MAX = 15;
        int runStart = trailer.length;
        while (runStart > from && (trailer[runStart - 1] & 0xFF) <= GRID_MAX) runStart--;
        int run = trailer.length - runStart;
        System.out.println("trailing run of bytes <= " + GRID_MAX + ": " + run
                + " (overshoots into zero high-bytes; treated as upper bound)");

        System.out.println("\nsearching grid size x building-record shape:");
        System.out.println("  prefix = leading int32s per building before the room count");
        int bestSide = -1, bestPrefix = -1;
        BuildResult bestRes = null;
        for (int side = 1; side * side <= run; side++) {
            int sq = side * side;
            int regionEnd = trailer.length - sq;
            if (regionEnd < from) break;
            for (int prefix = 0; prefix <= 5; prefix++) {
                BuildResult br = parseBuildings(trailer, from, regionEnd, prefix, roomCount);
                if (br.exact) {
                    System.out.printf("   EXACT  grid %2dx%-2d (%5d B)  prefix=%d  ->  %d buildings, %d refs%n",
                            side, side, sq, prefix, br.buildings, br.totalRefs);
                    if (bestRes == null || Math.abs(br.buildings - buildingCount)
                            < Math.abs(bestRes.buildings - buildingCount)) {
                        bestSide = side; bestPrefix = prefix; bestRes = br;
                    }
                }
            }
        }

        if (bestRes != null) {
            System.out.println("\n>>> BEST FIT <<<");
            System.out.println("  grid " + bestSide + "x" + bestSide
                    + " = " + (bestSide * bestSide) + " bytes, one entry per chunk");
            if (256 % bestSide == 0)
                System.out.println("  256-tile cell / " + bestSide
                        + " chunks per side  =>  CHUNK SIZE " + (256 / bestSide));
            System.out.println("  building record: " + bestPrefix
                    + " leading int32s, then roomCount, then indices");
            System.out.println("  buildings: " + bestRes.buildings
                    + (bestRes.buildings == buildingCount
                       ? "  MATCHES trailer+12" : "  MISMATCH vs trailer+12=" + buildingCount));
            System.out.println("  room references: " + bestRes.totalRefs + " of " + roomCount);
        } else {
            System.out.println("\nNo (grid, prefix) combination consumed the region exactly.");
        }

        // Indexed int32 view: the fastest way to spot record boundaries by eye.
        LE t = new LE(trailer);
        t.seek(from);
        int words = Math.min(160, (trailer.length - from) / 4);
        System.out.println("\ntail as indexed int32s (index -> value):");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            sb.append(String.format("[%3d]%-7d", i, t.i32()));
            if (i % 8 == 7) { System.out.println("  " + sb); sb.setLength(0); }
        }
        if (sb.length() > 0) System.out.println("  " + sb);
    }
}

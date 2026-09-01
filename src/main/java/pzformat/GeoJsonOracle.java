package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Cross-language oracle for Json + GeoJson, Java side.
 *
 *   emit <input.geojson> <out.txt>
 *
 * Emits a canonical digest of the parsed feature list for cross-language diff,
 * in the ClassifyOracle style: one tab-separated record per line, fixed field
 * order, "-" for absent.
 *
 * TWO DEPARTURES from ClassifyOracle, both deliberate:
 *
 *  1. NOT SORTED. ClassifyOracle sorts because tile names are a set. Here the
 *     feature order IS the contract — GisImport.buildings is "in import order"
 *     and per-building RNG seeding indexes into that list, so sorting would
 *     hide exactly the class of bug this oracle exists to catch. Emitting in
 *     file order also sidesteps the Java-UTF-16 vs C++-UTF-8 collation
 *     difference entirely rather than having to defend against it.
 *
 *  2. COORDINATES AS RAW BITS. Doubles are emitted as %016x of their bit
 *     pattern, not as text. Comparing formatted coordinates would test the two
 *     printf implementations rather than the two parsers; comparing bits tests
 *     that the parse produced the identical double. Property values still go
 *     through asText(), because there the string conversion IS the behaviour
 *     under test.
 */
public final class GeoJsonOracle {

    /** Escape control characters so one record is always exactly one line with
     *  fixed tab positions. Without this a property value containing a tab or a
     *  newline (OSM description tags do) silently misaligns the digest fields —
     *  identically on both sides, so the diff would still pass while comparing
     *  the wrong columns. */
    static String esc(String s) {
        if (s == null) return "-";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\t'  -> b.append("\\t");
                case '\n'  -> b.append("\\n");
                case '\r'  -> b.append("\\r");
                default -> {
                    if (c < 0x20) b.append(String.format("\\x%02x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    public static void main(String[] args) throws Exception {
        Path in  = Path.of(args[0]);
        Path out = Path.of(args[1]);

        GeoJson g = GeoJson.read(in);
        List<String> lines = new ArrayList<>();

        for (int i = 0; i < g.features.size(); i++) {
            GeoJson.Feature f = g.features.get(i);
            int pts = 0;
            for (List<double[]> r : f.rings) pts += r.size();
            String type = (f.type == null || f.type.isEmpty()) ? "-" : f.type;
            lines.add("F\t" + i + "\t" + esc(type) + "\t" + f.rings.size() + "\t" + pts);

            for (Map.Entry<String, String> e : f.props.entrySet()) {
                String v = e.getValue();
                lines.add("P\t" + i + "\t" + esc(e.getKey()) + "\t" + esc(v));
            }

            for (int r = 0; r < f.rings.size(); r++) {
                List<double[]> ring = f.rings.get(r);
                for (int j = 0; j < ring.size(); j++) {
                    double[] p = ring.get(j);
                    lines.add("C\t" + i + "\t" + r + "\t" + j + "\t"
                            + String.format("%016x", Double.doubleToRawLongBits(p[0])) + "\t"
                            + String.format("%016x", Double.doubleToRawLongBits(p[1])));
                }
            }
        }

        Files.write(out, lines);
        System.out.println("java geojson: " + g.features.size() + " features, "
                + lines.size() + " digest lines -> " + out);
    }
}

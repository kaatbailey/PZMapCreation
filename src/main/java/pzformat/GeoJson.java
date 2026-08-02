package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Minimal GeoJSON reader: enough for polygons and linestrings with properties.
 *
 * Deliberately dependency-free, matching the rest of the project. Handles the
 * Esri-flavoured GeoJSON that ArcGIS feature services emit.
 */
public final class GeoJson {

    public static final class Feature {
        public String type;                       // Polygon, MultiPolygon, LineString, ...
        public final List<List<double[]>> rings = new ArrayList<>();  // lon,lat pairs
        public final Map<String, String> props = new LinkedHashMap<>();
        public String prop(String k) { return props.get(k); }
    }

    public final List<Feature> features = new ArrayList<>();

    public static GeoJson read(Path file) throws Exception {
        String text = Files.readString(file);
        GeoJson g = new GeoJson();
        Json.Value root = Json.parse(text);
        Json.Value feats = root.get("features");
        if (feats == null || feats.array == null) return g;
        for (Json.Value f : feats.array) {
            Json.Value geom = f.get("geometry");
            if (geom == null || geom.object == null) continue;
            Json.Value coords = geom.get("coordinates");
            if (coords == null) continue;
            Feature out = new Feature();
            Json.Value t = geom.get("type");
            out.type = t == null ? "" : t.str;
            collectRings(coords, out.rings);
            Json.Value props = f.get("properties");
            if (props != null && props.object != null)
                props.object.forEach((k, v) -> out.props.put(k, v.asText()));
            if (!out.rings.isEmpty()) g.features.add(out);
        }
        return g;
    }

    /** Descend until we find arrays of coordinate pairs; each becomes a ring. */
    private static void collectRings(Json.Value node, List<List<double[]>> out) {
        if (node.array == null) return;
        if (isPairArray(node)) {
            List<double[]> ring = new ArrayList<>();
            for (Json.Value p : node.array)
                ring.add(new double[]{p.array.get(0).num, p.array.get(1).num});
            out.add(ring);
            return;
        }
        for (Json.Value child : node.array) collectRings(child, out);
    }

    private static boolean isPairArray(Json.Value node) {
        if (node.array == null || node.array.isEmpty()) return false;
        Json.Value first = node.array.get(0);
        return first.array != null && first.array.size() >= 2
                && first.array.get(0).isNum && first.array.get(1).isNum;
    }
}

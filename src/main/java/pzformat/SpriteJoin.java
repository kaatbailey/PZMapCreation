package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Do tile names from the map resolve to sprites in the .pack atlases?
 *
 * Rendering assumes they do. That assumption has not been tested, and an
 * unverified join is exactly the kind of thing that produced the x/y
 * transposition — plausible, self-consistent, and wrong. So: measure it before
 * writing a renderer on top.
 *
 * Also reports sprite dimensions, which determine the isometric projection.
 */
public final class SpriteJoin {

    public static void run(Path texturePackDir, Path lotheader) throws Exception {
        List<Path> packs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(texturePackDir, "*.pack")) {
            for (Path p : ds) packs.add(p);
        }
        Collections.sort(packs);
        System.out.println("texture packs: " + packs.size());

        // name -> which pack it came from, plus its rect
        Map<String, PackFile.Entry> entries = new HashMap<>();
        Map<String, String> source = new HashMap<>();
        Map<String, Integer> perPack = new LinkedHashMap<>();

        for (Path p : packs) {
            try {
                PackFile pf = PackFile.read(p);
                int n = 0;
                for (PackFile.Page page : pf.pages)
                    for (PackFile.Entry e : page.entries) {
                        entries.putIfAbsent(e.name, e);
                        source.putIfAbsent(e.name, p.getFileName().toString());
                        n++;
                    }
                perPack.put(p.getFileName().toString(), n);
            } catch (Exception e) {
                System.out.println("   " + p.getFileName() + " FAILED: " + e.getMessage());
            }
        }
        System.out.println("distinct sprite names: " + entries.size());
        perPack.forEach((k, v) -> System.out.printf("   %-34s %6d entries%n", k, v));

        // Sprite dimensions drive the isometric projection.
        Map<String, Integer> dims = new TreeMap<>();
        for (PackFile.Entry e : entries.values())
            dims.merge(e.fx + "x" + e.fy, 1, Integer::sum);
        System.out.println("\nmost common full sprite sizes:");
        dims.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(8)
            .forEach(e -> System.out.printf("   %-14s %d sprites%n", e.getKey(), e.getValue()));

        if (lotheader == null) return;

        // The join that matters.
        LotHeader h = LotHeader.read(lotheader);
        int found = 0;
        List<String> missing = new ArrayList<>();
        Map<String, Integer> missingByPrefix = new TreeMap<>();
        for (String name : h.tileNames) {
            if (entries.containsKey(name)) found++;
            else {
                if (missing.size() < 12) missing.add(name);
                int us = name.lastIndexOf('_');
                missingByPrefix.merge(us < 0 ? name : name.substring(0, us), 1, Integer::sum);
            }
        }
        System.out.println("\n=== join: " + lotheader.getFileName() + " ===");
        System.out.printf("   %d / %d tile names resolve to a sprite  (%.2f%%)%n",
                found, h.tileNames.size(), 100.0 * found / h.tileNames.size());
        if (!missing.isEmpty()) {
            System.out.println("   unresolved examples:");
            for (String s : missing) System.out.println("      " + s);
            System.out.println("   unresolved grouped by tileset (top 10):");
            missingByPrefix.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .forEach(e -> System.out.printf("      %-40s %d%n", e.getKey(), e.getValue()));
        }

        // Which packs actually supply this cell's sprites?
        Map<String, Integer> usedFrom = new TreeMap<>();
        for (String name : h.tileNames) {
            String src = source.get(name);
            if (src != null) usedFrom.merge(src, 1, Integer::sum);
        }
        System.out.println("\n   sprites for this cell come from:");
        usedFrom.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(e -> System.out.printf("      %-34s %d%n", e.getKey(), e.getValue()));

        System.out.println("\n   sample resolved sprites:");
        int shown = 0;
        for (String name : h.tileNames) {
            PackFile.Entry e = entries.get(name);
            if (e == null) continue;
            System.out.printf("      %-38s %s   from %s%n", name, e, source.get(name));
            if (++shown >= 6) break;
        }
    }
}

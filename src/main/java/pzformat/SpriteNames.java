package pzformat;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every sprite name present in the .pack atlases.
 *
 * A tile name can exist in the tiledefs (so it has properties) without existing
 * in any atlas (so it has no pixels). Such a tile writes into the lotpack
 * correctly, round-trips byte-identically, satisfies
 * hasEmptySquaresOnLevelZero(), and renders in game as a missing-texture
 * checkerboard. TilePalette selected on properties alone, so nothing caught it.
 *
 * Same load SpriteJoin already performs, reduced to the name set.
 */
public final class SpriteNames {

    private SpriteNames() { }

    public static Set<String> load(Path texturePackDir) throws Exception {
        List<Path> packs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(texturePackDir, "*.pack")) {
            for (Path p : ds) {
                packs.add(p);
            }
        }
        Collections.sort(packs);

        Set<String> names = new HashSet<>();
        List<String> failed = new ArrayList<>();
        for (Path p : packs) {
            try {
                PackFile pf = PackFile.read(p);
                for (PackFile.Page page : pf.pages) {
                    for (PackFile.Entry e : page.entries) {
                        names.add(e.name);
                    }
                }
            } catch (Exception e) {
                failed.add(p.getFileName().toString());
            }
        }

        System.out.println("sprite atlas: " + names.size() + " names from "
                + (packs.size() - failed.size()) + "/" + packs.size() + " packs");
        if (!failed.isEmpty()) {
            System.out.println("   unparsed packs (known, UI/effects art): " + failed.size());
        }
        if (names.isEmpty()) {
            throw new IllegalStateException(
                    "no sprite names loaded from " + texturePackDir
                            + " — palette validation would pass vacuously");
        }
        return names;
    }
}

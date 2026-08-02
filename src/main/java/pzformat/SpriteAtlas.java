package pzformat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;

/**
 * Sprite lookup across several .pack atlases.
 *
 * Loading everything eagerly is not viable: Tiles2x.pack alone is 321 MB across
 * 369 pages, and decoding all of them would run to gigabytes of raster. So the
 * index is built from the entry tables only, and a page's PNG is decoded on
 * first use and cached. Pages containing nothing we need are dropped outright.
 *
 * Sprites come in 1x (64x128) and 2x (128x256). Everything is normalised to 1x
 * so a cell can mix packs freely; 2x art is drawn at half size.
 */
public final class SpriteAtlas {

    public static final int TILE_W = 64;    // 1x sprite cell
    public static final int TILE_H = 128;

    /** Atlases that carry map tiles, in preference order (1x first: no downscale). */
    public static final String[] MAP_PACKS = {
        "Tiles1x.pack", "Tiles1x.floor.pack",
        "Tiles2x.pack", "Tiles2x.floor.pack",
        "JumboTreesBigs2x.pack", "Overlays2x.pack", "Overlays2x.floor.pack"
    };

    public static final class Sprite {
        public String name, pack;
        public int pageIndex;
        public int x, y, w, h, ox, oy, fx, fy;
        /** 1.0 for 64x128 art, 0.5 for 128x256. */
        public double scale = 1.0;
        byte[] pngBytes;
        BufferedImage page;

        BufferedImage page() {
            if (page == null) {
                try {
                    page = ImageIO.read(new ByteArrayInputStream(pngBytes));
                } catch (Exception e) {
                    throw new RuntimeException("decoding page " + pageIndex + " of " + pack, e);
                }
            }
            return page;
        }

        /** The trimmed sub-image for this sprite. */
        public BufferedImage image() {
            BufferedImage p = page();
            int cw = Math.min(w, p.getWidth() - x), ch = Math.min(h, p.getHeight() - y);
            if (cw <= 0 || ch <= 0) return null;
            return p.getSubimage(x, y, cw, ch);
        }
    }

    private final Map<String, Sprite> byName = new HashMap<>();
    public int packsLoaded, packsFailed, pagesRetained, spritesIndexed;
    public final List<String> failures = new ArrayList<>();

    /**
     * @param needed sprite names actually required; pages with none are discarded.
     *               Pass null to keep everything (heavy).
     */
    public static SpriteAtlas load(Path texturePackDir, Set<String> needed) throws Exception {
        SpriteAtlas a = new SpriteAtlas();
        for (String packName : MAP_PACKS) {
            Path p = texturePackDir.resolve(packName);
            if (!Files.exists(p)) continue;
            try {
                PackFile pf = PackFile.read(p);
                a.packsLoaded++;
                for (int pi = 0; pi < pf.pages.size(); pi++) {
                    PackFile.Page page = pf.pages.get(pi);
                    boolean useful = needed == null;
                    if (!useful)
                        for (PackFile.Entry e : page.entries)
                            if (needed.contains(e.name) && !a.byName.containsKey(e.name)) {
                                useful = true;
                                break;
                            }
                    if (!useful) continue;
                    a.pagesRetained++;
                    for (PackFile.Entry e : page.entries) {
                        if (needed != null && !needed.contains(e.name)) continue;
                        if (a.byName.containsKey(e.name)) continue;   // earlier pack wins
                        Sprite s = new Sprite();
                        s.name = e.name; s.pack = packName; s.pageIndex = pi;
                        s.x = e.x; s.y = e.y; s.w = e.w; s.h = e.h;
                        s.ox = e.ox; s.oy = e.oy; s.fx = e.fx; s.fy = e.fy;
                        s.scale = e.fx >= TILE_W * 2 ? 0.5 : 1.0;
                        s.pngBytes = page.png;
                        a.byName.put(e.name, s);
                        a.spritesIndexed++;
                    }
                }
            } catch (Exception e) {
                a.packsFailed++;
                a.failures.add(packName + ": " + e.getMessage());
            }
        }
        return a;
    }

    public Sprite get(String name) { return byName.get(name); }
    public boolean has(String name) { return byName.containsKey(name); }
    public int size() { return byName.size(); }
}

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
 * first use and kept in a small LRU cache.
 *
 * Sprites come in 1x (64x128) and 2x (128x256). Everything is normalised to 1x
 * so a cell can mix packs freely; 2x art is drawn at half size.
 */
public final class SpriteAtlas {

    public static final int TILE_W = 64;
    public static final int TILE_H = 128;

    /** Maximum number of decoded atlas pages retained at once. */
    private static final int MAX_DECODED_PAGES = 8;

    /** Atlases that carry map tiles, in preference order (1x first: no downscale). */
    public static final String[] MAP_PACKS = {
            "Tiles1x.pack", "Tiles1x.floor.pack",
            "Tiles2x.pack", "Tiles2x.floor.pack",
            "JumboTrees1x.pack",
            "JumboTreesBigs2x.pack", "JumboTrees2x.pack",
            "Overlays2x.pack", "Overlays2x.floor.pack"
    };

    /**
     * One decoded atlas page.
     *
     * The key includes the pack name because page number 29 in two different
     * packs is obviously not the same page.
     */
    private static final class PageKey {

        final String pack;
        final int pageIndex;

        PageKey(String pack, int pageIndex) {
            this.pack = pack;
            this.pageIndex = pageIndex;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PageKey other)) {
                return false;
            }

            return pageIndex == other.pageIndex
                    && pack.equals(other.pack);
        }

        @Override
        public int hashCode() {
            return 31 * pack.hashCode() + pageIndex;
        }
    }

    private final Map<String, Sprite> byName = new HashMap<>();

    /**
     * Decoded pages are cached globally for this atlas instance, with the
     * least-recently-used page automatically discarded.
     */
    private final LinkedHashMap<PageKey, BufferedImage> decodedPages =
            new LinkedHashMap<>(16, 0.75f, true) {

                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<PageKey, BufferedImage> eldest) {

                    return size() > MAX_DECODED_PAGES;
                }
            };

    public int packsLoaded;
    public int packsFailed;
    public int pagesRetained;
    public int spritesIndexed;

    public final List<String> failures = new ArrayList<>();

    public static final class Sprite {

        public String name;
        public String pack;

        public int pageIndex;

        public int x;
        public int y;
        public int w;
        public int h;

        public int ox;
        public int oy;
        public int fx;
        public int fy;

        /** 1.0 for 64x128 art, 0.5 for 128x256. */
        public double scale = 1.0;

        byte[] pngBytes;

        /*
         * Cached extracted sprite raster.
         *
         * This is intentionally stored on the Sprite itself. The sprite is
         * already indexed for the lifetime of the atlas, so repeated calls to
         * image() can reuse the small raster instead of repeatedly allocating
         * and copying it from the atlas page.
         */
        private BufferedImage cachedImage;

        /*
         * Back-reference to the owning atlas so sprites can share the
         * decoded-page cache.
         */
        private SpriteAtlas owner;

        /**
         * Return the trimmed raster for this sprite.
         *
         * The first call extracts the sprite from the decoded atlas page.
         * Subsequent calls return the same small independent raster.
         *
         * The atlas page itself remains managed by SpriteAtlas's bounded LRU.
         */
        public synchronized BufferedImage image() {

            if (cachedImage != null) {
                return cachedImage;
            }

            BufferedImage page =
                    owner.decodedPage(this);

            if (page == null) {
                return null;
            }

            int cw =
                    Math.min(
                            w,
                            page.getWidth() - x
                    );

            int ch =
                    Math.min(
                            h,
                            page.getHeight() - y
                    );

            if (cw <= 0 || ch <= 0) {
                return null;
            }

            BufferedImage sprite =
                    new BufferedImage(
                            cw,
                            ch,
                            BufferedImage.TYPE_INT_ARGB
                    );

            java.awt.Graphics2D g =
                    sprite.createGraphics();

            try {

                g.drawImage(
                        page,
                        -x,
                        -y,
                        null
                );

            } finally {

                g.dispose();
            }

            cachedImage = sprite;

            return cachedImage;
        }
    }

    /**
     * Decode one page, using the bounded LRU cache.
     *
     * The important part is that the key is the pack + page number. Multiple
     * sprites belonging to the same page therefore share exactly one decoded
     * BufferedImage while that page is resident.
     */
    private synchronized BufferedImage decodedPage(
            Sprite sprite
    ) {

        PageKey key =
                new PageKey(
                        sprite.pack,
                        sprite.pageIndex
                );

        BufferedImage page =
                decodedPages.get(key);

        if (page != null) {
            return page;
        }

        try {

            page =
                    ImageIO.read(
                            new ByteArrayInputStream(
                                    sprite.pngBytes
                            )
                    );

            if (page == null) {
                return null;
            }

            decodedPages.put(
                    key,
                    page
            );

            return page;

        } catch (Exception e) {

            throw new RuntimeException(
                    "decoding page "
                            + sprite.pageIndex
                            + " of "
                            + sprite.pack,
                    e
            );
        }
    }

    /**
     * @param needed sprite names actually required; pages with none are discarded.
     *               Pass null to keep everything (heavy).
     */
    public static SpriteAtlas load(
            Path texturePackDir,
            Set<String> needed
    ) throws Exception {

        SpriteAtlas a =
                new SpriteAtlas();

        for (String packName : MAP_PACKS) {

            Path p =
                    texturePackDir.resolve(
                            packName
                    );

            if (!Files.exists(p)) {
                continue;
            }

            try {

                PackFile pf =
                        PackFile.read(p);

                a.packsLoaded++;

                for (
                        int pi = 0;
                        pi < pf.pages.size();
                        pi++
                ) {

                    PackFile.Page page =
                            pf.pages.get(pi);

                    boolean useful =
                            needed == null;

                    if (!useful) {

                        for (PackFile.Entry e : page.entries) {

                            if (
                                    needed.contains(e.name)
                                            && !a.byName.containsKey(e.name)
                            ) {

                                useful = true;
                                break;
                            }
                        }
                    }

                    if (!useful) {
                        continue;
                    }

                    a.pagesRetained++;

                    for (PackFile.Entry e : page.entries) {

                        if (
                                needed != null
                                        && !needed.contains(e.name)
                        ) {
                            continue;
                        }

                        if (a.byName.containsKey(e.name)) {
                            continue;
                        }

                        Sprite s =
                                new Sprite();

                        s.name =
                                e.name;

                        s.pack =
                                packName;

                        s.pageIndex =
                                pi;

                        s.x =
                                e.x;

                        s.y =
                                e.y;

                        s.w =
                                e.w;

                        s.h =
                                e.h;

                        s.ox =
                                e.ox;

                        s.oy =
                                e.oy;

                        s.fx =
                                e.fx;

                        s.fy =
                                e.fy;

                        /*
                         * Scale is a property of the PACK, not the sprite.
                         * Jumbo trees are 1x art at 192x256 - large, not
                         * double-resolution.
                         */
                        s.scale =
                                packName.contains("2x")
                                        ? 0.5
                                        : 1.0;

                        s.pngBytes =
                                page.png;

                        s.owner =
                                a;

                        a.byName.put(
                                e.name,
                                s
                        );

                        a.spritesIndexed++;
                    }
                }

            } catch (Exception e) {

                a.packsFailed++;

                a.failures.add(
                        packName
                                + ": "
                                + e.getMessage()
                );
            }
        }

        return a;
    }

    public Sprite get(String name) {
        return byName.get(name);
    }

    public boolean has(String name) {
        return byName.containsKey(name);
    }

    public int size() {
        return byName.size();
    }
}


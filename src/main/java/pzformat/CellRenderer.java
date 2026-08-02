package pzformat;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Renders a region of a cell to a PNG.
 *
 * Deliberately not a live renderer. A file you can open and compare against the
 * in-game map is the fastest way to find interpretation errors — the kind that
 * pass every byte-level check, as the x/y transposition did.
 *
 * Isometric projection. Sprites are 64x128 at 1x with the floor diamond filling
 * the bottom 64x32, so the diamond is 64 wide and 32 tall:
 *
 *     screenX = (x - y) * 32
 *     screenY = (x + y) * 16  -  z * Z_STEP
 *
 * Sprites are trimmed, so each is drawn at its (ox, oy) within the 64x128 cell
 * rather than at the cell origin.
 *
 * Draw order is painter's: z ascending, then y, then x — both axes increase
 * screenY, so this fills back to front.
 */
public final class CellRenderer {

    /** Vertical rise of one z level, in 1x pixels. Wall art is 128 tall over a 32 tall floor. */
    public static int Z_STEP = 96;

    public static final int HALF_W = SpriteAtlas.TILE_W / 2;    // 32
    public static final int QUARTER_W = SpriteAtlas.TILE_W / 4; // 16

    public static void run(Path mapDir, Path texturePackDir, String cellName,
                           int x0, int y0, int size, int zFrom, int zTo,
                           Path outPng) throws Exception {
        Path lh = mapDir.resolve(cellName + ".lotheader");
        Path lp = mapDir.resolve("world_" + cellName + ".lotpack");
        CellData cell = CellData.load(lp, lh);
        System.out.println("cell " + cellName + ": z " + cell.minLevel + ".." + cell.maxLevel
                + ", region (" + x0 + "," + y0 + ") " + size + "x" + size);

        zFrom = Math.max(zFrom, cell.minLevel);
        zTo = Math.min(zTo, cell.maxLevel);

        // Which sprites does this region actually need?
        Set<String> needed = new HashSet<>();
        for (int z = zFrom; z <= zTo; z++)
            for (int x = x0; x < x0 + size; x++)
                for (int y = y0; y < y0 + size; y++) {
                    if (x >= cell.cellSize || y >= cell.cellSize) continue;
                    String[] names = cell.tileNamesAt(x, y, z);
                    if (names != null) needed.addAll(Arrays.asList(names));
                }
        System.out.println("distinct sprites needed: " + needed.size());

        SpriteAtlas atlas = SpriteAtlas.load(texturePackDir, needed);
        System.out.println("packs loaded: " + atlas.packsLoaded
                + "   pages retained: " + atlas.pagesRetained
                + "   sprites indexed: " + atlas.spritesIndexed);
        for (String f : atlas.failures) System.out.println("   pack failed: " + f);
        int missing = 0;
        for (String n : needed) if (!atlas.has(n)) missing++;
        System.out.println("sprites not found: " + missing + " / " + needed.size());

        // Canvas bounds. Corners of the region in screen space, plus sprite extents.
        int minSx = Integer.MAX_VALUE, maxSx = Integer.MIN_VALUE;
        int minSy = Integer.MAX_VALUE, maxSy = Integer.MIN_VALUE;
        for (int x = x0; x <= x0 + size; x++)
            for (int y = y0; y <= y0 + size; y++) {
                int sx = (x - y) * HALF_W, sy = (x + y) * QUARTER_W;
                minSx = Math.min(minSx, sx); maxSx = Math.max(maxSx, sx);
                minSy = Math.min(minSy, sy); maxSy = Math.max(maxSy, sy);
            }
        int pad = SpriteAtlas.TILE_W;
        int originX = -minSx + pad;
        int originY = -minSy + pad + (zTo - zFrom + 1) * Z_STEP;
        int width = (maxSx - minSx) + SpriteAtlas.TILE_W + pad * 2;
        int height = (maxSy - minSy) + SpriteAtlas.TILE_H + pad * 2
                + (zTo - zFrom + 1) * Z_STEP;
        System.out.println("canvas: " + width + "x" + height);

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(24, 24, 28));
        g.fillRect(0, 0, width, height);

        long drawn = 0, skipped = 0;
        for (int z = zFrom; z <= zTo; z++)
            for (int y = y0; y < y0 + size; y++)
                for (int x = x0; x < x0 + size; x++) {
                    if (x >= cell.cellSize || y >= cell.cellSize) continue;
                    String[] names = cell.tileNamesAt(x, y, z);
                    if (names == null) continue;
                    int sx = originX + (x - y) * HALF_W;
                    int sy = originY + (x + y) * QUARTER_W - (z - zFrom) * Z_STEP;
                    for (String n : names) {
                        SpriteAtlas.Sprite s = atlas.get(n);
                        if (s == null) { skipped++; continue; }
                        BufferedImage sub = s.image();
                        if (sub == null) { skipped++; continue; }
                        int dw = (int) Math.round(sub.getWidth() * s.scale);
                        int dh = (int) Math.round(sub.getHeight() * s.scale);
                        int dx = sx + (int) Math.round(s.ox * s.scale);
                        int dy = sy + (int) Math.round(s.oy * s.scale);
                        g.drawImage(sub, dx, dy, dw, dh, null);
                        drawn++;
                    }
                }
        g.dispose();

        Files.createDirectories(outPng.toAbsolutePath().getParent());
        ImageIO.write(img, "png", outPng.toFile());
        System.out.println("sprites drawn: " + drawn + "   skipped: " + skipped);
        System.out.println("\nwrote " + outPng.toAbsolutePath());
        System.out.println("Open it and compare against the in-game map. Wrong geometry"
                + " is obvious by eye in a way that byte checks cannot show.");
    }
}

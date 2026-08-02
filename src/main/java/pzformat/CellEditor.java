package pzformat;

import java.util.*;

/**
 * Layer-aware editing over a CellData, with undo/redo.
 *
 * CellData.fill replaces a square's ENTIRE tile stack, which is why the in-game
 * test punched holes through houses — walls and furniture sharing those squares
 * went with the floor. That is the right behaviour for "clear", and the wrong
 * behaviour for everything else. These operations target one layer and leave
 * the rest alone, using TileIndex to tell the layers apart.
 *
 * Every mutation goes through apply(), so undo is uniform: an edit is just the
 * before and after state of the squares it touched. Related changes can be
 * grouped so a rectangle fill undoes in one step.
 */
public final class CellEditor {

    public final CellData cell;
    public final TileIndex tiles;

    private final Deque<Edit> undoStack = new ArrayDeque<>();
    private final Deque<Edit> redoStack = new ArrayDeque<>();
    private Edit group;
    private String groupLabel;

    public CellEditor(CellData cell, TileIndex tiles) {
        this.cell = cell;
        this.tiles = tiles;
    }

    // ---------------- undo journal ----------------

    private static final class Change {
        final int x, y, z, oldRoom, newRoom;
        final int[] oldTiles, newTiles;
        Change(int x, int y, int z, int[] oldTiles, int oldRoom, int[] newTiles, int newRoom) {
            this.x = x; this.y = y; this.z = z;
            this.oldTiles = oldTiles; this.oldRoom = oldRoom;
            this.newTiles = newTiles; this.newRoom = newRoom;
        }
    }

    public static final class Edit {
        public String label;
        final List<Change> changes = new ArrayList<>();
        public int squaresTouched() { return changes.size(); }
        @Override public String toString() { return label + " (" + changes.size() + " squares)"; }
    }

    /** Group subsequent operations into a single undo step. */
    public void begin(String label) {
        if (group != null) throw new IllegalStateException("group '" + groupLabel + "' already open");
        group = new Edit();
        group.label = label;
        groupLabel = label;
    }

    public Edit end() {
        if (group == null) throw new IllegalStateException("no open group");
        Edit e = group;
        group = null;
        groupLabel = null;
        if (!e.changes.isEmpty()) {
            undoStack.push(e);
            redoStack.clear();
        }
        return e;
    }

    private void record(int x, int y, int z, int[] oldTiles, int oldRoom,
                        int[] newTiles, int newRoom) {
        Change c = new Change(x, y, z, oldTiles, oldRoom, newTiles, newRoom);
        if (group != null) { group.changes.add(c); return; }
        Edit e = new Edit();
        e.label = "edit";
        e.changes.add(c);
        undoStack.push(e);
        redoStack.clear();
    }

    private void apply(int x, int y, int z, int[] newTiles, int newRoom, String label) {
        int[] oldTiles = cell.tilesAt(x, y, z);
        int oldRoom = cell.roomAt(x, y, z);
        if (Arrays.equals(oldTiles, newTiles) && oldRoom == newRoom) return;
        cell.setSquare(x, y, z, newTiles, newRoom);
        record(x, y, z, oldTiles, oldRoom, newTiles, newRoom);
        if (group != null && group.label == null) group.label = label;
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }
    public int undoDepth() { return undoStack.size(); }

    public Edit undo() {
        if (undoStack.isEmpty()) return null;
        Edit e = undoStack.pop();
        for (int i = e.changes.size() - 1; i >= 0; i--) {
            Change c = e.changes.get(i);
            cell.setSquare(c.x, c.y, c.z, c.oldTiles, c.oldRoom);
        }
        redoStack.push(e);
        return e;
    }

    public Edit redo() {
        if (redoStack.isEmpty()) return null;
        Edit e = redoStack.pop();
        for (Change c : e.changes) cell.setSquare(c.x, c.y, c.z, c.newTiles, c.newRoom);
        undoStack.push(e);
        return e;
    }

    // ---------------- layer-aware operations ----------------

    private int[] stack(int x, int y, int z) {
        int[] t = cell.tilesAt(x, y, z);
        return t == null ? new int[0] : t;
    }

    private String nameOf(int idx) {
        return idx >= 0 && idx < cell.header.tileNames.size()
                ? cell.header.tileNames.get(idx) : null;
    }

    /**
     * Replace the floor, preserving walls, objects and overlays. If the square
     * has no floor the new tile goes first, since floors draw beneath.
     */
    public void setFloor(int x, int y, int z, String tileName) {
        int idx = cell.tileIndex(tileName);
        int[] cur = stack(x, y, z);
        List<Integer> out = new ArrayList<>();
        boolean replaced = false;
        for (int t : cur) {
            String n = nameOf(t);
            if (!replaced && n != null && tiles.kindOf(n) == TileIndex.Kind.FLOOR
                    && !tiles.isOverlay(n)) {
                out.add(idx);
                replaced = true;
            } else out.add(t);
        }
        if (!replaced) out.add(0, idx);
        apply(x, y, z, toArray(out), cell.roomAt(x, y, z), "set floor");
    }

    /** Place or replace the wall on one edge, leaving the other edge and everything else. */
    public void setWall(int x, int y, int z, TileIndex.Edge edge, String tileName) {
        if (edge != TileIndex.Edge.NORTH && edge != TileIndex.Edge.WEST)
            throw new IllegalArgumentException("edge must be NORTH or WEST");
        int idx = cell.tileIndex(tileName);
        int[] cur = stack(x, y, z);
        List<Integer> out = new ArrayList<>();
        boolean replaced = false;
        for (int t : cur) {
            String n = nameOf(t);
            if (!replaced && n != null && tiles.isStructuralWall(n) && edgeMatches(n, edge)) {
                out.add(idx);
                replaced = true;
            } else out.add(t);
        }
        if (!replaced) out.add(idx);
        apply(x, y, z, toArray(out), cell.roomAt(x, y, z), "set wall");
    }

    /** Remove the wall on one edge, and any door leaf or pane mounted in it. */
    public void removeWall(int x, int y, int z, TileIndex.Edge edge) {
        int[] cur = stack(x, y, z);
        List<Integer> out = new ArrayList<>();
        for (int t : cur) {
            String n = nameOf(t);
            if (n != null && edgeMatches(n, edge)
                    && (tiles.isStructuralWall(n) || tiles.isWallFixture(n))) continue;
            out.add(t);
        }
        apply(x, y, z, toArray(out), cell.roomAt(x, y, z), "remove wall");
    }

    private boolean edgeMatches(String tileName, TileIndex.Edge edge) {
        TileIndex.Edge e = tiles.edgeOf(tileName);
        return e == edge || e == TileIndex.Edge.BOTH;
    }

    /** Add an object on top, leaving structure intact. */
    public void addObject(int x, int y, int z, String tileName) {
        int idx = cell.tileIndex(tileName);
        int[] cur = stack(x, y, z);
        List<Integer> out = new ArrayList<>();
        for (int t : cur) out.add(t);
        out.add(idx);
        apply(x, y, z, toArray(out), cell.roomAt(x, y, z), "add object");
    }

    /** Remove every non-structural object, keeping floor, walls and fixtures. */
    public void clearObjects(int x, int y, int z) {
        int[] cur = stack(x, y, z);
        List<Integer> out = new ArrayList<>();
        for (int t : cur) {
            String n = nameOf(t);
            if (n == null) { out.add(t); continue; }
            TileIndex.Kind k = tiles.kindOf(n);
            boolean structural = tiles.isStructuralWall(n) || tiles.isWallFixture(n)
                    || (k == TileIndex.Kind.FLOOR && !tiles.isOverlay(n));
            if (structural) out.add(t);
        }
        apply(x, y, z, toArray(out), cell.roomAt(x, y, z), "clear objects");
    }

    /** Remove everything. This is the destructive operation; the others are not. */
    public void clearSquare(int x, int y, int z) {
        apply(x, y, z, null, -1, "clear square");
    }

    public void setRoom(int x, int y, int z, int roomId) {
        apply(x, y, z, cell.tilesAt(x, y, z), roomId, "set room");
    }

    /** Floor fill over a rectangle, as one undo step. */
    public Edit fillFloor(int x0, int y0, int w, int h, int z, String tileName) {
        begin("fill floor " + w + "x" + h);
        for (int x = x0; x < x0 + w; x++)
            for (int y = y0; y < y0 + h; y++) {
                if (x < 0 || y < 0 || x >= cell.cellSize || y >= cell.cellSize) continue;
                setFloor(x, y, z, tileName);
            }
        return end();
    }

    /** Wall around a rectangle: north walls on the top row, west on the left column. */
    public Edit outlineRoom(int x0, int y0, int w, int h, int z,
                            String northTile, String westTile) {
        begin("outline room " + w + "x" + h);
        for (int x = x0; x < x0 + w; x++) {
            setWall(x, y0, z, TileIndex.Edge.NORTH, northTile);
            setWall(x, y0 + h, z, TileIndex.Edge.NORTH, northTile);
        }
        for (int y = y0; y < y0 + h; y++) {
            setWall(x0, y, z, TileIndex.Edge.WEST, westTile);
            setWall(x0 + w, y, z, TileIndex.Edge.WEST, westTile);
        }
        return end();
    }

    private static int[] toArray(List<Integer> list) {
        if (list.isEmpty()) return null;
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    public Square square(int x, int y, int z) { return Square.at(cell, tiles, x, y, z); }
}

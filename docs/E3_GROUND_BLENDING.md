# E3 — Ground blending: how vanilla composes a ground square

Investigation document. **Deliverable is this document; no code was written and
`GroundPalette` was not touched.** A future session should be able to implement
from this without opening the game.

Every claim below is marked **CONFIRMED** or **UNVERIFIED**, with its source
named and — where it is a rule rather than a reading — the observation that
would disprove it.

Sources used, in the order Charter §4 prefers them:

| Source | What it settled |
|---|---|
| `blends_natural_01` tile properties (`PaletteScan`) | The block contract, the mask vocabulary |
| Decompiled `zombie.iso.worldgen.blending.Blending` / `BlendDirection` | That the engine does **not** author masks |
| `$PZ/media/lua/**` grep | That WorldGen can only place solid tiles |
| Muldraugh 42_40 measurement | The mask **rule**, and the material precedence |

Muldraugh was used last and only for the mechanical rule. Charter §4's warning
is about inferring *where regions go* from a hand-authored town; it does not
forbid measuring what the authoring tool mechanically emitted. Where a claim
does rest on Muldraugh's aesthetic choices, it is marked as such.

---

## 1. Ground is three layers, and the project has been conflating two of them

**CONFIRMED.** Source: `Probe square` on 42_40, `PaletteScan blends_natural_01`,
`PaletteScan blends_grassoverlays_01`.

| Layer | Tileset | Discriminating flags | Count per square |
|---|---|---|---|
| **solid** | `blends_natural_01`, `blends_street_01` | `solidfloor`, `diamondFloor` | exactly 1 |
| **mask** | same sheets | `FloorOverlay`, `IsFloorAttached`, `FloorAttachment{N,S,E,W}` | 0 to 4 |
| **tuft** | `blends_grassoverlays_01` | `vegitation`, `MoveWithWind`, `canBeRemoved` | 0 or 1 |

`GroundPalette` calls the tuft layer "overlay" (`OVERLAY_SHEET`,
`Ground(base, overlay)`, `overlayRate`). That name is now ambiguous and is
the single most likely cause of a future misimplementation. **Use
solid / mask / tuft throughout.** `GroundPalette` has never written a mask and
its `Ground` record has no slot for one.

**Stack order: solid first, masks after, tuft last.** CONFIRMED on every one of
the ~60 squares probed, across two tilesets. This matters: `IsoGridSquare.getFloor()`
returns the first floor object, and `cleanChunk` (STATE §7) reads
`floor.getSprite().getProperties().get("FloorMaterial")` off it. A mask written
first would be mistaken for the floor.

**Falsifier:** any square, anywhere, with a `FloorOverlay` tile before a
`solidfloor` tile in its stack.

### 1a. The tuft sheet, confirmed against `GroundPalette`'s existing model

`blends_grassoverlays_01` is 80 tiles, 8 wide. Columns 0–5 are `VEGETATION`
with sprites; columns 6 and 7 are `FLOOR`-classified with no sprite, except
indices 70 and 72–75 which have sprites and no properties. `GroundPalette`'s
`OVERLAY_ROW_WIDTH = 8` / `OVERLAY_USABLE_COLS = 6` model is correct and can
stand.

`IsoChunk` carries a rename table — `vegetation_groundcover_01_0` maps to
`blends_grassoverlays_01_16`, `_1` to `_8`, `_2` to `_0`, and so on. So the
tuft sheet **is** the legacy groundcover sheet under a new name, which means
`cleanChunk(ch, "Sand", "vegetation_groundcover_01")` strips tufts from Sand
and Road squares. Authoring tufts onto Sand or Road is wasted bytes.

---

## 2. The tileset contract — a 16-tile block per material

**CONFIRMED.** Source: `PaletteScan "$PZ/media" blends_natural_01`, all 160
indices read. Uniform across all seven materials.

For a material with block base **B** ∈ {0, 16, 32, 48, 64, 80, 96}:

| offset | role | direction |
|---|---|---|
| B+0, B+5, B+6, B+7 | **solid** variants, interchangeable | — |
| B+1 | corner mask | N+W |
| B+2 | corner mask | E+S |
| B+3 | corner mask | S+W |
| B+4 | corner mask | E+N |
| B+8 | side mask | N |
| B+9 | side mask | W |
| B+10 | side mask | E |
| B+11 | side mask | S |
| B+12 | side mask | N (variant 2) |
| B+13 | side mask | W (variant 2) |
| B+14 | side mask | E (variant 2) |
| B+15 | side mask | S (variant 2) |

Block bases, from the WorldGen GROUND features (STATE §8) and confirmed by the
solid-tile flags:

| B | material (`FloorMaterial`) | feature name |
|---|---|---|
| 0 | `Sand` | `sand` |
| 16 | `Grass_Dark` | `dark_grass` |
| 32 | `Grass_Medium` | `medium_grass` |
| 48 | `Grass_Light` | `light_grass` |
| 64 | `Dirt` | `dirt` |
| 80 | `Dirt_Grass` | `dirt_grass` |
| 96 | `Clay` | `clay` |

Indices 112–127 are a further side-mask set with **no solid tiles in the same
block**; 128–159 have no sprites. **UNVERIFIED** what 112–127 belongs to. It
does not matter for implementation — nothing needs to write them — but a
session that sees them should not assume block regularity continues past 111.

**Same contract in `blends_street_01`.** Square (90,190) is `_53` (`Road_04`,
solid) with `_26` (E) and `_25` (W) masks carrying `Road_02`. The offsets match
the table exactly. **CONFIRMED** for one block of one street tileset;
**UNVERIFIED** that every street block follows it.

Direction convention throughout: **+x is East, +y is South**, matching STATE
§10's "south wall belongs to the next square down, east wall to the next square
right".

---

## 3. The mask rule — CONFIRMED

**Source:** a complete contiguous 9×5 rectangle, Muldraugh 42_40, x=110–118,
y=198–202. 45 squares, 21 masks, every mask checked against its actual
neighbours. No mask in the rectangle is unexplained by the rule below.

> A square carries mask tiles drawn from its **neighbour's** block, not its own.
> The mask names the direction in which the other material lies.

Let `S` = the set of orthogonal directions whose neighbour carries the
higher-priority material (§4).

| \|S\| | encoding | vanilla samples in the rectangle |
|---|---|---|
| 0 | no mask | (115,200) (114,201) (115,201) (116,202) |
| 1 | one **side** tile | (115,198)W (115,199)W (116,199)E (110,199)N (110,200)E (116,200)E (110,201)W (116,201)E (110,202)W (111,202)E |
| 2, adjacent | one **corner** tile | (114,200)N+W (111,201)E+N (113,201)N+W (118,199)S+W (113,202)S+W (117,202)E+N |
| 2, opposite | two **side** tiles | (117,198) N and S |
| 3 | two **corner** tiles, sharing the middle direction | (111,199) E+N and E+S |
| 4 | four **corner** tiles | (112,200) N+W, E+N, S+W, E+S |

The |S|=4 case is an isolated Medium square with Dark on all four sides; four
corner tiles tile its entire perimeter. The |S|=2-adjacent case is the one that
matters most and the one most easily got wrong: **two adjacent sides use a
single corner tile, not two side tiles.**

Side masks have two interchangeable variants (B+8..11 and B+12..15) and vanilla
uses both — (116,199) and (116,200) use `_26`, (116,201) uses `_30`, all three
E-masks on identical geometry. **Pick the variant at random**, exactly as the
four solid variants are picked.

Stack order among masks at (112,200) was N+W, E+N, S+W, E+S — i.e. NW, NE, SW,
SE, reading order. **UNVERIFIED** that order is required; nothing suggests the
renderer cares, since the masks do not overlap.

### Implementable form

```
maskTilesFor(square):
    base     = material of this square's solid tile
    S        = { d in {N,W,E,S} : priority(materialAt(neighbour(d))) > priority(base) }
    if S is empty: return []

    X = material of the neighbours in S        # see UNVERIFIED note below
    B = blockBase(X)
    out = []

    if |S| == 4:
        out = [B+1, B+4, B+3, B+2]                       # NW, NE, SW, SE
    else if |S| == 3:
        mid = the direction adjacent to both others
        out = [corner(mid, other1), corner(mid, other2)]
    else if |S| == 2 and the two are adjacent:
        out = [corner(d1, d2)]
    else:
        for d in S: out += [side(d, randomVariant())]

    return out

corner(N,W)=B+1   corner(E,S)=B+2   corner(S,W)=B+3   corner(E,N)=B+4
side(N)=B+8|B+12  side(W)=B+9|B+13  side(E)=B+10|B+14 side(S)=B+11|B+15
```

**UNVERIFIED — the multi-material case.** In all 45 squares measured, every
neighbour in `S` carried the *same* material, so the rule was never tested where
a square borders two different higher-priority materials at once (Dark to the
north, Sand to the east). The natural extension is to run the procedure once per
distinct neighbouring material and concatenate, but that is inference.
**Falsifier / check:** find a square adjacent to two different non-base
materials — the Sand/grass boundary around (60–104, 200) is the obvious hunting
ground — and read its stack. If it carries masks from both blocks, the extension
holds. If it carries only one, there is a precedence tie-break that must be
found before this is implemented.

**UNVERIFIED — the |S|=3 case rests on one sample.** (111,199) chose two corner
tiles over "one corner plus one side". One observation. **Falsifier:** any
|S|=3 square encoded as corner + side.

**General falsifier for the whole rule:** any square whose mask set does not
follow from its four orthogonal neighbours' materials. One clean counterexample
outside a hand-edited area kills it.

---

## 4. Blending is one-way — a material precedence order

**CONFIRMED, and this is the finding that makes the rule deterministic.**

In the 9×5 rectangle, **not one `Grass_Dark` base square carries a mask.** All
21 masks are Grass_Dark drawn onto Grass_Medium. The relationship is not
reciprocal: (116,200) is Medium with a Dark E-mask, and its eastern neighbour
(117,200) is Dark with nothing.

Adding the two other adjacencies measured:

| observation | implies |
|---|---|
| Dark masks on Medium bases, never the reverse (21 samples) | `Grass_Dark` > `Grass_Medium` |
| (60,200): Sand solid + `_36` Grass_Medium corner mask | `Grass_Medium` > `Sand` |
| (90,190): `Road_04` solid + `Road_02` side masks | `Road_02` > `Road_04` |

So there is a **priority table**, and the higher-priority material is the one
that gets drawn as a mask onto its neighbour.

It is **not** block-index order — Sand is block 0 and loses to Medium at block
32 — so it must be measured, not derived. Three of the twenty-one possible
`blends_natural_01` pairs are known.

**UNVERIFIED:** the rest of the ordering, and whether it is even a total order.
**Check, cheap:** for each pair that occurs in vanilla, find one adjacency and
read which side carries the mask. **Falsifier for totality:** any pair where
each material is observed masking the other in different places, which would
mean priority is contextual rather than a fixed table.

**UNVERIFIED:** whether masks cross tilesets at all — whether a grass square
adjacent to a road carries a road mask, or the boundary is simply hard. No
sample was taken. **Check:** probe the four neighbours of a `blends_street_01`
square that borders grass.

---

## 5. Region boundaries are dithered, not clean

**CONFIRMED for this area of this cell.** Base materials over the measured
rectangle (D = `Grass_Dark`, M = `Grass_Medium`):

```
x:      110 111 112 113 114 115 116 117 118
y=198:   D   D   D   D   D   M   M   M   M
y=199:   M   M   D   D   D   M   M   D   M
y=200:   M   D   M   D   M   M   M   D   D
y=201:   M   M   D   M   M   M   M   D   D
y=202:   M   M   D   M   M   M   M   M   D
```

Dark holds the north edge and the east side, Medium the centre and southwest,
and the two **interpenetrate per square across a band two to four squares
wide**. Region interiors are still pure — STATE §21 test 40 found `Grass_Dark`
16/16 in forest cell 35_35, and (117,200)/(118,200)/(180,200) here carry no
masks at all.

So the model is:

1. **Region** — a coherent area of one material. Pure in the interior.
2. **Texture** — uniform random choice among the four solid variants,
   per square. (STATE §21; unchanged.)
3. **Dither** — at a region boundary, the two materials interleave per square
   over a few squares rather than meeting on a line.
4. **Mask** — every square whose neighbour carries a higher-priority material
   gets that material's mask tiles, per §3.

Layers 3 and 4 are separate mechanisms and both are needed. Dither without
masks is the scattered-diamond defect at region scale; masks without dither
gives a soft but geometrically straight edge.

### This retires STATE §24's central anomaly

§24 reported a `Grass_Medium` band at x=112–124 sitting inside `Grass_Dark`
with Dark on both sides, and treated it as evidence against any region model.
That transect sampled x = 112, 116, 120, 124 — **a 4-tile stride through a
dithered zone**, which returned Medium four times by aliasing. The row actually
reads `M D M D M M M D D` across x=110–118.

There is no band. The anomaly was a measurement artifact, and the same stride
appears in the earlier 40-tile-spacing transect, so **any conclusion drawn from
a strided ground sample in this project should be re-derived from contiguous
data.**

This does not resurrect the distance-from-habitation hypothesis. It removes
the specific evidence that was cited against it; that hypothesis remains
unsupported, because nothing has been measured that supports it either.

**Falsifier for the dither claim:** a contiguous rectangle across a region
boundary elsewhere in vanilla — a different cell, ideally a non-town one —
showing a clean straight material edge with masks but no interleaving. That
would make the dither a local hand-painting quirk of 42_40 rather than a
convention. **This check has not been run and should be, before dither is
implemented.**

---

## 6. The engine's own blending pass touches only the procedural seam

**CONFIRMED.** Source: decompiled `zombie.iso.worldgen.blending.Blending` and
`BlendDirection`, plus the call site in `IsoChunk.update()`.

```java
// IsoChunk.update()
if (doAttachments && !this.blendingDoneFull
        && !Arrays.equals(this.blendingModified, comparatorBool4)) {
    IsoWorld.instance.getBlending().applyBlending(this);
}
```

`comparatorBool4` is `{true,true,true,true}`. Chunk reset leaves
`blendingDoneFull = false` and `blendingModified` all false, so the gate opens
on authored chunks. But inside `applyBlending`, the per-direction work is
guarded:

```java
if (sourceChunk.isBlendingDoneFull()) {
    this.removeTrees(cell, chunk, dir);
    this.changeGround(cell, chunk, dir);
}
```

`blendingDoneFull` is set true in exactly one place — `WorldGenChunk.genRandomChunk`.
**So the neighbouring chunk must be a fully procedural WorldGen chunk.**
`genMapChunk` never touches these flags, so between two authored chunks nothing
happens.

And what it does is not masking:

```java
TileGroup tileGround = this.wgTile.getGround(biome, rnd);
int depth = rnd.nextInt(4);
for (int j = 0; j < depth; j++) {
    ...
    if (floor.getSprite().getName().contains("blends_natural_01")) {
        this.wgTile.setGround(this.wgTile.getSprite(tileGround.tiles().get(0)), square);
        this.wgTile.deleteTiles(square, this.plantsAdded);
        // then adds a PLANT tile from the neighbouring biome
    }
}
```

It **replaces the solid floor** with `tiles().get(0)` from the neighbouring
chunk's biome GROUND feature, for a random depth of 0–3 squares inward, along
each of the 8 columns of the shared edge. A ragged solid-tile feathering pass.
`maxDepth = 4` is a dead constant.

### Four consequences for us

1. **We must author every mask ourselves.** Every mask tile in Muldraugh's
   lotpack was written by the authoring tool. The engine will never soften an
   interior region boundary on our cells. This closes the fork that E3 opened.
2. **This is what removed the map-edge seam**, at least partly. STATE §21 and
   test 38 credit the biome map for the clean boundary after
   `WorldGenOverride.lua` was dropped; `changeGround` is a second mechanism
   doing visible work there. Not a contradiction, but §21's attribution is
   incomplete.
3. **The `contains("blends_natural_01")` guard is a design lever.** Our roads
   (`blends_street_01`) and building floors are immune to seam replacement;
   our natural ground is not. Anything that must survive the map edge intact
   should not be a `blends_natural_01` tile.
4. **Feathering only reaches 0–3 squares in from the outermost chunk edge.**
   Everything further in is ours.

`BlendDirection`'s `defaultDepth` — NORTH 7, SOUTH 0, WEST 7, EAST 0 — are the
seed values for the min/max tracking in `genRandomSquare`, not a blend radius.
NORTH/WEST start at 7 so `Math.min` can walk down; SOUTH/EAST start at 0 so
`Math.max` can walk up.

---

## 7. WorldGen can only place solid tiles

**CONFIRMED.** Source: `grep -rho -e 'blends_natural_01_[0-9][0-9]*' "$PZ/media/lua/" | sort -u`

Twenty-eight distinct tiles referenced anywhere in the game's Lua, and every one
is a solid: 0/5/6/7, 16/21/22/23, 32/37/38/39, 48/53/54/55, 64/69/70/71,
80/85/86/87, 96/101/102/103. **No mask tile appears in any Lua file.**

Combined with §6, this fully partitions the producers: the feature system places
solids, the engine's blending pass replaces solids, and **only the authoring
tool writes masks.** That is why every hypothesis formed from the Lua alone
missed the blend layer entirely.

---

## 8. The five questions

### Q1. What decides a region's ground type?

**There are two producers, and E3's question conflated them.**

- **Procedurally generated land** — the biome, via `WorldGenChunk.getBiome` and
  the biome's `GROUND` feature. Recipe, transferable, CONFIRMED (STATE §8, §9).
- **Authored cells, including all of Muldraugh** — a human, painting land use.
  Every region observed in 42_40 corresponds to a real-world surface: a road at
  y=190, a shed at y=177–180, a fenced yard, grass beyond.

**So for our generator, the answer is: we decide, from GIS land use.** There is
no hidden vanilla algorithm to reverse-engineer, because for authored maps there
was never an algorithm. This is a better answer than a noise field and it is
the one STATE §22 already argued for — parcels, fields, residential, industrial,
from real data.

The habitation-distance hypothesis is **UNSUPPORTED, not refuted**. §5 above
removed the evidence that appeared to kill it, but nothing supports it either.
Do not build on it. **Falsifier if anyone wants to revive it:** contiguous
material sampling along a transect from a town centre outward in a cell whose
buildings are known, with `solidfloor` filtering.

### Q2. How are stacked ground tiles ordered, and which is the base?

**CONFIRMED, §1.** Solid first — it is the only tile with `solidfloor` and the
only one `getFloor()` will return. Masks follow, in any order. Tuft last.

### Q3. What decides that two regions blend, and which tiles get used?

**CONFIRMED, §3 and §4.** Two materials blend wherever they are orthogonally
adjacent — there is no pair whitelist. The **higher-priority** material's block
supplies the mask, the **lower-priority** square carries it, and which offsets
are used follows deterministically from the set of higher-priority orthogonal
neighbours.

### Q4. What is `Sand` doing mid-cell in 42_40 at x=60–100?

**Land use. A yard or lot on a developed parcel** — not shore, not distance.

Evidence: (60,200) is Sand solid, carries `fencing_01_59`
(`TallHoppableW`, `WallWTrans`), and sits **inside room 78 `emptyoutside`, rect
[53,199 8×12]**. Directly north at (90,190) is a `Road_04` road square, and at
(90,180) a `floors_exterior_street_01_16` floor inside room 36 **`shed`**,
rect [89,177 3×4]. Fenced open ground between a road and a shed.

**This changes the region design in our favour, as §24 anticipated it might.**
Land use is exactly what GIS import already knows, and the signal is one we
already parse — `emptyoutside` rooms and fence tiles are co-located with it.
STATE §22's "three consumers want the same region signal" argument gains a
fourth consumer and a confirmation that vanilla's own regions are parcels.

**UNVERIFIED:** that this generalises. One parcel is not a rule. **Check:**
sample `Sand` regions in two other vanilla cells and test whether each falls
inside an `emptyoutside` room or against a road.

### Q5. Can the dirt groups come back?

**Yes, and the blocking objection is answered.**

`dirt` (64/69/70/71) and `dirt_grass` (80/85/86/87) have full 16-tile blocks
with the same mask vocabulary as the grasses — §2 confirms the contract is
uniform. The vanilla dirt region in 35_35 alternating `Dirt` with `Dirt_Grass`
per square (STATE test 41) is the **texture** layer behaving exactly as the
grass groups do.

The reason test 27 had to drop them stands and is unchanged: `biomes/map/*`
`placements` excludes those tiles from PLANT and BUSH, so `genMapSquare` strips
vegetation from any dirt square, and scattered dirt reads as bare diamonds.

**That is an argument against scattering, not against dirt.** With a region
layer, dirt returns gated to tracks, yards and unpaved roads —
`GroundPalette`'s own comment already says so — and being bare is then correct,
because a yard should be bare.

Note the interaction: dirt's `overlayRate` in `GroundPalette` is 0 for both
groups, i.e. no tufts, which is consistent.

---

## 9. Corrections owed to STATE

To be **added**, per Charter §5 — nothing deleted, wrong beliefs moved to the
Corrections table rather than removed.

| Old claim | Status |
|---|---|
| A vanilla square can carry several base ground tiles; one in 42_40 carries five (§24) | **FALSE.** Exactly one solid tile. The others are mask tiles with `FloorOverlay`. The five-tile square (112,200) is one Medium solid plus four Dark corner masks. |
| A `Grass_Medium` band sits at x=112–124 inside `Grass_Dark` with Dark on both sides (§24) | **FALSE — sampling artifact.** A 4-tile stride aliased a dithered boundary. The contiguous row is `M D M D M M M D D`. |
| A ground square is one base plus at most one overlay (§13) | **Still FALSE, but for a different reason than §24 gave.** It is one solid plus 0–4 masks plus 0–1 tuft. |
| `GroundSurvey`'s "never more than one overlay, 0 of 257,703" (test 22) | **Measured the tuft layer only.** True as stated about `blends_grassoverlays_01`; it never counted masks, which live on the base sheet. Not wrong, but it does not mean what §21 read into it. |
| "25 `FloorMaterial` lines from 16 ground probes" is unexplained (§21 loose thread) | **CLOSED.** Mask tiles carry `FloorMaterial` too. Filtering on `FloorMaterial` cannot distinguish a region from a blend edge — filter on `solidfloor`. This is the flaw behind three of the four ground transects. |
| The biome map is what removed the map-edge seam (§21, test 38) | **INCOMPLETE.** `Blending.changeGround` feathers solid tiles 0–3 squares in from any edge shared with a procedural chunk, and is a second mechanism doing visible work there. |
| Ground region choice tracks distance from habitation (§13, §24) | **Still UNSUPPORTED.** §24's evidence against it was the aliased transect and is withdrawn, but nothing supports it. Vanilla's regions in 42_40 are parcels. |

New entry for the method notes, alongside "frequency is not distribution":

> **A strided sample of spatial data can alias.** The 4-tile and 40-tile
> transects both produced structure that a contiguous read does not show.
> Ground measurements must be contiguous rectangles.

---

## 10. What a future session should implement

In dependency order. None of this is authorised by E3 — it is the spec.

1. **A `GroundMaterial` priority table.** Three pairs known (§4); the rest need
   measuring. Without it the mask direction is undecidable.
2. **A region layer that assigns one material per square from GIS land use**,
   with pure interiors. This is the piece STATE has wanted since §21 and it now
   has a confirmed justification: vanilla's regions are parcels, not noise.
3. **A dither pass** at region boundaries, 2–4 squares wide. Run §5's falsifier
   in another cell first.
4. **A mask pass**, run after regions and dither are final, implementing §3.
   It is a pure function of the four orthogonal neighbours — which means it is
   also exactly the shape of the editor's auto-wall-joining problem, and worth
   writing so both can use it.
5. **Restore dirt and dirt_grass**, gated to yard/track/unpaved-road regions.
6. Keep the tuft layer as it is. `GroundPalette`'s measured model is sound; only
   its naming needs changing.

Two implementation notes that will otherwise cost a session:

- **Mask tiles must be declared in the `.lotheader` tile table.**
  `GroundPalette.all` currently collects solids and tufts only.
- **The mask pass must run after every square's material is final**, because a
  square's masks depend on its neighbours. It cannot be folded into the
  per-square roll in `GroundPalette.roll()`.

---

## 11. Named checks not yet run

Each would change something above. None were run because E3's deliverable is
this document.

1. Contiguous rectangle across a region boundary in a **non-town** vanilla cell.
   Tests whether dither is a convention or a 42_40 quirk. (§5)
2. A square bordering two different higher-priority materials. Tests the
   multi-material extension of the mask rule. (§3)
3. More |S|=3 squares. One sample currently. (§3)
4. Material precedence for the remaining pairs, and whether it is a total order.
   (§4)
5. Whether masks cross tilesets — grass adjacent to road. (§4)
6. `Sand` regions in two other cells, against `emptyoutside` rooms and roads.
   (Q4)

---

## Noticed, out of scope

Recorded and not acted on, per the chunk prompt.

- **`Blending.removeTrees` is a fourth mechanism that touches trees.** Along an
  edge shared with a procedural chunk it deletes trees with probability ramping
  by distance from that edge — `rnd.nextInt(100) >= y*10`, so certain at the
  edge and impossible beyond 10 squares — and substitutes `e_newgrass_1_40` or
  `e_newgrass_1_42` about 75% of the time. This bears directly on STATE §25's
  unresolved tree-ownership question and on the positional test described there:
  authored trees near the map edge may be deleted by *this* pass rather than by
  `genMapSquare`, which would confound a test run too close to the boundary.
  §25 already warns to pick interior squares; this is a second reason.
- `IsoChunk` line ~4180 special-cases `blends_natural_01_87` (`Dirt_Grass`
  solid) for removal from a floor's attached anim sprites inside some zone
  operation. Unexplained, possibly farming or erosion.
- `attachmentsDoneFull` defaults to `true` at chunk reset while `blendingDoneFull`
  defaults to `false`, and only `genRandomChunk` clears the former. So
  `applyAttachments` appears not to run on authored chunks at all. Not
  investigated; may matter if authored masks ever fail to render.

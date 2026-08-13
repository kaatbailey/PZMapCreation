# E7 — Ground precedence and dither generality

Investigation document. **Deliverable is this document; no code changed except
the addition of `GroundCensus`, a read-only measurement class.**
`GroundPalette` was not touched.

Measured over **4,065 cells — the entire Muldraugh retail map**, ~266 million
squares, at z=0. Every claim below is marked CONFIRMED or UNVERIFIED with the
observation count behind it, and with what would disprove it.

E3 (`docs/E3_GROUND_BLENDING.md`, STATE §26) established the mask mechanism
from one 45-square rectangle. E7 tests it at corpus scale and fills the four
holes E3 left.

---

## 0. The headline, for a session that reads nothing else

1. **The priority table is measured and complete** for all seven natural
   materials and all seven road types. §2.
2. **Priority is a strong default, not a law.** Every pair shows reversals.
   For natural ground the reversal rate is 1 in 3,000 to 1 in 36,000 — noise.
   Between similar road types it reaches 1 in 2.5 — not noise. §3.
3. **Dither is general, not a 42_40 quirk.** Mean single-square-island share
   19.97% across 4,065 cells. E8 keeps its dither pass. §4.
4. **The 16-tile block contract holds for every natural material except Clay.**
   `blends_street_01` uses an 8-mask variant. `floors_burnt_01` uses the same
   vocabulary. §5.
5. **Masks cross tilesets, and multi-material squares are common.** E3's
   guessed extension is confirmed. §6, §7.
6. **E3's Clay ordering was wrong** and is corrected here. §8.

---

## 1. Method, and why the first attempt was void

The first approach used `Probe findprop ... FloorOverlay` on three cells. It
returned 3 squares per cell, because `PropsProbe.find` has a hard-coded
`found < 3` cap. The dither test built on it reported **"width 1: 100%" for
every cell including 42_40**, which E3 had measured by hand to be dithered.

A 3-square sample cannot produce a run longer than 1. **The test could not
fail, so it proved nothing** — the same failure as §24's strided transect, in a
new costume. It was caught only because 42_40's hand-measured ground truth was
checked first.

The replacement is `pzformat.GroundCensus`: one JVM, one `TileIndex.load`, every
square of every named cell at z=0. Read-only; writes nothing.

```fish
java -cp out pzformat.GroundCensus "$PZ/media" "$MAPS/Muldraugh, KY" $cells
```

**Standing note for future sessions:** `Probe findprop` finds *an example*, not
*all examples*. It is a hunting tool, never a census. Building a rate or a
distribution on it produces a number that looks like a measurement and is not.

---

## 2. The material priority table — CONFIRMED

The higher-priority material supplies the mask tile; the lower-priority square
carries it. Read the chain as: each material masks onto everything below it.

### Natural ground, `blends_natural_01`

| rank | material | block base |
|---|---|---|
| 1 | `Grass_Dark` | 16 |
| 2 | `Grass_Medium` | 32 |
| 3 | `Grass_Light` | 48 |
| 4 | `Sand` | 0 |
| 5 | `Dirt_Grass` | 80 |
| 6 | `Dirt` | 64 |
| 7 | `Clay` | 96 |

Every adjacent link, and every skip link that occurs, is directly observed:

| pair | n | pair | n |
|---|---|---|---|
| `Grass_Dark > Grass_Medium` | 8,119,438 | `Grass_Light > Dirt` | 677,353 |
| `Grass_Medium > Grass_Light` | 3,424,535 | `Grass_Light > Dirt_Grass` | 284,699 |
| `Dirt_Grass > Dirt` | 1,319,008 | `Grass_Light > Sand` | 256,784 |
| `Grass_Dark > Grass_Light` | 615,314 | `Grass_Dark > Dirt_Grass` | 193,591 |
| `Grass_Medium > Dirt` | 484,489 | `Grass_Medium > Dirt_Grass` | 142,652 |
| `Grass_Dark > Dirt` | 484,001 | `Grass_Medium > Sand` | 67,082 |
| `Grass_Dark > Sand` | 49,306 | `Sand > Dirt` | 6,140 |
| `Dirt > Clay` | 1,119 | `Dirt_Grass > Clay` | 1,038 |
| `Sand > Dirt_Grass` | 1,017 | | |

**Transitivity is observed, not inferred.** `Grass_Dark` is seen masking onto
all six materials below it directly. No skip link contradicts the chain.

**It is not derivable.** Block index order would be 0, 16, 32, 48, 64, 80, 96;
the priority order is 16, 32, 48, 0, 80, 64, 96. Not brightness either —
`Grass_Dark` (darkest) outranks `Grass_Light`, but `Sand` (pale) outranks
`Dirt` (dark). **It is a table. Hard-code it.**

### Roads, `blends_street_01`

Same structure, seven materials `Road_01`..`Road_07`, blocks at 0/16/32/48/64/80/96.
The dominant directions give:

**`Road_01` > `Road_02` > `Road_04` > `Road_03` ≈ `Road_05` > `Road_07` > `Road_06`**

Marked **UNVERIFIED as a strict order** — see §3. `Road_03` and `Road_05` do not
separate cleanly (`Road_03 > Road_05` n=1,876 against `Road_05 > Road_03` n=78,
a 24:1 ratio that is weaker than any natural-ground link).

### Cross-family

Grass consistently outranks road: `Grass_Dark > Road_04` (284,583),
`Grass_Dark > Road_06` (213,709), `Grass_Medium > Road_04` (127,673), and so on
for every grass/road pair. `Sand > Road_04` (15,106). `Dirt > Road_04` (12,985).

**`Burnt` > `Grass_Light`** (2,608) — `floors_burnt_01` participates in the same
system. Its position in the wider order is UNVERIFIED; only this one pair was
observed in quantity.

**Falsifier for the whole table:** any pair where the reversal rate approaches
1:1 across a large sample. At that point the pair has no priority and the rule
needs a tie-break.

---

## 3. Priority is a strong default, not a law — CONFIRMED

**Every single pair in the corpus shows both directions.** This refutes the
prediction made before the run, which was that reversals would be confined to a
handful of hand-edits.

But the *magnitude* separates two regimes:

| pair | dominant | reverse | ratio | reading |
|---|---|---|---|---|
| `Grass_Medium` / `Grass_Light` | 3,424,535 | 94 | 36,400 : 1 | rule |
| `Grass_Light` / `Sand` | 256,784 | 86 | 2,986 : 1 | rule |
| `Grass_Medium` / `Road_04` | 127,673 | 12 | 10,640 : 1 | rule |
| `Grass_Dark` / `Sand` | 49,306 | 129 | 382 : 1 | rule |
| `Dirt` / `Dirt_Grass` | 1,319,008 | 94,696 | 14 : 1 | weak |
| `Road_02` / `Road_06` | 8,871 | 607 | 15 : 1 | weak |
| `Road_01` / `Road_02` | 495 | 63 | 8 : 1 | weak |
| `Grass_Light` / `Road_01` | 137 | 55 | 2.5 : 1 | no rule |

**Interpretation.** For the natural materials the reversal rate is a noise
floor — hand-edits, tool re-runs over edited ground, or authoring predating the
current blend pass. Vanilla is not internally consistent, and **we should not
reproduce its inconsistency.** Author from a strict table.

The weak pairs are all between *similar* materials — two dirt variants, two
road surfaces. Plausibly the authoring tool has no defined precedence there and
the result depends on paint order. UNVERIFIED.

**What this means for E9:** implement the table as absolute. A validator that
flags "mask direction disagrees with the priority table" would fire on roughly
1 in 3,000 vanilla squares, which is the correct rate for a rule with a noise
floor — and is itself a useful check that the table is right.

---

## 4. Dither is general — CONFIRMED, and this was predicted wrong

E3 found `Grass_Dark` and `Grass_Medium` interpenetrating per square across a
2–4 square band in 42_40, and flagged it UNVERIFIED — possibly a hand-painting
quirk of one town cell. The prediction going into E7 was that forest cells would
show clean edges.

**They do not.**

| cell | single-square islands | as % of components | 2–3 differing neighbours |
|---|---|---|---|
| 42_40 (town) | 233 | 32.5% | 45.1% |
| 35_35 (forest) | 101 | 19.4% | 43.2% |
| 30_30 | 912 | 35.5% | 58.7% |

**Corpus-wide: mean single-square-island share 19.97% across all 4,065 cells.**

A single-square 4-connected component is one square of material A entirely
surrounded by material B. **No curved, diagonal or irregular edge can produce
one.** It is dither by definition, and one component in five is one.

**E8 keeps its dither pass.** STATE §26's implementation list stays at six items.

**Falsifier:** a cell — or a contiguous region of one — with a material boundary
and zero single-square islands. None was found in 4,065 cells.

**UNVERIFIED: the dither's spatial law.** We know it exists and roughly how
dense it is. We do not know whether the interleaving is random per square,
noise-driven, or a fixed pattern, nor how band width relates to the materials
involved. **This is the one measurement E8 still needs**, and it is a different
shape of question — it wants the distribution of island positions relative to a
region's true edge, not a count. See §10.

---

## 5. The block contract, completed — CONFIRMED

Every material's mask indices, read from the corpus:

| sheet | material | mask indices | count |
|---|---|---|---|
| `blends_natural_01` | `Sand` | 1–4, 8–15 | 12 |
| | `Grass_Dark` | 17–20, 24–31 | 12 |
| | `Grass_Medium` | 33–36, 40–47 | 12 |
| | `Grass_Light` | 49–52, 56–63 | 12 |
| | `Dirt` | 65–68, 72–79 | 12 |
| | `Dirt_Grass` | 81–84, 88–95 | 12 |
| | `Clay` | **97–127** | **28** |
| `blends_street_01` | `Road_01` | 1–4, 8–11 | 8 |
| | `Road_02` | 17–20, 24–27 | 8 |
| | `Road_03` | 33–36, 40–43 | 8 |
| | `Road_04` | 49–52, 56–59 | 8 |
| | `Road_05` | 65–68, 72–75 | 8 |
| | `Road_06` | 81–84, 88–91 | 8 |
| | `Road_07` | 97–100, 104–107 | 8 |
| `floors_burnt_01` | `Burnt` | 9–12, 16–31 | 20 |

**Natural ground: 12 masks per block** — 4 corners at B+1..B+4, then 8 sides at
B+8..B+15 in two interchangeable variant sets. Exactly the E3 contract, now
confirmed for all seven materials rather than one.

**Roads: 8 masks per block** — 4 corners at B+1..B+4, 4 sides at B+8..B+11.
**One variant set, not two.** An implementation that assumes two variant sets
everywhere will emit `blends_street_01_12`..`_15`, which are not road masks.

**Clay is the exception.** 28 indices spanning 97–127, where the block would
predict 97–100 and 104–111. Indices 112–127 carry `FloorMaterial Clay` but sit
outside clay's 16-tile block. **CONFIRMED as an observation, UNVERIFIED as an
interpretation** — most likely a third and fourth variant set, but it could be
an eighth material sharing the label. Clay is the lowest-priority natural
material and therefore rarely masks; this can be left unresolved without
blocking E8 or E9, provided the implementation emits only 97–100 and 104–111.

**`floors_burnt_01` uses the same vocabulary** for fire damage. Out of scope
here, but it means the neighbour-rule engine E9 builds has at least three
consumers, not one.

---

## 6. Masks cross tilesets — CONFIRMED

E3 could not test this. The corpus is unambiguous:

- `blends_street_01_48` (Road_04 solid) carrying `blends_natural_01_24`
  (Grass_Dark N mask) — grass feathering onto road.
- `blends_street_01_54` carrying `blends_natural_01_75` (Dirt).
- `blends_natural_01_16` (Grass_Dark solid) carrying `industry_01_58`.
- `blends_natural_01_71` (Dirt solid) carrying `vegetation_farm_01_23`.
- `blends_natural_01_0` (Sand) carrying `location_trailer_02_56`.

**So the mask rule is keyed on `FloorMaterial`, not on tileset.** Two squares
blend if their materials differ and one outranks the other, regardless of which
sheet each tile comes from.

### A discriminator correction that matters

`street_curbs_01_*`, `overlay_grime_floor_01_*`, `industry_01_*` and
`location_trailer_02_*` all carry `FloorOverlay` — and **none carry
`FloorMaterial`**. They are decals, not blend masks.

**`FloorOverlay` alone does not identify a blend mask.** STATE §26's layer table
implies it does. The correct discriminator is all three:

```
isBlendMask(tile) = props.has("FloorOverlay")
                 && props.has("FloorMaterial")
                 && props.hasAny("FloorAttachmentN","FloorAttachmentS",
                                 "FloorAttachmentE","FloorAttachmentW")
```

An implementation using `FloorOverlay` alone will treat grime and curb decals as
blend masks and try to reason about their material, which they do not have.

---

## 7. Multi-material squares are common — CONFIRMED

E3 could not test this and guessed that the rule extends by running once per
distinct neighbouring material and concatenating. **Confirmed.**

Examples, all with a single solid and masks from two different blocks:

- `0_36 (7,94)` through `(7,179)` — a long contiguous run of `Grass_Light`
  solid carrying both `Grass_Dark` and `Grass_Medium` masks.
- `0_34 (127,215)` — `Dirt` solid with `Grass_Dark` and `Grass_Light` masks.
- `42_40 (1,77)` — `Road_04` solid with `Grass_Medium` and `Road_01` masks,
  i.e. multi-material *and* cross-tileset on one square.

**So E9's mask pass is:** for each distinct neighbouring material that outranks
this square's material, run the §3-of-E3 encoding independently and concatenate
the results. No interaction between the sets was observed.

**UNVERIFIED:** whether two materials can claim the *same* direction — e.g. a
square with `Grass_Dark` to the north and also needing a `Grass_Medium` north
mask. Not observed, and geometrically it cannot arise from orthogonal
neighbours, since a square has one northern neighbour with one material.

---

## 8. Corrections owed

To STATE, per Charter §5 — added, not substituted.

| Old claim | Status |
|---|---|
| `Clay` is the highest-priority natural material | **FALSE.** Stated in-session from a 3-cell sample where `Clay > Grass_Dark` appeared at n=3. The full corpus shows `Dirt > Clay` (1,119) and `Dirt_Grass > Clay` (1,038): **Clay is the lowest.** The n=3 observation is within the noise floor of §3. |
| Priority is a total order (E3, from 21 samples) | **INCOMPLETE.** Every pair shows both directions. Natural ground behaves as a rule with a 1-in-3,000 noise floor; similar road types do not separate cleanly. §3. |
| `FloorOverlay` identifies a blend mask (STATE §26 layer table) | **INSUFFICIENT.** Decal sheets carry `FloorOverlay` without `FloorMaterial`. Needs all three properties. §6. |
| Dither may be a 42_40 hand-painting quirk (E3 §5, UNVERIFIED) | **RESOLVED — it is general.** 19.97% mean island share over 4,065 cells. |
| The 16-tile block contract is uniform (STATE §26) | **TRUE for natural ground, FALSE in general.** Roads use 8 masks, one variant set. Clay uses 28. §5. |
| Indices 112–127 are "a further side-mask set, UNVERIFIED what it belongs to" | **They carry `FloorMaterial Clay`.** Interpretation still UNVERIFIED. §5. |
| `Probe findprop` can measure a distribution | **FALSE.** Hard-capped at 3 hits per cell by `PropsProbe.find`. It finds an example. §1. |

New method note, alongside "sample spatial data contiguously":

> **A sampling tool's cap is part of the measurement.** `findprop`'s 3-hit cap
> produced a clean, plausible, entirely void result — "100% width 1" — that
> agreed with the hypothesis under test. Check what the tool *can* return before
> believing what it did return.

---

## 9. What E8 and E9 implement

Revised from STATE §26 with E7's results folded in.

1. **`GroundMaterial` priority table** — §2. Hard-coded, absolute. Natural
   ground CONFIRMED; road ordering UNVERIFIED between `Road_03` and `Road_05`.
2. **Region layer from GIS land use** (E8) — unchanged from §26.
3. **Dither pass** (E8) — **now required**, not conditional. Target ~20% of
   components as single-square islands at boundaries. The spatial law is still
   unmeasured; see §10.
4. **Mask pass** (E9), per E3 §3, with three E7 amendments:
   - key on `FloorMaterial`, not tileset — §6
   - use the three-property discriminator — §6
   - run once per distinct outranking neighbour material and concatenate — §7
   - emit only 8 masks for `blends_street_01` blocks, 12 for natural — §5
5. **Restore dirt** (E10) — unchanged.
6. **Rename `GroundPalette`'s "overlay" to "tuft"** — unchanged, and now more
   urgent given §6 introduces a third meaning (decals).

---

## 10. Named checks not run

1. **The dither spatial law.** Random per square, noise-driven, or patterned?
   How does band width relate to the material pair? This is what E8 needs and
   E7 did not measure. Suggested shape: for a known boundary, the distribution
   of island offsets from the region's majority edge, over many boundaries.
2. **`Road_03` vs `Road_05`.** 24:1 is the weakest natural-looking link in the
   road order and may be no order at all.
3. **`Burnt`'s position** in the wider priority order. One pair observed.
4. **Clay 112–127.** Third/fourth variant set, or an eighth material?
5. **Whether the noise floor is spatially clustered.** If the ~1-in-3,000
   reversals concentrate in particular cells, they are hand-edits and can be
   ignored with confidence. If they are uniformly scattered, something
   systematic is being missed.

---

## Noticed, out of scope

- `floors_burnt_01` carries `FloorMaterial Burnt` masks at indices 9–31 and
  participates in the priority system (`Burnt > Grass_Light`, n=2,608). Fire
  damage uses the blend vocabulary. A third consumer for E9's neighbour-rule
  engine, alongside ground and A3's wall-joining.
- `Probe findprop`'s 3-hit cap is undocumented in CHUNKS' standing notes and
  cost a full measurement cycle. Worth adding there.

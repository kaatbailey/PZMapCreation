# pzformat — Chunk index and prompts

Companion to `CHARTER.md` and `STATE.md`. This file holds the work breakdown and
the ready-to-paste prompt for each chunk.

---

## How to run a chunk

1. Open a new session.
2. Paste, in this order: **`CHARTER.md`**, then **`STATE.md`**, then **the one
   chunk prompt** you are working on, then **the `FINDINGS` block from the
   chunk you just finished** if it is listed as an input.
3. Work the chunk. Do not start the next one.
4. At the end, the session writes a `FINDINGS` block in the format at the bottom
   of this file.
5. You fold the findings into `STATE.md` — adding, never deleting — and tick the
   chunk here.

**Never paste more than one chunk prompt.** A session that can see three chunks
will half-do all three and hand you back something none of them defined as done.

**Why prompts are short and the charter is long:** the charter is the part that
must survive; the prompt is disposable. If a future session only reads one
document, it should be the charter.

---

## Chunk index

Status: `[ ]` not started · `[~]` in progress · `[x]` done · `[!]` blocked

### Track A — Library. Architecture-independent, useful whatever the UI becomes.

| | Chunk | Depends on | Deliverable |
|---|---|---|---|
| `[x]` | **A1** Verify `outlineRoom` wall placement | — | **CLOSED 2026-08-10. Offsets CORRECT** (STATE §18) |
| | **A2** Remove superseded vegetation code | — | **RE-SCOPED into A2a/A2b/A2c** (STATE §20) |
| `[!]` | **A2a** Delete `TreeScatter` / `TreePalette` | A2-gate | **BLOCKED** — tree ownership unresolved (STATE §25) |
| `[x]` | **A2b** Stop writing `WorldGenOverride.lua` | — | **Confirmed inert in game 2026-08-11.** Write still to be deleted |
| `[ ]` | **A2c** Authored trees vs engine biome vegetation | A2-gate | Open question, not cleanup |
| `[ ]` | **A2-gate** Settle tree ownership | — | **A written decision.** Positional test in game |
| `[ ]` | **A3** Auto wall-joining | A1, A3-pre1, A3-pre2, E9 | `WallJoin` + tests. **Inherits E9's neighbour-rule engine** — do not write a second one |
| `[ ]` | **A3-pre1** Fix `edgeOf` decoration fallback | — | Small. `attachedN` proxy is reachable via a public method |
| `[ ]` | **A3-pre2** Confirm tileset variant cycle | — | Small. Wall-joining picks by position, not flags alone |
| `[ ]` | **A4** Validation rule engine | A1, A3 | `Validator` + rule set. **Must work at 1×N** (STATE §19) |
| `[ ]` | **A5** TMX read/write | A1 | Interop, checked against Unjammer corpus |
| `[ ]` | **A6** `.tiles` writer | — | Writer + round-trip |
| `[ ]` | **A7** `objects.lua` read/write | — | Parser, writer, room-type link |

### Track B — Buildings. Evidence first, then a decision, then code.

| | Chunk | Depends on | Deliverable |
|---|---|---|---|
| `[ ]` | **B1** Vanilla house anatomy | — | **A document. No code.** |
| `[ ]` | **B2** `StaticModule.prefab` decision gate | B1 | **A written decision.** |
| `[!]` | **B3** Room decomposition | B2 | Blocked — cannot be written until B2 resolves |
| `[!]` | **B4** Openings: doors and windows | B3 | Blocked |
| `[!]` | **B5** Roofs | B3 | Blocked |
| `[ ]` | **B6** Room typing and loot tables | B1, A7 | Named room types in output |

### Track C — Application. Local single-user desktop editor.

| | Chunk | Depends on | Deliverable |
|---|---|---|---|
| `[ ]` | **C1** Architecture decision gate | — | **A written decision.** |
| `[!]` | **C2** Working store and project format | C1 | Blocked |
| `[!]` | **C3** Interactive viewport | C1, C2 | Blocked |
| `[!]` | **C4** Tool layer: brushes, selection, undo UI | C3, A3 | Blocked |
| `[!]` | **C5** Shell: panels, tile picker, validation panel | C4, A4 | Blocked |

### Track D — Sustaining.

| | Chunk | Depends on | Deliverable |
|---|---|---|---|
| `[ ]` | **D1** Fast round-trip regression | — | Sub-minute suite |
| `[ ]` | **D2** Publish B42 format documentation | — | Public doc |
| `[ ]` | **D3** GIS licensing review and project licence | — | Decision |

### Track E — GIS pipeline. Charter §2: worth building only when it teaches the editor something, or is nearly free.

| | Chunk | Depends on | Deliverable |
|---|---|---|---|
| `[x]` | **E1** Write zombie density into `chunkGrid` | — | **DONE 2026-08-11, confirmed in game** (STATE §23) |
| `[ ]` | **E2** Calibrate density values | E1 | Values by occupancy class, not one constant |
| `[x]` | **E3** Ground blending investigation | — | **CLOSED 2026-08-13. Mechanism CONFIRMED** (STATE §26, `docs/E3_GROUND_BLENDING.md`) |
| `[ ]` | **E4** Scene rotation pass | §17 (resolved) | Footprints axis-aligned before rasterizing |
| `[ ]` | **E5** `FootprintSnap` | E4 | **Shared with the editor** — refuses off-axis footprints |
| `[ ]` | **E6** Extract `BiomeMapWriter` distance banding | — | Small. Three consumers want a region signal |
| `[ ]` | **E7** Ground precedence and dither generality | E3 | **A document. No code.** Priority table + non-town rectangle |
| `[ ]` | **E8** Region layer from GIS land use | E7 | Regions with pure interiors |
| `[ ]` | **E9** Mask pass | E7, E8 | **Shared with A3** — neighbour-rule engine, ground is its first consumer |
| `[ ]` | **E10** Restore dirt, gated to yards and tracks | E8 | Reverses test 27's symptom fix |

**E9 is the second charter §2 test, and E5 the first:** a GIS feature the editor needs
regardless of who authors the tiles. E4 exists to serve it.

### Suggested order

**A1 → A2 → B1 → B2 → C1**, then the tracks open up in parallel. A1 first
because room geometry is load-bearing for everything in B and C. A2 second
because it is cheap, the evidence is already in hand, and it removes two files
a future session would otherwise have to reason about.

**AMENDED 2026-08-11.** A1 is closed. A2 turned out not to be cheap — its
premise is contested and A2a is blocked on a question nobody had asked. The
order is now:

**AMENDED 2026-08-13.** E3 is closed. It found a confirmed mechanism rather
than a fourth hypothesis, and left one measurement blocking everything
downstream: the material priority table. The order is now:

**E7 → A2-gate → B1 → B2 → C1**, with A3-pre1/pre2 and E6 available as small
fillers at any point.

**E3 first** because ground appearance is the most immersion-breaking defect
on the generated map (owner, 2026-08-11) and because it is an investigation,
not a build — the same shape as B1, which the charter's method section says
should come before code.

**A2-gate second** because it is cheap, it unblocks or kills A2a, and leaving
a blocked chunk in the index invites a future session to start it anyway.

---

## Shared preamble

Every prompt below assumes this, and every prompt written later must include it:

> You have been given `CHARTER.md` and `STATE.md`. Work **only** the chunk
> below. If you find something that belongs to another chunk, record it in
> FINDINGS under "Noticed, out of scope" and do not act on it.
>
> Do not rewrite `CHARTER.md`. Do not delete anything from `STATE.md`.
>
> Before writing code: state your approach, name the check that would prove it
> wrong, and run that check. Patches are delivered as Python scripts that abort
> unless each anchor matches exactly once. The shell is fish — no heredocs. Run
> everything from `~/Documents/PZMapCreation`.
>
> **The owner runs every command.** You have no access to the repo, the PZ
> install, or the game. Give exact commands with real paths — angle-bracket
> placeholders have twice been read by fish as redirections.

### Standing environment notes

Added 2026-08-11 after each of these cost real time.

### GREP RULE — no exceptions

**`grep` is aliased to `ugrep`.** Write every pattern with its own `-e`.
Always, even for a single pattern. Never write `\|` in a grep pattern.

```fish
# CORRECT — one -e per pattern
grep -rn -e chunkGrid -e GRID_BYTES src/main/java/pzformat/

# WRONG — ugrep is POSIX-strict, so this searches for the literal
# string "chunkGrid|GRID_BYTES" and silently finds nothing
grep -rn 'chunkGrid\|GRID_BYTES' src/main/java/pzformat/
```

**Why this is a rule and not a note.** The failure is silent — an empty result
looks exactly like "the symbol is not there." On 2026-08-11 this produced two
false negatives that sent a session down the wrong path, once concluding a
class did not reference symbols it plainly did. The session then wrote a
warning about it into two documents and emitted `\|` again twice in the same
sitting. Knowing the hazard does not prevent it; using `-e` unconditionally
does.

**Also:** `ugrep` aborts the entire command if any named file is missing, so
one guessed filename kills the search of the files that do exist. Name files
you have confirmed exist, or search a directory.

Same class as the `ls`/eza gotcha in STATE §5.

- **The two GIS commands are in STATE §6.** `gisimport` writes a schematic
  PNG; `giscells` writes the mod. Not interchangeable. They were recovered
  from shell history three times in one session before being written down.
- **`Probe` argument shapes vary.** `lotheader` takes a *file*; `square`,
  `findprop` and `roomgeom` take a media dir *plus* a map dir.
- **`Probe survey` takes ~90s on Muldraugh** and, piped through `grep`,
  prints nothing until it finishes. Warn before long runs; it is not hung.
- **In-game tests need a fresh world**, not a resumed save — spawn and chunk
  data get baked for territory already visited.

---

# Track A prompts

## A1 — Verify `outlineRoom` wall placement  ✅ CLOSED 2026-08-10

> **RESULT: the offsets are CORRECT.** South wall sits at `ry+rh`, east at
> `rx+rw` — the next square out, as `outlineRoom` assumed. Two independent
> lines: vanilla measurement across 86 rooms in 42_40 (south `ry+rh` 67.0%
> vs `ry+rh-1` 10.2%, a 6.6x margin; east 83.9% vs 3.7%, 22.4x), and source
> inspection of `CellEditor.outlineRoom`. Worked example
> `weldingworkshop [57,169 12x12]` matched all four corners including the
> empty far corner at `(rx+rw, ry+rh)`. Full detail in STATE §18.
>
> **The instrument already existed.** `Probe roomgeom` had made the
> measurement and STATE §10 recorded it; the open thread in §2 was stale
> when written. *Check what this project already does, not only what vanilla
> does.*
>
> **A3, A4 and A5 are unblocked.**

The original prompt follows, kept so a later session can see what was asked.

**Why this is first.** `outlineRoom` places north walls at `y0+h` and west walls
at `x0+w`, the far edges belonging to the *next* square out. That follows from
edge-based walls, but it is **reasoning, not measurement** — and unmeasured
reasoning is what produced the x/y transposition and the `attachedN` bug, two of
the eight bugs that got through the test suite. Every building and every editor
room-drawing tool sits on this.

**Scope.** Take real rooms from vanilla Muldraugh. Read their actual wall
positions off the map data. Compare against what `outlineRoom` generates for the
same rectangle. Fix it or confirm it.

**Method.**
- Use `Probe square` and `Probe findprop` against `"$MAPS/Muldraugh, KY"`.
  `RoomGeometry` already measured wall offsets across 86 rooms — reuse that
  rather than re-deriving it, but treat its conclusion as the hypothesis under
  test, not as the answer.
- Predict the wall coordinates before dumping them.
- Test more than one room shape. A square room can be right by accident where an
  L-shape or a 1-tile-wide room is wrong.
- Test a room on a cell boundary if one exists — that is where an off-by-one
  becomes a cross-cell bug.

**Definition of done.** For at least five vanilla rooms of differing shapes, the
walls `outlineRoom` would generate match the walls actually present, or the
discrepancy is characterised exactly and fixed. A self-test encodes the result.

**Falsification.** If your check cannot distinguish "walls at `y0+h`" from
"walls at `y0+h-1`", it is not a check. Say what output each hypothesis predicts
before you look.

**Non-goals.** Do not build room *creation* UI, do not touch building
generation, do not refactor `RoomGeometry`.

---

## A2 — Remove superseded vegetation code  ⚠ RE-SCOPED 2026-08-10/11

> **Do not run this prompt as written.** Investigation split it into three
> pieces that are not interchangeable, and one of them is blocked. See
> A2a / A2b / A2c / A2-gate below. STATE §20 and §25.
>
> **What went wrong with the original scoping.** The prompt assumed the two
> deletions were independent and both safe. Neither held:
>
> - `BiomeMapWriter` depends on `TreeScatter.distanceToStructure`, which is
>   grid geometry rather than vegetation placement. Deleting the class
>   outright breaks the biome map — the thing that most recently worked.
> - Whether the engine actually discards our authored trees is **not
>   established**, and two findings point against it.
>
> A session on 2026-08-10 ran `grep`, found live callers, and wrongly
> concluded A2's premise was false; "superseded" means the engine discards
> the output, not that the code is unreachable. A different session then
> wrote the opposite claim into STATE §21 and had to retract it. **Both
> errors came from reasoning about the engine instead of observing it.**

The original prompt follows, kept for the record.

**Context.** `TreeScatter` and `TreePalette` place ~7,800 trees that
`genMapSquare` deletes on load (STATE §9 — the engine owns TREE/BUSH/PLANT and
replaces them per tile from the biome map). `WorldGenOverride.lua` is still
written and is superseded by the biome map on authored chunks.

**Scope.** Delete both, stop writing `WorldGenOverride.lua`, regenerate, and
**prove in game that nothing changed.**

**Method.** The proof is the point, not the deletion. Before deleting, predict
what the square dump and the in-game view will show afterwards. Then:

- Regenerate the mod and diff the square dumps before/after. Authored trees
  should be the only difference.
- Load in game and check vegetation, the biome gradient, and the cell boundary
  seam. All three should be unchanged.

**Definition of done.** Both classes gone, `WorldGenOverride.lua` no longer
written, self-tests pass, and an in-game observation recorded confirming the
gradient and seam are unchanged.

**Falsification.** If in-game vegetation *does* change, the belief in STATE §9
is wrong and that is a far more valuable result than the cleanup. Record it in
the Corrections table and stop.

**Non-goals.** Do not improve the biome map while you are in there. Do not touch
`GroundPalette`.

---

## A2-gate — Settle tree ownership

**Deliverable is a written decision.** One in-game observation. No code.

**Why this exists.** A2a rests on the claim that the engine discards our
authored trees. That claim is not established, and A2a would delete the code
that places 7,797 of them.

**Evidence for** (STATE §9, §11): `genMapSquare` deletes and replaces
TREE/BUSH/PLANT per tile on load, and the engine substitutes species art for
our generic `vegetation_trees_01_*` tiles — so beautiful varied trees in game
are compatible with our writing generic ones.

**Evidence against** (STATE §25): `BiomeMapWriter`'s own scope note says
WorldGen only generates chunks where `hasEmptySquaresOnLevelZero()` is true,
and `GisCells` fills every square — so WorldGen may never run on our chunks.
And our tree tiles are demonstrably in the lotpack.

These are not necessarily contradictory: `genMapSquare` and WorldGen chunk
generation are different mechanisms and one could run while the other does
not. That ambiguity is what `BiomeMapWriter` itself flags as UNVERIFIED.

**Method.** Positional. Get authored tree coordinates from the generated
cell, convert to world coordinates, walk that line in game.

```fish
java -cp out pzformat.Probe findprop "$PZ/media" \
    ~/Zomboid/mods/PZGisImport/common/media/maps/PZGisImport 200_200 tree
```

World coordinate for cell 200_200 local (x,y) is `(51200 + x, 51200 + y)`.
**Use interior squares — cell-local x roughly 120–180.** A first attempt used
local x=0, which is world x=51200 and sits on the map edge.

**Definition of done.** One of three answers, recorded with the coordinates
checked:

- Trees at exactly those coordinates, bare ground between → **positions are
  ours. A2a is killed** and `TreeScatter` is load-bearing.
- Trees along the line at unrelated positions → **the engine re-scatters.
  A2a proceeds.**
- Dense forest everywhere → the engine adds on top of ours; say so and
  propose a different test rather than guessing.

**Falsification.** Predict which of the three you expect before looking, and
say why. Note that 7,797 trees over four cells is ~3% of squares, and the
forest in the 2026-08-11 screenshots looks denser than that — canopy overlap
could explain it, or the engine adding trees could.

**Non-goals.** Do not delete anything. Do not touch `GroundPalette`.

---

## A2a — Delete `TreeScatter` and `TreePalette`  🚫 BLOCKED

**Blocked on A2-gate.** Do not start.

When unblocked, note that the deletion is **not wholesale**:
`BiomeMapWriter` calls `TreeScatter.distanceToStructure`, which is grid
geometry. Extract it to a helper first — that move should be byte-identical
on its own, which makes it a separately provable step. Proof mechanism is
hash-before/hash-after plus an in-game check; the lotpacks are *expected* to
differ, so the claim under test is "the loaded map is identical", not "the
output is identical".

---

## A2b — Stop writing `WorldGenOverride.lua`  ✅ CONFIRMED INERT 2026-08-11

> **RESULT: the file does nothing.** Moved out of the generated mod
> directory and loaded in game: **no seam, foliage flows cleanly across the
> boundary** — dense mixed forest one side, open grass with saplings the
> other, trees crossing coherently. The biome map is doing that work.
>
> **Remaining:** delete the write at `GisCells:220` and the
> `writeWorldGenOverride` method. Trivial, and safe on this evidence.

---

## A2c — Authored trees vs engine biome vegetation

Open question, not cleanup. Depends on A2-gate.

Biomes drive vegetation in engine-generated terrain, and we also write tree
tiles into authored cells. Do both target the same squares? If the engine
populates authored cells too, vegetation is being decided twice by different
rules, and which one wins matters before the editor authors vegetation
deliberately. Full prompt when A2-gate resolves.

---

## A3 — Auto wall-joining

**Depends on A1** (closed) **and on A3-pre1 and A3-pre2.** Both prerequisites
are small and both are upstream of the join algorithm — cheaper to do first
than to discover halfway through.

> **The wall vocabulary is CONFIRMED** (STATE §19) and A3 can rely on it:
>
> | Flag | Role | `edgeOf` |
> |---|---|---|
> | `WallW` / `WallN` | west / north edge | WEST / NORTH |
> | `WallNW` | corner, both segments on one square | BOTH |
> | `WallSE` | **pillar/post** (`PaintingType = pillar`), owns no edge | NONE |
> | `WindowN/W`, `DoorWallN/W` | openings in a wall | NORTH / WEST |
>
> **No diagonal wall primitive exists.** `Facing` is an OBJECT property
> (N/S/E/W, ~6,200 tiles); no wall tile carries it. Objects have four
> facings; walls have two edges plus a corner and a post.

### A3-pre1 — Fix the `edgeOf` decoration fallback

`TileIndex.edgeOf`'s final block falls back to `attachedN`/`attachedW` for
tiles with no `Wall*` flag — **the exact proxy its own comment warns
against**, and the one that validated at 99.5% while being wrong (STATE §11).
It is unreachable from `wallOn`, which gates on `isStructuralWall` first, so
A1's measurement is unaffected. But `edgeOf` is public and A3 will call it on
neighbours, where a grime overlay would report `Edge.NORTH`.

Return NONE for attached-only tiles, or split the fallback into a separate
`decorationEdge()` so a caller has to ask for it deliberately.

### A3-pre2 — Confirm the tileset variant cycle

The per-tile flags say *which edge*; they do not say corner vs end vs
junction. In `walls_exterior_house_01` the observed pattern is
`WallW, WallN, WallNW, WallSE` every 4, with openings every 16. Confirm that
cycle holds across other tilesets before A3 designs around it — if it is a
per-tileset accident rather than a convention, the join algorithm needs a
different input.

**Context.** All the information needed is already present: `TileIndex` and
`Square` resolve walls, and `.tiles` carries facing. What is missing is choosing
the *right variant* when a wall is placed — corner, end, T-junction, straight.
This is a headline feature the official tools handle poorly.

**Scope.** Given a placed wall and its neighbours, select the correct wall tile
variant. Update neighbours when a wall is added or removed.

**Method.** Read vanilla first. Find a house corner, a T-junction and a wall end
in Muldraugh with `Probe square`, and record which tiles vanilla uses in each
case. The tileset's own naming and facing properties are the recipe; do not
infer the variant set from what looks right in a render.

**Definition of done.** Placing a wall run around a rectangle in `CellEditor`
produces the same tile choices vanilla uses for an equivalent structure, and
removing one wall re-joins its neighbours correctly. Undo still restores
byte-identical output.

**Falsification.** Re-derive the variant for a wall you did not use to build the
rule, in a different tileset. If the rule only works on the tileset it was
derived from, it is a lookup table, not a rule.

**Non-goals.** No UI. No doors or windows — fixtures mount in walls and have
their own chunk.

---

## A4 — Validation rule engine

**Depends on A1** (closed) **and A3.** This is the chunk that makes the
charter's competitive claim true, so it should not be rushed to a thin
version.

> **DESIGN CONSTRAINT discovered 2026-08-10** (STATE §19). A corpus sweep of
> all 4065 cells found **152,317 room rects, of which 80.3% are under 4 on a
> side and 46,482 are one square wide.** Vanilla rooms are decomposed into
> thin strips.
>
> **Any rule reasoning about a rect's interior is inapplicable to four-fifths
> of the corpus. A4 must work at 1×N.** This constrains the design more than
> the orientation question does.
>
> **Room membership comes only from lotheader rects.** The per-square room id
> is `-1` on every vanilla interior square sampled (STATE §18), so A4 needs a
> spatial index over `RoomDef` rects built per cell — the same index
> `FootprintSnap` (E5) needs.
>
> **Multi-storey rooms repeat per level.** `prisoncells [193,203 5x54]`
> appears identically at z=0,1,2,3, so vertical connectivity is a separate
> question from horizontal for "room with no exit".
>
> **An "expressible as a room rect" rule belongs here**, and a working
> prototype exists in `RoomGeometry.alignment()` — but it took four attempts
> to stop false-positiving on vanilla, and it cannot test rects under 4 on a
> side. Read STATE §19's table of what failed before rebuilding it.

**Scope.** A `Validator` that walks a cell and reports structural problems with
coordinates: doorway with no adjacent floor, room with no exit, wall gap that is
not a door, floor at z>0 with nothing beneath, room rectangle overlapping
another, square with no object at z=0 (the chunk gate from STATE §7).

**Method.**
- **Run it against vanilla Muldraugh first.** Vanilla is hand-authored and
  correct-by-construction; a rule that fires on hundreds of vanilla squares is a
  wrong rule, not a discovery. Tune against the 4065-cell corpus.
- Report rates over the population that can discriminate. A rule that cannot
  fire on most squares will look impressively clean for no reason.
- Each rule needs a severity: error (map will not work) vs warning (probably a
  mistake).

**Definition of done.** The rule set runs clean, or with characterised and
explained exceptions, across a sample of vanilla cells; and it correctly flags
each problem in a deliberately broken test cell.

**Falsification.** For every rule, construct the broken case *and* confirm the
rule fires on it. A rule never observed firing is not known to work.

**Non-goals.** No UI panel. No auto-fix. Report only.

---

## A5 / A6 / A7 — stubs

Write the full prompt when the chunk comes up, using the shape above.

- **A5 TMX read/write.** Interop boundary with the official tools.
  `Unjammer/PZ_Vanilla_map_b42` is the whole vanilla map already decompiled to a
  WorldEd project — an independent regression corpus. Parse a cell, compare
  against their TMX for the same coordinates. Depends on A1 because room
  rectangles cross the boundary.
- **A6 `.tiles` writer.** Reader is confirmed on 73,644 tiles. Writer is needed
  for custom tile properties.
- **A7 `objects.lua` read/write.** Currently `{}`; vanilla's is 4 MB. Likely
  connected to room loot tables, so pair with B6.

---

# Track B prompts

## B1 — Vanilla house anatomy

**Deliverable is a document, not code.** If this chunk produces Java, it has
failed. Three multi-session detours have come from building before reading.

**Scope.** Pick three vanilla Muldraugh houses of different sizes. Using
`Probe square` and `Probe findprop`, write down exactly how a PZ house is
constructed:

- Floor tiles by room type, and how they change between rooms
- Wall tiles: exterior vs interior, material variation, corner handling
- Where doors sit relative to the wall edge; interior vs exterior door tiles
- Windows: tile, placement rules, height
- Roof: which z-levels, which tiles, how overhang works, what happens at eaves
- Room rectangles: how an L-shaped house decomposes; how many rooms per building
- Room *names* actually used, and the `objects.lua` entries alongside them
- Anything at z=-1

**Definition of done.** A markdown document a future session can build from
without opening the game — with tile names, coordinates, and at least one
worked example per structure. Contradictions between the three houses are
recorded as contradictions, not averaged away.

**Falsification.** For each claim, note whether it held in all three houses or
only one. A pattern from a single house is a hypothesis.

**Non-goals.** No generator design. No opinions on how *we* should build houses.

---

## B2 — `StaticModule.prefab` decision gate

**Deliverable is a written decision with evidence attached.** Not code.

**Context.** Building generation has an open fork that has been open for three
sessions and must not be decided on vibes:

- **Room-splitting generator** — we author the geometry. The editor can then
  inspect, validate and undo it. Consistent with the charter's semantic layer.
- **`StaticModule.prefab`** — the engine's own structure placement mechanism.
  Never tried, never read. The engine assembles at load time, which may mean the
  editor cannot inspect or validate what the player will see.

**Scope.** Read `StaticModule` and everything it touches in the decompiler
(`~/Downloads/ZOMBOIDSTUFF/decompiled/`, Vineflower at
`~/Downloads/ZOMBOIDSTUFF/vineflower.jar`; `grep -rl StaticModule
--include='*.class'` finds it). Answer:

1. What does a prefab consist of on disk, and where does the engine read it?
2. Does vanilla use it? For what? Find a call site.
3. Does it run on authored chunks, or only procedural ones? (Compare with the
   `genMapChunk` / `genRandomChunk` split in STATE §7.)
4. Can authored map data reference a prefab, or is it WorldGen-only?
5. Is prefab output inspectable from map data, or only after load?

**Definition of done.** A decision — room generator, prefab, or both with a
stated boundary — with the answers above and their evidence. Question 5 is
decisive for the editor: if prefab output cannot be inspected from map data, the
editor cannot validate it, and that outweighs any convenience.

**Falsification.** Note which answers came from reading code and which from
inference. Any inference gets flagged UNVERIFIED in STATE.

**Non-goals.** Do not implement either path.

---

## B6 — Room typing and loot tables

Depends on B1 and A7. Generic `"room"` gives no loot tables, so a generated map
is unplayable regardless of how good the geometry is. Full prompt when it comes
up.

---

# Track C prompts

## C1 — Architecture decision gate

**Deliverable is a written decision.** Not code, not a prototype.

**Constraints already fixed by the charter — do not re-litigate:**

- Local, single-user. **No multi-user concurrent editing** (decided
  2026-08-08 — the official tools don't support it and there's no demand).
- The library layer stays dependency-free Java 21. The application layer may
  take dependencies.
- Reads assets from the user's PZ install. Ships no TIS art.
- Runs on Linux. Development is Garuda + IntelliJ.

**Decide, with reasons:**

1. **UI toolkit.** Options previously floated but never chosen: a local
   Spring Boot process serving a browser canvas, or native LWJGL / libGDX. With
   multi-user gone, the main argument for the web stack was removed — so this
   should be re-argued from scratch, not inherited. Weigh against: Knox County
   is ~1,300 cells, B42 has negative z-levels, and a naive per-tile draw dies
   immediately. Sprite batching, an atlas cache and viewport streaming are
   needed from day one whatever the choice.
2. **Working store.** SQLite, chunked binary, or the game's own format as the
   live format. Thousands of TMX files is not a working store — TMX stays an
   interop boundary (A5).
3. **Where undo lives.** `CellEditor` has an in-memory grouped journal that
   restores byte-identical output. Does it persist across sessions, and if so,
   in what?
4. **Process boundary.** One process or two, and what crosses.

**Definition of done.** A document giving the choice, the alternatives
considered, and — for each — what would make it the wrong choice. That last part
matters: it is what lets a future session recognise a mistake instead of
inheriting it.

**Falsification.** For the rendering choice specifically, estimate the frame
budget for a full viewport of tiles before choosing, and say what number would
rule the option out. Don't discover it after building the shell.

**Non-goals.** No prototype. No scaffolding. No dependency added to the library.

---

## C2–C5 — stubs

Cannot be written until C1 resolves; their content depends on the toolkit and
store chosen. Shapes only:

- **C2 Working store and project format.** Open, edit, save, reopen without
  loss. Crash safety.
- **C3 Interactive viewport.** Pan, zoom, z-level switching, layer visibility.
  Streaming and atlas caching per C1's numbers.
- **C4 Tool layer.** Brushes, rectangle select, floor fill, wall draw (using
  A3), delete. Undo/redo bound to the UI on top of `CellEditor`'s journal.
- **C5 Shell.** Tile picker driven by the semantic layer — search by *property*,
  not filename. Validation panel from A4, click-to-navigate. Room inspector.

---

# Track E prompts

## E3 — Ground blending investigation  ✅ DONE 2026-08-13

> Ground read as scattered tan diamonds because two layers were missing, not
> because the tile mix was wrong.
>
> **The mechanism is CONFIRMED.** A square carries exactly one **solid** tile,
> zero to four **mask** tiles drawn from a *neighbour's* 16-tile block, and at
> most one **tuft**. Masks carry `FloorOverlay` and `FloorAttachment{N,S,E,W}`;
> the flag names the direction the other material lies in. Two adjacent sides
> use one corner tile, not two side tiles.
>
> **Blending is one-way** — 21 of 21 masks in the measured rectangle are
> `Grass_Dark` onto `Grass_Medium`, never the reverse. There is a material
> precedence table, and it is not block-index order.
>
> **The engine will not do this for us.** `Blending.applyBlending` fires only
> where a chunk borders a *procedural* chunk, and it replaces solid tiles
> rather than writing masks. No mask tile appears anywhere in the game's Lua.
> Every mask must be authored.
>
> **Two prior beliefs fell.** A square does not carry several base tiles — it
> carries one solid plus masks. And §24's `Grass_Medium` band at x=112–124 does
> not exist: a 4-tile stride aliased a dithered boundary that actually reads
> `M D M D M M M D D`.
>
> **`GroundPalette` was not touched**, as the prompt required. Full document:
> `docs/E3_GROUND_BLENDING.md`. Findings folded into STATE §26 with seven
> Corrections rows and one new method note.

---

## E7 — Ground precedence and dither generality

**Deliverable is a document, not code.** Same rule as E3 and B1. E3 produced an
implementable mask rule with two holes in it; this chunk fills them. If this
chunk produces a `GroundPalette` rewrite or a region layer, it has failed —
those are E8 and E9.

**Why this is next.** STATE §26's implementation list has a hard dependency at
the top. The mask pass cannot decide *which* square carries the mask without a
material priority table, and only three of twenty-one `blends_natural_01` pairs
are known. Nothing downstream can start.

**Read first:** STATE §26 and `docs/E3_GROUND_BLENDING.md` §11, which lists six
named checks. This chunk runs the first four.

## Answer these, with evidence for each

1. **The material priority table.** For each pair of ground materials that
   occurs adjacently in vanilla, which one supplies the mask? Known:
   `Grass_Dark` > `Grass_Medium` > `Sand`, and `Road_02` > `Road_04`. It is not
   block-index order — Sand is block 0 and loses to Medium at block 32.
2. **Is it a total order?** If some pair shows each material masking the other
   in different places, priority is contextual and the mask rule needs a
   tie-break that does not currently exist.
3. **Do masks cross tilesets?** Does a `blends_natural_01` square adjacent to a
   `blends_street_01` square carry a road mask, or is that boundary hard? No
   sample was taken in E3.
4. **Is dither a convention or a 42_40 quirk?** E3 found `Grass_Dark` and
   `Grass_Medium` interpenetrating per square across 2–4 squares at their
   boundary. That was one area of one hand-authored town cell. If a non-town
   cell shows a clean straight material edge with masks and no interleaving,
   **implementation step 3 disappears and E8 gets simpler.**
5. **The multi-material mask case.** Find a square bordering two *different*
   higher-priority materials — the Sand/grass boundary around (60–104, 200) is
   the obvious hunting ground. Does it carry masks from both blocks?

## Method

**Sample contiguously.** This is the method note E3 added to STATE §4, and it
is the whole reason §24 was wrong for two sessions. A 4-tile stride through a
dithered boundary returned four identical materials in a row and was read as a
region band. Rectangles, not transects.

**Filter on `solidfloor`, never on `FloorMaterial`.** Mask tiles carry
`FloorMaterial` too. Three of the four ground transects in §21 and §24 are
wrong for exactly this reason.

**Prefer a non-town cell for question 4**, and a cell other than 42_40 for
everything else. Every measurement E3 made came from one cell.

`CellRenderer` composites masks correctly — it draws the whole square stack in
stored order onto an ARGB canvas — so a rendered PNG of a vanilla boundary is a
legitimate check here and is much faster to read than probe output.

## Definition of done

A markdown document a future session can implement E8 and E9 from. Each claim
CONFIRMED or UNVERIFIED with its source named. Contradictions recorded as
contradictions.

## Falsification

For each claim, say what observation would disprove it. E3's four failed
hypotheses all looked right against the data that suggested them; what caught
them was checking against data that had not been used to form them.

## Do not

- **Do not build the region layer.** That is E8, and it depends on this.
- **Do not touch `GroundPalette`.** Its tuft model is measured and sound; only
  its naming is wrong, and renaming is E9's job.
- **Do not assume the priority order is derivable** from block index, from
  brightness, or from anything else. Measure it.

---

## E1 — Zombie density  ✅ DONE 2026-08-11

> `chunkGrid` was all zeros, which is why the generated map had no zombies at
> all while vanilla ground across the boundary did.
> `GisCells.writeChunkDensity` now writes 2 for chunks holding building tiles
> and 1 for orthogonal neighbours.
>
> **Predicted from building geometry before running:** 40–70 twos, 80–150
> ones, 95%+ zero. **Got:** 72 twos, 89 ones, 96.1% zero — against vanilla's
> 96.4%, a number not tuned for.
>
> **In game, fresh world: zombies at the building, none on the way.** First
> ever seen on the generated map. Both halves held, including the negative.
>
> **Mechanism proven; calibration untested** — see E2. STATE §22, §23.

---

## E2 / E4 / E5 / E6 / E8 / E9 / E10 — stubs

- **E2 Calibrate density.** 2 near buildings is at the low end of vanilla's
  0–10 range, and seven buildings is a hamlet, not a town. The import already
  distinguishes `Agriculture` from `Residential` and treats them alike.
  Measure how vanilla's nonzero density relates to what a place is — and
  prefer the engine's spawn code to measuring Muldraugh. **Do not sample the
  vanilla histogram per chunk:** it is a frequency measurement, and density
  clusters around habitation.
- **E4 Scene rotation pass.** Room rects are `x, y, w, h` with no rotation
  field, so off-axis footprints are unrepresentable, not merely ugly. Take
  each footprint's min-area-rect angle mod 90°, histogram weighted by area,
  rotate the **whole scene** — buildings, roads, area polygon — by the
  dominant mode before rasterizing, then snap residuals to 90°. Per-footprint
  alignment is wrong: it squares each building against itself and randomises
  them against each other and the roads. **Store the rotation angle with the
  import; do not discard it** — `worldmap.xml` and any GIS overlay need it.
  Predict the histogram shape first: a grid town should show one mode holding
  over half the footprint area within ±3°; flat or bimodal means no single
  grid and whole-scene rotation is wrong for that area.
- **E5 `FootprintSnap`.** One module, two callers: GIS import and interactive
  authoring. **Refuses off-axis footprints rather than warning** — §17 is
  resolved (see below). The editor side is snap-to-90° on rectangle tools;
  the enforcement point is A4's "wall run not expressible as a room rect",
  which catches hand-painted zigzags and imported data alike.
- **E6 Extract `BiomeMapWriter` distance banding.** Currently inline in the
  pixel loop and discarded. It is a pure function of `dist[gx][gy]` plus a
  bounds check, so extraction is small, and three consumers want a region
  signal. Worth doing regardless of how E3 resolves — but do not wire it to
  `GroundPalette` until E7 settles the priority table. **E3 answered the driver question:** for
  authored cells it is a human painting land use, and for us it is GIS land
  use. There is no hidden algorithm. Distance banding is still worth extracting
  as a signal, but it is not the region driver.
- **E8 Region layer from GIS land use.** One material per square, pure
  interiors, from the land-use data the import already carries. STATE §26
  confirms vanilla's own regions are parcels: 42_40's mid-cell `Sand` is a
  fenced yard between a road and a shed, inside an `emptyoutside` room. Add the
  dither pass only if E7 says dither is a convention.
- **E9 Mask pass.** Implements STATE §26's rule. Runs **after** every square's
  material is final — a square's masks depend on its neighbours, so this cannot
  be folded into the per-square roll in `GroundPalette.roll()`. Mask tiles must
  be declared in the `.lotheader` tile table; `GroundPalette.all` currently
  collects solids and tufts only. **Write it as a general neighbour-rule engine,
  not a ground-specific pass** — it is the same shape as A3 auto wall-joining,
  and A3 should inherit it rather than duplicate it. Rename
  `GroundPalette`'s "overlay" to "tuft" here; the collision with mask tiles will
  otherwise cause a misimplementation.
- **E10 Restore dirt.** `dirt` and `dirt_grass` have full 16-tile blocks with
  the same mask vocabulary as the grasses. Test 27 dropped them because
  scattered dirt reads as bare diamonds — correct diagnosis of a symptom.
  Gated to yards, tracks and unpaved roads, bare is right. Depends on E8
  existing to gate against.

---

## Decision gates resolved 2026-08-10/11

### §17 — building orientation. BOTH CHECKS CLOSED.

**Check 2 (vocabulary): CLOSED.** No diagonal wall primitive exists in the
tile art. This covers the whole corpus, and is the stronger of the two lines.

**Check 1 (does vanilla ever go off-axis): CLOSED, with a stated limit.**
Zero non-aligned rooms among 29,928 testable rects across 4065 cells, using a
test calibrated against a known positive *and* known negatives. The limit:
that is 19.6% of rects; the rest are under 4 on a side and have no interior
for walls to spread across.

**RESOLUTION: `FootprintSnap` REFUSES off-axis footprints, it does not warn.**
An earlier "warn, not refuse" recommendation came from a broken guard and is
retracted (STATE §13, §19).

---

# Track D — stubs

- **D1 Fast round-trip regression.** Currently ~14 min, which means it doesn't
  get run. Target under a minute for a representative sample, with the full
  4065-cell run kept as an occasional job.
- **D2 Publish B42 format documentation.** PZwiki documents B41 and is wrong for
  B42 on magic bytes, string terminators, cell size, offset width and chunk
  size. The format work is confirmed byte-identical; publishing it is cheap
  goodwill and invites correction from people who know things we don't.
- **D3 Licensing.** Verify GIS dataset terms per state; choose a project licence.

---

## FINDINGS block format

Every chunk ends by producing this. Paste it into the next session if it is
listed as an input, and fold it into `STATE.md` before starting anything else.

```markdown
## FINDINGS — <chunk id> — <date>

**Status:** complete / partial / blocked

**What was done:**
- …

**Confirmed** (verified against vanilla data, decompiled engine, or in game):
- …

**Unverified** (believed, not tested — say what would test it):
- …

**Corrections** (something in STATE.md is wrong):
- Old claim → what is actually true → evidence

**Files changed:**
- created / modified / deleted, by path

**Commands worth keeping:**
```fish
…
```

**Noticed, out of scope:**
- …

**What the next chunk needs to know:**
- …
```

The **Corrections** and **Unverified** sections are the ones that earn their
keep. Eight bugs got through 224 automated tests, and every one was caught by
comparison against an independent source — a session that records what it
merely *believes*, separately from what it *checked*, is handing the next
session the list of things worth checking.

"""
pzorch.pipeline — the wizard steps, as subprocess invocations.

Every command here is the one already written down in STATE.md section 0. The
orchestrator does not invent a new way to drive the pipeline; it types the same
commands you would type, in the same order, with the paths filled in.

Steps
-----
    compile   rm -rf out && javac -encoding UTF-8 -d out $(find src -name '*.java')
    fetch     python3 fetch_gis.py <area> <gis_dir> [--source osm]
    generate  <generator> giscells <buildings> <roads> <area> <media> <mods> <mod> 0 <ox> <oy>
    render    <venv python> main.py render base_top <mod>

Note that "generate" and "build" are the SAME command with a different origin.
That is why placement sits between them: you generate at a provisional origin,
look at it on the vanilla map, then regenerate with the real one.
"""

from __future__ import annotations

import re
import shutil
import time
from pathlib import Path

from . import config
from .projects import Project, ProjectError, REQUIRED_LAYERS
from .runner import Job, runner


# Provisional origin used for the first generate, before placement.
# STATE.md uses 200,200 as the working example.
DEFAULT_ORIGIN = (200, 200)

# Lines worth keeping out of generator output. Mirrors the grep in STATE.md.
# Lines worth surfacing. These are the exact summary lines GisCells.run prints:
#   "roofs: N pitched, N flat (wider than 12 tiles...), N flat (commercial...)"
#   "cells written: N   squares: N   rooms: N   edge-filled: N"
#   "ground tufts: N  (X% of ground squares; vanilla measures 43.3%)"
#   "generating NxN cells"
#   "tile palette: ...", "incomplete palette; cannot generate cells"
INTERESTING = re.compile(
    r"(EMPTY|roles,|stall_toilet|stall_wall|"
    r"roofs:|cells written:|ground tufts:|generating \d+x\d+ cells|"
    r"incomplete palette|tile palette:|exterior wall skins:|"
    r"WARN|ERROR|Exception)",
    re.IGNORECASE,
)
ROOF_LINE = re.compile(r"^roofs:\s*(.+)$", re.IGNORECASE)
CELLS_LINE = re.compile(r"^cells written:\s*(\d+)", re.IGNORECASE)
GENERATING_LINE = re.compile(r"^generating\s+(\d+)x(\d+)\s+cells", re.IGNORECASE)

# GisCells prints this and RETURNS ZERO -- a successful exit with no map.
INCOMPLETE_PALETTE = "incomplete palette; cannot generate cells"


def _log_path(proj: Project, step: str) -> Path:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    return proj.logs_dir / f"{stamp}-{step}.log"


# --- step 0: compile --------------------------------------------------------


async def compile_java(proj: Project) -> Job:
    """rm -rf out && mkdir out && javac -encoding UTF-8 -d out <all .java>"""
    if config.GENERATOR_KIND == "native":
        raise ProjectError(
            "Generator is set to native (PZMC_GENERATOR_KIND=native); there is "
            "nothing to compile here. Build PZMapMaker with its own toolchain."
        )

    if not config.JAVA_SRC.is_dir():
        raise ProjectError(f"Java sources not found: {config.JAVA_SRC}")

    sources = sorted(str(p) for p in config.JAVA_SRC.rglob("*.java"))
    if not sources:
        raise ProjectError(f"No .java files under {config.JAVA_SRC}")

    # The rm/mkdir half of the standing command, done in-process.
    shutil.rmtree(config.JAVA_OUT, ignore_errors=True)
    config.JAVA_OUT.mkdir(parents=True, exist_ok=True)

    argv = [
        "javac",
        "-encoding",
        "UTF-8",
        "-d",
        str(config.JAVA_OUT),
        *sources,
    ]

    return await runner.start(
        project_id=proj.id,
        step="compile",
        argv=argv,
        cwd=config.PROJECT_ROOT,
        log_path=_log_path(proj, "compile"),
    )


# --- step 1: fetch GIS ------------------------------------------------------


async def fetch_gis(proj: Project) -> Job:
    """Run pzgis over the project's area.geojson, into the project's gis/ dir."""
    if not proj.area_path.is_file():
        raise ProjectError("No area.geojson yet -- paste the GeoJSON first.")

    if not config.FETCH_GIS.is_file():
        raise ProjectError(f"pzgis not found: {config.FETCH_GIS}")

    source = config.COUNTRY_SOURCES.get(proj.country)
    if source is None:
        raise ProjectError(f"Unsupported country: {proj.country}")

    # pzgis has its OWN virtualenv (make_new_gis_map.md step 1 activates it
    # before running frontend.py). Running that venv's interpreter directly is
    # equivalent to activating it; the ambient python3 would be missing its
    # dependencies entirely.
    argv = [
        config.venv_python(config.PZGIS_VENV_PY),
        str(config.FETCH_GIS),
        str(proj.area_path),
        str(proj.gis_dir),
    ]
    if source == "osm":
        argv += ["--source", "osm"]

    def on_finish(job: Job) -> None:
        if job.status != "done":
            proj.last_error = f"pzgis exited {job.returncode}"
            proj.save()
            return

        missing = proj.missing_required_layers()
        if missing:
            proj.last_error = (
                "pzgis completed but did not produce: " + ", ".join(missing)
            )
            proj.save()
            job.append("!! " + proj.last_error)
            job.status = "failed"
            return

        proj.last_error = None
        proj.advance_to("FETCHED")
        proj.save()
        job.append(f"++ layers present: {', '.join(proj.present_layers())}")

    return await runner.start(
        project_id=proj.id,
        step="fetch",
        argv=argv,
        cwd=config.PZGIS_DIR,
        log_path=_log_path(proj, "fetch"),
        on_finish=on_finish,
    )


# --- spawn point preservation ----------------------------------------------
#
# STATE.md section 2: "regenerating the mod OVERWRITES spawnpoints.lua with auto
# spawns." So the orchestrator owns the file across regenerates.
#
# We preserve the Lua VERBATIM rather than regenerating it from coordinates.
# modmap.html's spawn picker already emits paste-ready Lua on the legacy
# 300-tile grid; re-deriving it here would be a second implementation of the
# same conversion and a second chance to get /300 vs /256 wrong.


def capture_spawnpoints(proj: Project) -> bool:
    """Copy the mod's current spawnpoints.lua into the project. True if saved."""
    src = proj.mod_spawnpoints_path
    if not src.is_file():
        return False
    proj.spawnpoints_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, proj.spawnpoints_path)
    return True


def restore_spawnpoints(proj: Project) -> bool:
    """Write the preserved spawnpoints.lua back over the generated one."""
    saved = proj.spawnpoints_path
    if not saved.is_file():
        return False
    dest = proj.mod_spawnpoints_path
    if not dest.parent.is_dir():
        return False
    shutil.copy2(saved, dest)
    return True


def set_spawnpoints_lua(proj: Project, lua: str) -> None:
    """Store Lua pasted from modmap.html's picker. Applied after every build."""
    text = (lua or "").strip()
    if not text:
        proj.spawnpoints_path.unlink(missing_ok=True)
        return
    if "SpawnPoints" not in text:
        raise ProjectError(
            "That does not look like spawnpoints.lua -- expected a SpawnPoints "
            "function. Paste the output of modmap.html's 'Generate "
            "spawnpoints.lua' button."
        )
    proj.ensure_dirs()
    # Keep a trailing newline: this is written straight over a .lua file the
    # game reads, and a file that ends mid-line is needless untidiness.
    proj.spawnpoints_path.write_text(text + "\n", encoding="utf-8")


# --- step 2/4: generate (and build, which is the same thing) ----------------


async def generate(
    proj: Project,
    origin_x: int | None = None,
    origin_y: int | None = None,
    *,
    final: bool = False,
) -> Job:
    """
    Run the generator.

    maxTiles is ALWAYS 0. STATE.md section 5: "Never pass a hardcoded tile
    count; it will clip the map silently." There is deliberately no way to set
    it from the UI.
    """
    missing = proj.missing_required_layers()
    if missing:
        raise ProjectError("Missing GIS layers: " + ", ".join(missing))

    if origin_x is None or origin_y is None:
        if proj.origin_x is not None and proj.origin_y is not None:
            origin_x, origin_y = proj.origin_x, proj.origin_y
        else:
            origin_x, origin_y = DEFAULT_ORIGIN

    if not config.PZ_MEDIA.is_dir():
        raise ProjectError(f"PZ media not found: {config.PZ_MEDIA}")

    config.ZOMBOID_MODS.mkdir(parents=True, exist_ok=True)

    # Capture whatever spawn points the mod currently has, unless we already
    # hold a saved copy (a saved copy is the user's intent; the generated file
    # is not).
    if not proj.spawnpoints_path.is_file():
        capture_spawnpoints(proj)

    argv = config.generator_argv(
        "giscells",
        [
            str(proj.layer_path("buildings.geojson")),
            str(proj.layer_path("roads.geojson")),
            str(proj.layer_path("area.geojson")),
            str(config.PZ_MEDIA),
            str(config.ZOMBOID_MODS),
            proj.mod_name,
            "0",  # maxTiles: auto-size from area boundary. Never hardcode.
            str(origin_x),
            str(origin_y),
        ],
    )

    notable: list[str] = []
    state = {"incomplete_palette": False, "cells": None, "grid": None}

    def on_line(job: Job, line: str) -> None:
        stripped = line.strip()
        if INCOMPLETE_PALETTE in stripped:
            state["incomplete_palette"] = True
        m = CELLS_LINE.match(stripped)
        if m:
            state["cells"] = int(m.group(1))
        m = GENERATING_LINE.match(stripped)
        if m:
            state["grid"] = f"{m.group(1)}x{m.group(2)}"
        if INTERESTING.search(stripped):
            notable.append(stripped)

    def on_finish(job: Job) -> None:
        if job.status != "done":
            proj.last_error = f"generator exited {job.returncode}"
            proj.save()
            return

        # GisCells.run() RETURNS NORMALLY when the tile palette is incomplete:
        #   if (!pal.complete()) { println("incomplete palette..."); return; }
        # Exit code 0, no cells written. Without this check the project would
        # advance to GENERATED with an empty mod folder.
        if state["incomplete_palette"]:
            proj.last_error = (
                "Tile palette incomplete -- the generator stopped before "
                "writing any cells. Check the PZ media path."
            )
            proj.save()
            job.append("!! " + proj.last_error)
            job.status = "failed"
            return

        # Same class of failure with a different cause: exit 0, nothing on disk.
        if state["cells"] == 0 or not proj.mod_map_dir.is_dir():
            proj.last_error = (
                f"Generator finished but wrote no cells to {proj.mod_map_dir}."
            )
            proj.save()
            job.append("!! " + proj.last_error)
            job.status = "failed"
            return

        proj.last_error = None
        proj.origin_x = origin_x
        proj.origin_y = origin_y

        stats: dict[str, object] = {
            "notable": notable[-40:],
            "origin": [origin_x, origin_y],
        }
        if state["cells"] is not None:
            stats["cells_written"] = state["cells"]
        if state["grid"] is not None:
            stats["grid"] = state["grid"]
        for line in notable:
            m = ROOF_LINE.match(line)
            if m:
                stats["roofs"] = m.group(1).strip()
        proj.stats = stats

        if restore_spawnpoints(proj):
            job.append("++ restored preserved spawnpoints.lua over generated one")
        else:
            job.append("++ using generated spawnpoints.lua (none preserved)")

        proj.advance_to("GENERATED")
        if final:
            proj.advance_to("BUILT")
        proj.save()
        job.append(f"++ mod written to {proj.mod_dir}")
        job.append("++ NEW GAME required in PZ -- a resumed save will not see it")

    return await runner.start(
        project_id=proj.id,
        step="build" if final else "generate",
        argv=argv,
        cwd=config.PROJECT_ROOT,
        log_path=_log_path(proj, "build" if final else "generate"),
        on_line=on_line,
        on_finish=on_finish,
    )


# --- step 3: render ---------------------------------------------------------
#
# pzmap2dzi's CLI takes RENDER JOBS, not map names:
#     main.py [-c conf] render base_top
# The maps come from conf.yaml -- `base_map` plus the `mod_maps` list -- so a
# new mod has to be registered there first. Two files are involved:
#
#   conf/mod/pzorch_maps.txt   the map entry (path, display name). Entirely
#                              ours; conf.yaml already globs conf/mod/.
#   conf/conf.yaml             the `mod_maps:` list. Patched as TEXT, one line
#                              inserted, because a YAML round-trip would strip
#                              the ~200 lines of comments in that file.

MOD_MAPS_RE = re.compile(r"^mod_maps:\s*$")
LIST_ITEM_RE = re.compile(r"^(\s*)-\s*(.+?)\s*$")
COMMENTED_ITEM_RE = re.compile(r"^\s*#\s*-\s*(.+?)\s*$")

# Failure lines pzmap2dzi prints while still exiting 0.
# NB: "unspported" is pzmap2dzi's own spelling (render_map in main.py).
UNSUPPORTED_CMD = re.compile(r"unsp+orted render cmd:\s*(.+)$", re.IGNORECASE)
MAP_NOT_FOUND = re.compile(r"mod map \[(.+?)\] not found in map_conf")
RENDER_ERROR = re.compile(r"render \[(.+?)\] for map \[(.+?)\] error")
RENDER_DONE = re.compile(r"render \[(.+?)\] for map \[(.+?)\] done")


def map_conf_entry(proj: Project) -> str:
    """One YAML map entry for this project's mod.

    map_path is absolute. The default B42 template is
        {mod_root}/{steam_id}/mods/{mod_name}/common/media/maps/{map_name}
    which assumes a Workshop mod; ours lives under ~/Zomboid/mods. str.format
    leaves a brace-free string untouched, so an absolute path is safe here.
    """
    return (
        f"{proj.mod_name}:\n"
        f"  display_name: {proj.name}\n"
        f"  map_name: {proj.mod_name}\n"
        f"  mod_name: {proj.mod_name}\n"
        f"  map_path: '{proj.mod_map_dir}'\n"
        f"  texture: false\n"
    )


def _read_pzorch_entries(path: Path) -> dict[str, str]:
    """Parse our own generated file back into {mod_name: entry text}."""
    if not path.is_file():
        return {}
    entries: dict[str, str] = {}
    name: str | None = None
    buf: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines(keepends=True):
        if line.strip() and not line[0].isspace() and line.rstrip().endswith(":"):
            if name is not None:
                entries[name] = "".join(buf)
            name = line.rstrip().rstrip(":").strip()
            buf = [line]
        elif name is not None:
            buf.append(line)
    if name is not None:
        entries[name] = "".join(buf)
    return entries


def register_map_entry(proj: Project) -> Path:
    """Write/replace this project's entry in conf/mod/pzorch_maps.txt."""
    conf_dir = config.PZMAP2DZI_CONF.parent
    target = conf_dir / "mod" / config.PZORCH_MAP_CONF_NAME
    target.parent.mkdir(parents=True, exist_ok=True)

    entries = _read_pzorch_entries(target)
    entries[proj.mod_name] = map_conf_entry(proj)

    body = (
        "# Generated by pzorch. Map entries for orchestrator projects.\n"
        "# conf.yaml globs this whole folder via `map_conf: [vanilla.txt, mod/]`,\n"
        "# so entries here are available without editing anything else.\n"
        "# A map is only rendered when its name is also in conf.yaml's mod_maps.\n"
        "\n"
    )
    for key in sorted(entries):
        body += entries[key].rstrip("\n") + "\n\n"

    target.write_text(body, encoding="utf-8")
    return target


def register_mod_map(proj: Project) -> tuple[bool, str]:
    """Add this mod to conf.yaml's mod_maps list. (changed, message)."""
    conf = config.PZMAP2DZI_CONF
    if not conf.is_file():
        raise ProjectError(f"pzmap2dzi config not found: {conf}")

    lines = conf.read_text(encoding="utf-8").splitlines(keepends=True)

    start = None
    for i, line in enumerate(lines):
        if MOD_MAPS_RE.match(line):
            start = i
            break
    if start is None:
        raise ProjectError(
            f"No 'mod_maps:' key in {conf}. Add one (it may be an empty list) "
            "and run this again."
        )

    # Walk the block: list items, commented-out items, and blank lines.
    end = start + 1
    existing: list[str] = []
    while end < len(lines):
        line = lines[end]
        if not line.strip():
            end += 1
            continue
        m = LIST_ITEM_RE.match(line)
        if m and line[0].isspace():
            existing.append(m.group(2))
            end += 1
            continue
        if COMMENTED_ITEM_RE.match(line):
            end += 1
            continue
        break

    if proj.mod_name in existing:
        return False, f"{proj.mod_name} already in mod_maps"

    # Back up once, the first time we touch their file.
    backup = conf.with_suffix(conf.suffix + ".pzorch-bak")
    if not backup.exists():
        shutil.copy2(conf, backup)

    lines.insert(start + 1, f"    - {proj.mod_name}\n")
    conf.write_text("".join(lines), encoding="utf-8")
    return True, f"added {proj.mod_name} to mod_maps in {conf}"


async def render(proj: Project) -> Job:
    """
    Register the mod, then: main.py -c <conf> render base_top

    Seconds. The 167-minute "base" job is never run -- RENDER_CMD is base_top
    and there is no way to change it from the UI.
    """
    if not proj.mod_map_dir.is_dir():
        raise ProjectError(
            f"No generated mod at {proj.mod_map_dir} -- run Generate first."
        )
    if not config.PZMAP2DZI_DIR.is_dir():
        raise ProjectError(f"pzmap2dzi not found: {config.PZMAP2DZI_DIR}")

    entry_file = register_map_entry(proj)
    changed, reg_msg = register_mod_map(proj)

    python_bin = config.venv_python(config.PZMAP2DZI_VENV_PY)

    # Clear this mod's previous tiles only. Guarded so a bad mod name can never
    # point rmtree somewhere else.
    render_dir = proj.render_dir
    mod_maps_root = config.MOD_MAPS_DIR.resolve()
    if render_dir.is_dir():
        if render_dir.resolve().parent == mod_maps_root:
            shutil.rmtree(render_dir, ignore_errors=True)
        else:
            raise ProjectError(f"Refusing to delete {render_dir}")

    argv = [
        python_bin,
        "main.py",
        "-c",
        str(config.PZMAP2DZI_CONF),
        "render",
        config.RENDER_CMD,
    ]

    state = {"done": False, "unsupported": [], "not_found": [], "error": False}

    def on_line(job: Job, line: str) -> None:
        stripped = line.strip()
        m = UNSUPPORTED_CMD.search(stripped)
        if m:
            state["unsupported"].append(m.group(1).strip())
        m = MAP_NOT_FOUND.search(stripped)
        if m:
            state["not_found"].append(m.group(1).strip())
        m = RENDER_ERROR.search(stripped)
        if m and m.group(2).strip() == proj.mod_name:
            state["error"] = True
        m = RENDER_DONE.search(stripped)
        if m and m.group(2).strip() == proj.mod_name:
            state["done"] = True

    def on_finish(job: Job) -> None:
        if job.status != "done":
            proj.last_error = f"pzmap2dzi exited {job.returncode}"
            proj.save()
            return

        # pzmap2dzi exits 0 for all of these. Without the checks below, a render
        # of the WRONG map reports success -- which is exactly how
        # "render base_top <modname>" went unnoticed.
        problems: list[str] = []

        # Always our fault -- we build the argv.
        if state["unsupported"]:
            problems.append(
                "pzmap2dzi did not recognise these as render jobs: "
                + ", ".join(state["unsupported"])
            )

        # conf.yaml's mod_maps is shared. Another mod being misconfigured is
        # worth saying out loud, but it is not a reason to fail THIS render.
        missing = state["not_found"]
        if proj.mod_name in missing:
            problems.append(
                f"not registered in map_conf: {proj.mod_name}"
            )
        for other in missing:
            if other != proj.mod_name:
                job.append(
                    f"** warning: mod map [{other}] is listed in mod_maps but has "
                    "no map_conf entry -- unrelated to this project, but "
                    "pzmap2dzi will keep skipping it"
                )

        if state["error"]:
            problems.append(f"pzmap2dzi reported an error rendering {proj.mod_name}")
        if not state["done"]:
            problems.append(
                f"pzmap2dzi never reported '{proj.mod_name}' as rendered"
            )

        out_dir = proj.render_dir / config.RENDER_CMD
        if not out_dir.is_dir():
            problems.append(f"no tiles were written to {out_dir}")

        if problems:
            proj.last_error = "; ".join(problems)
            proj.save()
            for p in problems:
                job.append("!! " + p)
            job.status = "failed"
            return

        proj.last_error = None
        proj.save()
        job.append(f"++ tiles written to {out_dir}")

    job = await runner.start(
        project_id=proj.id,
        step="render",
        argv=argv,
        cwd=config.PZMAP2DZI_DIR,
        log_path=_log_path(proj, "render"),
        on_line=on_line,
        on_finish=on_finish,
    )
    job.append(f"++ map entry: {entry_file}")
    job.append("++ " + reg_msg)
    return job

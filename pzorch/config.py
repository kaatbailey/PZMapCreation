"""
pzorch.config — path resolution for the PZMapCreation orchestrator.

Every path can be overridden with an environment variable so this runs on a
machine that does not match the standing layout in STATE.md section 0.

The defaults assume pzorch/ lives inside the PZMapCreation checkout:

    ~/Documents/PZMapCreation/
    |-- src/main/java/pzformat/
    |-- out/
    |-- pzgis/
    |-- pzmap2dzi/
    |-- map-output/html/
    |-- workspace/          <- created by the orchestrator
    `-- pzorch/             <- this package
"""

from __future__ import annotations

import os
import shutil
from dataclasses import dataclass
from pathlib import Path


HOME = Path.home()


def _env_path(name: str, default: Path) -> Path:
    raw = os.environ.get(name)
    if raw:
        return Path(raw).expanduser().resolve()
    return default


# --- repository layout ------------------------------------------------------

PZORCH_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = _env_path("PZMC_ROOT", PZORCH_DIR.parent)

JAVA_SRC = PROJECT_ROOT / "src" / "main" / "java"
JAVA_OUT = PROJECT_ROOT / "out"

# pzgis is a SEPARATE checkout at ~/pzgis with its own virtualenv --
# make_new_gis_map.md step 1 activates ~/pzgis/.venv before running frontend.py,
# and the layers land in ~/pzgis/. It is not inside the PZMapCreation tree.
PZGIS_DIR = _env_path("PZMC_PZGIS", HOME / "pzgis")
FETCH_GIS = PZGIS_DIR / "fetch_gis.py"
PZGIS_VENV_PY = PZGIS_DIR / ".venv" / "bin" / "python"

# pzmap2dzi DOES live inside the tree (gitignored), with its own virtualenv.
PZMAP2DZI_DIR = _env_path("PZMC_PZMAP2DZI", PROJECT_ROOT / "pzmap2dzi")
PZMAP2DZI_VENV_PY = PZMAP2DZI_DIR / ".venv" / "bin" / "python"

# pzmap2dzi does NOT take a map name on the command line. Its CLI is
#     main.py [-c conf] render <cmd> [<cmd> ...]
# where each <cmd> is a RENDER JOB (base, base_top, rooms, ...), and the maps
# come from conf.yaml: `base_map` plus the `mod_maps` list. A mod map therefore
# has to be REGISTERED in the config before it can be rendered.
#
# Anything else on that line is parsed as another render job and produces
#     unspported render cmd: <whatever>
# on stdout -- with exit code 0. That is why STATE.md section 0's
# "render base_top PZGisImport" appeared to work: the trailing mod name was
# discarded and PZGisImport was already listed in mod_maps.
PZMAP2DZI_CONF = _env_path(
    "PZMC_PZMAP2DZI_CONF", PZMAP2DZI_DIR / "conf" / "conf.yaml"
)

# Map entries the orchestrator writes for its own projects. Lives in conf/mod/,
# which conf.yaml already globs wholesale ("map_conf: [vanilla.txt, mod/]"), so
# no change to their config is needed to have it picked up.
PZORCH_MAP_CONF_NAME = "pzorch_maps.txt"

RENDER_CMDS = (
    "base", "base_top", "rooms", "zombie", "zombie_top", "foraging",
    "foraging_top", "objects", "streets", "save", "save_top",
)

# The only render job the orchestrator ever runs. "base" is the 167-minute one.
RENDER_CMD = os.environ.get("PZMC_RENDER_CMD", "base_top")


def venv_python(venv_py: Path) -> str:
    """A venv interpreter if it exists, else the ambient python3.

    Running a venv's interpreter directly is equivalent to activating the venv
    -- sys.prefix is derived from the executable path -- so the orchestrator
    never has to source an activate script.
    """
    return str(venv_py) if venv_py.is_file() else "python3"

MAP_OUTPUT_HTML = _env_path("PZMC_MAP_HTML", PROJECT_ROOT / "map-output" / "html")
MOD_MAPS_DIR = MAP_OUTPUT_HTML / "map_data" / "mod_maps"

WORKSPACE = _env_path("PZMC_WORKSPACE", PROJECT_ROOT / "workspace")


# --- game + mod locations ---------------------------------------------------

PZ_DIR = _env_path(
    "PZMC_PZ",
    HOME / ".local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid",
)
PZ_MEDIA = PZ_DIR / "media"

ZOMBOID_MODS = _env_path("PZMC_MODS", HOME / "Zomboid" / "mods")


# --- generator invocation ---------------------------------------------------
#
# THE C++ PORT SEAM.
#
# When PZMapMaker replaces the Java generator, this is the only thing that
# changes. Set PZMC_GENERATOR to the native binary and PZMC_GENERATOR_KIND to
# "native"; the orchestrator, the API and the UI are all unaffected.
#
#   Java   (default):  java -cp <out> pzformat.Probe giscells <args...>
#   Native:            <binary> giscells <args...>

GENERATOR_KIND = os.environ.get("PZMC_GENERATOR_KIND", "java").strip().lower()
GENERATOR_BINARY = os.environ.get("PZMC_GENERATOR", "").strip()
JAVA_MAIN_CLASS = os.environ.get("PZMC_JAVA_MAIN", "pzformat.Probe")
JAVA_HEAP = os.environ.get("PZMC_JAVA_HEAP", "4g")


def generator_argv(subcommand: str, args: list[str]) -> list[str]:
    """Build the argv for one generator invocation."""
    if GENERATOR_KIND == "native":
        if not GENERATOR_BINARY:
            raise RuntimeError(
                "PZMC_GENERATOR_KIND=native but PZMC_GENERATOR is not set."
            )
        return [GENERATOR_BINARY, subcommand, *args]

    return [
        "java",
        f"-Xmx{JAVA_HEAP}",
        "-cp",
        str(JAVA_OUT),
        JAVA_MAIN_CLASS,
        subcommand,
        *args,
    ]


# The mod name placement.html and modmap.html point at when no ?mod= is given.
# build.gradle.kts sets a Java 21 toolchain, so javac must be 21 or newer.
DEFAULT_MOD_NAME = os.environ.get("PZMC_DEFAULT_MOD", "PZGisImport")


# --- server -----------------------------------------------------------------

ORCH_HOST = os.environ.get("PZMC_HOST", "127.0.0.1")
ORCH_PORT = int(os.environ.get("PZMC_PORT", "8899"))

# pzmap2dzi's own server (map-output/html/server.py). The placement view and
# the mod map viewer are served from here, not from the orchestrator.
VIEWER_BASE = os.environ.get("PZMC_VIEWER", "http://localhost:8880").rstrip("/")


# --- GIS source routing -----------------------------------------------------
#
# Carried over from frontend.py. Two real processing paths today:
#   United States -> US government GIS sources
#   everything else -> OpenStreetMap / Overpass

COUNTRY_SOURCES: dict[str, str] = {
    "United States": "us",
    "Japan": "osm",
    "Canada": "osm",
    "Mexico": "osm",
    "Brazil": "osm",
    "Germany": "osm",
    "France": "osm",
    "United Kingdom": "osm",
    "Australia": "osm",
    "New Zealand": "osm",
    "Other": "osm",
}


# --- preflight --------------------------------------------------------------


@dataclass
class Check:
    name: str
    ok: bool
    detail: str
    fatal: bool


def preflight() -> list[Check]:
    """Check everything the pipeline needs. Reported by GET /api/doctor."""
    checks: list[Check] = []

    def add(name: str, ok: bool, detail: str, fatal: bool = True) -> None:
        checks.append(Check(name=name, ok=ok, detail=detail, fatal=fatal))

    add(
        "PZMapCreation root",
        JAVA_SRC.is_dir(),
        str(PROJECT_ROOT),
    )
    add(
        "Java sources",
        JAVA_SRC.is_dir(),
        str(JAVA_SRC),
    )

    if GENERATOR_KIND == "native":
        add(
            "Generator (native)",
            bool(GENERATOR_BINARY) and Path(GENERATOR_BINARY).is_file(),
            GENERATOR_BINARY or "PZMC_GENERATOR not set",
        )
    else:
        add("javac", shutil.which("javac") is not None, "javac on PATH")
        add("java", shutil.which("java") is not None, "java on PATH")
        add(
            "Compiled classes",
            JAVA_OUT.is_dir(),
            f"{JAVA_OUT} (run Compile if missing)",
            fatal=False,
        )

    add("pzgis fetch_gis.py", FETCH_GIS.is_file(), str(FETCH_GIS))
    add(
        "pzgis venv python",
        PZGIS_VENV_PY.is_file(),
        str(PZGIS_VENV_PY) + " (falls back to system python3)",
        fatal=False,
    )
    add("pzmap2dzi", PZMAP2DZI_DIR.is_dir(), str(PZMAP2DZI_DIR))
    add(
        "pzmap2dzi conf.yaml",
        PZMAP2DZI_CONF.is_file(),
        str(PZMAP2DZI_CONF) + " (mod maps are registered here)",
    )
    add(
        "pzmap2dzi venv python",
        PZMAP2DZI_VENV_PY.is_file(),
        str(PZMAP2DZI_VENV_PY),
        fatal=False,
    )
    add("PZ media", PZ_MEDIA.is_dir(), str(PZ_MEDIA))
    add("Zomboid mods dir", ZOMBOID_MODS.is_dir(), str(ZOMBOID_MODS))
    add(
        "map-output/html",
        MAP_OUTPUT_HTML.is_dir(),
        str(MAP_OUTPUT_HTML),
        fatal=False,
    )
    add("Workspace", True, str(WORKSPACE), fatal=False)

    return checks


def ensure_workspace() -> Path:
    WORKSPACE.mkdir(parents=True, exist_ok=True)
    return WORKSPACE

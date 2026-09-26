"""
pzorch.projects — the project/workspace model.

A project is a folder plus a project.json. It is the thing that turns the pile
of command-line flags in STATE.md section 0 into something resumable.

    workspace/<id>/
        project.json
        gis/
            area.geojson        <- pasted by the user
            buildings.geojson   <- produced by pzgis
            roads.geojson       <- produced by pzgis
            water.geojson       (optional)
            landuse.geojson     (optional)
        spawnpoints.lua         <- preserved across regenerates
        logs/<job-id>.log
"""

from __future__ import annotations

import json
import re
import shutil
import time
import unicodedata
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any

from . import config


# Pipeline state machine. Steps 3 and 5 of the wizard are the same generator
# call with a different origin, which is why PLACED sits between them.
STATES = ["NEW", "AREA_SET", "FETCHED", "GENERATED", "PLACED", "BUILT"]

MOD_NAME_RE = re.compile(r"^[A-Za-z0-9_]{1,64}$")

# The three layers the generator consumes, in the order it wants them.
REQUIRED_LAYERS = ["buildings.geojson", "roads.geojson", "area.geojson"]
OPTIONAL_LAYERS = ["water.geojson", "landuse.geojson"]


class ProjectError(Exception):
    """Something the user can fix. Surfaced as a 400."""


def slugify(text: str) -> str:
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    text = re.sub(r"[^A-Za-z0-9]+", "-", text).strip("-").lower()
    return text or "project"


def validate_mod_name(name: str) -> str:
    name = (name or "").strip()
    if not MOD_NAME_RE.match(name):
        raise ProjectError(
            "Mod name must be 1-64 characters, letters/digits/underscore only "
            "(it becomes a folder name under ~/Zomboid/mods). Got: "
            f"{name!r}"
        )
    return name


@dataclass
class Project:
    id: str
    name: str
    mod_name: str
    state: str = "NEW"
    country: str = "United States"
    origin_x: int | None = None
    origin_y: int | None = None
    # Spawn points are world tile coordinates. spawnpoints.lua uses the LEGACY
    # 300-tile grid (STATE.md section 2) -- the conversion lives in pipeline.py,
    # not here. Store raw world tiles.
    spawnpoints: list[dict[str, int]] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    last_error: str | None = None
    # Free-form notes from the last generate: building counts, roof tallies etc.
    stats: dict[str, Any] = field(default_factory=dict)

    # --- paths --------------------------------------------------------------

    @property
    def dir(self) -> Path:
        return config.WORKSPACE / self.id

    @property
    def gis_dir(self) -> Path:
        return self.dir / "gis"

    @property
    def logs_dir(self) -> Path:
        return self.dir / "logs"

    @property
    def json_path(self) -> Path:
        return self.dir / "project.json"

    @property
    def area_path(self) -> Path:
        return self.gis_dir / "area.geojson"

    @property
    def spawnpoints_path(self) -> Path:
        """Our preserved copy, NOT the one in the mod folder."""
        return self.dir / "spawnpoints.lua"

    @property
    def mod_dir(self) -> Path:
        return config.ZOMBOID_MODS / self.mod_name

    @property
    def mod_map_dir(self) -> Path:
        """~/Zomboid/mods/<mod>/common/media/maps/<mod> -- the $GISMAP of STATE.md."""
        return self.mod_dir / "common" / "media" / "maps" / self.mod_name

    @property
    def mod_spawnpoints_path(self) -> Path:
        return self.mod_map_dir / "spawnpoints.lua"

    @property
    def render_dir(self) -> Path:
        """Where pzmap2dzi writes this mod's tiles."""
        return config.MOD_MAPS_DIR / self.mod_name

    # --- layer discovery ----------------------------------------------------

    def layer_path(self, filename: str) -> Path:
        return self.gis_dir / filename

    def present_layers(self) -> list[str]:
        names = REQUIRED_LAYERS + OPTIONAL_LAYERS
        return [n for n in names if self.layer_path(n).is_file()]

    def missing_required_layers(self) -> list[str]:
        return [n for n in REQUIRED_LAYERS if not self.layer_path(n).is_file()]

    # --- state --------------------------------------------------------------

    def state_index(self) -> int:
        try:
            return STATES.index(self.state)
        except ValueError:
            return 0

    def at_least(self, state: str) -> bool:
        return self.state_index() >= STATES.index(state)

    def advance_to(self, state: str) -> None:
        """Move forward only. Re-running an earlier step never regresses state."""
        if STATES.index(state) > self.state_index():
            self.state = state

    # --- persistence --------------------------------------------------------

    def ensure_dirs(self) -> None:
        self.gis_dir.mkdir(parents=True, exist_ok=True)
        self.logs_dir.mkdir(parents=True, exist_ok=True)

    def save(self) -> None:
        self.ensure_dirs()
        self.updated_at = time.time()
        tmp = self.json_path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(asdict(self), indent=2), encoding="utf-8")
        tmp.replace(self.json_path)

    def to_api(self) -> dict[str, Any]:
        data = asdict(self)
        data["present_layers"] = self.present_layers()
        data["missing_required_layers"] = self.missing_required_layers()
        data["has_area"] = self.area_path.is_file()
        data["mod_dir"] = str(self.mod_dir)
        data["mod_built"] = self.mod_map_dir.is_dir()
        # The mod folder can exist with no tiles in it; what matters is that
        # the render job actually wrote its output directory.
        data["rendered"] = (self.render_dir / config.RENDER_CMD).is_dir()
        # The stock placement.html / modmap.html hardcode PZGisImport in their
        # DZI paths. The patched copies shipped alongside this package read a
        # ?mod= parameter and fall back to PZGisImport, so a project with any
        # mod name gets the right overlay.
        data["viewer_modmap"] = f"{config.VIEWER_BASE}/modmap.html?mod={self.mod_name}"
        data["viewer_placement"] = (
            f"{config.VIEWER_BASE}/placement.html?mod={self.mod_name}"
        )
        data["viewer_needs_patch"] = self.mod_name != config.DEFAULT_MOD_NAME
        return data


# --- store ------------------------------------------------------------------


def _load(path: Path) -> Project | None:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None

    known = {f for f in Project.__dataclass_fields__}
    filtered = {k: v for k, v in raw.items() if k in known}
    try:
        return Project(**filtered)
    except TypeError:
        return None


def list_projects() -> list[Project]:
    config.ensure_workspace()
    out: list[Project] = []
    for child in sorted(config.WORKSPACE.iterdir()):
        if not child.is_dir():
            continue
        proj = _load(child / "project.json")
        if proj is not None:
            out.append(proj)
    out.sort(key=lambda p: p.updated_at, reverse=True)
    return out


def get_project(project_id: str) -> Project:
    # Never let a path component out of the workspace.
    if "/" in project_id or ".." in project_id:
        raise ProjectError(f"Bad project id: {project_id!r}")
    proj = _load(config.WORKSPACE / project_id / "project.json")
    if proj is None:
        raise ProjectError(f"No such project: {project_id}")
    return proj


def default_mod_name() -> str:
    return config.DEFAULT_MOD_NAME


def create_project(name: str, mod_name: str, country: str) -> Project:
    name = (name or "").strip()
    if not name:
        raise ProjectError("Project needs a name.")

    mod_name = validate_mod_name(mod_name)

    if country not in config.COUNTRY_SOURCES:
        raise ProjectError(f"Unsupported country: {country}")

    config.ensure_workspace()

    base = slugify(name)
    project_id = base
    n = 2
    while (config.WORKSPACE / project_id).exists():
        project_id = f"{base}-{n}"
        n += 1

    proj = Project(id=project_id, name=name, mod_name=mod_name, country=country)
    proj.ensure_dirs()
    proj.save()
    return proj


def delete_project(project_id: str, remove_mod: bool = False) -> None:
    proj = get_project(project_id)

    if remove_mod and proj.mod_dir.is_dir():
        # Only ever delete inside ~/Zomboid/mods, and only a folder we own.
        mods_root = config.ZOMBOID_MODS.resolve()
        target = proj.mod_dir.resolve()
        if target.parent == mods_root and target != mods_root:
            shutil.rmtree(target, ignore_errors=True)
        if proj.render_dir.is_dir():
            shutil.rmtree(proj.render_dir, ignore_errors=True)

    shutil.rmtree(proj.dir, ignore_errors=True)


def set_area(proj: Project, country: str, geojson: dict) -> Project:
    if country not in config.COUNTRY_SOURCES:
        raise ProjectError(f"Unsupported country: {country}")

    if not isinstance(geojson, dict):
        raise ProjectError("GeoJSON must be a JSON object.")

    if "type" not in geojson:
        raise ProjectError("GeoJSON is missing its 'type' property.")

    proj.ensure_dirs()
    proj.area_path.write_text(json.dumps(geojson, indent=2), encoding="utf-8")
    proj.country = country
    proj.advance_to("AREA_SET")
    proj.last_error = None
    proj.save()
    return proj


def set_origin(proj: Project, origin_x: int, origin_y: int) -> Project:
    """World origin CELL coordinates, as read off placement.html."""
    for label, value in (("X", origin_x), ("Y", origin_y)):
        if not isinstance(value, int) or value < 0 or value > 300:
            raise ProjectError(
                f"Origin cell {label} must be an integer between 0 and 300. "
                f"Got: {value!r}"
            )
    proj.origin_x = origin_x
    proj.origin_y = origin_y
    proj.advance_to("PLACED")
    proj.save()
    return proj


def set_mod_name(proj: Project, mod_name: str) -> Project:
    mod_name = validate_mod_name(mod_name)
    if mod_name != proj.mod_name:
        proj.mod_name = mod_name
        # The mod folder and render folder are keyed by name, so a rename means
        # the previous build is no longer this project's build.
        if proj.at_least("GENERATED"):
            proj.state = "PLACED" if proj.origin_x is not None else "FETCHED"
    proj.save()
    return proj

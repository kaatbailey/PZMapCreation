"""
pzorch.server — FastAPI orchestrator for PZMapCreation.

Run it:

    cd ~/Documents/PZMapCreation
    python3 -m uvicorn pzorch.server:app --host 127.0.0.1 --port 8899 --reload

Then open http://127.0.0.1:8899/

The API is deliberately thin and stack-agnostic: JSON in, JSON out, SSE for
live job output. A future PZMapMaker front end in any language can drive the
same endpoints.
"""

from __future__ import annotations

import json
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse
from pydantic import BaseModel, Field

from . import config, pipeline, projects
from .projects import Project, ProjectError
from .runner import JobBusy, runner


app = FastAPI(title="PZ Map Orchestrator")

# placement.html and modmap.html are served by pzmap2dzi's own server on :8880
# and POST the chosen origin / spawn points back here on :8899. Different port
# means a different origin, so without CORS the browser drops those requests.
# Both servers are loopback-only, so allowing any origin costs nothing here.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

STATIC_DIR = Path(__file__).resolve().parent / "static"


# --- request models ---------------------------------------------------------


class CreateProjectRequest(BaseModel):
    name: str
    mod_name: str = Field(..., description="Folder name under ~/Zomboid/mods")
    country: str = "United States"


class AreaRequest(BaseModel):
    country: str
    geojson: dict


class OriginRequest(BaseModel):
    origin_x: int
    origin_y: int


class ModNameRequest(BaseModel):
    mod_name: str


class SpawnPointsRequest(BaseModel):
    lua: str


class GenerateRequest(BaseModel):
    origin_x: int | None = None
    origin_y: int | None = None
    final: bool = False


# --- helpers ----------------------------------------------------------------


def _project(project_id: str) -> Project:
    try:
        return projects.get_project(project_id)
    except ProjectError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


async def _launch(coro):
    """Start a pipeline step, mapping its errors onto HTTP codes."""
    try:
        job = await coro
    except JobBusy as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except ProjectError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return job.to_api()


# --- static -----------------------------------------------------------------


@app.get("/", response_class=HTMLResponse)
def index() -> HTMLResponse:
    page = STATIC_DIR / "index.html"
    if not page.is_file():
        raise HTTPException(status_code=500, detail=f"Missing UI file: {page}")
    return HTMLResponse(page.read_text(encoding="utf-8"))


# --- environment ------------------------------------------------------------


@app.get("/api/doctor")
def doctor() -> dict:
    checks = config.preflight()
    return {
        "ok": all(c.ok for c in checks if c.fatal),
        "generator_kind": config.GENERATOR_KIND,
        "viewer_base": config.VIEWER_BASE,
        "workspace": str(config.WORKSPACE),
        "checks": [
            {"name": c.name, "ok": c.ok, "detail": c.detail, "fatal": c.fatal}
            for c in checks
        ],
    }


@app.get("/api/defaults")
def defaults() -> dict:
    return {
        "mod_name": config.DEFAULT_MOD_NAME,
        "origin_x": pipeline.DEFAULT_ORIGIN[0],
        "origin_y": pipeline.DEFAULT_ORIGIN[1],
        "viewer_base": config.VIEWER_BASE,
        "orch_base": f"http://{config.ORCH_HOST}:{config.ORCH_PORT}",
    }


@app.get("/api/countries")
def countries() -> dict:
    return {
        "countries": [
            {"name": name, "source": source}
            for name, source in config.COUNTRY_SOURCES.items()
        ]
    }


# --- projects ---------------------------------------------------------------


@app.get("/api/projects")
def api_list_projects() -> dict:
    return {"projects": [p.to_api() for p in projects.list_projects()]}


@app.post("/api/projects")
def api_create_project(req: CreateProjectRequest) -> dict:
    try:
        proj = projects.create_project(req.name, req.mod_name, req.country)
    except ProjectError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return proj.to_api()


@app.get("/api/projects/{project_id}")
def api_get_project(project_id: str) -> dict:
    return _project(project_id).to_api()


@app.delete("/api/projects/{project_id}")
def api_delete_project(project_id: str, remove_mod: bool = False) -> dict:
    try:
        projects.delete_project(project_id, remove_mod=remove_mod)
    except ProjectError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return {"deleted": project_id, "removed_mod": remove_mod}


@app.put("/api/projects/{project_id}/area")
def api_set_area(project_id: str, req: AreaRequest) -> dict:
    proj = _project(project_id)
    try:
        proj = projects.set_area(proj, req.country, req.geojson)
    except ProjectError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return proj.to_api()


@app.put("/api/projects/{project_id}/origin")
def api_set_origin(project_id: str, req: OriginRequest) -> dict:
    proj = _project(project_id)
    try:
        proj = projects.set_origin(proj, req.origin_x, req.origin_y)
    except ProjectError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return proj.to_api()


@app.put("/api/projects/{project_id}/mod-name")
def api_set_mod_name(project_id: str, req: ModNameRequest) -> dict:
    proj = _project(project_id)
    try:
        proj = projects.set_mod_name(proj, req.mod_name)
    except ProjectError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return proj.to_api()


# --- spawn points -----------------------------------------------------------


@app.get("/api/projects/{project_id}/spawnpoints")
def api_get_spawnpoints(project_id: str) -> dict:
    proj = _project(project_id)
    path = proj.spawnpoints_path
    return {
        "preserved": path.is_file(),
        "lua": path.read_text(encoding="utf-8") if path.is_file() else "",
        "mod_file_exists": proj.mod_spawnpoints_path.is_file(),
    }


@app.put("/api/projects/{project_id}/spawnpoints")
def api_set_spawnpoints(project_id: str, req: SpawnPointsRequest) -> dict:
    proj = _project(project_id)
    try:
        pipeline.set_spawnpoints_lua(proj, req.lua)
    except ProjectError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    proj.save()
    applied = pipeline.restore_spawnpoints(proj)
    return {"saved": True, "applied_to_mod": applied}


@app.post("/api/projects/{project_id}/spawnpoints/capture")
def api_capture_spawnpoints(project_id: str) -> dict:
    """Pull the mod's current spawnpoints.lua into the project and keep it."""
    proj = _project(project_id)
    saved = pipeline.capture_spawnpoints(proj)
    if not saved:
        raise HTTPException(
            status_code=404,
            detail=f"No spawnpoints.lua at {proj.mod_spawnpoints_path}",
        )
    return {"captured": True}


# --- pipeline steps ---------------------------------------------------------


@app.post("/api/projects/{project_id}/compile")
async def api_compile(project_id: str) -> dict:
    proj = _project(project_id)
    return await _launch(pipeline.compile_java(proj))


@app.post("/api/projects/{project_id}/fetch")
async def api_fetch(project_id: str) -> dict:
    proj = _project(project_id)
    return await _launch(pipeline.fetch_gis(proj))


@app.post("/api/projects/{project_id}/generate")
async def api_generate(project_id: str, req: GenerateRequest) -> dict:
    proj = _project(project_id)
    return await _launch(
        pipeline.generate(proj, req.origin_x, req.origin_y, final=req.final)
    )


@app.post("/api/projects/{project_id}/render")
async def api_render(project_id: str) -> dict:
    proj = _project(project_id)
    return await _launch(pipeline.render(proj))


# --- jobs -------------------------------------------------------------------


@app.get("/api/jobs/current")
def api_current_job() -> dict:
    job = runner.current()
    return {"job": job.to_api() if job else None}


@app.get("/api/jobs")
def api_jobs(project_id: str | None = None, limit: int = 20) -> dict:
    return {"jobs": [j.to_api() for j in runner.recent(project_id, limit)]}


@app.get("/api/jobs/{job_id}")
def api_job(job_id: str) -> dict:
    job = runner.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"No such job: {job_id}")
    return job.to_api()


@app.post("/api/jobs/{job_id}/cancel")
async def api_cancel_job(job_id: str) -> dict:
    ok = await runner.cancel(job_id)
    if not ok:
        raise HTTPException(status_code=409, detail="Job is not running.")
    return {"cancelled": job_id}


@app.get("/api/jobs/{job_id}/log")
def api_job_log(job_id: str):
    job = runner.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"No such job: {job_id}")
    path = Path(job.log_path)
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Log file not written yet.")
    return FileResponse(path, media_type="text/plain", filename=path.name)


@app.get("/api/jobs/{job_id}/stream")
async def api_job_stream(job_id: str, request: Request, from_index: int = 0):
    """Server-sent events: one event per output line, then a status event."""
    job = runner.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail=f"No such job: {job_id}")

    async def events():
        async for item in runner.stream(job, from_index):
            if await request.is_disconnected():
                break
            yield f"data: {json.dumps(item)}\n\n"

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


# --- entrypoint -------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn

    config.ensure_workspace()
    uvicorn.run(
        "pzorch.server:app",
        host=config.ORCH_HOST,
        port=config.ORCH_PORT,
        reload=True,
    )

"""
pzorch.runner — one job at a time, streamed line by line.

The pipeline is inherently serial (fetch -> generate -> render) and every step
is heavy, so the orchestrator runs exactly one job at a time and rejects a
second. That is honest about what the machine is doing and keeps the log pane
unambiguous.

Output is merged stderr-into-stdout deliberately: the generator writes its
progress to stderr (STATE.md section 0 pipes 2>&1 before grepping) and a split
stream would interleave wrongly in the UI.
"""

from __future__ import annotations

import asyncio
import os
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence


MAX_LINES = 5000  # kept in memory per job; the full log always goes to disk


@dataclass
class Job:
    id: str
    project_id: str
    step: str
    argv: list[str]
    cwd: str
    log_path: str
    status: str = "running"  # running | done | failed | cancelled
    returncode: int | None = None
    started_at: float = field(default_factory=time.time)
    finished_at: float | None = None
    lines: list[str] = field(default_factory=list)
    truncated: bool = False
    _event: asyncio.Event = field(default_factory=asyncio.Event, repr=False)
    _proc: asyncio.subprocess.Process | None = field(default=None, repr=False)

    def append(self, line: str) -> None:
        if len(self.lines) >= MAX_LINES:
            del self.lines[: MAX_LINES // 5]
            self.truncated = True
        self.lines.append(line)
        self._event.set()

    def finish(self, status: str, returncode: int | None) -> None:
        self.status = status
        self.returncode = returncode
        self.finished_at = time.time()
        self._event.set()

    @property
    def running(self) -> bool:
        return self.status == "running"

    def to_api(self) -> dict:
        return {
            "id": self.id,
            "project_id": self.project_id,
            "step": self.step,
            "status": self.status,
            "returncode": self.returncode,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "command": " ".join(self.argv),
            "cwd": self.cwd,
            "log_path": self.log_path,
            "truncated": self.truncated,
            "line_count": len(self.lines),
        }


class JobBusy(Exception):
    """A job is already running."""


class Runner:
    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._current: Job | None = None
        self._lock = asyncio.Lock()
        # asyncio holds only a WEAK reference to a running task, so a task
        # nobody keeps can be collected mid-flight. Hold them here and discard
        # on completion.
        self._tasks: set[asyncio.Task] = set()

    # --- accessors ----------------------------------------------------------

    def get(self, job_id: str) -> Job | None:
        return self._jobs.get(job_id)

    def current(self) -> Job | None:
        if self._current is not None and self._current.running:
            return self._current
        return None

    def recent(self, project_id: str | None = None, limit: int = 20) -> list[Job]:
        jobs = sorted(self._jobs.values(), key=lambda j: j.started_at, reverse=True)
        if project_id:
            jobs = [j for j in jobs if j.project_id == project_id]
        return jobs[:limit]

    # --- launching ----------------------------------------------------------

    async def start(
        self,
        *,
        project_id: str,
        step: str,
        argv: Sequence[str],
        cwd: Path,
        log_path: Path,
        env_extra: dict[str, str] | None = None,
        on_line=None,
        on_finish=None,
    ) -> Job:
        """Launch a job. Raises JobBusy if one is already running."""
        async with self._lock:
            if self.current() is not None:
                raise JobBusy(
                    f"A job is already running: {self._current.step} "
                    f"(project {self._current.project_id})"
                )

            job = Job(
                id=uuid.uuid4().hex[:12],
                project_id=project_id,
                step=step,
                argv=[str(a) for a in argv],
                cwd=str(cwd),
                log_path=str(log_path),
            )
            self._jobs[job.id] = job
            self._current = job

        task = asyncio.create_task(
            self._run(job, cwd, log_path, env_extra or {}, on_line, on_finish)
        )
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return job

    async def _run(
        self,
        job: Job,
        cwd: Path,
        log_path: Path,
        env_extra: dict[str, str],
        on_line,
        on_finish,
    ) -> None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        env = {**os.environ, **env_extra}

        header = (
            f"$ cd {cwd}\n"
            f"$ {' '.join(job.argv)}\n"
            f"{'-' * 70}\n"
        )
        job.append(header.rstrip("\n"))

        try:
            with log_path.open("w", encoding="utf-8", errors="replace") as log:
                log.write(header)
                log.flush()

                proc = await asyncio.create_subprocess_exec(
                    *job.argv,
                    cwd=str(cwd),
                    env=env,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.STDOUT,
                )
                job._proc = proc

                assert proc.stdout is not None
                while True:
                    raw = await proc.stdout.readline()
                    if not raw:
                        break
                    line = raw.decode("utf-8", errors="replace").rstrip("\n")
                    log.write(line + "\n")
                    log.flush()
                    job.append(line)
                    if on_line is not None:
                        try:
                            on_line(job, line)
                        except Exception:  # a parser bug must not kill the job
                            pass

                rc = await proc.wait()
                log.write(f"{'-' * 70}\nexit {rc}\n")

        except FileNotFoundError as exc:
            job.append(f"!! executable not found: {exc}")
            job.finish("failed", 127)
        except Exception as exc:  # noqa: BLE001 - surfaced to the UI
            job.append(f"!! {type(exc).__name__}: {exc}")
            job.finish("failed", -1)
        else:
            job.finish("done" if rc == 0 else "failed", rc)

        if on_finish is not None:
            try:
                on_finish(job)
            except Exception as exc:  # noqa: BLE001
                job.append(f"!! post-step error: {type(exc).__name__}: {exc}")

        if self._current is job:
            self._current = None

    async def cancel(self, job_id: str) -> bool:
        job = self._jobs.get(job_id)
        if job is None or not job.running or job._proc is None:
            return False
        try:
            job._proc.terminate()
        except ProcessLookupError:
            return False
        job.append("!! cancelled by user")
        job.finish("cancelled", None)
        return True

    # --- streaming ----------------------------------------------------------

    async def stream(self, job: Job, from_index: int = 0):
        """Yield lines as they arrive, then a terminal status. Async generator."""
        index = from_index
        while True:
            while index < len(job.lines):
                yield {"type": "line", "index": index, "text": job.lines[index]}
                index += 1

            if not job.running:
                yield {
                    "type": "status",
                    "status": job.status,
                    "returncode": job.returncode,
                }
                return

            job._event.clear()
            try:
                await asyncio.wait_for(job._event.wait(), timeout=15.0)
            except asyncio.TimeoutError:
                # Keepalive so proxies and browsers do not drop the connection.
                yield {"type": "ping"}


runner = Runner()

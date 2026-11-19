from __future__ import annotations

import os
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

KINDS = {"REMOTE_GIT_SYNC", "AI_SUMMARY", "PDF_INDEX"}
DISK_LIMIT = 128 * 1024**2


def execute(kind, raw, config, check_grant):
    if len(raw)>config.max_input_bytes:
        raise ValueError("INPUT_LIMIT")
    # The Docker worker processes one job at a time. A bounded tmpfs constrains all
    # files together (including packs and indices), even between supervisor polls.
    base = None
    if kind == "REMOTE_GIT_SYNC":
        base = "/dev/shm"
        if not Path(base).is_mount():
            raise ValueError("BOUNDED_TMPFS_REQUIRED")
        fs = os.statvfs(base)
        if fs.f_blocks * fs.f_frsize > DISK_LIMIT:
            raise ValueError("BOUNDED_TMPFS_REQUIRED")
    with tempfile.TemporaryDirectory(dir=base,prefix="orbit-task-") as directory:
        env = {"PATH":os.defpath,"HOME":directory,"TMPDIR":directory,
               "PYTHONPATH":str(Path(__file__).resolve().parents[1]),
               "PYTHONDONTWRITEBYTECODE":"1","PYTHONNOUSERSITE":"1",
               "HTTPS_PROXY":os.getenv("HTTPS_PROXY",""),
               "ORBIT_GIT_TRUSTED_HOSTS":",".join(h.strip().lower() for h in os.getenv(
                   "ORBIT_GIT_TRUSTED_HOSTS","github.com,gitlab.com,bitbucket.org").split(","))}
        with subprocess.Popen([sys.executable,"-m","orbit_worker.isolated_task",kind,directory,str(DISK_LIMIT),
                               str(config.task_timeout_seconds),str(config.max_output_bytes)],cwd=directory,env=env,
                              stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,
                              start_new_session=True,shell=False) as process:
            deadline = time.monotonic() + config.task_timeout_seconds
            try:
                while True:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise TimeoutError("TASK_TIMEOUT")
                    try:
                        output,_ = process.communicate(raw, timeout=min(remaining,1.0))
                        break
                    except subprocess.TimeoutExpired:
                        raw = None
                        check_grant()
                if process.returncode == 65:
                    raise ValueError("INPUT_OR_RESOURCE_REJECTED")
                if process.returncode != 0:
                    raise RuntimeError("ISOLATED_TASK_FAILED")
                if len(output)>config.max_output_bytes:
                    raise ValueError("OUTPUT_LIMIT")
                return output
            finally:
                try:
                    os.killpg(process.pid,signal.SIGKILL)
                except ProcessLookupError:
                    pass

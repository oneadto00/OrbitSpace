"""One short-lived, resource-limited process group per PDF, summary, or remote repository."""
from __future__ import annotations

import json
import os
from pathlib import Path
import resource
import signal
import sys


def main():
    kind, directory, budget, timeout, output = sys.argv[1:]
    budget, timeout, output = int(budget), int(timeout), int(output)
    # Kill the entire process group, including curl/index-pack, on fatal timeout.
    signal.signal(signal.SIGALRM, lambda *_: os.killpg(0,signal.SIGKILL))
    signal.alarm(timeout)
    resource.setrlimit(resource.RLIMIT_CORE,(0,0))
    resource.setrlimit(resource.RLIMIT_FSIZE,(budget,budget))
    resource.setrlimit(resource.RLIMIT_CPU,(timeout,timeout+1))
    if sys.platform.startswith("linux"):
        resource.setrlimit(resource.RLIMIT_AS,(384*1024**2,384*1024**2))
    raw = sys.stdin.buffer.read()
    if kind == "REMOTE_GIT_SYNC":
        from orbit_worker.remote_git import observe
        result = observe(json.loads(raw), Path(directory), output)
    elif kind == "PDF_INDEX":
        from orbit_worker.pdf_index import index_pdf
        result = index_pdf(raw, output)
    elif kind == "AI_SUMMARY":
        from orbit_worker.summary_processor import summarize
        result = summarize(json.loads(raw), output)
    else:
        raise ValueError("UNSUPPORTED_JOB_TYPE")
    sys.stdout.buffer.write(result)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, RuntimeError, MemoryError):
        # Do not return parser diagnostics, repository names, credentials, or input text.
        sys.exit(65)

from __future__ import annotations

import logging
import os

from orbit_worker.config import WorkerConfig
from orbit_worker.stream_consumer import StreamWorkerLoop


def configure_logging() -> None:
    level_name = os.environ.get("ORBIT_WORKER_LOG_LEVEL", "INFO").upper()
    level = getattr(logging, level_name, logging.INFO)
    logging.basicConfig(
        level=level,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )


def main() -> None:
    configure_logging()
    config = WorkerConfig.from_env()
    worker = StreamWorkerLoop(config)
    worker.run_forever()


if __name__ == "__main__":
    main()

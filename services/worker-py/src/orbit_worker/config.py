from __future__ import annotations

import os
import socket
from dataclasses import dataclass


@dataclass(frozen=True)
class WorkerConfig:
    redis_host: str
    redis_port: int
    redis_password: str
    stream_key: str
    consumer_group: str
    consumer_name: str
    server_base_url: str
    hmac_secret: str
    s3_endpoint: str
    s3_bucket: str
    s3_access_key: str
    s3_secret_key: str
    s3_region: str
    lease_seconds: int
    task_timeout_seconds: int
    max_input_bytes: int
    max_output_bytes: int
    max_image_pixels: int
    thumbnail_max_dim: int
    block_ms: int
    claim_min_idle_ms: int

    @classmethod
    def from_env(cls) -> WorkerConfig:
        hmac_secret = (
            os.environ.get("ORBIT_INTERNAL_HMAC_SECRET")
            or os.environ.get("ORBIT_INTEGRATION_HMAC_SECRET")
            or os.environ.get("ORBIT_HMAC_SECRET")
            or "local-only-internal-hmac-secret"
        )
        default_consumer = "worker-{}-{}".format(socket.gethostname(), os.getpid())
        task_timeout = max(5, int(os.environ.get("ORBIT_WORKER_TASK_TIMEOUT_SECONDS", "30")))
        lease_seconds = max(task_timeout + 15, int(os.environ.get("ORBIT_WORKER_LEASE_SECONDS", "90")))
        return cls(
            redis_host=os.environ.get("SPRING_DATA_REDIS_HOST", os.environ.get("REDIS_HOST", "localhost")),
            redis_port=int(os.environ.get("SPRING_DATA_REDIS_PORT", os.environ.get("REDIS_PORT", "6379"))),
            redis_password=os.environ.get("REDIS_PASSWORD", ""),
            stream_key=os.environ.get("ORBIT_WORKER_STREAM_KEY", "orbit:async:jobs"),
            consumer_group=os.environ.get("ORBIT_WORKER_CONSUMER_GROUP", "orbit-worker-py"),
            consumer_name=os.environ.get("ORBIT_WORKER_CONSUMER_NAME", default_consumer),
            server_base_url=os.environ.get("ORBIT_SERVER_INTERNAL_URL", "http://server:8080").rstrip("/"),
            hmac_secret=hmac_secret,
            s3_endpoint=os.environ.get("ORBIT_S3_ENDPOINT", "http://minio:9000"),
            s3_bucket=os.environ.get("ORBIT_S3_BUCKET", "orbitspace-private"),
            s3_access_key=os.environ.get("ORBIT_S3_ACCESS_KEY", os.environ.get("MINIO_ROOT_USER", "orbit-local")),
            s3_secret_key=os.environ.get(
                "ORBIT_S3_SECRET_KEY", os.environ.get("MINIO_ROOT_PASSWORD", "local-only-minio-password")
            ),
            s3_region=os.environ.get("ORBIT_S3_REGION", "us-east-1"),
            lease_seconds=lease_seconds,
            task_timeout_seconds=task_timeout,
            max_input_bytes=int(os.environ.get("ORBIT_WORKER_MAX_INPUT_BYTES", str(20 * 1024 * 1024))),
            max_output_bytes=int(os.environ.get("ORBIT_WORKER_MAX_OUTPUT_BYTES", str(8 * 1024 * 1024))),
            max_image_pixels=int(os.environ.get("ORBIT_WORKER_MAX_IMAGE_PIXELS", "25000000")),
            thumbnail_max_dim=int(os.environ.get("ORBIT_WORKER_THUMBNAIL_MAX_DIM", "320")),
            block_ms=int(os.environ.get("ORBIT_WORKER_BLOCK_MS", "2000")),
            claim_min_idle_ms=int(
                os.environ.get("ORBIT_WORKER_CLAIM_MIN_IDLE_MS", str(max(60000, lease_seconds * 1000)))
            ),
        )

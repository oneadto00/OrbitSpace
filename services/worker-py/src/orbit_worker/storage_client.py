from __future__ import annotations

import io

import boto3
from botocore.config import Config as BotoConfig

from orbit_worker.config import WorkerConfig


class InputLimitExceeded(ValueError):
    pass


class ObjectStorageReader:
    def __init__(self, config: WorkerConfig) -> None:
        self._bucket = config.s3_bucket
        self._max_input_bytes = config.max_input_bytes
        self._s3 = boto3.client(
            "s3",
            endpoint_url=config.s3_endpoint,
            aws_access_key_id=config.s3_access_key,
            aws_secret_access_key=config.s3_secret_key,
            region_name=config.s3_region,
            config=BotoConfig(
                s3={"addressing_style": "path"},
                connect_timeout=5,
                read_timeout=20,
                retries={"max_attempts": 2},
            ),
        )

    def read_bounded(self, object_key: str) -> bytes:
        if not object_key or ".." in object_key:
            raise ValueError("INVALID_OBJECT_KEY")
        resp = self._s3.get_object(Bucket=self._bucket, Key=object_key)
        content_length = resp.get("ContentLength")
        if isinstance(content_length, int) and content_length > self._max_input_bytes:
            resp["Body"].close()
            raise InputLimitExceeded("INPUT_TOO_LARGE")

        buf = io.BytesIO()
        body = resp["Body"]
        try:
            total = 0
            while True:
                chunk = body.read(65536)
                if not chunk:
                    break
                total += len(chunk)
                if total > self._max_input_bytes:
                    raise InputLimitExceeded("INPUT_TOO_LARGE")
                buf.write(chunk)
        finally:
            body.close()
        return buf.getvalue()

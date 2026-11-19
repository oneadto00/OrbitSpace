from __future__ import annotations

import json
import logging
import signal
import time
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeoutError
from typing import Any, Dict, List, Optional, Tuple

import redis

from orbit_worker.callback_client import CallbackError, ClaimedJob, InternalCallbackClient
from orbit_worker.config import WorkerConfig
from orbit_worker import isolation
from orbit_worker.diff_enrichment import enrich_diff_payload
from orbit_worker.storage_client import InputLimitExceeded, ObjectStorageReader
from orbit_worker.thumbnail_processor import ThumbnailProcessingError, generate_safe_thumbnail

LOG = logging.getLogger("orbit_worker")

JOB_IMAGE_THUMBNAIL = "IMAGE_THUMBNAIL"
JOB_DIFF_ENRICHMENT = "DIFF_ENRICHMENT"


class StreamWorkerLoop:
    def __init__(self, config: WorkerConfig) -> None:
        self._config = config
        self._running = True
        self._redis = redis.Redis(
            host=config.redis_host,
            port=config.redis_port,
            password=config.redis_password or None,
            decode_responses=True,
            socket_timeout=10,
            socket_connect_timeout=5,
        )
        self._callbacks = InternalCallbackClient(config)
        self._storage = ObjectStorageReader(config)
        self._executor = ThreadPoolExecutor(max_workers=1)

    def stop(self, *_args: Any) -> None:
        self._running = False

    def ensure_consumer_group(self) -> None:
        try:
            self._redis.xgroup_create(
                name=self._config.stream_key,
                groupname=self._config.consumer_group,
                id="0",
                mkstream=True,
            )
        except redis.ResponseError as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    def run_forever(self) -> None:
        signal.signal(signal.SIGTERM, self.stop)
        signal.signal(signal.SIGINT, self.stop)
        self.ensure_consumer_group()
        LOG.info(
            "Worker started stream=%s group=%s consumer=%s",
            self._config.stream_key,
            self._config.consumer_group,
            self._config.consumer_name,
        )
        autoclaim_cursor = "0-0"
        try:
            while self._running:
                try:
                    autoclaim_cursor = self._reclaim_pending(autoclaim_cursor)
                    entries = self._redis.xreadgroup(
                        groupname=self._config.consumer_group,
                        consumername=self._config.consumer_name,
                        streams={self._config.stream_key: ">"},
                        count=8,
                        block=self._config.block_ms,
                    )
                    if not entries:
                        continue
                    for _stream_name, messages in entries:
                        for message_id, fields in messages:
                            if not self._running:
                                break
                            self._handle_message(message_id, fields)
                except redis.RedisError as exc:
                    LOG.warning("Redis poll deferred error=%s", type(exc).__name__)
                    time.sleep(2.0)
        finally:
            self._executor.shutdown(wait=False, cancel_futures=True)
            self._callbacks.close()

    def _reclaim_pending(self, start_id: str) -> str:
        try:
            result = self._redis.xautoclaim(
                name=self._config.stream_key,
                groupname=self._config.consumer_group,
                consumername=self._config.consumer_name,
                min_idle_time=self._config.claim_min_idle_ms,
                start_id=start_id,
                count=8,
            )
        except redis.ResponseError:
            return "0-0"
        if not result or len(result) < 2:
            return "0-0"
        next_cursor = str(result[0])
        claimed_msgs: List[Tuple[str, Dict[str, str]]] = result[1]
        for message_id, fields in claimed_msgs:
            if not self._running:
                break
            self._handle_message(message_id, fields)
        return next_cursor

    def _handle_message(self, message_id: str, fields: Dict[str, str]) -> None:
        job_id = str(fields.get("jobId", "")).strip()
        if not job_id:
            self._ack(message_id)
            return

        claimed: Optional[ClaimedJob] = None
        try:
            claimed = self._callbacks.claim_job(
                job_id=job_id,
                worker_id=self._config.consumer_name,
                lease_seconds=self._config.lease_seconds,
            )
        except CallbackError as exc:
            if exc.status_code == 404:
                LOG.warning("Job missing jobId=%s status=%d", job_id, exc.status_code)
                self._ack(message_id)
            else:
                LOG.warning("Claim rejected jobId=%s code=%s", job_id, exc.code)
            return
        except Exception as exc:
            LOG.warning("Claim deferred jobId=%s error=%s", job_id, type(exc).__name__)
            return

        if not claimed.claimed or not claimed.lease_id:
            # Already claimed/completed/cancelled; acknowledge redundant stream delivery.
            self._ack(message_id)
            return

        LOG.info(
            "Job claimed jobId=%s resourceId=%s jobType=%s",
            claimed.job_id,
            claimed.resource_id,
            claimed.job_type,
        )

        if claimed.job_type not in {JOB_IMAGE_THUMBNAIL, JOB_DIFF_ENRICHMENT} | isolation.KINDS:
            self._safe_report_failure_and_ack(
                message_id, claimed, retryable=False, failure_code="UNSUPPORTED_JOB_TYPE"
            )
            return

        try:
            output_bytes = self._process_with_timeout(claimed)
            stored = self._callbacks.upload_output(claimed.job_id, output_bytes)
            acceptance = self._callbacks.submit_result(claimed, stored)
            LOG.info(
                "Job callback completed jobId=%s resourceId=%s acceptance=%s",
                claimed.job_id,
                claimed.resource_id,
                acceptance,
            )
            if acceptance in {"ACCEPTED", "ALREADY_ACCEPTED", "STALE"}:
                self._ack(message_id)
        except ThumbnailProcessingError as exc:
            LOG.warning(
                "Thumbnail rejected jobId=%s resourceId=%s code=%s",
                claimed.job_id,
                claimed.resource_id,
                exc.code,
            )
            self._safe_report_failure_and_ack(
                message_id, claimed, retryable=False, failure_code=exc.code
            )
        except InputLimitExceeded:
            LOG.warning(
                "Input limit exceeded jobId=%s resourceId=%s",
                claimed.job_id,
                claimed.resource_id,
            )
            self._safe_report_failure_and_ack(
                message_id, claimed, retryable=False, failure_code="INPUT_TOO_LARGE"
            )
        except ValueError:
            self._safe_report_failure_and_ack(message_id, claimed, retryable=False, failure_code="INPUT_OR_RESOURCE_REJECTED")
        except FutureTimeoutError:
            LOG.warning(
                "Task timed out jobId=%s resourceId=%s",
                claimed.job_id,
                claimed.resource_id,
            )
            self._safe_report_failure_and_ack(
                message_id,
                claimed,
                retryable=True,
                failure_code="TASK_TIMEOUT",
                retry_delay_seconds=30,
            )
        except CallbackError as exc:
            LOG.warning(
                "Callback error jobId=%s resourceId=%s code=%s status=%d",
                claimed.job_id,
                claimed.resource_id,
                exc.code,
                exc.status_code,
            )
            if exc.status_code in {400, 403, 404, 410}:
                self._safe_report_failure_and_ack(message_id, claimed, retryable=False, failure_code="JOB_ACCESS_REJECTED")
        except Exception as exc:
            LOG.warning(
                "Transient processing failure jobId=%s resourceId=%s error=%s",
                claimed.job_id,
                claimed.resource_id,
                type(exc).__name__,
            )
            self._safe_report_failure_and_ack(
                message_id,
                claimed,
                retryable=True,
                failure_code="WORKER_TRANSIENT_ERROR",
                retry_delay_seconds=30,
            )

    def _process_with_timeout(self, claimed: ClaimedJob) -> bytes:
        if claimed.job_type in isolation.KINDS:
            manifest = self._callbacks.authorized_input(claimed)
            if claimed.job_type == "PDF_INDEX":
                raw = self._storage.read_bounded(manifest["objectKey"])
            else:
                raw = json.dumps(manifest,ensure_ascii=False).encode()
                manifest.clear()
            return isolation.execute(claimed.job_type,raw,self._config,
                                     lambda: self._callbacks.authorized_input(claimed, check_only=True))
        future = self._executor.submit(self._execute_job, claimed)
        try:
            return future.result(timeout=self._config.task_timeout_seconds)
        except FutureTimeoutError:
            future.cancel()
            raise

    def _execute_job(self, claimed: ClaimedJob) -> bytes:
        raw_input = self._storage.read_bounded(claimed.input_object_key)
        if claimed.job_type == JOB_IMAGE_THUMBNAIL:
            return generate_safe_thumbnail(
                raw_bytes=raw_input,
                max_image_pixels=self._config.max_image_pixels,
                max_dim=self._config.thumbnail_max_dim,
                max_output_bytes=self._config.max_output_bytes,
            )
        if claimed.job_type == JOB_DIFF_ENRICHMENT:
            return enrich_diff_payload(
                raw_bytes=raw_input,
                max_output_bytes=self._config.max_output_bytes,
            )
        raise ValueError("UNSUPPORTED_JOB_TYPE")

    def _safe_report_failure_and_ack(
        self,
        message_id: str,
        claimed: ClaimedJob,
        retryable: bool,
        failure_code: str,
        retry_delay_seconds: Optional[int] = None,
    ) -> None:
        if not claimed.lease_id:
            return
        try:
            self._callbacks.report_failure(
                job_id=claimed.job_id,
                lease_id=claimed.lease_id,
                retryable=retryable,
                failure_code=failure_code,
                retry_delay_seconds=retry_delay_seconds,
            )
            self._ack(message_id)
        except Exception as exc:
            LOG.warning(
                "Failure report deferred jobId=%s resourceId=%s error=%s",
                claimed.job_id,
                claimed.resource_id,
                type(exc).__name__,
            )

    def _ack(self, message_id: str) -> None:
        try:
            self._redis.xack(
                self._config.stream_key, self._config.consumer_group, message_id
            )
        except redis.RedisError as exc:
            LOG.warning("XACK deferred error=%s", type(exc).__name__)

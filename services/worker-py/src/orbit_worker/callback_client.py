from __future__ import annotations

import hashlib
import hmac
import json
import time
import uuid
from dataclasses import dataclass
from typing import Any, Dict, Optional

import httpx

from orbit_worker.config import WorkerConfig


class CallbackError(RuntimeError):
    def __init__(self, status_code: int, code: str) -> None:
        super().__init__("Callback failed: HTTP {} ({})".format(status_code, code))
        self.status_code = status_code
        self.code = code


@dataclass(frozen=True)
class ClaimedJob:
    claimed: bool
    job_id: str
    job_type: str
    space_id: str
    resource_id: str
    actor_id: str
    input_object_key: str
    input_version: int
    resource_lifecycle_version: int
    lease_id: Optional[str]
    lease_until: Optional[str]


@dataclass(frozen=True)
class StoredOutput:
    object_key: str
    size_bytes: int
    sha256: str


class InternalCallbackClient:
    def __init__(self, config: WorkerConfig) -> None:
        self._base_url = config.server_base_url
        self._secret = config.hmac_secret.encode("utf-8")
        self._client = httpx.Client(
            base_url=self._base_url,
            timeout=httpx.Timeout(20.0, connect=5.0),
            follow_redirects=False,
        )

    def close(self) -> None:
        self._client.close()

    def _signed_headers(self, method: str, path: str, body: bytes, content_type: str) -> Dict[str, str]:
        ts = str(int(time.time()))
        nonce = uuid.uuid4().hex
        body_digest = hashlib.sha256(body).hexdigest()
        canonical = "{}\n{}\n{}\n{}\n{}".format(method.upper(), path, ts, nonce, body_digest)
        sig = hmac.new(self._secret, canonical.encode("utf-8"), hashlib.sha256).hexdigest()
        return {
            "Content-Type": content_type,
            "X-Orbit-Timestamp": ts,
            "X-Orbit-Nonce": nonce,
            "X-Orbit-Signature": sig,
        }

    def _post(self, path: str, body: bytes, content_type: str, extra_headers: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
        headers = self._signed_headers("POST", path, body, content_type)
        if extra_headers:
            headers.update(extra_headers)
        resp = self._client.post(path, content=body, headers=headers)
        if resp.status_code >= 400:
            code = "HTTP_ERROR"
            try:
                parsed = resp.json()
                if isinstance(parsed, dict) and isinstance(parsed.get("code"), str):
                    code = parsed["code"]
            except Exception:
                pass
            raise CallbackError(resp.status_code, code)
        data = resp.json()
        if not isinstance(data, dict):
            raise RuntimeError("Unexpected callback response shape")
        return data

    def claim_job(self, job_id: str, worker_id: str, lease_seconds: int) -> ClaimedJob:
        path = "/internal/v1/jobs/{}/claim".format(job_id)
        payload = json.dumps({"workerId": worker_id, "leaseSeconds": lease_seconds}).encode("utf-8")
        data = self._post(path, payload, "application/json")
        return ClaimedJob(
            claimed=bool(data.get("claimed", False)),
            job_id=str(data.get("jobId", job_id)),
            job_type=str(data.get("jobType", "")),
            space_id=str(data.get("spaceId", "")),
            resource_id=str(data.get("resourceId", "")),
            actor_id=str(data.get("actorId", "")),
            input_object_key=str(data.get("inputObjectKey", "")),
            input_version=int(data.get("inputVersion", 0)),
            resource_lifecycle_version=int(data.get("resourceLifecycleVersion", 0)),
            lease_id=data.get("leaseId"),
            lease_until=data.get("leaseUntil"),
        )

    def upload_output(self, job_id: str, output_bytes: bytes) -> StoredOutput:
        path = "/internal/v1/jobs/{}/output".format(job_id)
        sha256_hex = hashlib.sha256(output_bytes).hexdigest()
        data = self._post(
            path,
            output_bytes,
            "application/octet-stream",
            extra_headers={"X-Orbit-Content-Sha256": sha256_hex},
        )
        return StoredOutput(
            object_key=str(data["objectKey"]),
            size_bytes=int(data["sizeBytes"]),
            sha256=str(data["sha256"]),
        )

    def submit_result(self, claimed: ClaimedJob, stored: StoredOutput) -> str:
        if not claimed.lease_id:
            raise ValueError("Missing lease_id for result submission")
        path = "/internal/v1/jobs/{}/result".format(claimed.job_id)
        payload = json.dumps(
            {
                "leaseId": claimed.lease_id,
                "expectedJobStatus": "RUNNING",
                "inputVersion": claimed.input_version,
                "resourceLifecycleVersion": claimed.resource_lifecycle_version,
                "resultObjectKey": stored.object_key,
                "expectedSizeBytes": stored.size_bytes,
                "expectedSha256": stored.sha256,
            }
        ).encode("utf-8")
        data = self._post(path, payload, "application/json")
        return str(data.get("acceptance", "STALE"))

    def report_failure(
        self,
        job_id: str,
        lease_id: str,
        retryable: bool,
        failure_code: str,
        retry_delay_seconds: Optional[int] = None,
    ) -> bool:
        path = "/internal/v1/jobs/{}/fail".format(job_id)
        body_obj: Dict[str, Any] = {
            "leaseId": lease_id,
            "retryable": retryable,
            "failureCode": failure_code,
        }
        if retry_delay_seconds is not None:
            body_obj["retryDelaySeconds"] = retry_delay_seconds
        data = self._post(path, json.dumps(body_obj).encode("utf-8"), "application/json")
        return bool(data.get("updated", False))

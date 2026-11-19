import type {
  AirdropListRole,
  AirdropShareDto,
  AirdropShareListResponse,
  AttachSpaceFileRequest,
  CompleteUploadSessionRequest,
  CreateAirdropRequest,
  CreateDownloadSessionRequest,
  CreateUploadSessionRequest,
  DownloadSessionDto,
  FileRecordDto,
  GrantType,
  SpaceFileListResponse,
  UploadPartAck,
  UploadSessionDto,
} from "@orbitspace/shared-types";
import type { SpaceAccess } from "../../shared/api/access";
import { api, apiRaw, HttpError } from "../../shared/api/httpClient";
import { featureErrorText, jsonRequest, makeIdempotencyKey } from "../space/spaceApi";

export const DEFAULT_PART_SIZE_BYTES = 5 * 1024 * 1024; // 5 MiB
export const MAX_UPLOAD_BYTES = 512 * 1024 * 1024; // 512 MiB

export const airdropsKey = (role: AirdropListRole) => ["airdrops", role] as const;

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "0 B";
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(2)} MB`;
  return `${(mb / 1024).toFixed(2)} GB`;
}

export function shortDigest(sha256: string): string {
  return sha256.length > 12 ? sha256.slice(0, 12) : sha256;
}

export function filesErrorText(error: unknown): string {
  if (error instanceof HttpError) {
    const reason = error.details?.reason;
    if (error.code === "INVALID_REQUEST") {
      if (reason === "UNSAFE_FILENAME") {
        return "文件名包含不安全字符或路径片段，请重命名后再上传。";
      }
      if (reason === "DISALLOWED_MIME_TYPE" || reason === "DANGEROUS_MAGIC_BYTES") {
        return "出于安全策略，不支持上传 HTML、SVG 或可执行程序类型文件。";
      }
      if (reason === "MIME_SPOOFING") {
        return "文件实际内容签名与声明的 MIME 类型不一致，已拒绝上传。";
      }
    }
    if (error.code === "AUTH_FORBIDDEN" && reason === "INSTANT_MATCH_NOT_AVAILABLE") {
      return "本人授权范围内的可复用文件已失效，将改用普通分片上传。";
    }
  }
  return featureErrorText(error);
}

export function createUploadSession(
  body: CreateUploadSessionRequest,
  idempotencyKey: string,
  access?: SpaceAccess,
  signal?: AbortSignal,
): Promise<UploadSessionDto> {
  return api<UploadSessionDto>(
    "/files/upload-sessions",
    {
      ...jsonRequest(body, "POST", { "Idempotency-Key": idempotencyKey }),
      signal,
    },
    access,
  );
}

export function getUploadSession(
  uploadId: string,
  access?: SpaceAccess,
  signal?: AbortSignal,
): Promise<UploadSessionDto> {
  return api<UploadSessionDto>(
    `/files/upload-sessions/${encodeURIComponent(uploadId)}`,
    { signal },
    access,
  );
}

export function uploadFilePart(
  uploadId: string,
  partNumber: number,
  chunk: Blob,
  partSha256?: string,
  access?: SpaceAccess,
  signal?: AbortSignal,
): Promise<UploadPartAck> {
  const headers: Record<string, string> = {
    "Content-Type": "application/octet-stream",
  };
  if (partSha256) {
    headers["X-Part-Sha256"] = partSha256;
  }
  return api<UploadPartAck>(
    `/files/upload-sessions/${encodeURIComponent(uploadId)}/parts/${partNumber}`,
    {
      method: "PUT",
      headers,
      body: chunk,
      signal,
    },
    access,
  );
}

export function completeUploadSession(
  uploadId: string,
  body: CompleteUploadSessionRequest,
  idempotencyKey: string,
  access?: SpaceAccess,
  signal?: AbortSignal,
): Promise<FileRecordDto> {
  return api<FileRecordDto>(
    `/files/upload-sessions/${encodeURIComponent(uploadId)}/complete`,
    {
      ...jsonRequest(body, "POST", { "Idempotency-Key": idempotencyKey }),
      signal,
    },
    access,
  );
}

export function listSpaceFiles(
  spaceId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<SpaceFileListResponse> {
  return api<SpaceFileListResponse>(
    `/spaces/${encodeURIComponent(spaceId)}/files`,
    { signal },
    access,
  );
}

export function attachSpaceFile(
  spaceId: string,
  body: AttachSpaceFileRequest,
  idempotencyKey: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<FileRecordDto> {
  return api<FileRecordDto>(
    `/spaces/${encodeURIComponent(spaceId)}/files`,
    {
      ...jsonRequest(body, "POST", { "Idempotency-Key": idempotencyKey }),
      signal,
    },
    access,
  );
}

export function deleteSpaceFile(
  spaceId: string,
  fileId: string,
  access: SpaceAccess,
): Promise<void> {
  return api<void>(
    `/spaces/${encodeURIComponent(spaceId)}/files/${encodeURIComponent(fileId)}`,
    { method: "DELETE" },
    access,
  );
}

export function createAirdrop(
  body: CreateAirdropRequest,
  idempotencyKey: string,
  access?: SpaceAccess,
  signal?: AbortSignal,
): Promise<AirdropShareDto> {
  return api<AirdropShareDto>(
    "/airdrops",
    {
      ...jsonRequest(body, "POST", { "Idempotency-Key": idempotencyKey }),
      signal,
    },
    access,
  );
}

export function listAirdrops(
  role: AirdropListRole = "ALL",
  signal?: AbortSignal,
): Promise<AirdropShareListResponse> {
  const qs = new URLSearchParams();
  qs.set("role", role);
  return api<AirdropShareListResponse>(`/airdrops?${qs.toString()}`, { signal });
}

export function getAirdrop(shareId: string, signal?: AbortSignal): Promise<AirdropShareDto> {
  return api<AirdropShareDto>(`/airdrops/${encodeURIComponent(shareId)}`, { signal });
}

export function revokeAirdropRecipient(
  shareId: string,
  recipientUserId: string,
  idempotencyKey: string,
): Promise<AirdropShareDto> {
  return api<AirdropShareDto>(
    `/airdrops/${encodeURIComponent(shareId)}/recipients/${encodeURIComponent(recipientUserId)}/revoke`,
    {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    },
  );
}

export function revokeAirdrop(
  shareId: string,
  idempotencyKey: string,
): Promise<AirdropShareDto> {
  return api<AirdropShareDto>(
    `/airdrops/${encodeURIComponent(shareId)}/revoke`,
    {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    },
  );
}

export function createDownloadSession(
  body: CreateDownloadSessionRequest,
  idempotencyKey: string,
  access?: SpaceAccess,
  signal?: AbortSignal,
): Promise<DownloadSessionDto> {
  return api<DownloadSessionDto>(
    "/download-sessions",
    {
      ...jsonRequest(body, "POST", { "Idempotency-Key": idempotencyKey }),
      signal,
    },
    access,
  );
}

function normalizeDownloadRelativePath(downloadPath: string): string {
  const relative = downloadPath.startsWith("/api/v1/")
    ? downloadPath.slice("/api/v1".length)
    : downloadPath;
  if (!relative.startsWith("/download-sessions/") || relative.startsWith("//")) {
    throw new Error("Invalid download session path");
  }
  return relative;
}

function isAbortError(error: unknown): boolean {
  return (
    (error instanceof DOMException && error.name === "AbortError") ||
    (error instanceof Error && error.name === "AbortError")
  );
}

function triggerBrowserDownload(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  anchor.style.display = "none";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

export interface DownloadAuthorizedFileParams {
  grantType: GrantType;
  grantId: string;
  fileId: string;
  access?: SpaceAccess;
  onSessionCreated?: (session: DownloadSessionDto) => void;
  onProgress?: (loadedBytes: number, totalBytes: number) => void;
  signal?: AbortSignal;
}

/**
 * Create a live-verified download session, stream bytes via authenticated fetch
 * (with automatic HTTP Range resume within the same session on transient stream interruption),
 * and trigger browser download.
 */
export async function downloadAuthorizedFile({
  grantType,
  grantId,
  fileId,
  access,
  onSessionCreated,
  onProgress,
  signal,
}: DownloadAuthorizedFileParams): Promise<DownloadSessionDto> {
  const session = await createDownloadSession(
    { grantType, grantId, fileId },
    makeIdempotencyKey(),
    access,
    signal,
  );
  onSessionCreated?.(session);

  const relativePath = normalizeDownloadRelativePath(session.downloadPath);
  const totalBytes = session.sizeBytes;
  const chunks: BlobPart[] = [];
  let receivedBytes = 0;

  if (totalBytes === 0) {
    const response = await apiRaw(relativePath, { method: "GET", signal }, access);
    const emptyBlob = await response.blob();
    onProgress?.(0, 0);
    triggerBrowserDownload(emptyBlob, session.fileName);
    return session;
  }

  const maxRangeRetries = 2;
  for (let attempt = 0; attempt <= maxRangeRetries; attempt++) {
    const headers: Record<string, string> = {};
    if (receivedBytes > 0) {
      headers["Range"] = `bytes=${receivedBytes}-${totalBytes - 1}`;
    }

    try {
      const response = await apiRaw(
        relativePath,
        { method: "GET", headers, signal },
        access,
      );

      if (receivedBytes > 0 && response.status !== 206) {
        // Server returned full representation instead of partial range; reset accumulator.
        chunks.length = 0;
        receivedBytes = 0;
      }

      if (response.body) {
        const reader = response.body.getReader();
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          if (value && value.byteLength > 0) {
            const copy = new Uint8Array(value.byteLength);
            copy.set(value);
            chunks.push(copy);
            receivedBytes += value.byteLength;
            onProgress?.(Math.min(receivedBytes, totalBytes), totalBytes);
          }
        }
      } else {
        const buf = await response.arrayBuffer();
        chunks.push(buf);
        receivedBytes += buf.byteLength;
        onProgress?.(Math.min(receivedBytes, totalBytes), totalBytes);
      }

      if (receivedBytes >= totalBytes) {
        break;
      }
      if (attempt === maxRangeRetries) {
        throw new Error("下载流提前中断，请重试。");
      }
    } catch (error) {
      if (isAbortError(error) || error instanceof HttpError || attempt === maxRangeRetries) {
        throw error;
      }
      await new Promise((resolve) => window.setTimeout(resolve, 700 * (attempt + 1)));
    }
  }

  const blob = new Blob(chunks, {
    type: session.contentType || "application/octet-stream",
  });
  triggerBrowserDownload(blob, session.fileName);
  return session;
}

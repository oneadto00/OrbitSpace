import { useCallback, useEffect, useRef, useState } from "react";
import type { FileRecordDto, GrantType, UploadSessionDto } from "@orbitspace/shared-types";
import { bindSpaceConnection, type SpaceAccess } from "../../shared/api/access";
import { HttpError } from "../../shared/api/httpClient";
import { makeIdempotencyKey } from "../space/spaceApi";
import type {
  FileChunkHashRequest,
  FileChunkHashResponse,
} from "./fileChunkHash.worker";
import {
  attachSpaceFile,
  completeUploadSession,
  createUploadSession,
  DEFAULT_PART_SIZE_BYTES,
  filesErrorText,
  getUploadSession,
  MAX_UPLOAD_BYTES,
  uploadFilePart,
} from "./filesApi";

export type UploadTaskPhase =
  | "hashing"
  | "creating_session"
  | "uploading"
  | "paused"
  | "completing"
  | "completed"
  | "error";

export interface UploadTaskItem {
  localId: string;
  file: File;
  fileName: string;
  fileSizeBytes: number;
  phase: UploadTaskPhase;
  hashProgress: number;
  uploadProgress: number;
  fileSha256: string | null;
  uploadId: string | null;
  totalParts: number;
  uploadedPartsCount: number;
  instantMatchAvailable: boolean;
  instantMatchUsed: boolean;
  completedFile: FileRecordDto | null;
  errorMessage: string | null;
  canRetry: boolean;
}

export interface UseChunkedUploadOptions {
  targetScope: GrantType;
  spaceId?: string | null;
  access?: SpaceAccess | null;
  concurrency?: number;
  partSizeBytes?: number;
  onCompleted?: (file: FileRecordDto) => void;
}

interface HashResult {
  fileSha256: string;
  partSizeBytes: number;
  totalParts: number;
  partSha256s: string[];
}

interface PendingHashCallbacks {
  onProgress: (percent: number) => void;
  resolve: (result: HashResult) => void;
  reject: (reason: Error) => void;
}

interface TaskRuntime {
  localId: string;
  file: File;
  fileSha256: string | null;
  partSha256s: string[] | null;
  hashedPartSizeBytes: number;
  uploadId: string | null;
  createIdemKey: string;
  completeIdemKey: string;
  attachIdemKey: string;
  paused: boolean;
  cancelled: boolean;
  activeHashRequestId: number | null;
  abortController: AbortController | null;
}

function isAbortError(error: unknown): boolean {
  return (
    (error instanceof DOMException && error.name === "AbortError") ||
    (error instanceof Error && error.name === "AbortError")
  );
}

function waitMs(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException("Aborted", "AbortError"));
      return;
    }
    const timer = window.setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      window.clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

function normalizeClientContentType(rawType: string): string | null {
  const trimmed = rawType.trim();
  return trimmed.length > 0 ? trimmed : null;
}

export function useChunkedUpload({
  targetScope,
  spaceId = null,
  access = null,
  concurrency = 3,
  partSizeBytes = DEFAULT_PART_SIZE_BYTES,
  onCompleted,
}: UseChunkedUploadOptions) {
  const [tasks, setTasks] = useState<UploadTaskItem[]>([]);

  const workerRef = useRef<Worker | null>(null);
  const hashReqSeqRef = useRef(0);
  const pendingHashesRef = useRef<Map<number, PendingHashCallbacks>>(new Map());
  const runtimesRef = useRef<Map<string, TaskRuntime>>(new Map());
  const mountedRef = useRef(true);

  const onCompletedRef = useRef(onCompleted);
  onCompletedRef.current = onCompleted;

  const accessRef = useRef<SpaceAccess | null>(access);
  accessRef.current = access;

  const spaceIdRef = useRef<string | null>(spaceId);
  spaceIdRef.current = spaceId;

  const updateTask = useCallback(
    (localId: string, updater: (prev: UploadTaskItem) => UploadTaskItem) => {
      if (!mountedRef.current) return;
      setTasks((prev) => prev.map((item) => (item.localId === localId ? updater(item) : item)));
    },
    [],
  );

  const terminateWorker = useCallback(() => {
    const worker = workerRef.current;
    workerRef.current = null;
    if (worker) {
      worker.terminate();
    }
    for (const callbacks of pendingHashesRef.current.values()) {
      callbacks.reject(new DOMException("Worker terminated", "AbortError"));
    }
    pendingHashesRef.current.clear();
  }, []);

  const ensureWorker = useCallback((): Worker => {
    if (workerRef.current) return workerRef.current;
    if (typeof Worker === "undefined") {
      throw new Error("当前浏览器不支持 Web Worker 分片哈希计算。");
    }
    const worker = new Worker(new URL("./fileChunkHash.worker.ts", import.meta.url), {
      type: "module",
    });

    worker.addEventListener("message", (event: MessageEvent<FileChunkHashResponse>) => {
      const msg = event.data;
      const pending = pendingHashesRef.current.get(msg.requestId);
      if (!pending) return;

      if (msg.type === "PROGRESS") {
        const pct =
          msg.totalBytes > 0
            ? Math.min(100, Math.round((msg.processedBytes / msg.totalBytes) * 100))
            : 100;
        pending.onProgress(pct);
      } else if (msg.type === "DONE") {
        pendingHashesRef.current.delete(msg.requestId);
        pending.resolve({
          fileSha256: msg.fileSha256,
          partSizeBytes: msg.partSizeBytes,
          totalParts: msg.totalParts,
          partSha256s: msg.partSha256s,
        });
      } else if (msg.type === "ERROR") {
        pendingHashesRef.current.delete(msg.requestId);
        pending.reject(new Error(msg.error));
      }
    });

    worker.addEventListener("error", (event: ErrorEvent) => {
      const message = event.message || "分片哈希 Worker 运行异常";
      for (const callbacks of pendingHashesRef.current.values()) {
        callbacks.reject(new Error(message));
      }
      pendingHashesRef.current.clear();
    });

    workerRef.current = worker;
    return worker;
  }, []);

  const hashFileWithWorker = useCallback(
    (runtime: TaskRuntime, chunkBytes: number, onProgress: (pct: number) => void): Promise<HashResult> => {
      const worker = ensureWorker();
      const requestId = ++hashReqSeqRef.current;
      runtime.activeHashRequestId = requestId;

      return new Promise<HashResult>((resolve, reject) => {
        pendingHashesRef.current.set(requestId, {
          onProgress,
          resolve: (res) => {
            if (runtime.activeHashRequestId === requestId) {
              runtime.activeHashRequestId = null;
            }
            resolve(res);
          },
          reject: (err) => {
            if (runtime.activeHashRequestId === requestId) {
              runtime.activeHashRequestId = null;
            }
            reject(err);
          },
        });

        const message: FileChunkHashRequest = {
          type: "HASH_FILE",
          requestId,
          file: runtime.file,
          partSizeBytes: chunkBytes,
        };
        worker.postMessage(message);
      });
    },
    [ensureWorker],
  );

  useEffect(() => {
    mountedRef.current = true;
    const unbind =
      targetScope === "SPACE_FILE" && access
        ? bindSpaceConnection(access, () => {
            for (const runtime of runtimesRef.current.values()) {
              runtime.cancelled = true;
              runtime.abortController?.abort();
            }
            terminateWorker();
          })
        : null;

    return () => {
      mountedRef.current = false;
      unbind?.();
      for (const runtime of runtimesRef.current.values()) {
        runtime.cancelled = true;
        runtime.abortController?.abort();
      }
      terminateWorker();
    };
  }, [access?.spaceId, access?.membershipEpoch, targetScope, terminateWorker]);

  const runTask = useCallback(
    async (localId: string) => {
      const runtime = runtimesRef.current.get(localId);
      if (!runtime || runtime.cancelled) return;

      runtime.paused = false;
      const controller = new AbortController();
      runtime.abortController = controller;
      const signal = controller.signal;
      const currentAccess = targetScope === "SPACE_FILE" ? accessRef.current ?? undefined : undefined;
      const currentSpaceId = targetScope === "SPACE_FILE" ? spaceIdRef.current : null;

      if (targetScope === "SPACE_FILE" && (!currentAccess || !currentSpaceId)) {
        updateTask(localId, (prev) => ({
          ...prev,
          phase: "error",
          errorMessage: "空间授权上下文不可用，请刷新后重试。",
          canRetry: true,
        }));
        return;
      }

      if (runtime.file.size > MAX_UPLOAD_BYTES) {
        updateTask(localId, (prev) => ({
          ...prev,
          phase: "error",
          errorMessage: `文件超过单文件上传上限（512 MB）。`,
          canRetry: false,
        }));
        return;
      }

      try {
        // Cache the worker hash across pause, resume, and retry.
        if (!runtime.fileSha256 || !runtime.partSha256s) {
          updateTask(localId, (prev) => ({
            ...prev,
            phase: "hashing",
            errorMessage: null,
          }));

          const hashResult = await hashFileWithWorker(runtime, partSizeBytes, (pct) => {
            updateTask(localId, (prev) => ({
              ...prev,
              hashProgress: pct,
            }));
          });

          if (runtime.cancelled || runtime.paused || signal.aborted) {
            return;
          }

          runtime.fileSha256 = hashResult.fileSha256;
          runtime.partSha256s = hashResult.partSha256s;
          runtime.hashedPartSizeBytes = hashResult.partSizeBytes;

          updateTask(localId, (prev) => ({
            ...prev,
            hashProgress: 100,
            fileSha256: hashResult.fileSha256,
            totalParts: hashResult.totalParts,
          }));
        }

        const fileSha256 = runtime.fileSha256;
        if (!fileSha256) {
          throw new Error("未计算出有效文件校验和。");
        }

        updateTask(localId, (prev) => ({
          ...prev,
          phase: "creating_session",
          errorMessage: null,
        }));

        let session: UploadSessionDto | null = null;
        if (runtime.uploadId) {
          try {
            const existing = await getUploadSession(runtime.uploadId, currentAccess, signal);
            if (existing.status === "EXPIRED" || existing.status === "CANCELLED") {
              runtime.uploadId = null;
              runtime.createIdemKey = makeIdempotencyKey();
              runtime.completeIdemKey = makeIdempotencyKey();
              runtime.attachIdemKey = makeIdempotencyKey();
            } else {
              session = existing;
            }
          } catch (err) {
            if (isAbortError(err)) throw err;
            if (
              err instanceof HttpError &&
              (err.status === 404 || err.status === 410 || err.code === "RESOURCE_EXPIRED_OR_PURGED")
            ) {
              runtime.uploadId = null;
              runtime.createIdemKey = makeIdempotencyKey();
              runtime.completeIdemKey = makeIdempotencyKey();
              runtime.attachIdemKey = makeIdempotencyKey();
            } else {
              throw err;
            }
          }
        }

        if (!session) {
          session = await createUploadSession(
            {
              targetScope,
              spaceId: targetScope === "SPACE_FILE" ? currentSpaceId : null,
              fileName: runtime.file.name,
              contentType: normalizeClientContentType(runtime.file.type),
              expectedSizeBytes: runtime.file.size,
              expectedSha256: fileSha256,
              partSizeBytes,
            },
            runtime.createIdemKey,
            currentAccess,
            signal,
          );
          runtime.uploadId = session.uploadId;
        }

        if (runtime.cancelled || runtime.paused || signal.aborted) {
          return;
        }

        const effectiveSession = session;
        updateTask(localId, (prev) => ({
          ...prev,
          uploadId: effectiveSession.uploadId,
          totalParts: effectiveSession.totalParts,
          uploadedPartsCount: effectiveSession.uploadedParts.length,
          instantMatchAvailable: effectiveSession.instantMatchAvailable,
          uploadProgress:
            effectiveSession.totalParts > 0
              ? Math.round(
                  (effectiveSession.uploadedParts.length / effectiveSession.totalParts) * 100,
                )
              : 0,
        }));

        // Reuse only an existing file the uploader is already authorized to access.
        let completedRecord: FileRecordDto | null = null;
        let usedInstantMatch = false;

        if (effectiveSession.instantMatchAvailable && effectiveSession.status !== "COMPLETED") {
          updateTask(localId, (prev) => ({
            ...prev,
            phase: "completing",
            instantMatchAvailable: true,
          }));
          try {
            completedRecord = await completeUploadSession(
              effectiveSession.uploadId,
              {
                expectedSha256: fileSha256,
                useInstantMatch: true,
              },
              runtime.completeIdemKey,
              currentAccess,
              signal,
            );
            usedInstantMatch = true;
          } catch (instantErr) {
            if (isAbortError(instantErr)) throw instantErr;
            if (
              instantErr instanceof HttpError &&
              instantErr.code === "AUTH_FORBIDDEN" &&
              instantErr.details?.reason === "INSTANT_MATCH_NOT_AVAILABLE"
            ) {
              // Original user-owned file became unavailable concurrently; fall back to chunked upload.
              runtime.completeIdemKey = makeIdempotencyKey();
              updateTask(localId, (prev) => ({
                ...prev,
                instantMatchAvailable: false,
              }));
            } else {
              throw instantErr;
            }
          }
        }

        if (!completedRecord && effectiveSession.status !== "COMPLETED") {
          const uploadedSet = new Set<number>(effectiveSession.uploadedParts);
          const missingParts: number[] = [];
          for (let partNo = 1; partNo <= effectiveSession.totalParts; partNo++) {
            if (!uploadedSet.has(partNo)) {
              missingParts.push(partNo);
            }
          }

          let completedCount = uploadedSet.size;
          updateTask(localId, (prev) => ({
            ...prev,
            phase: "uploading",
            uploadedPartsCount: completedCount,
            uploadProgress: Math.round((completedCount / effectiveSession.totalParts) * 100),
          }));

          if (missingParts.length > 0) {
            let nextIndex = 0;
            const partShaMatchesSize =
              runtime.partSha256s !== null &&
              runtime.hashedPartSizeBytes === effectiveSession.partSizeBytes;

            const uploadOnePart = async (partNumber: number): Promise<void> => {
              const start = (partNumber - 1) * effectiveSession.partSizeBytes;
              const end = Math.min(runtime.file.size, start + effectiveSession.partSizeBytes);
              const chunk = runtime.file.slice(start, end);
              const partSha = partShaMatchesSize
                ? runtime.partSha256s?.[partNumber - 1]
                : undefined;

              const maxRetries = 2;
              for (let attempt = 0; attempt <= maxRetries; attempt++) {
                if (runtime.cancelled || runtime.paused || signal.aborted) {
                  throw new DOMException("Paused or aborted", "AbortError");
                }
                try {
                  await uploadFilePart(
                    effectiveSession.uploadId,
                    partNumber,
                    chunk,
                    partSha,
                    currentAccess,
                    signal,
                  );
                  return;
                } catch (err) {
                  if (isAbortError(err) || err instanceof HttpError || attempt === maxRetries) {
                    throw err;
                  }
                  await waitMs(600 * (attempt + 1), signal);
                }
              }
            };

            const workerCount = Math.min(Math.max(1, concurrency), missingParts.length);
            const workers = Array.from({ length: workerCount }, async () => {
              while (true) {
                if (runtime.cancelled || runtime.paused || signal.aborted) {
                  return;
                }
                const idx = nextIndex++;
                if (idx >= missingParts.length) {
                  return;
                }
                const partNumber = missingParts[idx];
                if (partNumber === undefined) return;
                await uploadOnePart(partNumber);
                completedCount += 1;
                updateTask(localId, (prev) => ({
                  ...prev,
                  uploadedPartsCount: completedCount,
                  uploadProgress: Math.min(
                    99,
                    Math.round((completedCount / effectiveSession.totalParts) * 100),
                  ),
                }));
              }
            });

            await Promise.all(workers);
          }

          if (runtime.cancelled || runtime.paused || signal.aborted) {
            return;
          }

          updateTask(localId, (prev) => ({
            ...prev,
            phase: "completing",
          }));

          completedRecord = await completeUploadSession(
            effectiveSession.uploadId,
            {
              expectedSha256: fileSha256,
              useInstantMatch: false,
            },
            runtime.completeIdemKey,
            currentAccess,
            signal,
          );
        }

        // If session was already COMPLETED on resume, replay idempotent complete to recover FileRecordDto
        if (!completedRecord && effectiveSession.status === "COMPLETED") {
          updateTask(localId, (prev) => ({
            ...prev,
            phase: "completing",
          }));
          completedRecord = await completeUploadSession(
            effectiveSession.uploadId,
            {
              expectedSha256: fileSha256,
              useInstantMatch: effectiveSession.instantMatchAvailable,
            },
            runtime.completeIdemKey,
            currentAccess,
            signal,
          );
        }

        if (!completedRecord) {
          throw new Error("上传合并未能返回有效文件记录。");
        }

        const finalRecord: FileRecordDto =
          targetScope === "SPACE_FILE" && currentSpaceId && currentAccess
            ? await attachSpaceFile(
                currentSpaceId,
                { uploadId: effectiveSession.uploadId },
                runtime.attachIdemKey,
                currentAccess,
                signal,
              )
            : completedRecord;

        if (runtime.cancelled) return;

        updateTask(localId, (prev) => ({
          ...prev,
          phase: "completed",
          hashProgress: 100,
          uploadProgress: 100,
          uploadedPartsCount: effectiveSession.totalParts,
          instantMatchUsed: usedInstantMatch,
          completedFile: finalRecord,
          errorMessage: null,
          canRetry: false,
        }));

        onCompletedRef.current?.(finalRecord);
      } catch (err) {
        if (runtime.cancelled) return;
        if (runtime.paused && isAbortError(err)) {
          updateTask(localId, (prev) => ({
            ...prev,
            phase: "paused",
            errorMessage: null,
          }));
          return;
        }
        if (isAbortError(err)) {
          updateTask(localId, (prev) => ({
            ...prev,
            phase: "paused",
            errorMessage: null,
          }));
          return;
        }

        const isNonRetryableValidation =
          err instanceof HttpError &&
          err.code === "INVALID_REQUEST" &&
          (err.details?.reason === "UNSAFE_FILENAME" ||
            err.details?.reason === "DISALLOWED_MIME_TYPE" ||
            err.details?.reason === "MIME_SPOOFING" ||
            err.details?.reason === "DANGEROUS_MAGIC_BYTES");

        updateTask(localId, (prev) => ({
          ...prev,
          phase: "error",
          errorMessage: filesErrorText(err),
          canRetry: !isNonRetryableValidation,
        }));
      } finally {
        if (runtime.abortController === controller) {
          runtime.abortController = null;
        }
      }
    },
    [concurrency, hashFileWithWorker, partSizeBytes, targetScope, updateTask],
  );

  const enqueueFiles = useCallback(
    (input: FileList | File[]) => {
      const list = Array.from(input);
      if (list.length === 0) return;

      const newItems: UploadTaskItem[] = [];
      const idsToStart: string[] = [];

      for (const file of list) {
        const localId = makeIdempotencyKey();
        const estParts = file.size === 0 ? 1 : Math.ceil(file.size / partSizeBytes);

        const runtime: TaskRuntime = {
          localId,
          file,
          fileSha256: null,
          partSha256s: null,
          hashedPartSizeBytes: partSizeBytes,
          uploadId: null,
          createIdemKey: makeIdempotencyKey(),
          completeIdemKey: makeIdempotencyKey(),
          attachIdemKey: makeIdempotencyKey(),
          paused: false,
          cancelled: false,
          activeHashRequestId: null,
          abortController: null,
        };
        runtimesRef.current.set(localId, runtime);

        newItems.push({
          localId,
          file,
          fileName: file.name,
          fileSizeBytes: file.size,
          phase: "hashing",
          hashProgress: 0,
          uploadProgress: 0,
          fileSha256: null,
          uploadId: null,
          totalParts: estParts,
          uploadedPartsCount: 0,
          instantMatchAvailable: false,
          instantMatchUsed: false,
          completedFile: null,
          errorMessage: null,
          canRetry: true,
        });
        idsToStart.push(localId);
      }

      setTasks((prev) => [...newItems, ...prev]);

      for (const id of idsToStart) {
        void runTask(id);
      }
    },
    [partSizeBytes, runTask],
  );

  const pauseTask = useCallback(
    (localId: string) => {
      const runtime = runtimesRef.current.get(localId);
      if (!runtime) return;
      runtime.paused = true;

      if (runtime.activeHashRequestId !== null && workerRef.current) {
        const reqId = runtime.activeHashRequestId;
        runtime.activeHashRequestId = null;
        const cancelMsg: FileChunkHashRequest = { type: "CANCEL", requestId: reqId };
        workerRef.current.postMessage(cancelMsg);
        const pending = pendingHashesRef.current.get(reqId);
        if (pending) {
          pendingHashesRef.current.delete(reqId);
          pending.reject(new DOMException("Paused", "AbortError"));
        }
      }

      runtime.abortController?.abort();
      updateTask(localId, (prev) =>
        prev.phase === "completed"
          ? prev
          : {
              ...prev,
              phase: "paused",
              errorMessage: null,
            },
      );
    },
    [updateTask],
  );

  const resumeTask = useCallback(
    (localId: string) => {
      const runtime = runtimesRef.current.get(localId);
      if (!runtime || runtime.cancelled) return;
      void runTask(localId);
    },
    [runTask],
  );

  const retryTask = useCallback(
    (localId: string) => {
      const runtime = runtimesRef.current.get(localId);
      if (!runtime || runtime.cancelled) return;
      void runTask(localId);
    },
    [runTask],
  );

  const removeTask = useCallback((localId: string) => {
    const runtime = runtimesRef.current.get(localId);
    if (runtime) {
      runtime.cancelled = true;
      runtime.paused = true;
      if (runtime.activeHashRequestId !== null && workerRef.current) {
        const reqId = runtime.activeHashRequestId;
        runtime.activeHashRequestId = null;
        const cancelMsg: FileChunkHashRequest = { type: "CANCEL", requestId: reqId };
        workerRef.current.postMessage(cancelMsg);
        pendingHashesRef.current.delete(reqId);
      }
      runtime.abortController?.abort();
      runtimesRef.current.delete(localId);
    }
    setTasks((prev) => prev.filter((item) => item.localId !== localId));
  }, []);

  const clearCompleted = useCallback(() => {
    setTasks((prev) => {
      for (const item of prev) {
        if (item.phase === "completed") {
          runtimesRef.current.delete(item.localId);
        }
      }
      return prev.filter((item) => item.phase !== "completed");
    });
  }, []);

  return {
    tasks,
    enqueueFiles,
    pauseTask,
    resumeTask,
    retryTask,
    removeTask,
    clearCompleted,
  };
}

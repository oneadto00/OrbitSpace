import type {
  AccessSession,
  ApiError,
  CardState,
  ChangeCodeCommentStatusRequest,
  CodeCommentDto,
  CodeCommentListResponse,
  CommitDetail,
  CreateCodeCommentRequest,
  CreateTwoPointDiffRequest,
  ForwardCodeCommentRequest,
  Member,
  ParseJob,
  ProjectGraph,
  ReplyCodeCommentRequest,
  SnapshotDetail,
  TwoPointDiffDto,
} from "@orbitspace/shared-types";
import { revokeSpace, spaceSignal, type SpaceAccess } from "../../shared/api/access";
import {
  api,
  endSession,
  errorText,
  HttpError,
  refreshSession,
} from "../../shared/api/httpClient";
import { useAuth } from "../../shared/auth/session";

const API_BASE = "/api/v1";

export interface RawPatchPayload {
  buffer: ArrayBuffer;
  text: string;
}

/** Generates a fresh UUID idempotency key with fallback for constrained runtimes. */
export function makeIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `idem-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** Copies text to clipboard with fallback for non-HTTPS local origins. */
export async function copyTextToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // Fallback below
  }
  try {
    const textarea = document.createElement("textarea");
    textarea.value = text;
    textarea.style.position = "fixed";
    textarea.style.opacity = "0";
    document.body.appendChild(textarea);
    textarea.select();
    const ok = document.execCommand("copy");
    textarea.remove();
    return ok;
  } catch {
    return false;
  }
}

function jsonPostInit(
  value: unknown,
  idempotencyKey: string,
  signal?: AbortSignal,
): RequestInit {
  return {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify(value),
    signal,
  };
}

export function buildCommitDetailPath(
  spaceId: string,
  projectId: string,
  commitOid: string,
  parentOid?: string | null,
): string {
  const base = `/spaces/${encodeURIComponent(spaceId)}/projects/${encodeURIComponent(projectId)}/commits/${encodeURIComponent(commitOid)}`;
  return parentOid ? `${base}?parentOid=${encodeURIComponent(parentOid)}` : base;
}

export function buildCommitPatchPath(
  spaceId: string,
  projectId: string,
  commitOid: string,
  parentOid?: string | null,
): string {
  const base = `/spaces/${encodeURIComponent(spaceId)}/projects/${encodeURIComponent(projectId)}/commits/${encodeURIComponent(commitOid)}/patch`;
  return parentOid ? `${base}?parentOid=${encodeURIComponent(parentOid)}` : base;
}

export function buildSnapshotDetailPath(
  spaceId: string,
  projectId: string,
  snapshotId: string,
): string {
  return `/spaces/${encodeURIComponent(spaceId)}/projects/${encodeURIComponent(projectId)}/snapshots/${encodeURIComponent(snapshotId)}`;
}

export function buildSnapshotPatchPath(
  spaceId: string,
  projectId: string,
  snapshotId: string,
): string {
  return `/spaces/${encodeURIComponent(spaceId)}/projects/${encodeURIComponent(projectId)}/snapshots/${encodeURIComponent(snapshotId)}/patch`;
}

export function buildDiffsPath(spaceId: string, projectId: string): string {
  return `/spaces/${encodeURIComponent(spaceId)}/projects/${encodeURIComponent(projectId)}/diffs`;
}

export function buildDiffDetailPath(
  spaceId: string,
  projectId: string,
  diffId: string,
): string {
  return `${buildDiffsPath(spaceId, projectId)}/${encodeURIComponent(diffId)}`;
}

export function buildCodeCommentsPath(
  spaceId: string,
  projectId: string,
  params?: { diffId?: string; path?: string },
): string {
  const base = `/spaces/${encodeURIComponent(spaceId)}/projects/${encodeURIComponent(projectId)}/code-comments`;
  const qs = new URLSearchParams();
  if (params?.diffId) qs.set("diffId", params.diffId);
  if (params?.path) qs.set("path", params.path);
  const suffix = qs.toString();
  return suffix ? `${base}?${suffix}` : base;
}

export function fetchSpaceMembers(
  spaceId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<Member[]> {
  return api<Member[]>(`/spaces/${encodeURIComponent(spaceId)}/members`, { signal }, access);
}

export function createTwoPointDiff(
  spaceId: string,
  projectId: string,
  payload: CreateTwoPointDiffRequest,
  access: SpaceAccess,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<TwoPointDiffDto> {
  return api<TwoPointDiffDto>(
    buildDiffsPath(spaceId, projectId),
    jsonPostInit(payload, idempotencyKey, signal),
    access,
  );
}

export function getTwoPointDiff(
  spaceId: string,
  projectId: string,
  diffId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<TwoPointDiffDto> {
  return api<TwoPointDiffDto>(
    buildDiffDetailPath(spaceId, projectId, diffId),
    { signal },
    access,
  );
}

export function listCodeComments(
  spaceId: string,
  projectId: string,
  access: SpaceAccess,
  params?: { diffId?: string; path?: string },
  signal?: AbortSignal,
): Promise<CodeCommentListResponse> {
  return api<CodeCommentListResponse>(
    buildCodeCommentsPath(spaceId, projectId, params),
    { signal },
    access,
  );
}

export function createCodeComment(
  spaceId: string,
  projectId: string,
  payload: CreateCodeCommentRequest,
  access: SpaceAccess,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<CodeCommentDto> {
  return api<CodeCommentDto>(
    buildCodeCommentsPath(spaceId, projectId),
    jsonPostInit(payload, idempotencyKey, signal),
    access,
  );
}

export function replyCodeComment(
  spaceId: string,
  projectId: string,
  commentId: string,
  payload: ReplyCodeCommentRequest,
  access: SpaceAccess,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<CodeCommentDto> {
  const path = `${buildCodeCommentsPath(spaceId, projectId)}/${encodeURIComponent(commentId)}/replies`;
  return api<CodeCommentDto>(path, jsonPostInit(payload, idempotencyKey, signal), access);
}

export function changeCodeCommentDiscussion(
  spaceId: string,
  projectId: string,
  commentId: string,
  payload: ChangeCodeCommentStatusRequest,
  access: SpaceAccess,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<CodeCommentDto> {
  const path = `${buildCodeCommentsPath(spaceId, projectId)}/${encodeURIComponent(commentId)}/discussion`;
  return api<CodeCommentDto>(path, jsonPostInit(payload, idempotencyKey, signal), access);
}

export function forwardCodeCommentToChat(
  spaceId: string,
  projectId: string,
  commentId: string,
  payload: ForwardCodeCommentRequest,
  access: SpaceAccess,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<CodeCommentDto> {
  const path = `${buildCodeCommentsPath(spaceId, projectId)}/${encodeURIComponent(commentId)}/forward-chat`;
  return api<CodeCommentDto>(path, jsonPostInit(payload, idempotencyKey, signal), access);
}

export async function fetchProjectGraph(
  spaceId: string,
  projectId: string,
  access: SpaceAccess,
  cursor?: string | null,
  limit = 50,
  signal?: AbortSignal,
): Promise<ProjectGraph> {
  const params = new URLSearchParams();
  params.set("limit", String(limit));
  if (cursor) params.set("cursor", cursor);
  const path = `/spaces/${encodeURIComponent(spaceId)}/projects/${encodeURIComponent(projectId)}/graph?${params.toString()}`;
  return api<ProjectGraph>(path, { signal }, access);
}

export async function fetchCommitDetail(
  spaceId: string,
  projectId: string,
  commitOid: string,
  access: SpaceAccess,
  parentOid?: string | null,
  signal?: AbortSignal,
): Promise<CommitDetail> {
  const path = buildCommitDetailPath(spaceId, projectId, commitOid, parentOid);
  return api<CommitDetail>(path, { signal }, access);
}

export async function fetchSnapshotDetail(
  spaceId: string,
  projectId: string,
  snapshotId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<SnapshotDetail> {
  const path = buildSnapshotDetailPath(spaceId, projectId, snapshotId);
  return api<SnapshotDetail>(path, { signal }, access);
}

export async function retrySnapshotParseJob(
  spaceId: string,
  projectId: string,
  snapshotId: string,
  jobId: string,
  access: SpaceAccess,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<ParseJob> {
  const path = `/spaces/${encodeURIComponent(spaceId)}/projects/${encodeURIComponent(projectId)}/snapshots/${encodeURIComponent(snapshotId)}/parse-jobs/${encodeURIComponent(jobId)}/retry`;
  return api<ParseJob>(
    path,
    {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      signal,
    },
    access,
  );
}

export async function fetchRawPatch(
  path: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<RawPatchPayload> {
  if (!path.startsWith("/") || path.startsWith("//")) {
    throw new Error("Invalid API path");
  }

  const original = useAuth.getState().session;
  const expectedSessionId = original?.sessionId;

  const send = async (session: AccessSession | null): Promise<RawPatchPayload> => {
    const activeSessionId = useAuth.getState().session?.sessionId;
    if (expectedSessionId && activeSessionId !== expectedSessionId) {
      throw new DOMException("Session changed", "AbortError");
    }

    const headers = new Headers();
    if (session) {
      headers.set("Authorization", `Bearer ${session.accessToken}`);
    }
    headers.set("X-Membership-Epoch", String(access.membershipEpoch));

    const signals = signal ? [signal, spaceSignal(access)] : [spaceSignal(access)];
    const combinedSignal = AbortSignal.any(signals);
    combinedSignal.throwIfAborted();

    const response = await fetch(API_BASE + path, {
      method: "GET",
      cache: "no-store",
      redirect: "error",
      credentials: "omit",
      headers,
      signal: combinedSignal,
    });

    if (!response.ok) {
      const fault: Partial<ApiError> = await response.json().catch(() => ({}));
      throw new HttpError(response.status, fault?.code, fault?.requestId, fault?.details);
    }

    const buffer = await response.arrayBuffer();
    combinedSignal.throwIfAborted();
    if (expectedSessionId && useAuth.getState().session?.sessionId !== expectedSessionId) {
      throw new DOMException("Session changed", "AbortError");
    }

    const text = new TextDecoder().decode(buffer);
    return { buffer, text };
  };

  try {
    try {
      return await send(original);
    } catch (error) {
      if (
        !(error instanceof HttpError) ||
        error.status !== 401 ||
        error.code === "AUTH_REVOKED" ||
        error.code === "MEMBERSHIP_EPOCH_INVALID"
      ) {
        throw error;
      }
      const current = useAuth.getState().session;
      const refreshed =
        current && original?.accessToken !== current.accessToken
          ? current
          : await refreshSession();
      return await send(refreshed);
    }
  } catch (error) {
    if (error instanceof HttpError) {
      if (error.code === "AUTH_REVOKED" || error.code === "MEMBERSHIP_EPOCH_INVALID") {
        const spaceId = error.details?.spaceId;
        const membershipEpoch = error.details?.membershipEpoch;
        const affected =
          access ??
          (typeof spaceId === "string" && typeof membershipEpoch === "number"
            ? { spaceId, membershipEpoch }
            : undefined);
        if (affected) revokeSpace(affected);
        else endSession(errorText(error));
      } else if (error.status === 401) {
        endSession(errorText(error));
      }
    }
    throw error;
  }
}

export function resolveResourceCardState(
  error: unknown,
): Exclude<CardState, "AVAILABLE"> | "REVOKED_FOR_ACTOR" | null {
  if (!(error instanceof HttpError)) return null;
  if (error.code === "AUTH_REVOKED" || error.code === "MEMBERSHIP_EPOCH_INVALID") {
    return "REVOKED_FOR_ACTOR";
  }
  if (error.status === 403 || error.code === "AUTH_FORBIDDEN") {
    return "FORBIDDEN";
  }
  if (error.status === 410 || error.code === "RESOURCE_EXPIRED_OR_PURGED") {
    const purged = error.details?.purged === true || error.details?.reason === "FORCE_PURGED";
    return purged ? "FORCE_PURGED" : "EXPIRED_OR_DELETED";
  }
  return null;
}

export function gitCanvasErrorText(error: unknown): string {
  if (error instanceof HttpError) {
    switch (error.code) {
      case "RESOURCE_EXPIRED_OR_PURGED":
        return "该快照、补丁或对比资源已过期或已被清理。";
      case "RESOURCE_NOT_FOUND":
        return "未找到对应的节点、对比差异或代码批注（可能已失效或无权访问）。";
      case "IDEMPOTENCY_CONFLICT":
        return "操作幂等键冲突，请刷新后重试。";
      case "WORKTREE_EPOCH_STALE":
      case "CAPTURE_SEQ_STALE":
        return "工作区采集序列已更新，请刷新画布查看最新状态。";
      case "DIFF_DATA_INSUFFICIENT":
        return "两端点共享内容或基础对象不足以计算完整差异，请按提示使用 orbit backfill 补传。";
      default:
        return errorText(error);
    }
  }
  return errorText(error);
}

import type {
  AiSourceKind,
  ApiError,
  CreateInvite,
  DocumentComment,
  DocumentInfo,
  DocumentSavedBoundary,
  DocumentVersion,
  DocumentVersionPage,
  ErrorCode,
  GitProject,
  Invite,
  InviteRole,
  Member,
  SpaceAiPolicy,
  SpaceDetail,
  SpaceRole,
  SpaceSummary,
} from "@orbitspace/shared-types";
import { queryClient, spacesKey, type SpaceAccess } from "../../shared/api/access";
import { api, errorText, HttpError, jsonBody } from "../../shared/api/httpClient";

export interface CreateSpaceInput {
  name: string;
  description?: string;
  initialProjectName?: string;
}

export interface CreateInviteResult {
  invite: Invite;
  joinUrl: string;
}

export const SPACE_ROLE_LABELS: Record<SpaceRole, string> = {
  OWNER: "所有者 (OWNER)",
  ADMIN: "管理员 (ADMIN)",
  MEMBER: "成员 (MEMBER)",
  VIEWER: "只读 (VIEWER)",
};

const DOMAIN_ERROR_MESSAGES: Partial<Record<ErrorCode, string>> = {
  SPACE_SINGLE_OWNER_REQUIRED: "空间必须保留唯一 OWNER，请先将所有权转让给其他成员后再执行此操作。",
  INVITE_UNAVAILABLE: "邀请链接无效、已过期、已撤销或已达到使用次数上限。",
  IDEMPOTENCY_CONFLICT: "请求幂等键冲突（参数已变更），请刷新后重试。",
  RESOURCE_EXPIRED_OR_PURGED: "目标资源已过期或已被清理。",
  WS_CURSOR_EXPIRED: "实时同步游标已过期，正在重新拉取授权历史。",
  WS_TICKET_INVALID: "实时聊天连接票据已失效，正在重新建立连接。",
  QUOTA_EXCEEDED: "存储配额不足，请清理旧文件或联系管理员调整配额后重试。",
  SHARE_REVOKED: "该空投分享或你的接收权限已被发送者撤销。",
  DOWNLOAD_LIMIT_EXCEEDED: "已达到该空投为你设置的下载次数上限。",
  NOT_FRIENDS_FOR_AIRDROP: "只能向当前有效好友发起独立文件空投，请先确认好友关系。",
  DIFF_DATA_INSUFFICIENT: "两端点共享内容或基础对象不足以计算完整差异，请按提示使用 CLI 补传。",
  REMOTE_URL_FORBIDDEN:
    "受控策略拒绝该远端地址：仅允许受信任托管平台的 HTTPS（443 端口）地址，禁止用户信息、查询参数或非法协议。",
  SSRF_TARGET_BLOCKED: "受控出口代理已拦截目标地址（内网/保留网段或受限重定向）。",
  AI_DISABLED_IN_SPACE: "当前空间尚未启用 AI 辅助摘要，请联系空间管理员开启。",
  DOC_ANCHOR_UNPERSISTED: "所选文档内容尚未持久化保存或版本边界已变化，请等待保存完成后重试。",
  COLLAB_REVOKED: "文档协同访问授权已撤销，请重新核对空间权限。",
  INTERNAL_ERROR: "服务处理出现异常，请稍后重试。",
};

export const ALL_AI_SOURCE_KINDS: AiSourceKind[] = [
  "DOCUMENT_VERSION",
  "PDF_VERSION",
  "GIT_SNAPSHOT",
  "CHAT_MESSAGE",
];

export const AI_SOURCE_KIND_META: Record<
  AiSourceKind,
  { label: string; description: string }
> = {
  DOCUMENT_VERSION: {
    label: "协作文档版本",
    description: "允许读取发起人有权访问的已保存协作文档历史版本。",
  },
  PDF_VERSION: {
    label: "PDF 资料版本",
    description: "允许读取空间文件区中已安全解析的只读 PDF 分页文本。",
  },
  GIT_SNAPSHOT: {
    label: "Git WIP 快照",
    description: "允许读取发起人有权查看的已授权 WIP 补丁与共享说明。",
  },
  CHAT_MESSAGE: {
    label: "主群聊文本消息",
    description: "允许读取空间主群聊中的普通文本讨论消息。",
  },
};

export function makeIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `idem-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** Build a JSON RequestInit while preserving Content-Type alongside extra headers. */
export function jsonRequest(
  value: unknown,
  method = "POST",
  extraHeaders?: Record<string, string>,
): RequestInit {
  const base = jsonBody(value, method);
  return {
    ...base,
    headers: {
      "Content-Type": "application/json",
      ...extraHeaders,
    },
  };
}

export function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

/** Copy text to clipboard with execCommand fallback for non-HTTPS local origins. */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
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

export function featureErrorText(
  error: unknown,
  overrides?: Partial<Record<ErrorCode, string>>,
): string {
  if (error instanceof HttpError && error.code) {
    return overrides?.[error.code] ?? DOMAIN_ERROR_MESSAGES[error.code] ?? errorText(error);
  }
  return errorText(error);
}

export function apiPayloadErrorText(
  payload: ApiError,
  overrides?: Partial<Record<ErrorCode, string>>,
): string {
  return featureErrorText(
    new HttpError(400, payload.code, payload.requestId, payload.details),
    overrides,
  );
}

export function upsertSpaceSummary(summary: SpaceSummary): void {
  queryClient.setQueryData<SpaceSummary[]>(spacesKey, (prev) => {
    if (!prev) return [summary];
    const exists = prev.some((item) => item.spaceId === summary.spaceId);
    return exists
      ? prev.map((item) => (item.spaceId === summary.spaceId ? summary : item))
      : [summary, ...prev];
  });
}

export function buildCliQuickstart(projectId: string): string {
  return [
    "orbit login",
    `orbit init --project ${projectId}`,
    "orbit push-state --with-diff --dry-run",
  ].join("\n");
}

export function listMySpaces(signal?: AbortSignal): Promise<SpaceSummary[]> {
  return api<SpaceSummary[]>("/spaces", { signal });
}

export function createSpace(
  payload: CreateSpaceInput,
  idempotencyKey: string,
): Promise<SpaceDetail> {
  return api<SpaceDetail>(
    "/spaces",
    jsonRequest(payload, "POST", { "Idempotency-Key": idempotencyKey }),
  );
}

export function getSpaceDetail(
  spaceId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<SpaceDetail> {
  return api<SpaceDetail>(`/spaces/${encodeURIComponent(spaceId)}`, { signal }, access);
}

export function listSpaceMembers(
  spaceId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<Member[]> {
  return api<Member[]>(`/spaces/${encodeURIComponent(spaceId)}/members`, { signal }, access);
}

export function listSpaceInvites(
  spaceId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<Invite[]> {
  return api<Invite[]>(`/spaces/${encodeURIComponent(spaceId)}/invites`, { signal }, access);
}

export function createSpaceInvite(
  spaceId: string,
  access: SpaceAccess,
  payload: CreateInvite,
  idempotencyKey: string,
): Promise<CreateInviteResult> {
  return api<CreateInviteResult>(
    `/spaces/${encodeURIComponent(spaceId)}/invites`,
    jsonRequest(payload, "POST", { "Idempotency-Key": idempotencyKey }),
    access,
  );
}

export function revokeSpaceInvite(
  spaceId: string,
  inviteId: string,
  access: SpaceAccess,
): Promise<void> {
  return api<void>(
    `/spaces/${encodeURIComponent(spaceId)}/invites/${encodeURIComponent(inviteId)}`,
    { method: "DELETE" },
    access,
  );
}

export function joinSpaceByToken(token: string, idempotencyKey: string): Promise<SpaceSummary> {
  return api<SpaceSummary>(
    "/space-invites/join",
    jsonRequest({ token }, "POST", { "Idempotency-Key": idempotencyKey }),
  );
}

export function changeSpaceMemberRole(
  spaceId: string,
  userId: string,
  role: InviteRole,
  access: SpaceAccess,
): Promise<Member> {
  return api<Member>(
    `/spaces/${encodeURIComponent(spaceId)}/members/${encodeURIComponent(userId)}`,
    jsonRequest({ role }, "PATCH"),
    access,
  );
}

export function removeSpaceMember(
  spaceId: string,
  userId: string,
  access: SpaceAccess,
): Promise<void> {
  return api<void>(
    `/spaces/${encodeURIComponent(spaceId)}/members/${encodeURIComponent(userId)}`,
    { method: "DELETE" },
    access,
  );
}

export function transferSpaceOwnership(
  spaceId: string,
  newOwnerUserId: string,
  access: SpaceAccess,
  idempotencyKey: string,
): Promise<void> {
  return api<void>(
    `/spaces/${encodeURIComponent(spaceId)}/owner`,
    jsonRequest({ newOwnerUserId }, "PUT", { "Idempotency-Key": idempotencyKey }),
    access,
  );
}

export function leaveSpace(spaceId: string, access: SpaceAccess): Promise<void> {
  return api<void>(
    `/spaces/${encodeURIComponent(spaceId)}/leave`,
    { method: "POST" },
    access,
  );
}

export function createGitProject(
  spaceId: string,
  name: string,
  access: SpaceAccess,
  idempotencyKey: string,
): Promise<GitProject> {
  return api<GitProject>(
    `/spaces/${encodeURIComponent(spaceId)}/projects`,
    jsonRequest({ name }, "POST", { "Idempotency-Key": idempotencyKey }),
    access,
  );
}

export function getSpaceAiConfig(
  spaceId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<SpaceAiPolicy> {
  return api<SpaceAiPolicy>(
    `/spaces/${encodeURIComponent(spaceId)}/ai/config`,
    { signal },
    access,
  );
}

export function updateSpaceAiConfig(
  spaceId: string,
  payload: SpaceAiPolicy,
  access: SpaceAccess,
  idempotencyKey: string,
): Promise<SpaceAiPolicy> {
  return api<SpaceAiPolicy>(
    `/spaces/${encodeURIComponent(spaceId)}/ai/config`,
    jsonRequest(payload, "PUT", { "Idempotency-Key": idempotencyKey }),
    access,
  );
}

export function listSpaceDocuments(
  spaceId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<DocumentInfo[]> {
  return api<DocumentInfo[]>(
    `/spaces/${encodeURIComponent(spaceId)}/documents`,
    { signal },
    access,
  );
}

export function createSpaceDocument(
  spaceId: string,
  title: string,
  access: SpaceAccess,
  idempotencyKey: string,
): Promise<DocumentInfo> {
  return api<DocumentInfo>(
    `/spaces/${encodeURIComponent(spaceId)}/documents`,
    jsonRequest({ title }, "POST", { "Idempotency-Key": idempotencyKey }),
    access,
  );
}

export function deleteSpaceDocument(
  spaceId: string,
  documentId: string,
  access: SpaceAccess,
  idempotencyKey: string,
): Promise<void> {
  return api<void>(
    `/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}`,
    {
      method: "DELETE",
      headers: { "Idempotency-Key": idempotencyKey },
    },
    access,
  );
}

export function getDocumentSavedBoundary(
  spaceId: string,
  documentId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<DocumentSavedBoundary> {
  return api<DocumentSavedBoundary>(
    `/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/saved-boundary`,
    { signal },
    access,
  );
}

export function listDocumentVersions(
  spaceId: string,
  documentId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<DocumentVersionPage> {
  return api<DocumentVersionPage>(
    `/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/versions`,
    { signal },
    access,
  );
}

export function createDocumentVersion(
  spaceId: string,
  documentId: string,
  expectedPersistedBatchSeq: number,
  access: SpaceAccess,
  idempotencyKey: string,
): Promise<DocumentVersion> {
  return api<DocumentVersion>(
    `/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/versions`,
    jsonRequest({ expectedPersistedBatchSeq }, "POST", { "Idempotency-Key": idempotencyKey }),
    access,
  );
}

export function exportDocumentVersionMarkdown(
  spaceId: string,
  documentId: string,
  versionId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<{ markdown: string; persistedVersionId: string }> {
  return api<{ markdown: string; persistedVersionId: string }>(
    `/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/versions/${encodeURIComponent(versionId)}/markdown`,
    { signal },
    access,
  );
}

export function importDocumentMarkdown(
  spaceId: string,
  documentId: string,
  payload: { markdown: string; expectedPersistedBatchSeq: number },
  access: SpaceAccess,
  idempotencyKey: string,
): Promise<DocumentSavedBoundary> {
  return api<DocumentSavedBoundary>(
    `/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/markdown`,
    jsonRequest(payload, "POST", { "Idempotency-Key": idempotencyKey }),
    access,
  );
}

export function locateDocumentComment(
  spaceId: string,
  documentId: string,
  commentId: string,
  view: "CURRENT" | "QUOTED_VERSION",
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<DocumentComment> {
  return api<DocumentComment>(
    `/spaces/${encodeURIComponent(spaceId)}/documents/${encodeURIComponent(documentId)}/comments/${encodeURIComponent(commentId)}?view=${view}`,
    { signal },
    access,
  );
}

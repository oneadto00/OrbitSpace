import type { GitSource, P3JobReceipt } from "@orbitspace/shared-types";
import type { SpaceAccess } from "../../shared/api/access";
import { api, HttpError } from "../../shared/api/httpClient";
import { featureErrorText, jsonRequest } from "../space/spaceApi";

export type RemoteReachability = "REACHABLE" | "NO_LONGER_REACHABLE" | "UNKNOWN";
export type RemoteHistoryStatus = "COMPLETE" | "HISTORY_INCOMPLETE";

export interface RemoteGitComparison {
  remoteRef: string;
  remoteOid: string;
  syncedAt: string;
  localReachability: RemoteReachability;
  historyStatus: RemoteHistoryStatus;
  ahead: number | null;
  behind: number | null;
}

export interface BindGitSourcePayload {
  httpsUrl: string;
  accessToken?: string;
}

const MASKED_TOKEN_LABEL = "••••••••••••";

export function maskedTokenDisplay(configured: boolean): string {
  return configured ? `已配置加密 Token（${MASKED_TOKEN_LABEL}）` : "未配置访问令牌（仅公开 HTTPS 拉取）";
}

export function validateControlledHttpsUrl(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return "请输入受信任托管平台的 HTTPS 仓库地址。";
  if (trimmed.length > 2048) return "仓库地址长度超过上限（2048 字符）。";
  if (!trimmed.toLowerCase().startsWith("https://")) {
    return "仅允许 https:// 协议，禁止 file://、ssh://、http:// 或 ext:: 等协议。";
  }
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return "HTTPS 仓库地址格式无效，请核对后重试。";
  }
  if (parsed.protocol !== "https:") {
    return "仅允许 https:// 协议。";
  }
  if (parsed.username || parsed.password) {
    return "禁止在 URL 中嵌入用户名或密码/Token，请在下方独立加密令牌输入框录入。";
  }
  if (parsed.port && parsed.port !== "443") {
    return "受控策略仅允许 HTTPS 默认 443 端口。";
  }
  if (parsed.search || parsed.hash) {
    return "远端仓库地址禁止包含查询参数（?）或锚点（#）。";
  }
  if (!parsed.pathname || parsed.pathname === "/") {
    return "请提供完整的仓库路径（例如 https://github.com/org/repo.git）。";
  }
  return null;
}

export function remoteGitErrorText(error: unknown): string {
  if (error instanceof HttpError) {
    if (error.code === "REMOTE_URL_FORBIDDEN") {
      return "远端地址被受控白名单策略拒绝：仅允许受信任托管平台（HTTPS 443 端口），禁止内网地址、用户信息、查询参数或非法协议。";
    }
    if (error.code === "SSRF_TARGET_BLOCKED") {
      return "受控出口代理已拦截目标地址（内网/回环/保留网段或非法重定向），同步任务已终止。";
    }
    if (error.code === "AUTH_FORBIDDEN") {
      return "权限不足：绑定/同步需 MEMBER 及以上权限；解绑仅限空间管理员（OWNER/ADMIN）或原绑定者本人。";
    }
    if (error.code === "RESOURCE_NOT_FOUND") {
      return "未找到对应的远端来源或远端引用（请先完成一次受控同步，再执行领先/落后对比）。";
    }
  }
  return featureErrorText(error);
}

function sourcesBasePath(spaceId: string, projectId: string): string {
  return `/spaces/${encodeURIComponent(spaceId)}/projects/${encodeURIComponent(projectId)}/git-sources`;
}

export function listGitSources(
  spaceId: string,
  projectId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<GitSource[]> {
  return api<GitSource[]>(sourcesBasePath(spaceId, projectId), { signal }, access);
}

export function bindGitSource(
  spaceId: string,
  projectId: string,
  payload: BindGitSourcePayload,
  access: SpaceAccess,
  idempotencyKey: string,
): Promise<GitSource> {
  const body: BindGitSourcePayload = {
    httpsUrl: payload.httpsUrl.trim(),
    ...(payload.accessToken?.trim() ? { accessToken: payload.accessToken.trim() } : {}),
  };
  return api<GitSource>(
    sourcesBasePath(spaceId, projectId),
    jsonRequest(body, "POST", { "Idempotency-Key": idempotencyKey }),
    access,
  );
}

export function unbindGitSource(
  spaceId: string,
  projectId: string,
  sourceId: string,
  access: SpaceAccess,
  idempotencyKey: string,
): Promise<void> {
  return api<void>(
    `${sourcesBasePath(spaceId, projectId)}/${encodeURIComponent(sourceId)}`,
    {
      method: "DELETE",
      headers: { "Idempotency-Key": idempotencyKey },
    },
    access,
  );
}

export function syncGitSource(
  spaceId: string,
  projectId: string,
  sourceId: string,
  access: SpaceAccess,
  idempotencyKey: string,
): Promise<P3JobReceipt> {
  return api<P3JobReceipt>(
    `${sourcesBasePath(spaceId, projectId)}/${encodeURIComponent(sourceId)}/sync`,
    {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    },
    access,
  );
}

export function compareGitSource(
  spaceId: string,
  projectId: string,
  sourceId: string,
  params: { localOid: string; remoteRef: string },
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<RemoteGitComparison> {
  const qs = new URLSearchParams({
    localOid: params.localOid.trim(),
    remoteRef: params.remoteRef.trim(),
  });
  return api<RemoteGitComparison>(
    `${sourcesBasePath(spaceId, projectId)}/${encodeURIComponent(sourceId)}/comparison?${qs.toString()}`,
    { signal },
    access,
  );
}

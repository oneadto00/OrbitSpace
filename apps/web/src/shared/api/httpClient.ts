import type { AccessSession, ApiError, EmailCredentials } from "@orbitspace/shared-types";
import { useAuth } from "../auth/session";
import { clearPrivateData, revokeSpace, spaceSignal, type SpaceAccess } from "./access";

const base = "/api/v1";
let generation = 0;
let refreshing: Promise<AccessSession> | undefined;

export class HttpError extends Error {
  constructor(readonly status: number, readonly code?: ApiError["code"], readonly requestId?: string, readonly details?: ApiError["details"]) {
    super(code ?? `HTTP ${status}`);
  }
}

export function errorText(error: unknown) {
  if (!(error instanceof HttpError)) return "连接失败，请检查网络后重试。";
  switch (error.code) {
    case "AUTH_REVOKED": return "授权已撤销，请重新登录或联系空间管理员。";
    case "AUTH_UNAUTHORIZED": return "认证失败，请核对账号信息并重新登录。";
    case "MEMBERSHIP_EPOCH_INVALID": return "空间授权已变更，请重新选择空间。";
    case "CSRF_INVALID": return "会话校验失败，请刷新页面重试。";
    case "RATE_LIMITED": return "操作过于频繁，请稍后再试。";
    case "DEVICE_EXPIRED": return "设备短码已过期，请在终端重新申请。";
    case "AUTH_FORBIDDEN":
    case "DEVICE_ACCESS_DENIED": return "当前账号无权完成此操作。";
    case "RESOURCE_NOT_FOUND": return "未找到对应记录，请检查输入后重试。";
    case "INVALID_REQUEST": return "提交的信息无效或已过期，请核对后重试。";
    case "QUOTA_EXCEEDED": return "存储配额不足，请清理旧文件或联系管理员调整配额后重试。";
    case "SHARE_REVOKED": return "该空投分享或你的接收权限已被发送者撤销。";
    case "DOWNLOAD_LIMIT_EXCEEDED": return "已达到该空投为你设置的下载次数上限。";
    case "NOT_FRIENDS_FOR_AIRDROP": return "只能向当前有效好友发起独立文件空投，请先确认好友关系。";
    case "DIFF_DATA_INSUFFICIENT": return "两端点共享内容或基础对象不足以计算完整差异，请按提示补传内容。";
    default: return "服务暂时无法完成请求，请稍后重试。";
  }
}

async function rawRequest(path: string, init: RequestInit): Promise<Response> {
  if (!path.startsWith("/") || path.startsWith("//")) throw new Error("Invalid API path");
  const response = await fetch(base + path, { cache: "no-store", redirect: "error", ...init });
  if (!response.ok) {
    const fault: Partial<ApiError> = await response.json().catch(() => ({}));
    throw new HttpError(response.status, fault?.code, fault?.requestId, fault?.details);
  }
  return response;
}

async function request<T>(path: string, init: RequestInit): Promise<T> {
  const response = await rawRequest(path, init);
  const body = await response.text();
  return body ? JSON.parse(body) as T : undefined as T;
}

async function cookieLock<T>(task: () => Promise<T>): Promise<T> {
  // Other tabs share the rotating cookie. Serialize the CSRF lookup + use.
  return navigator.locks ? navigator.locks.request("orbitspace-auth-cookie", task) : task();
}

function checkGeneration(expected: number) {
  if (generation !== expected) throw new DOMException("Session changed", "AbortError");
}

export function endSession(notice: string | null = null) {
  generation++;
  clearPrivateData();
  useAuth.setState({ session: null, status: "anonymous", notice });
}

const changes = typeof BroadcastChannel === "function" ? new BroadcastChannel("orbitspace-account") : null;
changes?.addEventListener("message", () => endSession("账号会话已在其他标签页变更。"));

function csrf() {
  return request<{ csrfToken: string }>("/auth/csrf", { credentials: "same-origin" });
}

export function refreshSession(): Promise<AccessSession> {
  if (refreshing) return refreshing;
  const expected = generation;
  refreshing = cookieLock(async () => {
    checkGeneration(expected);
    const { csrfToken } = await csrf();
    const session = await request<AccessSession>("/auth/refresh", {
      method: "POST", credentials: "same-origin", headers: { "X-CSRF-Token": csrfToken },
    });
    checkGeneration(expected);
    if (useAuth.getState().session && useAuth.getState().session?.sessionId !== session.sessionId) {
      endSession("账号会话已变更，请重新进入工作台。");
    }
    useAuth.setState({ session, status: "authenticated" });
    return session;
  }).catch((error: unknown) => {
    if (expected === generation && error instanceof HttpError && [401, 403].includes(error.status)) {
      endSession(error.code === "AUTH_REVOKED" ? errorText(error) : null);
    }
    throw error;
  }).finally(() => { refreshing = undefined; });
  return refreshing;
}

export async function login(credentials: EmailCredentials) {
  return cookieLock(async () => {
    const session = await request<AccessSession>("/auth/login", {
      ...jsonBody(credentials), credentials: "same-origin",
    });
    endSession();
    useAuth.setState({ session, status: "authenticated" });
    changes?.postMessage("changed");
  });
}

export async function logout() {
  return cookieLock(async () => {
    try {
      const { csrfToken } = await csrf();
      await request("/auth/logout", {
        method: "POST", credentials: "same-origin", headers: { "X-CSRF-Token": csrfToken },
      });
    } catch (error) {
      if (!(error instanceof HttpError && error.status === 401)) throw error;
    }
    endSession();
    changes?.postMessage("changed");
  });
}

export function jsonBody(value: unknown, method = "POST"): RequestInit {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(value) };
}

export function publicPost(path: string, value: unknown) {
  return request<void>(path, { ...jsonBody(value), credentials: "omit" });
}

export async function apiRaw(path: string, init: RequestInit = {}, access?: SpaceAccess): Promise<Response> {
  const expected = generation;
  const send = async (session: AccessSession | null) => {
    checkGeneration(expected);
    const headers = new Headers(init.headers);
    if (session) headers.set("Authorization", `Bearer ${session.accessToken}`);
    const signals = init.signal ? [init.signal] : [];
    if (access) {
      headers.set("X-Membership-Epoch", String(access.membershipEpoch));
      signals.push(spaceSignal(access));
    }
    const signal = signals.length ? AbortSignal.any(signals) : undefined;
    const response = await rawRequest(path, { ...init, headers, signal, credentials: "omit" });
    checkGeneration(expected);
    signal?.throwIfAborted();
    return response;
  };
  const original = useAuth.getState().session;
  try {
    try {
      return await send(original);
    } catch (error) {
      if (!(error instanceof HttpError) || error.status !== 401 ||
          error.code === "AUTH_REVOKED" || error.code === "MEMBERSHIP_EPOCH_INVALID") throw error;
      checkGeneration(expected);
      const current = useAuth.getState().session;
      // A concurrent request may already have completed the refresh.
      return await send(current && original?.accessToken !== current.accessToken ? current : await refreshSession());
    }
  } catch (error) {
    if (expected === generation && error instanceof HttpError) {
      if (error.code === "AUTH_REVOKED" || error.code === "MEMBERSHIP_EPOCH_INVALID") {
        const spaceId = error.details?.spaceId;
        const membershipEpoch = error.details?.membershipEpoch;
        const affected = access ?? (typeof spaceId === "string" && typeof membershipEpoch === "number"
          ? { spaceId, membershipEpoch } : undefined);
        if (affected) revokeSpace(affected);
        else endSession(errorText(error));
      } else if (error.status === 401) {
        endSession(errorText(error));
      }
    }
    throw error;
  }
}

export async function api<T>(path: string, init: RequestInit = {}, access?: SpaceAccess): Promise<T> {
  const expected = generation;
  const response = await apiRaw(path, init, access);
  const body = await response.text();
  checkGeneration(expected);
  if (init.signal?.aborted || (access && spaceSignal(access).aborted)) {
    throw new DOMException("Aborted", "AbortError");
  }
  return body ? JSON.parse(body) as T : undefined as T;
}

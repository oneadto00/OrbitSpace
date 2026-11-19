import type { DocQuoteAnchor, DocumentTicket, PdfQuoteAnchor } from "@orbitspace/shared-types";
import type { SpaceAccess } from "../../shared/api/access";
import { api, errorText, HttpError, jsonBody } from "../../shared/api/httpClient";

export const segment = encodeURIComponent;
export const spacePath = ({ spaceId }: SpaceAccess) => "/spaces/" + segment(spaceId);
export const documentPath = (access: SpaceAccess, id: string) => spacePath(access) + "/documents/" + segment(id);
export function read<T>(access: SpaceAccess, path: string, signal?: AbortSignal) {
  return api<T>(path, { signal }, access);
}
export async function post<T>(access: SpaceAccess, path: string, data?: object, key = crypto.randomUUID()) {
  const init = data ? jsonBody(data) : { method: "POST" };
  const headers = new Headers(init.headers);
  headers.set("Idempotency-Key", key);
  try {
    return await api<T>(path, { ...init, headers }, access);
  } catch (error) {
    // Preserve the same key and body when a proxy or network breaks after commit.
    if (!(error instanceof TypeError || error instanceof HttpError && [502,503,504].includes(error.status))) throw error;
    return api<T>(path, { ...init, headers }, access);
  }
}
export const ticket = (access: SpaceAccess, documentId: string) =>
  post<DocumentTicket>(access, documentPath(access, documentId) + "/collab-ticket");

export type DiscussionQuote =
  | { kind: "DOC_REF"; commentId: string; discussionThreadId: string; anchor: DocQuoteAnchor }
  | { kind: "PDF_REF"; commentId: string; discussionThreadId: string; anchor: PdfQuoteAnchor };
export type ForwardQuote = (quote: DiscussionQuote) => Promise<void>;

export function documentError(error: unknown) {
  if (error instanceof HttpError) {
    if (error.code === "DOC_ANCHOR_UNPERSISTED") return "选区尚未保存或已变化，请重新选择并保存。";
    if (error.code === "AI_DISABLED_IN_SPACE") return "空间尚未启用 AI 摘要。";
    if (error.code === "COLLAB_REVOKED") return "协同访问权限已撤销。";
    return errorText(error);
  }
  return error instanceof Error ? error.message : "请求失败，请重试。";
}

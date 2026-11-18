import type {
  ChangeDiscussion,
  Discussion,
  EventPage,
  ExpiredCursorError,
  HistoryPage,
  MessageAck,
  ResourceCardResult,
  RoomInfo,
  SendMessage,
  SyncBoundary,
} from "@orbitspace/shared-types";
import type { SpaceAccess } from "../../shared/api/access";
import { api, HttpError } from "../../shared/api/httpClient";
import { featureErrorText, jsonRequest } from "../space/spaceApi";

export interface WsTicketResponse {
  ticket: string;
  expiresAt: string;
  wsPath: "/ws/chat";
}

const CARD_RESOLVE_BATCH_SIZE = 100;

export function chatErrorText(error: unknown): string {
  return featureErrorText(error);
}

export function extractExpiredCursorDetails(
  error: unknown,
): ExpiredCursorError["details"] | null {
  if (
    !(error instanceof HttpError) ||
    error.status !== 410 ||
    error.code !== "WS_CURSOR_EXPIRED"
  ) {
    return null;
  }
  const details = error.details;
  if (!details || typeof details !== "object") return null;
  const rawBoundary = details.boundary as Partial<SyncBoundary> | undefined;
  const resumeAfterEventSeq = details.resumeAfterEventSeq;
  if (
    !rawBoundary ||
    typeof rawBoundary.maxSeq !== "number" ||
    typeof rawBoundary.maxEventSeq !== "number" ||
    typeof rawBoundary.minAfterEventSeq !== "number" ||
    typeof resumeAfterEventSeq !== "number"
  ) {
    return null;
  }
  return {
    boundary: {
      maxSeq: rawBoundary.maxSeq,
      maxEventSeq: rawBoundary.maxEventSeq,
      minAfterEventSeq: rawBoundary.minAfterEventSeq,
    },
    resumeAfterEventSeq,
  };
}

export function getRoomInfo(
  spaceId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<RoomInfo> {
  return api<RoomInfo>(`/spaces/${encodeURIComponent(spaceId)}/chat`, { signal }, access);
}

export function issueWsTicket(
  spaceId: string,
  access: SpaceAccess,
  signal?: AbortSignal,
): Promise<WsTicketResponse> {
  return api<WsTicketResponse>(
    `/spaces/${encodeURIComponent(spaceId)}/chat/ws-ticket`,
    { method: "POST", signal },
    access,
  );
}

export function getMessageHistory(
  spaceId: string,
  access: SpaceAccess,
  params: { beforeSeq?: number; throughSeq?: number; limit?: number } = {},
  signal?: AbortSignal,
): Promise<HistoryPage> {
  const qs = new URLSearchParams();
  if (params.beforeSeq !== undefined) qs.set("beforeSeq", String(params.beforeSeq));
  if (params.throughSeq !== undefined) qs.set("throughSeq", String(params.throughSeq));
  if (params.limit !== undefined) qs.set("limit", String(params.limit));
  const suffix = qs.toString() ? `?${qs.toString()}` : "";
  return api<HistoryPage>(
    `/spaces/${encodeURIComponent(spaceId)}/chat/messages${suffix}`,
    { signal },
    access,
  );
}

export function sendMessageHttp(
  spaceId: string,
  access: SpaceAccess,
  payload: SendMessage,
  signal?: AbortSignal,
): Promise<MessageAck> {
  return api<MessageAck>(
    `/spaces/${encodeURIComponent(spaceId)}/chat/messages`,
    { ...jsonRequest(payload, "POST"), signal },
    access,
  );
}

export function getRoomEvents(
  spaceId: string,
  access: SpaceAccess,
  params: { afterEventSeq: number; throughEventSeq?: number; limit?: number },
  signal?: AbortSignal,
): Promise<EventPage> {
  const qs = new URLSearchParams();
  qs.set("afterEventSeq", String(params.afterEventSeq));
  if (params.throughEventSeq !== undefined) {
    qs.set("throughEventSeq", String(params.throughEventSeq));
  }
  if (params.limit !== undefined) qs.set("limit", String(params.limit));
  return api<EventPage>(
    `/spaces/${encodeURIComponent(spaceId)}/chat/events?${qs.toString()}`,
    { signal },
    access,
  );
}

export function advanceLastRead(
  spaceId: string,
  access: SpaceAccess,
  lastReadSeq: number,
  signal?: AbortSignal,
): Promise<{ lastReadSeq: number }> {
  return api<{ lastReadSeq: number }>(
    `/spaces/${encodeURIComponent(spaceId)}/chat/read`,
    { ...jsonRequest({ lastReadSeq }, "PUT"), signal },
    access,
  );
}

export function changeDiscussion(
  spaceId: string,
  messageId: string,
  access: SpaceAccess,
  payload: ChangeDiscussion,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<Discussion> {
  return api<Discussion>(
    `/spaces/${encodeURIComponent(spaceId)}/chat/messages/${encodeURIComponent(messageId)}/discussion`,
    {
      ...jsonRequest(payload, "PUT", { "Idempotency-Key": idempotencyKey }),
      signal,
    },
    access,
  );
}

export async function resolveResourceCards(
  spaceId: string,
  access: SpaceAccess,
  messageIds: string[],
  signal?: AbortSignal,
): Promise<ResourceCardResult[]> {
  const uniqueIds = [...new Set(messageIds)];
  if (uniqueIds.length === 0) return [];

  const results: ResourceCardResult[] = [];
  for (let i = 0; i < uniqueIds.length; i += CARD_RESOLVE_BATCH_SIZE) {
    const chunk = uniqueIds.slice(i, i + CARD_RESOLVE_BATCH_SIZE);
    const batch = await api<ResourceCardResult[]>(
      `/spaces/${encodeURIComponent(spaceId)}/chat/cards/resolve`,
      { ...jsonRequest({ messageIds: chunk }, "POST"), signal },
      access,
    );
    results.push(...batch);
  }
  return results;
}

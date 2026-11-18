import { useCallback, useEffect, useRef, useState } from "react";
import type {
  CardView,
  ChangeDiscussion,
  ChatMessage,
  Discussion,
  MessageAck,
  RoomEvent,
  RoomInfo,
  SendMessage,
  WsEnvelope,
} from "@orbitspace/shared-types";
import {
  bindSpaceConnection,
  queryClient,
  revokeSpace,
  spaceSignal,
  spacesKey,
  type SpaceAccess,
} from "../../shared/api/access";
import { apiPayloadErrorText, makeIdempotencyKey } from "../space/spaceApi";
import {
  advanceLastRead,
  changeDiscussion,
  chatErrorText,
  extractExpiredCursorDetails,
  getMessageHistory,
  getRoomEvents,
  getRoomInfo,
  issueWsTicket,
  resolveResourceCards,
  sendMessageHttp,
} from "./chatApi";

export type WsConnectionStatus =
  | "idle"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "revoked";

export interface PendingOutgoingMessage {
  clientMsgId: string;
  payload: SendMessage;
  createdAt: string;
  status: "sending" | "acked" | "failed";
  errorMessage?: string;
  messageId?: string;
  seq?: number;
}

export interface UseSpaceChatOptions {
  spaceId: string;
  access: SpaceAccess | null;
  isRevoked: boolean;
}

const HISTORY_PAGE_SIZE = 50;
const EVENT_PAGE_SIZE = 100;
const HEARTBEAT_INTERVAL_MS = 25_000;
const DRIFT_POLL_INTERVAL_MS = 15_000;
const READ_ADVANCE_DEBOUNCE_MS = 500;
const WS_ACK_HTTP_FALLBACK_MS = 4_500;

function isAbortError(error: unknown): boolean {
  return (
    (error instanceof DOMException && error.name === "AbortError") ||
    (error instanceof Error && error.name === "AbortError")
  );
}

function sortMessagesBySeq(map: Map<string, ChatMessage>): ChatMessage[] {
  return [...map.values()].sort((a, b) => {
    if (a.seq !== b.seq) return a.seq - b.seq;
    return a.messageId.localeCompare(b.messageId);
  });
}

function isCardMessage(msg: ChatMessage): boolean {
  return msg.content.kind === "COMMIT_REF" || msg.content.kind === "WIP_REF";
}

function extractCardMessageIds(messages: Iterable<ChatMessage>): string[] {
  const ids: string[] = [];
  for (const msg of messages) {
    if (isCardMessage(msg)) {
      ids.push(msg.messageId);
    }
  }
  return ids;
}

function maxSeqInMessages(messages: Iterable<ChatMessage>): number {
  let max = 0;
  for (const msg of messages) {
    if (msg.seq > max) max = msg.seq;
  }
  return max;
}

function pickNewerDiscussion(
  a: Discussion | null,
  b: Discussion | null,
): Discussion | null {
  if (!a) return b;
  if (!b) return a;
  return b.version >= a.version ? b : a;
}

export function useSpaceChat({
  spaceId,
  access,
  isRevoked,
}: UseSpaceChatOptions) {
  const [roomInfo, setRoomInfo] = useState<RoomInfo | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [pendingMessages, setPendingMessages] = useState<PendingOutgoingMessage[]>([]);
  const [hasMoreHistory, setHasMoreHistory] = useState(false);
  const [loadingInitial, setLoadingInitial] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [initialError, setInitialError] = useState<unknown>(null);
  const [olderError, setOlderError] = useState<unknown>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [wsStatus, setWsStatus] = useState<WsConnectionStatus>("idle");
  const [isRevokedInChat, setIsRevokedInChat] = useState(false);
  const [lastReadSeq, setLastReadSeq] = useState(0);
  const [eventSeq, setEventSeq] = useState(0);
  const [cardViews, setCardViews] = useState<Record<string, CardView>>({});
  const [cardResolvingIds, setCardResolvingIds] = useState<Record<string, boolean>>({});
  const [reloadNonce, setReloadNonce] = useState(0);

  // Authoritative mutable refs for the dual-cursor & WS state machine
  const epochGenRef = useRef(0);
  const roomAbortRef = useRef<AbortController | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const heartbeatTimerRef = useRef<number | null>(null);
  const reconnectTimerRef = useRef<number | null>(null);
  const readTimerRef = useRef<number | null>(null);
  const ackFallbackTimersRef = useRef<Map<string, number>>(new Map());
  const pendingStatusRef = useRef<Map<string, "sending" | "acked" | "failed">>(new Map());

  const accessRef = useRef<SpaceAccess | null>(access);
  accessRef.current = access;

  const roomIdRef = useRef<string | null>(null);
  const messagesMapRef = useRef<Map<string, ChatMessage>>(new Map());
  const nextBeforeSeqRef = useRef<number | null>(null);
  const eventSeqRef = useRef(0);
  const lastReadSeqRef = useRef(0);
  const lastRequestedReadSeqRef = useRef(0);

  const seenEventSeqsRef = useRef<Set<number>>(new Set());
  const seenTargetVersionsRef = useRef<Map<string, number>>(new Map());
  const pendingDiscussionsRef = useRef<Map<string, Discussion>>(new Map());
  const cardInvalidationSeqRef = useRef<Record<string, number>>({});

  const revokedLocallyRef = useRef(false);
  const hasOpenedOnceRef = useRef(false);
  const reconnectAttemptRef = useRef(0);
  const syncInFlightRef = useRef(false);
  const syncQueuedRef = useRef(false);
  const queuedResolveAllCardsRef = useRef(false);
  const syncEventsRef = useRef<(opts?: { resolveAllVisibleCards?: boolean }) => Promise<void>>(
    async () => {},
  );

  const cleanupTimersAndSocket = useCallback(() => {
    if (heartbeatTimerRef.current !== null) {
      window.clearInterval(heartbeatTimerRef.current);
      heartbeatTimerRef.current = null;
    }
    if (reconnectTimerRef.current !== null) {
      window.clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    if (readTimerRef.current !== null) {
      window.clearTimeout(readTimerRef.current);
      readTimerRef.current = null;
    }
    for (const timer of ackFallbackTimersRef.current.values()) {
      window.clearTimeout(timer);
    }
    ackFallbackTimersRef.current.clear();
    pendingStatusRef.current.clear();

    const ws = wsRef.current;
    wsRef.current = null;
    if (ws) {
      ws.onopen = null;
      ws.onmessage = null;
      ws.onerror = null;
      ws.onclose = null;
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close();
      }
    }
  }, []);

  const resetRoomState = useCallback(() => {
    roomIdRef.current = null;
    messagesMapRef.current.clear();
    nextBeforeSeqRef.current = null;
    eventSeqRef.current = 0;
    lastReadSeqRef.current = 0;
    lastRequestedReadSeqRef.current = 0;
    seenEventSeqsRef.current.clear();
    seenTargetVersionsRef.current.clear();
    pendingDiscussionsRef.current.clear();
    cardInvalidationSeqRef.current = {};
    hasOpenedOnceRef.current = false;
    reconnectAttemptRef.current = 0;
    syncInFlightRef.current = false;
    syncQueuedRef.current = false;
    queuedResolveAllCardsRef.current = false;

    setRoomInfo(null);
    setMessages([]);
    setPendingMessages([]);
    setHasMoreHistory(false);
    setInitialError(null);
    setOlderError(null);
    setSyncError(null);
    setLastReadSeq(0);
    setEventSeq(0);
    setCardViews({});
    setCardResolvingIds({});
  }, []);

  const scheduleReadAdvance = useCallback(
    (candidateSeq: number) => {
      const currentAccess = accessRef.current;
      const signal = roomAbortRef.current?.signal;
      if (!currentAccess || !signal || signal.aborted || revokedLocallyRef.current) return;
      if (candidateSeq <= lastRequestedReadSeqRef.current) return;

      lastRequestedReadSeqRef.current = candidateSeq;
      if (readTimerRef.current !== null) {
        window.clearTimeout(readTimerRef.current);
      }

      const capturedGen = epochGenRef.current;
      readTimerRef.current = window.setTimeout(() => {
        readTimerRef.current = null;
        if (
          capturedGen !== epochGenRef.current ||
          signal.aborted ||
          revokedLocallyRef.current
        ) {
          return;
        }
        const targetSeq = lastRequestedReadSeqRef.current;
        if (targetSeq <= lastReadSeqRef.current) return;

        void advanceLastRead(spaceId, currentAccess, targetSeq, signal)
          .then((res) => {
            if (
              capturedGen !== epochGenRef.current ||
              signal.aborted ||
              revokedLocallyRef.current
            ) {
              return;
            }
            const prevPersisted = lastReadSeqRef.current;
            const persisted = Math.max(prevPersisted, res.lastReadSeq);
            lastReadSeqRef.current = persisted;
            lastRequestedReadSeqRef.current = Math.max(
              lastRequestedReadSeqRef.current,
              persisted,
            );
            setLastReadSeq(persisted);
            if (persisted > prevPersisted) {
              void queryClient.invalidateQueries({ queryKey: spacesKey });
            }
          })
          .catch((error) => {
            if (!isAbortError(error) && capturedGen === epochGenRef.current) {
              // Non-fatal; next advance will retry
              lastRequestedReadSeqRef.current = Math.max(
                lastReadSeqRef.current,
                lastRequestedReadSeqRef.current,
              );
            }
          });
      }, READ_ADVANCE_DEBOUNCE_MS);
    },
    [spaceId],
  );

  const resolveCardsBatch = useCallback(
    async (rawIds: string[]): Promise<Record<string, CardView>> => {
      const currentAccess = accessRef.current;
      const signal = roomAbortRef.current?.signal;
      if (!currentAccess || !signal || signal.aborted || revokedLocallyRef.current) {
        return {};
      }
      const uniqueCardIds = [...new Set(rawIds)].filter((id) => {
        const msg = messagesMapRef.current.get(id);
        return msg ? isCardMessage(msg) : false;
      });
      if (uniqueCardIds.length === 0) return {};

      const capturedGen = epochGenRef.current;
      const invalidationSnapshot: Record<string, number> = {};
      for (const id of uniqueCardIds) {
        invalidationSnapshot[id] = cardInvalidationSeqRef.current[id] ?? 0;
      }

      setCardResolvingIds((prev) => {
        const next = { ...prev };
        for (const id of uniqueCardIds) next[id] = true;
        return next;
      });

      try {
        const results = await resolveResourceCards(
          spaceId,
          currentAccess,
          uniqueCardIds,
          signal,
        );
        if (
          capturedGen !== epochGenRef.current ||
          signal.aborted ||
          revokedLocallyRef.current
        ) {
          return {};
        }

        const resolvedMap: Record<string, CardView> = {};
        for (const item of results) {
          resolvedMap[item.messageId] = item.view;
        }

        setCardViews((prev) => {
          const next = { ...prev };
          for (const item of results) {
            const curSeq = cardInvalidationSeqRef.current[item.messageId] ?? 0;
            if (curSeq === invalidationSnapshot[item.messageId]) {
              next[item.messageId] = item.view;
            }
          }
          return next;
        });

        return resolvedMap;
      } catch (error) {
        if (!isAbortError(error) && capturedGen === epochGenRef.current) {
          setSyncError(chatErrorText(error));
        }
        return {};
      } finally {
        if (capturedGen === epochGenRef.current) {
          setCardResolvingIds((prev) => {
            const next = { ...prev };
            for (const id of uniqueCardIds) delete next[id];
            return next;
          });
        }
      }
    },
    [spaceId],
  );

  const applyRoomEvents = useCallback(
    (events: RoomEvent[]): { cardMessageIds: string[]; maxNewSeq: number } => {
      let messagesChanged = false;
      const cardMessageIds = new Set<string>();
      const invalidatedCardIds = new Set<string>();
      const ackedClientMsgIds = new Set<string>();
      const ackedMsgIds = new Set<string>();
      let maxNewSeq = 0;

      for (const event of events) {
        if (roomIdRef.current && event.roomId !== roomIdRef.current) continue;
        if (seenEventSeqsRef.current.has(event.eventSeq)) continue;
        seenEventSeqsRef.current.add(event.eventSeq);

        if (event.type === "MESSAGE_CREATED") {
          const verKey = `MSG:${event.targetId}`;
          const prevVer = seenTargetVersionsRef.current.get(verKey) ?? 0;
          if (event.version <= prevVer) continue;
          seenTargetVersionsRef.current.set(verKey, event.version);

          const incoming = event.payload.message;
          const existing = messagesMapRef.current.get(incoming.messageId);
          const pendingDisc = pendingDiscussionsRef.current.get(incoming.messageId) ?? null;

          let mergedDiscussion = pickNewerDiscussion(
            existing?.discussion ?? null,
            incoming.discussion,
          );
          mergedDiscussion = pickNewerDiscussion(mergedDiscussion, pendingDisc);
          if (pendingDisc) {
            pendingDiscussionsRef.current.delete(incoming.messageId);
          }

          const mergedMessage: ChatMessage = {
            ...incoming,
            discussion: mergedDiscussion,
          };

          messagesMapRef.current.set(mergedMessage.messageId, mergedMessage);
          messagesChanged = true;
          ackedMsgIds.add(mergedMessage.messageId);
          if (mergedMessage.clientMsgId) {
            ackedClientMsgIds.add(mergedMessage.clientMsgId);
          }
          if (isCardMessage(mergedMessage)) {
            cardMessageIds.add(mergedMessage.messageId);
          }
          if (mergedMessage.seq > maxNewSeq) {
            maxNewSeq = mergedMessage.seq;
          }
        } else if (event.type === "DISCUSSION_STATUS_CHANGED") {
          const verKey = `DISC:${event.targetId}`;
          const prevVer = seenTargetVersionsRef.current.get(verKey) ?? 0;
          if (event.version <= prevVer) continue;

          const existing = messagesMapRef.current.get(event.targetId);
          const existingDiscVer = existing?.discussion?.version ?? 0;
          if (existing) {
            if (event.payload.discussion.version <= existingDiscVer) continue;
            seenTargetVersionsRef.current.set(verKey, event.version);
            messagesMapRef.current.set(event.targetId, {
              ...existing,
              discussion: event.payload.discussion,
            });
            messagesChanged = true;
          } else {
            const pendingDisc = pendingDiscussionsRef.current.get(event.targetId);
            if (!pendingDisc || event.payload.discussion.version > pendingDisc.version) {
              pendingDiscussionsRef.current.set(event.targetId, event.payload.discussion);
            }
            seenTargetVersionsRef.current.set(verKey, event.version);
          }
        } else if (event.type === "RESOURCE_CARD_STATE_CHANGED") {
          const verKey = `CARD:${event.targetId}`;
          const prevVer = seenTargetVersionsRef.current.get(verKey) ?? 0;
          if (event.version <= prevVer) continue;
          seenTargetVersionsRef.current.set(verKey, event.version);

          cardInvalidationSeqRef.current[event.targetId] =
            (cardInvalidationSeqRef.current[event.targetId] ?? 0) + 1;
          invalidatedCardIds.add(event.targetId);
          cardMessageIds.add(event.targetId);
        }
      }

      if (invalidatedCardIds.size > 0) {
        setCardViews((prev) => {
          let touched = false;
          const next = { ...prev };
          for (const id of invalidatedCardIds) {
            if (id in next) {
              delete next[id];
              touched = true;
            }
          }
          return touched ? next : prev;
        });
      }

      if (messagesChanged) {
        setMessages(sortMessagesBySeq(messagesMapRef.current));
      }

      if (ackedClientMsgIds.size > 0 || ackedMsgIds.size > 0) {
        for (const cId of ackedClientMsgIds) {
          pendingStatusRef.current.delete(cId);
          const t = ackFallbackTimersRef.current.get(cId);
          if (t !== undefined) {
            window.clearTimeout(t);
            ackFallbackTimersRef.current.delete(cId);
          }
        }
        setPendingMessages((prev) =>
          prev.filter(
            (item) =>
              !ackedClientMsgIds.has(item.clientMsgId) &&
              !(item.messageId && ackedMsgIds.has(item.messageId)),
          ),
        );
      }

      if (maxNewSeq > 0) {
        scheduleReadAdvance(maxNewSeq);
      }

      return { cardMessageIds: [...cardMessageIds], maxNewSeq };
    },
    [scheduleReadAdvance],
  );

  const handleCursorExpiredReset = useCallback(
    async (
      details: {
        boundary: { maxSeq: number; maxEventSeq: number; minAfterEventSeq: number };
        resumeAfterEventSeq: number;
      },
      capturedGen: number,
      signal: AbortSignal,
    ) => {
      const currentAccess = accessRef.current;
      if (!currentAccess || signal.aborted || revokedLocallyRef.current) return;

      const reloaded = await getMessageHistory(
        spaceId,
        currentAccess,
        { throughSeq: details.boundary.maxSeq, limit: HISTORY_PAGE_SIZE },
        signal,
      );

      if (
        capturedGen !== epochGenRef.current ||
        signal.aborted ||
        revokedLocallyRef.current
      ) {
        return;
      }

      messagesMapRef.current.clear();
      seenEventSeqsRef.current.clear();
      seenTargetVersionsRef.current.clear();
      pendingDiscussionsRef.current.clear();
      cardInvalidationSeqRef.current = {};

      const reloadedIds = new Set<string>();
      const reloadedClientIds = new Set<string>();
      for (const msg of reloaded.messages) {
        messagesMapRef.current.set(msg.messageId, msg);
        reloadedIds.add(msg.messageId);
        if (msg.clientMsgId) reloadedClientIds.add(msg.clientMsgId);
      }
      setPendingMessages((prev) =>
        prev.filter(
          (item) =>
            !reloadedClientIds.has(item.clientMsgId) &&
            !(item.messageId && reloadedIds.has(item.messageId)),
        ),
      );

      nextBeforeSeqRef.current = reloaded.nextBeforeSeq;
      setHasMoreHistory(reloaded.nextBeforeSeq !== null);
      setCardViews({});
      setMessages(sortMessagesBySeq(messagesMapRef.current));

      // Switch eventSeq to details.resumeAfterEventSeq as instructed by 410 contract
      eventSeqRef.current = details.resumeAfterEventSeq;
      setEventSeq(details.resumeAfterEventSeq);

      const visibleCardIds = extractCardMessageIds(messagesMapRef.current.values());
      if (visibleCardIds.length > 0) {
        await resolveCardsBatch(visibleCardIds);
      }

      const maxSeq = maxSeqInMessages(messagesMapRef.current.values());
      if (maxSeq > lastReadSeqRef.current) {
        scheduleReadAdvance(maxSeq);
      }

      // Single follow-up replay from resumeAfterEventSeq in case events landed during reload
      if (
        capturedGen === epochGenRef.current &&
        !signal.aborted &&
        !revokedLocallyRef.current
      ) {
        try {
          let replayCursor = eventSeqRef.current;
          let hasMore = true;
          let pages = 0;
          const postResetCards = new Set<string>();
          while (hasMore && pages < 20) {
            const page = await getRoomEvents(
              spaceId,
              currentAccess,
              { afterEventSeq: replayCursor, limit: EVENT_PAGE_SIZE },
              signal,
            );
            if (
              capturedGen !== epochGenRef.current ||
              signal.aborted ||
              revokedLocallyRef.current
            ) {
              return;
            }
            const sorted = [...page.events].sort((a, b) => a.eventSeq - b.eventSeq);
            const { cardMessageIds } = applyRoomEvents(sorted);
            for (const cid of cardMessageIds) postResetCards.add(cid);
            eventSeqRef.current = Math.max(eventSeqRef.current, page.nextAfterEventSeq);
            setEventSeq(eventSeqRef.current);
            if (page.hasMore && page.nextAfterEventSeq <= replayCursor) break;
            replayCursor = page.nextAfterEventSeq;
            hasMore = page.hasMore;
            pages++;
          }
          if (postResetCards.size > 0) {
            await resolveCardsBatch([...postResetCards]);
          }
        } catch (followErr) {
          if (!isAbortError(followErr) && !extractExpiredCursorDetails(followErr)) {
            setSyncError(chatErrorText(followErr));
          }
        }
      }
    },
    [applyRoomEvents, resolveCardsBatch, scheduleReadAdvance, spaceId],
  );

  const syncEvents = useCallback(
    async ({ resolveAllVisibleCards = false }: { resolveAllVisibleCards?: boolean } = {}) => {
      const currentAccess = accessRef.current;
      const signal = roomAbortRef.current?.signal;
      if (!currentAccess || !signal || signal.aborted || revokedLocallyRef.current) {
        return;
      }

      if (syncInFlightRef.current) {
        syncQueuedRef.current = true;
        if (resolveAllVisibleCards) queuedResolveAllCardsRef.current = true;
        return;
      }

      syncInFlightRef.current = true;
      const capturedGen = epochGenRef.current;

      try {
        let cursor = eventSeqRef.current;
        let hasMore = true;
        let pages = 0;
        const affectedCards = new Set<string>();

        while (hasMore && pages < 50) {
          if (
            capturedGen !== epochGenRef.current ||
            signal.aborted ||
            revokedLocallyRef.current
          ) {
            return;
          }
          const page = await getRoomEvents(
            spaceId,
            currentAccess,
            { afterEventSeq: cursor, limit: EVENT_PAGE_SIZE },
            signal,
          );
          if (
            capturedGen !== epochGenRef.current ||
            signal.aborted ||
            revokedLocallyRef.current
          ) {
            return;
          }

          const sorted = [...page.events].sort((a, b) => a.eventSeq - b.eventSeq);
          const { cardMessageIds } = applyRoomEvents(sorted);
          for (const cid of cardMessageIds) affectedCards.add(cid);

          eventSeqRef.current = Math.max(eventSeqRef.current, page.nextAfterEventSeq);
          setEventSeq(eventSeqRef.current);

          if (page.hasMore && page.nextAfterEventSeq <= cursor) break;
          cursor = page.nextAfterEventSeq;
          hasMore = page.hasMore;
          pages++;
        }

        if (resolveAllVisibleCards) {
          const allVisibleCards = extractCardMessageIds(messagesMapRef.current.values());
          if (allVisibleCards.length > 0) {
            await resolveCardsBatch(allVisibleCards);
          }
        } else if (affectedCards.size > 0) {
          await resolveCardsBatch([...affectedCards]);
        }

        if (capturedGen === epochGenRef.current) {
          setSyncError(null);
        }
      } catch (error) {
        if (isAbortError(error) || capturedGen !== epochGenRef.current) return;
        const expired = extractExpiredCursorDetails(error);
        if (expired) {
          try {
            await handleCursorExpiredReset(expired, capturedGen, signal);
            if (capturedGen === epochGenRef.current) {
              setSyncError(null);
            }
          } catch (resetErr) {
            if (!isAbortError(resetErr) && capturedGen === epochGenRef.current) {
              setSyncError(chatErrorText(resetErr));
            }
          }
        } else {
          setSyncError(chatErrorText(error));
        }
      } finally {
        syncInFlightRef.current = false;
        if (
          syncQueuedRef.current &&
          capturedGen === epochGenRef.current &&
          !signal.aborted &&
          !revokedLocallyRef.current
        ) {
          const nextResolveAll = queuedResolveAllCardsRef.current;
          syncQueuedRef.current = false;
          queuedResolveAllCardsRef.current = false;
          void syncEventsRef.current({ resolveAllVisibleCards: nextResolveAll });
        } else {
          syncQueuedRef.current = false;
          queuedResolveAllCardsRef.current = false;
        }
      }
    },
    [applyRoomEvents, handleCursorExpiredReset, resolveCardsBatch, spaceId],
  );

  syncEventsRef.current = syncEvents;

  const handleMsgAck = useCallback(
    (ack: MessageAck) => {
      if (roomIdRef.current && ack.roomId !== roomIdRef.current) return;
      const t = ackFallbackTimersRef.current.get(ack.clientMsgId);
      if (t !== undefined) {
        window.clearTimeout(t);
        ackFallbackTimersRef.current.delete(ack.clientMsgId);
      }

      if (messagesMapRef.current.has(ack.messageId)) {
        pendingStatusRef.current.delete(ack.clientMsgId);
        setPendingMessages((prev) =>
          prev.filter((item) => item.clientMsgId !== ack.clientMsgId),
        );
      } else {
        pendingStatusRef.current.set(ack.clientMsgId, "acked");
        setPendingMessages((prev) =>
          prev.map((item) =>
            item.clientMsgId === ack.clientMsgId
              ? {
                  ...item,
                  status: "acked",
                  messageId: ack.messageId,
                  seq: ack.seq,
                  errorMessage: undefined,
                }
              : item,
          ),
        );
      }

      scheduleReadAdvance(ack.seq);

      if (ack.eventSeq > eventSeqRef.current + 1) {
        void syncEventsRef.current({ resolveAllVisibleCards: false });
      }
    },
    [scheduleReadAdvance],
  );

  const handleLiveRoomEvent = useCallback(
    (event: RoomEvent) => {
      if (roomIdRef.current && event.roomId !== roomIdRef.current) return;

      const expectedNext = eventSeqRef.current + 1;
      const { cardMessageIds } = applyRoomEvents([event]);

      if (event.eventSeq === expectedNext) {
        eventSeqRef.current = event.eventSeq;
        setEventSeq(event.eventSeq);
        if (cardMessageIds.length > 0) {
          void resolveCardsBatch(cardMessageIds);
        }
      } else if (event.eventSeq > expectedNext) {
        // Gap detected: do not advance contiguous eventSeqRef past missing range; trigger HTTP replay
        void syncEventsRef.current({ resolveAllVisibleCards: false });
      } else if (cardMessageIds.length > 0) {
        void resolveCardsBatch(cardMessageIds);
      }
    },
    [applyRoomEvents, resolveCardsBatch],
  );

  // Main lifecycle effect: initial load + WS connection + 15s drift poll + revocation binding
  useEffect(() => {
    const capturedGen = ++epochGenRef.current;
    roomAbortRef.current?.abort();
    cleanupTimersAndSocket();
    resetRoomState();

    if (!access || isRevoked || spaceSignal(access).aborted) {
      revokedLocallyRef.current = isRevoked || Boolean(access && spaceSignal(access).aborted);
      setIsRevokedInChat(revokedLocallyRef.current);
      setWsStatus(revokedLocallyRef.current ? "revoked" : "idle");
      setLoadingInitial(false);
      return;
    }

    revokedLocallyRef.current = false;
    setIsRevokedInChat(false);
    const controller = new AbortController();
    roomAbortRef.current = controller;

    const handleInstantRevoke = () => {
      if (capturedGen !== epochGenRef.current) return;
      revokedLocallyRef.current = true;
      setIsRevokedInChat(true);
      setWsStatus("revoked");
      controller.abort();
      cleanupTimersAndSocket();
      resetRoomState();
    };

    const unbind = bindSpaceConnection(access, handleInstantRevoke);
    if (controller.signal.aborted || revokedLocallyRef.current) {
      unbind();
      return;
    }

    let driftTimer: number | null = null;

    const scheduleReconnect = () => {
      if (
        capturedGen !== epochGenRef.current ||
        controller.signal.aborted ||
        revokedLocallyRef.current ||
        spaceSignal(access).aborted
      ) {
        return;
      }
      if (reconnectTimerRef.current !== null) {
        window.clearTimeout(reconnectTimerRef.current);
      }
      const attempt = reconnectAttemptRef.current;
      reconnectAttemptRef.current = Math.min(attempt + 1, 6);
      const delay = Math.min(15_000, 1000 * Math.pow(2, attempt)) + Math.floor(Math.random() * 250);
      setWsStatus("reconnecting");
      reconnectTimerRef.current = window.setTimeout(() => {
        reconnectTimerRef.current = null;
        void connectSocket();
      }, delay);
    };

    const connectSocket = async () => {
      if (
        capturedGen !== epochGenRef.current ||
        controller.signal.aborted ||
        revokedLocallyRef.current ||
        spaceSignal(access).aborted
      ) {
        return;
      }

      if (heartbeatTimerRef.current !== null) {
        window.clearInterval(heartbeatTimerRef.current);
        heartbeatTimerRef.current = null;
      }

      setWsStatus(hasOpenedOnceRef.current ? "reconnecting" : "connecting");

      try {
        const ticketRes = await issueWsTicket(spaceId, access, controller.signal);
        if (
          capturedGen !== epochGenRef.current ||
          controller.signal.aborted ||
          revokedLocallyRef.current ||
          spaceSignal(access).aborted
        ) {
          return;
        }
        if (!ticketRes.wsPath.startsWith("/") || ticketRes.wsPath.startsWith("//")) {
          throw new Error("Invalid WebSocket path");
        }

        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        const wsUrl = `${protocol}//${window.location.host}${ticketRes.wsPath}?ticket=${encodeURIComponent(ticketRes.ticket)}`;
        const ws = new WebSocket(wsUrl);
        wsRef.current = ws;

        ws.onopen = () => {
          if (
            capturedGen !== epochGenRef.current ||
            controller.signal.aborted ||
            revokedLocallyRef.current
          ) {
            ws.close();
            return;
          }
          const isReconnect = hasOpenedOnceRef.current;
          hasOpenedOnceRef.current = true;
          reconnectAttemptRef.current = 0;
          setWsStatus("connected");
          setSyncError(null);

          heartbeatTimerRef.current = window.setInterval(() => {
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify({ type: "PING" } satisfies WsEnvelope));
            }
          }, HEARTBEAT_INTERVAL_MS);

          void syncEventsRef.current({ resolveAllVisibleCards: isReconnect });
        };

        ws.onmessage = (event) => {
          if (
            capturedGen !== epochGenRef.current ||
            controller.signal.aborted ||
            revokedLocallyRef.current
          ) {
            return;
          }
          if (typeof event.data !== "string") return;
          let frame: WsEnvelope;
          try {
            frame = JSON.parse(event.data) as WsEnvelope;
          } catch {
            return;
          }

          switch (frame.type) {
            case "PONG":
              break;
            case "MSG_ACK":
              handleMsgAck(frame.payload);
              break;
            case "ROOM_EVENT":
              handleLiveRoomEvent(frame.payload);
              break;
            case "AUTH_REVOKED_CLOSE": {
              revokedLocallyRef.current = true;
              setIsRevokedInChat(true);
              setWsStatus("revoked");
              controller.abort();
              cleanupTimersAndSocket();
              resetRoomState();
              const targetEpoch =
                frame.payload.spaceId === access.spaceId
                  ? Math.max(frame.payload.membershipEpoch, access.membershipEpoch)
                  : frame.payload.membershipEpoch;
              revokeSpace({
                spaceId: frame.payload.spaceId,
                membershipEpoch: targetEpoch,
              });
              break;
            }
            case "ERROR": {
              const messageText = apiPayloadErrorText(frame.payload);
              setSyncError(messageText);
              const detailClientMsgId = frame.payload.details?.clientMsgId;
              if (typeof detailClientMsgId === "string") {
                pendingStatusRef.current.set(detailClientMsgId, "failed");
                const t = ackFallbackTimersRef.current.get(detailClientMsgId);
                if (t !== undefined) {
                  window.clearTimeout(t);
                  ackFallbackTimersRef.current.delete(detailClientMsgId);
                }
                setPendingMessages((prev) =>
                  prev.map((item) =>
                    item.clientMsgId === detailClientMsgId
                      ? { ...item, status: "failed", errorMessage: messageText }
                      : item,
                  ),
                );
              }
              if (
                frame.payload.code === "AUTH_REVOKED" ||
                frame.payload.code === "MEMBERSHIP_EPOCH_INVALID"
              ) {
                revokeSpace(access);
              }
              break;
            }
          }
        };

        ws.onclose = () => {
          if (heartbeatTimerRef.current !== null) {
            window.clearInterval(heartbeatTimerRef.current);
            heartbeatTimerRef.current = null;
          }
          if (wsRef.current === ws) {
            wsRef.current = null;
          }
          if (
            capturedGen !== epochGenRef.current ||
            controller.signal.aborted ||
            revokedLocallyRef.current ||
            spaceSignal(access).aborted
          ) {
            return;
          }
          scheduleReconnect();
        };
      } catch (error) {
        if (
          isAbortError(error) ||
          capturedGen !== epochGenRef.current ||
          revokedLocallyRef.current ||
          spaceSignal(access).aborted
        ) {
          return;
        }
        setSyncError(chatErrorText(error));
        scheduleReconnect();
      }
    };

    const initialize = async () => {
      setLoadingInitial(true);
      setInitialError(null);
      try {
        const info = await getRoomInfo(spaceId, access, controller.signal);
        if (
          capturedGen !== epochGenRef.current ||
          controller.signal.aborted ||
          revokedLocallyRef.current
        ) {
          return;
        }
        const history = await getMessageHistory(
          spaceId,
          access,
          { limit: HISTORY_PAGE_SIZE },
          controller.signal,
        );
        if (
          capturedGen !== epochGenRef.current ||
          controller.signal.aborted ||
          revokedLocallyRef.current
        ) {
          return;
        }

        roomIdRef.current = info.roomId;
        setRoomInfo(info);

        messagesMapRef.current.clear();
        for (const msg of history.messages) {
          messagesMapRef.current.set(msg.messageId, msg);
        }

        nextBeforeSeqRef.current = history.nextBeforeSeq;
        setHasMoreHistory(history.nextBeforeSeq !== null);
        setMessages(sortMessagesBySeq(messagesMapRef.current));

        eventSeqRef.current = history.boundary.maxEventSeq;
        setEventSeq(history.boundary.maxEventSeq);

        lastReadSeqRef.current = info.lastReadSeq;
        lastRequestedReadSeqRef.current = info.lastReadSeq;
        setLastReadSeq(info.lastReadSeq);

        setLoadingInitial(false);

        const initialCards = extractCardMessageIds(messagesMapRef.current.values());
        if (initialCards.length > 0) {
          void resolveCardsBatch(initialCards);
        }

        const maxLoadedSeq = maxSeqInMessages(messagesMapRef.current.values());
        if (maxLoadedSeq > info.lastReadSeq) {
          scheduleReadAdvance(maxLoadedSeq);
        }

        driftTimer = window.setInterval(() => {
          void syncEventsRef.current({ resolveAllVisibleCards: false });
        }, DRIFT_POLL_INTERVAL_MS);

        void connectSocket();
      } catch (error) {
        if (isAbortError(error) || capturedGen !== epochGenRef.current) return;
        setLoadingInitial(false);
        setInitialError(error);
      }
    };

    void initialize();

    return () => {
      unbind();
      if (driftTimer !== null) {
        window.clearInterval(driftTimer);
      }
      controller.abort();
      cleanupTimersAndSocket();
    };
  }, [
    access?.membershipEpoch,
    access?.spaceId,
    cleanupTimersAndSocket,
    handleLiveRoomEvent,
    handleMsgAck,
    isRevoked,
    reloadNonce,
    resetRoomState,
    resolveCardsBatch,
    scheduleReadAdvance,
    spaceId,
  ]);

  const loadOlderHistory = useCallback(async () => {
    const currentAccess = accessRef.current;
    const signal = roomAbortRef.current?.signal;
    const beforeSeq = nextBeforeSeqRef.current;
    if (
      !currentAccess ||
      !signal ||
      signal.aborted ||
      revokedLocallyRef.current ||
      beforeSeq === null ||
      loadingOlder
    ) {
      return;
    }

    const capturedGen = epochGenRef.current;
    setLoadingOlder(true);
    setOlderError(null);
    try {
      const page = await getMessageHistory(
        spaceId,
        currentAccess,
        { beforeSeq, limit: HISTORY_PAGE_SIZE },
        signal,
      );
      if (
        capturedGen !== epochGenRef.current ||
        signal.aborted ||
        revokedLocallyRef.current
      ) {
        return;
      }

      const newlyLoadedCardIds: string[] = [];
      for (const msg of page.messages) {
        const existing = messagesMapRef.current.get(msg.messageId);
        const pendingDisc = pendingDiscussionsRef.current.get(msg.messageId) ?? null;
        let mergedDisc = pickNewerDiscussion(
          existing?.discussion ?? null,
          msg.discussion,
        );
        mergedDisc = pickNewerDiscussion(mergedDisc, pendingDisc);
        const merged: ChatMessage = {
          ...msg,
          discussion: mergedDisc,
        };
        if (pendingDisc) {
          pendingDiscussionsRef.current.delete(msg.messageId);
        }
        messagesMapRef.current.set(merged.messageId, merged);
        if (isCardMessage(merged)) {
          newlyLoadedCardIds.push(merged.messageId);
        }
      }

      nextBeforeSeqRef.current = page.nextBeforeSeq;
      setHasMoreHistory(page.nextBeforeSeq !== null);
      setMessages(sortMessagesBySeq(messagesMapRef.current));

      if (newlyLoadedCardIds.length > 0) {
        void resolveCardsBatch(newlyLoadedCardIds);
      }
    } catch (error) {
      if (!isAbortError(error) && capturedGen === epochGenRef.current) {
        setOlderError(error);
      }
    } finally {
      if (capturedGen === epochGenRef.current) {
        setLoadingOlder(false);
      }
    }
  }, [loadingOlder, resolveCardsBatch, spaceId]);

  const sendViaHttp = useCallback(
    async (payload: SendMessage, capturedGen: number) => {
      const currentAccess = accessRef.current;
      const signal = roomAbortRef.current?.signal;
      if (!currentAccess || !signal || signal.aborted || revokedLocallyRef.current) {
        return;
      }

      try {
        const ack = await sendMessageHttp(spaceId, currentAccess, payload, signal);
        if (
          capturedGen !== epochGenRef.current ||
          signal.aborted ||
          revokedLocallyRef.current
        ) {
          return;
        }
        handleMsgAck(ack);
        if (!messagesMapRef.current.has(ack.messageId)) {
          void syncEventsRef.current({ resolveAllVisibleCards: false });
        }
      } catch (error) {
        if (isAbortError(error) || capturedGen !== epochGenRef.current) return;
        const msgText = chatErrorText(error);
        pendingStatusRef.current.set(payload.clientMsgId, "failed");
        setPendingMessages((prev) =>
          prev.map((item) =>
            item.clientMsgId === payload.clientMsgId
              ? { ...item, status: "failed", errorMessage: msgText }
              : item,
          ),
        );
      }
    },
    [handleMsgAck, spaceId],
  );

  const dispatchOutgoingPayload = useCallback(
    async (payload: SendMessage) => {
      const currentAccess = accessRef.current;
      const signal = roomAbortRef.current?.signal;
      if (!currentAccess || !signal || signal.aborted || revokedLocallyRef.current) {
        return;
      }

      const capturedGen = epochGenRef.current;
      const ws = wsRef.current;

      if (ws && ws.readyState === WebSocket.OPEN) {
        try {
          ws.send(JSON.stringify({ type: "SEND_MSG", payload } satisfies WsEnvelope));
          const existingTimer = ackFallbackTimersRef.current.get(payload.clientMsgId);
          if (existingTimer !== undefined) {
            window.clearTimeout(existingTimer);
          }
          const timer = window.setTimeout(() => {
            ackFallbackTimersRef.current.delete(payload.clientMsgId);
            if (
              capturedGen !== epochGenRef.current ||
              signal.aborted ||
              revokedLocallyRef.current
            ) {
              return;
            }
            // Idempotent HTTP fallback with exact same clientMsgId if WS ACK did not arrive
            if (pendingStatusRef.current.get(payload.clientMsgId) === "sending") {
              void sendViaHttp(payload, capturedGen);
            }
          }, WS_ACK_HTTP_FALLBACK_MS);
          ackFallbackTimersRef.current.set(payload.clientMsgId, timer);
          return;
        } catch {
          // Fall through to HTTP send with the same clientMsgId
        }
      }

      await sendViaHttp(payload, capturedGen);
    },
    [sendViaHttp],
  );

  const sendMessage = useCallback(
    async (input: { content: SendMessage["content"]; replyToMsgId?: string }) => {
      const clientMsgId = makeIdempotencyKey();
      const payload: SendMessage = {
        clientMsgId,
        ...(input.replyToMsgId ? { replyToMsgId: input.replyToMsgId } : {}),
        content: input.content,
      };

      pendingStatusRef.current.set(clientMsgId, "sending");
      setPendingMessages((prev) => [
        ...prev,
        {
          clientMsgId,
          payload,
          createdAt: new Date().toISOString(),
          status: "sending",
        },
      ]);

      await dispatchOutgoingPayload(payload);
    },
    [dispatchOutgoingPayload],
  );

  const retryPendingMessage = useCallback(
    async (clientMsgId: string) => {
      const found = pendingMessages.find((item) => item.clientMsgId === clientMsgId);
      if (!found) return;
      pendingStatusRef.current.set(clientMsgId, "sending");
      setPendingMessages((prev) =>
        prev.map((item) =>
          item.clientMsgId === clientMsgId
            ? { ...item, status: "sending", errorMessage: undefined }
            : item,
        ),
      );
      await dispatchOutgoingPayload(found.payload);
    },
    [dispatchOutgoingPayload, pendingMessages],
  );

  const discardPendingMessage = useCallback((clientMsgId: string) => {
    pendingStatusRef.current.delete(clientMsgId);
    const t = ackFallbackTimersRef.current.get(clientMsgId);
    if (t !== undefined) {
      window.clearTimeout(t);
      ackFallbackTimersRef.current.delete(clientMsgId);
    }
    setPendingMessages((prev) => prev.filter((item) => item.clientMsgId !== clientMsgId));
  }, []);

  const toggleDiscussion = useCallback(
    async (
      messageId: string,
      payload: ChangeDiscussion,
      idempotencyKey: string,
    ): Promise<Discussion> => {
      const currentAccess = accessRef.current;
      const signal = roomAbortRef.current?.signal;
      if (!currentAccess || !signal || signal.aborted || revokedLocallyRef.current) {
        throw new Error("Space access unavailable");
      }

      const capturedGen = epochGenRef.current;
      const updated = await changeDiscussion(
        spaceId,
        messageId,
        currentAccess,
        payload,
        idempotencyKey,
        signal,
      );

      if (
        capturedGen === epochGenRef.current &&
        !signal.aborted &&
        !revokedLocallyRef.current
      ) {
        const existing = messagesMapRef.current.get(messageId);
        if (existing && updated.version >= (existing.discussion?.version ?? 0)) {
          messagesMapRef.current.set(messageId, { ...existing, discussion: updated });
          setMessages(sortMessagesBySeq(messagesMapRef.current));
        }
      }

      return updated;
    },
    [spaceId],
  );

  const resolveCardForOpen = useCallback(
    async (messageId: string): Promise<CardView | null> => {
      const currentAccess = accessRef.current;
      const signal = roomAbortRef.current?.signal;
      if (!currentAccess || !signal || signal.aborted || revokedLocallyRef.current) {
        return null;
      }

      const capturedGen = epochGenRef.current;
      setCardResolvingIds((prev) => ({ ...prev, [messageId]: true }));
      try {
        const results = await resolveResourceCards(
          spaceId,
          currentAccess,
          [messageId],
          signal,
        );
        if (
          capturedGen !== epochGenRef.current ||
          signal.aborted ||
          revokedLocallyRef.current
        ) {
          return null;
        }
        const match = results.find((item) => item.messageId === messageId);
        if (!match) {
          const denied: CardView = { state: "FORBIDDEN" };
          setCardViews((prev) => ({ ...prev, [messageId]: denied }));
          return denied;
        }
        setCardViews((prev) => ({ ...prev, [messageId]: match.view }));
        return match.view;
      } catch (error) {
        if (!isAbortError(error) && capturedGen === epochGenRef.current) {
          setSyncError(chatErrorText(error));
        }
        return null;
      } finally {
        if (capturedGen === epochGenRef.current) {
          setCardResolvingIds((prev) => {
            const next = { ...prev };
            delete next[messageId];
            return next;
          });
        }
      }
    },
    [spaceId],
  );

  const refreshVisibleCards = useCallback(async () => {
    const ids = extractCardMessageIds(messagesMapRef.current.values());
    if (ids.length > 0) {
      await resolveCardsBatch(ids);
    }
  }, [resolveCardsBatch]);

  const reloadRoom = useCallback(() => {
    setReloadNonce((n) => n + 1);
  }, []);

  return {
    roomInfo,
    messages,
    pendingMessages,
    hasMoreHistory,
    loadingInitial,
    loadingOlder,
    initialError,
    olderError,
    syncError,
    wsStatus,
    isRevokedInChat,
    lastReadSeq,
    eventSeq,
    cardViews,
    cardResolvingIds,
    loadOlderHistory,
    sendMessage,
    retryPendingMessage,
    discardPendingMessage,
    toggleDiscussion,
    resolveCardForOpen,
    refreshVisibleCards,
    syncNow: () => syncEvents({ resolveAllVisibleCards: false }),
    markReadUpTo: scheduleReadAdvance,
    reloadRoom,
  };
}

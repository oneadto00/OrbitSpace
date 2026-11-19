import { randomUUID } from "node:crypto";
import { Server, type Document, type Connection } from "@hocuspocus/server";
import type { CollabAccess, CollabBatchReceipt, CollabSession, CollabStatePage } from "@orbitspace/shared-types";
import { Redis } from "ioredis";
import * as Y from "yjs";
import { hook, origin, transformRequest } from "./internalHooks.js";

interface Room { seq: number; pending: Promise<void>; }
const rooms = new WeakMap<Document, Room>();
const rate = new WeakMap<Connection, { since: number; frames: number }>();
const channel = "orbit:collab:control";
const control = new Redis({ host: process.env.ORBIT_REDIS_HOST ?? "localhost", port: Number(process.env.ORBIT_REDIS_PORT ?? 6379),
  password: process.env.ORBIT_REDIS_PASSWORD, enableOfflineQueue: false });
let listening = false;

function serial(room: Room, action: () => Promise<void>) {
  const outcome = room.pending.then(action);
  room.pending = outcome.catch(() => {});
  return outcome;
}
const sessionKey = (s: CollabSession) => ({ documentId: s.documentId, sessionId: s.sessionId });

async function recheck(connection: Connection<CollabSession>) {
  const session = connection.context;
  if (!listening || !connection.document.hasConnection(connection)) throw new Error("Collaboration unavailable");
  let access: CollabAccess;
  try {
    access = await hook<CollabAccess>("revoke-check", sessionKey(session));
  } catch (error) {
    if (error && typeof error === "object" && "status" in error && [401,403,404,410].includes(Number(error.status)))
      closeRevoked(connection);
    throw error;
  }
  if (access.membershipEpoch !== session.membershipEpoch || access.readOnly !== connection.readOnly)
    throw new Error("Authorization changed");
}

async function load(document: Document, session: CollabSession, room: Room) {
  let cursor: string | undefined, seq = 0, boundary: number | undefined;
  const state = new Y.Doc(), cursors = new Set<string>();
  try {
    do {
      const page = await hook<CollabStatePage>("load", { ...sessionKey(session), cursor });
      boundary ??= page.persistedBatchSeq;
      if (boundary !== page.persistedBatchSeq) throw new Error("Inconsistent load boundary");
      if (page.snapshot) {
        if (cursor) throw new Error("Unexpected snapshot");
        seq = page.snapshot.throughBatchSeq;
        Y.applyUpdate(state, Buffer.from(page.snapshot.update, "base64"));
      }
      for (const item of page.batches) {
        if (item.batchSeq !== ++seq) throw new Error("Missing durable update");
        Y.applyUpdate(state, Buffer.from(item.update, "base64"));
      }
      cursor = page.nextCursor ?? undefined;
      if (cursor && cursors.has(cursor)) throw new Error("Repeated cursor");
      if (cursor) cursors.add(cursor);
    } while (cursor);
    if (boundary !== seq || seq < room.seq) throw new Error("Invalid durable sequence");
    Y.applyUpdate(document, Y.encodeStateAsUpdate(state), { source: "local" });
    room.seq = seq;
  } finally { state.destroy(); }
}
function saved(document: Document, seq: number) {
  document.broadcastStateless(JSON.stringify({ type: "saved", persistedBatchSeq: seq }));
}

const server = new Server<CollabSession>({
  port: Number(process.env.PORT ?? 1234), quiet: true,
  websocketOptions: { maxPayload: 6 * 1024 * 1024, perMessageDeflate: false },
  maxUnauthenticatedQueueSize: 64 * 1024, maxUnauthenticatedQueueMessages: 16, maxPendingDocuments: 1,
  async onUpgrade({ request, socket }) {
    if (!listening || request.url !== "/collab" || request.headers.origin !== origin) {
      socket.end("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n"); throw null;
    }
    // Limit wire ingress while asynchronous access checks may be holding up
    // the per-connection message queue.
    let started = Date.now(), bytes = 0, chunks = 0;
    socket.on("data", (chunk: Buffer) => {
      if (Date.now() - started >= 1000) { started = Date.now(); bytes = 0; chunks = 0; }
      bytes += chunk.length;
      if (++chunks > 4096 || bytes > 8 * 1024 * 1024) socket.destroy();
    });
  },
  async onRequest({ request, response }) { await transformRequest(request, response); throw null; },
  async onAuthenticate({ documentName, token, connectionConfig }) {
    if (!listening || !token || token.length > 512) throw new Error("Invalid collaboration ticket");
    const session = await hook<CollabSession>("authenticate", { documentId: documentName, ticket: token });
    if (session.documentId !== documentName || !session.sessionId || !(session.membershipEpoch > 0))
      throw new Error("Invalid collaboration session");
    connectionConfig.readOnly = session.role === "VIEWER" || session.readOnly;
    return session;
  },
  async onLoadDocument({ document, context }) {
    const room: Room = { seq: 0, pending: Promise.resolve() };
    await load(document, context, room); rooms.set(document, room);
  },
  async connected({ connection }) {
    const document = connection.document;
    try {
      await recheck(connection);
      connection.sendStateless(JSON.stringify({ type: "saved", persistedBatchSeq: rooms.get(document)!.seq }));
    } catch { connection.close({ code: 4403, reason: "COLLAB_REVOKED" }); }
  },
  async beforeHandleMessage({ connection }) {
    const now = Date.now();
    const recent = rate.get(connection);
    const bucket = recent && now - recent.since < 1000 ? recent : { since: now, frames: 0 };
    rate.set(connection, bucket);
    if (++bucket.frames > 150) throw new Error("Too many messages");
    await recheck(connection);
  },
  async beforeSync({ type, payload, connection, document, context }) {
    if (type === 0 || connection.readOnly) return;
    const room = rooms.get(document)!;
    await serial(room, async () => {
      if (!document.hasConnection(connection)) throw new Error("Session closed");
      Y.decodeUpdate(payload);
      if (Y.snapshotContainsUpdate(Y.snapshot(document), payload)) return;
      const batchId = randomUUID();
      const result = await hook<CollabBatchReceipt>("store-batch", {
        ...sessionKey(context), batchId, expectedPersistedBatchSeq: room.seq,
        update: Buffer.from(payload).toString("base64"),
      }, true);
      if (result.documentId !== document.name || result.batchId !== batchId || result.persistedBatchSeq !== room.seq + 1)
        throw new Error("Invalid save receipt");
      room.seq = result.persistedBatchSeq;
      Y.applyUpdate(document, payload, { source: "connection", connection });
      saved(document, room.seq);
    }).catch(async error => {
      // A lost receipt or external edit may already have advanced the base.
      await serial(room, () => load(document, context, room)).catch(() => {});
      throw error;
    });
  },
  async onStateless({ connection, document, payload }) {
    // Hocuspocus processes frames in order per connection. Echoing a barrier
    // after its earlier updates proves that client's edits are in this boundary.
    if (/^flush:[a-zA-Z0-9-]{1,64}$/.test(payload)) {
      const room = rooms.get(document)!;
      await room.pending;
      if (document.hasConnection(connection)) connection.sendStateless(JSON.stringify({
        type: "flushed", id: payload.slice(6), persistedBatchSeq: room.seq,
      }));
    }
  },
});

function allConnections() {
  return Array.from(server.hocuspocus.documents.values()).flatMap(doc => doc.getConnections());
}
const closeRevoked = (c: Connection) => c.close({ code: 4403, reason: "COLLAB_REVOKED" });
control.on("message", (_, json: string) => {
  let event;
  try { event = JSON.parse(json); } catch { return; }
  if (event?.type === "DOCUMENT_CHANGED") {
    const doc = server.hocuspocus.documents.get(event.documentId);
    if (!doc) return;
    const room = rooms.get(doc)!;
    void serial(room, async () => {
      for (const c of doc.getConnections()) {
        try { await load(doc, c.context, room); saved(doc, room.seq); return; }
        catch { closeRevoked(c); }
      }
    });
    return;
  }
  for (const c of allConnections()) {
    const s: CollabSession = c.context;
    if ((["DOCUMENT_DELETED","DOCUMENT_PURGED"].includes(event?.type) && event.documentId === s.documentId) ||
        (event?.type === "MEMBERSHIP_REVOKED" && event.spaceId === s.spaceId &&
         event.userId === s.userId && event.membershipEpoch === s.membershipEpoch)) closeRevoked(c);
  }
});
control.on("close", () => { listening = false; allConnections().forEach(closeRevoked); });
control.on("ready", () => {
  void control.subscribe(channel).then(() => { listening = true; }).catch(() => { listening = false; });
});
// Recheck idle readers as a backstop to a missed notification.
let checking = false;
setInterval(async () => {
  if (checking) return;
  checking = true;
  try { await Promise.all(allConnections().map(c => recheck(c).catch(() => closeRevoked(c)))); }
  finally { checking = false; }
}, 5000).unref();
await server.listen();

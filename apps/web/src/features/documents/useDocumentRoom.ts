import { useEffect, useRef, useState } from "react";
import { HocuspocusProvider } from "@hocuspocus/provider";
import * as Y from "yjs";
import { bindSpaceConnection, type SpaceAccess } from "../../shared/api/access";
import { ticket } from "./documentsApi";

interface Room {
  document: Y.Doc;
  flush: () => Promise<number>;
}
interface Waiter { finish: (seq: number) => void; fail: () => void; }
export function useDocumentRoom(access: SpaceAccess, documentId: string) {
  const [room, setRoom] = useState<Room>();
  const [synced, setSynced] = useState(false), [saved, setSaved] = useState<number | null>(null);
  const [readOnly, setReadOnly] = useState(true), [error, setError] = useState("");
  const generation = useRef(0);
  const { spaceId, membershipEpoch } = access;
  useEffect(() => {
    const document = new Y.Doc(), waiters = new Map<string, Waiter>();
    let stopped = false, reconnect: ReturnType<typeof setTimeout> | undefined;
    setSynced(false); setSaved(null); setReadOnly(true); setError("");
    const provider = new HocuspocusProvider({
      url: (location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/collab",
      name: documentId, document, flushDelay: false,
      token: async () => {
        const issued = await ticket({ spaceId, membershipEpoch }, documentId);
        if (stopped || issued.membershipEpoch !== membershipEpoch || issued.documentId !== documentId) throw new Error("授权已变更");
        return issued.ticket;
      },
      onAuthenticated: ({ scope }) => { if (!stopped) { setReadOnly(scope !== "read-write"); setError(""); } },
      onAuthenticationFailed: () => revoke(),
      onClose: ({ event }) => {
        if (event.reason === "COLLAB_REVOKED") { revoke(); return; }
        disconnected();
        // A document-level reset leaves the underlying socket open. Request
        // a fresh ticket and reconcile unacknowledged local edits on reconnect.
        if (event.reason === "Reset Connection" && !stopped) {
          provider.disconnect();
          clearTimeout(reconnect);
          reconnect = setTimeout(() => { if (!stopped) void provider.connect(); }, 1000);
        }
      },
      onDisconnect: () => disconnected(),
      onSynced: () => syncState(),
      onUnsyncedChanges: () => syncState(),
      onStateless: ({ payload }) => {
        if (stopped) return;
        let data;
        try { data = JSON.parse(payload); } catch { return; }
        if (data.type === "flushed" && Number.isSafeInteger(data.persistedBatchSeq)) {
          waiters.get(data.id)?.finish(data.persistedBatchSeq);
        }
        if (data.type === "saved" && provider.synced && !provider.hasUnsyncedChanges) scheduleStatus();
      },
    });
    function disconnected() {
      if (stopped) return;
      generation.current++; setSynced(false); setSaved(null); setReadOnly(true);
      for (const waiter of waiters.values()) waiter.fail();
    }
    function flush(): Promise<number> {
      if (stopped || !provider.isAuthenticated || !provider.synced) return Promise.reject(new Error("请先连接协同服务。"));
      const id = crypto.randomUUID();
      return new Promise((resolve, reject) => {
        function cleanup() { clearTimeout(timer); waiters.delete(id); }
        const fail = () => { cleanup(); reject(new Error("无法确认已保存的编辑，请重试。")); };
        const timer = setTimeout(fail, 15000);
        waiters.set(id, { finish(seq) { cleanup(); resolve(seq); }, fail });
        provider.sendStateless("flush:" + id);
      });
    }
    let statusScheduled = false;
    function scheduleStatus() {
      if (statusScheduled) return;
      statusScheduled = true;
      queueMicrotask(() => {
        statusScheduled = false;
        if (stopped || !provider.synced || provider.hasUnsyncedChanges) return;
        const revision = generation.current;
        void flush().then(seq => {
          if (!stopped && revision === generation.current && !provider.hasUnsyncedChanges) setSaved(seq);
        }).catch(() => {});
      });
    }
    function syncState() {
      if (stopped) return;
      generation.current++;
      const complete = provider.synced && !provider.hasUnsyncedChanges;
      setSynced(complete);
      if (complete) scheduleStatus(); else setSaved(null);
    }
    function stop() {
      if (stopped) return;
      stopped = true; clearTimeout(reconnect);
      for (const waiter of waiters.values()) waiter.fail();
      provider.destroy(); document.destroy();
    }
    function revoke() {
      if (stopped) return;
      stop(); setRoom(undefined); setSaved(null); setSynced(false); setReadOnly(true);
      setError("协同会话授权已失效，请重新进入。");
    }
    setRoom({ document, flush });
    const unbind = bindSpaceConnection({ spaceId, membershipEpoch }, revoke);
    return () => { unbind(); stop(); };
  }, [spaceId, membershipEpoch, documentId]);
  return { room, synced, saved, readOnly, error };
}

import { useEffect, useState } from "react";
import type { AiSourceRef, AiSummaryStatus, P3JobReceipt } from "@orbitspace/shared-types";
import type { SpaceAccess } from "../../shared/api/access";
import { Button, Modal } from "../../shared/ui/UiComponents";
import { documentError, post, read, segment, spacePath } from "./documentsApi";

export interface SummarySourceOption { source: AiSourceRef; title: string; }
export interface SummaryOptions {
  sources: SummarySourceOption[];
  sourceHref: (source: AiSourceRef) => string;
}
interface Props extends SummaryOptions {
  access: SpaceAccess; documentId: string; flush: () => Promise<number>; onClose: () => void; readOnly: boolean;
}
export function AiSummaryDrawer({ access, documentId, flush, onClose, readOnly, sources, sourceHref }: Props) {
  const [selected, setSelected] = useState(new Set<number>()), [job, setJob] = useState("");
  const [status, setStatus] = useState<AiSummaryStatus>(), [error, setError] = useState("");
  const [busy, setBusy] = useState(false), [committed, setCommitted] = useState(false);
  const [base, setBase] = useState<number>();
  const path = spacePath(access) + "/ai/summaries";
  const { spaceId, membershipEpoch } = access;
  useEffect(() => {
    if (!job) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const preview = await read<AiSummaryStatus>({ spaceId, membershipEpoch }, path + "/" + segment(job), controller.signal);
        setStatus(preview);
        if (preview.status === "SUCCEEDED") setBase(await flush());
        else if (["RUNNING","PENDING","RETRY_WAIT"].includes(preview.status)) timer = setTimeout(poll, 1500);
      } catch (e) { if (!controller.signal.aborted) setError(documentError(e)); }
    }
    void poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [job, spaceId, membershipEpoch, path, flush]);
  async function request() {
    setBusy(true); setError(""); setCommitted(false); setBase(undefined); setStatus(undefined);
    try {
      const result = await post<P3JobReceipt>(access, path, { sources: sources.filter((_, i) => selected.has(i)).map(s => s.source) });
      setJob(result.jobId);
    } catch (e) { setError(documentError(e)); }
    finally { setBusy(false); }
  }
  async function commit() {
    if (status?.status !== "SUCCEEDED" || base === undefined) return;
    setBusy(true); setError("");
    try {
      if (await flush() !== base) throw new Error("文档已修改，请重新预览后再确认。");
      await post(access, path + "/" + segment(job) + "/commit", { resultVersionId: status.resultVersionId,
        documentId, expectedPersistedBatchSeq: base, confirmed: true });
      setCommitted(true);
    } catch (e) { setError(documentError(e)); }
    finally { setBusy(false); }
  }
  return <Modal title="AI 摘要" onClose={onClose}>
    <fieldset disabled={busy || !!job}><legend>选择提供给 AI 的资料版本</legend>
      {sources.map(({ source, title }, i) => <label key={JSON.stringify(source)}><input type="checkbox" checked={selected.has(i)}
        onChange={() => setSelected(previous => { const next = new Set(previous); if (next.has(i)) next.delete(i); else next.add(i); return next; })}/>{title}</label>)}
    </fieldset>
    {!job && <Button disabled={busy || !selected.size} onClick={request}>生成摘要</Button>}
    {status?.status === "SUCCEEDED" ? <><h3>写入前预览</h3><ol>{status.points.map((point, i) =>
      <li key={i}><p>{point.text}</p>{point.sources.map(source => <a key={JSON.stringify(source)} className="document-citation" href={sourceHref(source)}>{sources.find(s => s.source.kind === source.kind && s.source.resourceId === source.resourceId &&
          s.source.versionId === source.versionId)?.title ?? source.resourceId} · {source.versionId}</a>)}</li>)}</ol>
      <Button disabled={busy || readOnly || committed || base === undefined} onClick={commit}>{committed ? "已写入" : "确认将以上摘要写入当前文档"}</Button>
      {!!error && <Button onClick={async () => {try { setBase(await flush()); setError(""); } catch (e) {setError(documentError(e));} }}>刷新写入边界</Button>}</>
      : job && <p role="status">{status && "reason" in status ? status.reason : "正在生成摘要…"}</p>}
    {error && <p role="alert">{error}</p>}
  </Modal>;
}

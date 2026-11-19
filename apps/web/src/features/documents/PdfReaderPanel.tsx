import { useEffect, useRef, useState, type PointerEvent } from "react";
import { AnnotationMode, getDocument, GlobalWorkerOptions, type PDFDocumentProxy } from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import type { PdfComment, PdfQuoteAnchor, PdfSelectionRect } from "@orbitspace/shared-types";
import { bindSpaceConnection, type SpaceAccess } from "../../shared/api/access";
import { apiRaw } from "../../shared/api/httpClient";
import { Button } from "../../shared/ui/UiComponents";
import { createDownloadSession } from "../files/filesApi";
import { documentError, post, read, segment, spacePath, type ForwardQuote } from "./documentsApi";
import "./documents.css";

GlobalWorkerOptions.workerSrc = workerUrl;
interface Span { text: string; x: number; y: number; width: number; height: number }
interface PageIndex { page: number; spans: Span[] }
interface PdfIndex { status: string; pages?: PageIndex[] }
export interface PdfReaderProps {
  access: SpaceAccess; fileId: string; fileVersionId: string; title: string; readOnly: boolean;
  commentId?: string; onForward?: ForwardQuote;
}
const spanRect = (s: Span): PdfSelectionRect => ({ left: s.x, top: s.y, right: s.x + s.width, bottom: s.y + s.height });
function bounded(value: number) { return Math.min(1, Math.max(0, value)); }

async function pdfBytes(props: PdfReaderProps, signal: AbortSignal) {
  const session = await createDownloadSession({ grantType: "SPACE_FILE", grantId: props.access.spaceId, fileId: props.fileId },
    crypto.randomUUID(), props.access, signal);
  if (session.sha256 !== props.fileVersionId || session.fileId !== props.fileId || session.grantId !== props.access.spaceId ||
      session.grantType !== "SPACE_FILE" || session.sizeBytes > 32 * 1024 * 1024) throw new Error("PDF 版本或大小不符合预览条件。");
  const path = "/download-sessions/" + segment(session.downloadSessionId) + "/content";
  const response = await apiRaw(path, { signal }, props.access);
  const reader = response.body?.getReader();
  if (!reader) throw new Error("无法读取 PDF 内容。");
  const data = new Uint8Array(session.sizeBytes);
  let offset = 0;
  try {
    for (;;) {
      const {done, value} = await reader.read(); if (done) break;
      if (offset + value.length > data.length) throw new Error("PDF 下载大小异常。");
      data.set(value, offset); offset += value.length;
    }
  } finally { await reader.cancel(); }
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
  if (offset !== data.length || Array.from(hash, b => b.toString(16).padStart(2,"0")).join("") !== session.sha256)
    throw new Error("PDF 版本校验失败。");
  return data;
}

function PdfSession(props: PdfReaderProps) {
  const { access, fileId, fileVersionId, title, readOnly, commentId, onForward } = props;
  const { spaceId, membershipEpoch } = access;
  const base = spacePath(access) + "/files/" + segment(fileId) + "/versions/" + segment(fileVersionId);
  const [pdf, setPdf] = useState<PDFDocumentProxy>(), [pages, setPages] = useState<PageIndex[]>([]);
  const [page, setPage] = useState(1), [readyPage, setReadyPage] = useState(0);
  const [status, setStatus] = useState("正在检查 PDF 索引…"), [error, setError] = useState("");
  const [anchor, setAnchor] = useState<PdfQuoteAnchor>(), [comment, setComment] = useState<PdfComment>();
  const [body, setBody] = useState(""), [busy, setBusy] = useState(false), [retry, setRetry] = useState(0);
  const [mode, setMode] = useState<"text"|"rect">("text"), [revoked, setRevoked] = useState(false);
  const canvas = useRef<HTMLCanvasElement>(null);
  const drag = useRef<{ x: number; y: number } | null>(null);

  useEffect(() => {
    const controller = new AbortController(), current = { spaceId, membershipEpoch };
    let timer: ReturnType<typeof setTimeout>, task: ReturnType<typeof getDocument> | undefined;
    let started = false;
    async function load() {
      try {
        let index: PdfIndex;
        try { index = await read<PdfIndex>(current, base + "/pdf-index", controller.signal); }
        catch (e) {
          if (started || !(typeof e === "object" && e && "status" in e && e.status === 404)) throw e;
          index = { status: "MISSING" };
        }
        if (index.status !== "SUCCEEDED") {
          if (index.status === "FAILED" || index.status === "CANCELLED") throw new Error("此版本未通过 PDF 解析，无法预览。");
          if (!started && (index.status === "MISSING" || !["RUNNING","RETRY_WAIT","PENDING"].includes(index.status))) {
            await post(current, base + "/pdf-index"); started = true;
          }
          setStatus("正在安全解析 PDF…"); timer = setTimeout(load,1500); return;
        }
        if (!index.pages?.length) throw new Error("PDF 缺少可用分页索引。");
        setPages(index.pages); setStatus("正在读取已授权版本…");
        const data = await pdfBytes({ ...props, access: current }, controller.signal);
        controller.signal.throwIfAborted();
        task = getDocument({ data, stopAtErrors: true, enableXfa: false, useSystemFonts: false,
          maxImageSize: 8_000_000, canvasMaxAreaInBytes: 32_000_000, disableFontFace: true });
        const opened = await task.promise;
        controller.signal.throwIfAborted();
        if (opened.numPages !== index.pages.length) throw new Error("PDF 页数和解析索引不一致。");
        setPdf(opened); setStatus("");
        if (commentId) {
          const found = await read<PdfComment>(current, base + "/pdf-comments/" + segment(commentId), controller.signal);
          setComment(found); setPage(found.anchor.pageNumber); setAnchor(found.anchor);
        }
      } catch (e) { if (!controller.signal.aborted) { setError(documentError(e)); setStatus(""); } }
    }
    const close = () => { controller.abort(); clearTimeout(timer); void task?.destroy(); };
    const unbind = bindSpaceConnection(current, () => { close(); setRevoked(true); });
    if (!controller.signal.aborted) void load();
    return () => { unbind(); close(); };
  }, [base, fileId, fileVersionId, spaceId, membershipEpoch, retry, commentId]);

  useEffect(() => {
    if (!pdf || !canvas.current) return;
    let stopped = false, render: ReturnType<Awaited<ReturnType<typeof pdf.getPage>>["render"]> | undefined;
    const target = canvas.current;
    setReadyPage(0);
    void pdf.getPage(page).then(async pageProxy => {
      if (stopped) return;
      const natural = pageProxy.getViewport({ scale: 1, rotation: 0 });
      const scale = Math.min(2, 1200 / natural.width, 1800 / natural.height);
      const viewport = pageProxy.getViewport({ scale, rotation: 0 });
      target.width = Math.ceil(viewport.width); target.height = Math.ceil(viewport.height);
      render = pageProxy.render({ canvas: target, viewport, annotationMode: AnnotationMode.DISABLE });
      await render.promise;
      if (!stopped) setReadyPage(page);
    }).catch(e => { if (!stopped) setError(documentError(e)); });
    return () => { stopped = true; render?.cancel(); };
  }, [pdf, page]);

  function point(e: PointerEvent<HTMLDivElement>) {
    const r = e.currentTarget.getBoundingClientRect();
    return { x: bounded((e.clientX - r.left) / r.width), y: bounded((e.clientY - r.top) / r.height) };
  }
  function select(e: PointerEvent<HTMLDivElement>) {
    const first = drag.current; drag.current = null;
    if (!first || readyPage !== page) return;
    const end = point(e);
    const rectangle = { left: Math.min(first.x,end.x), right: Math.max(first.x,end.x), top: Math.min(first.y,end.y), bottom: Math.max(first.y,end.y) };
    const spans = pages.find(p => p.page === page)?.spans ?? [];
    const intersect = (s: Span) => s.x <= rectangle.right && s.x + s.width >= rectangle.left && s.y <= rectangle.bottom && s.y + s.height >= rectangle.top;
    const matched = spans.filter(intersect);
    const selected = mode === "text" && matched.length ? spans.slice(spans.indexOf(matched[0]!), spans.indexOf(matched[matched.length-1]!) + 1) : matched;
    const rectangles = mode === "rect" ? [rectangle] : selected.map(spanRect);
    const text = selected.map(s => s.text).join(" ").trim();
    if (!text || text.length > 4096 || rectangles.length > 128 || rectangles.some(r => r.left >= r.right || r.top >= r.bottom)) {
      setStatus("请拖选包含文字的较小区域。"); setAnchor(undefined); return;
    }
    setComment(undefined); setStatus(""); setAnchor({ fileId, fileVersionId, pageNumber: page, quotedText: text, rectangles });
  }
  async function submit(forward: boolean) {
    if (!anchor || busy) return;
    setBusy(true); setError("");
    try {
      const result = comment ?? await post<PdfComment>(access, base + "/pdf-comments", { anchor, body: body.trim() || anchor.quotedText });
      setComment(result); setStatus("批注已保存");
      if (forward) await onForward?.({ kind: "PDF_REF", ...result });
    } catch (e) { setError(documentError(e)); } finally { setBusy(false); }
  }
  if (revoked) return <p role="alert">空间访问权限已撤销。</p>;
  return <section className="document-pane" aria-label="PDF 精读"><header><h2>{title}</h2><p>只读 PDF · {pdf ? pdf.numPages + " 页" : "载入中"}</p></header>
    {pdf && <><div className="document-actions"><label>页码<input type="number" min={1} max={pdf.numPages} value={page}
      onChange={e => {setPage(Math.min(pdf.numPages, Math.max(1, Math.floor(+e.target.value) || 1))); setAnchor(undefined); setComment(undefined);}}/></label>
      <Button aria-pressed={mode === "text"} onClick={() => setMode("text")}>文本选区</Button>
      <Button aria-pressed={mode === "rect"} onClick={() => setMode("rect")}>矩形选区</Button></div>
      <div className="pdf-sheet" onPointerDown={e => { drag.current = point(e); e.currentTarget.setPointerCapture(e.pointerId); }}
        onPointerCancel={() => { drag.current = null; }} onPointerUp={select}>
        <canvas ref={canvas} aria-label={"PDF 第 " + page + " 页"}/>
        {anchor?.pageNumber === readyPage && anchor.rectangles.map((r,i) => <span key={i} className="pdf-mark"
          style={{left:r.left*100+"%",top:r.top*100+"%",width:(r.right-r.left)*100+"%",height:(r.bottom-r.top)*100+"%"}}/>)}
      </div>
      <details><summary>本页文本</summary><p>{pages.find(p => p.page === page)?.spans.map(s => s.text).join(" ")}</p></details></>}
    {anchor && <aside><blockquote>{anchor.quotedText}</blockquote>{comment && <p>{comment.body}</p>}
      {!readOnly && !comment && <><label>批注<input value={body} maxLength={8000} onChange={e => setBody(e.target.value)}/></label>
      <Button disabled={busy || !body.trim()} onClick={() => submit(false)}>保存批注</Button></>}
      {onForward && (!readOnly || comment) && <Button disabled={busy} onClick={() => submit(true)}>引用到群聊</Button>}</aside>}
    {status && <p role="status">{status}</p>}{error && <p role="alert">{error} <Button onClick={() => {
      setError(""); setPdf(undefined); setAnchor(undefined); setComment(undefined); setRetry(n => n+1);
    }}>重试</Button></p>}
  </section>;
}
export function PdfReaderPanel(props: PdfReaderProps) {
  return <PdfSession key={[props.access.spaceId, props.access.membershipEpoch, props.fileId, props.fileVersionId, props.commentId].join("/")} {...props}/>;
}

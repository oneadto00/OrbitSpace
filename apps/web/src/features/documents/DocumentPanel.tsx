import { useEffect, useRef, useState } from "react";
import { EditorContent, useEditor } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import Collaboration from "@tiptap/extension-collaboration";
import * as Y from "yjs";
import type { DocumentComment, DocQuoteAnchor, DocumentRestorePreview, DocumentVersion, DocumentVersionContent, DocumentVersionPage } from "@orbitspace/shared-types";
import { type SpaceAccess } from "../../shared/api/access";
import { Button, Modal } from "../../shared/ui/UiComponents";
import { AiSummaryDrawer, type SummaryOptions } from "./AiSummaryDrawer";
import { documentError, documentPath, post, read, segment, type ForwardQuote } from "./documentsApi";
import { bytes, locate, selection } from "./documentSelection";
import { useDocumentRoom } from "./useDocumentRoom";
import "./documents.css";

export interface DocumentPanelProps {
  access: SpaceAccess; documentId: string; title: string;
  entry?: { commentId: string; view: "CURRENT" | "QUOTED_VERSION" };
  onForward?: ForwardQuote; summary?: SummaryOptions;
}

function useRichEditor(document: Y.Doc, readOnly: boolean) {
  const editor = useEditor({
    extensions: [StarterKit.configure({ undoRedo: false, link: { openOnClick: false } }),
      Collaboration.configure({ document, field: "default" })],
    editable: !readOnly, editorProps: { attributes: { "aria-label": "文档正文" } },
  }, [document]);
  useEffect(() => { editor?.setEditable(!readOnly); }, [editor, readOnly]);
  return editor;
}

function FrozenView({ content, anchor }: { content: DocumentVersionContent; anchor?: DocQuoteAnchor }) {
  const [document] = useState(() => {
    const state = new Y.Doc(); Y.applyUpdate(state, bytes(content.yjsUpdate)); return state;
  });
  const editor = useRichEditor(document, true);
  const [status, setStatus] = useState("");
  useEffect(() => {
    if (!editor || !anchor) return;
    const frame = requestAnimationFrame(() => setStatus(locate(editor, document, anchor)));
    return () => cancelAnimationFrame(frame);
  }, [editor, document, anchor]);
  return <><p>只读版本 · 已保存批次 {content.version.persistedBatchSeq}</p><p role="status">{status}</p><EditorContent editor={editor}/></>;
}

function ConnectedDocument({ access, documentId, title, entry, onForward, summary, state }:
    DocumentPanelProps & { state: ReturnType<typeof useDocumentRoom> & { room: NonNullable<ReturnType<typeof useDocumentRoom>["room"]> } }) {
  const { room, synced, saved, readOnly } = state;
  const editor = useRichEditor(room.document, readOnly);
  const path = documentPath(access, documentId);
  const [body, setBody] = useState(""), [notice, setNotice] = useState(""), [busy, setBusy] = useState(false);
  const [comment, setComment] = useState<DocumentComment>(), [versions, setVersions] = useState<DocumentVersionPage>();
  const [frozen, setFrozen] = useState<DocumentVersionContent>(), [preview, setPreview] = useState<DocumentRestorePreview>();
  const [aiOpen, setAiOpen] = useState(false);
  const { spaceId, membershipEpoch } = access;
  const resolvedEntry = useRef("");
  useEffect(() => {
    if (!entry || !editor || !synced) return;
    const key = JSON.stringify([entry.commentId, entry.view]);
    if (resolvedEntry.current === key) return;
    const controller = new AbortController();
    const current = { spaceId, membershipEpoch };
    void read<DocumentComment>(current, path + "/comments/" + segment(entry.commentId) + "?view=" + entry.view, controller.signal)
      .then(async result => {
        setComment(result);
        if (entry.view === "CURRENT") setNotice(locate(editor, room.document, result.anchor));
        else setFrozen(await read<DocumentVersionContent>(current, path + "/versions/" + segment(result.anchor.persistedVersionId), controller.signal));
        resolvedEntry.current = key;
      }).catch(e => { if (!controller.signal.aborted) setNotice(documentError(e)); });
    return () => controller.abort();
  }, [entry?.commentId, entry?.view, editor, path, room, spaceId, membershipEpoch, synced]);
  async function act(fn: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setNotice("");
    try { await fn(); } catch (e) { setNotice(documentError(e)); } finally { setBusy(false); }
  }
  async function version() { return post<DocumentVersion>(access, path + "/versions", { expectedPersistedBatchSeq: await room.flush() }); }
  async function annotate(forward: boolean) {
    const selected = selection(editor!);
    const anchor = await post<DocQuoteAnchor>(access, path + "/quote-versions", { persistedBatchSeq: await room.flush(), selection: selected });
    const result = await post<DocumentComment>(access, path + "/comments", { persistedVersionId: anchor.persistedVersionId,
      selection: selected, body: body.trim() || selected.quotedText });
    setComment(result); setBody(""); setNotice("批注已保存");
    if (forward) await onForward?.({ kind: "DOC_REF", ...result });
  }
  async function exportMarkdown(source: DocumentVersion) {
    const data = await read<{ markdown: string }>(access, path + "/versions/" + segment(source.persistedVersionId) + "/markdown");
    const url = URL.createObjectURL(new Blob([data.markdown], { type: "text/markdown;charset=utf-8" }));
    const a = document.createElement("a"); a.href = url; a.download = title + ".md"; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <section className="document-pane" aria-label="协作文档">
    <header><h2>{title}</h2><div role="status">{synced ? "已同步" : "同步中"} · {saved === null ? "等待保存" : "已保存 · 批次 " + saved}{readOnly && " · 只读"}</div></header>
    <div className="document-actions">
      <Button disabled={busy} onClick={() => act(async () => setVersions(await read(access, path + "/versions")))}>历史版本</Button>
      <Button disabled={busy || readOnly} onClick={() => act(async () => { await version(); setNotice("已创建版本"); })}>保存版本</Button>
      {!readOnly && <Button disabled={busy} onClick={() => act(async () => exportMarkdown(await version()))}>导出 Markdown</Button>}
      {!readOnly && <label>导入 Markdown<input aria-label="导入 Markdown" type="file" accept=".md,.markdown,text/markdown" disabled={busy}
        onChange={e => { const file = e.target.files?.[0]; e.target.value = "";
          if (file) void act(async () => {
            if (file.size > 4194304) throw new Error("文件超过 4 MiB");
            await post(access, path + "/markdown", { markdown: await file.text(), expectedPersistedBatchSeq: await room.flush() }); setNotice("导入已保存");
          }); }}/></label>}
      {summary && <Button onClick={() => setAiOpen(true)}>AI 摘要</Button>}
    </div><small>支持 Markdown 快捷输入。导入和导出时，部分富文本格式可能变化。</small>
    <EditorContent editor={editor}/>
    {!readOnly && <div className="document-actions"><label>选区批注<input value={body} maxLength={8000} onChange={e => setBody(e.target.value)}/></label>
      <Button disabled={busy || !editor || !body.trim()} onClick={() => act(() => annotate(false))}>保存批注</Button>
      {onForward && <Button disabled={busy || !editor} onClick={() => act(() => annotate(true))}>引用选区到群聊</Button>}</div>}
    {comment && <aside><blockquote>{comment.anchor.quotedText}</blockquote><p>{comment.body}</p>
      <Button disabled={busy} onClick={() => act(async () => {
        const current = await read<DocumentComment>(access, path + "/comments/" + segment(comment.commentId) + "?view=CURRENT");
        setNotice(locate(editor!, room.document, current.anchor));
      })}>查看当前位置</Button>
      <Button disabled={busy} onClick={() => act(async () => setFrozen(await read(access, path + "/versions/" + segment(comment.anchor.persistedVersionId))))}>查看引用时内容</Button>
      {onForward && <Button disabled={busy} onClick={() => act(() => onForward({ kind: "DOC_REF", ...comment }))}>引用此批注到群聊</Button>}</aside>}
    {versions && <Modal title="历史版本" onClose={() => setVersions(undefined)}>
      {!versions.versions.length && <p>尚无历史版本</p>}
      {versions.versions.map(v => <div className="document-actions" key={v.persistedVersionId}><span>批次 {v.persistedBatchSeq} · {new Date(v.createdAt).toLocaleString()}</span>
        <Button disabled={busy} onClick={() => act(async () => {setFrozen(await read(access, path + "/versions/" + segment(v.persistedVersionId))); setVersions(undefined);})}>只读查看</Button>
        <Button disabled={busy} onClick={() => act(() => exportMarkdown(v))}>导出此版本 Markdown</Button>
        {!readOnly && <Button disabled={busy} onClick={() => act(async () => {
          await room.flush(); setPreview(await post(access, path + "/versions/" + segment(v.persistedVersionId) + "/restore-preview")); setVersions(undefined);
        })}>预览恢复差异</Button>}</div>)}
      {versions.nextCursor && <Button disabled={busy} onClick={() => act(async () => {
        const next = await read<DocumentVersionPage>(access, path + "/versions?cursor=" + segment(versions.nextCursor!));
        setVersions({ ...next, versions: [...versions.versions, ...next.versions] });
      })}>加载更多</Button>}</Modal>}
    {preview && <Modal title="恢复历史版本" onClose={() => setPreview(undefined)}><pre>{preview.diff}</pre>
      <p>确认后将备份当前版本，再将预览中的变化作为一次新编辑应用。在线协作者会看到此次变更。</p>
      <Button disabled={busy || readOnly} onClick={() => act(async () => {
        if (await room.flush() !== preview.basePersistedBatchSeq) throw new Error("文档已变化，请重新预览差异。");
        await post(access, path + "/versions/" + segment(preview.targetVersionId) + "/restore", {
          previewId: preview.previewId, confirmed: true, expectedPersistedBatchSeq: preview.basePersistedBatchSeq });
        setPreview(undefined); setNotice("恢复已保存，恢复前版本已备份。");
      })}>确认备份并应用</Button>{notice && <p role="alert">{notice}</p>}</Modal>}
    {frozen && <Modal title="不可变历史版本" onClose={() => setFrozen(undefined)}>
      <FrozenView key={frozen.version.persistedVersionId} content={frozen} anchor={comment?.anchor.persistedVersionId === frozen.version.persistedVersionId ? comment.anchor : undefined}/></Modal>}
    {aiOpen && summary && <AiSummaryDrawer {...summary} access={access} documentId={documentId} readOnly={readOnly} flush={room.flush} onClose={() => setAiOpen(false)}/>}
    {notice && <p role="status">{notice}</p>}
  </section>;
}
function SessionPanel(props: DocumentPanelProps) {
  const state = useDocumentRoom(props.access, props.documentId);
  if (!state.room) return <p role="status">{state.error || "正在连接协同服务…"}</p>;
  return <ConnectedDocument {...props} state={{ ...state, room: state.room }}/>;
}
export function DocumentPanel(props: DocumentPanelProps) {
  return <SessionPanel key={[props.access.spaceId, props.access.membershipEpoch, props.documentId].join("/")} {...props}/>;
}

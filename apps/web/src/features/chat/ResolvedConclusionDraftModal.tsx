import { useState, type FormEvent } from "react";
import type { DocumentInfo } from "@orbitspace/shared-types";
import { AlertCircle, FileCheck2, ListTodo } from "lucide-react";
import { queryClient, spaceKey, type SpaceAccess } from "../../shared/api/access";
import { Button, Field, Modal } from "../../shared/ui/UiComponents";
import {
  createDocumentVersion,
  createSpaceDocument,
  exportDocumentVersionMarkdown,
  featureErrorText,
  formatDateTime,
  getDocumentSavedBoundary,
  importDocumentMarkdown,
  makeIdempotencyKey,
} from "../space/spaceApi";

export interface ResolvedDiscussionDraftInput {
  rootSeq: number;
  rootAuthor: string;
  rootCreatedAt: string;
  rootSummary: string;
  conclusionAuthor?: string;
  conclusionSummary?: string;
  replyCount: number;
}

export interface ResolvedConclusionDraftModalProps {
  spaceId: string;
  access: SpaceAccess;
  documents: DocumentInfo[];
  draftInput: ResolvedDiscussionDraftInput;
  onClose: () => void;
  onCommitted: (documentId: string, title: string) => void;
}

function buildInitialTodoMarkdown(input: ResolvedDiscussionDraftInput): string {
  const lines: string[] = [
    `## 讨论结论待办（主群聊 #${input.rootSeq}）`,
    "",
  ];
  const primaryTodo = input.conclusionSummary?.trim() || input.rootSummary.trim();
  lines.push(`- [ ] ${primaryTodo}`);
  lines.push("");
  lines.push(`> 讨论主题：${input.rootSummary}（${input.rootAuthor} · ${formatDateTime(input.rootCreatedAt)}）`);
  if (input.conclusionSummary) {
    lines.push(
      `> 选定结论：${input.conclusionSummary}${input.conclusionAuthor ? `（${input.conclusionAuthor}）` : ""}`,
    );
  }
  if (input.replyCount > 0) {
    lines.push(`> 关联回复数：${input.replyCount} 条（状态：RESOLVED）`);
  }
  return lines.join("\n");
}

export function ResolvedConclusionDraftModal({
  spaceId,
  access,
  documents,
  draftInput,
  onClose,
  onCommitted,
}: ResolvedConclusionDraftModalProps) {
  const [targetMode, setTargetMode] = useState<"existing" | "new">(
    documents.length > 0 ? "existing" : "new",
  );
  const [selectedDocumentId, setSelectedDocumentId] = useState<string>(
    documents[0]?.documentId ?? "",
  );
  const [newDocumentTitle, setNewDocumentTitle] = useState<string>(
    `讨论结论待办 #${draftInput.rootSeq}`,
  );
  const [draftMarkdown, setDraftMarkdown] = useState<string>(() =>
    buildInitialTodoMarkdown(draftInput),
  );
  const [confirmed, setConfirmed] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (submitting || !confirmed) return;

    const trimmedDraft = draftMarkdown.trim();
    if (!trimmedDraft) {
      setError("待办草稿内容不能为空。");
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      if (targetMode === "new" || !selectedDocumentId) {
        const title = newDocumentTitle.trim();
        if (!title) {
          setError("请输入新建协作文档的标题。");
          setSubmitting(false);
          return;
        }
        const created = await createSpaceDocument(
          spaceId,
          title,
          access,
          makeIdempotencyKey(),
        );
        await importDocumentMarkdown(
          spaceId,
          created.documentId,
          {
            markdown: trimmedDraft,
            expectedPersistedBatchSeq: created.persistedBatchSeq,
          },
          access,
          makeIdempotencyKey(),
        );
        void queryClient.invalidateQueries({
          queryKey: [...spaceKey(access), "documents"],
        });
        onCommitted(created.documentId, created.title);
        return;
      }

      const targetDoc = documents.find((d) => d.documentId === selectedDocumentId);
      if (!targetDoc) {
        setError("所选目标协作文档不存在，请重新选择。");
        setSubmitting(false);
        return;
      }

      const boundary = await getDocumentSavedBoundary(spaceId, targetDoc.documentId, access);
      let combinedMarkdown = trimmedDraft;
      if (boundary.persistedBatchSeq > 0) {
        const frozenVersion = await createDocumentVersion(
          spaceId,
          targetDoc.documentId,
          boundary.persistedBatchSeq,
          access,
          makeIdempotencyKey(),
        );
        const exported = await exportDocumentVersionMarkdown(
          spaceId,
          targetDoc.documentId,
          frozenVersion.persistedVersionId,
          access,
        );
        if (exported.markdown.trim()) {
          combinedMarkdown = `${exported.markdown.trimEnd()}\n\n${trimmedDraft}`;
        }
      }

      await importDocumentMarkdown(
        spaceId,
        targetDoc.documentId,
        {
          markdown: combinedMarkdown,
          expectedPersistedBatchSeq: boundary.persistedBatchSeq,
        },
        access,
        makeIdempotencyKey(),
      );
      void queryClient.invalidateQueries({
        queryKey: [...spaceKey(access), "documents"],
      });
      onCommitted(targetDoc.documentId, targetDoc.title);
    } catch (err) {
      setError(featureErrorText(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal title="整理讨论结论至协作文档待办草稿" onClose={onClose}>
      <form className="chat-todo-draft-form" onSubmit={handleSubmit}>
        <p className="muted">
          将已标记 <strong>RESOLVED</strong> 的讨论结论整理为待办清单并写入协作文档。写入前请核对目标文档与草稿内容。
        </p>

        <div className="space-create-tabs" role="tablist" aria-label="写入方式">
          <button
            type="button"
            role="tab"
            aria-selected={targetMode === "existing"}
            className={`space-tab-btn ${targetMode === "existing" ? "active" : ""}`}
            disabled={documents.length === 0 || submitting}
            onClick={() => setTargetMode("existing")}
          >
            追加到已有文档 ({documents.length})
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={targetMode === "new"}
            className={`space-tab-btn ${targetMode === "new" ? "active" : ""}`}
            disabled={submitting}
            onClick={() => setTargetMode("new")}
          >
            新建待办协作文档
          </button>
        </div>

        {targetMode === "existing" && documents.length > 0 ? (
          <label className="field">
            选择目标协作文档（将保留已保存正文并在末尾追加待办）
            <select
              value={selectedDocumentId}
              disabled={submitting}
              onChange={(e) => setSelectedDocumentId(e.target.value)}
            >
              {documents.map((doc) => (
                <option key={doc.documentId} value={doc.documentId}>
                  {doc.title}（已保存批次 {doc.persistedBatchSeq}）
                </option>
              ))}
            </select>
          </label>
        ) : (
          <Field
            label="新建协作文档标题"
            name="todoDraftDocTitle"
            required
            maxLength={255}
            value={newDocumentTitle}
            disabled={submitting}
            onChange={(e) => setNewDocumentTitle(e.target.value)}
          />
        )}

        <label className="field">
          待办草稿内容预览（可编辑，Markdown 格式）
          <textarea
            rows={7}
            maxLength={20000}
            value={draftMarkdown}
            disabled={submitting}
            onChange={(e) => setDraftMarkdown(e.target.value)}
          />
        </label>

        <label className="chat-todo-confirm-check">
          <input
            type="checkbox"
            checked={confirmed}
            disabled={submitting}
            onChange={(e) => setConfirmed(e.target.checked)}
          />
          <span>
            我已预览并确认以上待办草稿内容，同意作为新编辑写入目标协作文档。
          </span>
        </label>

        {error && (
          <div className="alert" role="alert">
            <AlertCircle size={15} />
            <span>{error}</span>
          </div>
        )}

        <div className="actions">
          <Button
            className="secondary workbench-btn-xs"
            disabled={submitting}
            onClick={onClose}
          >
            取消
          </Button>
          <Button
            type="submit"
            className="primary workbench-btn-xs"
            disabled={submitting || !confirmed || !draftMarkdown.trim()}
          >
            {submitting ? (
              <>
                <ListTodo size={13} /> 正在写入文档…
              </>
            ) : (
              <>
                <FileCheck2 size={13} /> 确认带入协作文档
              </>
            )}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

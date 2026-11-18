import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type {
  CodeCommentDto,
  CodeCommentSide,
  DiscussionStatus,
  Member,
  TwoPointDiffDto,
  TwoPointDiffFileEntry,
} from "@orbitspace/shared-types";
import {
  CheckCircle2,
  CircleDot,
  Forward,
  Link2,
  MessageSquarePlus,
  RefreshCw,
  Reply,
  Send,
} from "lucide-react";
import { spaceKey, type SpaceAccess } from "../../../shared/api/access";
import { Button, Loading, ResourceStateCard } from "../../../shared/ui/UiComponents";
import {
  changeCodeCommentDiscussion,
  createCodeComment,
  forwardCodeCommentToChat,
  gitCanvasErrorText,
  makeIdempotencyKey,
  replyCodeComment,
  resolveResourceCardState,
} from "../gitCanvasApi";
import {
  CODE_COMMENT_SIDE_LABELS,
  DISCUSSION_STATUS_LABELS,
  formatDateTime,
  isEntryComparable,
  shortOid,
} from "../labels";

export interface CommentComposerDraft {
  filePath: string;
  side: CodeCommentSide;
  lineNumber: number;
  allowSideToggle?: boolean;
  oldLineNumber?: number | null;
  newLineNumber?: number | null;
}

export interface CodeCommentPanelProps {
  spaceId: string;
  projectId: string;
  access: SpaceAccess;
  canUploadOrManage: boolean;
  diff: TwoPointDiffDto;
  selectedFile: TwoPointDiffFileEntry | null;
  comments: CodeCommentDto[];
  commentsLoading: boolean;
  commentsError: unknown;
  membersById: Map<string, Member>;
  composerDraft: CommentComposerDraft | null;
  onChangeComposerDraft: (draft: CommentComposerDraft | null) => void;
  focusedCommentId: string | null;
  highlightNonce?: number;
  onSelectCommentAnchor: (comment: CodeCommentDto) => void;
  onForwardCodeComment?: (comment: CodeCommentDto) => void;
  onRefreshComments: () => void;
}

interface ScopedIdempotencyState {
  signature: string;
  key: string;
}

function resolveScopedIdemKey(
  store: Map<string, ScopedIdempotencyState>,
  bucket: string,
  signature: string,
): string {
  const existing = store.get(bucket);
  if (existing && existing.signature === signature) {
    return existing.key;
  }
  const key = makeIdempotencyKey();
  store.set(bucket, { signature, key });
  return key;
}

function formatAuthorLabel(userId: string, membersById: Map<string, Member>): string {
  const member = membersById.get(userId);
  return member ? member.displayName : shortOid(userId);
}

/**
 * Line-anchored code comment thread panel bound to OrbitSpace's unified main-room
 * discussion records (replies, OPEN/RESOLVED status, and one-click chat forwarding).
 */
export function CodeCommentPanel({
  spaceId,
  projectId,
  access,
  canUploadOrManage,
  diff,
  selectedFile,
  comments,
  commentsLoading,
  commentsError,
  membersById,
  composerDraft,
  onChangeComposerDraft,
  focusedCommentId,
  highlightNonce,
  onSelectCommentAnchor,
  onForwardCodeComment,
  onRefreshComments,
}: CodeCommentPanelProps) {
  const queryClient = useQueryClient();
  const idemStoreRef = useRef<Map<string, ScopedIdempotencyState>>(new Map());
  const commentCardRefs = useRef<Map<string, HTMLLIElement>>(new Map());

  const [scopeFilter, setScopeFilter] = useState<"FILE" | "ALL">(selectedFile ? "FILE" : "ALL");
  const [statusFilter, setStatusFilter] = useState<"ALL" | DiscussionStatus>("ALL");
  const [composerBody, setComposerBody] = useState("");
  const [forwardOnCreate, setForwardOnCreate] = useState(false);

  const [replyDrafts, setReplyDrafts] = useState<Record<string, string>>({});
  const [replyTargetId, setReplyTargetId] = useState<string | null>(null);
  const [resolvingCommentId, setResolvingCommentId] = useState<string | null>(null);
  const [conclusionSelection, setConclusionSelection] = useState<Record<string, string>>({});
  const [forwardedFeedbackId, setForwardedFeedbackId] = useState<string | null>(null);
  const [actionErrorByCommentId, setActionErrorByCommentId] = useState<Record<string, unknown>>({});

  useEffect(() => {
    if (!selectedFile && scopeFilter === "FILE") {
      setScopeFilter("ALL");
    }
  }, [scopeFilter, selectedFile]);

  useEffect(() => {
    if (!composerDraft) {
      setComposerBody("");
      setForwardOnCreate(false);
    }
  }, [composerDraft]);

  useEffect(() => {
    if (!focusedCommentId) return;
    setScopeFilter("ALL");
    setStatusFilter("ALL");
    const timer = window.setTimeout(() => {
      const node = commentCardRefs.current.get(focusedCommentId);
      node?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }, 60);
    return () => window.clearTimeout(timer);
  }, [focusedCommentId, highlightNonce]);

  const commentsQueryKey = useMemo(
    () => [...spaceKey(access), "project", projectId, "code-comments"] as const,
    [access, projectId],
  );

  const upsertCommentInCache = (updated: CodeCommentDto) => {
    queryClient.setQueryData<{ items: CodeCommentDto[] }>(commentsQueryKey, (prev) => {
      if (!prev) return { items: [updated] };
      const exists = prev.items.some((item) => item.commentId === updated.commentId);
      return {
        items: exists
          ? prev.items.map((item) => (item.commentId === updated.commentId ? updated : item))
          : [updated, ...prev.items],
      };
    });
    void queryClient.invalidateQueries({ queryKey: commentsQueryKey });
  };

  const createCommentMutation = useMutation({
    mutationFn: async (params: {
      filePath: string;
      side: CodeCommentSide;
      lineNumber: number;
      bodyText: string;
      forwardToChat: boolean;
    }) => {
      const signature = JSON.stringify({
        diffId: diff.diffId,
        base: diff.base,
        target: diff.target,
        ...params,
      });
      const key = resolveScopedIdemKey(idemStoreRef.current, "create", signature);
      return createCodeComment(
        spaceId,
        projectId,
        {
          diffId: diff.diffId,
          base: diff.base,
          target: diff.target,
          filePath: params.filePath,
          side: params.side,
          lineNumber: params.lineNumber,
          bodyText: params.bodyText,
          forwardToChat: params.forwardToChat,
        },
        access,
        key,
      );
    },
    onSuccess: (created, vars) => {
      idemStoreRef.current.delete("create");
      setComposerBody("");
      setForwardOnCreate(false);
      onChangeComposerDraft(null);
      upsertCommentInCache(created);
      onSelectCommentAnchor(created);
      if (vars.forwardToChat || created.rootChatMessageId) {
        onForwardCodeComment?.(created);
      }
    },
  });

  const replyMutation = useMutation({
    mutationFn: async (params: { commentId: string; bodyText: string }) => {
      const key = resolveScopedIdemKey(
        idemStoreRef.current,
        `reply:${params.commentId}`,
        params.bodyText,
      );
      return replyCodeComment(
        spaceId,
        projectId,
        params.commentId,
        { bodyText: params.bodyText },
        access,
        key,
      );
    },
    onSuccess: (updated, vars) => {
      idemStoreRef.current.delete(`reply:${vars.commentId}`);
      setReplyDrafts((prev) => ({ ...prev, [vars.commentId]: "" }));
      setReplyTargetId(null);
      setActionErrorByCommentId((prev) => ({ ...prev, [vars.commentId]: null }));
      upsertCommentInCache(updated);
    },
    onError: (err, vars) => {
      setActionErrorByCommentId((prev) => ({ ...prev, [vars.commentId]: err }));
    },
  });

  const statusMutation = useMutation({
    mutationFn: async (params: {
      commentId: string;
      status: DiscussionStatus;
      conclusionMsgId: string | null;
    }) => {
      const sig = `${params.status}:${params.conclusionMsgId ?? ""}`;
      const key = resolveScopedIdemKey(idemStoreRef.current, `status:${params.commentId}`, sig);
      return changeCodeCommentDiscussion(
        spaceId,
        projectId,
        params.commentId,
        { status: params.status, conclusionMsgId: params.conclusionMsgId },
        access,
        key,
      );
    },
    onSuccess: (updated, vars) => {
      idemStoreRef.current.delete(`status:${vars.commentId}`);
      setResolvingCommentId(null);
      setActionErrorByCommentId((prev) => ({ ...prev, [vars.commentId]: null }));
      upsertCommentInCache(updated);
    },
    onError: (err, vars) => {
      setActionErrorByCommentId((prev) => ({ ...prev, [vars.commentId]: err }));
    },
  });

  const forwardMutation = useMutation({
    mutationFn: async (comment: CodeCommentDto) => {
      const key = resolveScopedIdemKey(
        idemStoreRef.current,
        `forward:${comment.commentId}`,
        comment.commentId,
      );
      return forwardCodeCommentToChat(spaceId, projectId, comment.commentId, {}, access, key);
    },
    onSuccess: (updated, orig) => {
      idemStoreRef.current.delete(`forward:${orig.commentId}`);
      setForwardedFeedbackId(updated.commentId);
      setActionErrorByCommentId((prev) => ({ ...prev, [orig.commentId]: null }));
      upsertCommentInCache(updated);
      onForwardCodeComment?.(updated);
    },
    onError: (err, orig) => {
      setActionErrorByCommentId((prev) => ({ ...prev, [orig.commentId]: err }));
    },
  });

  const selectedFileComparable = selectedFile ? isEntryComparable(selectedFile) : false;
  const allowOldSide =
    !selectedFile ||
    !selectedFileComparable ||
    selectedFile.baseStatus !== "ABSENT_CONFIRMED";
  const allowNewSide =
    !selectedFile ||
    !selectedFileComparable ||
    selectedFile.targetStatus !== "ABSENT_CONFIRMED";

  const visibleComments = useMemo(() => {
    return comments.filter((c) => {
      if (scopeFilter === "FILE" && selectedFile) {
        const matchesPath =
          c.filePath === selectedFile.path ||
          (selectedFile.oldPath !== null && c.filePath === selectedFile.oldPath);
        if (!matchesPath) return false;
      }
      if (statusFilter !== "ALL" && c.discussionStatus !== statusFilter) {
        return false;
      }
      return true;
    });
  }, [comments, scopeFilter, selectedFile, statusFilter]);

  const handleOpenSelectedFileComposer = () => {
    if (!selectedFile || !selectedFileComparable) return;
    const defaultSide: CodeCommentSide =
      selectedFile.comparisonState === "DELETED" ? "OLD" : "NEW";
    onChangeComposerDraft({
      filePath: selectedFile.path,
      side: defaultSide,
      lineNumber: 1,
      allowSideToggle: selectedFile.comparisonState !== "ADDED" && selectedFile.comparisonState !== "DELETED",
    });
  };

  const handleCreateSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!composerDraft || !canUploadOrManage || createCommentMutation.isPending) return;
    const trimmed = composerBody.trim();
    if (!trimmed) return;
    const lineNum = Math.max(1, Math.floor(composerDraft.lineNumber || 1));
    createCommentMutation.mutate({
      filePath: composerDraft.filePath,
      side: composerDraft.side,
      lineNumber: lineNum,
      bodyText: trimmed,
      forwardToChat: forwardOnCreate,
    });
  };

  const commentsCardState = resolveResourceCardState(commentsError);

  return (
    <section className="code-comment-panel" aria-label="行级代码批注与统一讨论">
      <div className="drawer-section-head">
        <h3>
          <MessageSquarePlus size={16} /> 代码行级批注 ({comments.length})
        </h3>
        <div className="comment-toolbar-actions">
          {selectedFile && selectedFileComparable && canUploadOrManage && !composerDraft && (
            <Button
              className="primary drawer-btn-sm"
              onClick={handleOpenSelectedFileComposer}
            >
              <MessageSquarePlus size={14} /> 对当前文件发起批注
            </Button>
          )}
          <Button
            className="secondary drawer-btn-sm"
            onClick={onRefreshComments}
            aria-label="刷新批注列表"
          >
            <RefreshCw size={13} />
          </Button>
        </div>
      </div>

      <p className="drawer-hint">
        批注直接绑定主群聊统一讨论记录，回复与 <code>OPEN / RESOLVED</code> 状态在画布与主群聊间实时共享，绝不产生孤立副本。
      </p>

      {!canUploadOrManage && (
        <div className="drawer-callout drawer-callout-info" role="status">
          当前角色仅支持只读查阅批注与讨论记录；需要 MEMBER 及以上权限方可发起批注、回复、变更状态或转发主群聊。
        </div>
      )}

      {selectedFile && !selectedFileComparable && (
        <div className="drawer-callout drawer-callout-warn" role="status">
          当前选中的文件（<code>{selectedFile.path}</code>）未共享、缺少基础内容或不受支持，不允许在其上发起新的代码行级批注。
        </div>
      )}

      {/* Line-anchored composer */}
      {composerDraft && canUploadOrManage && (
        <form className="comment-composer-card" onSubmit={handleCreateSubmit}>
          <div className="comment-composer-head">
            <div className="comment-anchor-pill">
              <code>{composerDraft.filePath}</code>
              <span>
                {composerDraft.side} · L{composerDraft.lineNumber}
              </span>
            </div>
            <Button
              className="secondary drawer-btn-sm"
              onClick={() => onChangeComposerDraft(null)}
            >
              取消
            </Button>
          </div>

          <div className="comment-composer-coords">
            <label className="comment-coord-field">
              <span>对比侧</span>
              <select
                value={composerDraft.side}
                onChange={(e) => {
                  const nextSide = e.target.value as CodeCommentSide;
                  const mappedLine =
                    nextSide === "OLD"
                      ? composerDraft.oldLineNumber ?? composerDraft.lineNumber
                      : composerDraft.newLineNumber ?? composerDraft.lineNumber;
                  onChangeComposerDraft({
                    ...composerDraft,
                    side: nextSide,
                    lineNumber: Math.max(1, mappedLine),
                  });
                }}
              >
                <option value="NEW" disabled={!allowNewSide && composerDraft.side !== "NEW"}>
                  {CODE_COMMENT_SIDE_LABELS.NEW}
                </option>
                <option value="OLD" disabled={!allowOldSide && composerDraft.side !== "OLD"}>
                  {CODE_COMMENT_SIDE_LABELS.OLD}
                </option>
              </select>
            </label>

            <label className="comment-coord-field">
              <span>锚点行号</span>
              <input
                type="number"
                min={1}
                step={1}
                required
                value={composerDraft.lineNumber}
                onChange={(e) => {
                  const nextLine = Number.parseInt(e.target.value, 10);
                  onChangeComposerDraft({
                    ...composerDraft,
                    lineNumber: Number.isFinite(nextLine) && nextLine >= 1 ? nextLine : 1,
                  });
                }}
              />
            </label>
          </div>

          <label className="field">
            批注内容
            <textarea
              rows={3}
              required
              maxLength={4000}
              placeholder="输入针对该代码行的评审意见或问题…"
              value={composerBody}
              disabled={createCommentMutation.isPending}
              onChange={(e) => setComposerBody(e.target.value)}
            />
          </label>

          <label className="git-check-chip comment-forward-check">
            <input
              type="checkbox"
              checked={forwardOnCreate}
              disabled={createCommentMutation.isPending}
              onChange={(e) => setForwardOnCreate(e.target.checked)}
            />
            创建同时引用转发至空间主群聊（生成 CODE_COMMENT_REF 卡片）
          </label>

          {createCommentMutation.isError && (
            <div className="alert" role="alert">
              <span>{gitCanvasErrorText(createCommentMutation.error)}</span>
            </div>
          )}

          <div className="comment-composer-actions">
            <Button
              type="submit"
              className="primary drawer-btn-sm"
              disabled={createCommentMutation.isPending || !composerBody.trim()}
            >
              <Send size={13} />
              {createCommentMutation.isPending ? "提交中…" : "发布行级批注"}
            </Button>
          </div>
        </form>
      )}

      {/* Filter bar */}
      <div className="comment-filter-bar">
        <div className="comment-tab-group" role="tablist" aria-label="批注显示范围">
          {selectedFile && (
            <button
              type="button"
              role="tab"
              aria-selected={scopeFilter === "FILE"}
              className={`comment-tab-btn ${scopeFilter === "FILE" ? "is-active" : ""}`}
              onClick={() => setScopeFilter("FILE")}
            >
              当前文件
            </button>
          )}
          <button
            type="button"
            role="tab"
            aria-selected={scopeFilter === "ALL"}
            className={`comment-tab-btn ${scopeFilter === "ALL" ? "is-active" : ""}`}
            onClick={() => setScopeFilter("ALL")}
          >
            全部对比文件 ({comments.length})
          </button>
        </div>

        <select
          className="comment-status-select"
          aria-label="按讨论状态筛选"
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value as "ALL" | DiscussionStatus)}
        >
          <option value="ALL">全部状态</option>
          <option value="OPEN">仅 OPEN</option>
          <option value="RESOLVED">仅 RESOLVED</option>
        </select>
      </div>

      {commentsLoading && <Loading label="正在加载代码批注与讨论串…" />}

      {Boolean(commentsError) &&
        (commentsCardState ? (
          <ResourceStateCard state={commentsCardState} />
        ) : (
          <div className="alert" role="alert">
            <span>{gitCanvasErrorText(commentsError)}</span>
            <Button className="secondary drawer-btn-sm" onClick={onRefreshComments}>
              重试
            </Button>
          </div>
        ))}

      {!commentsLoading && !commentsError && visibleComments.length === 0 && (
        <div className="comment-empty-box">
          <span>
            {comments.length === 0
              ? "当前双点对比尚未创建代码批注；点击可比对 Diff 中的任意代码行即可锚定行号发起讨论。"
              : "当前筛选条件下暂无匹配的代码批注。"}
          </span>
        </div>
      )}

      {visibleComments.length > 0 && (
        <ul className="comment-list">
          {visibleComments.map((comment) => {
            const isFocused = focusedCommentId === comment.commentId;
            const isResolved = comment.discussionStatus === "RESOLVED";
            const replyText = replyDrafts[comment.commentId] ?? "";
            const isReplyingHere = replyTargetId === comment.commentId;
            const isResolvingHere = resolvingCommentId === comment.commentId;
            const rowError = actionErrorByCommentId[comment.commentId];
            const isForwardBusy =
              forwardMutation.isPending && forwardMutation.variables?.commentId === comment.commentId;
            const isStatusBusy =
              statusMutation.isPending && statusMutation.variables?.commentId === comment.commentId;
            const isReplyBusy =
              replyMutation.isPending && replyMutation.variables?.commentId === comment.commentId;

            return (
              <li
                key={isFocused ? `${comment.commentId}-${highlightNonce ?? 0}` : comment.commentId}
                ref={(el) => {
                  if (el) commentCardRefs.current.set(comment.commentId, el);
                  else commentCardRefs.current.delete(comment.commentId);
                }}
                className={`comment-card ${isResolved ? "is-resolved" : ""} ${
                  isFocused ? "comment-card-focused" : ""
                }`}
              >
                <div className="comment-card-head">
                  <button
                    type="button"
                    className="comment-anchor-link"
                    onClick={() => onSelectCommentAnchor(comment)}
                    title="定位并闪烁高亮锚点代码行"
                  >
                    <code>{comment.filePath}</code>
                    <span className="comment-side-tag">
                      {comment.side} L{comment.lineNumber}
                    </span>
                  </button>

                  <span
                    className={`drawer-badge ${
                      isResolved ? "drawer-badge-ok" : "drawer-badge-warn"
                    }`}
                  >
                    {isResolved ? <CheckCircle2 size={12} /> : <CircleDot size={12} />}
                    {DISCUSSION_STATUS_LABELS[comment.discussionStatus]} · v
                    {comment.discussionVersion}
                  </span>
                </div>

                <div className="comment-card-meta">
                  <strong>{formatAuthorLabel(comment.authorUserId, membersById)}</strong>
                  <span>{formatDateTime(comment.createdAt)}</span>
                  <span className="comment-thread-link-tag">
                    <Link2 size={12} />
                    {comment.rootChatMessageId
                      ? `已绑定主群聊 (${shortOid(comment.rootChatMessageId)})`
                      : "未转发主群聊"}
                  </span>
                </div>

                <p className="comment-body-text">{comment.bodyText}</p>

                {/* Replies from unified discussion thread */}
                {comment.replies.length > 0 && (
                  <ul className="comment-reply-list" aria-label="统一讨论回复">
                    {comment.replies.map((reply) => (
                      <li key={reply.messageId} className="comment-reply-item">
                        <div className="comment-reply-meta">
                          <strong>{formatAuthorLabel(reply.senderId, membersById)}</strong>
                          <span>{formatDateTime(reply.createdAt)}</span>
                        </div>
                        <p className="comment-reply-body">{reply.bodyText}</p>
                      </li>
                    ))}
                  </ul>
                )}

                {forwardedFeedbackId === comment.commentId && (
                  <div className="drawer-inline-success" role="status">
                    已引用转发至空间主群聊并复用同一讨论记录。
                  </div>
                )}

                {Boolean(rowError) && (
                  <div className="drawer-inline-error" role="alert">
                    {gitCanvasErrorText(rowError)}
                  </div>
                )}

                {/* Action bar */}
                {canUploadOrManage && (
                  <div className="comment-card-actions">
                    <Button
                      className="secondary drawer-btn-sm"
                      onClick={() => {
                        setReplyTargetId(isReplyingHere ? null : comment.commentId);
                      }}
                    >
                      <Reply size={13} /> 回复 ({comment.replies.length})
                    </Button>

                    {!isResolved ? (
                      <>
                        <Button
                          className="secondary drawer-btn-sm"
                          disabled={isStatusBusy}
                          onClick={() => {
                            statusMutation.mutate({
                              commentId: comment.commentId,
                              status: "RESOLVED",
                              conclusionMsgId: conclusionSelection[comment.commentId] || null,
                            });
                          }}
                        >
                          <CheckCircle2 size={13} />
                          {isStatusBusy ? "更新中…" : "标记 RESOLVED"}
                        </Button>
                        {comment.replies.length > 0 && (
                          <Button
                            className="secondary drawer-btn-sm"
                            onClick={() =>
                              setResolvingCommentId(
                                isResolvingHere ? null : comment.commentId,
                              )
                            }
                          >
                            选择结论回复
                          </Button>
                        )}
                      </>
                    ) : (
                      <Button
                        className="secondary drawer-btn-sm"
                        disabled={isStatusBusy}
                        onClick={() => {
                          statusMutation.mutate({
                            commentId: comment.commentId,
                            status: "OPEN",
                            conclusionMsgId: null,
                          });
                        }}
                      >
                        <CircleDot size={13} />
                        {isStatusBusy ? "更新中…" : "重新打开 (OPEN)"}
                      </Button>
                    )}

                    <Button
                      className="primary drawer-btn-sm"
                      disabled={isForwardBusy}
                      onClick={() => forwardMutation.mutate(comment)}
                    >
                      <Forward size={13} />
                      {isForwardBusy ? "转发中…" : "引用转发至主群聊"}
                    </Button>
                  </div>
                )}

                {/* Optional conclusion reply selector when resolving */}
                {isResolvingHere && canUploadOrManage && !isResolved && (
                  <div className="comment-conclusion-box">
                    <label className="comment-coord-field">
                      <span>关联结论回复（可选）</span>
                      <select
                        value={conclusionSelection[comment.commentId] ?? ""}
                        onChange={(e) =>
                          setConclusionSelection((prev) => ({
                            ...prev,
                            [comment.commentId]: e.target.value,
                          }))
                        }
                      >
                        <option value="">不指定具体回复</option>
                        {comment.replies.map((r) => (
                          <option key={r.messageId} value={r.messageId}>
                            {formatAuthorLabel(r.senderId, membersById)}: {r.bodyText.slice(0, 40)}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>
                )}

                {/* Inline reply composer */}
                {isReplyingHere && canUploadOrManage && (
                  <form
                    className="comment-reply-form"
                    onSubmit={(e) => {
                      e.preventDefault();
                      const trimmed = replyText.trim();
                      if (!trimmed || isReplyBusy) return;
                      replyMutation.mutate({
                        commentId: comment.commentId,
                        bodyText: trimmed,
                      });
                    }}
                  >
                    <input
                      type="text"
                      required
                      maxLength={4000}
                      placeholder="回复此代码批注（同步写入统一讨论串）…"
                      value={replyText}
                      disabled={isReplyBusy}
                      onChange={(e) =>
                        setReplyDrafts((prev) => ({
                          ...prev,
                          [comment.commentId]: e.target.value,
                        }))
                      }
                    />
                    <Button
                      type="submit"
                      className="primary drawer-btn-sm"
                      disabled={isReplyBusy || !replyText.trim()}
                    >
                      {isReplyBusy ? "发送中…" : "发送回复"}
                    </Button>
                  </form>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

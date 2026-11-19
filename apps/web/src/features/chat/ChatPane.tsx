import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import type {
  CardView,
  ChatMessage,
  GrantType,
  Member,
  ProjectSummary,
  SpaceRole,
} from "@orbitspace/shared-types";
import {
  AlertCircle,
  ArrowDown,
  CheckCircle2,
  CircleDot,
  CornerDownRight,
  FileCode2,
  Filter,
  FolderOpen,
  GitCommitHorizontal,
  GitPullRequestDraft,
  History,
  MessageSquare,
  Paperclip,
  RefreshCw,
  Reply,
  Send,
  Trash2,
  Wifi,
  WifiOff,
  X,
} from "lucide-react";
import { spaceKey, type SpaceAccess } from "../../shared/api/access";
import { canManageSpace, canUploadOrManage } from "../../shared/auth/session";
import { Button, Loading, ResourceStateCard } from "../../shared/ui/UiComponents";
import {
  airdropsKey,
  filesErrorText,
  formatBytes,
  listAirdrops,
  listSpaceFiles,
} from "../files/filesApi";
import {
  formatDateTime,
  listSpaceMembers,
  makeIdempotencyKey,
} from "../space/spaceApi";
import { chatErrorText } from "./chatApi";
import { useSpaceChat, type WsConnectionStatus } from "./useSpaceChat";

export type ChatDraftQuote =
  | {
      kind: "COMMIT_REF";
      projectId: string;
      targetId: string;
      title: string;
      nonce: number;
    }
  | {
      kind: "WIP_REF";
      projectId: string;
      targetId: string;
      title: string;
      nonce: number;
    }
  | {
      kind: "FILE_REF";
      grantType: GrantType;
      targetId: string;
      title: string;
      nonce: number;
    };

export interface ChatPaneProps {
  spaceId: string;
  spaceRole: SpaceRole;
  access: SpaceAccess | null;
  isRevoked: boolean;
  currentUserId: string | null;
  projects: ProjectSummary[];
  activeProjectId: string | null;
  draftQuote: ChatDraftQuote | null;
  externalSyncNonce?: number;
  onClearDraftQuote: () => void;
  onSelectFileQuote?: (ref: {
    grantType: GrantType;
    targetId: string;
    title: string;
  }) => void;
  onOpenResourceCard: (target: {
    kind: "COMMIT" | "WIP";
    projectId: string;
    targetId: string;
  }) => void;
  onOpenCodeCommentCard?: (target: {
    projectId: string;
    commentId: string;
  }) => void;
  onOpenFileCard?: (target: {
    grantType: GrantType;
    targetId: string;
  }) => void;
}

function shortId(value: string): string {
  return value.length > 10 ? value.slice(0, 8) : value;
}

function messageSnippet(msg: ChatMessage, cardView?: CardView): string {
  switch (msg.content.kind) {
    case "TEXT":
      return msg.content.text.length > 56
        ? `${msg.content.text.slice(0, 56)}…`
        : msg.content.text;
    case "SYSTEM":
      return msg.content.text;
    case "COMMIT_REF":
      if (cardView && cardView.state !== "AVAILABLE") {
        return "[提交引用不可用]";
      }
      if (cardView?.state === "AVAILABLE") {
        return `[提交] ${cardView.label}`;
      }
      return `[提交引用] ${msg.content.purpose ?? "代码提交"}`;
    case "WIP_REF":
      if (cardView && cardView.state !== "AVAILABLE") {
        return "[WIP 快照不可用]";
      }
      if (cardView?.state === "AVAILABLE") {
        return `[WIP] ${cardView.label}`;
      }
      return `[WIP 快照] ${msg.content.purpose ?? "工作区快照"}`;
    case "FILE_REF":
      if (cardView && cardView.state !== "AVAILABLE") {
        return "[文件引用不可用]";
      }
      if (cardView?.state === "AVAILABLE") {
        return `[文件] ${cardView.label}`;
      }
      return `[文件引用] ${msg.content.purpose ?? "共享文件"}`;
    case "CODE_COMMENT_REF":
      if (cardView && cardView.state !== "AVAILABLE") {
        return "[代码批注不可用]";
      }
      if (cardView?.state === "AVAILABLE") {
        return `[代码批注] ${cardView.label}`;
      }
      return `[代码批注] ${msg.content.purpose ?? "行级批注"}`;
  }
}

function wsStatusBadge(status: WsConnectionStatus): { label: string; tone: string } {
  switch (status) {
    case "connected":
      return { label: "实时已连接", tone: "ok" };
    case "connecting":
      return { label: "连接中…", tone: "warn" };
    case "reconnecting":
      return { label: "重连同步中…", tone: "warn" };
    case "revoked":
      return { label: "授权已失效", tone: "danger" };
    default:
      return { label: "未连接", tone: "muted" };
  }
}

export function ChatPane({
  spaceId,
  spaceRole,
  access,
  isRevoked,
  currentUserId,
  projects,
  activeProjectId,
  draftQuote,
  externalSyncNonce = 0,
  onClearDraftQuote,
  onSelectFileQuote,
  onOpenResourceCard,
  onOpenCodeCommentCard,
  onOpenFileCard,
}: ChatPaneProps) {
  const canSend = canUploadOrManage(spaceRole);
  const isOwnerOrAdmin = canManageSpace(spaceRole);

  const {
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
    syncNow,
    reloadRoom,
  } = useSpaceChat({
    spaceId,
    access,
    isRevoked,
  });

  const [showFilePicker, setShowFilePicker] = useState(false);
  const [filePickerTab, setFilePickerTab] = useState<GrantType>("SPACE_FILE");
  const [manualAirdropId, setManualAirdropId] = useState("");
  const [localFileQuote, setLocalFileQuote] = useState<
    Extract<ChatDraftQuote, { kind: "FILE_REF" }> | null
  >(null);

  const activeDraftQuote = draftQuote ?? localFileQuote;

  const membersQuery = useQuery({
    queryKey: access ? ([...spaceKey(access), "members"] as const) : ["chat-members-none"],
    enabled: Boolean(access) && !isRevoked && !isRevokedInChat,
    queryFn: ({ signal }) => listSpaceMembers(spaceId, access!, signal),
  });

  const spaceFilesPickerQuery = useQuery({
    queryKey: access ? ([...spaceKey(access), "files"] as const) : ["chat-files-none"],
    enabled:
      showFilePicker &&
      filePickerTab === "SPACE_FILE" &&
      Boolean(access) &&
      !isRevoked &&
      !isRevokedInChat,
    queryFn: ({ signal }) => listSpaceFiles(spaceId, access!, signal),
  });

  const airdropsPickerQuery = useQuery({
    queryKey: airdropsKey("ALL"),
    enabled: showFilePicker && filePickerTab === "AIRDROP_SHARE" && !isRevoked && !isRevokedInChat,
    queryFn: ({ signal }) => listAirdrops("ALL", signal),
  });

  const membersById = useMemo(() => {
    const map = new Map<string, Member>();
    for (const m of membersQuery.data ?? []) {
      map.set(m.userId, m);
    }
    return map;
  }, [membersQuery.data]);

  const projectsById = useMemo(() => {
    const map = new Map<string, ProjectSummary>();
    for (const p of projects) {
      map.set(p.projectId, p);
    }
    return map;
  }, [projects]);

  const messagesById = useMemo(() => {
    const map = new Map<string, ChatMessage>();
    for (const m of messages) {
      map.set(m.messageId, m);
    }
    return map;
  }, [messages]);

  const repliesByRootId = useMemo(() => {
    const map = new Map<string, ChatMessage[]>();
    for (const m of messages) {
      if (m.replyToMsgId) {
        const list = map.get(m.replyToMsgId) ?? [];
        list.push(m);
        map.set(m.replyToMsgId, list);
      }
    }
    return map;
  }, [messages]);

  const [filterByActiveProject, setFilterByActiveProject] = useState(false);
  const [composerText, setComposerText] = useState("");
  const [replyTargetId, setReplyTargetId] = useState<string | null>(null);
  const [resolvingDiscussionMsgId, setResolvingDiscussionMsgId] = useState<string | null>(
    null,
  );
  const [selectedConclusionId, setSelectedConclusionId] = useState<string>("");
  const [discussionBusyId, setDiscussionBusyId] = useState<string | null>(null);
  const [discussionError, setDiscussionError] = useState<string | null>(null);
  const [cardActionError, setCardActionError] = useState<string | null>(null);
  const [openingCardId, setOpeningCardId] = useState<string | null>(null);
  const [showScrollBottom, setShowScrollBottom] = useState(false);

  const listRef = useRef<HTMLDivElement>(null);
  const composerInputRef = useRef<HTMLTextAreaElement>(null);
  const discussionIdemRef = useRef<{ key: string; signature: string } | null>(null);
  const wasAtBottomRef = useRef(true);
  const initialScrolledRef = useRef(false);

  // Reset composer UI state when space or membership epoch changes
  useEffect(() => {
    setComposerText("");
    setReplyTargetId(null);
    setResolvingDiscussionMsgId(null);
    setSelectedConclusionId("");
    setDiscussionError(null);
    setCardActionError(null);
    setShowFilePicker(false);
    setManualAirdropId("");
    setLocalFileQuote(null);
    initialScrolledRef.current = false;
    wasAtBottomRef.current = true;
  }, [spaceId, access?.membershipEpoch]);

  // Trigger sync when external producer (e.g. GitWorkspaceView forwarding a code comment) notifies
  const lastExternalSyncNonceRef = useRef(externalSyncNonce);
  useEffect(() => {
    if (externalSyncNonce > 0 && externalSyncNonce !== lastExternalSyncNonceRef.current) {
      lastExternalSyncNonceRef.current = externalSyncNonce;
      void syncNow();
    }
  }, [externalSyncNonce, syncNow]);

  // Focus composer when user quotes a commit/WIP/file
  useEffect(() => {
    if (activeDraftQuote) {
      composerInputRef.current?.focus();
    }
  }, [activeDraftQuote]);

  // Auto-scroll to bottom on initial load and new messages when near bottom
  useEffect(() => {
    const el = listRef.current;
    if (!el || loadingInitial) return;
    if (!initialScrolledRef.current && messages.length > 0) {
      el.scrollTop = el.scrollHeight;
      initialScrolledRef.current = true;
      wasAtBottomRef.current = true;
      setShowScrollBottom(false);
      return;
    }
    if (wasAtBottomRef.current) {
      el.scrollTop = el.scrollHeight;
      setShowScrollBottom(false);
    } else if (messages.length > 0) {
      setShowScrollBottom(true);
    }
  }, [loadingInitial, messages.length, pendingMessages.length]);

  const handleListScroll = () => {
    const el = listRef.current;
    if (!el) return;
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    const nearBottom = distanceFromBottom < 72;
    wasAtBottomRef.current = nearBottom;
    if (nearBottom) setShowScrollBottom(false);
  };

  const scrollToBottom = () => {
    const el = listRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
    wasAtBottomRef.current = true;
    setShowScrollBottom(false);
  };

  const scrollToMessage = (messageId: string) => {
    const el = listRef.current?.querySelector<HTMLElement>(
      `[data-msg-id="${CSS.escape(messageId)}"]`,
    );
    el?.scrollIntoView({ behavior: "smooth", block: "center" });
  };

  const visibleMessages = useMemo(() => {
    if (!filterByActiveProject || !activeProjectId) {
      return messages;
    }
    const matchingRootIds = new Set<string>();
    for (const msg of messages) {
      if (
        (msg.content.kind === "COMMIT_REF" ||
          msg.content.kind === "WIP_REF" ||
          msg.content.kind === "CODE_COMMENT_REF") &&
        msg.content.projectId === activeProjectId
      ) {
        matchingRootIds.add(msg.replyToMsgId ?? msg.messageId);
      }
    }
    return messages.filter((msg) => {
      if (matchingRootIds.has(msg.messageId)) return true;
      if (msg.replyToMsgId && matchingRootIds.has(msg.replyToMsgId)) return true;
      return false;
    });
  }, [activeProjectId, filterByActiveProject, messages]);

  const replyTarget = replyTargetId ? messagesById.get(replyTargetId) ?? null : null;

  const senderLabel = (senderId: string | null): string => {
    if (!senderId) return "系统";
    if (senderId === currentUserId) {
      const me = membersById.get(senderId);
      return me ? `${me.displayName}（我）` : "我";
    }
    return membersById.get(senderId)?.displayName ?? `成员 ${shortId(senderId)}`;
  };

  const canControlDiscussion = (msg: ChatMessage): boolean => {
    if (msg.replyToMsgId !== null || msg.discussion === null) return false;
    if (isOwnerOrAdmin) return true;
    return Boolean(currentUserId && msg.senderId === currentUserId && canSend);
  };

  const applyFileQuote = (ref: { grantType: GrantType; targetId: string; title: string }) => {
    if (onSelectFileQuote) {
      setLocalFileQuote(null);
      onSelectFileQuote(ref);
    } else {
      onClearDraftQuote();
      setLocalFileQuote({
        kind: "FILE_REF",
        ...ref,
        nonce: Date.now(),
      });
    }
    setShowFilePicker(false);
    setManualAirdropId("");
    composerInputRef.current?.focus();
  };

  const clearCurrentQuote = () => {
    setLocalFileQuote(null);
    onClearDraftQuote();
  };

  const submitComposer = async () => {
    if (!canSend || isRevoked || isRevokedInChat) return;

    const trimmed = composerText.trim();
    if (!activeDraftQuote && !trimmed) return;

    let content: Parameters<typeof sendMessage>[0]["content"];
    if (activeDraftQuote) {
      if (activeDraftQuote.kind === "COMMIT_REF") {
        content = {
          kind: "COMMIT_REF",
          projectId: activeDraftQuote.projectId,
          commitOid: activeDraftQuote.targetId,
          ...(trimmed ? { purpose: trimmed } : {}),
        };
      } else if (activeDraftQuote.kind === "WIP_REF") {
        content = {
          kind: "WIP_REF",
          projectId: activeDraftQuote.projectId,
          snapshotId: activeDraftQuote.targetId,
          ...(trimmed ? { purpose: trimmed } : {}),
        };
      } else {
        content = {
          kind: "FILE_REF",
          grantType: activeDraftQuote.grantType,
          targetId: activeDraftQuote.targetId,
          ...(trimmed ? { purpose: trimmed } : {}),
        };
      }
    } else {
      content = {
        kind: "TEXT",
        text: trimmed,
      };
    }

    const replyId = replyTarget?.messageId;
    setComposerText("");
    setReplyTargetId(null);
    setShowFilePicker(false);
    if (activeDraftQuote) {
      clearCurrentQuote();
    }
    wasAtBottomRef.current = true;

    await sendMessage({
      content,
      ...(replyId ? { replyToMsgId: replyId } : {}),
    });
  };

  const handleSubmitMessage = async (event: FormEvent) => {
    event.preventDefault();
    await submitComposer();
  };

  const handleOpenCard = async (msg: ChatMessage) => {
    if (
      msg.content.kind !== "COMMIT_REF" &&
      msg.content.kind !== "WIP_REF" &&
      msg.content.kind !== "FILE_REF" &&
      msg.content.kind !== "CODE_COMMENT_REF"
    ) {
      return;
    }
    setCardActionError(null);
    setOpeningCardId(msg.messageId);
    try {
      const freshView = await resolveCardForOpen(msg.messageId);
      if (!freshView) return;
      if (freshView.state !== "AVAILABLE") {
        setCardActionError(
          freshView.state === "FORBIDDEN"
            ? "无访问权限：当前账号无权查看该资源卡片（如独立空投仅限指定接收人）。"
            : "该引用资源当前不可访问、已过期或已被清理，已更新卡片状态。",
        );
        return;
      }
      if (msg.content.kind === "COMMIT_REF") {
        onOpenResourceCard({
          kind: "COMMIT",
          projectId: msg.content.projectId,
          targetId: msg.content.commitOid,
        });
      } else if (msg.content.kind === "WIP_REF") {
        onOpenResourceCard({
          kind: "WIP",
          projectId: msg.content.projectId,
          targetId: msg.content.snapshotId,
        });
      } else if (msg.content.kind === "CODE_COMMENT_REF") {
        onOpenCodeCommentCard?.({
          projectId: msg.content.projectId,
          commentId: msg.content.commentId,
        });
      } else if (msg.content.kind === "FILE_REF") {
        onOpenFileCard?.({
          grantType: msg.content.grantType,
          targetId: msg.content.targetId,
        });
      }
    } finally {
      setOpeningCardId(null);
    }
  };

  const submitDiscussionChange = async (
    messageId: string,
    status: "OPEN" | "RESOLVED",
    conclusionMsgId: string | null,
  ) => {
    setDiscussionBusyId(messageId);
    setDiscussionError(null);
    const sig = `${messageId}:${status}:${conclusionMsgId ?? ""}`;
    const key =
      discussionIdemRef.current?.signature === sig
        ? discussionIdemRef.current.key
        : makeIdempotencyKey();
    discussionIdemRef.current = { key, signature: sig };

    try {
      await toggleDiscussion(messageId, { status, conclusionMsgId }, key);
      discussionIdemRef.current = null;
      setResolvingDiscussionMsgId(null);
      setSelectedConclusionId("");
    } catch (error) {
      setDiscussionError(chatErrorText(error));
    } finally {
      setDiscussionBusyId(null);
    }
  };

  if (isRevoked || isRevokedInChat) {
    return (
      <aside className="chat-pane" aria-label="空间唯一主群聊">
        <header className="chat-pane-header">
          <div className="chat-pane-title">
            <MessageSquare size={16} />
            <h2>空间唯一主群聊</h2>
          </div>
        </header>
        <div className="chat-pane-body-center">
          <ResourceStateCard state="REVOKED_FOR_ACTOR" />
        </div>
      </aside>
    );
  }

  const wsBadge = wsStatusBadge(wsStatus);
  const activeProjectName = activeProjectId
    ? projectsById.get(activeProjectId)?.name ?? shortId(activeProjectId)
    : null;

  return (
    <aside className="chat-pane" aria-label="空间唯一主群聊">
      <header className="chat-pane-header">
        <div className="chat-pane-title-row">
          <div className="chat-pane-title">
            <MessageSquare size={16} />
            <h2>空间唯一主群聊</h2>
          </div>
          <div className="chat-pane-tools">
            <span
              className={`chat-ws-pill tone-${wsBadge.tone}`}
              title={`已读游标 seq #${lastReadSeq} · 事件游标 eventSeq #${eventSeq}`}
            >
              {wsStatus === "connected" ? <Wifi size={12} /> : <WifiOff size={12} />}
              {wsBadge.label}
            </span>
            <Button
              className="secondary workbench-btn-xs"
              title="手动同步事件并刷新资源卡片权限"
              onClick={() => {
                void syncNow();
                void refreshVisibleCards();
              }}
            >
              <RefreshCw size={13} />
            </Button>
          </div>
        </div>

        <div className="chat-filter-bar">
          <label className="chat-project-filter-toggle">
            <input
              type="checkbox"
              checked={filterByActiveProject}
              disabled={!activeProjectId}
              onChange={(e) => setFilterByActiveProject(e.target.checked)}
            />
            <Filter size={13} />
            <span>仅筛选当前工程讨论</span>
            {filterByActiveProject && activeProjectName && (
              <span className="chat-filter-project-tag">{activeProjectName}</span>
            )}
          </label>
        </div>

        <p className="chat-discussion-disclaimer">
          讨论仅围绕本空间主群聊展开；标记已处理不代表代码已合并。
        </p>
      </header>

      {syncError && (
        <div className="alert chat-inline-alert" role="alert">
          <AlertCircle size={15} />
          <span>{syncError}</span>
        </div>
      )}

      {discussionError && (
        <div className="alert chat-inline-alert" role="alert">
          <AlertCircle size={15} />
          <span>{discussionError}</span>
        </div>
      )}

      {cardActionError && (
        <div className="alert chat-inline-alert" role="alert">
          <AlertCircle size={15} />
          <span>{cardActionError}</span>
        </div>
      )}

      <div className="chat-message-list-wrap">
        <div
          ref={listRef}
          className="chat-message-list"
          onScroll={handleListScroll}
          role="log"
          aria-live="polite"
        >
          {loadingInitial && <Loading label="正在加载空间主群聊…" />}

          {!loadingInitial && initialError ? (
            <div className="alert" role="alert">
              <AlertCircle size={16} />
              <span>{chatErrorText(initialError)}</span>
              <Button className="secondary workbench-btn-xs" onClick={reloadRoom}>
                重试
              </Button>
            </div>
          ) : null}

          {!loadingInitial && !initialError && hasMoreHistory && (
            <div className="chat-history-more">
              <Button
                className="secondary workbench-btn-xs"
                disabled={loadingOlder}
                onClick={() => {
                  void loadOlderHistory();
                }}
              >
                <History size={13} />
                {loadingOlder ? "加载更早消息中…" : "加载更早消息"}
              </Button>
              {olderError ? (
                <p className="chat-inline-error">{chatErrorText(olderError)}</p>
              ) : null}
            </div>
          )}

          {!loadingInitial && !initialError && visibleMessages.length === 0 && (
            <div className="chat-empty-state">
              {filterByActiveProject ? (
                <>
                  <p>当前工程下暂无关联讨论卡片。</p>
                  <Button
                    className="secondary workbench-btn-xs"
                    onClick={() => setFilterByActiveProject(false)}
                  >
                    显示全部空间讨论
                  </Button>
                </>
              ) : (
                <p>空间主群聊暂无消息。你可以发送讨论文本，或在 Git 拓扑节点中点击「引用到群聊」。</p>
              )}
            </div>
          )}

          {visibleMessages.map((msg) => {
            if (msg.content.kind === "SYSTEM") {
              return (
                <div
                  key={msg.messageId}
                  data-msg-id={msg.messageId}
                  className="chat-system-msg"
                >
                  <span>{msg.content.text}</span>
                  <time>{formatDateTime(msg.createdAt)}</time>
                </div>
              );
            }

            const parentMsg = msg.replyToMsgId
              ? messagesById.get(msg.replyToMsgId)
              : undefined;
            const cardView = cardViews[msg.messageId];
            const isCardResolving = Boolean(cardResolvingIds[msg.messageId]);
            const isSelf = Boolean(currentUserId && msg.senderId === currentUserId);
            const rootReplies =
              msg.replyToMsgId === null ? repliesByRootId.get(msg.messageId) ?? [] : [];
            const conclusionMsg =
              msg.discussion?.conclusionMsgId
                ? messagesById.get(msg.discussion.conclusionMsgId)
                : undefined;

            const isDeniedCard = Boolean(cardView && cardView.state !== "AVAILABLE");

            return (
              <article
                key={msg.messageId}
                data-msg-id={msg.messageId}
                className={`chat-msg-card ${isSelf ? "is-self" : ""} ${
                  msg.discussion?.status === "RESOLVED" ? "is-resolved" : ""
                }`}
              >
                <header className="chat-msg-head">
                  <div className="chat-msg-author-row">
                    <strong>{senderLabel(msg.senderId)}</strong>
                    <span className="chat-msg-seq">#{msg.seq}</span>
                  </div>
                  <time className="chat-msg-time">{formatDateTime(msg.createdAt)}</time>
                </header>

                {msg.replyToMsgId && (
                  <button
                    type="button"
                    className="chat-reply-quote"
                    onClick={() => scrollToMessage(msg.replyToMsgId!)}
                  >
                    <CornerDownRight size={12} />
                    <span>
                      回复{" "}
                      {parentMsg
                        ? `${senderLabel(parentMsg.senderId)}: ${messageSnippet(
                            parentMsg,
                            cardViews[parentMsg.messageId],
                          )}`
                        : `消息 #${shortId(msg.replyToMsgId)}`}
                    </span>
                  </button>
                )}

                {msg.content.kind === "TEXT" && (
                  <p className="chat-msg-text">{msg.content.text}</p>
                )}

                {(msg.content.kind === "COMMIT_REF" || msg.content.kind === "WIP_REF") && (
                  <div className="chat-resource-block">
                    {isDeniedCard && cardView && cardView.state !== "AVAILABLE" ? (
                      <ResourceStateCard state={cardView.state} />
                    ) : (
                      <>
                        {msg.content.purpose && (
                          <p className="chat-msg-text">{msg.content.purpose}</p>
                        )}
                        {cardView?.state === "AVAILABLE" ? (
                          <button
                            type="button"
                            className="chat-resource-card-btn"
                            disabled={openingCardId === msg.messageId}
                            onClick={() => {
                              void handleOpenCard(msg);
                            }}
                          >
                            <div className="chat-resource-card-top">
                              <span className="chat-resource-kind">
                                {msg.content.kind === "COMMIT_REF" ? (
                                  <GitCommitHorizontal size={14} />
                                ) : (
                                  <GitPullRequestDraft size={14} />
                                )}
                                {msg.content.kind === "COMMIT_REF"
                                  ? "提交引用"
                                  : "WIP 快照"}
                              </span>
                              <span className="chat-resource-project">
                                {projectsById.get(msg.content.projectId)?.name ??
                                  shortId(msg.content.projectId)}
                              </span>
                            </div>
                            <strong className="chat-resource-label">
                              {cardView.label}
                            </strong>
                            <div className="chat-resource-card-foot">
                              <code>
                                {shortId(
                                  msg.content.kind === "COMMIT_REF"
                                    ? msg.content.commitOid
                                    : msg.content.snapshotId,
                                )}
                              </code>
                              <span>
                                {openingCardId === msg.messageId
                                  ? "正在校验权限…"
                                  : isCardResolving
                                    ? "正在刷新…"
                                    : "定位到 Git 画布 →"}
                              </span>
                            </div>
                          </button>
                        ) : (
                          <div className="chat-card-pending-box">
                            <span>正在校验资源卡片访问权限…</span>
                            {!isCardResolving && (
                              <Button
                                className="secondary workbench-btn-xs"
                                onClick={() => {
                                  void resolveCardForOpen(msg.messageId);
                                }}
                              >
                                重新解析
                              </Button>
                            )}
                          </div>
                        )}
                      </>
                    )}
                  </div>
                )}

                {msg.content.kind === "FILE_REF" && (
                  <div className="chat-resource-block">
                    {isDeniedCard && cardView && cardView.state !== "AVAILABLE" ? (
                      <div className="chat-denied-card-wrap">
                        <ResourceStateCard state={cardView.state} />
                        {msg.content.grantType === "AIRDROP_SHARE" &&
                          cardView.state === "FORBIDDEN" && (
                            <span className="chat-denied-card-note">
                              独立空投卡片不扩大权限，仅发送者与未撤销的指定接收人可访问。
                            </span>
                          )}
                      </div>
                    ) : (
                      <>
                        {msg.content.purpose && (
                          <p className="chat-msg-text">{msg.content.purpose}</p>
                        )}
                        {cardView?.state === "AVAILABLE" ? (
                          <button
                            type="button"
                            className={`chat-resource-card-btn ${
                              msg.content.grantType === "AIRDROP_SHARE"
                                ? "is-airdrop-card"
                                : "is-space-file-card"
                            }`}
                            disabled={openingCardId === msg.messageId}
                            onClick={() => {
                              void handleOpenCard(msg);
                            }}
                          >
                            <div className="chat-resource-card-top">
                              <span className="chat-resource-kind">
                                {msg.content.grantType === "SPACE_FILE" ? (
                                  <FolderOpen size={14} />
                                ) : (
                                  <Send size={14} />
                                )}
                                {msg.content.grantType === "SPACE_FILE"
                                  ? "空间文件"
                                  : "独立空投"}
                              </span>
                              <span className="chat-resource-project">
                                {msg.content.grantType === "SPACE_FILE"
                                  ? "继承空间权限"
                                  : "独立接收人授权"}
                              </span>
                            </div>
                            <strong className="chat-resource-label">
                              {cardView.label}
                            </strong>
                            <div className="chat-resource-card-foot">
                              <code>{shortId(msg.content.targetId)}</code>
                              <span>
                                {openingCardId === msg.messageId
                                  ? "正在校验权限…"
                                  : isCardResolving
                                    ? "正在刷新…"
                                    : msg.content.grantType === "SPACE_FILE"
                                      ? "打开空间文件区 →"
                                      : "查看空投并下载 →"}
                              </span>
                            </div>
                          </button>
                        ) : (
                          <div className="chat-card-pending-box">
                            <span>
                              正在校验
                              {msg.content.grantType === "SPACE_FILE" ? "空间文件" : "独立空投"}
                              访问权限…
                            </span>
                            {!isCardResolving && (
                              <Button
                                className="secondary workbench-btn-xs"
                                onClick={() => {
                                  void resolveCardForOpen(msg.messageId);
                                }}
                              >
                                重新解析
                              </Button>
                            )}
                          </div>
                        )}
                      </>
                    )}
                  </div>
                )}

                {msg.content.kind === "CODE_COMMENT_REF" && (
                  <div className="chat-resource-block">
                    {isDeniedCard && cardView && cardView.state !== "AVAILABLE" ? (
                      <ResourceStateCard state={cardView.state} />
                    ) : (
                      <>
                        {msg.content.purpose && (
                          <p className="chat-msg-text">{msg.content.purpose}</p>
                        )}
                        {cardView?.state === "AVAILABLE" ? (
                          <button
                            type="button"
                            className="chat-resource-card-btn is-comment-card"
                            disabled={openingCardId === msg.messageId}
                            onClick={() => {
                              void handleOpenCard(msg);
                            }}
                          >
                            <div className="chat-resource-card-top">
                              <span className="chat-resource-kind">
                                <FileCode2 size={14} />
                                代码批注
                              </span>
                              <span className="chat-resource-project">
                                {projectsById.get(msg.content.projectId)?.name ??
                                  shortId(msg.content.projectId)}
                              </span>
                            </div>
                            <strong className="chat-resource-label">
                              {cardView.label}
                            </strong>
                            <div className="chat-resource-card-foot">
                              <code>{shortId(msg.content.commentId)}</code>
                              <span>
                                {openingCardId === msg.messageId
                                  ? "正在校验权限…"
                                  : isCardResolving
                                    ? "正在刷新…"
                                    : "定位到双点 Diff 代码行 →"}
                              </span>
                            </div>
                          </button>
                        ) : (
                          <div className="chat-card-pending-box">
                            <span>正在校验代码批注访问权限…</span>
                            {!isCardResolving && (
                              <Button
                                className="secondary workbench-btn-xs"
                                onClick={() => {
                                  void resolveCardForOpen(msg.messageId);
                                }}
                              >
                                重新解析
                              </Button>
                            )}
                          </div>
                        )}
                      </>
                    )}
                  </div>
                )}

                {msg.discussion && (
                  <div className="chat-discussion-bar">
                    <div className="chat-discussion-status-row">
                      <span
                        className={`chat-discussion-pill status-${msg.discussion.status.toLowerCase()}`}
                      >
                        {msg.discussion.status === "RESOLVED" ? (
                          <CheckCircle2 size={12} />
                        ) : (
                          <CircleDot size={12} />
                        )}
                        {msg.discussion.status === "RESOLVED"
                          ? "已处理 (RESOLVED)"
                          : "进行中 (OPEN)"}
                      </span>
                      <span className="chat-discussion-note">
                        标记已处理不代表代码已合并
                      </span>
                    </div>

                    {msg.discussion.status === "RESOLVED" &&
                      msg.discussion.conclusionMsgId && (
                        <button
                          type="button"
                          className="chat-conclusion-chip"
                          onClick={() =>
                            scrollToMessage(msg.discussion!.conclusionMsgId!)
                          }
                        >
                          结论回复：
                          {conclusionMsg
                            ? `${senderLabel(conclusionMsg.senderId)} — ${messageSnippet(
                                conclusionMsg,
                                cardViews[conclusionMsg.messageId],
                              )}`
                            : `#${shortId(msg.discussion.conclusionMsgId)}`}
                        </button>
                      )}

                    {canControlDiscussion(msg) &&
                      resolvingDiscussionMsgId === msg.messageId && (
                        <div className="chat-resolve-box">
                          <label className="field">
                            选择结论回复（可选）
                            <select
                              value={selectedConclusionId}
                              disabled={discussionBusyId === msg.messageId}
                              onChange={(e) => setSelectedConclusionId(e.target.value)}
                            >
                              <option value="">不指定结论回复</option>
                              {rootReplies.map((reply) => (
                                <option key={reply.messageId} value={reply.messageId}>
                                  #{reply.seq} {senderLabel(reply.senderId)}:{" "}
                                  {messageSnippet(reply, cardViews[reply.messageId])}
                                </option>
                              ))}
                            </select>
                          </label>
                          <p className="chat-resolve-warn">
                            提示：标记已处理不代表代码已合并。
                          </p>
                          <div className="actions">
                            <Button
                              className="secondary workbench-btn-xs"
                              disabled={discussionBusyId === msg.messageId}
                              onClick={() => {
                                setResolvingDiscussionMsgId(null);
                                setSelectedConclusionId("");
                              }}
                            >
                              取消
                            </Button>
                            <Button
                              className="primary workbench-btn-xs"
                              disabled={discussionBusyId === msg.messageId}
                              onClick={() => {
                                void submitDiscussionChange(
                                  msg.messageId,
                                  "RESOLVED",
                                  selectedConclusionId || null,
                                );
                              }}
                            >
                              {discussionBusyId === msg.messageId
                                ? "提交中…"
                                : "确认标记已处理"}
                            </Button>
                          </div>
                        </div>
                      )}
                  </div>
                )}

                <footer className="chat-msg-actions">
                  {canSend && (
                    <button
                      type="button"
                      className="chat-msg-action-btn"
                      onClick={() => {
                        const targetRoot = msg.replyToMsgId ?? msg.messageId;
                        setReplyTargetId(targetRoot);
                        composerInputRef.current?.focus();
                      }}
                    >
                      <Reply size={12} /> 回复
                    </button>
                  )}

                  {canControlDiscussion(msg) &&
                    msg.discussion?.status === "OPEN" &&
                    resolvingDiscussionMsgId !== msg.messageId && (
                      <button
                        type="button"
                        className="chat-msg-action-btn"
                        disabled={discussionBusyId === msg.messageId}
                        onClick={() => {
                          setResolvingDiscussionMsgId(msg.messageId);
                          setSelectedConclusionId("");
                          setDiscussionError(null);
                        }}
                      >
                        <CheckCircle2 size={12} /> 标记已处理
                      </button>
                    )}

                  {canControlDiscussion(msg) && msg.discussion?.status === "RESOLVED" && (
                    <button
                      type="button"
                      className="chat-msg-action-btn"
                      disabled={discussionBusyId === msg.messageId}
                      onClick={() => {
                        void submitDiscussionChange(msg.messageId, "OPEN", null);
                      }}
                    >
                      重新打开
                    </button>
                  )}
                </footer>
              </article>
            );
          })}

          {pendingMessages.map((item) => (
            <div
              key={item.clientMsgId}
              className={`chat-msg-card is-self is-pending status-${item.status}`}
            >
              <header className="chat-msg-head">
                <strong>{senderLabel(currentUserId)}</strong>
                <span className="chat-pending-tag">
                  {item.status === "sending"
                    ? "发送中…"
                    : item.status === "acked"
                      ? `已确认 #${item.seq ?? ""}`
                      : "发送失败"}
                </span>
              </header>
              {item.payload.content.kind === "TEXT" ? (
                <p className="chat-msg-text">{item.payload.content.text}</p>
              ) : (
                <div className="chat-pending-ref">
                  <code>
                    {item.payload.content.kind === "COMMIT_REF"
                      ? `Commit ${shortId(item.payload.content.commitOid)}`
                      : item.payload.content.kind === "WIP_REF"
                        ? `WIP ${shortId(item.payload.content.snapshotId)}`
                        : item.payload.content.kind === "FILE_REF"
                          ? `File ${shortId(item.payload.content.targetId)}`
                          : `Comment ${shortId(item.payload.content.commentId)}`}
                  </code>
                  {item.payload.content.purpose && (
                    <p className="chat-msg-text">{item.payload.content.purpose}</p>
                  )}
                </div>
              )}
              {item.status === "failed" && (
                <div className="chat-pending-fail-row">
                  <span className="chat-inline-error">
                    {item.errorMessage ?? "发送失败"}
                  </span>
                  <div className="actions">
                    <Button
                      className="secondary workbench-btn-xs"
                      onClick={() => {
                        void retryPendingMessage(item.clientMsgId);
                      }}
                    >
                      <RefreshCw size={12} /> 重试
                    </Button>
                    <Button
                      className="secondary workbench-btn-xs"
                      onClick={() => discardPendingMessage(item.clientMsgId)}
                    >
                      <Trash2 size={12} /> 放弃
                    </Button>
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>

        {showScrollBottom && (
          <button
            type="button"
            className="chat-scroll-bottom-btn"
            onClick={scrollToBottom}
          >
            <ArrowDown size={13} /> 回到底部
          </button>
        )}
      </div>

      <footer className="chat-composer-area">
        {!canSend ? (
          <div className="chat-readonly-notice" role="status">
            当前为只读成员（VIEWER），仅可查看讨论并打开有权限的代码节点卡片。
          </div>
        ) : (
          <form className="chat-composer-form" onSubmit={handleSubmitMessage}>
            {replyTarget && (
              <div className="chat-composer-banner">
                <span>
                  <Reply size={13} /> 回复{" "}
                  <strong>{senderLabel(replyTarget.senderId)}</strong>：
                  {messageSnippet(replyTarget, cardViews[replyTarget.messageId])}
                </span>
                <button
                  type="button"
                  aria-label="取消回复"
                  onClick={() => setReplyTargetId(null)}
                >
                  <X size={13} />
                </button>
              </div>
            )}

            {activeDraftQuote && (
              <div className="chat-composer-banner is-quote">
                <span>
                  {activeDraftQuote.kind === "COMMIT_REF" ? (
                    <GitCommitHorizontal size={13} />
                  ) : activeDraftQuote.kind === "WIP_REF" ? (
                    <GitPullRequestDraft size={13} />
                  ) : activeDraftQuote.grantType === "SPACE_FILE" ? (
                    <FolderOpen size={13} />
                  ) : (
                    <Send size={13} />
                  )}
                  <strong>
                    {activeDraftQuote.kind === "COMMIT_REF"
                      ? "提交卡片"
                      : activeDraftQuote.kind === "WIP_REF"
                        ? "WIP 快照卡片"
                        : activeDraftQuote.grantType === "SPACE_FILE"
                          ? "空间文件卡片"
                          : "独立空投卡片"}
                  </strong>
                  ：{activeDraftQuote.title}
                </span>
                <button
                  type="button"
                  aria-label="移除引用卡片"
                  onClick={clearCurrentQuote}
                >
                  <X size={13} />
                </button>
              </div>
            )}

            {showFilePicker && (
              <div className="chat-file-picker" role="region" aria-label="选择要引用的文件或空投">
                <div className="chat-file-picker-head">
                  <div className="chat-file-picker-tabs" role="tablist">
                    <button
                      type="button"
                      role="tab"
                      aria-selected={filePickerTab === "SPACE_FILE"}
                      className={`chat-file-picker-tab ${
                        filePickerTab === "SPACE_FILE" ? "active" : ""
                      }`}
                      onClick={() => setFilePickerTab("SPACE_FILE")}
                    >
                      空间文件
                    </button>
                    <button
                      type="button"
                      role="tab"
                      aria-selected={filePickerTab === "AIRDROP_SHARE"}
                      className={`chat-file-picker-tab ${
                        filePickerTab === "AIRDROP_SHARE" ? "active" : ""
                      }`}
                      onClick={() => setFilePickerTab("AIRDROP_SHARE")}
                    >
                      独立空投
                    </button>
                  </div>
                  <button
                    type="button"
                    aria-label="关闭文件选择器"
                    className="chat-msg-action-btn"
                    onClick={() => setShowFilePicker(false)}
                  >
                    <X size={13} />
                  </button>
                </div>

                {filePickerTab === "SPACE_FILE" && (
                  <div className="chat-file-picker-body">
                    {spaceFilesPickerQuery.isPending && (
                      <span className="muted">加载空间文件中…</span>
                    )}
                    {spaceFilesPickerQuery.isError && (
                      <span className="chat-inline-error">
                        {filesErrorText(spaceFilesPickerQuery.error)}
                      </span>
                    )}
                    {spaceFilesPickerQuery.isSuccess &&
                      spaceFilesPickerQuery.data.items.length === 0 && (
                        <span className="muted">空间文件区暂无文件可引用。</span>
                      )}
                    {spaceFilesPickerQuery.isSuccess &&
                      spaceFilesPickerQuery.data.items.length > 0 && (
                        <ul className="chat-file-picker-list">
                          {spaceFilesPickerQuery.data.items.map((file) => (
                            <li key={file.fileId}>
                              <button
                                type="button"
                                className="chat-file-picker-item"
                                onClick={() =>
                                  applyFileQuote({
                                    grantType: "SPACE_FILE",
                                    targetId: file.fileId,
                                    title: file.fileName,
                                  })
                                }
                              >
                                <strong>{file.fileName}</strong>
                                <span>{formatBytes(file.sizeBytes)}</span>
                              </button>
                            </li>
                          ))}
                        </ul>
                      )}
                  </div>
                )}

                {filePickerTab === "AIRDROP_SHARE" && (
                  <div className="chat-file-picker-body">
                    <p className="chat-file-picker-warn">
                      群聊引用独立空投卡片绝不会扩大接收人权限，非指定接收人仅显示无权占位。
                    </p>
                    <div className="chat-file-picker-manual">
                      <input
                        type="text"
                        placeholder="粘贴空投分享 ID…"
                        value={manualAirdropId}
                        onChange={(e) => setManualAirdropId(e.target.value)}
                      />
                      <Button
                        className="secondary workbench-btn-xs"
                        disabled={!manualAirdropId.trim()}
                        onClick={() => {
                          const shareId = manualAirdropId.trim();
                          if (!shareId) return;
                          applyFileQuote({
                            grantType: "AIRDROP_SHARE",
                            targetId: shareId,
                            title: `空投 ${shortId(shareId)}`,
                          });
                        }}
                      >
                        引用 ID
                      </Button>
                    </div>
                    {airdropsPickerQuery.isPending && (
                      <span className="muted">加载可用空投中…</span>
                    )}
                    {airdropsPickerQuery.isError && (
                      <span className="chat-inline-error">
                        {filesErrorText(airdropsPickerQuery.error)}
                      </span>
                    )}
                    {airdropsPickerQuery.isSuccess &&
                      airdropsPickerQuery.data.items.filter((s) => !s.revoked).length > 0 && (
                        <ul className="chat-file-picker-list">
                          {airdropsPickerQuery.data.items
                            .filter((s) => !s.revoked)
                            .map((share) => (
                              <li key={share.shareId}>
                                <button
                                  type="button"
                                  className="chat-file-picker-item"
                                  onClick={() =>
                                    applyFileQuote({
                                      grantType: "AIRDROP_SHARE",
                                      targetId: share.shareId,
                                      title: share.title,
                                    })
                                  }
                                >
                                  <strong>{share.title}</strong>
                                  <span>{shortId(share.shareId)}</span>
                                </button>
                              </li>
                            ))}
                        </ul>
                      )}
                  </div>
                )}
              </div>
            )}

            <textarea
              ref={composerInputRef}
              rows={3}
              maxLength={activeDraftQuote ? 4000 : 8000}
              value={composerText}
              placeholder={
                activeDraftQuote
                  ? "补充引用说明或讨论问题（可选）…"
                  : "输入空间讨论内容…（Ctrl/Cmd + Enter 发送）"
              }
              onChange={(e) => setComposerText(e.target.value)}
              onKeyDown={(e) => {
                if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
                  e.preventDefault();
                  void submitComposer();
                }
              }}
            />

            <div className="chat-composer-footer">
              <div className="chat-composer-left-tools">
                <Button
                  className="secondary workbench-btn-xs"
                  onClick={() => setShowFilePicker((prev) => !prev)}
                >
                  <Paperclip size={12} /> 引用文件
                </Button>
                <span className="chat-composer-hint">
                  {activeDraftQuote
                    ? activeDraftQuote.kind === "FILE_REF"
                      ? activeDraftQuote.grantType === "SPACE_FILE"
                        ? "发送空间文件引用卡片"
                        : "发送独立空投卡片（不扩大权限）"
                      : "发送不可变代码引用卡片"
                    : "支持文本、代码与文件引用"}
                </span>
              </div>
              <Button
                type="submit"
                className="primary workbench-btn-xs"
                disabled={!activeDraftQuote && !composerText.trim()}
              >
                <Send size={13} /> 发送
              </Button>
            </div>
          </form>
        )}
      </footer>
    </aside>
  );
}

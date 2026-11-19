import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import type {
  AirdropDeliveryState,
  AirdropExpiryPreset,
  AirdropShareDto,
  FileRecordDto,
  FriendSummary,
} from "@orbitspace/shared-types";
import {
  AlertCircle,
  Ban,
  Check,
  CheckCircle2,
  Clock,
  Copy,
  Download,
  Eye,
  File as FileIcon,
  Inbox,
  Info,
  Pause,
  Play,
  RefreshCw,
  RotateCcw,
  Send,
  ShieldAlert,
  Upload,
  Users,
  X,
  Zap,
} from "lucide-react";
import { Link, useSearchParams } from "react-router-dom";
import { queryClient } from "../../shared/api/access";
import { useAuth } from "../../shared/auth/session";
import { Button, Loading } from "../../shared/ui/UiComponents";
import { friendsKey, listFriends } from "../friends/friendsApi";
import { copyToClipboard, formatDateTime, makeIdempotencyKey } from "../space/spaceApi";
import {
  airdropsKey,
  createAirdrop,
  downloadAuthorizedFile,
  filesErrorText,
  formatBytes,
  getAirdrop,
  listAirdrops,
  revokeAirdrop,
  revokeAirdropRecipient,
  shortDigest,
} from "./filesApi";
import { useChunkedUpload, type UploadTaskItem } from "./useChunkedUpload";
import "./files.css";

type AirdropTab = "SENT" | "RECEIVED";

const EXPIRY_PRESET_LABELS: Record<AirdropExpiryPreset, string> = {
  H24: "24 小时",
  D7: "7 天",
  PERMANENT: "长期有效",
};

const DELIVERY_STATE_LABELS: Record<AirdropDeliveryState, { label: string; tone: string }> = {
  DELIVERED: { label: "已投递", tone: "muted" },
  VIEWED: { label: "已查看", tone: "info" },
  DOWNLOAD_STARTED: { label: "已开始下载", tone: "ok" },
};

function isShareExpired(share: AirdropShareDto): boolean {
  if (!share.expiresAt) return false;
  const ts = Date.parse(share.expiresAt);
  return Number.isFinite(ts) && ts <= Date.now();
}

function uploadPhaseLabel(task: UploadTaskItem): { label: string; tone: string } {
  switch (task.phase) {
    case "hashing":
      return { label: `计算分片哈希 (${task.hashProgress}%)`, tone: "info" };
    case "creating_session":
      return { label: "预检个人配额…", tone: "info" };
    case "uploading":
      return { label: `分片上传 (${task.uploadedPartsCount}/${task.totalParts})`, tone: "info" };
    case "paused":
      return { label: `已暂停 (${task.uploadedPartsCount}/${task.totalParts})`, tone: "warn" };
    case "completing":
      return { label: task.instantMatchAvailable ? "本人授权秒传校验…" : "合并校验中…", tone: "info" };
    case "completed":
      return { label: task.instantMatchUsed ? "已就绪（本人授权秒传）" : "已就绪", tone: "ok" };
    case "error":
      return { label: "上传失败", tone: "danger" };
  }
}

export function AirdropPage() {
  const currentUserId = useAuth((s) => s.session?.userId ?? null);
  const [searchParams, setSearchParams] = useSearchParams();
  const initialRecipientId = searchParams.get("recipientId")?.trim() || null;
  const inspectedShareIdParam = searchParams.get("shareId")?.trim() || null;

  const [activeTab, setActiveTab] = useState<AirdropTab>(
    inspectedShareIdParam ? "RECEIVED" : "SENT",
  );
  const [stagedFiles, setStagedFiles] = useState<FileRecordDto[]>([]);
  const [selectedFileIds, setSelectedFileIds] = useState<string[]>([]);
  const [selectedRecipientIds, setSelectedRecipientIds] = useState<string[]>(
    initialRecipientId ? [initialRecipientId] : [],
  );
  const [expiryPreset, setExpiryPreset] = useState<AirdropExpiryPreset>("D7");
  const [limitDownloads, setLimitDownloads] = useState(false);
  const [maxDownloadsInput, setMaxDownloadsInput] = useState(3);
  const [noteInput, setNoteInput] = useState("");

  const [confirmRevokeShareId, setConfirmRevokeShareId] = useState<string | null>(null);
  const [copiedShareId, setCopiedShareId] = useState<string | null>(null);
  const [downloadingKey, setDownloadingKey] = useState<string | null>(null);
  const [downloadProgressLabel, setDownloadProgressLabel] = useState<string | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [inspectedShare, setInspectedShare] = useState<AirdropShareDto | null>(null);
  const [inspectError, setInspectError] = useState<string | null>(null);
  const [inspectLoading, setInspectLoading] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const createIdemRef = useRef<string>(makeIdempotencyKey());
  const revokeIdemRef = useRef<Map<string, string>>(new Map());

  const friendsQuery = useQuery({
    queryKey: friendsKey,
    queryFn: ({ signal }) => listFriends(signal),
  });

  const sentQuery = useQuery({
    queryKey: airdropsKey("SENT"),
    queryFn: ({ signal }) => listAirdrops("SENT", signal),
  });

  const receivedQuery = useQuery({
    queryKey: airdropsKey("RECEIVED"),
    queryFn: ({ signal }) => listAirdrops("RECEIVED", signal),
  });

  const friends = friendsQuery.data?.items ?? [];
  const friendsById = useMemo(() => {
    const map = new Map<string, FriendSummary>();
    for (const f of friends) {
      map.set(f.userId, f);
    }
    return map;
  }, [friends]);

  // Apply recipientId from URL when coming from FriendsPage
  useEffect(() => {
    if (!initialRecipientId) return;
    if (friends.some((f) => f.userId === initialRecipientId)) {
      setSelectedRecipientIds((prev) =>
        prev.includes(initialRecipientId) ? prev : [...prev, initialRecipientId],
      );
    }
  }, [friends, initialRecipientId]);

  // Auto-inspect deep-linked shareId (e.g. opened from a chat FILE_REF card)
  useEffect(() => {
    if (!inspectedShareIdParam) {
      setInspectedShare(null);
      setInspectError(null);
      return;
    }
    let active = true;
    setInspectLoading(true);
    setInspectError(null);
    void getAirdrop(inspectedShareIdParam)
      .then((share) => {
        if (!active) return;
        setInspectedShare(share);
        setActiveTab(share.senderUserId === currentUserId ? "SENT" : "RECEIVED");
        void queryClient.invalidateQueries({ queryKey: airdropsKey("RECEIVED") });
      })
      .catch((err) => {
        if (!active) return;
        setInspectedShare(null);
        setInspectError(filesErrorText(err));
      })
      .finally(() => {
        if (active) setInspectLoading(false);
      });
    return () => {
      active = false;
    };
  }, [currentUserId, inspectedShareIdParam]);

  const {
    tasks,
    enqueueFiles,
    pauseTask,
    resumeTask,
    retryTask,
    removeTask,
    clearCompleted,
  } = useChunkedUpload({
    targetScope: "AIRDROP_SHARE",
    onCompleted: (record) => {
      setStagedFiles((prev) =>
        prev.some((f) => f.fileId === record.fileId) ? prev : [record, ...prev],
      );
      setSelectedFileIds((prev) =>
        prev.includes(record.fileId) ? prev : [...prev, record.fileId],
      );
      createIdemRef.current = makeIdempotencyKey();
    },
  });

  const refreshAllAirdrops = () => {
    void queryClient.invalidateQueries({ queryKey: airdropsKey("SENT") });
    void queryClient.invalidateQueries({ queryKey: airdropsKey("RECEIVED") });
  };

  const createAirdropMutation = useMutation({
    mutationFn: (payload: { fileIds: string[]; recipientUserIds: string[] }) => {
      const trimmedNote = noteInput.trim();
      const maxDl = limitDownloads ? Math.max(1, Math.floor(maxDownloadsInput)) : null;
      return createAirdrop(
        {
          fileIds: payload.fileIds,
          recipientUserIds: payload.recipientUserIds,
          expiryPreset,
          maxDownloadsPerRecipient: maxDl,
          ...(trimmedNote ? { note: trimmedNote } : {}),
        },
        createIdemRef.current,
      );
    },
    onSuccess: (created, variables) => {
      createIdemRef.current = makeIdempotencyKey();
      const usedIds = new Set(variables.fileIds);
      setSelectedFileIds((prev) => prev.filter((id) => !usedIds.has(id)));
      setStagedFiles((prev) => prev.filter((f) => !usedIds.has(f.fileId)));
      setNoteInput("");
      setActionError(null);
      setActionNotice(`独立文件空投「${created.title}」已发送给 ${created.recipients.length} 位好友。`);
      setActiveTab("SENT");
      refreshAllAirdrops();
    },
    onError: (err) => {
      setActionNotice(null);
      setActionError(filesErrorText(err));
    },
  });

  const revokeRecipientMutation = useMutation({
    mutationFn: ({
      shareId,
      recipientUserId,
      key,
    }: {
      shareId: string;
      recipientUserId: string;
      key: string;
    }) => revokeAirdropRecipient(shareId, recipientUserId, key),
    onSuccess: (updated, vars) => {
      revokeIdemRef.current.delete(`recipient:${vars.shareId}:${vars.recipientUserId}`);
      setActionError(null);
      setActionNotice("已立即撤销该接收人的访问与下载权限（不影响其他接收人）。");
      if (inspectedShare?.shareId === updated.shareId) {
        setInspectedShare(updated);
      }
      refreshAllAirdrops();
    },
    onError: (err) => {
      setActionNotice(null);
      setActionError(filesErrorText(err));
    },
  });

  const revokeShareMutation = useMutation({
    mutationFn: ({ shareId, key }: { shareId: string; key: string }) =>
      revokeAirdrop(shareId, key),
    onSuccess: (updated, vars) => {
      revokeIdemRef.current.delete(`share:${vars.shareId}`);
      setConfirmRevokeShareId(null);
      setActionError(null);
      setActionNotice("已立即撤回整条空投分享，所有接收人的未完成下载会话已阻断。");
      if (inspectedShare?.shareId === updated.shareId) {
        setInspectedShare(updated);
      }
      refreshAllAirdrops();
    },
    onError: (err) => {
      setActionNotice(null);
      setActionError(filesErrorText(err));
    },
  });

  const getRevokeKey = (tag: string): string => {
    let key = revokeIdemRef.current.get(tag);
    if (!key) {
      key = makeIdempotencyKey();
      revokeIdemRef.current.set(tag, key);
    }
    return key;
  };

  const handleInspectShare = async (shareId: string) => {
    setActionError(null);
    setInspectLoading(true);
    setInspectError(null);
    try {
      const fresh = await getAirdrop(shareId);
      setInspectedShare(fresh);
      refreshAllAirdrops();
    } catch (err) {
      setInspectError(filesErrorText(err));
    } finally {
      setInspectLoading(false);
    }
  };

  const handleDownloadAirdropFile = async (shareId: string, fileId: string) => {
    const key = `${shareId}:${fileId}`;
    if (downloadingKey) return;
    setActionError(null);
    setDownloadingKey(key);
    setDownloadProgressLabel("创建下载会话…");
    try {
      const session = await downloadAuthorizedFile({
        grantType: "AIRDROP_SHARE",
        grantId: shareId,
        fileId,
        onSessionCreated: () => {
          // Immediately refresh counts because creating the session deducts 1 slot atomically.
          refreshAllAirdrops();
        },
        onProgress: (loaded, total) => {
          if (total > 0) {
            const pct = Math.min(100, Math.round((loaded / total) * 100));
            setDownloadProgressLabel(`下载中 ${pct}%`);
          } else {
            setDownloadProgressLabel("已完成");
          }
        },
      });
      if (session.remainingDownloads !== null) {
        setActionNotice(
          `文件「${session.fileName}」下载完成（剩余可新建下载会话次数：${session.remainingDownloads} 次）。`,
        );
      } else {
        setActionNotice(`文件「${session.fileName}」下载完成。`);
      }
      refreshAllAirdrops();
      if (inspectedShare?.shareId === shareId) {
        void getAirdrop(shareId)
          .then((fresh) => setInspectedShare(fresh))
          .catch(() => {});
      }
    } catch (err) {
      setActionError(filesErrorText(err));
      refreshAllAirdrops();
    } finally {
      setDownloadingKey(null);
      setDownloadProgressLabel(null);
    }
  };

  const handleCreateSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (createAirdropMutation.isPending) return;
    if (selectedFileIds.length === 0 || selectedRecipientIds.length === 0) return;
    createAirdropMutation.mutate({
      fileIds: [...selectedFileIds],
      recipientUserIds: [...selectedRecipientIds],
    });
  };

  const toggleFileSelection = (fileId: string) => {
    createIdemRef.current = makeIdempotencyKey();
    setSelectedFileIds((prev) =>
      prev.includes(fileId) ? prev.filter((id) => id !== fileId) : [...prev, fileId],
    );
  };

  const toggleRecipientSelection = (userId: string) => {
    createIdemRef.current = makeIdempotencyKey();
    setSelectedRecipientIds((prev) =>
      prev.includes(userId) ? prev.filter((id) => id !== userId) : [...prev, userId],
    );
  };

  const resolveUserName = (userId: string): string => {
    if (userId === currentUserId) return "我";
    return friendsById.get(userId)?.displayName ?? `用户 ${userId.slice(0, 8)}`;
  };

  const sentShares = sentQuery.data?.items ?? [];
  const receivedShares = receivedQuery.data?.items ?? [];

  return (
    <section className="content">
      <header className="page-head space-home-head">
        <div>
          <h1>文件空投</h1>
          <p className="subtitle">面向当前有效好友的独立文件投递、下载会话计数与即时撤回</p>
        </div>
        <div className="actions">
          <Button
            className="secondary"
            disabled={sentQuery.isFetching || receivedQuery.isFetching}
            onClick={() => {
              void sentQuery.refetch();
              void receivedQuery.refetch();
              void friendsQuery.refetch();
            }}
          >
            <RefreshCw size={15} /> 刷新
          </Button>
          <Link className="button secondary" to="/friends">
            <Users size={15} /> 管理好友
          </Link>
        </div>
      </header>

      {/* Mandatory Product Policy Banners */}
      <div className="airdrop-policies">
        <div className="files-policy-box" role="note">
          <Info size={16} />
          <div>
            <strong>独立空投核心规则：</strong>
            <ul className="files-policy-list">
              <li>
                <strong>长期有效说明：</strong>
                长期有效表示无预设到期时间，仍受所有者删除、账号状态和存储配额约束。
              </li>
              <li>
                <strong>下载次数计费口径：</strong>
                下载次数按新建下载会话计费：每次新建下载会话扣减 1 次额度，同一会话内断网重试或 Range 断点续传不重复扣减。
              </li>
              <li>
                <strong>独立授权边界：</strong>
                独立空投仅面向当前有效好友且与空间权限完全隔离；将空投卡片引用到空间主群聊绝不会扩大接收人访问权限。
              </li>
            </ul>
          </div>
        </div>
      </div>

      {actionNotice && (
        <div className="success" role="status" style={{ marginTop: 0, marginBottom: 16 }}>
          <div className="section-head" style={{ width: "100%", marginBottom: 0 }}>
            <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
              <CheckCircle2 size={16} /> {actionNotice}
            </span>
            <Button
              className="icon-button secondary"
              aria-label="关闭通知"
              onClick={() => setActionNotice(null)}
            >
              <X size={14} />
            </Button>
          </div>
        </div>
      )}

      {actionError && (
        <div className="alert" role="alert" style={{ marginBottom: 16 }}>
          <AlertCircle size={17} />
          <span>{actionError}</span>
        </div>
      )}

      {/* Inspected / Deep-linked Share Panel */}
      {(inspectLoading || inspectError || inspectedShare) && (
        <section className="surface airdrop-inspect-card" aria-label="空投详情检查">
          <div className="section-head">
            <h2>
              <Eye size={16} style={{ display: "inline", verticalAlign: "-3px", marginRight: 6 }} />
              空投分享详情
            </h2>
            <Button
              className="secondary workbench-btn-xs"
              onClick={() => {
                setInspectedShare(null);
                setInspectError(null);
                if (inspectedShareIdParam) {
                  const next = new URLSearchParams(searchParams);
                  next.delete("shareId");
                  setSearchParams(next, { replace: true });
                }
              }}
            >
              <X size={13} /> 关闭详情
            </Button>
          </div>

          {inspectLoading && <Loading label="正在实时校验空投授权并加载详情…" />}

          {inspectError && (
            <div className="alert" role="alert">
              <ShieldAlert size={17} />
              <span>{inspectError}</span>
            </div>
          )}

          {inspectedShare && (
            <div style={{ display: "grid", gap: 10 }}>
              <div className="airdrop-share-head">
                <div>
                  <strong>{inspectedShare.title}</strong>
                  <div className="airdrop-share-meta">
                    <code>{inspectedShare.shareId}</code>
                    <span>·</span>
                    <span>发送者：{resolveUserName(inspectedShare.senderUserId)}</span>
                    <span>·</span>
                    <span>
                      有效期：{EXPIRY_PRESET_LABELS[inspectedShare.expiryPreset]}
                      {inspectedShare.expiresAt
                        ? `（至 ${formatDateTime(inspectedShare.expiresAt)}）`
                        : "（无预设到期时间）"}
                    </span>
                  </div>
                </div>
                {inspectedShare.revoked ? (
                  <span className="files-pill tone-danger">已撤回</span>
                ) : isShareExpired(inspectedShare) ? (
                  <span className="files-pill tone-warn">已过期</span>
                ) : (
                  <span className="files-pill tone-ok">生效中</span>
                )}
              </div>

              <ul className="airdrop-sublist">
                {inspectedShare.files.map((item) => {
                  const dlKey = `${inspectedShare.shareId}:${item.fileId}`;
                  const myRecipient = inspectedShare.recipients.find(
                    (r) => r.recipientUserId === currentUserId,
                  );
                  const canDownload =
                    Boolean(myRecipient) &&
                    !myRecipient?.revoked &&
                    !inspectedShare.revoked &&
                    !isShareExpired(inspectedShare) &&
                    (myRecipient?.remainingDownloads === null ||
                      (myRecipient?.remainingDownloads ?? 0) > 0);

                  return (
                    <li key={item.fileId} className="airdrop-file-row">
                      <span>
                        <FileIcon
                          size={13}
                          style={{ display: "inline", verticalAlign: "-2px", marginRight: 5 }}
                        />
                        <strong>{item.fileName}</strong> ({formatBytes(item.sizeBytes)}) ·{" "}
                        <code>{shortDigest(item.sha256)}</code>
                      </span>
                      {myRecipient && (
                        <Button
                          className="primary workbench-btn-xs"
                          disabled={!canDownload || Boolean(downloadingKey)}
                          onClick={() => {
                            void handleDownloadAirdropFile(inspectedShare.shareId, item.fileId);
                          }}
                        >
                          <Download size={13} />
                          {downloadingKey === dlKey
                            ? (downloadProgressLabel ?? "下载中…")
                            : "下载文件"}
                        </Button>
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          )}
        </section>
      )}

      <div className="airdrop-layout">
        {/* Left Column: Create Independent Airdrop */}
        <section className="surface" aria-label="发起新空投">
          <div className="section-head">
            <h2>发起新空投</h2>
            <Send size={16} />
          </div>

          <form className="airdrop-form-stack" onSubmit={handleCreateSubmit}>
            {/* Step 1: Upload Files via Chunked Worker */}
            <div className="airdrop-step-block">
              <div className="section-head" style={{ marginBottom: 0 }}>
                <span className="airdrop-step-label">1. 上传待空投文件（单/多文件）</span>
                <div className="actions">
                  {tasks.some((t) => t.phase === "completed") && (
                    <Button className="secondary workbench-btn-xs" onClick={clearCompleted}>
                      清空队列
                    </Button>
                  )}
                  <Button
                    className="secondary workbench-btn-xs"
                    onClick={() => fileInputRef.current?.click()}
                  >
                    <Upload size={13} /> 选择文件
                  </Button>
                </div>
              </div>
              <p className="files-upload-hint">
                占用发送者个人配额；Web Worker 本地分片计算 SHA-256 并支持断点续传，秒传严格限定在本人已持有且获授权的文件范围内。
              </p>

              <input
                ref={fileInputRef}
                type="file"
                multiple
                style={{ display: "none" }}
                onChange={(e) => {
                  if (e.target.files && e.target.files.length > 0) {
                    enqueueFiles(e.target.files);
                    e.target.value = "";
                  }
                }}
              />

              {tasks.length > 0 && (
                <ul className="files-queue-list">
                  {tasks.map((task) => {
                    const meta = uploadPhaseLabel(task);
                    const pct = task.phase === "hashing" ? task.hashProgress : task.uploadProgress;
                    return (
                      <li key={task.localId} className={`files-queue-item phase-${task.phase}`}>
                        <div className="files-queue-top">
                          <span className="files-queue-name">
                            <FileIcon size={13} />
                            {task.fileName} ({formatBytes(task.fileSizeBytes)})
                          </span>
                          <div className="actions">
                            <span className={`files-pill tone-${meta.tone}`}>
                              {task.instantMatchUsed && <Zap size={11} />}
                              {meta.label}
                            </span>
                            {(task.phase === "hashing" ||
                              task.phase === "creating_session" ||
                              task.phase === "uploading") && (
                              <Button
                                className="secondary workbench-btn-xs"
                                onClick={() => pauseTask(task.localId)}
                              >
                                <Pause size={11} /> 暂停
                              </Button>
                            )}
                            {task.phase === "paused" && (
                              <Button
                                className="primary workbench-btn-xs"
                                onClick={() => resumeTask(task.localId)}
                              >
                                <Play size={11} /> 继续
                              </Button>
                            )}
                            {task.phase === "error" && task.canRetry && (
                              <Button
                                className="primary workbench-btn-xs"
                                onClick={() => retryTask(task.localId)}
                              >
                                <RotateCcw size={11} /> 重试
                              </Button>
                            )}
                            <Button
                              className="icon-button secondary"
                              aria-label="移除队列项"
                              onClick={() => removeTask(task.localId)}
                            >
                              <X size={12} />
                            </Button>
                          </div>
                        </div>
                        <div className="files-progress-track">
                          <div
                            className={`files-progress-fill ${
                              task.phase === "hashing"
                                ? "is-hash"
                                : task.phase === "error"
                                  ? "is-danger"
                                  : ""
                            }`}
                            style={{ width: `${pct}%` }}
                          />
                        </div>
                        {task.errorMessage && (
                          <div className="alert" role="alert" style={{ padding: "6px 9px" }}>
                            <AlertCircle size={13} />
                            <span>{task.errorMessage}</span>
                          </div>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}

              {stagedFiles.length > 0 && (
                <ul className="airdrop-staged-list" aria-label="已上传可投递文件">
                  {stagedFiles.map((file) => (
                    <li key={file.fileId} className="airdrop-check-row">
                      <label>
                        <input
                          type="checkbox"
                          checked={selectedFileIds.includes(file.fileId)}
                          onChange={() => toggleFileSelection(file.fileId)}
                        />
                        <span>
                          <strong>{file.fileName}</strong> ({formatBytes(file.sizeBytes)})
                        </span>
                      </label>
                      <Button
                        className="icon-button secondary"
                        aria-label={`移除文件 ${file.fileName}`}
                        onClick={() => {
                          setStagedFiles((prev) => prev.filter((f) => f.fileId !== file.fileId));
                          setSelectedFileIds((prev) => prev.filter((id) => id !== file.fileId));
                          createIdemRef.current = makeIdempotencyKey();
                        }}
                      >
                        <X size={12} />
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* Step 2: Select 1+ Active Friends */}
            <div className="airdrop-step-block">
              <span className="airdrop-step-label">
                2. 选择接收好友（已选 {selectedRecipientIds.length} 位）
              </span>

              {friendsQuery.isPending && <Loading label="加载好友列表…" />}

              {friendsQuery.isSuccess && friends.length === 0 && (
                <div className="files-empty-box" style={{ padding: "14px 12px" }}>
                  <span>暂无有效好友，独立空投仅可面向当前有效好友投递。</span>
                  <Link className="text-link" to="/friends">
                    前往好友页面添加好友 →
                  </Link>
                </div>
              )}

              {friendsQuery.isSuccess && friends.length > 0 && (
                <ul className="airdrop-friend-picker">
                  {friends.map((friend) => (
                    <li key={friend.userId} className="airdrop-check-row">
                      <label>
                        <input
                          type="checkbox"
                          checked={selectedRecipientIds.includes(friend.userId)}
                          onChange={() => toggleRecipientSelection(friend.userId)}
                        />
                        <span>
                          <strong>{friend.displayName}</strong>{" "}
                          <code className="muted">{friend.userId.slice(0, 8)}</code>
                        </span>
                      </label>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* Step 3: Expiry Preset, Download Limit & Note */}
            <div className="airdrop-step-block">
              <span className="airdrop-step-label">3. 有效期与下载次数限制</span>

              <div className="airdrop-preset-group" role="radiogroup" aria-label="有效期预设">
                {(["H24", "D7", "PERMANENT"] as const).map((preset) => (
                  <button
                    key={preset}
                    type="button"
                    role="radio"
                    aria-checked={expiryPreset === preset}
                    className={`airdrop-preset-btn ${expiryPreset === preset ? "active" : ""}`}
                    onClick={() => {
                      setExpiryPreset(preset);
                      createIdemRef.current = makeIdempotencyKey();
                    }}
                  >
                    {EXPIRY_PRESET_LABELS[preset]}
                  </button>
                ))}
              </div>

              <p
                className="files-upload-hint"
                style={
                  expiryPreset === "PERMANENT"
                    ? { color: "#92400e", fontWeight: 600 }
                    : undefined
                }
              >
                长期有效表示无预设到期时间，仍受所有者删除、账号状态和存储配额约束。
              </p>

              <label className="checkbox" style={{ marginTop: 4 }}>
                <input
                  type="checkbox"
                  checked={limitDownloads}
                  onChange={(e) => {
                    setLimitDownloads(e.target.checked);
                    createIdemRef.current = makeIdempotencyKey();
                  }}
                />
                <span>限制每位接收人可下载次数</span>
              </label>

              {limitDownloads && (
                <label className="field">
                  每位接收人最大下载次数
                  <input
                    type="number"
                    min={1}
                    max={1000}
                    required
                    value={maxDownloadsInput}
                    onChange={(e) => {
                      setMaxDownloadsInput(Math.max(1, Number(e.target.value) || 1));
                      createIdemRef.current = makeIdempotencyKey();
                    }}
                  />
                </label>
              )}

              <p className="files-upload-hint">
                下载次数按新建下载会话计费：每次新建下载会话扣减 1 次额度，同一会话内断网重试或 Range 断点续传不重复扣减。
              </p>

              <label className="field">
                附言说明（可选）
                <textarea
                  rows={2}
                  maxLength={1000}
                  placeholder="简要说明本次空投文件用途…"
                  value={noteInput}
                  onChange={(e) => {
                    setNoteInput(e.target.value);
                    createIdemRef.current = makeIdempotencyKey();
                  }}
                />
              </label>
            </div>

            <Button
              type="submit"
              className="primary"
              disabled={
                createAirdropMutation.isPending ||
                selectedFileIds.length === 0 ||
                selectedRecipientIds.length === 0
              }
            >
              <Send size={15} />
              {createAirdropMutation.isPending ? "正在发起空投…" : "发起独立空投"}
            </Button>
          </form>
        </section>

        {/* Right Column: Sent & Received Airdrops */}
        <section className="surface" aria-label="空投记录列表">
          <div className="airdrop-tabs" role="tablist" aria-label="空投分类">
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === "SENT"}
              className={`airdrop-tab-btn ${activeTab === "SENT" ? "active" : ""}`}
              onClick={() => setActiveTab("SENT")}
            >
              <Send size={14} /> 我发出的空投 ({sentShares.length})
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeTab === "RECEIVED"}
              className={`airdrop-tab-btn ${activeTab === "RECEIVED" ? "active" : ""}`}
              onClick={() => setActiveTab("RECEIVED")}
            >
              <Inbox size={14} /> 我收到的空投 ({receivedShares.length})
            </button>
          </div>

          {activeTab === "SENT" && (
            <>
              {sentQuery.isPending && <Loading label="正在加载发出的空投记录…" />}
              {sentQuery.isError && (
                <div className="alert" role="alert">
                  <AlertCircle size={16} />
                  <span>{filesErrorText(sentQuery.error)}</span>
                </div>
              )}
              {sentQuery.isSuccess && sentShares.length === 0 && (
                <div className="files-empty-box">
                  <Send size={26} />
                  <strong>还没有发出过独立文件空投</strong>
                  <span>在左侧上传文件并选择好友即可发起一次独立空投。</span>
                </div>
              )}
              {sentQuery.isSuccess && sentShares.length > 0 && (
                <ul className="airdrop-share-list">
                  {sentShares.map((share) => {
                    const expired = isShareExpired(share);
                    const isHighlighted = inspectedShare?.shareId === share.shareId;

                    return (
                      <li
                        key={share.shareId}
                        className={`airdrop-share-card ${share.revoked ? "is-revoked" : ""} ${
                          isHighlighted ? "is-highlighted" : ""
                        }`}
                      >
                        <div className="airdrop-share-head">
                          <div style={{ display: "grid", gap: 4 }}>
                            <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 8 }}>
                              <strong>{share.title}</strong>
                              {share.revoked ? (
                                <span className="files-pill tone-danger">已整条撤回</span>
                              ) : expired ? (
                                <span className="files-pill tone-warn">已过期</span>
                              ) : (
                                <span className="files-pill tone-ok">生效中</span>
                              )}
                              <span className="files-pill tone-muted">
                                {EXPIRY_PRESET_LABELS[share.expiryPreset]}
                              </span>
                            </div>
                            <div className="airdrop-share-meta">
                              <code>ID: {share.shareId}</code>
                              <span>·</span>
                              <span>
                                <Clock
                                  size={12}
                                  style={{ display: "inline", verticalAlign: "-2px", marginRight: 3 }}
                                />
                                创建于 {formatDateTime(share.createdAt)}
                              </span>
                              <span>·</span>
                              <span>
                                {share.expiresAt
                                  ? `到期：${formatDateTime(share.expiresAt)}`
                                  : "无预设到期时间（受删除/账号状态/配额约束）"}
                              </span>
                              <span>·</span>
                              <span>
                                {share.maxDownloadsPerRecipient !== null
                                  ? `每人限 ${share.maxDownloadsPerRecipient} 次下载会话`
                                  : "不限下载次数"}
                              </span>
                            </div>
                          </div>

                          <div className="actions">
                            <Button
                              className="secondary workbench-btn-xs"
                              onClick={() => {
                                void copyToClipboard(share.shareId).then((ok) => {
                                  if (ok) {
                                    setCopiedShareId(share.shareId);
                                    window.setTimeout(() => setCopiedShareId(null), 2000);
                                  }
                                });
                              }}
                            >
                              {copiedShareId === share.shareId ? (
                                <Check size={12} />
                              ) : (
                                <Copy size={12} />
                              )}
                              {copiedShareId === share.shareId ? "已复制 ID" : "复制分享 ID"}
                            </Button>

                            {!share.revoked && (
                              <Button
                                className="danger workbench-btn-xs"
                                disabled={revokeShareMutation.isPending}
                                onClick={() => setConfirmRevokeShareId(share.shareId)}
                              >
                                <Ban size={12} /> 撤回整条分享
                              </Button>
                            )}
                          </div>
                        </div>

                        {confirmRevokeShareId === share.shareId && (
                          <div className="files-confirm-box" role="alertdialog" aria-label="确认撤回整条分享">
                            <p>
                              <strong>确认撤回整条空投「{share.title}」？</strong>
                              撤回后所有接收人将立即失去查看与下载权限（包括进行中的 Range 续传请求），且群聊中的对应卡片将立即失效。
                            </p>
                            <div className="actions">
                              <Button
                                className="secondary workbench-btn-xs"
                                disabled={revokeShareMutation.isPending}
                                onClick={() => setConfirmRevokeShareId(null)}
                              >
                                取消
                              </Button>
                              <Button
                                className="danger workbench-btn-xs"
                                disabled={revokeShareMutation.isPending}
                                onClick={() =>
                                  revokeShareMutation.mutate({
                                    shareId: share.shareId,
                                    key: getRevokeKey(`share:${share.shareId}`),
                                  })
                                }
                              >
                                {revokeShareMutation.isPending ? "撤回中…" : "确认撤回整条分享"}
                              </Button>
                            </div>
                          </div>
                        )}

                        <div className="airdrop-share-warning">
                          提示：可将分享 ID 引用至空间主群聊，但<strong>绝不会扩大接收人权限</strong>——仅下方未撤销的指定接收人可查看与下载。
                        </div>

                        <div>
                          <div className="muted" style={{ fontSize: "0.76rem", marginBottom: 5 }}>
                            包含文件 ({share.files.length})
                          </div>
                          <ul className="airdrop-sublist">
                            {share.files.map((file) => (
                              <li key={file.fileId} className="airdrop-file-row">
                                <span>
                                  <FileIcon
                                    size={13}
                                    style={{ display: "inline", verticalAlign: "-2px", marginRight: 5 }}
                                  />
                                  <strong>{file.fileName}</strong>
                                </span>
                                <span className="muted">
                                  {formatBytes(file.sizeBytes)} · <code>{shortDigest(file.sha256)}</code>
                                </span>
                              </li>
                            ))}
                          </ul>
                        </div>

                        <div>
                          <div className="muted" style={{ fontSize: "0.76rem", marginBottom: 5 }}>
                            接收人投递与权限状态 ({share.recipients.length})
                          </div>
                          <ul className="airdrop-sublist">
                            {share.recipients.map((rec) => {
                              const stateMeta = DELIVERY_STATE_LABELS[rec.deliveryState];
                              return (
                                <li key={rec.recipientUserId} className="airdrop-recipient-row">
                                  <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 8 }}>
                                    <strong>{resolveUserName(rec.recipientUserId)}</strong>
                                    <code className="muted">{rec.recipientUserId.slice(0, 8)}</code>
                                    {rec.revoked ? (
                                      <span className="files-pill tone-danger">已单独撤销</span>
                                    ) : (
                                      <span className={`files-pill tone-${stateMeta.tone}`}>
                                        {stateMeta.label}
                                      </span>
                                    )}
                                    <span className="muted">
                                      已建下载会话 {rec.downloadCount} 次
                                      {rec.remainingDownloads !== null
                                        ? ` · 剩余 ${rec.remainingDownloads} 次`
                                        : " · 不限次数"}
                                    </span>
                                  </div>

                                  {!share.revoked && !rec.revoked && (
                                    <Button
                                      className="secondary workbench-btn-xs"
                                      disabled={revokeRecipientMutation.isPending}
                                      onClick={() =>
                                        revokeRecipientMutation.mutate({
                                          shareId: share.shareId,
                                          recipientUserId: rec.recipientUserId,
                                          key: getRevokeKey(
                                            `recipient:${share.shareId}:${rec.recipientUserId}`,
                                          ),
                                        })
                                      }
                                    >
                                      <Ban size={12} /> 撤销此接收人
                                    </Button>
                                  )}
                                </li>
                              );
                            })}
                          </ul>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
            </>
          )}

          {activeTab === "RECEIVED" && (
            <>
              {receivedQuery.isPending && <Loading label="正在加载收到的空投…" />}
              {receivedQuery.isError && (
                <div className="alert" role="alert">
                  <AlertCircle size={16} />
                  <span>{filesErrorText(receivedQuery.error)}</span>
                </div>
              )}
              {receivedQuery.isSuccess && receivedShares.length === 0 && (
                <div className="files-empty-box">
                  <Inbox size={26} />
                  <strong>暂未收到好友发来的独立空投</strong>
                  <span>好友向你投递独立文件后将显示在此处。</span>
                </div>
              )}
              {receivedQuery.isSuccess && receivedShares.length > 0 && (
                <ul className="airdrop-share-list">
                  {receivedShares.map((share) => {
                    const expired = isShareExpired(share);
                    const myState =
                      share.recipients.find((r) => r.recipientUserId === currentUserId) ??
                      share.recipients[0];
                    const isRevokedForMe = share.revoked || Boolean(myState?.revoked);
                    const isExhausted =
                      myState !== undefined &&
                      myState.remainingDownloads !== null &&
                      myState.remainingDownloads <= 0;
                    const canDownload = !isRevokedForMe && !expired && !isExhausted;
                    const deliveryMeta = myState
                      ? DELIVERY_STATE_LABELS[myState.deliveryState]
                      : null;

                    return (
                      <li
                        key={share.shareId}
                        className={`airdrop-share-card ${isRevokedForMe ? "is-revoked" : ""} ${
                          inspectedShare?.shareId === share.shareId ? "is-highlighted" : ""
                        }`}
                      >
                        <div className="airdrop-share-head">
                          <div style={{ display: "grid", gap: 4 }}>
                            <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 8 }}>
                              <strong>{share.title}</strong>
                              {isRevokedForMe ? (
                                <span className="files-pill tone-danger">权限已撤销</span>
                              ) : expired ? (
                                <span className="files-pill tone-warn">已过期</span>
                              ) : isExhausted ? (
                                <span className="files-pill tone-warn">下载次数已用尽</span>
                              ) : (
                                <span className="files-pill tone-ok">可下载</span>
                              )}
                              {deliveryMeta && !isRevokedForMe && (
                                <span className={`files-pill tone-${deliveryMeta.tone}`}>
                                  {deliveryMeta.label}
                                </span>
                              )}
                            </div>

                            <div className="airdrop-share-meta">
                              <span>发送者：{resolveUserName(share.senderUserId)}</span>
                              <span>·</span>
                              <code>ID: {share.shareId.slice(0, 8)}</code>
                              <span>·</span>
                              <span>
                                {share.expiresAt
                                  ? `有效期至 ${formatDateTime(share.expiresAt)}`
                                  : "长期有效（受删除/账号状态/配额约束）"}
                              </span>
                              {myState && (
                                <>
                                  <span>·</span>
                                  <span>
                                    已下载 {myState.downloadCount} 次
                                    {myState.remainingDownloads !== null
                                      ? ` · 剩余 ${myState.remainingDownloads} 次`
                                      : " · 不限次数"}
                                  </span>
                                </>
                              )}
                            </div>
                          </div>

                          {!isRevokedForMe && !expired && (
                            <Button
                              className="secondary workbench-btn-xs"
                              disabled={inspectLoading}
                              onClick={() => {
                                void handleInspectShare(share.shareId);
                              }}
                            >
                              <Eye size={12} /> 查看详情
                            </Button>
                          )}
                        </div>

                        {isRevokedForMe ? (
                          <div className="alert" role="status" style={{ padding: "8px 10px" }}>
                            <ShieldAlert size={15} />
                            <span>该空投或你的接收权限已被发送者撤销，无法查看或下载文件。</span>
                          </div>
                        ) : (
                          <ul className="airdrop-sublist">
                            {share.files.map((item) => {
                              const dlKey = `${share.shareId}:${item.fileId}`;
                              return (
                                <li key={item.fileId} className="airdrop-file-row">
                                  <span>
                                    <FileIcon
                                      size={13}
                                      style={{ display: "inline", verticalAlign: "-2px", marginRight: 5 }}
                                    />
                                    <strong>{item.fileName}</strong> ({formatBytes(item.sizeBytes)})
                                  </span>
                                  <Button
                                    className="primary workbench-btn-xs"
                                    disabled={!canDownload || Boolean(downloadingKey)}
                                    onClick={() => {
                                      void handleDownloadAirdropFile(share.shareId, item.fileId);
                                    }}
                                  >
                                    <Download size={13} />
                                    {downloadingKey === dlKey
                                      ? (downloadProgressLabel ?? "下载中…")
                                      : "新建会话下载"}
                                  </Button>
                                </li>
                              );
                            })}
                          </ul>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </>
          )}
        </section>
      </div>
    </section>
  );
}

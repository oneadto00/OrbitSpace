import { useEffect, useMemo, useRef, useState, type DragEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import type { FileRecordDto, Member, SpaceRole } from "@orbitspace/shared-types";
import {
  AlertCircle,
  CheckCircle2,
  Clock,
  Download,
  File as FileIcon,
  FolderOpen,
  HardDrive,
  Image as ImageIcon,
  Info,
  MessageSquare,
  Pause,
  Play,
  RefreshCw,
  RotateCcw,
  Trash2,
  Upload,
  X,
  Zap,
} from "lucide-react";
import { queryClient, spaceKey, type SpaceAccess } from "../../shared/api/access";
import { canManageSpace, canUploadOrManage } from "../../shared/auth/session";
import { Button, Loading } from "../../shared/ui/UiComponents";
import { formatDateTime, listSpaceMembers } from "../space/spaceApi";
import {
  deleteSpaceFile,
  downloadAuthorizedFile,
  filesErrorText,
  formatBytes,
  listSpaceFiles,
  shortDigest,
} from "./filesApi";
import { useChunkedUpload, type UploadTaskItem } from "./useChunkedUpload";
import "./files.css";

export interface SpaceFilesPanelProps {
  spaceId: string;
  spaceRole: SpaceRole;
  access: SpaceAccess;
  currentUserId: string | null;
  highlightedFileId?: string | null;
  onQuoteFileToChat: (ref: {
    grantType: "SPACE_FILE";
    targetId: string;
    title: string;
  }) => void;
}

function uploadPhaseMeta(task: UploadTaskItem): { label: string; tone: string } {
  switch (task.phase) {
    case "hashing":
      return { label: `本地分片 SHA-256 计算中 (${task.hashProgress}%)`, tone: "info" };
    case "creating_session":
      return { label: "预检空间配额与会话…", tone: "info" };
    case "uploading":
      return {
        label: `并发分片上传 (${task.uploadedPartsCount}/${task.totalParts})`,
        tone: "info",
      };
    case "paused":
      return {
        label: `已暂停 (${task.uploadedPartsCount}/${task.totalParts} 分片)`,
        tone: "warn",
      };
    case "completing":
      return {
        label: task.instantMatchAvailable ? "本人授权秒传校验中…" : "合并并校验最终 SHA-256…",
        tone: "info",
      };
    case "completed":
      return {
        label: task.instantMatchUsed ? "上传完成（本人授权秒传复用）" : "上传完成",
        tone: "ok",
      };
    case "error":
      return { label: "上传中断", tone: "danger" };
  }
}

export function SpaceFilesPanel({
  spaceId,
  spaceRole,
  access,
  currentUserId,
  highlightedFileId = null,
  onQuoteFileToChat,
}: SpaceFilesPanelProps) {
  const canWrite = canUploadOrManage(spaceRole);
  const isAdminOrOwner = canManageSpace(spaceRole);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<FileRecordDto | null>(null);
  const [downloadingFileId, setDownloadingFileId] = useState<string | null>(null);
  const [downloadProgressText, setDownloadProgressText] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const filesQueryKey = useMemo(
    () => [...spaceKey(access), "files"] as const,
    [access],
  );

  const filesQuery = useQuery({
    queryKey: filesQueryKey,
    queryFn: ({ signal }) => listSpaceFiles(spaceId, access, signal),
  });

  const membersQuery = useQuery({
    queryKey: [...spaceKey(access), "members"] as const,
    queryFn: ({ signal }) => listSpaceMembers(spaceId, access, signal),
  });

  const membersById = useMemo(() => {
    const map = new Map<string, Member>();
    for (const m of membersQuery.data ?? []) {
      map.set(m.userId, m);
    }
    return map;
  }, [membersQuery.data]);

  const {
    tasks,
    enqueueFiles,
    pauseTask,
    resumeTask,
    retryTask,
    removeTask,
    clearCompleted,
  } = useChunkedUpload({
    targetScope: "SPACE_FILE",
    spaceId,
    access,
    onCompleted: () => {
      void queryClient.invalidateQueries({ queryKey: filesQueryKey });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (fileId: string) => deleteSpaceFile(spaceId, fileId, access),
    onSuccess: () => {
      setPendingDelete(null);
      setActionError(null);
      void queryClient.invalidateQueries({ queryKey: filesQueryKey });
    },
    onError: (err) => {
      setActionError(filesErrorText(err));
    },
  });

  useEffect(() => {
    if (!highlightedFileId) return;
    const el = document.querySelector<HTMLElement>(
      `[data-space-file-id="${CSS.escape(highlightedFileId)}"]`,
    );
    el?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [highlightedFileId, filesQuery.data?.items.length]);

  const handleDownload = async (file: FileRecordDto) => {
    if (downloadingFileId) return;
    setActionError(null);
    setDownloadingFileId(file.fileId);
    setDownloadProgressText("创建鉴权下载会话…");
    try {
      await downloadAuthorizedFile({
        grantType: "SPACE_FILE",
        grantId: spaceId,
        fileId: file.fileId,
        access,
        onProgress: (loaded, total) => {
          if (total > 0) {
            const pct = Math.min(100, Math.round((loaded / total) * 100));
            setDownloadProgressText(`下载中 ${pct}%`);
          } else {
            setDownloadProgressText("下载完成");
          }
        },
      });
    } catch (err) {
      setActionError(filesErrorText(err));
    } finally {
      setDownloadingFileId(null);
      setDownloadProgressText(null);
    }
  };

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setIsDragOver(false);
    if (!canWrite) return;
    if (event.dataTransfer.files.length > 0) {
      enqueueFiles(event.dataTransfer.files);
    }
  };

  const quotaUsed = filesQuery.data?.quotaUsedBytes ?? 0;
  const quotaLimit = filesQuery.data?.quotaLimitBytes ?? 0;
  const quotaPercent =
    quotaLimit > 0 ? Math.min(100, Math.round((quotaUsed / quotaLimit) * 100)) : 0;
  const quotaToneClass =
    quotaPercent >= 95 ? "is-danger" : quotaPercent >= 80 ? "is-warn" : "";

  const files = filesQuery.data?.items ?? [];
  const hasCompletedTasks = tasks.some((t) => t.phase === "completed");

  const ownerDisplay = (ownerUserId: string): string => {
    if (ownerUserId === currentUserId) return "我";
    return membersById.get(ownerUserId)?.displayName ?? ownerUserId.slice(0, 8);
  };

  return (
    <section className="files-panel" aria-label="空间文件区">
      <header className="files-panel-head">
        <div className="files-panel-title">
          <FolderOpen size={19} />
          <h2>空间文件区</h2>
          <span className="files-pill tone-muted">{files.length} 个文件</span>
        </div>
        <div className="actions">
          <Button
            className="secondary workbench-btn-xs"
            disabled={filesQuery.isFetching}
            onClick={() => {
              void filesQuery.refetch();
            }}
          >
            <RefreshCw size={13} /> 刷新列表
          </Button>
        </div>
      </header>

      <div className="files-policy-box" role="note">
        <Info size={16} />
        <div>
          <strong>权限与配额规则：</strong>
          空间文件区继承当前空间成员与角色权限；移除空间成员后将立即撤销访问权限。空间文件下载通过实时鉴权会话流式传输，不设单人次数上限；删除逻辑文件仅撤回空间文件区访问，不影响其他独立授权记录。
        </div>
      </div>

      {/* Space Quota Bar */}
      <div className="files-quota-card">
        <div className="files-quota-row">
          <span className="files-quota-label">
            <HardDrive size={15} /> 空间存储配额
          </span>
          <span>
            已用 <strong>{formatBytes(quotaUsed)}</strong> / {formatBytes(quotaLimit)}（{quotaPercent}%）
          </span>
        </div>
        <div
          className="files-progress-track"
          role="progressbar"
          aria-label="空间存储配额使用率"
          aria-valuenow={quotaPercent}
          aria-valuemin={0}
          aria-valuemax={100}
        >
          <div
            className={`files-progress-fill ${quotaToneClass}`}
            style={{ width: `${quotaPercent}%` }}
          />
        </div>
      </div>

      {/* Chunked Upload Widget */}
      {canWrite ? (
        <div
          className={`files-upload-box ${isDragOver ? "is-dragover" : ""}`}
          onDragOver={(e) => {
            e.preventDefault();
            setIsDragOver(true);
          }}
          onDragLeave={() => setIsDragOver(false)}
          onDrop={handleDrop}
        >
          <div className="files-upload-head">
            <div>
              <strong>分片上传到空间文件区</strong>
              <p className="files-upload-hint">
                独立 Web Worker 本地流式计算分片与整文件 SHA-256，预检空间配额后并发上传（支持暂停、断点续传与断网重试）。秒传复用严格限定在当前用户本人已持有且获授权的文件范围内。
              </p>
            </div>
            <div className="actions">
              {hasCompletedTasks && (
                <Button className="secondary workbench-btn-xs" onClick={clearCompleted}>
                  清空已完成
                </Button>
              )}
              <Button
                className="primary workbench-btn-xs"
                onClick={() => fileInputRef.current?.click()}
              >
                <Upload size={13} /> 选择文件上传
              </Button>
            </div>
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
          </div>

          {tasks.length > 0 && (
            <ul className="files-queue-list">
              {tasks.map((task) => {
                const phaseMeta = uploadPhaseMeta(task);
                const barValue =
                  task.phase === "hashing" ? task.hashProgress : task.uploadProgress;
                return (
                  <li key={task.localId} className={`files-queue-item phase-${task.phase}`}>
                    <div className="files-queue-top">
                      <span className="files-queue-name">
                        <FileIcon size={14} />
                        {task.fileName}
                        <span className="muted" style={{ fontWeight: 400 }}>
                          ({formatBytes(task.fileSizeBytes)})
                        </span>
                      </span>
                      <div className="actions">
                        <span className={`files-pill tone-${phaseMeta.tone}`}>
                          {task.instantMatchUsed && <Zap size={11} />}
                          {task.phase === "completed" && !task.instantMatchUsed && (
                            <CheckCircle2 size={11} />
                          )}
                          {phaseMeta.label}
                        </span>

                        {(task.phase === "hashing" ||
                          task.phase === "creating_session" ||
                          task.phase === "uploading") && (
                          <Button
                            className="secondary workbench-btn-xs"
                            onClick={() => pauseTask(task.localId)}
                          >
                            <Pause size={12} /> 暂停
                          </Button>
                        )}

                        {task.phase === "paused" && (
                          <Button
                            className="primary workbench-btn-xs"
                            onClick={() => resumeTask(task.localId)}
                          >
                            <Play size={12} /> 继续上传
                          </Button>
                        )}

                        {task.phase === "error" && task.canRetry && (
                          <Button
                            className="primary workbench-btn-xs"
                            onClick={() => retryTask(task.localId)}
                          >
                            <RotateCcw size={12} /> 断点重试
                          </Button>
                        )}

                        <Button
                          className="icon-button secondary"
                          aria-label="移除上传任务"
                          onClick={() => removeTask(task.localId)}
                        >
                          <X size={13} />
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
                        style={{ width: `${barValue}%` }}
                      />
                    </div>

                    <div className="files-queue-meta">
                      <span>
                        {task.fileSha256
                          ? `SHA-256: ${shortDigest(task.fileSha256)}…`
                          : "正在分片计算 SHA-256…"}
                      </span>
                      <span>
                        {task.instantMatchAvailable && task.phase !== "completed"
                          ? "检测到本人授权可复用对象"
                          : `分片 ${task.uploadedPartsCount}/${task.totalParts}`}
                      </span>
                    </div>

                    {task.errorMessage && (
                      <div className="alert" role="alert" style={{ padding: "7px 10px" }}>
                        <AlertCircle size={14} />
                        <span>{task.errorMessage}</span>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      ) : (
        <div className="files-policy-box is-warn" role="status">
          <Info size={16} />
          <span>当前为只读成员（VIEWER），可查看并下载空间文件，不可上传、引用或删除文件。</span>
        </div>
      )}

      {actionError && (
        <div className="alert" role="alert">
          <AlertCircle size={16} />
          <span>{actionError}</span>
        </div>
      )}

      {pendingDelete && (
        <div className="files-confirm-box" role="alertdialog" aria-label="确认删除空间文件">
          <p>
            <strong>确认删除空间文件「{pendingDelete.fileName}」？</strong>
            删除后将立即撤销空间文件区访问与对应下载会话，并将群聊中引用该文件的卡片置为失效状态；其他独立授权不会受影响。
          </p>
          <div className="actions">
            <Button
              className="secondary workbench-btn-xs"
              disabled={deleteMutation.isPending}
              onClick={() => setPendingDelete(null)}
            >
              取消
            </Button>
            <Button
              className="danger workbench-btn-xs"
              disabled={deleteMutation.isPending}
              onClick={() => deleteMutation.mutate(pendingDelete.fileId)}
            >
              {deleteMutation.isPending ? "删除中…" : "确认删除"}
            </Button>
          </div>
        </div>
      )}

      {/* Space File List */}
      {filesQuery.isPending && <Loading label="正在加载空间文件列表…" />}

      {filesQuery.isError && (
        <div className="alert" role="alert">
          <AlertCircle size={16} />
          <span>{filesErrorText(filesQuery.error)}</span>
          <Button
            className="secondary workbench-btn-xs"
            onClick={() => {
              void filesQuery.refetch();
            }}
          >
            重试
          </Button>
        </div>
      )}

      {filesQuery.isSuccess && files.length === 0 && (
        <div className="files-empty-box">
          <FolderOpen size={28} />
          <strong>空间文件区暂无文件</strong>
          <span>
            {canWrite
              ? "拖拽文件到上方区域或点击「选择文件上传」，上传后可直接在空间主群聊中引用讨论。"
              : "空间成员上传文件后将显示在此处。"}
          </span>
        </div>
      )}

      {filesQuery.isSuccess && files.length > 0 && (
        <ul className="files-list">
          {files.map((file) => {
            const isHighlighted = highlightedFileId === file.fileId;
            const canDelete =
              canWrite && (isAdminOrOwner || file.ownerUserId === currentUserId);
            const isDownloading = downloadingFileId === file.fileId;

            return (
              <li
                key={file.fileId}
                data-space-file-id={file.fileId}
                className={`files-item-row ${isHighlighted ? "is-highlighted" : ""}`}
              >
                <div className="files-item-main">
                  <div className="files-item-title">
                    <FileIcon size={15} />
                    <strong>{file.fileName}</strong>
                    {file.thumbnailReady ? (
                      <span className="files-pill tone-ok">
                        <ImageIcon size={11} /> 缩略图已就绪
                      </span>
                    ) : file.thumbnailJobId ? (
                      <span className="files-pill tone-info">
                        <ImageIcon size={11} /> 缩略图生成中
                      </span>
                    ) : null}
                  </div>
                  <div className="files-item-meta">
                    <span>{formatBytes(file.sizeBytes)}</span>
                    <span>·</span>
                    <span>{file.contentType ?? "application/octet-stream"}</span>
                    <span>·</span>
                    <code>SHA-256 {shortDigest(file.sha256)}</code>
                    <span>·</span>
                    <span>上传者：{ownerDisplay(file.ownerUserId)}</span>
                    <span>·</span>
                    <span>
                      <Clock
                        size={12}
                        style={{ display: "inline", verticalAlign: "-2px", marginRight: 3 }}
                      />
                      {formatDateTime(file.createdAt)}
                    </span>
                  </div>
                </div>

                <div className="files-item-actions">
                  <Button
                    className="secondary workbench-btn-xs"
                    disabled={Boolean(downloadingFileId)}
                    onClick={() => {
                      void handleDownload(file);
                    }}
                  >
                    <Download size={13} />
                    {isDownloading ? (downloadProgressText ?? "下载中…") : "鉴权下载"}
                  </Button>

                  {canWrite && (
                    <Button
                      className="secondary workbench-btn-xs"
                      onClick={() =>
                        onQuoteFileToChat({
                          grantType: "SPACE_FILE",
                          targetId: file.fileId,
                          title: file.fileName,
                        })
                      }
                    >
                      <MessageSquare size={13} /> 引用到主群聊
                    </Button>
                  )}

                  {canDelete && (
                    <Button
                      className="secondary workbench-btn-xs"
                      disabled={deleteMutation.isPending}
                      onClick={() => setPendingDelete(file)}
                    >
                      <Trash2 size={13} /> 删除
                    </Button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

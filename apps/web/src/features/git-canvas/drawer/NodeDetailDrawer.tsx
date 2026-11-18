import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  CommitDetail,
  CommitObservation,
  ContentManifestEntry,
  DataLevel,
  DiffEndpointRef,
  PatchDescriptor,
  ScopeDeclaration,
  SnapshotDetail,
} from "@orbitspace/shared-types";
import {
  AlertTriangle,
  Download,
  FileCode2,
  GitCommitHorizontal,
  GitCompareArrows,
  GitPullRequestDraft,
  MessageSquareQuote,
  RefreshCw,
  ShieldCheck,
  X,
} from "lucide-react";
import { spaceKey, type SpaceAccess } from "../../../shared/api/access";
import type { GitWorkspaceSlotProps } from "../../../shared/contracts/gitWorkspaceContract";
import { Button, Loading, ResourceStateCard } from "../../../shared/ui/UiComponents";
import {
  buildCommitPatchPath,
  buildSnapshotPatchPath,
  fetchCommitDetail,
  fetchRawPatch,
  fetchSnapshotDetail,
  gitCanvasErrorText,
  makeIdempotencyKey,
  resolveResourceCardState,
  retrySnapshotParseJob,
} from "../gitCanvasApi";
import {
  CAPTURE_STATE_LABELS,
  CONTENT_MANIFEST_META,
  DATA_LEVEL_LABELS,
  PARSE_STATE_LABELS,
  TRUST_STATE_LABELS,
  WIP_FRESHNESS_LABELS,
  formatBytes,
  formatDateTime,
  shortOid,
} from "../labels";

export interface NodeDetailDrawerProps {
  spaceId: string;
  projectId: string;
  access: SpaceAccess;
  canUploadOrManage: boolean;
  selected: { kind: "COMMIT" | "WIP"; id: string };
  isOutsideCurrentGraph: boolean;
  highlightNonce?: number;
  onClose: () => void;
  onQuoteToChat: GitWorkspaceSlotProps["onQuoteToChat"];
  onSnapshotUpdated?: () => void;
  onAssignDiffEndpoint?: (role: "base" | "target", ref: DiffEndpointRef) => void;
}

const INITIAL_DIFF_LINE_LIMIT = 1500;

function sortObservationsDesc(observations: CommitObservation[]): CommitObservation[] {
  return [...observations].sort((a, b) => {
    const dt = Date.parse(b.source.observedAt) - Date.parse(a.source.observedAt);
    if (dt !== 0) return dt;
    const dCmp = a.source.deviceId.localeCompare(b.source.deviceId);
    if (dCmp !== 0) return dCmp;
    return a.source.userId.localeCompare(b.source.userId);
  });
}

function getOrderedCommitParents(sortedObservations: CommitObservation[]): string[] {
  const primary = sortedObservations[0];
  if (!primary) return [];
  const seen = new Set<string>();
  const parents: string[] = [];
  for (const p of primary.metadata.parents) {
    if (!seen.has(p)) {
      seen.add(p);
      parents.push(p);
    }
  }
  for (const obs of sortedObservations) {
    for (const p of obs.metadata.parents) {
      if (!seen.has(p)) {
        seen.add(p);
        parents.push(p);
      }
    }
  }
  return parents;
}

function ScopeDeclarationSection({
  scopeDeclaration,
  dataLevel,
}: {
  scopeDeclaration: ScopeDeclaration;
  dataLevel: DataLevel;
}) {
  return (
    <section className="drawer-section">
      <h3>共享范围与数据层级</h3>
      <div className="drawer-kv-grid">
        <div>
          <span className="drawer-kv-label">数据级别</span>
          <span className="drawer-badge drawer-badge-neutral">{DATA_LEVEL_LABELS[dataLevel]}</span>
        </div>
        <div>
          <span className="drawer-kv-label">已披露路径</span>
          <strong>{scopeDeclaration.includedPaths.length} 项</strong>
        </div>
        <div>
          <span className="drawer-kv-label">隐藏条目数</span>
          <strong>{scopeDeclaration.hiddenItemCount}</strong>
        </div>
        <div>
          <span className="drawer-kv-label">排除条目数</span>
          <strong>{scopeDeclaration.excludedItemCount}</strong>
        </div>
      </div>
      <p className="drawer-hint">
        仅披露授权范围内的路径与计数；隐藏/排除条目不包含路径名，范围外路径的存在状态未知。
      </p>
      {scopeDeclaration.includedPaths.length > 0 && (
        <ul className="drawer-path-list">
          {scopeDeclaration.includedPaths.map((path) => (
            <li key={path}>
              <code>{path}</code>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ContentManifestSection({ entries }: { entries: ContentManifestEntry[] }) {
  const unsupportedCount = entries.filter((e) => e.status === "UNSUPPORTED").length;

  return (
    <section className="drawer-section">
      <div className="drawer-section-head">
        <h3>文件状态清单 (Content Manifest)</h3>
        <span className="drawer-count">{entries.length} 项</span>
      </div>
      {unsupportedCount > 0 && (
        <div className="drawer-callout drawer-callout-warn" role="status">
          <AlertTriangle size={15} />
          <span>
            包含 {unsupportedCount} 个不支持的条目（子模块 / Git LFS 指针等），已标记为 UNSUPPORTED。
          </span>
        </div>
      )}
      {entries.length === 0 ? (
        <p className="drawer-hint">本次声明未包含文件状态条目。</p>
      ) : (
        <ul className="drawer-manifest-list">
          {entries.map((entry) => {
            const meta = CONTENT_MANIFEST_META[entry.status];
            return (
              <li key={`${entry.path}:${entry.status}`} className="drawer-manifest-item">
                <div className="drawer-manifest-row">
                  <code className="drawer-manifest-path">{entry.path}</code>
                  <span className={`drawer-badge drawer-badge-${meta.tone}`}>
                    {meta.label} ({entry.status})
                  </span>
                </div>
                <div className="drawer-manifest-sub">
                  <span>{meta.description}</span>
                  {entry.sha256 && <code title={entry.sha256}>sha256:{entry.sha256.slice(0, 12)}</code>}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function UnifiedPatchViewer({
  dataLevel,
  patch,
  patchPath,
  downloadFilename,
  access,
  queryKeyPrefix,
}: {
  dataLevel: DataLevel;
  patch: PatchDescriptor | null;
  patchPath: string;
  downloadFilename: string;
  access: SpaceAccess;
  queryKeyPrefix: readonly unknown[];
}) {
  const [showAllLines, setShowAllLines] = useState(false);
  const [downloadError, setDownloadError] = useState<unknown>(null);

  useEffect(() => {
    setShowAllLines(false);
    setDownloadError(null);
  }, [patchPath]);

  const patchQuery = useQuery({
    queryKey: [...queryKeyPrefix, "raw-patch", patchPath],
    enabled: dataLevel !== "METADATA_ONLY" && patch !== null,
    queryFn: ({ signal }) => fetchRawPatch(patchPath, access, signal),
  });

  const lines = useMemo(() => {
    if (!patchQuery.data) return [];
    return patchQuery.data.text.split(/\r?\n/);
  }, [patchQuery.data]);

  if (dataLevel === "METADATA_ONLY") {
    return (
      <section className="drawer-section">
        <h3>内容边界说明</h3>
        <div className="drawer-callout drawer-callout-muted">
          <strong>当前为 METADATA_ONLY（仅元信息）</strong>
          <p>
            本节点仅上传了提交/工作区元信息与范围声明，未上传 Patch 补丁字节或完整代码树，因此不提供代码或补丁查看入口。
          </p>
        </div>
      </section>
    );
  }

  const triggerDownload = async () => {
    setDownloadError(null);
    try {
      const payload = patchQuery.data ?? (await fetchRawPatch(patchPath, access));
      const blob = new Blob([payload.buffer], { type: "text/x-diff;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = downloadFilename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (err) {
      setDownloadError(err);
    }
  };

  const truncated = !showAllLines && lines.length > INITIAL_DIFF_LINE_LIMIT;
  const displayedLines = truncated ? lines.slice(0, INITIAL_DIFF_LINE_LIMIT) : lines;

  const cardState = resolveResourceCardState(patchQuery.error ?? downloadError);

  return (
    <section className="drawer-section">
      <div className="drawer-section-head">
        <h3>
          <FileCode2 size={16} /> 单点 Patch 补丁视图
        </h3>
        {patch !== null && (
          <Button
            className="secondary drawer-btn-sm"
            onClick={() => {
              void triggerDownload();
            }}
          >
            <Download size={14} /> 下载 Patch
          </Button>
        )}
      </div>

      <p className="drawer-hint">
        当前为单点增量 Patch 视图（单次提交对其所选父提交、或 WIP 对其基准 HEAD）；如需比较任意两节点的文件树差异，请使用「双点 Diff 对比」。
      </p>

      {patch === null ? (
        <div className="drawer-callout drawer-callout-muted">
          当前节点未持有可读取的保留 Patch 描述符，无法读取补丁内容。
        </div>
      ) : (
        <>
          <div className="drawer-patch-meta">
            <span>大小: {formatBytes(patch.sizeBytes)}</span>
            <code title={patch.sha256}>SHA-256: {patch.sha256.slice(0, 16)}…</code>
          </div>

          {patchQuery.isPending && <Loading label="正在通过受保护通道流式读取 Patch…" />}

          {downloadError ? (
            <div className="alert" role="alert">
              <span>{gitCanvasErrorText(downloadError)}</span>
            </div>
          ) : null}

          {patchQuery.isError &&
            (cardState ? (
              <ResourceStateCard state={cardState} />
            ) : (
              <div className="alert" role="alert">
                <span>{gitCanvasErrorText(patchQuery.error)}</span>
                <Button
                  className="secondary drawer-btn-sm"
                  onClick={() => {
                    void patchQuery.refetch();
                  }}
                >
                  重试
                </Button>
              </div>
            ))}

          {patchQuery.isSuccess && (
            <div className="drawer-diff-box" role="region" aria-label="Unified Diff 内容">
              {lines.length === 0 || (lines.length === 1 && lines[0] === "") ? (
                <div className="drawer-diff-empty">Patch 内容为空（0 字节差异）。</div>
              ) : (
                <pre className="drawer-diff-pre">
                  {displayedLines.map((line, idx) => {
                    const cls = line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff --git")
                      ? "diff-line-header"
                      : line.startsWith("@@")
                        ? "diff-line-hunk"
                        : line.startsWith("+")
                          ? "diff-line-add"
                          : line.startsWith("-")
                            ? "diff-line-del"
                            : "diff-line-ctx";
                    return (
                      <div key={idx} className={`diff-line ${cls}`}>
                        <span className="diff-ln">{idx + 1}</span>
                        <span className="diff-code">{line || " "}</span>
                      </div>
                    );
                  })}
                </pre>
              )}
              {truncated && (
                <div className="drawer-diff-more">
                  <span>
                    已展示前 {INITIAL_DIFF_LINE_LIMIT} 行（共 {lines.length} 行）
                  </span>
                  <Button
                    className="secondary drawer-btn-sm"
                    onClick={() => setShowAllLines(true)}
                  >
                    展开全部 {lines.length} 行
                  </Button>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </section>
  );
}

export function NodeDetailDrawer({
  spaceId,
  projectId,
  access,
  canUploadOrManage,
  selected,
  isOutsideCurrentGraph,
  highlightNonce,
  onClose,
  onQuoteToChat,
  onSnapshotUpdated,
  onAssignDiffEndpoint,
}: NodeDetailDrawerProps) {
  const queryClient = useQueryClient();
  const [selectedParentOid, setSelectedParentOid] = useState<string | undefined>(undefined);
  const retryKeyRef = useRef<string>(makeIdempotencyKey());

  useEffect(() => {
    setSelectedParentOid(undefined);
    retryKeyRef.current = makeIdempotencyKey();
  }, [selected.kind, selected.id]);

  const commitQueryKey = [
    ...spaceKey(access),
    "project",
    projectId,
    "commit",
    selected.id,
    selectedParentOid ?? "DEFAULT",
  ] as const;

  const commitQuery = useQuery({
    queryKey: commitQueryKey,
    enabled: selected.kind === "COMMIT",
    queryFn: ({ signal }) =>
      fetchCommitDetail(spaceId, projectId, selected.id, access, selectedParentOid, signal),
  });

  const snapshotQueryKey = [
    ...spaceKey(access),
    "project",
    projectId,
    "snapshot",
    selected.id,
  ] as const;

  const snapshotQuery = useQuery({
    queryKey: snapshotQueryKey,
    enabled: selected.kind === "WIP",
    queryFn: ({ signal }) => fetchSnapshotDetail(spaceId, projectId, selected.id, access, signal),
  });

  const retryParseMutation = useMutation({
    mutationFn: async (params: { snapshotId: string; jobId: string }) => {
      const result = await retrySnapshotParseJob(
        spaceId,
        projectId,
        params.snapshotId,
        params.jobId,
        access,
        retryKeyRef.current,
      );
      return result;
    },
    onSuccess: async () => {
      retryKeyRef.current = makeIdempotencyKey();
      await queryClient.invalidateQueries({ queryKey: snapshotQueryKey });
      onSnapshotUpdated?.();
    },
  });

  const activeError = selected.kind === "COMMIT" ? commitQuery.error : snapshotQuery.error;
  const activeCardState = resolveResourceCardState(activeError);

  const commitDetail: CommitDetail | undefined = commitQuery.data;
  const snapshotDetail: SnapshotDetail | undefined = snapshotQuery.data;

  const sortedObservations = useMemo(
    () => (commitDetail ? sortObservationsDesc(commitDetail.commit.observations) : []),
    [commitDetail],
  );
  const primaryObservation = sortedObservations[0];

  const orderedParents = useMemo(
    () => getOrderedCommitParents(sortedObservations),
    [sortedObservations],
  );

  const effectiveParentOid =
    selectedParentOid ?? commitDetail?.selectedParentOid ?? orderedParents[0] ?? null;

  const handleQuote = () => {
    if (selected.kind === "COMMIT") {
      const title = primaryObservation && commitDetail
        ? `${shortOid(commitDetail.commit.oid)} ${primaryObservation.metadata.subject}`.trim()
        : `Commit ${shortOid(selected.id)}`;
      onQuoteToChat({
        kind: "COMMIT_REF",
        projectId,
        targetId: selected.id,
        title,
      });
    } else {
      const branchLabel = snapshotDetail
        ? snapshotDetail.wip.head.branch ?? "Detached HEAD"
        : "WIP";
      onQuoteToChat({
        kind: "WIP_REF",
        projectId,
        targetId: selected.id,
        title: `WIP ${branchLabel} (${shortOid(selected.id)})`,
      });
    }
  };

  return (
    <aside
      key={`drawer-${selected.kind}-${selected.id}-${highlightNonce ?? 0}`}
      className={`git-detail-drawer ${highlightNonce ? "git-detail-drawer-highlight" : ""}`}
      aria-label="节点详情抽屉"
    >
      <header className="drawer-header">
        <div className="drawer-title-group">
          <span className="drawer-kind-pill">
            {selected.kind === "COMMIT" ? (
              <>
                <GitCommitHorizontal size={14} /> 提交详情
              </>
            ) : (
              <>
                <GitPullRequestDraft size={14} /> WIP 快照详情
              </>
            )}
          </span>
          <code className="drawer-id-code">{shortOid(selected.id)}</code>
        </div>
        <div className="drawer-header-actions">
          <Button
            className="secondary drawer-btn-sm"
            disabled={!!activeCardState}
            onClick={handleQuote}
          >
            <MessageSquareQuote size={14} /> 引用到群聊
          </Button>
          <Button
            className="icon-button secondary"
            onClick={onClose}
            aria-label="关闭详情抽屉"
          >
            <X size={16} />
          </Button>
        </div>
      </header>

      <div className="drawer-body">
        {onAssignDiffEndpoint && (
          <div className="drawer-diff-jump-bar">
            <span className="drawer-kv-label">
              <GitCompareArrows size={13} /> 发起双点 Diff 对比：
            </span>
            <div className="drawer-diff-jump-actions">
              <Button
                className="secondary drawer-btn-sm"
                onClick={() => onAssignDiffEndpoint("base", { kind: selected.kind, id: selected.id })}
              >
                设为旧端 Base
              </Button>
              <Button
                className="secondary drawer-btn-sm"
                onClick={() =>
                  onAssignDiffEndpoint("target", { kind: selected.kind, id: selected.id })
                }
              >
                设为新端 Target
              </Button>
            </div>
          </div>
        )}

        {isOutsideCurrentGraph && (
          <div className="drawer-callout drawer-callout-info" role="status">
            {selected.kind === "WIP"
              ? "当前展示的是不可变历史 WIP 快照（可能已不再是工作区最新活跃指针），已直接读取该快照保留记录。"
              : "该提交暂未出现在当前已加载的画布分页中，已直接加载单点详情；可继续“加载更多历史”以在画布定位。"}
          </div>
        )}

        {selected.kind === "COMMIT" && (
          <>
            {commitQuery.isPending && <Loading label="正在读取提交详情…" />}
            {commitQuery.isError &&
              (activeCardState ? (
                <ResourceStateCard state={activeCardState} />
              ) : (
                <div className="alert" role="alert">
                  <span>{gitCanvasErrorText(commitQuery.error)}</span>
                  <Button
                    className="secondary drawer-btn-sm"
                    onClick={() => {
                      void commitQuery.refetch();
                    }}
                  >
                    重试
                  </Button>
                </div>
              ))}
            {commitDetail && (
              <>
                <section className="drawer-section">
                  <div className="drawer-badge-row">
                    <span
                      className={`drawer-badge ${
                        commitDetail.commit.trustState === "VERIFIED"
                          ? "drawer-badge-ok"
                          : commitDetail.commit.trustState === "CONFLICT"
                            ? "drawer-badge-danger"
                            : "drawer-badge-neutral"
                      }`}
                    >
                      <ShieldCheck size={13} />{" "}
                      {TRUST_STATE_LABELS[commitDetail.commit.trustState]}
                    </span>
                    <span className="drawer-badge drawer-badge-neutral">
                      {DATA_LEVEL_LABELS[commitDetail.dataLevel]}
                    </span>
                  </div>

                  <h4 className="drawer-subject">
                    {primaryObservation?.metadata.subject || "(无提交说明)"}
                  </h4>
                  <code className="drawer-full-oid">{commitDetail.commit.oid}</code>

                  {commitDetail.commit.missingParentOids.length > 0 && (
                    <div className="drawer-callout drawer-callout-warn" role="status">
                      <AlertTriangle size={15} />
                      <span>
                        历史不完整：缺失祖先 OID{" "}
                        {commitDetail.commit.missingParentOids.map(shortOid).join("、")}
                      </span>
                    </div>
                  )}

                  {/* Parent selector for merge commits */}
                  <div className="drawer-parent-box">
                    {orderedParents.length === 0 ? (
                      <p className="drawer-hint">根提交（比较基准：EMPTY_TREE 空树）。</p>
                    ) : orderedParents.length === 1 ? (
                      <p className="drawer-hint">
                        默认比较父提交：<code>{orderedParents[0]}</code>
                      </p>
                    ) : (
                      <label className="field drawer-parent-select">
                        比较基准父提交（合并提交支持切换父提交）
                        <select
                          value={effectiveParentOid ?? orderedParents[0]}
                          onChange={(e) => setSelectedParentOid(e.target.value)}
                        >
                          {orderedParents.map((pOid, idx) => (
                            <option key={pOid} value={pOid}>
                              #{idx + 1} {idx === 0 ? "(默认第一父提交) " : ""}
                              {pOid}
                            </option>
                          ))}
                        </select>
                      </label>
                    )}
                  </div>
                </section>

                <section className="drawer-section">
                  <h3>来源观测记录 ({commitDetail.commit.observations.length})</h3>
                  <ul className="drawer-obs-list">
                    {commitDetail.commit.observations.map((obs, idx) => (
                      <li key={`${obs.source.deviceId}:${obs.source.observedAt}:${idx}`}>
                        <div className="drawer-obs-head">
                          <strong>{obs.metadata.authorName}</strong>
                          <span className="drawer-badge drawer-badge-neutral">
                            {obs.trustState}
                          </span>
                        </div>
                        <div className="drawer-obs-meta">
                          <span>提交时间: {formatDateTime(obs.metadata.authoredAt)}</span>
                          <span>观测时间: {formatDateTime(obs.source.observedAt)}</span>
                          <span>
                            设备: <code>{shortOid(obs.source.deviceId)}</code> · 用户:{" "}
                            <code>{shortOid(obs.source.userId)}</code>
                          </span>
                        </div>
                      </li>
                    ))}
                  </ul>
                </section>

                <ScopeDeclarationSection
                  scopeDeclaration={commitDetail.scopeDeclaration}
                  dataLevel={commitDetail.dataLevel}
                />

                <ContentManifestSection entries={commitDetail.contentManifest} />

                <UnifiedPatchViewer
                  dataLevel={commitDetail.dataLevel}
                  patch={commitDetail.patch}
                  patchPath={buildCommitPatchPath(
                    spaceId,
                    projectId,
                    commitDetail.commit.oid,
                    effectiveParentOid,
                  )}
                  downloadFilename={`commit-${shortOid(commitDetail.commit.oid)}.patch`}
                  access={access}
                  queryKeyPrefix={commitQueryKey}
                />
              </>
            )}
          </>
        )}

        {selected.kind === "WIP" && (
          <>
            {snapshotQuery.isPending && <Loading label="正在读取快照详情…" />}
            {snapshotQuery.isError &&
              (activeCardState ? (
                <ResourceStateCard state={activeCardState} />
              ) : (
                <div className="alert" role="alert">
                  <span>{gitCanvasErrorText(snapshotQuery.error)}</span>
                  <Button
                    className="secondary drawer-btn-sm"
                    onClick={() => {
                      void snapshotQuery.refetch();
                    }}
                  >
                    重试
                  </Button>
                </div>
              ))}
            {snapshotDetail && (
              <>
                <section className="drawer-section">
                  <div className="drawer-badge-row">
                    <span
                      className={`drawer-badge ${
                        snapshotDetail.wip.freshness === "ACTIVE"
                          ? "drawer-badge-ok"
                          : "drawer-badge-muted"
                      }`}
                    >
                      {WIP_FRESHNESS_LABELS[snapshotDetail.wip.freshness]}
                    </span>
                    <span className="drawer-badge drawer-badge-neutral">
                      {snapshotDetail.wip.head.branch ?? "Detached HEAD"}
                    </span>
                    <span className="drawer-badge drawer-badge-neutral">
                      {DATA_LEVEL_LABELS[snapshotDetail.wip.dataLevel]}
                    </span>
                  </div>

                  <div className="drawer-kv-grid">
                    <div>
                      <span className="drawer-kv-label">基准语义</span>
                      <strong>
                        {snapshotDetail.wip.head.baseKind === "EMPTY_TREE"
                          ? "EMPTY_TREE (空仓库) → 最终工作区"
                          : `基准 HEAD (${shortOid(snapshotDetail.wip.head.baseCommitOid)}) → 最终工作区`}
                      </strong>
                    </div>
                    <div>
                      <span className="drawer-kv-label">Worktree / 序列</span>
                      <code>
                        {shortOid(snapshotDetail.wip.worktreeId)} · epoch #
                        {snapshotDetail.wip.worktreeEpoch} · seq #{snapshotDetail.wip.captureSeq}
                      </code>
                    </div>
                    <div>
                      <span className="drawer-kv-label">采集时间</span>
                      <span>{formatDateTime(snapshotDetail.wip.capturedAt)}</span>
                    </div>
                    <div>
                      <span className="drawer-kv-label">来源用户 / 设备</span>
                      <code>
                        {shortOid(snapshotDetail.wip.source.userId)} /{" "}
                        {shortOid(snapshotDetail.wip.source.deviceId)}
                      </code>
                    </div>
                  </div>

                  <div className="drawer-note-box">
                    <span className="drawer-kv-label">共享目的说明 (Note)</span>
                    <p>{snapshotDetail.note ?? "未填写共享说明。"}</p>
                  </div>
                </section>

                {/* Strictly separated captureState vs parseState */}
                <section className="drawer-section">
                  <h3>采集状态与内容解析状态</h3>
                  <div className="drawer-state-split">
                    <div className="drawer-state-card">
                      <span className="drawer-kv-label">最新采集状态 (captureState)</span>
                      <strong className="drawer-state-value">
                        {CAPTURE_STATE_LABELS[snapshotDetail.wip.captureState]} (
                        {snapshotDetail.wip.captureState})
                      </strong>
                    </div>
                    <div
                      className={`drawer-state-card ${
                        snapshotDetail.wip.parseState === "FAILED" ? "drawer-state-card-failed" : ""
                      }`}
                    >
                      <span className="drawer-kv-label">内容解析状态 (parseState)</span>
                      <strong className="drawer-state-value">
                        {PARSE_STATE_LABELS[snapshotDetail.wip.parseState]} (
                        {snapshotDetail.wip.parseState})
                      </strong>
                    </div>
                  </div>

                  {snapshotDetail.wip.parseState === "FAILED" && (
                    <div className="drawer-callout drawer-callout-danger" role="alert">
                      <AlertTriangle size={16} />
                      <div className="drawer-retry-box">
                        <span>
                          快照内容解析失败。当前严格保留失败状态，绝不会回退展示旧快照或伪造空变更。
                        </span>
                        <div className="drawer-retry-actions">
                          <Button
                            className="primary drawer-btn-sm"
                            disabled={
                              !canUploadOrManage ||
                              snapshotDetail.parseJobId === null ||
                              retryParseMutation.isPending
                            }
                            onClick={() => {
                              if (snapshotDetail.parseJobId) {
                                retryParseMutation.mutate({
                                  snapshotId: snapshotDetail.wip.snapshotId,
                                  jobId: snapshotDetail.parseJobId,
                                });
                              }
                            }}
                          >
                            <RefreshCw size={14} />
                            {retryParseMutation.isPending ? "正在提交重试…" : "重试解析"}
                          </Button>
                          {!canUploadOrManage && (
                            <span className="drawer-hint">仅项目成员及以上角色可重试解析。</span>
                          )}
                          {canUploadOrManage && snapshotDetail.parseJobId === null && (
                            <span className="drawer-hint">缺少可用解析任务 ID，暂不可重试。</span>
                          )}
                        </div>
                        {retryParseMutation.isError && (
                          <div className="drawer-inline-error">
                            {gitCanvasErrorText(retryParseMutation.error)}
                          </div>
                        )}
                        {retryParseMutation.isSuccess && (
                          <div className="drawer-inline-success" role="status">
                            已提交重试任务（当前状态：{PARSE_STATE_LABELS[retryParseMutation.data.state]}）。
                          </div>
                        )}
                      </div>
                    </div>
                  )}
                </section>

                {/* Auxiliary stagingSummary */}
                <section className="drawer-section">
                  <h3>暂存区辅助摘要 (Staging Summary)</h3>
                  <p className="drawer-hint">
                    此项仅作辅助状态参考，不用于重建完整 Git 索引（主对比语义以基准 HEAD → 最终工作区为准）。
                  </p>
                  <div className="drawer-staging-grid">
                    <div>
                      <strong>已暂存 staged ({snapshotDetail.stagingSummary.staged.length})</strong>
                      {snapshotDetail.stagingSummary.staged.length > 0 && (
                        <ul>
                          {snapshotDetail.stagingSummary.staged.map((p) => (
                            <li key={`s-${p}`}>
                              <code>{p}</code>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                    <div>
                      <strong>未暂存 unstaged ({snapshotDetail.stagingSummary.unstaged.length})</strong>
                      {snapshotDetail.stagingSummary.unstaged.length > 0 && (
                        <ul>
                          {snapshotDetail.stagingSummary.unstaged.map((p) => (
                            <li key={`u-${p}`}>
                              <code>{p}</code>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                    <div>
                      <strong>未跟踪 untracked ({snapshotDetail.stagingSummary.untracked.length})</strong>
                      {snapshotDetail.stagingSummary.untracked.length > 0 && (
                        <ul>
                          {snapshotDetail.stagingSummary.untracked.map((p) => (
                            <li key={`t-${p}`}>
                              <code>{p}</code>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  </div>
                </section>

                <ScopeDeclarationSection
                  scopeDeclaration={snapshotDetail.scopeDeclaration}
                  dataLevel={snapshotDetail.wip.dataLevel}
                />

                <ContentManifestSection entries={snapshotDetail.contentManifest} />

                <UnifiedPatchViewer
                  dataLevel={snapshotDetail.wip.dataLevel}
                  patch={snapshotDetail.patch}
                  patchPath={buildSnapshotPatchPath(
                    spaceId,
                    projectId,
                    snapshotDetail.wip.snapshotId,
                  )}
                  downloadFilename={`wip-${shortOid(snapshotDetail.wip.snapshotId)}.patch`}
                  access={access}
                  queryKeyPrefix={snapshotQueryKey}
                />
              </>
            )}
          </>
        )}
      </div>
    </aside>
  );
}

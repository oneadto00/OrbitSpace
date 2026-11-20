import { useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  CodeCommentDto,
  CodeCommentSide,
  ContentManifestStatus,
  DiffEndpointRef,
  Member,
  ProjectGraph,
  TwoPointDiffDto,
  TwoPointDiffFileEntry,
} from "@orbitspace/shared-types";
import {
  AlertTriangle,
  ArrowLeftRight,
  Check,
  Copy,
  FileCode2,
  GitCompareArrows,
  Maximize2,
  MessageSquarePlus,
  Minimize2,
  RefreshCw,
  Search,
  Sparkles,
  X,
} from "lucide-react";
import { spaceKey, type SpaceAccess } from "../../../shared/api/access";
import { HttpError } from "../../../shared/api/httpClient";
import { Button, Loading, ResourceStateCard } from "../../../shared/ui/UiComponents";
import {
  copyTextToClipboard,
  createTwoPointDiff,
  getTwoPointDiff,
  gitCanvasErrorText,
  makeIdempotencyKey,
  resolveResourceCardState,
} from "../gitCanvasApi";
import {
  CONTENT_MANIFEST_META,
  TWO_POINT_COMPARISON_META,
  TWO_POINT_DIFF_STATUS_LABELS,
  buildBackfillCommand,
  endpointsEqual,
  formatDateTime,
  isEntryComparable,
  resolveEffectiveComparisonState,
  shortOid,
} from "../labels";
import { CodeCommentPanel, type CommentComposerDraft } from "./CodeCommentPanel";

export interface DiffFocusLineTarget {
  filePath?: string;
  side?: CodeCommentSide;
  lineNumber?: number;
  commentId?: string;
  highlightNonce: number;
}

export interface TwoPointDiffDrawerProps {
  spaceId: string;
  projectId: string;
  access: SpaceAccess;
  canUploadOrManage: boolean;
  base: DiffEndpointRef;
  target: DiffEndpointRef;
  requestedDiffId?: string | null;
  graph: ProjectGraph | null;
  comments: CodeCommentDto[];
  commentsLoading: boolean;
  commentsError: unknown;
  membersById: Map<string, Member>;
  focusLineTarget?: DiffFocusLineTarget | null;
  isWide: boolean;
  onToggleWide: () => void;
  onSwapEndpoints: () => void;
  onClose: () => void;
  onRefreshComments: () => void;
  onForwardCodeComment?: (comment: CodeCommentDto) => void;
}

interface ParsedDiffLine {
  index: number;
  kind: "header" | "hunk" | "add" | "del" | "ctx" | "meta";
  oldLine: number | null;
  newLine: number | null;
  text: string;
}

const INITIAL_DIFF_LINE_LIMIT = 1200;
const HUNK_HEADER_RE = /^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@/;

/**
 * Parses unified diff text into typed rows with accurate OLD and NEW line numbers.
 * Falls back cleanly to sequential numbering if a snippet omits hunk headers.
 */
function parseUnifiedDiffLines(
  patchText: string,
  comparisonState: TwoPointDiffFileEntry["comparisonState"],
): ParsedDiffLine[] {
  const rawLines = patchText.split(/\r?\n/);
  if (rawLines.length > 1 && rawLines[rawLines.length - 1] === "") {
    rawLines.pop();
  }

  const parsed: ParsedDiffLine[] = [];
  let inHunk = false;
  let sawAnyHunk = false;
  let oldCursor = 1;
  let newCursor = 1;
  let oldRemaining = 0;
  let newRemaining = 0;

  for (let i = 0; i < rawLines.length; i++) {
    const line = rawLines[i] ?? "";
    const hunkMatch = HUNK_HEADER_RE.exec(line);
    if (hunkMatch) {
      sawAnyHunk = true;
      inHunk = true;
      oldCursor = Number.parseInt(hunkMatch[1] ?? "1", 10);
      oldRemaining = hunkMatch[2] !== undefined ? Number.parseInt(hunkMatch[2], 10) : 1;
      newCursor = Number.parseInt(hunkMatch[3] ?? "1", 10);
      newRemaining = hunkMatch[4] !== undefined ? Number.parseInt(hunkMatch[4], 10) : 1;
      parsed.push({ index: i, kind: "hunk", oldLine: null, newLine: null, text: line });
      continue;
    }

    if (
      !inHunk &&
      (line.startsWith("diff --git ") ||
        line.startsWith("index ") ||
        line.startsWith("--- ") ||
        line.startsWith("+++ ") ||
        line.startsWith("old mode ") ||
        line.startsWith("new mode ") ||
        line.startsWith("new file mode ") ||
        line.startsWith("deleted file mode ") ||
        line.startsWith("rename from ") ||
        line.startsWith("rename to ") ||
        line.startsWith("similarity index ") ||
        line.startsWith("Binary files "))
    ) {
      parsed.push({ index: i, kind: "header", oldLine: null, newLine: null, text: line });
      continue;
    }

    if (inHunk) {
      if (line.startsWith("\\")) {
        parsed.push({ index: i, kind: "meta", oldLine: null, newLine: null, text: line });
        continue;
      }
      if (oldRemaining <= 0 && newRemaining <= 0) {
        inHunk = false;
        parsed.push({ index: i, kind: "header", oldLine: null, newLine: null, text: line });
        continue;
      }
      if (line.startsWith("+")) {
        parsed.push({ index: i, kind: "add", oldLine: null, newLine: newCursor, text: line });
        newCursor += 1;
        if (newRemaining > 0) newRemaining -= 1;
        continue;
      }
      if (line.startsWith("-")) {
        parsed.push({ index: i, kind: "del", oldLine: oldCursor, newLine: null, text: line });
        oldCursor += 1;
        if (oldRemaining > 0) oldRemaining -= 1;
        continue;
      }
      parsed.push({
        index: i,
        kind: "ctx",
        oldLine: oldCursor,
        newLine: newCursor,
        text: line,
      });
      oldCursor += 1;
      newCursor += 1;
      if (oldRemaining > 0) oldRemaining -= 1;
      if (newRemaining > 0) newRemaining -= 1;
    } else {
      parsed.push({ index: i, kind: "header", oldLine: null, newLine: null, text: line });
    }
  }

  if (!sawAnyHunk && parsed.length > 0) {
    let fallbackCursor = 1;
    return parsed.map((row) => {
      if (row.kind !== "header") return row;
      if (row.text.startsWith("diff --git ") || row.text.startsWith("--- ") || row.text.startsWith("+++ ")) {
        return row;
      }
      const lineNo = fallbackCursor++;
      if (comparisonState === "DELETED") {
        return { ...row, kind: "del", oldLine: lineNo, newLine: null };
      }
      if (comparisonState === "ADDED") {
        return { ...row, kind: "add", oldLine: null, newLine: lineNo };
      }
      return { ...row, kind: "ctx", oldLine: lineNo, newLine: lineNo };
    });
  }

  return parsed;
}

function describeEndpointDetail(ref: DiffEndpointRef, graph: ProjectGraph | null): string {
  if (ref.kind === "COMMIT") {
    const commit = graph?.commits.find((c) => c.oid === ref.id);
    const subject = commit?.observations[0]?.metadata.subject;
    return subject ? `Commit ${shortOid(ref.id)} · ${subject}` : `Commit ${shortOid(ref.id)}`;
  }
  const wip = graph?.wipNodes.find((w) => w.snapshotId === ref.id);
  if (wip) {
    return `WIP ${wip.head.branch ?? "Detached HEAD"} (${shortOid(ref.id)}) · seq #${wip.captureSeq}`;
  }
  return `WIP ${shortOid(ref.id)}`;
}

function manifestBadgeClass(status: ContentManifestStatus): string {
  const tone = CONTENT_MANIFEST_META[status].tone;
  return `drawer-badge drawer-badge-${tone}`;
}

function BackfillCommandHints({
  targets,
}: {
  targets: Array<{ role: string; endpoint: DiffEndpointRef }>;
}) {
  const [copiedId, setCopiedId] = useState<string | null>(null);

  if (targets.length === 0) return null;

  return (
    <div className="diff-backfill-box">
      <span className="diff-backfill-title">
        受控补传指引（在持有对应工作区/仓库的本地终端确认预览后执行）：
      </span>
      <ul className="diff-backfill-list">
        {targets.map(({ role, endpoint }) => {
          const cmd = buildBackfillCommand(endpoint.id);
          const token = `${role}:${endpoint.id}`;
          const isCopied = copiedId === token;
          return (
            <li key={token} className="diff-backfill-row">
              <div className="diff-backfill-cmd">
                <span className="diff-backfill-role">{role}</span>
                <code>{cmd}</code>
              </div>
              <Button
                className="secondary drawer-btn-sm"
                onClick={() => {
                  void copyTextToClipboard(cmd).then((ok) => {
                    if (ok) {
                      setCopiedId(token);
                      window.setTimeout(() => setCopiedId((prev) => (prev === token ? null : prev)), 2000);
                    }
                  });
                }}
              >
                {isCopied ? <Check size={12} /> : <Copy size={12} />}
                {isCopied ? "已复制" : "复制命令"}
              </Button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export function TwoPointDiffDrawer({
  spaceId,
  projectId,
  access,
  canUploadOrManage,
  base,
  target,
  requestedDiffId,
  graph,
  comments,
  commentsLoading,
  commentsError,
  membersById,
  focusLineTarget,
  isWide,
  onToggleWide,
  onSwapEndpoints,
  onClose,
  onRefreshComments,
  onForwardCodeComment,
}: TwoPointDiffDrawerProps) {
  const queryClient = useQueryClient();
  const [recomputeNonce, setRecomputeNonce] = useState(0);
  const [useFreshCreate, setUseFreshCreate] = useState(false);
  const createIdemKeyRef = useRef<string>(makeIdempotencyKey());
  const lastPairSigRef = useRef<string>("");

  const pairSig = `${access.spaceId}:${access.membershipEpoch}:${projectId}:${base.kind}:${base.id}:${target.kind}:${target.id}`;
  if (lastPairSigRef.current !== pairSig) {
    lastPairSigRef.current = pairSig;
    createIdemKeyRef.current = makeIdempotencyKey();
  }

  useEffect(() => {
    setUseFreshCreate(false);
    setRecomputeNonce(0);
    setSelectedFilePath(null);
    setComposerDraft(null);
    setFocusedCommentId(null);
    setLineFlashState(null);
    setAnchorNotice(null);
  }, [pairSig, requestedDiffId]);

  const effectiveDiffIdToLoad = !useFreshCreate ? requestedDiffId ?? null : null;

  const createQuery = useQuery({
    queryKey: [
      ...spaceKey(access),
      "project",
      projectId,
      "two-point-diff-create",
      base.kind,
      base.id,
      target.kind,
      target.id,
      recomputeNonce,
    ] as const,
    enabled: !effectiveDiffIdToLoad,
    queryFn: ({ signal }) =>
      createTwoPointDiff(
        spaceId,
        projectId,
        { base, target },
        access,
        createIdemKeyRef.current,
        signal,
      ),
  });

  const activeDiffId = effectiveDiffIdToLoad ?? createQuery.data?.diffId ?? null;
  const shouldPollGet =
    Boolean(effectiveDiffIdToLoad) ||
    (createQuery.data !== undefined && createQuery.data.status === "PENDING");

  const detailQuery = useQuery({
    queryKey: [
      ...spaceKey(access),
      "project",
      projectId,
      "two-point-diff-detail",
      activeDiffId ?? "NONE",
    ] as const,
    enabled: Boolean(activeDiffId) && shouldPollGet,
    queryFn: ({ signal }) => getTwoPointDiff(spaceId, projectId, activeDiffId!, access, signal),
    refetchInterval: (query) => (query.state.data?.status === "PENDING" ? 2500 : false),
  });

  const diff: TwoPointDiffDto | undefined = shouldPollGet
    ? (detailQuery.data ?? createQuery.data)
    : createQuery.data;
  const activeError = shouldPollGet
    ? (detailQuery.error ?? createQuery.error)
    : createQuery.error;
  const isInitialLoading =
    (effectiveDiffIdToLoad ? detailQuery.isPending : createQuery.isPending) && !diff;

  const [selectedFilePath, setSelectedFilePath] = useState<string | null>(null);
  const [fileFilterMode, setFileFilterMode] = useState<"ALL" | "COMPARABLE" | "INCOMPARABLE">("ALL");
  const [fileSearch, setFileSearch] = useState("");
  const [showAllPatchLines, setShowAllPatchLines] = useState(false);

  const [composerDraft, setComposerDraft] = useState<CommentComposerDraft | null>(null);
  const [focusedCommentId, setFocusedCommentId] = useState<string | null>(null);
  const [lineFlashState, setLineFlashState] = useState<{
    filePath: string;
    side: CodeCommentSide;
    lineNumber: number;
    nonce: number;
  } | null>(null);
  const [anchorNotice, setAnchorNotice] = useState<string | null>(null);

  const lineRowRefs = useRef<Map<string, HTMLDivElement>>(new Map());
  const appliedFocusNonceRef = useRef<number | null>(null);

  const resolvedBase = diff?.base ?? base;
  const resolvedTarget = diff?.target ?? target;

  const diffComments = useMemo(() => {
    return comments.filter((c) => {
      if (diff && c.diffId && c.diffId === diff.diffId) return true;
      return (
        endpointsEqual(c.base, resolvedBase) && endpointsEqual(c.target, resolvedTarget)
      );
    });
  }, [comments, diff, resolvedBase, resolvedTarget]);

  const commentCountByFile = useMemo(() => {
    const map = new Map<string, number>();
    for (const c of diffComments) {
      map.set(c.filePath, (map.get(c.filePath) ?? 0) + 1);
    }
    return map;
  }, [diffComments]);

  useEffect(() => {
    if (!diff || diff.files.length === 0) return;
    if (
      selectedFilePath &&
      diff.files.some((f) => f.path === selectedFilePath || f.oldPath === selectedFilePath)
    ) {
      return;
    }
    if (focusLineTarget?.filePath) {
      const targetMatch = diff.files.find(
        (f) => f.path === focusLineTarget.filePath || f.oldPath === focusLineTarget.filePath,
      );
      if (targetMatch) {
        setSelectedFilePath(targetMatch.path);
        return;
      }
    }
    setSelectedFilePath(diff.files[0]!.path);
  }, [diff, focusLineTarget?.filePath, selectedFilePath]);

  useEffect(() => {
    if (!focusLineTarget || !diff) return;
    if (appliedFocusNonceRef.current === focusLineTarget.highlightNonce) return;
    appliedFocusNonceRef.current = focusLineTarget.highlightNonce;

    if (focusLineTarget.commentId) {
      setFocusedCommentId(focusLineTarget.commentId);
    }

    if (!focusLineTarget.filePath) {
      setAnchorNotice(null);
      return;
    }

    setFileFilterMode("ALL");
    setFileSearch("");

    const matchedEntry = diff.files.find(
      (f) => f.path === focusLineTarget.filePath || f.oldPath === focusLineTarget.filePath,
    );

    if (!matchedEntry) {
      setAnchorNotice(
        `目标文件 ${focusLineTarget.filePath} 在当前对比范围内不可用、未共享或无权限查看（原锚点：${
          focusLineTarget.side ?? "NEW"
        } 第 ${focusLineTarget.lineNumber ?? "?"} 行）。`,
      );
      return;
    }

    setSelectedFilePath(matchedEntry.path);
    setShowAllPatchLines(true);

    if (!isEntryComparable(matchedEntry)) {
      const effectiveState = resolveEffectiveComparisonState(matchedEntry);
      setAnchorNotice(
        `目标文件 ${matchedEntry.path} 当前状态为 ${TWO_POINT_COMPARISON_META[effectiveState].label}（Base: ${matchedEntry.baseStatus}, Target: ${matchedEntry.targetStatus}），缺少可用内容或不受支持，无法定位代码行（原锚点：${
          focusLineTarget.side ?? "NEW"
        } 第 ${focusLineTarget.lineNumber ?? "?"} 行）。`,
      );
      return;
    }

    if (focusLineTarget.lineNumber && focusLineTarget.side) {
      setLineFlashState({
        filePath: matchedEntry.path,
        side: focusLineTarget.side,
        lineNumber: focusLineTarget.lineNumber,
        nonce: focusLineTarget.highlightNonce,
      });
    } else {
      setAnchorNotice(null);
    }
  }, [diff, focusLineTarget]);

  const filteredFiles = useMemo(() => {
    if (!diff) return [];
    const q = fileSearch.trim().toLowerCase();
    return diff.files.filter((entry) => {
      const comparable = isEntryComparable(entry);
      if (fileFilterMode === "COMPARABLE" && !comparable) return false;
      if (fileFilterMode === "INCOMPARABLE" && comparable) return false;
      if (q) {
        const pathMatch = entry.path.toLowerCase().includes(q);
        const oldMatch = entry.oldPath?.toLowerCase().includes(q) ?? false;
        if (!pathMatch && !oldMatch) return false;
      }
      return true;
    });
  }, [diff, fileFilterMode, fileSearch]);

  const selectedEntry = useMemo(() => {
    if (!diff || !selectedFilePath) return null;
    return (
      diff.files.find((f) => f.path === selectedFilePath || f.oldPath === selectedFilePath) ?? null
    );
  }, [diff, selectedFilePath]);

  useEffect(() => {
    setShowAllPatchLines(false);
  }, [selectedEntry?.path]);

  const parsedPatchLines = useMemo(() => {
    if (!selectedEntry || !isEntryComparable(selectedEntry) || !selectedEntry.patchText) {
      return [];
    }
    return parseUnifiedDiffLines(
      selectedEntry.patchText,
      resolveEffectiveComparisonState(selectedEntry),
    );
  }, [selectedEntry]);

  const commentsByAnchor = useMemo(() => {
    const map = new Map<string, CodeCommentDto[]>();
    if (!selectedEntry) return map;
    for (const c of diffComments) {
      const sameFile =
        c.filePath === selectedEntry.path ||
        (selectedEntry.oldPath !== null && c.filePath === selectedEntry.oldPath);
      if (!sameFile) continue;
      const key = `${c.side}:${c.lineNumber}`;
      const list = map.get(key) ?? [];
      list.push(c);
      map.set(key, list);
    }
    return map;
  }, [diffComments, selectedEntry]);

  useEffect(() => {
    if (!lineFlashState || !selectedEntry) return;
    const matchesFile =
      selectedEntry.path === lineFlashState.filePath ||
      selectedEntry.oldPath === lineFlashState.filePath;
    if (!matchesFile) return;

    const matchingRow = parsedPatchLines.find((row) =>
      lineFlashState.side === "OLD"
        ? row.oldLine === lineFlashState.lineNumber
        : row.newLine === lineFlashState.lineNumber,
    );

    if (!matchingRow) {
      if (isEntryComparable(selectedEntry)) {
        setAnchorNotice(
          `已定位文件 ${selectedEntry.path}（${lineFlashState.side} 第 ${lineFlashState.lineNumber} 行），当前 Patch 片段未包含该行上下文。`,
        );
      }
      return;
    }

    setAnchorNotice(null);
    if (!showAllPatchLines && matchingRow.index >= INITIAL_DIFF_LINE_LIMIT) {
      setShowAllPatchLines(true);
      return;
    }

    const rowKey = `${selectedEntry.path}:${matchingRow.index}`;
    const timer = window.setTimeout(() => {
      const el = lineRowRefs.current.get(rowKey);
      el?.scrollIntoView({ behavior: "smooth", block: "center" });
    }, 60);
    return () => window.clearTimeout(timer);
  }, [lineFlashState, parsedPatchLines, selectedEntry, showAllPatchLines]);

  const backfillTargets = useMemo(() => {
    if (!diff) return [];
    let baseNeeds = false;
    let targetNeeds = false;
    for (const entry of diff.files) {
      if (entry.baseStatus === "MISSING" || entry.baseStatus === "NOT_SHARED") {
        baseNeeds = true;
      }
      if (entry.targetStatus === "MISSING" || entry.targetStatus === "NOT_SHARED") {
        targetNeeds = true;
      }
    }
    const list: Array<{ role: string; endpoint: DiffEndpointRef }> = [];
    if (baseNeeds || (!baseNeeds && !targetNeeds && diff.summary.unsharedOrMissingFiles > 0)) {
      list.push({ role: "Base (旧端)", endpoint: diff.base });
    }
    if (targetNeeds) {
      list.push({ role: "Target (新端)", endpoint: diff.target });
    }
    return list;
  }, [diff]);

  const activeCardState = resolveResourceCardState(activeError);
  const isInsufficientError =
    activeError instanceof HttpError && activeError.code === "DIFF_DATA_INSUFFICIENT";

  const handleRecompute = () => {
    createIdemKeyRef.current = makeIdempotencyKey();
    setUseFreshCreate(true);
    setRecomputeNonce((n) => n + 1);
  };

  const handleRefresh = () => {
    if (activeDiffId) {
      void queryClient.invalidateQueries({
        queryKey: [...spaceKey(access), "project", projectId, "two-point-diff-detail", activeDiffId],
      });
    } else {
      handleRecompute();
    }
  };

  const jumpToLine = (
    filePath: string,
    side: CodeCommentSide,
    lineNumber: number,
    commentId?: string,
  ) => {
    setSelectedFilePath(filePath);
    if (commentId) setFocusedCommentId(commentId);
    setLineFlashState({
      filePath,
      side,
      lineNumber,
      nonce: Date.now(),
    });
  };

  const truncated = !showAllPatchLines && parsedPatchLines.length > INITIAL_DIFF_LINE_LIMIT;
  const displayedPatchLines = truncated
    ? parsedPatchLines.slice(0, INITIAL_DIFF_LINE_LIMIT)
    : parsedPatchLines;

  const comparableCount = diff
    ? diff.files.filter((f) => isEntryComparable(f)).length
    : 0;
  const incomparableCount = diff ? diff.files.length - comparableCount : 0;

  return (
    <aside className="git-detail-drawer git-diff-drawer" aria-label="受控双点 Diff 抽屉">
      <header className="drawer-header">
        <div className="drawer-title-group">
          <span className="drawer-kind-pill diff-kind-pill">
            <GitCompareArrows size={15} /> 受控双点文件树 Diff
          </span>
          <span className="drawer-badge drawer-badge-neutral">非单提交父节点对比</span>
        </div>
        <div className="drawer-header-actions">
          <Button
            className="secondary drawer-btn-sm"
            onClick={onSwapEndpoints}
            title="交换 Base 与 Target 端点"
          >
            <ArrowLeftRight size={13} /> 交换两端
          </Button>
          <Button
            className="secondary drawer-btn-sm"
            onClick={handleRecompute}
            disabled={createQuery.isFetching}
            title="重新计算双点 Diff（例如 CLI 补传后）"
          >
            <RefreshCw size={13} /> 重新计算
          </Button>
          <Button
            className="icon-button secondary"
            onClick={onToggleWide}
            aria-label={isWide ? "恢复标准抽屉宽度" : "加宽双点 Diff 视图"}
            title={isWide ? "恢复标准宽度" : "加宽视图"}
          >
            {isWide ? <Minimize2 size={15} /> : <Maximize2 size={15} />}
          </Button>
          <Button
            className="icon-button secondary"
            onClick={onClose}
            aria-label="关闭双点 Diff 抽屉"
          >
            <X size={16} />
          </Button>
        </div>
      </header>

      <div className="drawer-body">
        <section className="diff-endpoints-card">
          <div className="diff-endpoint-row">
            <span className="diff-role-pill diff-role-base">Base 旧端</span>
            <span className="diff-endpoint-text">{describeEndpointDetail(resolvedBase, graph)}</span>
          </div>
          <div className="diff-endpoint-row">
            <span className="diff-role-pill diff-role-target">Target 新端</span>
            <span className="diff-endpoint-text">{describeEndpointDetail(resolvedTarget, graph)}</span>
          </div>
          <p className="drawer-hint">
            受控双点比较展示两个任意节点（Commit / WIP）在授权范围内的文件树差异，语义上严格区分于“单提交对父提交增量 Patch”。
          </p>
        </section>

        {isInitialLoading && <Loading label="正在计算受控双点文件树 Diff…" />}

        {Boolean(activeError) &&
          (activeCardState ? (
            <div className="diff-unavailable-block">
              <ResourceStateCard state={activeCardState} />
              <p className="drawer-hint">
                当前账户无权读取该对比资源或对应节点已过期/清除，无法展示代码差异或批注代码行。
              </p>
            </div>
          ) : isInsufficientError ? (
            <div className="drawer-callout drawer-callout-warn" role="alert">
              <AlertTriangle size={16} />
              <div className="diff-insufficient-body">
                <strong>共享数据不足 — 无法直接完成双点比较</strong>
                <p>{gitCanvasErrorText(activeError)}</p>
                <BackfillCommandHints
                  targets={[
                    { role: "Base (旧端)", endpoint: base },
                    { role: "Target (新端)", endpoint: target },
                  ]}
                />
              </div>
            </div>
          ) : (
            <div className="alert" role="alert">
              <span>{gitCanvasErrorText(activeError)}</span>
              {effectiveDiffIdToLoad ? (
                <Button className="secondary drawer-btn-sm" onClick={handleRecompute}>
                  尝试重新计算端点 Diff
                </Button>
              ) : (
                <Button
                  className="secondary drawer-btn-sm"
                  onClick={() => {
                    void createQuery.refetch();
                  }}
                >
                  重试
                </Button>
              )}
            </div>
          ))}

        {diff && (
          <>
            <section className="drawer-section">
              <div className="drawer-section-head">
                <div className="drawer-badge-row">
                  <span
                    className={`drawer-badge ${
                      diff.status === "READY"
                        ? "drawer-badge-ok"
                        : diff.status === "FAILED"
                          ? "drawer-badge-danger"
                          : "drawer-badge-warn"
                    }`}
                  >
                    {TWO_POINT_DIFF_STATUS_LABELS[diff.status]}
                  </span>
                  {diff.enrichmentJobId && (
                    <span className="drawer-badge drawer-badge-neutral">
                      <Sparkles size={12} />
                      {diff.status === "PENDING" ? "符号解析中…" : "含符号变更辅助"}
                    </span>
                  )}
                  <span className="drawer-count">{formatDateTime(diff.createdAt)}</span>
                </div>
                {diff.status === "PENDING" && (
                  <Button className="secondary drawer-btn-sm" onClick={handleRefresh}>
                    <RefreshCw size={12} /> 检查状态
                  </Button>
                )}
              </div>

              <div className="diff-summary-grid">
                <div className="diff-summary-cell">
                  <span className="drawer-kv-label">可确定比较</span>
                  <strong>{diff.summary.comparableFiles} 项</strong>
                </div>
                <div className="diff-summary-cell">
                  <span className="drawer-kv-label">未共享 / 缺失内容</span>
                  <strong>{diff.summary.unsharedOrMissingFiles} 项</strong>
                </div>
                <div className="diff-summary-cell">
                  <span className="drawer-kv-label">不支持条目</span>
                  <strong>{diff.summary.unsupportedFiles} 项</strong>
                </div>
              </div>

              {(diff.summary.hasInsufficientData ||
                diff.summary.unsharedOrMissingFiles > 0 ||
                diff.summary.unsupportedFiles > 0 ||
                incomparableCount > 0) && (
                <div className="drawer-callout drawer-callout-warn" role="status">
                  <AlertTriangle size={16} />
                  <div className="diff-insufficient-body">
                    <strong>五态诚实边界提示：部分路径不可比较</strong>
                    <p>
                      仅当两端对同一路径均具备 <code>AVAILABLE</code> 或{" "}
                      <code>ABSENT_CONFIRMED</code> 证据时，才渲染确定性新增 / 删除 / 修改差异；
                      任意一端为 <code>NOT_SHARED</code>、<code>MISSING</code> 或{" "}
                      <code>UNSUPPORTED</code> 时，均显式标记为不可比较，绝不将其显示为文件删除或拼凑看似完整的仓库差异。
                    </p>
                    <BackfillCommandHints targets={backfillTargets} />
                  </div>
                </div>
              )}
            </section>

            {anchorNotice && (
              <div className="drawer-callout drawer-callout-warn" role="status">
                <AlertTriangle size={15} />
                <span>{anchorNotice}</span>
              </div>
            )}

            <section className="drawer-section">
              <div className="drawer-section-head">
                <h3>文件树差异清单 ({diff.files.length})</h3>
                <div className="diff-file-filters">
                  <button
                    type="button"
                    className={`comment-tab-btn ${fileFilterMode === "ALL" ? "is-active" : ""}`}
                    onClick={() => setFileFilterMode("ALL")}
                  >
                    全部 ({diff.files.length})
                  </button>
                  <button
                    type="button"
                    className={`comment-tab-btn ${fileFilterMode === "COMPARABLE" ? "is-active" : ""}`}
                    onClick={() => setFileFilterMode("COMPARABLE")}
                  >
                    可比较 ({comparableCount})
                  </button>
                  <button
                    type="button"
                    className={`comment-tab-btn ${fileFilterMode === "INCOMPARABLE" ? "is-active" : ""}`}
                    onClick={() => setFileFilterMode("INCOMPARABLE")}
                  >
                    不可比较 ({incomparableCount})
                  </button>
                </div>
              </div>

              {diff.files.length > 0 && (
                <label className="git-search-box diff-file-search">
                  <Search size={14} aria-hidden="true" />
                  <input
                    type="search"
                    value={fileSearch}
                    onChange={(e) => setFileSearch(e.target.value)}
                    placeholder="过滤文件路径…"
                    aria-label="过滤双点 Diff 文件路径"
                  />
                </label>
              )}

              {diff.files.length === 0 ? (
                <p className="drawer-hint">
                  {diff.status === "PENDING"
                    ? "差异文件列表仍在生成中，请稍候刷新查看。"
                    : "两端点在授权范围内暂无披露的文件差异条目。"}
                </p>
              ) : filteredFiles.length === 0 ? (
                <p className="drawer-hint">当前过滤条件下无匹配文件。</p>
              ) : (
                <ul className="diff-file-list">
                  {filteredFiles.map((entry) => {
                    const effectiveState = resolveEffectiveComparisonState(entry);
                    const meta = TWO_POINT_COMPARISON_META[effectiveState];
                    const isSelected = selectedEntry?.path === entry.path;
                    const fileComments = commentCountByFile.get(entry.path) ?? 0;

                    return (
                      <li key={entry.path}>
                        <button
                          type="button"
                          className={`diff-file-item ${isSelected ? "is-selected" : ""}`}
                          onClick={() => setSelectedFilePath(entry.path)}
                        >
                          <div className="diff-file-item-top">
                            <code className="diff-file-path">{entry.path}</code>
                            <div className="diff-file-item-badges">
                              {fileComments > 0 && (
                                <span className="drawer-badge drawer-badge-info">
                                  批注 {fileComments}
                                </span>
                              )}
                              <span className={`drawer-badge drawer-badge-${meta.tone}`}>
                                {meta.shortLabel}
                              </span>
                            </div>
                          </div>
                          {entry.oldPath && entry.oldPath !== entry.path && (
                            <div className="diff-file-oldpath">
                              原路径: <code>{entry.oldPath}</code>
                            </div>
                          )}
                          <div className="diff-file-evidence">
                            <span>Base: {entry.baseStatus}</span>
                            <span>Target: {entry.targetStatus}</span>
                          </div>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>

            {selectedEntry && (
              <section className="drawer-section">
                <div className="drawer-section-head">
                  <h3>
                    <FileCode2 size={16} /> <code>{selectedEntry.path}</code>
                  </h3>
                  <span
                    className={`drawer-badge drawer-badge-${
                      TWO_POINT_COMPARISON_META[resolveEffectiveComparisonState(selectedEntry)].tone
                    }`}
                  >
                    {TWO_POINT_COMPARISON_META[resolveEffectiveComparisonState(selectedEntry)].label}
                  </span>
                </div>

                <div className="diff-evidence-pair">
                  <span className={manifestBadgeClass(selectedEntry.baseStatus)}>
                    Base: {CONTENT_MANIFEST_META[selectedEntry.baseStatus].label} (
                    {selectedEntry.baseStatus})
                  </span>
                  <span className={manifestBadgeClass(selectedEntry.targetStatus)}>
                    Target: {CONTENT_MANIFEST_META[selectedEntry.targetStatus].label} (
                    {selectedEntry.targetStatus})
                  </span>
                </div>

                {!isEntryComparable(selectedEntry) ? (
                  <div className="diff-incomparable-card" role="status">
                    <strong>
                      {resolveEffectiveComparisonState(selectedEntry) === "INCOMPARABLE_UNSUPPORTED"
                        ? "不支持比较（UNSUPPORTED）"
                        : "未共享 / 缺少基础内容不可比较"}
                    </strong>
                    <p>
                      {selectedEntry.reasonMessage ??
                        (resolveEffectiveComparisonState(selectedEntry) === "INCOMPARABLE_UNSUPPORTED"
                          ? "该路径为 Git 子模块、LFS 指针或当前不支持解析的条目，系统不生成确定性代码差异。"
                          : "因为至少一端为 NOT_SHARED 或 MISSING，无法确定该文件是否新增、修改或删除；系统绝不把未共享或缺失文件展示为已删除。")}
                    </p>
                    {(selectedEntry.baseStatus === "NOT_SHARED" ||
                      selectedEntry.baseStatus === "MISSING" ||
                      selectedEntry.targetStatus === "NOT_SHARED" ||
                      selectedEntry.targetStatus === "MISSING") && (
                      <BackfillCommandHints
                        targets={[
                          ...(selectedEntry.baseStatus === "NOT_SHARED" ||
                          selectedEntry.baseStatus === "MISSING"
                            ? [{ role: "Base (旧端)", endpoint: diff.base }]
                            : []),
                          ...(selectedEntry.targetStatus === "NOT_SHARED" ||
                          selectedEntry.targetStatus === "MISSING"
                            ? [{ role: "Target (新端)", endpoint: diff.target }]
                            : []),
                        ]}
                      />
                    )}
                  </div>
                ) : (
                  <>
                    {selectedEntry.symbols.length > 0 && (
                      <div className="diff-symbols-bar" aria-label="代码符号变更辅助">
                        <span className="drawer-kv-label">符号变更辅助：</span>
                        <div className="diff-symbols-list">
                          {selectedEntry.symbols.map((sym, idx) => {
                            const targetLine = sym.newStartLine ?? sym.oldStartLine;
                            const targetSide: CodeCommentSide =
                              sym.newStartLine !== null ? "NEW" : "OLD";
                            return (
                              <button
                                key={`${sym.kind}:${sym.name}:${idx}`}
                                type="button"
                                className="diff-symbol-chip"
                                disabled={targetLine === null}
                                onClick={() => {
                                  if (targetLine !== null) {
                                    jumpToLine(selectedEntry.path, targetSide, targetLine);
                                  }
                                }}
                              >
                                <span className="diff-symbol-kind">{sym.kind}</span>
                                <code>{sym.name}</code>
                                {targetLine !== null && <span>L{targetLine}</span>}
                              </button>
                            );
                          })}
                        </div>
                      </div>
                    )}

                    {parsedPatchLines.length === 0 ? (
                      <div className="drawer-callout drawer-callout-muted">
                        <strong>
                          {resolveEffectiveComparisonState(selectedEntry) === "UNCHANGED"
                            ? "两端内容已核验一致，无文本变更行。"
                            : "当前条目未携带逐行文本 Patch（例如空文件或二进制受控摘要）。"}
                        </strong>
                        {selectedEntry.reasonMessage && <p>{selectedEntry.reasonMessage}</p>}
                      </div>
                    ) : (
                      <div className="drawer-diff-box" role="region" aria-label="双点代码差异正文">
                        <div className="diff-patch-hint">
                          点击行号或行尾「+ 批注」即可在 <code>OLD</code> / <code>NEW</code> 对应行发起代码批注。
                        </div>
                        <div className="drawer-diff-pre diff-table">
                          {displayedPatchLines.map((row) => {
                            const rowKey = `${selectedEntry.path}:${row.index}`;
                            const isLineFlashed =
                              !!lineFlashState &&
                              (lineFlashState.filePath === selectedEntry.path ||
                                lineFlashState.filePath === selectedEntry.oldPath) &&
                              ((lineFlashState.side === "OLD" &&
                                row.oldLine === lineFlashState.lineNumber) ||
                                (lineFlashState.side === "NEW" &&
                                  row.newLine === lineFlashState.lineNumber));

                            const oldComments =
                              row.oldLine !== null
                                ? commentsByAnchor.get(`OLD:${row.oldLine}`) ?? []
                                : [];
                            const newComments =
                              row.newLine !== null
                                ? commentsByAnchor.get(`NEW:${row.newLine}`) ?? []
                                : [];
                            const lineComments = [...oldComments, ...newComments];

                            const cls =
                              row.kind === "header"
                                ? "diff-line-header"
                                : row.kind === "hunk"
                                  ? "diff-line-hunk"
                                  : row.kind === "add"
                                    ? "diff-line-add"
                                    : row.kind === "del"
                                      ? "diff-line-del"
                                      : "diff-line-ctx";

                            const canCommentRow =
                              canUploadOrManage &&
                              (row.kind === "add" || row.kind === "del" || row.kind === "ctx") &&
                              (row.oldLine !== null || row.newLine !== null);

                            const openComposerForRow = (preferredSide?: CodeCommentSide) => {
                              if (!canCommentRow) return;
                              const chosenSide: CodeCommentSide =
                                preferredSide ??
                                (row.kind === "del"
                                  ? "OLD"
                                  : row.kind === "add"
                                    ? "NEW"
                                    : row.newLine !== null
                                      ? "NEW"
                                      : "OLD");
                              const chosenLine =
                                chosenSide === "OLD"
                                  ? row.oldLine ?? row.newLine ?? 1
                                  : row.newLine ?? row.oldLine ?? 1;

                              setComposerDraft({
                                filePath: selectedEntry.path,
                                side: chosenSide,
                                lineNumber: chosenLine,
                                allowSideToggle: row.oldLine !== null && row.newLine !== null,
                                oldLineNumber: row.oldLine,
                                newLineNumber: row.newLine,
                              });
                            };

                            return (
                              <div
                                key={
                                  isLineFlashed && lineFlashState
                                    ? `${rowKey}-flash-${lineFlashState.nonce}`
                                    : rowKey
                                }
                                ref={(el) => {
                                  if (el) lineRowRefs.current.set(rowKey, el);
                                  else lineRowRefs.current.delete(rowKey);
                                }}
                                className={`diff-line-dual ${cls} ${
                                  isLineFlashed ? "diff-line-flash" : ""
                                }`}
                              >
                                <button
                                  type="button"
                                  className="diff-ln-btn"
                                  disabled={!canUploadOrManage || row.oldLine === null}
                                  title={
                                    row.oldLine !== null
                                      ? `在旧侧 OLD 第 ${row.oldLine} 行发起批注`
                                      : undefined
                                  }
                                  onClick={() => openComposerForRow("OLD")}
                                >
                                  {row.oldLine ?? ""}
                                </button>
                                <button
                                  type="button"
                                  className="diff-ln-btn"
                                  disabled={!canUploadOrManage || row.newLine === null}
                                  title={
                                    row.newLine !== null
                                      ? `在新侧 NEW 第 ${row.newLine} 行发起批注`
                                      : undefined
                                  }
                                  onClick={() => openComposerForRow("NEW")}
                                >
                                  {row.newLine ?? ""}
                                </button>
                                <span className="diff-code">{row.text || " "}</span>
                                <div className="diff-line-gutter-actions">
                                  {lineComments.length > 0 && (
                                    <button
                                      type="button"
                                      className="diff-line-comment-badge"
                                      title="查看该行代码批注"
                                      onClick={() => {
                                        const first = lineComments[0]!;
                                        setFocusedCommentId(first.commentId);
                                        jumpToLine(
                                          selectedEntry.path,
                                          first.side,
                                          first.lineNumber,
                                          first.commentId,
                                        );
                                      }}
                                    >
                                      {lineComments.length} 批注
                                    </button>
                                  )}
                                  {canCommentRow && (
                                    <button
                                      type="button"
                                      className="diff-line-add-comment"
                                      aria-label={`对 ${selectedEntry.path} 第 ${
                                        row.newLine ?? row.oldLine
                                      } 行发起批注`}
                                      onClick={() => openComposerForRow()}
                                    >
                                      <MessageSquarePlus size={12} /> 批注
                                    </button>
                                  )}
                                </div>
                              </div>
                            );
                          })}
                        </div>
                        {truncated && (
                          <div className="drawer-diff-more">
                            <span>
                              已展示前 {INITIAL_DIFF_LINE_LIMIT} 行（共 {parsedPatchLines.length} 行）
                            </span>
                            <Button
                              className="secondary drawer-btn-sm"
                              onClick={() => setShowAllPatchLines(true)}
                            >
                              展开全部 {parsedPatchLines.length} 行
                            </Button>
                          </div>
                        )}
                      </div>
                    )}
                  </>
                )}
              </section>
            )}

            <CodeCommentPanel
              spaceId={spaceId}
              projectId={projectId}
              access={access}
              canUploadOrManage={canUploadOrManage}
              diff={diff}
              selectedFile={selectedEntry}
              comments={diffComments}
              commentsLoading={commentsLoading}
              commentsError={commentsError}
              membersById={membersById}
              composerDraft={composerDraft}
              onChangeComposerDraft={setComposerDraft}
              focusedCommentId={focusedCommentId}
              highlightNonce={lineFlashState?.nonce ?? focusLineTarget?.highlightNonce}
              onSelectCommentAnchor={(comment) => {
                jumpToLine(comment.filePath, comment.side, comment.lineNumber, comment.commentId);
              }}
              onForwardCodeComment={onForwardCodeComment}
              onRefreshComments={onRefreshComments}
            />
          </>
        )}
      </div>
    </aside>
  );
}

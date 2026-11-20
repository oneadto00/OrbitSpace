import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  CodeCommentDto,
  CodeCommentSide,
  CommitNode,
  DiffEndpointRef,
  Member,
  ObservedRef,
  ProjectGraph,
  SpaceSummary,
  WipNode,
} from "@orbitspace/shared-types";
import {
  AlertTriangle,
  ArrowLeftRight,
  GitBranch,
  GitCompareArrows,
  History,
  RefreshCw,
  Search,
  X,
} from "lucide-react";
import {
  bindSpaceConnection,
  spaceKey,
  spacesKey,
  type SpaceAccess,
} from "../../shared/api/access";
import { api } from "../../shared/api/httpClient";
import type { GitWorkspaceSlotProps } from "../../shared/contracts/gitWorkspaceContract";
import { Button, Loading, ResourceStateCard } from "../../shared/ui/UiComponents";
import {
  fetchProjectGraph,
  fetchSpaceMembers,
  getTwoPointDiff,
  gitCanvasErrorText,
  listCodeComments,
  resolveResourceCardState,
} from "./gitCanvasApi";
import { DagSvgCanvas } from "./canvas/DagSvgCanvas";
import { NodeDetailDrawer } from "./drawer/NodeDetailDrawer";
import {
  TwoPointDiffDrawer,
  type DiffFocusLineTarget,
} from "./drawer/TwoPointDiffDrawer";
import { endpointsEqual, formatEndpointShort, shortOid } from "./labels";
import {
  computeDagLayout,
  stripRefPrefix,
  type DagFilterOptions,
  type DagLayoutResult,
} from "./worker/dagLayoutCore";
import type { DagWorkerResponse } from "./worker/dagLayout.worker";
import "./git-canvas.css";

export interface CommentFocusTarget {
  commentId?: string;
  diffId?: string | null;
  base?: DiffEndpointRef;
  target?: DiffEndpointRef;
  filePath?: string;
  side?: CodeCommentSide;
  lineNumber?: number;
  highlightNonce: number;
}

export interface P2GitWorkspaceExtraProps {
  commentFocusTarget?: CommentFocusTarget;
  onForwardCodeComment?: (comment: CodeCommentDto) => void;
}

export type GitWorkspaceViewProps = GitWorkspaceSlotProps & P2GitWorkspaceExtraProps;

function encodeEndpointOption(ref: DiffEndpointRef): string {
  return `${ref.kind}:${ref.id}`;
}

function decodeEndpointOption(raw: string): DiffEndpointRef | null {
  if (!raw) return null;
  const idx = raw.indexOf(":");
  if (idx <= 0) return null;
  const kind = raw.slice(0, idx);
  const id = raw.slice(idx + 1);
  if ((kind !== "COMMIT" && kind !== "WIP") || !id) return null;
  return { kind, id };
}

function mergeProjectGraphs(pages: ProjectGraph[], projectId: string): ProjectGraph {
  const commitMap = new Map<string, CommitNode>();
  const refMap = new Map<string, ObservedRef>();
  const wipMap = new Map<string, WipNode>();
  let nextCursor: string | null = null;

  for (const page of pages) {
    for (const c of page.commits) {
      if (!commitMap.has(c.oid)) {
        commitMap.set(c.oid, c);
      }
    }
    for (const r of page.refs) {
      const rKey = `${r.ref.name}:${r.ref.targetOid}:${r.source.deviceId}:${r.source.observedAt}`;
      if (!refMap.has(rKey)) {
        refMap.set(rKey, r);
      }
    }
    for (const w of page.wipNodes) {
      const wKey = `${w.worktreeId}:${w.worktreeEpoch}:${w.captureSeq}:${w.snapshotId}`;
      if (!wipMap.has(wKey)) {
        wipMap.set(wKey, w);
      }
    }
    nextCursor = page.nextCursor;
  }

  return {
    projectId,
    commits: [...commitMap.values()],
    refs: [...refMap.values()],
    wipNodes: [...wipMap.values()],
    nextCursor,
  };
}

export function GitWorkspaceView({
  spaceId,
  projectId,
  canUploadOrManage,
  focusTarget,
  onQuoteToChat,
  commentFocusTarget,
  onForwardCodeComment,
}: GitWorkspaceViewProps) {
  const queryClient = useQueryClient();

  const spacesQuery = useQuery({
    queryKey: spacesKey,
    queryFn: ({ signal }) => api<SpaceSummary[]>("/spaces", { signal }),
  });

  const currentSpace = spacesQuery.data?.find((item) => item.spaceId === spaceId);
  const access: SpaceAccess | null = currentSpace
    ? { spaceId: currentSpace.spaceId, membershipEpoch: currentSpace.membershipEpoch }
    : null;
  const accessIdentity = access ? `${access.spaceId}:${access.membershipEpoch}` : null;

  const [revokedAccessKey, setRevokedAccessKey] = useState<string | null>(null);
  const isRevoked = !!accessIdentity && revokedAccessKey === accessIdentity;

  const [extraPages, setExtraPages] = useState<ProjectGraph[]>([]);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<unknown>(null);

  const [selectedNode, setSelectedNode] = useState<{ kind: "COMMIT" | "WIP"; id: string } | null>(
    null,
  );

  const [compareMode, setCompareMode] = useState(false);
  const [compareBase, setCompareBase] = useState<DiffEndpointRef | null>(null);
  const [compareTarget, setCompareTarget] = useState<DiffEndpointRef | null>(null);
  const [compareActiveSlot, setCompareActiveSlot] = useState<"base" | "target">("base");
  const [compareNotice, setCompareNotice] = useState<string | null>(null);
  const [diffDrawerOpen, setDiffDrawerOpen] = useState(false);
  const [diffDrawerWide, setDiffDrawerWide] = useState(false);
  const [requestedDiffId, setRequestedDiffId] = useState<string | null>(null);
  const [focusLineTarget, setFocusLineTarget] = useState<DiffFocusLineTarget | null>(null);
  const [focusPlaceholder, setFocusPlaceholder] = useState<
    | { kind: "LOADING" }
    | { kind: "NOT_FOUND"; message: string }
    | { kind: "ERROR"; error: unknown }
    | null
  >(null);

  const [queryText, setQueryText] = useState("");
  const [branchFilter, setBranchFilter] = useState("");
  const [authorFilter, setAuthorFilter] = useState("");
  const [onlyConflicts, setOnlyConflicts] = useState(false);
  const [onlyWip, setOnlyWip] = useState(false);

  const workerRef = useRef<Worker | null>(null);
  const latestWorkerReqIdRef = useRef(0);
  const previousLanesRef = useRef<Record<string, number>>({});
  const [layoutResult, setLayoutResult] = useState<DagLayoutResult | null>(null);
  const [isLayoutComputing, setIsLayoutComputing] = useState(false);
  const [layoutError, setLayoutError] = useState<string | null>(null);
  const lastAppliedCommentNonceRef = useRef<number | null>(null);

  useEffect(() => {
    setExtraPages([]);
    setLoadMoreError(null);
    setLoadingMore(false);
    setSelectedNode(null);
    setCompareMode(false);
    setCompareBase(null);
    setCompareTarget(null);
    setCompareActiveSlot("base");
    setCompareNotice(null);
    setDiffDrawerOpen(false);
    setDiffDrawerWide(false);
    setRequestedDiffId(null);
    setFocusLineTarget(null);
    setFocusPlaceholder(null);
    lastAppliedCommentNonceRef.current = null;
    setLayoutResult(null);
    setLayoutError(null);
    previousLanesRef.current = {};
    setQueryText("");
    setBranchFilter("");
    setAuthorFilter("");
    setOnlyConflicts(false);
    setOnlyWip(false);
  }, [spaceId, projectId, access?.membershipEpoch]);

  const graphQueryKey = useMemo(
    () =>
      access
        ? ([...spaceKey(access), "project", projectId, "graph"] as const)
        : (["git-canvas", "no-access", spaceId, projectId] as const),
    [access, projectId, spaceId],
  );

  const firstPageQuery = useQuery({
    queryKey: graphQueryKey,
    enabled: !!access && !isRevoked,
    queryFn: ({ signal }) => fetchProjectGraph(spaceId, projectId, access!, undefined, 50, signal),
  });

  const membersQuery = useQuery({
    queryKey: access
      ? ([...spaceKey(access), "members"] as const)
      : (["git-canvas", "no-members", spaceId] as const),
    enabled: !!access && !isRevoked,
    queryFn: ({ signal }) => fetchSpaceMembers(spaceId, access!, signal),
  });

  const membersById = useMemo(() => {
    const map = new Map<string, Member>();
    for (const member of membersQuery.data ?? []) {
      map.set(member.userId, member);
    }
    return map;
  }, [membersQuery.data]);

  const codeCommentsQueryKey = useMemo(
    () =>
      access
        ? ([...spaceKey(access), "project", projectId, "code-comments"] as const)
        : (["git-canvas", "no-comments", spaceId, projectId] as const),
    [access, projectId, spaceId],
  );

  const codeCommentsQuery = useQuery({
    queryKey: codeCommentsQueryKey,
    enabled: !!access && !isRevoked,
    queryFn: ({ signal }) => listCodeComments(spaceId, projectId, access!, undefined, signal),
  });

  const focusDiffIdLookup =
    commentFocusTarget &&
    !commentFocusTarget.commentId &&
    !(commentFocusTarget.base && commentFocusTarget.target) &&
    commentFocusTarget.diffId
      ? commentFocusTarget.diffId
      : null;

  const focusDiffLookupQuery = useQuery({
    queryKey: access
      ? ([
          ...spaceKey(access),
          "project",
          projectId,
          "focus-diff-lookup",
          focusDiffIdLookup ?? "NONE",
        ] as const)
      : (["git-canvas", "no-focus-diff", spaceId, projectId] as const),
    enabled:
      !!access &&
      !isRevoked &&
      Boolean(focusDiffIdLookup) &&
      lastAppliedCommentNonceRef.current !== commentFocusTarget?.highlightNonce,
    queryFn: ({ signal }) => getTwoPointDiff(spaceId, projectId, focusDiffIdLookup!, access!, signal),
  });

  const mergedGraph = useMemo(() => {
    if (!firstPageQuery.data) return null;
    return mergeProjectGraphs([firstPageQuery.data, ...extraPages], projectId);
  }, [extraPages, firstPageQuery.data, projectId]);

  const filterOptions: DagFilterOptions = useMemo(
    () => ({
      query: queryText,
      branch: branchFilter,
      author: authorFilter,
      onlyConflicts,
      onlyWip,
    }),
    [authorFilter, branchFilter, onlyConflicts, onlyWip, queryText],
  );

  const hasActiveFilter = Boolean(
    queryText.trim() || branchFilter || authorFilter.trim() || onlyConflicts || onlyWip,
  );

  const clearFilters = useCallback(() => {
    setQueryText("");
    setBranchFilter("");
    setAuthorFilter("");
    setOnlyConflicts(false);
    setOnlyWip(false);
  }, []);

  const branchOptions = useMemo(() => {
    if (!mergedGraph) return [];
    const set = new Set<string>();
    for (const r of mergedGraph.refs) {
      set.add(stripRefPrefix(r.ref.name));
    }
    for (const w of mergedGraph.wipNodes) {
      set.add(w.head.branch ?? "Detached HEAD");
    }
    return [...set].sort((a, b) => a.localeCompare(b));
  }, [mergedGraph]);

  // Manage Web Worker lifecycle and instant termination on space revocation or unmount
  useEffect(() => {
    if (!access || isRevoked) {
      if (workerRef.current) {
        workerRef.current.terminate();
        workerRef.current = null;
      }
      return undefined;
    }

    let worker: Worker | null = null;
    if (typeof Worker !== "undefined") {
      worker = new Worker(new URL("./worker/dagLayout.worker.ts", import.meta.url), {
        type: "module",
      });
      workerRef.current = worker;

      const onMessage = (event: MessageEvent<DagWorkerResponse>) => {
        const { requestId, result, error } = event.data;
        if (requestId !== latestWorkerReqIdRef.current) return;
        setIsLayoutComputing(false);
        if (error) {
          setLayoutError(error);
          return;
        }
        if (result) {
          setLayoutError(null);
          previousLanesRef.current = result.lanesByNodeId;
          setLayoutResult(result);
        }
      };

      const onError = (event: ErrorEvent) => {
        setIsLayoutComputing(false);
        setLayoutError(event.message || "DAG 布局 Worker 异常");
      };

      worker.addEventListener("message", onMessage);
      worker.addEventListener("error", onError);

      const unbind = bindSpaceConnection(
        { spaceId: access.spaceId, membershipEpoch: access.membershipEpoch },
        () => {
          worker?.terminate();
          if (workerRef.current === worker) {
            workerRef.current = null;
          }
          setRevokedAccessKey(`${access.spaceId}:${access.membershipEpoch}`);
          setLayoutResult(null);
          setSelectedNode(null);
          setDiffDrawerOpen(false);
          setFocusPlaceholder(null);
        },
      );

      return () => {
        unbind();
        worker?.removeEventListener("message", onMessage);
        worker?.removeEventListener("error", onError);
        worker?.terminate();
        if (workerRef.current === worker) {
          workerRef.current = null;
        }
      };
    }

    const unbind = bindSpaceConnection(
      { spaceId: access.spaceId, membershipEpoch: access.membershipEpoch },
      () => {
        setRevokedAccessKey(`${access.spaceId}:${access.membershipEpoch}`);
        setLayoutResult(null);
        setSelectedNode(null);
        setDiffDrawerOpen(false);
        setFocusPlaceholder(null);
      },
    );
    return () => unbind();
  }, [access?.spaceId, access?.membershipEpoch, isRevoked]);

  useEffect(() => {
    if (!mergedGraph || isRevoked) return;
    const reqId = ++latestWorkerReqIdRef.current;
    const worker = workerRef.current;

    if (worker) {
      setIsLayoutComputing(true);
      worker.postMessage({
        requestId: reqId,
        graph: mergedGraph,
        previousLanes: previousLanesRef.current,
        options: filterOptions,
      });
    } else {
      const result = computeDagLayout(mergedGraph, previousLanesRef.current, filterOptions);
      if (reqId === latestWorkerReqIdRef.current) {
        previousLanesRef.current = result.lanesByNodeId;
        setLayoutResult(result);
        setIsLayoutComputing(false);
      }
    }
  }, [filterOptions, isRevoked, mergedGraph]);

  const lastAppliedFocusNonceRef = useRef<number | null>(null);
  useEffect(() => {
    if (!focusTarget) return;
    setSelectedNode({ kind: focusTarget.kind, id: focusTarget.id });

    if (lastAppliedFocusNonceRef.current !== focusTarget.highlightNonce) {
      lastAppliedFocusNonceRef.current = focusTarget.highlightNonce;
      setDiffDrawerOpen(false);
      setFocusPlaceholder(null);
      if (mergedGraph && hasActiveFilter) {
        const existsInGraph =
          focusTarget.kind === "COMMIT"
            ? mergedGraph.commits.some((c) => c.oid === focusTarget.id)
            : mergedGraph.wipNodes.some((w) => w.snapshotId === focusTarget.id);
        if (existsInGraph) {
          clearFilters();
        }
      }
    }
  }, [clearFilters, focusTarget, hasActiveFilter, mergedGraph]);

  useEffect(() => {
    if (!commentFocusTarget || !access || isRevoked) return;
    if (lastAppliedCommentNonceRef.current === commentFocusTarget.highlightNonce) return;

    setSelectedNode(null);

    if (commentFocusTarget.commentId) {
      if (codeCommentsQuery.isPending) {
        setFocusPlaceholder({ kind: "LOADING" });
        setDiffDrawerOpen(false);
        return;
      }
      if (codeCommentsQuery.isError) {
        lastAppliedCommentNonceRef.current = commentFocusTarget.highlightNonce;
        setFocusPlaceholder({ kind: "ERROR", error: codeCommentsQuery.error });
        setDiffDrawerOpen(false);
        return;
      }
      const matched = codeCommentsQuery.data?.items.find(
        (c) => c.commentId === commentFocusTarget.commentId,
      );
      lastAppliedCommentNonceRef.current = commentFocusTarget.highlightNonce;
      if (!matched) {
        setFocusPlaceholder({
          kind: "NOT_FOUND",
          message: `未在当前工程中找到代码批注 ${shortOid(commentFocusTarget.commentId)}（可能已失效、被清理或当前账户无权访问）。`,
        });
        setDiffDrawerOpen(false);
        return;
      }
      setCompareBase(matched.base);
      setCompareTarget(matched.target);
      setRequestedDiffId(matched.diffId);
      setFocusLineTarget({
        filePath: matched.filePath,
        side: matched.side,
        lineNumber: matched.lineNumber,
        commentId: matched.commentId,
        highlightNonce: commentFocusTarget.highlightNonce,
      });
      setFocusPlaceholder(null);
      setDiffDrawerOpen(true);
      setCompareMode(true);
      return;
    }

    if (commentFocusTarget.base && commentFocusTarget.target) {
      lastAppliedCommentNonceRef.current = commentFocusTarget.highlightNonce;
      setCompareBase(commentFocusTarget.base);
      setCompareTarget(commentFocusTarget.target);
      setRequestedDiffId(commentFocusTarget.diffId ?? null);
      setFocusLineTarget({
        filePath: commentFocusTarget.filePath,
        side: commentFocusTarget.side,
        lineNumber: commentFocusTarget.lineNumber,
        highlightNonce: commentFocusTarget.highlightNonce,
      });
      setFocusPlaceholder(null);
      setDiffDrawerOpen(true);
      setCompareMode(true);
      return;
    }

    if (commentFocusTarget.diffId) {
      if (focusDiffLookupQuery.isPending) {
        setFocusPlaceholder({ kind: "LOADING" });
        setDiffDrawerOpen(false);
        return;
      }
      if (focusDiffLookupQuery.isError) {
        lastAppliedCommentNonceRef.current = commentFocusTarget.highlightNonce;
        setFocusPlaceholder({ kind: "ERROR", error: focusDiffLookupQuery.error });
        setDiffDrawerOpen(false);
        return;
      }
      if (focusDiffLookupQuery.data) {
        lastAppliedCommentNonceRef.current = commentFocusTarget.highlightNonce;
        setCompareBase(focusDiffLookupQuery.data.base);
        setCompareTarget(focusDiffLookupQuery.data.target);
        setRequestedDiffId(focusDiffLookupQuery.data.diffId);
        setFocusLineTarget({
          filePath: commentFocusTarget.filePath,
          side: commentFocusTarget.side,
          lineNumber: commentFocusTarget.lineNumber,
          highlightNonce: commentFocusTarget.highlightNonce,
        });
        setFocusPlaceholder(null);
        setDiffDrawerOpen(true);
        setCompareMode(true);
      }
      return;
    }

    lastAppliedCommentNonceRef.current = commentFocusTarget.highlightNonce;
    setFocusPlaceholder({
      kind: "NOT_FOUND",
      message: "缺少有效的代码批注或对比端点参数，无法定位目标代码行。",
    });
    setDiffDrawerOpen(false);
  }, [
    access,
    codeCommentsQuery.data,
    codeCommentsQuery.error,
    codeCommentsQuery.isError,
    codeCommentsQuery.isPending,
    commentFocusTarget,
    focusDiffLookupQuery.data,
    focusDiffLookupQuery.error,
    focusDiffLookupQuery.isError,
    focusDiffLookupQuery.isPending,
    isRevoked,
  ]);

  const endpointOptions = useMemo(() => {
    if (!mergedGraph) return [];
    const options: Array<{ value: string; ref: DiffEndpointRef; label: string }> = [];
    for (const w of mergedGraph.wipNodes) {
      const ref: DiffEndpointRef = { kind: "WIP", id: w.snapshotId };
      options.push({
        value: encodeEndpointOption(ref),
        ref,
        label: `WIP · ${w.head.branch ?? "Detached HEAD"} (${shortOid(w.snapshotId)}) #${w.captureSeq}`,
      });
    }
    for (const c of mergedGraph.commits) {
      const ref: DiffEndpointRef = { kind: "COMMIT", id: c.oid };
      const subject = c.observations[0]?.metadata.subject ?? "(无提交说明)";
      options.push({
        value: encodeEndpointOption(ref),
        ref,
        label: `Commit ${shortOid(c.oid)} · ${subject.slice(0, 40)}`,
      });
    }
    return options;
  }, [mergedGraph]);

  const openTwoPointDiff = useCallback((baseRef: DiffEndpointRef, targetRef: DiffEndpointRef) => {
    if (endpointsEqual(baseRef, targetRef)) {
      setCompareNotice("基准端点 (Base) 与目标端点 (Target) 不能为同一个节点，请选择不同的 Commit 或 WIP。");
      return;
    }
    setCompareNotice(null);
    setFocusPlaceholder(null);
    setSelectedNode(null);
    setRequestedDiffId(null);
    setFocusLineTarget(null);
    setCompareBase(baseRef);
    setCompareTarget(targetRef);
    setDiffDrawerOpen(true);
  }, []);

  const assignCompareSlot = useCallback(
    (slot: "base" | "target", ref: DiffEndpointRef) => {
      const other = slot === "base" ? compareTarget : compareBase;
      if (endpointsEqual(other, ref)) {
        setCompareNotice("基准端点 (Base) 与目标端点 (Target) 不能为同一个节点，请选择另一个节点。");
        return;
      }
      setCompareNotice(null);
      if (slot === "base") {
        setCompareBase(ref);
        if (compareTarget) {
          openTwoPointDiff(ref, compareTarget);
        } else {
          setCompareActiveSlot("target");
        }
      } else {
        setCompareTarget(ref);
        if (compareBase) {
          openTwoPointDiff(compareBase, ref);
        } else {
          setCompareActiveSlot("base");
        }
      }
    },
    [compareBase, compareTarget, openTwoPointDiff],
  );

  const handleCanvasSelectNode = useCallback(
    (target: { kind: "COMMIT" | "WIP"; id: string }) => {
      if (compareMode) {
        assignCompareSlot(compareActiveSlot, target);
        return;
      }
      setFocusPlaceholder(null);
      setDiffDrawerOpen(false);
      setSelectedNode(target);
    },
    [assignCompareSlot, compareActiveSlot, compareMode],
  );

  const handleToggleCompareMode = useCallback(() => {
    setCompareNotice(null);
    setCompareMode((prev) => {
      const next = !prev;
      if (next) {
        if (selectedNode && !compareBase && !endpointsEqual(compareTarget, selectedNode)) {
          setCompareBase(selectedNode);
          setCompareActiveSlot("target");
        } else if (!compareBase) {
          setCompareActiveSlot("base");
        } else if (!compareTarget) {
          setCompareActiveSlot("target");
        }
      }
      return next;
    });
  }, [compareBase, compareTarget, selectedNode]);

  const handleSwapEndpoints = useCallback(() => {
    if (!compareBase || !compareTarget) return;
    openTwoPointDiff(compareTarget, compareBase);
  }, [compareBase, compareTarget, openTwoPointDiff]);

  const isSelectedOutsideCurrentGraph = useMemo(() => {
    if (!selectedNode || !mergedGraph) return false;
    return selectedNode.kind === "COMMIT"
      ? !mergedGraph.commits.some((c) => c.oid === selectedNode.id)
      : !mergedGraph.wipNodes.some((w) => w.snapshotId === selectedNode.id);
  }, [mergedGraph, selectedNode]);

  const handleLoadMore = async () => {
    if (!access || !mergedGraph?.nextCursor || loadingMore) return;
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const page = await fetchProjectGraph(
        spaceId,
        projectId,
        access,
        mergedGraph.nextCursor,
        50,
      );
      setExtraPages((prev) => [...prev, page]);
    } catch (err) {
      setLoadMoreError(err);
    } finally {
      setLoadingMore(false);
    }
  };

  const handleRefresh = useCallback(async () => {
    if (!access) return;
    setExtraPages([]);
    setLoadMoreError(null);
    await queryClient.invalidateQueries({ queryKey: graphQueryKey });
  }, [access, graphQueryKey, queryClient]);

  if (!access) {
    if (spacesQuery.isPending) {
      return (
        <section className="git-workspace-shell">
          <Loading label="正在核对空间成员资格与 Epoch…" />
        </section>
      );
    }
    if (spacesQuery.isError) {
      const cardState = resolveResourceCardState(spacesQuery.error);
      return (
        <section className="git-workspace-shell">
          {cardState ? (
            <ResourceStateCard state={cardState} />
          ) : (
            <div className="alert" role="alert">
              <span>{gitCanvasErrorText(spacesQuery.error)}</span>
              <Button
                className="secondary"
                onClick={() => {
                  void spacesQuery.refetch();
                }}
              >
                重试
              </Button>
            </div>
          )}
        </section>
      );
    }
    return (
      <section className="git-workspace-shell">
        <ResourceStateCard state="REVOKED_FOR_ACTOR" />
      </section>
    );
  }

  if (isRevoked) {
    return (
      <section className="git-workspace-shell">
        <ResourceStateCard state="REVOKED_FOR_ACTOR" />
      </section>
    );
  }

  const graphCardState = resolveResourceCardState(firstPageQuery.error);
  const focusHighlight = focusTarget
    ? { kind: focusTarget.kind, id: focusTarget.id, nonce: focusTarget.highlightNonce }
    : null;

  return (
    <section className="git-workspace-shell" aria-label="Git 拓扑工作区">
      <header className="git-workspace-toolbar">
        <div className="git-filter-row">
          <label className="git-search-box">
            <Search size={15} aria-hidden="true" />
            <input
              type="search"
              value={queryText}
              onChange={(e) => setQueryText(e.target.value)}
              placeholder="筛选提交说明 / OID / 分支 / 作者 / 快照 ID…"
              aria-label="筛选 Git 节点"
            />
          </label>

          <label className="git-select-box">
            <GitBranch size={14} aria-hidden="true" />
            <select
              value={branchFilter}
              onChange={(e) => setBranchFilter(e.target.value)}
              aria-label="按分支筛选"
            >
              <option value="">全部分支</option>
              {branchOptions.map((b) => (
                <option key={b} value={b}>
                  {b}
                </option>
              ))}
            </select>
          </label>

          <input
            type="text"
            className="git-author-input"
            value={authorFilter}
            onChange={(e) => setAuthorFilter(e.target.value)}
            placeholder="作者 / 用户 ID"
            aria-label="按作者筛选"
          />

          <label className="git-check-chip">
            <input
              type="checkbox"
              checked={onlyConflicts}
              onChange={(e) => setOnlyConflicts(e.target.checked)}
            />
            仅冲突
          </label>

          <label className="git-check-chip">
            <input
              type="checkbox"
              checked={onlyWip}
              onChange={(e) => setOnlyWip(e.target.checked)}
            />
            仅 WIP
          </label>

          {hasActiveFilter && (
            <Button className="secondary git-btn-xs" onClick={clearFilters}>
              <X size={13} /> 清除筛选
            </Button>
          )}
        </div>

        <div className="git-action-row">
          <Button
            className={compareMode ? "primary git-btn-xs" : "secondary git-btn-xs"}
            onClick={handleToggleCompareMode}
          >
            <GitCompareArrows size={14} />
            {compareMode ? "退出双点选择" : "双点 Diff 对比"}
          </Button>
          {mergedGraph?.nextCursor && (
            <Button
              className="secondary git-btn-xs"
              disabled={loadingMore}
              onClick={() => {
                void handleLoadMore();
              }}
            >
              <History size={14} />
              {loadingMore ? "加载中…" : "加载更多历史"}
            </Button>
          )}
          <Button
            className="secondary git-btn-xs"
            disabled={firstPageQuery.isFetching}
            onClick={() => {
              void handleRefresh();
            }}
          >
            <RefreshCw size={14} /> 刷新拓扑
          </Button>
        </div>
      </header>

      {(compareMode || diffDrawerOpen) && (
        <section className="git-compare-bar" aria-label="受控双点 Diff 端点选择器">
          <div className="git-compare-bar-head">
            <span className="git-compare-bar-title">
              <GitCompareArrows size={15} /> 受控双点文件树 Diff（区别于单提交父节点 Patch）
            </span>
            <span className="drawer-hint">
              {compareMode
                ? `画布点选模式已开启：点击节点将填充 ${
                    compareActiveSlot === "base" ? "Base (旧端)" : "Target (新端)"
                  }`
                : "已选定比较端点"}
            </span>
          </div>

          <div className="git-compare-slots">
            <div
              className={`git-compare-slot ${
                compareMode && compareActiveSlot === "base" ? "is-awaiting" : ""
              }`}
            >
              <button
                type="button"
                className="git-compare-slot-btn"
                onClick={() => {
                  setCompareMode(true);
                  setCompareActiveSlot("base");
                }}
              >
                <span className="diff-role-pill diff-role-base">Base 旧端</span>
                <span className="git-compare-slot-val">
                  {compareBase ? formatEndpointShort(compareBase) : "点击画布选择旧端"}
                </span>
              </button>
              <select
                aria-label="选择 Base 旧端节点"
                value={compareBase ? encodeEndpointOption(compareBase) : ""}
                onChange={(e) => {
                  const picked = decodeEndpointOption(e.target.value);
                  if (picked) assignCompareSlot("base", picked);
                  else {
                    setCompareBase(null);
                    setDiffDrawerOpen(false);
                  }
                }}
              >
                <option value="">从列表选择 Base…</option>
                {compareBase &&
                  !endpointOptions.some((o) => o.value === encodeEndpointOption(compareBase)) && (
                    <option value={encodeEndpointOption(compareBase)}>
                      {formatEndpointShort(compareBase)} (当前聚焦)
                    </option>
                  )}
                {endpointOptions.map((opt) => (
                  <option key={`b-${opt.value}`} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            </div>

            <Button
              className="secondary git-btn-xs"
              disabled={!compareBase || !compareTarget}
              onClick={handleSwapEndpoints}
              title="交换 Base 与 Target"
              aria-label="交换 Base 与 Target"
            >
              <ArrowLeftRight size={14} />
            </Button>

            <div
              className={`git-compare-slot ${
                compareMode && compareActiveSlot === "target" ? "is-awaiting" : ""
              }`}
            >
              <button
                type="button"
                className="git-compare-slot-btn"
                onClick={() => {
                  setCompareMode(true);
                  setCompareActiveSlot("target");
                }}
              >
                <span className="diff-role-pill diff-role-target">Target 新端</span>
                <span className="git-compare-slot-val">
                  {compareTarget ? formatEndpointShort(compareTarget) : "点击画布选择新端"}
                </span>
              </button>
              <select
                aria-label="选择 Target 新端节点"
                value={compareTarget ? encodeEndpointOption(compareTarget) : ""}
                onChange={(e) => {
                  const picked = decodeEndpointOption(e.target.value);
                  if (picked) assignCompareSlot("target", picked);
                  else {
                    setCompareTarget(null);
                    setDiffDrawerOpen(false);
                  }
                }}
              >
                <option value="">从列表选择 Target…</option>
                {compareTarget &&
                  !endpointOptions.some((o) => o.value === encodeEndpointOption(compareTarget)) && (
                    <option value={encodeEndpointOption(compareTarget)}>
                      {formatEndpointShort(compareTarget)} (当前聚焦)
                    </option>
                  )}
                {endpointOptions.map((opt) => (
                  <option key={`t-${opt.value}`} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            </div>

            <div className="git-compare-actions">
              <Button
                className="primary git-btn-xs"
                disabled={
                  !compareBase ||
                  !compareTarget ||
                  endpointsEqual(compareBase, compareTarget)
                }
                onClick={() => {
                  if (compareBase && compareTarget) {
                    openTwoPointDiff(compareBase, compareTarget);
                  }
                }}
              >
                <GitCompareArrows size={13} /> 查看双点 Diff
              </Button>
              <Button
                className="secondary git-btn-xs"
                onClick={() => {
                  setCompareBase(null);
                  setCompareTarget(null);
                  setCompareNotice(null);
                  setDiffDrawerOpen(false);
                  setRequestedDiffId(null);
                  setFocusLineTarget(null);
                }}
              >
                清空
              </Button>
            </div>
          </div>

          {compareNotice && (
            <div className="drawer-inline-error" role="status">
              {compareNotice}
            </div>
          )}
        </section>
      )}

      {isSelectedOutsideCurrentGraph && selectedNode && (
        <div className="git-focus-banner" role="status">
          <AlertTriangle size={16} />
          <span>
            {selectedNode.kind === "WIP"
              ? `当前聚焦快照 ${shortOid(selectedNode.id)} 已不在工作区最新活跃指针列表中（属于历史不可变快照），已在右侧独立打开其详情。`
              : `当前聚焦提交 ${shortOid(selectedNode.id)} 不在已加载的画布分页内，已在右侧直接加载其单点详情。`}
          </span>
        </div>
      )}

      {loadMoreError ? (
        <div className="alert" role="alert">
          <span>加载历史分页失败：{gitCanvasErrorText(loadMoreError)}</span>
        </div>
      ) : null}

      {layoutError && (
        <div className="alert" role="alert">
          <span>拓扑计算错误：{layoutError}</span>
        </div>
      )}

      {(() => {
        const showingDiffDrawer = Boolean(diffDrawerOpen && compareBase && compareTarget);
        const showingFocusPlaceholder = Boolean(focusPlaceholder) && !showingDiffDrawer;
        const hasDrawer = Boolean(selectedNode || showingDiffDrawer || showingFocusPlaceholder);
        const bodyModifier = !hasDrawer
          ? ""
          : showingDiffDrawer && diffDrawerWide
            ? "with-diff-drawer-wide"
            : showingDiffDrawer
              ? "with-diff-drawer"
              : "with-drawer";

        return (
          <div className={`git-workspace-body ${bodyModifier}`}>
            <div className="git-canvas-pane">
              {firstPageQuery.isPending && <Loading label="正在加载项目 Git 拓扑…" />}

              {firstPageQuery.isError &&
                (graphCardState ? (
                  <ResourceStateCard state={graphCardState} />
                ) : (
                  <div className="alert" role="alert">
                    <span>{gitCanvasErrorText(firstPageQuery.error)}</span>
                    <Button
                      className="secondary"
                      onClick={() => {
                        void firstPageQuery.refetch();
                      }}
                    >
                      重试
                    </Button>
                  </div>
                ))}

              {mergedGraph &&
                layoutResult &&
                layoutResult.nodes.length === 0 &&
                !firstPageQuery.isPending && (
                  <div className="git-empty-state">
                    {hasActiveFilter ? (
                      <>
                        <p>当前筛选条件下没有匹配的提交或 WIP 快照。</p>
                        <Button className="secondary" onClick={clearFilters}>
                          清除筛选条件
                        </Button>
                      </>
                    ) : (
                      <p>当前工程尚无已上报的提交或 WIP 工作区快照。</p>
                    )}
                  </div>
                )}

              {mergedGraph && (!layoutResult || layoutResult.nodes.length > 0) && (
                <DagSvgCanvas
                  layout={layoutResult}
                  isComputing={isLayoutComputing}
                  selectedNodeKey={selectedNode}
                  focusHighlight={focusHighlight}
                  compareEndpoints={
                    compareMode || diffDrawerOpen
                      ? { base: compareBase, target: compareTarget }
                      : null
                  }
                  onSelectNode={handleCanvasSelectNode}
                />
              )}
            </div>

            {showingDiffDrawer && compareBase && compareTarget && (
              <TwoPointDiffDrawer
                spaceId={spaceId}
                projectId={projectId}
                access={access}
                canUploadOrManage={canUploadOrManage}
                base={compareBase}
                target={compareTarget}
                requestedDiffId={requestedDiffId}
                graph={mergedGraph}
                comments={codeCommentsQuery.data?.items ?? []}
                commentsLoading={codeCommentsQuery.isPending}
                commentsError={codeCommentsQuery.error}
                membersById={membersById}
                focusLineTarget={focusLineTarget}
                isWide={diffDrawerWide}
                onToggleWide={() => setDiffDrawerWide((w) => !w)}
                onSwapEndpoints={handleSwapEndpoints}
                onClose={() => {
                  setDiffDrawerOpen(false);
                  setFocusLineTarget(null);
                }}
                onRefreshComments={() => {
                  void queryClient.invalidateQueries({ queryKey: codeCommentsQueryKey });
                }}
                onForwardCodeComment={onForwardCodeComment}
              />
            )}

            {showingFocusPlaceholder && focusPlaceholder && (
              <aside className="git-detail-drawer git-diff-drawer" aria-label="代码批注定位状态">
                <header className="drawer-header">
                  <div className="drawer-title-group">
                    <span className="drawer-kind-pill diff-kind-pill">
                      <GitCompareArrows size={15} /> 代码批注定位
                    </span>
                  </div>
                  <Button
                    className="icon-button secondary"
                    onClick={() => setFocusPlaceholder(null)}
                    aria-label="关闭定位状态面板"
                  >
                    <X size={16} />
                  </Button>
                </header>
                <div className="drawer-body">
                  {focusPlaceholder.kind === "LOADING" && (
                    <Loading label="正在核对权限并定位代码批注与对比差异…" />
                  )}
                  {focusPlaceholder.kind === "NOT_FOUND" && (
                    <div className="drawer-callout drawer-callout-warn" role="status">
                      <AlertTriangle size={16} />
                      <span>{focusPlaceholder.message}</span>
                    </div>
                  )}
                  {focusPlaceholder.kind === "ERROR" &&
                    (() => {
                      const errCard = resolveResourceCardState(focusPlaceholder.error);
                      return errCard ? (
                        <div className="diff-unavailable-block">
                          <ResourceStateCard state={errCard} />
                          <p className="drawer-hint">
                            目标批注或文件当前不可访问（无权限或已过期/被清除）。
                          </p>
                        </div>
                      ) : (
                        <div className="alert" role="alert">
                          <span>{gitCanvasErrorText(focusPlaceholder.error)}</span>
                        </div>
                      );
                    })()}
                </div>
              </aside>
            )}

            {!showingDiffDrawer && !showingFocusPlaceholder && selectedNode && (
              <NodeDetailDrawer
                spaceId={spaceId}
                projectId={projectId}
                access={access}
                canUploadOrManage={canUploadOrManage}
                selected={selectedNode}
                isOutsideCurrentGraph={isSelectedOutsideCurrentGraph}
                highlightNonce={
                  focusTarget &&
                  focusTarget.kind === selectedNode.kind &&
                  focusTarget.id === selectedNode.id
                    ? focusTarget.highlightNonce
                    : undefined
                }
                onClose={() => setSelectedNode(null)}
                onQuoteToChat={onQuoteToChat}
                onSnapshotUpdated={() => {
                  void handleRefresh();
                }}
                onAssignDiffEndpoint={(role, ref) => {
                  setCompareMode(true);
                  assignCompareSlot(role, ref);
                }}
              />
            )}
          </div>
        );
      })()}
    </section>
  );
}

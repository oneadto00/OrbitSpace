import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  CommitNode,
  ObservedRef,
  ProjectGraph,
  SpaceSummary,
  WipNode,
} from "@orbitspace/shared-types";
import {
  AlertTriangle,
  GitBranch,
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
  gitCanvasErrorText,
  resolveResourceCardState,
} from "./gitCanvasApi";
import { DagSvgCanvas } from "./canvas/DagSvgCanvas";
import { NodeDetailDrawer } from "./drawer/NodeDetailDrawer";
import { shortOid } from "./labels";
import {
  computeDagLayout,
  stripRefPrefix,
  type DagFilterOptions,
  type DagLayoutResult,
} from "./worker/dagLayoutCore";
import type { DagWorkerResponse } from "./worker/dagLayout.worker";
import "./git-canvas.css";

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
}: GitWorkspaceSlotProps) {
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

  // Filter controls
  const [queryText, setQueryText] = useState("");
  const [branchFilter, setBranchFilter] = useState("");
  const [authorFilter, setAuthorFilter] = useState("");
  const [onlyConflicts, setOnlyConflicts] = useState(false);
  const [onlyWip, setOnlyWip] = useState(false);

  // Worker layout state
  const workerRef = useRef<Worker | null>(null);
  const latestWorkerReqIdRef = useRef(0);
  const previousLanesRef = useRef<Record<string, number>>({});
  const [layoutResult, setLayoutResult] = useState<DagLayoutResult | null>(null);
  const [isLayoutComputing, setIsLayoutComputing] = useState(false);
  const [layoutError, setLayoutError] = useState<string | null>(null);

  // Reset per-project / per-epoch state cleanly
  useEffect(() => {
    setExtraPages([]);
    setLoadMoreError(null);
    setLoadingMore(false);
    setSelectedNode(null);
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

  // Derive branch options from loaded graph
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
      },
    );
    return () => unbind();
  }, [access?.spaceId, access?.membershipEpoch, isRevoked]);

  // Dispatch layout computation whenever graph or filter changes
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

  // Handle focusTarget from chat quotes or deep links
  const lastAppliedFocusNonceRef = useRef<number | null>(null);
  useEffect(() => {
    if (!focusTarget) return;
    setSelectedNode({ kind: focusTarget.kind, id: focusTarget.id });

    if (lastAppliedFocusNonceRef.current !== focusTarget.highlightNonce) {
      lastAppliedFocusNonceRef.current = focusTarget.highlightNonce;
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

  // Guard states before rendering workspace
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

      <div className={`git-workspace-body ${selectedNode ? "with-drawer" : ""}`}>
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
              onSelectNode={(target) => setSelectedNode(target)}
            />
          )}
        </div>

        {selectedNode && (
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
          />
        )}
      </div>
    </section>
  );
}

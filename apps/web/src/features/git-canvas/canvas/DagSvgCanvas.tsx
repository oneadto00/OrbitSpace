import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
} from "react";
import { LocateFixed, Minus, Plus, RotateCcw } from "lucide-react";
import {
  DAG_LANE_WIDTH,
  DAG_LEFT_MARGIN,
  stripRefPrefix,
  type DagLayoutResult,
  type LayoutEdge,
  type LayoutNode,
} from "../worker/dagLayoutCore";
import {
  CAPTURE_STATE_LABELS,
  PARSE_STATE_LABELS,
  TRUST_STATE_LABELS,
  WIP_FRESHNESS_LABELS,
  formatDateTime,
  shortOid,
} from "../labels";

export interface DagSvgCanvasProps {
  layout: DagLayoutResult | null;
  isComputing: boolean;
  selectedNodeKey: { kind: "COMMIT" | "WIP"; id: string } | null;
  focusHighlight?: { kind: "COMMIT" | "WIP"; id: string; nonce: number } | null;
  compareEndpoints?: {
    base: { kind: "COMMIT" | "WIP"; id: string } | null;
    target: { kind: "COMMIT" | "WIP"; id: string } | null;
  } | null;
  onSelectNode: (target: { kind: "COMMIT" | "WIP"; id: string }) => void;
}

const LANE_PALETTE = [
  "#28634e",
  "#1d4ed8",
  "#7c3aed",
  "#0f766e",
  "#b45309",
  "#be123c",
];

const VIEWPORT_MARGIN_X = 240;
const VIEWPORT_MARGIN_Y = 160;
const MIN_ZOOM = 0.35;
const MAX_ZOOM = 2.4;

function laneColor(lane: number): string {
  return LANE_PALETTE[Math.abs(lane) % LANE_PALETTE.length] ?? "#28634e";
}

function truncateText(text: string, maxChars: number): string {
  if (text.length <= maxChars) return text;
  return `${text.slice(0, maxChars - 1)}…`;
}

function approxBadgeWidth(text: string, fontSize = 11): number {
  let units = 0;
  for (const ch of text) {
    units += ch.charCodeAt(0) > 255 ? 1.85 : 1.05;
  }
  return Math.ceil(units * (fontSize * 0.58)) + 14;
}

function buildEdgePath(edge: LayoutEdge): string {
  const { x1, y1, x2, y2 } = edge;
  if (Math.abs(x1 - x2) < 0.5) {
    return `M ${x1} ${y1} L ${x2} ${y2}`;
  }
  const dy = y2 - y1;
  const c1y = y1 + dy * 0.48;
  const c2y = y2 - dy * 0.48;
  return `M ${x1} ${y1} C ${x1} ${c1y}, ${x2} ${c2y}, ${x2} ${y2}`;
}

function lowerBoundByY(nodes: LayoutNode[], minY: number): number {
  let lo = 0;
  let hi = nodes.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    const node = nodes[mid];
    if (node && node.y < minY) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

function upperBoundByY(nodes: LayoutNode[], maxY: number): number {
  let lo = 0;
  let hi = nodes.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    const node = nodes[mid];
    if (node && node.y <= maxY) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

export function DagSvgCanvas({
  layout,
  isComputing,
  selectedNodeKey,
  focusHighlight,
  compareEndpoints,
  onSelectNode,
}: DagSvgCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [viewport, setViewport] = useState({ width: 960, height: 560 });
  const [pan, setPan] = useState({ x: 0, y: 0, zoom: 1 });
  const [lastFrameDurationMs, setLastFrameDurationMs] = useState<number | null>(null);
  const dragStateRef = useRef<{
    pointerId: number;
    startClientX: number;
    startClientY: number;
    originX: number;
    originY: number;
    moved: boolean;
  } | null>(null);
  const lastCenteredFocusRef = useRef<string | null>(null);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const updateSize = () => {
      const rect = el.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        setViewport({ width: rect.width, height: rect.height });
      }
    };
    updateSize();
    if (typeof ResizeObserver === "function") {
      const ro = new ResizeObserver(updateSize);
      ro.observe(el);
      return () => ro.disconnect();
    }
    return undefined;
  }, []);

  // Non-passive wheel zoom around cursor
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      const rect = el.getBoundingClientRect();
      const pointerX = event.clientX - rect.left;
      const pointerY = event.clientY - rect.top;
      const factor = event.deltaY < 0 ? 1.12 : 1 / 1.12;
      setPan((prev) => {
        const nextZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, prev.zoom * factor));
        const ratio = nextZoom / prev.zoom;
        return {
          x: pointerX - (pointerX - prev.x) * ratio,
          y: pointerY - (pointerY - prev.y) * ratio,
          zoom: nextZoom,
        };
      });
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, []);

  const matchedFocusNode = useMemo(() => {
    if (!focusHighlight || !layout) return null;
    return (
      layout.nodes.find((node) =>
        focusHighlight.kind === "COMMIT"
          ? node.kind === "COMMIT" && node.oid === focusHighlight.id
          : node.kind === "WIP" && node.snapshotId === focusHighlight.id,
      ) ?? null
    );
  }, [focusHighlight, layout]);

  useEffect(() => {
    if (!focusHighlight || !matchedFocusNode) return;
    const token = `${focusHighlight.kind}:${focusHighlight.id}:${focusHighlight.nonce}`;
    if (lastCenteredFocusRef.current === token) return;
    lastCenteredFocusRef.current = token;

    setPan((prev) => ({
      ...prev,
      x: Math.min(20, viewport.width * 0.16 - matchedFocusNode.x * prev.zoom),
      y: viewport.height * 0.42 - matchedFocusNode.y * prev.zoom,
    }));
  }, [focusHighlight, matchedFocusNode, viewport.height, viewport.width]);

  const trackRight = useMemo(() => {
    const lanes = Math.max(1, layout?.laneCount ?? 1);
    return DAG_LEFT_MARGIN + lanes * DAG_LANE_WIDTH + 18;
  }, [layout?.laneCount]);

  const { visibleNodes, visibleEdges, worldMinY, worldMaxY } = useMemo(() => {
    if (!layout || layout.nodes.length === 0) {
      return {
        visibleNodes: [] as LayoutNode[],
        visibleEdges: [] as LayoutEdge[],
        worldMinY: 0,
        worldMaxY: 0,
      };
    }

    const wMinX = (0 - pan.x) / pan.zoom - VIEWPORT_MARGIN_X;
    const wMaxX = (viewport.width - pan.x) / pan.zoom + VIEWPORT_MARGIN_X;
    const wMinY = (0 - pan.y) / pan.zoom - VIEWPORT_MARGIN_Y;
    const wMaxY = (viewport.height - pan.y) / pan.zoom + VIEWPORT_MARGIN_Y;

    const startIdx = lowerBoundByY(layout.nodes, wMinY);
    const endIdx = upperBoundByY(layout.nodes, wMaxY);

    const nodesSlice: LayoutNode[] = [];
    for (let i = startIdx; i < endIdx; i++) {
      const n = layout.nodes[i];
      if (!n) continue;
      const rowRight = Math.max(trackRight + 640, n.x + 640);
      if (rowRight >= wMinX && n.x - 24 <= wMaxX) {
        nodesSlice.push(n);
      }
    }

    const edgesSlice: LayoutEdge[] = [];
    for (const edge of layout.edges) {
      const eMinY = Math.min(edge.y1, edge.y2);
      const eMaxY = Math.max(edge.y1, edge.y2);
      const eMinX = Math.min(edge.x1, edge.x2);
      const eMaxX = Math.max(edge.x1, edge.x2);
      if (eMaxY >= wMinY && eMinY <= wMaxY && eMaxX >= wMinX && eMinX <= wMaxX) {
        edgesSlice.push(edge);
      }
    }

    return {
      visibleNodes: nodesSlice,
      visibleEdges: edgesSlice,
      worldMinY: Math.max(0, wMinY),
      worldMaxY: Math.min(layout.height, wMaxY),
    };
  }, [layout, pan.x, pan.y, pan.zoom, trackRight, viewport.height, viewport.width]);

  useEffect(() => {
    const start = performance.now();
    const rafId = window.requestAnimationFrame(() => {
      setLastFrameDurationMs(Math.max(0, performance.now() - start));
    });
    return () => window.cancelAnimationFrame(rafId);
  }, [layout, pan, viewport, visibleNodes.length]);

  const handlePointerDown = useCallback(
    (event: PointerEvent<HTMLDivElement>) => {
      if (event.button !== 0) return;
      const target = event.target as HTMLElement | null;
      if (target?.closest("[data-canvas-toolbar='true']")) return;
      dragStateRef.current = {
        pointerId: event.pointerId,
        startClientX: event.clientX,
        startClientY: event.clientY,
        originX: pan.x,
        originY: pan.y,
        moved: false,
      };
      event.currentTarget.setPointerCapture(event.pointerId);
    },
    [pan.x, pan.y],
  );

  const handlePointerMove = useCallback((event: PointerEvent<HTMLDivElement>) => {
    const drag = dragStateRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
    const dx = event.clientX - drag.startClientX;
    const dy = event.clientY - drag.startClientY;
    if (!drag.moved && Math.hypot(dx, dy) > 4) {
      drag.moved = true;
    }
    if (drag.moved) {
      setPan((prev) => ({
        ...prev,
        x: drag.originX + dx,
        y: drag.originY + dy,
      }));
    }
  }, []);

  const handlePointerEnd = useCallback((event: PointerEvent<HTMLDivElement>) => {
    const drag = dragStateRef.current;
    if (drag && drag.pointerId === event.pointerId) {
      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        event.currentTarget.releasePointerCapture(event.pointerId);
      }
      window.setTimeout(() => {
        dragStateRef.current = null;
      }, 0);
    }
  }, []);

  const zoomBy = useCallback(
    (factor: number) => {
      const cx = viewport.width * 0.4;
      const cy = viewport.height * 0.4;
      setPan((prev) => {
        const nextZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, prev.zoom * factor));
        const ratio = nextZoom / prev.zoom;
        return {
          x: cx - (cx - prev.x) * ratio,
          y: cy - (cy - prev.y) * ratio,
          zoom: nextZoom,
        };
      });
    },
    [viewport.height, viewport.width],
  );

  const resetView = useCallback(() => {
    setPan({ x: 0, y: 0, zoom: 1 });
  }, []);

  const centerOnSelected = useCallback(() => {
    if (!layout || !selectedNodeKey) return;
    const node = layout.nodes.find((n) =>
      selectedNodeKey.kind === "COMMIT"
        ? n.kind === "COMMIT" && n.oid === selectedNodeKey.id
        : n.kind === "WIP" && n.snapshotId === selectedNodeKey.id,
    );
    if (!node) return;
    setPan((prev) => ({
      ...prev,
      x: Math.min(20, viewport.width * 0.16 - node.x * prev.zoom),
      y: viewport.height * 0.42 - node.y * prev.zoom,
    }));
  }, [layout, selectedNodeKey, viewport.height, viewport.width]);

  const activateNode = useCallback(
    (node: LayoutNode) => {
      if (dragStateRef.current?.moved) return;
      if (node.kind === "COMMIT" && node.oid) {
        onSelectNode({ kind: "COMMIT", id: node.oid });
      } else if (node.kind === "WIP" && node.snapshotId) {
        onSelectNode({ kind: "WIP", id: node.snapshotId });
      }
    },
    [onSelectNode],
  );

  const handleNodeKeyDown = useCallback(
    (event: KeyboardEvent<SVGGElement>, node: LayoutNode) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        activateNode(node);
      }
    },
    [activateNode],
  );

  const totalNodes = layout?.nodes.length ?? 0;
  const layoutDurationMs = layout?.layoutDurationMs ?? 0;

  return (
    <div
      ref={containerRef}
      className="git-canvas-viewport"
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerEnd}
      onPointerCancel={handlePointerEnd}
      role="region"
      aria-label="Git 拓扑画布"
    >
      <div className="git-canvas-toolbar" data-canvas-toolbar="true">
        <button
          type="button"
          className="git-tool-btn"
          onClick={() => zoomBy(1.18)}
          aria-label="放大画布"
          title="放大"
        >
          <Plus size={15} />
        </button>
        <span className="git-zoom-label">{Math.round(pan.zoom * 100)}%</span>
        <button
          type="button"
          className="git-tool-btn"
          onClick={() => zoomBy(1 / 1.18)}
          aria-label="缩小画布"
          title="缩小"
        >
          <Minus size={15} />
        </button>
        <button
          type="button"
          className="git-tool-btn"
          onClick={resetView}
          aria-label="重置视图"
          title="重置视图"
        >
          <RotateCcw size={15} />
        </button>
        {selectedNodeKey && (
          <button
            type="button"
            className="git-tool-btn"
            onClick={centerOnSelected}
            aria-label="定位当前选中节点"
            title="定位当前选中节点"
          >
            <LocateFixed size={15} />
          </button>
        )}
      </div>

      <div className="git-canvas-metrics" data-testid="canvas-metrics" aria-live="off">
        <span>布局耗时: {layoutDurationMs.toFixed(2)} ms</span>
        <span>
          可见节点: {visibleNodes.length} / {totalNodes}
        </span>
        <span>
          最近帧耗时:{" "}
          {lastFrameDurationMs !== null ? `${lastFrameDurationMs.toFixed(2)} ms` : "测量中…"}
        </span>
        {isComputing && <span className="git-metrics-computing">Worker 布局计算中…</span>}
      </div>

      <svg
        className="git-dag-svg"
        width="100%"
        height="100%"
        aria-label="Git 提交与 WIP 拓扑图"
      >
        <g transform={`translate(${pan.x}, ${pan.y}) scale(${pan.zoom})`}>
          {layout &&
            Array.from({ length: layout.laneCount }, (_, laneIdx) => {
              const lx = DAG_LEFT_MARGIN + laneIdx * DAG_LANE_WIDTH;
              return (
                <line
                  key={`lane-${laneIdx}`}
                  x1={lx}
                  y1={worldMinY}
                  x2={lx}
                  y2={worldMaxY}
                  stroke="#e2e8e2"
                  strokeWidth={1}
                  strokeDasharray="2 4"
                />
              );
            })}

          <g className="git-dag-edges">
            {visibleEdges.map((edge) => {
              const pathD = buildEdgePath(edge);
              const isOmitted = edge.kind === "OMITTED_LINK";
              const isMissing = edge.kind === "MISSING_ANCESTRY";
              const stroke = isMissing
                ? "#b45309"
                : isOmitted
                  ? "#475569"
                  : edge.kind === "WIP_BASE"
                    ? "#0f766e"
                    : laneColor(edge.fromLane);
              const dash = isMissing ? "5 4" : isOmitted ? "6 4" : undefined;
              const mx = (edge.x1 + edge.x2) / 2;
              const my = (edge.y1 + edge.y2) / 2;
              const omittedText = `已筛选省略连接${edge.omittedCount ? ` (+${edge.omittedCount})` : ""}`;

              return (
                <g key={edge.id}>
                  <path
                    d={pathD}
                    fill="none"
                    stroke={stroke}
                    strokeWidth={edge.kind === "MERGE_PARENT" ? 1.8 : 2.2}
                    strokeOpacity={edge.kind === "MERGE_PARENT" ? 0.78 : 0.95}
                    strokeDasharray={dash}
                    strokeLinecap="round"
                  />
                  {isOmitted && (
                    <g transform={`translate(${mx + 8}, ${my})`}>
                      <rect
                        x={0}
                        y={-10}
                        width={approxBadgeWidth(omittedText, 10)}
                        height={18}
                        rx={4}
                        fill="#f1f5f9"
                        stroke="#94a3b8"
                        strokeWidth={1}
                      />
                      <text x={7} y={3} fontSize={10} fontWeight={600} fill="#334155">
                        {omittedText}
                      </text>
                    </g>
                  )}
                </g>
              );
            })}
          </g>

          <g className="git-dag-nodes">
            {visibleNodes.map((node) => {
              const isCommit = node.kind === "COMMIT";
              const isWip = node.kind === "WIP";
              const isMissing = node.kind === "MISSING_ANCESTRY";
              const isEmptyTree = node.kind === "EMPTY_TREE";
              const isInteractive = isCommit || isWip;

              const isSelected =
                !!selectedNodeKey &&
                ((isCommit && selectedNodeKey.kind === "COMMIT" && selectedNodeKey.id === node.oid) ||
                  (isWip && selectedNodeKey.kind === "WIP" && selectedNodeKey.id === node.snapshotId));

              const isFocused =
                !!focusHighlight &&
                ((isCommit && focusHighlight.kind === "COMMIT" && focusHighlight.id === node.oid) ||
                  (isWip && focusHighlight.kind === "WIP" && focusHighlight.id === node.snapshotId));

              const isCompareBase =
                !!compareEndpoints?.base &&
                ((isCommit &&
                  compareEndpoints.base.kind === "COMMIT" &&
                  compareEndpoints.base.id === node.oid) ||
                  (isWip &&
                    compareEndpoints.base.kind === "WIP" &&
                    compareEndpoints.base.id === node.snapshotId));

              const isCompareTarget =
                !!compareEndpoints?.target &&
                ((isCommit &&
                  compareEndpoints.target.kind === "COMMIT" &&
                  compareEndpoints.target.id === node.oid) ||
                  (isWip &&
                    compareEndpoints.target.kind === "WIP" &&
                    compareEndpoints.target.id === node.snapshotId));

              const color = isWip
                ? node.freshness === "ACTIVE"
                  ? "#0f766e"
                  : "#64748b"
                : isMissing
                  ? "#b45309"
                  : isEmptyTree
                    ? "#0f766e"
                    : laneColor(node.lane);

              const badges: Array<{ text: string; bg: string; fg: string; border: string; mono?: boolean }> = [];

              if (isCompareBase) {
                badges.push({
                  text: "BASE 旧端",
                  bg: "#dbeafe",
                  fg: "#1e40af",
                  border: "#60a5fa",
                });
              }
              if (isCompareTarget) {
                badges.push({
                  text: "TARGET 新端",
                  bg: "#ccfbf1",
                  fg: "#115e59",
                  border: "#2dd4bf",
                });
              }

              if (isCommit && node.oid) {
                badges.push({
                  text: shortOid(node.oid),
                  bg: "#f1f5f9",
                  fg: "#0f172a",
                  border: "#cbd5e1",
                  mono: true,
                });
                const trust = node.trustState ?? "DECLARED";
                const trustText = TRUST_STATE_LABELS[trust];
                badges.push({
                  text: trustText,
                  bg: trust === "VERIFIED" ? "#dcfce7" : trust === "CONFLICT" ? "#fef2f2" : "#f8fafc",
                  fg: trust === "VERIFIED" ? "#166534" : trust === "CONFLICT" ? "#991b1b" : "#334155",
                  border: trust === "VERIFIED" ? "#86efac" : trust === "CONFLICT" ? "#fca5a5" : "#cbd5e1",
                });
                const shownRefs = node.refs.slice(0, 2);
                for (const r of shownRefs) {
                  badges.push({
                    text: stripRefPrefix(r.ref.name),
                    bg: "#e0f2fe",
                    fg: "#075985",
                    border: "#7dd3fc",
                  });
                }
                if (node.refs.length > 2) {
                  badges.push({
                    text: `+${node.refs.length - 2} 引用`,
                    bg: "#e0f2fe",
                    fg: "#075985",
                    border: "#7dd3fc",
                  });
                }
              } else if (isWip && node.wip) {
                const isActive = node.freshness === "ACTIVE";
                badges.push({
                  text: WIP_FRESHNESS_LABELS[node.wip.freshness],
                  bg: isActive ? "#ccfbf1" : "#f1f5f9",
                  fg: isActive ? "#115e59" : "#475569",
                  border: isActive ? "#5eead4" : "#cbd5e1",
                });
                badges.push({
                  text: node.isDetachedHead ? "Detached HEAD" : node.label,
                  bg: node.isDetachedHead ? "#fef3c7" : "#ecfdf5",
                  fg: node.isDetachedHead ? "#92400e" : "#166534",
                  border: node.isDetachedHead ? "#fcd34d" : "#86efac",
                });
                if (node.isEmptyTree) {
                  badges.push({
                    text: "初始空仓库 (EMPTY_TREE)",
                    bg: "#ede9fe",
                    fg: "#5b21b6",
                    border: "#c4b5fd",
                  });
                }
                const parseFailed = node.wip.parseState === "FAILED";
                badges.push({
                  text: PARSE_STATE_LABELS[node.wip.parseState],
                  bg: parseFailed ? "#fef2f2" : "#f8fafc",
                  fg: parseFailed ? "#991b1b" : "#475569",
                  border: parseFailed ? "#fca5a5" : "#cbd5e1",
                });
                badges.push({
                  text: node.wip.dataLevel === "METADATA_ONLY" ? "仅元信息" : "Patch 可用",
                  bg: "#f8fafc",
                  fg: "#334155",
                  border: "#cbd5e1",
                });
              } else if (isMissing) {
                badges.push({
                  text: "历史不完整",
                  bg: "#fef3c7",
                  fg: "#92400e",
                  border: "#fcd34d",
                });
                if (node.oid) {
                  badges.push({
                    text: shortOid(node.oid),
                    bg: "#fffbeb",
                    fg: "#78350f",
                    border: "#fde68a",
                    mono: true,
                  });
                }
              } else if (isEmptyTree) {
                badges.push({
                  text: "初始空仓库",
                  bg: "#ecfdf5",
                  fg: "#065f46",
                  border: "#6ee7b7",
                });
                badges.push({
                  text: "EMPTY_TREE",
                  bg: "#f1f5f9",
                  fg: "#0f172a",
                  border: "#cbd5e1",
                  mono: true,
                });
              }

              const secondaryLine = isCommit
                ? `${truncateText(node.label, 52)}${
                    node.primaryObservation
                      ? ` · ${node.primaryObservation.metadata.authorName} · ${formatDateTime(node.primaryObservation.metadata.authoredAt)}`
                      : ""
                  }`
                : isWip && node.wip
                  ? `快照 ${shortOid(node.wip.snapshotId)} · worktree ${shortOid(node.wip.worktreeId)} #${node.wip.worktreeEpoch}.${node.wip.captureSeq} · ${CAPTURE_STATE_LABELS[node.wip.captureState]}`
                  : isMissing
                    ? node.missingReason === "EXPLICIT_MISSING"
                      ? "缺失父提交祖先边界（未共享或历史不可达）"
                      : "父提交尚未在当前分页加载"
                    : "无前置父提交的空仓库根基线";

              let cursorX = trackRight;
              const badgeLayouts = badges.map((badge) => {
                const bw = approxBadgeWidth(badge.text, 11);
                const bx = cursorX;
                cursorX += bw + 6;
                return { ...badge, bw, bx };
              });
              const rowRectWidth = Math.max(640, cursorX - trackRight + 20);

              return (
                <g
                  key={node.id}
                  className={`dag-node ${isInteractive ? "dag-node-interactive" : "dag-node-boundary"} ${
                    isSelected ? "dag-node-selected" : ""
                  }`}
                  role={isInteractive ? "button" : undefined}
                  tabIndex={isInteractive ? 0 : undefined}
                  aria-label={
                    isCommit
                      ? `提交 ${node.oid} ${node.label}`
                      : isWip
                        ? `WIP 快照 ${node.snapshotId} ${node.label}`
                        : node.label
                  }
                  onClick={isInteractive ? () => activateNode(node) : undefined}
                  onKeyDown={isInteractive ? (e) => handleNodeKeyDown(e, node) : undefined}
                >
                  {(isSelected || isFocused || isCompareBase || isCompareTarget) && (
                    <rect
                      key={isFocused && focusHighlight ? `row-${node.id}-${focusHighlight.nonce}` : `row-${node.id}`}
                      x={trackRight - 10}
                      y={node.y - 26}
                      width={rowRectWidth}
                      height={52}
                      rx={8}
                      fill={
                        isCompareBase
                          ? "#eff6ff"
                          : isCompareTarget
                            ? "#f0fdfa"
                            : isSelected
                              ? "#edf5ef"
                              : "#fef9c3"
                      }
                      stroke={
                        isCompareBase
                          ? "#2563eb"
                          : isCompareTarget
                            ? "#0d9488"
                            : isSelected
                              ? "#28634e"
                              : "#eab308"
                      }
                      strokeWidth={isSelected || isCompareBase || isCompareTarget ? 1.5 : 1.2}
                      className={isFocused ? "dag-row-focus-pulse" : undefined}
                    />
                  )}

                  {(isCompareBase || isCompareTarget) && (
                    <circle
                      cx={node.x}
                      cy={node.y}
                      r={13.5}
                      fill="none"
                      stroke={isCompareBase ? "#2563eb" : "#0d9488"}
                      strokeWidth={2.2}
                      strokeDasharray={isCompareBase ? undefined : "4 2"}
                    />
                  )}

                  {trackRight - node.x > 16 && (
                    <line
                      x1={node.x + 10}
                      y1={node.y}
                      x2={trackRight - 8}
                      y2={node.y}
                      stroke="#e5ebe5"
                      strokeWidth={1}
                      strokeDasharray="2 3"
                    />
                  )}

                  {isFocused && focusHighlight && (
                    <circle
                      key={`pulse-${node.id}-${focusHighlight.nonce}`}
                      cx={node.x}
                      cy={node.y}
                      r={16}
                      fill="none"
                      stroke="#eab308"
                      strokeWidth={3}
                      className="dag-focus-pulse"
                    />
                  )}

                  {isCommit && (
                    <>
                      {node.trustState === "CONFLICT" && (
                        <circle
                          cx={node.x}
                          cy={node.y}
                          r={10.5}
                          fill="none"
                          stroke="#f59e0b"
                          strokeWidth={2}
                        />
                      )}
                      <circle
                        cx={node.x}
                        cy={node.y}
                        r={7}
                        fill={
                          node.trustState === "CONFLICT"
                            ? "#b91c1c"
                            : node.trustState === "VERIFIED"
                              ? "#28634e"
                              : color
                        }
                        stroke="#ffffff"
                        strokeWidth={2}
                      />
                    </>
                  )}

                  {isWip && (
                    <>
                      {node.freshness === "ACTIVE" && (
                        <circle
                          cx={node.x}
                          cy={node.y}
                          r={11.5}
                          fill="none"
                          stroke="#0f766e"
                          strokeWidth={2}
                          className="dag-wip-pulse"
                        />
                      )}
                      <circle
                        cx={node.x}
                        cy={node.y}
                        r={7.5}
                        fill={node.freshness === "ACTIVE" ? "#ffffff" : "#f1f5f9"}
                        stroke={node.freshness === "ACTIVE" ? "#0f766e" : "#64748b"}
                        strokeWidth={2.5}
                      />
                    </>
                  )}

                  {isMissing && (
                    <circle
                      cx={node.x}
                      cy={node.y}
                      r={7.5}
                      fill="#fffbeb"
                      stroke="#b45309"
                      strokeWidth={2}
                      strokeDasharray="3 2.5"
                    />
                  )}

                  {isEmptyTree && (
                    <rect
                      x={node.x - 6}
                      y={node.y - 6}
                      width={12}
                      height={12}
                      rx={2}
                      transform={`rotate(45 ${node.x} ${node.y})`}
                      fill="#ecfdf5"
                      stroke="#0f766e"
                      strokeWidth={2}
                    />
                  )}

                  {badgeLayouts.map((badge, idx) => (
                    <g key={`${node.id}-b-${idx}`} transform={`translate(${badge.bx}, ${node.y - 19})`}>
                      <rect
                        x={0}
                        y={0}
                        width={badge.bw}
                        height={20}
                        rx={5}
                        fill={badge.bg}
                        stroke={badge.border}
                        strokeWidth={1}
                      />
                      <text
                        x={7}
                        y={13.5}
                        fontSize={11}
                        fontWeight={600}
                        fill={badge.fg}
                        fontFamily={
                          badge.mono
                            ? '"IBM Plex Mono", ui-monospace, monospace'
                            : undefined
                        }
                      >
                        {badge.text}
                      </text>
                    </g>
                  ))}

                  <text
                    x={trackRight}
                    y={node.y + 17}
                    fontSize={12.5}
                    fill={isMissing ? "#92400e" : "#1f2937"}
                    fontWeight={isCommit ? 500 : 450}
                  >
                    {secondaryLine}
                  </text>
                </g>
              );
            })}
          </g>
        </g>
      </svg>
    </div>
  );
}

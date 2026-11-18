import type {
  CaptureState,
  CommitNode,
  CommitObservation,
  DataLevel,
  GitTrustState,
  ObservedRef,
  ParseState,
  ProjectGraph,
  WipNode,
} from "@orbitspace/shared-types";

export const DAG_LANE_WIDTH = 30;
export const DAG_ROW_HEIGHT = 68;
export const DAG_LEFT_MARGIN = 36;
export const DAG_TOP_MARGIN = 36;

export type PreviousLanes = Record<string, number> | Map<string, number>;

export interface DagFilterOptions {
  query?: string;
  branch?: string;
  author?: string;
  onlyConflicts?: boolean;
  onlyWip?: boolean;
}

export type LayoutNodeKind = "COMMIT" | "WIP" | "MISSING_ANCESTRY" | "EMPTY_TREE";

export type LayoutEdgeKind =
  | "FIRST_PARENT"
  | "MERGE_PARENT"
  | "WIP_BASE"
  | "MISSING_ANCESTRY"
  | "OMITTED_LINK";

export interface LayoutNode {
  id: string;
  kind: LayoutNodeKind;
  oid?: string;
  snapshotId?: string;
  worktreeId?: string;
  worktreeEpoch?: number;
  captureSeq?: number;
  lane: number;
  row: number;
  topoOrder: number;
  x: number;
  y: number;
  label: string;
  subLabel?: string;
  commit?: CommitNode;
  primaryObservation?: CommitObservation;
  orderedParents: string[];
  wip?: WipNode;
  refs: ObservedRef[];
  isDetachedHead?: boolean;
  isEmptyTree?: boolean;
  isMissingAncestry?: boolean;
  missingReason?: "EXPLICIT_MISSING" | "OUTSIDE_PAGE";
  hasConflict?: boolean;
  trustState?: GitTrustState;
  freshness?: WipNode["freshness"];
  parseState?: ParseState;
  captureState?: CaptureState;
  dataLevel?: DataLevel;
}

export interface LayoutEdge {
  id: string;
  fromId: string;
  toId: string;
  kind: LayoutEdgeKind;
  parentIndex?: number;
  omittedCount?: number;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  fromLane: number;
  toLane: number;
}

export interface DagLayoutResult {
  nodes: LayoutNode[];
  edges: LayoutEdge[];
  lanesByNodeId: Record<string, number>;
  laneCount: number;
  rowCount: number;
  width: number;
  height: number;
  layoutDurationMs: number;
  totalSourceCommits: number;
  totalSourceWips: number;
  visibleCommitCount: number;
  visibleWipCount: number;
  hasBrokenCycles: boolean;
}

interface InternalNode {
  id: string;
  kind: LayoutNodeKind;
  timestampMs: number;
  oid?: string;
  snapshotId?: string;
  worktreeId?: string;
  worktreeEpoch?: number;
  captureSeq?: number;
  label: string;
  subLabel?: string;
  commit?: CommitNode;
  primaryObservation?: CommitObservation;
  orderedParents: string[];
  wip?: WipNode;
  refs: ObservedRef[];
  isDetachedHead?: boolean;
  isEmptyTree?: boolean;
  isMissingAncestry?: boolean;
  missingReason?: "EXPLICIT_MISSING" | "OUTSIDE_PAGE";
  hasConflict?: boolean;
  trustState?: GitTrustState;
  freshness?: WipNode["freshness"];
  parseState?: ParseState;
  captureState?: CaptureState;
  dataLevel?: DataLevel;
}

interface InternalEdge {
  fromId: string;
  toId: string;
  kind: Exclude<LayoutEdgeKind, "OMITTED_LINK">;
  parentIndex?: number;
}

interface FilteredEdge {
  fromId: string;
  toId: string;
  kind: LayoutEdgeKind;
  parentIndex?: number;
  omittedCount?: number;
}

const KIND_PRIORITY: Record<LayoutNodeKind, number> = {
  EMPTY_TREE: 0,
  MISSING_ANCESTRY: 1,
  COMMIT: 2,
  WIP: 3,
};

export function commitNodeId(oid: string): string {
  return `commit:${oid}`;
}

export function wipNodeId(wip: Pick<WipNode, "worktreeId" | "worktreeEpoch" | "captureSeq" | "snapshotId">): string {
  return `wip:${wip.worktreeId}:${wip.worktreeEpoch}:${wip.captureSeq}:${wip.snapshotId}`;
}

export function missingNodeId(oid: string): string {
  return `missing:${oid}`;
}

export const EMPTY_TREE_NODE_ID = "empty-tree:root";

function parseTimestampMs(iso: string | undefined): number {
  if (!iso) return 0;
  const parsed = Date.parse(iso);
  return Number.isFinite(parsed) ? parsed : 0;
}

function readPrevLane(previousLanes: PreviousLanes | null | undefined, nodeId: string): number | undefined {
  if (!previousLanes) return undefined;
  const val = previousLanes instanceof Map ? previousLanes.get(nodeId) : previousLanes[nodeId];
  return typeof val === "number" && Number.isInteger(val) && val >= 0 ? val : undefined;
}

export function stripRefPrefix(refName: string): string {
  return refName.replace(/^refs\/(heads|remotes\/[^/]+|tags)\//, "");
}

function selectPrimaryObservation(observations: CommitObservation[]): CommitObservation {
  const sorted = [...observations].sort((a, b) => {
    const dt = parseTimestampMs(b.source.observedAt) - parseTimestampMs(a.source.observedAt);
    if (dt !== 0) return dt;
    const dCmp = a.source.deviceId.localeCompare(b.source.deviceId);
    if (dCmp !== 0) return dCmp;
    return a.source.userId.localeCompare(b.source.userId);
  });
  return sorted[0]!;
}

function detectObservationDisagreement(commit: CommitNode): boolean {
  if (commit.trustState === "CONFLICT") return true;
  if (commit.observations.length <= 1) return false;
  const first = commit.observations[0]!;
  const firstParents = first.metadata.parents.join(",");
  for (let i = 1; i < commit.observations.length; i++) {
    const obs = commit.observations[i]!;
    if (
      obs.metadata.subject !== first.metadata.subject ||
      obs.metadata.authorName !== first.metadata.authorName ||
      obs.metadata.authoredAt !== first.metadata.authoredAt ||
      obs.metadata.parents.join(",") !== firstParents
    ) {
      return true;
    }
  }
  return false;
}

function mergeOrderedParents(observations: CommitObservation[], primary: CommitObservation): string[] {
  const seen = new Set<string>();
  const ordered: string[] = [];
  for (const oid of primary.metadata.parents) {
    if (!seen.has(oid)) {
      seen.add(oid);
      ordered.push(oid);
    }
  }
  for (const obs of observations) {
    for (const oid of obs.metadata.parents) {
      if (!seen.has(oid)) {
        seen.add(oid);
        ordered.push(oid);
      }
    }
  }
  return ordered;
}

function deduplicateCommits(commits: CommitNode[]): Map<string, CommitNode> {
  const byOid = new Map<string, CommitNode>();
  for (const commit of commits) {
    const existing = byOid.get(commit.oid);
    if (!existing) {
      byOid.set(commit.oid, {
        ...commit,
        observations: [...commit.observations],
        missingParentOids: [...commit.missingParentOids],
      });
      continue;
    }
    const obsKeys = new Set(
      existing.observations.map((o) => `${o.source.deviceId}:${o.source.userId}:${o.source.observedAt}:${o.metadata.subject}`),
    );
    for (const obs of commit.observations) {
      const k = `${obs.source.deviceId}:${obs.source.userId}:${obs.source.observedAt}:${obs.metadata.subject}`;
      if (!obsKeys.has(k)) {
        obsKeys.add(k);
        existing.observations.push(obs);
      }
    }
    const missingSet = new Set(existing.missingParentOids);
    for (const m of commit.missingParentOids) {
      missingSet.add(m);
    }
    existing.missingParentOids = [...missingSet];
    if (commit.trustState === "CONFLICT" || existing.trustState === "CONFLICT") {
      existing.trustState = "CONFLICT";
    } else if (commit.trustState === "VERIFIED" && existing.trustState === "DECLARED") {
      existing.trustState = "VERIFIED";
    }
  }
  return byOid;
}

function compareTopoReady(a: InternalNode, b: InternalNode): number {
  const kp = KIND_PRIORITY[a.kind] - KIND_PRIORITY[b.kind];
  if (kp !== 0) return kp;
  const ts = a.timestampMs - b.timestampMs;
  if (ts !== 0) return ts;
  return a.id.localeCompare(b.id);
}

function compareVisualReady(
  a: InternalNode,
  b: InternalNode,
  topoOrder: Map<string, number>,
): number {
  const ts = b.timestampMs - a.timestampMs;
  if (ts !== 0) return ts;
  const kp = KIND_PRIORITY[b.kind] - KIND_PRIORITY[a.kind];
  if (kp !== 0) return kp;
  const to = (topoOrder.get(b.id) ?? 0) - (topoOrder.get(a.id) ?? 0);
  if (to !== 0) return to;
  return a.id.localeCompare(b.id);
}

class MinHeap<T> {
  private readonly items: T[] = [];
  constructor(private readonly compare: (a: T, b: T) => number) {}
  get size(): number {
    return this.items.length;
  }
  push(item: T): void {
    this.items.push(item);
    let idx = this.items.length - 1;
    while (idx > 0) {
      const parent = (idx - 1) >> 1;
      if (this.compare(this.items[idx]!, this.items[parent]!) < 0) {
        const tmp = this.items[idx]!;
        this.items[idx] = this.items[parent]!;
        this.items[parent] = tmp;
        idx = parent;
      } else {
        break;
      }
    }
  }
  pop(): T | undefined {
    const n = this.items.length;
    if (n === 0) return undefined;
    const top = this.items[0]!;
    const last = this.items.pop()!;
    if (n > 1) {
      this.items[0] = last;
      let idx = 0;
      const len = this.items.length;
      while (true) {
        const left = idx * 2 + 1;
        const right = left + 1;
        let smallest = idx;
        if (left < len && this.compare(this.items[left]!, this.items[smallest]!) < 0) {
          smallest = left;
        }
        if (right < len && this.compare(this.items[right]!, this.items[smallest]!) < 0) {
          smallest = right;
        }
        if (smallest === idx) break;
        const tmp = this.items[idx]!;
        this.items[idx] = this.items[smallest]!;
        this.items[smallest] = tmp;
        idx = smallest;
      }
    }
    return top;
  }
}

export function computeDagLayout(
  graph: ProjectGraph,
  previousLanes?: PreviousLanes | null,
  options?: DagFilterOptions,
): DagLayoutResult {
  const startMs = performance.now();

  const commitMap = deduplicateCommits(graph.commits);
  const refsByTarget = new Map<string, ObservedRef[]>();
  for (const observedRef of graph.refs) {
    const list = refsByTarget.get(observedRef.ref.targetOid) ?? [];
    list.push(observedRef);
    refsByTarget.set(observedRef.ref.targetOid, list);
  }
  for (const list of refsByTarget.values()) {
    list.sort((a, b) => {
      const nameCmp = a.ref.name.localeCompare(b.ref.name);
      if (nameCmp !== 0) return nameCmp;
      return a.source.deviceId.localeCompare(b.source.deviceId);
    });
  }

  const nodeMap = new Map<string, InternalNode>();
  const outgoingEdges = new Map<string, InternalEdge[]>();

  const ensureMissingNode = (oid: string, reason: "EXPLICIT_MISSING" | "OUTSIDE_PAGE"): string => {
    const id = missingNodeId(oid);
    const existing = nodeMap.get(id);
    if (!existing) {
      nodeMap.set(id, {
        id,
        kind: "MISSING_ANCESTRY",
        timestampMs: -1,
        oid,
        label: oid.slice(0, 8),
        subLabel: "历史不完整",
        orderedParents: [],
        refs: [],
        isMissingAncestry: true,
        missingReason: reason,
      });
      outgoingEdges.set(id, []);
    } else if (reason === "EXPLICIT_MISSING") {
      existing.missingReason = "EXPLICIT_MISSING";
    }
    return id;
  };

  const ensureEmptyTreeNode = (): string => {
    if (!nodeMap.has(EMPTY_TREE_NODE_ID)) {
      nodeMap.set(EMPTY_TREE_NODE_ID, {
        id: EMPTY_TREE_NODE_ID,
        kind: "EMPTY_TREE",
        timestampMs: -2,
        label: "EMPTY_TREE",
        subLabel: "初始空仓库",
        orderedParents: [],
        refs: [],
        isEmptyTree: true,
      });
      outgoingEdges.set(EMPTY_TREE_NODE_ID, []);
    }
    return EMPTY_TREE_NODE_ID;
  };

  // Register commits
  const sortedCommits = [...commitMap.values()].sort((a, b) => a.oid.localeCompare(b.oid));
  for (const commit of sortedCommits) {
    const primary = selectPrimaryObservation(commit.observations);
    const orderedParents = mergeOrderedParents(commit.observations, primary);
    const hasConflict = detectObservationDisagreement(commit);
    const id = commitNodeId(commit.oid);
    const refs = refsByTarget.get(commit.oid) ?? [];

    nodeMap.set(id, {
      id,
      kind: "COMMIT",
      timestampMs: parseTimestampMs(primary.metadata.authoredAt),
      oid: commit.oid,
      label: primary.metadata.subject || "(无提交说明)",
      subLabel: primary.metadata.authorName,
      commit,
      primaryObservation: primary,
      orderedParents,
      refs,
      hasConflict,
      trustState: hasConflict ? "CONFLICT" : commit.trustState,
    });

    const edges: InternalEdge[] = [];
    const seenEdgeTargets = new Set<string>();
    const explicitMissingSet = new Set(commit.missingParentOids);

    orderedParents.forEach((pOid, index) => {
      if (pOid === commit.oid) return; // guard self-loop
      if (commitMap.has(pOid)) {
        const targetId = commitNodeId(pOid);
        if (!seenEdgeTargets.has(targetId)) {
          seenEdgeTargets.add(targetId);
          edges.push({
            fromId: id,
            toId: targetId,
            kind: index === 0 ? "FIRST_PARENT" : "MERGE_PARENT",
            parentIndex: index,
          });
        }
      } else {
        const reason = explicitMissingSet.has(pOid) ? "EXPLICIT_MISSING" : "OUTSIDE_PAGE";
        const targetId = ensureMissingNode(pOid, reason);
        if (!seenEdgeTargets.has(targetId)) {
          seenEdgeTargets.add(targetId);
          edges.push({
            fromId: id,
            toId: targetId,
            kind: "MISSING_ANCESTRY",
            parentIndex: index,
          });
        }
      }
    });

    for (const mOid of commit.missingParentOids) {
      if (mOid === commit.oid || commitMap.has(mOid)) continue;
      const targetId = ensureMissingNode(mOid, "EXPLICIT_MISSING");
      if (!seenEdgeTargets.has(targetId)) {
        seenEdgeTargets.add(targetId);
        edges.push({
          fromId: id,
          toId: targetId,
          kind: "MISSING_ANCESTRY",
        });
      }
    }

    outgoingEdges.set(id, edges);
  }

  // Register independent WIP nodes by (worktreeId, worktreeEpoch, captureSeq)
  const sortedWips = [...graph.wipNodes].sort((a, b) => {
    const tCmp = parseTimestampMs(b.capturedAt) - parseTimestampMs(a.capturedAt);
    if (tCmp !== 0) return tCmp;
    const wCmp = a.worktreeId.localeCompare(b.worktreeId);
    if (wCmp !== 0) return wCmp;
    if (b.worktreeEpoch !== a.worktreeEpoch) return b.worktreeEpoch - a.worktreeEpoch;
    if (b.captureSeq !== a.captureSeq) return b.captureSeq - a.captureSeq;
    return a.snapshotId.localeCompare(b.snapshotId);
  });

  for (const wip of sortedWips) {
    const id = wipNodeId(wip);
    const isDetachedHead = wip.head.branch === null;
    const isEmptyTree = wip.head.baseKind === "EMPTY_TREE";
    const edges: InternalEdge[] = [];
    const orderedParents: string[] = [];

    if (wip.head.baseKind === "EMPTY_TREE") {
      const targetId = ensureEmptyTreeNode();
      edges.push({ fromId: id, toId: targetId, kind: "WIP_BASE", parentIndex: 0 });
    } else if (wip.head.baseCommitOid) {
      const baseOid = wip.head.baseCommitOid;
      orderedParents.push(baseOid);
      if (commitMap.has(baseOid)) {
        edges.push({ fromId: id, toId: commitNodeId(baseOid), kind: "WIP_BASE", parentIndex: 0 });
      } else {
        const targetId = ensureMissingNode(baseOid, "OUTSIDE_PAGE");
        edges.push({ fromId: id, toId: targetId, kind: "MISSING_ANCESTRY", parentIndex: 0 });
      }
    }

    nodeMap.set(id, {
      id,
      kind: "WIP",
      timestampMs: parseTimestampMs(wip.capturedAt),
      snapshotId: wip.snapshotId,
      worktreeId: wip.worktreeId,
      worktreeEpoch: wip.worktreeEpoch,
      captureSeq: wip.captureSeq,
      label: wip.head.branch ?? "Detached HEAD",
      subLabel: `worktree ${wip.worktreeId.slice(0, 8)} #${wip.worktreeEpoch}.${wip.captureSeq}`,
      orderedParents,
      wip,
      refs: [],
      isDetachedHead,
      isEmptyTree,
      freshness: wip.freshness,
      parseState: wip.parseState,
      captureState: wip.captureState,
      dataLevel: wip.dataLevel,
    });
    outgoingEdges.set(id, edges);
  }

  // Step 2: Topological sort (parents -> children) with defensive cycle breaking
  const allIds = [...nodeMap.keys()];
  const childrenOf = new Map<string, string[]>();
  const inDegree = new Map<string, number>();

  for (const id of allIds) {
    childrenOf.set(id, []);
    inDegree.set(id, 0);
  }

  for (const [childId, edges] of outgoingEdges.entries()) {
    const distinctParents = new Set<string>();
    for (const edge of edges) {
      if (edge.toId !== childId) distinctParents.add(edge.toId);
    }
    inDegree.set(childId, distinctParents.size);
    for (const parentId of distinctParents) {
      childrenOf.get(parentId)?.push(childId);
    }
  }

  const visited = new Set<string>();
  const brokenEdgeKeys = new Set<string>();
  const topoList: InternalNode[] = [];
  let hasBrokenCycles = false;

  const ready = new MinHeap<InternalNode>(compareTopoReady);
  for (const id of allIds) {
    if ((inDegree.get(id) ?? 0) === 0) {
      const node = nodeMap.get(id);
      if (node) ready.push(node);
    }
  }

  while (topoList.length < allIds.length) {
    let current: InternalNode | undefined;
    if (ready.size > 0) {
      current = ready.pop()!;
    } else {
      // Cycle detected: pick remaining node with minimal inDegree, oldest timestamp, deterministic id
      hasBrokenCycles = true;
      let candidate: InternalNode | undefined;
      let candidateDeg = Infinity;
      for (const id of allIds) {
        if (visited.has(id)) continue;
        const node = nodeMap.get(id)!;
        const deg = inDegree.get(id) ?? 0;
        if (
          !candidate ||
          deg < candidateDeg ||
          (deg === candidateDeg && compareTopoReady(node, candidate) < 0)
        ) {
          candidate = node;
          candidateDeg = deg;
        }
      }
      if (!candidate) break;
      current = candidate;
      // Mark all remaining unvisited parent edges into `current` as broken back-edges
      for (const edge of outgoingEdges.get(current.id) ?? []) {
        if (!visited.has(edge.toId)) {
          brokenEdgeKeys.add(`${edge.fromId}->${edge.toId}`);
        }
      }
    }

    if (visited.has(current.id)) continue;
    visited.add(current.id);
    topoList.push(current);

    for (const childId of childrenOf.get(current.id) ?? []) {
      if (visited.has(childId)) continue;
      const nextDeg = (inDegree.get(childId) ?? 0) - 1;
      inDegree.set(childId, nextDeg);
      if (nextDeg === 0) {
        const childNode = nodeMap.get(childId);
        if (childNode) ready.push(childNode);
      }
    }
  }

  const topoOrderMap = new Map<string, number>();
  topoList.forEach((node, idx) => topoOrderMap.set(node.id, idx));

  // Build cycle-free parent/child adjacency for visual ordering and filtering
  const dagParents = new Map<string, InternalEdge[]>();
  const dagChildren = new Map<string, string[]>();
  for (const id of allIds) {
    dagParents.set(id, []);
    dagChildren.set(id, []);
  }
  for (const [childId, edges] of outgoingEdges.entries()) {
    for (const edge of edges) {
      if (edge.toId === childId) continue;
      if (brokenEdgeKeys.has(`${edge.fromId}->${edge.toId}`)) continue;
      dagParents.get(childId)!.push(edge);
      dagChildren.get(edge.toId)!.push(childId);
    }
  }

  // Step 3: Reverse topological ordering (children -> parents) for visual rows
  const outDegree = new Map<string, number>();
  const visualReady = new MinHeap<InternalNode>((a, b) => compareVisualReady(a, b, topoOrderMap));
  for (const id of allIds) {
    const deg = (dagChildren.get(id) ?? []).length;
    outDegree.set(id, deg);
    if (deg === 0) {
      const node = nodeMap.get(id);
      if (node) visualReady.push(node);
    }
  }

  const visualOrder: InternalNode[] = [];
  while (visualReady.size > 0) {
    const current = visualReady.pop()!;
    visualOrder.push(current);
    for (const edge of dagParents.get(current.id) ?? []) {
      const pId = edge.toId;
      const nextOut = (outDegree.get(pId) ?? 0) - 1;
      outDegree.set(pId, nextOut);
      if (nextOut === 0) {
        const pNode = nodeMap.get(pId);
        if (pNode) visualReady.push(pNode);
      }
    }
  }

  // Step 4: Filtering & honest OMITTED_LINK computation
  const query = options?.query?.trim().toLowerCase() ?? "";
  const branch = options?.branch?.trim() ?? "";
  const author = options?.author?.trim().toLowerCase() ?? "";
  const onlyConflicts = Boolean(options?.onlyConflicts);
  const onlyWip = Boolean(options?.onlyWip);
  const hasFilter = Boolean(query || branch || author || onlyConflicts || onlyWip);

  let branchReachable: Set<string> | null = null;
  if (branch) {
    branchReachable = new Set<string>();
    const queue: string[] = [];
    for (const node of visualOrder) {
      if (node.kind === "WIP") {
        const wipBranch = node.wip?.head.branch ?? "Detached HEAD";
        if (wipBranch === branch) {
          queue.push(node.id);
        }
      } else if (node.kind === "COMMIT") {
        const matchesRef = node.refs.some(
          (r) => r.ref.name === branch || stripRefPrefix(r.ref.name) === branch,
        );
        if (matchesRef) {
          queue.push(node.id);
        }
      }
    }
    let head = 0;
    while (head < queue.length) {
      const cur = queue[head++]!;
      if (branchReachable.has(cur)) continue;
      branchReachable.add(cur);
      for (const edge of dagParents.get(cur) ?? []) {
        if (!branchReachable.has(edge.toId)) {
          queue.push(edge.toId);
        }
      }
    }
  }

  const matchesPrimaryFilter = (node: InternalNode): boolean => {
    if (node.kind !== "COMMIT" && node.kind !== "WIP") return false;
    if (onlyWip && node.kind !== "WIP") return false;
    if (onlyConflicts && (node.kind !== "COMMIT" || node.trustState !== "CONFLICT")) return false;
    if (branchReachable && !branchReachable.has(node.id)) return false;

    if (author) {
      if (node.kind === "COMMIT") {
        const matchCommitAuthor = node.commit!.observations.some(
          (o) =>
            o.metadata.authorName.toLowerCase().includes(author) ||
            (o.metadata.authorEmail?.toLowerCase().includes(author) ?? false) ||
            o.source.userId.toLowerCase().includes(author),
        );
        if (!matchCommitAuthor) return false;
      } else if (node.kind === "WIP") {
        if (!node.wip!.source.userId.toLowerCase().includes(author)) return false;
      }
    }

    if (query) {
      if (node.kind === "COMMIT") {
        const refMatch = node.refs.some((r) => r.ref.name.toLowerCase().includes(query));
        const obsMatch = node.commit!.observations.some(
          (o) =>
            o.metadata.subject.toLowerCase().includes(query) ||
            o.metadata.authorName.toLowerCase().includes(query) ||
            (o.metadata.authorEmail?.toLowerCase().includes(query) ?? false) ||
            o.source.userId.toLowerCase().includes(query) ||
            o.source.deviceId.toLowerCase().includes(query),
        );
        const oidMatch = node.commit!.oid.toLowerCase().includes(query);
        const trustMatch = (node.trustState ?? "").toLowerCase().includes(query);
        if (!oidMatch && !refMatch && !obsMatch && !trustMatch) return false;
      } else if (node.kind === "WIP") {
        const w = node.wip!;
        const haystack = [
          w.snapshotId,
          w.worktreeId,
          w.head.branch ?? "Detached HEAD",
          w.head.baseCommitOid ?? "EMPTY_TREE",
          w.freshness,
          w.captureState,
          w.parseState,
          w.source.userId,
          w.source.deviceId,
        ]
          .join(" ")
          .toLowerCase();
        if (!haystack.includes(query)) return false;
      }
    }

    return true;
  };

  const keptPrimaryIds = new Set<string>();
  const keptBoundaryIds = new Set<string>();
  const filteredEdges: FilteredEdge[] = [];

  if (!hasFilter) {
    for (const node of visualOrder) {
      if (node.kind === "COMMIT" || node.kind === "WIP") keptPrimaryIds.add(node.id);
      else keptBoundaryIds.add(node.id);
    }
    for (const node of visualOrder) {
      for (const edge of dagParents.get(node.id) ?? []) {
        filteredEdges.push({
          fromId: edge.fromId,
          toId: edge.toId,
          kind: edge.kind,
          parentIndex: edge.parentIndex,
        });
      }
    }
  } else {
    for (const node of visualOrder) {
      if (matchesPrimaryFilter(node)) {
        keptPrimaryIds.add(node.id);
      }
    }

    for (const node of visualOrder) {
      if (!keptPrimaryIds.has(node.id)) continue;

      const directTargetIds = new Set<string>();
      const hiddenStartParents: string[] = [];

      for (const edge of dagParents.get(node.id) ?? []) {
        const targetNode = nodeMap.get(edge.toId);
        if (!targetNode) continue;
        if (keptPrimaryIds.has(edge.toId)) {
          directTargetIds.add(edge.toId);
          filteredEdges.push({
            fromId: edge.fromId,
            toId: edge.toId,
            kind: edge.kind,
            parentIndex: edge.parentIndex,
          });
        } else if (targetNode.kind === "MISSING_ANCESTRY" || targetNode.kind === "EMPTY_TREE") {
          keptBoundaryIds.add(edge.toId);
          directTargetIds.add(edge.toId);
          filteredEdges.push({
            fromId: edge.fromId,
            toId: edge.toId,
            kind: edge.kind,
            parentIndex: edge.parentIndex,
          });
        } else {
          hiddenStartParents.push(edge.toId);
        }
      }

      if (hiddenStartParents.length > 0) {
        // BFS across hidden nodes to find nearest kept ancestors or boundaries
        const bestHiddenDepth = new Map<string, number>();
        const reachedKept = new Map<string, number>();
        const bfsQueue: Array<{ id: string; omitted: number }> = [];

        for (const startHidden of hiddenStartParents) {
          bfsQueue.push({ id: startHidden, omitted: 0 });
        }

        let bfsHead = 0;
        while (bfsHead < bfsQueue.length) {
          const item = bfsQueue[bfsHead++]!;
          const targetNode = nodeMap.get(item.id);
          if (!targetNode) continue;

          if (keptPrimaryIds.has(item.id)) {
            const prev = reachedKept.get(item.id);
            if (prev === undefined || item.omitted < prev) {
              reachedKept.set(item.id, item.omitted);
            }
            continue;
          }

          if (targetNode.kind === "MISSING_ANCESTRY" || targetNode.kind === "EMPTY_TREE") {
            keptBoundaryIds.add(item.id);
            const prev = reachedKept.get(item.id);
            if (prev === undefined || item.omitted < prev) {
              reachedKept.set(item.id, item.omitted);
            }
            continue;
          }

          const seenDepth = bestHiddenDepth.get(item.id);
          if (seenDepth !== undefined && seenDepth <= item.omitted) continue;
          bestHiddenDepth.set(item.id, item.omitted);

          for (const nextEdge of dagParents.get(item.id) ?? []) {
            bfsQueue.push({ id: nextEdge.toId, omitted: item.omitted + 1 });
          }
        }

        const sortedTargets = [...reachedKept.entries()].sort((a, b) => a[0].localeCompare(b[0]));
        for (const [targetId, omittedCount] of sortedTargets) {
          if (directTargetIds.has(targetId)) continue;
          filteredEdges.push({
            fromId: node.id,
            toId: targetId,
            kind: "OMITTED_LINK",
            omittedCount,
          });
        }
      }
    }
  }

  const keptVisualNodes = visualOrder.filter(
    (node) => keptPrimaryIds.has(node.id) || keptBoundaryIds.has(node.id),
  );

  // Assign dense visual rows
  const keptRowById = new Map<string, number>();
  keptVisualNodes.forEach((node, rowIdx) => {
    keptRowById.set(node.id, rowIdx);
  });

  // Group kept outgoing edges by source node, mainline first
  const keptOutgoingByFrom = new Map<string, FilteredEdge[]>();
  for (const node of keptVisualNodes) keptOutgoingByFrom.set(node.id, []);
  for (const edge of filteredEdges) {
    keptOutgoingByFrom.get(edge.fromId)?.push(edge);
  }
  const edgeOrderPriority = (kind: LayoutEdgeKind): number => {
    switch (kind) {
      case "FIRST_PARENT":
      case "WIP_BASE":
        return 0;
      case "MERGE_PARENT":
        return 1;
      case "MISSING_ANCESTRY":
        return 2;
      case "OMITTED_LINK":
        return 3;
    }
  };
  for (const edges of keptOutgoingByFrom.values()) {
    edges.sort((a, b) => {
      const ep = edgeOrderPriority(a.kind) - edgeOrderPriority(b.kind);
      if (ep !== 0) return ep;
      const pi = (a.parentIndex ?? 999) - (b.parentIndex ?? 999);
      if (pi !== 0) return pi;
      const rowDiff = (keptRowById.get(a.toId) ?? 0) - (keptRowById.get(b.toId) ?? 0);
      if (rowDiff !== 0) return rowDiff;
      return a.toId.localeCompare(b.toId);
    });
  }

  // Step 5: Deterministic lane assignment with mainline preference & previousLanes stability
  const activeLaneTarget: Array<string | null> = [];
  const laneById = new Map<string, number>();

  // Index unplaced nodes by their previous lane in visual row order (O(1) amortized claimant checks)
  const prevClaimRowsByLane = new Map<number, number[]>();
  const prevClaimHeadByLane = new Map<number, number>();
  keptVisualNodes.forEach((node, rowIdx) => {
    const prevL = readPrevLane(previousLanes, node.id);
    if (prevL !== undefined) {
      let arr = prevClaimRowsByLane.get(prevL);
      if (!arr) {
        arr = [];
        prevClaimRowsByLane.set(prevL, arr);
        prevClaimHeadByLane.set(prevL, 0);
      }
      arr.push(rowIdx);
    }
  });

  const advanceClaimHeads = (currentRow: number) => {
    for (const [laneIdx, rows] of prevClaimRowsByLane.entries()) {
      let ptr = prevClaimHeadByLane.get(laneIdx) ?? 0;
      while (ptr < rows.length && rows[ptr]! <= currentRow) {
        ptr++;
      }
      prevClaimHeadByLane.set(laneIdx, ptr);
    }
  };

  const hasAnyUnplacedClaimant = (laneIdx: number): boolean => {
    const rows = prevClaimRowsByLane.get(laneIdx);
    if (!rows) return false;
    const ptr = prevClaimHeadByLane.get(laneIdx) ?? 0;
    return ptr < rows.length;
  };

  const hasUnplacedClaimantBeforeRow = (laneIdx: number, limitRow: number): boolean => {
    const rows = prevClaimRowsByLane.get(laneIdx);
    if (!rows) return false;
    const ptr = prevClaimHeadByLane.get(laneIdx) ?? 0;
    return ptr < rows.length && rows[ptr]! < limitRow;
  };

  const findSmallestFreeLane = (preferredMin = 0, avoidPrevClaimed = true): number => {
    if (avoidPrevClaimed) {
      for (let i = preferredMin; i < activeLaneTarget.length; i++) {
        if (activeLaneTarget[i] === null && !hasAnyUnplacedClaimant(i)) return i;
      }
      for (let i = 0; i < preferredMin && i < activeLaneTarget.length; i++) {
        if (activeLaneTarget[i] === null && !hasAnyUnplacedClaimant(i)) return i;
      }
      // Allocate a fresh lane beyond all previously claimed lanes if needed
      let nextIdx = activeLaneTarget.length;
      while (hasAnyUnplacedClaimant(nextIdx)) {
        activeLaneTarget.push(null);
        nextIdx++;
      }
      activeLaneTarget.push(null);
      return nextIdx;
    }
    for (let i = preferredMin; i < activeLaneTarget.length; i++) {
      if (activeLaneTarget[i] === null) return i;
    }
    for (let i = 0; i < preferredMin && i < activeLaneTarget.length; i++) {
      if (activeLaneTarget[i] === null) return i;
    }
    activeLaneTarget.push(null);
    return activeLaneTarget.length - 1;
  };

  const reserveSpecificOrFreeLane = (laneIdx: number, targetId: string): number => {
    while (activeLaneTarget.length <= laneIdx) activeLaneTarget.push(null);
    if (activeLaneTarget[laneIdx] === null) {
      activeLaneTarget[laneIdx] = targetId;
      return laneIdx;
    }
    const free = findSmallestFreeLane(0, true);
    activeLaneTarget[free] = targetId;
    return free;
  };

  keptVisualNodes.forEach((node, currentRow) => {
    // Advance claimant heads past any strictly earlier rows (not yet past currentRow until placed)
    const prevNodeLane = readPrevLane(previousLanes, node.id);
    const matchingLanes: number[] = [];
    for (let i = 0; i < activeLaneTarget.length; i++) {
      if (activeLaneTarget[i] === node.id) {
        matchingLanes.push(i);
      }
    }

    let chosenLane: number;
    if (matchingLanes.length > 0) {
      if (prevNodeLane !== undefined && matchingLanes.includes(prevNodeLane)) {
        chosenLane = prevNodeLane;
      } else {
        chosenLane = matchingLanes[0]!;
      }
      for (const l of matchingLanes) {
        activeLaneTarget[l] = null;
      }
    } else {
      const out = keptOutgoingByFrom.get(node.id) ?? [];
      const firstTargetId = out[0]?.toId;
      const firstTargetRow = firstTargetId ? (keptRowById.get(firstTargetId) ?? currentRow + 1) : currentRow + 1;
      const prevParentLane = firstTargetId ? readPrevLane(previousLanes, firstTargetId) : undefined;

      if (
        prevNodeLane !== undefined &&
        (prevNodeLane >= activeLaneTarget.length || activeLaneTarget[prevNodeLane] === null)
      ) {
        chosenLane = prevNodeLane;
      } else if (
        prevParentLane !== undefined &&
        firstTargetId &&
        (prevParentLane >= activeLaneTarget.length || activeLaneTarget[prevParentLane] === null) &&
        !hasUnplacedClaimantBeforeRow(prevParentLane, firstTargetRow)
      ) {
        chosenLane = prevParentLane;
      } else {
        chosenLane = findSmallestFreeLane(0, true);
      }
      while (activeLaneTarget.length <= chosenLane) activeLaneTarget.push(null);
      activeLaneTarget[chosenLane] = null;
    }

    laneById.set(node.id, chosenLane);
    advanceClaimHeads(currentRow);

    // Reserve lanes for outgoing edges
    const outEdges = keptOutgoingByFrom.get(node.id) ?? [];
    outEdges.forEach((edge, idx) => {
      const targetId = edge.toId;
      const targetRow = keptRowById.get(targetId) ?? currentRow + 1;
      const existingLaneIdx = activeLaneTarget.findIndex((t) => t === targetId);
      const prevTargetLane = readPrevLane(previousLanes, targetId);

      if (idx === 0) {
        if (existingLaneIdx >= 0) {
          // If target was tentatively reserved on another lane by a side branch,
          // allow mainline / prevTargetLane to also reserve chosenLane so target stays stable!
          if (
            activeLaneTarget[chosenLane] === null &&
            (prevTargetLane === chosenLane || (prevTargetLane === undefined && chosenLane < existingLaneIdx))
          ) {
            activeLaneTarget[chosenLane] = targetId;
          }
          return;
        }
        if (activeLaneTarget[chosenLane] === null) {
          activeLaneTarget[chosenLane] = targetId;
        } else if (
          prevTargetLane !== undefined &&
          !hasUnplacedClaimantBeforeRow(prevTargetLane, targetRow)
        ) {
          reserveSpecificOrFreeLane(prevTargetLane, targetId);
        } else {
          const free = findSmallestFreeLane(chosenLane, true);
          activeLaneTarget[free] = targetId;
        }
      } else {
        if (existingLaneIdx >= 0) return;
        if (
          prevTargetLane !== undefined &&
          (prevTargetLane >= activeLaneTarget.length || activeLaneTarget[prevTargetLane] === null) &&
          !hasUnplacedClaimantBeforeRow(prevTargetLane, targetRow)
        ) {
          reserveSpecificOrFreeLane(prevTargetLane, targetId);
        } else {
          const free = findSmallestFreeLane(chosenLane + 1, true);
          activeLaneTarget[free] = targetId;
        }
      }
    });
  });

  // Step 6: Materialize LayoutNodes & LayoutEdges
  let maxLane = 0;
  let visibleCommitCount = 0;
  let visibleWipCount = 0;
  const lanesByNodeId: Record<string, number> = {};
  const layoutNodeById = new Map<string, LayoutNode>();

  const nodes: LayoutNode[] = keptVisualNodes.map((node, row) => {
    const lane = laneById.get(node.id) ?? 0;
    if (lane > maxLane) maxLane = lane;
    lanesByNodeId[node.id] = lane;

    if (node.kind === "COMMIT") visibleCommitCount++;
    if (node.kind === "WIP") visibleWipCount++;

    const layoutNode: LayoutNode = {
      id: node.id,
      kind: node.kind,
      oid: node.oid,
      snapshotId: node.snapshotId,
      worktreeId: node.worktreeId,
      worktreeEpoch: node.worktreeEpoch,
      captureSeq: node.captureSeq,
      lane,
      row,
      topoOrder: topoOrderMap.get(node.id) ?? row,
      x: DAG_LEFT_MARGIN + lane * DAG_LANE_WIDTH,
      y: DAG_TOP_MARGIN + row * DAG_ROW_HEIGHT,
      label: node.label,
      subLabel: node.subLabel,
      commit: node.commit,
      primaryObservation: node.primaryObservation,
      orderedParents: node.orderedParents,
      wip: node.wip,
      refs: node.refs,
      isDetachedHead: node.isDetachedHead,
      isEmptyTree: node.isEmptyTree,
      isMissingAncestry: node.isMissingAncestry,
      missingReason: node.missingReason,
      hasConflict: node.hasConflict,
      trustState: node.trustState,
      freshness: node.freshness,
      parseState: node.parseState,
      captureState: node.captureState,
      dataLevel: node.dataLevel,
    };
    layoutNodeById.set(layoutNode.id, layoutNode);
    return layoutNode;
  });

  const edges: LayoutEdge[] = [];
  for (const edge of filteredEdges) {
    const fromNode = layoutNodeById.get(edge.fromId);
    const toNode = layoutNodeById.get(edge.toId);
    if (!fromNode || !toNode) continue;
    edges.push({
      id: `${edge.fromId}->${edge.toId}:${edge.kind}`,
      fromId: edge.fromId,
      toId: edge.toId,
      kind: edge.kind,
      parentIndex: edge.parentIndex,
      omittedCount: edge.omittedCount,
      x1: fromNode.x,
      y1: fromNode.y,
      x2: toNode.x,
      y2: toNode.y,
      fromLane: fromNode.lane,
      toLane: toNode.lane,
    });
  }

  const laneCount = nodes.length === 0 ? 0 : maxLane + 1;
  const rowCount = nodes.length;
  const trackRight = DAG_LEFT_MARGIN + Math.max(1, laneCount) * DAG_LANE_WIDTH;
  const width = Math.max(860, trackRight + 640);
  const height = Math.max(360, DAG_TOP_MARGIN * 2 + Math.max(0, rowCount - 1) * DAG_ROW_HEIGHT + 40);
  const layoutDurationMs = Math.max(0, performance.now() - startMs);

  return {
    nodes,
    edges,
    lanesByNodeId,
    laneCount,
    rowCount,
    width,
    height,
    layoutDurationMs,
    totalSourceCommits: sortedCommits.length,
    totalSourceWips: sortedWips.length,
    visibleCommitCount,
    visibleWipCount,
    hasBrokenCycles,
  };
}

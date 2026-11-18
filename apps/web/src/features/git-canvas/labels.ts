import type {
  CaptureState,
  CodeCommentSide,
  ContentManifestStatus,
  DataLevel,
  DiffEndpointRef,
  DiscussionStatus,
  GitTrustState,
  ParseState,
  TwoPointComparisonState,
  TwoPointDiffFileEntry,
  TwoPointDiffStatus,
  WipNode,
} from "@orbitspace/shared-types";

export const TRUST_STATE_LABELS: Record<GitTrustState, string> = {
  DECLARED: "已声明",
  VERIFIED: "已校验",
  CONFLICT: "观测冲突 — 来源元信息存在分歧，不显示未说明领先/落后步数",
};

export const WIP_FRESHNESS_LABELS: Record<WipNode["freshness"], string> = {
  ACTIVE: "活跃 WIP",
  STALE: "状态可能已过期",
};

export const CAPTURE_STATE_LABELS: Record<CaptureState, string> = {
  CHANGES: "已采集共享变更",
  NO_SHARED_CHANGES: "授权范围内无共享变更",
  CLEAN: "工作区与索引已确认干净",
};

export const PARSE_STATE_LABELS: Record<ParseState, string> = {
  NOT_REQUESTED: "未请求解析",
  PENDING: "排队等待解析",
  RUNNING: "正在解析",
  SUCCEEDED: "解析成功",
  RETRY_WAIT: "等待重试解析",
  FAILED: "解析失败",
  CANCELLED: "已取消解析",
};

export const DATA_LEVEL_LABELS: Record<DataLevel, string> = {
  METADATA_ONLY: "仅元信息 (METADATA_ONLY)",
  PATCH_ONLY: "仅 Patch (PATCH_ONLY)",
  TREE_AVAILABLE: "代码树可用 (TREE_AVAILABLE)",
};

export const CONTENT_MANIFEST_META: Record<
  ContentManifestStatus,
  { label: string; tone: "ok" | "removed" | "muted" | "warn"; description: string }
> = {
  AVAILABLE: {
    label: "内容可用",
    tone: "ok",
    description: "已有授权可读取的内容或已校验的重建证据。",
  },
  ABSENT_CONFIRMED: {
    label: "已确认不存在/删除",
    tone: "removed",
    description: "仅此状态明确断言该路径在最终工作区中不存在或已删除。",
  },
  NOT_SHARED: {
    label: "未共享",
    tone: "muted",
    description: "未纳入本次共享范围，切勿视为文件已删除。",
  },
  MISSING: {
    label: "内容缺失",
    tone: "warn",
    description: "记录存在但实际内容字节缺失，切勿视为文件已删除。",
  },
  UNSUPPORTED: {
    label: "不支持的条目（子模块 / LFS 指针）",
    tone: "warn",
    description: "Git 子模块、LFS 指针或当前不支持解析的条目。",
  },
};

export function shortOid(oid: string): string {
  return oid.slice(0, 8);
}

export function formatDateTime(iso: string): string {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return iso;
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(ms));
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

export const TWO_POINT_DIFF_STATUS_LABELS: Record<TwoPointDiffStatus, string> = {
  PENDING: "计算 / 解析中",
  READY: "差异已就绪",
  FAILED: "计算失败",
};

export const TWO_POINT_COMPARISON_META: Record<
  TwoPointComparisonState,
  {
    label: string;
    shortLabel: string;
    tone: "ok" | "removed" | "info" | "muted" | "warn";
  }
> = {
  ADDED: {
    label: "确定新增 (ADDED)",
    shortLabel: "新增",
    tone: "ok",
  },
  DELETED: {
    label: "已确认删除 (DELETED)",
    shortLabel: "删除",
    tone: "removed",
  },
  MODIFIED: {
    label: "确定修改 (MODIFIED)",
    shortLabel: "修改",
    tone: "info",
  },
  UNCHANGED: {
    label: "内容一致 (UNCHANGED)",
    shortLabel: "未变更",
    tone: "muted",
  },
  INCOMPARABLE_NOT_SHARED: {
    label: "未共享 / 缺少基础内容不可比较 (NOT_SHARED)",
    shortLabel: "未共享不可比较",
    tone: "warn",
  },
  INCOMPARABLE_MISSING: {
    label: "未共享 / 缺少基础内容不可比较 (MISSING)",
    shortLabel: "缺少内容不可比较",
    tone: "warn",
  },
  INCOMPARABLE_UNSUPPORTED: {
    label: "不支持比较 (UNSUPPORTED)",
    shortLabel: "不支持比较",
    tone: "warn",
  },
};

export const DISCUSSION_STATUS_LABELS: Record<DiscussionStatus, string> = {
  OPEN: "进行中 (OPEN)",
  RESOLVED: "已解决 (RESOLVED)",
};

export const CODE_COMMENT_SIDE_LABELS: Record<CodeCommentSide, string> = {
  OLD: "旧侧 (OLD / Base)",
  NEW: "新侧 (NEW / Target)",
};

/** Returns true only when the manifest status carries positive presence or absence evidence. */
export function isEvidenceSufficient(status: ContentManifestStatus): boolean {
  return status === "AVAILABLE" || status === "ABSENT_CONFIRMED";
}

/**
 * Enforces the five-state honesty boundary on the client regardless of optimistic flags:
 * any NOT_SHARED, MISSING, or UNSUPPORTED endpoint forces an incomparable classification.
 */
export function resolveEffectiveComparisonState(
  entry: TwoPointDiffFileEntry,
): TwoPointComparisonState {
  if (entry.baseStatus === "UNSUPPORTED" || entry.targetStatus === "UNSUPPORTED") {
    return "INCOMPARABLE_UNSUPPORTED";
  }
  if (entry.baseStatus === "NOT_SHARED" || entry.targetStatus === "NOT_SHARED") {
    return "INCOMPARABLE_NOT_SHARED";
  }
  if (entry.baseStatus === "MISSING" || entry.targetStatus === "MISSING") {
    return "INCOMPARABLE_MISSING";
  }
  if (!entry.deterministic) {
    if (
      entry.comparisonState === "INCOMPARABLE_NOT_SHARED" ||
      entry.comparisonState === "INCOMPARABLE_MISSING" ||
      entry.comparisonState === "INCOMPARABLE_UNSUPPORTED"
    ) {
      return entry.comparisonState;
    }
    return "INCOMPARABLE_UNSUPPORTED";
  }
  return entry.comparisonState;
}

/** Returns true only when both endpoints have sufficient evidence and deterministic diff output. */
export function isEntryComparable(entry: TwoPointDiffFileEntry): boolean {
  const effective = resolveEffectiveComparisonState(entry);
  return (
    isEvidenceSufficient(entry.baseStatus) &&
    isEvidenceSufficient(entry.targetStatus) &&
    entry.deterministic &&
    (effective === "ADDED" ||
      effective === "DELETED" ||
      effective === "MODIFIED" ||
      effective === "UNCHANGED")
  );
}

export function buildBackfillCommand(targetId: string): string {
  return `orbit backfill --target ${targetId}`;
}

export function endpointsEqual(
  a: DiffEndpointRef | null | undefined,
  b: DiffEndpointRef | null | undefined,
): boolean {
  if (!a || !b) return false;
  return a.kind === b.kind && a.id === b.id;
}

export function formatEndpointShort(ref: DiffEndpointRef): string {
  return `${ref.kind === "COMMIT" ? "Commit" : "WIP"} ${shortOid(ref.id)}`;
}

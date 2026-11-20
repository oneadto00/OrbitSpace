import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import type { CommitNode, GitSource, ObservedRef, SpaceRole } from "@orbitspace/shared-types";
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  Clock,
  GitBranch,
  GitCompareArrows,
  KeyRound,
  Link2,
  RefreshCw,
  ShieldCheck,
  Trash2,
  Unlink,
} from "lucide-react";
import { queryClient, spaceKey, type SpaceAccess } from "../../shared/api/access";
import { canUploadOrManage } from "../../shared/auth/session";
import { Button, Field, Loading } from "../../shared/ui/UiComponents";
import { fetchProjectGraph } from "../git-canvas/gitCanvasApi";
import { formatDateTime, makeIdempotencyKey } from "../space/spaceApi";
import {
  bindGitSource,
  compareGitSource,
  listGitSources,
  maskedTokenDisplay,
  remoteGitErrorText,
  syncGitSource,
  unbindGitSource,
  validateControlledHttpsUrl,
  type RemoteGitComparison,
} from "./remoteGitApi";
import "./remote-git.css";

export interface RemoteGitSettingsPanelProps {
  spaceId: string;
  projectId: string;
  projectName: string;
  spaceRole: SpaceRole;
  access: SpaceAccess;
  onBackToCanvas?: () => void;
}

interface SourceObservationStats {
  remoteRefs: ObservedRef[];
  latestSyncedAt: string | null;
  verifiedCommits: number;
  conflictCommits: CommitNode[];
}

function shortOid(oid: string): string {
  return oid.length > 12 ? oid.slice(0, 10) : oid;
}

function reachabilityLabel(value: RemoteGitComparison["localReachability"]): {
  label: string;
  tone: string;
} {
  switch (value) {
    case "REACHABLE":
      return { label: "远端可达 (REACHABLE)", tone: "ok" };
    case "NO_LONGER_REACHABLE":
      return { label: "远端不再可达（可能 rebase / force-push）", tone: "warn" };
    default:
      return { label: "可达性未知 (UNKNOWN)", tone: "muted" };
  }
}

export function RemoteGitSettingsPanel({
  spaceId,
  projectId,
  projectName,
  spaceRole,
  access,
  onBackToCanvas,
}: RemoteGitSettingsPanelProps) {
  const canConfigure = canUploadOrManage(spaceRole);

  const [httpsUrl, setHttpsUrl] = useState("");
  const [accessToken, setAccessToken] = useState("");
  const [urlError, setUrlError] = useState<string | null>(null);
  const [statusNotice, setStatusNotice] = useState<string | null>(null);
  const [syncReceipts, setSyncReceipts] = useState<Record<string, { jobId: string; queuedAt: string }>>({});

  const [rotatingSourceId, setRotatingSourceId] = useState<string | null>(null);
  const [rotateTokenInput, setRotateTokenInput] = useState("");
  const [confirmUnbindSource, setConfirmUnbindSource] = useState<GitSource | null>(null);

  const [compareSourceId, setCompareSourceId] = useState("");
  const [compareRemoteRef, setCompareRemoteRef] = useState("");
  const [compareLocalOid, setCompareLocalOid] = useState("");
  const [compareResult, setCompareResult] = useState<RemoteGitComparison | null>(null);
  const [compareError, setCompareError] = useState<string | null>(null);

  const bindIdemRef = useRef<string>(makeIdempotencyKey());

  const sourcesQueryKey = useMemo(
    () => [...spaceKey(access), "project", projectId, "git-sources"] as const,
    [access, projectId],
  );
  const graphQueryKey = useMemo(
    () => [...spaceKey(access), "project", projectId, "remote-observation-graph"] as const,
    [access, projectId],
  );

  const sourcesQuery = useQuery({
    queryKey: sourcesQueryKey,
    queryFn: ({ signal }) => listGitSources(spaceId, projectId, access, signal),
  });

  const graphQuery = useQuery({
    queryKey: graphQueryKey,
    queryFn: ({ signal }) => fetchProjectGraph(spaceId, projectId, access, null, 100, signal),
  });

  const sources = sourcesQuery.data ?? [];
  const graph = graphQuery.data;

  // Build per-source sync observations and project-wide conflict observations from the live DAG
  const { statsBySource, allConflictCommits, localCommits } = useMemo(() => {
    const map = new Map<string, SourceObservationStats>();
    for (const s of sources) {
      map.set(s.sourceId, {
        remoteRefs: [],
        latestSyncedAt: null,
        verifiedCommits: 0,
        conflictCommits: [],
      });
    }

    const conflicts: CommitNode[] = [];
    const locals: CommitNode[] = [];

    if (graph) {
      for (const ref of graph.refs) {
        if (!ref.source.deviceId.startsWith("remote:")) continue;
        const sourceId = ref.source.deviceId.slice("remote:".length);
        const bucket = map.get(sourceId);
        if (!bucket) continue;
        bucket.remoteRefs.push(ref);
        if (
          !bucket.latestSyncedAt ||
          Date.parse(ref.source.observedAt) > Date.parse(bucket.latestSyncedAt)
        ) {
          bucket.latestSyncedAt = ref.source.observedAt;
        }
      }

      for (const commit of graph.commits) {
        if (commit.trustState === "CONFLICT") {
          conflicts.push(commit);
        }
        const hasLocalObs = commit.observations.some(
          (obs) => !obs.source.deviceId.startsWith("remote:"),
        );
        if (hasLocalObs) {
          locals.push(commit);
        }
        for (const obs of commit.observations) {
          if (!obs.source.deviceId.startsWith("remote:")) continue;
          const sourceId = obs.source.deviceId.slice("remote:".length);
          const bucket = map.get(sourceId);
          if (!bucket) continue;
          if (obs.trustState === "VERIFIED") {
            bucket.verifiedCommits += 1;
          }
          if (commit.trustState === "CONFLICT" && !bucket.conflictCommits.includes(commit)) {
            bucket.conflictCommits.push(commit);
          }
          if (
            !bucket.latestSyncedAt ||
            Date.parse(obs.source.observedAt) > Date.parse(bucket.latestSyncedAt)
          ) {
            bucket.latestSyncedAt = obs.source.observedAt;
          }
        }
      }
    }

    return {
      statsBySource: map,
      allConflictCommits: conflicts,
      localCommits: locals.length > 0 ? locals : graph?.commits ?? [],
    };
  }, [graph, sources]);

  useEffect(() => {
    if (sources.length === 0) {
      setCompareSourceId("");
      return;
    }
    if (!compareSourceId || !sources.some((s) => s.sourceId === compareSourceId)) {
      setCompareSourceId(sources[0]!.sourceId);
    }
  }, [compareSourceId, sources]);

  const selectedSourceRefs = useMemo(() => {
    if (!compareSourceId) return [];
    return statsBySource.get(compareSourceId)?.remoteRefs ?? [];
  }, [compareSourceId, statsBySource]);

  useEffect(() => {
    if (selectedSourceRefs.length > 0 && !compareRemoteRef) {
      setCompareRemoteRef(selectedSourceRefs[0]!.ref.name);
    }
  }, [compareRemoteRef, selectedSourceRefs]);

  useEffect(() => {
    if (localCommits.length > 0 && !compareLocalOid) {
      setCompareLocalOid(localCommits[0]!.oid);
    }
  }, [compareLocalOid, localCommits]);

  const invalidateProjectGitData = () => {
    void queryClient.invalidateQueries({ queryKey: sourcesQueryKey });
    void queryClient.invalidateQueries({ queryKey: graphQueryKey });
    void queryClient.invalidateQueries({
      queryKey: [...spaceKey(access), "project", projectId, "graph"],
    });
  };

  const bindMutation = useMutation({
    mutationFn: ({ url, token, key }: { url: string; token: string; key: string }) =>
      bindGitSource(
        spaceId,
        projectId,
        { httpsUrl: url, accessToken: token || undefined },
        access,
        key,
      ),
    onSuccess: (created) => {
      bindIdemRef.current = makeIdempotencyKey();
      setHttpsUrl("");
      setAccessToken("");
      setUrlError(null);
      setStatusNotice(
        `已绑定受控远端仓库 ${created.httpsUrl}${created.credentialConfigured ? "（加密 Token 已存储，界面仅显示掩码）" : ""}。`,
      );
      invalidateProjectGitData();
    },
  });

  const syncMutation = useMutation({
    mutationFn: ({ sourceId, key }: { sourceId: string; key: string }) =>
      syncGitSource(spaceId, projectId, sourceId, access, key),
    onSuccess: (receipt, variables) => {
      setSyncReceipts((prev) => ({
        ...prev,
        [variables.sourceId]: { jobId: receipt.jobId, queuedAt: new Date().toISOString() },
      }));
      setStatusNotice(`已提交受控远端同步任务（Job #${receipt.jobId.slice(0, 8)}），同步完成后可刷新观测状态。`);
      invalidateProjectGitData();
    },
  });

  const unbindMutation = useMutation({
    mutationFn: ({ sourceId, key }: { sourceId: string; key: string }) =>
      unbindGitSource(spaceId, projectId, sourceId, access, key),
    onSuccess: (_data, variables) => {
      setConfirmUnbindSource(null);
      setSyncReceipts((prev) => {
        const next = { ...prev };
        delete next[variables.sourceId];
        return next;
      });
      setStatusNotice("已解绑远端仓库来源：加密凭据已撤销，未完成同步任务已取消。");
      invalidateProjectGitData();
    },
  });

  const rotateTokenMutation = useMutation({
    mutationFn: async ({
      source,
      newToken,
    }: {
      source: GitSource;
      newToken: string;
    }) => {
      // Bind replacement source with rotated encrypted token first so failure never drops the existing binding
      const replacement = await bindGitSource(
        spaceId,
        projectId,
        { httpsUrl: source.httpsUrl, accessToken: newToken },
        access,
        makeIdempotencyKey(),
      );
      try {
        await unbindGitSource(spaceId, projectId, source.sourceId, access, makeIdempotencyKey());
      } catch (unbindErr) {
        return { replacement, unbindWarning: remoteGitErrorText(unbindErr) };
      }
      return { replacement, unbindWarning: null };
    },
    onSuccess: ({ replacement, unbindWarning }) => {
      setRotatingSourceId(null);
      setRotateTokenInput("");
      setStatusNotice(
        unbindWarning
          ? `已创建新凭据来源（${replacement.sourceId.slice(0, 8)}），但解绑旧来源失败：${unbindWarning}`
          : `已完成远端 Token 轮换（旧凭据已撤销，界面仅展示掩码）。`,
      );
      invalidateProjectGitData();
    },
  });

  const compareMutation = useMutation({
    mutationFn: ({
      sourceId,
      localOid,
      remoteRef,
    }: {
      sourceId: string;
      localOid: string;
      remoteRef: string;
    }) => compareGitSource(spaceId, projectId, sourceId, { localOid, remoteRef }, access),
    onSuccess: (result) => {
      setCompareError(null);
      setCompareResult(result);
    },
    onError: (err) => {
      setCompareResult(null);
      setCompareError(remoteGitErrorText(err));
    },
  });

  const handleBindSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!canConfigure || bindMutation.isPending) return;
    setStatusNotice(null);
    const validationErr = validateControlledHttpsUrl(httpsUrl);
    if (validationErr) {
      setUrlError(validationErr);
      return;
    }
    setUrlError(null);
    bindMutation.mutate({
      url: httpsUrl.trim(),
      token: accessToken,
      key: bindIdemRef.current,
    });
  };

  const handleRunCompare = (event: FormEvent) => {
    event.preventDefault();
    if (!compareSourceId || !compareLocalOid.trim() || !compareRemoteRef.trim()) return;
    setCompareError(null);
    compareMutation.mutate({
      sourceId: compareSourceId,
      localOid: compareLocalOid.trim(),
      remoteRef: compareRemoteRef.trim(),
    });
  };

  return (
    <section className="remote-git-panel" aria-label="工程远端 Git 来源与同步观测">
      <header className="remote-git-header">
        <div className="remote-git-title">
          <h2>
            <Link2 size={18} /> 工程设置 · 受控远端 Git 来源（{projectName}）
          </h2>
          <p className="muted">
            仅支持受信任托管平台 HTTPS（443 端口）显式绑定；远端令牌经认证加密保存、绝不回显明文。
          </p>
        </div>
        <div className="remote-git-header-actions">
          <Button
            className="secondary workbench-btn-xs"
            onClick={() => {
              void sourcesQuery.refetch();
              void graphQuery.refetch();
            }}
          >
            <RefreshCw size={13} /> 刷新同步观测
          </Button>
          {onBackToCanvas && (
            <Button className="secondary workbench-btn-xs" onClick={onBackToCanvas}>
              <ArrowLeft size={13} /> 返回 Git 轨道画布
            </Button>
          )}
        </div>
      </header>

      <div className="remote-git-policy-note" role="note">
        <ShieldCheck size={16} />
        <span>
          出站安全策略：禁用 <code>file://</code>、<code>ext::</code>、URL 内嵌凭据、递归子模块与外部过滤器；受控出口代理自动拦截内网与保留网段，且同步结果仅作为元信息观测与校验来源。
        </span>
      </div>

      {statusNotice && (
        <div className="remote-git-receipt" role="status">
          <span>
            <CheckCircle2 size={15} /> {statusNotice}
          </span>
        </div>
      )}

      <div className="remote-git-card">
        <h3>
          <Link2 size={15} /> 绑定受信任 HTTPS 远端仓库
        </h3>
        {!canConfigure ? (
          <p className="muted">
            当前角色为只读（VIEWER），仅 OWNER / ADMIN / MEMBER 可绑定远端仓库或手动触发同步。
          </p>
        ) : (
          <form className="remote-git-bind-form" onSubmit={handleBindSubmit}>
            <div className="remote-git-form-grid">
              <Field
                label="受信任 HTTPS 仓库地址"
                name="remoteHttpsUrl"
                type="url"
                required
                placeholder="https://github.com/org/repo.git"
                value={httpsUrl}
                disabled={bindMutation.isPending}
                onChange={(e) => {
                  setHttpsUrl(e.target.value);
                  setUrlError(null);
                  bindIdemRef.current = makeIdempotencyKey();
                }}
              />
              <Field
                label="访问令牌 Access Token（可选，私有仓库必填，提交后仅显示掩码）"
                name="remoteAccessToken"
                type="password"
                autoComplete="new-password"
                maxLength={4096}
                placeholder="留空表示公开仓库；录入后加密存储且永不回显"
                value={accessToken}
                disabled={bindMutation.isPending}
                onChange={(e) => {
                  setAccessToken(e.target.value);
                  bindIdemRef.current = makeIdempotencyKey();
                }}
              />
            </div>

            {urlError && (
              <div className="alert" role="alert">
                <AlertCircle size={15} />
                <span>{urlError}</span>
              </div>
            )}
            {bindMutation.isError && (
              <div className="alert" role="alert">
                <AlertCircle size={15} />
                <span>{remoteGitErrorText(bindMutation.error)}</span>
              </div>
            )}

            <div className="actions">
              <Button
                type="submit"
                className="primary workbench-btn-xs"
                disabled={bindMutation.isPending || !httpsUrl.trim()}
              >
                {bindMutation.isPending ? "正在校验并绑定…" : "绑定受控远端仓库"}
              </Button>
            </div>
          </form>
        )}
      </div>

      <div className="remote-git-card">
        <h3>
          <GitBranch size={15} /> 已绑定的远端来源 ({sources.length})
        </h3>

        {sourcesQuery.isPending && <Loading label="正在加载远端来源列表…" />}
        {sourcesQuery.isError && (
          <div className="alert" role="alert">
            <AlertCircle size={15} />
            <span>{remoteGitErrorText(sourcesQuery.error)}</span>
          </div>
        )}
        {syncMutation.isError && (
          <div className="alert" role="alert">
            <AlertCircle size={15} />
            <span>{remoteGitErrorText(syncMutation.error)}</span>
          </div>
        )}
        {unbindMutation.isError && (
          <div className="alert" role="alert">
            <AlertCircle size={15} />
            <span>{remoteGitErrorText(unbindMutation.error)}</span>
          </div>
        )}
        {rotateTokenMutation.isError && (
          <div className="alert" role="alert">
            <AlertCircle size={15} />
            <span>{remoteGitErrorText(rotateTokenMutation.error)}</span>
          </div>
        )}

        {sourcesQuery.isSuccess && sources.length === 0 && (
          <p className="muted">当前工程尚未绑定任何受控远端仓库，可先通过本地 CLI 上报或在上方绑定 HTTPS 远端。</p>
        )}

        {sources.length > 0 && (
          <ul className="remote-git-source-list">
            {sources.map((source) => {
              const stats = statsBySource.get(source.sourceId);
              const receipt = syncReceipts[source.sourceId];
              const isRotating = rotatingSourceId === source.sourceId;
              const isConfirmingUnbind = confirmUnbindSource?.sourceId === source.sourceId;
              const conflictCount = stats?.conflictCommits.length ?? 0;

              return (
                <li key={source.sourceId} className="remote-git-source-item">
                  <div className="remote-git-source-top">
                    <div className="remote-git-source-url">
                      <strong>{source.httpsUrl}</strong>
                      <div className="remote-git-source-badges">
                        <code>#{source.sourceId.slice(0, 8)}</code>
                        <span
                          className={`remote-git-pill tone-${source.credentialConfigured ? "ok" : "muted"}`}
                        >
                          <KeyRound size={12} /> {maskedTokenDisplay(source.credentialConfigured)}
                        </span>
                        {conflictCount > 0 ? (
                          <span className="remote-git-pill tone-danger">
                            <AlertTriangle size={12} /> 发现 {conflictCount} 条观测冲突 (CONFLICT)
                          </span>
                        ) : (
                          <span className="remote-git-pill tone-ok">
                            <CheckCircle2 size={12} /> 无观测冲突
                          </span>
                        )}
                      </div>
                    </div>

                    {canConfigure && (
                      <div className="remote-git-source-actions">
                        <Button
                          className="primary workbench-btn-xs"
                          disabled={syncMutation.isPending}
                          onClick={() => {
                            setStatusNotice(null);
                            syncMutation.mutate({
                              sourceId: source.sourceId,
                              key: makeIdempotencyKey(),
                            });
                          }}
                        >
                          <RefreshCw size={12} />
                          {syncMutation.isPending && syncMutation.variables?.sourceId === source.sourceId
                            ? "触发中…"
                            : "手动触发受控同步"}
                        </Button>
                        <Button
                          className="secondary workbench-btn-xs"
                          disabled={rotateTokenMutation.isPending}
                          onClick={() => {
                            setConfirmUnbindSource(null);
                            setRotateTokenInput("");
                            setRotatingSourceId(isRotating ? null : source.sourceId);
                          }}
                        >
                          <KeyRound size={12} /> 轮换 Token
                        </Button>
                        <Button
                          className="secondary workbench-btn-xs"
                          disabled={unbindMutation.isPending}
                          onClick={() => {
                            setRotatingSourceId(null);
                            setConfirmUnbindSource(isConfirmingUnbind ? null : source);
                          }}
                        >
                          <Unlink size={12} /> 解绑
                        </Button>
                      </div>
                    )}
                  </div>

                  <div className="remote-git-obs-grid">
                    <div className="remote-git-obs-cell">
                      <span>
                        <Clock size={12} /> 最近远端同步观测时间
                      </span>
                      <strong>
                        {stats?.latestSyncedAt
                          ? formatDateTime(stats.latestSyncedAt)
                          : "尚未同步或暂无远端引用"}
                      </strong>
                    </div>
                    <div className="remote-git-obs-cell">
                      <span>远端引用数 / 已校验提交</span>
                      <strong>
                        {stats?.remoteRefs.length ?? 0} 个 Ref · {stats?.verifiedCommits ?? 0} 条 VERIFIED
                      </strong>
                    </div>
                    <div className="remote-git-obs-cell">
                      <span>来源冲突状态</span>
                      <strong>
                        {conflictCount > 0
                          ? `存在 ${conflictCount} 个 CONFLICT 提交`
                          : "与本地声明一致 / 暂无冲突"}
                      </strong>
                    </div>
                  </div>

                  {receipt && (
                    <div className="remote-git-receipt" role="status">
                      <span>
                        最近触发同步任务：<code>{receipt.jobId}</code>（提交于{" "}
                        {formatDateTime(receipt.queuedAt)}）
                      </span>
                      <Button
                        className="secondary workbench-btn-xs"
                        onClick={() => {
                          void graphQuery.refetch();
                        }}
                      >
                        刷新结果
                      </Button>
                    </div>
                  )}

                  {isRotating && canConfigure && (
                    <form
                      className="remote-git-inline-box"
                      onSubmit={(e) => {
                        e.preventDefault();
                        if (!rotateTokenInput.trim() || rotateTokenMutation.isPending) return;
                        rotateTokenMutation.mutate({
                          source,
                          newToken: rotateTokenInput.trim(),
                        });
                      }}
                    >
                      <Field
                        label="输入新访问令牌（轮换后将加密保存新 Token 并立即撤销旧凭据）"
                        type="password"
                        autoComplete="new-password"
                        required
                        maxLength={4096}
                        placeholder="粘贴新 Token（不会以明文回显）"
                        value={rotateTokenInput}
                        disabled={rotateTokenMutation.isPending}
                        onChange={(e) => setRotateTokenInput(e.target.value)}
                      />
                      <div className="actions">
                        <Button
                          className="secondary workbench-btn-xs"
                          disabled={rotateTokenMutation.isPending}
                          onClick={() => {
                            setRotatingSourceId(null);
                            setRotateTokenInput("");
                          }}
                        >
                          取消
                        </Button>
                        <Button
                          type="submit"
                          className="primary workbench-btn-xs"
                          disabled={rotateTokenMutation.isPending || !rotateTokenInput.trim()}
                        >
                          {rotateTokenMutation.isPending ? "轮换中…" : "确认轮换 Token"}
                        </Button>
                      </div>
                    </form>
                  )}

                  {isConfirmingUnbind && canConfigure && (
                    <div className="remote-git-inline-box is-danger" role="alertdialog" aria-label="确认解绑远端来源">
                      <p>
                        <strong>确认解绑远端仓库 {source.httpsUrl}？</strong>
                        解绑后将立即销毁加密凭据、取消进行中的同步任务，并拒绝任何迟到的异步同步结果。
                      </p>
                      <div className="actions">
                        <Button
                          className="secondary workbench-btn-xs"
                          disabled={unbindMutation.isPending}
                          onClick={() => setConfirmUnbindSource(null)}
                        >
                          取消
                        </Button>
                        <Button
                          className="danger workbench-btn-xs"
                          disabled={unbindMutation.isPending}
                          onClick={() =>
                            unbindMutation.mutate({
                              sourceId: source.sourceId,
                              key: makeIdempotencyKey(),
                            })
                          }
                        >
                          <Trash2 size={12} />{" "}
                          {unbindMutation.isPending ? "解绑中…" : "确认解绑并撤销凭据"}
                        </Button>
                      </div>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <div className="remote-git-card">
        <h3>
          <GitCompareArrows size={15} /> 远端领先 / 落后与可达性对比
        </h3>
        <p className="muted">
          基于已知远端引用与本地提交计算领先/落后步数并展示同步时间；遇到浅历史或存在冲突提交时如实显示“历史不完整”，绝不伪造精确步数。
        </p>

        {sources.length === 0 ? (
          <p className="muted">请先绑定并同步一个远端仓库后再进行领先/落后对比。</p>
        ) : (
          <form className="remote-git-bind-form" onSubmit={handleRunCompare}>
            <div className="remote-git-compare-grid">
              <label className="field">
                远端来源
                <select
                  value={compareSourceId}
                  onChange={(e) => {
                    setCompareSourceId(e.target.value);
                    setCompareRemoteRef("");
                    setCompareResult(null);
                  }}
                >
                  {sources.map((s) => (
                    <option key={s.sourceId} value={s.sourceId}>
                      {s.httpsUrl}
                    </option>
                  ))}
                </select>
              </label>

              <label className="field">
                远端引用（Remote Ref）
                {selectedSourceRefs.length > 0 ? (
                  <select
                    value={compareRemoteRef}
                    onChange={(e) => setCompareRemoteRef(e.target.value)}
                  >
                    {selectedSourceRefs.map((ref) => (
                      <option key={ref.ref.name} value={ref.ref.name}>
                        {ref.ref.name} ({shortOid(ref.ref.targetOid)})
                      </option>
                    ))}
                  </select>
                ) : (
                  <input
                    type="text"
                    placeholder="例如：refs/heads/main"
                    value={compareRemoteRef}
                    onChange={(e) => setCompareRemoteRef(e.target.value)}
                  />
                )}
              </label>

              <label className="field">
                本地提交 OID
                {localCommits.length > 0 ? (
                  <select
                    value={compareLocalOid}
                    onChange={(e) => setCompareLocalOid(e.target.value)}
                  >
                    {localCommits.map((c) => (
                      <option key={c.oid} value={c.oid}>
                        {shortOid(c.oid)} · {c.observations[0]?.metadata.subject ?? "Commit"}
                      </option>
                    ))}
                  </select>
                ) : (
                  <input
                    type="text"
                    placeholder="输入 40/64 位完整 Commit OID"
                    value={compareLocalOid}
                    onChange={(e) => setCompareLocalOid(e.target.value)}
                  />
                )}
              </label>
            </div>

            <div className="actions">
              <Button
                type="submit"
                className="secondary workbench-btn-xs"
                disabled={
                  compareMutation.isPending ||
                  !compareSourceId ||
                  !compareRemoteRef.trim() ||
                  !compareLocalOid.trim()
                }
              >
                <GitCompareArrows size={13} />
                {compareMutation.isPending ? "计算中…" : "查询领先 / 落后状态"}
              </Button>
            </div>
          </form>
        )}

        {compareError && (
          <div className="alert" role="alert">
            <AlertCircle size={15} />
            <span>{compareError}</span>
          </div>
        )}

        {compareResult && (
          <div className="remote-git-compare-result" role="status">
            <div className="remote-git-source-badges">
              <span
                className={`remote-git-pill tone-${
                  compareResult.historyStatus === "COMPLETE" ? "ok" : "warn"
                }`}
              >
                {compareResult.historyStatus === "COMPLETE"
                  ? "历史完整 (COMPLETE)"
                  : "历史不完整 (HISTORY_INCOMPLETE)"}
              </span>
              <span
                className={`remote-git-pill tone-${reachabilityLabel(compareResult.localReachability).tone}`}
              >
                {reachabilityLabel(compareResult.localReachability).label}
              </span>
            </div>

            <div className="remote-git-compare-metrics">
              <div className="remote-git-obs-cell">
                <span>领先远端 (Ahead)</span>
                <strong>
                  {compareResult.ahead === null ? "不完整（未估算）" : `+${compareResult.ahead} 次提交`}
                </strong>
              </div>
              <div className="remote-git-obs-cell">
                <span>落后远端 (Behind)</span>
                <strong>
                  {compareResult.behind === null ? "不完整（未估算）" : `-${compareResult.behind} 次提交`}
                </strong>
              </div>
              <div className="remote-git-obs-cell">
                <span>远端目标 OID</span>
                <strong>
                  <code>{shortOid(compareResult.remoteOid)}</code>
                </strong>
              </div>
              <div className="remote-git-obs-cell">
                <span>远端同步时间</span>
                <strong>{formatDateTime(compareResult.syncedAt)}</strong>
              </div>
            </div>
          </div>
        )}
      </div>

      {allConflictCommits.length > 0 && (
        <div className="remote-git-card">
          <h3>
            <AlertTriangle size={15} /> 元信息冲突观测记录 ({allConflictCommits.length})
          </h3>
          <p className="muted">
            以下提交在本地 CLI 声明与远端校验观测之间存在元信息不一致（如作者、父节点或标题冲突），系统已保留原始观测证据并标记为 CONFLICT，不用于未经说明的精确领先/落后计算。
          </p>
          <ul className="remote-git-conflict-list">
            {allConflictCommits.map((commit) => (
              <li key={commit.oid} className="remote-git-conflict-row">
                <div>
                  <strong>
                    <code>{commit.oid}</code>
                  </strong>{" "}
                  <span className="remote-git-pill tone-danger">CONFLICT</span>
                </div>
                <div>
                  观测来源数：{commit.observations.length}（
                  {commit.observations
                    .map((o) =>
                      o.source.deviceId.startsWith("remote:")
                        ? `远端 ${o.source.deviceId.slice(7, 15)}`
                        : `CLI 设备 ${o.source.deviceId.slice(0, 8)}`,
                    )
                    .join(" vs ")}
                  ）
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

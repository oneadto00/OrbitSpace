import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import type { SpaceDetail, SpaceSummary } from "@orbitspace/shared-types";
import {
  AlertCircle,
  ArrowLeft,
  Check,
  Copy,
  FolderGit2,
  FolderPlus,
  LogOut,
  Orbit,
  Plus,
  Settings,
  ShieldCheck,
  Terminal,
  X,
} from "lucide-react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import {
  bindSpaceConnection,
  queryClient,
  spaceKey,
  spaceSignal,
  spacesKey,
  type SpaceAccess,
} from "../shared/api/access";
import { logout } from "../shared/api/httpClient";
import { canManageSpace, canUploadOrManage, useAuth } from "../shared/auth/session";
import { Brand, Button, Field, Loading, ResourceStateCard } from "../shared/ui/UiComponents";
import { ChatPane, type ChatDraftQuote } from "../features/chat/ChatPane";
import { GitWorkspaceView } from "../features/git-canvas/GitWorkspaceView";
import { SpaceSettingsPanel } from "../features/space/SpaceSettingsPanel";
import {
  buildCliQuickstart,
  copyToClipboard,
  createGitProject,
  featureErrorText,
  getSpaceDetail,
  listMySpaces,
  makeIdempotencyKey,
  SPACE_ROLE_LABELS,
  upsertSpaceSummary,
} from "../features/space/spaceApi";

export function SpaceWorkbenchPage() {
  const { spaceId = "" } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const currentUserId = useAuth((s) => s.session?.userId ?? null);

  const queryProjectId = searchParams.get("projectId")?.trim() || null;
  const querySnapshotId = searchParams.get("snapshotId")?.trim() || null;
  const queryCommitOid = searchParams.get("commitOid")?.trim() || null;

  const spacesQuery = useQuery({
    queryKey: spacesKey,
    queryFn: ({ signal }) => listMySpaces(signal),
  });

  const currentSpace: SpaceSummary | undefined = spacesQuery.data?.find(
    (item) => item.spaceId === spaceId,
  );

  const access: SpaceAccess | null = currentSpace
    ? { spaceId: currentSpace.spaceId, membershipEpoch: currentSpace.membershipEpoch }
    : null;
  const accessIdentity = access ? `${access.spaceId}:${access.membershipEpoch}` : null;

  const [revokedAccessKey, setRevokedAccessKey] = useState<string | null>(null);
  const isRevoked =
    Boolean(accessIdentity && revokedAccessKey === accessIdentity) ||
    Boolean(access && spaceSignal(access).aborted);

  const [activeProjectId, setActiveProjectId] = useState<string | null>(queryProjectId);
  const [focusTarget, setFocusTarget] = useState<{
    kind: "COMMIT" | "WIP";
    id: string;
    highlightNonce: number;
  } | undefined>(undefined);
  const [draftQuote, setDraftQuote] = useState<ChatDraftQuote | null>(null);

  const [showCreateProject, setShowCreateProject] = useState(false);
  const [newProjectName, setNewProjectName] = useState("");
  const [showSettingsDrawer, setShowSettingsDrawer] = useState(false);
  const [copiedCli, setCopiedCli] = useState(false);

  const projectIdemKeyRef = useRef<string>(makeIdempotencyKey());
  const appliedDeepLinkRef = useRef<string | null>(null);
  const lastNonceRef = useRef(0);
  const settingsDialogRef = useRef<HTMLDialogElement>(null);

  const nextNonce = useCallback(() => {
    const next = Math.max(Date.now(), lastNonceRef.current + 1);
    lastNonceRef.current = next;
    return next;
  }, []);

  // Reset per-space / per-epoch UI state
  useEffect(() => {
    setDraftQuote(null);
    setShowCreateProject(false);
    setNewProjectName("");
    setShowSettingsDrawer(false);
    setFocusTarget(undefined);
    appliedDeepLinkRef.current = null;
  }, [spaceId, access?.membershipEpoch]);

  // Bind to instant space revocation
  useEffect(() => {
    if (!access || isRevoked) return;
    return bindSpaceConnection(access, () => {
      setRevokedAccessKey(`${access.spaceId}:${access.membershipEpoch}`);
      setShowSettingsDrawer(false);
      setDraftQuote(null);
      setFocusTarget(undefined);
    });
  }, [access?.spaceId, access?.membershipEpoch, isRevoked]);

  const spaceDetailQuery = useQuery({
    queryKey: access
      ? ([...spaceKey(access), "detail"] as const)
      : ["space-detail-none", spaceId],
    enabled: Boolean(access) && !isRevoked,
    queryFn: ({ signal }) => getSpaceDetail(spaceId, access!, signal),
  });

  const resolvedSpace = spaceDetailQuery.data?.space ?? currentSpace;
  const projects = spaceDetailQuery.data?.projects ?? [];

  // Keep spacesKey list synced if SpaceDetail returned fresher metadata without changing epoch
  useEffect(() => {
    const detailSpace = spaceDetailQuery.data?.space;
    if (!detailSpace || !currentSpace) return;
    if (
      detailSpace.name !== currentSpace.name ||
      detailSpace.role !== currentSpace.role ||
      detailSpace.membershipEpoch !== currentSpace.membershipEpoch ||
      detailSpace.recentlyVisitedAt !== currentSpace.recentlyVisitedAt
    ) {
      upsertSpaceSummary(detailSpace);
    }
  }, [currentSpace, spaceDetailQuery.data?.space]);

  // Apply deep-link (?projectId=...&snapshotId=...) and fallback activeProjectId
  useEffect(() => {
    if (!spaceDetailQuery.isSuccess) return;
    const deepLinkKey = `${spaceId}:${queryProjectId ?? ""}:${querySnapshotId ?? ""}:${queryCommitOid ?? ""}`;
    if (appliedDeepLinkRef.current !== deepLinkKey) {
      appliedDeepLinkRef.current = deepLinkKey;
      if (queryProjectId && projects.some((p) => p.projectId === queryProjectId)) {
        setActiveProjectId(queryProjectId);
      } else {
        setActiveProjectId((prev) =>
          prev && projects.some((p) => p.projectId === prev)
            ? prev
            : projects[0]?.projectId ?? null,
        );
      }

      if (querySnapshotId) {
        setFocusTarget({ kind: "WIP", id: querySnapshotId, highlightNonce: nextNonce() });
      } else if (queryCommitOid) {
        setFocusTarget({ kind: "COMMIT", id: queryCommitOid, highlightNonce: nextNonce() });
      }
      return;
    }

    if (projects.length > 0 && (!activeProjectId || !projects.some((p) => p.projectId === activeProjectId))) {
      setActiveProjectId(projects[0]?.projectId ?? null);
    } else if (projects.length === 0 && activeProjectId !== null) {
      setActiveProjectId(null);
    }
  }, [
    activeProjectId,
    nextNonce,
    projects,
    queryCommitOid,
    queryProjectId,
    querySnapshotId,
    spaceDetailQuery.isSuccess,
    spaceId,
  ]);

  // Manage settings modal dialog open/close
  useEffect(() => {
    const dialog = settingsDialogRef.current;
    if (!dialog) return;
    if (showSettingsDrawer) {
      if (!dialog.open) dialog.showModal();
    } else if (dialog.open) {
      dialog.close();
    }
  }, [showSettingsDrawer]);

  const exitMutation = useMutation({ mutationFn: logout });

  const createProjectMutation = useMutation({
    mutationFn: ({ name, key }: { name: string; key: string }) => {
      if (!access) throw new Error("Space access unavailable");
      return createGitProject(spaceId, name, access, key);
    },
    onSuccess: (created) => {
      projectIdemKeyRef.current = makeIdempotencyKey();
      setNewProjectName("");
      setShowCreateProject(false);
      if (access) {
        const detailKey = [...spaceKey(access), "detail"] as const;
        queryClient.setQueryData<SpaceDetail>(detailKey, (prev) => {
          if (!prev) return prev;
          const exists = prev.projects.some((p) => p.projectId === created.projectId);
          return exists
            ? prev
            : {
                ...prev,
                projects: [
                  ...prev.projects,
                  { projectId: created.projectId, name: created.name },
                ],
              };
        });
        void queryClient.invalidateQueries({ queryKey: detailKey });
      }
      setActiveProjectId(created.projectId);
    },
  });

  const handleCreateProject = (event: FormEvent) => {
    event.preventDefault();
    if (createProjectMutation.isPending) return;
    const trimmed = newProjectName.trim();
    if (!trimmed) return;
    createProjectMutation.mutate({ name: trimmed, key: projectIdemKeyRef.current });
  };

  const handleQuoteToChat = useCallback(
    (ref: {
      kind: "COMMIT_REF" | "WIP_REF";
      projectId: string;
      targetId: string;
      title: string;
    }) => {
      setDraftQuote({
        ...ref,
        nonce: nextNonce(),
      });
    },
    [nextNonce],
  );

  const handleOpenResourceCard = useCallback(
    (target: { kind: "COMMIT" | "WIP"; projectId: string; targetId: string }) => {
      setActiveProjectId(target.projectId);
      setFocusTarget({
        kind: target.kind,
        id: target.targetId,
        highlightNonce: nextNonce(),
      });
    },
    [nextNonce],
  );

  const activeProject = useMemo(
    () => projects.find((p) => p.projectId === activeProjectId) ?? null,
    [activeProjectId, projects],
  );

  if (!access) {
    if (spacesQuery.isPending) {
      return (
        <section className="workbench-guard-page">
          <Loading label="正在核对空间成员资格与 Epoch…" />
        </section>
      );
    }
    if (spacesQuery.isError) {
      return (
        <section className="workbench-guard-page">
          <div className="alert" role="alert">
            <AlertCircle size={17} />
            <span>{featureErrorText(spacesQuery.error)}</span>
            <Button
              className="secondary workbench-btn-xs"
              onClick={() => {
                void spacesQuery.refetch();
              }}
            >
              重试
            </Button>
          </div>
          <Link className="text-link" to="/">
            <ArrowLeft size={15} /> 返回我的空间
          </Link>
        </section>
      );
    }
    return (
      <section className="workbench-guard-page">
        <ResourceStateCard state="REVOKED_FOR_ACTOR" />
        <Link className="text-link" to="/">
          <ArrowLeft size={15} /> 返回我的空间
        </Link>
      </section>
    );
  }

  if (isRevoked) {
    return (
      <section className="workbench-guard-page">
        <ResourceStateCard state="REVOKED_FOR_ACTOR" />
        <Link className="text-link" to="/">
          <ArrowLeft size={15} /> 返回我的空间
        </Link>
      </section>
    );
  }

  if (!resolvedSpace) {
    return (
      <section className="workbench-guard-page">
        <Loading label="正在加载空间详情…" />
      </section>
    );
  }

  const canManage = canManageSpace(resolvedSpace.role);
  const canWrite = canUploadOrManage(resolvedSpace.role);
  const cliQuickstart = buildCliQuickstart(activeProject?.projectId ?? "<projectId>");

  return (
    <div className="space-workbench-shell">
      <header className="workbench-topbar">
        <div className="workbench-topbar-left">
          <Link className="workbench-brand-link" to="/" title="返回 OrbitSpace 工作台">
            <Brand />
          </Link>
          <Link className="workbench-back-btn" to="/">
            <ArrowLeft size={14} /> 我的空间
          </Link>
          <div className="workbench-space-heading">
            <h1>{resolvedSpace.name}</h1>
            <span className={`space-role-badge role-${resolvedSpace.role.toLowerCase()}`}>
              {SPACE_ROLE_LABELS[resolvedSpace.role]}
            </span>
            <span className="workbench-epoch-tag">Epoch #{resolvedSpace.membershipEpoch}</span>
          </div>
        </div>

        <div className="workbench-topbar-actions">
          {activeProject && (
            <span className="workbench-active-project-pill">
              <FolderGit2 size={14} /> {activeProject.name}
            </span>
          )}
          <Link className="workbench-nav-link" to="/device">
            <Terminal size={14} /> 授权 CLI 设备
          </Link>
          <Link className="workbench-nav-link" to="/account">
            <ShieldCheck size={14} /> 账号与安全
          </Link>
          <Button
            className="secondary workbench-btn-xs"
            onClick={() => setShowSettingsDrawer(true)}
          >
            <Settings size={14} /> 空间设置与成员
          </Button>
          <Button
            className="secondary workbench-btn-xs"
            disabled={exitMutation.isPending}
            onClick={() => exitMutation.mutate()}
          >
            <LogOut size={14} /> 退出登录
          </Button>
        </div>
      </header>

      {spaceDetailQuery.isError && (
        <div className="alert workbench-top-alert" role="alert">
          <AlertCircle size={16} />
          <span>{featureErrorText(spaceDetailQuery.error)}</span>
          <Button
            className="secondary workbench-btn-xs"
            onClick={() => {
              void spaceDetailQuery.refetch();
            }}
          >
            重试
          </Button>
        </div>
      )}

      <div className="workbench-3pane">
        {/* Left Pane: Space & Project Navigation */}
        <aside className="workbench-left-rail" aria-label="空间与工程导航">
          <div className="rail-section-head">
            <h2>空间与工程导航</h2>
            {canManage && (
              <Button
                className="secondary workbench-btn-xs"
                onClick={() => {
                  setShowCreateProject((prev) => !prev);
                  createProjectMutation.reset();
                  projectIdemKeyRef.current = makeIdempotencyKey();
                }}
              >
                <Plus size={13} /> 新建 Git 工程
              </Button>
            )}
          </div>

          {canManage && showCreateProject && (
            <form className="rail-create-project-form" onSubmit={handleCreateProject}>
              <Field
                label="工程名称"
                name="projectName"
                required
                maxLength={120}
                placeholder="例如：web-app"
                value={newProjectName}
                disabled={createProjectMutation.isPending}
                onChange={(e) => {
                  setNewProjectName(e.target.value);
                  projectIdemKeyRef.current = makeIdempotencyKey();
                }}
              />
              {createProjectMutation.isError && (
                <div className="alert" role="alert">
                  <AlertCircle size={15} />
                  <span>{featureErrorText(createProjectMutation.error)}</span>
                </div>
              )}
              <div className="actions">
                <Button
                  className="secondary workbench-btn-xs"
                  disabled={createProjectMutation.isPending}
                  onClick={() => setShowCreateProject(false)}
                >
                  取消
                </Button>
                <Button
                  type="submit"
                  className="primary workbench-btn-xs"
                  disabled={createProjectMutation.isPending || !newProjectName.trim()}
                >
                  {createProjectMutation.isPending ? "创建中…" : "确认创建"}
                </Button>
              </div>
            </form>
          )}

          {spaceDetailQuery.isPending && <Loading label="加载工程列表…" />}

          {spaceDetailQuery.isSuccess && projects.length === 0 && (
            <div className="rail-empty-box">
              <p className="muted">当前空间尚未创建 Git 工程。</p>
              {canManage ? (
                <Button
                  className="primary workbench-btn-xs"
                  onClick={() => setShowCreateProject(true)}
                >
                  <FolderPlus size={14} /> 新建 Git 工程
                </Button>
              ) : (
                <p className="muted">请联系空间 OWNER 或 ADMIN 创建工程。</p>
              )}
            </div>
          )}

          {projects.length > 0 && (
            <ul className="rail-project-list">
              {projects.map((project) => {
                const isActive = project.projectId === activeProjectId;
                return (
                  <li key={project.projectId}>
                    <button
                      type="button"
                      className={`rail-project-btn ${isActive ? "is-active" : ""}`}
                      aria-current={isActive ? "true" : undefined}
                      onClick={() => setActiveProjectId(project.projectId)}
                    >
                      <FolderGit2 size={15} />
                      <div className="rail-project-meta">
                        <strong>{project.name}</strong>
                        <code>{project.projectId}</code>
                      </div>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}

          <div className="rail-cli-box">
            <div className="rail-cli-head">
              <span>
                <Terminal size={13} /> CLI 快速接入
              </span>
              <Button
                className="secondary workbench-btn-xs"
                onClick={() => {
                  void copyToClipboard(cliQuickstart).then((ok) => {
                    if (ok) {
                      setCopiedCli(true);
                      window.setTimeout(() => setCopiedCli(false), 2000);
                    }
                  });
                }}
              >
                {copiedCli ? <Check size={12} /> : <Copy size={12} />}
                {copiedCli ? "已复制" : "复制"}
              </Button>
            </div>
            <pre className="cli-code-block">
              <code>{cliQuickstart}</code>
            </pre>
            <Link className="text-link rail-device-link" to="/device">
              前往授权 CLI 设备 →
            </Link>
          </div>
        </aside>

        {/* Center Pane: Git Topology Workspace */}
        <section className="workbench-center-pane" aria-label="工程代码画布">
          {activeProjectId ? (
            <GitWorkspaceView
              spaceId={spaceId}
              projectId={activeProjectId}
              canUploadOrManage={canWrite}
              focusTarget={focusTarget}
              onQuoteToChat={handleQuoteToChat}
            />
          ) : (
            <div className="workbench-center-empty">
              <span className="empty-mark">
                <Orbit size={32} />
              </span>
              <h3>选择或创建 Git 工程以查看代码拓扑</h3>
              <p className="muted">
                每个空间支持管理多个 Git 工程，右侧始终保留唯一的空间主群聊用于协作讨论。
              </p>
            </div>
          )}
        </section>

        {/* Right Pane: Single Space Main Chat */}
        <ChatPane
          spaceId={spaceId}
          spaceRole={resolvedSpace.role}
          access={access}
          isRevoked={isRevoked}
          currentUserId={currentUserId}
          projects={projects}
          activeProjectId={activeProjectId}
          draftQuote={draftQuote}
          onClearDraftQuote={() => setDraftQuote(null)}
          onOpenResourceCard={handleOpenResourceCard}
        />
      </div>

      {/* Collapsible Space Settings / Members / Invites Modal */}
      <dialog
        ref={settingsDialogRef}
        className="workbench-settings-dialog"
        aria-label="空间设置、邀请与成员管理"
        onCancel={(event) => {
          event.preventDefault();
          setShowSettingsDrawer(false);
        }}
      >
        <div className="workbench-settings-head">
          <h2>空间设置、邀请与成员管理</h2>
          <Button
            className="icon-button secondary"
            aria-label="关闭空间设置"
            onClick={() => setShowSettingsDrawer(false)}
          >
            <X size={16} />
          </Button>
        </div>
        {showSettingsDrawer && (
          <SpaceSettingsPanel
            space={resolvedSpace}
            access={access}
            currentUserId={currentUserId}
            onLeftSpace={() => {
              setShowSettingsDrawer(false);
              navigate("/", { replace: true });
            }}
            onSpaceUpdated={() => {
              void spacesQuery.refetch();
              void spaceDetailQuery.refetch();
            }}
          />
        )}
      </dialog>
    </div>
  );
}

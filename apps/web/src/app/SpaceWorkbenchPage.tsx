import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import type {
  CodeCommentDto,
  CodeCommentListResponse,
  GrantType,
  SpaceDetail,
  SpaceSummary,
} from "@orbitspace/shared-types";
import {
  AlertCircle,
  ArrowLeft,
  Check,
  CheckCircle2,
  Copy,
  FolderGit2,
  FolderOpen,
  FolderPlus,
  LogOut,
  Orbit,
  Plus,
  Send,
  Settings,
  ShieldCheck,
  Terminal,
  Users,
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
import { SpaceFilesPanel } from "../features/files/SpaceFilesPanel";
import {
  GitWorkspaceView,
  type CommentFocusTarget,
} from "../features/git-canvas/GitWorkspaceView";
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
  const queryTab = searchParams.get("tab")?.trim() || null;

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

  const [centerMode, setCenterMode] = useState<"git" | "files">(
    queryTab === "files" ? "files" : "git",
  );
  const [highlightedFileId, setHighlightedFileId] = useState<string | null>(null);
  const [activeProjectId, setActiveProjectId] = useState<string | null>(queryProjectId);
  const [focusTarget, setFocusTarget] = useState<{
    kind: "COMMIT" | "WIP";
    id: string;
    highlightNonce: number;
  } | undefined>(undefined);
  const [commentFocusTarget, setCommentFocusTarget] = useState<
    CommentFocusTarget | undefined
  >(undefined);
  const [draftQuote, setDraftQuote] = useState<ChatDraftQuote | null>(null);
  const [chatSyncNonce, setChatSyncNonce] = useState(0);
  const [forwardNotice, setForwardNotice] = useState<string | null>(null);

  const [showCreateProject, setShowCreateProject] = useState(false);
  const [newProjectName, setNewProjectName] = useState("");
  const [showSettingsDrawer, setShowSettingsDrawer] = useState(false);
  const [copiedCli, setCopiedCli] = useState(false);

  const projectIdemKeyRef = useRef<string>(makeIdempotencyKey());
  const appliedDeepLinkRef = useRef<string | null>(null);
  const lastNonceRef = useRef(0);
  const settingsDialogRef = useRef<HTMLDialogElement>(null);
  const forwardNoticeTimerRef = useRef<number | null>(null);

  const nextNonce = useCallback(() => {
    const next = Math.max(Date.now(), lastNonceRef.current + 1);
    lastNonceRef.current = next;
    return next;
  }, []);

  // Reset per-space / per-epoch UI state
  useEffect(() => {
    setCenterMode(queryTab === "files" ? "files" : "git");
    setHighlightedFileId(null);
    setDraftQuote(null);
    setShowCreateProject(false);
    setNewProjectName("");
    setShowSettingsDrawer(false);
    setFocusTarget(undefined);
    setCommentFocusTarget(undefined);
    setForwardNotice(null);
    appliedDeepLinkRef.current = null;
  }, [spaceId, access?.membershipEpoch, queryTab]);

  // Clear forward toast timer on unmount
  useEffect(() => {
    return () => {
      if (forwardNoticeTimerRef.current !== null) {
        window.clearTimeout(forwardNoticeTimerRef.current);
      }
    };
  }, []);

  // Bind to instant space revocation
  useEffect(() => {
    if (!access || isRevoked) return;
    return bindSpaceConnection(access, () => {
      setRevokedAccessKey(`${access.spaceId}:${access.membershipEpoch}`);
      setShowSettingsDrawer(false);
      setDraftQuote(null);
      setFocusTarget(undefined);
      setCommentFocusTarget(undefined);
      setHighlightedFileId(null);
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
      setCenterMode("git");
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

  const handleQuoteFileToChat = useCallback(
    (ref: { grantType: GrantType; targetId: string; title: string }) => {
      setDraftQuote({
        kind: "FILE_REF",
        grantType: ref.grantType,
        targetId: ref.targetId,
        title: ref.title,
        nonce: nextNonce(),
      });
    },
    [nextNonce],
  );

  const handleOpenResourceCard = useCallback(
    (target: { kind: "COMMIT" | "WIP"; projectId: string; targetId: string }) => {
      setCenterMode("git");
      setCommentFocusTarget(undefined);
      setActiveProjectId(target.projectId);
      setFocusTarget({
        kind: target.kind,
        id: target.targetId,
        highlightNonce: nextNonce(),
      });
    },
    [nextNonce],
  );

  const handleOpenCodeCommentCard = useCallback(
    (target: { projectId: string; commentId: string }) => {
      if (access) {
        const commentsKey = [
          ...spaceKey(access),
          "project",
          target.projectId,
          "code-comments",
        ] as const;
        const cached = queryClient.getQueryData<CodeCommentListResponse>(commentsKey);
        if (cached && !cached.items.some((item) => item.commentId === target.commentId)) {
          // Drop stale cache so GitWorkspaceView loads fresh comments before resolving target line
          queryClient.removeQueries({ queryKey: commentsKey });
        }
      }
      setCenterMode("git");
      setFocusTarget(undefined);
      setActiveProjectId(target.projectId);
      setCommentFocusTarget({
        commentId: target.commentId,
        highlightNonce: nextNonce(),
      });
    },
    [access, nextNonce],
  );

  const handleOpenFileCard = useCallback(
    (target: { grantType: GrantType; targetId: string }) => {
      if (target.grantType === "SPACE_FILE") {
        setCenterMode("files");
        setHighlightedFileId(target.targetId);
      } else {
        navigate(`/airdrops?shareId=${encodeURIComponent(target.targetId)}`);
      }
    },
    [navigate],
  );

  const handleForwardCodeComment = useCallback((comment: CodeCommentDto) => {
    setChatSyncNonce((n) => n + 1);
    setForwardNotice(
      `已将代码批注（${comment.filePath}:${comment.lineNumber}）转发至空间主群聊。`,
    );
    if (forwardNoticeTimerRef.current !== null) {
      window.clearTimeout(forwardNoticeTimerRef.current);
    }
    forwardNoticeTimerRef.current = window.setTimeout(() => {
      setForwardNotice(null);
      forwardNoticeTimerRef.current = null;
    }, 4500);
  }, []);

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
          {centerMode === "files" ? (
            <span className="workbench-active-project-pill">
              <FolderOpen size={14} /> 空间文件区
            </span>
          ) : activeProject ? (
            <span className="workbench-active-project-pill">
              <FolderGit2 size={14} /> {activeProject.name}
            </span>
          ) : null}
          <Link className="workbench-nav-link" to="/airdrops">
            <Send size={14} /> 文件空投
          </Link>
          <Link className="workbench-nav-link" to="/friends">
            <Users size={14} /> 好友
          </Link>
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

      {forwardNotice && (
        <div className="workbench-forward-toast" role="status">
          <span>
            <CheckCircle2 size={15} /> {forwardNotice}
          </span>
          <Button
            className="icon-button secondary"
            aria-label="关闭转发提示"
            onClick={() => setForwardNotice(null)}
          >
            <X size={13} />
          </Button>
        </div>
      )}

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
          <div className="rail-mode-switcher" role="tablist" aria-label="工作区视图切换">
            <button
              type="button"
              role="tab"
              aria-selected={centerMode === "git"}
              className={`rail-mode-btn ${centerMode === "git" ? "active" : ""}`}
              onClick={() => setCenterMode("git")}
            >
              <FolderGit2 size={14} /> Git 工程
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={centerMode === "files"}
              className={`rail-mode-btn ${centerMode === "files" ? "active" : ""}`}
              onClick={() => setCenterMode("files")}
            >
              <FolderOpen size={14} /> 空间文件区
            </button>
          </div>

          <div className="rail-section-head">
            <h2>Git 工程列表</h2>
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
                const isActive =
                  centerMode === "git" && project.projectId === activeProjectId;
                return (
                  <li key={project.projectId}>
                    <button
                      type="button"
                      className={`rail-project-btn ${isActive ? "is-active" : ""}`}
                      aria-current={isActive ? "true" : undefined}
                      onClick={() => {
                        setActiveProjectId(project.projectId);
                        setCenterMode("git");
                      }}
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

        {/* Center Pane: Git Topology Workspace or Space Files Area */}
        <section
          className="workbench-center-pane"
          aria-label={centerMode === "files" ? "空间文件区" : "工程代码画布"}
        >
          {centerMode === "files" ? (
            <SpaceFilesPanel
              spaceId={spaceId}
              spaceRole={resolvedSpace.role}
              access={access}
              currentUserId={currentUserId}
              highlightedFileId={highlightedFileId}
              onQuoteFileToChat={handleQuoteFileToChat}
            />
          ) : activeProjectId ? (
            <GitWorkspaceView
              spaceId={spaceId}
              projectId={activeProjectId}
              canUploadOrManage={canWrite}
              focusTarget={focusTarget}
              commentFocusTarget={commentFocusTarget}
              onQuoteToChat={handleQuoteToChat}
              onForwardCodeComment={handleForwardCodeComment}
            />
          ) : (
            <div className="workbench-center-empty">
              <span className="empty-mark">
                <Orbit size={32} />
              </span>
              <h3>选择或创建 Git 工程以查看代码拓扑</h3>
              <p className="muted">
                每个空间支持管理多个 Git 工程与空间文件区，右侧始终保留唯一的空间主群聊用于协作讨论。
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
          externalSyncNonce={chatSyncNonce}
          onClearDraftQuote={() => setDraftQuote(null)}
          onSelectFileQuote={handleQuoteFileToChat}
          onOpenResourceCard={handleOpenResourceCard}
          onOpenCodeCommentCard={handleOpenCodeCommentCard}
          onOpenFileCard={handleOpenFileCard}
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

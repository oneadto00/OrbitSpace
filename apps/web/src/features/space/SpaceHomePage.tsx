import { useRef, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import type { SpaceDetail } from "@orbitspace/shared-types";
import {
  AlertCircle,
  ArrowRight,
  Check,
  Clock,
  Copy,
  FolderGit2,
  MessageSquare,
  Orbit,
  Plus,
  RefreshCw,
  Send,
  Terminal,
  Users,
} from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import { queryClient, spacesKey } from "../../shared/api/access";
import { Button, Field, Loading, Modal } from "../../shared/ui/UiComponents";
import {
  buildCliQuickstart,
  copyToClipboard,
  createSpace,
  featureErrorText,
  formatDateTime,
  listMySpaces,
  makeIdempotencyKey,
  SPACE_ROLE_LABELS,
  upsertSpaceSummary,
  type CreateSpaceInput,
} from "./spaceApi";

type CreationMode = "general" | "project";

interface CreateSpaceModalProps {
  onClose: () => void;
}

function CreateSpaceModal({ onClose }: CreateSpaceModalProps) {
  const navigate = useNavigate();
  const [mode, setMode] = useState<CreationMode>("general");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [initialProjectName, setInitialProjectName] = useState("");
  const [createdProjectDetail, setCreatedProjectDetail] = useState<SpaceDetail | null>(null);
  const [copiedCli, setCopiedCli] = useState(false);

  const idempotencyKeyRef = useRef<string>(makeIdempotencyKey());

  const resetIdempotencyKey = () => {
    idempotencyKeyRef.current = makeIdempotencyKey();
  };

  const createMutation = useMutation({
    mutationFn: (payload: CreateSpaceInput) => createSpace(payload, idempotencyKeyRef.current),
    onSuccess: (detail, variables) => {
      upsertSpaceSummary(detail.space);
      void queryClient.invalidateQueries({ queryKey: spacesKey });
      if (variables.initialProjectName && detail.projects.length > 0) {
        setCreatedProjectDetail(detail);
      } else {
        onClose();
        navigate(`/spaces/${encodeURIComponent(detail.space.spaceId)}`);
      }
    },
  });

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (createMutation.isPending) return;
    const trimmedName = name.trim();
    const trimmedDesc = description.trim();
    const trimmedProject = initialProjectName.trim();
    if (!trimmedName) return;
    if (mode === "project" && !trimmedProject) return;

    const payload: CreateSpaceInput = {
      name: trimmedName,
      ...(trimmedDesc ? { description: trimmedDesc } : {}),
      ...(mode === "project" ? { initialProjectName: trimmedProject } : {}),
    };
    createMutation.mutate(payload);
  };

  if (createdProjectDetail) {
    const project = createdProjectDetail.projects[0];
    const quickstart = project ? buildCliQuickstart(project.projectId) : "";
    const targetUrl = project
      ? `/spaces/${encodeURIComponent(createdProjectDetail.space.spaceId)}?projectId=${encodeURIComponent(project.projectId)}`
      : `/spaces/${encodeURIComponent(createdProjectDetail.space.spaceId)}`;

    return (
      <Modal title="空间与 Git 工程已创建" onClose={onClose}>
        <div className="space-created-box">
          <p>
            空间 <strong>{createdProjectDetail.space.name}</strong> 与工程{" "}
            <strong>{project?.name}</strong>（<code>{project?.projectId}</code>）已就绪。
          </p>
          {project && (
            <div className="cli-quickstart-card">
              <div className="cli-quickstart-head">
                <span>
                  <Terminal size={14} /> CLI 快速接入命令
                </span>
                <Button
                  className="secondary workbench-btn-xs"
                  onClick={() => {
                    void copyToClipboard(quickstart).then((ok) => {
                      if (ok) {
                        setCopiedCli(true);
                        window.setTimeout(() => setCopiedCli(false), 2000);
                      }
                    });
                  }}
                >
                  {copiedCli ? <Check size={13} /> : <Copy size={13} />}
                  {copiedCli ? "已复制" : "复制命令"}
                </Button>
              </div>
              <pre className="cli-code-block">
                <code>{quickstart}</code>
              </pre>
            </div>
          )}
          <div className="actions">
            <Button className="secondary" onClick={onClose}>
              留在首页
            </Button>
            <Button
              className="primary"
              onClick={() => {
                onClose();
                navigate(targetUrl);
              }}
            >
              进入空间工作台 <ArrowRight size={15} />
            </Button>
          </div>
        </div>
      </Modal>
    );
  }

  return (
    <Modal title="新建空间" onClose={() => { if (!createMutation.isPending) onClose(); }}>
      <div className="space-create-tabs" role="tablist" aria-label="空间创建路径">
        <button
          type="button"
          role="tab"
          aria-selected={mode === "general"}
          className={`space-tab-btn ${mode === "general" ? "active" : ""}`}
          disabled={createMutation.isPending}
          onClick={() => {
            setMode("general");
            createMutation.reset();
            resetIdempotencyKey();
          }}
        >
          普通创建
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={mode === "project"}
          className={`space-tab-btn ${mode === "project" ? "active" : ""}`}
          disabled={createMutation.isPending}
          onClick={() => {
            setMode("project");
            createMutation.reset();
            resetIdempotencyKey();
          }}
        >
          从 Git 工程起步
        </button>
      </div>

      <form className="space-create-form" onSubmit={handleSubmit}>
        <Field
          label="空间名称"
          name="spaceName"
          required
          maxLength={120}
          placeholder="例如：Orbit 核心研发组"
          value={name}
          disabled={createMutation.isPending}
          onChange={(e) => {
            setName(e.target.value);
            resetIdempotencyKey();
          }}
        />
        <label className="field">
          空间说明（可选）
          <textarea
            name="spaceDescription"
            rows={2}
            maxLength={500}
            placeholder="简要介绍协作目标或代码仓说明"
            value={description}
            disabled={createMutation.isPending}
            onChange={(e) => {
              setDescription(e.target.value);
              resetIdempotencyKey();
            }}
          />
        </label>

        {mode === "project" && (
          <>
            <Field
              label="首个 Git 工程名称"
              name="initialProjectName"
              required
              maxLength={120}
              placeholder="例如：orbit-core"
              value={initialProjectName}
              disabled={createMutation.isPending}
              onChange={(e) => {
                setInitialProjectName(e.target.value);
                resetIdempotencyKey();
              }}
            />
            <div className="cli-quickstart-preview">
              <p className="muted">创建后将生成绑定工程 ID 的 CLI 接入命令：</p>
              <pre className="cli-code-block">
                <code>{buildCliQuickstart("<projectId>")}</code>
              </pre>
            </div>
          </>
        )}

        {createMutation.isError && (
          <div className="alert" role="alert">
            <AlertCircle size={17} />
            <span>{featureErrorText(createMutation.error)}</span>
          </div>
        )}

        <div className="actions">
          <Button className="secondary" disabled={createMutation.isPending} onClick={onClose}>
            取消
          </Button>
          <Button
            type="submit"
            className="primary"
            disabled={
              createMutation.isPending ||
              !name.trim() ||
              (mode === "project" && !initialProjectName.trim())
            }
          >
            {createMutation.isPending
              ? "正在创建…"
              : mode === "project"
                ? "创建空间与工程"
                : "创建空间"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

export function SpaceHomePage() {
  const [showCreateModal, setShowCreateModal] = useState(false);

  const spacesQuery = useQuery({
    queryKey: spacesKey,
    queryFn: ({ signal }) => listMySpaces(signal),
  });

  const spaces = spacesQuery.data ?? [];

  return (
    <section className="content">
      <header className="page-head space-home-head">
        <div>
          <h1>工作台</h1>
          <p className="subtitle">我的空间、最近访问和未读讨论</p>
        </div>
        <div className="actions">
          <Button
            className="secondary"
            disabled={spacesQuery.isFetching}
            onClick={() => {
              void spacesQuery.refetch();
            }}
          >
            <RefreshCw size={15} /> 刷新
          </Button>
          <Button className="primary" onClick={() => setShowCreateModal(true)}>
            <Plus size={16} /> 新建空间
          </Button>
        </div>
      </header>

      <div className="workbench">
        <section className="surface work-area" aria-label="我的空间列表">
          <div className="section-head">
            <h2>我的空间</h2>
            <span className="muted">{spacesQuery.isSuccess ? `共 ${spaces.length} 个空间` : ""}</span>
          </div>

          {spacesQuery.isPending && <Loading label="正在加载空间列表与未读讨论…" />}

          {spacesQuery.isError && (
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
          )}

          {spacesQuery.isSuccess && spaces.length === 0 && (
            <div className="empty">
              <span className="empty-mark">
                <Orbit size={34} />
              </span>
              <h3>还没有加入任何空间</h3>
              <p>新建一个协作空间，或通过团队分享的邀请链接加入现有空间。</p>
              <Button className="primary" onClick={() => setShowCreateModal(true)}>
                <Plus size={16} /> 新建空间
              </Button>
            </div>
          )}

          {spacesQuery.isSuccess && spaces.length > 0 && (
            <ul className="space-list">
              {spaces.map((space) => (
                <li key={`${space.spaceId}:${space.membershipEpoch}`} className="space-card">
                  <div className="space-card-main">
                    <div className="space-card-title-row">
                      <Link
                        className="space-card-title"
                        to={`/spaces/${encodeURIComponent(space.spaceId)}`}
                      >
                        <FolderGit2 size={18} />
                        <span>{space.name}</span>
                      </Link>
                      <span className={`space-role-badge role-${space.role.toLowerCase()}`}>
                        {SPACE_ROLE_LABELS[space.role]}
                      </span>
                    </div>
                    <div className="space-card-meta">
                      <span className="space-meta-item">
                        <Clock size={14} />
                        {space.recentlyVisitedAt
                          ? `最近访问：${formatDateTime(space.recentlyVisitedAt)}`
                          : "最近访问：尚未访问"}
                      </span>
                      <span
                        className={`space-unread-pill ${
                          space.unreadDiscussionCount > 0 ? "has-unread" : ""
                        }`}
                      >
                        <MessageSquare size={14} />
                        {space.unreadDiscussionCount > 0
                          ? `${space.unreadDiscussionCount} 条未读讨论`
                          : "0 条未读讨论"}
                      </span>
                    </div>
                  </div>
                  <Link
                    className="button secondary space-open-btn"
                    to={`/spaces/${encodeURIComponent(space.spaceId)}`}
                  >
                    进入工作台 <ArrowRight size={15} />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>

        <aside className="side-panels">
          <section className="surface">
            <div className="section-head">
              <h2>文件空投</h2>
              <Send size={16} />
            </div>
            <p className="muted">
              面向当前有效好友投递单/多文件，支持分片断点续传、下载次数会话计数与随时撤回。
            </p>
            <Link className="text-link" to="/airdrops">
              进入文件空投 <ArrowRight size={15} />
            </Link>
          </section>
          <section className="surface">
            <div className="section-head">
              <h2>好友</h2>
              <Users size={16} />
            </div>
            <p className="muted">
              搜索协作者、处理好友申请并管理独立空投联系人（好友关系绝不授予空间访问权限）。
            </p>
            <Link className="text-link" to="/friends">
              管理好友关系 <ArrowRight size={15} />
            </Link>
          </section>
          <section className="surface">
            <div className="section-head">
              <h2>最近访问与未读概览</h2>
              <MessageSquare size={16} />
            </div>
            <p className="muted">
              每个空间拥有唯一的主群聊与空间文件区。进入空间后即可切换工程 Git 拓扑图、空间文件与协同讨论。
            </p>
          </section>
          <section className="surface">
            <h2>设备访问</h2>
            <p className="muted">查看登录会话和已授权的终端 CLI 设备凭证。</p>
            <div className="actions">
              <Link className="text-link" to="/device">
                授权 CLI 设备 <ArrowRight size={15} />
              </Link>
              <Link className="text-link" to="/account">
                账号与安全 <ArrowRight size={15} />
              </Link>
            </div>
          </section>
        </aside>
      </div>

      {showCreateModal && <CreateSpaceModal onClose={() => setShowCreateModal(false)} />}
    </section>
  );
}

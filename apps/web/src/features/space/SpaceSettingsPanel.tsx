import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import type {
  AiSourceKind,
  CreateInvite,
  Invite,
  InviteRole,
  Member,
  SpaceAiPolicy,
  SpaceSummary,
} from "@orbitspace/shared-types";
import {
  AlertCircle,
  AlertTriangle,
  Check,
  CheckCircle2,
  Copy,
  Crown,
  Link2,
  LogOut,
  Shield,
  Sparkles,
  Trash2,
  UserMinus,
  Users,
} from "lucide-react";
import { queryClient, revokeSpace, spaceKey, spacesKey, type SpaceAccess } from "../../shared/api/access";
import { canManageSpace } from "../../shared/auth/session";
import { Button, Loading } from "../../shared/ui/UiComponents";
import {
  AI_SOURCE_KIND_META,
  ALL_AI_SOURCE_KINDS,
  changeSpaceMemberRole,
  copyToClipboard,
  createSpaceInvite,
  featureErrorText,
  formatDateTime,
  getSpaceAiConfig,
  leaveSpace,
  listSpaceInvites,
  listSpaceMembers,
  makeIdempotencyKey,
  removeSpaceMember,
  revokeSpaceInvite,
  SPACE_ROLE_LABELS,
  transferSpaceOwnership,
  updateSpaceAiConfig,
  type CreateInviteResult,
} from "./spaceApi";

export interface SpaceSettingsPanelProps {
  space: SpaceSummary;
  access: SpaceAccess;
  currentUserId: string | null;
  onLeftSpace: () => void;
  onSpaceUpdated: () => void;
}

type ExpiryPreset = "24h" | "7d" | "30d" | "custom";

const MAX_TIMESTAMP_MS = Date.parse("2038-01-19T03:14:07Z");

function toLocalInputValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function inviteStatusLabel(invite: Invite): { label: string; tone: string } {
  if (invite.revoked) return { label: "已撤销", tone: "muted" };
  if (Date.parse(invite.expiresAt) <= Date.now()) return { label: "已过期", tone: "warn" };
  if (invite.usedCount >= invite.maxUses) return { label: "已达上限", tone: "warn" };
  return { label: "有效", tone: "ok" };
}

export function SpaceSettingsPanel({
  space,
  access,
  currentUserId,
  onLeftSpace,
  onSpaceUpdated,
}: SpaceSettingsPanelProps) {
  const isOwner = space.role === "OWNER";
  const isAdminOrOwner = canManageSpace(space.role);

  const [targetRole, setTargetRole] = useState<InviteRole>("MEMBER");
  const [expiryPreset, setExpiryPreset] = useState<ExpiryPreset>("7d");
  const [customExpiresLocal, setCustomExpiresLocal] = useState(() =>
    toLocalInputValue(new Date(Date.now() + 7 * 24 * 3600 * 1000)),
  );
  const [maxUses, setMaxUses] = useState(10);
  const [expiryValidationError, setExpiryValidationError] = useState<string | null>(null);
  const [createdInvite, setCreatedInvite] = useState<CreateInviteResult | null>(null);
  const [copiedInviteUrl, setCopiedInviteUrl] = useState(false);

  const [pendingRemoval, setPendingRemoval] = useState<Member | null>(null);
  const [pendingTransfer, setPendingTransfer] = useState<Member | null>(null);
  const [confirmLeave, setConfirmLeave] = useState(false);

  const [aiEnabledDraft, setAiEnabledDraft] = useState(false);
  const [aiKindsDraft, setAiKindsDraft] = useState<AiSourceKind[]>([]);
  const [aiDraftDirty, setAiDraftDirty] = useState(false);
  const [aiSavedBanner, setAiSavedBanner] = useState<string | null>(null);

  const transferIdemRef = useRef<{ userId: string; key: string } | null>(null);
  const aiConfigIdemRef = useRef<string>(makeIdempotencyKey());

  const membersQueryKey = useMemo(
    () => [...spaceKey(access), "members"] as const,
    [access],
  );
  const invitesQueryKey = useMemo(
    () => [...spaceKey(access), "invites"] as const,
    [access],
  );
  const aiConfigQueryKey = useMemo(
    () => [...spaceKey(access), "ai-config"] as const,
    [access],
  );

  const membersQuery = useQuery({
    queryKey: membersQueryKey,
    queryFn: ({ signal }) => listSpaceMembers(space.spaceId, access, signal),
  });

  const invitesQuery = useQuery({
    queryKey: invitesQueryKey,
    enabled: isAdminOrOwner,
    queryFn: ({ signal }) => listSpaceInvites(space.spaceId, access, signal),
  });

  const aiConfigQuery = useQuery({
    queryKey: aiConfigQueryKey,
    queryFn: ({ signal }) => getSpaceAiConfig(space.spaceId, access, signal),
  });

  useEffect(() => {
    if (!aiConfigQuery.data || aiDraftDirty) return;
    setAiEnabledDraft(aiConfigQuery.data.enabled);
    setAiKindsDraft(aiConfigQuery.data.allowedKinds);
  }, [aiConfigQuery.data, aiDraftDirty]);

  const saveAiConfigMutation = useMutation({
    mutationFn: ({ payload, key }: { payload: SpaceAiPolicy; key: string }) =>
      updateSpaceAiConfig(space.spaceId, payload, access, key),
    onSuccess: (updated) => {
      aiConfigIdemRef.current = makeIdempotencyKey();
      setAiEnabledDraft(updated.enabled);
      setAiKindsDraft(updated.allowedKinds);
      setAiDraftDirty(false);
      setAiSavedBanner(
        updated.enabled
          ? `已保存：AI 辅助摘要已启用（已允许 ${updated.allowedKinds.length} 类数据源）。`
          : "已保存：空间 AI 辅助摘要已关闭。",
      );
      queryClient.setQueryData<SpaceAiPolicy>(aiConfigQueryKey, updated);
      void queryClient.invalidateQueries({ queryKey: aiConfigQueryKey });
      onSpaceUpdated();
    },
  });

  const toggleAiKind = (kind: AiSourceKind) => {
    setAiSavedBanner(null);
    setAiDraftDirty(true);
    aiConfigIdemRef.current = makeIdempotencyKey();
    setAiKindsDraft((prev) =>
      prev.includes(kind) ? prev.filter((item) => item !== kind) : [...prev, kind],
    );
  };

  const handleSaveAiConfig = (event: FormEvent) => {
    event.preventDefault();
    if (!isAdminOrOwner || saveAiConfigMutation.isPending) return;
    setAiSavedBanner(null);
    saveAiConfigMutation.mutate({
      payload: {
        enabled: aiEnabledDraft,
        allowedKinds: aiKindsDraft,
      },
      key: aiConfigIdemRef.current,
    });
  };

  const refreshMembershipData = () => {
    void queryClient.invalidateQueries({ queryKey: membersQueryKey });
    void queryClient.invalidateQueries({ queryKey: [...spaceKey(access), "detail"] });
    void queryClient.invalidateQueries({ queryKey: spacesKey });
    onSpaceUpdated();
  };

  const createInviteMutation = useMutation({
    mutationFn: ({ payload, key }: { payload: CreateInvite; key: string }) =>
      createSpaceInvite(space.spaceId, access, payload, key),
    onSuccess: (result) => {
      setCreatedInvite(result);
      setCopiedInviteUrl(false);
      void queryClient.invalidateQueries({ queryKey: invitesQueryKey });
    },
  });

  const revokeInviteMutation = useMutation({
    mutationFn: (inviteId: string) => revokeSpaceInvite(space.spaceId, inviteId, access),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: invitesQueryKey });
    },
  });

  const changeRoleMutation = useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: InviteRole }) =>
      changeSpaceMemberRole(space.spaceId, userId, role, access),
    onSuccess: () => {
      refreshMembershipData();
    },
  });

  const removeMemberMutation = useMutation({
    mutationFn: (userId: string) => removeSpaceMember(space.spaceId, userId, access),
    onSuccess: () => {
      setPendingRemoval(null);
      refreshMembershipData();
    },
  });

  const transferOwnerMutation = useMutation({
    mutationFn: ({ newOwnerUserId, key }: { newOwnerUserId: string; key: string }) =>
      transferSpaceOwnership(space.spaceId, newOwnerUserId, access, key),
    onSuccess: () => {
      setPendingTransfer(null);
      transferIdemRef.current = null;
      refreshMembershipData();
    },
  });

  const leaveSpaceMutation = useMutation({
    mutationFn: () => leaveSpace(space.spaceId, access),
    onSuccess: () => {
      revokeSpace(access);
      onLeftSpace();
    },
  });

  const allowedInviteRoles: InviteRole[] = isOwner
    ? ["ADMIN", "MEMBER", "VIEWER"]
    : ["MEMBER", "VIEWER"];

  const effectiveTargetRole: InviteRole = allowedInviteRoles.includes(targetRole)
    ? targetRole
    : "MEMBER";

  const handleCreateInvite = (event: FormEvent) => {
    event.preventDefault();
    if (createInviteMutation.isPending) return;
    setExpiryValidationError(null);

    let targetMs: number;
    if (expiryPreset === "24h") targetMs = Date.now() + 24 * 3600 * 1000;
    else if (expiryPreset === "7d") targetMs = Date.now() + 7 * 24 * 3600 * 1000;
    else if (expiryPreset === "30d") targetMs = Date.now() + 30 * 24 * 3600 * 1000;
    else targetMs = Date.parse(customExpiresLocal);

    if (!Number.isFinite(targetMs) || targetMs <= Date.now() + 60_000) {
      setExpiryValidationError("过期时间必须晚于当前时间至少 1 分钟。");
      return;
    }
    if (targetMs > MAX_TIMESTAMP_MS) {
      setExpiryValidationError("过期时间超出系统支持上限（2038-01-19）。");
      return;
    }
    const clampedUses = Math.max(1, Math.floor(maxUses));

    createInviteMutation.mutate({
      payload: {
        targetRole: effectiveTargetRole,
        expiresAt: new Date(targetMs).toISOString(),
        maxUses: clampedUses,
      },
      key: makeIdempotencyKey(),
    });
  };

  const canModifyMemberRole = (member: Member): boolean => {
    if (member.userId === currentUserId) return false;
    if (member.role === "OWNER") return false;
    if (isOwner) return true;
    if (space.role === "ADMIN") {
      return member.role === "MEMBER" || member.role === "VIEWER";
    }
    return false;
  };

  return (
    <div className="space-settings-panel">
      <div className="settings-space-banner">
        <div>
          <h3>{space.name}</h3>
          <p className="muted">
            你的角色：{SPACE_ROLE_LABELS[space.role]} · 成员 Epoch #{space.membershipEpoch}
          </p>
        </div>
      </div>

      <section className="settings-section" aria-label="AI 辅助摘要策略">
        <div className="section-head">
          <h3>
            <Sparkles size={16} /> AI 辅助摘要策略
          </h3>
          {aiConfigQuery.data && (
            <span
              className={`settings-status-pill tone-${aiConfigQuery.data.enabled ? "ok" : "muted"}`}
            >
              {aiConfigQuery.data.enabled ? "已启用" : "默认关闭"}
            </span>
          )}
        </div>

        <p className="muted">
          AI 摘要默认关闭。每次调用仅读取发起人当前有权访问的不可变版本，输出附带引用来源，并在写入协作文档前由用户预览确认。
        </p>

        {aiConfigQuery.isPending && <Loading label="正在读取空间 AI 策略…" />}
        {aiConfigQuery.isError && (
          <div className="alert" role="alert">
            <AlertCircle size={16} />
            <span>{featureErrorText(aiConfigQuery.error)}</span>
          </div>
        )}

        {aiConfigQuery.isSuccess && isAdminOrOwner && (
          <form className="ai-policy-form" onSubmit={handleSaveAiConfig}>
            <label className="ai-policy-switch">
              <input
                type="checkbox"
                checked={aiEnabledDraft}
                disabled={saveAiConfigMutation.isPending}
                onChange={(e) => {
                  setAiEnabledDraft(e.target.checked);
                  setAiDraftDirty(true);
                  setAiSavedBanner(null);
                  aiConfigIdemRef.current = makeIdempotencyKey();
                }}
              />
              <div>
                <strong>启用本空间 AI 辅助摘要</strong>
                <p className="muted">
                  仅 OWNER / ADMIN 可调整此开关及允许发送给 AI 的资源类型范围。
                </p>
              </div>
            </label>

            <fieldset
              className="ai-policy-kinds"
              disabled={saveAiConfigMutation.isPending}
            >
              <legend>允许发送给 AI 的数据范围（按类型控制）</legend>
              <div className="ai-policy-kinds-grid">
                {ALL_AI_SOURCE_KINDS.map((kind) => {
                  const checked = aiKindsDraft.includes(kind);
                  const meta = AI_SOURCE_KIND_META[kind];
                  return (
                    <label
                      key={kind}
                      className={`ai-kind-option ${checked ? "is-checked" : ""}`}
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggleAiKind(kind)}
                      />
                      <div>
                        <strong>{meta.label}</strong>
                        <p className="muted">{meta.description}</p>
                      </div>
                    </label>
                  );
                })}
              </div>
            </fieldset>

            {aiEnabledDraft && aiKindsDraft.length === 0 && (
              <div className="drawer-callout drawer-callout-warn" role="status">
                <AlertTriangle size={15} />
                <span>当前未勾选任何允许发送的数据类型，成员将无法选择资料发起 AI 摘要。</span>
              </div>
            )}

            {saveAiConfigMutation.isError && (
              <div className="alert" role="alert">
                <AlertCircle size={16} />
                <span>{featureErrorText(saveAiConfigMutation.error)}</span>
              </div>
            )}

            {aiSavedBanner && (
              <div className="ai-policy-saved" role="status">
                <CheckCircle2 size={15} />
                <span>{aiSavedBanner}</span>
              </div>
            )}

            <div className="actions">
              <Button
                type="submit"
                className="primary workbench-btn-xs"
                disabled={saveAiConfigMutation.isPending || !aiDraftDirty}
              >
                {saveAiConfigMutation.isPending ? "保存中…" : "保存 AI 策略配置"}
              </Button>
              {aiDraftDirty && (
                <Button
                  className="secondary workbench-btn-xs"
                  disabled={saveAiConfigMutation.isPending}
                  onClick={() => {
                    setAiEnabledDraft(aiConfigQuery.data.enabled);
                    setAiKindsDraft(aiConfigQuery.data.allowedKinds);
                    setAiDraftDirty(false);
                    setAiSavedBanner(null);
                  }}
                >
                  还原更改
                </Button>
              )}
            </div>
          </form>
        )}

        {aiConfigQuery.isSuccess && !isAdminOrOwner && (
          <div className="ai-policy-readonly">
            <p className="muted">
              当前状态：{aiConfigQuery.data.enabled ? "已启用" : "未启用"} · 允许范围：
              {aiConfigQuery.data.allowedKinds.length > 0
                ? aiConfigQuery.data.allowedKinds
                    .map((k) => AI_SOURCE_KIND_META[k].label)
                    .join("、")
                : "无（管理员未开放任何数据类型）"}
            </p>
            <p className="muted">仅空间 OWNER 或 ADMIN 可修改 AI 辅助策略。</p>
          </div>
        )}
      </section>

      {isAdminOrOwner && (
        <section className="settings-section" aria-label="邀请管理">
          <div className="section-head">
            <h3>
              <Link2 size={16} /> 邀请链接管理
            </h3>
          </div>

          <form className="invite-create-form" onSubmit={handleCreateInvite}>
            <div className="invite-form-grid">
              <label className="field">
                授予角色
                <select
                  value={effectiveTargetRole}
                  disabled={createInviteMutation.isPending}
                  onChange={(e) => setTargetRole(e.target.value as InviteRole)}
                >
                  {allowedInviteRoles.map((role) => (
                    <option key={role} value={role}>
                      {SPACE_ROLE_LABELS[role]}
                    </option>
                  ))}
                </select>
              </label>

              <label className="field">
                有效期
                <select
                  value={expiryPreset}
                  disabled={createInviteMutation.isPending}
                  onChange={(e) => {
                    setExpiryPreset(e.target.value as ExpiryPreset);
                    setExpiryValidationError(null);
                  }}
                >
                  <option value="24h">24 小时内有效</option>
                  <option value="7d">7 天内有效</option>
                  <option value="30d">30 天内有效</option>
                  <option value="custom">自定义时间</option>
                </select>
              </label>

              <label className="field">
                最大使用次数
                <input
                  type="number"
                  min={1}
                  max={10000}
                  required
                  value={maxUses}
                  disabled={createInviteMutation.isPending}
                  onChange={(e) => setMaxUses(Number(e.target.value) || 1)}
                />
              </label>
            </div>

            {expiryPreset === "custom" && (
              <label className="field">
                自定义过期时间
                <input
                  type="datetime-local"
                  required
                  value={customExpiresLocal}
                  disabled={createInviteMutation.isPending}
                  onChange={(e) => {
                    setCustomExpiresLocal(e.target.value);
                    setExpiryValidationError(null);
                  }}
                />
              </label>
            )}

            {expiryValidationError && (
              <div className="alert" role="alert">
                <AlertCircle size={16} />
                <span>{expiryValidationError}</span>
              </div>
            )}

            {createInviteMutation.isError && (
              <div className="alert" role="alert">
                <AlertCircle size={16} />
                <span>{featureErrorText(createInviteMutation.error)}</span>
              </div>
            )}

            <Button
              type="submit"
              className="primary workbench-btn-xs"
              disabled={createInviteMutation.isPending}
            >
              {createInviteMutation.isPending ? "正在生成…" : "生成邀请链接"}
            </Button>
          </form>

          {createdInvite && (
            <div className="invite-result-box" role="status">
              <div className="invite-result-head">
                <strong>新邀请链接（仅此一次展示完整 Token）</strong>
                <Button
                  className="secondary workbench-btn-xs"
                  onClick={() => {
                    void copyToClipboard(createdInvite.joinUrl).then((ok) => {
                      if (ok) {
                        setCopiedInviteUrl(true);
                        window.setTimeout(() => setCopiedInviteUrl(false), 2000);
                      }
                    });
                  }}
                >
                  {copiedInviteUrl ? <Check size={13} /> : <Copy size={13} />}
                  {copiedInviteUrl ? "已复制" : "复制链接"}
                </Button>
              </div>
              <input
                type="text"
                readOnly
                value={createdInvite.joinUrl}
                aria-label="邀请链接"
                className="invite-url-input"
                onFocus={(e) => e.currentTarget.select()}
              />
            </div>
          )}

          {invitesQuery.isPending && <Loading label="正在加载邀请列表…" />}
          {invitesQuery.isError && (
            <div className="alert" role="alert">
              <AlertCircle size={16} />
              <span>{featureErrorText(invitesQuery.error)}</span>
            </div>
          )}
          {revokeInviteMutation.isError && (
            <div className="alert" role="alert">
              <AlertCircle size={16} />
              <span>{featureErrorText(revokeInviteMutation.error)}</span>
            </div>
          )}

          {invitesQuery.isSuccess && invitesQuery.data.length === 0 && (
            <p className="muted">当前暂无邀请记录。</p>
          )}
          {invitesQuery.isSuccess && invitesQuery.data.length > 0 && (
            <ul className="settings-list">
              {invitesQuery.data.map((invite) => {
                const status = inviteStatusLabel(invite);
                return (
                  <li key={invite.inviteId} className="settings-list-row">
                    <div className="settings-row-info">
                      <div className="settings-row-title">
                        <span className={`space-role-badge role-${invite.targetRole.toLowerCase()}`}>
                          {SPACE_ROLE_LABELS[invite.targetRole]}
                        </span>
                        <span className={`settings-status-pill tone-${status.tone}`}>
                          {status.label}
                        </span>
                        <code>#{invite.inviteId.slice(0, 8)}</code>
                      </div>
                      <p className="muted">
                        已使用 {invite.usedCount} / {invite.maxUses} 次 · 有效期至{" "}
                        {formatDateTime(invite.expiresAt)}
                      </p>
                    </div>
                    {!invite.revoked && (
                      <Button
                        className="secondary workbench-btn-xs"
                        disabled={revokeInviteMutation.isPending}
                        onClick={() => revokeInviteMutation.mutate(invite.inviteId)}
                      >
                        <Trash2 size={13} /> 撤销
                      </Button>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      )}

      <section className="settings-section" aria-label="成员管理">
        <div className="section-head">
          <h3>
            <Users size={16} /> 空间成员 ({membersQuery.data?.length ?? 0})
          </h3>
        </div>

        {membersQuery.isPending && <Loading label="正在加载成员列表…" />}
        {membersQuery.isError && (
          <div className="alert" role="alert">
            <AlertCircle size={16} />
            <span>{featureErrorText(membersQuery.error)}</span>
          </div>
        )}
        {changeRoleMutation.isError && (
          <div className="alert" role="alert">
            <AlertCircle size={16} />
            <span>{featureErrorText(changeRoleMutation.error)}</span>
          </div>
        )}
        {removeMemberMutation.isError && (
          <div className="alert" role="alert">
            <AlertCircle size={16} />
            <span>{featureErrorText(removeMemberMutation.error)}</span>
          </div>
        )}
        {transferOwnerMutation.isError && (
          <div className="alert" role="alert">
            <AlertCircle size={16} />
            <span>{featureErrorText(transferOwnerMutation.error)}</span>
          </div>
        )}

        {pendingTransfer && (
          <div className="settings-confirm-box" role="alertdialog" aria-label="确认转让所有权">
            <p>
              <strong>确认将空间 OWNER 转让给「{pendingTransfer.displayName}」？</strong>
              转让后你将自动降级为 ADMIN，且该操作不可逆转。
            </p>
            <div className="actions">
              <Button
                className="secondary workbench-btn-xs"
                disabled={transferOwnerMutation.isPending}
                onClick={() => setPendingTransfer(null)}
              >
                取消
              </Button>
              <Button
                className="primary workbench-btn-xs"
                disabled={transferOwnerMutation.isPending}
                onClick={() => {
                  const key =
                    transferIdemRef.current?.userId === pendingTransfer.userId
                      ? transferIdemRef.current.key
                      : makeIdempotencyKey();
                  transferIdemRef.current = { userId: pendingTransfer.userId, key };
                  transferOwnerMutation.mutate({
                    newOwnerUserId: pendingTransfer.userId,
                    key,
                  });
                }}
              >
                {transferOwnerMutation.isPending ? "转让中…" : "确认转让 OWNER"}
              </Button>
            </div>
          </div>
        )}

        {pendingRemoval && (
          <div className="settings-confirm-box danger-box" role="alertdialog" aria-label="确认移除成员">
            <p>
              <strong>确认移除成员「{pendingRemoval.displayName}」？</strong>
              移除后其当前 Epoch、WebSocket 连接及所有设备访问授权将立即失效。
            </p>
            <div className="actions">
              <Button
                className="secondary workbench-btn-xs"
                disabled={removeMemberMutation.isPending}
                onClick={() => setPendingRemoval(null)}
              >
                取消
              </Button>
              <Button
                className="danger workbench-btn-xs"
                disabled={removeMemberMutation.isPending}
                onClick={() => removeMemberMutation.mutate(pendingRemoval.userId)}
              >
                {removeMemberMutation.isPending ? "移除中…" : "确认移除"}
              </Button>
            </div>
          </div>
        )}

        {membersQuery.isSuccess && (
          <ul className="settings-list">
            {membersQuery.data.map((member) => {
              const isSelf = member.userId === currentUserId;
              const canEdit = canModifyMemberRole(member);
              const editableRoles: InviteRole[] = isOwner
                ? ["ADMIN", "MEMBER", "VIEWER"]
                : ["MEMBER", "VIEWER"];

              return (
                <li key={member.userId} className="settings-list-row">
                  <div className="settings-row-info">
                    <div className="settings-row-title">
                      <strong>{member.displayName}</strong>
                      {isSelf && <span className="tag">当前账号</span>}
                      <span className={`space-role-badge role-${member.role.toLowerCase()}`}>
                        {SPACE_ROLE_LABELS[member.role]}
                      </span>
                    </div>
                    <p className="muted">
                      <code>{member.userId}</code> · Epoch #{member.membershipEpoch}
                    </p>
                  </div>

                  {canEdit && (
                    <div className="settings-member-actions">
                      <select
                        aria-label={`修改 ${member.displayName} 的角色`}
                        className="settings-role-select"
                        value={member.role}
                        disabled={changeRoleMutation.isPending}
                        onChange={(e) => {
                          changeRoleMutation.mutate({
                            userId: member.userId,
                            role: e.target.value as InviteRole,
                          });
                        }}
                      >
                        {editableRoles.map((r) => (
                          <option key={r} value={r}>
                            {r}
                          </option>
                        ))}
                      </select>

                      {isOwner && (
                        <Button
                          className="secondary workbench-btn-xs"
                          title="转让空间所有权给该成员"
                          disabled={transferOwnerMutation.isPending}
                          onClick={() => {
                            setPendingRemoval(null);
                            setPendingTransfer(member);
                          }}
                        >
                          <Crown size={13} /> 转让 OWNER
                        </Button>
                      )}

                      <Button
                        className="secondary workbench-btn-xs"
                        title="移除该成员"
                        disabled={removeMemberMutation.isPending}
                        onClick={() => {
                          setPendingTransfer(null);
                          setPendingRemoval(member);
                        }}
                      >
                        <UserMinus size={13} /> 移除
                      </Button>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <section className="settings-section" aria-label="离开空间">
        <div className="section-head">
          <h3>
            <Shield size={16} /> 退出成员资格
          </h3>
        </div>

        {isOwner ? (
          <div className="drawer-callout drawer-callout-warn" role="status">
            <AlertTriangle size={16} />
            <div>
              <strong>唯一 OWNER 不可直接离开空间</strong>
              <p>请先在上方成员列表中将空间所有权转让给其他成员，再执行离开操作。</p>
            </div>
          </div>
        ) : (
          <div className="settings-leave-box">
            <p className="muted">
              离开空间将立即终止你当前 Epoch 的全部连接、未读游标、已授权 CLI 设备凭证和消息读取权限。
            </p>
            {leaveSpaceMutation.isError && (
              <div className="alert" role="alert">
                <AlertCircle size={16} />
                <span>{featureErrorText(leaveSpaceMutation.error)}</span>
              </div>
            )}
            {!confirmLeave ? (
              <Button
                className="danger workbench-btn-xs"
                onClick={() => setConfirmLeave(true)}
              >
                <LogOut size={14} /> 离开此空间
              </Button>
            ) : (
              <div className="actions">
                <Button
                  className="secondary workbench-btn-xs"
                  disabled={leaveSpaceMutation.isPending}
                  onClick={() => setConfirmLeave(false)}
                >
                  取消
                </Button>
                <Button
                  className="danger workbench-btn-xs"
                  disabled={leaveSpaceMutation.isPending}
                  onClick={() => leaveSpaceMutation.mutate()}
                >
                  {leaveSpaceMutation.isPending ? "正在离开…" : "确认离开空间"}
                </Button>
              </div>
            )}
          </div>
        )}
      </section>
    </div>
  );
}

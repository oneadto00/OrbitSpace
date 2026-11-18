import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { DeviceGrant, LoginSession } from "@orbitspace/shared-types";
import { Laptop, Terminal } from "lucide-react";
import { api, jsonBody, logout } from "../../shared/api/httpClient";
import { dateText, scopeNames } from "./display";
import { Button, Failure, Field, Loading, Modal } from "../../shared/ui/components";

type Profile = { userId: string; displayName: string; email: string; emailVerified: boolean };

function ProfileForm({ profile }: { profile: Profile }) {
  const cache = useQueryClient();
  const [name, setName] = useState(profile.displayName);
  const save = useMutation({
    mutationFn: () => api<Profile>("/auth/profile", jsonBody({ displayName: name.trim() }, "PATCH")),
    onSuccess: (updated) => cache.setQueryData(["profile"], updated),
  });
  function submit(e: FormEvent) {
    e.preventDefault();
    if (name.trim() && !save.isPending) save.mutate();
  }
  return <form onSubmit={submit}>
    <div className="account-email"><strong>{profile.email}</strong><span>{profile.emailVerified ? "邮箱已验证" : "邮箱待验证"}</span></div>
    <Field label="显示名称" value={name} maxLength={120} required onChange={(e) => { setName(e.target.value); save.reset(); }} />
    <Failure error={save.error} />
    <div className="actions"><Button type="submit" className="primary" disabled={save.isPending || !name.trim() || name.trim() === profile.displayName}>保存资料</Button>
      {save.isSuccess && <span role="status">已保存</span>}</div>
  </form>;
}

type Revoke = { kind: "session" | "credential"; id: string; label: string; current?: boolean };

export function AccountPage() {
  const cache = useQueryClient();
  const [target, setTarget] = useState<Revoke>();
  const profile = useQuery({ queryKey: ["profile"], queryFn: ({ signal }) => api<Profile>("/auth/profile", { signal }) });
  const sessions = useQuery({ queryKey: ["sessions"], queryFn: ({ signal }) => api<LoginSession[]>("/auth/sessions", { signal }) });
  const credentials = useQuery({ queryKey: ["credentials"], queryFn: ({ signal }) => api<DeviceGrant[]>("/auth/device/credentials", { signal }) });
  const revoke = useMutation({
    mutationFn: async (entry: Revoke) => {
      if (entry.current) return logout();
      const path = entry.kind === "session" ? "/auth/sessions/" : "/auth/device/credentials/";
      await api(path + encodeURIComponent(entry.id), { method: "DELETE" });
    },
    onSuccess: (_, entry) => {
      setTarget(undefined);
      if (!entry.current) void cache.invalidateQueries({ queryKey: [entry.kind === "session" ? "sessions" : "credentials"] });
    },
  });
  const confirm = (entry: Revoke) => { revoke.reset(); setTarget(entry); };
  return <section className="content">
    <header className="page-head"><h1>账号与安全</h1><p className="subtitle">管理个人资料、浏览器会话和已授权的 CLI 设备。</p></header>
    <section className="surface"><h2>个人资料</h2><Failure error={profile.error} />
      {profile.isPending && <Loading />}{profile.data && <ProfileForm profile={profile.data} />}</section>
    <section className="surface"><h2>登录会话</h2><Failure error={sessions.error} />
      {sessions.isPending && <Loading />}{sessions.data?.length === 0 && <p>没有活动会话。</p>}
      {sessions.data?.map((s) => <div className="access-row" key={s.sessionId}><Laptop size={20} />
        <div><strong>{s.deviceLabel}</strong>{s.current && <span className="tag">当前设备</span>}
          <p>最近使用 {dateText(s.lastUsedAt)}</p></div>
        <Button className="secondary" onClick={() => confirm({ kind: "session", id: s.sessionId, label: s.deviceLabel, current: s.current })}>
          {s.current ? "退出" : "撤销"}</Button>
      </div>)}</section>
    <section className="surface"><h2>CLI 设备凭证</h2><Failure error={credentials.error} />
      {credentials.isPending && <Loading />}{credentials.data?.length === 0 && <p className="muted">还没有已授权的 CLI 设备。</p>}
      {credentials.data?.map((d) => <div className="access-row" key={d.credentialId}><Terminal size={20} />
        <div><strong>{d.deviceLabel}</strong><p>空间 {d.spaceId} · 工程 {d.projectId}</p>
          <p>{d.scopes.map((s) => scopeNames[s]).join("、")} · 有效至 {dateText(d.expiresAt)}</p></div>
        <Button className="secondary" onClick={() => confirm({ kind: "credential", id: d.credentialId, label: d.deviceLabel })}>撤销</Button>
      </div>)}</section>
    {target && <Modal title="撤销设备访问" onClose={() => { if (!revoke.isPending) setTarget(undefined); }}>
      <p>确认结束「{target.label}」的{target.kind === "session" ? "登录会话" : "工程访问权限"}？</p>
      <Failure error={revoke.error} />
      <div className="actions"><Button className="secondary" disabled={revoke.isPending} onClick={() => setTarget(undefined)}>取消</Button>
        <Button className="danger" disabled={revoke.isPending} onClick={() => revoke.mutate(target)}>确认撤销</Button></div>
    </Modal>}
  </section>;
}

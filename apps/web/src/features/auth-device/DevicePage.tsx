import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import type { DeviceApproval, DeviceScope, IdentitySpaceOperations, SpaceDetail, SpaceSummary } from "@orbitspace/shared-types";
import { ArrowRight, CheckCircle2, Terminal } from "lucide-react";
import { Link, useSearchParams } from "react-router-dom";
import { api, jsonBody } from "../../shared/api/httpClient";
import { spacesKey, spaceKey } from "../../shared/api/access";
import { canUploadOrManage } from "../../shared/auth/session";
import { Button, Failure, Field, Loading } from "../../shared/ui/UiComponents";
import { dateText, scopeNames } from "./display";

type PendingDevice = IdentitySpaceOperations["getPendingDeviceRequest"]["responses"][200]["content"]["application/json"];

function GrantForm({ code, device, restart }: { code: string; device: PendingDevice; restart: () => void }) {
  const [spaceId, setSpaceId] = useState("");
  const [projectId, setProjectId] = useState("");
  const [scopes, setScopes] = useState<DeviceScope[]>(device.requestedScopes);
  const [expired, setExpired] = useState(Date.now() >= Date.parse(device.expiresAt));
  useEffect(() => {
    const id = window.setTimeout(() => setExpired(true), Math.max(0, Date.parse(device.expiresAt) - Date.now()));
    return () => clearTimeout(id);
  }, [device.expiresAt]);
  const spaces = useQuery({
    queryKey: spacesKey,
    queryFn: ({ signal }) => api<SpaceSummary[]>("/spaces", { signal }),
  });
  const eligible = spaces.data?.filter((item) => canUploadOrManage(item.role)) ?? [];
  const space = eligible.find((item) => item.spaceId === spaceId);
  const detail = useQuery({
    queryKey: space ? [...spaceKey(space), "detail"] : ["device", "no-space"],
    enabled: !!space,
    queryFn: ({ signal }) => api<SpaceDetail>("/spaces/" + encodeURIComponent(space!.spaceId), { signal }, space),
  });
  const project = detail.data?.projects.find((item) => item.projectId === projectId);
  const approve = useMutation({
    mutationFn: (payload: DeviceApproval) => api<void>("/auth/device/approve", jsonBody(payload), space),
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    if (!space || !project || approve.isPending || !scopes.length || Date.now() >= Date.parse(device.expiresAt)) return;
    approve.mutate({ user_code: code, spaceId: space.spaceId, projectId: project.projectId, membershipEpoch: space.membershipEpoch, scopes });
  }
  if (approve.isSuccess) return <div className="success" role="status"><CheckCircle2 size={25} />
    <h2>设备授权已确认</h2><p>请回到终端完成登录。</p><Link className="text-link" to="/account">查看账号与安全 <ArrowRight size={15} /></Link></div>;
  return <form onSubmit={submit} className="grant-form">
    <div className="device-heading"><Terminal size={24} /><div><h2>{device.deviceLabel}</h2><p>短码 {code} · 有效至 {dateText(device.expiresAt)}</p></div></div>
    <p>请核对终端中的短码，仅为你正在使用的设备授权。</p>
    {spaces.isPending && <Loading label="正在读取可授权的空间…" />}
    {spaces.isError && <div className="alert" role="alert">暂时无法读取可授权的空间。<Button className="secondary" onClick={() => { void spaces.refetch(); }}>重试</Button></div>}
    {spaces.isSuccess && eligible.length === 0 && <p className="alert">当前账号没有可授予上传权限的空间。</p>}
    <label className="field">空间<select required value={space?.spaceId ?? ""} disabled={!eligible.length || approve.isPending}
      onChange={(e) => { setSpaceId(e.target.value); setProjectId(""); }}>
      <option value="">选择空间</option>{eligible.map((s) => <option key={s.spaceId} value={s.spaceId}>{s.name}</option>)}</select></label>
    <label className="field">Git 工程<select required value={project?.projectId ?? ""} disabled={!detail.data?.projects.length || approve.isPending}
      onChange={(e) => setProjectId(e.target.value)}>
      <option value="">选择工程</option>{detail.data?.projects.map((p) => <option key={p.projectId} value={p.projectId}>{p.name}</option>)}</select></label>
    {space && detail.isFetching && <Loading label="正在读取工程…" />}
    {space && detail.isError && <><Failure error={detail.error} /><Button className="secondary" onClick={() => { void detail.refetch(); }}>重新读取工程</Button></>}
    {space && detail.data?.projects.length === 0 && <p>该空间没有可授权的 Git 工程。</p>}
    <fieldset disabled={approve.isPending}><legend>允许此设备执行</legend>
      {device.requestedScopes.map((scope) => <label className="checkbox" key={scope}><input type="checkbox" checked={scopes.includes(scope)}
        onChange={(e) => setScopes((current) => e.target.checked ? [...current, scope] : current.filter((s) => s !== scope))} />
        {scopeNames[scope]} <code>{scope}</code></label>)}
    </fieldset>
    {expired && <p role="alert" className="alert">短码已过期，请在终端重新申请。</p>}
    <Failure error={approve.error} />
    <div className="actions"><Button type="submit" className="primary" disabled={!space || !project || expired || !scopes.length || approve.isPending}>确认授权</Button>
      <Button className="secondary" disabled={approve.isPending} onClick={restart}>更换短码</Button></div>
  </form>;
}

export function DevicePage() {
  const [params] = useSearchParams();
  const [code, setCode] = useState(params.get("user_code") ?? "");
  const lookup = useMutation({
    mutationFn: (user_code: string) => api<PendingDevice>("/auth/device/lookup", jsonBody({ user_code })),
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    if (code.trim() && !lookup.isPending) lookup.mutate(code.trim());
  }
  return <section className="content"><header className="page-head"><h1>授权 CLI 设备</h1><p className="subtitle">将终端设备连接到指定空间的 Git 工程。</p></header>
    <div className="surface narrow">
      {lookup.data && lookup.variables ? <GrantForm code={lookup.variables} device={lookup.data} restart={() => { lookup.reset(); setCode(""); }} /> :
        <form onSubmit={submit}><h2>输入设备短码</h2><p className="muted">运行 <code>orbit login</code> 后，在这里输入终端显示的短码。</p>
          <Field label="设备短码" name="user_code" value={code} required maxLength={64} spellCheck={false} autoComplete="off"
            autoCapitalize="characters" onChange={(e) => setCode(e.target.value)} />
          <Failure error={lookup.error} /><Button type="submit" className="primary" disabled={lookup.isPending || !code.trim()}>核对设备 <ArrowRight size={16} /></Button>
        </form>}
    </div></section>;
}

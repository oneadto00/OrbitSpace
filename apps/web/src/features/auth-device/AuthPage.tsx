import { useState, type FormEvent } from "react";
import { useMutation } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, CheckCircle2 } from "lucide-react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { Brand, Button, Failure, Field } from "../../shared/ui/components";
import { login, publicPost } from "../../shared/api/httpClient";

type Mode = "login" | "register" | "verify" | "forgot" | "reset";
const copy = {
  login: ["欢迎回来", "使用你的 OrbitSpace 账号登录工作台。", "登录"],
  register: ["创建账号", "使用邮箱注册，完成验证后即可登录。", "注册并发送验证邮件"],
  verify: ["验证邮箱", "确认后，邮箱将绑定到你的 OrbitSpace 账号。", "确认验证"],
  forgot: ["找回密码", "输入注册邮箱以接收密码重置链接。", "发送重置链接"],
  reset: ["设置新密码", "更新密码后，其他登录会话将失效。", "保存新密码"],
} as const;
const completed = {
  register: "请检查邮箱，按照邮件中的链接完成验证。",
  verify: "邮箱验证完成，现在可以登录账号。",
  forgot: "若该邮箱已注册，你将收到一封密码重置邮件。",
  reset: "密码已更新，请使用新密码登录。",
};

export function AuthPage({ mode, challenge }: { mode: Mode; challenge?: string }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [token, setToken] = useState(challenge ?? "");
  const [title, description, action] = copy[mode];
  const auth = useMutation({
    mutationFn: async () => {
      switch (mode) {
        case "login": return login({ email: email.trim(), password });
        case "register": return publicPost("/auth/register", { email: email.trim(), password });
        case "verify": return publicPost("/auth/verify-email", { token: token.trim() });
        case "forgot": return publicPost("/auth/password-reset/request", { email: email.trim() });
        case "reset": return publicPost("/auth/password-reset/confirm", { token: token.trim(), newPassword: password });
      }
    },
    onSuccess: () => {
      setPassword("");
      setToken("");
      if (mode === "login") {
        const next: unknown = location.state?.returnTo;
        navigate(typeof next === "string" && /^\/(?:device(?:\?|$)|account$)/.test(next) ? next : "/", { replace: true });
      }
    },
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    if (!auth.isPending) auth.mutate();
  }
  const hasEmail = mode === "login" || mode === "register" || mode === "forgot";
  const hasPassword = mode === "login" || mode === "register" || mode === "reset";
  return <main className="auth-layout">
    <aside className="identity-art" aria-label="OrbitSpace">
      <Brand />
      <div className="orbits" aria-hidden="true">
        <div className="arc arc-outer" /><div className="arc arc-middle" /><div className="arc arc-inner" />
        <div className="orbital-core" /><i className="satellite one" /><i className="satellite two" /><i className="satellite three" />
      </div>
    </aside>
    <section className="auth-panel">
      <div className="auth-form">
        {mode !== "login" && <Link className="back-link" to="/login" state={location.state}><ArrowLeft size={15} /> 返回登录</Link>}
        <h1>{title}</h1>
        {auth.isSuccess && mode !== "login" ? <div role="status" className="success">
          <CheckCircle2 size={24} /><p>{completed[mode]}</p><Link className="text-link" to="/login" state={location.state}>前往登录 <ArrowRight size={15} /></Link>
        </div> : <>
          <p className="subtitle">{description}</p>
          <form onSubmit={submit}>
            {hasEmail && <Field label="邮箱地址" name="email" autoComplete="email" type="email" required placeholder="name@example.com"
              value={email} onChange={(e) => setEmail(e.target.value)} />}
            {(mode === "verify" || mode === "reset") && !challenge && <Field label="邮件中的验证码" name="token" required autoComplete="off"
              value={token} onChange={(e) => setToken(e.target.value)} />}
            {hasPassword && <Field label={mode === "reset" ? "新密码" : "密码"} name="password" type="password" required
              autoComplete={mode === "login" ? "current-password" : "new-password"} value={password} onChange={(e) => setPassword(e.target.value)} />}
            {mode === "login" && <Link className="forgot" to="/forgot-password" state={location.state}>忘记密码？</Link>}
            <Failure error={auth.error} />
            <Button type="submit" disabled={auth.isPending} className="primary wide">{auth.isPending ? "正在提交…" : action}<ArrowRight size={16} /></Button>
          </form>
          {mode === "login" && <p className="switch-mode">还没有账号？ <Link className="text-link" to="/register" state={location.state}>注册账号</Link></p>}
        </>}
      </div>
    </section>
  </main>;
}

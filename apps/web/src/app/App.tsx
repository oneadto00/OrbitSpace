import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import {
  ArrowRight,
  LayoutDashboard,
  LogOut,
  ShieldCheck,
  Terminal,
  X,
} from "lucide-react";
import {
  Link,
  NavLink,
  Navigate,
  Outlet,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from "react-router-dom";
import { AccountPage } from "../features/auth-device/AccountPage";
import { AuthPage } from "../features/auth-device/AuthPage";
import { DevicePage } from "../features/auth-device/DevicePage";
import { JoinSpacePage } from "../features/space/JoinSpacePage";
import { SpaceHomePage } from "../features/space/SpaceHomePage";
import { logout, refreshSession } from "../shared/api/httpClient";
import { useAuth } from "../shared/auth/session";
import { Brand, Button, Failure, Loading } from "../shared/ui/components";
import { SpaceWorkbenchPage } from "./SpaceWorkbenchPage";
import "./workbench.css";

let pendingReturnTo: string | null = null;
let sawAuthenticatedInTab = false;

function Guard() {
  const status = useAuth((s) => s.status);
  const location = useLocation();
  const [error, setError] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (status === "authenticated") {
      sawAuthenticatedInTab = true;
      if (location.pathname === "/join" || location.pathname.startsWith("/spaces/")) {
        pendingReturnTo = null;
      }
    }
  }, [location.pathname, status]);

  useEffect(() => {
    if (status !== "unknown") return;
    let active = true;
    void refreshSession().catch((reason) => {
      if (active) setError(reason);
    });
    return () => {
      active = false;
    };
  }, [attempt, status]);

  if (status === "unknown") {
    return (
      <main className="standalone">
        <Brand />
        {error ? (
          <>
            <Failure error={error} />
            <Button
              className="primary"
              onClick={() => {
                setError(null);
                setAttempt(attempt + 1);
              }}
            >
              重新连接
            </Button>
            <Link className="text-link" to="/login">
              前往登录
            </Link>
          </>
        ) : (
          <Loading label="正在恢复会话…" />
        )}
      </main>
    );
  }

  if (status === "anonymous") {
    const returnTo = location.pathname + location.search;
    if (
      (location.pathname === "/join" && location.search) ||
      (!sawAuthenticatedInTab &&
        location.pathname.startsWith("/spaces/") &&
        location.search)
    ) {
      pendingReturnTo = returnTo;
    }
    return <Navigate replace to="/login" state={{ returnTo }} />;
  }

  return <Outlet />;
}

function HomeRoute() {
  const navigate = useNavigate();

  useEffect(() => {
    if (pendingReturnTo) {
      const target = pendingReturnTo;
      pendingReturnTo = null;
      navigate(target, { replace: true });
    }
  }, [navigate]);

  if (pendingReturnTo) {
    return (
      <section className="content">
        <Loading label="正在跳转至目标页面…" />
      </section>
    );
  }

  return <SpaceHomePage />;
}

function Shell() {
  const exit = useMutation({ mutationFn: logout });
  return (
    <div className="shell">
      <aside className="sidebar">
        <Link className="brand-link" to="/">
          <Brand />
        </Link>
        <nav aria-label="主要导航">
          <NavLink end to="/">
            <LayoutDashboard size={18} />
            工作台
          </NavLink>
          <NavLink to="/device">
            <Terminal size={18} />
            授权 CLI 设备
          </NavLink>
          <NavLink to="/account">
            <ShieldCheck size={18} />
            账号与安全
          </NavLink>
        </nav>
        <div className="sidebar-end">
          <Failure error={exit.error} />
          <Button
            className="logout"
            onClick={() => exit.mutate()}
            disabled={exit.isPending}
          >
            <LogOut size={17} />
            退出登录
          </Button>
        </div>
      </aside>
      <main className="workspace">
        <Outlet />
      </main>
    </div>
  );
}

function Notice() {
  const notice = useAuth((s) => s.notice);
  return notice ? (
    <div className="notice" role="alert">
      {notice}
      <Button
        className="icon-button secondary"
        aria-label="关闭提示"
        onClick={() => useAuth.setState({ notice: null })}
      >
        <X size={14} />
      </Button>
    </div>
  ) : null;
}

export function App({
  challenge,
}: {
  challenge?: { path: string; token: string };
}) {
  return (
    <>
      <Notice />
      <Routes>
        <Route path="/login" element={<AuthPage key="login" mode="login" />} />
        <Route
          path="/register"
          element={<AuthPage key="register" mode="register" />}
        />
        <Route
          path="/verify-email"
          element={
            <AuthPage
              key="verify"
              mode="verify"
              challenge={
                challenge?.path === "/verify-email" ? challenge.token : undefined
              }
            />
          }
        />
        <Route
          path="/forgot-password"
          element={<AuthPage key="forgot" mode="forgot" />}
        />
        <Route
          path="/reset-password"
          element={
            <AuthPage
              key="reset"
              mode="reset"
              challenge={
                challenge?.path === "/reset-password"
                  ? challenge.token
                  : undefined
              }
            />
          }
        />
        <Route element={<Guard />}>
          <Route element={<Shell />}>
            <Route index element={<HomeRoute />} />
            <Route path="/join" element={<JoinSpacePage />} />
            <Route path="/account" element={<AccountPage />} />
            <Route path="/device" element={<DevicePage />} />
          </Route>
          <Route path="/spaces/:spaceId" element={<SpaceWorkbenchPage />} />
        </Route>
        <Route
          path="*"
          element={
            <main className="standalone">
              <Brand />
              <h1>页面不存在</h1>
              <Link className="text-link" to="/">
                返回工作台 <ArrowRight size={16} />
              </Link>
            </main>
          }
        />
      </Routes>
    </>
  );
}

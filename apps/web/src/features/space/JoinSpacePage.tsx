import { useCallback, useEffect, useRef, useState } from "react";
import type { SpaceSummary } from "@orbitspace/shared-types";
import { AlertCircle, ArrowRight, RefreshCw, UserPlus } from "lucide-react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { queryClient, spacesKey } from "../../shared/api/access";
import { useAuth } from "../../shared/auth/session";
import { Button, Loading } from "../../shared/ui/components";
import {
  featureErrorText,
  joinSpaceByToken,
  makeIdempotencyKey,
  upsertSpaceSummary,
} from "./spaceApi";

interface JoinAttempt {
  attemptKey: string;
  idempotencyKey: string;
  promise: Promise<SpaceSummary>;
}

export function JoinSpacePage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const userId = useAuth((s) => s.session?.userId ?? "");
  const token = params.get("token")?.trim() ?? "";

  const [status, setStatus] = useState<"idle" | "joining" | "error">("idle");
  const [error, setError] = useState<unknown>(null);
  const [retrySeq, setRetrySeq] = useState(0);

  const idempotencyKeysRef = useRef<Map<string, string>>(new Map());
  const inFlightRef = useRef<JoinAttempt | null>(null);

  const getIdempotencyKey = useCallback((attemptKey: string) => {
    const existing = idempotencyKeysRef.current.get(attemptKey);
    if (existing) return existing;
    const created = makeIdempotencyKey();
    idempotencyKeysRef.current.set(attemptKey, created);
    return created;
  }, []);

  useEffect(() => {
    if (!token) return;
    let active = true;
    const attemptKey = `${userId}:${token}`;
    const idempotencyKey = getIdempotencyKey(attemptKey);

    setStatus("joining");
    setError(null);

    if (!inFlightRef.current || inFlightRef.current.attemptKey !== attemptKey) {
      const promise = joinSpaceByToken(token, idempotencyKey).catch((err) => {
        if (inFlightRef.current?.attemptKey === attemptKey) {
          inFlightRef.current = null;
        }
        throw err;
      });
      inFlightRef.current = { attemptKey, idempotencyKey, promise };
    }

    inFlightRef.current.promise.then(
      (space) => {
        if (!active) return;
        upsertSpaceSummary(space);
        void queryClient.invalidateQueries({ queryKey: spacesKey });
        navigate(`/spaces/${encodeURIComponent(space.spaceId)}`, { replace: true });
      },
      (err: unknown) => {
        if (!active) return;
        setStatus("error");
        setError(err);
      },
    );

    return () => {
      active = false;
    };
  }, [getIdempotencyKey, navigate, retrySeq, token, userId]);

  return (
    <section className="content">
      <header className="page-head">
        <h1>加入空间</h1>
        <p className="subtitle">校验邀请链接并建立空间成员关系。</p>
      </header>

      <div className="surface narrow join-space-card">
        <div className="device-heading">
          <UserPlus size={22} />
          <div>
            <h2>空间邀请兑换</h2>
            <p className="muted">验证成功后将自动进入该空间工作台。</p>
          </div>
        </div>

        {!token && (
          <>
            <div className="alert" role="alert">
              <AlertCircle size={17} />
              <span>当前链接缺少邀请令牌（token），请检查邀请链接是否完整。</span>
            </div>
            <Link className="text-link" to="/">
              返回空间列表 <ArrowRight size={15} />
            </Link>
          </>
        )}

        {token && status !== "error" && <Loading label="正在校验邀请令牌并加入空间…" />}

        {token && status === "error" && (
          <>
            <div className="alert" role="alert">
              <AlertCircle size={17} />
              <span>{featureErrorText(error)}</span>
            </div>
            <div className="actions">
              <Button
                className="primary"
                onClick={() => {
                  inFlightRef.current = null;
                  setRetrySeq((seq) => seq + 1);
                }}
              >
                <RefreshCw size={15} /> 重试加入
              </Button>
              <Link className="button secondary" to="/">
                返回空间列表
              </Link>
            </div>
          </>
        )}
      </div>
    </section>
  );
}

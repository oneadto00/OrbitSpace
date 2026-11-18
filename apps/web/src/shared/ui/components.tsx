import { useEffect, useId, useRef, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode } from "react";
import { AlertCircle, Archive, LockKeyhole, Orbit, ShieldAlert, X } from "lucide-react";
import type { CardState } from "@orbitspace/shared-types";
import { errorText } from "../api/httpClient";

export function Brand() {
  return <span className="brand"><span className="brand-mark"><Orbit size={22} /></span>OrbitSpace</span>;
}

export function Button({ children, className = "", type = "button", ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button type={type} className={`button ${className}`} {...props}>{children}</button>;
}

export function Field({ label, id, ...props }: InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  const generated = useId();
  return <label htmlFor={id ?? generated} className="field">{label}
    <input {...props} id={id ?? generated} />
  </label>;
}

export function Failure({ error }: { error: unknown }) {
  return error ? <div className="alert" role="alert"><AlertCircle size={17} /><span>{errorText(error)}</span></div> : null;
}

export function Loading({ label = "正在加载…" }: { label?: string }) {
  return <div className="loading" role="status"><span aria-hidden="true" className="skeleton" />{label}</div>;
}

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  const id = useId();
  useEffect(() => {
    const dialog = ref.current;
    dialog?.showModal();
    return () => dialog?.close();
  }, []);
  return <dialog ref={ref} aria-labelledby={id} onCancel={(event) => { event.preventDefault(); onClose(); }}>
    <div className="section-head"><h2 id={id}>{title}</h2><Button className="icon-button secondary" onClick={onClose} aria-label="关闭"><X size={16} /></Button></div>
    {children}
  </dialog>;
}

const cardStates = {
  REVOKED_FOR_ACTOR: { icon: LockKeyhole, title: "无权查看此资源" },
  FORBIDDEN: { icon: LockKeyhole, title: "无权查看此资源" },
  EXPIRED_OR_DELETED: { icon: Archive, title: "资源已过期或已删除" },
  FORCE_PURGED: { icon: ShieldAlert, title: "资源内容已清除" },
};
export function ResourceStateCard({ state }: { state: Exclude<CardState, "AVAILABLE"> | "REVOKED_FOR_ACTOR" }) {
  const { icon: Icon, title } = cardStates[state];
  return <div className="resource-state" role="status"><Icon size={22} /><strong>{title}</strong></div>;
}

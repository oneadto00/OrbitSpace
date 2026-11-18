import { create } from "zustand";
import type { AccessSession, SpaceRole } from "@orbitspace/shared-types";

type AuthState = {
  session: AccessSession | null;
  status: "unknown" | "authenticated" | "anonymous";
  notice: string | null;
};

// Access tokens never leave this in-memory store.
export const useAuth = create<AuthState>(() => ({ session: null, status: "unknown", notice: null }));

export function canUploadOrManage(role?: SpaceRole) {
  return role === "MEMBER" || role === "ADMIN" || role === "OWNER";
}

export function canManageSpace(role?: SpaceRole) {
  return role === "ADMIN" || role === "OWNER";
}

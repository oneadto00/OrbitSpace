import { QueryClient } from "@tanstack/react-query";
import type { SpaceSummary } from "@orbitspace/shared-types";
import { useAuth } from "../auth/session";

export const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
});

export type SpaceAccess = Pick<SpaceSummary, "spaceId" | "membershipEpoch">;
export const spacesKey = ["memberships"] as const;
export const spaceKey = ({ spaceId, membershipEpoch }: SpaceAccess) =>
  ["space", spaceId, membershipEpoch] as const;

const controllers = new Map<string, Map<number, AbortController>>();
const revoked = new Map<string, number>();

// Bind space requests and live connections to this signal. A new membership
// receives an independent signal, even if an older request arrives late.
export function spaceSignal({ spaceId, membershipEpoch }: SpaceAccess): AbortSignal {
  if (membershipEpoch <= (revoked.get(spaceId) ?? 0)) return AbortSignal.abort();
  let epochs = controllers.get(spaceId);
  if (!epochs) controllers.set(spaceId, epochs = new Map());
  let controller = epochs.get(membershipEpoch);
  if (!controller) epochs.set(membershipEpoch, controller = new AbortController());
  return controller.signal;
}

export function bindSpaceConnection(access: SpaceAccess, close: () => void) {
  const signal = spaceSignal(access);
  if (signal.aborted) close();
  else signal.addEventListener("abort", close, { once: true });
  return () => signal.removeEventListener("abort", close);
}

export function revokeSpace(access: SpaceAccess) {
  const { spaceId, membershipEpoch } = access;
  revoked.set(spaceId, Math.max(revoked.get(spaceId) ?? 0, membershipEpoch));
  const epochs = controllers.get(spaceId);
  for (const [epoch, controller] of epochs ?? []) {
    if (epoch <= membershipEpoch) {
      controller.abort();
      epochs?.delete(epoch);
    }
  }
  queryClient.removeQueries({ predicate: ({ queryKey: [prefix, id, epoch] }) =>
    prefix === "space" && id === spaceId && typeof epoch === "number" && epoch <= membershipEpoch });
  void queryClient.cancelQueries({ queryKey: spacesKey });
  queryClient.setQueryData<SpaceSummary[]>(spacesKey, (items) =>
    items?.filter((item) => item.spaceId !== spaceId || item.membershipEpoch > membershipEpoch));
  void queryClient.invalidateQueries({ queryKey: spacesKey });
  useAuth.setState({ notice: "你对该空间的授权已失效。请重新核对空间成员资格。" });
}

export function clearPrivateData() {
  for (const epochs of controllers.values()) for (const controller of epochs.values()) controller.abort();
  controllers.clear();
  revoked.clear();
  queryClient.clear();
}

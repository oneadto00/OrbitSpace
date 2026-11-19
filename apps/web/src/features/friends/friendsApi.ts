import type {
  FriendListResponse,
  FriendRequestDirection,
  FriendRequestDto,
  FriendRequestListResponse,
  SendFriendRequestBody,
  UserSearchResponse,
} from "@orbitspace/shared-types";
import { api, HttpError } from "../../shared/api/httpClient";
import { featureErrorText, jsonRequest } from "../space/spaceApi";

export const friendsKey = ["friends", "list"] as const;
export const friendRequestsKey = ["friends", "requests"] as const;

export function friendsErrorText(error: unknown): string {
  if (error instanceof HttpError && error.code === "INVALID_REQUEST") {
    const reason = error.details?.reason;
    if (reason === "ALREADY_FRIENDS") {
      return "双方已经是好友，无需重复申请。";
    }
    if (reason === "PENDING_REQUEST_EXISTS") {
      return "双方已存在待处理的好友申请，请在好友申请列表中处理。";
    }
  }
  return featureErrorText(error);
}

export function searchUsers(
  query: string,
  limit = 20,
  signal?: AbortSignal,
): Promise<UserSearchResponse> {
  const qs = new URLSearchParams();
  qs.set("q", query.trim());
  qs.set("limit", String(limit));
  return api<UserSearchResponse>(`/users/search?${qs.toString()}`, { signal });
}

export function sendFriendRequest(
  body: SendFriendRequestBody,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<FriendRequestDto> {
  return api<FriendRequestDto>("/friend-requests", {
    ...jsonRequest(body, "POST", { "Idempotency-Key": idempotencyKey }),
    signal,
  });
}

export function listFriendRequests(
  direction: FriendRequestDirection = "ALL",
  signal?: AbortSignal,
): Promise<FriendRequestListResponse> {
  const qs = new URLSearchParams();
  qs.set("direction", direction);
  return api<FriendRequestListResponse>(`/friend-requests?${qs.toString()}`, { signal });
}

export function acceptFriendRequest(
  requestId: string,
  idempotencyKey: string,
): Promise<FriendRequestDto> {
  return api<FriendRequestDto>(
    `/friend-requests/${encodeURIComponent(requestId)}/accept`,
    {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    },
  );
}

export function rejectFriendRequest(
  requestId: string,
  idempotencyKey: string,
): Promise<FriendRequestDto> {
  return api<FriendRequestDto>(
    `/friend-requests/${encodeURIComponent(requestId)}/reject`,
    {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    },
  );
}

export function cancelFriendRequest(
  requestId: string,
  idempotencyKey: string,
): Promise<FriendRequestDto> {
  return api<FriendRequestDto>(
    `/friend-requests/${encodeURIComponent(requestId)}/cancel`,
    {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
    },
  );
}

export function listFriends(signal?: AbortSignal): Promise<FriendListResponse> {
  return api<FriendListResponse>("/friends", { signal });
}

export function removeFriend(friendUserId: string): Promise<void> {
  return api<void>(`/friends/${encodeURIComponent(friendUserId)}`, {
    method: "DELETE",
  });
}

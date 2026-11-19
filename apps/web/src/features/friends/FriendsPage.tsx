import { useMemo, useRef, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import type {
  FriendRequestDto,
  FriendRequestStatus,
  FriendSummary,
  UserSearchHit,
} from "@orbitspace/shared-types";
import {
  AlertCircle,
  CheckCircle2,
  Clock,
  Info,
  RefreshCw,
  Search,
  Send,
  UserCheck,
  UserMinus,
  UserPlus,
  UserX,
  Users,
  X,
} from "lucide-react";
import { useNavigate } from "react-router-dom";
import { queryClient } from "../../shared/api/access";
import { useAuth } from "../../shared/auth/session";
import { Button, Field, Loading } from "../../shared/ui/UiComponents";
import { formatDateTime, makeIdempotencyKey } from "../space/spaceApi";
import {
  acceptFriendRequest,
  cancelFriendRequest,
  friendRequestsKey,
  friendsErrorText,
  friendsKey,
  listFriendRequests,
  listFriends,
  rejectFriendRequest,
  removeFriend,
  searchUsers,
  sendFriendRequest,
} from "./friendsApi";
import "./friends.css";

type RequestTab = "incoming" | "outgoing";

const REQUEST_STATUS_LABELS: Record<FriendRequestStatus, string> = {
  PENDING: "待处理",
  ACCEPTED: "已同意",
  REJECTED: "已拒绝",
  CANCELLED: "已撤回",
};

function shortUserId(userId: string): string {
  return userId.length > 10 ? userId.slice(0, 8) : userId;
}

export function FriendsPage() {
  const navigate = useNavigate();
  const currentUserId = useAuth((s) => s.session?.userId ?? null);

  const [searchInput, setSearchInput] = useState("");
  const [submittedQuery, setSubmittedQuery] = useState("");
  const [searchHitsCache, setSearchHitsCache] = useState<Record<string, UserSearchHit>>({});
  const [requestTab, setRequestTab] = useState<RequestTab>("incoming");
  const [pendingRemoval, setPendingRemoval] = useState<FriendSummary | null>(null);
  const [actionNotice, setActionNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const sendIdemRef = useRef<Map<string, string>>(new Map());
  const requestActionIdemRef = useRef<Map<string, string>>(new Map());

  const friendsQuery = useQuery({
    queryKey: friendsKey,
    queryFn: ({ signal }) => listFriends(signal),
  });

  const requestsQuery = useQuery({
    queryKey: friendRequestsKey,
    queryFn: ({ signal }) => listFriendRequests("ALL", signal),
  });

  const searchQuery = useQuery({
    queryKey: ["friends", "user-search", submittedQuery] as const,
    enabled: submittedQuery.length > 0,
    queryFn: async ({ signal }) => {
      const res = await searchUsers(submittedQuery, 20, signal);
      setSearchHitsCache((prev) => {
        const next = { ...prev };
        for (const hit of res.items) {
          next[hit.userId] = hit;
        }
        return next;
      });
      return res;
    },
  });

  const friends = friendsQuery.data?.items ?? [];
  const allRequests = requestsQuery.data?.items ?? [];

  const friendsById = useMemo(() => {
    const map = new Map<string, FriendSummary>();
    for (const f of friends) {
      map.set(f.userId, f);
    }
    return map;
  }, [friends]);

  const incomingRequests = useMemo(
    () =>
      allRequests.filter(
        (req) => currentUserId !== null && req.recipientUserId === currentUserId,
      ),
    [allRequests, currentUserId],
  );

  const outgoingRequests = useMemo(
    () =>
      allRequests.filter(
        (req) => currentUserId !== null && req.requesterUserId === currentUserId,
      ),
    [allRequests, currentUserId],
  );

  const pendingIncomingByRequester = useMemo(() => {
    const map = new Map<string, FriendRequestDto>();
    for (const req of incomingRequests) {
      if (req.status === "PENDING") {
        map.set(req.requesterUserId, req);
      }
    }
    return map;
  }, [incomingRequests]);

  const pendingOutgoingTargets = useMemo(() => {
    const set = new Set<string>();
    for (const req of outgoingRequests) {
      if (req.status === "PENDING") {
        set.add(req.recipientUserId);
      }
    }
    return set;
  }, [outgoingRequests]);

  const pendingIncomingCount = useMemo(
    () => incomingRequests.filter((r) => r.status === "PENDING").length,
    [incomingRequests],
  );
  const pendingOutgoingCount = useMemo(
    () => outgoingRequests.filter((r) => r.status === "PENDING").length,
    [outgoingRequests],
  );

  const invalidateFriendData = () => {
    void queryClient.invalidateQueries({ queryKey: friendsKey });
    void queryClient.invalidateQueries({ queryKey: friendRequestsKey });
  };

  const sendRequestMutation = useMutation({
    mutationFn: ({ targetUserId, key }: { targetUserId: string; key: string }) =>
      sendFriendRequest({ targetUserId }, key),
    onSuccess: (_data, vars) => {
      sendIdemRef.current.delete(vars.targetUserId);
      setActionError(null);
      setActionNotice("好友申请已发送。");
      invalidateFriendData();
    },
    onError: (err) => {
      setActionNotice(null);
      setActionError(friendsErrorText(err));
    },
  });

  const acceptMutation = useMutation({
    mutationFn: ({ requestId, key }: { requestId: string; key: string }) =>
      acceptFriendRequest(requestId, key),
    onSuccess: (_data, vars) => {
      requestActionIdemRef.current.delete(`accept:${vars.requestId}`);
      setActionError(null);
      setActionNotice("已同意好友申请，现可向对方发起独立文件空投。");
      invalidateFriendData();
    },
    onError: (err) => {
      setActionNotice(null);
      setActionError(friendsErrorText(err));
    },
  });

  const rejectMutation = useMutation({
    mutationFn: ({ requestId, key }: { requestId: string; key: string }) =>
      rejectFriendRequest(requestId, key),
    onSuccess: (_data, vars) => {
      requestActionIdemRef.current.delete(`reject:${vars.requestId}`);
      setActionError(null);
      setActionNotice("已拒绝该好友申请。");
      invalidateFriendData();
    },
    onError: (err) => {
      setActionNotice(null);
      setActionError(friendsErrorText(err));
    },
  });

  const cancelMutation = useMutation({
    mutationFn: ({ requestId, key }: { requestId: string; key: string }) =>
      cancelFriendRequest(requestId, key),
    onSuccess: (_data, vars) => {
      requestActionIdemRef.current.delete(`cancel:${vars.requestId}`);
      setActionError(null);
      setActionNotice("已撤回发出的好友申请。");
      invalidateFriendData();
    },
    onError: (err) => {
      setActionNotice(null);
      setActionError(friendsErrorText(err));
    },
  });

  const removeMutation = useMutation({
    mutationFn: (friendUserId: string) => removeFriend(friendUserId),
    onSuccess: () => {
      setPendingRemoval(null);
      setActionError(null);
      setActionNotice("已移除好友关系（既有空间成员资格不受影响）。");
      invalidateFriendData();
    },
    onError: (err) => {
      setActionNotice(null);
      setActionError(friendsErrorText(err));
    },
  });

  const handleSearchSubmit = (event: FormEvent) => {
    event.preventDefault();
    const trimmed = searchInput.trim();
    if (!trimmed) return;
    setActionError(null);
    if (trimmed === submittedQuery) {
      void searchQuery.refetch();
    } else {
      setSubmittedQuery(trimmed);
    }
  };

  const getSendIdempotencyKey = (targetUserId: string): string => {
    let key = sendIdemRef.current.get(targetUserId);
    if (!key) {
      key = makeIdempotencyKey();
      sendIdemRef.current.set(targetUserId, key);
    }
    return key;
  };

  const getActionIdempotencyKey = (action: string, requestId: string): string => {
    const mapKey = `${action}:${requestId}`;
    let key = requestActionIdemRef.current.get(mapKey);
    if (!key) {
      key = makeIdempotencyKey();
      requestActionIdemRef.current.set(mapKey, key);
    }
    return key;
  };

  const resolvePeerDisplay = (userId: string): string => {
    const friend = friendsById.get(userId);
    if (friend) return friend.displayName;
    const cachedHit = searchHitsCache[userId];
    if (cachedHit) return `${cachedHit.displayName} (${cachedHit.emailMasked})`;
    return `用户 ${shortUserId(userId)}`;
  };

  const isAnyRequestMutating =
    acceptMutation.isPending || rejectMutation.isPending || cancelMutation.isPending;

  const displayedRequests = requestTab === "incoming" ? incomingRequests : outgoingRequests;

  return (
    <section className="content">
      <header className="page-head space-home-head">
        <div>
          <h1>好友</h1>
          <p className="subtitle">协作者发现、好友申请管理与独立文件空投联系人</p>
        </div>
        <div className="actions">
          <Button
            className="secondary"
            disabled={friendsQuery.isFetching || requestsQuery.isFetching}
            onClick={() => {
              void friendsQuery.refetch();
              void requestsQuery.refetch();
            }}
          >
            <RefreshCw size={15} /> 刷新
          </Button>
          <Button className="primary" onClick={() => navigate("/airdrops")}>
            <Send size={15} /> 前往文件空投
          </Button>
        </div>
      </header>

      <div className="friends-policy-banner" role="note">
        <Info size={17} />
        <div>
          <strong>权限边界说明：</strong>
          好友关系仅用于发现协作者、空间邀请与发起独立文件空投；<strong>好友关系绝不授予任何空间访问权限</strong>。单向移除好友仅解除空投联系人关系，不会改变任何既有空间成员资格。
        </div>
      </div>

      {actionNotice && (
        <div className="success" role="status" style={{ marginTop: 0, marginBottom: 16 }}>
          <div className="section-head" style={{ width: "100%", marginBottom: 0 }}>
            <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
              <CheckCircle2 size={16} /> {actionNotice}
            </span>
            <Button
              className="icon-button secondary"
              aria-label="关闭通知"
              onClick={() => setActionNotice(null)}
            >
              <X size={14} />
            </Button>
          </div>
        </div>
      )}

      {actionError && (
        <div className="alert" role="alert" style={{ marginBottom: 16 }}>
          <AlertCircle size={17} />
          <span>{actionError}</span>
        </div>
      )}

      <div className="friends-layout">
        <div className="friends-stack">
          {/* Active Friends List */}
          <section className="surface" aria-label="好友列表">
            <div className="section-head">
              <h2>
                <Users size={17} style={{ display: "inline", verticalAlign: "-3px", marginRight: 6 }} />
                我的好友 ({friends.length})
              </h2>
            </div>

            {pendingRemoval && (
              <div className="friends-confirm-box" role="alertdialog" aria-label="确认移除好友">
                <p>
                  <strong>确认移除好友「{pendingRemoval.displayName}」？</strong>
                  移除后将无法向对方发起新的独立文件空投；既有空间成员资格与历史空间数据不受影响。
                </p>
                <div className="actions">
                  <Button
                    className="secondary workbench-btn-xs"
                    disabled={removeMutation.isPending}
                    onClick={() => setPendingRemoval(null)}
                  >
                    取消
                  </Button>
                  <Button
                    className="danger workbench-btn-xs"
                    disabled={removeMutation.isPending}
                    onClick={() => removeMutation.mutate(pendingRemoval.userId)}
                  >
                    {removeMutation.isPending ? "移除中…" : "确认移除"}
                  </Button>
                </div>
              </div>
            )}

            {friendsQuery.isPending && <Loading label="正在加载好友列表…" />}

            {friendsQuery.isError && (
              <div className="alert" role="alert">
                <AlertCircle size={17} />
                <span>{friendsErrorText(friendsQuery.error)}</span>
                <Button
                  className="secondary workbench-btn-xs"
                  onClick={() => {
                    void friendsQuery.refetch();
                  }}
                >
                  重试
                </Button>
              </div>
            )}

            {friendsQuery.isSuccess && friends.length === 0 && (
              <div className="friends-empty-inline">
                当前尚无好友。可在右侧通过昵称或完整邮箱搜索用户并发送好友申请。
              </div>
            )}

            {friendsQuery.isSuccess && friends.length > 0 && (
              <ul className="friends-list">
                {friends.map((friend) => (
                  <li key={friend.userId} className="friends-row">
                    <div className="friends-row-info">
                      <div className="friends-row-title">
                        <strong>{friend.displayName}</strong>
                      </div>
                      <div className="friends-row-meta">
                        <code>{friend.userId}</code>
                        <span>·</span>
                        <span>
                          <Clock size={12} style={{ display: "inline", verticalAlign: "-2px", marginRight: 4 }} />
                          成为好友于 {formatDateTime(friend.becameFriendsAt)}
                        </span>
                      </div>
                    </div>
                    <div className="friends-row-actions">
                      <Button
                        className="secondary workbench-btn-xs"
                        onClick={() =>
                          navigate(`/airdrops?recipientId=${encodeURIComponent(friend.userId)}`)
                        }
                      >
                        <Send size={13} /> 发起空投
                      </Button>
                      <Button
                        className="secondary workbench-btn-xs"
                        disabled={removeMutation.isPending}
                        onClick={() => setPendingRemoval(friend)}
                      >
                        <UserMinus size={13} /> 移除好友
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {/* Incoming & Outgoing Friend Requests */}
          <section className="surface" aria-label="好友申请管理">
            <div className="section-head">
              <h2>好友申请</h2>
            </div>

            <div className="friends-tabs" role="tablist" aria-label="申请方向">
              <button
                type="button"
                role="tab"
                aria-selected={requestTab === "incoming"}
                className={`friends-tab-btn ${requestTab === "incoming" ? "active" : ""}`}
                onClick={() => setRequestTab("incoming")}
              >
                收到的申请
                {pendingIncomingCount > 0 && (
                  <span className="friends-count-badge">{pendingIncomingCount}</span>
                )}
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={requestTab === "outgoing"}
                className={`friends-tab-btn ${requestTab === "outgoing" ? "active" : ""}`}
                onClick={() => setRequestTab("outgoing")}
              >
                我发出的申请
                {pendingOutgoingCount > 0 && (
                  <span className="friends-count-badge">{pendingOutgoingCount}</span>
                )}
              </button>
            </div>

            {requestsQuery.isPending && <Loading label="正在加载好友申请记录…" />}

            {requestsQuery.isError && (
              <div className="alert" role="alert">
                <AlertCircle size={17} />
                <span>{friendsErrorText(requestsQuery.error)}</span>
              </div>
            )}

            {requestsQuery.isSuccess && displayedRequests.length === 0 && (
              <div className="friends-empty-inline">
                {requestTab === "incoming" ? "暂无收到的好友申请。" : "暂无发出的好友申请。"}
              </div>
            )}

            {requestsQuery.isSuccess && displayedRequests.length > 0 && (
              <ul className="friends-list">
                {displayedRequests.map((req) => {
                  const peerUserId =
                    requestTab === "incoming" ? req.requesterUserId : req.recipientUserId;
                  return (
                    <li key={req.requestId} className="friends-row">
                      <div className="friends-row-info">
                        <div className="friends-row-title">
                          <strong>{resolvePeerDisplay(peerUserId)}</strong>
                          <span className={`friends-status-pill status-${req.status.toLowerCase()}`}>
                            {REQUEST_STATUS_LABELS[req.status]}
                          </span>
                        </div>
                        <div className="friends-row-meta">
                          <code>{peerUserId}</code>
                          <span>·</span>
                          <span>更新于 {formatDateTime(req.updatedAt)}</span>
                        </div>
                      </div>

                      <div className="friends-row-actions">
                        {requestTab === "incoming" && req.status === "PENDING" && (
                          <>
                            <Button
                              className="primary workbench-btn-xs"
                              disabled={isAnyRequestMutating}
                              onClick={() =>
                                acceptMutation.mutate({
                                  requestId: req.requestId,
                                  key: getActionIdempotencyKey("accept", req.requestId),
                                })
                              }
                            >
                              <UserCheck size={13} /> 同意
                            </Button>
                            <Button
                              className="secondary workbench-btn-xs"
                              disabled={isAnyRequestMutating}
                              onClick={() =>
                                rejectMutation.mutate({
                                  requestId: req.requestId,
                                  key: getActionIdempotencyKey("reject", req.requestId),
                                })
                              }
                            >
                              <UserX size={13} /> 拒绝
                            </Button>
                          </>
                        )}

                        {requestTab === "outgoing" && req.status === "PENDING" && (
                          <Button
                            className="secondary workbench-btn-xs"
                            disabled={isAnyRequestMutating}
                            onClick={() =>
                              cancelMutation.mutate({
                                requestId: req.requestId,
                                key: getActionIdempotencyKey("cancel", req.requestId),
                              })
                            }
                          >
                            <X size={13} /> 撤回申请
                          </Button>
                        )}
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        </div>

        {/* User Search & Add Friend */}
        <section className="surface" aria-label="搜索用户并添加好友">
          <div className="section-head">
            <h2>搜索用户</h2>
            <Search size={16} />
          </div>
          <p className="muted" style={{ fontSize: "0.84rem", marginBottom: 14 }}>
            输入对方昵称关键字或已验证完整邮箱查找协作者（已脱敏展示邮箱，受频率限制保护）。
          </p>

          <form className="friends-search-form" onSubmit={handleSearchSubmit}>
            <Field
              label="昵称或邮箱"
              name="friendSearchQuery"
              maxLength={100}
              placeholder="例如：Alice 或 alice@example.com"
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
            />
            <Button
              type="submit"
              className="primary"
              disabled={searchQuery.isFetching || !searchInput.trim()}
            >
              <Search size={15} /> {searchQuery.isFetching ? "搜索中…" : "搜索"}
            </Button>
          </form>

          <div style={{ marginTop: 16 }}>
            {searchQuery.isPending && submittedQuery.length > 0 && (
              <Loading label="正在搜索用户…" />
            )}

            {searchQuery.isError && (
              <div className="alert" role="alert">
                <AlertCircle size={17} />
                <span>{friendsErrorText(searchQuery.error)}</span>
              </div>
            )}

            {searchQuery.isSuccess && searchQuery.data.items.length === 0 && (
              <div className="friends-empty-inline">
                未找到匹配「{submittedQuery}」的已验证用户。
              </div>
            )}

            {searchQuery.isSuccess && searchQuery.data.items.length > 0 && (
              <ul className="friends-list">
                {searchQuery.data.items.map((hit) => {
                  const isFriend = friendsById.has(hit.userId);
                  const pendingIncoming = pendingIncomingByRequester.get(hit.userId);
                  const hasPendingOutgoing = pendingOutgoingTargets.has(hit.userId);

                  return (
                    <li key={hit.userId} className="friends-row">
                      <div className="friends-row-info">
                        <div className="friends-row-title">
                          <strong>{hit.displayName}</strong>
                        </div>
                        <div className="friends-row-meta">
                          <span>{hit.emailMasked}</span>
                          <span>·</span>
                          <code>{hit.userId}</code>
                        </div>
                      </div>

                      <div className="friends-row-actions">
                        {isFriend ? (
                          <span className="friends-status-pill status-accepted">已是好友</span>
                        ) : pendingIncoming ? (
                          <Button
                            className="primary workbench-btn-xs"
                            disabled={isAnyRequestMutating}
                            onClick={() =>
                              acceptMutation.mutate({
                                requestId: pendingIncoming.requestId,
                                key: getActionIdempotencyKey("accept", pendingIncoming.requestId),
                              })
                            }
                          >
                            <UserCheck size={13} /> 同意对方申请
                          </Button>
                        ) : hasPendingOutgoing ? (
                          <span className="friends-status-pill status-pending">已发送申请</span>
                        ) : (
                          <Button
                            className="primary workbench-btn-xs"
                            disabled={sendRequestMutation.isPending}
                            onClick={() =>
                              sendRequestMutation.mutate({
                                targetUserId: hit.userId,
                                key: getSendIdempotencyKey(hit.userId),
                              })
                            }
                          >
                            <UserPlus size={13} /> 发送好友申请
                          </Button>
                        )}
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </section>
      </div>
    </section>
  );
}

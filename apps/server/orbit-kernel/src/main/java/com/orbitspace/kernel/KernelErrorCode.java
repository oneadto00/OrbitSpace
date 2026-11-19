package com.orbitspace.kernel;

public enum KernelErrorCode implements ErrorCode {
    AUTH_UNAUTHORIZED(401, "Authentication required."),
    AUTH_REVOKED(401, "The credential has been revoked."),
    CSRF_INVALID(403, "The CSRF token is missing or invalid."),
    RATE_LIMITED(429, "Too many requests."),
    MEMBERSHIP_EPOCH_INVALID(403, "The membership is no longer current."),
    SPACE_SINGLE_OWNER_REQUIRED(409, "The space must retain one active owner."),
    DEVICE_AUTH_PENDING(400, "Device approval is pending."),
    DEVICE_SLOW_DOWN(400, "Increase the device polling interval."),
    DEVICE_EXPIRED(400, "The device authorization request has expired."),
    WORKTREE_EPOCH_STALE(409, "The worktree epoch is stale."),
    CAPTURE_SEQ_STALE(409, "The capture sequence is stale."),
    IDEMPOTENCY_CONFLICT(409, "The idempotency key is bound to different input."),
    WS_TICKET_INVALID(401, "The connection ticket is invalid or expired."),
    WS_CURSOR_EXPIRED(410, "Reload room history at the supplied boundary."),
    RESOURCE_EXPIRED_OR_PURGED(410, "The resource is no longer available."),
    AUTH_FORBIDDEN(403, "Access is denied."),
    INVALID_REQUEST(400, "Invalid request."),
    RESOURCE_NOT_FOUND(404, "Resource not found."),
    INVITE_UNAVAILABLE(410, "The invitation is no longer available."),
    DEVICE_ACCESS_DENIED(400, "Device authorization was denied."),
    INTERNAL_ERROR(500, "The request could not be completed."),
    QUOTA_EXCEEDED(409, "Storage quota exceeded."),
    SHARE_REVOKED(403, "The file share has been revoked."),
    DOWNLOAD_LIMIT_EXCEEDED(403, "The download limit for this share has been reached."),
    NOT_FRIENDS_FOR_AIRDROP(403, "Independent file airdrop requires an active friendship."),
    DIFF_DATA_INSUFFICIENT(409, "Insufficient shared content to compute a deterministic diff."),
    REMOTE_URL_FORBIDDEN(400, "The remote Git URL is not permitted."),
    SSRF_TARGET_BLOCKED(403, "The upstream destination is blocked."),
    AI_DISABLED_IN_SPACE(403, "AI summaries are disabled in this space."),
    DOC_ANCHOR_UNPERSISTED(409, "The selection has no confirmed durable version."),
    COLLAB_REVOKED(403, "The collaboration session is no longer authorized.");

    private final int httpStatus;
    private final String defaultMessage;

    KernelErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}

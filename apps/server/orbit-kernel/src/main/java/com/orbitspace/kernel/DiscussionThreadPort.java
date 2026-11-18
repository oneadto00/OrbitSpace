package com.orbitspace.kernel;

import java.util.Optional;

/** Cross-module SPI binding code comments to unified main-room discussion threads. */
public interface DiscussionThreadPort {
    record ThreadSummary(
            String rootMessageId,
            String roomId,
            DiscussionStatus status,
            String conclusionMessageId,
            long version) {}

    record CodeCommentCardRequest(
            String spaceId,
            String projectId,
            String commentId,
            String authorUserId,
            long membershipEpoch,
            String clientMsgId,
            String purpose) {}

    Optional<ThreadSummary> findThread(String spaceId, String rootMessageId);

    ThreadSummary bindOrForwardCodeComment(CodeCommentCardRequest request);

    ThreadSummary syncConclusionStatus(
            String spaceId,
            String rootMessageId,
            String actorUserId,
            long membershipEpoch,
            DiscussionStatus status,
            String conclusionMessageId,
            String idempotencyKey);
}

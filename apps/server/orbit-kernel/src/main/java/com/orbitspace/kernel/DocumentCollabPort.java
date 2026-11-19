package com.orbitspace.kernel;

import java.util.List;

/** Java owns the live authorization and durable Yjs V1 boundary for internal collaboration hooks. */
public interface DocumentCollabPort {
    record Session(String documentId, String sessionId, String spaceId, String userId,
                   long membershipEpoch, SpaceRole role, boolean readOnly) {}

    record Snapshot(long throughBatchSeq, String update) {}
    record Update(long batchSeq, String update) {}

    /** Base64 document updates in ascending order after the optional snapshot. Null cursor ends the read. */
    record StatePage(Snapshot snapshot, List<Update> batches, long persistedBatchSeq, String nextCursor) {
        public StatePage {
            batches = List.copyOf(batches);
        }
    }

    record BatchReceipt(String documentId, String batchId, long persistedBatchSeq) {}
    record Access(boolean readOnly, long membershipEpoch) {}

    /** Atomically consume a short-lived ticket for this document and the current membership epoch. */
    Session authenticate(String documentId, String ticket);

    /** Reauthorize each page. A cursor binds the document, session and original durable high-water mark. */
    StatePage load(String documentId, String sessionId, String cursor);

    /**
     * Persist a base64 batch of updates from one session. Exclude awareness. Verify and promote the
     * immutable object before the transaction. Under the document lock recheck live edit permission,
     * epoch and lifecycle. Replay a matching document/batchId before comparing the expected sequence.
     * Reject changed input. Insert the reference and advance the sequence atomically, then acknowledge.
     */
    BatchReceipt storeBatch(String documentId, String sessionId, String batchId,
                            long expectedPersistedBatchSeq, String update);

    /** Resolve live access. COLLAB_REVOKED closes the connection, including after leave and rejoin. */
    Access revokeCheck(String documentId, String sessionId);
}

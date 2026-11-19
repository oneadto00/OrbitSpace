package com.orbitspace.kernel;

import java.util.List;

public interface AiSummaryGatewayPort {
    enum SourceKind { DOCUMENT_VERSION, PDF_VERSION, GIT_SNAPSHOT, CHAT_MESSAGE }

    record Source(SourceKind kind, String resourceId, String versionId, long resourceLifecycleVersion) {}

    record Request(SpaceAuthorizationPort.Membership actor, List<Source> sources, String idempotencyKey) {
        public Request {
            sources = List.copyOf(sources);
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("A summary needs at least one authorized source");
            }
        }
    }

    /**
     * The caller checks the space AI policy and the actor's read access to each immutable input.
     * Enqueue only this manifest, under the actor and membership epoch, in the caller's transaction.
     * Recheck policy, input versions, lifecycle and grants before dispatch and when accepting results.
     * Each summary point references its source versions. Document writes require a separate confirmed edit.
     *
     * @return the durable job ID
     */
    String enqueueSummary(Request request);
}

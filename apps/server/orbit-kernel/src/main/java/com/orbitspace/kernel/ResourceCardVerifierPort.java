package com.orbitspace.kernel;

import java.util.Objects;

public interface ResourceCardVerifierPort {
    /** targetId is the full Git OID for COMMIT_REF and the immutable snapshot ID for WIP_REF. */
    record Reference(MessageKind kind, String projectId, String targetId) {
        public Reference {
            if (kind != MessageKind.COMMIT_REF && kind != MessageKind.WIP_REF) {
                throw new IllegalArgumentException("Expected a Git resource reference");
            }
            Objects.requireNonNull(projectId);
            Objects.requireNonNull(targetId);
        }
    }

    record Preview(ResourceLifecycleState state, String label) {
        public Preview {
            Objects.requireNonNull(state);
            if (state == ResourceLifecycleState.ACTIVE) {
                if (label == null || label.isBlank()) {
                    throw new IllegalArgumentException("An available card needs a label");
                }
            } else if (label != null) {
                throw new IllegalArgumentException("A placeholder cannot contain preview content");
            }
        }
    }

    boolean supports(MessageKind kind);

    /** Check membership, epoch, project ownership and live resource state before returning preview text. */
    Preview verify(String userId, String spaceId, long membershipEpoch, Reference reference);
}

package com.orbitspace.kernel;

import java.util.Objects;

public interface ResourceCardVerifierPort {
    /**
     * Resource card reference. targetId is the full Git OID for COMMIT_REF, the immutable snapshot
     * ID for WIP_REF, the file or airdrop share ID for FILE_REF, and the space-scoped comment ID for
     * CODE_COMMENT_REF, DOC_REF and PDF_REF. Only Git references need a project ID.
     */
    record Reference(MessageKind kind, String projectId, String targetId, GrantType grantType) {
        public Reference {
            Objects.requireNonNull(kind);
            if (kind == MessageKind.TEXT || kind == MessageKind.SYSTEM) {
                throw new IllegalArgumentException("Expected a resource card reference kind");
            }
            if (kind == MessageKind.COMMIT_REF || kind == MessageKind.WIP_REF
                    || kind == MessageKind.CODE_COMMENT_REF) {
                Objects.requireNonNull(projectId);
            }
            Objects.requireNonNull(targetId);
        }

        public Reference(MessageKind kind, String projectId, String targetId) {
            this(kind, projectId, targetId, null);
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

package com.orbitspace.kernel;

import java.net.URI;

public interface ControlledEgressGitPort {
    /** Return a canonical HTTPS repository URI on an exact trusted host and port 443. */
    URI normalize(String repositoryUrl);

    /**
     * Recheck actor membership, project ownership, source lifecycle and the credential owner's grant.
     * Resolve the bound URL and secret by source ID. Queue by idempotency key in the caller's transaction.
     * Persist only a versioned credential reference in the job. All fetches use the isolated proxy with
     * checked numeric destinations, verified TLS and redirects disabled.
     *
     * @return the durable job ID
     */
    String enqueueSync(SpaceAuthorizationPort.Membership actor, String projectId, String sourceId,
                       long sourceLifecycleVersion, String idempotencyKey);
}

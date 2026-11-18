package com.orbitspace.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

public interface ObjectStoragePort {
    record StoredObject(String objectKey, long sizeBytes, String sha256) {}
    record Candidate(String objectKey, Instant unreferencedSince) {}
    record Candidates(List<Candidate> objects, String nextCursor) {
        public Candidates {
            objects = List.copyOf(objects);
        }
    }

    /** The caller owns the stream. Enforce the declared byte limit while streaming. */
    StoredObject writeTemporary(String uploadId, InputStream bytes, long sizeLimit) throws IOException;

    /** Check stored size and SHA-256 before promotion. Identical retries return the same immutable object. */
    StoredObject promote(String temporaryKey, long expectedSize, String expectedSha256) throws IOException;

    /** Call requireReadAccess before opening. Use an authorized business reference to resolve objectKey. */
    InputStream openRead(String objectKey, Runnable requireReadAccess) throws IOException;

    /** Bounded scan of temporary and immutable candidates. A null cursor starts or ends a scan. */
    Candidates scanUnreferenced(Instant cutoff, String cursor, int limit);

    /** Fence new references and recheck live references, retention and grace period before physical removal. */
    boolean deleteIfStillUnreferenced(Candidate candidate, Instant cutoff) throws IOException;
}

package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.AsyncJobProcessor;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.ObjectStoragePort;
import com.orbitspace.kernel.SpaceRole;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class GitWipPatchParseProcessor implements AsyncJobProcessor {
    public static final String JOB_TYPE = "GIT_WIP_PATCH_PARSE";

    record FileParseSummary(String path, int hunks, int addedLines, int deletedLines) {}

    record PatchParseSummary(
            String snapshotId,
            int fileCount,
            int totalHunks,
            int totalAddedLines,
            int totalDeletedLines,
            List<FileParseSummary> files) {}

    private record SnapshotLockRow(
            String projectId,
            String lifecycleState,
            long lifecycleVersion,
            String parseJobId,
            String parseState) {}

    private static final class MutableFileSummary {
        String path;
        int hunks;
        int addedLines;
        int deletedLines;

        MutableFileSummary(String path) {
            this.path = path;
        }
    }

    private final JdbcTemplate db;
    private final ObjectStoragePort objectStorage;
    private final ObjectMapper mapper;

    public GitWipPatchParseProcessor(
            JdbcTemplate db, ObjectStoragePort objectStorage, ObjectMapper mapper) {
        this.db = db;
        this.objectStorage = objectStorage;
        this.mapper = mapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public String process(AsyncJobPort.Attempt attempt) throws Exception {
        AsyncJobPort.Job job = attempt.job();
        PatchParseSummary summary;
        try (InputStream raw =
                objectStorage.openRead(
                        job.inputObjectKey(), () -> requireProcessingAccess(job))) {
            summary = parseUnifiedDiff(job.resourceId(), raw);
        }
        byte[] encoded = GitProjectUtils.toJson(mapper, summary).getBytes(StandardCharsets.UTF_8);
        String uploadId = "parse-result:" + job.jobId() + ":" + attempt.leaseId();
        ObjectStoragePort.StoredObject temp =
                objectStorage.writeTemporary(
                        uploadId,
                        new ByteArrayInputStream(encoded),
                        GitProjectUtils.MAX_PATCH_BYTES);
        ObjectStoragePort.StoredObject promoted =
                objectStorage.promote(temp.objectKey(), temp.sizeBytes(), temp.sha256());
        return promoted.objectKey();
    }

    @Override
    public AsyncJobPort.ResourceState lockAndCheck(AsyncJobPort.Job job) {
        SnapshotLockRow snap =
                db.query(
                                """
                                SELECT project_id, lifecycle_state, lifecycle_version, parse_job_id, parse_state
                                FROM wip_snapshots
                                WHERE id = ? AND space_id = ?
                                FOR UPDATE
                                """,
                                (rs, i) ->
                                        new SnapshotLockRow(
                                                rs.getString(1),
                                                rs.getString(2),
                                                rs.getLong(3),
                                                rs.getString(4),
                                                rs.getString(5)),
                                job.resourceId(),
                                job.spaceId())
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (snap == null) {
            return new AsyncJobPort.ResourceState(-1L, false);
        }
        boolean authorized =
                "ACTIVE".equals(snap.lifecycleState())
                        && job.jobId().equals(snap.parseJobId())
                        && isPendingParseState(snap.parseState())
                        && isProcessingGrantActive(job.spaceId(), snap.projectId(), job.actorId());
        return new AsyncJobPort.ResourceState(snap.lifecycleVersion(), authorized);
    }

    @Override
    public void attachResult(AsyncJobPort.Job job, String resultObjectKey) {
        boolean refExists =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(
                                    SELECT 1 FROM storage_object_references
                                    WHERE owner_type = 'WIP_PARSE_RESULT' AND owner_id = ? AND object_key = ?
                                )
                                """,
                                Boolean.class,
                                job.resourceId(),
                                resultObjectKey));
        if (!refExists) {
            db.update(
                    """
                    INSERT INTO storage_object_references(owner_type, owner_id, object_key)
                    VALUES ('WIP_PARSE_RESULT', ?, ?)
                    """,
                    job.resourceId(),
                    resultObjectKey);
        }
        db.update(
                """
                UPDATE wip_snapshots
                SET parse_state = 'SUCCEEDED', parse_result_object_key = ?
                WHERE id = ? AND space_id = ? AND parse_job_id = ? AND lifecycle_state = 'ACTIVE'
                """,
                resultObjectKey,
                job.resourceId(),
                job.spaceId(),
                job.jobId());
    }

    private void requireProcessingAccess(AsyncJobPort.Job job) {
        SnapshotLockRow snap =
                db.query(
                                """
                                SELECT project_id, lifecycle_state, lifecycle_version, parse_job_id, parse_state
                                FROM wip_snapshots
                                WHERE id = ? AND space_id = ?
                                """,
                                (rs, i) ->
                                        new SnapshotLockRow(
                                                rs.getString(1),
                                                rs.getString(2),
                                                rs.getLong(3),
                                                rs.getString(4),
                                                rs.getString(5)),
                                job.resourceId(),
                                job.spaceId())
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (snap == null
                || !"ACTIVE".equals(snap.lifecycleState())
                || snap.lifecycleVersion() != job.resourceLifecycleVersion()
                || !job.jobId().equals(snap.parseJobId())
                || !isPendingParseState(snap.parseState())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
        if (!isProcessingGrantActive(job.spaceId(), snap.projectId(), job.actorId())) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
    }

    private boolean isProcessingGrantActive(String spaceId, String projectId, String actorId) {
        boolean projectActive =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(
                                    SELECT 1 FROM git_projects p
                                    JOIN spaces s ON s.id = p.space_id
                                    WHERE p.id = ? AND p.space_id = ? AND p.status = 'ACTIVE' AND s.status = 'ACTIVE'
                                )
                                """,
                                Boolean.class,
                                projectId,
                                spaceId));
        if (!projectActive) {
            return false;
        }
        List<String> roles =
                db.queryForList(
                        """
                        SELECT m.role
                        FROM space_members m
                        JOIN spaces s ON s.id = m.space_id
                        WHERE m.space_id = ? AND m.user_id = ? AND m.status = 'ACTIVE' AND s.status = 'ACTIVE'
                        """,
                        String.class,
                        spaceId,
                        actorId);
        if (roles.isEmpty()) {
            return false;
        }
        return SpaceRole.valueOf(roles.getFirst()).atLeast(SpaceRole.MEMBER);
    }

    private static boolean isPendingParseState(String parseState) {
        return "PENDING".equals(parseState)
                || "RUNNING".equals(parseState)
                || "RETRY_WAIT".equals(parseState);
    }

    static PatchParseSummary parseUnifiedDiff(String snapshotId, InputStream raw)
            throws IOException {
        List<MutableFileSummary> files = new ArrayList<>();
        MutableFileSummary current = null;
        boolean inHunk = false;

        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("diff --git ")) {
                    current = new MutableFileSummary(extractDiffGitPath(line));
                    files.add(current);
                    inHunk = false;
                } else if (line.startsWith("--- ")) {
                    inHunk = false;
                    if (current == null) {
                        current = new MutableFileSummary("unknown");
                        files.add(current);
                    }
                    String minusPath = extractHeaderPath(line.substring(4), "a/");
                    if (minusPath != null) {
                        current.path = minusPath;
                    }
                } else if (line.startsWith("+++ ")) {
                    inHunk = false;
                    if (current == null) {
                        current = new MutableFileSummary("unknown");
                        files.add(current);
                    }
                    String plusPath = extractHeaderPath(line.substring(4), "b/");
                    if (plusPath != null) {
                        current.path = plusPath;
                    }
                } else if (line.startsWith("@@ ")) {
                    if (current == null) {
                        current = new MutableFileSummary("unknown");
                        files.add(current);
                    }
                    current.hunks++;
                    inHunk = true;
                } else if (inHunk && current != null) {
                    if (line.startsWith("+")) {
                        current.addedLines++;
                    } else if (line.startsWith("-")) {
                        current.deletedLines++;
                    } else if (!line.isEmpty()
                            && !line.startsWith(" ")
                            && !line.startsWith("\\")) {
                        inHunk = false;
                    }
                }
            }
        }

        List<FileParseSummary> resultFiles = new ArrayList<>(files.size());
        int totalHunks = 0;
        int totalAdded = 0;
        int totalDeleted = 0;
        for (MutableFileSummary f : files) {
            resultFiles.add(new FileParseSummary(f.path, f.hunks, f.addedLines, f.deletedLines));
            totalHunks += f.hunks;
            totalAdded += f.addedLines;
            totalDeleted += f.deletedLines;
        }
        return new PatchParseSummary(
                snapshotId,
                resultFiles.size(),
                totalHunks,
                totalAdded,
                totalDeleted,
                List.copyOf(resultFiles));
    }

    private static String extractDiffGitPath(String headerLine) {
        String rest = headerLine.substring("diff --git ".length()).strip();
        int bIdx = rest.lastIndexOf(" b/");
        if (bIdx > 0) {
            return rest.substring(bIdx + 3);
        }
        int spaceIdx = rest.lastIndexOf(' ');
        if (spaceIdx > 0) {
            String candidate = rest.substring(spaceIdx + 1);
            if (!"/dev/null".equals(candidate)) {
                return stripPrefix(candidate, "b/");
            }
            return stripPrefix(rest.substring(0, spaceIdx), "a/");
        }
        return rest.isEmpty() ? "unknown" : rest;
    }

    private static String extractHeaderPath(String rawPath, String prefix) {
        String candidate = rawPath;
        int tabIdx = candidate.indexOf('\t');
        if (tabIdx >= 0) {
            candidate = candidate.substring(0, tabIdx);
        }
        candidate = candidate.strip();
        if (candidate.isEmpty() || "/dev/null".equals(candidate)) {
            return null;
        }
        return stripPrefix(candidate, prefix);
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }
}

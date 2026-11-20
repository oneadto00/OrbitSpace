package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.GitTrustState;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.modules.gitproject.GitProjectDtos.CommitMetadataDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.CommitNodeDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.CommitObservationDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ObservationSourceDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ObservedRefDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.RefObservationDto;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class GitDagObservationStore {
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    record CommitPage(List<CommitNodeDto> commits, String nextCursor) {}

    record CommitLookup(CommitNodeDto commit, String selectedParentOid) {}

    private record CommitDbRow(long id, String objectFormat, String oid, GitTrustState trustState) {}

    private record ObservationDbRow(
            String id,
            String oid,
            String deviceId,
            String userId,
            Instant observedAt,
            GitTrustState trustState,
            String subject,
            String authorName,
            String authorEmail,
            Instant authoredAt,
            List<String> parents) {}

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    GitDagObservationStore(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    void persistObservations(
            String projectId,
            String objectFormat,
            String deviceId,
            String userId,
            Instant observedAt,
            List<CommitMetadataDto> commits,
            List<RefObservationDto> refs) {
        Timestamp observedTs = GitProjectUtils.toMysqlTimestamp(observedAt);

        if (commits != null && !commits.isEmpty()) {
            List<CommitMetadataDto> sortedCommits = new ArrayList<>(commits);
            sortedCommits.sort(Comparator.comparing(CommitMetadataDto::oid));
            for (CommitMetadataDto commit : sortedCommits) {
                persistSingleCommit(
                        projectId, objectFormat, deviceId, userId, observedTs, commit, null, null);
            }
        }

        if (refs != null && !refs.isEmpty()) {
            List<RefObservationDto> sortedRefs = new ArrayList<>(refs);
            sortedRefs.sort(Comparator.comparing(RefObservationDto::name));
            for (RefObservationDto ref : sortedRefs) {
                db.update(
                        """
                        INSERT INTO git_refs(project_id, device_id, user_id, ref_name, target_oid, observed_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            target_oid = IF(VALUES(observed_at) >= observed_at, VALUES(target_oid), target_oid),
                            observed_at = GREATEST(observed_at, VALUES(observed_at))
                        """,
                        projectId,
                        deviceId,
                        userId,
                        ref.name(),
                        ref.targetOid(),
                        GitProjectUtils.toMysqlTimestamp(ref.observedAt()));
            }
        }
    }

    void persistVerifiedCommit(String projectId, String format, String sourceId, String owner,
                               Timestamp syncedAt, CommitMetadataDto commit, String jobId) {
        persistSingleCommit(projectId, format, null, owner, syncedAt, commit, sourceId, jobId);
    }

    private void persistSingleCommit(
            String projectId,
            String objectFormat,
            String deviceId,
            String userId,
            Timestamp observedTs,
            CommitMetadataDto commit, String remoteSourceId, String reachableJob) {
        db.update(
                """
                INSERT INTO git_commits(project_id, object_format, oid, trust_state, first_seen_at)
                VALUES (?, ?, ?, 'DECLARED', CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE id = id
                """,
                projectId,
                objectFormat,
                commit.oid());

        CommitDbRow commitRow =
                db.queryForObject(
                        """
                        SELECT id, object_format, oid, trust_state
                        FROM git_commits
                        WHERE project_id = ? AND object_format = ? AND oid = ?
                        FOR UPDATE
                        """,
                        (rs, i) ->
                                new CommitDbRow(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        GitTrustState.valueOf(rs.getString(4))),
                        projectId,
                        objectFormat,
                        commit.oid());

        boolean hasParents =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM git_commit_parents WHERE commit_id = ?)",
                                Boolean.class,
                                commitRow.id()));
        if (!hasParents && !commit.parents().isEmpty()) {
            for (int i = 0; i < commit.parents().size(); i++) {
                db.update(
                        """
                        INSERT INTO git_commit_parents(commit_id, parent_order, parent_oid)
                        VALUES (?, ?, ?)
                        """,
                        commitRow.id(),
                        i,
                        commit.parents().get(i));
            }
        }

        List<ObservationDbRow> existing =
                db.query(
                        """
                        SELECT id, oid, COALESCE(device_id,CONCAT('remote:',remote_source_id)), user_id, observed_at, trust_state,
                               subject, author_name, author_email, authored_at, parents_json
                        FROM git_object_observations
                        WHERE project_id = ? AND object_format = ? AND oid = ?
                        FOR UPDATE
                        """,
                        (rs, i) ->
                                new ObservationDbRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getTimestamp(5).toInstant(),
                                        GitTrustState.valueOf(rs.getString(6)),
                                        rs.getString(7),
                                        rs.getString(8),
                                        rs.getString(9),
                                        rs.getTimestamp(10).toInstant(),
                                        parseParentsJson(rs.getString(11))),
                        projectId,
                        objectFormat,
                        commit.oid());

        Instant incomingAuthoredAt = commit.authoredAt().truncatedTo(ChronoUnit.MICROS);
        boolean conflict = commitRow.trustState() == GitTrustState.CONFLICT;
        ObservationDbRow sameSourceRow = null;
        for (ObservationDbRow obs : existing) {
            if (obs.deviceId().equals(remoteSourceId == null ? deviceId : "remote:" + remoteSourceId) && obs.userId().equals(userId)) {
                sameSourceRow = obs;
            }
            if (observationDiffers(obs, commit, incomingAuthoredAt)) {
                conflict = true;
            }
        }

        if (conflict) {
            if (commitRow.trustState() != GitTrustState.CONFLICT) {
                db.update(
                        "UPDATE git_commits SET trust_state = 'CONFLICT' WHERE id = ?",
                        commitRow.id());
            }
            db.update(
                    """
                    UPDATE git_object_observations
                    SET trust_state = 'CONFLICT'
                    WHERE project_id = ? AND object_format = ? AND oid = ? AND trust_state <> 'CONFLICT'
                    """,
                    projectId,
                    objectFormat,
                    commit.oid());
        }

        if (!conflict && remoteSourceId != null && commitRow.trustState() == GitTrustState.DECLARED) {
            db.update("UPDATE git_commits SET trust_state='VERIFIED' WHERE id=?", commitRow.id());
        }
        String obsTrust = conflict ? "CONFLICT" : (remoteSourceId == null ? "DECLARED" : "VERIFIED");
        if (sameSourceRow == null) {
            String parentsJson = GitProjectUtils.toJson(mapper, commit.parents());
            db.update(
                    """
                    INSERT INTO git_object_observations(
                        id, project_id, object_format, oid, device_id, user_id, observed_at,
                        trust_state, subject, author_name, author_email, authored_at, parents_json,
                        remote_source_id, last_reachable_job_id
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?)
                    """,
                    GitProjectUtils.newId(),
                    projectId,
                    objectFormat,
                    commit.oid(),
                    deviceId,
                    userId,
                    observedTs,
                    obsTrust,
                    commit.subject(),
                    commit.authorName(),
                    commit.authorEmail(),
                    Timestamp.from(incomingAuthoredAt),
                    parentsJson, remoteSourceId, reachableJob);
        } else {
            db.update(
                    """
                    UPDATE git_object_observations
                    SET observed_at = GREATEST(observed_at, ?), trust_state = ?, last_reachable_job_id = ?
                    WHERE id = ?
                    """,
                    observedTs,
                    obsTrust,
                    reachableJob, sameSourceRow.id());
        }
    }

    private static boolean observationDiffers(
            ObservationDbRow obs, CommitMetadataDto commit, Instant incomingAuthoredAt) {
        return !obs.subject().equals(commit.subject())
                || !obs.authorName().equals(commit.authorName())
                || (obs.authorEmail() != null && commit.authorEmail() != null && !obs.authorEmail().equals(commit.authorEmail()))
                || !obs.authoredAt().truncatedTo(ChronoUnit.MICROS).equals(incomingAuthoredAt)
                || !obs.parents().equals(commit.parents());
    }

    CommitPage loadCommitNodesPage(String projectId, Long beforeCommitDbId, int limit) {
        List<CommitDbRow> rows;
        if (beforeCommitDbId == null) {
            rows =
                    db.query(
                            """
                            SELECT id, object_format, oid, trust_state
                            FROM git_commits
                            WHERE project_id = ?
                            ORDER BY id DESC
                            LIMIT ?
                            """,
                            (rs, i) ->
                                    new CommitDbRow(
                                            rs.getLong(1),
                                            rs.getString(2),
                                            rs.getString(3),
                                            GitTrustState.valueOf(rs.getString(4))),
                            projectId,
                            limit + 1);
        } else {
            rows =
                    db.query(
                            """
                            SELECT id, object_format, oid, trust_state
                            FROM git_commits
                            WHERE project_id = ? AND id < ?
                            ORDER BY id DESC
                            LIMIT ?
                            """,
                            (rs, i) ->
                                    new CommitDbRow(
                                            rs.getLong(1),
                                            rs.getString(2),
                                            rs.getString(3),
                                            GitTrustState.valueOf(rs.getString(4))),
                            projectId,
                            beforeCommitDbId,
                            limit + 1);
        }

        if (rows.isEmpty()) {
            return new CommitPage(List.of(), null);
        }
        boolean hasMore = rows.size() > limit;
        List<CommitDbRow> pageRows = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? Long.toString(pageRows.getLast().id()) : null;

        List<String> oids = pageRows.stream().map(CommitDbRow::oid).toList();
        Map<String, List<ObservationDbRow>> obsByOid = loadObservationsByOid(projectId, oids);
        Set<String> allParentOids = new HashSet<>();
        for (List<ObservationDbRow> list : obsByOid.values()) {
            for (ObservationDbRow obs : list) {
                allParentOids.addAll(obs.parents());
            }
        }
        Set<String> knownParentOids = findKnownCommitOids(projectId, allParentOids);

        List<CommitNodeDto> nodes = new ArrayList<>(pageRows.size());
        for (CommitDbRow row : pageRows) {
            List<ObservationDbRow> obsList = obsByOid.getOrDefault(row.oid(), List.of());
            if (obsList.isEmpty()) {
                continue;
            }
            nodes.add(toCommitNode(row, obsList, knownParentOids));
        }
        return new CommitPage(List.copyOf(nodes), nextCursor);
    }

    CommitLookup loadSingleCommit(
            String projectId, String commitOid, String requestedParentOid) {
        CommitDbRow row =
                db.query(
                                """
                                SELECT id, object_format, oid, trust_state
                                FROM git_commits
                                WHERE project_id = ? AND oid = ?
                                LIMIT 1
                                """,
                                (rs, i) ->
                                        new CommitDbRow(
                                                rs.getLong(1),
                                                rs.getString(2),
                                                rs.getString(3),
                                                GitTrustState.valueOf(rs.getString(4))),
                                projectId,
                                commitOid)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));

        List<String> storedParents =
                db.queryForList(
                        """
                        SELECT parent_oid
                        FROM git_commit_parents
                        WHERE commit_id = ?
                        ORDER BY parent_order ASC
                        """,
                        String.class,
                        row.id());

        List<ObservationDbRow> observations =
                loadObservationsByOid(projectId, List.of(row.oid()))
                        .getOrDefault(row.oid(), List.of());
        if (observations.isEmpty()) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }

        Set<String> candidateParents = new LinkedHashSet<>(storedParents);
        for (ObservationDbRow obs : observations) {
            candidateParents.addAll(obs.parents());
        }

        String selectedParentOid;
        if (requestedParentOid != null) {
            if (!GitProjectUtils.isValidOid(row.objectFormat(), requestedParentOid)
                    || !candidateParents.contains(requestedParentOid)) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            selectedParentOid = requestedParentOid;
        } else {
            selectedParentOid = storedParents.isEmpty() ? null : storedParents.getFirst();
        }

        Set<String> knownParentOids = findKnownCommitOids(projectId, candidateParents);
        CommitNodeDto node = toCommitNode(row, observations, knownParentOids);
        return new CommitLookup(node, selectedParentOid);
    }

    List<ObservedRefDto> loadObservedRefs(String projectId) {
        return db.query(
                """
                SELECT ref_name, target_oid, observed_at, device_id, user_id
                FROM git_refs
                WHERE project_id = ?
                UNION ALL
                SELECT r.ref_name,r.target_oid,r.synced_at,CONCAT('remote:',s.id),s.owner_user_id
                FROM git_remote_refs r JOIN git_sources s ON s.id=r.source_id
                WHERE s.project_id=? AND s.status='ACTIVE'
                ORDER BY observed_at DESC, ref_name ASC, device_id ASC, user_id ASC
                """,
                (rs, i) -> {
                    Instant observedAt = rs.getTimestamp(3).toInstant();
                    return new ObservedRefDto(
                            new RefObservationDto(rs.getString(1), rs.getString(2), observedAt),
                            new ObservationSourceDto(rs.getString(4), rs.getString(5), observedAt));
                },
                projectId, projectId);
    }

    private Map<String, List<ObservationDbRow>> loadObservationsByOid(
            String projectId, List<String> oids) {
        if (oids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(oids.size(), "?"));
        List<Object> params = new ArrayList<>(oids.size() + 1);
        params.add(projectId);
        params.addAll(oids);

        List<ObservationDbRow> rows =
                db.query(
                        """
                        SELECT id, oid, COALESCE(device_id,CONCAT('remote:',remote_source_id)), user_id, observed_at, trust_state,
                               subject, author_name, author_email, authored_at, parents_json
                        FROM git_object_observations
                        WHERE project_id = ? AND oid IN ("""
                                + placeholders
                                + """
                                )
                        ORDER BY observed_at DESC, id ASC
                        """,
                        (rs, i) ->
                                new ObservationDbRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getTimestamp(5).toInstant(),
                                        GitTrustState.valueOf(rs.getString(6)),
                                        rs.getString(7),
                                        rs.getString(8),
                                        rs.getString(9),
                                        rs.getTimestamp(10).toInstant(),
                                        parseParentsJson(rs.getString(11))),
                        params.toArray());

        Map<String, List<ObservationDbRow>> grouped = new HashMap<>();
        for (ObservationDbRow row : rows) {
            grouped.computeIfAbsent(row.oid(), k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private Set<String> findKnownCommitOids(String projectId, Set<String> parentOids) {
        if (parentOids.isEmpty()) {
            return Set.of();
        }
        List<String> list = new ArrayList<>(parentOids);
        Set<String> known = new HashSet<>();
        for (int start = 0; start < list.size(); start += 500) {
            List<String> batch = list.subList(start, Math.min(start + 500, list.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            List<Object> params = new ArrayList<>(batch.size() + 1);
            params.add(projectId);
            params.addAll(batch);
            known.addAll(
                    db.queryForList(
                            "SELECT oid FROM git_commits WHERE project_id = ? AND oid IN ("
                                    + placeholders
                                    + ")",
                            String.class,
                            params.toArray()));
        }
        return known;
    }

    private static CommitNodeDto toCommitNode(
            CommitDbRow row, List<ObservationDbRow> obsList, Set<String> knownParentOids) {
        List<CommitObservationDto> observations = new ArrayList<>(obsList.size());
        Set<String> missingParents = new LinkedHashSet<>();
        for (ObservationDbRow obs : obsList) {
            observations.add(
                    new CommitObservationDto(
                            new ObservationSourceDto(
                                    obs.deviceId(), obs.userId(), obs.observedAt()),
                            obs.trustState(),
                            new CommitMetadataDto(
                                    obs.oid(),
                                    obs.parents(),
                                    obs.subject(),
                                    obs.authorName(),
                                    obs.authorEmail(),
                                    obs.authoredAt())));
            for (String p : obs.parents()) {
                if (!knownParentOids.contains(p)) {
                    missingParents.add(p);
                }
            }
        }
        return new CommitNodeDto(
                row.oid(),
                row.trustState(),
                List.copyOf(observations),
                List.copyOf(missingParents));
    }

    private List<String> parseParentsJson(String json) {
        try {
            return mapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Corrupt parents_json in git_object_observations", ex);
        }
    }
}

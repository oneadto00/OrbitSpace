package com.orbitspace.modules.gitproject;

import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.SpaceRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

class GitRemoteComparisonService {
    record Comparison(String remoteRef, String remoteOid, Instant syncedAt, String localReachability,
                      String historyStatus, Integer ahead, Integer behind) {}
    private record Remote(String oid, Instant syncedAt, String reachability) {}
    private record Edge(String oid, String parent, boolean conflict) {}
    private record Walk(Set<String> commits, boolean complete) {}
    private final GitRemoteSourceService sources;
    private final JdbcTemplate db;
    GitRemoteComparisonService(GitRemoteSourceService sources, JdbcTemplate db) {
        this.sources = sources;
        this.db = db;
    }

    @Transactional
    public Comparison compare(String space, String project, String sourceId, String actor, long epoch, String localOid, String ref) {
        sources.authorize(space, project, actor, epoch, SpaceRole.VIEWER);
        if (!GitProjectUtils.isAnyValidOid(localOid) || ref == null || ref.length() > 300) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        Remote remote = db.query("""
                SELECT r.target_oid,r.synced_at, CASE
                  WHEN obs.last_reachable_job_id=s.last_synced_job_id THEN 'REACHABLE'
                  WHEN obs.id IS NOT NULL AND s.last_history_complete THEN 'NO_LONGER_REACHABLE'
                  ELSE 'UNKNOWN' END
                FROM git_sources s JOIN git_remote_refs r ON r.source_id=s.id
                LEFT JOIN git_object_observations obs ON obs.remote_source_id=s.id AND obs.oid=?
                WHERE s.space_id=? AND s.project_id=? AND s.id=? AND s.status='ACTIVE' AND r.ref_name=?
                """, (rs, i) -> new Remote(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3)),
                localOid, space, project, sourceId, ref).stream().findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
        Walk local = ancestors(project, localOid);
        Walk upstream = ancestors(project, remote.oid());
        boolean complete = local.complete() && upstream.complete() && localOid.length() == remote.oid().length();
        int common = complete ? (int) local.commits().stream().filter(upstream.commits()::contains).count() : 0;
        return new Comparison(ref, remote.oid(), remote.syncedAt(), remote.reachability(),
                complete ? "COMPLETE" : "HISTORY_INCOMPLETE", complete ? local.commits().size()-common : null,
                complete ? upstream.commits().size()-common : null);
    }

    private Walk ancestors(String project, String tip) {
        Set<String> visited = new HashSet<>();
        Set<String> pending = new LinkedHashSet<>();
        pending.add(tip);
        while (!pending.isEmpty()) {
            var batch = pending.stream().limit(256).toList();
            pending.removeAll(batch);
            visited.addAll(batch);
            if (visited.size() > 50000) return new Walk(visited, false);
            var params = new ArrayList<Object>();
            params.add(project);
            params.addAll(batch);
            String marks = String.join(",", Collections.nCopies(batch.size(),"?"));
            var edges = db.query("""
                    SELECT c.oid,p.parent_oid,c.trust_state='CONFLICT'
                    FROM git_commits c LEFT JOIN git_commit_parents p ON p.commit_id=c.id
                    WHERE c.project_id=? AND c.oid IN (
                    """ + marks + ")", (rs, i) -> new Edge(rs.getString(1), rs.getString(2), rs.getBoolean(3)), params.toArray());
            var found = new HashSet<String>();
            for (var edge : edges) {
                if (edge.conflict()) return new Walk(visited, false);
                found.add(edge.oid());
                if (edge.parent() != null && !visited.contains(edge.parent())) pending.add(edge.parent());
            }
            if (found.size() != batch.size()) return new Walk(visited, false);
        }
        return new Walk(visited, true);
    }
}

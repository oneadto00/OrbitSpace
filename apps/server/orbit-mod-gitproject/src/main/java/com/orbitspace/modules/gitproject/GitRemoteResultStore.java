package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.ObjectStoragePort;
import com.orbitspace.modules.gitproject.GitProjectDtos.CommitMetadataDto;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Only metadata survives the isolated fetch. Neither an OID nor a parsed commit is proof of a retained tree. */
class GitRemoteResultStore implements AsyncJobPort.ResultTarget {
    private static final int MAX_RESULT_BYTES = 16 * 1024 * 1024;
    private static final Pattern AUTHOR = Pattern.compile("(.+) <([^<>]*)> (-?[0-9]+) [+-][0-9]{4}");
    private record Binding(String project, String owner) {}
    private final GitRemoteSourceService sources;
    private final JdbcTemplate db;
    private final ObjectStoragePort storage;
    private final ObjectMapper mapper;
    private final GitDagObservationStore observations;

    GitRemoteResultStore(GitRemoteSourceService sources, JdbcTemplate db, ObjectStoragePort storage, ObjectMapper mapper) {
        this.sources = sources;
        this.db = db;
        this.storage = storage;
        this.mapper = mapper;
        this.observations = new GitDagObservationStore(db, mapper);
    }

    @Override
    public AsyncJobPort.ResourceState lockAndCheck(AsyncJobPort.Job job) {
        return sources.lockAndCheck(job);
    }

    @Override
    public void attachResult(AsyncJobPort.Job job, String resultObjectKey) {
        JsonNode report;
        try (InputStream input = storage.openRead(resultObjectKey, () -> {
            if (!lockAndCheck(job).processingAuthorized()) throw new DomainException(KernelErrorCode.AUTH_REVOKED);
        })) {
            byte[] bytes = input.readNBytes(MAX_RESULT_BYTES + 1);
            if (bytes.length > MAX_RESULT_BYTES) throw invalid();
            report = mapper.readTree(bytes);
        } catch (IOException ex) {
            throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
        }
        if (report == null || !report.isObject()) throw invalid();
        String format = report.path("objectFormat").asText();
        if (!List.of("sha1","sha256").contains(format)) throw invalid();
        if (!report.path("completeHistory").isBoolean()) throw invalid();
        boolean complete = report.path("completeHistory").booleanValue();
        Map<String, CommitMetadataDto> commits = new TreeMap<>();
        for (JsonNode node : array(report, "commits", 10000)) {
            String oid = node.path("oid").asText();
            if (!GitProjectUtils.isValidOid(format, oid) || !node.path("rawBase64").isTextual()) throw invalid();
            CommitMetadataDto commit = verified(format, oid, node.path("rawBase64"));
            if (commits.putIfAbsent(oid, commit) != null) throw invalid();
        }
        Map<String, String> refs = new TreeMap<>();
        for (JsonNode node : array(report, "refs", 10000)) {
            String name = node.path("name").asText();
            String oid = node.path("oid").asText();
            if (!name.startsWith("refs/") || name.length() > 300 || name.chars().anyMatch(Character::isISOControl)
                    || !GitProjectUtils.isValidOid(format, oid) || refs.putIfAbsent(name, oid) != null) throw invalid();
        }
        // Only a full traversal of the fetched refs is sufficient evidence of lost reachability at this source.
        var pending = new ArrayDeque<>(refs.values());
        var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String oid = pending.removeFirst();
            if (!visited.add(oid)) continue;
            var commit = commits.get(oid);
            if (commit == null) {
                if (complete) throw invalid();
            } else {
                pending.addAll(commit.parents());
            }
        }
        if (!visited.containsAll(commits.keySet())) throw invalid();
        Binding binding = db.queryForObject("SELECT project_id,owner_user_id FROM git_sources WHERE id=? AND space_id=?",
                (rs, i) -> new Binding(rs.getString(1), rs.getString(2)), job.resourceId(), job.spaceId());
        Timestamp now = Timestamp.from(Instant.now());
        for (CommitMetadataDto commit : commits.values()) {
            observations.persistVerifiedCommit(binding.project(), format, job.resourceId(), binding.owner(), now, commit, job.jobId());
        }
        if (complete) db.update("DELETE FROM git_remote_refs WHERE source_id=?", job.resourceId());
        for (var ref : refs.entrySet()) {
            db.update("""
                    INSERT INTO git_remote_refs(source_id,ref_name,target_oid,synced_at) VALUES (?,?,?,?)
                    ON DUPLICATE KEY UPDATE target_oid=VALUES(target_oid),synced_at=VALUES(synced_at)
                    """, job.resourceId(), ref.getKey(), ref.getValue(), now);
        }
        db.update("""
                UPDATE git_sources SET last_synced_job_id=?,last_synced_at=?,last_history_complete=?,
                    active_job_id=NULL,active_actor_epoch=NULL WHERE id=?
                """, job.jobId(), now, complete, job.resourceId());
    }

    private static JsonNode array(JsonNode report, String name, int max) {
        JsonNode node = report.path(name);
        if (!node.isArray() || node.size() > max) throw invalid();
        return node;
    }

    static CommitMetadataDto verified(String format, String oid, JsonNode encoded) {
        try {
            byte[] raw = encoded.binaryValue();
            MessageDigest hash = MessageDigest.getInstance(format.equals("sha256") ? "SHA-256" : "SHA-1");
            hash.update(("commit " + raw.length + "\0").getBytes(StandardCharsets.US_ASCII));
            if (!HexFormat.of().formatHex(hash.digest(raw)).equals(oid)) throw invalid();

            // Locate headers in a single-byte encoding first. The optional Git encoding header covers the message.
            String latin = new String(raw, StandardCharsets.ISO_8859_1);
            int boundary = latin.indexOf("\n\n");
            if (boundary < 0) throw invalid();
            String[] headers = latin.substring(0, boundary).split("\n");
            if (!headers[0].startsWith("tree ") || !GitProjectUtils.isValidOid(format, headers[0].substring(5))) throw invalid();
            Charset encoding = StandardCharsets.UTF_8;
            String author = null;
            List<String> parents = new ArrayList<>();
            for (String header : headers) {
                if (header.startsWith("parent ")) {
                    String parent = header.substring(7);
                    if (!GitProjectUtils.isValidOid(format, parent)) throw invalid();
                    parents.add(parent);
                } else if (header.startsWith("author ")) {
                    if (author != null) throw invalid();
                    author = header.substring(7);
                } else if (header.startsWith("encoding ")) {
                    encoding = Charset.forName(header.substring(9));
                }
            }
            if (author == null) throw invalid();
            var identity = AUTHOR.matcher(new String(author.getBytes(StandardCharsets.ISO_8859_1), encoding));
            if (!identity.matches()) throw invalid();
            String message = new String(raw, boundary+2, raw.length-boundary-2, encoding);
            StringBuilder subject = new StringBuilder();
            for (String line : message.split("\n")) {
                String part = line.stripTrailing();
                if (part.isEmpty()) {
                    if (!subject.isEmpty()) break;
                } else {
                    if (!subject.isEmpty()) subject.append(' ');
                    subject.append(part);
                }
            }
            return new CommitMetadataDto(oid, List.copyOf(parents), bounded(subject.toString(), 1000),
                    bounded(identity.group(1), 200), bounded(identity.group(2), 254),
                    GitProjectUtils.requireMysqlInstant(Instant.ofEpochSecond(Long.parseLong(identity.group(3)))));
        } catch (IllegalArgumentException | IOException | java.time.DateTimeException ex) {
            throw invalid();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String bounded(String value, int max) {
        if (value.codePointCount(0, value.length()) > max || value.indexOf('\0') >= 0) throw invalid();
        return value;
    }

    private static DomainException invalid() {
        return new DomainException(KernelErrorCode.INVALID_REQUEST);
    }
}

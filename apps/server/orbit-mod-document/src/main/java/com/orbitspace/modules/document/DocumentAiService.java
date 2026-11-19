package com.orbitspace.modules.document;

import static com.orbitspace.kernel.KernelErrorCode.*;
import static com.orbitspace.modules.document.DocumentStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.orbitspace.kernel.*;
import com.orbitspace.kernel.AiSummaryGatewayPort.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;

class DocumentAiService implements AsyncJobPort.ResultTarget {
    record Policy(boolean enabled, List<String> allowedKinds, long version) {}
    record Input(Source source, String text) {}
    record Summary(
            Actor actor, long policy, JsonNode sources, String key, String version, boolean previewed) {}
    private final DocumentStore s;
    private final DocumentVersionService versions;
    private final DocumentPdfService pdf;
    private final ObjectProvider<AiSummaryGatewayPort> gateway;

    DocumentAiService(DocumentStore s, DocumentVersionService versions, DocumentPdfService pdf,
            ObjectProvider<AiSummaryGatewayPort> gateway) {
        this.s = s;
        this.versions = versions;
        this.pdf = pdf;
        this.gateway = gateway;
    }
    private Policy policy(String space) {
        return s.db
                .query("SELECT enabled,allowed_kinds,policy_version FROM space_ai_configs WHERE space_id=? "
                       + "FOR SHARE",
                        (r, i)
                                -> new Policy(r.getBoolean(1),
                                        s.parse(r.getString(2))
                                                .valueStream()
                                                .map(JsonNode::textValue)
                                                .toList(),
                                        r.getLong(3)),
                        space)
                .stream()
                .findFirst()
                .orElse(new Policy(false, List.of(), 0));
    }
    Object config(String space) {
        var p = policy(space);
        return Map.of("enabled", p.enabled, "allowedKinds", p.allowedKinds);
    }
    Object configure(Actor a, String key, JsonNode body) {
        check(body.path("enabled").isBoolean() && body.path("allowedKinds").isArray());
        var kinds = new HashSet<SourceKind>();
        for (var k : body.get("allowedKinds")) {
            check(k.isTextual() && kinds.add(kind(k.textValue())));
        }
        return s.once(a, "ai-config", key, body, () -> {
            s.db.update("""
                    INSERT INTO space_ai_configs(space_id,enabled,allowed_kinds) VALUES(?,?,?)
                    ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),allowed_kinds=VALUES(allowed_kinds),policy_version=policy_version+1
                    """, a.space(), body.get("enabled").booleanValue(), s.json(kinds));
            return config(a.space());
        });
    }
    private static SourceKind kind(String name) {
        try {
            return SourceKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw error(INVALID_REQUEST);
        }
    }
    private Input resolve(Actor a, Policy p, JsonNode ref, boolean read) {
        var type = kind(text(ref, "kind", 32));
        String id = text(ref, "resourceId", 160), version = text(ref, "versionId", 160);
        if (!p.enabled)
            throw error(AI_DISABLED_IN_SPACE);
        if (!p.allowedKinds.contains(type.name()))
            throw error(AUTH_FORBIDDEN);
        long lifecycle;
        String data = null;
        switch (type) {
            case DOCUMENT_VERSION -> {
                Doc d = s.document(a.space(), id);
                versions.version(d, version);
                lifecycle = d.lifecycle();
                if (read)
                    data = versions.transform
                                   .transform(
                                           "MARKDOWN_EXPORT", List.of(versions.content(d, version)), Map.of())
                                   .required("text")
                                   .textValue();
            }
            case PDF_VERSION -> {
                var f = pdf.require(a, id, version);
                lifecycle = f.lifecycle();
                if (read) {
                    var chunks = new StringJoiner("\n");
                    for (var page : pdf.pages(a, id, version)) chunks.add(page.path("text").asText());
                    data = chunks.toString();
                }
            }
            case GIT_SNAPSHOT -> {
                check(version.equals(id));
                var rows = s.db.queryForList("""
                        SELECT w.lifecycle_version,w.patch_object_key,w.note FROM wip_snapshots w
                        JOIN git_projects p ON p.id=w.project_id AND p.space_id=w.space_id
                        WHERE w.id=? AND w.space_id=? AND w.lifecycle_state='ACTIVE' AND p.status='ACTIVE' FOR SHARE
                        """, id, a.space());
                if (rows.isEmpty())
                    throw error(RESOURCE_NOT_FOUND);
                var row = rows.getFirst();
                lifecycle = ((Number) row.get("lifecycle_version")).longValue();
                if (read) {
                    String object = (String) row.get("patch_object_key");
                    data = object == null
                            ? (String) row.get("note")
                            : new String(s.read(object, 1024 * 1024, () -> s.member(a, SpaceRole.VIEWER)),
                                      StandardCharsets.UTF_8);
                }
            }
            case CHAT_MESSAGE -> {
                check(id.equals(version));
                var rows = s.db.queryForList("""
                        SELECT m.body_text FROM chat_messages m JOIN chat_rooms r ON r.id=m.room_id
                        WHERE m.id=? AND r.space_id=? AND m.kind='TEXT' FOR SHARE
                        """, String.class, id, a.space());
                if (rows.isEmpty())
                    throw error(RESOURCE_NOT_FOUND);
                lifecycle = 1;
                if (read)
                    data = rows.getFirst();
            }
            default -> throw error(INVALID_REQUEST);
        }
        if (read)
            check(data != null && !data.isBlank() && data.length() <= 1048576);
        return new Input(new Source(type, id, version, lifecycle), data);
    }
    Object request(Actor a, String key, JsonNode body) {
        var policy = policy(a.space());
        var actor = s.member(a, SpaceRole.MEMBER);
        var sources = body.path("sources");
        check(sources.isArray() && sources.size() > 0 && sources.size() <= 64);
        var authorized = sources.valueStream().map(r -> resolve(a, policy, r, false).source).toList();
        check(new HashSet<>(authorized).size() == authorized.size());
        return s.once(a, "ai-summary", key, body, () -> {
            String job = gateway.getObject().enqueueSummary(new Request(actor, authorized, key));
            s.db.update("""
                    INSERT INTO document_ai_summaries(job_id,space_id,user_id,membership_epoch,policy_version,sources_json)
                    VALUES(?,?,?,?,?,?)
                    """, job, a.space(), a.user(), a.epoch(), policy.version, s.json(authorized));
            return Map.of("jobId", job);
        });
    }
    Summary summary(String space, String user, String id) {
        return s.db
                .query("""
                SELECT membership_epoch,policy_version,sources_json,result_object_key,result_version_id,previewed
                FROM document_ai_summaries WHERE job_id=? AND space_id=? AND user_id=? FOR UPDATE
                """,
                        (r, i)
                                -> new Summary(new Actor(space, user, r.getLong(1), null), r.getLong(2),
                                        s.parse(r.getString(3)), r.getString(4), r.getString(5),
                                        r.getBoolean(6)),
                        id, space, user)
                .stream()
                .findFirst()
                .orElseThrow(() -> error(RESOURCE_NOT_FOUND));
    }

    private List<Input> authorize(Summary sum, boolean read) {
        s.member(sum.actor, SpaceRole.VIEWER);
        var p = policy(sum.actor.space());
        if (!p.enabled)
            throw error(AI_DISABLED_IN_SPACE);
        if (p.version != sum.policy)
            throw error(AUTH_FORBIDDEN);
        var inputs = new ArrayList<Input>();
        long length = 0;
        for (var ref : sum.sources) {
            var input = resolve(sum.actor, p, ref, read);
            if (ref.path("resourceLifecycleVersion").asLong() != input.source.resourceLifecycleVersion())
                throw error(RESOURCE_EXPIRED_OR_PURGED);
            if (read) {
                length += input.text.length();
                check(length <= 2_097_152);
            }
            inputs.add(input);
        }
        return inputs;
    }
    Map<String, Object> workerInput(AsyncJobPort.Job job) {
        var sum = summary(job.spaceId(), job.actorId(), job.jobId());
        return Map.of("sources",
                authorize(sum, true)
                        .stream()
                        .map(i
                                -> Map.of("kind", i.source.kind().name(), "resourceId", i.source.resourceId(),
                                        "versionId", i.source.versionId(), "text", i.text))
                        .toList());
    }
    @Override
    public AsyncJobPort.ResourceState lockAndCheck(AsyncJobPort.Job job) {
        try {
            var sum = summary(job.spaceId(), job.actorId(), job.jobId());
            authorize(sum, false);
            return new AsyncJobPort.ResourceState(1, true);
        } catch (DomainException e) {
            return new AsyncJobPort.ResourceState(0, false);
        }
    }
    private JsonNode result(Summary sum, String key) {
        var json = s.parse(new String(
                s.read(key, 8 * 1024 * 1024, () -> authorize(sum, false)), StandardCharsets.UTF_8));
        var points = json.path("points");
        check(points.isArray() && !points.isEmpty() && points.size() <= 256);
        Set<List<String>> permitted = new HashSet<>();
        for (var source : sum.sources) permitted.add(refKey(source));
        for (var point : points) {
            text(point, "text", 8192);
            var refs = point.path("sources");
            check(refs.isArray() && !refs.isEmpty() && refs.size() <= 64);
            for (var ref : refs) check(ref.size() == 3 && permitted.contains(refKey(ref)));
        }
        return points;
    }
    private List<String> refKey(JsonNode ref) {
        return List.of(text(ref, "kind", 32), text(ref, "resourceId", 160), text(ref, "versionId", 160));
    }
    @Override
    public void attachResult(AsyncJobPort.Job job, String key) {
        var sum = summary(job.spaceId(), job.actorId(), job.jobId());
        result(sum, key);
        s.attach("AI_SUMMARY", job.jobId(), key);
        s.db.update("UPDATE document_ai_summaries SET result_object_key=?,result_version_id=? WHERE job_id=?",
                key, id(), job.jobId());
    }
    Object preview(Actor a, String job) {
        var sum = summary(a.space(), a.user(), job);
        if (sum.actor.epoch() != a.epoch())
            throw error(AUTH_FORBIDDEN);
        authorize(sum, false);
        String status = s.scalar("SELECT status FROM async_jobs WHERE job_id=?", job);
        if (!status.equals("SUCCEEDED")) {
            return Set.of("FAILED", "CANCELLED").contains(status)
                    ? Map.of("jobId", job, "status", status, "reason", status)
                    : Map.of("jobId", job, "status", status);
        }
        var points = result(sum, sum.key);
        s.db.update("UPDATE document_ai_summaries SET previewed=1 WHERE job_id=?", job);
        return Map.of("jobId", job, "status", status, "resultVersionId", sum.version, "points", points);
    }
    Object commit(Actor a, String job, String key, JsonNode body) {
        var sum = summary(a.space(), a.user(), job);
        if (sum.actor.epoch() != a.epoch() || !sum.previewed
                || !Objects.equals(text(body, "resultVersionId", 160), sum.version))
            throw error(AUTH_FORBIDDEN);
        check(body.path("confirmed").isBoolean() && body.get("confirmed").booleanValue());
        var d = s.document(a.space(), text(body, "documentId", 160));
        authorize(sum, false);
        return s.once(a, "ai-commit/" + job, key, body, () -> {
            expect(d.seq(), seq(body, "expectedPersistedBatchSeq"));
            var lines = new StringJoiner("\n");
            for (var p : result(sum, sum.key)) {
                var refs = new StringJoiner(", ");
                for (var ref : p.required("sources")) refs.add(String.join("/", refKey(ref)));
                lines.add(p.required("text").asText() + " [" + refs + "]");
            }
            return versions.edit(a, d, "APPEND", Map.of("text", lines.toString()));
        });
    }
}

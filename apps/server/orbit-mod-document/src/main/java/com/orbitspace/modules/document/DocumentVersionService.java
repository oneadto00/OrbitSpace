package com.orbitspace.modules.document;

import static com.orbitspace.kernel.KernelErrorCode.*;
import static com.orbitspace.modules.document.DocumentStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.orbitspace.kernel.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

class DocumentVersionService {
    final DocumentStore s;
    final DocumentTransformService transform;
    final DocumentCollabService collab;
    final ObjectProvider<ResourceLifecycleHook> lifecycle;

    DocumentVersionService(DocumentStore s, DocumentTransformService transform, DocumentCollabService collab,
            ObjectProvider<ResourceLifecycleHook> lifecycle) {
        this.s = s;
        this.transform = transform;
        this.collab = collab;
        this.lifecycle = lifecycle;
    }
    Object info(Doc d) {
        return Map.of(
                "documentId", d.id(), "spaceId", d.space(), "title", d.title(), "persistedBatchSeq", d.seq());
    }
    JsonNode create(Actor a, String key, JsonNode body) {
        var result = s.once(a, "create", key, body, () -> {
            String id = id();
            s.db.update("INSERT INTO documents(id,space_id,title) VALUES(?,?,?)", id, a.space(),
                    text(body, "title", 255));
            return info(s.document(a.space(), id));
        });
        s.document(a.space(), result.path("documentId").asText());
        return result;
    }

    void delete(Actor a, String doc, String key) {
        s.once(a, "delete/" + doc, key, Map.of(), () -> {
            var d = s.document(a.space(), doc);
            s.db.update("UPDATE documents SET lifecycle_state='DELETED', "
                        + "lifecycle_version=lifecycle_version+1 WHERE id=?",
                    doc);
            s.db.update("UPDATE document_sessions SET revoked=1 WHERE document_id=?", doc);
            var ids = s.db.queryForList(
                    "SELECT id FROM document_comments WHERE document_id=?", String.class, doc);
            for (String comment : ids)
                lifecycle.orderedStream().forEach(hook
                        -> hook.onLifecycleChanged(new ResourceLifecycleHook.Change(a.space(),
                                new ResourceCardVerifierPort.Reference(MessageKind.DOC_REF, null, comment),
                                ResourceLifecycleState.EXPIRED_OR_DELETED, d.lifecycle() + 1)));
            s.notifyDocument("DOCUMENT_DELETED", d);
            return true;
        });
    }
    String snapshot(Doc d, long through) {
        check(through >= 0 && through <= d.seq());
        var last = s.db.queryForList("""
                SELECT persisted_batch_seq,snapshot_object_key FROM document_versions WHERE document_id=?
                AND persisted_batch_seq<=? ORDER BY persisted_batch_seq DESC LIMIT 1
                """, d.id(), through);
        long after = 0;
        var inputs = new ArrayList<String>();
        Runnable allowed = () -> s.document(d.space(), d.id());
        if (!last.isEmpty()) {
            var row = last.getFirst();
            after = ((Number) row.get("persisted_batch_seq")).longValue();
            inputs.add(s.encoded((String) row.get("snapshot_object_key"), MAX_STATE, allowed));
        }
        while (after < through) {
            var page =
                    s.db.query("""
                    SELECT batch_seq,object_key FROM document_update_batches
                    WHERE document_id=? AND batch_seq>? AND batch_seq<=? ORDER BY batch_seq LIMIT 8
                    """, (r, i) -> Map.entry(r.getLong(1), r.getString(2)), d.id(), after, through);
            if (page.isEmpty())
                throw new IllegalStateException("Incomplete durable log");
            for (var ref : page) {
                if (ref.getKey() != ++after)
                    throw new IllegalStateException("Non-contiguous batch log");
                inputs.add(s.encoded(ref.getValue(), MAX_BATCH, allowed));
                if (inputs.stream().mapToLong(String::length).sum() >= 56L * 1024 * 1024) {
                    var compact =
                            transform.transform("SNAPSHOT", inputs, Map.of()).required("snapshot").asText();
                    inputs.clear();
                    inputs.add(compact);
                }
            }
            if (after < through) {
                String compact =
                        transform.transform("SNAPSHOT", inputs, Map.of()).required("snapshot").asText();
                inputs.clear();
                inputs.add(compact);
            }
        }
        if (inputs.isEmpty())
            return "AAA=";
        return inputs.size() == 1
                ? inputs.getFirst()
                : transform.transform("SNAPSHOT", inputs, Map.of()).required("snapshot").asText();
    }

    Version version(Doc doc, String version) {
        return s.db
                .query("SELECT * FROM document_versions WHERE id=? AND document_id=?",
                        DocumentStore::versionRow, version, doc.id())
                .stream()
                .findFirst()
                .orElseThrow(() -> error(RESOURCE_NOT_FOUND));
    }
    String content(Doc d, String version) {
        String key =
                s.scalar("SELECT snapshot_object_key FROM document_versions WHERE id=? AND document_id=?",
                        version, d.id());
        return s.encoded(key, MAX_STATE, () -> s.document(d.space(), d.id()));
    }

    Version freeze(Doc d, long seq, String kind) {
        check(seq >= 0 && seq <= d.seq());
        var existing = s.db.query("""
                SELECT * FROM document_versions WHERE document_id=? AND persisted_batch_seq=? ORDER BY created_at DESC LIMIT 1
                """, DocumentStore::versionRow, d.id(), seq);
        if (kind.equals("QUOTE") && !existing.isEmpty())
            return existing.getFirst();
        String key, digest;
        if (!existing.isEmpty()) {
            var row = s.db.queryForMap(
                    "SELECT snapshot_object_key,snapshot_sha256 FROM document_versions WHERE id=?",
                    existing.getFirst().persistedVersionId());
            key = (String) row.get("snapshot_object_key");
            digest = (String) row.get("snapshot_sha256");
        } else {
            var object = s.promote(bytes(snapshot(d, seq), MAX_STATE));
            key = object.objectKey();
            digest = object.sha256();
        }
        String id = id();
        s.attach("DOCUMENT_VERSION", id, key);
        s.db.update("""
                INSERT INTO document_versions(id,document_id,persisted_batch_seq,snapshot_object_key,snapshot_sha256,kind)
                VALUES(?,?,?,?,?,?)
                """, id, d.id(), seq, key, digest, kind);
        s.db.update(
                "UPDATE documents SET snapshot_seq=?,snapshot_object_key=? WHERE id=? AND snapshot_seq<=?",
                seq, key, d.id(), seq);
        return version(d, id);
    }

    @Scheduled(fixedDelayString = "${orbit.document.snapshot-interval:PT1M}")
    public void checkpoint() {
        var ids = s.db.query("SELECT space_id,id FROM documents WHERE lifecycle_state='ACTIVE' AND "
                             + "persisted_batch_seq>snapshot_seq ORDER BY id LIMIT 32",
                (r, i) -> Map.entry(r.getString(1), r.getString(2)));
        for (var entry : ids) {
            try {
                s.tx.executeWithoutResult(tx -> {
                    var d = s.document(entry.getKey(), entry.getValue());
                    if (d.seq() > d.snapshotSeq())
                        freeze(d, d.seq(), "AUTO");
                });
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Document checkpoint deferred for {}", entry.getValue());
            }
        }
    }
    Object listVersions(Doc d, String cursor) {
        var sql = "SELECT * FROM document_versions WHERE document_id=?";
        var args = new ArrayList<Object>();
        args.add(d.id());
        if (cursor != null) {
            var previous = version(d, cursor);
            sql += " AND (created_at,id)<(?,?)";
            args.add(Timestamp.from(previous.createdAt()));
            args.add(cursor);
        }
        var rows = s.db.query(sql + " ORDER BY created_at DESC,id DESC LIMIT 51", DocumentStore::versionRow,
                args.toArray());
        var page = rows.stream().limit(50).toList();
        var result = new HashMap<String, Object>();
        result.put("versions", page);
        result.put("nextCursor", rows.size() > 50 ? page.getLast().persistedVersionId() : null);
        return result;
    }
    Object preview(Actor a, Doc d, String version, String key) {
        var target = version(d, version);
        var preview = s.once(a, "preview/" + d.id() + "/" + version, key, Map.of(), () -> {
            String id = id();
            Instant expires = Instant.now().plusSeconds(180);
            s.db.update("""
                    INSERT INTO document_restore_previews(id,document_id,version_id,user_id,membership_epoch,base_seq,expires_at)
                    VALUES(?,?,?,?,?,?,?)
                    """, id, d.id(), version, a.user(), a.epoch(), d.seq(), Timestamp.from(expires));
            return Map.of("previewId", id, "basePersistedBatchSeq", d.seq(), "expiresAt", expires.toString(),
                    "targetVersionId", version);
        });
        String base = snapshot(d, preview.path("basePersistedBatchSeq").longValue());
        String diff = transform
                              .transform("DIFF", List.of(base),
                                      Map.of("targetUpdate", content(d, target.persistedVersionId())))
                              .required("text")
                              .asText();
        var response = preview.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) response).put("diff", diff);
        return response;
    }
    Object restore(Actor a, Doc d, String version, String key, JsonNode body) {
        return s.once(a, "restore/" + d.id() + "/" + version, key, body, () -> {
            check(body.path("confirmed").isBoolean() && body.get("confirmed").booleanValue());
            String preview = text(body, "previewId", 160);
            expect(d.seq(), seq(body, "expectedPersistedBatchSeq"));
            if (s.db.queryForList("""
                    SELECT id FROM document_restore_previews WHERE id=? AND document_id=? AND version_id=? AND user_id=?
                    AND membership_epoch=? AND base_seq=? AND expires_at>CURRENT_TIMESTAMP(6) FOR UPDATE
                    """, String.class, preview, d.id(), version, a.user(), a.epoch(), d.seq())
                            .isEmpty())
                throw error(DOC_ANCHOR_UNPERSISTED);
            var backup = freeze(d, d.seq(), "RESTORE_BACKUP");
            edit(a, d, "RESTORE", Map.of("targetUpdate", content(d, version)));
            Doc changed = s.document(a.space(), d.id());
            return Map.of("backup", backup, "newVersion", freeze(changed, changed.seq(), "RESTORE_RESULT"));
        });
    }
    Object edit(Actor a, Doc d, String op, Map<String, ?> options) {
        var edit = transform.transform(op, List.of(snapshot(d, d.seq())), options);
        var delta = s.promote(bytes(edit.required("update").asText(), MAX_BATCH));
        collab.append(d, a.user(), id(), delta);
        s.notifyDocument("DOCUMENT_CHANGED", d);
        return Map.of("documentId", d.id(), "persistedBatchSeq", d.seq() + 1);
    }
}

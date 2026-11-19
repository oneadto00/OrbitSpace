package com.orbitspace.modules.document;

import static com.orbitspace.kernel.KernelErrorCode.*;
import static com.orbitspace.modules.document.DocumentStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.orbitspace.kernel.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

class DocumentCollabService implements DocumentCollabPort, MembershipRevocationHook {
    private final DocumentStore s;
    private final DocumentTransformService transforms;

    DocumentCollabService(DocumentStore s, DocumentTransformService transforms) {
        this.s = s;
        this.transforms = transforms;
    }
    private record Grant(Actor actor, String id, String document, boolean consumed) {}
    private Grant grant(String doc, String column, String value, boolean admission) {
        String predicate =
                admission ? " AND ticket_expires>CURRENT_TIMESTAMP(6) AND consumed=0" : " AND consumed=1";
        var rows = s.db.query("""
                SELECT space_id,user_id,membership_epoch,auth_session_id,id,document_id,consumed
                FROM document_sessions WHERE document_id=? AND revoked=0 AND
                """ + column + "=?" + predicate,
                (r, i)
                        -> new Grant(new Actor(r.getString(1), r.getString(2), r.getLong(3), r.getString(4)),
                                r.getString(5), r.getString(6), r.getBoolean(7)),
                doc, value);
        if (rows.isEmpty())
            throw error(COLLAB_REVOKED);
        return rows.getFirst();
    }
    private Session live(Grant grant, SpaceRole role) {
        var a = grant.actor();
        try {
            var member = s.member(a, role);
            s.document(a.space(), grant.document());
            if (s.db.queryForList("""
                    SELECT ds.id FROM document_sessions ds JOIN auth_sessions ac ON ds.auth_session_id=ac.id
                    WHERE ds.id=? AND ds.revoked=0 AND ac.user_id=? AND ac.revoked_at IS NULL
                    AND ac.expires_at>CURRENT_TIMESTAMP(6) FOR SHARE
                    """, String.class, grant.id(), a.user()).isEmpty())
                throw error(COLLAB_REVOKED);
            return new Session(grant.document(), grant.id(), a.space(), a.user(), a.epoch(), member.role(),
                    member.role() == SpaceRole.VIEWER);
        } catch (DomainException e) {
            throw error(COLLAB_REVOKED);
        }
    }
    Object issue(Actor actor, Doc doc, String key) {
        JsonNode saved = s.once(actor, "ticket/" + doc.id(), key, actor.accountSession(), () -> {
            String session = id(), token = transforms.sign("collab-ticket/" + session);
            var expires = Instant.now().plusSeconds(60);
            s.db.update("""
                    INSERT INTO document_sessions(id,document_id,space_id,user_id,auth_session_id,membership_epoch,ticket_hash,ticket_expires)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, session, doc.id(), actor.space(), actor.user(), actor.accountSession(),
                    actor.epoch(), hash(token.getBytes(StandardCharsets.UTF_8)), Timestamp.from(expires));
            return Map.of("session", session, "expires", expires.toString());
        });
        return Map.of("ticket", transforms.sign("collab-ticket/" + saved.path("session").asText()),
                "documentId", doc.id(), "membershipEpoch", actor.epoch(), "expiresAt", saved.path("expires"),
                "wsPath", "/collab");
    }
    public Session authenticate(String doc, String ticket) {
        check(ticket != null && ticket.length() <= 512);
        return s.tx.execute(tx -> {
            var grant = grant(doc, "ticket_hash", hash(ticket.getBytes(StandardCharsets.UTF_8)), true);
            var session = live(grant, SpaceRole.VIEWER);
            if (s.db.update("""
                    UPDATE document_sessions SET consumed=1 WHERE id=? AND consumed=0 AND revoked=0
                    AND ticket_expires>CURRENT_TIMESTAMP(6)
                    """, grant.id()) != 1)
                throw error(COLLAB_REVOKED);
            return session;
        });
    }
    public Access revokeCheck(String doc, String id) {
        return s.tx.execute(tx -> {
            var session = live(grant(doc, "id", id, false), SpaceRole.VIEWER);
            return new Access(session.readOnly(), session.membershipEpoch());
        });
    }
    public StatePage load(String doc, String id, String cursor) {
        return s.tx.execute(tx -> {
            var g = grant(doc, "id", id, false);
            var session = live(g, SpaceRole.VIEWER);
            var d = s.document(session.spaceId(), doc);
            long after = d.snapshotSeq(), through = d.seq();
            Snapshot snapshot = null;
            Runnable access = () -> live(g, SpaceRole.VIEWER);
            if (cursor != null) {
                String[] fields = cursor.split("\\.", -1);
                check(fields.length == 3 && fields[0].matches("[0-9]{1,16}")
                        && fields[1].matches("[0-9]{1,16}"));
                String signature =
                        transforms.sign("cursor/" + doc + "/" + id + "/" + fields[0] + "." + fields[1]);
                check(MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),
                        fields[2].getBytes(StandardCharsets.US_ASCII)));
                after = Long.parseLong(fields[0]);
                through = Long.parseLong(fields[1]);
                check(after >= 0 && through <= d.seq() && after <= through);
            } else if (d.snapshotKey() != null)
                snapshot = new Snapshot(after, s.encoded(d.snapshotKey(), MAX_STATE, access));
            var refs = s.db.query("""
                    SELECT batch_seq,object_key FROM document_update_batches WHERE document_id=?
                    AND batch_seq>? AND batch_seq<=? ORDER BY batch_seq LIMIT 4
                    """, (r, i) -> Map.entry(r.getLong(1), r.getString(2)), doc, after, through);
            var updates = new ArrayList<Update>();
            for (var ref : refs) {
                if (ref.getKey() != after + 1)
                    throw new IllegalStateException("Non-contiguous collaboration log");
                updates.add(new Update(++after, s.encoded(ref.getValue(), MAX_BATCH, access)));
            }
            if (updates.isEmpty() && after != through)
                throw new IllegalStateException("Missing durable batch");
            String mark = after + "." + through;
            return new StatePage(snapshot, updates, through,
                    after == through ? null
                                     : mark + "." + transforms.sign("cursor/" + doc + "/" + id + "/" + mark));
        });
    }

    public BatchReceipt storeBatch(
            String doc, String sessionId, String batchId, long expected, String update) {
        check(batchId != null && !batchId.isBlank() && batchId.length() <= 160 && expected >= 0);
        s.tx.executeWithoutResult(tx -> live(grant(doc, "id", sessionId, false), SpaceRole.MEMBER));
        var object = s.promote(bytes(update, MAX_BATCH));
        return s.tx.execute(tx -> {
            var g = grant(doc, "id", sessionId, false);
            live(g, SpaceRole.MEMBER);
            var d = s.document(g.actor().space(), doc);
            var old = s.db.queryForList("""
                    SELECT batch_seq,writer_id,sha256 FROM document_update_batches WHERE document_id=? AND batch_id=?
                    FOR UPDATE
                    """, doc, batchId);
            if (!old.isEmpty()) {
                var row = old.getFirst();
                long seq = ((Number) row.get("batch_seq")).longValue();
                if (!object.sha256().equals(row.get("sha256")) || !sessionId.equals(row.get("writer_id"))
                        || expected != seq - 1)
                    throw error(IDEMPOTENCY_CONFLICT);
                return new BatchReceipt(doc, batchId, seq);
            }
            expect(d.seq(), expected);
            append(d, sessionId, batchId, object);
            return new BatchReceipt(doc, batchId, expected + 1);
        });
    }

    void append(Doc d, String writer, String batch, ObjectStoragePort.StoredObject object) {
        long seq = d.seq() + 1;
        s.attach("DOCUMENT_BATCH", d.id() + "/" + seq, object.objectKey());
        s.db.update("""
                INSERT INTO document_update_batches(document_id,batch_seq,batch_id,writer_id,object_key,sha256)
                VALUES(?,?,?,?,?,?)
                """, d.id(), seq, batch, writer, object.objectKey(), object.sha256());
        if (s.db.update("UPDATE documents SET persisted_batch_seq=? WHERE id=? AND persisted_batch_seq=?",
                    seq, d.id(), d.seq())
                != 1)
            throw error(DOC_ANCHOR_UNPERSISTED);
    }

    public void onRevoked(Revocation r) {
        s.db.update("UPDATE document_sessions SET revoked=1 WHERE space_id=? AND user_id=? AND "
                    + "membership_epoch=?",
                r.spaceId(), r.userId(), r.endedMembershipEpoch());
    }
}

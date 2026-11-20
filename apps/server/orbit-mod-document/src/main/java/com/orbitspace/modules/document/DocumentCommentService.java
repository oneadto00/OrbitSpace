package com.orbitspace.modules.document;

import static com.orbitspace.kernel.KernelErrorCode.*;
import static com.orbitspace.modules.document.DocumentStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orbitspace.kernel.*;
import com.orbitspace.modules.chat.ChatDtos.*;
import com.orbitspace.modules.chat.ChatService;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;

class DocumentCommentService implements ResourceCardVerifierPort, ResourceLifecycleHook {
    final DocumentStore s;
    final DocumentVersionService versions;
    final ObjectProvider<DocumentPdfService> pdf;
    final ObjectProvider<ChatService> chat;
    DocumentCommentService(DocumentStore s, DocumentVersionService versions,
            ObjectProvider<DocumentPdfService> pdf, ObjectProvider<ChatService> chat) {
        this.s = s;
        this.versions = versions;
        this.pdf = pdf;
        this.chat = chat;
    }
    String resolve(String state, JsonNode selection) {
        return versions.transform.transform("RESOLVE", List.of(state), Map.of("selection", selection))
                .required("anchorStatus")
                .asText();
    }
    private ObjectNode selection(JsonNode raw) {
        ObjectNode clean = s.mapper.createObjectNode();
        for (String name : List.of("relativeStart", "relativeEnd")) {
            String value = text(raw, name, 1024);
            bytes(value, 768);
            clean.put(name, value);
        }
        clean.put("quotedText", text(raw, "quotedText", 4096));
        for (String name : List.of("contextBefore", "contextAfter"))
            if (raw.has(name))
                clean.put(name, text(raw, name, 512));
        return clean;
    }
    JsonNode anchor(Doc d, String version, JsonNode raw) {
        var result = selection(raw);
        if (!"ACTIVE".equals(resolve(versions.content(d, version), result)))
            throw error(DOC_ANCHOR_UNPERSISTED);
        result.put("documentId", d.id()).put("persistedVersionId", version).put("anchorStatus", "ACTIVE");
        return result;
    }
    Object quote(Actor a, Doc d, String key, JsonNode body) {
        return s.once(a, "quote/" + d.id(), key, body, () -> {
            long seq = seq(body, "persistedBatchSeq");
            if (seq > d.seq())
                throw error(DOC_ANCHOR_UNPERSISTED);
            if (body.has("batchId")
                    && s.db.queryForList("""
                    SELECT batch_id FROM document_update_batches WHERE document_id=? AND batch_seq=? AND batch_id=?
                    """, String.class, d.id(), seq, text(body, "batchId", 160)).isEmpty())
                throw error(DOC_ANCHOR_UNPERSISTED);
            var version = versions.freeze(d, seq, "QUOTE");
            return anchor(d, version.persistedVersionId(), body.path("selection"));
        });
    }

    Object comment(Actor a, Doc doc, String file, String fileVersion, String key, JsonNode body) {
        boolean document = doc != null;
        String scope = document ? doc.id() : file + "/" + fileVersion;
        if (!document)
            pdf.getObject().require(a, file, fileVersion);
        return s.once(a, (document ? "doc-comment/" : "pdf-comment/") + scope, key, body, () -> {
            JsonNode anchor;
            String version = null, kind = document ? "DOC_REF" : "PDF_REF";
            if (document) {
                version = text(body, "persistedVersionId", 160);
                anchor = anchor(doc, version, body.path("selection"));
            } else {
                anchor = body.path("anchor");
                pdf.getObject().validateAnchor(a, file, fileVersion, anchor);
            }
            String message = text(body, "body", 8000), id = id();
            // Keep the selected excerpt out of the discussion root.
            var root = chat.getObject().sendMessage(a.user(), a.space(), a.epoch(),
                    new SendMessageRequest(
                            id(), null, new TextContent(document ? "协作文档批注讨论" : "PDF 批注讨论")));
            s.db.update("""
                    INSERT INTO document_comments(id,space_id,kind,document_id,version_id,file_id,file_version_id,
                    anchor_json,body,discussion_thread_id) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, id, a.space(), kind, document ? doc.id() : null, version, file, fileVersion,
                    s.json(anchor), message, root.messageId());
            return Map.of("commentId", id, "anchor", anchor, "body", message, "discussionThreadId",
                    root.messageId());
        });
    }
    Object locate(Actor a, Doc doc, String file, String fileVersion, String id, String view) {
        boolean document = doc != null;
        if (!document)
            pdf.getObject().require(a, file, fileVersion);
        var rows = document
                ? s.db.queryForList(
                          "SELECT * FROM document_comments WHERE id=? AND document_id=? AND space_id=?", id,
                          doc.id(), a.space())
                : s.db.queryForList("SELECT * FROM document_comments WHERE id=? AND file_id=? AND "
                                    + "file_version_id=? AND space_id=?",
                          id, file, fileVersion, a.space());
        if (rows.isEmpty())
            throw error(RESOURCE_NOT_FOUND);
        var row = rows.getFirst();
        var anchor = (ObjectNode) s.parse((String) row.get("anchor_json"));
        if (document) {
            check(Set.of("CURRENT", "QUOTED_VERSION").contains(view));
            if (view.equals("CURRENT"))
                anchor.put("anchorStatus", resolve(versions.snapshot(doc, doc.seq()), selection(anchor)));
        }
        return Map.of("commentId", id, "body", row.get("body"), "anchor", anchor, "discussionThreadId",
                row.get("discussion_thread_id"));
    }
    @Override
    public void onLifecycleChanged(ResourceLifecycleHook.Change change) {
        if (change.reference().kind() != MessageKind.FILE_REF
                || change.reference().grantType() != GrantType.SPACE_FILE
                || change.state() == ResourceLifecycleState.ACTIVE) return;
        var anchors = s.db.queryForList(
                "SELECT id FROM document_comments WHERE space_id=? AND file_id=? AND kind='PDF_REF'",
                String.class, change.spaceId(), change.reference().targetId());
        for (var anchor : anchors) {
            var derived = new ResourceLifecycleHook.Change(change.spaceId(),
                    new Reference(MessageKind.PDF_REF, null, anchor), change.state(), change.lifecycleVersion());
            versions.lifecycle.orderedStream().filter(hook -> hook != this)
                    .forEach(hook -> hook.onLifecycleChanged(derived));
        }
    }
    public boolean supports(MessageKind kind) {
        return kind == MessageKind.DOC_REF || kind == MessageKind.PDF_REF;
    }
    public Preview verify(String userId, String space, long epoch, Reference ref) {
        return s.tx.execute(tx -> {
            var a = new Actor(space, userId, epoch, null);
            s.member(a, SpaceRole.VIEWER);
            var rows =
                    s.db.queryForList("SELECT * FROM document_comments WHERE id=? AND space_id=? AND kind=?",
                            ref.targetId(), space, ref.kind().name());
            if (rows.isEmpty())
                return new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
            var row = rows.getFirst();
            try {
                if (ref.kind() == MessageKind.DOC_REF) {
                    var d = s.document(space, (String) row.get("document_id"));
                    versions.version(d, (String) row.get("version_id"));
                } else
                    pdf.getObject().require(
                            a, (String) row.get("file_id"), (String) row.get("file_version_id"));
            } catch (DomainException ex) {
                if (ex.errorCode() == RESOURCE_EXPIRED_OR_PURGED || ex.errorCode() == RESOURCE_NOT_FOUND)
                    return new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
                throw ex;
            }
            return new Preview(ResourceLifecycleState.ACTIVE,
                    s.parse((String) row.get("anchor_json")).required("quotedText").asText());
        });
    }
}

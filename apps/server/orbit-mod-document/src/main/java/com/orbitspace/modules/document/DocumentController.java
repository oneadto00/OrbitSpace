package com.orbitspace.modules.document;

import static com.orbitspace.kernel.SpaceRole.*;
import static com.orbitspace.modules.document.DocumentStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.orbitspace.kernel.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
@RequestMapping("/api/v1/spaces/{spaceId}")
public class DocumentController {
    static final String DOC = "/documents/{documentId}";
    static final String VERSION = DOC + "/versions/{versionId}";
    static final String PDF = "/files/{fileId}/versions/{fileVersionId}";
    static final String AI = "/ai/summaries/{jobId}";
    static final String KEY = "Idempotency-Key";

    private final DocumentStore s;
    private final DocumentVersionService versions;
    private final DocumentCollabService collab;
    private final DocumentCommentService comments;
    private final DocumentPdfService pdf;
    private final DocumentAiService ai;
    DocumentController(DocumentStore s, DocumentVersionService versions, DocumentCollabService collab,
            DocumentCommentService comments, DocumentPdfService pdf, DocumentAiService ai) {
        this.s = s;
        this.versions = versions;
        this.collab = collab;
        this.comments = comments;
        this.pdf = pdf;
        this.ai = ai;
    }
    @ModelAttribute(name = "actor", binding = false)
    Actor actor(@AuthenticationPrincipal AuthenticatedPrincipal account, @PathVariable String spaceId,
            @RequestHeader("X-Membership-Epoch") long epoch) {
        if (account == null)
            throw error(KernelErrorCode.AUTH_UNAUTHORIZED);
        return new Actor(spaceId, account.userId(), epoch, account.sessionId());
    }
    private Doc authorized(Actor a, String id, SpaceRole role) {
        s.member(a, role);
        return s.document(a.space(), id);
    }
    @GetMapping("/documents")
    public Object list(@ModelAttribute(name = "actor", binding = false) Actor a) {
        s.member(a, VIEWER);
        return s.db.query("SELECT id,title,persisted_batch_seq FROM documents WHERE space_id=? AND "
                          + "lifecycle_state='ACTIVE' ORDER BY id",
                (r, i)
                        -> Map.of("documentId", r.getString(1), "spaceId", a.space(), "title", r.getString(2),
                                "persistedBatchSeq", r.getLong(3)),
                a.space());
    }
    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public Object create(@ModelAttribute(name = "actor", binding = false) Actor a,
            @RequestHeader(KEY) String key, @RequestBody JsonNode body) {
        s.member(a, MEMBER);
        return versions.create(a, key, body);
    }
    @DeleteMapping(DOC)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @RequestHeader(KEY) String key) {
        s.member(a, MEMBER);
        versions.delete(a, documentId, key);
    }
    @PostMapping(DOC + "/collab-ticket")
    public Object ticket(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @RequestHeader(KEY) String key) {
        return collab.issue(a, authorized(a, documentId, VIEWER), key);
    }
    @GetMapping(DOC + "/saved-boundary")
    public Object boundary(
            @ModelAttribute(name = "actor", binding = false) Actor a, @PathVariable String documentId) {
        var d = authorized(a, documentId, VIEWER);
        return Map.of("documentId", d.id(), "persistedBatchSeq", d.seq());
    }
    @GetMapping(DOC + "/versions")
    public Object versions(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @RequestParam(required = false) String cursor) {
        return versions.listVersions(authorized(a, documentId, VIEWER), cursor);
    }
    @PostMapping(DOC + "/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Object save(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @RequestHeader(KEY) String key, @RequestBody JsonNode body) {
        var d = authorized(a, documentId, MEMBER);
        return s.once(a, "save/" + documentId, key, body, () -> {
            expect(d.seq(), seq(body, "expectedPersistedBatchSeq"));
            return versions.freeze(d, d.seq(), "MANUAL");
        });
    }
    @GetMapping(VERSION)
    public Object version(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @PathVariable String versionId) {
        var d = authorized(a, documentId, VIEWER);
        return Map.of("version", versions.version(d, versionId), "yjsUpdate", versions.content(d, versionId));
    }
    @PostMapping(DOC + "/quote-versions")
    public Object quote(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @RequestHeader(KEY) String key, @RequestBody JsonNode body) {
        return comments.quote(a, authorized(a, documentId, MEMBER), key, body);
    }
    @PostMapping(VERSION + "/restore-preview")
    public Object previewRestore(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @PathVariable String versionId, @RequestHeader(KEY) String key) {
        return versions.preview(a, authorized(a, documentId, MEMBER), versionId, key);
    }
    @PostMapping(VERSION + "/restore")
    public Object restore(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @PathVariable String versionId, @RequestHeader(KEY) String key,
            @RequestBody JsonNode body) {
        return versions.restore(a, authorized(a, documentId, MEMBER), versionId, key, body);
    }
    @PostMapping(DOC + "/markdown")
    public Object importMarkdown(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @RequestHeader(KEY) String key, @RequestBody JsonNode body) {
        var d = authorized(a, documentId, MEMBER);
        return s.once(a, "markdown/" + d.id(), key, body, () -> {
            expect(d.seq(), seq(body, "expectedPersistedBatchSeq"));
            return versions.edit(a, d, "MARKDOWN_IMPORT", Map.of("text", text(body, "markdown", 4194304)));
        });
    }
    @GetMapping(VERSION + "/markdown")
    public Object exportMarkdown(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @PathVariable String versionId) {
        var d = authorized(a, documentId, VIEWER);
        var result = versions.transform.transform(
                "MARKDOWN_EXPORT", List.of(versions.content(d, versionId)), Map.of());
        return Map.of("markdown", result.required("text"), "persistedVersionId", versionId);
    }
    @PostMapping(DOC + "/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Object comment(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @RequestHeader(KEY) String key, @RequestBody JsonNode body) {
        return comments.comment(a, authorized(a, documentId, MEMBER), null, null, key, body);
    }
    @GetMapping(DOC + "/comments/{commentId}")
    public Object locate(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String documentId, @PathVariable String commentId, @RequestParam String view) {
        return comments.locate(a, authorized(a, documentId, VIEWER), null, null, commentId, view);
    }
    @PostMapping(PDF + "/pdf-comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Object pdfComment(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String fileId, @PathVariable String fileVersionId, @RequestHeader(KEY) String key,
            @RequestBody JsonNode body) {
        s.member(a, MEMBER);
        return comments.comment(a, null, fileId, fileVersionId, key, body);
    }
    @GetMapping(PDF + "/pdf-comments/{commentId}")
    public Object pdfLocate(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String fileId, @PathVariable String fileVersionId, @PathVariable String commentId) {
        s.member(a, VIEWER);
        return comments.locate(a, null, fileId, fileVersionId, commentId, null);
    }
    @PostMapping(PDF + "/pdf-index")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object indexPdf(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String fileId, @PathVariable String fileVersionId, @RequestHeader(KEY) String key) {
        s.member(a, VIEWER);
        return pdf.queue(a, fileId, fileVersionId, key);
    }
    @GetMapping(PDF + "/pdf-index")
    public Object pdfIndex(@ModelAttribute(name = "actor", binding = false) Actor a,
            @PathVariable String fileId, @PathVariable String fileVersionId) {
        s.member(a, VIEWER);
        return pdf.index(a, fileId, fileVersionId);
    }
    @GetMapping("/ai/config")
    public Object config(@ModelAttribute(name = "actor", binding = false) Actor a) {
        s.member(a, VIEWER);
        return ai.config(a.space());
    }
    @PutMapping("/ai/config")
    public Object configure(@ModelAttribute(name = "actor", binding = false) Actor a,
            @RequestHeader(KEY) String key, @RequestBody JsonNode body) {
        s.member(a, ADMIN);
        return ai.configure(a, key, body);
    }
    @PostMapping("/ai/summaries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object summarize(@ModelAttribute(name = "actor", binding = false) Actor a,
            @RequestHeader(KEY) String key, @RequestBody JsonNode body) {
        return ai.request(a, key, body);
    }
    @GetMapping(AI)
    public Object summary(
            @ModelAttribute(name = "actor", binding = false) Actor a, @PathVariable String jobId) {
        s.member(a, VIEWER);
        return ai.preview(a, jobId);
    }
    @PostMapping(AI + "/commit")
    public Object commit(@ModelAttribute(name = "actor", binding = false) Actor a, @PathVariable String jobId,
            @RequestHeader(KEY) String key, @RequestBody JsonNode body) {
        s.member(a, MEMBER);
        return ai.commit(a, jobId, key, body);
    }
}

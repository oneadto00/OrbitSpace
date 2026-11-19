package com.orbitspace.modules.document;

import static com.orbitspace.kernel.KernelErrorCode.*;
import static com.orbitspace.modules.document.DocumentStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.orbitspace.kernel.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class DocumentPdfService implements AsyncJobPort.ResultTarget {
    final DocumentStore s;
    final FileQuotaAndGrantPort files;
    final AsyncJobPort jobs;
    DocumentPdfService(DocumentStore s, FileQuotaAndGrantPort files, AsyncJobPort jobs) {
        this.s = s;
        this.files = files;
        this.jobs = jobs;
    }
    record Pdf(FileQuotaAndGrantPort.VerifiedGrant grant, long lifecycle) {}
    Pdf require(Actor a, String file, String version) {
        var row = s.db.query("""
                SELECT lifecycle_version FROM files WHERE id=? AND space_id=? AND scope='SPACE_FILE'
                AND lifecycle_state='ACTIVE' AND sha256_hex=? FOR SHARE
                """, (r, i) -> r.getLong(1), file, a.space(), version);
        if (row.isEmpty())
            throw error(RESOURCE_EXPIRED_OR_PURGED);
        var grant = files.verifyGrant(a.user(), a.space(), a.epoch(), GrantType.SPACE_FILE, a.space(), file);
        check("application/pdf".equalsIgnoreCase(grant.contentType()) && version.equals(grant.sha256()));
        return new Pdf(grant, row.getFirst());
    }
    Object queue(Actor a, String file, String version, String key) {
        var pdf = require(a, file, version);
        return s.once(a, "pdf-index/" + file + "/" + version, key, Map.of(), () -> {
            var active = s.db.queryForList("""
                    SELECT p.job_id FROM document_pdf_indexes p JOIN async_jobs j ON j.job_id=p.job_id
                    WHERE p.file_id=? AND p.file_version_id=? AND (p.result_object_key IS NOT NULL OR
                    (p.user_id=? AND p.membership_epoch=? AND j.status IN ('PENDING','RUNNING','RETRY_WAIT')))
                    ORDER BY j.created_at DESC LIMIT 1
                    """, String.class, file, version, a.user(), a.epoch());
            if (!active.isEmpty())
                return Map.of("jobId", active.getFirst());
            String id = id();
            s.db.update("INSERT INTO "
                        + "document_pdf_indexes(job_id,file_id,file_version_id,user_id,membership_epoch) "
                        + "VALUES(?,?,?,?,?)",
                    id, file, version, a.user(), a.epoch());
            jobs.enqueue(new AsyncJobPort.Job(
                    id, "PDF_INDEX", a.space(), file, a.user(), pdf.grant.objectKey(), 1, pdf.lifecycle));
            return Map.of("jobId", id);
        });
    }
    JsonNode pages(Actor a, String file, String version) {
        require(a, file, version);
        String key = s.scalar("""
                SELECT result_object_key FROM document_pdf_indexes WHERE file_id=? AND file_version_id=?
                AND result_object_key IS NOT NULL LIMIT 1
                """, file, version);
        return s.parse(new String(s.read(key, 16 * 1024 * 1024, () -> require(a, file, version)),
                               StandardCharsets.UTF_8))
                .required("pages");
    }
    Object index(Actor a, String file, String version) {
        require(a, file, version);
        String status = s.scalar("""
                SELECT j.status FROM document_pdf_indexes p JOIN async_jobs j ON j.job_id=p.job_id
                WHERE p.file_id=? AND p.file_version_id=? AND (j.status='SUCCEEDED' OR (p.user_id=? AND p.membership_epoch=?))
                ORDER BY p.result_object_key IS NULL,j.created_at DESC LIMIT 1
                """, file, version, a.user(), a.epoch());
        return status.equals("SUCCEEDED") ? Map.of("status", status, "pages", pages(a, file, version))
                                          : Map.of("status", status);
    }
    private static double coordinate(JsonNode node, String key) {
        JsonNode n = node.path(key);
        check(n.isNumber() && n.asDouble() >= 0 && n.asDouble() <= 1);
        return n.asDouble();
    }
    void validateAnchor(Actor a, String file, String version, JsonNode anchor) {
        check(file.equals(text(anchor, "fileId", 160)) && version.equals(text(anchor, "fileVersionId", 160)));
        long page = seq(anchor, "pageNumber");
        JsonNode pages = pages(a, file, version);
        check(page >= 1 && page <= pages.size());
        var data = pages.get((int) page - 1);
        check(data.path("page").asLong() == page);
        var rects = anchor.path("rectangles");
        check(rects.isArray() && !rects.isEmpty() && rects.size() <= 128);
        for (var rect : rects) {
            check(coordinate(rect, "left") < coordinate(rect, "right")
                    && coordinate(rect, "top") < coordinate(rect, "bottom"));
        }
        var selected = new StringJoiner(" ");
        for (var span : data.path("spans")) {
            double x = span.path("x").doubleValue(), y = span.path("y").doubleValue();
            for (var rect : rects) {
                if (x < rect.path("right").asDouble()
                        && x + span.path("width").asDouble() > rect.path("left").asDouble()
                        && y < rect.path("bottom").asDouble()
                        && y + span.path("height").asDouble() > rect.path("top").asDouble()) {
                    selected.add(span.path("text").asText());
                    break;
                }
            }
        }
        String quote = text(anchor, "quotedText", 4096).replaceAll("\\s+", " ").strip();
        check(selected.toString().replaceAll("\\s+", " ").contains(quote));
    }
    @Override
    public AsyncJobPort.ResourceState lockAndCheck(AsyncJobPort.Job job) {
        var rows = s.db.queryForList(
                "SELECT * FROM document_pdf_indexes WHERE job_id=? AND user_id=? AND file_id=? FOR UPDATE",
                job.jobId(), job.actorId(), job.resourceId());
        if (rows.isEmpty())
            return new AsyncJobPort.ResourceState(0, false);
        var row = rows.getFirst();
        var a = new Actor(
                job.spaceId(), job.actorId(), ((Number) row.get("membership_epoch")).longValue(), null);
        try {
            s.member(a, SpaceRole.VIEWER);
            var pdf = require(a, job.resourceId(), (String) row.get("file_version_id"));
            return new AsyncJobPort.ResourceState(
                    pdf.lifecycle, pdf.grant.objectKey().equals(job.inputObjectKey()));
        } catch (DomainException e) {
            return new AsyncJobPort.ResourceState(0, false);
        }
    }
    @Override
    public void attachResult(AsyncJobPort.Job job, String key) {
        s.attach("PDF_INDEX", job.jobId(), key);
        s.db.update("UPDATE document_pdf_indexes SET result_object_key=? WHERE job_id=?", key, job.jobId());
    }
}

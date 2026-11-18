package com.orbitspace.modules.gitproject;

import com.orbitspace.kernel.AuthenticatedPrincipal;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.modules.gitproject.GitProjectDtos.CommitDetailDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.GitProjectDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ParseJobDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ProjectGraphDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.SnapshotDetailDto;
import com.orbitspace.modules.gitproject.GitProjectService.OpenedPatch;
import com.orbitspace.security.RequireSpaceRole;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;

/** Browser REST controller implementing the 7 ACCOUNT routes in gitproject-cli.yaml. */
@RestController
public class GitProjectController {
    private static final String EPOCH_HEADER = "X-Membership-Epoch";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final GitProjectService gitProjects;

    public GitProjectController(GitProjectService gitProjects) {
        this.gitProjects = gitProjects;
    }

    @PostMapping("/api/v1/spaces/{spaceId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireSpaceRole(SpaceRole.ADMIN)
    public GitProjectDto createGitProject(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {
        return gitProjects.createGitProject(
                spaceId, account.userId(), membershipEpoch(request), idempotencyKey, rawBody);
    }

    @GetMapping("/api/v1/spaces/{spaceId}/projects/{projectId}/graph")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public ProjectGraphDto getProjectGraph(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String projectId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        return gitProjects.getProjectGraph(
                spaceId, projectId, account.userId(), membershipEpoch(request), cursor, limit);
    }

    @GetMapping("/api/v1/spaces/{spaceId}/projects/{projectId}/commits/{commitOid}")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public CommitDetailDto getCommit(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String projectId,
            @PathVariable String commitOid,
            @RequestParam(value = "parentOid", required = false) String parentOid,
            HttpServletRequest request) {
        return gitProjects.getCommit(
                spaceId, projectId, account.userId(), membershipEpoch(request), commitOid, parentOid);
    }

    @GetMapping("/api/v1/spaces/{spaceId}/projects/{projectId}/commits/{commitOid}/patch")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public ResponseEntity<StreamingResponseBody> downloadCommitPatch(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String projectId,
            @PathVariable String commitOid,
            @RequestParam(value = "parentOid", required = false) String parentOid,
            HttpServletRequest request) {
        gitProjects.verifyCommitPatchUnavailable(
                spaceId, projectId, account.userId(), membershipEpoch(request), commitOid, parentOid);
        throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
    }

    @GetMapping("/api/v1/spaces/{spaceId}/projects/{projectId}/snapshots/{snapshotId}")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public SnapshotDetailDto getSnapshot(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String projectId,
            @PathVariable String snapshotId,
            HttpServletRequest request) {
        return gitProjects.getSnapshot(
                spaceId, projectId, account.userId(), membershipEpoch(request), snapshotId);
    }

    @GetMapping("/api/v1/spaces/{spaceId}/projects/{projectId}/snapshots/{snapshotId}/patch")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public ResponseEntity<StreamingResponseBody> downloadPatch(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String projectId,
            @PathVariable String snapshotId,
            HttpServletRequest request) {
        OpenedPatch opened =
                gitProjects.openSnapshotPatch(
                        spaceId, projectId, account.userId(), membershipEpoch(request), snapshotId);
        StreamingResponseBody body =
                out -> {
                    try (InputStream in = opened.stream()) {
                        in.transferTo(out);
                    }
                };
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(opened.sizeBytes())
                .body(body);
    }

    @PostMapping(
            "/api/v1/spaces/{spaceId}/projects/{projectId}/snapshots/{snapshotId}/parse-jobs/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequireSpaceRole(SpaceRole.MEMBER)
    public ParseJobDto retrySnapshotParse(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String projectId,
            @PathVariable String snapshotId,
            @PathVariable String jobId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            HttpServletRequest request) {
        return gitProjects.retrySnapshotParse(
                spaceId,
                projectId,
                account.userId(),
                membershipEpoch(request),
                snapshotId,
                jobId,
                idempotencyKey);
    }

    private static long membershipEpoch(HttpServletRequest request) {
        try {
            return Long.parseLong(request.getHeader(EPOCH_HEADER));
        } catch (NumberFormatException ex) {
            throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
        }
    }
}

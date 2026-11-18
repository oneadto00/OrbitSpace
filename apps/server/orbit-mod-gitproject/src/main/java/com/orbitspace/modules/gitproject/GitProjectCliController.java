package com.orbitspace.modules.gitproject;

import com.orbitspace.kernel.DevicePrincipal;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.modules.gitproject.GitProjectDtos.CaptureReceiptDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ParseJobDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ReportSessionDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.WorktreeDto;
import com.orbitspace.security.RequireSpaceRole;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** CLI REST controller implementing the 7 DEVICE routes in gitproject-cli.yaml. */
@RestController
public class GitProjectCliController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final GitProjectService gitProjects;

    public GitProjectCliController(GitProjectService gitProjects) {
        this.gitProjects = gitProjects;
    }

    @PostMapping("/api/v1/cli/projects/{projectId}/worktrees")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireSpaceRole(value = SpaceRole.MEMBER, deviceScope = "metadata:write")
    public WorktreeDto registerWorktree(
            @AuthenticationPrincipal DevicePrincipal device,
            @PathVariable String projectId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) String rawBody) {
        return gitProjects.registerWorktree(device, projectId, idempotencyKey, rawBody);
    }

    @PostMapping("/api/v1/cli/projects/{projectId}/worktrees/{worktreeId}/epoch")
    @RequireSpaceRole(value = SpaceRole.MEMBER, deviceScope = "metadata:write")
    public WorktreeDto advanceWorktreeEpoch(
            @AuthenticationPrincipal DevicePrincipal device,
            @PathVariable String projectId,
            @PathVariable String worktreeId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) String rawBody) {
        return gitProjects.advanceWorktreeEpoch(
                device, projectId, worktreeId, idempotencyKey, rawBody);
    }

    @PostMapping("/api/v1/cli/projects/{projectId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireSpaceRole(value = SpaceRole.MEMBER, deviceScope = "metadata:write")
    public ReportSessionDto createReport(
            @AuthenticationPrincipal DevicePrincipal device,
            @PathVariable String projectId,
            @RequestBody(required = false) String rawBody) {
        return gitProjects.createReport(device, projectId, rawBody);
    }

    @PutMapping("/api/v1/cli/projects/{projectId}/reports/{reportId}/patch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireSpaceRole(value = SpaceRole.MEMBER, deviceScope = "patch:write")
    public void putTemporaryPatch(
            @AuthenticationPrincipal DevicePrincipal device,
            @PathVariable String projectId,
            @PathVariable String reportId,
            HttpServletRequest request)
            throws IOException {
        gitProjects.putTemporaryPatch(device, projectId, reportId, request.getInputStream());
    }

    @PostMapping("/api/v1/cli/projects/{projectId}/reports/{reportId}/complete")
    @RequireSpaceRole(value = SpaceRole.MEMBER, deviceScope = "metadata:write")
    public CaptureReceiptDto completeReport(
            @AuthenticationPrincipal DevicePrincipal device,
            @PathVariable String projectId,
            @PathVariable String reportId,
            @RequestBody(required = false) String rawBody) {
        return gitProjects.completeReport(device, projectId, reportId, rawBody);
    }

    @PostMapping("/api/v1/cli/projects/{projectId}/worktrees/{worktreeId}/clean")
    @RequireSpaceRole(value = SpaceRole.MEMBER, deviceScope = "metadata:write")
    public WorktreeDto reportClean(
            @AuthenticationPrincipal DevicePrincipal device,
            @PathVariable String projectId,
            @PathVariable String worktreeId,
            @RequestBody(required = false) String rawBody) {
        return gitProjects.reportClean(device, projectId, worktreeId, rawBody);
    }

    @PostMapping(
            "/api/v1/cli/projects/{projectId}/snapshots/{snapshotId}/parse-jobs/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequireSpaceRole(value = SpaceRole.MEMBER, deviceScope = "patch:write")
    public ParseJobDto retryOwnReportParse(
            @AuthenticationPrincipal DevicePrincipal device,
            @PathVariable String projectId,
            @PathVariable String snapshotId,
            @PathVariable String jobId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        return gitProjects.retryOwnReportParse(
                device, projectId, snapshotId, jobId, idempotencyKey);
    }
}

package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.orbitspace.kernel.AuthenticatedPrincipal;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.security.RequireSpaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(GitRemoteSourceController.BASE)
class GitRemoteSourceController {
    static final String BASE = "/api/v1/spaces/{spaceId}/projects/{projectId}/git-sources";
    private static final String EPOCH = "X-Membership-Epoch";
    private static final String IDEMPOTENCY = "Idempotency-Key";
    record Bind(String httpsUrl,
                @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String accessToken) {
        @Override public String toString() { return "Bind[credential redacted]"; }
    }
    private final GitRemoteSourceService sources;
    private final GitRemoteComparisonService comparisons;
    GitRemoteSourceController(GitRemoteSourceService sources, GitRemoteComparisonService comparisons) {
        this.sources = sources;
        this.comparisons = comparisons;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireSpaceRole(SpaceRole.MEMBER)
    public GitRemoteSourceService.Source bind(@AuthenticationPrincipal AuthenticatedPrincipal actor,
            @PathVariable String spaceId, @PathVariable String projectId, @RequestHeader(EPOCH) long epoch,
            @RequestHeader(IDEMPOTENCY) String key, @RequestBody Bind body) {
        return sources.bind(spaceId, projectId, actor.userId(), epoch, key, body.httpsUrl(), body.accessToken());
    }

    @GetMapping
    @RequireSpaceRole(SpaceRole.VIEWER)
    public List<GitRemoteSourceService.Source> list(@AuthenticationPrincipal AuthenticatedPrincipal actor,
            @PathVariable String spaceId, @PathVariable String projectId, @RequestHeader(EPOCH) long epoch) {
        return sources.list(spaceId, projectId, actor.userId(), epoch);
    }

    @DeleteMapping("/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireSpaceRole(SpaceRole.VIEWER)
    public void unbind(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable String spaceId,
            @PathVariable String projectId, @PathVariable String sourceId, @RequestHeader(EPOCH) long epoch,
            @RequestHeader(IDEMPOTENCY) String key) {
        sources.unbind(spaceId, projectId, sourceId, actor.userId(), epoch, key);
    }

    @PostMapping("/{sourceId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequireSpaceRole(SpaceRole.MEMBER)
    public GitRemoteSourceService.Receipt sync(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable String spaceId,
            @PathVariable String projectId, @PathVariable String sourceId, @RequestHeader(EPOCH) long epoch,
            @RequestHeader(IDEMPOTENCY) String key) {
        return sources.sync(spaceId, projectId, sourceId, actor.userId(), epoch, key);
    }

    @GetMapping("/{sourceId}/comparison")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public GitRemoteComparisonService.Comparison compare(@AuthenticationPrincipal AuthenticatedPrincipal actor,
            @PathVariable String spaceId, @PathVariable String projectId, @PathVariable String sourceId,
            @RequestHeader(EPOCH) long epoch, @RequestParam String localOid, @RequestParam String remoteRef) {
        return comparisons.compare(spaceId, projectId, sourceId, actor.userId(), epoch, localOid, remoteRef);
    }
}

package com.orbitspace.modules.gitproject;

import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.ACCOUNT;
import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.DEVICE;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.ObjectStoragePort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import java.util.function.Function;
import com.orbitspace.kernel.ModuleSecurityCustomizer;
import com.orbitspace.kernel.ModuleSecurityCustomizer.Route;
import com.orbitspace.kernel.RevocationLedgerPort;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

@AutoConfiguration
@Import({
    GitRemoteSourceService.class,
    GitRemoteSourceController.class,
    GitRemoteSecrets.class,
    GitRemoteComparisonService.class,
    GitProjectIdempotencyStore.class,
    GitProjectScopeService.class,
    GitBackfillService.class,
    GitTwoPointDiffService.class,
    GitCodeCommentService.class,
    GitProjectService.class,
    GitWipPatchParseProcessor.class,
    GitProjectController.class,
    GitProjectCliController.class
})
public class GitProjectAutoConfiguration {
    @Bean
    ModuleSecurityCustomizer gitProjectRoutes() {
        return () ->
                List.of(
                        accountRoute("GET", GitRemoteSourceController.BASE),
                        accountRoute("POST", GitRemoteSourceController.BASE),
                        accountRoute("DELETE", GitRemoteSourceController.BASE + "/{sourceId}"),
                        accountRoute("POST", GitRemoteSourceController.BASE + "/{sourceId}/sync"),
                        accountRoute("GET", GitRemoteSourceController.BASE + "/{sourceId}/comparison"),
                        accountRoute("POST", "/api/v1/spaces/{spaceId}/projects"),
                        accountRoute("GET", "/api/v1/spaces/{spaceId}/projects/{projectId}/graph"),
                        accountRoute(
                                "GET",
                                "/api/v1/spaces/{spaceId}/projects/{projectId}/commits/{commitOid}"),
                        accountRoute(
                                "GET",
                                "/api/v1/spaces/{spaceId}/projects/{projectId}/commits/{commitOid}/patch"),
                        accountRoute(
                                "GET",
                                "/api/v1/spaces/{spaceId}/projects/{projectId}/snapshots/{snapshotId}"),
                        accountRoute(
                                "GET",
                                "/api/v1/spaces/{spaceId}/projects/{projectId}/snapshots/{snapshotId}/patch"),
                        accountRoute(
                                "POST",
                                "/api/v1/spaces/{spaceId}/projects/{projectId}/snapshots/{snapshotId}/parse-jobs/{jobId}/retry"),
                        accountRoute("POST", "/api/v1/spaces/{spaceId}/projects/{projectId}/diffs"),
                        accountRoute(
                                "GET",
                                "/api/v1/spaces/{spaceId}/projects/{projectId}/diffs/{diffId}"),
                        accountRoute(
                                "GET",
                                "/api/v1/spaces/{spaceId}/projects/{projectId}/code-comments"),
                        accountRoute(
                                "POST",
                                "/api/v1/spaces/{spaceId}/projects/{projectId}/code-comments"),
                        accountRoute(
                                "POST",
                                "/api/v1/spaces/{spaceId}/projects/{projectId}/code-comments/{commentId}/replies"),
                        accountRoute(
                                "POST",
                                "/api/v1/spaces/{spaceId}/projects/{projectId}/code-comments/{commentId}/discussion"),
                        accountRoute(
                                "POST",
                                "/api/v1/spaces/{spaceId}/projects/{projectId}/code-comments/{commentId}/forward-chat"),
                        deviceRoute("POST", "/api/v1/cli/projects/{projectId}/worktrees"),
                        deviceRoute(
                                "POST",
                                "/api/v1/cli/projects/{projectId}/worktrees/{worktreeId}/epoch"),
                        deviceRoute("POST", "/api/v1/cli/projects/{projectId}/reports"),
                        deviceRoute(
                                "PUT",
                                "/api/v1/cli/projects/{projectId}/reports/{reportId}/patch"),
                        deviceRoute(
                                "POST",
                                "/api/v1/cli/projects/{projectId}/reports/{reportId}/complete"),
                        deviceRoute(
                                "POST",
                                "/api/v1/cli/projects/{projectId}/worktrees/{worktreeId}/clean"),
                        deviceRoute(
                                "POST",
                                "/api/v1/cli/projects/{projectId}/snapshots/{snapshotId}/parse-jobs/{jobId}/retry"),
                        deviceRoute("POST", "/api/v1/cli/projects/{projectId}/backfill/check"),
                        deviceRoute("POST", "/api/v1/cli/projects/{projectId}/backfill/sessions"),
                        deviceRoute(
                                "PUT",
                                "/api/v1/cli/projects/{projectId}/backfill/sessions/{backfillId}/objects"),
                        deviceRoute(
                                "POST",
                                "/api/v1/cli/projects/{projectId}/backfill/sessions/{backfillId}/complete"));
    }

    @Bean("remoteGitWorkerInput")
    Function<AsyncJobPort.Job,Map<String,Object>> remoteGitWorkerInput(GitRemoteSourceService sources) {
        return sources::workerInput;
    }

    @Bean("remoteGitResultTarget")
    AsyncJobPort.ResultTarget remoteGitResultTarget(GitRemoteSourceService sources, JdbcTemplate db,
                                                    ObjectStoragePort storage, ObjectMapper mapper) {
        return new GitRemoteResultStore(sources, db, storage, mapper);
    }

    @Bean("orbitReplayRESOURCE_FORCE_PURGE")
    Consumer<RevocationLedgerPort.Entry> orbitReplayResourceForcePurge(
            GitProjectService gitProjects, GitRemoteSourceService sources, PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return entry -> tx.executeWithoutResult(status -> {
            if (!sources.replay(entry)) gitProjects.replayResourceForcePurge(entry);
        });
    }

    private static Route accountRoute(String method, String pathPattern) {
        return new Route(method, pathPattern, ACCOUNT, true);
    }

    private static Route deviceRoute(String method, String pathPattern) {
        return new Route(method, pathPattern, DEVICE, true);
    }
}

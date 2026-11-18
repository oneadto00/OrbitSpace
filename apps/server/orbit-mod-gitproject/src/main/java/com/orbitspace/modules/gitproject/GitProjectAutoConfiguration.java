package com.orbitspace.modules.gitproject;

import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.ACCOUNT;
import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.DEVICE;

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

/** Spring Boot auto-configuration for orbit-mod-gitproject routes, services, SPIs, and replay. */
@AutoConfiguration
@Import({
    GitProjectIdempotencyStore.class,
    GitProjectScopeService.class,
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
                                "/api/v1/cli/projects/{projectId}/snapshots/{snapshotId}/parse-jobs/{jobId}/retry"));
    }

    @Bean("orbitReplayRESOURCE_FORCE_PURGE")
    Consumer<RevocationLedgerPort.Entry> orbitReplayResourceForcePurge(
            GitProjectService gitProjects, PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return entry -> tx.executeWithoutResult(status -> gitProjects.replayResourceForcePurge(entry));
    }

    private static Route accountRoute(String method, String pathPattern) {
        return new Route(method, pathPattern, ACCOUNT, true);
    }

    private static Route deviceRoute(String method, String pathPattern) {
        return new Route(method, pathPattern, DEVICE, true);
    }
}

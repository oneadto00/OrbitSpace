package com.orbitspace.modules.space;

import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.ACCOUNT;

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
    SpaceAuthorizationService.class,
    SpaceIdempotencyStore.class,
    SpaceService.class,
    SpaceController.class
})
public class SpaceAutoConfiguration {
    @Bean
    ModuleSecurityCustomizer spaceRoutes() {
        return () -> List.of(
                accountRoute("POST", "/api/v1/spaces"),
                accountRoute("GET", "/api/v1/spaces"),
                accountRoute("GET", "/api/v1/spaces/{spaceId}"),
                accountRoute("GET", "/api/v1/spaces/{spaceId}/members"),
                accountRoute("GET", "/api/v1/spaces/{spaceId}/invites"),
                accountRoute("POST", "/api/v1/spaces/{spaceId}/invites"),
                accountRoute("DELETE", "/api/v1/spaces/{spaceId}/invites/{inviteId}"),
                accountRoute("POST", "/api/v1/space-invites/join"),
                accountRoute("PATCH", "/api/v1/spaces/{spaceId}/members/{userId}"),
                accountRoute("DELETE", "/api/v1/spaces/{spaceId}/members/{userId}"),
                accountRoute("PUT", "/api/v1/spaces/{spaceId}/owner"),
                accountRoute("POST", "/api/v1/spaces/{spaceId}/leave"));
    }

    @Bean("orbitReplayMEMBERSHIP_EPOCH")
    Consumer<RevocationLedgerPort.Entry> orbitReplayMembershipEpoch(
            SpaceService spaces, PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return entry -> tx.executeWithoutResult(status -> spaces.replayMembershipRevocation(entry));
    }

    private static Route accountRoute(String method, String pathPattern) {
        return new Route(method, pathPattern, ACCOUNT, true);
    }
}

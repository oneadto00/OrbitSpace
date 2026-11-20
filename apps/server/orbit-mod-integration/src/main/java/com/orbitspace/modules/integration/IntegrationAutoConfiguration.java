package com.orbitspace.modules.integration;

import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.PUBLIC;

import com.orbitspace.kernel.ModuleSecurityCustomizer;
import com.orbitspace.kernel.ModuleSecurityCustomizer.Route;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.OutboxEventHandler;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@Import({
    IntegrationHmacSupport.class,
    IntegrationEgressService.class,
    IntegrationCollabController.class,
    IntegrationCollabEventHandler.class,
    IntegrationJobResultService.class,
    IntegrationJobResultController.class
})
public class IntegrationAutoConfiguration {
    @Bean
    OutboxEventHandler membershipCollabControl(StringRedisTemplate redis, ObjectMapper json) {
        return new IntegrationCollabControlHandler("MEMBERSHIP_REVOKED",redis,json);
    }

    @Bean
    OutboxEventHandler documentCollabControl(StringRedisTemplate redis, ObjectMapper json) {
        return new IntegrationCollabControlHandler("DOCUMENT_",redis,json);
    }

    @Bean
    ModuleSecurityCustomizer integrationRoutes() {
        // Registered as PUBLIC + csrfExempt so CredentialFilter does not treat worker calls as user JWTs;
        // IntegrationHmacSupport strictly enforces HMAC-SHA256 + timestamp window + atomic nonce on every route.
        return () ->
                List.of(
                        internalRoute("/internal/v1/jobs/{jobId}/claim"),
                        internalRoute("/internal/v1/jobs/{jobId}/input"),
                        internalRoute("/internal/v1/collab/authenticate"),
                        internalRoute("/internal/v1/collab/load"),
                        internalRoute("/internal/v1/collab/store-batch"),
                        internalRoute("/internal/v1/collab/revoke-check"),
                        internalRoute("/internal/v1/jobs/{jobId}/output"),
                        internalRoute("/internal/v1/jobs/{jobId}/result"),
                        internalRoute("/internal/v1/jobs/{jobId}/fail"));
    }

    private static Route internalRoute(String pathPattern) {
        return new Route("POST", pathPattern, PUBLIC, true);
    }
}

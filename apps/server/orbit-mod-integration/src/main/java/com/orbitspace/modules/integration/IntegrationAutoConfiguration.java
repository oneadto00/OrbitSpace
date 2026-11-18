package com.orbitspace.modules.integration;

import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.PUBLIC;

import com.orbitspace.kernel.ModuleSecurityCustomizer;
import com.orbitspace.kernel.ModuleSecurityCustomizer.Route;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;

/** Spring Boot auto-configuration for P2 internal worker callback endpoints and HMAC anti-replay. */
@AutoConfiguration
@Import({
    IntegrationHmacSupport.class,
    IntegrationJobResultService.class,
    IntegrationJobResultController.class
})
public class IntegrationAutoConfiguration {
    @Bean
    ModuleSecurityCustomizer integrationRoutes() {
        // Registered as PUBLIC + csrfExempt so CredentialFilter does not treat worker calls as user JWTs;
        // IntegrationHmacSupport strictly enforces HMAC-SHA256 + timestamp window + atomic nonce on every route.
        return () ->
                List.of(
                        internalRoute("/internal/v1/jobs/{jobId}/claim"),
                        internalRoute("/internal/v1/jobs/{jobId}/output"),
                        internalRoute("/internal/v1/jobs/{jobId}/result"),
                        internalRoute("/internal/v1/jobs/{jobId}/fail"));
    }

    private static Route internalRoute(String pathPattern) {
        return new Route("POST", pathPattern, PUBLIC, true);
    }
}

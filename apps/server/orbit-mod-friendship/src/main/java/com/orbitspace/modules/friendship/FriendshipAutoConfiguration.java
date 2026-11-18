package com.orbitspace.modules.friendship;

import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.ACCOUNT;

import com.orbitspace.kernel.ModuleSecurityCustomizer;
import com.orbitspace.kernel.ModuleSecurityCustomizer.Route;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;

/** Spring Boot auto-configuration registering friendship routes, service, and FriendshipQueryPort. */
@AutoConfiguration
@Import({FriendshipService.class, FriendshipController.class})
public class FriendshipAutoConfiguration {
    @Bean
    ModuleSecurityCustomizer friendshipRoutes() {
        return () ->
                List.of(
                        accountRoute("GET", "/api/v1/users/search"),
                        accountRoute("POST", "/api/v1/friend-requests"),
                        accountRoute("GET", "/api/v1/friend-requests"),
                        accountRoute("POST", "/api/v1/friend-requests/{requestId}/accept"),
                        accountRoute("POST", "/api/v1/friend-requests/{requestId}/reject"),
                        accountRoute("POST", "/api/v1/friend-requests/{requestId}/cancel"),
                        accountRoute("GET", "/api/v1/friends"),
                        accountRoute("DELETE", "/api/v1/friends/{friendUserId}"));
    }

    private static Route accountRoute(String method, String pathPattern) {
        return new Route(method, pathPattern, ACCOUNT, true);
    }
}

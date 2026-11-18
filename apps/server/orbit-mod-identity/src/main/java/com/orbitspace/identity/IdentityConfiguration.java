package com.orbitspace.identity;

import com.orbitspace.kernel.ModuleSecurityCustomizer;
import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.*;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@AutoConfiguration
@Import({AccountService.class, SessionService.class, DeviceService.class, AccessTokens.class,
        IdentityAuthentication.class, AccountController.class, DeviceController.class, RequestLimits.class})
public class IdentityConfiguration {
    @Bean("orbitReplayDEVICE_CREDENTIAL")
    java.util.function.Consumer<com.orbitspace.kernel.RevocationLedgerPort.Entry> deviceReplay(DeviceService devices) {
        return devices::replay;
    }

    @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean ModuleSecurityCustomizer identityRoutes() {
        return () -> List.of(
                route("POST", "/register", PUBLIC), route("POST", "/login", PUBLIC),
                route("POST", "/verify-email", PUBLIC), route("POST", "/password-reset/request", PUBLIC),
                route("POST", "/password-reset/confirm", PUBLIC), route("GET", "/csrf", REFRESH_COOKIE),
                route("POST", "/refresh", REFRESH_COOKIE), route("POST", "/logout", REFRESH_COOKIE),
                route("GET", "/profile", ACCOUNT), route("PATCH", "/profile", ACCOUNT),
                route("GET", "/sessions", ACCOUNT), route("DELETE", "/sessions/{sessionId}", ACCOUNT),
                route("POST", "/device/authorize", PUBLIC), route("POST", "/device/token", PUBLIC),
                route("POST", "/device/lookup", ACCOUNT), route("POST", "/device/approve", ACCOUNT),
                route("GET", "/device/credentials", ACCOUNT), route("DELETE", "/device/credentials/{credentialId}", ACCOUNT));
    }

    private static ModuleSecurityCustomizer.Route route(String method, String path, ModuleSecurityCustomizer.Access access) {
        return new ModuleSecurityCustomizer.Route(method, "/api/v1/auth" + path, access, access != REFRESH_COOKIE);
    }
}

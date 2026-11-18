package com.orbitspace.kernel;

import java.util.List;
import java.util.Objects;

/** Modules declare method-specific routes; the security module combines declarations and denies unknown routes. */
public interface ModuleSecurityCustomizer {
    enum Access { PUBLIC, ACCOUNT, DEVICE, REFRESH_COOKIE }
    record Route(String method, String pathPattern, Access access, boolean csrfExempt) {
        public Route {
            Objects.requireNonNull(method);
            Objects.requireNonNull(pathPattern);
            Objects.requireNonNull(access);
            if (access == Access.REFRESH_COOKIE && csrfExempt
                    && !("GET".equals(method) || "HEAD".equals(method))) {
                throw new IllegalArgumentException("Cookie mutations require CSRF protection");
            }
        }
    }

    List<Route> routes();
}

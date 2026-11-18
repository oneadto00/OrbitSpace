package com.orbitspace.kernel;

import java.security.Principal;
import java.util.Objects;

/** An account session may access several spaces, each with its own membership epoch. */
public record AuthenticatedPrincipal(String userId, String sessionId) implements Principal {
    public AuthenticatedPrincipal {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(sessionId);
    }

    @Override
    public String getName() {
        return userId;
    }
}

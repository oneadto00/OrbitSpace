package com.orbitspace.kernel;

import java.security.Principal;
import java.util.Objects;
import java.util.Set;

/** Identity and grant binding resolved from a live device credential. Contains no bearer material. */
public record DevicePrincipal(String userId, String deviceId, String spaceId, String projectId,
                              Set<String> scopes, long membershipEpoch) implements Principal {
    public DevicePrincipal {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(projectId);
        scopes = Set.copyOf(scopes);
        if (membershipEpoch < 1 || scopes.isEmpty()) {
            throw new IllegalArgumentException("An active epoch and granted scopes are required");
        }
    }

    @Override
    public String getName() {
        return userId;
    }
}

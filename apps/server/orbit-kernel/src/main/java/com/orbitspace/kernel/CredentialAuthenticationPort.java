package com.orbitspace.kernel;

/** Identity verifies the bearer material and checks revocation before security installs a principal. */
public interface CredentialAuthenticationPort {
    AuthenticatedPrincipal authenticateAccount(String accessToken);
    DevicePrincipal authenticateDevice(String deviceToken);
}

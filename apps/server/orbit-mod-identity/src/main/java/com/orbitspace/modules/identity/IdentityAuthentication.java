package com.orbitspace.modules.identity;

import com.orbitspace.kernel.*;

final class IdentityAuthentication implements CredentialAuthenticationPort {
    private final IdentityAccessTokenService tokens;
    private final SessionService sessions;
    private final DeviceService devices;

    IdentityAuthentication(IdentityAccessTokenService tokens, SessionService sessions, DeviceService devices) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.devices = devices;
    }

    @Override public AuthenticatedPrincipal authenticateAccount(String raw) {
        var account = tokens.parse(raw);
        sessions.requireActive(account);
        return account;
    }

    @Override public DevicePrincipal authenticateDevice(String raw) {
        return devices.authenticate(raw);
    }
}

package com.orbitspace.security;

import com.orbitspace.kernel.AuthenticatedPrincipal;

/** Identity is the authority for both HTTP cookie sessions and already-issued WS grants. */
public interface BrowserSessionPort {
    AuthenticatedPrincipal fromRefresh(String refresh, boolean revokeOnReplay);
    void requireActive(AuthenticatedPrincipal principal);
}

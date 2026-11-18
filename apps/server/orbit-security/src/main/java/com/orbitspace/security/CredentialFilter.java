package com.orbitspace.security;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import com.orbitspace.kernel.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class CredentialFilter extends OncePerRequestFilter {
    private final List<SecurityConfig.Rule> rules;
    private final ObjectProvider<CredentialAuthenticationPort> credentials;
    private final ObjectProvider<BrowserSessions> sessions;
    private final HandlerExceptionResolver errors;

    CredentialFilter(List<SecurityConfig.Rule> rules, ObjectProvider<CredentialAuthenticationPort> credentials,
            ObjectProvider<BrowserSessions> sessions, HandlerExceptionResolver errors) {
        this.rules = rules;
        this.credentials = credentials;
        this.sessions = sessions;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        var rule = rules.stream().filter(r -> r.matcher().matches(req)).findFirst().orElse(null);
        if (rule != null && rule.route().access() != ModuleSecurityCustomizer.Access.PUBLIC) {
            try {
                Principal principal = switch (rule.route().access()) {
                    case ACCOUNT -> credentials.getObject().authenticateAccount(bearer(req));
                    case DEVICE -> credentials.getObject().authenticateDevice(bearer(req));
                    case REFRESH_COOKIE -> sessions.getObject().fromRefresh(RefreshCsrfTokens.cookie(req),
                            rule.route().pathPattern().equals("/api/v1/auth/refresh"));
                    case PUBLIC -> throw new IllegalStateException();
                };
                if (principal instanceof DevicePrincipal device) {
                    var variables = rule.matcher().matcher(req).getVariables();
                    if (!device.projectId().equals(variables.get("projectId"))
                            || variables.containsKey("spaceId") && !device.spaceId().equals(variables.get("spaceId"))) {
                        throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
                    }
                }
                var context = SecurityContextHolder.createEmptyContext();
                var authority = new SimpleGrantedAuthority(rule.route().access().name());
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(authority)));
                SecurityContextHolder.setContext(context);
            } catch (RuntimeException ex) {
                errors.resolveException(req, res, null, ex);
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static String bearer(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || header.length() > 4096 || !header.regionMatches(true, 0, "Bearer ", 0, 7)
                || header.length() <= 7) throw new DomainException(KernelErrorCode.AUTH_UNAUTHORIZED);
        return header.substring(7);
    }
}

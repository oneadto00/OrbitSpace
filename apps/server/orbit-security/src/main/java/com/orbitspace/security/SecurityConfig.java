package com.orbitspace.security;

import com.orbitspace.kernel.CredentialAuthenticationPort;
import com.orbitspace.kernel.ModuleSecurityCustomizer;
import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.*;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;
import jakarta.servlet.http.HttpServletRequest;

@AutoConfiguration(beforeName = "com.orbitspace.app.config.FoundationSecurityAutoConfiguration")
@Import({SpaceAccess.class, SpaceRoleAspect.class, WebSocketSecurity.class})
public class SecurityConfig {
    record Rule(RequestMatcher matcher, ModuleSecurityCustomizer.Route route) {}

    @Bean
    SecurityFilterChain applicationSecurity(HttpSecurity http, List<ModuleSecurityCustomizer> modules,
            ObjectProvider<CredentialAuthenticationPort> credentials, ObjectProvider<BrowserSessions> sessions,
            AuthenticationEntryPoint entryPoint, AccessDeniedHandler denied,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        var rules = modules.stream().flatMap(module -> module.routes().stream())
                .map(route -> new Rule(paths.matcher(HttpMethod.valueOf(route.method()), route.pathPattern()), route))
                .toList();
        // Never accept the CSRF token from a query string, cookie or form field.
        var headersOnly = new CsrfTokenRequestAttributeHandler() {
            @Override public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
                return request.getHeader(token.getHeaderName());
            }
        };
        return http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable()).logout(c -> c.disable())
                .formLogin(c -> c.disable()).httpBasic(c -> c.disable())
                .csrf(csrf -> csrf.csrfTokenRepository(new RefreshCsrfTokens()).csrfTokenRequestHandler(headersOnly)
                        .requireCsrfProtectionMatcher(req -> !Set.of("GET","HEAD","OPTIONS","TRACE").contains(req.getMethod())
                                && rules.stream().anyMatch(rule -> rule.matcher.matches(req) && !rule.route.csrfExempt())))
                .authorizeHttpRequests(auth -> {
                    for (Rule rule : rules) {
                        var route = auth.requestMatchers(rule.matcher);
                        if (rule.route.access() == PUBLIC) route.permitAll();
                        else route.hasAuthority(rule.route.access().name());
                    }
                    auth.anyRequest().denyAll();
                })
                .exceptionHandling(handler -> handler.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .addFilterAfter(new CredentialFilter(rules, credentials, sessions, errors), CsrfFilter.class)
                .build();
    }
}

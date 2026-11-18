package com.orbitspace.app.config;

import com.orbitspace.app.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Allow feature auto-configurations to supply a chain before applying the fail-closed default. */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
public class FoundationSecurityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain foundationSecurity(HttpSecurity http, GlobalExceptionHandler errors) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(config -> config.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .build();
    }
}

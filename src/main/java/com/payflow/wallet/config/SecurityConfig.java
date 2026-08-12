package com.payflow.wallet.config;

import com.payflow.wallet.security.JwtAuthenticationEntryPoint;
import com.payflow.wallet.security.JwtAuthenticationFilter;
import com.payflow.wallet.security.RestAccessDeniedHandler;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Web security for wallet-service: stateless bearer-token authentication, with no public
 * business endpoints at all.
 *
 * <p>Unlike auth-service, which must expose {@code /login} and {@code /register} to
 * unauthenticated callers, every endpoint here moves or reveals money. The only
 * {@code permitAll} rules are for health probes and the API contract.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Internal endpoints, called by payment-service rather than by a person.
     *
     * <p>These credit and debit an <em>arbitrary</em> wallet named in the path, so they
     * cannot simply be "authenticated": any logged-in user holding a valid token could
     * otherwise POST to {@code /api/v1/wallets/{their-own-id}/credit} and mint themselves
     * money. Restricting them to {@code ROLE_ADMIN} means an ordinary user token is
     * rejected with a 403 by the authorisation rules, before a controller is reached.
     *
     * <p>{@code ROLE_ADMIN} is what the existing platform offers — auth-service models
     * only {@code USER} and {@code ADMIN} — so payment-service authenticates with an admin
     * token. A dedicated service role, or mutual TLS with these paths not routable from
     * the public gateway at all, would be the stronger arrangement.
     */
    private static final String[] INTERNAL_ENDPOINTS = {
        "/api/v1/wallets/*/credit",
        "/api/v1/wallets/*/debit"
    };

    /** Health probes and the API contract, needed by orchestrators and client tooling. */
    private static final String[] INFRASTRUCTURE_ENDPOINTS = {
        "/actuator/health",
        "/actuator/health/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/v3/api-docs",
        "/v3/api-docs/**"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    /**
     * The filter chain.
     *
     * <p>Rule order is significant: the internal-endpoint rule is declared first because
     * {@code authorizeHttpRequests} matches top-down and stops at the first hit. Placing
     * {@code anyRequest().authenticated()} above it would swallow those paths and silently
     * drop the role requirement.
     *
     * @param http the builder Spring Security hands us
     * @return the configured chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // CSRF protection defends against a browser silently attaching ambient
                // credentials (a session cookie) to a forged cross-site request. This
                // service issues no cookies and keeps no session: the only credential is
                // a bearer token that JavaScript must read from storage and set on an
                // Authorization header, which a cross-site form post cannot do. With no
                // ambient credential to abuse, CSRF tokens would guard nothing.
                .csrf(csrf -> csrf.disable())

                // No HTTP session is ever created; the JWT carries the whole identity.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, INTERNAL_ENDPOINTS).hasRole("ADMIN")
                        .requestMatchers(INFRASTRUCTURE_ENDPOINTS).permitAll()
                        // Everything else — including /actuator/metrics and /prometheus,
                        // which carry operational detail — needs a valid token. The /me
                        // endpoints need no rule beyond this: they read the wallet owner
                        // from the token's claims, never from the request, so there is no
                        // parameter for one user to point at another user's wallet.
                        .anyRequest().authenticated())

                // Authentication and authorisation failures happen inside the chain,
                // where @RestControllerAdvice cannot reach; these keep the error body
                // identical to every other error the service returns.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Stops Boot from also registering {@link JwtAuthenticationFilter} directly with the
     * servlet container.
     *
     * <p>Any {@code Filter} bean is auto-registered by servlet auto-configuration, which
     * would place this one in front of the whole application rather than at its intended
     * position inside the security chain. The chain wiring above is the only registration
     * that should exist.
     *
     * @param filter the JWT filter, wired into the chain by {@code securityFilterChain}
     * @return a registration whose sole purpose is to be disabled
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterContainerRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}

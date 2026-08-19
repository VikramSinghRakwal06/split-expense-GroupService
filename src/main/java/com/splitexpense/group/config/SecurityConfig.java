package com.splitexpense.group.config;

import com.splitexpense.group.security.JwtAuthenticationEntryPoint;
import com.splitexpense.group.security.JwtAuthenticationFilter;
import com.splitexpense.group.security.RestAccessDeniedHandler;
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
 * Web security for group-service: stateless bearer-token authentication, with no public
 * business endpoints at all.
 *
 * <p>Unlike auth-service, which must expose {@code /login} and {@code /register} to
 * unauthenticated callers, every endpoint here reveals or changes what people owe each
 * other. The only {@code permitAll} rules are for health probes and the API contract.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Internal endpoints, called by expense-service rather than by a person.
     *
     * <p>This one applies debts between <em>arbitrary</em> members named in the body, so it
     * cannot simply be "authenticated": any logged-in user holding a valid token could
     * otherwise apply a delta clearing everything they owe. Restricting it to
     * {@code ROLE_ADMIN} means an ordinary user token is rejected with a 403 by the
     * authorisation rules, before a controller is reached.
     *
     * <p>{@code ROLE_ADMIN} is what the existing platform offers — auth-service models only
     * {@code USER} and {@code ADMIN} — so expense-service authenticates with an admin token.
     * A dedicated service role, or mutual TLS with this path not routable from the public
     * gateway at all, would be the stronger arrangement.
     *
     * <p>Note the literal colon: the path is {@code /balances:apply}, an action on the
     * collection rather than a sub-resource of it. Ant matching treats the colon as an
     * ordinary character, so the pattern matches exactly the one endpoint.
     */
    private static final String[] INTERNAL_ENDPOINTS = {
        "/api/v1/groups/*/balances:apply"
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
                        // endpoint needs no rule beyond this: it reads the caller from the
                        // token's claims, never from the request, so there is no parameter
                        // for one user to point at another user's groups.
                        //
                        // The /{groupId} endpoints DO name a resource, so authentication
                        // alone is not enough for them. Their membership check lives in
                        // GroupService and GroupTransactionService rather than here,
                        // because it needs a database read that this chain cannot make.
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

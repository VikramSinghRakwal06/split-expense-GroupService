package com.splitexpense.group.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns an {@code Authorization: Bearer <jwt>} header into an authenticated
 * {@code SecurityContext} for the duration of one request.
 *
 * <h2>No database, no network</h2>
 *
 * <p>The entire authentication decision is a signature check over the request's own bytes.
 * There is no {@code UserDetailsService} lookup here and no call back to auth-service —
 * this service holds no user table and could not perform either. That is deliberate: it
 * keeps authentication a constant-time local operation and means wallet-service stays
 * available for reads even while auth-service is down.
 *
 * <h2>Where this sits in the chain</h2>
 *
 * <p>Registered <em>before</em> {@code UsernamePasswordAuthenticationFilter}, so the
 * {@code SecurityContext} is populated by the time the authorisation rules in
 * {@code SecurityConfig} are evaluated. A filter placed after it would be too late to
 * affect the decision.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>A missing, malformed, expired or otherwise unusable token is <strong>not</strong> an
 * error here — the filter leaves the context empty and calls the rest of the chain.
 * Rejecting the request is the job of the authorisation rules, which is what lets the same
 * filter sit in front of public endpoints like {@code /actuator/health} without
 * special-casing them. The caller sees a 401 from {@link JwtAuthenticationEntryPoint} only
 * if the endpoint actually required authentication.
 *
 * <p>Extending {@code OncePerRequestFilter} guarantees the work happens a single time per
 * request even when a forward or error dispatch re-enters the chain.
 *
 * @see JwtTokenValidator for exactly which claims are checked
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    /** Spring Security's convention: an authority backing {@code hasRole("X")} is "ROLE_X". */
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtTokenValidator tokenValidator;

    public JwtAuthenticationFilter(JwtTokenValidator tokenValidator) {
        this.tokenValidator = tokenValidator;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractBearerToken(request);

        if (token == null || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            authenticate(request, tokenValidator.validate(token));
        } catch (Exception ex) {
            // Covers bad signatures, expired tokens, refresh tokens presented as bearer
            // credentials and malformed claims. Logged at debug because unauthenticated
            // traffic is routine and an attacker should not be able to fill the logs by
            // sending junk tokens.
            log.debug("Bearer token rejected on {}: {}",
                    request.getRequestURI(), ex.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Places the verified caller in the context, with no credentials attached — the token
     * has already been checked and must not be retained.
     */
    private void authenticate(HttpServletRequest request, AuthenticatedUser caller) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(caller, null, authorities(caller));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * auth-service issues exactly one role per token, in a {@code role} claim holding the
     * bare name ({@code USER}, {@code ADMIN}). Spring Security expects the {@code ROLE_}
     * prefix on the authority, so it is added here rather than being baked into the token.
     *
     * @return the caller's authorities, empty if the token carried no role
     */
    private List<GrantedAuthority> authorities(AuthenticatedUser caller) {
        if (caller.role() == null || caller.role().isBlank()) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority(ROLE_PREFIX + caller.role()));
    }

    /**
     * @return the raw JWT, or null when the header is absent or not a bearer credential
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}

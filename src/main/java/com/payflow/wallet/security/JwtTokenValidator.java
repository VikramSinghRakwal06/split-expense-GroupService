package com.payflow.wallet.security;

import com.payflow.wallet.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Verifies access tokens minted by auth-service and turns them into an
 * {@link AuthenticatedUser}.
 *
 * <p>Named a <em>validator</em> rather than a {@code JwtService} on purpose: the
 * counterpart class in auth-service both signs and verifies, whereas this one is
 * structurally incapable of signing. There is no {@code generate} method here and there
 * must never be one — two services minting credentials against the same key would make
 * "who issued this token?" unanswerable during an incident.
 *
 * <h2>What is checked</h2>
 *
 * <p>All of it happens in {@link #validate(String)}, in one parse:
 *
 * <ul>
 *   <li><strong>Signature</strong> — HMAC-SHA over the shared secret. This is the whole
 *       basis of trust: a token that verifies was produced by something holding the
 *       key.</li>
 *   <li><strong>Expiry</strong> — enforced by JJWT against the {@code exp} claim. Access
 *       tokens are short-lived precisely because nothing can revoke one; this service
 *       cannot ask auth-service whether a token is still good, and does not try.</li>
 *   <li><strong>Issuer</strong> — required to equal the configured value, so a token
 *       signed by a different environment sharing a key is rejected.</li>
 *   <li><strong>Token type</strong> — required to be {@code access}. Without this check a
 *       stolen 7-day refresh token could be replayed here as a bearer credential, which
 *       is exactly the escalation the two-token split exists to prevent.</li>
 * </ul>
 *
 * <p>No database is touched. See {@link AuthenticatedUser} for why there is nothing to
 * look up.
 */
@Service
public class JwtTokenValidator {

    /** Claim naming the token's purpose, so access and refresh cannot be swapped. */
    static final String CLAIM_TYPE = "type";

    /** Claim carrying the user's surrogate id, so this service needs no user lookup. */
    static final String CLAIM_USER_ID = "uid";

    /** Claim carrying the authorisation role. */
    static final String CLAIM_ROLE = "role";

    static final String TYPE_ACCESS = "access";

    /**
     * Built once and reused. {@code JwtParser} is thread-safe and immutable after
     * {@code build()}, and re-deriving the HMAC key per request would be pure overhead on
     * the hottest path in the service.
     */
    private final JwtParser parser;

    public JwtTokenValidator(JwtProperties properties) {
        SecretKey signingKey =
                Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));

        this.parser = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                // Declaring the requirement here rather than checking it afterwards means
                // a refresh token fails during parsing and can never reach the caller as a
                // half-validated claim set.
                .require(CLAIM_TYPE, TYPE_ACCESS)
                .build();
    }

    /**
     * Verifies a bearer token and extracts the caller's identity from it.
     *
     * @param token the raw compact JWT from the {@code Authorization} header
     * @return the caller the token asserts
     * @throws JwtException             if the signature, expiry, issuer or token type
     *                                  check fails
     * @throws IllegalArgumentException if the token is null, blank, or carries a
     *                                  {@code uid} claim that is not a UUID
     */
    public AuthenticatedUser validate(String token) {
        Claims claims = parser.parseSignedClaims(token).getPayload();

        String userId = claims.get(CLAIM_USER_ID, String.class);
        if (userId == null) {
            // Correctly signed but unusable: every wallet lookup keys off this claim, and
            // guessing an identity is not an option.
            throw new IllegalArgumentException("Token carries no " + CLAIM_USER_ID + " claim");
        }

        return new AuthenticatedUser(
                UUID.fromString(userId),
                claims.getSubject(),
                claims.get(CLAIM_ROLE, String.class));
    }
}

package com.splitexpense.group.config;

import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised JWT settings, bound from the {@code splitexpense.jwt.*} configuration prefix.
 *
 * <p>Carries no token lifetimes, unlike auth-service's equivalent. This service only ever
 * <em>verifies</em> tokens; it has no signing endpoint and no ability to mint one, so an
 * expiry setting here would describe nothing.
 *
 * <p>Both values must match auth-service exactly. The secret is obvious — HMAC
 * verification fails otherwise — but the issuer matters just as much: it is checked on
 * every parse, so a token correctly signed by a <em>different</em> SplitExpense environment
 * that happens to share a key is still rejected.
 *
 * <p>Nothing here has a compiled-in default. The dev fallbacks live in
 * {@code application.yml} as {@code ${JWT_SECRET:...}} placeholders;
 * {@code application-prod.yml} removes them, so a production start-up without a real
 * secret fails fast rather than trusting a value from source control.
 *
 * @param secret HMAC-SHA signing key, at least 32 bytes for HS256
 * @param issuer value required in the {@code iss} claim
 */
@Validated
@ConfigurationProperties(prefix = "splitexpense.jwt")
public record JwtProperties(

        @NotBlank(message = "splitexpense.jwt.secret must be set") String secret,

        @NotBlank(message = "splitexpense.jwt.issuer must be set") String issuer) {

    /** HS256 requires a key of at least 256 bits; anything shorter is rejected by JJWT. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret != null
                && secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "splitexpense.jwt.secret must be at least " + MINIMUM_SECRET_BYTES
                            + " bytes for HS256 verification");
        }
    }
}

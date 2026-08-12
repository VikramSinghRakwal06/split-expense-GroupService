package com.payflow.wallet.security;

import java.util.UUID;

/**
 * The caller, as stated by a verified JWT and nothing else.
 *
 * <p>This is the {@code Authentication} principal for every request, reachable in a
 * controller with {@code @AuthenticationPrincipal AuthenticatedUser caller}.
 *
 * <p>Note what it is <em>not</em>: it is not a {@code UserDetails}, and there is no
 * {@code UserDetailsService} behind it. auth-service owns the user table, in a different
 * database this service cannot read, so there is nobody to look up. Everything known about
 * the caller is what the token asserts — which is the whole design of a stateless
 * platform: verifying a signature is a local CPU operation, so this service authenticates
 * requests without a network hop and scales independently of auth-service.
 *
 * <p>{@code id} is the value the {@code /me} endpoints resolve a wallet from. It comes from
 * the signed {@code uid} claim and can therefore never be influenced by a request
 * parameter, which is what stops one user reading another's balance.
 *
 * @param id    the account's identifier, from the {@code uid} claim
 * @param email login identifier, from the {@code sub} claim; carried for logging only
 * @param role  authorisation role, from the {@code role} claim, without the
 *              {@code ROLE_} prefix Spring Security adds
 */
public record AuthenticatedUser(UUID id, String email, String role) {
}

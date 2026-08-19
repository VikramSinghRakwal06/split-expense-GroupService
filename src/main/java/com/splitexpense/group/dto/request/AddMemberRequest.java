package com.splitexpense.group.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Payload for {@code POST /api/v1/groups/{groupId}/members}.
 *
 * <p><strong>Names the person by user id, which is not yet a usable product surface.</strong>
 * Nobody knows another person's UUID, so a real invite flow has to accept something a human
 * has — an email address, or a shareable code. Both need work outside this service: an email
 * lookup means a new internal endpoint on auth-service, which owns the user table, and an
 * invite code means a {@code group_invites} table and a second endpoint for the invitee to
 * redeem it.
 *
 * <p>That decision is deliberately still open, so this request takes the id directly. It is
 * the honest minimal surface — enough for expense-service and the integration tests to
 * exercise membership, and small enough that adding an {@code email} alternative later is a
 * new field rather than a rewrite.
 *
 * @param userId the person to add, as minted by auth-service
 */
@Schema(description = "Request to add an existing user to a group")
public record AddMemberRequest(

        @Schema(example = "3f2a5c81-9b4e-4d2f-8a17-6c0e1d9b3a44")
        @NotNull(message = "User id is required")
        UUID userId) {
}

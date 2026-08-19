package com.splitexpense.group.dto.response;

import com.splitexpense.group.entity.GroupMemberRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One member of a group, as a client sees them.
 *
 * <p>Carries only the user id, not a name or email. This service does not have them —
 * auth-service owns the user table, in a database this one cannot read — and inventing a
 * cross-service join to decorate a membership list would make every group read depend on
 * auth-service's availability. Resolving ids to people is the client's job, from its own
 * session or a separate lookup.
 *
 * @param userId   the member, as minted by auth-service
 * @param role     their authority within this group
 * @param joinedAt when they were added
 */
@Schema(description = "A member of a group")
public record GroupMemberResponse(
        UUID userId,
        GroupMemberRole role,
        Instant joinedAt) {
}

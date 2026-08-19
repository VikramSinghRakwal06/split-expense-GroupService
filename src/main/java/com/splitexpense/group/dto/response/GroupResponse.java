package com.splitexpense.group.dto.response;

import com.splitexpense.group.entity.GroupStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Client-facing view of a group and who is in it. The only shape a {@code Group} is exposed
 * in.
 *
 * <p>Carries no {@code version}: the optimistic-locking counter is an internal persistence
 * detail, and publishing it would invite a client to try to supply one.
 *
 * <p>Carries no balances either. A group's membership is small and bounded, while its debt
 * graph grows with the square of that membership and changes far more often — so the two are
 * read through separate endpoints with separate caching. See {@link GroupBalancesResponse}.
 *
 * @param id          group identifier
 * @param name        what the group is called
 * @param description optional longer note, may be null
 * @param currency    ISO-4217 code every expense in the group is denominated in
 * @param createdBy   who created it
 * @param status      whether it still accepts new activity
 * @param members     everyone currently in the group
 * @param createdAt   when it was created
 * @param updatedAt   when its name, description or status last changed
 */
@Schema(description = "An expense-sharing group and its members")
public record GroupResponse(
        UUID id,
        String name,
        String description,
        String currency,
        UUID createdBy,
        GroupStatus status,
        List<GroupMemberResponse> members,
        Instant createdAt,
        Instant updatedAt) {
}

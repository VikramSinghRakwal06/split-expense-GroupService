package com.splitexpense.group.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Result of an internal apply, returned to expense-service.
 *
 * <p>{@link #applied} is the field that matters. {@code false} means this reference had
 * already been applied and nothing changed — the caller is looking at a replay of its own
 * earlier request, which is precisely the answer a retry after a timeout needs. Both cases
 * are a {@code 200}: a duplicate apply is a success, not an error, because the caller's
 * intent is satisfied either way.
 *
 * <p>The resulting balances travel back with it so the caller can confirm the effect
 * without a second round trip.
 *
 * @param groupId     the group the deltas were applied to
 * @param referenceId the causing expense or settlement
 * @param applied     whether this call changed anything, or found the work already done
 * @param balances    the group's debt graph as it now stands
 */
@Schema(description = "Outcome of applying a set of debts to a group")
public record ApplyBalancesResponse(
        UUID groupId,
        String referenceId,
        boolean applied,
        GroupBalancesResponse balances) {
}

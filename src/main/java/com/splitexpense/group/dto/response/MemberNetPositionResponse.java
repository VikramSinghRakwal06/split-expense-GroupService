package com.splitexpense.group.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One member's overall standing in a group: everything they are owed, less everything they
 * owe.
 *
 * <p>Derived from the pair balances rather than stored. Keeping it as a second persisted
 * total would mean two sources of truth for the same fact, and the interesting failure is
 * not that the derived number is slow to compute — a group has tens of pairs — but that a
 * stored one could silently disagree with the pairs it claims to summarise.
 *
 * <p>Across a whole group these values always sum to zero: every debt is someone's negative
 * and someone else's positive. That is a useful invariant to assert in tests.
 *
 * @param userId the member
 * @param net    positive if the group owes them, negative if they owe the group, zero if
 *               they are square
 */
@Schema(description = "A member's net position across every pair balance in one group")
public record MemberNetPositionResponse(
        UUID userId,
        BigDecimal net) {
}

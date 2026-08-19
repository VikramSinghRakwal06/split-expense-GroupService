package com.splitexpense.group.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * A group's whole debt graph: who owes whom, and where each member stands overall.
 *
 * <p>Both views ship together because a client needs both to render one screen — the
 * headline "you are owed 1,240" comes from {@link #netPositions}, and the breakdown beneath
 * it from {@link #pairs} — and splitting them across two endpoints would mean two round
 * trips returning two possibly-different snapshots of the same graph.
 *
 * <p>This is the type held in the {@code groupBalances} Redis cache, which is why it is a
 * self-contained record of immutable values rather than anything carrying entity state.
 *
 * @param groupId      the group these balances belong to
 * @param currency     ISO-4217 code every amount here is denominated in
 * @param pairs        outstanding debts, settled pairs omitted
 * @param netPositions every member's overall standing, including those at zero
 */
@Schema(description = "Every outstanding debt in a group, plus each member's net position")
public record GroupBalancesResponse(
        UUID groupId,
        String currency,
        List<PairBalanceResponse> pairs,
        List<MemberNetPositionResponse> netPositions) {
}

package com.splitexpense.group.mapper;

import com.splitexpense.group.domain.UserPair;
import com.splitexpense.group.dto.response.GroupBalancesResponse;
import com.splitexpense.group.dto.response.MemberNetPositionResponse;
import com.splitexpense.group.dto.response.PairBalanceResponse;
import com.splitexpense.group.entity.Group;
import com.splitexpense.group.entity.GroupBalance;
import com.splitexpense.group.entity.GroupMember;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns the stored debt graph into the shape a client can actually read.
 *
 * <p>Two translations happen here, and both are the point of the class.
 *
 * <h2>Undoing the canonical ordering</h2>
 *
 * <p>A stored row says "what {@code userLow} owes {@code userHigh}", where the ordering was
 * chosen by {@link UserPair} purely so the database can guarantee one row per pair. That
 * ordering carries no meaning for a reader, and the sign carries all of it. So each row is
 * re-stated in the direction the debt actually runs, with a positive amount — and pairs that
 * have settled back to zero are dropped entirely rather than reported as a debt of nothing.
 *
 * <h2>Deriving net positions</h2>
 *
 * <p>Summed from the pair rows rather than stored. A persisted total would be a second
 * source of truth for the same fact, and the interesting failure is not that summing is slow
 * — a group has tens of pairs — but that a stored total could silently disagree with the
 * pairs it claims to summarise.
 */
@Component
public class GroupBalanceMapper {

    /** Matches {@code NUMERIC(19,4)}, so every amount serialises at a consistent scale. */
    private static final int MONEY_SCALE = 4;

    /**
     * Stable output ordering, so two reads of an unchanged graph produce byte-identical
     * JSON. The repository promises no order of its own, and an unstable list would make
     * responses needlessly hard to diff and would defeat any client-side caching keyed on
     * the body.
     */
    private static final Comparator<PairBalanceResponse> BY_PAIR =
            Comparator.comparing(PairBalanceResponse::debtorId, UserPair.POSTGRES_UUID_ORDER)
                    .thenComparing(PairBalanceResponse::creditorId, UserPair.POSTGRES_UUID_ORDER);

    /**
     * Builds the full balances view for a group.
     *
     * @param group    the group, for its id and currency
     * @param members  its current membership — every one of them appears in the net
     *                 positions, including those who have never taken part in an expense
     * @param balances its pair rows, in any order
     * @return the client-facing debt graph
     */
    public GroupBalancesResponse toResponse(
            Group group, List<GroupMember> members, List<GroupBalance> balances) {

        List<PairBalanceResponse> pairs = new ArrayList<>();

        // Seeded with every member at zero, so somebody who has not yet taken part in an
        // expense still appears in the response rather than being silently missing from a
        // list the client renders as "everyone".
        Map<UUID, BigDecimal> netByUser = new HashMap<>();
        for (GroupMember member : members) {
            netByUser.put(member.getUserId(), BigDecimal.ZERO);
        }

        for (GroupBalance balance : balances) {
            // compareTo, never equals: this must be true for 0, 0.00 and 0.0000 alike.
            int direction = balance.getAmount().compareTo(BigDecimal.ZERO);
            if (direction > 0) {
                pairs.add(new PairBalanceResponse(
                        balance.getUserLow(), balance.getUserHigh(), balance.getAmount()));
            } else if (direction < 0) {
                pairs.add(new PairBalanceResponse(
                        balance.getUserHigh(), balance.getUserLow(), balance.getAmount().negate()));
            }
            // A settled pair contributes no debt and shifts nobody's net position, so it is
            // skipped entirely — including in the sums below, where adding zero is a no-op.

            accumulate(netByUser, balance.getUserLow(), balance.netFor(balance.getUserLow()));
            accumulate(netByUser, balance.getUserHigh(), balance.netFor(balance.getUserHigh()));
        }

        pairs.sort(BY_PAIR);

        List<MemberNetPositionResponse> netPositions = netByUser.entrySet().stream()
                .map(entry -> new MemberNetPositionResponse(
                        entry.getKey(), entry.getValue().setScale(MONEY_SCALE)))
                .sorted(Comparator.comparing(
                        MemberNetPositionResponse::userId, UserPair.POSTGRES_UUID_ORDER))
                .toList();

        return new GroupBalancesResponse(
                group.getId(), group.getCurrency(), List.copyOf(pairs), netPositions);
    }

    /**
     * Adds one side of a pair row into a member's running total.
     *
     * <p>Uses {@code merge} with an absent-key default rather than assuming the member is
     * already in the map: a balance can name somebody who has since been removed from the
     * group, and dropping their contribution would make the net positions stop summing to
     * zero — the one invariant that says the graph is coherent.
     */
    private void accumulate(Map<UUID, BigDecimal> netByUser, UUID userId, BigDecimal delta) {
        netByUser.merge(userId, delta, BigDecimal::add);
    }
}

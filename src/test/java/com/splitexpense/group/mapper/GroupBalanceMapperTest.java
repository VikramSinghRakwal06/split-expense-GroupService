package com.splitexpense.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.splitexpense.group.domain.UserPair;
import com.splitexpense.group.dto.response.GroupBalancesResponse;
import com.splitexpense.group.dto.response.MemberNetPositionResponse;
import com.splitexpense.group.entity.Group;
import com.splitexpense.group.entity.GroupBalance;
import com.splitexpense.group.entity.GroupMember;
import com.splitexpense.group.entity.GroupMemberRole;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for turning the stored debt graph into the shape a client reads.
 *
 * <p>Two properties carry most of the weight: a stored row must be re-stated in the direction
 * the debt actually runs, and every member's net position must sum to zero across the group.
 * The second is the invariant that says the graph is coherent — if it ever fails, a debt has
 * been recorded against one side without the other.
 */
class GroupBalanceMapperTest {

    private final GroupBalanceMapper mapper = new GroupBalanceMapper();

    private UUID groupId;
    private UUID alice;
    private UUID bob;
    private UUID carol;
    private Group group;

    @BeforeEach
    void setUp() {
        groupId = UUID.randomUUID();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        carol = UUID.randomUUID();
        group = Group.builder()
                .id(groupId)
                .name("Goa Trip")
                .currency("INR")
                .createdBy(alice)
                .build();
    }

    private GroupMember member(UUID userId) {
        return new GroupMember(groupId, userId, GroupMemberRole.MEMBER);
    }

    /** Builds a row already in canonical order, as the service always would. */
    private GroupBalance balance(UUID a, UUID b, String amountOwedByLowToHigh) {
        UserPair pair = UserPair.of(a, b);
        return GroupBalance.builder()
                .id(UUID.randomUUID())
                .groupId(groupId)
                .userLow(pair.low())
                .userHigh(pair.high())
                .amount(new BigDecimal(amountOwedByLowToHigh))
                .build();
    }

    @Test
    @DisplayName("states a positive stored amount as low owing high")
    void positiveAmountReadsAsLowOwesHigh() {
        UserPair pair = UserPair.of(alice, bob);

        GroupBalancesResponse response = mapper.toResponse(
                group,
                List.of(member(alice), member(bob)),
                List.of(balance(alice, bob, "450.0000")));

        assertThat(response.pairs()).singleElement().satisfies(entry -> {
            assertThat(entry.debtorId()).isEqualTo(pair.low());
            assertThat(entry.creditorId()).isEqualTo(pair.high());
            assertThat(entry.amount()).isEqualByComparingTo("450.0000");
        });
    }

    @Test
    @DisplayName("flips a negative stored amount so the reported amount is always positive")
    void negativeAmountReadsAsHighOwesLow() {
        UserPair pair = UserPair.of(alice, bob);

        GroupBalancesResponse response = mapper.toResponse(
                group,
                List.of(member(alice), member(bob)),
                List.of(balance(alice, bob, "-450.0000")));

        assertThat(response.pairs()).singleElement().satisfies(entry -> {
            assertThat(entry.debtorId()).isEqualTo(pair.high());
            assertThat(entry.creditorId()).isEqualTo(pair.low());
            assertThat(entry.amount()).isEqualByComparingTo("450.0000");
        });
    }

    @Test
    @DisplayName("omits a settled pair rather than reporting a debt of nothing")
    void settledPairIsOmitted() {
        GroupBalancesResponse response = mapper.toResponse(
                group,
                List.of(member(alice), member(bob)),
                List.of(balance(alice, bob, "0.0000")));

        assertThat(response.pairs()).isEmpty();
        // Both members still appear, at zero — they are in the group whether or not they owe.
        assertThat(response.netPositions()).hasSize(2);
        assertThat(response.netPositions())
                .allSatisfy(net -> assertThat(net.net()).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("net positions sum to zero across the whole group")
    void netPositionsSumToZero() {
        GroupBalancesResponse response = mapper.toResponse(
                group,
                List.of(member(alice), member(bob), member(carol)),
                List.of(
                        balance(alice, bob, "450.0000"),
                        balance(bob, carol, "-120.5000"),
                        balance(alice, carol, "75.2500")));

        BigDecimal total = response.netPositions().stream()
                .map(MemberNetPositionResponse::net)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(total).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("computes each member's net as what they are owed less what they owe")
    void netPositionIsOwedLessOwing() {
        // Alice owes Bob 300, and Carol owes Alice 500. Alice is therefore up 200.
        GroupBalance aliceOwesBob = signedFor(alice, bob, alice, "300.0000");
        GroupBalance carolOwesAlice = signedFor(alice, carol, carol, "500.0000");

        GroupBalancesResponse response = mapper.toResponse(
                group,
                List.of(member(alice), member(bob), member(carol)),
                List.of(aliceOwesBob, carolOwesAlice));

        assertThat(netFor(response, alice)).isEqualByComparingTo("200.0000");
        assertThat(netFor(response, bob)).isEqualByComparingTo("300.0000");
        assertThat(netFor(response, carol)).isEqualByComparingTo("-500.0000");
    }

    @Test
    @DisplayName("includes a member who has never taken part in an expense")
    void memberWithNoBalancesStillAppears() {
        GroupBalancesResponse response = mapper.toResponse(
                group,
                List.of(member(alice), member(bob), member(carol)),
                List.of(balance(alice, bob, "10.0000")));

        assertThat(response.netPositions())
                .extracting(MemberNetPositionResponse::userId)
                .containsExactlyInAnyOrder(alice, bob, carol);
        assertThat(netFor(response, carol)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("orders pairs and net positions deterministically")
    void outputOrderIsStable() {
        List<GroupMember> members = List.of(member(alice), member(bob), member(carol));
        List<GroupBalance> balances = List.of(
                balance(alice, bob, "10.0000"),
                balance(bob, carol, "20.0000"),
                balance(alice, carol, "30.0000"));

        GroupBalancesResponse first = mapper.toResponse(group, members, balances);
        // Same data, presented in a different order by the repository.
        GroupBalancesResponse second = mapper.toResponse(
                group, members.reversed(), balances.reversed());

        assertThat(first.pairs()).isEqualTo(second.pairs());
        assertThat(first.netPositions()).isEqualTo(second.netPositions());
    }

    /**
     * Builds a row where {@code debtor} owes the other member {@code amount}, working out the
     * sign the canonical ordering demands — the same arithmetic the service does.
     */
    private GroupBalance signedFor(UUID a, UUID b, UUID debtor, String amount) {
        UserPair pair = UserPair.of(a, b);
        BigDecimal signed = pair.signFor(debtor) > 0
                ? new BigDecimal(amount)
                : new BigDecimal(amount).negate();

        return GroupBalance.builder()
                .id(UUID.randomUUID())
                .groupId(groupId)
                .userLow(pair.low())
                .userHigh(pair.high())
                .amount(signed)
                .build();
    }

    private BigDecimal netFor(GroupBalancesResponse response, UUID userId) {
        return response.netPositions().stream()
                .filter(net -> net.userId().equals(userId))
                .map(MemberNetPositionResponse::net)
                .findFirst()
                .orElseThrow();
    }
}

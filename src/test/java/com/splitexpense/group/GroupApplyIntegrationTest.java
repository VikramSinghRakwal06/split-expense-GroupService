package com.splitexpense.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitexpense.group.domain.UserPair;
import com.splitexpense.group.dto.request.ApplyBalancesRequest;
import com.splitexpense.group.dto.request.BalanceDeltaRequest;
import com.splitexpense.group.dto.response.ApplyBalancesResponse;
import com.splitexpense.group.dto.response.GroupBalancesResponse;
import com.splitexpense.group.dto.response.MemberNetPositionResponse;
import com.splitexpense.group.entity.BalanceEntryReason;
import com.splitexpense.group.entity.Group;
import com.splitexpense.group.entity.GroupMember;
import com.splitexpense.group.entity.GroupMemberRole;
import com.splitexpense.group.exception.NotAGroupMemberException;
import com.splitexpense.group.repository.BalanceEntryRepository;
import com.splitexpense.group.repository.GroupBalanceRepository;
import com.splitexpense.group.repository.GroupMemberRepository;
import com.splitexpense.group.service.GroupService;
import com.splitexpense.group.service.GroupTransactionService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for the balance apply, against a real PostgreSQL.
 *
 * <p>These cover the things that cannot be meaningfully faked, and each one guards a specific
 * claim made elsewhere in the service:
 *
 * <ul>
 *   <li><strong>Idempotency.</strong> A repeat apply under the same reference changes nothing
 *       and reports {@code applied: false}. This is the property that lets expense-service
 *       retry a call it never saw the answer to, and therefore the reason its saga needs no
 *       compensation step.</li>
 *   <li><strong>Atomicity.</strong> A rejected delta anywhere in a set leaves no trace of any
 *       of them, including the idempotency receipt — so the caller may fix the request and
 *       resend under the same reference.</li>
 *   <li><strong>Canonical ordering.</strong> Rows this service writes must satisfy
 *       {@code ck_group_balances_canonical}, which PostgreSQL evaluates with <em>unsigned</em>
 *       uuid comparison. {@code UserPairTest} pins the Java side; this pins the agreement.</li>
 *   <li><strong>Concurrency.</strong> Simultaneous applies to one group neither lose a debt
 *       nor duplicate one.</li>
 * </ul>
 *
 * <p>Redis is switched off rather than containerised: these tests are about what the database
 * guarantees, and a cache in front of the reads would only obscure it. The caching behaviour
 * itself is a separate concern.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.cache.type=none",
    "spring.flyway.clean-disabled=false"
})
class GroupApplyIntegrationTest {

    // Non-generic in Testcontainers 2.x, unlike the 1.x class of the same name.
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private GroupService groupService;
    @Autowired
    private GroupTransactionService groupTransactionService;
    @Autowired
    private GroupMemberRepository groupMemberRepository;
    @Autowired
    private GroupBalanceRepository groupBalanceRepository;
    @Autowired
    private BalanceEntryRepository balanceEntryRepository;

    private UUID groupId;
    private UUID alice;
    private UUID bob;
    private UUID carol;

    /**
     * Namespaces every reference this test method uses.
     *
     * <p>{@code applied_operations.reference_id} is the primary key across the whole table,
     * not per group — deliberately, because an expense belongs to exactly one group and
     * applying it to a second would be a bug worth refusing. The tests share one database, so
     * a literal reference like {@code "expense-1"} would be seen as an already-applied replay
     * by whichever test ran second. Real references are expense-service's UUIDs, so
     * namespacing here matches production rather than working around it.
     */
    private String runId;

    @BeforeEach
    void setUp() {
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        carol = UUID.randomUUID();
        runId = UUID.randomUUID().toString();

        Group group = groupTransactionService.createGroup(alice, "Flat", "Shared flat", "INR");
        groupId = group.getId();

        groupMemberRepository.save(new GroupMember(groupId, bob, GroupMemberRole.MEMBER));
        groupMemberRepository.save(new GroupMember(groupId, carol, GroupMemberRole.MEMBER));
    }

    private ApplyBalancesRequest request(String reference, BalanceDeltaRequest... deltas) {
        return new ApplyBalancesRequest(
                runId + "-" + reference, BalanceEntryReason.EXPENSE, "Dinner", List.of(deltas));
    }

    private BalanceDeltaRequest delta(UUID debtor, UUID creditor, String amount) {
        return new BalanceDeltaRequest(debtor, creditor, new BigDecimal(amount));
    }

    @Test
    @DisplayName("applies an expense's deltas and reports the resulting graph")
    void appliesDeltas() {
        // Alice paid 1350 for dinner; Bob and Carol each owe her 450.
        ApplyBalancesResponse response = groupService.applyBalances(groupId, request(
                "expense-1",
                delta(bob, alice, "450.0000"),
                delta(carol, alice, "450.0000")));

        assertThat(response.applied()).isTrue();
        assertThat(response.balances().pairs()).hasSize(2);
        assertThat(netFor(response.balances(), alice)).isEqualByComparingTo("900.0000");
        assertThat(netFor(response.balances(), bob)).isEqualByComparingTo("-450.0000");
        assertThat(netFor(response.balances(), carol)).isEqualByComparingTo("-450.0000");
    }

    @Test
    @DisplayName("a repeat under the same reference changes nothing and says so")
    void replayIsANoOp() {
        ApplyBalancesRequest first = request("expense-1", delta(bob, alice, "450.0000"));

        ApplyBalancesResponse original = groupService.applyBalances(groupId, first);
        assertThat(original.applied()).isTrue();

        // Exactly what expense-service would send after a timeout it never learned the
        // outcome of.
        ApplyBalancesResponse replay = groupService.applyBalances(groupId, first);

        assertThat(replay.applied()).isFalse();
        assertThat(netFor(replay.balances(), alice)).isEqualByComparingTo("450.0000");
        assertThat(balanceEntryRepository.countByGroupId(groupId))
                .as("the replay must not append a second ledger entry")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("distinct references accumulate rather than replacing each other")
    void distinctReferencesAccumulate() {
        groupService.applyBalances(groupId, request("expense-1", delta(bob, alice, "450.0000")));
        groupService.applyBalances(groupId, request("expense-2", delta(bob, alice, "50.0000")));

        GroupBalancesResponse balances = groupService.getBalances(groupId, alice);

        assertThat(netFor(balances, bob)).isEqualByComparingTo("-500.0000");
        assertThat(balanceEntryRepository.countByGroupId(groupId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a debt in the opposite direction nets off against an existing one")
    void oppositeDebtNetsOff() {
        groupService.applyBalances(groupId, request("expense-1", delta(bob, alice, "450.0000")));
        groupService.applyBalances(groupId, request("expense-2", delta(alice, bob, "450.0000")));

        GroupBalancesResponse balances = groupService.getBalances(groupId, alice);

        assertThat(balances.pairs())
                .as("a settled pair is omitted rather than reported as zero")
                .isEmpty();
        assertThat(netFor(balances, alice)).isEqualByComparingTo("0");
        assertThat(netFor(balances, bob)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("rejecting one delta rolls back every delta and the receipt with it")
    void rejectedDeltaLeavesNoTrace() {
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> groupService.applyBalances(groupId, request(
                "expense-1",
                delta(bob, alice, "450.0000"),
                delta(stranger, alice, "450.0000"))))
                .isInstanceOf(NotAGroupMemberException.class);

        assertThat(groupBalanceRepository.findByGroupId(groupId))
                .as("the valid delta must not have been applied on its own")
                .isEmpty();
        assertThat(balanceEntryRepository.countByGroupId(groupId)).isZero();

        // And because the receipt rolled back too, the corrected request may reuse the
        // reference — which is what a caller would naturally do.
        ApplyBalancesResponse retry = groupService.applyBalances(groupId, request(
                "expense-1",
                delta(bob, alice, "450.0000"),
                delta(carol, alice, "450.0000")));

        assertThat(retry.applied()).isTrue();
        assertThat(netFor(retry.balances(), alice)).isEqualByComparingTo("900.0000");
    }

    @Test
    @DisplayName("writes pair rows PostgreSQL's canonical-order constraint accepts")
    void canonicalOrderingSatisfiesTheDatabaseConstraint() {
        // Every pair in the group, applied in whichever argument order the caller chose. If
        // the Java ordering disagreed with PostgreSQL's unsigned uuid comparison, one of
        // these would be rejected by ck_group_balances_canonical.
        groupService.applyBalances(groupId, request("e1", delta(alice, bob, "10.0000")));
        groupService.applyBalances(groupId, request("e2", delta(carol, alice, "20.0000")));
        groupService.applyBalances(groupId, request("e3", delta(bob, carol, "30.0000")));

        assertThat(groupBalanceRepository.findByGroupId(groupId)).hasSize(3);
        assertThat(groupBalanceRepository.findByGroupId(groupId)).allSatisfy(balance ->
                assertThat(UserPair.POSTGRES_UUID_ORDER
                        .compare(balance.getUserLow(), balance.getUserHigh()))
                        .isLessThan(0));
    }

    @Test
    @DisplayName("reuses one pair row however the two members are named")
    void oneRowPerPairRegardlessOfArgumentOrder() {
        groupService.applyBalances(groupId, request("e1", delta(alice, bob, "10.0000")));
        groupService.applyBalances(groupId, request("e2", delta(bob, alice, "4.0000")));

        assertThat(groupBalanceRepository.findByGroupId(groupId))
                .as("naming the pair the other way round must not create a second row")
                .hasSize(1);

        GroupBalancesResponse balances = groupService.getBalances(groupId, alice);
        assertThat(balances.pairs()).singleElement().satisfies(pair -> {
            assertThat(pair.debtorId()).isEqualTo(alice);
            assertThat(pair.creditorId()).isEqualTo(bob);
            assertThat(pair.amount()).isEqualByComparingTo("6.0000");
        });
    }

    @Test
    @DisplayName("concurrent applies to one pair neither lose nor duplicate a debt")
    void concurrentAppliesAllLand() throws Exception {
        int concurrentExpenses = 8;
        CyclicBarrier startTogether = new CyclicBarrier(concurrentExpenses);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrentExpenses; i++) {
            String reference = "expense-" + i;
            tasks.add(() -> {
                // Line every thread up so they collide on the same pair row for real,
                // rather than trickling through one after another.
                startTogether.await();
                groupService.applyBalances(
                        groupId, request(reference, delta(bob, alice, "10.0000")));
                return null;
            });
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(concurrentExpenses)) {
            for (Future<Void> result : pool.invokeAll(tasks)) {
                // Surfaces any thread's failure — a lost optimistic-lock race that exhausted
                // its retries would arrive here as a ConcurrentUpdateException.
                result.get();
            }
        }

        GroupBalancesResponse balances = groupService.getBalances(groupId, alice);

        assertThat(netFor(balances, alice))
                .as("every one of the %d expenses must be reflected exactly once",
                        concurrentExpenses)
                .isEqualByComparingTo(new BigDecimal("10.0000")
                        .multiply(BigDecimal.valueOf(concurrentExpenses)));

        assertThat(balanceEntryRepository.countByGroupId(groupId))
                .as("the ledger must explain the balance entry for entry")
                .isEqualTo(concurrentExpenses);
    }

    @Test
    @DisplayName("simultaneous applies of the SAME reference take effect exactly once")
    void concurrentReplaysOfOneReferenceApplyOnce() throws Exception {
        // The scenario expense-service actually produces: a call times out, it retries, and
        // the original request is still in flight. Both arrive together.
        //
        // The cheap existsByReferenceId check cannot decide this — both callers pass it — so
        // the primary key on applied_operations is the only thing standing between one
        // expense and a double charge. That guard is inert unless the entity forces a
        // persist() rather than a merge(); see AppliedOperation's Javadoc.
        int simultaneousRetries = 6;
        ApplyBalancesRequest sameRequest = request("expense-1", delta(bob, alice, "450.0000"));
        CyclicBarrier startTogether = new CyclicBarrier(simultaneousRetries);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < simultaneousRetries; i++) {
            tasks.add(() -> {
                startTogether.await();
                try {
                    groupService.applyBalances(groupId, sameRequest);
                } catch (RuntimeException contended) {
                    // A loser may exhaust its retry budget under this much contention. That
                    // is an acceptable outcome — what must never happen is a second charge.
                }
                return null;
            });
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(simultaneousRetries)) {
            for (Future<Void> result : pool.invokeAll(tasks)) {
                result.get();
            }
        }

        assertThat(netFor(groupService.getBalances(groupId, alice), alice))
                .as("the expense must be reflected exactly once, however many retries raced")
                .isEqualByComparingTo("450.0000");
        assertThat(balanceEntryRepository.countByGroupId(groupId))
                .as("and it must have left exactly one ledger entry")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("net positions always sum to zero across the group")
    void netPositionsSumToZero() {
        groupService.applyBalances(groupId, request("e1", delta(bob, alice, "450.0000")));
        groupService.applyBalances(groupId, request("e2", delta(carol, alice, "137.3300")));
        groupService.applyBalances(groupId, request("e3", delta(alice, carol, "22.7700")));
        groupService.applyBalances(groupId, request("e4", delta(bob, carol, "9.9999")));

        BigDecimal total = groupService.getBalances(groupId, alice).netPositions().stream()
                .map(MemberNetPositionResponse::net)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(total).isEqualByComparingTo("0");
    }

    private BigDecimal netFor(GroupBalancesResponse balances, UUID userId) {
        return balances.netPositions().stream()
                .filter(net -> net.userId().equals(userId))
                .map(MemberNetPositionResponse::net)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No net position for " + userId));
    }
}

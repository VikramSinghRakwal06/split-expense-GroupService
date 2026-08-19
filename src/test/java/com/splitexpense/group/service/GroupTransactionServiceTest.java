package com.splitexpense.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitexpense.group.domain.UserPair;
import com.splitexpense.group.dto.request.ApplyBalancesRequest;
import com.splitexpense.group.dto.request.BalanceDeltaRequest;
import com.splitexpense.group.entity.AppliedOperation;
import com.splitexpense.group.entity.BalanceEntry;
import com.splitexpense.group.entity.BalanceEntryReason;
import com.splitexpense.group.entity.Group;
import com.splitexpense.group.entity.GroupBalance;
import com.splitexpense.group.entity.GroupStatus;
import com.splitexpense.group.exception.GroupNotActiveException;
import com.splitexpense.group.exception.InvalidAmountException;
import com.splitexpense.group.exception.InvalidBalanceDeltaException;
import com.splitexpense.group.exception.NotAGroupMemberException;
import com.splitexpense.group.exception.ResourceNotFoundException;
import com.splitexpense.group.repository.AppliedOperationRepository;
import com.splitexpense.group.repository.BalanceEntryRepository;
import com.splitexpense.group.repository.GroupBalanceRepository;
import com.splitexpense.group.repository.GroupMemberRepository;
import com.splitexpense.group.repository.GroupRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for the domain rules around applying balances.
 *
 * <p>Plain Mockito against one class, which is possible precisely because every rule lives in
 * {@link GroupTransactionService} rather than being spread between the DTO layer and the
 * database. A caller that bypassed validation entirely still cannot record a negative debt or
 * a debt against a non-member, and that is what these assert.
 *
 * <p>The concurrency behaviour these rules sit inside — optimistic locking, retries, the
 * canonical ordering agreeing with PostgreSQL — is covered by
 * {@code GroupApplyIntegrationTest} against a real database, because none of it can be
 * meaningfully faked.
 */
@ExtendWith(MockitoExtension.class)
class GroupTransactionServiceTest {

    @Mock
    private GroupRepository groupRepository;
    @Mock
    private GroupMemberRepository groupMemberRepository;
    @Mock
    private GroupBalanceRepository groupBalanceRepository;
    @Mock
    private BalanceEntryRepository balanceEntryRepository;
    @Mock
    private AppliedOperationRepository appliedOperationRepository;

    @InjectMocks
    private GroupTransactionService service;

    private UUID groupId;
    private UUID alice;
    private UUID bob;
    private Group activeGroup;

    @BeforeEach
    void setUp() {
        groupId = UUID.randomUUID();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        activeGroup = Group.builder()
                .id(groupId)
                .name("Flat")
                .currency("INR")
                .createdBy(alice)
                .status(GroupStatus.ACTIVE)
                .build();
    }

    private ApplyBalancesRequest request(BalanceDeltaRequest... deltas) {
        return new ApplyBalancesRequest(
                "expense-1", BalanceEntryReason.EXPENSE, "Dinner", List.of(deltas));
    }

    private BalanceDeltaRequest delta(UUID debtor, UUID creditor, String amount) {
        return new BalanceDeltaRequest(debtor, creditor, new BigDecimal(amount));
    }

    /** Wires the happy path: active group, unseen reference, everyone a member. */
    private void givenApplicableGroup(int participantCount) {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(activeGroup));
        when(appliedOperationRepository.existsByReferenceId(anyString())).thenReturn(false);
        when(groupMemberRepository.countMembersIn(eq(groupId), any()))
                .thenReturn((long) participantCount);
    }

    @Test
    @DisplayName("moves the pair balance in the direction the canonical ordering demands")
    void appliesADeltaWithTheCorrectSign() {
        givenApplicableGroup(2);
        UserPair pair = UserPair.of(alice, bob);
        GroupBalance existing = GroupBalance.builder()
                .id(UUID.randomUUID())
                .groupId(groupId)
                .userLow(pair.low())
                .userHigh(pair.high())
                .amount(BigDecimal.ZERO)
                .build();
        when(groupBalanceRepository
                .findByGroupIdAndUserLowAndUserHigh(groupId, pair.low(), pair.high()))
                .thenReturn(Optional.of(existing));

        // Alice owes Bob 450.
        service.applyBalances(groupId, request(delta(alice, bob, "450.0000")));

        BigDecimal expected = pair.signFor(alice) > 0
                ? new BigDecimal("450.0000")
                : new BigDecimal("-450.0000");
        assertThat(existing.getAmount()).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("appends a ledger entry stating the debt as the caller gave it")
    void appendsLedgerEntryInStatedDirection() {
        givenApplicableGroup(2);
        UserPair pair = UserPair.of(alice, bob);
        when(groupBalanceRepository
                .findByGroupIdAndUserLowAndUserHigh(groupId, pair.low(), pair.high()))
                .thenReturn(Optional.of(GroupBalance.builder()
                        .id(UUID.randomUUID())
                        .groupId(groupId)
                        .userLow(pair.low())
                        .userHigh(pair.high())
                        .amount(BigDecimal.ZERO)
                        .build()));

        service.applyBalances(groupId, request(delta(alice, bob, "450.0000")));

        ArgumentCaptor<BalanceEntry> captor = ArgumentCaptor.forClass(BalanceEntry.class);
        verify(balanceEntryRepository).save(captor.capture());

        BalanceEntry entry = captor.getValue();
        // Stated, not canonical: the ledger reads back as what happened.
        assertThat(entry.getDebtorId()).isEqualTo(alice);
        assertThat(entry.getCreditorId()).isEqualTo(bob);
        assertThat(entry.getAmount()).isEqualByComparingTo("450.0000");
        assertThat(entry.getReferenceId()).isEqualTo("expense-1");
        assertThat(entry.getReason()).isEqualTo(BalanceEntryReason.EXPENSE);
    }

    @Test
    @DisplayName("creates the pair row at zero the first time two members transact")
    void createsMissingPairRow() {
        givenApplicableGroup(2);
        UserPair pair = UserPair.of(alice, bob);
        when(groupBalanceRepository
                .findByGroupIdAndUserLowAndUserHigh(groupId, pair.low(), pair.high()))
                .thenReturn(Optional.empty());
        when(groupBalanceRepository.saveAndFlush(any(GroupBalance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.applyBalances(groupId, request(delta(alice, bob, "10.0000")));

        ArgumentCaptor<GroupBalance> captor = ArgumentCaptor.forClass(GroupBalance.class);
        verify(groupBalanceRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserLow()).isEqualTo(pair.low());
        assertThat(captor.getValue().getUserHigh()).isEqualTo(pair.high());
    }

    @Test
    @DisplayName("refuses a reference that has already been applied, before doing any work")
    void rejectsAlreadyAppliedReference() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(activeGroup));
        when(appliedOperationRepository.existsByReferenceId("expense-1")).thenReturn(true);

        assertThatThrownBy(() ->
                service.applyBalances(groupId, request(delta(alice, bob, "10.0000"))))
                .isInstanceOf(OperationAlreadyAppliedException.class);

        verify(balanceEntryRepository, never()).save(any());
        verify(appliedOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("treats losing the race to reserve a reference as an already-applied replay")
    void reservationRaceBecomesReplay() {
        givenApplicableGroup(2);
        when(appliedOperationRepository.saveAndFlush(any(AppliedOperation.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() ->
                service.applyBalances(groupId, request(delta(alice, bob, "10.0000"))))
                .isInstanceOf(OperationAlreadyAppliedException.class);

        verify(balanceEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuses to change an archived group")
    void rejectsArchivedGroup() {
        Group archived = Group.builder()
                .id(groupId)
                .name("Old trip")
                .currency("INR")
                .createdBy(alice)
                .status(GroupStatus.ARCHIVED)
                .build();
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(archived));

        assertThatThrownBy(() ->
                service.applyBalances(groupId, request(delta(alice, bob, "10.0000"))))
                .isInstanceOf(GroupNotActiveException.class);

        verify(appliedOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("refuses a delta naming somebody outside the group")
    void rejectsNonMemberParticipant() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(activeGroup));
        when(appliedOperationRepository.existsByReferenceId(anyString())).thenReturn(false);
        // Two distinct participants asked about, only one is a member.
        when(groupMemberRepository.countMembersIn(eq(groupId), any())).thenReturn(1L);

        assertThatThrownBy(() ->
                service.applyBalances(groupId, request(delta(alice, bob, "10.0000"))))
                .isInstanceOf(NotAGroupMemberException.class);

        verify(appliedOperationRepository, never()).saveAndFlush(any());
        verify(balanceEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuses a debt from somebody to themselves")
    void rejectsSelfDebt() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(activeGroup));
        when(appliedOperationRepository.existsByReferenceId(anyString())).thenReturn(false);

        assertThatThrownBy(() ->
                service.applyBalances(groupId, request(delta(alice, alice, "10.0000"))))
                .isInstanceOf(InvalidBalanceDeltaException.class)
                .hasMessageContaining("cannot owe themselves");

        verify(appliedOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("refuses an amount finer than the ledger can record")
    void rejectsOverlyPreciseAmount() {
        givenApplicableGroup(2);

        assertThatThrownBy(() ->
                service.applyBalances(groupId, request(delta(alice, bob, "10.000005"))))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("4 decimal places");
    }

    @Test
    @DisplayName("refuses a non-positive amount")
    void rejectsNonPositiveAmount() {
        givenApplicableGroup(2);

        assertThatThrownBy(() ->
                service.applyBalances(groupId, request(delta(alice, bob, "0.0000"))))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    @DisplayName("reports an unknown group as not found")
    void rejectsUnknownGroup() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.applyBalances(groupId, request(delta(alice, bob, "10.0000"))))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

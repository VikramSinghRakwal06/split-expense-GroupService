package com.splitexpense.group.service;

import com.splitexpense.group.domain.UserPair;
import com.splitexpense.group.dto.request.ApplyBalancesRequest;
import com.splitexpense.group.dto.request.BalanceDeltaRequest;
import com.splitexpense.group.entity.AppliedOperation;
import com.splitexpense.group.entity.BalanceEntry;
import com.splitexpense.group.entity.Group;
import com.splitexpense.group.entity.GroupBalance;
import com.splitexpense.group.entity.GroupMember;
import com.splitexpense.group.entity.GroupMemberRole;
import com.splitexpense.group.entity.GroupStatus;
import com.splitexpense.group.exception.DuplicateResourceException;
import com.splitexpense.group.exception.GroupNotActiveException;
import com.splitexpense.group.exception.InvalidAmountException;
import com.splitexpense.group.exception.InvalidBalanceDeltaException;
import com.splitexpense.group.exception.NotAGroupMemberException;
import com.splitexpense.group.exception.NotGroupOwnerException;
import com.splitexpense.group.exception.OutstandingBalanceException;
import com.splitexpense.group.exception.ResourceNotFoundException;
import com.splitexpense.group.repository.AppliedOperationRepository;
import com.splitexpense.group.repository.BalanceEntryRepository;
import com.splitexpense.group.repository.GroupBalanceRepository;
import com.splitexpense.group.repository.GroupMemberRepository;
import com.splitexpense.group.repository.GroupRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of the group domain: every rule about when a group or a debt may
 * change, and the atomic writes that change them.
 *
 * <h2>Why this is a separate bean from {@link GroupService}</h2>
 *
 * <p>{@link BalanceApplyService} owns the {@code @Retryable} boundary; this class owns the
 * {@code @Transactional} boundary. They are two beans specifically so that the transaction
 * sits <strong>inside</strong> the retry, and the split is load-bearing rather than
 * stylistic — see the class Javadoc on {@link BalanceApplyService} for the full argument. In
 * short: Spring's annotations are applied by proxies, and a proxy only intercepts calls
 * arriving from outside the object. Putting both annotations on one class would mean the
 * retry re-invoked the method body without ever re-entering the transaction proxy, so every
 * attempt after the first would run in the failed, rollback-only transaction.
 *
 * <p>Consequently: <strong>nothing in this class may call another public method on
 * {@code this}</strong> expecting transactional semantics, and this class must never be
 * annotated {@code @Retryable}.
 *
 * <h2>Domain rules enforced here</h2>
 *
 * <p>All of them, including amount validation. That keeps the domain testable with plain
 * Mockito against this one class, and means a caller that bypassed the DTO layer entirely
 * still cannot record a negative debt.
 */
@Service
public class GroupTransactionService {

    private static final Logger log = LoggerFactory.getLogger(GroupTransactionService.class);

    /**
     * Maximum decimal places a delta may carry, matching {@code NUMERIC(19,4)}. An amount
     * finer than the ledger can store would be silently rounded by the driver, and silently
     * losing fractions of a currency unit is how a group's balances stop summing to zero.
     */
    private static final int MAX_SCALE = 4;

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupBalanceRepository groupBalanceRepository;
    private final BalanceEntryRepository balanceEntryRepository;
    private final AppliedOperationRepository appliedOperationRepository;

    public GroupTransactionService(
            GroupRepository groupRepository,
            GroupMemberRepository groupMemberRepository,
            GroupBalanceRepository groupBalanceRepository,
            BalanceEntryRepository balanceEntryRepository,
            AppliedOperationRepository appliedOperationRepository) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupBalanceRepository = groupBalanceRepository;
        this.balanceEntryRepository = balanceEntryRepository;
        this.appliedOperationRepository = appliedOperationRepository;
    }

    /**
     * Creates a group and enrols its creator as the first {@code OWNER}.
     *
     * <p>Both writes happen in this one transaction. A group with no members would be
     * unreachable by every endpoint in the service — membership is how groups are found —
     * so a half-completed creation would leak a row nobody could ever see or delete.
     *
     * @param creatorId   the caller, from a verified JWT
     * @param name        what the group is called
     * @param description optional longer note
     * @param currency    ISO-4217 code, or null to take the entity default
     * @return the newly created group
     */
    @Transactional
    public Group createGroup(UUID creatorId, String name, String description, String currency) {
        Group.GroupBuilder builder = Group.builder()
                .name(name)
                .description(description)
                .createdBy(creatorId)
                .status(GroupStatus.ACTIVE);

        // Only override the entity's default when the client actually chose something;
        // passing null explicitly would defeat @Builder.Default and insert a null currency.
        if (currency != null) {
            builder.currency(currency);
        }

        Group group = groupRepository.save(builder.build());
        groupMemberRepository.save(
                new GroupMember(group.getId(), creatorId, GroupMemberRole.OWNER));

        log.info("Created group {} for owner {}", group.getId(), creatorId);
        return group;
    }

    /**
     * Adds a user to a group.
     *
     * @param groupId  group to add to
     * @param callerId the caller, from a verified JWT; must be the group's owner
     * @param userId   the person to add
     * @return the new membership
     * @throws ResourceNotFoundException  if the group does not exist, or the caller is not in it
     * @throws NotGroupOwnerException     if the caller is a member but not an owner
     * @throws GroupNotActiveException    if the group has been archived
     * @throws DuplicateResourceException if the user is already a member
     */
    @Transactional
    public GroupMember addMember(UUID groupId, UUID callerId, UUID userId) {
        Group group = requireGroupVisibleTo(groupId, callerId);
        requireActive(group);
        requireOwner(groupId, callerId);

        try {
            // saveAndFlush, not save: the INSERT must hit the database inside this try
            // block. With a plain save the statement would be deferred to commit, the
            // primary-key violation would surface outside the method, and two simultaneous
            // requests adding the same person would produce a 500 instead of a 409.
            return groupMemberRepository.saveAndFlush(
                    new GroupMember(groupId, userId, GroupMemberRole.MEMBER));
        } catch (DataIntegrityViolationException ex) {
            log.debug("Concurrent add of user {} to group {} lost the race", userId, groupId);
            throw new DuplicateResourceException("This user is already a member of the group");
        }
    }

    /**
     * Removes a member from a group.
     *
     * <p>Permitted only when every balance they appear in has settled to zero. Removing
     * somebody who still owes or is owed would delete the membership row that every balance
     * is interpreted against, leaving debts pointing at a non-member with no endpoint
     * through which they could ever be settled.
     *
     * @param groupId  group to remove from
     * @param callerId the caller, from a verified JWT
     * @param userId   the person to remove
     * @throws ResourceNotFoundException   if the group or the membership does not exist
     * @throws NotGroupOwnerException      if the caller is neither the owner nor the person
     *                                     being removed
     * @throws OutstandingBalanceException if they still owe or are owed anything
     */
    @Transactional
    public void removeMember(UUID groupId, UUID callerId, UUID userId) {
        Group group = requireGroupVisibleTo(groupId, callerId);
        requireActive(group);

        // Anyone may leave a group they are in; only an owner may remove somebody else.
        if (!callerId.equals(userId)) {
            requireOwner(groupId, callerId);
        }

        GroupMember member = groupMemberRepository
                .findByIdGroupIdAndIdUserId(groupId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "This user is not a member of the group"));

        boolean hasOutstanding = groupBalanceRepository.findByGroupIdAndUser(groupId, userId)
                .stream()
                .anyMatch(balance -> !balance.isSettled());

        if (hasOutstanding) {
            throw new OutstandingBalanceException(
                    "This member still has unsettled balances in the group. Settle up first.");
        }

        groupMemberRepository.delete(member);
        log.info("Removed user {} from group {}", userId, groupId);
    }

    /**
     * Archives a group, freezing every balance in it.
     *
     * <p>Refused while any pair balance is unsettled, for the same reason {@link
     * #removeMember} refuses to drop a member who still owes or is owed: an archived group can
     * never apply another balance, so a debt frozen inside one would be unreachable through
     * any endpoint.
     *
     * @param groupId  group to archive
     * @param callerId the caller, from a verified JWT; must be the group's owner
     * @return the archived group
     * @throws ResourceNotFoundException   if the group does not exist, or the caller is not in it
     * @throws NotGroupOwnerException      if the caller is a member but not an owner
     * @throws GroupNotActiveException     if the group is already archived
     * @throws OutstandingBalanceException if any pair balance in the group has not settled
     */
    @Transactional
    public Group archiveGroup(UUID groupId, UUID callerId) {
        Group group = requireGroupVisibleTo(groupId, callerId);
        requireActive(group);
        requireOwner(groupId, callerId);

        boolean hasOutstanding = groupBalanceRepository.findByGroupId(groupId).stream()
                .anyMatch(balance -> !balance.isSettled());
        if (hasOutstanding) {
            throw new OutstandingBalanceException(
                    "This group still has unsettled balances. Settle up before archiving.");
        }

        group.setStatus(GroupStatus.ARCHIVED);
        log.info("Archived group {}", groupId);
        return group;
    }

    /**
     * Reactivates an archived group.
     *
     * @param groupId  group to reactivate
     * @param callerId the caller, from a verified JWT; must be the group's owner
     * @return the reactivated group
     * @throws ResourceNotFoundException if the group does not exist, or the caller is not in it
     * @throws NotGroupOwnerException    if the caller is a member but not an owner
     * @throws GroupNotActiveException   if the group is not currently archived
     */
    @Transactional
    public Group reactivateGroup(UUID groupId, UUID callerId) {
        Group group = requireGroupVisibleTo(groupId, callerId);
        requireOwner(groupId, callerId);

        if (group.getStatus() != GroupStatus.ARCHIVED) {
            throw new GroupNotActiveException(
                    "Group is already " + group.getStatus() + "; nothing to reactivate");
        }

        group.setStatus(GroupStatus.ACTIVE);
        log.info("Reactivated group {}", groupId);
        return group;
    }

    /**
     * Applies a whole set of debts to a group, atomically.
     *
     * <h2>Atomicity</h2>
     *
     * <p>Every delta, every ledger entry and the idempotency receipt commit together or not
     * at all. A partially applied expense would leave the group's debt graph disagreeing
     * with its own expense list, with nothing to detect it — and the caller, having received
     * an error, would reasonably retry and apply the surviving half twice.
     *
     * <h2>Idempotency</h2>
     *
     * <p>The receipt is written in <em>this</em> transaction, so it and the deltas cannot
     * disagree. A second call under the same reference is refused — either by the cheap
     * existence check, or, when two callers race past it, by the primary key itself. Both
     * paths raise {@link OperationAlreadyAppliedException}, which {@link GroupService} turns
     * into a successful no-op.
     *
     * <h2>Concurrency</h2>
     *
     * <p>Each balance row is read with an ordinary {@code SELECT}, holding no database lock.
     * The protection against a lost update is the {@code @Version} column: Hibernate turns
     * the dirty-checked amount change into
     * {@code UPDATE group_balances SET amount = ?, version = n+1 WHERE id = ? AND version = n},
     * so if another transaction committed between our read and our write, the row no longer
     * matches, zero rows update, and Spring raises an
     * {@code OptimisticLockingFailureException} at commit. Our arithmetic is thrown away
     * rather than overwriting theirs. That exception surfaces <em>after</em> this method
     * returns; it is caught and retried one level up, in {@link BalanceApplyService}.
     *
     * <p>Deltas are applied in a deterministic pair order. An expense touches several rows,
     * and two concurrent expenses touching the same rows in opposite orders can deadlock in
     * the database — a failure mode the single-row wallet design never had. Sorting makes
     * every transaction take its rows in the same sequence, so one simply waits for the
     * other.
     *
     * @param groupId group to apply to
     * @param request the reference, reason and deltas
     * @throws ResourceNotFoundException          if the group does not exist
     * @throws GroupNotActiveException            if the group has been archived
     * @throws InvalidBalanceDeltaException       if a delta names the same user twice
     * @throws InvalidAmountException             if an amount is null, non-positive or too precise
     * @throws NotAGroupMemberException           if a delta names somebody outside the group
     * @throws OperationAlreadyAppliedException   if this reference has already been applied
     */
    @Transactional
    public void applyBalances(UUID groupId, ApplyBalancesRequest request) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + groupId));
        requireActive(group);

        // Cheap rejection of the common replay case. Not the guarantee — see the reservation
        // below, which is what actually holds under a race.
        if (appliedOperationRepository.existsByReferenceId(request.referenceId())) {
            throw new OperationAlreadyAppliedException(request.referenceId());
        }

        requireWellFormedDeltas(request.deltas());
        requireAllMembers(groupId, request.deltas());

        try {
            // saveAndFlush for the same reason as addMember: the INSERT has to hit the
            // database while we are still inside this try block, so a concurrent apply under
            // the same reference is recognised here rather than escaping at commit.
            appliedOperationRepository.saveAndFlush(
                    new AppliedOperation(request.referenceId(), groupId));
        } catch (DataIntegrityViolationException raceLost) {
            log.debug("Lost the race to reserve reference {}", request.referenceId());
            throw new OperationAlreadyAppliedException(request.referenceId());
        }

        for (BalanceDeltaRequest delta : sortedByPair(request.deltas())) {
            applyDelta(groupId, delta, request);
        }

        log.info("Applied {} delta(s) to group {} under reference {}",
                request.deltas().size(), groupId, request.referenceId());
    }

    /**
     * Moves one pair's balance and appends the entry that explains it.
     *
     * <p><strong>No {@code save()} is called on an existing balance.</strong> It is a managed
     * entity, so JPA dirty checking emits the UPDATE at flush time on its own. Calling
     * {@code save()} would be redundant, and calling {@code saveAndFlush()} would force the
     * write early and change when the lock conflict is detected.
     */
    private void applyDelta(UUID groupId, BalanceDeltaRequest delta, ApplyBalancesRequest request) {
        requireUsableAmount(delta.amount());

        UserPair pair = UserPair.of(delta.debtorId(), delta.creditorId());
        GroupBalance balance = findOrCreateBalance(groupId, pair);

        // The stored amount means "what userLow owes userHigh", so a debt whose debtor is
        // userLow adds to it and one whose debtor is userHigh subtracts. signFor decides
        // which, and rejects a debtor who is not part of the pair at all.
        BigDecimal signed = pair.signFor(delta.debtorId()) > 0
                ? delta.amount()
                : delta.amount().negate();

        balance.setAmount(balance.getAmount().add(signed));

        balanceEntryRepository.save(BalanceEntry.builder()
                .groupId(groupId)
                // Stored as stated, not in canonical order: the ledger has to read back as
                // what happened, not as how the balance row is keyed.
                .debtorId(delta.debtorId())
                .creditorId(delta.creditorId())
                .amount(delta.amount())
                .reason(request.reason())
                .referenceId(request.referenceId())
                .description(request.description())
                .build());
    }

    /**
     * Finds a pair's balance row, creating it at zero the first time the two transact.
     *
     * <p>The insert is flushed immediately so that a concurrent apply creating the same pair
     * is detected here, as a {@code DataIntegrityViolationException} from
     * {@code uq_group_balances_pair}. That exception is deliberately <em>not</em> caught: it
     * propagates, rolls this attempt back, and is retried by {@link BalanceApplyService},
     * whose next attempt finds the row the winner created and updates it instead.
     */
    private GroupBalance findOrCreateBalance(UUID groupId, UserPair pair) {
        return groupBalanceRepository
                .findByGroupIdAndUserLowAndUserHigh(groupId, pair.low(), pair.high())
                .orElseGet(() -> groupBalanceRepository.saveAndFlush(GroupBalance.builder()
                        .groupId(groupId)
                        .userLow(pair.low())
                        .userHigh(pair.high())
                        .amount(BigDecimal.ZERO)
                        .build()));
    }

    /**
     * Orders deltas by the canonical pair they touch, so concurrent applies acquire rows in
     * the same sequence and cannot deadlock against each other.
     */
    private List<BalanceDeltaRequest> sortedByPair(List<BalanceDeltaRequest> deltas) {
        return deltas.stream()
                .sorted(Comparator
                        .comparing((BalanceDeltaRequest d) -> UserPair.of(d.debtorId(), d.creditorId()).low(),
                                UserPair.POSTGRES_UUID_ORDER)
                        .thenComparing(d -> UserPair.of(d.debtorId(), d.creditorId()).high(),
                                UserPair.POSTGRES_UUID_ORDER))
                .toList();
    }

    /**
     * @throws InvalidBalanceDeltaException if any delta is a debt from someone to themselves
     */
    private void requireWellFormedDeltas(List<BalanceDeltaRequest> deltas) {
        for (BalanceDeltaRequest delta : deltas) {
            if (delta.debtorId().equals(delta.creditorId())) {
                throw new InvalidBalanceDeltaException(
                        "A member cannot owe themselves: " + delta.debtorId());
            }
        }
    }

    /**
     * Checks every participant's membership in one query rather than one round trip each.
     *
     * @throws NotAGroupMemberException if any participant is not in the group
     */
    private void requireAllMembers(UUID groupId, List<BalanceDeltaRequest> deltas) {
        Set<UUID> participants = new LinkedHashSet<>();
        for (BalanceDeltaRequest delta : deltas) {
            participants.add(delta.debtorId());
            participants.add(delta.creditorId());
        }

        long found = groupMemberRepository.countMembersIn(groupId, participants);
        if (found != participants.size()) {
            // The specific offenders are deliberately not named: the caller is another
            // service that already knows who it asked about, and echoing user ids back into
            // an error message is how they end up in logs that outlive them.
            throw new NotAGroupMemberException(
                    "Every debtor and creditor must be a current member of the group");
        }
    }

    /**
     * Rejects anything that is not a sum this ledger can faithfully record.
     *
     * @throws InvalidAmountException if null, zero, negative, or finer than 4 decimals
     */
    private void requireUsableAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidAmountException("Amount is required");
        }
        // compareTo against ZERO rather than equals or signum on a scaled value: this is
        // true for 0, 0.00 and 0.0000 alike.
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Amount must be positive");
        }
        if (amount.scale() > MAX_SCALE) {
            throw new InvalidAmountException(
                    "Amount must have at most " + MAX_SCALE + " decimal places");
        }
    }

    /**
     * Loads a group, but only if the caller belongs to it.
     *
     * <p>A non-member gets a 404 rather than a 403, and that is deliberate: confirming that
     * a particular group id exists is already more than somebody outside it should learn.
     *
     * @throws ResourceNotFoundException if the group does not exist or the caller is not in it
     */
    private Group requireGroupVisibleTo(UUID groupId, UUID callerId) {
        if (!groupMemberRepository.existsByIdGroupIdAndIdUserId(groupId, callerId)) {
            throw new ResourceNotFoundException("Group not found: " + groupId);
        }
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + groupId));
    }

    /**
     * @throws NotGroupOwnerException if the caller is a member but not an owner
     */
    private void requireOwner(UUID groupId, UUID callerId) {
        boolean owner = groupMemberRepository.findByIdGroupIdAndIdUserId(groupId, callerId)
                .map(GroupMember::isOwner)
                .orElse(false);

        if (!owner) {
            throw new NotGroupOwnerException("Only the group's owner may do this");
        }
    }

    /**
     * @throws GroupNotActiveException if the group has been archived
     */
    private void requireActive(Group group) {
        if (!group.permitsChanges()) {
            throw new GroupNotActiveException(
                    "Group is " + group.getStatus() + " and cannot be changed");
        }
    }
}

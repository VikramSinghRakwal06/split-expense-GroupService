package com.splitexpense.group.service;

import com.splitexpense.group.dto.request.ApplyBalancesRequest;
import com.splitexpense.group.dto.request.CreateGroupRequest;
import com.splitexpense.group.dto.response.ApplyBalancesResponse;
import com.splitexpense.group.dto.response.BalanceEntryResponse;
import com.splitexpense.group.dto.response.GroupBalancesResponse;
import com.splitexpense.group.dto.response.GroupMemberResponse;
import com.splitexpense.group.dto.response.GroupResponse;
import com.splitexpense.group.entity.Group;
import com.splitexpense.group.entity.GroupMember;
import com.splitexpense.group.exception.ResourceNotFoundException;
import com.splitexpense.group.mapper.BalanceEntryMapper;
import com.splitexpense.group.mapper.GroupMapper;
import com.splitexpense.group.repository.BalanceEntryRepository;
import com.splitexpense.group.repository.GroupMemberRepository;
import com.splitexpense.group.repository.GroupRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The group API as the controller sees it: one dependency, covering groups, membership,
 * balances and history.
 *
 * <h2>Why this class delegates rather than doing the work</h2>
 *
 * <p>The behaviour that matters in this service — transactions, optimistic-lock retries,
 * cache invalidation — is all applied by Spring proxies, and a proxy only intercepts calls
 * arriving from <em>outside</em> the object. Anything this class called on itself would
 * silently skip every one of those annotations. So each concern lives in its own bean and is
 * reached from here through its proxy:
 *
 * <ul>
 *   <li>{@link BalanceApplyService} — retry and cache eviction around a balance change</li>
 *   <li>{@link GroupTransactionService} — the atomic writes and every domain rule</li>
 *   <li>{@link GroupQueryService} — cached balance reads</li>
 * </ul>
 *
 * <h2>Where authorisation happens</h2>
 *
 * <p>Group-scoped reads are checked here, because they do not pass through
 * {@link GroupTransactionService} at all. Writes are checked inside it, where the check and
 * the write share a transaction. Both call the same private helper shape: resolve the
 * caller's membership first, and treat its absence as a 404.
 */
@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    private final GroupTransactionService groupTransactionService;
    private final BalanceApplyService balanceApplyService;
    private final GroupQueryService groupQueryService;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final BalanceEntryRepository balanceEntryRepository;
    private final GroupMapper groupMapper;
    private final BalanceEntryMapper balanceEntryMapper;

    public GroupService(
            GroupTransactionService groupTransactionService,
            BalanceApplyService balanceApplyService,
            GroupQueryService groupQueryService,
            GroupRepository groupRepository,
            GroupMemberRepository groupMemberRepository,
            BalanceEntryRepository balanceEntryRepository,
            GroupMapper groupMapper,
            BalanceEntryMapper balanceEntryMapper) {
        this.groupTransactionService = groupTransactionService;
        this.balanceApplyService = balanceApplyService;
        this.groupQueryService = groupQueryService;
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.balanceEntryRepository = balanceEntryRepository;
        this.groupMapper = groupMapper;
        this.balanceEntryMapper = balanceEntryMapper;
    }

    /**
     * Creates a group owned by the authenticated caller.
     *
     * @param creatorId account identifier from the caller's JWT
     * @param request   name, optional description and optional currency
     * @return the new group, with its single owner member
     */
    public GroupResponse createGroup(UUID creatorId, CreateGroupRequest request) {
        Group group = groupTransactionService.createGroup(
                creatorId, request.name(), request.description(), request.currency());

        return groupMapper.toResponse(
                group, groupMemberRepository.findByIdGroupId(group.getId()));
    }

    /**
     * Every group the caller belongs to.
     *
     * <p>Reached through membership rather than by querying groups directly, so there is no
     * code path here that could list a group the caller is not in.
     *
     * @param userId account identifier from the caller's JWT
     * @return their groups, newest first
     */
    @Transactional(readOnly = true)
    public List<GroupResponse> getGroupsForUser(UUID userId) {
        List<UUID> groupIds = groupMemberRepository.findByIdUserId(userId).stream()
                .map(GroupMember::getGroupId)
                .toList();

        return groupRepository.findAllById(groupIds).stream()
                .sorted(Comparator.comparing(Group::getCreatedAt).reversed())
                .map(group -> groupMapper.toResponse(
                        group, groupMemberRepository.findByIdGroupId(group.getId())))
                .toList();
    }

    /**
     * One group and its membership.
     *
     * @param groupId  group to read
     * @param callerId account identifier from the caller's JWT
     * @return the group
     * @throws ResourceNotFoundException if it does not exist, or the caller is not in it
     */
    @Transactional(readOnly = true)
    public GroupResponse getGroup(UUID groupId, UUID callerId) {
        Group group = requireGroupVisibleTo(groupId, callerId);
        return groupMapper.toResponse(group, groupMemberRepository.findByIdGroupId(groupId));
    }

    /**
     * Adds a user to a group.
     *
     * @param groupId  group to add to
     * @param callerId account identifier from the caller's JWT; must be the owner
     * @param userId   the person to add
     * @return the new membership
     */
    public GroupMemberResponse addMember(UUID groupId, UUID callerId, UUID userId) {
        return groupMapper.toResponse(
                groupTransactionService.addMember(groupId, callerId, userId));
    }

    /**
     * Removes a member, provided they have settled up.
     *
     * @param groupId  group to remove from
     * @param callerId account identifier from the caller's JWT
     * @param userId   the person to remove
     */
    public void removeMember(UUID groupId, UUID callerId, UUID userId) {
        groupTransactionService.removeMember(groupId, callerId, userId);
    }

    /**
     * Archives a group, provided every balance in it has settled.
     *
     * @param groupId  group to archive
     * @param callerId account identifier from the caller's JWT; must be the owner
     * @return the archived group
     */
    public GroupResponse archiveGroup(UUID groupId, UUID callerId) {
        Group group = groupTransactionService.archiveGroup(groupId, callerId);
        return groupMapper.toResponse(group, groupMemberRepository.findByIdGroupId(groupId));
    }

    /**
     * Reactivates an archived group.
     *
     * @param groupId  group to reactivate
     * @param callerId account identifier from the caller's JWT; must be the owner
     * @return the reactivated group
     */
    public GroupResponse reactivateGroup(UUID groupId, UUID callerId) {
        Group group = groupTransactionService.reactivateGroup(groupId, callerId);
        return groupMapper.toResponse(group, groupMemberRepository.findByIdGroupId(groupId));
    }

    /**
     * Reads a group's outstanding debts and each member's net position.
     *
     * <p>Authorises first, then delegates to the cached read. The two are separate calls on
     * purpose: the cache is keyed by group alone so that every member shares one entry, which
     * means the membership check cannot live inside the cached method — a cache hit would
     * skip it entirely and serve one group's balances to anybody who asked.
     *
     * @param groupId  group to read
     * @param callerId account identifier from the caller's JWT
     * @return the group's debt graph
     * @throws ResourceNotFoundException if it does not exist, or the caller is not in it
     */
    public GroupBalancesResponse getBalances(UUID groupId, UUID callerId) {
        requireMembership(groupId, callerId);
        return groupQueryService.getBalances(groupId);
    }

    /**
     * One page of a group's activity feed, newest entry first.
     *
     * <p>Not cached: a history page is large, differs per page number, and is read far less
     * often than the balances, so it would mostly evict more useful entries.
     *
     * @param groupId  group whose history is being read
     * @param callerId account identifier from the caller's JWT
     * @param pageable page number, size and sort supplied by the controller
     * @return the requested page, already mapped to DTOs
     * @throws ResourceNotFoundException if it does not exist, or the caller is not in it
     */
    @Transactional(readOnly = true)
    public Page<BalanceEntryResponse> getEntries(UUID groupId, UUID callerId, Pageable pageable) {
        requireMembership(groupId, callerId);
        return balanceEntryRepository.findByGroupId(groupId, pageable)
                .map(balanceEntryMapper::toResponse);
    }

    /**
     * INTERNAL. Applies a set of debts on expense-service's instruction.
     *
     * <h2>Why a replay is a success</h2>
     *
     * <p>expense-service may retry an apply it never learned the outcome of, because a
     * timeout says nothing about what the server did. When the reference has already been
     * recorded, {@link GroupTransactionService} raises
     * {@link OperationAlreadyAppliedException} and nothing changes — and that is exactly the
     * answer the caller needs, so it is reported as a {@code 200} carrying
     * {@code applied: false} rather than as an error.
     *
     * <p>This is the whole reason the expense saga needs no compensation step: whatever
     * happened to the first attempt, asking again converges on the same state.
     *
     * <p>No membership check for the caller: this endpoint is restricted to
     * {@code ROLE_ADMIN} in {@code SecurityConfig}, and the service account calling it is not
     * a member of any group. The <em>subjects</em> of the deltas are still checked, inside
     * the transaction.
     *
     * @param groupId group to apply to
     * @param request the reference, reason and deltas
     * @return the outcome, and the group's balances as they now stand
     */
    public ApplyBalancesResponse applyBalances(UUID groupId, ApplyBalancesRequest request) {
        boolean applied;
        try {
            balanceApplyService.apply(groupId, request);
            applied = true;
        } catch (OperationAlreadyAppliedException alreadyApplied) {
            log.info("Reference {} was already applied to group {}; returning the existing state",
                    request.referenceId(), groupId);
            applied = false;
        }

        return new ApplyBalancesResponse(
                groupId,
                request.referenceId(),
                applied,
                groupQueryService.getBalances(groupId));
    }

    /**
     * Loads a group, but only if the caller belongs to it.
     *
     * <p>A non-member gets a 404 rather than a 403: confirming that a particular group id
     * exists is already more than somebody outside it should learn.
     *
     * @throws ResourceNotFoundException if the group does not exist or the caller is not in it
     */
    private Group requireGroupVisibleTo(UUID groupId, UUID callerId) {
        requireMembership(groupId, callerId);
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + groupId));
    }

    /**
     * @throws ResourceNotFoundException if the caller is not a member of the group
     */
    private void requireMembership(UUID groupId, UUID callerId) {
        if (!groupMemberRepository.existsByIdGroupIdAndIdUserId(groupId, callerId)) {
            throw new ResourceNotFoundException("Group not found: " + groupId);
        }
    }
}

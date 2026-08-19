package com.splitexpense.group.service;

import com.splitexpense.group.config.CacheConfig;
import com.splitexpense.group.dto.response.GroupBalancesResponse;
import com.splitexpense.group.entity.Group;
import com.splitexpense.group.exception.ResourceNotFoundException;
import com.splitexpense.group.mapper.GroupBalanceMapper;
import com.splitexpense.group.repository.GroupBalanceRepository;
import com.splitexpense.group.repository.GroupMemberRepository;
import com.splitexpense.group.repository.GroupRepository;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached reads of a group's debt graph, keyed by group id.
 *
 * <h2>Why this is its own bean</h2>
 *
 * <p>Same reason the retry and transaction boundaries are separate beans: {@code @Cacheable}
 * is applied by a proxy, and a proxy only intercepts calls arriving from outside the object.
 * If this method lived on {@link GroupService}, every internal call would reach it as a plain
 * {@code this} invocation and silently never cache anything — the annotation would look
 * correct in review and do nothing at runtime.
 *
 * <h2>What is cached, and what deliberately is not</h2>
 *
 * <p>Only the balances read. A group's graph is read far more often than it changes — every
 * time anyone opens the group — and computing it means two queries plus a fold over the pair
 * rows.
 *
 * <p>Membership is <em>not</em> cached, even though it is read on every single request. It is
 * the authorisation boundary of the whole service, and a stale membership entry would mean
 * somebody removed from a group could still read it for the length of a TTL. A primary-key
 * lookup is cheap; being wrong about who may see a group is not.
 *
 * <p>Nothing on the write path consults this cache either: an apply validates membership and
 * reads every balance it changes from the database, under the protection of the version
 * column. See {@link BalanceApplyService}.
 *
 * @see CacheConfig for the TTL and serialisation
 */
@Service
public class GroupQueryService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupBalanceRepository groupBalanceRepository;
    private final GroupBalanceMapper groupBalanceMapper;

    public GroupQueryService(
            GroupRepository groupRepository,
            GroupMemberRepository groupMemberRepository,
            GroupBalanceRepository groupBalanceRepository,
            GroupBalanceMapper groupBalanceMapper) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupBalanceRepository = groupBalanceRepository;
        this.groupBalanceMapper = groupBalanceMapper;
    }

    /**
     * Reads a group's whole debt graph, from Redis when possible.
     *
     * <p>Performs <strong>no authorisation of its own</strong>, and takes no caller id — by
     * design, because the cache key must be the group alone. Two members of the same group
     * see the same graph, and mixing the caller into the key would multiply the cache by the
     * membership while caching identical values. Every path that reaches this method must
     * therefore have already established that the caller belongs to the group; {@link
     * GroupService} is the only such path, and it checks first.
     *
     * <p>Returns a DTO rather than entities on purpose: a detached entity in a shared cache
     * would be a trap, since callers could mutate it and Hibernate would know nothing about
     * it. A record is immutable and safe to hand to every caller.
     *
     * @param groupId group to read
     * @return its outstanding debts and every member's net position
     * @throws ResourceNotFoundException if no such group exists; not cached, so a group
     *                                   created moments later is visible immediately
     */
    @Cacheable(cacheNames = CacheConfig.GROUP_BALANCES_CACHE, key = "#groupId")
    @Transactional(readOnly = true)
    public GroupBalancesResponse getBalances(UUID groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + groupId));

        return groupBalanceMapper.toResponse(
                group,
                groupMemberRepository.findByIdGroupId(groupId),
                groupBalanceRepository.findByGroupId(groupId));
    }
}

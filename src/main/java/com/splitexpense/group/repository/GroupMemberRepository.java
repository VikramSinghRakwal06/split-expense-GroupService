package com.splitexpense.group.repository;

import com.splitexpense.group.entity.GroupMember;
import com.splitexpense.group.entity.GroupMemberId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link GroupMember}.
 *
 * <p>Every group-scoped authorisation decision in the service is made from this table, so
 * the membership lookups below are on the hot path of essentially every request.
 */
@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    /**
     * The caller's own membership of a group, which carries their role.
     *
     * <p>An index lookup on the primary key. This is the method that answers both "may this
     * caller see this group" and "may they administer it".
     *
     * @param groupId group being accessed
     * @param userId  caller, from a verified JWT
     * @return the membership, or empty if the caller is not in the group
     */
    Optional<GroupMember> findByIdGroupIdAndIdUserId(UUID groupId, UUID userId);

    /**
     * Whether a user belongs to a group. Primary-key lookup.
     *
     * @param groupId group being accessed
     * @param userId  the person in question
     * @return whether the membership row exists
     */
    boolean existsByIdGroupIdAndIdUserId(UUID groupId, UUID userId);

    /**
     * Everyone in a group.
     *
     * @param groupId group to list
     * @return its members, in no guaranteed order
     */
    List<GroupMember> findByIdGroupId(UUID groupId);

    /**
     * Every membership held by one user, serving "which groups am I in" — the first query
     * most screens make. Backed by {@code idx_group_members_user}.
     *
     * @param userId caller, from a verified JWT
     * @return their memberships, in no guaranteed order
     */
    List<GroupMember> findByIdUserId(UUID userId);

    /**
     * How many of the given users are members of a group.
     *
     * <p>Used to validate a whole set of balance deltas in one query rather than one
     * round trip per participant: an expense split ten ways would otherwise cost ten
     * lookups before any work began. The caller compares the count against the size of the
     * distinct set it asked about.
     *
     * @param groupId group to check within
     * @param userIds distinct user ids to look for
     * @return how many of them hold a membership row
     */
    @Query("""
            SELECT COUNT(m) FROM GroupMember m
            WHERE m.id.groupId = :groupId AND m.id.userId IN :userIds
            """)
    long countMembersIn(@Param("groupId") UUID groupId, @Param("userIds") Iterable<UUID> userIds);
}

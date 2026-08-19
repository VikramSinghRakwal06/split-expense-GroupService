package com.splitexpense.group.mapper;

import com.splitexpense.group.dto.response.GroupMemberResponse;
import com.splitexpense.group.dto.response.GroupResponse;
import com.splitexpense.group.entity.Group;
import com.splitexpense.group.entity.GroupMember;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Converts {@link Group} and {@link GroupMember} entities into their client-facing
 * representations.
 *
 * <p>Kept as an explicit component rather than a mapping framework: there are two
 * translations, and writing them by hand makes it obvious that {@code version} is excluded
 * rather than relying on a generator to leave it out.
 *
 * <p>Mapping through a DTO — rather than serialising the entity — also means a response is a
 * detached snapshot. Returning the entity would let Jackson touch it after the transaction
 * closed, and would tie the JSON contract to the database schema so that a column rename
 * became a breaking API change.
 */
@Component
public class GroupMapper {

    /**
     * Members are returned oldest first, so the owner who created the group heads the list
     * and the order is stable between calls. The repository makes no ordering promise of its
     * own, and an unstable member list would make responses needlessly hard to diff in tests
     * and in a client's cache.
     */
    private static final Comparator<GroupMember> BY_JOIN_ORDER =
            Comparator.comparing(GroupMember::getJoinedAt).thenComparing(GroupMember::getUserId);

    /**
     * @param group   entity to convert, never null
     * @param members the group's current membership, in any order
     * @return the safe-to-serialise view of that group
     */
    public GroupResponse toResponse(Group group, List<GroupMember> members) {
        List<GroupMemberResponse> memberViews = members.stream()
                .sorted(BY_JOIN_ORDER)
                .map(this::toResponse)
                .toList();

        return new GroupResponse(
                group.getId(),
                group.getName(),
                group.getDescription(),
                group.getCurrency(),
                group.getCreatedBy(),
                group.getStatus(),
                memberViews,
                group.getCreatedAt(),
                group.getUpdatedAt());
    }

    /**
     * @param member entity to convert, never null
     * @return the safe-to-serialise view of that membership
     */
    public GroupMemberResponse toResponse(GroupMember member) {
        return new GroupMemberResponse(
                member.getUserId(),
                member.getRole(),
                member.getJoinedAt());
    }
}

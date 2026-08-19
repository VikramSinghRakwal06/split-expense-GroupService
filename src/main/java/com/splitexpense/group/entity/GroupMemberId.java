package com.splitexpense.group.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key of {@link GroupMember}: one person, in one group.
 *
 * <p>A natural key rather than a surrogate one, because the pair genuinely <em>is</em> the
 * identity of a membership — and making it the primary key is what turns "a person may
 * only be in a group once" into a guarantee the database enforces, rather than a check the
 * service performs and two concurrent requests can both pass.
 *
 * <p>A class rather than a record: JPA requires an {@code @Embeddable} id to have a
 * no-argument constructor and to be non-final, which records are not.
 */
@Embeddable
public class GroupMemberId implements Serializable {

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Required by JPA. */
    protected GroupMemberId() {
    }

    public GroupMemberId(UUID groupId, UUID userId) {
        this.groupId = groupId;
        this.userId = userId;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public UUID getUserId() {
        return userId;
    }

    /**
     * Value equality over both columns — mandatory for a composite key, because Hibernate
     * uses it to decide whether two rows are the same entity within a persistence context.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GroupMemberId id)) {
            return false;
        }
        return Objects.equals(groupId, id.groupId) && Objects.equals(userId, id.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, userId);
    }

    @Override
    public String toString() {
        return "GroupMemberId[group=%s, user=%s]".formatted(groupId, userId);
    }
}

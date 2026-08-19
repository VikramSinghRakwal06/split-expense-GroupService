package com.splitexpense.group.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

/**
 * One person's membership of one group.
 *
 * <p>Membership is the authorisation boundary of this whole service: every group-scoped
 * endpoint answers "may this caller see this group" by asking whether a row exists here.
 * Unlike the {@code /me} endpoints, which take the caller's identity from the token and
 * expose no parameter to point elsewhere, a group-scoped endpoint <em>does</em> name a
 * group in the path — so the check is explicit, and it is the thing that has to be right.
 *
 * <p>Carries no {@code @ManyToOne} to {@link Group}. The foreign key exists in the database;
 * what is avoided is the object graph, so that listing a user's groups does not pull every
 * group's full row into the persistence context on the way.
 *
 * <h2>Why this implements {@link Persistable}</h2>
 *
 * <p>Spring Data decides between {@code persist} and {@code merge} by asking whether an
 * entity is new, and its default answer is "the id is null". That default is wrong for every
 * entity with an <em>assigned</em> key, which this one has: the id is populated by the
 * constructor, so {@code save} would conclude the row already exists and issue a
 * {@code merge} — an UPDATE.
 *
 * <p>The consequence is not a missing 409. It is that adding somebody who is already in the
 * group would <strong>silently overwrite their existing row</strong>, and since the
 * constructor used for new members hard-codes {@link GroupMemberRole#MEMBER}, re-adding a
 * group's owner would quietly demote them and strip their authority. Forcing a
 * {@code persist} makes the primary key refuse the duplicate instead, which is the behaviour
 * {@code GroupTransactionService#addMember} is written against.
 */
@Entity
@Table(name = "group_members")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupMember implements Persistable<GroupMemberId> {

    @EmbeddedId
    private GroupMemberId id;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private GroupMemberRole role;

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    /**
     * Whether this instance corresponds to a row that is already in the database.
     *
     * <p>Not a column — it is persistence bookkeeping, flipped by the callbacks below once
     * the row exists or has been read back.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean persisted;

    /**
     * @param groupId group being joined
     * @param userId  the person joining, from a verified JWT
     * @param role    their authority within this group
     */
    public GroupMember(UUID groupId, UUID userId, GroupMemberRole role) {
        this.id = new GroupMemberId(groupId, userId);
        this.role = role;
    }

    /**
     * Tells Spring Data to {@code persist} rather than {@code merge}. See the class Javadoc
     * for why the default would silently demote a group's owner.
     */
    @Override
    public boolean isNew() {
        return !persisted;
    }

    /**
     * Marks the instance as corresponding to a real row, after it has been inserted or
     * loaded. Without this, an entity read from the database and then saved again would be
     * treated as new and fail on its own primary key.
     */
    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }

    public UUID getGroupId() {
        return id.getGroupId();
    }

    public UUID getUserId() {
        return id.getUserId();
    }

    /**
     * @return whether this member may add and remove other members
     */
    public boolean isOwner() {
        return role == GroupMemberRole.OWNER;
    }

    /** Identity is the composite key, which is meaningful from construction onwards. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        // instanceof rather than getClass() so a Hibernate lazy proxy still compares equal.
        if (!(other instanceof GroupMember member)) {
            return false;
        }
        return id != null && id.equals(member.id);
    }

    @Override
    public int hashCode() {
        return GroupMember.class.hashCode();
    }
}

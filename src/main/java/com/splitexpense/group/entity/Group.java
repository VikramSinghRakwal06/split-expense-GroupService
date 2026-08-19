package com.splitexpense.group.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A set of people who share expenses: a flat, a trip, a dinner club.
 *
 * <p>The group is the scope of every balance in this service. Two people can owe each other
 * different amounts in two different groups, and those debts never mix — which is what lets
 * a shared flat and a one-off holiday be settled separately.
 *
 * <p>Holds no collection of members or balances. Those are separate entities keyed by
 * {@code group_id}, deliberately not mapped as {@code @OneToMany}: a group's balance graph
 * grows with the square of its membership, and an association would drag all of it into
 * memory to answer "what is this group called".
 *
 * <p>Never serialised to a client; controllers map it to a {@code GroupResponse}.
 */
@Entity
@Table(name = "groups")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Group {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /**
     * ISO-4217 code every expense in this group is denominated in.
     *
     * <p>{@code updatable = false}: re-denominating a group whose balances are already
     * non-zero would restate what its members owe each other without anyone agreeing to it.
     */
    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency = "INR";

    /**
     * The user who created the group, as identified by auth-service.
     *
     * <p>Not a foreign key: users live in another service behind another database.
     * {@code updatable = false} because authorship is a historical fact — ownership is
     * carried by {@link GroupMember#getRole()} and can be reassigned there.
     */
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GroupStatus status = GroupStatus.ACTIVE;

    /**
     * Optimistic-locking counter, managed entirely by Hibernate — application code must
     * never read or set it. Left null by the builder; Hibernate assigns 0 on insert.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @return whether balances in this group may currently change
     */
    public boolean permitsChanges() {
        return status != null && status.permitsChanges();
    }

    /**
     * Identity is the surrogate key alone. A transient instance (null id) is equal only
     * to itself, which keeps unsaved entities from colliding inside collections.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        // instanceof rather than getClass() so a Hibernate lazy proxy still compares equal.
        if (!(other instanceof Group group)) {
            return false;
        }
        return id != null && id.equals(group.id);
    }

    /**
     * Constant across the entity's lifecycle, so a Group stays findable in a HashSet
     * after an id is assigned by the insert.
     */
    @Override
    public int hashCode() {
        return Group.class.hashCode();
    }
}

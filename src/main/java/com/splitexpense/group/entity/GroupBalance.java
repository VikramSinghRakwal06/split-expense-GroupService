package com.splitexpense.group.entity;

import com.splitexpense.group.domain.UserPair;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
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
 * What one pair of people owe each other inside one group.
 *
 * <p>This is the only mutable row in the service, and the analogue of a wallet balance in
 * the platform this one replaced — with one difference that changes everything downstream:
 * it is <strong>signed</strong>. A wallet may never go negative, and a check constraint
 * enforces it. A debt is negative half the time by construction, because
 * {@link #amount} means "what {@link #userLow} owes {@link #userHigh}" and the debt runs
 * both ways over a group's life.
 *
 * <h2>Canonical ordering</h2>
 *
 * <p>{@code userLow} and {@code userHigh} are never assigned directly from a request. They
 * come from {@link UserPair#of}, which orders them the way PostgreSQL's {@code uuid} type
 * does — <em>not</em> the way {@code UUID.compareTo} does. See that class for why the
 * distinction is load-bearing rather than pedantic.
 *
 * <p>Every change to {@link #amount} is paired, inside one transaction, with an immutable
 * {@link BalanceEntry} recording it: the amount is a running total and the entries are the
 * evidence for it.
 */
@Entity
@Table(name = "group_balances")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class GroupBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    /** The smaller of the two ids under PostgreSQL's {@code uuid} ordering. */
    @Column(name = "user_low", nullable = false, updatable = false)
    private UUID userLow;

    /** The larger of the two ids under PostgreSQL's {@code uuid} ordering. */
    @Column(name = "user_high", nullable = false, updatable = false)
    private UUID userHigh;

    /**
     * What {@link #userLow} owes {@link #userHigh}. Negative means the debt runs the other
     * way; zero means they are square.
     *
     * <p>Always exact decimal, never a floating-point type. Compare with {@code compareTo},
     * never {@code equals}: {@code new BigDecimal("0.00").equals(new BigDecimal("0.0000"))}
     * is false, because equals considers scale, and a settled balance read back from the
     * database at scale 4 would fail to match a literal zero written at scale 2.
     */
    @Builder.Default
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;

    /**
     * Optimistic-locking counter, managed entirely by Hibernate — application code must
     * never read or set it.
     *
     * <p>On every UPDATE Hibernate appends {@code AND version = ?} to the WHERE clause and
     * increments the stored value. Two expenses in the same group touching the same pair
     * both read this row; without the version, the second write would silently discard the
     * first and a debt would simply vanish. The loser instead matches zero rows and is
     * retried against the now-current amount.
     *
     * <p>Left null by the builder: Hibernate assigns 0 on the initial insert.
     *
     * @see com.splitexpense.group.service.GroupTransactionService
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
     * @return the canonical pair this row holds the balance for
     */
    public UserPair pair() {
        return new UserPair(userLow, userHigh);
    }

    /**
     * How much {@code user} is owed by the other member of this pair.
     *
     * @param user one of the two users in this pair
     * @return a positive value if the other owes {@code user}, negative if {@code user} owes
     * @throws IllegalArgumentException if {@code user} is not part of this pair
     */
    public BigDecimal netFor(UUID user) {
        if (userHigh.equals(user)) {
            return amount;
        }
        if (userLow.equals(user)) {
            return amount.negate();
        }
        throw new IllegalArgumentException(
                "User %s is not part of the pair (%s, %s)".formatted(user, userLow, userHigh));
    }

    /**
     * @return whether the two members are square
     */
    public boolean isSettled() {
        // compareTo, never equals: this must be true for 0, 0.00 and 0.0000 alike.
        return amount.compareTo(BigDecimal.ZERO) == 0;
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
        if (!(other instanceof GroupBalance balance)) {
            return false;
        }
        return id != null && id.equals(balance.id);
    }

    /**
     * Constant across the entity's lifecycle, so a balance stays findable in a HashSet
     * after an id is assigned by the insert.
     */
    @Override
    public int hashCode() {
        return GroupBalance.class.hashCode();
    }
}

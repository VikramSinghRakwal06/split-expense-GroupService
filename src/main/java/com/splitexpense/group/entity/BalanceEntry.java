package com.splitexpense.group.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One immutable record of a debt changing between two people.
 *
 * <p><strong>This table is append-only.</strong> A row is written when a
 * {@link GroupBalance} changes and is never updated or deleted afterwards; a mistake is
 * corrected by appending an entry in the opposite direction — which is exactly what voiding
 * an expense does — not by editing history. That is what makes the graph auditable: the
 * sequence of entries for a group is a complete, replayable account of how every balance in
 * it reached its present value.
 *
 * <p>The rule is enforced structurally rather than by convention:
 * <ul>
 *   <li>the class exposes <em>no setters</em>, so an instance cannot be mutated in Java;</li>
 *   <li>every column is mapped {@code updatable = false}, so even a mutation reaching the
 *       persistence context by reflection produces no UPDATE statement;</li>
 *   <li>{@link com.splitexpense.group.repository.BalanceEntryRepository} deliberately does
 *       not extend {@code JpaRepository}, so no delete method exists to call.</li>
 * </ul>
 *
 * <h2>Not stored in canonical order</h2>
 *
 * <p>Unlike {@link GroupBalance}, {@link #debtorId} and {@link #creditorId} are stored
 * exactly as the caller stated them. The balance row is an index and needs one row per
 * pair; the ledger is a narrative and needs to read back as what actually happened — "Bob
 * owes Alice 450", not "the (Alice, Bob) balance moved by -450".
 */
@Entity
@Table(name = "balance_entries")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BalanceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    /** Who came to owe money as a result of this entry. */
    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    /** Who came to be owed it. */
    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    /**
     * Magnitude of the change, always positive. Direction is carried by the two user
     * columns, so a negative value here would be a debt in disguise and would corrupt any
     * sum over the table.
     */
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 30)
    private BalanceEntryReason reason;

    /**
     * The expense or settlement in expense-service that caused this.
     *
     * <p>Mandatory, unlike the wallet ledger's optional reference: every change to a debt
     * originates in another service, and an entry that cannot be traced back to the thing
     * that caused it is not auditable.
     */
    @Column(name = "reference_id", nullable = false, updatable = false, length = 100)
    private String referenceId;

    @Column(name = "description", updatable = false, length = 255)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
        if (!(other instanceof BalanceEntry entry)) {
            return false;
        }
        return id != null && id.equals(entry.id);
    }

    /**
     * Constant across the entity's lifecycle, so an entry stays findable in a HashSet
     * after an id is assigned by the insert.
     */
    @Override
    public int hashCode() {
        return BalanceEntry.class.hashCode();
    }
}

package com.payflow.wallet.entity;

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
 * One immutable record of money moving into or out of a {@link Wallet}.
 *
 * <p><strong>This table is append-only.</strong> A row is written when a balance changes
 * and is never updated or deleted afterwards; a mistake is corrected by appending a
 * compensating entry in the opposite direction, not by editing history. That is what makes
 * the ledger auditable: the sequence of entries for a wallet is a complete, replayable
 * account of how its balance reached its present value.
 *
 * <p>The rule is enforced structurally rather than by convention:
 * <ul>
 *   <li>the class exposes <em>no setters</em>, so an instance cannot be mutated in Java;</li>
 *   <li>every column is mapped {@code updatable = false}, so even a mutation reaching the
 *       persistence context by reflection produces no UPDATE statement;</li>
 *   <li>{@link com.payflow.wallet.repository.LedgerEntryRepository} deliberately does not
 *       extend {@code JpaRepository}, so no delete method exists to call.</li>
 * </ul>
 *
 * <p>Holds {@code walletId} as a plain UUID rather than a {@code @ManyToOne} association.
 * The foreign key still exists in the database; what is avoided is the object graph. A
 * statement page renders thousands of these, and an association would either trigger a
 * query per row or drag the full wallet into memory for entries that only ever need its
 * id.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    /**
     * Magnitude of the movement, always positive. Direction is carried by {@link #type}.
     */
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 10)
    private LedgerEntryType type;

    /**
     * The wallet's balance immediately after this entry was applied.
     *
     * <p>Redundant with a running sum over earlier entries, and that redundancy is the
     * point: a statement can be rendered without replaying arithmetic, and any divergence
     * between this column and {@code wallets.balance} is a detectable inconsistency rather
     * than a silent one.
     */
    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    /** Caller's correlation id — a payment id, a top-up reference. May be null. */
    @Column(name = "reference_id", updatable = false, length = 100)
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
        if (!(other instanceof LedgerEntry entry)) {
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
        return LedgerEntry.class.hashCode();
    }
}

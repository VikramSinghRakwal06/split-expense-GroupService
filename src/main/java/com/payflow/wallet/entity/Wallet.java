package com.payflow.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A user's stored-value account: what they hold, in what currency, and whether it may
 * currently move.
 *
 * <p>This is the only mutable row in the service. Every change to {@link #balance} is
 * paired, inside one transaction, with an immutable {@link LedgerEntry} recording it — the
 * balance is a running total and the ledger is the evidence for it.
 *
 * <p>Never serialised to a client; controllers map it to a {@code WalletResponse}.
 */
@Entity
@Table(name = "wallets")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The account holder, as identified by auth-service.
     *
     * <p>Not a foreign key: users live in another service behind another database, so
     * there is nothing here to reference. {@code updatable = false} because a wallet is
     * never reassigned to a different person — that would silently transfer their money.
     */
    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    /**
     * Current balance, always exact decimal and never a floating-point type.
     *
     * <p>Compare with {@code compareTo}, never {@code equals}: {@code new
     * BigDecimal("100.00").equals(new BigDecimal("100.0000"))} is false, because equals
     * considers scale, and the same amount read from the database at scale 4 would fail
     * to match a literal written at scale 2.
     */
    @Builder.Default
    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    /** ISO-4217 code. Fixed at creation; a wallet is not re-denominated in place. */
    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency = "INR";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WalletStatus status = WalletStatus.ACTIVE;

    /**
     * Optimistic-locking counter, managed entirely by Hibernate — application code must
     * never read or set it.
     *
     * <p>On every UPDATE Hibernate appends {@code AND version = ?} to the WHERE clause and
     * increments the stored value. If another transaction committed a change to this row
     * since it was read, the predicate matches nothing, Hibernate sees zero affected rows
     * and raises an optimistic-lock failure instead of overwriting that change. This is
     * what makes concurrent credits and debits safe without holding a database lock for
     * the duration of the transaction.
     *
     * <p>Left null by the builder: Hibernate assigns 0 on the initial insert.
     *
     * @see com.payflow.wallet.service.WalletTransactionService
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
     * @return whether money may currently move into or out of this wallet
     */
    public boolean isActive() {
        return status != null && status.permitsMovement();
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
        if (!(other instanceof Wallet wallet)) {
            return false;
        }
        return id != null && id.equals(wallet.id);
    }

    /**
     * Constant across the entity's lifecycle, so a Wallet stays findable in a HashSet
     * after an id is assigned by the insert.
     */
    @Override
    public int hashCode() {
        return Wallet.class.hashCode();
    }
}

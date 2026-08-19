package com.splitexpense.group.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

/**
 * A receipt proving that one expense or settlement has already been applied to this group.
 *
 * <h2>Why this table is the reason the expense saga needs no compensation</h2>
 *
 * <p>expense-service applies an expense's deltas with a single call, and may have to retry
 * that call without ever having learned whether the first attempt landed — a timeout tells
 * the caller nothing about what the server did with the request.
 *
 * <p>In the payments platform this service replaces, that ambiguity was unresolvable: the
 * wallet endpoints applied a movement unconditionally on every call, so a retry might
 * double-charge and a compensating reversal might reverse something that never happened.
 * The saga documented the resulting hole rather than closing it, and left transfers stranded
 * for manual reconciliation.
 *
 * <p>Here the retry is simply safe. The reference id is the primary key, so a second apply
 * under the same reference is refused by the database, and the row is written in the
 * <em>same transaction</em> as the balance mutations it describes — so the receipt and the
 * deltas cannot disagree. Either both committed or neither did, and a caller who never saw
 * the answer can ask again as many times as it likes.
 *
 * <p>Immutable, like {@link BalanceEntry}: no setters, every column {@code updatable = false},
 * and a repository narrowed so nothing can delete a receipt and re-enable a double charge.
 *
 * <h2>Why this implements {@link Persistable}</h2>
 *
 * <p>Spring Data decides between {@code persist} and {@code merge} by asking whether the id
 * is null, and this entity's id is <em>assigned</em> — it is expense-service's reference,
 * populated by the constructor. Left to the default, {@code saveAndFlush} would therefore
 * issue a {@code merge}, quietly UPDATING an existing receipt instead of colliding with it.
 *
 * <p>That would defeat the entire purpose of the table. The cheap
 * {@code existsByReferenceId} check in {@code GroupTransactionService} catches the ordinary
 * replay, but the check races: two genuinely concurrent applies of the same expense can both
 * pass it, and the primary key is what is supposed to stop the second. With a {@code merge}
 * it would not stop anything, both applies would commit their deltas, and the expense would
 * be charged twice — the precise failure this design exists to make impossible.
 */
@Entity
@Table(name = "applied_operations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AppliedOperation implements Persistable<String> {

    /**
     * The expense or settlement id from expense-service, used verbatim as the key.
     *
     * <p>A natural key rather than a surrogate one, because the whole purpose of the row is
     * for the database to refuse a duplicate of it.
     */
    @Id
    @Column(name = "reference_id", nullable = false, updatable = false, length = 100)
    private String referenceId;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @CreationTimestamp
    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;

    /**
     * Whether this instance corresponds to a row that is already in the database.
     *
     * <p>Not a column — it is persistence bookkeeping, flipped by the callbacks below once
     * the row exists or has been read back.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    private boolean persisted;

    /**
     * @param referenceId the causing expense or settlement id
     * @param groupId     the group its deltas were applied to
     */
    public AppliedOperation(String referenceId, UUID groupId) {
        this.referenceId = referenceId;
        this.groupId = groupId;
    }

    /**
     * The assigned primary key. Required by {@link Persistable}.
     */
    @Override
    public String getId() {
        return referenceId;
    }

    /**
     * Tells Spring Data to {@code persist} rather than {@code merge}, so a duplicate
     * reference collides with the primary key instead of overwriting the receipt. See the
     * class Javadoc for why that distinction is the whole point of this entity.
     */
    @Override
    public boolean isNew() {
        return !persisted;
    }

    /**
     * Marks the instance as corresponding to a real row, after it has been inserted or
     * loaded.
     */
    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }

    /** Identity is the natural key, which is meaningful from construction onwards. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        // instanceof rather than getClass() so a Hibernate lazy proxy still compares equal.
        if (!(other instanceof AppliedOperation operation)) {
            return false;
        }
        return referenceId != null && referenceId.equals(operation.referenceId);
    }

    @Override
    public int hashCode() {
        return AppliedOperation.class.hashCode();
    }
}

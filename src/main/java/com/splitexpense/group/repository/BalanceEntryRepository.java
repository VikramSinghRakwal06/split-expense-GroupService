package com.splitexpense.group.repository;

import com.splitexpense.group.entity.BalanceEntry;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the append-only {@link BalanceEntry} table.
 *
 * <p><strong>Deliberately does not extend {@code JpaRepository}.</strong> It extends the
 * bare {@code Repository} marker instead, which contributes no methods at all, so this
 * interface exposes exactly the operations declared below and nothing else. There is no
 * {@code delete}, no {@code deleteAll}, no {@code saveAndFlush} that could rewrite an entry
 * — not because callers are trusted not to use them, but because they do not exist to be
 * called or to be reached by mistake in a future change.
 *
 * <p>That is the whole point of an immutable ledger: correcting a mistake means appending a
 * compensating entry, never editing or removing the original. Narrowing the repository
 * turns that rule from a comment into a compile error.
 *
 * @see BalanceEntry
 */
@Repository
public interface BalanceEntryRepository
        extends org.springframework.data.repository.Repository<BalanceEntry, UUID> {

    /**
     * Appends one entry. The only write this interface permits.
     *
     * @param entry a new, never-before-persisted entry
     * @return the persisted instance, with its generated id and timestamp populated
     */
    BalanceEntry save(BalanceEntry entry);

    /**
     * One page of a group's activity feed.
     *
     * <p>Callers pass a {@code Pageable} sorted by {@code createdAt} descending, which
     * {@code idx_balance_entries_group_created} serves directly — PostgreSQL walks the index
     * rather than sorting the matched rows.
     *
     * @param groupId  group whose history is being read
     * @param pageable page number, size and sort
     * @return the requested page, newest entry first under the expected sort
     */
    Page<BalanceEntry> findByGroupId(UUID groupId, Pageable pageable);

    /**
     * Number of entries recorded against a group.
     *
     * <p>Used to reconcile the ledger against the graph: under concurrency, the count of
     * entries must equal the number of deltas that actually committed, and a mismatch means
     * a balance changed without leaving evidence.
     *
     * @param groupId group to count entries for
     * @return the entry count
     */
    long countByGroupId(UUID groupId);
}

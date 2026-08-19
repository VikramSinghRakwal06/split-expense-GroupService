package com.splitexpense.group.repository;

import com.splitexpense.group.entity.AppliedOperation;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the idempotency receipts that make an apply safe to retry.
 *
 * <p><strong>Deliberately does not extend {@code JpaRepository}.</strong> A receipt says
 * "this expense has already been applied to this group", and deleting one would re-enable
 * the double charge it exists to prevent. There is no delete method here to call, and none
 * to reach by mistake later.
 *
 * <p>{@code saveAndFlush} rather than {@code save} is exposed on purpose: the INSERT must
 * hit the database while the caller is still inside its try block, so a concurrent apply
 * under the same reference surfaces as a {@code DataIntegrityViolationException} it can
 * recognise. With a deferred write the violation would escape at commit, outside any
 * handler, and a duplicate request would produce a 500 instead of the correct no-op.
 *
 * @see AppliedOperation
 */
@Repository
public interface AppliedOperationRepository
        extends org.springframework.data.repository.Repository<AppliedOperation, String> {

    /**
     * Records that a reference has been applied, flushing immediately.
     *
     * @param operation the receipt to write
     * @return the persisted instance
     * @throws org.springframework.dao.DataIntegrityViolationException if this reference has
     *                                                                already been applied
     */
    AppliedOperation saveAndFlush(AppliedOperation operation);

    /**
     * Whether a reference has already been applied.
     *
     * <p>A cheap rejection of the common replay case, and <em>not</em> the guarantee: two
     * simultaneous first-time applies can both pass it. The primary key is what actually
     * holds, which is why callers must still handle the violation from
     * {@link #saveAndFlush}.
     *
     * @param referenceId the expense or settlement id
     * @return whether a receipt exists
     */
    boolean existsByReferenceId(String referenceId);
}

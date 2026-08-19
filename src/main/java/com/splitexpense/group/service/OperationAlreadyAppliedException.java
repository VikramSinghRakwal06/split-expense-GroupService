package com.splitexpense.group.service;

/**
 * Internal signal that an apply's reference id has already been recorded, so this call must
 * change nothing.
 *
 * <p><strong>Never reaches a client.</strong> {@link GroupService} catches it and turns it
 * into an ordinary {@code 200} carrying {@code applied: false} — a duplicate apply is a
 * success, because the caller's intent is already satisfied.
 *
 * <p>It exists as an exception rather than a return value for a specific reason: the replay
 * is detected <em>inside</em> a transaction, either by the cheap existence check or by the
 * primary key refusing the insert. Once a flush has failed, the transaction is marked
 * rollback-only and returning normally from it would fail at commit with an
 * {@code UnexpectedRollbackException}. Throwing is what lets the transaction roll back
 * cleanly — which is correct in any case, since the work was done by whoever got there
 * first — and lets the outcome be decided outside it.
 *
 * <p>Package-private on purpose: nothing outside this package should be able to catch it or
 * depend on it.
 */
class OperationAlreadyAppliedException extends RuntimeException {

    private final String referenceId;

    OperationAlreadyAppliedException(String referenceId) {
        super("Operation %s has already been applied to this group".formatted(referenceId));
        this.referenceId = referenceId;
    }

    String getReferenceId() {
        return referenceId;
    }
}

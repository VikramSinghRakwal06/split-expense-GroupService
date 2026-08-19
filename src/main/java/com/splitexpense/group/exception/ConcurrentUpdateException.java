package com.splitexpense.group.exception;

/**
 * A movement could not be applied because the wallet stayed under contention for every
 * retry attempt. Mapped to 409.
 *
 * <p>Thrown from the {@code @Recover} method in {@code WalletService}, once the optimistic
 * locking retries are exhausted. Reaching it means several writers kept winning the race
 * ahead of this one — not that anything is corrupt. <strong>No money moved</strong>: each
 * failed attempt rolled its transaction back completely, so retrying the request is safe.
 *
 * <p>Distinct from a raw {@code OptimisticLockingFailureException} on purpose. That one
 * escaping to the handler means a lock conflict happened somewhere <em>without</em> the
 * protection of a retry loop, which is a different and more interesting signal.
 */
public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.splitexpense.group.exception;

/**
 * A balance delta was structurally impossible — most often a debt from someone to
 * themselves. Mapped to 400.
 *
 * <p>Distinct from {@link NotAGroupMemberException}, which is a 422: there the request was
 * well-formed and the group simply could not satisfy it. Here the request describes
 * something that is not a debt at all, and no change to the group would make it valid.
 */
public class InvalidBalanceDeltaException extends RuntimeException {

    public InvalidBalanceDeltaException(String message) {
        super(message);
    }
}

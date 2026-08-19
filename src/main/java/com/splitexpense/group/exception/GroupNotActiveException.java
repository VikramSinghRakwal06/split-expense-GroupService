package com.splitexpense.group.exception;

/**
 * Balances were asked to change in a group that has been archived. Mapped to 409.
 *
 * <p>A conflict rather than a permission problem: the caller is entitled to operate this
 * group, but its current state forbids new activity. Reopening it makes the same request
 * valid.
 */
public class GroupNotActiveException extends RuntimeException {

    public GroupNotActiveException(String message) {
        super(message);
    }
}

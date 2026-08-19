package com.splitexpense.group.exception;

/**
 * A user who is not in a group was named in an operation on it. Mapped to 422.
 *
 * <p>Raised when a balance delta names a debtor or creditor who does not hold a membership
 * row — an expense split with someone who has since left, most often. 422 rather than 400:
 * nothing about the request is malformed, and adding the person to the group makes the same
 * request valid.
 *
 * <p><strong>Not the exception for an unauthorised caller.</strong> A caller who is not a
 * member of a group they asked to read gets a {@link ResourceNotFoundException} and a 404,
 * because confirming that a group exists is itself more than a non-member should learn.
 * This one is about the <em>subjects</em> of an operation, not its author.
 */
public class NotAGroupMemberException extends RuntimeException {

    public NotAGroupMemberException(String message) {
        super(message);
    }
}

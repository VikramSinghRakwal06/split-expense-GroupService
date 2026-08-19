package com.splitexpense.group.exception;

/**
 * An ordinary member attempted something only a group's owner may do. Mapped to 403.
 *
 * <p>403 rather than 404 here, unlike the non-member case: the caller has already proved
 * they belong to the group, so its existence is not a secret from them. What they lack is
 * the authority for this particular operation, and saying so plainly is the honest answer.
 */
public class NotGroupOwnerException extends RuntimeException {

    public NotGroupOwnerException(String message) {
        super(message);
    }
}

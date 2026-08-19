package com.splitexpense.group.exception;

/**
 * A member was asked to be removed from a group while they still owe someone in it, or are
 * still owed by someone. Mapped to 409.
 *
 * <p>Removing them would delete the membership row that every balance in the group is
 * interpreted against, leaving debts pointing at somebody the group no longer contains —
 * and no endpoint through which those debts could ever be settled. The group would be
 * permanently unable to reach zero.
 *
 * <p>A conflict, not a validation failure: settling up first makes the same request valid.
 */
public class OutstandingBalanceException extends RuntimeException {

    public OutstandingBalanceException(String message) {
        super(message);
    }
}

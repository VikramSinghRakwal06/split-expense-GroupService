package com.payflow.wallet.exception;

/**
 * A resource the caller asked to create already exists. Mapped to 409.
 *
 * <p>Raised when a user who already holds a wallet asks for another one.
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}

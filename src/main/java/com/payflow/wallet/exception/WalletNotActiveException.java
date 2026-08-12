package com.payflow.wallet.exception;

/**
 * Money was asked to move on a wallet that is FROZEN or CLOSED. Mapped to 409.
 *
 * <p>A conflict rather than a permission problem: the caller is entitled to operate this
 * wallet, but its current state forbids the operation. Unfreezing it makes the same
 * request valid.
 */
public class WalletNotActiveException extends RuntimeException {

    public WalletNotActiveException(String message) {
        super(message);
    }
}

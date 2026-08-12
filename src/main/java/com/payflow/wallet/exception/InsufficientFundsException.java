package com.payflow.wallet.exception;

/**
 * A debit was rejected because the wallet does not hold enough money. Mapped to 422.
 *
 * <p>422 rather than 400: the request was well-formed and perfectly understood: it is the
 * current state of the account that makes it impossible. A client that retries the
 * identical request after a top-up will succeed, which is not true of a 400.
 *
 * <p>Carries no balance figure in its message. The caller of an internal debit is
 * payment-service, which is not necessarily entitled to know how much the user holds.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}

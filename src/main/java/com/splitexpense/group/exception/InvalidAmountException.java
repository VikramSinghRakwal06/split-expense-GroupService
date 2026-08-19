package com.splitexpense.group.exception;

/**
 * The amount supplied for a movement is not a usable sum of money. Mapped to 400.
 *
 * <p>Covers null, zero, negative, and any value carrying more decimal places than the
 * ledger can store. Bean validation rejects most of these at the API edge; this is the
 * service-layer guarantee, which also holds for callers that never went through a DTO.
 */
public class InvalidAmountException extends RuntimeException {

    public InvalidAmountException(String message) {
        super(message);
    }
}

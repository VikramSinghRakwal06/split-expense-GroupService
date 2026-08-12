package com.payflow.wallet.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Payload for the two internal endpoints, {@code POST /api/v1/wallets/{walletId}/credit}
 * and {@code POST /api/v1/wallets/{walletId}/debit}, called by payment-service.
 *
 * <p>One type serves both directions because the direction is already stated by the URL,
 * and because the two carry identical fields under identical money rules. Splitting them
 * into a {@code CreditRequest} and a {@code DebitRequest} would duplicate the constraints
 * below in two places — and the failure mode of that duplication is the two drifting
 * apart, so that one direction accepts an amount the other rejects. For money validation
 * that is not a risk worth taking for the sake of two type names.
 *
 * @param amount      money to move, always a positive magnitude
 * @param reference   caller's correlation id, required so every internal movement is
 *                    traceable back to the payment that caused it
 * @param description optional human-readable note stored on the ledger entry
 */
@Schema(description = "Internal request to move money into or out of a wallet")
public record MoneyMovementRequest(

        @Schema(example = "250.0000", description = "Positive amount, up to 4 decimal places")
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        // Mirrors NUMERIC(19,4): 15 integer digits + 4 fractional.
        @Digits(integer = 15, fraction = 4,
                message = "Amount must have at most 15 integer digits and 4 decimal places")
        BigDecimal amount,

        /*
         * Mandatory here, unlike on a user-initiated top-up. These endpoints move money on
         * another service's instruction, and an entry in the ledger that cannot be traced
         * back to the payment that caused it is not auditable.
         */
        @Schema(example = "pay-7c31f9e2", description = "Caller correlation id, required")
        @NotBlank(message = "Reference is required for internal movements")
        @Size(max = 100, message = "Reference must not exceed 100 characters")
        String reference,

        @Schema(example = "Order #4471 settlement")
        @Size(max = 255, message = "Description must not exceed 255 characters")
        String description) {
}

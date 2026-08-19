package com.splitexpense.group.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One debt, in one direction, forming part of an {@link ApplyBalancesRequest}.
 *
 * <p>Direction is carried by the two fields rather than by the sign of the amount, so a
 * delta reads as the sentence it represents: {@code debtorId} came to owe {@code creditorId}
 * this much. A signed amount would make {@code -450} and a swapped pair two ways of writing
 * the same thing, and the ledger would record whichever one the caller happened to choose.
 *
 * @param debtorId   who came to owe money
 * @param creditorId who came to be owed it
 * @param amount     positive magnitude of the debt
 */
@Schema(description = "A single debt from one group member to another")
public record BalanceDeltaRequest(

        @Schema(example = "b1e7d3c2-4a58-4f19-9d63-2e8a7c015f4b")
        @NotNull(message = "Debtor id is required")
        UUID debtorId,

        @Schema(example = "a0c4f912-7d36-4b81-93ea-5f28c6d40719")
        @NotNull(message = "Creditor id is required")
        UUID creditorId,

        /*
         * The floor is 0.0001 rather than 0.01, unlike the wallet endpoints this replaces.
         * A split of an odd amount across several people genuinely produces shares at the
         * fourth decimal place, and the residual allocation in expense-service hands out
         * single 0.0001 units to make the shares sum exactly to the expense. Rejecting them
         * here would make exact splitting impossible.
         */
        @Schema(example = "450.0000", description = "Positive amount, up to 4 decimal places")
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.0001", message = "Amount must be at least 0.0001")
        // Mirrors NUMERIC(19,4): 15 integer digits + 4 fractional.
        @Digits(integer = 15, fraction = 4,
                message = "Amount must have at most 15 integer digits and 4 decimal places")
        BigDecimal amount) {
}

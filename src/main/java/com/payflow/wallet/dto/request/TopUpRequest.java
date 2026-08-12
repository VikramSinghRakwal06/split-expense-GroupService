package com.payflow.wallet.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Payload for {@code POST /api/v1/wallets/me/topup}.
 *
 * <p>The wallet credited is always the caller's own, resolved from the JWT. There is
 * deliberately no wallet or user field here: accepting one would let a caller name someone
 * else's wallet.
 *
 * @param amount    money to add, in the wallet's currency
 * @param reference caller's correlation id for the top-up, stored on the ledger entry
 */
@Schema(description = "Request to add money to the authenticated user's wallet")
public record TopUpRequest(

        /*
         * Declared as BigDecimal, and Jackson binds the JSON number straight into one
         * without ever going through a double, so a value like 0.1 survives the wire
         * exactly as written. A double field here would corrupt the amount before any
         * validation or business rule ever saw it.
         */
        @Schema(example = "500.0000", description = "Positive amount, up to 4 decimal places")
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        // Mirrors NUMERIC(19,4): 15 integer digits + 4 fractional. A value the column
        // could not hold is rejected at the edge rather than truncated by the driver.
        @Digits(integer = 15, fraction = 4,
                message = "Amount must have at most 15 integer digits and 4 decimal places")
        BigDecimal amount,

        @Schema(example = "upi-txn-8f2c1a", description = "Optional caller correlation id")
        @Size(max = 100, message = "Reference must not exceed 100 characters")
        String reference) {
}

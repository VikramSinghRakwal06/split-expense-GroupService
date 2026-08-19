package com.splitexpense.group.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What one pair of members currently owe each other, stated in the direction the debt
 * actually runs.
 *
 * <p>The stored row is signed and keyed by a canonical pair ordering that exists purely so
 * the database can guarantee one row per pair — an implementation detail with no meaning to
 * a client. This DTO undoes it: {@link #amount} is always positive, and the two ids say who
 * owes whom. A settled pair is omitted from the response entirely rather than reported as
 * zero.
 *
 * @param debtorId   who owes
 * @param creditorId who is owed
 * @param amount     positive outstanding amount
 */
@Schema(description = "An outstanding debt between two group members")
public record PairBalanceResponse(
        UUID debtorId,
        UUID creditorId,
        BigDecimal amount) {
}

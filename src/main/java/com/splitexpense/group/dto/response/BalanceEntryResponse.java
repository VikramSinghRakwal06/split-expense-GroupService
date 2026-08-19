package com.splitexpense.group.dto.response;

import com.splitexpense.group.entity.BalanceEntryReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a group's activity feed: a debt that came into existence, and why.
 *
 * <p>{@code amount} serialises as a JSON number with its full scale preserved
 * ({@code 450.0000}, not {@code 450}), because Jackson writes a {@code BigDecimal} verbatim
 * rather than converting it to a double.
 *
 * @param id          entry identifier
 * @param groupId     the group this happened in
 * @param debtorId    who came to owe
 * @param creditorId  who came to be owed
 * @param amount      positive magnitude of the change
 * @param reason      what kind of event caused it
 * @param referenceId the expense or settlement in expense-service that caused it
 * @param description optional note, may be null
 * @param createdAt   when it was recorded
 */
@Schema(description = "One immutable record of a debt changing between two members")
public record BalanceEntryResponse(
        UUID id,
        UUID groupId,
        UUID debtorId,
        UUID creditorId,
        BigDecimal amount,
        BalanceEntryReason reason,
        String referenceId,
        String description,
        Instant createdAt) {
}

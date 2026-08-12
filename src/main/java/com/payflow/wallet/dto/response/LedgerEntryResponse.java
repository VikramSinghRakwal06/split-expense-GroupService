package com.payflow.wallet.dto.response;

import com.payflow.wallet.entity.LedgerEntryType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a wallet statement: the client-facing view of a {@code LedgerEntry}.
 *
 * <p>{@code amount} is always positive; read it together with {@code type} to know which
 * way the money went.
 *
 * @param id           entry identifier
 * @param walletId     wallet the entry belongs to
 * @param amount       magnitude of the movement, exact decimal and always positive
 * @param type         direction of the movement
 * @param balanceAfter the wallet's balance immediately after this entry was applied
 * @param referenceId  caller's correlation id, may be null
 * @param description  human-readable note, may be null
 * @param createdAt    when the movement was recorded
 */
@Schema(description = "A single immutable movement on a wallet's ledger")
public record LedgerEntryResponse(
        UUID id,
        UUID walletId,
        BigDecimal amount,
        LedgerEntryType type,
        BigDecimal balanceAfter,
        String referenceId,
        String description,
        Instant createdAt) {
}

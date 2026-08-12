package com.payflow.wallet.mapper;

import com.payflow.wallet.dto.response.LedgerEntryResponse;
import com.payflow.wallet.entity.LedgerEntry;
import org.springframework.stereotype.Component;

/**
 * Converts {@link LedgerEntry} entities into their client-facing representation.
 *
 * <p>Separate from {@code WalletMapper} because the two translate unrelated aggregates:
 * a statement page maps thousands of entries and never touches a wallet.
 */
@Component
public class LedgerEntryMapper {

    /**
     * @param entry entity to convert, never null
     * @return the safe-to-serialise view of that ledger entry
     */
    public LedgerEntryResponse toResponse(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getWalletId(),
                entry.getAmount(),
                entry.getType(),
                entry.getBalanceAfter(),
                entry.getReferenceId(),
                entry.getDescription(),
                entry.getCreatedAt());
    }
}

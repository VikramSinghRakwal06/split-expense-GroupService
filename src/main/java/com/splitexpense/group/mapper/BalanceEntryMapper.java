package com.splitexpense.group.mapper;

import com.splitexpense.group.dto.response.BalanceEntryResponse;
import com.splitexpense.group.entity.BalanceEntry;
import org.springframework.stereotype.Component;

/**
 * Converts {@link BalanceEntry} entities into their client-facing representation.
 *
 * <p>A near-flat copy, unlike {@link GroupBalanceMapper} — the ledger already stores debtor
 * and creditor in the direction the debt ran, so there is no canonical ordering to undo.
 */
@Component
public class BalanceEntryMapper {

    /**
     * @param entry entity to convert, never null
     * @return the safe-to-serialise view of that entry
     */
    public BalanceEntryResponse toResponse(BalanceEntry entry) {
        return new BalanceEntryResponse(
                entry.getId(),
                entry.getGroupId(),
                entry.getDebtorId(),
                entry.getCreditorId(),
                entry.getAmount(),
                entry.getReason(),
                entry.getReferenceId(),
                entry.getDescription(),
                entry.getCreatedAt());
    }
}

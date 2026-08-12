package com.payflow.wallet.mapper;

import com.payflow.wallet.dto.response.WalletResponse;
import com.payflow.wallet.entity.Wallet;
import org.springframework.stereotype.Component;

/**
 * Converts {@link Wallet} entities into their client-facing representation.
 *
 * <p>Kept as an explicit component rather than a mapping framework: there is one
 * translation, and writing it by hand makes it obvious that {@code version} is excluded
 * rather than relying on a generator to leave it out.
 *
 * <p>Mapping through a DTO — rather than serialising the entity — also means a response is
 * a detached snapshot. Returning the entity would let Jackson touch it after the
 * transaction closed, and would tie the JSON contract to the database schema so that a
 * column rename became a breaking API change.
 */
@Component
public class WalletMapper {

    /**
     * @param wallet entity to convert, never null
     * @return the safe-to-serialise view of that wallet
     */
    public WalletResponse toResponse(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getUserId(),
                wallet.getBalance(),
                wallet.getCurrency(),
                wallet.getStatus(),
                wallet.getCreatedAt(),
                wallet.getUpdatedAt());
    }
}

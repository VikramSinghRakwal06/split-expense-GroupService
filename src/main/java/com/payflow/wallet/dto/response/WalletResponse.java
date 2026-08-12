package com.payflow.wallet.dto.response;

import com.payflow.wallet.entity.WalletStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Client-facing view of a wallet. The only shape a {@code Wallet} is exposed in.
 *
 * <p>Carries no {@code version}: the optimistic-locking counter is an internal
 * persistence detail, and publishing it would invite a client to try to supply one.
 *
 * <p>{@code balance} serialises as a JSON number with its full scale preserved
 * ({@code 100.0000}, not {@code 100}), because Jackson writes a {@code BigDecimal}
 * verbatim rather than converting it to a double.
 *
 * @param id        wallet identifier
 * @param userId    account holder, as issued by auth-service
 * @param balance   current balance, exact decimal
 * @param currency  ISO-4217 code the balance is denominated in
 * @param status    whether the wallet may currently move money
 * @param createdAt when the wallet was opened
 * @param updatedAt when the balance or status last changed
 */
@Schema(description = "A user's wallet and its current balance")
public record WalletResponse(
        UUID id,
        UUID userId,
        BigDecimal balance,
        String currency,
        WalletStatus status,
        Instant createdAt,
        Instant updatedAt) {
}

package com.payflow.wallet.repository;

import com.payflow.wallet.entity.Wallet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link Wallet}.
 *
 * <p>Wallets are mutable, so the full {@code JpaRepository} surface is appropriate here —
 * unlike {@link LedgerEntryRepository}, which is deliberately narrowed.
 *
 * <p>Note what is <em>absent</em>: no pessimistic-lock query. Concurrent movements on one
 * wallet are resolved by the {@code @Version} column and a retry, not by {@code SELECT
 * ... FOR UPDATE}. See {@code WalletTransactionService} for why.
 */
@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /**
     * Resolves the wallet belonging to an account. Backed by {@code uq_wallets_user_id},
     * so this is an index lookup returning at most one row.
     *
     * @param userId account identifier taken from the caller's JWT
     * @return the wallet, or empty if the user has never created one
     */
    Optional<Wallet> findByUserId(UUID userId);

    /**
     * Cheap existence check used to reject a duplicate wallet before attempting the
     * insert. Note this is an optimisation, not the guarantee — two simultaneous requests
     * can both pass it, and {@code uq_wallets_user_id} is what actually prevents a user
     * ending up with two balances.
     *
     * @param userId account identifier
     * @return whether a wallet already exists for that account
     */
    boolean existsByUserId(UUID userId);
}

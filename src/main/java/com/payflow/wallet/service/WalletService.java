package com.payflow.wallet.service;

import com.payflow.wallet.dto.response.LedgerEntryResponse;
import com.payflow.wallet.dto.response.WalletResponse;
import com.payflow.wallet.entity.Wallet;
import com.payflow.wallet.exception.ResourceNotFoundException;
import com.payflow.wallet.mapper.LedgerEntryMapper;
import com.payflow.wallet.mapper.WalletMapper;
import com.payflow.wallet.repository.LedgerEntryRepository;
import com.payflow.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The wallet API as the controller sees it: one dependency, covering creation, reads and
 * both directions of money movement.
 *
 * <h2>Why this class delegates rather than doing the work</h2>
 *
 * <p>The behaviour that matters in this service — transactions, optimistic-lock retries,
 * cache invalidation — is all applied by Spring proxies, and a proxy only intercepts calls
 * arriving from <em>outside</em> the object. Anything this class called on itself would
 * silently skip every one of those annotations. So each concern lives in its own bean and
 * is reached from here through its proxy:
 *
 * <ul>
 *   <li>{@link WalletMovementService} — retry and cache eviction around money movement</li>
 *   <li>{@link WalletTransactionService} — the atomic balance-plus-ledger write</li>
 *   <li>{@link WalletQueryService} — cached balance reads</li>
 * </ul>
 *
 * <p>{@link #topUp} is the clearest illustration: it is a credit, and it must get the same
 * retry and eviction as any other credit. Had {@code credit} been a method on this class,
 * calling it from {@code topUp} would have quietly bypassed both.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletTransactionService walletTransactionService;
    private final WalletMovementService walletMovementService;
    private final WalletQueryService walletQueryService;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletMapper walletMapper;
    private final LedgerEntryMapper ledgerEntryMapper;

    public WalletService(
            WalletTransactionService walletTransactionService,
            WalletMovementService walletMovementService,
            WalletQueryService walletQueryService,
            WalletRepository walletRepository,
            LedgerEntryRepository ledgerEntryRepository,
            WalletMapper walletMapper,
            LedgerEntryMapper ledgerEntryMapper) {
        this.walletTransactionService = walletTransactionService;
        this.walletMovementService = walletMovementService;
        this.walletQueryService = walletQueryService;
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletMapper = walletMapper;
        this.ledgerEntryMapper = ledgerEntryMapper;
    }

    /**
     * Opens a wallet for the authenticated user.
     *
     * @param userId account identifier from the caller's JWT
     * @return the new wallet
     * @throws com.payflow.wallet.exception.DuplicateResourceException if one already exists
     */
    public WalletResponse createWallet(UUID userId) {
        Wallet wallet = walletTransactionService.createWallet(userId);
        log.info("Opened wallet {} for user {}", wallet.getId(), userId);
        return walletMapper.toResponse(wallet);
    }

    /**
     * Reads a user's own wallet, from cache when possible.
     *
     * <p>Resolves the user's wallet id first — an indexed lookup on {@code uq_wallets_user_id}
     * — and then reads the balance through the cached, wallet-id-keyed path. Keying the cache
     * by wallet id rather than user id is what lets {@code credit} and {@code debit} evict
     * it: those endpoints are given a wallet id and never learn who owns it.
     *
     * @param userId account identifier from the caller's JWT
     * @return the wallet and its current balance
     * @throws ResourceNotFoundException if the user has no wallet yet
     */
    public WalletResponse getWalletForUser(UUID userId) {
        return walletQueryService.getWalletById(requireWalletIdForUser(userId));
    }

    /**
     * Reads one page of a user's statement, newest entry first.
     *
     * <p>Resolves the wallet from the user id rather than accepting a wallet id, so a caller
     * can only ever page through their own history.
     *
     * <p>Not cached: a statement page is large, individual to the caller, and read far less
     * often than a balance, so it would mostly evict more useful entries.
     *
     * @param userId   account identifier from the caller's JWT
     * @param pageable page number, size and sort supplied by the controller
     * @return the requested page of ledger entries, already mapped to DTOs
     * @throws ResourceNotFoundException if the user has no wallet yet
     */
    @Transactional(readOnly = true)
    public Page<LedgerEntryResponse> getLedgerForUser(UUID userId, Pageable pageable) {
        return ledgerEntryRepository
                .findByWalletId(requireWalletIdForUser(userId), pageable)
                .map(ledgerEntryMapper::toResponse);
    }

    /**
     * Tops up the authenticated user's own wallet.
     *
     * <p>Resolves the wallet from the JWT's user id and then takes the ordinary credit path,
     * so a top-up gets the same locking, retry, ledger and cache-eviction guarantees as any
     * other movement.
     *
     * @param userId    account identifier from the caller's JWT
     * @param amount    positive sum to add
     * @param reference caller's correlation id, may be null
     * @return the wallet with its new balance
     * @throws ResourceNotFoundException if the user has no wallet yet
     */
    public WalletResponse topUp(UUID userId, BigDecimal amount, String reference) {
        // Read outside the retried movement on purpose: a wallet's id never changes, so
        // resolving it once is safe even if the credit below is attempted several times.
        UUID walletId = requireWalletIdForUser(userId);
        return walletMovementService.credit(walletId, amount, reference, "Wallet top-up");
    }

    /**
     * Credits a wallet. See {@link WalletMovementService#credit} for the concurrency
     * strategy that makes this safe under simultaneous writers.
     *
     * @param walletId    wallet to credit
     * @param amount      positive sum to add
     * @param reference   caller's correlation id
     * @param description human-readable note, may be null
     * @return the wallet with its new balance
     */
    public WalletResponse credit(
            UUID walletId, BigDecimal amount, String reference, String description) {
        return walletMovementService.credit(walletId, amount, reference, description);
    }

    /**
     * Debits a wallet. See {@link WalletMovementService#debit} for the concurrency strategy
     * and for why this path must never read a cached balance.
     *
     * @param walletId    wallet to debit
     * @param amount      positive sum to remove
     * @param reference   caller's correlation id
     * @param description human-readable note, may be null
     * @return the wallet with its new balance
     */
    public WalletResponse debit(
            UUID walletId, BigDecimal amount, String reference, String description) {
        return walletMovementService.debit(walletId, amount, reference, description);
    }

    /**
     * @throws ResourceNotFoundException if the user holds no wallet
     */
    private UUID requireWalletIdForUser(UUID userId) {
        return walletRepository.findByUserId(userId)
                .map(Wallet::getId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No wallet exists for this user. Create one first."));
    }
}

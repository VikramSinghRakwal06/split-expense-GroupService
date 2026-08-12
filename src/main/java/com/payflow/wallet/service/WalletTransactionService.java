package com.payflow.wallet.service;

import com.payflow.wallet.entity.LedgerEntry;
import com.payflow.wallet.entity.LedgerEntryType;
import com.payflow.wallet.entity.Wallet;
import com.payflow.wallet.exception.DuplicateResourceException;
import com.payflow.wallet.exception.InsufficientFundsException;
import com.payflow.wallet.exception.InvalidAmountException;
import com.payflow.wallet.exception.ResourceNotFoundException;
import com.payflow.wallet.exception.WalletNotActiveException;
import com.payflow.wallet.repository.LedgerEntryRepository;
import com.payflow.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of the wallet domain: every rule about when money may move, and
 * the atomic balance-plus-ledger write that moves it.
 *
 * <h2>Why this is a separate bean from {@link WalletService}</h2>
 *
 * <p>{@code WalletService} owns the {@code @Retryable} boundary; this class owns the
 * {@code @Transactional} boundary. They are two beans specifically so that the transaction
 * sits <strong>inside</strong> the retry, and the split is load-bearing rather than
 * stylistic — see the class Javadoc on {@code WalletService} for the full argument. In
 * short: Spring's annotations are applied by proxies, and a proxy only intercepts calls
 * arriving from outside the object. Putting both annotations on one class would mean the
 * retry re-invoked the method body without ever re-entering the transaction proxy, so
 * every attempt after the first would run in the failed, rollback-only transaction.
 *
 * <p>Consequently: <strong>nothing in this class may call another public method on
 * {@code this}</strong> expecting transactional semantics, and this class must never be
 * annotated {@code @Retryable}.
 *
 * <h2>Money rules enforced here</h2>
 *
 * <p>All of them, including amount validation. That keeps the domain testable with plain
 * Mockito against this one class, and means a caller that bypassed the DTO layer entirely
 * still cannot move a negative sum. The cost is opening a transaction to reject a bad
 * amount; that transaction does no work and rolls back immediately.
 */
@Service
public class WalletTransactionService {

    private static final Logger log = LoggerFactory.getLogger(WalletTransactionService.class);

    /**
     * Maximum decimal places a movement may carry, matching {@code NUMERIC(19,4)}. An
     * amount finer than the ledger can store would be silently rounded by the driver, and
     * silently losing fractions of a currency unit is how money goes missing.
     */
    private static final int MAX_SCALE = 4;

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public WalletTransactionService(
            WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * Opens a wallet for a user.
     *
     * @param userId account the wallet belongs to, taken from a verified JWT
     * @return the newly created wallet
     * @throws DuplicateResourceException if the user already holds a wallet
     */
    @Transactional
    public Wallet createWallet(UUID userId) {
        // Cheap rejection of the common case. Not the guarantee — see below.
        if (walletRepository.existsByUserId(userId)) {
            throw new DuplicateResourceException("A wallet already exists for this user");
        }

        Wallet wallet = Wallet.builder().userId(userId).build();

        try {
            // saveAndFlush, not save: the INSERT must hit the database inside this try
            // block. With a plain save the statement would be deferred to commit, the
            // unique-constraint violation would surface outside the method, and two
            // simultaneous first-time requests would produce a 500 instead of a 409.
            // The exists() check above races; uq_wallets_user_id is what actually holds.
            return walletRepository.saveAndFlush(wallet);
        } catch (DataIntegrityViolationException ex) {
            log.debug("Concurrent wallet creation for user {} lost the race", userId);
            throw new DuplicateResourceException("A wallet already exists for this user");
        }
    }

    /**
     * Adds money to a wallet and records it, atomically.
     *
     * <p>See {@link #applyMovement} for the concurrency strategy; this is the credit
     * direction of it.
     *
     * @param walletId    wallet to credit
     * @param amount      positive sum to add
     * @param reference   caller's correlation id, may be null
     * @param description human-readable note, may be null
     * @return the wallet with its new balance, detached once the transaction commits
     * @throws ResourceNotFoundException if no such wallet exists
     * @throws InvalidAmountException    if the amount is null, non-positive, or too precise
     * @throws WalletNotActiveException  if the wallet is FROZEN or CLOSED
     */
    @Transactional
    public Wallet credit(UUID walletId, BigDecimal amount, String reference, String description) {
        return applyMovement(walletId, amount, LedgerEntryType.CREDIT, reference, description);
    }

    /**
     * Removes money from a wallet and records it, atomically.
     *
     * <p>See {@link #applyMovement} for the concurrency strategy; this is the debit
     * direction of it, and additionally refuses to overdraw.
     *
     * @param walletId    wallet to debit
     * @param amount      positive sum to remove
     * @param reference   caller's correlation id, may be null
     * @param description human-readable note, may be null
     * @return the wallet with its new balance, detached once the transaction commits
     * @throws ResourceNotFoundException   if no such wallet exists
     * @throws InvalidAmountException      if the amount is null, non-positive, or too precise
     * @throws WalletNotActiveException    if the wallet is FROZEN or CLOSED
     * @throws InsufficientFundsException  if the balance is smaller than the amount
     */
    @Transactional
    public Wallet debit(UUID walletId, BigDecimal amount, String reference, String description) {
        return applyMovement(walletId, amount, LedgerEntryType.DEBIT, reference, description);
    }

    /**
     * The one place a balance ever changes.
     *
     * <h2>Atomicity</h2>
     *
     * <p>The balance update and the ledger insert happen in this single transaction, so
     * they commit together or not at all. A balance that moved without a matching entry
     * would be unexplainable money, and an entry without a matching balance change would
     * be a lie about the account — neither is reachable from here.
     *
     * <h2>Concurrency</h2>
     *
     * <p>The wallet is read with an ordinary {@code SELECT}, holding no database lock. The
     * protection against a lost update is the {@code @Version} column: Hibernate turns the
     * dirty-checked balance change into
     * {@code UPDATE wallets SET balance = ?, version = n+1 WHERE id = ? AND version = n},
     * so if another transaction committed between our read and our write, the row no
     * longer matches, zero rows update, and Spring raises an
     * {@code OptimisticLockingFailureException} at commit. Our arithmetic is thrown away
     * rather than overwriting theirs.
     *
     * <p>That exception surfaces <em>after</em> this method returns, when the transaction
     * commits. It is caught and retried one level up, in {@code WalletService}.
     *
     * <p><strong>No {@code save()} is called on the wallet.</strong> It is a managed
     * entity, so JPA dirty checking emits the UPDATE at flush time on its own. Calling
     * {@code save()} would be redundant, and calling {@code saveAndFlush()} would force
     * the write early and change when the lock conflict is detected.
     */
    private Wallet applyMovement(
            UUID walletId,
            BigDecimal amount,
            LedgerEntryType type,
            String reference,
            String description) {

        requireUsableAmount(amount);

        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));

        if (!wallet.isActive()) {
            throw new WalletNotActiveException(
                    "Wallet is " + wallet.getStatus() + " and cannot move money");
        }

        BigDecimal newBalance = switch (type) {
            case CREDIT -> wallet.getBalance().add(amount);
            case DEBIT -> {
                // compareTo, never equals: equals(BigDecimal) also compares scale, so
                // 100.00 would not equal 100.0000 and this test would misfire.
                if (wallet.getBalance().compareTo(amount) < 0) {
                    throw new InsufficientFundsException(
                            "Wallet has insufficient funds for this debit");
                }
                yield wallet.getBalance().subtract(amount);
            }
        };

        // Dirty checking does the rest; see the Javadoc above for why there is no save().
        wallet.setBalance(newBalance);

        ledgerEntryRepository.save(LedgerEntry.builder()
                .walletId(wallet.getId())
                .amount(amount)
                .type(type)
                .balanceAfter(newBalance)
                .referenceId(reference)
                .description(description)
                .build());

        return wallet;
    }

    /**
     * Rejects anything that is not a sum of money this ledger can faithfully record.
     *
     * @throws InvalidAmountException if null, zero, negative, or finer than 4 decimals
     */
    private void requireUsableAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidAmountException("Amount is required");
        }
        // compareTo against ZERO rather than equals or signum on a scaled value: this is
        // true for 0, 0.00 and 0.0000 alike.
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Amount must be positive");
        }
        if (amount.scale() > MAX_SCALE) {
            throw new InvalidAmountException(
                    "Amount must have at most " + MAX_SCALE + " decimal places");
        }
    }
}

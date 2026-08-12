package com.payflow.wallet.service;

import com.payflow.wallet.config.CacheConfig;
import com.payflow.wallet.dto.response.WalletResponse;
import com.payflow.wallet.exception.ConcurrentUpdateException;
import com.payflow.wallet.mapper.WalletMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * The retry and cache-invalidation boundary around every movement of money.
 *
 * <h2>Why the retry lives here and the transaction lives in another class</h2>
 *
 * <p>This is the single most important structural decision in the service, so it is worth
 * stating precisely.
 *
 * <p>{@code @Transactional}, {@code @Retryable} and {@code @CacheEvict} are all implemented
 * with proxies: Spring wraps the bean, and the annotation's behaviour is applied by the
 * wrapper as a call passes through it. A call that a bean makes to itself does not pass
 * through its own wrapper — it is a plain Java invocation on {@code this}. That single fact
 * dictates the layout:
 *
 * <pre>
 *   caller
 *     -> [retry + evict proxy]    WalletMovementService.credit(...)     &lt;- @Retryable
 *          -> [transaction proxy] WalletTransactionService.credit(...)  &lt;- @Transactional
 *               -> read wallet, mutate balance, insert ledger entry
 *          &lt;- COMMIT happens here, and so does the optimistic-lock failure
 *     &lt;- retry catches it and calls through the transaction proxy again
 * </pre>
 *
 * <p>The ordering matters because <strong>an optimistic-lock failure is only detected at
 * commit</strong>, when Hibernate flushes the versioned UPDATE and finds it matched zero
 * rows. By then the transaction is finished and marked rollback-only. A retry must
 * therefore begin a <em>brand new</em> transaction, with a fresh persistence context and a
 * fresh read of the wallet — the previous attempt's entity holds a stale version number and
 * re-using it would fail identically forever.
 *
 * <p>Had both annotations been placed on one class, the retry interceptor would sit outside
 * the transaction interceptor but re-invoke the <em>method body</em> directly. Attempt two
 * would run inside the already-doomed transaction, every subsequent attempt would fail on
 * the same stale state, and the {@code @Recover} method would then report a contention
 * failure that was really a self-inflicted wound. Two beans is what makes each attempt
 * genuinely independent.
 *
 * <p>This class is deliberately <strong>not</strong> annotated {@code @Transactional}.
 * Doing so would open an outer transaction spanning every attempt, defeating the whole
 * arrangement.
 *
 * <p>The same proxy rule is why this class exists separately from {@code WalletService}
 * rather than being folded into it: {@code WalletService.topUp} needs the retry and the
 * eviction too, and reaching them by calling its own {@code credit} method would have
 * skipped both.
 *
 * @see WalletTransactionService for the money rules and the atomic write
 */
@Service
public class WalletMovementService {

    private static final Logger log = LoggerFactory.getLogger(WalletMovementService.class);

    /**
     * Attempts per movement, including the first, bound from
     * {@code payflow.wallet.movement-max-attempts}.
     *
     * <h2>Why this is tunable rather than a constant</h2>
     *
     * <p>The budget a wallet needs is a property of how contended it is, and that is a
     * deployment fact rather than a code fact. Optimistic locking assumes conflicts are
     * <em>rare</em>: when {@code k} writers are in flight against one row, roughly one wins
     * each round, so a writer's chance of surviving {@code n} attempts is about
     * {@code 1 - (1 - 1/k)^n}. At {@code k = 10} that is only ~27% for {@code n = 3} — fine
     * for an ordinary user's wallet, which sees one writer at a time, and hopeless for a
     * merchant settlement wallet taking hundreds of concurrent payments.
     *
     * <p>The default is 3, which suits the uncontended case this service is overwhelmingly
     * used in. A deployment with genuinely hot wallets should raise it rather than accept
     * 409s. Note the ceiling is throughput, not correctness: a movement that exhausts its
     * budget moved no money at all.
     */
    private final int maxAttempts;

    private final WalletTransactionService walletTransactionService;
    private final WalletMapper walletMapper;

    public WalletMovementService(
            WalletTransactionService walletTransactionService,
            WalletMapper walletMapper,
            @Value("${payflow.wallet.movement-max-attempts:3}") int maxAttempts) {
        this.walletTransactionService = walletTransactionService;
        this.walletMapper = walletMapper;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Credits a wallet, retrying if it is being written concurrently, and invalidating its
     * cached balance afterwards.
     *
     * <h2>Concurrency strategy</h2>
     *
     * <p>Two people crediting one wallet at the same moment both read the same balance, and
     * without protection the second write would overwrite the first — the lost update
     * problem, which silently destroys money. This service prevents it optimistically: the
     * wallet row carries a {@code @Version} column, Hibernate makes every UPDATE conditional
     * on the version it read, and the loser of a race updates zero rows and is told so.
     *
     * <p>Optimistic rather than pessimistic ({@code SELECT ... FOR UPDATE}) because
     * conflicts on a single wallet are rare while lock contention is expensive: a
     * pessimistic lock serialises every movement on a wallet for the duration of a
     * transaction and can deadlock across two of them, whereas this costs nothing at all in
     * the overwhelmingly common uncontended case and only pays on an actual collision.
     *
     * <p>{@code @Retryable} then makes the collision invisible to the caller. Each attempt
     * re-enters {@link WalletTransactionService} through its transaction proxy, so it
     * re-reads the wallet at its now-current version and recomputes from there. The backoff
     * is randomised: without jitter every loser of a race wakes at the same instant and
     * collides again in lockstep, which measurably wastes most of the retry budget.
     *
     * <p>Only {@code OptimisticLockingFailureException} is retried. A business failure —
     * insufficient funds, a frozen wallet — is a settled answer, and retrying it would just
     * produce the same answer three times.
     *
     * @param walletId    wallet to credit
     * @param amount      positive sum to add
     * @param reference   caller's correlation id, may be null
     * @param description human-readable note, may be null
     * @return the wallet with its new balance
     */
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttemptsExpression = "${payflow.wallet.movement-max-attempts:3}",
            backoff = @Backoff(delay = 50, multiplier = 2, random = true),
            // Named explicitly because credit and debit share a signature, and an
            // unqualified @Recover would be ambiguous between them.
            recover = "recoverCredit")
    // beforeInvocation = false: evict only after the method has returned successfully.
    //
    // THE STALENESS WINDOW. The transaction commits inside the call, and the eviction runs
    // after the call returns, so there is a brief interval — commit ... return ... evict —
    // during which the database already holds the new balance while Redis still serves the
    // old one. A read landing in that window gets a value that was true moments ago. It is
    // sub-millisecond in the normal case, it cannot be closed by reordering (evicting first
    // would open a strictly worse window, below), and no reader is harmed by it: spending
    // decisions are never made from the cache. See #debit.
    //
    // The window is bounded, not merely small. If the eviction itself fails — Redis
    // unreachable at exactly that moment — the stale entry survives only until the 30s TTL
    // in CacheConfig expires it.
    //
    // Why not beforeInvocation = true? It would evict up front, then leave the whole
    // duration of the movement — including every optimistic-lock retry, which can be tens
    // of milliseconds — as a window in which any concurrent read repopulates the cache with
    // the still-uncommitted OLD balance. That entry would then sit there for a full TTL,
    // turning a sub-millisecond staleness window into a 30-second one. It would also evict
    // on failure, discarding a perfectly valid entry for a movement that changed nothing.
    @CacheEvict(cacheNames = CacheConfig.WALLET_BALANCE_CACHE, key = "#walletId",
            beforeInvocation = false)
    public WalletResponse credit(
            UUID walletId, BigDecimal amount, String reference, String description) {
        return walletMapper.toResponse(
                walletTransactionService.credit(walletId, amount, reference, description));
    }

    /**
     * Debits a wallet, retrying if it is being written concurrently, and invalidating its
     * cached balance afterwards.
     *
     * <h2>Concurrency strategy</h2>
     *
     * <p>Identical to {@link #credit}, and for a sharper reason. A lost update on a credit
     * loses money that was owed to the user; a lost update on a debit lets the same balance
     * be spent twice, which is how a wallet is drained for more than it holds. The balance
     * check and the write must therefore be decided against one consistent version of the
     * row, which is exactly what the {@code @Version} predicate guarantees: if anything
     * committed between the check and the write, this attempt is rejected and redone
     * against the new balance — so the funds check is re-evaluated, never carried over.
     *
     * <h2>Why a debit must never read the cache</h2>
     *
     * <p>The funds check reads the wallet through {@link WalletTransactionService}, which
     * goes straight to the database and has no cache annotation anywhere on it. That is not
     * an oversight, it is the point. A cached balance is by definition a value that was true
     * at some earlier moment, and authorising a withdrawal against it would let a wallet be
     * spent twice inside one TTL window: two debits could each read a stale 100.00, each
     * conclude the wallet can afford 100.00, and between them take 200.00 out of an account
     * holding half that. Worse, the {@code @Version} check could not save us, because the
     * arithmetic it protects would already have been decided from stale input. Reads may be
     * approximate; the number a spending decision is made from may not.
     *
     * @param walletId    wallet to debit
     * @param amount      positive sum to remove
     * @param reference   caller's correlation id, may be null
     * @param description human-readable note, may be null
     * @return the wallet with its new balance
     */
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttemptsExpression = "${payflow.wallet.movement-max-attempts:3}",
            backoff = @Backoff(delay = 50, multiplier = 2, random = true),
            recover = "recoverDebit")
    // Same eviction policy and the same staleness window as #credit; see the comment there.
    @CacheEvict(cacheNames = CacheConfig.WALLET_BALANCE_CACHE, key = "#walletId",
            beforeInvocation = false)
    public WalletResponse debit(
            UUID walletId, BigDecimal amount, String reference, String description) {
        return walletMapper.toResponse(
                walletTransactionService.debit(walletId, amount, reference, description));
    }

    /**
     * Invoked when a credit stops retrying — whether because the optimistic-lock conflicts
     * were exhausted, or because it failed for a reason that is not retryable at all.
     *
     * <p>The first parameter is {@code Throwable}, not
     * {@code OptimisticLockingFailureException}, and that width is essential rather than
     * lazy. spring-retry routes <strong>every</strong> exception that ends the retry loop
     * through the recovery handler, including ones excluded by {@code retryFor}. If no
     * {@code @Recover} method accepts the thrown type, the handler cannot bind one and
     * raises {@code ExhaustedRetryException: Cannot locate recovery method} instead — so a
     * narrow signature here would convert every insufficient-funds and wallet-not-found
     * failure into an opaque 500. {@link #failureFor} therefore sorts genuine contention
     * from ordinary business failures and rethrows the latter untouched.
     *
     * <p>The remaining parameters must mirror {@link #credit}, and the return type must
     * match, or spring-retry will not bind this method to it.
     *
     * @throws ConcurrentUpdateException if the retries were exhausted by contention
     * @throws RuntimeException          the original failure, for anything else
     */
    @Recover
    public WalletResponse recoverCredit(
            Throwable failure,
            UUID walletId,
            BigDecimal amount,
            String reference,
            String description) {
        throw failureFor("credit", walletId, failure);
    }

    /**
     * Invoked when a debit stops retrying. See {@link #recoverCredit}; the reasoning and the
     * {@code Throwable} signature are the same.
     *
     * @throws ConcurrentUpdateException if the retries were exhausted by contention
     * @throws RuntimeException          the original failure, for anything else
     */
    @Recover
    public WalletResponse recoverDebit(
            Throwable failure,
            UUID walletId,
            BigDecimal amount,
            String reference,
            String description) {
        throw failureFor("debit", walletId, failure);
    }

    /**
     * Decides what a caller should actually see once retrying has stopped.
     *
     * @return the exception to throw; contention becomes a {@link ConcurrentUpdateException},
     *         anything else is passed through unchanged so the global handler can map it to
     *         its proper status
     */
    private RuntimeException failureFor(String operation, UUID walletId, Throwable failure) {
        if (failure instanceof OptimisticLockingFailureException) {
            // Warn, not error: this is a load signal rather than a defect. Sustained
            // occurrences on one wallet are worth an alert.
            log.warn("Gave up on {} for wallet {} after {} optimistic-lock conflicts; "
                    + "no money moved", operation, walletId, maxAttempts);

            return new ConcurrentUpdateException(
                    "This wallet is being updated concurrently. No money moved; please retry.",
                    failure);
        }

        // A settled business answer — insufficient funds, frozen wallet, no such wallet.
        // It was never retried; it simply passed through the retry interceptor on its way
        // out, and must reach GlobalExceptionHandler as itself.
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }

        // Nothing on these paths declares a checked exception, so this is unreachable in
        // practice; it exists so the Throwable signature above has no silent hole.
        return new IllegalStateException(
                "Unexpected checked exception from a money movement", failure);
    }
}

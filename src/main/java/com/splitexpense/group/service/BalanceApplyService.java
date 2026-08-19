package com.splitexpense.group.service;

import com.splitexpense.group.config.CacheConfig;
import com.splitexpense.group.dto.request.ApplyBalancesRequest;
import com.splitexpense.group.exception.ConcurrentUpdateException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * The retry and cache-invalidation boundary around every change to a group's debt graph.
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
 *     -&gt; [retry + evict proxy]    BalanceApplyService.apply(...)          &lt;- @Retryable
 *          -&gt; [transaction proxy] GroupTransactionService.applyBalances() &lt;- @Transactional
 *               -&gt; reserve the receipt, move each pair, append each entry
 *          &lt;- COMMIT happens here, and so does the optimistic-lock failure
 *     &lt;- retry catches it and calls through the transaction proxy again
 * </pre>
 *
 * <p>The ordering matters because <strong>an optimistic-lock failure is only detected at
 * commit</strong>, when Hibernate flushes the versioned UPDATE and finds it matched zero
 * rows. By then the transaction is finished and marked rollback-only. A retry must therefore
 * begin a <em>brand new</em> transaction, with a fresh persistence context and a fresh read
 * of every balance row — the previous attempt's entities hold stale version numbers and
 * re-using them would fail identically forever.
 *
 * <p>Had both annotations been placed on one class, the retry interceptor would sit outside
 * the transaction interceptor but re-invoke the <em>method body</em> directly. Attempt two
 * would run inside the already-doomed transaction, every subsequent attempt would fail on
 * the same stale state, and the {@code @Recover} method would then report a contention
 * failure that was really a self-inflicted wound. Two beans is what makes each attempt
 * genuinely independent.
 *
 * <p>This class is deliberately <strong>not</strong> annotated {@code @Transactional}. Doing
 * so would open an outer transaction spanning every attempt, defeating the whole arrangement.
 *
 * @see GroupTransactionService for the domain rules and the atomic write
 */
@Service
public class BalanceApplyService {

    private static final Logger log = LoggerFactory.getLogger(BalanceApplyService.class);

    /**
     * Attempts per apply, including the first, bound from
     * {@code splitexpense.group.apply-max-attempts}.
     *
     * <h2>Why this is tunable rather than a constant</h2>
     *
     * <p>The budget an apply needs is a property of how contended a group is, and that is a
     * deployment fact rather than a code fact. Optimistic locking assumes conflicts are
     * <em>rare</em>: when {@code k} writers are in flight against one pair, roughly one wins
     * each round, so a writer's chance of surviving {@code n} attempts is about
     * {@code 1 - (1 - 1/k)^n}.
     *
     * <p>An apply is more exposed to this than a wallet movement was, because it touches
     * several rows and loses if it collides on <em>any</em> of them: a nine-way dinner split
     * takes eight pair rows at once. The default of 3 suits the ordinary case, where a group
     * sees one expense at a time; a deployment where several people habitually add expenses
     * to a busy group at the same moment should raise it rather than accept 409s. The ceiling
     * is throughput, not correctness — an apply that exhausts its budget changed nothing.
     */
    private final int maxAttempts;

    private final GroupTransactionService groupTransactionService;

    public BalanceApplyService(
            GroupTransactionService groupTransactionService,
            @Value("${splitexpense.group.apply-max-attempts:3}") int maxAttempts) {
        this.groupTransactionService = groupTransactionService;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Applies a set of debts to a group, retrying if its balances are being written
     * concurrently, and invalidating the group's cached balances afterwards.
     *
     * <h2>Concurrency strategy</h2>
     *
     * <p>Two expenses in one group touching the same pair both read the same amount, and
     * without protection the second write would overwrite the first — the lost update
     * problem, which here silently destroys a debt. The pair row carries a {@code @Version}
     * column, Hibernate makes every UPDATE conditional on the version it read, and the loser
     * of a race updates zero rows and is told so.
     *
     * <p>Optimistic rather than pessimistic ({@code SELECT ... FOR UPDATE}) because conflicts
     * within a single group are rare while lock contention is expensive: a pessimistic lock
     * would serialise every expense in a group for the duration of a transaction, whereas
     * this costs nothing in the overwhelmingly common uncontended case and only pays on an
     * actual collision.
     *
     * <p>{@code @Retryable} then makes the collision invisible to the caller. Each attempt
     * re-enters {@link GroupTransactionService} through its transaction proxy, so it re-reads
     * every balance at its now-current version and recomputes from there. The backoff is
     * randomised: without jitter every loser of a race wakes at the same instant and collides
     * again in lockstep, which measurably wastes most of the retry budget.
     *
     * <h2>Why a constraint violation is also retried</h2>
     *
     * <p>{@code DataIntegrityViolationException} joins the retryable list, which it did not
     * on the wallet paths, for a reason specific to this design. The first time two members
     * transact, their pair row does not exist yet and the apply inserts it; two concurrent
     * applies can both attempt that insert, and {@code uq_group_balances_pair} rejects one of
     * them. That loser has hit a transient race, not a broken request — its next attempt
     * finds the row the winner created and simply updates it. Retrying converges; failing
     * would reject a perfectly valid expense because somebody else added one at the same
     * moment.
     *
     * <p>A genuinely malformed write would exhaust the budget and surface through
     * {@link #recoverApply} unchanged, so nothing is swallowed by this.
     *
     * <p>{@link OperationAlreadyAppliedException} is <em>not</em> retryable: it is a settled
     * answer meaning the work is already done. It passes through the recovery handler
     * untouched and is caught by {@link GroupService}.
     *
     * @param groupId group to apply to
     * @param request the reference, reason and deltas
     */
    @Retryable(
            retryFor = {
                OptimisticLockingFailureException.class,
                DataIntegrityViolationException.class
            },
            maxAttemptsExpression = "${splitexpense.group.apply-max-attempts:3}",
            backoff = @Backoff(delay = 50, multiplier = 2, random = true),
            recover = "recoverApply")
    // beforeInvocation = false: evict only after the method has returned successfully.
    //
    // THE STALENESS WINDOW. The transaction commits inside the call, and the eviction runs
    // after the call returns, so there is a brief interval — commit ... return ... evict —
    // during which the database already holds the new balances while Redis still serves the
    // old ones. A read landing in that window sees a graph that was true moments ago. It is
    // sub-millisecond in the normal case, it cannot be closed by reordering (evicting first
    // would open a strictly worse window, below), and nothing is decided from the cached
    // copy: an apply validates membership and computes amounts from the database, never from
    // this cache.
    //
    // The window is bounded, not merely small. If the eviction itself fails — Redis
    // unreachable at exactly that moment — the stale entry survives only until the TTL in
    // CacheConfig expires it.
    //
    // Why not beforeInvocation = true? It would evict up front, then leave the whole duration
    // of the apply — including every optimistic-lock retry — as a window in which any
    // concurrent read repopulates the cache with the still-uncommitted OLD graph. That entry
    // would then sit there for a full TTL, turning a sub-millisecond staleness window into a
    // 30-second one. It would also evict on failure, discarding a perfectly valid entry for
    // an apply that changed nothing.
    @CacheEvict(cacheNames = CacheConfig.GROUP_BALANCES_CACHE, key = "#groupId",
            beforeInvocation = false)
    public void apply(UUID groupId, ApplyBalancesRequest request) {
        groupTransactionService.applyBalances(groupId, request);
    }

    /**
     * Invoked when an apply stops retrying — whether because the conflicts were exhausted,
     * or because it failed for a reason that is not retryable at all.
     *
     * <p>The first parameter is {@code Throwable}, not one of the retryable types, and that
     * width is essential rather than lazy. spring-retry routes <strong>every</strong>
     * exception that ends the retry loop through the recovery handler, including ones
     * excluded by {@code retryFor}. If no {@code @Recover} method accepts the thrown type,
     * the handler cannot bind one and raises
     * {@code ExhaustedRetryException: Cannot locate recovery method} instead — so a narrow
     * signature here would convert every not-a-member, archived-group and already-applied
     * outcome into an opaque 500. {@link #failureFor} therefore sorts genuine contention from
     * settled answers and rethrows the latter untouched.
     *
     * <p>The remaining parameters must mirror {@link #apply}, and the return type must match,
     * or spring-retry will not bind this method to it.
     *
     * @throws ConcurrentUpdateException if the retries were exhausted by contention
     * @throws RuntimeException          the original failure, for anything else
     */
    @Recover
    public void recoverApply(Throwable failure, UUID groupId, ApplyBalancesRequest request) {
        throw failureFor(groupId, failure);
    }

    /**
     * Decides what a caller should actually see once retrying has stopped.
     *
     * @return the exception {@link #recoverApply} should throw; contention becomes a
     *         {@link ConcurrentUpdateException}, anything else is passed through unchanged so
     *         the global handler — or {@link GroupService} — can deal with it properly
     */
    private RuntimeException failureFor(UUID groupId, Throwable failure) {
        if (failure instanceof OptimisticLockingFailureException
                || failure instanceof DataIntegrityViolationException) {
            // Warn, not error: this is a load signal rather than a defect. Sustained
            // occurrences on one group are worth an alert.
            log.warn("Gave up applying to group {} after {} conflicting attempts; "
                    + "no balances changed", groupId, maxAttempts);

            return new ConcurrentUpdateException(
                    "This group is being updated concurrently. Nothing changed; please retry.",
                    failure);
        }

        // A settled answer — archived group, unknown member, already applied. It was never
        // retried; it simply passed through the retry interceptor on its way out, and must
        // reach its handler as itself.
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }

        // Nothing on these paths declares a checked exception, so this is unreachable in
        // practice; it exists so the Throwable signature above has no silent hole.
        return new IllegalStateException("Unexpected checked exception from a balance apply", failure);
    }
}

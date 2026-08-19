package com.splitexpense.group.domain;

import java.util.Comparator;
import java.util.UUID;

/**
 * Two users in the canonical order the {@code group_balances} table stores them in.
 *
 * <h2>Why a canonical order exists at all</h2>
 *
 * <p>A debt is a relation between two people, and "Alice and Bob" is the same relation as
 * "Bob and Alice". If the table allowed both orderings it would allow two rows for one
 * relation, and those two rows could disagree — Alice owing Bob 50 in one and Bob owing
 * Alice 30 in the other, with no way to say which is true. Storing every pair in one fixed
 * order makes that state unrepresentable: {@code uq_group_balances_pair} then guarantees
 * exactly one row per pair per group.
 *
 * <h2>Why the comparison is written by hand</h2>
 *
 * <p><strong>{@link UUID#compareTo} must not be used here, and this is a correctness
 * requirement rather than a preference.</strong> PostgreSQL compares {@code uuid} values as
 * sixteen <em>unsigned</em> bytes. Java's {@code UUID.compareTo} compares the two halves as
 * <em>signed</em> longs, so any UUID whose most significant bit is set — half of all random
 * UUIDs — sorts as a large negative number. The two orderings therefore disagree for a
 * great many pairs.
 *
 * <p>That disagreement would be quiet and expensive. The service would pick one order, the
 * {@code ck_group_balances_canonical CHECK (user_low &lt; user_high)} constraint would judge
 * it by the other, and roughly half of all first-time pair inserts would be rejected by the
 * database with a constraint violation that looks like nothing the code did. Worse, had the
 * constraint not existed, the same pair could land in two rows depending only on which
 * user id happened to be passed first.
 *
 * <p>{@link Long#compareUnsigned} on the most significant bits, then the least, is exactly
 * a byte-wise unsigned comparison of the big-endian sixteen bytes — which is what
 * PostgreSQL does.
 *
 * @param low  the smaller of the two ids under PostgreSQL's {@code uuid} ordering
 * @param high the larger of the two
 */
public record UserPair(UUID low, UUID high) {

    /**
     * Orders two user ids the way PostgreSQL's {@code uuid} type does.
     *
     * <p>Exposed so repositories and sorts can use the same ordering the table is keyed by
     * — notably when deltas are sorted before being written, to keep concurrent
     * transactions taking rows in a consistent order.
     */
    public static final Comparator<UUID> POSTGRES_UUID_ORDER = (a, b) -> {
        int mostSignificant =
                Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return mostSignificant != 0
                ? mostSignificant
                : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    };

    /**
     * Builds the canonical pair for two distinct users.
     *
     * @param a one user
     * @param b the other
     * @return the pair, ordered so {@code low} is the smaller under
     *         {@link #POSTGRES_UUID_ORDER}
     * @throws IllegalArgumentException if either id is null, or if the two are equal —
     *                                  nobody owes themselves, and callers are expected to
     *                                  have rejected that with a domain exception well
     *                                  before reaching here
     */
    public static UserPair of(UUID a, UUID b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Both user ids are required to form a pair");
        }
        int order = POSTGRES_UUID_ORDER.compare(a, b);
        if (order == 0) {
            throw new IllegalArgumentException("A user cannot form a pair with themselves: " + a);
        }
        return order < 0 ? new UserPair(a, b) : new UserPair(b, a);
    }

    /**
     * Whether a debt from {@code debtor} to {@code creditor} adds to this pair's stored
     * amount or subtracts from it.
     *
     * <p>{@code group_balances.amount} means "what {@link #low} owes {@link #high}". So a
     * debt whose debtor is {@code low} increases it, and one whose debtor is {@code high}
     * decreases it — the same number, read from the other end.
     *
     * @param debtor the user who owes, which must be one of the two in this pair
     * @return {@code +1} if the debt adds to the stored amount, {@code -1} if it subtracts
     * @throws IllegalArgumentException if {@code debtor} is not part of this pair
     */
    public int signFor(UUID debtor) {
        if (low.equals(debtor)) {
            return 1;
        }
        if (high.equals(debtor)) {
            return -1;
        }
        throw new IllegalArgumentException(
                "User %s is not part of the pair (%s, %s)".formatted(debtor, low, high));
    }
}

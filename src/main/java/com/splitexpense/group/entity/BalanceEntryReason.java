package com.splitexpense.group.entity;

/**
 * Why a debt changed.
 *
 * <p>This service does not know what an expense <em>is</em> — expense-service owns that —
 * but it records which kind of event moved a balance, so the group's activity feed can be
 * rendered without a second service call.
 *
 * <p>Persisted as a string and mirrored by {@code ck_balance_entries_reason}.
 */
public enum BalanceEntryReason {

    /** Somebody paid for something on another member's behalf. */
    EXPENSE,

    /**
     * An expense was voided, and its deltas were applied again in the opposite direction.
     *
     * <p>A distinct reason rather than a negative {@link #EXPENSE}, because the ledger is
     * append-only: the correction is a new fact about the group, and the feed should say so.
     */
    EXPENSE_VOID,

    /** One member paid another in the real world, cancelling part of what they owed. */
    SETTLEMENT
}

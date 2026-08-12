package com.payflow.wallet.entity;

/**
 * Direction of a {@link LedgerEntry}.
 *
 * <p>The direction lives here rather than in the sign of {@code amount}, which is always
 * positive and constrained to be so by {@code ck_ledger_entries_amount_positive}. Keeping
 * magnitude and direction in separate columns means a {@code SUM(amount)} over the table
 * cannot silently net off in the wrong direction because one row was stored negative.
 *
 * <p>Persisted as its {@code name()} string, and mirrored by the
 * {@code ck_ledger_entries_type} check constraint.
 */
public enum LedgerEntryType {

    /** Money into the wallet: a top-up, a refund, an incoming transfer. */
    CREDIT,

    /** Money out of the wallet: a payment, an outgoing transfer, a fee. */
    DEBIT
}

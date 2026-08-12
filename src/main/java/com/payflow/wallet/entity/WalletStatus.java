package com.payflow.wallet.entity;

/**
 * Lifecycle state of a {@link Wallet}, and the gate on every money movement.
 *
 * <p>Persisted as its {@code name()} string, and mirrored by the {@code ck_wallets_status}
 * check constraint in {@code V1__create_wallets_and_ledger.sql}. Adding a constant here
 * therefore requires a migration that widens that constraint.
 */
public enum WalletStatus {

    /** Normal operation: the only state in which money may move, in either direction. */
    ACTIVE,

    /**
     * Temporarily suspended — a compliance hold, or a suspected-fraud freeze. The balance
     * is preserved and readable, but neither credits nor debits are accepted. Reversible.
     */
    FROZEN,

    /** Permanently shut. Terminal: a closed wallet is never reopened, only read. */
    CLOSED;

    /**
     * @return whether money may move into or out of a wallet in this state
     */
    public boolean permitsMovement() {
        return this == ACTIVE;
    }
}

-- PayFlow wallet-service: initial schema.
-- Owns wallet balances and the append-only ledger that explains every one of them.

CREATE TABLE wallets (
    id         UUID          NOT NULL,

    -- The account holder, as minted by auth-service. Deliberately NOT a foreign key:
    -- users live in a different service backed by a different database, so there is
    -- no table here to reference. Referential integrity across that boundary is the
    -- platform's problem, not the database's.
    user_id    UUID          NOT NULL,

    -- NUMERIC(19,4), never a float type. Binary floating point cannot represent 0.10
    -- exactly, so repeated arithmetic on it drifts; money must be exact decimal.
    -- 4 decimal places leaves room for sub-paisa interim values without rounding.
    balance    NUMERIC(19,4) NOT NULL DEFAULT 0,

    currency   VARCHAR(3)    NOT NULL DEFAULT 'INR',
    status     VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',

    -- Optimistic-locking counter. Hibernate stamps it into the WHERE clause of every
    -- UPDATE and bumps it on success, so two concurrent writers racing on the same row
    -- cannot both win: the loser's UPDATE matches zero rows and Spring turns that into
    -- an OptimisticLockingFailureException. NOT NULL DEFAULT 0 so rows inserted by
    -- anything other than Hibernate still take part.
    version    BIGINT        NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ   NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_wallets PRIMARY KEY (id),

    -- One wallet per user. This is the constraint that makes "create my wallet"
    -- idempotent under a race: the second concurrent request fails here rather than
    -- silently producing a user with two balances.
    CONSTRAINT uq_wallets_user_id UNIQUE (user_id),

    -- The last line of defence against an overdraft. The service checks the balance
    -- before debiting, but a bug, a manual UPDATE or a future code path that forgets
    -- to check is stopped here. A wallet must never go negative.
    CONSTRAINT ck_wallets_balance_non_negative CHECK (balance >= 0),

    CONSTRAINT ck_wallets_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

-- Named explicitly, though uq_wallets_user_id already indexes this column: every
-- /me request resolves a wallet by user_id, so the lookup path is stated outright.
CREATE INDEX idx_wallets_user_id ON wallets (user_id);

-- The ledger. APPEND-ONLY: rows are inserted when money moves and never updated or
-- deleted afterwards. balance_after records what the wallet held immediately after
-- this entry was applied, so a statement can be read back without replaying arithmetic,
-- and any disagreement between the ledger and wallets.balance is detectable.
CREATE TABLE ledger_entries (
    id            UUID          NOT NULL,
    wallet_id     UUID          NOT NULL,
    amount        NUMERIC(19,4) NOT NULL,
    type          VARCHAR(10)   NOT NULL,
    balance_after NUMERIC(19,4) NOT NULL,

    -- Caller's correlation id (a payment id, a top-up reference). Nullable because not
    -- every movement originates from an external system.
    reference_id  VARCHAR(100),
    description   VARCHAR(255),
    created_at    TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT ck_ledger_entries_type CHECK (type IN ('CREDIT', 'DEBIT')),

    -- Direction is carried by `type`, so the magnitude is always positive. A negative
    -- CREDIT would be a debit wearing a disguise and would corrupt any sum over the table.
    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_ledger_entries_balance_after_non_negative CHECK (balance_after >= 0),

    -- Same database, same service: here a foreign key is both possible and correct.
    -- No ON DELETE clause, because wallets are closed by status, never deleted; if a
    -- delete is ever attempted this constraint blocks it and the ledger survives.
    CONSTRAINT fk_ledger_entries_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id)
);

-- Serves the statement endpoint: "this wallet's entries, newest first", which is the
-- only way the ledger is ever read. The DESC matches the query's sort order, so
-- PostgreSQL can walk the index instead of sorting the result set.
CREATE INDEX idx_ledger_entries_wallet_created
    ON ledger_entries (wallet_id, created_at DESC);

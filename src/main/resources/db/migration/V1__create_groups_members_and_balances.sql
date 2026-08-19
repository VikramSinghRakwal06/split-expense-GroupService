-- SplitExpense group-service: initial schema.
-- Owns groups, who belongs to them, and the pairwise debt graph that says who owes whom.
--
-- This service holds no money and no wallets. What it holds is a set of DEBTS between
-- pairs of people, scoped to a group, plus the append-only record of every change to them.

-- `groups` is an unreserved keyword in PostgreSQL and is legal unquoted; the entity still
-- names it explicitly with @Table(name = "groups") so nothing depends on that fact.
CREATE TABLE groups (
    id          UUID          NOT NULL,

    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(500),

    -- Every expense in the group is denominated in this. Fixed at creation: re-denominating
    -- a group whose balances are already non-zero would silently restate what people owe.
    --
    -- VARCHAR(3) rather than CHAR(3): PostgreSQL reports a CHAR column as `bpchar`, which
    -- Hibernate's schema validation rejects against a String field, and bpchar's blank
    -- padding would also make a mistakenly-short code compare equal to a padded one.
    currency    VARCHAR(3)    NOT NULL DEFAULT 'INR',

    -- The user who created the group, as minted by auth-service. Deliberately NOT a foreign
    -- key: users live in a different service backed by a different database, so there is no
    -- table here to reference.
    created_by  UUID          NOT NULL,

    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',

    -- Optimistic-locking counter, stamped into the WHERE clause of every UPDATE by
    -- Hibernate. See the same column on group_balances, where it does the load-bearing work.
    version     BIGINT        NOT NULL DEFAULT 0,

    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_groups PRIMARY KEY (id),
    CONSTRAINT ck_groups_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE group_members (
    group_id  UUID        NOT NULL,
    user_id   UUID        NOT NULL,
    role      VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    joined_at TIMESTAMPTZ NOT NULL,

    -- The composite key is the membership rule: one row per person per group, so
    -- "add Bob twice" is refused by the database rather than by a check that can race.
    CONSTRAINT pk_group_members PRIMARY KEY (group_id, user_id),

    -- Same database, same service: here a foreign key is both possible and correct.
    -- No ON DELETE clause, because groups are archived by status, never deleted.
    CONSTRAINT fk_group_members_group FOREIGN KEY (group_id) REFERENCES groups (id),

    CONSTRAINT ck_group_members_role CHECK (role IN ('OWNER', 'MEMBER'))
);

-- "Which groups am I in" is the first query every screen in the product makes, and the
-- primary key above indexes (group_id, user_id) — the wrong way round to serve it.
CREATE INDEX idx_group_members_user ON group_members (user_id);

-- THE DEBT GRAPH.
--
-- One row per unordered pair of people per group, and `amount` is what user_low owes
-- user_high. A negative amount means the debt runs the other way. Storing the pair in a
-- canonical order — enforced below — is what makes "exactly one row per pair" a fact the
-- database guarantees rather than a convention the service tries to keep: two rows for
-- (A,B) and (B,A) could disagree with each other, and nothing would detect it.
CREATE TABLE group_balances (
    id         UUID          NOT NULL,
    group_id   UUID          NOT NULL,

    user_low   UUID          NOT NULL,
    user_high  UUID          NOT NULL,

    -- NUMERIC(19,4), never a float type. Binary floating point cannot represent 0.10
    -- exactly, so repeated arithmetic on it drifts; a debt must be exact decimal. Signed,
    -- unlike a wallet balance: owing is the normal state of this column, in both directions.
    amount     NUMERIC(19,4) NOT NULL DEFAULT 0,

    -- Optimistic-locking counter. Two expenses in the same group touching the same pair
    -- race exactly as two movements on one wallet would: both read the same amount, and
    -- without this the second write silently discards the first. Hibernate makes every
    -- UPDATE conditional on the version it read, so the loser matches zero rows and is
    -- told, rather than overwriting a debt it never observed.
    version    BIGINT        NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ   NOT NULL,
    updated_at TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_group_balances PRIMARY KEY (id),
    CONSTRAINT fk_group_balances_group FOREIGN KEY (group_id) REFERENCES groups (id),

    -- The constraint that makes find-or-create idempotent under a race: two concurrent
    -- applies touching a pair for the first time both attempt the INSERT, and exactly one
    -- commits.
    CONSTRAINT uq_group_balances_pair UNIQUE (group_id, user_low, user_high),

    -- Canonical ordering. Note this is PostgreSQL's uuid ordering, which compares the 16
    -- bytes unsigned; Java's UUID.compareTo compares two SIGNED longs and disagrees
    -- whenever the top bit differs. UserPair in the service does the comparison the way
    -- PostgreSQL does it, and this constraint is what would catch it if that ever drifted.
    CONSTRAINT ck_group_balances_canonical CHECK (user_low < user_high)
);

-- Serves "show me this group's balances", the only way the graph is ever read.
CREATE INDEX idx_group_balances_group ON group_balances (group_id);

-- THE LEDGER. APPEND-ONLY: a row is inserted when a debt changes and is never updated or
-- deleted afterwards. A mistake is corrected by appending the opposite entry — which is
-- exactly what voiding an expense does — never by editing history. Any disagreement
-- between the sum of these entries and group_balances.amount is therefore detectable.
CREATE TABLE balance_entries (
    id           UUID          NOT NULL,
    group_id     UUID          NOT NULL,

    -- Direction is carried by the two columns, so the magnitude is always positive. These
    -- are stored as the caller stated them, NOT in canonical order: the ledger's job is to
    -- read back as what happened ("Bob owes Alice 450"), not as how it is indexed.
    debtor_id    UUID          NOT NULL,
    creditor_id  UUID          NOT NULL,
    amount       NUMERIC(19,4) NOT NULL,

    reason       VARCHAR(30)   NOT NULL,

    -- The expense or settlement that caused this, in expense-service. Mandatory: an entry
    -- that cannot be traced back to the thing that caused it is not auditable.
    reference_id VARCHAR(100)  NOT NULL,

    description  VARCHAR(255),
    created_at   TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_balance_entries PRIMARY KEY (id),
    CONSTRAINT fk_balance_entries_group FOREIGN KEY (group_id) REFERENCES groups (id),

    -- A negative debt would be a credit wearing a disguise and would corrupt any sum over
    -- the table.
    CONSTRAINT ck_balance_entries_amount_positive CHECK (amount > 0),

    -- Nobody owes themselves. Such an entry would consume a row and change nothing.
    CONSTRAINT ck_balance_entries_distinct CHECK (debtor_id <> creditor_id),

    -- Keeps the column honest against the Java enum. Adding a reason without a migration
    -- fails here at write time rather than storing a value nothing can read back.
    CONSTRAINT ck_balance_entries_reason
        CHECK (reason IN ('EXPENSE', 'EXPENSE_VOID', 'SETTLEMENT'))
);

-- Serves the group's activity feed: "this group's entries, newest first". The DESC matches
-- the query's sort order, so PostgreSQL walks the index instead of sorting the result set.
CREATE INDEX idx_balance_entries_group_created
    ON balance_entries (group_id, created_at DESC);

-- THE ARBITER OF IDEMPOTENCY for POST /groups/{id}/balances:apply.
--
-- expense-service applies an expense's deltas with one call, and may retry that call
-- without ever having learned whether the first attempt landed — a timeout tells you
-- nothing about what the server did. This table is what makes the retry a no-op instead of
-- a second charge: the reference id is the primary key, and the row is inserted in the SAME
-- transaction as the balance mutations it describes, so the two cannot disagree. Either the
-- deltas and their receipt both committed, or neither did.
--
-- This is the single reason the expense saga needs no compensation step, unlike the
-- transfer saga it replaces.
CREATE TABLE applied_operations (
    reference_id VARCHAR(100) NOT NULL,
    group_id     UUID         NOT NULL,
    applied_at   TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_applied_operations PRIMARY KEY (reference_id),
    CONSTRAINT fk_applied_operations_group FOREIGN KEY (group_id) REFERENCES groups (id)
);

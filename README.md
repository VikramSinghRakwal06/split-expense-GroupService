# PayFlow wallet-service

Owns wallet balances and the immutable money ledger for the PayFlow platform.

Every balance in this service is backed by an append-only ledger that explains it, and
every change to a balance is written in the same transaction as the entry recording it.
Concurrent movements on one wallet are made safe by optimistic locking with retry — see
[The lost update problem](#the-lost-update-problem) for what that means and why it matters.

This service **validates** JWTs issued by `auth-service` using the shared signing secret.
It never mints a token, and it holds no user table.

---

## Contents

- [Running it](#running-it)
- [Environment variables](#environment-variables)
- [API](#api)
- [The lost update problem](#the-lost-update-problem)
- [Design notes](#design-notes)
- [Tests](#tests)

---

## Running it

Requires Java 21, PostgreSQL and Redis. Flyway creates the schema on first start.

```bash
# Dependencies
docker run -d --name payflow-pg -p 5432:5432 \
  -e POSTGRES_USER=payflow -e POSTGRES_PASSWORD=payflow -e POSTGRES_DB=payflow_wallet \
  postgres:16-alpine
docker run -d --name payflow-redis -p 6379:6379 redis:7-alpine

# The service, on the dev profile
./mvnw spring-boot:run
```

It listens on **8082**. Swagger UI is at <http://localhost:8082/swagger-ui.html>.

```bash
# Container build
docker build -t payflow/wallet-service .
docker run -p 8082:8082 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/payflow_wallet \
  -e DB_USERNAME=payflow -e DB_PASSWORD=payflow \
  -e REDIS_HOST=host.docker.internal -e REDIS_PASSWORD= \
  -e JWT_SECRET="$JWT_SECRET" \
  payflow/wallet-service
```

---

## Environment variables

Everything is read from the environment, with development fallbacks in
`application.yml`. The `prod` profile removes the fallbacks for every secret, so a
production start-up fails fast rather than running on a value from source control.

| Variable | Default (dev) | Required in prod | Purpose |
|---|---|---|---|
| `SERVER_PORT` | `8082` | no | HTTP port |
| `SPRING_PROFILES_ACTIVE` | `dev` | — | `dev` or `prod` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/payflow_wallet` | **yes** | JDBC URL |
| `DB_USERNAME` | `payflow` | **yes** | Database user |
| `DB_PASSWORD` | `payflow` | **yes** | Database password |
| `DB_POOL_MAX` | `10` (prod `20`) | no | Hikari maximum pool size |
| `DB_POOL_MIN` | `2` (prod `5`) | no | Hikari minimum idle |
| `REDIS_HOST` | `localhost` | **yes** | Redis host |
| `REDIS_PORT` | `6379` | no | Redis port |
| `REDIS_PASSWORD` | *(empty)* | **yes** | Redis password |
| `JWT_SECRET` | `dev-secret-change-me-…` | **yes** | HMAC-SHA key, ≥32 bytes. **Must match `auth-service`.** |
| `JWT_ISSUER` | `payflow-auth-service` | no | Required value of the `iss` claim. **Must match `auth-service`.** |
| `CACHE_BALANCE_TTL` | `30s` | no | Balance cache TTL |
| `WALLET_MOVEMENT_MAX_ATTEMPTS` | `3` | no | Retry budget per movement — see [Tuning the retry budget](#tuning-the-retry-budget) |
| `SWAGGER_UI_ENABLED` | `true` (prod `false`) | no | Serve the interactive UI |
| `LOG_LEVEL` | `DEBUG` (prod `INFO`) | no | Level for `com.payflow.wallet` |

> **`JWT_SECRET` and `JWT_ISSUER` must be identical to `auth-service`'s.** The secret is
> obvious — HMAC verification fails otherwise — but the issuer is checked on every parse
> too, so a token correctly signed by a *different* environment sharing a key is still
> rejected.

---

## API

Base path `/api/v1/wallets`. Every endpoint requires
`Authorization: Bearer <access token>`; only `/actuator/health` and the OpenAPI paths are
public. Get a token from `auth-service`:

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@payflow.io","password":"correct-horse-9"}' | jq -r .accessToken)
```

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/wallets` | user | Create the caller's wallet — `409` if they already have one |
| `GET` | `/api/v1/wallets/me` | user | The caller's wallet and balance |
| `POST` | `/api/v1/wallets/me/topup` | user | Add money to the caller's own wallet |
| `GET` | `/api/v1/wallets/me/ledger` | user | Paginated statement, newest first, 20 per page |
| `POST` | `/api/v1/wallets/{walletId}/credit` | **`ROLE_ADMIN`** | INTERNAL — credit any wallet |
| `POST` | `/api/v1/wallets/{walletId}/debit` | **`ROLE_ADMIN`** | INTERNAL — debit any wallet |

The `/me` endpoints take the account identifier **only** from the verified JWT. None of
them accepts a user or wallet id anywhere in the request, so there is no parameter through
which one user could reach another user's wallet.

### Create a wallet

```bash
curl -X POST http://localhost:8082/api/v1/wallets \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "id": "d8f17020-92be-4080-808c-4c3e99a55033",
  "userId": "ab1d27c4-c423-401a-991e-7dcd760fc500",
  "balance": 0.0000,
  "currency": "INR",
  "status": "ACTIVE",
  "createdAt": "2026-08-12T08:34:25.798722Z",
  "updatedAt": "2026-08-12T08:34:25.798781Z"
}
```

### Read the balance

```bash
curl http://localhost:8082/api/v1/wallets/me \
  -H "Authorization: Bearer $TOKEN"
```

### Top up

```bash
curl -X POST http://localhost:8082/api/v1/wallets/me/topup \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"amount": 500.25, "reference": "upi-txn-8f2c1a"}'
```

```json
{ "id": "d8f1…", "balance": 500.2500, "currency": "INR", "status": "ACTIVE", "…": "…" }
```

### Statement

```bash
curl "http://localhost:8082/api/v1/wallets/me/ledger?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "content": [
    {
      "id": "64c681ed-dd71-44cd-8588-d5aca0b93103",
      "walletId": "d8f17020-92be-4080-808c-4c3e99a55033",
      "amount": 100.25,
      "type": "DEBIT",
      "balanceAfter": 400.0000,
      "referenceId": "pay-1",
      "description": "Order 1",
      "createdAt": "2026-08-12T08:41:28.575420Z"
    }
  ],
  "totalElements": 1,
  "number": 0,
  "size": 20
}
```

`amount` is always positive; read it together with `type` to know which way the money went.

### Internal credit / debit

Called by `payment-service` with an admin token. `reference` is **mandatory** here: a
ledger entry written on another service's instruction that cannot be traced back to the
payment which caused it is not auditable.

```bash
curl -X POST http://localhost:8082/api/v1/wallets/$WALLET_ID/debit \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"amount": 100.25, "reference": "pay-7c31f9e2", "description": "Order #4471"}'
```

### Errors

Every failure returns the same shape, from the controller advice and from the security
filter chain alike:

```json
{
  "timestamp": "2026-08-12T08:41:28.485441367Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Wallet has insufficient funds for this debit",
  "path": "/api/v1/wallets/d8801d04-.../debit"
}
```

| Status | When |
|---|---|
| `400` | Validation failure (per-field detail in `validationErrors`), malformed body, malformed UUID |
| `401` | Missing, expired, mis-signed, wrong-issuer, or non-access token |
| `403` | Authenticated, but not permitted — e.g. a user token on an internal endpoint |
| `404` | No such wallet, or the caller has no wallet yet |
| `409` | Wallet already exists · wallet is `FROZEN`/`CLOSED` · sustained write contention |
| `422` | Insufficient funds — the request was valid, the balance was not enough |
| `500` | Unexpected. Logged in full; the response never carries a stack trace |

A `409` from contention is always safe to retry: it means **no money moved**.

---

## The lost update problem

Two payments leave one wallet at the same instant. Both requests read the balance, both
compute a new one, both write it back:

```
balance = 100

  thread A                          thread B
  ────────                          ────────
  read balance            -> 100
                                    read balance            -> 100
  compute 100 - 30        -> 70
                                    compute 100 - 50        -> 50
  UPDATE balance = 70
                                    UPDATE balance = 50     <-- overwrites A

final balance = 50, but 80 was spent
```

Thirty rupees have vanished. Nothing errored, nothing was logged, and the ledger now
disagrees with the balance. This is not an exotic race — it is the default behaviour of
read-modify-write under concurrency, and on a debit path it is also how the *same* balance
gets spent twice.

### How optimistic locking solves it

The `wallets` table carries a `version` column, mapped with JPA's `@Version`. Hibernate
adds it to the `WHERE` clause of every update and increments it on success:

```sql
UPDATE wallets SET balance = ?, version = 6 WHERE id = ? AND version = 5
```

Now replay the race. Both threads read the row at `version = 5`. Thread A commits first
and the row becomes `version = 6`. Thread B's update then matches **zero rows**, because
no row with `version = 5` exists any more. Hibernate notices the row count is zero and
raises an `OptimisticLockingFailureException` instead of overwriting A's work. B's
arithmetic — computed from a balance that is now out of date — is discarded rather than
saved.

Nothing is lost, because B does not simply fail. `WalletMovementService` wraps each
movement in `@Retryable`, so B starts a **brand new transaction**, re-reads the wallet at
`version = 6`, recomputes `70 - 50 = 20` and commits. The final balance is 20, both ledger
entries exist, and the caller never learns a collision happened.

This is why the retry and the transaction live in **separate beans**. An optimistic-lock
failure is only detected at commit, by which point the transaction is finished and marked
rollback-only. A retry must therefore begin a genuinely new transaction with a fresh
persistence context — re-running the method body inside the failed transaction would fail
identically, forever. Spring applies both annotations with proxies, and a proxy only
intercepts calls arriving from *outside* the object, so the two concerns must sit in
different beans for the nesting to be real:

```
  [retry proxy]        WalletMovementService.credit(...)      @Retryable
    [tx proxy]         WalletTransactionService.credit(...)   @Transactional
      read wallet, change balance, insert ledger entry
    COMMIT — and any optimistic-lock failure surfaces here
  retry catches it and calls through the transaction proxy again
```

### Why not pessimistic locking?

`SELECT … FOR UPDATE` would also be correct, and it never rejects a writer. But it
serialises every movement on a wallet for the whole transaction and can deadlock across
two of them, and it pays that cost on every single request. Conflicts on a normal user's
wallet are vanishingly rare — most wallets have exactly one writer, their owner — so
optimistic locking costs nothing in the common case and only pays on an actual collision.

### Tuning the retry budget

The trade-off is that optimistic locking degrades under *sustained* contention. With `k`
writers in flight against one row, roughly one wins each round, so a writer's chance of
surviving `n` attempts is about `1 − (1 − 1/k)ⁿ`. Measured on this schema with 100
concurrent credits against a single wallet:

| `WALLET_MOVEMENT_MAX_ATTEMPTS` | Outcome |
|---|---|
| `3` (default) | ~45 of 100 succeed; the rest get a `409` |
| `20` | all 100 succeed |
| `40` | all 100 succeed, with margin (used by the concurrency test) |

**Correctness never depends on this setting.** Even at 3, the balance, the ledger and the
version agreed exactly in every run — 42 credits, 42 entries, `version = 42`. No money is
ever lost or duplicated; the budget only decides how much contention is absorbed silently
rather than surfaced as a retryable `409`.

The default of 3 suits ordinary wallets, which see one writer at a time. A deployment with
genuinely hot wallets — a merchant settlement account taking hundreds of concurrent
payments — should raise it, or move that workload to pessimistic locking.

---

## Design notes

**Money is `BigDecimal`, everywhere.** Never `double` or `float`, including in DTOs and
tests. Values are built from `String` literals, because `new BigDecimal(0.1)` is not `0.1`.
Comparisons use `compareTo`, never `equals`, because `equals` also compares scale and
`100.00` would not equal `100.0000`. Columns are `NUMERIC(19,4)`, and an amount with more
than four decimal places is rejected rather than silently rounded.

**The ledger is append-only, structurally.** `LedgerEntry` exposes no setters, every column
is mapped `updatable = false`, and `LedgerEntryRepository` deliberately does not extend
`JpaRepository` — it extends the bare `Repository` marker and declares exactly three
methods, so no `delete` exists to call. Mistakes are corrected by appending a compensating
entry, never by editing history.

**Flyway owns the schema**; Hibernate runs with `ddl-auto: validate` and fails at start-up
if the mappings and the migrated tables disagree.

**`user_id` is not a foreign key.** Users live in `auth-service` behind a different
database, so there is nothing to reference. `ledger_entries.wallet_id` *is* a foreign key,
because that table is in this database.

**The debit path never reads the cache.** Balances are cached in Redis for 30 seconds and
evicted whenever money moves, but a spending decision is always made against the
authoritative row inside the transaction. Authorising a withdrawal from a cached balance
would let a wallet be spent twice inside one TTL window, and the `@Version` check could not
save it — the arithmetic it protects would already have been decided from stale input.

**Redis is a performance dependency, not an availability one.** Cache failures are logged
and swallowed: a failed read falls through to PostgreSQL, and a failed eviction leaves an
entry the TTL will expire anyway. Without that, an unreachable Redis would fail money
movement, and — since eviction happens after commit — would report an error for a payment
that had in fact succeeded.

---

## Tests

```bash
./mvnw test
```

The integration tests need a Docker daemon for Testcontainers.

| Test | What it covers |
|---|---|
| `WalletTransactionServiceTest` | Money rules with mocked repositories: credit, debit, insufficient funds, inactive wallet, non-positive and over-precise amounts, duplicate wallet |
| `WalletControllerTest` | `@WebMvcTest` slice: routing, validation, status codes, and that `/me` ignores a smuggled `userId` parameter |
| `WalletConcurrencyIntegrationTest` | 100 concurrent credits of `1.00` against a real PostgreSQL — asserts the balance is exactly `100.0000`, that exactly 100 ledger entries exist, and that `version` advanced exactly 100 times |
| `WalletCacheIntegrationTest` | Real Redis: population, eviction on movement, key shape and TTL, that a cached balance survives JSON as a `BigDecimal` rather than a `Double`, and that a debit ignores a stale cache |

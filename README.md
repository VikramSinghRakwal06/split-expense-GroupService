# SplitExpense group-service

Owns groups, group membership, and the pairwise debt graph for the SplitExpense platform.

This service holds no money. What it holds is a set of **debts** between pairs of people,
scoped to a group — the "who owes whom" graph a Splitwise-style app is built around. Every
change to a debt is written in the same transaction as the append-only ledger entry that
explains it, so the graph is always reconstructable from its own history. Concurrent changes
to one pair are made safe by optimistic locking with retry, and the internal endpoint that
expense-service calls to apply a set of debts is idempotent by reference id — a retry after a
timeout is guaranteed to change nothing the second time. See
[Design notes](#design-notes) for how each of those guarantees actually holds.

This service **validates** JWTs issued by `auth-service` using the shared signing secret. It
never mints a token, and it holds no user table of its own.

---

## Contents

- [Running it](#running-it)
- [Environment variables](#environment-variables)
- [API](#api)
- [Design notes](#design-notes)
- [Tests](#tests)

---

## Running it

Requires Java 21, PostgreSQL and Redis. Flyway creates the schema on first start.

```bash
# Dependencies
docker run -d --name splitexpense-pg -p 5432:5432 \
  -e POSTGRES_USER=splitexpense -e POSTGRES_PASSWORD=splitexpense \
  -e POSTGRES_DB=splitexpense_group \
  postgres:16
docker run -d --name splitexpense-redis -p 6379:6379 redis:7-alpine

# The service, on the dev profile
./mvnw spring-boot:run
```

It listens on **8082**. Swagger UI is at <http://localhost:8082/swagger-ui.html>.

```bash
# Container build
docker build -t splitexpense/group-service .
docker run -p 8082:8082 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/splitexpense_group \
  -e DB_USERNAME=splitexpense -e DB_PASSWORD=splitexpense \
  -e REDIS_HOST=host.docker.internal -e REDIS_PASSWORD= \
  -e JWT_SECRET="$JWT_SECRET" \
  splitexpense/group-service
```

In the platform's `docker-compose.yml`, this service is one of several behind a single
`api-gateway`; `DB_URL`, `REDIS_HOST` and `JWT_SECRET` are wired there against the shared
Postgres, Redis and Kafka containers, and expense-service is given this service's internal
URL as `GROUP_SERVICE_URL`.

---

## Environment variables

Everything is read from the environment, with development fallbacks in `application.yml`.
The `prod` profile removes the fallbacks for every secret, so a production start-up fails
fast rather than running on a value from source control.

| Variable | Default (dev) | Required in prod | Purpose |
|---|---|---|---|
| `SERVER_PORT` | `8082` | no | HTTP port |
| `SPRING_PROFILES_ACTIVE` | `dev` | — | `dev` or `prod` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/splitexpense_group` | **yes** | JDBC URL |
| `DB_USERNAME` | `splitexpense` | **yes** | Database user |
| `DB_PASSWORD` | `splitexpense` | **yes** | Database password |
| `DB_POOL_MAX` | `10` (prod `20`) | no | Hikari maximum pool size |
| `DB_POOL_MIN` | `2` (prod `5`) | no | Hikari minimum idle |
| `REDIS_HOST` | `localhost` | **yes** | Redis host |
| `REDIS_PORT` | `6379` | no | Redis port |
| `REDIS_PASSWORD` | *(empty)* | **yes** | Redis password |
| `JWT_SECRET` | `dev-secret-change-me-…` | **yes** | HMAC-SHA key, ≥32 bytes. **Must match `auth-service`.** |
| `JWT_ISSUER` | `splitexpense-auth-service` | no | Required value of the `iss` claim. **Must match `auth-service`.** |
| `CACHE_BALANCES_TTL` | `30s` | no | Balances-cache TTL — see [Caching the debt graph](#caching-the-debt-graph) |
| `GROUP_APPLY_MAX_ATTEMPTS` | `3` | no | Retry budget per balance apply — see [Optimistic locking and the retry budget](#optimistic-locking-and-the-retry-budget) |
| `SWAGGER_UI_ENABLED` | `true` (prod `false`) | no | Serve the interactive UI |
| `LOG_LEVEL` | `DEBUG` (prod `INFO`) | no | Level for `com.splitexpense.group` |

> **`JWT_SECRET` and `JWT_ISSUER` must be identical to `auth-service`'s.** The secret is
> obvious — HMAC verification fails otherwise — but the issuer is checked on every parse too,
> so a token correctly signed by a *different* SplitExpense environment sharing a key is
> still rejected.

---

## API

Base path `/api/v1/groups`. Every endpoint requires `Authorization: Bearer <access token>`;
only `/actuator/health` and the OpenAPI paths are public. Get a token from `auth-service`:

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@splitexpense.io","password":"correct-horse-9"}' | jq -r .accessToken)
```

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/groups` | user | Create a group, owned by the caller |
| `GET` | `/api/v1/groups/me` | user | The caller's groups, newest first |
| `GET` | `/api/v1/groups/{groupId}` | member | One group and its members |
| `POST` | `/api/v1/groups/{groupId}/members` | owner | Add a user to the group |
| `DELETE` | `/api/v1/groups/{groupId}/members/{userId}` | member/owner | Remove a member — self, or (owner only) somebody else |
| `POST` | `/api/v1/groups/{groupId}:archive` | owner | Archive the group |
| `POST` | `/api/v1/groups/{groupId}:reactivate` | owner | Reactivate an archived group |
| `GET` | `/api/v1/groups/{groupId}/balances` | member | Outstanding debts and each member's net position |
| `GET` | `/api/v1/groups/{groupId}/entries` | member | Paginated activity feed, newest first, 20 per page |
| `POST` | `/api/v1/groups/{groupId}/balances:apply` | **`ROLE_ADMIN`** | INTERNAL — apply a set of debts, called by expense-service |

The `/me` endpoint takes the account identifier **only** from the verified JWT — no request
parameter names a user, so there is no way for one caller to list another's groups. Every
`/{groupId}` endpoint instead names a resource directly, so each one checks the caller's
membership before doing anything, and reports a non-member a **404, not a 403** — confirming
that a group id even exists is already more than an outsider should learn.

### Create a group

```bash
curl -X POST http://localhost:8082/api/v1/groups \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name": "Goa Trip 2026", "description": "Flights, hotel and everything in between", "currency": "INR"}'
```

```json
{
  "id": "5f1e2a3c-9b7d-4c6a-8e2f-1a9b3c5d7e0f",
  "name": "Goa Trip 2026",
  "description": "Flights, hotel and everything in between",
  "currency": "INR",
  "createdBy": "ab1d27c4-c423-401a-991e-7dcd760fc500",
  "status": "ACTIVE",
  "members": [
    {
      "userId": "ab1d27c4-c423-401a-991e-7dcd760fc500",
      "role": "OWNER",
      "joinedAt": "2026-08-20T08:34:25.798722Z"
    }
  ],
  "createdAt": "2026-08-20T08:34:25.798722Z",
  "updatedAt": "2026-08-20T08:34:25.798781Z"
}
```

`name`, an optional `description` and an optional `currency` (ISO-4217, defaults to `INR`)
are the only fields the request accepts — there is deliberately no way to name an owner or
seed a member list. The caller becomes the sole `OWNER` from the JWT alone.

### Add a member

Owner only.

```bash
curl -X POST http://localhost:8082/api/v1/groups/$GROUP_ID/members \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"userId": "3f2a5c81-9b4e-4d2f-8a17-6c0e1d9b3a44"}'
```

```json
{
  "userId": "3f2a5c81-9b4e-4d2f-8a17-6c0e1d9b3a44",
  "role": "MEMBER",
  "joinedAt": "2026-08-20T09:10:03.112000Z"
}
```

Members are named by user id, not email or invite code — there is no lookup-by-email or
invite-code flow yet, since that means a new endpoint on auth-service or a new
`group_invites` table this service does not have. `POST` returns `409` if the user is
already a member, `403` if the caller is not the owner.

### Read a group's balances

```bash
curl http://localhost:8082/api/v1/groups/$GROUP_ID/balances \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "groupId": "5f1e2a3c-9b7d-4c6a-8e2f-1a9b3c5d7e0f",
  "currency": "INR",
  "pairs": [
    {
      "debtorId": "3f2a5c81-9b4e-4d2f-8a17-6c0e1d9b3a44",
      "creditorId": "ab1d27c4-c423-401a-991e-7dcd760fc500",
      "amount": 450.0000
    }
  ],
  "netPositions": [
    { "userId": "ab1d27c4-c423-401a-991e-7dcd760fc500", "net": 450.0000 },
    { "userId": "3f2a5c81-9b4e-4d2f-8a17-6c0e1d9b3a44", "net": -450.0000 }
  ]
}
```

`pairs` lists only outstanding debts — a settled pair is omitted rather than reported as
zero. `netPositions` includes every current member, including anyone at zero, and always
sums to zero across the group: every debt is someone's positive and someone else's negative.
This response is served from a Redis cache keyed by group id; see
[Caching the debt graph](#caching-the-debt-graph).

### Activity feed

```bash
curl "http://localhost:8082/api/v1/groups/$GROUP_ID/entries?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "content": [
    {
      "id": "64c681ed-dd71-44cd-8588-d5aca0b93103",
      "groupId": "5f1e2a3c-9b7d-4c6a-8e2f-1a9b3c5d7e0f",
      "debtorId": "3f2a5c81-9b4e-4d2f-8a17-6c0e1d9b3a44",
      "creditorId": "ab1d27c4-c423-401a-991e-7dcd760fc500",
      "amount": 450.0000,
      "reason": "EXPENSE",
      "referenceId": "9f2c8b41-06de-4a35-8e7d-1b5c93a2704f",
      "description": "Dinner at Toit",
      "createdAt": "2026-08-20T08:41:28.575420Z"
    }
  ],
  "totalElements": 1,
  "number": 0,
  "size": 20
}
```

`amount` is always positive; `debtorId`/`creditorId` say which way it ran. `reason` is one
of `EXPENSE`, `EXPENSE_VOID` (an expense reversed by applying its deltas again in the
opposite direction) or `SETTLEMENT`. Unlike `pairs` above, this table is exactly what was
recorded, in the order it happened — it is never re-derived or reordered.

### Remove a member

```bash
curl -X DELETE http://localhost:8082/api/v1/groups/$GROUP_ID/members/$USER_ID \
  -H "Authorization: Bearer $TOKEN"
```

`204` on success. Anyone may remove themselves; only the owner may remove somebody else.
Refused with `409` while the member still owes or is owed anything in the group — removing
them would delete the membership row every balance is interpreted against, leaving a debt
pointing at somebody the group no longer contains.

**A caveat worth stating plainly: the owner is not special here.** `removeMember` only
requires ownership when `callerId != userId`; an owner removing *themselves* takes the same
"anyone may leave" branch as an ordinary member, provided they are settled up. Nothing
reassigns ownership or blocks the departure, so a group can be left with members but no
`OWNER` — after which nobody can add or remove anyone, or archive the group, until directly
edited in the database. See [Design notes](#design-notes).

### Archive / reactivate a group

```bash
curl -X POST "http://localhost:8082/api/v1/groups/$GROUP_ID:archive" \
  -H "Authorization: Bearer $TOKEN"
```

Owner only. Refused with `409` while any balance in the group is unsettled — an archived
group accepts no further applies, so a debt frozen inside one would be unreachable through
any endpoint. `POST /{groupId}:reactivate` reverses it, also owner only.

### Internal: apply balances

Called by `expense-service` with an admin token, whenever an expense or settlement changes
what members owe each other. `ROLE_ADMIN` is required because this endpoint names arbitrary
debtors and creditors in its body — an ordinary user token would otherwise be able to clear
its own debts.

```bash
curl -X POST http://localhost:8082/api/v1/groups/$GROUP_ID/balances:apply \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "referenceId": "9f2c8b41-06de-4a35-8e7d-1b5c93a2704f",
    "reason": "EXPENSE",
    "description": "Dinner at Toit",
    "deltas": [
      {
        "debtorId": "3f2a5c81-9b4e-4d2f-8a17-6c0e1d9b3a44",
        "creditorId": "ab1d27c4-c423-401a-991e-7dcd760fc500",
        "amount": 450.0000
      }
    ]
  }'
```

```json
{
  "groupId": "5f1e2a3c-9b7d-4c6a-8e2f-1a9b3c5d7e0f",
  "referenceId": "9f2c8b41-06de-4a35-8e7d-1b5c93a2704f",
  "applied": true,
  "balances": {
    "groupId": "5f1e2a3c-9b7d-4c6a-8e2f-1a9b3c5d7e0f",
    "currency": "INR",
    "pairs": [
      {
        "debtorId": "3f2a5c81-9b4e-4d2f-8a17-6c0e1d9b3a44",
        "creditorId": "ab1d27c4-c423-401a-991e-7dcd760fc500",
        "amount": 450.0000
      }
    ],
    "netPositions": [
      { "userId": "ab1d27c4-c423-401a-991e-7dcd760fc500", "net": 450.0000 },
      { "userId": "3f2a5c81-9b4e-4d2f-8a17-6c0e1d9b3a44", "net": -450.0000 }
    ]
  }
}
```

`referenceId` is the idempotency key — the expense or settlement id in expense-service. Send
the identical request again and the response is `200` with `applied: false` and the balances
unchanged: **not an error**, because the caller's intent is already satisfied, and that is
precisely the answer a retry after a timeout needs. `deltas` may carry up to 200 entries and
all commit together or none do — one expense typically produces several, one per participant
other than the payer. See
[Idempotent applies: why the expense saga needs no compensation](#idempotent-applies-why-the-expense-saga-needs-no-compensation).

### Errors

Every failure returns the same shape, from the controller advice and from the security
filter chain alike:

```json
{
  "timestamp": "2026-08-20T08:41:28.485441367Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Every debtor and creditor must be a current member of the group",
  "path": "/api/v1/groups/5f1e2a3c-.../balances:apply"
}
```

| Status | When |
|---|---|
| `400` | Validation failure (per-field detail in `validationErrors`), malformed body, malformed UUID, a debt from someone to themselves |
| `401` | Missing, expired, mis-signed, wrong-issuer, or non-access token |
| `403` | Authenticated, but not permitted — a member who is not the owner, or a user token on the internal apply endpoint |
| `404` | No such group, or the caller is not a member of it (the two are indistinguishable on purpose) |
| `409` | Member/group still has an unsettled balance · group already archived/not archived · sustained write contention on a balance apply |
| `422` | A delta names somebody who is not currently a member of the group |
| `500` | Unexpected. Logged in full; the response never carries a stack trace |

A `409` from contention is always safe to retry: it means **nothing changed**.

---

## Design notes

### Idempotent applies: why the expense saga needs no compensation

`applyBalances` is called by expense-service, which may retry the call without ever having
learned whether the first attempt landed — a timeout says nothing about what the server did.
The `applied_operations` table is what makes that retry safe: `reference_id` (the expense or
settlement id) is its primary key, and the receipt row is written in the *same transaction*
as the balance mutations it describes, so the receipt and the deltas cannot disagree — either
both committed or neither did.

`GroupTransactionService.applyBalances` first does a cheap `existsByReferenceId` check to
reject the common replay early, then still attempts `saveAndFlush` of the receipt inside a
`try`/`catch`. That second step is not redundant: the existence check races, so two genuinely
concurrent applies of the same reference can both pass it, and it is the primary key —
caught as a `DataIntegrityViolationException` — that actually stops the second one. Both
paths raise `OperationAlreadyAppliedException`, which `GroupService.applyBalances` turns into
a `200` carrying `applied: false` rather than an error.

`AppliedOperation` implements `Persistable<String>` for exactly this reason: its id is
assigned (expense-service's reference), and Spring Data's default new-vs-existing test
("is the id null?") would otherwise route a repeat through `merge` — silently UPDATING the
existing receipt instead of colliding with it, which would defeat the whole mechanism and
let the same expense post twice. Forcing `persist` makes the primary key do the job.

The predecessor platform (a digital-wallet ledger) had no equivalent: its movement endpoints
applied unconditionally on every call, so a retry could double-charge and a compensating
reversal could reverse something that never happened, and the saga around it was documented
as leaving transfers stranded for manual reconciliation rather than closing the hole. Here
the retry is simply safe by construction, which is why nothing analogous to a compensation
step exists in this service.

### Optimistic locking and the retry budget

`group_balances` carries a Hibernate `@Version` column. Two expenses touching the same pair
both read the same row; without protection the second write would silently discard the
first — a debt would simply vanish. Hibernate makes every `UPDATE` conditional on the
version it read (`... WHERE id = ? AND version = n`), so the loser of a race matches zero
rows and Spring raises `OptimisticLockingFailureException` at commit rather than overwriting.

Optimistic rather than pessimistic (`SELECT ... FOR UPDATE`), because conflicts within one
group are rare and a pessimistic lock would serialise every expense in a group for the whole
transaction. `BalanceApplyService.apply` wraps the transactional method in `@Retryable`,
catching both `OptimisticLockingFailureException` and — a difference from a single-row
balance design — `DataIntegrityViolationException`: the first time two members transact,
their pair row does not exist yet, and two concurrent applies can both attempt the insert,
with `uq_group_balances_pair` rejecting one. That loser's next attempt simply finds the row
the winner created and updates it, so retrying it converges instead of rejecting a valid
expense. Backoff is randomised (`@Backoff(delay = 50, multiplier = 2, random = true)`) so
that losers of a race do not all wake and collide again in lockstep.

Because an apply typically touches several pair rows at once (a nine-way dinner split takes
eight), and it loses if it collides on *any* of them, it is more exposed to contention than a
single-row balance ever was. Deltas are therefore applied in a deterministic order — sorted
by the canonical pair each one touches — so that two concurrent applies acquire rows in the
same sequence and cannot deadlock against each other. The retry budget
(`GROUP_APPLY_MAX_ATTEMPTS`, default 3) is a throughput knob, not a correctness one: an apply
that exhausts it changed nothing at all, and is reported as a `409 ConcurrentUpdateException`
safe to retry from the client.

**Why the retry and the transaction are separate Spring beans** (`BalanceApplyService` vs.
`GroupTransactionService`) is the single most load-bearing structural decision in the
service. `@Transactional`, `@Retryable` and `@CacheEvict` are all applied by proxies, and a
proxy only intercepts calls arriving from *outside* the object it wraps — a bean calling its
own method skips the annotation entirely. An optimistic-lock failure is only detected at
*commit*, by which point the transaction is finished and marked rollback-only, so a retry
must open a genuinely new transaction with a fresh persistence context and a fresh read of
every row. Had both annotations lived on one class, the retry interceptor would sit outside
the transaction interceptor but re-invoke the method body directly — every attempt after the
first would run inside the already-doomed transaction and fail identically forever. The same
reasoning is why `GroupQueryService` (the `@Cacheable` balances read) is its own bean too.

### Caching the debt graph

Only `GET /{groupId}/balances` is cached, in Redis, keyed by group id alone
(`CacheConfig.GROUP_BALANCES_CACHE`), TTL `CACHE_BALANCES_TTL` (default 30s). A group's graph
is read far more often than it changes — every member sees it every time they open the
group — and computing it is two queries plus a fold over the pair rows. Keying by group
rather than by (group, user) means one entry serves every member, which is *why* the
membership check cannot live inside the cached method: a cache hit would skip it entirely and
serve one group's balances to anybody who asked. `GroupService.getBalances` therefore checks
membership first, as a separate call through `GroupQueryService`'s proxy.

Membership itself is deliberately **not** cached, even though it is read on every request: it
is the authorisation boundary of the whole service, and a stale entry would let someone
removed from a group keep reading it for the length of a TTL. A primary-key lookup is cheap;
being wrong about who may see a group is not.

Nothing on the write path reads the cache. An apply validates membership and updates every
balance from the database under the protection of its version column — never from a cached
copy. `@CacheEvict` on `BalanceApplyService.apply` runs `beforeInvocation = false`, i.e. only
after the method returns successfully, which opens a brief window between the commit and the
eviction where Postgres already holds the new balances and Redis still serves the old ones.
That window is sub-millisecond in the normal case and is not closed by evicting first —
evicting up front would instead leave the *entire duration* of the apply, including every
optimistic-lock retry, open for a concurrent read to repopulate the cache with the
still-uncommitted old graph, which would then sit there for a full TTL. The TTL is the
backstop for a lost eviction (a Redis hiccup at exactly the wrong moment), not the primary
correctness mechanism — it just bounds how long any reader can trail a committed write.

Redis is a performance dependency, not an availability one: `CacheConfig` overrides the
default error handler to log and swallow cache faults rather than rethrow. A `@CacheEvict`
runs as part of recording an expense, so letting an unreachable Redis fail that call would
fail the write for a cache the write path does not even read — and since eviction happens
*after* commit, the caller would see an error describing an apply that had in fact succeeded,
and would retry a request that had already landed. A failed read simply falls through to
PostgreSQL; a failed eviction leaves an entry the TTL will expire anyway.

The value serialiser is locked to `GroupBalancesResponse`
(`JacksonJsonRedisSerializer<>(GroupBalancesResponse.class)`) rather than generic JSON. An
untyped serialiser would round-trip `"amount": 450.0000` back out of Redis as a
`LinkedHashMap` with a `Double` amount — the one representation this service forbids
everywhere else — instead of binding it straight into the record's `BigDecimal` field. Absent
values (a group that does not exist) are never cached (`disableCachingNullValues()`), so a
group created moments after a miss is visible immediately rather than reporting 404 for a TTL.

### The canonical pair ordering

`group_balances` stores one row per unordered pair per group — "Alice and Bob" is the same
relation as "Bob and Alice", and allowing both orderings would let the two rows silently
disagree with no way to say which is true. `UserPair.of` picks the canonical order, enforced
by `ck_group_balances_canonical CHECK (user_low < user_high)` in the schema.

The comparison is hand-written rather than `UUID.compareTo`, and this is a correctness
requirement, not a style preference: PostgreSQL compares its `uuid` type as sixteen
*unsigned* bytes, while `UUID.compareTo` compares the two halves as *signed* longs — the two
orderings disagree for any UUID whose most significant bit is set, roughly half of all random
UUIDs. Getting this wrong would be quiet and expensive: the constraint would reject about
half of all first-time pair inserts with a violation that looks like nothing the application
code did. `UserPair.POSTGRES_UUID_ORDER` uses `Long.compareUnsigned` on the most-significant
bits, then the least, which is exactly PostgreSQL's byte-wise unsigned comparison —
`UserPairTest` pins the Java side of this, and `GroupApplyIntegrationTest` pins the agreement
by letting a real PostgreSQL judge rows the service produced.

The debt itself is signed (`group_balances.amount` = what `userLow` owes `userHigh`,
negative if the debt runs the other way), unlike a wallet balance, which is the one respect
in which this design is *not* a straightforward rename of the platform it replaced: owing is
the normal state of this column, in both directions, roughly half the time.

### Owner removal is unguarded

`GroupTransactionService.removeMember` only requires ownership of the caller when
`callerId != userId`:

```java
if (!callerId.equals(userId)) {
    requireOwner(groupId, callerId);
}
```

An owner removing themselves takes the identical "anyone may leave" path as an ordinary
member — the only gate is `OutstandingBalanceException` if they are not settled up. Nothing
in `removeMember`, `addMember`, or anywhere else reassigns the `OWNER` role or blocks a
departure that would leave a group with members but no owner. Once that happens, `addMember`,
`removeMember` (of anyone else), `archiveGroup` and `reactivateGroup` all fail their
`requireOwner` check for every remaining member, with no in-product way to recover short of
a direct database edit. This is a known, currently open gap rather than an intentional
design choice — nothing in the codebase (tests, Javadoc, or otherwise) suggests ownership
transfer was deliberately deferred to a later change.

### Money and the ledger

**Amounts are `BigDecimal`, everywhere**, backed by `NUMERIC(19,4)`. The delta floor is
`0.0001`, not `0.01` — unlike the wallet endpoints this service replaced — because splitting
an odd amount across several people genuinely produces shares at the fourth decimal place,
and expense-service's residual allocation hands out single `0.0001` units to make shares sum
exactly to the original expense; rejecting them would make exact splitting impossible.
Comparisons use `compareTo`, never `equals` (`0.00` and `0.0000` are `equals`-unequal despite
being the same value), including in `GroupBalance.isSettled()` and throughout the mapper.

**`balance_entries` is append-only, structurally, not by convention.** `BalanceEntry` exposes
no setters, every column is `updatable = false`, and `BalanceEntryRepository` deliberately
does not extend `JpaRepository` — it extends the bare `Repository` marker and declares only
`save`, `findByGroupId` and `countByGroupId`, so there is no `delete` to call by mistake.
`AppliedOperationRepository` is narrowed the same way, for the same reason: deleting a
receipt would re-enable the double-apply it exists to prevent. A mistake is corrected by
appending an `EXPENSE_VOID` entry in the opposite direction, never by editing history.

**Flyway owns the schema**; Hibernate runs with `ddl-auto: validate` and fails at start-up if
the entity mappings and the migrated tables disagree.

**User ids are never foreign keys.** Users live in `auth-service` behind a different
database, so there is nothing in this schema to reference them against; `group_balances`'s
and `balance_entries`' foreign keys are only to `groups`, which is in the same database.

---

## Tests

```bash
./mvnw test
```

The integration tests need a Docker daemon for Testcontainers (real PostgreSQL; Redis is
switched off with `spring.cache.type=none` so they test what the database guarantees without
a cache in front of it).

| Test | What it covers |
|---|---|
| `GroupTransactionServiceTest` | Domain rules with mocked repositories: delta sign/direction, ledger entry content, pair-row creation, already-applied and reservation-race handling, archived-group rejection, non-member deltas, self-debt, non-positive and over-precise amounts, unknown group |
| `GroupMembershipIntegrationTest` | Real PostgreSQL: group creation and ownership, listing only the caller's own groups, non-member/unknown-group getting an indistinguishable 404, owner-only add, duplicate-member rejection, re-adding the owner not silently demoting them, settled-member removal, ordinary members unable to remove others, unsettled-member removal refusal, activity feed hidden from non-members |
| `GroupApplyIntegrationTest` | Real PostgreSQL: idempotent replay reporting `applied: false`, distinct references accumulating, opposite-direction deltas netting off, a rejected delta rolling back the whole batch and its receipt, canonical ordering satisfying the database constraint, one row per pair regardless of argument order, concurrent applies to one pair neither losing nor duplicating a debt, concurrent replays of the same reference applying exactly once, net positions summing to zero across the group |
| `GroupBalanceMapperTest` | Undoing the canonical storage order into signed-by-direction pairs, settled pairs omitted, net positions summing to zero and computed as owed-less-owing, a member with no balances still appearing, stable output ordering |
| `UserPairTest` | The unsigned-vs-signed UUID ordering disagreement with `UUID.compareTo`, pair symmetry, self-pairing and null rejection, `signFor` direction and its rejection of a debtor outside the pair |

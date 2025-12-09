# FireMUD Redis Lua Patterns

This document captures shared Lua scripting patterns used by FireMUD’s Redis
coordination layer. It focuses on idempotency, lock semantics, and safe
re-invocation behavior for tick-related scripts.

> 🔗 The high-level Redis coordination model, key naming, and failure modes are
> described in [System Architecture: Redis](./system-architecture-redis.md).

## Idempotent Script Patterns and Examples

Tick-related scripts must be idempotent: **re-running the same script with the same `KEYS` and `ARGV` must not apply new logical effects**. To make this concrete, scripts follow a small set of patterns:

### Determinism Requirements

To keep AOF replay and retries safe, Lua scripts must be **deterministic functions of their inputs and current Redis state**:

- Scripts may only use:
  - The `KEYS` and `ARGV` provided by the caller.
  - The current contents of Redis keys they read.
- Scripts must **not**:
  - Call `math.random` or any other RNG to influence behavior or generate IDs.
  - Use `TIME` or any other clock-based primitive to affect keys, members, scores, or control flow.
  - Synthesize new identifiers (for example, random suffixes or timestamps embedded in keys, ZSET members, or values) inside the Lua code.
  - Depend on external state or side effects outside Redis (for example, global variables mutated across runs).
- When randomness or time is required for a workflow:
  - Those values are generated in the caller (for example, Java code), passed in via `ARGV`, and treated as ordinary arguments.
  - AOF replay re-runs the script with the **same** `ARGV`, preserving idempotent behavior.

Scripts that violate these determinism requirements cannot guarantee safe replay and must be rejected during review and CI.

### Mandatory Validation Hedge

Every mutating script must perform the following validations before executing any writes:

- **Lease token** – re-read `tick-executor-lease:{tenantId}:{regionId}` and compare it to the supplied `leaseToken`. If they differ or the key is missing, the script returns a “stale lease” outcome and performs no writes.
- **Lock tokens** – for each `tick:{tenantId}:{regionId}:lock:{entityId}` key included in `KEYS`, compare the stored token to the expected value. Any mismatch or absence yields a `"STALE_LOCK"` result without mutation.
- **`tickId` guard** – when touching `tick:{tenantId}:{regionId}:pending` or other tick-scoped structures, verify the stored `tickId` is ≤ the requested `tickId`. Commit/rollback scripts abort if `tickId` is out of order so only the intended tick makes progress.

These checks are enforced via the Lua Script Registry descriptors, generated key-builder helpers, and CI linting so reviewers can automatically catch regressions. Scripts that cannot make these validations (for example, because they run outside a region lease context) are rejected or refactored.

### Script Complexity and Runtime Limits

To prevent Redis from stalling, tick-related scripts are bounded in keys touched and execution time:

- **Key limit** – each script invocation touches at most one lock key, one `pending` key, and a handful of other coordination keys (`timer`, `retry`, `queue`). Scripts that need to touch large key ranges must be split into smaller, bounded operations or moved out of the hot tick path.
- **Runtime limit** – scripts should finish well within tick latency targets (for example < 10‑20 ms). The registry annotates each script with a `max_execution_ms` hint, observability tracks percentiles, and CI flags scripts that routinely hit their budget.
- **No large scans** – `SCAN`, `HSCAN`, or cursor-based iterations are prohibited in tick scripts; maintenance helpers use those commands outside the tick loop with explicit scope and throttling.

Enforcing these limits prevents future scripts from violating hash-slot assumptions or blocking Redis for other regions.

- **Pattern 1 – Lease/lock token validation (guard-then-no-op)**
  - Every mutating script begins by validating the current lease and, where applicable, lock tokens:
    - Read `tick-executor-lease:{tenantId}:{regionId}` and compare its stored token to the `leaseToken` passed in `ARGV`.
    - For each entity lock key, compare the stored lock token to the expected token in `ARGV`.
  - If any token does not match, the script returns a **non-mutating outcome** such as `"STALE_LEASE"` or `"STALE_LOCK"` and performs **no writes**. Callers interpret this as “retry under the new lease” rather than as partial progress.
  - Region lease **renewal** uses a small, dedicated compare-and-extend helper that follows the same pattern:
    - Inputs:
      - `KEYS[1] = tick-executor-lease:{tenantId}:{regionId}`
      - `ARGV[1] = expectedLeaseToken`
      - `ARGV[2] = lease_ttl_ms`
    - Behavior (sketch):
      - Read `KEYS[1]`; if missing or if the stored token does not equal `expectedLeaseToken`, return `"STALE_LEASE"` and perform **no writes**.
      - If the token matches, extend the TTL via `PEXPIRE` (or `SET ... PX ... XX`) and return `"RENEWED"`.
    - Callers treat `"STALE_LEASE"` as loss of leadership for that `{tenantId, regionId}` and stop acting as executor, matching the lease semantics described in the Redis architecture document.

- **Pattern 2 – Compare-and-set on `tickId` (monotonic guard)**
  - Scripts that touch `tick:{tenantId}:{regionId}:pending` treat `tickId` as a monotonic guard:
    - Read the current `tickId` stored in `pending`.
    - If there is an existing `tickId` that is greater than the requested `tickId`, the script returns a replay/out-of-date result and does not modify state.
    - If the `tickId` is equal, the script proceeds but treats existing effect entries as already staged (see Pattern 3).
    - If there is no `tickId` or it is less than the requested `tickId`, the script sets/updates it and stages new effects.

- **Pattern 3 – Effect-key sets for staging (no duplicate staging)**
  - Staged effects inside `pending` are keyed by a deterministic `effectKey` (for example `entity:{entityId}:apply:damage:{commandId}`), and scripts use **set-style semantics**:
    - Before adding a staged effect, the script checks whether `effectKey` already exists in the pending structure (for example via `HEXISTS`, membership in a `SET`, or `ZSCORE` on a ZSET).
    - If the effect is already present, the script returns a replay outcome for that effect and does not create a second entry.
    - If it is not present, the script inserts or updates a single canonical entry for that `effectKey`.
  - Callers treat “already staged” as success; domain services decide whether to apply or skip based on their own idempotency rules.

- **Pattern 4 – Queue insertion with uniqueness**
  - When scripts enqueue work (for example timers or retryable actions), they use data structures that naturally deduplicate:
    - ZSET-based queues use `ZADD` with a unique member identifier (effect key or command ID); scripts check `ZSCORE` first and only call `ZADD` when the member is not already present.
    - For simple sets of flags or participants, scripts use `SADD` and ignore the return value except for observability; repeated `SADD` calls with the same member are safe no-ops.
  - This ensures that retries or replays do not create duplicate queue entries even when callers re-invoke the script.

- **Pattern 5 – Read/modify/write as a pure function of Redis state**
  - Scripts treat Redis as the single source of truth for coordination state during their execution:
    - They compute new values solely from the current contents of their keys plus the provided arguments.
    - They do not make assumptions about previous in-process computations; if a script is re-run after a crash or timeout, it sees whatever Redis currently holds and recomputes its result accordingly.
  - Combined with domain-level idempotency, this ensures that even if a script is run multiple times around failover, the final coordination state is consistent with the observed domain state.

- **Pattern 6 – Idempotency tests for every script**
  - Each Lua script has unit tests that:
    - Invoke the script once with a given key/value setup and record the resulting keyspace.
    - Invoke it again with the **same** `KEYS`/`ARGV` and assert that:
      - Return values indicate replay/no-op where appropriate.
      - The Redis keyspace is unchanged by the second invocation (modulo allowed derived counters or metrics).
  - For scripts that enqueue items, tests also cover the “replay after partial success” case: pre-populate keys to simulate a partially completed first run, then re-invoke the script and confirm it **does not** add duplicate entries or regress state.

New tick-related scripts are expected to adopt these patterns (or motivated variants) and include tests that prove re-invocation safety before they are accepted.

## Worked Example: Simple Lock-Acquire Script

As a concrete illustration, a simplified lock-acquire Lua script follows these patterns:

- **Inputs**
  - `KEYS[1]` – `tick:{tenantId}:{regionId}:lock:{entityId}`
  - `ARGV[1]` – `lockToken`
  - `ARGV[2]` – `leaseToken`
  - `KEYS[2]` – `tick-executor-lease:{tenantId}:{regionId}` (optional, when validating lease)

- **Behavior (sketch)**
  1. Read `KEYS[2]` (lease) and verify its token matches `ARGV[2]`; if not, return `"STALE_LEASE"` without writing.
  2. Read `KEYS[1]`:
     - If absent, set `KEYS[1] = ARGV[1]` with TTL `lock_ttl_ms` and return `"ACQUIRED"`.
     - If present and equal to `ARGV[1]`, treat as replay and return `"ALREADY_HELD"` without modifying TTL.
     - If present and different, return `"LOCK_HELD_BY_OTHER"` without modifying the key.

- **Idempotency properties**
  - Re-running the script with the same `KEYS`/`ARGV` after a successful acquire returns `"ALREADY_HELD"` and leaves the key unchanged.
  - Re-running after a failed lease or conflicting lock returns the same status and performs no writes.

Unit tests for this script would:

- Set up a fresh keyspace, call the script once, and assert that:
  - The lock key exists with the expected token and TTL.
  - The return value is `"ACQUIRED"`.
- Call the script again with the same `KEYS`/`ARGV` and assert that:
  - The lock key’s value is unchanged.
  - The TTL has not been extended unexpectedly (unless explicitly designed to refresh).
  - The return value is `"ALREADY_HELD"`.
- Simulate a conflicting holder by setting a different token in `KEYS[1]` and assert that the script returns `"LOCK_HELD_BY_OTHER"` and does not overwrite the existing token.

## Related Documentation

- [System Architecture: Redis](./system-architecture-redis.md)
- [System Architecture: Redis Cache & Rate Limiting](./system-architecture-redis-cache.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Transaction Strategies](./system-architecture-transactions.md)

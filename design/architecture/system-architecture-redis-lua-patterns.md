# FireMUD Redis Lua Patterns

This document captures shared Lua scripting patterns used by FireMUD’s Redis
coordination layer. It focuses on idempotency, lock semantics, and safe
re-invocation behavior for tick-related scripts.

> 🔗 The high-level Redis coordination model, key naming, and failure modes are
> described in [System Architecture: Redis](./system-architecture-redis.md).

## Idempotent Script Patterns and Examples

Tick-related scripts must be idempotent: **re-running the same script with the same `KEYS` and `ARGV` must not apply new logical effects**. To make this concrete, scripts follow a small set of patterns:

Before authoring or reviewing a new script, use this quick checklist:

- The script is **deterministic**: no RNG (`math.random`), no time-based control flow (`TIME`), and no external/global state.
- All keys are passed via `KEYS[...]` and built using shared key helpers; no hard-coded string concatenation of prefixes.
- The script’s category (region-lease, session-only, maintenance, automation, etc.) is clearly identified, and the expected **invariants** (lease token, epoch, lock tokens, session binding) are validated before any write.
- Structured payloads include an explicit `schemaVersion`, and the script:
  - Treats missing versions as a well-defined default.
  - Understands at least `N` and `N‑1` for the current rollout.
  - Returns a non-mutating `"UNSUPPORTED_SCHEMA_VERSION"` outcome for unknown versions.
- Writes are **idempotent** with respect to their inputs:
  - Lock and lease operations treat repeated acquire/refresh calls as no-ops with stable outcomes.
  - Queue/timer/effect insertion uses set-style semantics to avoid duplicate entries on replay.
- Error outcomes are explicit and non-mutating (for example `"STALE_LEASE"`, `"STALE_LOCK"`, `"SESSION_VERSION_MISMATCH"`); callers can safely retry or escalate based on return codes.
- The script has an entry in the **Lua Script Registry** (in `firemud-common`) that describes:
  - Key roles and order (`KEYS[1]`, `KEYS[2]`, etc.).
  - Allowed prefixes and hash-tag assumptions.
  - The script category and whether it is single-key or shard-local multi-key.
- Tests cover at least:
  - A fresh run from an initial state.
  - A pure replay with the same `KEYS`/`ARGV` and no intervening changes.
  - A replay after partial success (for example, some keys pre-populated) to prove the script does not double-apply effects.

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

### Schema Versioning and Script Evolution

Many coordination structures stored in Redis (for example `tick:{tenantRegionTag}:pending` payloads or structured `session:*` values) evolve over time. To keep script behavior compatible with both **old** and **new** shapes while preserving determinism:

- Every structured payload that may evolve must include a small, explicit `schemaVersion` field in its serialized representation.
- Scripts that read these payloads:
  - Treat a missing `schemaVersion` as a well-defined default (for example, version `1`).
  - Support **at least** the current and previous schema versions (`N` and `N‑1`) during rollout windows.
  - Never silently ignore unknown versions; instead they return a clear, non-mutating outcome such as `"UNSUPPORTED_SCHEMA_VERSION"` that callers can log and surface in metrics.
- Migration-friendly branching:
  - Scripts branch on `schemaVersion` only to:
    - Apply added fields with sensible defaults (for example, treat absent optional fields as `nil` / default values).
    - Adjust interpretation of existing fields in a way that remains idempotent for both `N` and `N‑1`.
  - They avoid “upgrade in place” behavior inside Lua (for example, rewriting the payload to a new shape as a side effect of reads) unless that behavior is explicitly designed and tested for replay.
- Rollout order mirrors the guidance in the Redis architecture doc:
  1. Deploy new scripts that understand both `N‑1` and `N` payloads everywhere.
  2. Update services to start writing `schemaVersion = N` payloads.
  3. Once metrics show old versions have drained, remove the `N‑1` branch from scripts in a separate change.
- Tests for versioned scripts:
  - Exercise both `N‑1` and `N` payloads (and the “missing version” default case).
  - Assert that re-running the script with the same payload and `schemaVersion` is idempotent.
  - Assert that unknown versions do not mutate Redis state and return the expected `"UNSUPPORTED_SCHEMA_VERSION"` (or equivalent) outcome.

### Script Categories and Validation Hedge

Not every mutating script participates in the same coordination context. For clarity and review, scripts are grouped into a small set of categories, each with its own validation rules.

The **Lua Script Registry** in `firemud-common` records, for each script:

- The script name and category (for example `region_lease_tick`, `session_only`, `automation_queue`, `maintenance`).
- The expected key roles and order (`KEYS[1] = lockKey`, `KEYS[2] = pendingKey`, etc.).
- Required invariants for that category (for example, lease and lock token validation for region-lease scripts, session binding and expiry checks for session-only scripts).

This registry is the authoritative mapping from **script name → category → required invariants** and is referenced by both application code and CI. When adding a new script or changing an existing one, update the registry entry so reviewers can see which category rules and validations apply without reverse-engineering the Lua source.

#### Region-lease scripts (tick and coordination)

These scripts operate on region-scoped coordination keys (`tick:{tenantId}:{regionId}:*`, `timer:{tenantId}:{regionId}`, `retry:{tenantId}:{regionId}`, `tick-executor-lease:{tenantId}:{regionId}` via the shared `{tenantRegionTag}`) and must run under an active region lease.

Every region-lease script must perform the following validations before executing any writes:

- **Lease token and epoch** – re-read `tick-executor-lease:{tenantId}:{regionId}` and compare its stored token and epoch to the supplied values in `ARGV`. If they differ or the key is missing, the script returns a non-mutating outcome such as `"STALE_LEASE"` / `"UNSUPPORTED_EPOCH"` and performs no writes.
- **Lock tokens** – for each `tick:{tenantId}:{regionId}:lock:{entityId}` key included in `KEYS`, compare the stored token to the expected value. Any mismatch or absence yields a `"STALE_LOCK"` result without mutation.
- **`tickId` guard** – when touching `tick:{tenantId}:{regionId}:pending` or other tick-scoped structures, verify the stored `tickId` is ≤ the requested `tickId`. Commit/rollback scripts abort if `tickId` is out of order so only the intended tick makes progress.

These checks are enforced via the Lua Script Registry descriptors, generated key-builder helpers, and CI linting so reviewers can automatically catch regressions. Scripts that cannot make these validations are not allowed to touch tick/coordination prefixes.

#### Session-only scripts

Session scripts operate only on `session:{tenantId}:{sessionId}` keys and do **not** run under a region lease. They must instead validate session-specific invariants:

- **Session key and binding** – verify that the target session key exists and, where applicable, that it is bound to the expected `playerId`/`tenantId` or token hash provided in `ARGV`.
- **Expiry and logical window** – enforce the logical expiry rules described in the session design (for example, do not revive sessions whose logical expiry timestamp has passed, even if the Redis TTL has not).
- **Optional CAS fields** – when scripts implement compare-and-set semantics on session payloads (for example, update only if a `version` or `lockToken` field matches), they must:
  - Treat mismatched versions as non-mutating outcomes (for example, `"SESSION_VERSION_MISMATCH"`).
  - Avoid partial updates that would leave the payload in a mixed version or conflicting binding state.

These rules keep session scripts lightweight while still protecting reconnect and binding invariants. They deliberately avoid a region lease so that session operations are not coupled to tick leadership, but they still behave deterministically and idempotently around reconnection windows.

#### Maintenance and non-lease scripts

Some maintenance or dev-tools scripts may operate on coordination prefixes outside the normal tick/session flow (for example, inspecting or cleaning up keys for a paused tenant/region). These scripts:

- Run only in **maintenance contexts** (for example, dev-tools jobs or coordination reset tooling) and must not be invoked from hot-path gameplay.
- Must respect the same shard-local and key-shape rules as tick scripts (for example, operate on one `{tenantRegionTag}` at a time).
- May skip lease validation **only** when the surrounding tool guarantees that ticks are paused for affected scopes and that no active executor is running; in that case they still:
  - Avoid partial mutations that would violate idempotency or schema assumptions.
  - Prefer to delegate destructive operations (for example, wiping a region) to the shared coordination reset tooling rather than hand-editing keys.

Scripts that do not clearly fit one of these categories should be refactored until their validation story is explicit. Region-lease scripts are the default for tick/coordination flows; session-only scripts and maintenance scripts are narrow exceptions with tighter scope and clearly defined behavior.

### Automation Scripts and Cluster Slotting

Automation-related Lua scripts follow stricter cluster slotting rules to avoid `CROSSSLOT` errors and keep coordination boundaries clear:

- Scripts that operate on `automation:tick:{tenantId}:{scriptId}:*` keys are registered as **single-key** scripts:
  - They may include multiple `automation:tick:{tenantId}:{scriptId}:*` keys for the **same** `{tenantId, scriptId}` in `KEYS`, but they must not mix different `{tenantId, scriptId}` pairs.
  - They must not include any `tick:{tenantId}:{regionId}:*` keys in the same invocation.
- Scripts that operate on `automation_queue:{tenantId}:*` keys:
  - Use only `automation_queue:{tenantId}:*` keys for a single tenant in `KEYS`.
  - Must not include `automation:tick:*` or `tick:*` keys in the same invocation.
- Cross-boundary rules:
  - Automation scripts **never** perform multi-key operations that span both `automation:*` and `tick:*` prefixes in one `EVAL`/`EVALSHA` call.
  - Automation work is staged under `automation_queue:*` and `automation:tick:*` and handed off to Game Session via gRPC; only Game Session scripts mutate `tick:*` prefixes.

CI must reject automation Lua scripts that:

- Reference both `automation:*` and `tick:*` keys in their registry descriptors, or
- Construct `tick:*` keys by hand instead of using shared key builders.

### Script Complexity and Runtime Limits

To prevent Redis from stalling, tick-related scripts are bounded in keys touched and execution time:

- **Key limit** – each script invocation touches at most one lock key, one `pending` key, and a handful of other coordination keys (`timer`, `retry`, `queue`). Scripts that need to touch large key ranges must be split into smaller, bounded operations or moved out of the hot tick path.
- **Runtime limit** – scripts should finish well within tick latency targets (for example < 10‑20 ms). The registry annotates each script with a `max_execution_ms` hint, observability tracks percentiles, and CI flags scripts that routinely hit their budget.
- **No large scans** – `SCAN`, `HSCAN`, or cursor-based iterations are prohibited in tick scripts; maintenance helpers use those commands outside the tick loop with explicit scope and throttling.

Enforcing these limits prevents future scripts from violating hash-slot assumptions or blocking Redis for other regions.

Bulk key-walking is reserved for **offline maintenance tooling**, not tick execution:

- Long-running maintenance tasks that need to inspect or repair many keys (for example, cleaning up old locks, timers, or mis-shaped coordination keys) should:
  - Live in dedicated maintenance scripts and dev-tools jobs, not in the hot tick/session loop.
  - Use `SCAN` with strict prefix filters, small batch sizes, and explicit rate limiting or sleeps between batches.
  - Operate on well-scoped prefixes (for example, a single `{tenantRegionTag}` or tenant) rather than scanning the entire keyspace.
- Detailed guidance on maintenance scripts and coordination reset lives in the Redis operations and reset documentation; this Lua patterns doc is focused on **hot-path** behavior.

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
-- Simulate a conflicting holder by setting a different token in `KEYS[1]` and assert that the script returns `"LOCK_HELD_BY_OTHER"` and does not overwrite the existing token.

## Lua Script Registry and CI Expectations

All coordination-related Lua scripts live in a **Lua Script Registry** in the shared library. For each script, the registry records:

- Script identifier and file path.
- Expected `KEYS` and `ARGV` ordering and allowed prefixes (including hash-tag rules).
- Script category (for example, tick lock, timer queue, session CAS) and reset-tolerance assumptions.

CI enforces the following invariants for registered scripts:

- Every Lua file under the coordination scripting path has a corresponding registry entry; unregistered scripts fail CI.
- Scripts pass determinism checks (no disallowed commands such as `TIME` and no RNG usage) and are covered by idempotency tests similar to the patterns in this document.
- Registry metadata is validated against tests so that `KEYS`/`ARGV` expectations stay in sync with callers.

## Call-Side Time and Randomness Contract

Determinism requirements forbid time and randomness inside Lua itself. Callers must:

- Generate any required timestamps or random tokens in application code and pass them via `ARGV`.
- Treat those values as **stable inputs**: AOF replay reuses the same `ARGV` so repeated executions see identical arguments.
- Avoid embedding current time or random suffixes in Redis key names from inside Lua; any such keys must be constructed by callers and passed in through `KEYS`.

CI and code review treat violations of this contract (for example, new scripts that invoke `TIME` or synthesize random IDs) as blocking issues.

## Related Documentation

- [System Architecture: Redis](./system-architecture-redis.md)
- [System Architecture: Redis Cache & Rate Limiting](./system-architecture-redis-cache.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Transaction Strategies](./system-architecture-transactions.md)

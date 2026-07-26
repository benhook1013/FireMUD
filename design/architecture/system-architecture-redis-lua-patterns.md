# FireMUD Redis Lua Patterns

This document captures shared Lua scripting patterns used by FireMUD’s Redis
coordination layer. It focuses on idempotency, lock semantics, and safe
re-invocation behavior for tick-related scripts.

> 🔗 The high-level Redis coordination model, key naming, and failure modes are
> described in [System Architecture: Redis](./system-architecture-redis.md).

## Default Author/Reviewer Expectations

- New or changed scripts must fit one of the existing **script categories** and satisfy the idempotency, determinism, and `schemaVersion` rules described here.
- Compatibility decisions (for example `compatible`, `requires_region_reset`, `requires_tenant_reset`, `requires_cluster_reset`) and rollout plans are made via the **Lua Script Registry**, not by introducing per-script operational knobs.
- The registry is the **single source of truth** for script metadata, including:
  - Key roles and order (`KEYS[1]`, `KEYS[2]`, etc.).
  - Allowed prefixes and hash-tag assumptions.
  - The Redis role (`redis_role`) the script is allowed to talk to (for coordination scripts this is always `coordination`).
  - Reset and tail-loss behavior (`reset_sensitivity`, `tail_loss_behavior`) describing how the script’s keys behave under region/tenant/cluster resets and within the tail-loss envelope described in `system-architecture-redis.md` and `system-architecture-redis-reset-and-recovery.md`.

Any proposal that relies on special per-script runtime flags or bespoke operational handling should be treated as **advanced** and pushed back toward these shared patterns and the central registry.

## Script Categories and Key Families

Every coordination script belongs to a **script category** that constrains which keys it may touch and how those keys are sharded. At a high level:

| Category | Example key families | Shard-locality rules |
| --- | --- | --- |
| Region lease | `tick-executor-lease:{tenantRegionTag}` | Single-key scripts scoped to a single `{tenantRegionTag}` hash tag. |
| Entity lock | `tick:{tenantRegionTag}:lock:<entityId>` | Single-key or small multi-key scripts; all lock keys share the same `{tenantRegionTag}` as their corresponding `pending` structures. |
| Tick staging / pending | `tick:{tenantRegionTag}:pending` and related effect structures | Single-key or shard-local multi-key scripts that operate entirely within one `{tenantRegionTag}` slot. |
| Timers and retries | `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}` | Shard-local scripts operating on keys that share the same `{tenantRegionTag}`; no cross-slot operations. |
| Session CAS / bindings | `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` plus `session:game:index:*:{tenantGameplayTag}:*`, and `session:auth:<scope>:<tokenHash>` (for example `session:auth:tenant:<tenantId>:<tokenHash>`) | Gameplay session CAS/update scripts may be shard-local multi-key scripts where all gameplay-session keys share `{tenantGameplayTag}`. Auth allowlist and revocation-watermark scripts remain single-key. Session scripts are never mixed with tick keys in the same invocation. |
| Session-to-region bridge | `tick:{tenantRegionTag}:session-binding:<entityId>` plus `tick-executor-lease:{tenantRegionTag}` | Region-lease scripts only. They update region-authoritative gameplay bindings using caller-supplied `sessionId` / `binding_generation` and never read `session:game:*` directly inside Lua. |
| Maintenance / cleanup | Region-local maintenance keys under `tick:{tenantRegionTag}:*` or `timer:{tenantRegionTag}` | Shard-local scans and deletes constrained to one `{tenantRegionTag}` at a time; no cross-slot operations. |
| Automation helpers (coordination role only) | `script-scheduler:{tenantRegionTag}:lastTickId` and similar | Shard-local scripts that operate on per-region scheduler metadata; must not touch Cache/Rate-Limit prefixes. |

The Lua Script Registry encodes these category→prefix→shard rules so that callers do not hard-code prefixes or slots. New categories or key families must be added to this table and to the registry schema before scripts using them are accepted.

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
  - The Redis role it is permitted to touch (`redis_role=coordination` for tick/session scripts).
  - Reset behavior (`reset_sensitivity` such as `safe_replay_after_reset`, `requires_region_reset`, `requires_tenant_reset`, `requires_cluster_reset`) and tail-loss behavior (`tail_loss_behavior` such as “duplicate enqueues possible but domain-idempotent”, “pure lease op, safe to lose”).
- Tests cover at least:
  - A fresh run from an initial state.
  - A pure replay with the same `KEYS`/`ARGV` and no intervening changes.
  - A replay after partial success (for example, some keys pre-populated) to prove the script does not double-apply effects.

## Outcome Codes and Caller Contract (Required)

Redis Lua scripts are invoked from timeout, retry, and failover paths. To keep callers safe and to prevent “did this mutate?” ambiguity, every coordination script must return an explicit, low-cardinality **outcome code** as its primary result, and that outcome must imply a clear caller action.

The Lua Script Registry is the source of truth for each script’s specific outcomes, but outcomes must fit these shared categories and semantics:

- **Success / applied**
  - Examples: `"ACQUIRED"`, `"STAGED"`, `"ENQUEUED"`, `"UPDATED"`.
  - Meaning: the script performed its intended mutation (or a deterministic, idempotent update) and the caller may proceed to the next phase.
- **Replay / already-done (non-mutating)**
  - Examples: `"ALREADY_HELD"`, `"ALREADY_STAGED"`, `"NOOP"`.
  - Meaning: the script observed that the intended work is already reflected in Redis state and performed no logical new work; callers must treat this as success and continue without attempting alternate paths that would create duplicates.
- **Stale leadership / stale timeline (non-mutating, retry by reacquiring)**
  - Examples: `"STALE_LEASE"`, `"STALE_EPOCH"`, `"STALE_LOCK"`, `"OUT_OF_DATE_TICK"`.
  - Meaning: the caller’s lease/epoch/lock/tick assumptions do not match Redis’ current coordination state. Callers must stop acting under the stale context, reacquire the relevant lease/lock, and re-bootstrap from the authoritative timeline (RegionStatus/ledger + heartbeat) before retrying.
- **Validation failure (non-mutating, do not retry blindly)**
  - Examples: `"UNSUPPORTED_SCHEMA_VERSION"`, `"INVALID_ARGS"`, `"FORBIDDEN_PREFIX"`.
  - Meaning: a contract mismatch or programming/configuration error. Callers must not spin retries; they should surface an alert, increment a dedicated metric, and follow the rollout/reset guidance for the script’s `compatibility_level` and `reset_sensitivity`.
- **Contention / capacity (non-mutating, bounded retry or defer)**
  - Examples: `"LOCK_HELD_BY_OTHER"`, `"QUEUE_FULL"`, `"BUDGET_EXCEEDED"`.
  - Meaning: the script refused to mutate due to current contention or a fairness/capacity guard. Callers must apply the documented backoff/defer policy (typically “retry in a later tick” rather than tight-looping).
- **Fairness / admission deferral (non-mutating, defer to the next eligible scheduling point)**
  - Examples: `"DEFER_TO_NEXT_TICK"`, `"REMOTE_FOLLOWUP_BACKLOG"`, `"FAIRNESS_WINDOW_EXHAUSTED"`.
  - Meaning: the script preserved correctness by declining work that would violate fairness or backlog-admission policy for the current scheduling window. Callers must record the bounded deferral outcome, avoid tight retries in the same tick, and rely on the next eligible tick or backlog-clearing signal before retrying.

Hard requirements:

- Outcomes that are documented as **non-mutating** must perform no writes, not even TTL refreshes or counters, so callers can safely retry without compounding state.
- Every script must define which outcomes are retryable and under what conditions, and CI must verify that retryable non-mutating outcomes are truly side-effect-free.
- Callers must treat unknown outcomes as fatal (log, metric, alert) rather than guessing whether a mutation occurred.

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

These scripts operate on region-scoped coordination keys (`tick:{tenantRegionTag}:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, `tick-executor-lease:{tenantRegionTag}`) and must run under an active region lease.

Every region-lease script must perform the following validations before executing any writes:

- **Lease token and epoch** – re-read `tick-executor-lease:{tenantRegionTag}` and compare its stored token and epoch to the supplied values in `ARGV`. If they differ or the key is missing, the script returns a non-mutating outcome such as `"STALE_LEASE"` / `"UNSUPPORTED_EPOCH"` and performs no writes.
- **Lock tokens** – for each `tick:{tenantRegionTag}:lock:<entityId>` key included in `KEYS`, compare the stored token to the expected value. Any mismatch or absence yields a `"STALE_LOCK"` result without mutation.
- **`tickId` guard via meta** – when touching `tick:{tenantRegionTag}:pending` or other tick-scoped structures, use the canonical metadata key `tick:{tenantRegionTag}:meta` as the monotonic guard: read `current_tick_id` from that hash and verify it is ≤ the requested `tickId` before staging new work. Commit/cleanup scripts abort if the guard indicates the requested `tickId` is out of order so only the intended tick makes progress. Any `tickId` fields embedded inside `pending` payloads are informational only and must not be used as guards.
- **`current_tick_state` gate via meta** – region-lease scripts must also read `current_tick_state` from `tick:{tenantRegionTag}:meta` and obey the canonical state machine from the Redis hub doc:
  - A new tick may be initialized only from a missing meta record or from a terminal state (`APPLIED` or `ABANDONED`) of the immediately prior tick.
  - Replays for the same tick may continue only while `current_tick_state in {STAGED, RESOLVING}`.
  - Hot-path scripts must never advance `current_tick_id` past a non-terminal tick.

The Redis operations docs and metrics catalog should treat `current_tick_state` transitions as first-class observability surfaces so operators can tell whether regions are stuck in `STAGED` or `RESOLVING`, how often ticks terminate as `APPLIED` vs `ABANDONED`, and whether stale-timeline outcomes correlate with reset or recovery activity.

These checks are enforced via the Lua Script Registry descriptors, generated key-builder helpers, and CI linting so reviewers can automatically catch regressions. Scripts that cannot make these validations are not allowed to touch tick/coordination prefixes.

#### Session-only scripts

Session scripts operate only on gameplay session keys that share one `{tenantGameplayTag}` and do **not** run under a region lease. The normal mutating set is:

- `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`
- `session:game:index:character:{tenantGameplayTag}:<gameInstanceId>:<characterId>`
- `session:game:index:account-tenant:{tenantGameplayTag}:<accountId>`
- `session:game:index:tenant:{tenantGameplayTag}`

These scripts must instead validate session-specific invariants:

- **Session key and binding** – verify that the target session key exists and, where applicable, that it is bound to the expected `characterId`/`tenantId` or token hash provided in `ARGV`.
- **Expiry and logical window** – enforce the logical expiry rules described in the session design (for example, do not revive sessions whose logical expiry timestamp has passed, even if the Redis TTL has not).
- **Optional CAS fields** – when scripts implement compare-and-set semantics on session payloads (for example, update only if a `version` or `lockToken` field matches), they must:
  - Treat mismatched versions as non-mutating outcomes (for example, `"SESSION_VERSION_MISMATCH"`).
  - Avoid partial updates that would leave the payload in a mixed version or conflicting binding state.

These rules keep session scripts lightweight while still protecting reconnect and binding invariants. They deliberately avoid a region lease so that session operations are not coupled to tick leadership, but they still behave deterministically and idempotently around reconnection windows. In Redis Cluster, their atomicity boundary is the tenant-scoped `{tenantGameplayTag}` slot only; anything region-local still goes through the separate bridge step.

Session scripts must not attempt to mutate region-scoped gameplay binding keys in the same invocation. Region-local gameplay authority lives under `tick:{tenantRegionTag}:session-binding:<entityId>` and is updated only by region-lease scripts using the monotonic `binding_generation` carried from the session contract. As a result:

- `session:game:*` is authoritative for reconnect eligibility, session CAS fields, and the latest desired binding generation.
- `tick:{tenantRegionTag}:session-binding:<entityId>` is authoritative for whether gameplay in a specific region currently recognizes that session.
- Any cross-region takeover or disconnect flow is implemented as a session-only step plus one or more per-region lease-guarded steps; it is never a single Lua invocation spanning both key families.

#### Session-to-region bridge scripts

To make the session/region split implementable in cluster mode, Game Session owns a small category of **session-to-region bridge** scripts with these rules:

- They are registered as **region-lease scripts**, not session-only scripts.
- They operate only on region-local keys such as `tick:{tenantRegionTag}:session-binding:<entityId>` plus the region lease key for the same `{tenantRegionTag}`.
- They receive the expected `sessionId` and `binding_generation` via `ARGV` from an already-validated session flow.
- They return explicit non-mutating outcomes such as `"STALE_SESSION_GENERATION"`, `"STALE_LEASE"`, or `"ALREADY_BOUND"` when the requested bridge mutation should not proceed.

This keeps cluster-slot correctness simple: the bridge step never needs to read `session:game:*` directly inside Lua, and the session step never writes region-local gameplay keys.

Registry and ops expectations for this category:

- Registry metadata should identify session-to-region bridge scripts explicitly so CI and reviewers can verify they touch only region-local binding keys plus the region lease.
- Outcome enums should include bridge-specific stale-generation results such as `"STALE_SESSION_GENERATION"` and replay/no-op results such as `"ALREADY_BOUND"` or `"ALREADY_UNBOUND"` where applicable.
- The Redis operations docs and metrics catalog should name these outcomes explicitly so operators can distinguish reconnect/takeover races from lease loss or generic script failures.

#### Maintenance and non-lease scripts

Some maintenance or dev-tools scripts may operate on coordination prefixes outside the normal tick/session flow (for example, inspecting or cleaning up keys for a paused tenant/game-instance/region). These scripts:

- Run only in **maintenance contexts** (for example, dev-tools jobs or coordination reset tooling) and must not be invoked from hot-path gameplay.
- Must respect the same shard-local and key-shape rules as tick scripts (for example, operate on one `{tenantRegionTag}` at a time).
- May skip lease validation **only** when the surrounding tool guarantees that ticks are paused for affected scopes and that no active executor is running; in that case they still:
  - Avoid partial mutations that would violate idempotency or schema assumptions.
  - Prefer to delegate destructive operations (for example, wiping a region) to the shared coordination reset tooling rather than hand-editing keys.

Scripts that do not clearly fit one of these categories should be refactored until their validation story is explicit. Region-lease scripts are the default for tick/coordination flows; session-only scripts and maintenance scripts are narrow exceptions with tighter scope and clearly defined behavior.

### Automation Scripts and Cluster Slotting

Automation-related Redis operations follow stricter slotting rules to avoid `CROSSSLOT` errors and keep coordination boundaries clear:

- Scripts that operate on `automation:queue:{tenantInstanceTag}:*` keys:
  - Use only `automation:queue:{tenantInstanceTag}:*` keys for a single runtime instance scope in `KEYS`; all such keys must share the same `{tenantInstanceTag}` hash tag and Redis Cluster slot.
  - Must not include `tick:*` keys in the same invocation.
- Cross-boundary rules:
  - Automation scripts **never** perform multi-key operations that span both `automation:*` and `tick:*` prefixes in one `EVAL`/`EVALSHA` call.
  - Automation work is projected under `automation:queue:*` and handed off to Game Session via gRPC; only Game Session scripts mutate `tick:*` prefixes.
  - Current live timer rows may expose an optional `dueTickId` alongside the existing due-state fields and derived projections; live Redis scripts must not assume that the tagged `duePoint` migration shape is already present. In target state, fairness-critical automation handoff is idempotent on the full scheduler Trigger Identity: `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, entityId, scriptId, eventType, eventSchemaVersion, scriptPatchVersion, scriptEventId, scheduleDefinitionId, duePoint, isDryRun, triggerMode)`, plus `pluginId` and `pluginVersionId` when applicable. `scriptEventId` is the generated stable identity for the trigger occurrence; it is part of the trigger identity even when a schedule definition also exists. Scheduled rows require exactly one tagged `duePoint`, either `dueTickId:<value>` or `dueAt:<epochMillis>`; both forms, neither form, empty values, and zero placeholders are invalid. Immediate event rows use the distinct event-driven identity branch, omit scheduler-only fields and `duePoint`, and have both physical `dueTickId` and `dueAt` columns explicitly `NULL`. A versioned migration must translate live due fields before enforcing the target tagged form. The per-command `automationDispatchId` plus `commandOrdinal` is a child handoff identity derived from that trigger and must not be replaced by `scriptEventId` or `commandKind` alone.
  - Before invoking the Redis enqueue script, Game Session must insert or confirm a durable admission row keyed by `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, automationDispatchId, commandOrdinal)` in its command/admission ledger. Redis enqueue scripts may treat the dispatch-plus-ordinal identity as an idempotent member key for hot-path dedupe, but the durable admission row and the durable trigger-instance uniqueness projection are the authorities used after resets, gRPC retries, and failover.

CI must reject automation Lua scripts that:

- Reference both `automation:*` and `tick:*` keys in their registry descriptors, or
- Construct `tick:*` keys by hand instead of using shared key builders.

From a correctness perspective, `automation:queue:*` and related automation caches are always treated as **best-effort buffers**:

- Scripts and callers must assume that queued items can be lost, duplicated, or reordered within the bounds described in the Redis hub doc.
- Any automation contract that requires “exactly once” semantics or durable ordering must record its authoritative state in PostgreSQL or another durable store and use `automation:queue:*` only as a convenience layer for scheduling, not as the sole record of work.
- For gameplay-equivalent automation, Automation & Scripting's authoritative due-work record is a durable PostgreSQL trigger-instance or outbox row keyed by the applicable Trigger Identity branch. Game Session's target authoritative admission record is its own durable command/admission row keyed by the full Trigger Identity, target runtime timeline, `automationDispatchId`, and `commandOrdinal`. Game Session treats that dispatch-plus-ordinal child identity as the Redis member-level dedupe key when enqueueing into `tick:{tenantRegionTag}:queue:<entityId>`, but Redis is only the materialized coordination buffer. The live enqueue proto is narrower and must not be described as satisfying this target contract until it carries the missing fields.
- The Redis operations docs and metrics catalog should name stale automation-dispatch outcomes explicitly (for example duplicate-dispatch no-op, stale epoch rejection, stale due-tick rejection) so on-call operators can separate healthy idempotent suppression from broken automation admission.

### Script Complexity and Runtime Limits

To prevent Redis from stalling, tick-related scripts are bounded in keys touched and execution time:

- **Key limit** – each script invocation touches at most one lock key, one `pending` key, and a handful of other coordination keys (`timer`, `retry`, `queue`). Scripts that need to touch large key ranges must be split into smaller, bounded operations or moved out of the hot tick path.
- **Runtime limit** – scripts should finish well within tick latency targets (for example < 10‑20 ms). The registry annotates each script with a `max_execution_ms` hint, observability tracks percentiles, and CI flags scripts that routinely hit their budget.
- **No large scans** – `SCAN`, `HSCAN`, or cursor-based iterations are prohibited in tick scripts; maintenance helpers use those commands outside the tick loop with explicit scope and throttling.

Enforcing these limits prevents future scripts from violating hash-slot assumptions or blocking Redis for other regions.

Bulk key-walking is reserved for **offline maintenance tooling**, not tick execution:

- Long-running maintenance tasks that need to inspect many keys or perform future supported cleanup flows (for example, old locks, timers, or mis-shaped coordination keys) should:
  - Live in dedicated maintenance scripts and dev-tools jobs, not in the hot tick/session loop.
  - Use `SCAN` with strict prefix filters, small batch sizes, and explicit rate limiting or sleeps between batches.
  - Operate on well-scoped prefixes (for example, a single `{tenantRegionTag}` or tenant) rather than scanning the entire keyspace.
- Detailed guidance on maintenance scripts and coordination reset lives in the Redis operations and reset documentation; this Lua patterns doc is focused on **hot-path** behavior.

- **Pattern 1 – Lease/lock token validation (guard-then-no-op)**
  - Every mutating script begins by validating the current lease and, where applicable, lock tokens:
    - Read `tick-executor-lease:{tenantRegionTag}` and compare its stored token to the `leaseToken` passed in `ARGV`.
    - For each entity lock key, compare the stored lock token to the expected token in `ARGV`.
  - If any token does not match, the script returns a **non-mutating outcome** such as `"STALE_LEASE"` or `"STALE_LOCK"` and performs **no writes**. Callers interpret this as “retry under the new lease” rather than as partial progress.
  - Region lease **renewal** uses a small, dedicated compare-and-extend helper that follows the same pattern:
    - Inputs:
      - `KEYS[1] = tick-executor-lease:{tenantRegionTag}`
      - `ARGV[1] = expectedLeaseToken`
      - `ARGV[2] = lease_ttl_ms`
    - Behavior (sketch):
      - Read `KEYS[1]`; if missing or if the stored token does not equal `expectedLeaseToken`, return `"STALE_LEASE"` and perform **no writes**.
      - If the token matches, extend the TTL via `PEXPIRE` (or `SET ... PX ... XX`) and return `"RENEWED"`.
    - Callers treat `"STALE_LEASE"` as loss of leadership for that `<tenantId, gameInstanceId, regionId>` and stop acting as executor, matching the lease semantics described in the Redis architecture document.

- **Pattern 2 – Compare-and-set on `region_epoch` + `tickId` (monotonic guards)**
  - Scripts that touch `tick:{tenantRegionTag}:pending` treat both `region_epoch` and `tickId` as monotonic guards:
    - Inputs include:
      - The expected `region_epoch` in `ARGV` (or encoded into the lease token associated with `tick-executor-lease:{tenantRegionTag}`).
      - The requested `tickId` for the staged work.
    - The script reads the current epoch/tick metadata from the canonical metadata key:
      - `KEYS` must include `tick:{tenantRegionTag}:meta` (a hash as defined in the Redis architecture doc).
      - The script loads `region_epoch` and `current_tick_id` from that hash.
      - The script also loads `current_tick_state` and refuses to advance to a newer tick while the stored state is non-terminal.
    - Behavior (hot-path tick staging/cleanup):
      - If the stored `region_epoch` does not match the expected epoch, the script returns a non-mutating `"STALE_EPOCH"` outcome and does not modify state; callers treat this as “reset or handoff happened, abandon this attempt and reacquire lease under the new epoch”.
      - For hot-path tick execution, callers must only invoke staging/cleanup scripts with `requestedTickId` equal to the scheduler’s current tick for that region (as derived from RegionStatus/ledger); under that assumption:
        - If `current_tick_id` is unset, the script sets `current_tick_id = requestedTickId`, `current_tick_state = STAGED`, and stages new effects.
        - If `current_tick_id == requestedTickId` and `current_tick_state in {STAGED, RESOLVING}`, the script proceeds and treats existing effect entries as already staged (see Pattern 3).
        - If `current_tick_id < requestedTickId`, the script may only advance to the newer tick when the stored `current_tick_state` is terminal (`APPLIED` or `ABANDONED`) and `requestedTickId` is the immediate next scheduler tick for that region.
        - If `current_tick_id > requestedTickId`, or if the stored state for an older tick is non-terminal, the script returns a non-mutating “out-of-date” outcome and does not modify state; callers must not attempt to re-stage older ticks through these hot-path scripts and should instead rely on ledger-driven replay/maintenance flows to reconcile older work.
      - Recovery rule:
        - These hot-path scripts are intentionally **not** the mechanism for reconstructing a lost older tick after tail-loss or reset.
        - First implementation replays older work directly from durable tick-batch manifests and ledger rows without re-materializing that old tick into `pending`.
        - The caller records the older tick as `APPLIED` or `ABANDONED` in `tick:{tenantRegionTag}:meta` only after the durable ledger/reconciliation state is terminal; Redis `pending` contents alone are never sufficient evidence that the region may move on.
        - If FireMUD later introduces a dedicated recovery-restage script, it must be registered as a separate maintenance script category with its own explicit invariants, compatibility mode, and runbook entry; it must not silently reuse the normal tick staging contract.
        - Such a recovery-restage path is not considered specified or implementable until the corresponding maintenance runbook in the Redis operations docs names the entrypoint, scope restrictions, and post-run verification steps.

- **Pattern 3 – Effect-key sets for staging (no duplicate staging)**
  - Staged effects inside `pending` are keyed by a deterministic `effectKey` (for example `entity:<entityId>:apply:damage:<commandId>`), and scripts use **set-style semantics**:
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
      - The Redis keyspace is unchanged by the second invocation for all outcomes documented as non-mutating (including no TTL refreshes, counters, or other Redis writes).
  - For scripts that enqueue items, tests also cover the “replay after partial success” case: pre-populate keys to simulate a partially completed first run, then re-invoke the script and confirm it **does not** add duplicate entries or regress state.

New tick-related scripts are expected to adopt these patterns (or motivated variants) and include tests that prove re-invocation safety before they are accepted.

## Worked Example: Simple Lock-Acquire Script

As a concrete illustration, a simplified lock-acquire Lua script follows these patterns:

- **Inputs**
  - `KEYS[1]` – `tick:{tenantRegionTag}:lock:<entityId>`
  - `ARGV[1]` – `lockToken`
  - `ARGV[2]` – `leaseToken`
  - `KEYS[2]` – `tick-executor-lease:{tenantRegionTag}` (optional, when validating lease)

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

## Script Rollout Compatibility and Reset Sensitivity

Script changes must be rolled out in a way that respects both AOF replay semantics and the reset model described in `system-architecture-redis-reset-and-recovery.md`:

- **Compatibility levels**
  - `compatible` – purely additive changes (for example, new return fields, extra read-only validations) that do not change key shape or semantics. These may be rolled out without coordination resets as long as callers tolerate older results.
  - `requires_region_reset` / `requires_tenant_reset` / `requires_cluster_reset` – changes that alter key shapes or semantics in ways that cannot be safely mixed with old behavior. These must run alongside the corresponding coordination reset flows and are expected to be rare.
  - If a rollout requires an epoch boundary to avoid mixed old/new semantics, classify it as the corresponding `requires_*_reset` scope and execute the full reset handshake (pause ticks → bump `region_epoch` → reconcile ledger → resume). Do not treat `region_epoch` bumps as a routine rollout mechanism outside this compatibility contract.
- **Registry as the source of truth**
  - The Lua Script Registry records a `compatibility_level` (or equivalent) and `reset_sensitivity` for each script and version.
  - Upgrades that move a script into a stricter compatibility level must be reflected in the registry before rollout, and their expected reset scope must be documented in design docs and runbooks.
- **AOF replay and schemaVersion**
  - `schemaVersion` changes must be backwards compatible for at least `N-1` versions, and scripts must treat unknown versions as non-mutating (`"UNSUPPORTED_SCHEMA_VERSION"`) so AOF replay cannot apply effects with mismatched schemas.
  - When a reset-required change is introduced, operators follow the reset runbooks so that any surviving AOF history for old scripts is discarded for the relevant scope rather than replayed under incompatible semantics.

## Lua Script Registry and CI Expectations

All coordination-related Lua scripts live in a **Lua Script Registry** in the shared library. For each script, the registry records:

- Script identifier and file path.
- Expected `KEYS` and `ARGV` ordering and allowed prefixes (including hash-tag rules).
- Script category (for example, tick lock, timer queue, session CAS) and reset-tolerance assumptions.
- The Redis role the script is allowed to target (for coordination scripts this is strictly `coordination`; they must never reference Cache/Rate-Limit prefixes such as `inventory:*`, `view:*`, `ratelimit:*`, or `automation:queue:*`).
- Reset and tail-loss metadata:
  - `reset_sensitivity` describing which reset scopes (region, tenant, cluster) must be considered when changing script behavior or key shape.
  - `tail_loss_behavior` describing what is expected to happen if the script’s writes are lost or replayed within the tail-loss envelope (for example “pure lease; safe to lose”, “can enqueue duplicates; relies on domain idempotency”, “must not silently drop without a corresponding ledger row”).
  - Shard-locality metadata for multi-key scripts, including whether all `KEYS` must share the same `{tenantRegionTag}`, `{tenantInstanceTag}`, or `{tenantGameplayTag}` hash tag and slot.

The registry descriptors are sufficient to **drive a generic test harness**: any coordination script must be invokable in isolation using only the registry metadata (script identifier, expected `KEYS`/`ARGV`, and allowed prefixes). Callers must not hard-code key names or slots that diverge from the registry.

CI enforces the following invariants for registered scripts:

- Every Lua file under the coordination scripting path has a corresponding registry entry; unregistered scripts fail CI.
- Scripts pass determinism checks (no disallowed commands such as `TIME` and no RNG usage) and are covered by idempotency tests similar to the patterns in this document.
- Registry metadata is validated against tests so that `KEYS`/`ARGV` expectations stay in sync with callers.
- Registry entries for coordination scripts are rejected if:
  - They declare a Redis role other than `coordination`, or
  - They reference prefixes that belong to Cache/Rate-Limit Redis, or
  - They omit required `reset_sensitivity` / `tail_loss_behavior` metadata.
- For scripts that declare shard-local multi-key behavior, CI verifies that:
  - All declared `KEYS` share the same hash tag (for example `{tenantRegionTag}`), and
  - No script attempts cross-slot operations under the `coordination` role.

## Call-Side Time and Randomness Contract

Determinism requirements forbid time and randomness inside Lua itself. Callers must:

- Generate any required timestamps or random tokens in application code and pass them via `ARGV`.
- Treat those values as **stable inputs**: AOF replay reuses the same `ARGV` so repeated executions see identical arguments.
- Avoid embedding current time or random suffixes in Redis key names from inside Lua; any such keys must be constructed by callers and passed in through `KEYS`.
- For timer scripts, pass `now_ms` (or equivalent) via `ARGV` and treat it as the sole time source for comparisons; scripts must not invoke Redis `TIME`.
- For retry scripts, pass tick-timeline inputs (for example `current_tick_id`, `next_eligible_tick_id`, and expected `region_epoch`) via `ARGV`; retry eligibility is based on tick IDs rather than wall-clock time.

CI and code review treat violations of this contract (for example, new scripts that invoke `TIME` or synthesize random IDs) as blocking issues.

## Related Documentation

- [System Architecture: Redis](./system-architecture-redis.md)
- [System Architecture: Redis Cache & Rate Limiting](./system-architecture-redis-cache.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Transaction Strategies](./system-architecture-transactions.md)

# FireMUD Redis Operations & Migrations

This document captures **operational runbooks and migration procedures** for Coordination Redis. It complements the conceptual guarantees in `system-architecture-redis.md` and the Lua authoring patterns in `system-architecture-redis-lua-patterns.md`.

The invariants and contracts in `system-architecture-redis.md` remain authoritative. This file focuses on **“when X happens, do 1–2–3”** guidance.

## Table of Contents

- [FireMUD Redis Operations & Migrations](#firemud-redis-operations--migrations)
  - [AOF Size and Restart Budget](#aof-size-and-restart-budget)
  - [Lua Compatibility Registry & Script Upgrades](#lua-compatibility-registry--script-upgrades)
  - [Replica Promotion and Missed Writes](#replica-promotion-and-missed-writes)
  - [Key Shape Mistakes and Coordination Resets](#key-shape-mistakes-and-coordination-resets)
  - [Normalization and Hash-Tag Migration](#normalization-and-hash-tag-migration)

---

## AOF Size and Restart Budget

**Goal:** Keep Coordination Redis restart behavior predictable and avoid unbounded AOF growth.

### Targets

- Soft AOF size limit per node: **1–2 GiB** for small/self-hosted deployments; larger clusters may accept proportionally larger files if restart budgets remain within the targets below.
- Typical restart time (AOF or RDB+AOF replay): **30–60 seconds** during planned maintenance for Coordination Redis nodes.
- Effective daily AOF growth for coordination workloads should normally stay below **~250–500 MiB/day** per node in steady state; sustained growth beyond that envelope warrants investigation.

These targets are enforced via a small set of metrics and dashboards:

- Metrics:
  - `redis_aof_current_size_bytes` (or equivalent from `INFO persistence`) per Coordination Redis node.
  - `redis_aof_rewrite_in_progress` and `redis_aof_rewrite_time_sec` to monitor rewrite behavior.
  - `redis_coordinator_restart_duration_seconds` (custom) emitted by coordination maintenance jobs during controlled restarts.
  - `redis_coordination_aof_growth_bytes_total` (custom) derived from periodic samples of AOF size to approximate daily growth.
- Dashboards:
  - An **AOF health panel** that plots AOF size per node, restart duration (where measured), and daily growth estimates against the soft limits above.
  - A **coordination capacity panel** that correlates AOF size with coordination key counts and per-region footprints so operators can see which regions or tenants are contributing to growth.

Operators should wire alerts directly to these metrics (for example, warn when AOF size crosses the soft limit, or when restart duration and growth remain above targets for several days) rather than relying on ad-hoc `INFO` calls.

Recommended AOF configuration profiles tie these targets back to concrete Redis settings:

| Profile | Use Case | Persistence Settings (example) | Notes |
| --- | --- | --- | --- |
| `dev_local` | Single-developer, non-player-facing experiments | `appendonly yes`, `appendfsync everysec`, `aof-use-rdb-preamble yes`, small `maxmemory` tuned for laptop resources | Tail-loss and restart time are less critical; AOF is primarily for debugging. Coordination SLOs do not apply. |
| `hobby_self_hosted` | Small/self-hosted games with real players | `appendonly yes`, `appendfsync everysec` (or `no` with careful risk acceptance), `aof-use-rdb-preamble yes`, `maxmemory` sized to keep AOF replay within the 30–60s target and coordination keys well under memory caps | This profile is expected to honor the AOF size and restart budgets in this section. Tail-loss envelopes in the main Redis doc assume configurations in this tier or better. |
| `production_clustered` | Multi-tenant or higher-scale deployments | `appendonly yes`, `appendfsync everysec` (or platform-recommended fsync policy), `aof-use-rdb-preamble yes`, coordinated `maxmemory` and shard sizing so per-node AOF size and restart times stay within agreed budgets | Platform SLOs for coordination availability and replay are evaluated against this profile; deviation (for example, disabling AOF) must be treated as an explicit architectural change. |

Concrete values may be tuned per environment, but deployments should always document which profile they approximate and ensure that observability dashboards validate AOF size and restart behavior against the chosen profile’s expectations.

### Runbook: AOF too large or restarts too slow

1. Confirm via metrics or `INFO`:
   - AOF size substantially exceeds the soft limit for the profile you are running (for example, > 2 GiB on a small self-hosted node), or
   - Restart time is routinely above 60 seconds for Coordination Redis nodes, or
   - Daily AOF growth is consistently above ~500 MiB/day per node without a clear explanation (for example, a deliberate large-scale test).
2. Schedule a maintenance window.
3. Stop game services for affected tenants/regions (or globally for a small/self-hosted deployment).
4. Reset Coordination Redis:
   - Stop Redis.
   - Delete or recreate the volume that holds the AOF.
   - Start Redis with an empty keyspace and the desired AOF configuration (`appendonly yes`, `appendfsync everysec`, `aof-use-rdb-preamble yes`, etc.).
5. Resume ticks and player traffic once services are healthy.
   - Expect players to re-login or restart games.
   - Coordination state is rebuilt from PostgreSQL and fresh gameplay activity.

If metrics show **spiky but short-lived** AOF growth (for example, a load test that briefly increases AOF by a few hundred MiB and then stabilizes), you may choose to defer a reset until the next planned maintenance window. Sustained, unexplained growth or restart times outside the budget should be treated as signals of either:

- Misuse of Coordination Redis (for example, using it as a general-purpose cache or log), or
- A need to raise capacity or move to a more appropriate Redis profile as described in the main Redis architecture doc.

Manual AOF “surgery” is **not supported**. Either the AOF is trusted and replayed as-is, or it is discarded and Redis restarts from a clean keyspace.

### Rule-of-Thumb Coordination Capacity per Region

**Goal:** Give operators a simple mental model for when a single `<tenantId, regionId>` is likely exceeding healthy coordination usage.

These are approximate guidelines for typical tick intervals (for example `tick_interval_ms >= 250`) and modestly sized worlds on a small/self-hosted deployment. Larger clusters with more memory and CPU can scale beyond these values, but **ratios and trends** remain useful signals.

- **Per-region coordination footprint (steady state)**
  - Active entity locks: typically **≤ a few hundred** per region; spikes are expected during busy ticks but should not remain at thousands for long durations.
  - Pending tick entries: on the order of **a few ticks worth of work**, not thousands of uncommitted `pending` entries.
  - Timers and retry queue items: typically **≤ tens of thousands** per busy region; consistently higher counts indicate that timers or retries are being used as general-purpose data stores.
  - Session keys: roughly **one key per active session** for that region, expiring when sessions end or age out.
- **Operator guidance**
  - If a single `<tenantId, regionId>` routinely exceeds these envelopes and is responsible for a disproportionate share of memory usage or AOF growth:
    - Review gameplay and automation features for that tenant/region to ensure they are not using Coordination Redis for long-lived data.
    - Consider applying per-tenant caps on active regions, sessions, timers, or queued commands so coordination footprints remain bounded.
    - If mis-keyed or runaway coordination state is suspected, use the relevant coordination reset runbooks (either per-region or per-tenant) to drop volatile state and rebuild from PostgreSQL.

These rules of thumb are intentionally conservative. For a single-admin hobby deployment, they help distinguish “normal busy evening” from “this one tenant/region is using Redis in a way the architecture did not intend”.

---

### Runbook: Explicit Coordination Reset

**Goal:** Provide a single, clear mechanism for deliberately starting Coordination Redis from an empty keyspace while keeping the normal posture “AOF persists across rollouts”.

This reset is intentionally **rare** – it is used for controlled scenarios such as:

- Validating reset-tolerant behavior in a test or preview environment.
- Recovering from mis-keyed coordination prefixes where dropping state is acceptable.
- Applying a `breaking_requires_reset` Lua change when the upgrade planner indicates a reset is required.

The steps mirror the “AOF too large” runbook but are driven by operator intent rather than metric thresholds:

1. **Plan scope**
   - Decide whether the reset applies:
     - To a whole Coordination Redis deployment (dev, small clusters), or
     - To one logical deployment / tenant subset in larger setups (for example, a specific Coordination Redis instance per environment or shard).
   - Confirm that all affected workloads are classified as **reset-tolerant** in the main Redis architecture doc; do not use this runbook for prefixes marked reset-sensitive or reset-forbidden.
   - Verify that **Coordination Redis and Cache/Rate-Limit Redis are distinct deployments**. Coordination reset tooling and jobs must refuse to run if `FIREMUD_REDIS_COORD_HOST:PORT == FIREMUD_REDIS_CACHE_HOST:PORT`, since a reset in that topology would also discard cache/rate-limit state and violate the role separation guarantees.
2. **Quiesce gameplay**
   - Pause ticks and stop accepting new gameplay commands for the affected scope using the Game Session admin/control APIs (or by shutting down dependent services for small/self-hosted installs).
   - Wait for in-flight requests to drain; regions should stop advancing and no new `pending` entries should be created.
3. **Run the reset tooling**
   - For Kubernetes/Helm deployments:
     - Run the coordination-reset Job or script provided with the charts (for example, the `redis-aof-reset` Job under `charts/firemud/templates/redis-aof-reset-job.yaml`), which:
       - Stops or disconnects the target Redis instance.
       - Deletes or recreates the PersistentVolume/volume contents that hold the AOF.
       - Restarts Redis with the desired AOF configuration (`appendonly yes`, `appendfsync everysec`, `aof-use-rdb-preamble yes`, etc.).
   - For local dev / Docker Compose:
     - Use the dedicated Gradle task or helper script (for example, `./gradlew devRedisReset` once implemented) that:
       - Stops the dev stack.
       - Clears the Redis data directory/volume used for Coordination Redis.
       - Restarts the stack so Redis comes up with an empty coordination keyspace.
4. **Verify health**
   - Ensure Coordination Redis is reachable and scripts preload successfully (no persistent `NOSCRIPT` errors).
   - Run a lightweight smoke test:
     - Schedule a tick for a test region.
     - Confirm that locks, `pending`, and timers can be created and cleared as expected.
5. **Resume gameplay**
   - Unpause ticks and re-enable command intake for the affected tenants/regions.
   - Expect players to re-login or restart games; coordination state (locks, queues, timers, sessions) is rebuilt from PostgreSQL and fresh tick activity.

Normal Helm upgrades and restarts **do not** run this reset by default. The reset is always an explicit, operator-driven action guarded by this runbook so that “AOF persists across rollouts” remains the common case.

## Reset Tolerance Classes

Not all Redis-backed workloads tolerate coordination resets equally. FireMUD therefore classifies coordination prefixes and features by **reset tolerance**:

- **Reset-tolerant** – workloads that can safely discard their Redis state and rebuild from PostgreSQL and fresh activity using the idempotent replay rules:
  - Tick locks, `pending` entries, timers, retry queues, and conflict metadata.
  - Short-lived gameplay sessions that rely on Redis only for reconnection windows and volatile state.
- **Reset-sensitive** – workloads where a reset is acceptable but has visible impact and may require explicit operator sign-off or tenant scoping:
  - Certain automation queues or non-critical analytics that can be recomputed or re-enqueued.
- **Reset-forbidden** – workloads that must not be dropped by generic coordination reset tooling:
  - Future features that treat Redis as a durable component of a long-lived contract (for example, replay timelines, high-value automation contracts, or analytics streams that cannot be recomputed).

Today, Coordination Redis is intentionally used only for reset-tolerant workloads. Any new feature that wants to use Coordination Redis must explicitly declare its reset tolerance class in design docs and, where necessary, use:

- Separate deployments or prefixes with their own runbooks, or
- Stronger durable stores (for example PostgreSQL or Kafka) as the primary record of long-lived streams, with Redis limited to cache/index roles that remain reset-tolerant.

Reset tooling and runbooks in this document apply **only** to reset-tolerant workloads unless explicitly noted.

## Session TTL & Reset Operator Flows

Session lifetimes and coordination resets interact in predictable ways. The table below captures recommended operator flows for common scenarios; refer to [Environment & Secrets – Authentication Variables](./infrastructure/environment-and-secrets.md#authentication) and [Redis Architecture – Session Keys and Gameplay Binding](./system-architecture-redis.md#session-keys-and-gameplay-binding) for full details.

| Scenario | Steps | Redis impact | Player behavior | Optional cleanup |
| --- | --- | --- | --- | --- |
| Decrease JWT/session TTL without reset | Lower `FIREMUD_AUTH_JWT_EXPIRATION_MS` and/or `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`, roll out services. | New `session:<tenantId>:<sessionId>` and `session:<tenantId>:<tokenHash>` keys get shorter TTLs; existing keys keep their original TTL until they expire naturally. | Existing sessions continue until their original TTLs; new logins and reconnects enforce the tighter lifetime immediately. | Not required. Operators may optionally run per‑tenant session cleanup (delete `session:<tenantId>:*`) to accelerate convergence if memory is tight. |
| Decrease JWT/session TTL and intentionally force reconnect | Same as above, then proactively delete session keys for selected tenants/regions (for example, `session:<tenantId>:*`) during a maintenance window. | Coordination Redis drops affected session keys immediately; memory for those sessions is reclaimed at once. | All affected players must log in again; reconnect attempts for deleted sessions behave like expired sessions. | Recommended when making a large TTL reduction and wanting a clean cut-over or when reclaiming session memory quickly. |
| Coordination reset with many active sessions | Follow the **Explicit Coordination Reset** runbook for the targeted scope; all coordination prefixes, including `session:*`, are dropped for the affected deployment/regions. | Coordination Redis restarts with an empty keyspace for coordination prefixes (locks, timers, queues, `session:*`, etc.). | All gameplay sessions are effectively terminated; players must log in again and sessions are recreated under the new coordination state. | No separate session cleanup is needed; the reset itself drops `session:*` keys. Operators should communicate expected reconnect behavior to players and monitor memory/latency as sessions rebuild. |

### Cluster-Safe Session Cleanup Procedure (No `KEYS`)

When runbooks call for per-tenant session cleanup (for example, deleting `session:<tenantId>:*` to accelerate a TTL cut-over), the procedure must be safe for Redis Cluster and large keyspaces:

- Never use `KEYS session:<tenantId>:*`.
- Iterate over **each master node** and run `SCAN` with `MATCH session:<tenantId>:*` using small `COUNT` values, strict time budgets per run (for example 10–30 seconds), and rate limiting between batches.
- Delete via `UNLINK` (preferred) to keep deletions non-blocking; fall back to `DEL` only when `UNLINK` is unavailable.
- Treat cleanup as a maintenance job: run one worker at a time per deployment/tenant, emit metrics, and stop early if Redis latency is elevated.

## Lua Compatibility Registry & Script Upgrades

**Goal:** Roll out Lua script changes safely, knowing when coordination state must be reset.

### Inputs

The **Lua Compatibility Registry** lives in the shared `firemud-common` module alongside key builders and Lua descriptors. It is owned by the **platform/coordination maintainers** (not individual services) and declares, per script:

- `schemaVersionsSupported`.
- `KEYS`/`ARGV` contract.
- A compatibility tag and rationale:
  - `compatible` – the new script is **behavior-preserving** for all `(KEYS, ARGV, Redis state)` combinations produced by current services and supported `schemaVersion` values.
  - `breaking_requires_reset` – the new script **changes behavior** for any existing state or inputs (including AOF replay of old calls) and therefore requires a coordination reset or an explicit multi-version/migration strategy.
- Optional metadata such as:
  - A brief **compatibility rationale** (for example, “refactor only; behavior verified via golden tests”).
  - The minimum/maximum `schemaVersion` values known to exist in production deployments.

For the purposes of this registry, **`compatible` is intentionally narrow**:

- It does **not** allow changes that:
  - Alter return codes for any valid input.
  - Turn a previous no-op into a mutating path (or vice versa).
  - Change how existing `schemaVersion` payloads are interpreted when replayed from AOF.
- It only allows:
  - Internal refactors that preserve both return values and key mutations.
  - Additional observability (metrics, logs) that does not affect control flow.
  - Targeted bug fixes where the previous behavior was already *outside* the documented contract; such fixes must be explicitly called out in the rationale.

All other changes must be tagged `breaking_requires_reset` or accompanied by explicit multi-version handling and data migration for affected keys.

### Runbook: Upgrading scripts

1. Classify changes:
   - For each modified script, decide whether the change is intended to be behavior-preserving (`compatible`) or intentionally changes semantics (`breaking_requires_reset` or multi-version).
   - For any script tagged `compatible`:
     - Update the registry rationale to describe why it is compatible.
     - Add or update **compatibility tests** in `firemud-common` that exercise a representative set of Redis fixtures (including edge cases and partially applied states) and prove that running the old script vs the new script with the same `(KEYS, ARGV)` yields identical:
       - Return values, and
       - Key mutations for all keys in the script’s descriptor.
     - CI must run these golden tests and fail if any observable behavior diverges.
   - For scripts tagged `breaking_requires_reset` or scripts that introduce multi-version handling, document the upgrade expectations in the registry (for example, “v2 adds support for schemaVersion=3; old data must be drained or migrated before support for schemaVersion=1 is removed”).
2. Run the **coordination upgrade planner** (dev-tools):
   - Compares the current deployment’s registry to the target version.
   - Reports whether a **coordination reset** is required based on scripts tagged `breaking_requires_reset` and any recorded multi-version windows that have closed.
3. If all changes are `compatible` and their compatibility tests pass:
   - Deploy new scripts and services as part of the normal rollout.
   - Rely on existing `NOSCRIPT` handling and Lua preload behavior.
4. If any script is `breaking_requires_reset` (and not covered by a live multi-version strategy):
   - Use the upgrade planner’s reset plan:
     1. Pause ticks and stop accepting new gameplay commands globally (or for the affected tenants/regions).
     2. Stop the Coordination Redis instance (or logical deployment) used for ticks and sessions.
     3. Delete or recreate the volume that holds its AOF.
     4. Restart Redis with an empty keyspace.
     5. Run a lightweight health check (scripts load successfully; test ticks can be scheduled).
     6. Unpause ticks and player traffic.
   - Optionally, advanced operators may:
     - Stand up a **new** Coordination Redis instance with an empty AOF.
     - Point application services at it.
     - Decommission the old instance after inspection.
5. Confirm reset safety before resuming:
   - Verify the tick effect ledger reports no `SCHEDULED` rows for the affected `(tenantId, regionId)` pairs.
   - Ensure any in-flight commands are either retried or marked `ABANDONED` at the domain layer.

The registry, together with its golden compatibility tests in `firemud-common`, remains the **single source of truth** for whether coordination state can be safely replayed across script versions (`compatible`) or must be reset (`breaking_requires_reset` or explicit migration).

---

## Replica Promotion and Missed Writes

**Goal:** Handle Redis replica promotion without violating tick/replay guarantees.

### Facts

- Coordination Redis uses **asynchronous replication**.
- A promoted replica may be missing recent coordination writes.
- The **new primary’s keyspace is authoritative** after promotion.

### Behavior

- Promotion from a replica with modest lag is equivalent to a small AOF tail-loss window:
  - Missing keys are treated as if they never existed.
  - Ticks/retries/timers are re-enqueued from surviving state/PostgreSQL or skipped within the accepted tail-loss envelope.
- Replay safety is preserved because:
  - Mutating scripts validate lease tokens, lock tokens, `tickId`, and `generation` before writing.
  - The tick effect ledger and idempotency guards in PostgreSQL remain the source of truth for “has this effect applied?”

### Lag envelopes (tie lag to tick interval)

- **Target:** p99 replication lag < ~0.25 × `tick_interval_ms`.
- **Warning:** sustained lag between ~0.25 × and 1.0 × `tick_interval_ms`.
- **Red line:** lag ≥ 1× `tick_interval_ms` for a shard.

### Runbook: Promotion decisions

1. Monitor replication lag via metrics (see Observability section in the main Redis doc).
2. If lag is within the **target** envelope:
   - Automatic or manual promotion is acceptable from a replay perspective; expect at most ~one tick of lost coordination state.
3. If lag is in the **warning** band:
   - Investigate underlying causes (capacity, network, slow scripts) and consider delaying promotions until lag recovers.
4. If lag crosses the **red line** for a shard:
   - Avoid automatic promotion from that replica.
   - Either:
     - Wait for lag to return to the target envelope, or
     - Treat promotion as a **deliberate “drop recent coordination state” event**:
       - Pause ticks for affected tenants/regions.
       - Promote the replica.
       - Use coordination reset tooling to rebuild state from PostgreSQL and new activity.

Smaller self-hosted deployments may prefer a single primary (with an optional replica only for observability/manual promotion) and treat any promotion with significant lag as equivalent to resetting coordination state for affected regions.

---

## Key Shape Mistakes and Coordination Resets

**Goal:** Remediate mis-keyed or mis-sharded coordination keys without complex in-place surgery.

**Coordination keys** (`tick:*`, `timer:*`, `retry:*`, `remote:*`, leases, and tick-related locks) are treated as reset-tolerant, volatile, and backed by PostgreSQL + replay.

Before performing any coordination reset (region/tenant/cluster scope), operators should walk a short **pre-reset validation checklist**:

- Confirm that PostgreSQL is healthy:
  - Core tables for tick coordination (for example, tick effect ledger, `coordination_meta`/leadership tables) are reachable and not reporting corruption or constraint failures.
  - Saga state for workflows that depend on coordination (for example, game startup/shutdown) is in a consistent state or can tolerate replay from the last committed step.
- Verify tick effect ledger status for the target scope:
  - No `SCHEDULED` or `IN_PROGRESS` tick effects remain that would be orphaned by dropping coordination state, or such effects are explicitly marked as `ABANDONED`/resolved at the domain layer.
- Ensure game traffic is quiesced for the affected scope:
  - Tick scheduling and new command intake are paused for the relevant `<tenantId, regionId>` or tenants.
  - Any long-running maintenance or backfill jobs that depend on coordination keys are stopped.
- Record operator intent:
  - Capture which tenants/regions are being reset, why the reset is needed, and which Redis deployment/role is affected, so the action is auditable alongside normal break-glass events.

Only after these checks pass should a reset proceed; if any item cannot be satisfied, treat the situation as an incident and resolve the underlying domain/database issues before discarding coordination state.

### Runbook: Mis-sharded coordination keys

1. Detect the issue:
   - Hash-tag or key-shape mistakes discovered via CI, logs, or metrics (for example, CROSSSLOT errors or inconsistent region placement).
2. Pause affected workloads:
   - Pause tick scheduling globally or for affected tenants/regions.
3. Reset Coordination Redis for the affected scope:
   - For node-wide issues: reset the Coordination Redis keyspace (per-node) using the AOF reset procedure, but only for reset-tolerant prefixes.
   - For tenant/region-local issues: use tooling that targets only those prefixes or logical databases; reset-sensitive workloads may require additional confirmation or tenant scoping, and reset-forbidden workloads must be excluded.
4. Resume ticks and sessions:
   - Allow coordination keys to rebuild naturally from PostgreSQL and fresh gameplay activity.

Fine-grained, live migration of mis-sharded coordination keys is **not** the default; it is considered an advanced, optional extension when dropping coordination state is unacceptable for particular tenants.

## Dual-Leader Detection & Coordination Reset

**Goal:** Detect Redis split-brain or conflicting primaries for coordination slots and recover via a coordinated reset before duplicate logical effects can escape the tick subsystem.

### Signals

- Repeated `STALE_LEASE`, `UNSUPPORTED_EPOCH`, or other Lua script responses that reference inconsistent `region_epoch` values for the same `<tenantId, regionId>`.
- PostgreSQL epoch validation rejecting writes because a second executor attempted to bump the same `coordination_meta` row with an older epoch.
- Redis/Sentinel/Cluster alerts showing simultaneous primaries for the same hash slot or other signs of split-brain.

### Runbook (control-plane implementation)

1. The Logging & Admin Service (or a future dedicated coordination manager) pauses tick scheduling for the affected `<tenantId, regionId>` pairs via Game Session’s admin/control APIs (or globally if multiple slots are impacted).
2. It verifies, using Postgres and Redis health APIs/metrics, that the coordination metadata table’s `region_epoch` reflects the highest-authoritative epoch and that Redis has converged to a single primary for the impacted slots.
3. It uses the coordination reset tooling:
   - Stop Redis or fail over to a clean node if necessary.
   - Delete or recreate the AOF volume so the keyspace resets.
   - Restart Redis, preload scripts, and allow the Game Session Service to acquire the new epoch.
4. Clear or reconcile any stale metadata (locks, pending entries) in PostgreSQL if required.
5. Resume ticks via the same control APIs only once Redis, PostgreSQL, and the epoch metadata are consistent to guarantee a single executor is in charge again.

Treat split-brain as an operational incident, not a service-level retry: the reset deliberately drops volatile coordination state and rebuilds it from PostgreSQL so that the single-authority invariant is re-established before gameplay continues. The Logging & Admin control plane is expected to automate these steps for narrow, clearly diagnosed cases (for example, per-region incidents) while still surfacing alerts and audit logs for operators.

---

## Normalization and Hash-Tag Migration

**Goal:** Change how `tenantId` / `regionId` normalization and hash tags are formed without breaking shard-local assumptions.

See the “Hash Tag Normalization” section in `system-architecture-redis.md` for the conceptual contract. This section focuses on the operational steps.

### Runbook: Normalization migration via reset (simplest path)

1. Plan the change:
   - Implement the new normalization version (for example `NORMALIZATION_V2`) in shared helpers.
   - Ensure the new normalization keeps `<tenantId, regionId>` stable and valid.
2. Schedule a maintenance window.
3. Pause ticks and stop accepting new commands for affected tenants/regions (or globally).
4. Deploy services using the new normalization helpers.
5. Start a fresh Coordination Redis deployment (or logical database) with an empty keyspace:
   - Point Game Session and other coordination clients at the new deployment.
   - Do not attempt to migrate existing coordination keys.
6. Resume ticks and player traffic:
   - Existing game instances may require restart or reconnection.
   - Coordination state is rebuilt from PostgreSQL and new activity.

### Runbook: In-place normalization migration (advanced option)

When dropping all coordination state is not acceptable, operators may implement a dedicated migration tool using the shared key builders:

1. Freeze topology:
   - Avoid Redis Cluster resharding during the normalization migration.
2. Pause or drain ticks and new commands for affected tenants/regions.
3. Migrate keys:
   - For each affected prefix, rewrite keys from old hash tags to new ones using a maintenance tool that:
     - Operates on explicit prefixes (no full-keyspace scans).
     - Preserves values and semantics across the move.
4. Validate:
   - Confirm that keys for a given `<tenantId, regionId>` share the expected hash tag.
   - Run smoke tests to verify tick and session behavior.
5. Resume ticks and commands.
6. Separately, perform any required Redis Cluster resharding as a **later, independent maintenance** once normalization is stable.

For most self-hosted deployments, the **reset-based migration** is preferred. In-place migrations are reserved for cases where coordination state for specific tenants cannot simply be dropped.

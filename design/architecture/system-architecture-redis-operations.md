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

- Soft AOF size limit per node: **1–2 GiB**.
- Typical restart time (AOF or RDB+AOF replay): **30–60 seconds**.

### Runbook: AOF too large or restarts too slow

1. Confirm via metrics or `INFO`:
   - AOF size substantially exceeds the soft limit, or
   - Restart time is routinely above 60 seconds.
2. Schedule a maintenance window.
3. Stop game services for affected tenants/regions (or globally for a single-node deployment).
4. Reset Coordination Redis:
   - Stop Redis.
   - Delete or recreate the volume that holds the AOF.
   - Start Redis with an empty keyspace and the desired AOF configuration (`appendonly yes`, `appendfsync everysec`, `aof-use-rdb-preamble yes`, etc.).
5. Resume ticks and player traffic once services are healthy.
   - Expect players to re-login or restart games.
   - Coordination state is rebuilt from PostgreSQL and fresh gameplay activity.

Manual AOF “surgery” is **not supported**. Either the AOF is trusted and replayed as-is, or it is discarded and Redis restarts from a clean keyspace.

---

## Lua Compatibility Registry & Script Upgrades

**Goal:** Roll out Lua script changes safely, knowing when coordination state must be reset.

### Inputs

- The **Lua Compatibility Registry** (in `firemud-common`) declares, per script:
  - `schemaVersionsSupported`.
  - `KEYS`/`ARGV` contract.
  - A compatibility tag: `compatible` or `breaking_requires_reset`.

### Runbook: Upgrading scripts

1. Classify changes:
   - Tag each modified script as `compatible` or `breaking_requires_reset` based on the registry rules (no change to KEYS/ARGV shape, semantics, or supported schemas for `compatible`).
2. Run the **coordination upgrade planner** (dev-tools):
   - Compares the current deployment’s registry to the target version.
   - Reports whether a **coordination reset** is required.
3. If all changes are `compatible`:
   - Deploy new scripts and services as part of the normal rollout.
   - Rely on existing `NOSCRIPT` handling and Lua preload behavior.
4. If any script is `breaking_requires_reset`:
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

The registry remains the **single source of truth** for whether coordination state can be safely replayed across script versions (`compatible`) or must be reset (`breaking_requires_reset`).

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

**Coordination keys** (`tick:*`, `timer:*`, `retry:*`, `remote:*`, leases, and tick-related locks) are treated as volatile and backed by PostgreSQL + replay.

### Runbook: Mis-sharded coordination keys

1. Detect the issue:
   - Hash-tag or key-shape mistakes discovered via CI, logs, or metrics (for example, CROSSSLOT errors or inconsistent region placement).
2. Pause affected workloads:
   - Pause tick scheduling globally or for affected tenants/regions.
3. Reset Coordination Redis for the affected scope:
   - For node-wide issues: reset the Coordination Redis keyspace (per-node) using the AOF reset procedure.
   - For tenant/region-local issues: use tooling that targets only those prefixes or logical databases, if available.
4. Resume ticks and sessions:
   - Allow coordination keys to rebuild naturally from PostgreSQL and fresh gameplay activity.

Fine-grained, live migration of mis-sharded coordination keys is **not** the default; it is considered an advanced, optional extension when dropping coordination state is unacceptable for particular tenants.

---

## Normalization and Hash-Tag Migration

**Goal:** Change how `tenantId` / `regionId` normalization and hash tags are formed without breaking shard-local assumptions.

See the “Hash Tag Normalization” section in `system-architecture-redis.md` for the conceptual contract. This section focuses on the operational steps.

### Runbook: Normalization migration via reset (simplest path)

1. Plan the change:
   - Implement the new normalization version (for example `NORMALIZATION_V2`) in shared helpers.
   - Ensure the new normalization keeps `{tenantId, regionId}` stable and valid.
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
   - Confirm that keys for a given `{tenantId, regionId}` share the expected hash tag.
   - Run smoke tests to verify tick and session behavior.
5. Resume ticks and commands.
6. Separately, perform any required Redis Cluster resharding as a **later, independent maintenance** once normalization is stable.

For most self-hosted deployments, the **reset-based migration** is preferred. In-place migrations are reserved for cases where coordination state for specific tenants cannot simply be dropped.

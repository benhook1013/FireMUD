# FireMUD Redis Reset & Recovery

This document defines the **coordination reset model** for FireMUD: when and how Coordination Redis can be reset or repaired, how tail‑loss interacts with recovery, and what operators should expect during incidents. It complements the conceptual hub (`system-architecture-redis.md`) and the concrete runbooks in `system-architecture-redis-operations.md`.

---

## Table of Contents

- [Coordination Reset Model](#coordination-reset-model)
- [Reset vs Repair vs Accept Loss](#reset-vs-repair-vs-accept-loss)
- [Common Reset Scenarios](#common-reset-scenarios)
- [Interaction with Tail-Loss and Replay](#interaction-with-tail-loss-and-replay)
- [Operator Expectations](#operator-expectations)
- [Related Documentation](#related-documentation)

---

## Coordination Reset Model

Coordination Redis is treated as a **long‑lived, tail‑loss‑bounded coordination buffer** in persistent environments, **not** as a durable log of record; it remains volatile and reset‑tolerant under controlled conditions. Authoritative history for gameplay outcomes always lives in PostgreSQL tick effect ledgers and domain stores as described in `system-architecture-redis.md`, and neither coordination keys nor AOF contents are ever treated as the primary log of record. The reset model centers on three scopes:

- **Region‑scoped reset** – affects a single `<tenantId, regionId>`:
  - Clears tick queues, timers, retry structures, and region‑scoped locks/leases for one region.
  - Leaves other regions and tenants untouched.
  - Typically used when:
    - A mis‑keyed script or bug has polluted tick state for one region.
    - An incident is confined to a subset of the world.

- **Tenant‑scoped reset** – affects a single `tenantId`:
  - Clears coordination keys for all regions and sessions under one tenant.
  - Often combined with an in‑game maintenance window or a revert/repin of tenant‑specific published content.
  - Used when:
    - A full in‑game reset is acceptable for a single tenant.
    - Cross‑region coordination problems cannot be repaired region by region.

- **Cluster‑scoped reset** – affects an entire Coordination Redis deployment:
  - Clears coordination state for all tenants and regions on that deployment.
  - Reserved for extreme cases:
    - Catastrophic corruption or misconfiguration.
    - Planned migrations where coordination state cannot be incrementally migrated.

Resets are always executed via **versioned coordination tooling** (for example, a maintenance CLI), not ad‑hoc `redis-cli` commands. Every reset:

- Identifies the exact scope (region/tenant/cluster).
- Uses shared key builders and descriptors for the relevant prefixes.
- Emits audit events documenting who initiated the reset, why, and what was affected.

Concrete commands live in `system-architecture-redis-operations.md`; this document explains when and why to choose each scope.

### Tick Reset Handshake (Timeline View)

Because ticks treat `(region_epoch, tickId)` as the canonical coordination timeline (see `system-architecture-ticks.md` and `system-architecture-tick-concepts-and-invariants.md`), every coordination reset must follow a simple handshake with the tick control plane:

1. **Pause ticks for the chosen scope**
   - The Game Session control plane (or equivalent admin service) pauses tick scheduling and new command intake for the affected `<tenantId, regionId>` pairs (region/tenant) or all regions (cluster).
2. **Bump `region_epoch` in PostgreSQL**
   - For each affected `<tenantId, regionId>`, the control plane updates `region_epoch` in the coordination metadata table so that any surviving executors and locks become stale by definition.
   - This step is authoritative: new executors always treat the highest `region_epoch` as the only valid timeline, and tick heartbeat streams (`StreamTickHeartbeats`) will begin emitting the new `regionEpoch` for those regions so consumers can distinguish pre- and post-reset ticks.
3. **Run the scoped reset tooling**
   - Use the versioned coordination maintenance CLI to clear keys in Coordination Redis for the chosen scope, using shared key builders and descriptors.
   - No ad-hoc `DEL`/`FLUSH*` commands are used; all prefixes and key shapes are driven from the same catalogs used by the Lua Script Registry.
4. **Reconcile tick effect ledger state**
   - For the affected scope, `SCHEDULED` ledger rows tied to the old `region_epoch` converge to terminal outcomes (typically `ABANDONED` with a reset-specific reason) via a scoped tick-effect-ledger reconcile step in the reset tooling, as described in `system-architecture-tick-failures-and-operations.md`.
   - New executors do not resume old-epoch `SCHEDULED` rows; any re-drive or migration across epochs is performed only by dedicated maintenance tooling that explicitly re-creates effects in the new epoch.
5. **Reset per-region metadata keys**
   - Using the same maintenance CLI and key-builder helpers, initialize or update `tick:{tenantRegionTag}:meta` for each affected `<tenantId, regionId>` so that:
     - `region_epoch` reflects the new epoch recorded in PostgreSQL.
     - `current_tick_id` is set to the RegionStatus commit baseline sentinel (default `-1` immediately after a reset so the first committable tick in the new epoch is `tickId=0`, unless an explicit maintenance baseline is documented).
   - This keeps Lua monotonic guards (`region_epoch`, `current_tick_id`) in Redis consistent with the durable timeline used by schedulers and operators.
6. **Resume ticks on the new epoch**
   - Once Coordination Redis is clean for the scope and the ledger has no indefinitely SCHEDULED rows for the old epoch, the control plane resumes tick scheduling.
   - New ticks start from the **new (bumped) `region_epoch`** with first committable tick `tickId=0` for each affected region (`lastCommittedTickId` remains at the sentinel `-1` until tick `0` commits), and all subsequent coordination state is written under that new epoch.

Heartbeat consumers that track progress or offsets must key their state by `(tenantId, regionId, regionEpoch)` (with `lastCommittedTickId` / offsets stored as values) and treat any observed epoch change on the stream as a reset boundary, rebuilding their own derived state from domain stores instead of assuming continuity of `tickId` alone.

This handshake ensures that resets move regions forward on the coordination timeline instead of trying to “repair” mixed-epoch state in place.

### Failover vs Cold Start vs Reset

Do not collapse all Redis events into “Redis repopulates from PostgreSQL.” Failover, cold start, and explicit reset have different safety properties:

- **Failover** (node crash, leader change, pod restart with intact AOF/PVCs)
  - Coordination Redis retains its AOF/replication history.
  - Keys such as `tick:{tenantRegionTag}:pending` and timers may survive.
  - Tick executors can replay or complete in‑flight ticks using idempotent domain logic and PostgreSQL guards.
  - This is the normal “Redis recovered” path; tail‑loss is bounded by the configured SLO.

- **Cold start** (empty Coordination Redis because the data directory/PVC is missing, wiped, or corrupted)
  - Treat as a **coordination reset event**, not a normal failover.
  - There is no durable coordination history to replay; all coordination keys start empty.
  - Services re‑establish leases/locks as new activity occurs, but any coordination intent that existed only in Redis (timers, retry schedules, in‑flight queues, session bindings) is dropped unless it is also represented durably elsewhere.

- **Reset** (intentional operational action)
  - A deliberate, scoped choice to discard volatile coordination state (region/tenant/cluster) and resume from PostgreSQL state plus new activity.
  - Must follow the reset model and runbooks; ad‑hoc `redis-cli` edits are treated as “unknown resets” and require a follow‑up scoped reset.

Design implications:

- Only coordination intent that is required for correctness is persisted durably (for example, via effect ledgers, transaction tables, or schedule tables in PostgreSQL).
- Best‑effort hints such as `remote:*` are explicitly not relied on for correctness; losing them affects latency only.
- When Redis and PostgreSQL disagree after split‑brain or data loss, **PostgreSQL wins**:
  - Operators do not attempt to “pick the right Redis side.”
  - Coordination histories in Redis for affected scopes are treated as disposable and rebuilt from durable state plus new commands after a reset.

### Reset Policy Matrix (Prefix Summary)

This table is the **canonical reset-policy catalog** for the main Redis prefixes. It is authoritative for:

- Prefix naming and the associated Redis **role** (Coordination vs Cache/Rate-Limit).
- The **reset policy** (reset-tolerant, reset-sensitive, or reset-forbidden) used by coordination reset tooling.
- A brief description of **what happens to gameplay or behavior if the prefix is dropped** during a reset.

Service design docs and per-service READMEs should link to this matrix (or any future expanded key catalog derived from it) instead of duplicating their own reset-policy tables; when a service introduces new prefixes, the catalog is extended here first.

| Prefix / Family | Role | Reset Policy (Coordination Reset) | Behavior When Dropped | Notes |
| --- | --- | --- | --- | --- |
| `tick:{tenantRegionTag}:pending` and `tick:{tenantRegionTag}:queue:*` | Coordination | **Reset-tolerant** | In-flight ticks and queued commands for affected regions are discarded; future ticks process only new commands. | `pending` effects converge via the tick effect ledger (replay/reconcile to `APPLIED`/`ABANDONED`) and idempotency prevents double-apply. Queued commands that were not yet staged are intentionally **lost**; they are not reconstructed from PostgreSQL. |
| `tick:{tenantRegionTag}:meta` | Coordination | **Reset-tolerant** | Epoch/tick guard metadata is dropped; scripts reinitialize metadata under the region lease and/or reset tooling re-establishes it from durable RegionStatus baselines for the new epoch. | `tick:{tenantRegionTag}:meta` is a monotonic guard and coordination helper only; authoritative baselines for `(region_epoch, tickId)` come from PostgreSQL RegionStatus/ledger plus heartbeats. Reset tooling reinitializes `region_epoch` and `current_tick_id` during the tick reset handshake. |
| `timer:{tenantRegionTag}` and `retry:{tenantRegionTag}` | Coordination | **Reset-tolerant** | Timers and retries for affected regions are discarded; future ticks process only newly scheduled timers/retries. | Only timers/retries that are also represented durably elsewhere (for example, PostgreSQL-backed automation schedules or durable follow-ups) are re-discovered after a reset; region-scoped timer/retry coordination keys themselves are not treated as reconstructible logs. |
| `tick-executor-lease:{tenantRegionTag}` and tick lock keys (`tick:{tenantRegionTag}:lock:*`) | Coordination | **Reset-tolerant** | Existing leases/locks vanish; new executors reacquire leadership and locks as ticks resume. | Leases and locks are transient; executors reacquire leases and lock state after reset. |
| `session:game:<tenantId>:<gameInstanceId>:<sessionId>` | Coordination | **Reset-sensitive** | Region-scoped resets preserve active sessions by default; tenant- and cluster-scoped resets may invalidate sessions, requiring fresh `LOGIN`. | Non-authoritative but player-visible. Region resets should avoid session eviction unless explicitly requested; broader resets require clear operator communication. |
| `session:auth:<scope>:<tokenHash>` (for example `session:auth:account:<accountId>:<tokenHash>`, `session:auth:tenant:<tenantId>:<tokenHash>`, `session:auth:global:<accountId>:<tokenHash>`) | Coordination | **Reset-sensitive** | JWT allowlist entries are dropped; internal calls must re-authenticate and obtain new tokens. | Security-critical but non-authoritative. Resets force re-authentication and token re-issuance; see `system-architecture-authentication.md` for full semantics. |
| `remote:<tenantId>:*` hint markers | Coordination | **Reset-tolerant** | Cross-region follow-ups rely solely on durable tables; hints may be temporarily missing, increasing latency only. Region-scoped coordination resets leave these tenant-scoped hints intact; tenant- and cluster-scoped resets may clear them. | Best-effort cross-region wake-up hints only; durable follow-ups live in PostgreSQL so dropping or retaining hints (including during tenant/cluster resets) affects latency, not correctness. Hint keys are TTL-bounded (default `remote_hint_ttl_ms = 60_000`) so stale hints age out automatically. |
| `ratelimit:<tenantId>:*` (and optional `:<shard>`) | Cache/Rate-Limit | **Reset-tolerant** | Rate-limit counters reset; future requests rebuild bucket state from zero. | Token buckets are best-effort; resets clear buckets and counters but do not affect authoritative state. Temporary post-reset bursts are acceptable as long as gateway policies still enforce global abuse limits. |
| `inventory:<tenantId>:*` | Cache | **Reset-tolerant** | Cached inventory/container aggregates are flushed; subsequent reads recompute views from PostgreSQL and repopulate Redis. | Inventories remain authoritative in PostgreSQL; resets may temporarily increase load but do not lose inventory data. |
| `character-cache:<tenantId>:*` | Cache | **Reset-tolerant** | Cached character graphs are dropped; hot paths fall back to Entity Management and repopulate caches on demand. | Character state lives in PostgreSQL; cache loss affects latency only. |
| `world-dynamic:<tenantId>:*` | Cache | **Reset-tolerant** | Cached dynamic world aggregates are flushed; subsequent reads recompute views from PostgreSQL. | World topology/dynamic state remains authoritative in PostgreSQL; resets may increase load temporarily. |
| `room:<tenantId>:*` | Cache | **Reset-tolerant** | Cached room topology snapshots are dropped; callers reload rooms from PostgreSQL and repopulate caches. | Used for LOOK/navigation snapshots; resets never affect canonical topology in PostgreSQL. |
| `view:room-look:<tenantId>:*` | Cache | **Reset-tolerant** | Cached rendered room views are dropped; Game Session recomputes views on demand for affected rooms. | Strictly Class B, TTL-only caches; correctness-critical flows (combat, visibility, movement) do not read from this prefix, and Game Session is the sole writer/reader of these keys. |
| `chat:say:<tenantId>:*`, `chat:tell:<tenantId>:*`, `chat:guild:<tenantId>:*`, `chat:account:<tenantId>:*` | Cache | **Reset-tolerant** | Short-lived chat buffers are cleared; subsequent reads fall back to PostgreSQL or rebuild windows from persisted history. | Treated as TTL-only rolling windows; resets drop recent in-memory history but do not lose persisted moderation logs where required. Clients must tolerate gaps and non-contiguous windows after resets. |
| `script-scheduler:{tenantRegionTag}:lastTickId` | Coordination | **Reset-tolerant** | Automation scheduler treats the next heartbeat as its baseline and may re-evaluate “every N ticks” windows. | Automation scheduler checkpoint for “every N ticks” triggers; losing it causes the scheduler to re-establish its baseline from the heartbeat stream and may temporarily re-evaluate interval boundaries. |
| `automation:tick:{tenantScriptTag}:*` (lock/queue/pending) | Coordination | **Reset-tolerant** | In-flight automation ticks are dropped; new tick-driven triggers rebuild coordination state. | Automation tick staging keys are treated like other coordination state and may be dropped during scoped resets; authoritative script triggers and audit trails remain in PostgreSQL. |
| `automation:queue:<tenantId>:*`, `automation:quota:<tenantId>:*` and other automation caches | Cache/Rate-Limit | **Reset-tolerant** | Queued work and quotas restart from an empty state; automation re-enqueues work based on durable triggers and budgets. | Best-effort buffers and counters; resets clear them but do not affect authoritative state. Repeated resets may temporarily relax fairness/throughput limits but must not change which work eventually runs. |
| `tick-events-lease:{tenantRegionTag}` | Coordination | **Reset-tolerant** | Observer leases are dropped; consumers reacquire leases and may duplicate best-effort processing until offsets are re-established. | Used only to avoid duplicate tick-event consumption work. Losing it is safe because tick events are observers/hints; correctness derives from the committed heartbeat/RegionStatus timeline and durable domain state. |
| `tick-events:{tenantRegionTag}` and `tick-events-offset:{tenantRegionTag}` | Coordination | **Reset-tolerant** | Tick event streams and consumer offsets are dropped; observers re-establish their baselines from the gRPC heartbeat and domain state. | Tick event streams are best-effort observer/wakeup hints (for example, reconnection hints and faster scheduler discovery). Streams are retention-capped (default `tick_events_maxlen = 2048` per region). Correctness derives from the committed heartbeat/RegionStatus timeline plus durable PostgreSQL schedules/effects; missing or duplicated events must not change which schedules eventually fire. |

When introducing a new prefix, service designs must extend this matrix (or a directly linked, expanded key catalog) with:

- Prefix pattern and Redis role.
- Reset policy (reset-tolerant, reset-sensitive, or reset-forbidden).
- A concise statement of what happens to gameplay or behavior if the prefix is dropped during a reset.

Reset tooling and runbooks are expected to consume this catalog to enforce reset behavior.

---

## Reset vs Repair vs Accept Loss

When coordination state appears incorrect or unhealthy, operators and designers choose between three strategies. Think of this as the **minimal decision tree** for a single‑admin operator:

1. **Can you safely accept the loss?**
   - Choose **Accept loss** when:
     - Metrics show tail‑loss stayed within the documented SLO window, and
     - Invariants (no double‑apply of critical effects, no cross‑tenant leaks, no broken financial flows) remain intact.
   - Behavior:
     - Acknowledge that some coordination state (timers, pending effects, non‑critical queues) has been lost within the tail‑loss envelope and **do nothing** beyond monitoring.
   - Examples:
     - Short Redis outage where `tail_loss_ms` and tick metrics confirm only the last 1–2 seconds of activity were affected.
     - Eviction of cache‑like coordination hints that are inherently best‑effort.

2. **If not acceptable, is the issue clearly localized and small?**
   - Choose **Repair** only when:
     - The blast radius is well understood (for example, one or two keys or a small set of known entities), and
     - You can express the fix using existing key builders and Lua helpers without inventing new patterns.
   - Behavior:
     - Attempt to fix specific keys or structures **without** clearing the entire scope.
   - Examples:
     - Removing a single stuck lock for a known entity.
     - Cleaning up a small number of malformed entries in a pending set after a known bug.
   - Rules:
     - Repairs must use the shared key builders and Lua registry helpers.
     - Break‑glass direct mutations to coordination prefixes (`tick:*`, `timer:*`, `retry:*`, `remote:*`, `session:*`, `tick-executor-lease:*`) require a follow‑up scoped reset for the affected region/tenant and must be recorded as an incident.

3. **Otherwise, reset at the smallest safe scope**
   - Choose **Reset** when:
     - The data corruption or bug is not safely repairable in place, or
     - You cannot confidently bound the impact to a handful of keys.
   - Behavior:
     - Intentionally clear coordination keys for a scope and allow services to rebuild from durable domain state.
   - Examples:
     - Region‑scoped reset after mis‑keyed `tick:*` data affecting many entities.
     - Tenant‑scoped reset after unrecoverable script bugs affecting multiple regions.
   - Rules:
     - Performed through the versioned coordination maintenance CLI.
     - Always accompanied by post‑reset health checks (ticks can be scheduled, sessions can be created/resumed, automation works).
     - Region‑ and tenant‑scoped resets should prefer **smaller scopes first**; cluster‑scoped reset is reserved for catastrophic or planned migration scenarios where finer scopes are ineffective.

Design reviews should explicitly state which of these strategies is expected to be safe for each coordination structure.

---

## Common Reset Scenarios

This section outlines representative scenarios and recommended reset scopes. Detailed step‑by‑step flows live in `system-architecture-redis-operations.md`.

### Mis-keyed Tick Data for a Single Region

Symptoms:

- Tick processing for one region stalls or repeatedly fails.
- Pending and retry queues show malformed or unexpected entries.

Recommended actions:

- Perform a **region‑scoped reset** for the affected `<tenantId, regionId>`:
  - Clear `tick:{tenantRegionTag}:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, and `tick-executor-lease:{tenantRegionTag}`.
  - Leave sessions and non‑region‑scoped keys intact unless they are known to be affected.
- Verify:
  - New tick leases can be acquired for the region.
  - Fresh ticks can schedule and commit.

Expected impact:

- Players in that region may see some actions dropped or replayed within the tail‑loss envelope.
- No permanent loss of authoritative game data in PostgreSQL.

### Buggy Coordination Script Affecting Multiple Regions for One Tenant

Symptoms:

- Multiple regions for a tenant show inconsistent pending/retry structures.
- Metrics indicate repeated script failures or unexpected error codes.

Recommended actions:

- Roll out a fixed script version.
- Perform a **tenant‑scoped reset**:
  - Clear coordination keys for all regions of that tenant.
  - Optionally schedule staged restarts or maintenance windows for the tenant’s players.

Expected impact:

- In‑progress actions for that tenant may be dropped/replayed within the tail‑loss envelope.
- Long‑lived domain state remains safe; scripts and tick processing resume in a clean coordination environment.

### Manual Break-Glass Edits to Coordination Keys

Symptoms:

- An operator used `redis-cli` or a raw script to mutate `tick:*`, `timer:*`, `retry:*`, `remote:*`, `session:*`, or `tick-executor-lease:*`.

Recommended actions:

- Treat the affected scope as “coordination state may be inconsistent”.
- Perform a **region‑ or tenant‑scoped reset**, depending on how broad the manual edits were.
- Record the incident using the standard audit fields (who, when, why, which prefixes/tenants/regions).

Expected impact:

- Coordination state is rebuilt from domain data; the risk from manual edits is removed.

### Full Cluster Rebuild or Migration

Symptoms:

- Coordination Redis must be replaced or re‑sharded in a way that invalidates existing keys.

Recommended actions:

- Plan a **cluster‑scoped reset** as part of a controlled maintenance window.
- Use migration and reset flows from `system-architecture-redis-operations.md`.
- Communicate expected impact to tenants and players.

Expected impact:

- All coordination state is reset; ticks and sessions restart from a clean slate.
- Domain data (PostgreSQL) remains authoritative.

---

## Interaction with Tail-Loss and Replay

Coordination resets interact with tail‑loss and replay in predictable ways:

- A **reset** is effectively a deliberate, large tail‑loss event for the chosen scope:
  - Instead of losing ~1–2 seconds of state, the system discards **all** coordination state for that scope.
  - This is only safe when:
    - All critical outcomes are recorded durably in PostgreSQL or another authoritative store.
    - Double‑apply is prevented via idempotency guards (for example, effect IDs, transaction IDs).

- A **repair** attempts to keep tail‑loss within the normal envelope:
  - Local mutations correct a small number of keys while preserving the rest of the coordination log.
  - This is inherently higher risk than a full reset and should be used sparingly.

- **Replay** behavior must remain safe regardless of resets:
  - Lua scripts must be idempotent with respect to their `KEYS` and `ARGV`.
  - Replaying a subset of surviving entries after a reset should not violate core invariants or double‑apply domain effects.

Designers should use the **Redis Design Checklist** to confirm that new flows remain safe under:

- Normal tail‑loss and replay.
- Scoped resets at region/tenant/cluster levels.

---

## Operator Expectations

Operators interacting with Coordination Redis should assume:

- **Resets are normal tools**, not last‑resort hacks:
  - Region‑ and tenant‑scoped resets are standard responses to certain classes of incidents.
  - Cluster‑scoped resets are rare but documented for extreme scenarios.

- **Break‑glass writes require follow‑up resets**:
  - Any manual mutation of core coordination prefixes is considered equivalent to corruption for that scope.
  - Runbooks must include clear guidance to reset and verify affected regions/tenants afterwards.

- **Metrics drive decisions**:
  - Tail‑loss SLO observability (described in `system-architecture-redis-operations.md`) surfaces when loss windows exceed acceptable bounds.
  - Tick watermarks, retry depths, and script error codes inform whether to repair, reset, or accept loss.

- **Auditability matters**:
  - All resets and break‑glass actions should emit structured audit events with:
    - A unique identifier and timestamp.
    - Affected prefixes, tenants, and regions.
    - Initiator identity (human or automation).
    - Rationale and incident links where applicable.

---

## Related Documentation

- `system-architecture-redis.md` – conceptual hub for roles, invariants, and key naming.
- `system-architecture-redis-operations.md` – concrete reset and migration runbooks.
- `system-architecture-redis-design-checklist.md` – checklist for assessing reset‑tolerance and tail‑loss compatibility.
- `system-architecture-redis-lua-patterns.md` – Lua script requirements for idempotency and replay safety.
- `system-architecture-redis-ops-access.md` – ACL and tooling expectations for operators.

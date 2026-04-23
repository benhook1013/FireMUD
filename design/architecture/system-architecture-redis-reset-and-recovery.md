# FireMUD Redis Reset & Recovery

This document defines the **coordination reset model** for FireMUD: when and how Coordination Redis can be reset, how tail‑loss interacts with recovery, and what operators should expect during incidents. It complements the conceptual hub (`system-architecture-redis.md`) and the concrete runbooks in `system-architecture-redis-operations.md`.

## Implementation Notes

This document describes the intended reset/recovery end state. The currently shipped runtime is narrower:

- Game Session already has a durable current-boundary ownership row, epoch/fence bumping on pause/resume, durable command status lookup, and startup convergence of accepted-but-unstaged commands to `LOST_BEFORE_STAGING`.
- Those live surfaces operate on the current `{tenantId, gameInstanceId}` queue boundary rather than the full region/tenant/cluster reset grammar described below.
- The full `coordination-maintenance pause/reset/reconcile-ledger/converge-commands/init-meta/smoke-check/resume` workflow remains the target-state operator model; it should not be read as fully implemented tooling in this repository today.

---

## Table of Contents

- [Coordination Reset Model](#coordination-reset-model)
- [Reset vs Accept Loss](#reset-vs-accept-loss)
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
  - Clears coordination keys for all regions under one tenant.
  - Preserves `session:game:*` only when operators explicitly choose `--preserve-sessions`; `session:auth:*` is always invalidated and reissued for tenant resets.
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
   - This pause step is complete only once the scope reaches the control-plane `PAUSED` state defined in `system-architecture-redis-ops-access.md`: no executor in the target scope is allowed to create new durable tick batches or new Redis coordination state under the old epoch.

2. **Bump `region_epoch` in PostgreSQL**
   - For each affected `<tenantId, regionId>`, the canonical reset control-plane operation (`RunScopedCoordinationReset(scope)` / `coordination-maintenance reset`) updates `region_epoch` in the coordination metadata table so that any surviving executors and locks become stale by definition.
   - This step is authoritative: new executors always treat the highest `region_epoch` as the only valid timeline, and tick heartbeat streams (`StreamTickHeartbeats`) will begin emitting the new `regionEpoch` for those regions so consumers can distinguish pre- and post-reset ticks.
3. **Run the scoped reset tooling**
   - Use the versioned coordination maintenance CLI to clear keys in Coordination Redis for the chosen scope, using shared key builders and descriptors.
   - No ad-hoc `DEL`/`FLUSH*` commands are used; all prefixes and key shapes are driven from the same catalogs used by the Lua Script Registry.
4. **Reconcile durable tick and command state**
   - For the affected scope, `SCHEDULED` ledger rows tied to the old `region_epoch` converge to terminal outcomes (typically `ABANDONED` with a reset-specific reason) via a scoped tick-effect-ledger reconcile step in the reset tooling, as described in `system-architecture-tick-failures-and-operations.md`.
   - New executors do not resume old-epoch `SCHEDULED` rows; any re-drive or migration across epochs is performed only by dedicated maintenance tooling that explicitly re-creates effects in the new epoch.
   - In the same reset scope, accepted command records that never became durably tied to a surviving `tick_batch_id` converge to `TERMINAL` with `executionOutcome = LOST_BEFORE_STAGING` and the command-type-appropriate `gameplayResult` (shared default `NOT_APPLIED`); reset tooling must not leave dedupe rows stranded in `RECEIVED` or `ENQUEUED`. For the canonical shared command terminal mapping table, see `system-architecture-tick-execution-flows.md` under `Canonical Command Terminal Mapping Table`.
5. **Reset per-region metadata keys**
   - Using the same maintenance CLI and key-builder helpers, initialize or update `tick:{tenantRegionTag}:meta` for each affected `<tenantId, regionId>` so that:
     - `region_epoch` reflects the new epoch recorded in PostgreSQL.
     - `current_tick_id` is set to the RegionStatus commit baseline sentinel (default `-1` immediately after a reset so the first committable tick in the new epoch is `tickId=0`, unless an explicit maintenance baseline is documented).
     - `current_tick_state` is initialized to the terminal baseline `APPLIED` for that sentinel `current_tick_id` so the next real tick may advance cleanly under the Lua state machine.
     - `current_tick_terminal_at_ms` is set to the reset/init-meta write timestamp for observability and bounded cleanup only; it is not a correctness input.
   - This keeps Lua monotonic guards (`region_epoch`, `current_tick_id`) in Redis consistent with the durable timeline used by schedulers and operators.
6. **Rebind preserved gameplay sessions for the affected regions**
   - Region-scoped resets preserve gameplay sessions by default, but clearing `tick:{tenantRegionTag}:*` also clears region-authoritative `tick:{tenantRegionTag}:session-binding:<entityId>` keys.
   - Before normal command intake resumes, Game Session runs the same session-to-region bridge flow used by reconnect/`PLAY` for any preserved authenticated session that still intends to control an entity in the reset region.
   - The rebind phase validates current account identity, membership authority, revocation state, and `binding_generation`; stale or unverifiable sessions remain connected but are not gameplay-admitted to that region until fresh `LOGIN` / `PLAY` succeeds.
   - During the gap between reset and successful rebind, command admission must fail closed with a non-applied outcome such as `"REGION_REBIND_REQUIRED"` rather than treating `session:game:*` or `sessionctx:*` advisory fields as region-local authority.
7. **Run the post-reset smoke check**
   - `RunPostResetSmokeCheck(scope)` is required before normal traffic resumes.
   - The smoke check must satisfy the canonical checklist in `system-architecture-redis-ops-access.md`, including lease acquisition, exactly-one batch allocation, Redis staging correlation, ledger convergence, and cleanup.
8. **Resume ticks on the new epoch**
   - Once Coordination Redis is clean for the scope, old-epoch ledger rows have converged, and accepted-but-unbound command records have converged, the control plane resumes tick scheduling.
   - New ticks start from the **new (bumped) `region_epoch`** with first committable tick `tickId=0` for each affected region (`lastCommittedTickId` remains at the sentinel `-1` until tick `0` commits), and all subsequent coordination state is written under that new epoch.

Heartbeat consumers that track progress or offsets must key their state by `(tenantId, regionId, regionEpoch)` (with `lastCommittedTickId` / offsets stored as values) and treat any observed epoch change on the stream as a reset boundary, rebuilding their own derived state from domain stores instead of assuming continuity of `tickId` alone.

This handshake ensures that resets move regions forward on the coordination timeline instead of trying to “repair” mixed-epoch state in place.

Worked example: region-scoped reset for `<tenantId=T1, regionId=R7>`

1. `PauseTicks(--scope region --tenant T1 --region R7)` rejects new command intake and stops new batch creation for `R7`.
2. Control plane bumps `region_epoch` for `(T1, R7)` from `12` to `13` in PostgreSQL.
3. `RunScopedCoordinationReset(--scope region --tenant T1 --region R7)` clears `tick:{tenantRegionTag}:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, and `tick-executor-lease:{tenantRegionTag}` for `R7`.
4. `ReconcileTickLedger(--scope region --tenant T1 --region R7 --old-region-epoch 12)` drives old-epoch `SCHEDULED` rows to `APPLIED` or `ABANDONED`, and `ConvergeCommandRecords(...)` moves accepted-but-unbound commands to `executionOutcome = LOST_BEFORE_STAGING` with default `gameplayResult = NOT_APPLIED`.
5. `InitializeRegionMeta(--scope region --tenant T1 --region R7 --region-epoch 13 --current-tick-id -1 --current-tick-state APPLIED --current-tick-terminal-at-ms <resetTimeMs>)` re-establishes the full Redis-side meta baseline.
6. `RebindRegionSessions(--scope region --tenant T1 --region R7 --region-epoch 13)` recreates region-authoritative `tick:{tenantRegionTag}:session-binding:<entityId>` entries for preserved sessions that still validate.
7. `RunPostResetSmokeCheck(--scope region --tenant T1 --region R7)` proves a fresh lease can be acquired and a sample tick can stage/clear.
8. `ResumeTicks(--scope region --tenant T1 --region R7)` allows `tickId=0` in epoch `13` to begin.

`RunPostResetSmokeCheck(scope)` is a required resume gate, not an optional example step. Its minimum pass criteria are defined in `system-architecture-redis-ops-access.md`.

Worked example: tenant-scoped reset for `<tenantId=T1>`

1. `PauseTicks(--scope tenant --tenant T1)` rejects new command intake and stops new batch creation for all regions owned by `T1`.
2. Control plane bumps `region_epoch` for every `(T1, regionId)` in PostgreSQL.
3. `RunScopedCoordinationReset(--scope tenant --tenant T1)` clears all coordination prefixes scoped to `T1`, including `tick:*`, `timer:*`, `retry:*`, `tick-executor-lease:*`, and any tenant-scoped `remote:T1:*` keys.
4. `ReconcileTickLedger(--scope tenant --tenant T1 --old-region-epoch-map ...)` converges old-epoch `SCHEDULED` rows for every affected region, and `ConvergeCommandRecords(--scope tenant --tenant T1)` terminates accepted-but-unbound command records with `executionOutcome = LOST_BEFORE_STAGING` and default `gameplayResult = NOT_APPLIED`.
5. `InitializeRegionMeta(--scope tenant --tenant T1 --region-epoch-map ... --current-tick-id -1 --current-tick-state APPLIED --current-tick-terminal-at-ms <resetTimeMs>)` re-establishes the full Redis-side meta baseline for each affected region.
6. If `--preserve-sessions` was used, `RebindRegionSessions(--scope tenant --tenant T1 --region-epoch-map ...)` recreates region-authoritative binding keys for preserved, still-valid sessions.
7. `RunPostResetSmokeCheck(--scope tenant --tenant T1)` samples at least one representative region per affected executor/shard group.
8. `ResumeTicks(--scope tenant --tenant T1)` allows each affected region to restart at `tickId=0` in its new epoch.

Worked example: cluster-scoped reset

1. `PauseTicks(--scope cluster)` rejects new command intake and stops all new batch creation.
2. Control plane bumps `region_epoch` for every region in PostgreSQL.
3. The maintenance workflow wipes Coordination Redis for the cluster, including any remaining leases, queues, timers, retries, remote follow-up hints, and observer streams.
4. `ReconcileTickLedger(--scope cluster --old-region-epoch-map ...)` converges old-epoch `SCHEDULED` rows cluster-wide, and `ConvergeCommandRecords(--scope cluster)` terminates accepted-but-unbound command records with `executionOutcome = LOST_BEFORE_STAGING` and default `gameplayResult = NOT_APPLIED`.
5. `InitializeRegionMeta(--scope cluster --region-epoch-map ... --current-tick-id -1 --current-tick-state APPLIED --current-tick-terminal-at-ms <resetTimeMs>)` re-establishes the full per-region Redis meta baseline from PostgreSQL baselines.
6. If an explicitly documented cluster-preserve session policy is used, `RebindRegionSessions(--scope cluster --region-epoch-map ...)` runs before smoke checks; the default cluster reset invalidates gameplay sessions and skips this step.
7. `RunPostResetSmokeCheck(--scope cluster)` samples at least one representative region per executor/shard group before reopening traffic.
8. `ResumeTicks(--scope cluster)` resumes normal scheduling on the new epochs.

### Reset Ordering Is Normative

The eight-step handshake above is the authoritative order for all scoped resets and full wipes:

- No runbook may clear Coordination Redis for a scope before the pause-and-epoch-bump steps complete for that same scope.
- Storage-level wipes, PVC deletion, `FLUSH*`, or prefix deletion that happen before epoch fencing are treated as an invalid reset sequence because stale executors could repopulate empty coordination state under the old epoch.
- Full-wipe runbooks in `system-architecture-redis-operations.md` are required to embed this same order rather than defining an alternate sequence.
- Any reset scope that preserves gameplay sessions but clears region-local `tick:{tenantRegionTag}:session-binding:*` keys must complete the rebind phase before normal command intake resumes.

### Failover vs Cold Start vs Reset

Do not collapse all Redis events into “Redis repopulates from PostgreSQL.” Failover, cold start, and explicit reset have different safety properties:

- **Failover** (node crash, leader change, pod restart with intact AOF/PVCs)
  - Coordination Redis retains its AOF/replication history.
  - Keys such as `tick:{tenantRegionTag}:pending` and timers may survive.
  - Tick executors can replay or complete in‑flight ticks using idempotent domain logic and PostgreSQL guards.
  - This is the normal “Redis recovered” path; tail‑loss is bounded by the configured SLO.

Worked example: normal failover for `<tenantId=T1, regionId=R7>`

1. Redis leader fails, but the replacement node replays intact AOF state and restores `tick:{tenantRegionTag}:pending`, `tick:{tenantRegionTag}:meta`, and the region lease key.
2. The old executor loses its lease heartbeat and stops acting on in-memory state.
3. A new executor reacquires `tick-executor-lease:{tenantRegionTag}`, reads PostgreSQL `RegionStatus(regionEpoch=13, lastCommittedTickId=41)`, and inspects surviving coordination state only as residue to correlate against the durable `tick_batch_id`.
4. If `pending` still matches the surviving durable batch, the executor replays or completes that batch under normal idempotent rules.
5. If `pending` is missing or inconsistent despite the intact failover, the executor does not guess from Redis alone; it runs the normal ledger replay/reconcile path for the affected scope, then advances the region using the durable timeline.
6. No epoch bump or explicit reset is required unless the incident escalates into an actual cold start or corruption event.

- **Cold start** (empty Coordination Redis because the data directory/PVC is missing, wiped, or corrupted)
  - Treat as a **coordination reset event**, not a normal failover.
  - There is no durable coordination history to replay; all coordination keys start empty.
  - Services re‑establish leases/locks as new activity occurs, but any coordination intent that existed only in Redis (timers, retry schedules, in‑flight queues, session bindings) is dropped unless it is also represented durably elsewhere.
  - Empty-start recovery is not a separate operator path:
    - Operators must run the same scoped reset handshake defined above, including pause, epoch bump, scoped reset, ledger reconcile, command convergence, metadata initialization, preserved-session rebind where applicable, smoke check, and resume.
    - Lazy recreation of `tick:{tenantRegionTag}:meta` by hot-path staging may still occur as an implementation detail after the reset completes, but it is not a substitute for the reset handshake and operators must not treat an empty keyspace as “safe to resume automatically”.

Worked example: cold start for `<tenantId=T1, regionId=R7>`

1. Coordination Redis starts empty after loss of its data directory, while PostgreSQL still shows `RegionStatus(regionEpoch=13, lastCommittedTickId=41)` for `(T1, R7)`.
2. Operators treat the event as a region-, tenant-, or cluster-scoped reset depending on blast radius and first drive the scope to the canonical `PAUSED` state.
3. Control plane bumps `region_epoch` for the affected scope, for example from `13` to `14` for `(T1, R7)`.
4. Reset tooling reconciles old-epoch ledger rows and converges accepted-but-unbound command records before any new tick is allowed to stage work.
5. `InitializeRegionMeta(...)` establishes the new-epoch Redis metadata baseline.
6. If the chosen reset scope preserves gameplay sessions, `RebindRegionSessions(...)` recreates region-authoritative binding keys for still-valid sessions.
7. `RunPostResetSmokeCheck(...)` proves a fresh tick can stage and clear safely.
8. `ResumeTicks(...)` allows the first committable tick in epoch `14` to begin.

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
| `tick:{tenantRegionTag}:pending` and `tick:{tenantRegionTag}:queue:*` | Coordination | **Reset-tolerant** | In-flight ticks and queued commands for affected regions are discarded; future ticks process only new commands. | `pending` effects converge via the tick effect ledger (replay/reconcile to `APPLIED`/`ABANDONED`) and idempotency prevents double-apply. Queued commands that were not yet staged are intentionally **lost**; they are not reconstructed from PostgreSQL. Their accepted command records must still converge to terminal command status (`executionOutcome = LOST_BEFORE_STAGING`) during reset handling. |
| `tick:{tenantRegionTag}:meta` | Coordination | **Reset-tolerant** | Epoch/tick guard metadata is dropped; scripts reinitialize metadata under the region lease and/or reset tooling re-establishes it from durable RegionStatus baselines for the new epoch. | `tick:{tenantRegionTag}:meta` is a monotonic guard and coordination helper only; authoritative baselines for `(region_epoch, tickId)` come from PostgreSQL RegionStatus/ledger plus heartbeats. Reset tooling reinitializes `region_epoch` and `current_tick_id` during the tick reset handshake. |
| `tick:{tenantRegionTag}:session-binding:*` | Coordination | **Reset-tolerant with preserved-session rebind** | Region-local gameplay admission bindings are dropped for affected regions. Preserved sessions are not gameplay-admitted again until the reset workflow recreates binding keys through the session-to-region bridge or the client completes fresh `LOGIN` / `PLAY`. | These keys are region-authoritative for gameplay command admission, but their source intent lives in authenticated session context. Reset workflows that preserve sessions must run `RebindRegionSessions(...)` before reopening normal command intake. |
| `timer:{tenantRegionTag}` and `retry:{tenantRegionTag}` | Coordination | **Reset-tolerant** | Timers and retries for affected regions are discarded; future ticks process only newly scheduled timers/retries. | Only timers/retries that are also represented durably elsewhere (for example, PostgreSQL-backed automation schedules or durable follow-ups) are re-discovered after a reset; region-scoped timer/retry coordination keys themselves are not treated as reconstructible logs. |
| `tick-executor-lease:{tenantRegionTag}` and tick lock keys (`tick:{tenantRegionTag}:lock:*`) | Coordination | **Reset-tolerant** | Existing leases/locks vanish; new executors reacquire leadership and locks as ticks resume. | Leases and locks are transient; executors reacquire leases and lock state after reset. |
| `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` | Coordination | **Reset-sensitive** | Region-scoped resets preserve active sessions by default. Tenant-scoped resets preserve gameplay sessions only when an explicit `--preserve-sessions` option is invoked. Cluster-scoped resets invalidate sessions by default. | Non-authoritative but player-visible. Region resets should avoid session eviction unless explicitly requested; tenant resets require an explicit operator choice for gameplay-session preservation; cluster resets require clear operator communication. |
| `sessionctx:*` | Coordination | **Reset-sensitive** | Region-scoped resets preserve bootstrap/session-context records by default. Tenant-scoped resets preserve them only when gameplay sessions are preserved. Cluster-scoped resets invalidate them by default. | Current Game Session implementation-local context and lookup indexes for pre-auth and authenticated session plumbing. These keys are never region-local gameplay authority; preserved entries must still pass authenticated rebind validation before gameplay admission resumes. |
| `session:auth:<scope>:<tokenHash>` (for example `session:auth:account:<accountId>:<tokenHash>`, `session:auth:tenant:<tenantId>:<tokenHash>`, `session:auth:global:<accountId>:<tokenHash>`) | Coordination | **Reset-sensitive** | JWT allowlist entries are dropped; internal calls must re-authenticate and obtain new tokens. | Security-critical but non-authoritative. Resets force re-authentication and token re-issuance; see `system-architecture-authentication.md` for full semantics. |
| `remote:<tenantId>:*` hint markers | Coordination | **Reset-tolerant** | Cross-region follow-ups rely solely on durable tables; hints may be temporarily missing, increasing latency only. Region-scoped coordination resets leave these tenant-scoped hints intact; tenant- and cluster-scoped resets may clear them. | Best-effort cross-region wake-up hints only; durable follow-ups live in PostgreSQL so dropping or retaining hints (including during tenant/cluster resets) affects latency, not correctness. Hint keys are TTL-bounded (default `remote_hint_ttl_ms = 60_000`) so stale hints age out automatically. |
| `ratelimit:<tenantId>:*` (and optional `:<shard>`) | Cache/Rate-Limit | **Reset-tolerant** | Rate-limit counters reset; future requests rebuild bucket state from zero. | Token buckets are best-effort; resets clear buckets and counters but do not affect authoritative state. Temporary post-reset bursts are acceptable as long as gateway policies still enforce global abuse limits. |
| `inventory:<tenantId>:*` | Cache | **Reset-tolerant** | Cached inventory/container aggregates are flushed; subsequent reads recompute views from PostgreSQL and repopulate Redis. | Inventories remain authoritative in PostgreSQL; resets may temporarily increase load but do not lose inventory data. |
| `character-cache:<tenantId>:*` | Cache | **Reset-tolerant** | Cached character graphs are dropped; hot paths fall back to Entity Management and repopulate caches on demand. | Character state lives in PostgreSQL; cache loss affects latency only. |
| `world-dynamic:<tenantId>:*` | Cache | **Reset-tolerant** | Cached dynamic world aggregates are flushed; subsequent reads recompute views from PostgreSQL. | World topology/dynamic state remains authoritative in PostgreSQL; resets may increase load temporarily. |
| `room:<tenantId>:*` | Cache | **Reset-tolerant** | Cached room topology snapshots are dropped; callers reload rooms from PostgreSQL and repopulate caches. | Used for LOOK/navigation snapshots; resets never affect canonical topology in PostgreSQL. |
| `view:room-look:<tenantId>:*` | Cache | **Reset-tolerant** | Cached rendered room views are dropped; Game Session recomputes views on demand for affected rooms. | Strictly Class B, TTL-only caches; correctness-critical flows (combat, visibility, movement) do not read from this prefix, and Game Session is the sole writer/reader of these keys. |
| `chat:say:<tenantId>:*`, `chat:tell:<tenantId>:*`, `chat:guild:<tenantId>:*`, `chat:account:<tenantId>:*` | Cache | **Reset-tolerant** | Short-lived chat buffers are cleared; subsequent reads fall back to PostgreSQL or rebuild windows from persisted history. | Treated as TTL-only rolling windows; resets drop recent in-memory history but do not lose persisted moderation logs where required. Clients must tolerate gaps and non-contiguous windows after resets. |
| `script-scheduler:{tenantRegionTag}:lastTickId` | Coordination | **Reset-tolerant** | Automation scheduler treats the next heartbeat as its baseline and may re-scan due interval boundaries, but durable trigger-instance uniqueness prevents duplicate logical trigger creation. | Automation scheduler checkpoint for “every N ticks” triggers; losing it causes the scheduler to re-establish its baseline from the heartbeat stream while PostgreSQL-backed trigger-instance rows remain the de-duplication boundary. |
| `automation:timer:{tenantRegionTag}` | Coordination | **Reset-tolerant** | Automation timer indexes for affected regions are discarded and rebuilt from durable schedules, trigger-instance rows, and heartbeat progress. | Region-scoped coordination index for script timers/intervals. Entries must remain instance-aware in payload and rebuild logic (`gameInstanceId`, and plugin identifiers when applicable) even though the Redis key is region-scoped for slotting/locality. |
| `automation:queue:{tenantInstanceTag}:*`, `automation:quota:<tenantId>:*`, `automation:tenant-budget:<tenantId>:tier:<tier>`, `automation:test:capacity:<tenantId>:*`, `automation:test:capacity:cluster*` and other automation caches | Cache/Rate-Limit | **Reset-tolerant** | Queued work and quotas restart from an empty state; automation re-enqueues work based on durable triggers and budgets. | Best-effort buffers and counters; resets clear them but do not affect authoritative state. Repeated resets may temporarily relax fairness/throughput limits but must not change which work eventually runs. |
| `tick-events-lease:{tenantRegionTag}` | Coordination | **Reset-tolerant** | Observer leases are dropped; consumers reacquire leases and may duplicate best-effort processing until offsets are re-established. | Used only to avoid duplicate tick-event consumption work. Losing it is safe because tick events are observers/hints; correctness derives from the committed heartbeat/RegionStatus timeline and durable domain state. |
| `tick-events:{tenantRegionTag}` and `tick-events-offset:{tenantRegionTag}` | Coordination | **Reset-tolerant** | Tick event streams and consumer offsets are dropped; observers re-establish their baselines from the gRPC heartbeat and domain state. | Tick event streams are best-effort observer/wakeup hints (for example, reconnection hints and faster scheduler discovery). Streams are retention-capped (default `tick_events_maxlen = 2048` per region). Correctness derives from the committed heartbeat/RegionStatus timeline plus durable PostgreSQL schedules/effects; missing or duplicated events must not change which schedules eventually fire. |

When introducing a new prefix, service designs must extend this matrix (or a directly linked, expanded key catalog) with:

- Prefix pattern and Redis role.
- Reset policy (reset-tolerant, reset-sensitive, or reset-forbidden).
- A concise statement of what happens to gameplay or behavior if the prefix is dropped during a reset.

Reset tooling and runbooks are expected to consume this catalog to enforce reset behavior.

---

## Reset vs Accept Loss

When coordination state appears incorrect or unhealthy, first-implementation operators choose between two supported strategies. Think of this as the **minimal decision tree** for a single‑admin operator:

1. **Can you safely accept the loss?**
   - Choose **Accept loss** when:
     - Metrics show tail‑loss stayed within the documented SLO window, and
     - Invariants (no double‑apply of critical effects, no cross‑tenant leaks, no broken financial flows) remain intact.
   - Behavior:
     - Acknowledge that some coordination state (timers, pending effects, non‑critical queues) has been lost within the tail‑loss envelope and **do nothing** beyond monitoring.
   - Examples:
     - Short Redis outage where `tail_loss_ms` and tick metrics confirm only the last 1–2 seconds of activity were affected.
     - Eviction of cache‑like coordination hints that are inherently best‑effort.

2. **Otherwise, reset at the smallest safe scope**
   - Choose **Reset** when:
     - The loss is outside the accepted envelope, or
     - The data corruption or bug is not known to be safe to ignore, or
     - You cannot confidently prove that doing nothing preserves the documented invariants.
   - Behavior:
     - Intentionally clear coordination keys for a scope and allow services to rebuild from durable domain state.
   - Examples:
     - Region‑scoped reset after mis‑keyed `tick:*` data affecting many entities.
     - Tenant‑scoped reset after unrecoverable script bugs affecting multiple regions.
   - Rules:
     - Performed through the versioned coordination maintenance CLI.
     - Always accompanied by post‑reset health checks (ticks can be scheduled, sessions can be created/resumed, automation works).
     - Region‑ and tenant‑scoped resets should prefer **smaller scopes first**; cluster‑scoped reset is reserved for catastrophic or planned migration scenarios where finer scopes are ineffective.

General in-place repair of coordination keys is intentionally **not** a first-implementation operator path. A future repair path may be added only by defining named maintenance CLI verbs with scope rules, fencing/quiescence requirements, audit output, and mandatory post-repair verification. Until that exists, any direct mutation of coordination prefixes is break-glass activity and must be followed by a scoped reset or documented cleanup flow that covers the mutated prefix before normal processing resumes.

Design reviews should explicitly state which of these strategies is expected to be safe for each coordination structure.

---

## Common Reset Scenarios

This section outlines representative scenarios and recommended reset scopes. Detailed step‑by‑step flows live in `system-architecture-redis-operations.md`.

### Mis-keyed Tick Data for a Single Region

Symptoms:

- Tick processing for one region stalls or repeatedly fails.
- Pending and retry queues show malformed or unexpected entries.

Recommended actions:

- Execute the canonical region-scoped workflow:
  - `coordination-maintenance pause --scope region --tenant <tenantId> --region <regionId>`
  - `coordination-maintenance reset --scope region --tenant <tenantId> --region <regionId>` (this command performs and audits the `region_epoch` bump)
  - `coordination-maintenance reconcile-ledger --scope region --tenant <tenantId> --region <regionId> --old-region-epoch <epoch>`
  - `coordination-maintenance converge-commands --scope region --tenant <tenantId> --region <regionId> --old-region-epoch <epoch>`
  - `coordination-maintenance init-meta --scope region --tenant <tenantId> --region <regionId> --region-epoch <epoch> --current-tick-id -1`
  - `coordination-maintenance rebind-sessions --scope region --tenant <tenantId> --region <regionId> --region-epoch <epoch>`
  - `coordination-maintenance smoke-check --scope region --tenant <tenantId> --region <regionId>`
  - `coordination-maintenance resume --scope region --tenant <tenantId> --region <regionId>`
- Apply the default region reset session policy:
  - Leave sessions and other non-region-scoped keys intact unless a broader documented workflow is explicitly chosen.
  - Recreate region-local gameplay bindings for preserved sessions through the rebind step before normal command intake resumes.

Expected impact:

- Players in that region may see some actions dropped or replayed within the tail‑loss envelope.
- No permanent loss of authoritative game data in PostgreSQL.

### Buggy Coordination Script Affecting Multiple Regions for One Tenant

Symptoms:

- Multiple regions for a tenant show inconsistent pending/retry structures.
- Metrics indicate repeated script failures or unexpected error codes.

Recommended actions:

- Roll out a fixed script version.
- Execute the canonical tenant-scoped workflow:
  - `coordination-maintenance pause --scope tenant --tenant <tenantId>`
  - `coordination-maintenance reset --scope tenant --tenant <tenantId> [--preserve-sessions]` (this command performs and audits the `region_epoch` bump; auth sessions are still invalidated)
  - `coordination-maintenance reconcile-ledger --scope tenant --tenant <tenantId> --old-region-epoch-map <path>`
  - `coordination-maintenance converge-commands --scope tenant --tenant <tenantId> --old-region-epoch-map <path>`
  - `coordination-maintenance init-meta --scope tenant --tenant <tenantId> --region-epoch-map <path> --current-tick-id -1`
  - `coordination-maintenance rebind-sessions --scope tenant --tenant <tenantId> --region-epoch-map <path>` when `--preserve-sessions` was used
  - `coordination-maintenance smoke-check --scope tenant --tenant <tenantId>`
  - `coordination-maintenance resume --scope tenant --tenant <tenantId>`
- Choose the tenant session policy explicitly when player-binding state is part of the incident.

Expected impact:

- In‑progress actions for that tenant may be dropped/replayed within the tail‑loss envelope.
- Long‑lived domain state remains safe; scripts and tick processing resume in a clean coordination environment.

### Manual Break-Glass Edits to Coordination Keys

Symptoms:

- An operator used `redis-cli` or a raw script to mutate `tick:*`, `timer:*`, `retry:*`, `remote:*`, `session:game:*`, `session:auth:*`, or `tick-executor-lease:*`.

Recommended actions:

- Treat the affected scope as “coordination state may be inconsistent”.
- Execute the canonical region- or tenant-scoped workflow for the smallest safe scope, using the same `pause -> reset -> reconcile-ledger -> converge-commands -> init-meta -> smoke-check -> resume` sequence defined above.
- Record the incident using the standard audit fields (who, when, why, which prefixes/tenants/regions).

Expected impact:

- Coordination state is rebuilt from domain data; the risk from manual edits is removed.

### Full Cluster Rebuild or Migration

Symptoms:

- Coordination Redis must be replaced or re‑sharded in a way that invalidates existing keys.

Recommended actions:

- Plan a **cluster‑scoped reset** as part of a controlled maintenance window.
- Execute the canonical cluster-scoped workflow from `system-architecture-redis-operations.md`, including `pause`, epoch fencing, storage-level wipe, `reconcile-ledger`, `converge-commands`, `init-meta`, `smoke-check`, and `resume`.
- Communicate expected impact to tenants and players.

Expected impact:

- All coordination state is reset; ticks restart from a clean slate. Tenant/cluster scope resets always invalidate `session:auth:*`; gameplay-session preservation for tenant scope is allowed only when the reset command explicitly records `--preserve-sessions`.
- Domain data (PostgreSQL) remains authoritative.

---

## Interaction with Tail-Loss and Replay

Coordination resets interact with tail‑loss and replay in predictable ways:

- A **reset** is effectively a deliberate, large tail‑loss event for the chosen scope:
  - Instead of losing up to `tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)` of state, the system discards **all** coordination state for that scope.
  - This is only safe when:
    - All critical outcomes are recorded durably in PostgreSQL or another authoritative store.
    - Double‑apply is prevented via idempotency guards (for example, effect IDs, transaction IDs).

- In-place **repair** is not part of the first-implementation operator model:
  - Local mutations of coordination keys bypass the normal script/key-builder path unless they are wrapped in future dedicated maintenance tooling.
  - Until such tooling exists, direct mutation is break-glass activity and is followed by the reset/cleanup rules above rather than treated as a durable fix.

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
  - Tick watermarks, retry depths, and script error codes inform whether to accept loss or reset at the smallest safe scope.

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

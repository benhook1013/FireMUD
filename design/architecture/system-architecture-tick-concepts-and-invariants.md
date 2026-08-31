# FireMUD Tick System: Concepts & Invariants

This document summarizes the **core concepts and invariants** of the FireMUD tick system. It is aimed at developers and reviewers who need to understand fairness, region authority, and idempotency without reading the full runtime design in `system-architecture-ticks.md`.

**Target-state only:** Automation-generated tick work must carry the exact Game Session-owned `(scriptPatchVersion, scriptPinEpoch)` tuple through admission and execution. This document owns only the tick-local consequence: the region executor rejects a stale tuple at the execution fence, while routine script rollback does not pause ordinary gameplay ticks. See [Scripting Contracts](./system-architecture-scripting-contracts.md) and [Runtime Execution](./system-architecture-scripting-runtime-execution.md) for the canonical script contract.

## Implementation Status

This document describes the target-state invariants. The target ownership model is a region-scoped Redis liveness lease paired with a durable executor fence and authority-fenced takeover/replay recovery for each `<tenantId, gameInstanceId, regionId>`. The live deployment has not yet converged on that region-scoped boundary: durable ownership is currently instance-scoped at `{tenantId, gameInstanceId}`, exposed through `RuntimeOwnershipStatus` with selected region fields and an opaque compare-and-match fence. True region-scoped lease/fence installation, `RegionStatus` authority, and takeover reconciliation remain target-state implementation and proof work. The invariants below therefore remain the canonical target contract and must not be read as a claim that the target recovery protocol is already live.

The exact `scriptPinEpoch` propagation and final same-version old-epoch rejection described above are target-state only. The live `EnqueueAutomationCommandIfAbsentRequest` carries `scriptPatchVersion` but not `scriptPinEpoch`, so the current Game Session boundary cannot reject same-version work from an older script pin epoch; the target invariant remains required and unimplemented at that boundary.

## What This Covers

- Hybrid tick model and player/AI fairness.
- Region authority, leadership, and locking.
- High-level retry, isolation, and idempotency rules.
- Region health, stalled-region detection, and tick pacing (including idle/background ticks and global fan-out).

## Key Sections in the Main Tick Doc

The following sections in `system-architecture-ticks.md` provide the primary conceptual model:

- **Hybrid Tick Model** – overall tick loop structure and fairness guarantees.
- **Tick Events & Heartbeat Stream** – how other services (such as Automation & Scripting) follow the tick timeline via gRPC streams.
- **Region Authority and Tick Executor** – single-writer semantics per region and executor lease behavior.
- **Distributed Locking** – how locks are scoped and enforced to avoid deadlocks.
- **Smart Retry and Conflict Resolution** – canonical patterns for retries under contention.
- **Tick Regions and Parallel Execution** – how work is partitioned to scale horizontally.
- **Region Sizing and Ownership** – guidance for region boundaries and ownership changes.
- **Timeout and Fairness Policy** – how long-running commands are handled without starving others.
- **Isolation and Replay Safety** – invariants that ensure deterministic replays and safe recovery.
- **Timers and Time Scaling** – how tick timers interact with scaled time and the heartbeat stream.

Refer to those sections for the authoritative wording and examples.

## Invariants to Preserve

When designing new tick-driven features, keep these invariants in mind:

- **Single authoritative executor per region** – all tick-side state for a `<tenantId, gameInstanceId, regionId>` is owned by one executor at a time.
- **Lease and lock tokens are scoped and validated proofs** – region leases and per-entity locks in Redis carry opaque tokens scoped to the current region timeline; tick scripts must validate those tokens, the expected `regionEpoch`, and the current `tickId` inside a single Lua invocation before applying or cleaning up any staged work. Lease possession alone is not durable authority: staging and recovery also require the current region epoch and durable executor fence under the handshake owned by [Tick System and Runtime Design](./system-architecture-ticks.md).
- **Separate actor-action and passive/inbound-effect lanes** – fairness limits each eligible entity to at most one root intentional actor action per tick. Passive pulses, environmental work, inbound or remote effects, actor-generated consequences, and retries of already-admitted effects use their own bounded lane and do not consume that actor-action slot. Every source declares its lane and cost class; a command remains an actor action even when its source is a timer or automation handoff.
- **No cross-region locks** – cross-region interactions are modeled as messages, not shared locks or multi-region transactions.
- **Idempotent side effects** – the region-scoped tick timeline `(region_epoch, tickId)` supplies ordering and fence context, while namespace-complete effect projections and owner-local effect guards ensure that replays after failure do not double-apply mutations.

The identifier glossary owns the stable root `EffectId` for a tick-driven mutation. A concrete ledger/guard/storage projection may include the namespace-complete tuple `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` for scope, ordering, and target lookup, but that tuple is a storage projection rather than the root identity. `playableStateScope` is separately persisted and exact-validated evidence, not a replacement for `playableStateNamespaceId`; `regionEpoch` and `tickId` are ordering/timeline metadata, not effect-guard uniqueness dimensions. The ADR-level `targetAggregateIdentity` is represented by the target aggregate type and ID (or an equivalent explicitly proven collision-safe encoding). Participant guard logical uniqueness is exactly the root `EffectId`, typed operation, and target aggregate. If an owner-local physical partition is used, it is storage placement only and cannot alter logical uniqueness or deduplication; the immutable request digest is bound to the logical identity, while durable outcome/evidence state is mutable guard-row state protected by CAS. A bare tick tuple or bare `effectKey` is not sufficient for that guard contract. Ordinary retries and replay preserve the root `EffectId`, original participant ledger projection, and collision-safe guard identity; retry eligibility/attempt metadata is separate and must not mutate the original `regionEpoch`, `tickId`, `effectKey`, or root/typed-operation/target projection. A future linked-attempt or supersession model requires an explicit contract. A fresh root is allowed only for a **post-abandon re-drive** after conclusive terminal `ABANDONED` plus source-claim terminalization, later coordinate allocation, and durable lineage.

The tick system adopts the same **coordination timeline** concept as the Redis architecture: for each `<tenantId, gameInstanceId, regionId>` there is a canonical timeline defined by `(region_epoch, tickId)`. Within a given `region_epoch`:

- `tickId` is monotonic and identifies the ordering position of each committed tick for that region; neither the `(region_epoch, tickId)` timeline nor its coordinates provide effect-guard uniqueness.
- All **staged tick coordination state** in Redis (for example `tick:{tenantRegionTag}:pending` entries and other data created for a specific in-flight tick) and all tick effect ledger rows in PostgreSQL conceptually belong to exactly one `(region_epoch, tickId)` pair.
- Region-scoped source structures such as `tick:{tenantRegionTag}:queue:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, and `tick-executor-lease:{tenantRegionTag}` are primarily **epoch-scoped** rather than tick-scoped:
  - They belong to the current `region_epoch`.
  - They carry eligibility/order metadata that later maps selected work into a specific `(region_epoch, tickId)` when the tick batch is formed.
  - Reset/replay tooling must therefore distinguish “epoch-scoped source state” from “tick-scoped staged state” rather than treating every region key as already owned by one committed or in-flight tick.
- Tenant-scoped coordination such as gameplay session keys (`session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`) live on Coordination Redis but are **not** bound to a single region epoch; they follow the authentication/reconnection contracts and reset behavior described in the Redis hub and usage/profile docs rather than the per-region epoch model.
- When a scoped coordination reset occurs (or a topology/maintenance operation explicitly severs the old region timeline), the tick control plane bumps `region_epoch` and ensures that subsequent tick work for that `<tenantId, gameInstanceId, regionId>` is scheduled only on the new timeline; survivors from older epochs in region-scoped Redis keys are treated as stale and either ignored or explicitly reconciled via the tick effect ledger and reset tooling.

The canonical `tenantRegionTag` used in coordination keys is an opaque encoding of the complete `<tenantId, gameInstanceId, regionId>` scope. In particular, a tick event stream or offset must never use a tenant-plus-region tag that omits `gameInstanceId`; two game instances of one tenant must have disjoint coordination and observer-hint namespaces.

The main tick document contains the detailed rules and Redis key shapes behind each of these points.

### Fairness Under Coordination Exposure and Resets

Lane fairness and the “one root actor action per eligible entity per tick” rule apply to steady-state execution within a stable `region_epoch`. Around measured coordination exposure and explicit resets:

- Redis coordination loss and scoped resets may cause some actions near the end of the coordination timeline to be delayed, dropped, replayed, or slightly re-ordered; `ticks_exposed` is diagnostic only.
- In these cases, the system prioritizes convergence of each concrete namespace-complete ledger/guard/storage projection `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, region_epoch, tickId, effectKey, targetAggregateType, targetAggregateId)` to durably `APPLIED` or `ABANDONED` when the evidence policy permits terminalization, while retaining its root `EffectId` and participant guard contract. `playableStateScope` remains separately exact-validated evidence, and `region_epoch`/`tickId` remain ordering metadata rather than uniqueness dimensions. Inconclusive old-epoch work remains reconciliation-required under the [Inconclusive Old-Epoch Reconciliation Policy](./system-architecture-tick-failures-and-operations.md#inconclusive-old-epoch-reconciliation-policy).
- Designers should treat fairness guarantees as strong within a healthy epoch and best-effort across failures and resets; if a feature requires stronger guarantees around resets, that requirement must be called out explicitly and validated against the measured unreplicated-write-window SLO and ADR 0058 outcome class.

Conceptually, domain services treat Redis locks and leases as **opaque tick-engine concerns**: handlers receive the root `EffectId`, typed operation, target aggregate, immutable request digest, and their own idempotency state. Ledger/guard/storage adapters may additionally expose the namespace-complete tick projection `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, region_epoch, tickId, effectKey, targetAggregateType, targetAggregateId)` for scope and ordering; `playableStateScope` is exact-validated evidence and the timeline coordinates are ordering metadata, not guard uniqueness. Handlers never read `tick:{tenantRegionTag}:lock:<entityId>` or `tick-executor-lease:{tenantRegionTag}` to make application-level decisions, nor do they depend on Cache/Rate-Limit Redis keys (for example `inventory:*`, `view:*`, `ratelimit:*`) for correctness or ordering. Cache usage, when present, is encapsulated inside domain services and affects only latency, not the tick engine’s notion of “what happened” or “in which order”.

- **Target-state automation identity:** Script-generated child dispatch persists the complete Command-Handoff Identity separately from the effect ledger/storage projection. The child identity is the complete source/target runtime scope plus `(automationDispatchId, commandOrdinal)`, as defined by the [normative Command-Handoff Identity table](./system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state). The complete applicable parent Trigger Identity, including `bindingId` when applicable, and `outboxWorkItemId` are retained for correlation only and are excluded from child uniqueness, deduplication, and replay selection. `scriptEventId` alone cannot distinguish fan-out commands. `effectKey` may encode the child pair only as deterministic descriptor/correlation metadata and scheduler tie-break metadata. It never replaces the root `EffectId`, typed operation, exact target aggregate, immutable request digest, sealed participant/manifest context, or digest-conflict enforcement.
- **Current-live fallback:** The Game Session handoff does not yet carry `commandOrdinal` or the full Trigger Identity. `TickStagingService` currently derives `effectKey` from `commandId`, falling back to a hash of command text plus the staging slot when no command id is available. This fallback is not the target-state automation identity and must not be documented as though `(automationDispatchId, commandOrdinal)` were enforced live.

### Coordination Exposure and Tick Replay Window

Redis coordination state has an environment-measured unreplicated-write exposure (see `system-architecture-redis.md` and `system-architecture-redis-operations.md`). The exposure is infrastructure evidence, not a product RPO and not permission to silently lose a number of ticks:

- `redis_unreplicated_write_window_slo_ms` is established from measured AOF, replication, promotion, and failover evidence. `ticks_exposed = ceil(window_ms / tick_interval_ms)` is diagnostic only.
- Tick-driven designs must tolerate coordination loss, delay, duplication, and replay while applying the ADR 0058 class-specific outcome matrix:
  - accepted commands retain durable command truth or explicitly terminalize as not applied;
  - staged effects, retries, and correctness-bearing timers use durable intent and evidence-backed replay or terminalization;
  - session, lease, cache, and wake-up loss may affect latency or require reauthentication but cannot erase canonical state; and
  - premium, financial, cross-tenant, and unique external effects use durable owner/outbox boundaries.
- Region leases may be briefly lost and re-acquired under the same or a new `region_epoch`; a breached SLO expands reconstruction, terminalization, and operator-reconciliation scope rather than weakening those outcomes.

## Tick Coordination Exposure Contract

The tick system and the Redis-measured exposure SLO combine into a simple contract:

- For the complete expected concrete participant-projection set for each root effect, including each namespace-complete `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, region_epoch, tickId, effectKey, targetAggregateType, targetAggregateId)` projection linked to its root `EffectId` and guard contract, every expected projection must eventually have exactly one **terminal** outcome in PostgreSQL (`APPLIED` or `ABANDONED`) when the existing evidence policy permits terminalization. `playableStateScope` is exact-validated evidence, and `region_epoch`/`tickId` are ordering metadata; neither is a participant guard uniqueness dimension. Reconciliation must reject missing, extra, partial, or conflicting projections rather than treating one root row as sufficient, even if:
  - Coordination writes near the measured exposure window are dropped, delayed, or replayed, or
  - Executors crash and re-acquire leases under the same `region_epoch`.
- Any work that cannot be safely replayed after Redis loss or tick re-execution must be:
  - Guarded with idempotency checks that detect and short-circuit replays, or
  - Intentionally marked `ABANDONED` in the tick effect ledger with a precise reason only when durable evidence proves it was not applied and the existing policy permits terminalization (for example, reset scopes as described in the failures and operations doc). Inconclusive old-epoch work remains non-terminal reconciliation-required under its original root `EffectId`; reset scope alone does not authorize bulk terminalization.

Players may observe brief rollbacks or duplicated feedback around failover boundaries, but they must never experience permanent double-application of critical effects or silent corruption of authoritative state.

Redis exposure thresholds are defined in `system-architecture-redis-operations.md` under Redis availability and safety guarantees. This section captures only the conceptual relationship between that infrastructure evidence and the tick invariants it is meant to uphold.

### Isolation Within a Tick

Per-tick isolation is defined explicitly so replay and fairness remain deterministic:

- Actions may only see staged state from the **current** tick for their `<tenantId, gameInstanceId, regionId>`.
- Changes from other tick regions or from **future** ticks in the same region are invisible while a tick is in progress.
- Changes staged earlier in the same tick are composable only where the semantic phase permits it: confirmed start-passive and inbound outcomes may feed root actor resolution, and a generated effect may observe its own parent's confirmed result and deterministic child ordinals. Root actor resolution does not observe mutations from other root actor actions, or their generated effects, in the same tick; those cross-actor consequences become visible in the next tick under [ADR 0070](./decisions/adr-0070-bounded-within-tick-visibility-by-semantic-phase.md).
- When required state is missing or inconsistent, the action must fail and retry under the normal retry/backoff rules rather than speculatively mixing cross-tick or cross-region reads.

These rules ensure clean, replayable ticks and keep visual or scripting shortcuts from leaking inconsistent state across ticks.

## Locking and Multi-Entity Commands (Conceptual)

Every Redis tick Lua invocation acquires at most one `tick:{tenantRegionTag}:lock:<entityId>`. Piecemeal acquisition across invocations and any multi-entity-lock whitelist are prohibited. A script fails closed when its declared key set or operation would require more than one entity lock. This is the hard initial rule from [ADR 0074](./decisions/adr-0074-one-entity-lock-per-redis-script.md).

Tick and lease keys are created, renewed, and released only through registered Lua helpers. Redis locks are coordination optimizations, not gameplay transactions: they do not replace the region lease, durable executor fence, exact owner preconditions, durable idempotency guards, or command outcome reconciliation.

Multi-entity commands use one owning PostgreSQL transaction when the invariant is co-located. Split-authority commands use exact aggregate/scope/epoch/version/holder/location preconditions, stable effect guards, decomposed durable effect legs, bounded reconciliation, or a reservation/saga workflow. Trade, grapple, group movement, and multi-target combat therefore remain supported without multi-lock Lua.

The registry and focused proof must reject scripts declaring or receiving more than one entity-lock key and prove stale lease/lock rejection, expiry cleanup, fence rejection, and absence of multi-lock call sequences. A future bounded multi-lock facility requires a new ADR with a dependency/wave model and atomicity, TTL, failure, replay, and observability proof.

## Timers and Time Scaling (Conceptual)

Tick timers (cooldowns, regeneration, delayed effects) are:

- Stored in per-region sorted sets such as `timer:{tenantRegionTag}`, where the score is an absolute millisecond timestamp and each member encodes the target entity/effect.
- Evaluated using a single, consistent application time source (NTP-synchronized wall clock); Redis server time is not used for timer comparisons.
- Treated as absolute wall-clock due times: changing `tick_interval_ms` does not rescale existing timer scores, and any time-scaling logic must be applied when scheduling (producing a new `due_ms`), not by rewriting past timers.
- Drained with bounded work per tick (for example, up to `game.tick-max-timers` timers per region per tick) so delayed or bursty timers do not turn a single tick into unbounded work.
- Implemented using deterministic, idempotent Lua scripts that accept `now_ms` as a caller-supplied `ARGV` value; scripts must not call Redis `TIME`, and AOF replay reuses the same `ARGV` values.

All writes to timer keys (`timer:{tenantRegionTag}`) are performed under the same region lease and Lua scripts as tick processing; domain services must not modify timer keys via ad-hoc Redis commands. This keeps timers and command queues in the same concurrency domain.

Timer and retry keys are **volatile coordination structures**, not durable schedules. After coordination resets or data loss, only schedules that are also represented durably elsewhere (for example PostgreSQL-backed automation schedules or explicit domain state) are expected to be recovered or re-derived; the existence of a `timer:{tenantRegionTag}` entry is never the only record of a correctness-critical timer.

Durations may be scaled dynamically:

- `scaledDuration = baseDuration * timeScaleMultiplier`
- Used for spell effects, world modifiers (for example, “slow motion” zones), or status changes.
- Time scaling changes **durations**, not tick cadence; ticks themselves advance at their configured interval.

## Bounded Effect Chaining

Generated effects are bounded by a shared bootstrap depth ceiling (`8`), deterministic root-chain count and cost budgets, and a deterministic per-target cap. Platform hard ceilings bound every setting; operators and authored features may lower them but may not raise them. Each child records immutable parent/root identity, `depth = parent.depth + 1`, and a digest-covered deterministic child ordinal.

Admission evaluates all limits deterministically. Only the child that would exceed a limit is suppressed; committed parents and earlier children are not rolled back. Every authored child is classified as required or optional so suppression derives truthful `SUCCESS`, `PARTIAL`, or `FAILED` outcomes. Durable suppression evidence records root/parent identity, feature/script/version, child ordinal, limit reason and actual/configured values, classification, and resulting player outcome. Metrics use bounded reason/depth/cost/classification dimensions; raw identities remain audit data. See [ADR 0075](./decisions/adr-0075-depth-cost-and-count-bounds-for-generated-effect-chains.md).

## Tick Budget, TTLs, and Region Health (Conceptual)

Two related configuration concepts control how long tick work is allowed to run and how long leases/locks are held:

- `tick_interval_ms` – the configured target interval between ticks for a region.
- `tick_budget_ms` – the soft execution budget for a tick. The shared bootstrap default starts from `tick_interval_ms * 0.8`; the canonical resolver rounds to the nearest integer millisecond with exact halves rounded upward and validates a positive integer before deriving lock TTL.
- `lock_ttl_ms` – the TTL used for per-entity locks. The shared bootstrap derivation starts from the resolved `tick_budget_ms * 8` and applies the same deterministic positive-integer rounding rule. `tick_lock_ttl_ms` is the effective regional `lock_ttl_ms` health metric. Numeric minimum and maximum bounds remain pending an owning settings decision.

These formulas are shared bootstrap defaults only. Production values remain explicitly pending evidence from p95/p99 execution, RPC latency/errors, runtime pauses, cleanup lag, takeover/recovery objectives, representative load, and fault injection. One resolver owns the defaults, operator safety settings, validation, and provenance; numeric minimum and maximum bounds remain pending the owning settings decision, and services may not define private derivations. Cadence, execution budget, and lock lifetime are separate health dimensions. See [ADR 0073](./decisions/adr-0073-evidence-calibrated-tick-budgets-and-lock-ttls.md).

The only allowed exception is an explicit **solo-tick budget mode** for commands marked `requiresSoloTick: true`:

- `solo_tick_budget_ms` is a second canonical shared setting, not an ad-hoc per-command override.
- When enabled for a tick, `solo_lock_ttl_ms` is derived from `solo_tick_budget_ms` using the same shared helper family and operator-visible health model.
- The scheduler must admit solo-budget ticks only when the command is the sole work item for that region tick.
- A deployment that does not enable `solo_tick_budget_ms` must treat `requiresSoloTick` as isolation-only; it does not permit budget overruns beyond the normal `tick_budget_ms`.
- During a solo-budget tick, health and alerting use the solo-derived ratios (`tick_execution_time_ms_* / solo_lock_ttl_ms`) for that tick rather than the normal `tick_lock_ttl_ms` denominator.

Changing tick cadence is also constrained by replay determinism:

- `tick_interval_ms` is fixed within a live `region_epoch`.
- Any cadence change that would alter timer ordering or due normalization requires an explicit `regionEpoch` bump and timer re-derivation/reconciliation for the affected region.

Worked cadence-change example:

1. Region `R7` is running at `tick_interval_ms = 100` in `regionEpoch = 13`.
2. Operators decide to move `R7` to `tick_interval_ms = 200`.
3. Game Session pauses the region and bumps `regionEpoch` to `14` rather than changing cadence in place.
4. Timer ordering state is re-derived for the new epoch, including canonical `due_tick_id` values for any timers that must survive the change.
5. The new epoch resumes at `lastCommittedTickId = -1`, so the first committable tick under the new cadence is `tickId = 0`.

At runtime, observed tick durations are compared against lock TTLs using Prometheus-facing series such as `tick_execution_time_ms_p95{scope_class}` and `tick_execution_time_ms_p99{scope_class}` (derived from `tick_execution_time_ms_bucket{scope_class,le}` recording rules). Ratios like `tick_execution_time_ms_p99{scope_class} / tick_lock_ttl_ms{scope_class}` are detection and escalation signals for bounded scope-class rollups. The `scope_class` value is the controlled aggregation class (`region`, `game_instance`, `tenant`, or `cluster`), never an individual region or other raw runtime identity; the exact `<tenantId, gameInstanceId, regionId>` tuple and its health remain authoritative control-plane/runtime-health evidence. A class-level rollup cannot identify or set the health of any individual region.

### Canonical Region Health States and Threshold Source

This table is the single source of truth for region health state names and threshold intent across tick, Redis, and scaling docs:

| State | Meaning | Primary triggers |
| --- | --- | --- |
| `RUNNING` | Region is making normal forward progress. | The authoritative control-plane/runtime-health record reports advancing commit progress without a safety-limit breach. A class-level execution-time ratio below the degraded threshold is supporting rollup evidence only. During an admitted solo-budget tick, evaluate the same state against the solo-derived TTL instead. |
| `DEGRADED` | Region is still progressing but close to safety limits. | The authoritative control-plane/runtime-health record reports progress near safety limits or a remote/retry backlog over budget. A class-level execution-time ratio near or above the degraded threshold is detection/escalation evidence only. During an admitted solo-budget tick, evaluate the same state against the solo-derived TTL instead. |
| `STALLED` | Region lease may still be held but progress has stopped. | No successful commits for multiple `tick_interval_ms` windows, repeated failed ticks, or persistent stuck cleanup/ledger signals. |
| `PAUSED` | Region is intentionally paused by control plane or maintenance flow. | Operator/control-plane pause for reset, migration, or incident mitigation. |

Threshold values and alert windows are defined by this document’s ratio formulas plus the concrete metric thresholds in `system-architecture-redis-operations.md`; they produce detection/escalation rollups such as `tick_status{scope_class,status="RUNNING|DEGRADED|STALLED|PAUSED"}`. These rollups do not enforce or write an individual region status. Exact per-region `RUNNING`, `DEGRADED`, `STALLED`, or `PAUSED` status and actionability come from the authoritative runtime-health/control-plane record: current live uses `GetRuntimeOwnershipStatus` with `ObserveRuntimeTickProgress`, while target state uses `RegionStatus` through `GetRegionTickStatus`. Any later metric reference that omits `{scope_class}` is shorthand for the same bounded class-level series; it must not be read as an exact region selector.

In addition to timing-based health, Game Session tracks **forward progress** for each `<tenantId, gameInstanceId, regionId>`:

- A simple progress record includes:
  - The tickId or timestamp of the last successful commit.
  - A counter of consecutive failed ticks due to downstream errors or timeouts (not mere lock contention).
- A region is treated as **stalled** when it still holds `tick-executor-lease:{tenantRegionTag}` but has not committed successfully for several multiples of `tick_interval_ms` or has exceeded a threshold of consecutive failures.

Conceptually:

- Lease ownership indicates **who** is allowed to coordinate work for a region.
- Progress signals indicate whether that owner is actually advancing game state.

Downstream behavior for stalled regions (rejecting new commands, marking instances unhealthy, and so on) is described in the failures and operations doc; this section captures only the invariants that distinguish “lease held but stalled” from `RUNNING`, `DEGRADED`, and intentionally `PAUSED` regions.

## Retry and Backoff Invariants

Retry behavior is bounded and failure-class-specific while preserving fairness:

- Each rescheduled item carries its lane, stable action/effect identity, bounded retry counter, and a `next_eligible_tick_id` value; these are eligibility/attempt metadata separate from the original participant ledger projection and guard identity. `next_eligible_tick_id` gates eligibility only and never replaces the original `due_tick_id`; once eligible, a retry preserves `due_tick_id` and re-enters the ADR 0065 ordering tuple. Lock-contention retries use capped exponential backoff in ticks, for example `nextTick = currentTick + min(2^retryCount, MAX_BACKOFF_TICKS)`. Dependency outages, ambiguous dispatch, stale preconditions, and persistent or unclassified failures use their distinct shedding, reconciliation, re-resolution, or escalation/quarantine policy under [ADR 0076](./decisions/adr-0076-failure-class-specific-durable-tick-retries.md), not that contention formula.
- Actor-action retries compete in the actor-action lane and preserve the original action identity; passive/effect retries remain passive and never create a new same-tick root actor action. Retries are scheduled **no earlier than a future tick**; the executor never spins inside a single tick waiting for locks. A fresh root is not created for these ordinary retries. Only a post-abandon re-drive, after terminal `ABANDONED` and source-claim terminalization, may allocate a later coordinate and fresh root with durable lineage.
- After a bounded number of failed attempts (for example `MAX_RETRIES`), the command is surfaced as retry-budget exhausted and escalated, with player-visible diagnostics and metrics/logs capturing the contention; retry exhaustion alone never authorizes `ABANDONED` or another permanent terminal failure. Terminalization still requires applicable durable evidence, while inconclusive work remains reconciliation-required until that evidence exists.
- Retries retain their original lane, priority, `entity_enqueue_seq`, source kind, and stable command/effect identity. When eligible, they re-enter the persisted fair scheduler; they do not enter a retry-private FIFO or gain priority by failing. Within an entity, the canonical ordering is `(priority, due_tick_id, entity_enqueue_seq, source_kind, commandId_or_effectKey)`. Across entities, persisted rotating/deficit scheduling advances only with the durable selected batch and provides eventual capacity to permanently eligible non-best-effort work within a healthy epoch. See [ADR 0065](./decisions/adr-0065-deterministic-fair-entity-tick-scheduling.md) and [ADR 0076](./decisions/adr-0076-failure-class-specific-durable-tick-retries.md).
- Metrics such as `tick_conflict_hotspot_detected_total` and `tick_retry_queue_depth` surface regions where contention is persistent so operators can adjust region layout, tick budgets, or feature design.

These invariants ensure that contention is handled predictably: retries remain bounded, hot entities cannot monopolize the loop indefinitely, and operators have clear signals when configuration or design changes are required.

At the configuration level:

- Lua staging scripts enforce hard per-tick caps on how many commands or events move from queues into `pending`, using keys such as:
  - `game.tick-max-commands`
  - `automation.tick-max-events`
  - These caps exist so no single player or script can monopolize either lane, even if it enqueues many actions or effects; excess work spills into subsequent ticks according to the lane budgets, backoff, and the persisted ordering tuple.

Runtime health pressure is also detected via ratios such as `tick_execution_time_ms_p95{scope_class}` or `tick_execution_time_ms_p99{scope_class}` over `tick_lock_ttl_ms{scope_class}`. These are class-level Prometheus rollups for detection and escalation, not per-region status or actionability:

- `RUNNING` – the authoritative runtime-health record reports normal forward progress; a low class-level p99 ratio is supporting evidence only.
- `DEGRADED` – the authoritative runtime-health record reports progress near safety limits; a sustained high class-level p99 ratio is supporting detection/escalation evidence.
- `STALLED` – authoritative forward progress has stopped even if class-level timing ratios are noisy or unavailable.

This keeps fairness and safety enforceable through bounded per-tick work and timing-based health checks without introducing alternate state names in other docs.

## Tick Regions, Global Effects, and Idle Background Ticks

Tick work is scoped to **regions** so that:

- Regions advance independently and can be assigned to different executors.
- Failures and stalls are isolated to a region rather than the whole game.

Two additional behaviors complete the mental model:

- **Global or multi-region effects use fan-out**:
  - Game Session creates one durable parent identity, freezes the affected region set and topology generation at acceptance, and creates idempotent durable child/injection rows for every selected region before publishing wake hints.
  - A bounded global outstanding-work cap and deterministic fair per-region admission provide backpressure. Child work enters the ordinary target-region inbound-effect path and remains subject to lease, fence, epoch, lane, budget, and idempotency rules; a wake hint never creates gameplay authority.
  - A paused or stalled region is not bypassed by a force-tick hint. Parent reconciliation records waiting, partial injection, and feature-declared terminal outcomes.
- **Idle regions still advance time via lightweight ticks**:
  - Idle/background behavior does not create a second slower canonical tick cadence inside the same live `region_epoch`.
  - A truly empty cadence boundary commits one lightweight durable fenced watermark/heartbeat and advances exactly one canonical `tickId` without domain RPCs, entity locks, Redis `pending`, or effect-batch creation. Tick/game time continues while the region is healthy and freezes only in canonical `PAUSED` or `STALLED` state.
  - Physical empty-boundary advancement remains the initial model. Sleep or fast-forward ranges require a separate decision proving due-work completeness, pause accounting, deterministic materialization/replay, wake-loss safety, and consumer migration.
  - Any true cadence change for an idle region still requires the same explicit epoch bump and timer re-derivation rules described above.

The underlying Redis key layout and shard-locality rules for these behaviors are documented in the Redis architecture; this section captures the conceptual guarantees for designers and implementers.

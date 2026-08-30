# FireMUD Redis Usage & Profiles

This document describes **how** FireMUD uses Redis in different roles and environments. It complements the conceptual hub (`system-architecture-redis.md`) by defining concrete usage patterns, profiles, and configuration wiring.

## Implementation Status

The live automation handoff still carries optional `dueTickId` for scheduler/timer work and omits a due point for immediate event-driven handoffs. The target tagged, mutually exclusive `duePoint` contract is not yet the live wire boundary; callers must not represent a wall-clock due point as a tick value.

The target automation handoff also requires the complete Trigger Identity, including `scriptPinEpoch`, plus `automationDispatchId` and `commandOrdinal`. The current Game Session request does not yet carry that complete contract, including `scriptPinEpoch`, so the target fields and uniqueness rules below are not implementation proof.

Separate Coordination and Cache/Rate-Limit Redis processes exist in current manifests, but role-specific application clients, ACLs, key/script registration, and ownership proof under [ADR 0171](./decisions/adr-0171-separated-redis-role-processes-and-owned-keyspaces.md) remain incomplete.

---

## Table of Contents

- [Implementation Status](#implementation-status)
- [Redis Roles and Usage Patterns](#redis-roles-and-usage-patterns)
- [Environment Profiles and Mappings](#environment-profiles-and-mappings)
- [Maxmemory, Eviction, and Sizing](#maxmemory-eviction-and-sizing)
- [Configuration Wiring and Misconfiguration Guards](#configuration-wiring-and-misconfiguration-guards)
- [Related Documentation](#related-documentation)

---

## Redis Roles and Usage Patterns

FireMUD runs two logical Redis roles in all non-ephemeral environments:

Scope-key convention: `{tenantRegionTag}` is the canonical opaque tag for the complete `<tenantId, gameInstanceId, regionId>` scope, while `{tenantInstanceTag}` is the canonical opaque tag for `<tenantId, gameInstanceId>`. Region-scoped coordination metadata keys therefore carry `gameInstanceId` through `{tenantRegionTag}`; callers must not substitute a tenant-only or region-only tag.

- **Coordination Redis**
  - Responsibilities:
    - Tick queues, locks, timers, and executor leases.
    - Gameplay session liveness, binding, and rebind coordination. Durable semantic reconnect context remains Game Session-owned persistence under [ADR 0134](./decisions/adr-0134-bounded-durable-semantic-reconnect-context.md) and [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#canonical-resume-context-model); Redis gameplay session state is not that context.
    - Retry metadata and conflict tracking.
    - Automation coordination structures that participate in tick timelines under Automation & Scripting ownership and Game Session enqueue contracts.
  - Characteristics:
    - Treated as a long-running **coordination buffer with bounded tail-loss** in persistent environments; durable history for tick effects and gameplay outcomes lives in PostgreSQL tick effect ledgers and domain stores.
    - Game Session owns only its tick/session coordination prefixes and registered scripts: gameplay coordination families such as `tick:*`, `timer:*`, `retry:*`, `tick-executor-lease:*`, the canonical `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` records, and their derived `session:game:index:*` projections, including the separately approved `session:game:auth:issuer-generation:v1:*` consumer projection. Account Service retains ownership of the explicit `session:auth:token:*` and `session:auth:generation:*` prefixes, and Automation & Scripting Service retains ownership of its automation-specific prefixes and scripts as documented below; Game Session invokes those contracts but does not write them.
    - AOF enabled in `dev_local`, `hobby_self_hosted`, and `production_clustered`–like profiles.
    - Subject to tail‑loss SLOs and replay guarantees described in the Redis hub doc.
  - Example prefixes:
    - `tick:{tenantRegionTag}:*`
    - `timer:{tenantRegionTag}`
    - `retry:{tenantRegionTag}`
    - `tick-executor-lease:{tenantRegionTag}`
    - `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`
    - `session:game:index:*` derived gameplay lookup projections
    - `sessionctx:*` bootstrap/session-context keys used by the current Game Session implementation.
    - Automation-owned coordination prefixes are documented separately and are not Game Session-owned.

- **Cache/Rate‑Limit Redis**
  - Responsibilities:
    - Read‑side caches for expensive aggregates (room views, inventories, topology slices).
    - Rate‑limit buckets (`ratelimit:*`) and small operational counters.
    - Best‑effort automation queues and quotas that can be rebuilt from domain state.
  - Characteristics:
    - Treated as **non‑authoritative** and fully reset‑tolerant.
    - Eviction and TTL are part of normal behavior; designs must tolerate cold caches.
    - Shared infrastructure libraries enforce the default cache schema and TTL policies (including serialization/TTL validation and global size/pressure envelopes), but they do not own each cache's semantic contract. The owning service and the canonical cache catalog retain per-prefix choices, including the `view:room-look:*` TTL, size, invalidation, metrics, and reset contract owned by Game Session.
  - Example prefixes:
    - `inventory:<tenantId>:<playableStateNamespaceId>:<containerId>`
    - `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>:<sessionId>:<viewerContextHash>:<policyContextHash>:<readFenceHash>` (target-only Game Session disposable presentation/redraw helper; exact room, viewer/session, policy context, and applicable read-fence context bound. Current `ResolveLook` behavior is uncached; cache misses, unavailable or untrusted cache/fence evidence, and write refusal fall back to the authoritative uncached `ResolveLook` result. See the [canonical Class-B contract](./system-architecture-redis-cache.md#canonical-viewroom-look-class-b-contract); never semantic reconnect context, frame/output replay, archive, or delivery ledger.)
    - `world-dynamic:<tenantId>:room-dynamic:<gameInstanceId>:<roomInstanceId>`
    - `ratelimit:<tenantId>:<subjectHash>:<timeWindow>` (one opaque stable subject hash per individual subject)
    - `automation:queue:{tenantInstanceTag}:<entityId>` and automation quota counters.

Coordination Redis and Cache/Rate‑Limit Redis must use **separate processes and endpoints** in every non-ephemeral or player-facing environment, including `local-dev` and hosted `pr-preview`, so cache eviction/pressure cannot silently impact coordination SLOs. They may be two containers or processes on the same hobby host or cluster node; separate hardware is not required. The only supported exception is an explicitly labelled one-shot ephemeral test/CI stack other than hosted `pr-preview`; it may collapse roles temporarily only when reset-tolerant, must visibly surface the shared endpoint, and provides no role-isolation, replay, tail-loss, or SLO evidence. Such a stack may still run reset-tolerant coordination tests, but it must not be used to establish role-isolation, replay, tail-loss, or SLO proof. See [ADR 0171](./decisions/adr-0171-separated-redis-role-processes-and-owned-keyspaces.md) and [Environment Profiles and Mappings](#environment-profiles-and-mappings).

New prefixes must declare:

- Which role they live on (Coordination vs Cache/Rate‑Limit),
- Whether they are reset‑tolerant, reset‑sensitive, or reset‑forbidden, and
- How they behave under tail‑loss and eviction.

Rate-limit cardinality must be bounded by TTL, active-subject admission, and deployment/per-tenant memory envelopes rather than modulo collision pools. Deliberately shared coarse buckets are separately named heuristics and cannot alone impose individual security consequences. Each limiter also declares its store-unavailable behavior and whether it is a heuristic or hard gate; evictable Cache/Rate-Limit Redis is never the sole authority for a hard invariant.

The **Redis Cheat Sheet** keeps a representative mapping from prefixes to roles and owning services.

Keep the cheat sheet and the owning service Redis sections aligned with the canonical names in this document, especially:

- `tick:{tenantRegionTag}:session-binding:<entityId>`
- `binding_generation`
- The target full automation Trigger Identity and per-command `automationDispatchId` plus `commandOrdinal` child identity.

### Automation & Scheduler Coordination Prefixes

A small set of automation/scheduler-specific prefixes live on Coordination Redis but remain **reset-tolerant**:

- `script-scheduler:{tenantRegionTag}:lastTickId`
  - Role: Coordination Redis.
  - Owner: Automation & Scripting Service.
  - Purpose: per-region derived discovery hint for “every N ticks” schedulers tied to the canonical `(regionEpoch, tickId)` timeline. Its value is `{regionEpoch, latestTickId}`; consumers reject and rebuild it when the stored epoch differs from the authoritative epoch. It does not store `streamOffset`; `tick-events-offset:{tenantRegionTag}:<consumerId>` is the sole event-stream offset record for each consumer.
  - Reset behavior: classified as reset-tolerant in the reset policy matrix; region/tenant/cluster resets may drop this key. After resets or data loss, Automation & Scripting recomputes due work from PostgreSQL schedules and the tick heartbeat as described in the tick and scripting docs. Duplicate trigger prevention comes from a durable PostgreSQL trigger-instance or outbox row with a uniqueness projection over the applicable identity branch. For schedule-derived triggers, the pre-claim branch includes `(tenantId, gameInstanceId, playableStateScope, stableOwnerKind, stableOwnerId, regionId, regionEpoch, entityId only when targetScopeType is entity-scoped, scriptId, eventType, eventSchemaVersion, scriptPatchVersion, scriptPinEpoch, isDryRun, scheduleDefinitionId, targetScopeType, targetScopeId, duePoint, triggerMode)`, plus `resumeWindowId` only when `triggerMode=CATCH_UP` and plugin identity `(pluginId, pluginVersionId, bindingId)` when applicable; `resumeWindowId` is absent otherwise. For plugin-owned schedules, the captured `pluginActivationEpoch` is an additional tagged dimension of due-candidate and firing-claim identity and therefore of the derived event-scope scheduler `scriptEventId`; the captured `lifecycleRevision` travels alongside as immutable non-identity lifecycle-fence evidence. Neither field is a per-command child-key dimension, and both are revalidated through admission, retry, replay, and recovery. The winning claim then derives one immutable event-scope scheduler `scriptEventId` from that candidate identity and propagates it into each resolved handler's normalized Trigger Identity; `pluginActivationEpoch` remains excluded from that handler identity. Contenders reuse the winning event-scope ID instead of including a generated value in the race key. `duePoint` is exactly `dueTickId:<value>` or `dueAt:<epochMillis>`; if physical storage uses nullable `dueTickId`/`dueAt` columns, the alternate field is explicitly `NULL`, never an empty/zero substitute, and both fields may not be null or populated together. For an immediate event-driven trigger, the scheduler-only fields `scheduleDefinitionId`, `duePoint`, and `triggerMode` are absent, both physical due columns are `NULL`, and the event-driven branch includes the generated stable `scriptEventId` as the occurrence discriminator. Storage must represent the event-driven versus scheduler-derived branch explicitly, for example with a discriminator or branch-specific unique constraints, rather than allowing nullable scheduler fields to alias distinct event triggers. The per-command `automationDispatchId` and `commandOrdinal` are separately unique under the same runtime timeline and link the trigger claim to each emitted command; they remain child handoff identity and do not replace the full Trigger Identity. The row retains the full schedule projection when applicable (`cadence`, `unit`, `priorityTag`, `targetScopeType`, `targetScopeId`, binding priority/exclusivity, `scheduleSemanticsHash`, the pin operation's `controlPlaneRequestId`, owner/version metadata, due-point state, and `runtimeRegionId`/`runtimeRegionEpoch`), including the captured plugin lifecycle pair when applicable; it is not reduced to this Redis checkpoint or a schedule ID alone.
- `automation:timer:{tenantRegionTag}`
  - Role: Coordination Redis.
  - Owner: Automation & Scripting Service.
  - Purpose: per-region timer/index structure for script intervals and timer-driven triggers; stored entries remain instance-aware via payload identity such as `gameInstanceId`.
  - Reset behavior: classified as reset-tolerant in the reset policy matrix; keys may be dropped by scoped coordination resets and are rebuilt from PostgreSQL-backed schedules, trigger-instance rows, and heartbeat progress.

Durable automation schedules, quotas, script configuration, and trigger-instance de-duplication live in PostgreSQL; these coordination prefixes are latency and progress hints only and must not be treated as the primary record of “which scripts should run”. Designs that introduce new automation-related coordination prefixes must register them in the reset policy matrix and document how they recover from resets.

The cheat sheet and the Automation & Scripting / Game Session service docs should also expose the operator-facing outcome vocabulary for fairness-critical automation admission so duplicate-dispatch no-ops, stale-timeline rejections, and successful admissions are recognizable without reading implementation code.

### Automation Routing: Coordination vs Cache/Rate-Limit

Automation workloads split into two broad classes, with different expectations and Redis roles:

- **Gameplay-equivalent, fairness-critical automation**
  - Examples: emitted gameplay commands, automated movement, and AI that compete with player actions for the one root actor-action slot; timer-driven buffs or debuffs that affect combat state must explicitly declare whether the timer makes the actor act or applies a passive effect.
  - Redis role: **Coordination Redis**.
  - Prefixes: use the same coordination families as player commands (for example `tick:{tenantRegionTag}:queue:<entityId>`, `tick:{tenantRegionTag}:pending`, `timer:{tenantRegionTag}`, and scheduler checkpoints such as `script-scheduler:{tenantRegionTag}:lastTickId`), but only through Game Session-owned enqueue contracts.
  - Guarantees (target state; these are not current implementation proof):
    - Subject to the same `(regionEpoch, tickId)` timeline, leases, and lock semantics as player commands.
    - Emitted gameplay commands, automated movement, and AI are `actor_action` work competing for one root actor-action slot per eligible entity. Timer-driven buffs/debuffs declare `actor_action` when they make the actor act, or `passive_effect` when they apply a passive pulse/effect; passive work uses a separately bounded lane and does not consume the target actor slot.
  - Design rule (target state): every source declares its lane and bounded `cost_class`, and automation in this category follows the canonical [Tick System and Runtime Design](./system-architecture-ticks.md) contract plus the [Scripting Scheduler and Timer Lifecycle](./system-architecture-scripting-scheduler-and-timers.md) owner contract. It must be reviewed like core gameplay logic and is **not** allowed to depend on TTL-only caches or best-effort queues for correctness.
  - Current implementation boundary: `EnqueueAutomationCommandIfAbsent` does not yet carry or fully enforce the target lane, one-root actor-slot, or bounded-cost guarantees. Its current wire, durable-storage, and admission path remains narrower than the target contract (including the complete Trigger Identity, `commandOrdinal`, `lane`, and `cost_class`), so current enqueue success is not proof of those guarantees.
  - Canonical handoff contract (target state):
    - When work references a revocable component, the durable trigger/admission row and handoff payload retain the applicable component-revocation security-policy fence as independent parent evidence. It is included in the canonical handoff request digest and immutable readback/conflict comparison, revalidated through handoff, retry, replay, recovery, and final-effect boundaries, and excluded from Trigger Identity, Command-Handoff Identity, and their uniqueness keys; a changed or unavailable fence fails closed under the established disposition rather than minting a replacement identity.
    - Automation & Scripting creates or reuses a durable PostgreSQL trigger-instance / outbox row keyed by the applicable Trigger Identity branch. A schedule-derived pre-claim row uses `(tenantId, gameInstanceId, playableStateScope, stableOwnerKind, stableOwnerId, regionId, regionEpoch, entityId only when targetScopeType is entity-scoped, scriptId, eventType, eventSchemaVersion, scriptPatchVersion, scriptPinEpoch, isDryRun, scheduleDefinitionId, targetScopeType, targetScopeId, duePoint, triggerMode)`, plus `resumeWindowId` only when `triggerMode=CATCH_UP` and plugin identity `(pluginId, pluginVersionId, bindingId)` when applicable; `resumeWindowId` is absent otherwise. For plugin-owned schedules, the captured `pluginActivationEpoch` is an additional tagged dimension of due-candidate and firing-claim identity and therefore of the derived `scriptEventId`; the captured `lifecycleRevision` travels alongside as immutable non-identity lifecycle-fence evidence. Neither field is a per-command child-key dimension, and both are carried and revalidated through admission, handoff, retry, replay, and recovery per [Scripting Contracts §8](./system-architecture-scripting-contracts.md#8-plugin-version-fencing-and-control-plane-scope). The winning claim derives one immutable `scriptEventId`, and retries reuse it. An immediate event-driven row omits the scheduler-only fields and uses its generated stable `scriptEventId` to distinguish one event occurrence from another; its storage uniqueness branch must remain distinct from scheduled rows and includes the server-derived `sourceService` in its event-scope claim when the event is custom or service-specific. The immutable source Trigger Identity, including source `regionId` and `regionEpoch`, is retained on the row; current enqueue routing fields are separate. The complete per-command child handoff identity comprises the source runtime scope, optional distinct target runtime scope, `automationDispatchId`, and `commandOrdinal`; it is stored separately from the parent Trigger Identity. The row also retains `cadence`, `unit`, `priorityTag`, `targetScopeType`, `targetScopeId`, binding priority/exclusivity, `scheduleSemanticsHash`, the pin operation's `controlPlaneRequestId`, owner/version metadata, due-point state, and `runtimeRegionId`/`runtimeRegionEpoch` when those fields apply, plus the captured plugin lifecycle pair when applicable.
    - Automation & Scripting then calls a Game Session gRPC/API contract such as `EnqueueAutomationCommandIfAbsent`, carrying the complete immutable Trigger Identity and child handoff identity:
      - Parent Trigger/event-scope evidence: `tenantId`, `gameInstanceId`, `playableStateScope`, source `regionId`, source `regionEpoch`, `entityId` only for an entity-scoped trigger, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `scriptPinEpoch`, `scriptEventId`, and `isDryRun`, plus applicable plugin identity `(pluginId, pluginVersionId, bindingId)`, captured `(pluginActivationEpoch, lifecycleRevision)` lifecycle-fence evidence, and the server-derived `sourceService` for custom or service-specific events.
      - Scheduled rows also carry `scheduleDefinitionId`, `targetScopeType`, `targetScopeId`, `triggerMode`, exactly one tagged `duePoint`: `dueTickId:<value>` or `dueAt:<epochMillis>`, and `resumeWindowId` only when `triggerMode=CATCH_UP`; immediate event-driven rows use the separate event branch, omit scheduler-only fields and `duePoint`, and carry a stable `scriptEventId`; their physical `dueTickId` and `dueAt` fields are both `NULL`.
      - Child handoff identity: complete source runtime scope, optional distinct target runtime scope, `automationDispatchId`, and `commandOrdinal`. `commandOrdinal` identifies the emitted command within the dispatch and is not replaced by `commandKind` or by `scriptEventId`.
      - Current enqueue routing fields, including `runtimeRegionId` and `runtimeRegionEpoch` when applicable, are carried separately from immutable source Trigger Identity.
      - The accepted immutable scheduling metadata is also carried explicitly: `lane` (`actor_action` or `passive_effect`) and bounded `cost_class`, as defined by the [Scripting Scheduler and Timer Lifecycle](./system-architecture-scripting-scheduler-and-timers.md) owner contract, with the current gap tracked in [Automation and Scheduler Runtime](../project-management/implementation-tracking/automation-and-scheduler-runtime.md). Game Session copies these values into the durable admission row and selected-work manifest before any tick-queue mutation. They are included in the canonical handoff request digest and durable readback/conflict comparison, but are immutable conflict fields rather than additional dimensions of the canonical command uniqueness key. The complete child handoff identity remains the source runtime scope, optional distinct target runtime scope, `automationDispatchId`, and `commandOrdinal`; the parent Trigger Identity and due-point branch remain immutable correlation and conflict/readback evidence.
      - the deterministic gameplay command payload
    - Game Session is the only service allowed to translate that contract into `tick:{tenantRegionTag}:queue:<entityId>` mutations.
    - Game Session first records the admission attempt in its durable command/admission ledger. Its uniqueness projection is the complete child Command-Handoff Identity: source runtime scope, optional distinct target runtime scope, `automationDispatchId`, and `commandOrdinal`. The row stores the parent Trigger Identity, canonical due-point state, entityId, target timeline, lane, cost class, and command payload hash alongside it as immutable correlation and conflict/readback evidence. For a schedule-derived handoff, the due point is required and validated as exactly one of `dueTickId:<value>` or `dueAt:<epochMillis>`; immediate event-driven handoffs omit the scheduler-only fields and both physical due fields.
    - Game Session deduplicates on that durable admission record, not on Redis queue contents. Retries, duplicate gRPC delivery, or leader changes select the row by the same complete child Command-Handoff Identity, then must present the same parent Trigger/event-scope evidence, due-point branch, `lane`, `cost_class`, and payload as immutable comparison evidence; any absent, malformed, or different immutable field is a conflicting request, not a replay/no-op, and must fail closed rather than enqueueing a second command. Immediate event-driven retries must reuse the same event Trigger Identity and applicable derived `sourceService` and consistently omit a physical due point; supplying a schedule-derived due point or schedule definition is conflicting evidence.
    - Only after the durable admission record is created or confirmed does Game Session invoke the region-lease Redis enqueue script for `tick:{tenantRegionTag}:queue:<entityId>`.
    - If the supplied `regionEpoch` is stale or the supplied `duePoint` is no longer valid for the active lease and tick/clock timeline, Game Session returns a non-applied outcome and Automation & Scripting re-derives the next action from durable trigger state instead of guessing from Redis.
    - Retries and recovery compare the originally admitted `lane` and `cost_class` from the durable row and selected-work manifest. Missing, changed, or contradictory values remain fenced and fail closed; recovery preserves those values and never infers or reclassifies them from source kind, queue state, or Redis.
  - Ordering point:
    - The durable trigger-instance / outbox row is the source of truth that the automation action became due.
    - The Game Session durable admission record is the source of truth that the due automation was accepted for gameplay admission.
    - The Game Session enqueue acknowledgement is the source of truth that the accepted automation was materialized into current Redis coordination for that region. If Redis enqueue fails after durable admission, Game Session retries materialization from the admission record or converges it to a terminal non-applied outcome under the same command-status rules used for player commands.
    - Redis keys are hot-path coordination state only; they are never the sole record that a fairness-critical automation action existed.
  - Worked example:
    - A schedule for NPC `entityId=E1` becomes due at `(tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120, gameInstanceId=9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, playableStateScope=shared, regionId=R1, regionEpoch=7, scriptPatchVersion=P3, scriptPinEpoch=4, duePoint=dueTickId:420)`.
    - Automation & Scripting creates the canonical trigger claim for that due point and evaluates under the claim; before a winning evaluated-descriptor/outbox commit it has no dispatch or command-child identity. If evaluation emits one command, that first atomic commit allocates and persists `automationDispatchId=AD1` and `commandOrdinal=0`; a valid zero-command evaluation creates neither. The source trigger-claim row is unique on `(tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120, gameInstanceId=9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, playableStateScope=shared, stableOwnerKind=SCRIPT, stableOwnerId=NPC_MOVE, regionId=R1, regionEpoch=7, entityId=E1, scriptId=NPC_MOVE, eventType=onInterval, eventSchemaVersion=v1, scriptPatchVersion=P3, scriptPinEpoch=4, isDryRun=false, scheduleDefinitionId=S1, targetScopeType=ENTITY, targetScopeId=E1, duePoint=dueTickId:420, triggerMode=CATCH_UP, resumeWindowId=RW1)`, plus any applicable plugin fields. This source trigger-claim projection is distinct from the Game Session durable admission projection, whose key is the complete child Command-Handoff Identity. The Game Session row retains the complete parent Trigger Identity and schedule projection, including the branch-specific target selector, due point, `resumeWindowId=RW1`, `cadence`, `unit`, `priorityTag`, `targetScopeType`, `targetScopeId`, binding priority/exclusivity, `scheduleSemanticsHash`, the pin operation's `controlPlaneRequestId`, owner/version metadata, due-point state, and `runtimeRegionId`/`runtimeRegionEpoch`, plus immutable `lane` and bounded `cost_class` metadata, as correlation and conflict/readback evidence.
    - After that commit, it calls `EnqueueAutomationCommandIfAbsent` with the complete Trigger Identity, including `scriptPatchVersion=P3` and `scriptPinEpoch=4`, persisted `automationDispatchId=AD1`, `commandOrdinal=0`, scheduled candidate evidence `targetScopeType=ENTITY`, `targetScopeId=E1`, `triggerMode=CATCH_UP`, `resumeWindowId=RW1`, and `duePoint=dueTickId:420`, immutable `lane` and bounded `cost_class`, the target region timeline fields, and the deterministic command payload. A retry after the winning commit reuses `AD1` and its ordinal.
    - Game Session inserts or reads the durable admission row identified by the complete child Command-Handoff Identity: source scope `(tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120, gameInstanceId=9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, playableStateScope=shared, regionId=R1, regionEpoch=7)`, no distinct target scope, and `(automationDispatchId=AD1, commandOrdinal=0)`. It verifies the stored parent Trigger Identity and scheduled candidate evidence `targetScopeType=ENTITY`, `targetScopeId=E1`, `triggerMode=CATCH_UP`, `resumeWindowId=RW1`, and `duePoint=dueTickId:420` as immutable comparison evidence, validates the active lease, epoch, and due point for `R1`, maps the request to `tick:{tenantRegionTag}:queue:E1`, and returns `"ENQUEUED"` on the first successful materialization.
    - If the same trigger retries due to gRPC timeout with the same immutable Trigger and scheduled candidate evidence, including `targetScopeType=ENTITY`, `targetScopeId=E1`, `triggerMode=CATCH_UP`, `resumeWindowId=RW1`, and `duePoint=dueTickId:420`, Game Session sees the same durable admission row and returns a replay/no-op outcome instead of enqueuing a second command; a retry with a different due point or resume window is rejected as conflicting evidence under the same child identity.
    - If the region has already moved to `regionEpoch=8`, Game Session returns a stale-timeline outcome and Automation & Scripting re-derives what should happen next from the durable trigger-instance row instead of inferring state from Redis.

- **Best-effort, non-critical automation**
  - Examples: analytics-style background work, non-critical notifications, opportunistic refreshes that can be dropped or reordered without visible gameplay impact.
  - Redis role: **Cache/Rate-Limit Redis**.
  - Prefixes: `automation:queue:{tenantInstanceTag}:*`, `automation:quota:<tenantId>:*`, `automation:tenant-budget:<tenantId>:tier:<tier>`, `automation:test:quota:<tenantId>:script:<scriptId>:*`, `automation:test:capacity:<tenantId>:*`, `automation:test:capacity:cluster*`, `automation:readiness:capacity:<tenantId>:*`, `automation:readiness:capacity:cluster*`, and similar TTL-only queues, counters, and leases documented in the Redis Cache & Rate Limiting design.
  - Guarantees:
    - Treated as **TTL-only, reset-tolerant** hints: items may be dropped, duplicated, or processed late.
    - Correctness (for example, “was this workflow triggered at least once?”) must come from durable trigger tables and idempotent domain logic, not from the queue contents.
  - Design rule: features that require strong ordering, fairness relative to player actions, or at-least-once guarantees **must not** rely solely on `automation:queue:*`; they either belong in the coordination category above or should use tick-adjacent outbox/saga patterns.

When designing new automation, authors must explicitly state which category it belongs to and which Redis role/prefixes it uses, and link to the relevant tick or cache sections. Reviews should push gameplay-equivalent automation toward coordination prefixes and reserve cache-based queues for truly best-effort workloads.

---

### Redis Usage by Service

The following table summarizes how core services interact with Coordination Redis and Cache/Rate‑Limit Redis. Per‑service design docs expand on these responsibilities and describe any participation in coordination via shared helpers (for example, tick locks or auth/session keys) even when a service does not own coordination prefixes itself.

| Service | Redis Usage |
| --- | --- |
| **Game Session Service** | Owns only its **Coordination Redis** tick/session families: tick queues, locks, timers, retry metadata, region leases, and Redis-backed session liveness, binding, and rebind coordination, together with their registered scripts. It also has sole read/write ownership of the **Cache/Rate‑Limit Redis** `view:room-look:*` disposable presentation/redraw helper; other services do not read or write that prefix directly. Game Session owns that prefix's presentation semantics, while the canonical [cache catalog](./system-architecture-redis-cache-reference.md) records its TTL, size, invalidation, metrics, and reset contract; shared libraries enforce defaults and guardrails without taking ownership. Durable semantic reconnect context remains Game Session-owned persistence rather than Redis session state. It does not write Account-owned `session:auth:*` prefixes or Automation-owned prefixes/scripts. |
| **Automation & Scripting Service** | Owns automation-specific prefixes such as `automation:queue:{tenantInstanceTag}:*`, `automation:timer:{tenantRegionTag}`, and `script-scheduler:{tenantRegionTag}:lastTickId`, but does **not** own gameplay `tick:*` queues or locks. It reads tick heartbeats via gRPC, uses PostgreSQL as the durable work source of truth, and uses **Cache/Rate‑Limit Redis** for script quotas and best-effort queue projection where documented. |
| **Spring Cloud Gateway** | **Target wiring:** Cache/Rate‑Limit Redis serves token‑bucket rate limiting and best‑effort caches, while a separate Coordination Redis client/ACL serves the narrow one-use connect-token replay marker `gateway:connect-token:{gateway-connect-token-replay-v1}:jti:<jti>`, browser-revocation deny marker `gateway:connect-token:{gateway-connect-token-replay-v1}:deny:jti:<jti>`, and readiness/fence record `gateway:connect-token:{gateway-connect-token-replay-v1}:readiness` (whose value carries `replayAdmissionFence`). Replay consumption and browser-deny writes use pinned Coordination connections and the configured threshold-satisfying `WAITAOF` acknowledgement; an ambiguous deny write is unconfirmed. The target wiring never touches tick, session, or other gameplay-coordination prefixes. The current generic Cache-bound writer emits only the untagged replay marker; it has no current deny/readiness/fence writer. Those untagged replay keys are migration-only drift and are not target ACL state. |
| **Logging & Admin Service** | Does not connect to Redis in the current runtime or target service role. It consumes Redis-derived health and metrics through Game Session APIs and exporters, may invoke named owner control APIs, and never receives Coordination or Cache/Rate-Limit Redis credentials. |
| **TCP Proxy Service** | No current Cache/Rate-Limit Redis runtime dependency or shared-helper use. Future proxy cache/throttling participation is target/optional, non-authoritative, and requires a dedicated `tcpproxy:*` prefix registration; it must never use Coordination Redis. |
| **Other microservices (Game Logic, Entity Management, World Management, Social & Groups, etc.)** | Do not define or own coordination prefixes; they participate in Coordination Redis **only** through shared helpers and Lua descriptors owned by Game Session (for example, `tick:{tenantRegionTag}:lock:<entityId>` for tick locks). Where they cache read‑heavy aggregates, they use **Cache/Rate‑Limit Redis** and the key patterns from the Redis Cache & Rate Limiting design. |

These boundaries are part of the **Redis Coordination Invariants** described in `system-architecture-redis.md`. In the target state, they will be enforced via shared key helpers, the Lua script registry, and CI tooling; current role-specific clients, ACLs, key/script registration, and ownership proof remain incomplete as noted above.

---

## Environment Profiles and Mappings

Redis deployments in FireMUD approximate one of three main profiles. Each environment (local dev, CI, staging, prod) documents which profile it uses and whether it behaves as an **ephemeral** stack for tail-loss and role-separation guarantees.

### Profiles

- **`dev_local`**
  - Use case: single‑developer, non‑player‑facing environments.
  - Coordination Redis:
    - `appendonly yes`, `appendfsync everysec`, `aof-use-rdb-preamble yes`.
    - Modest `maxmemory` sized for laptops and local Docker.
    - Tail‑loss SLOs relaxed but invariants and key rules still enforced.
  - Cache/Rate‑Limit Redis:
    - May run without AOF and with more aggressive eviction.
    - Configuration emphasizes low friction over durability; subject cardinality remains bounded by TTL and active-subject admission rather than collision buckets.

- **`hobby_self_hosted`**
  - Use case: small/self‑hosted FireMUD games with real players.
  - Coordination Redis:
    - `appendonly yes`, `appendfsync everysec` (or carefully documented alternative).
    - `aof-use-rdb-preamble yes`.
    - `maxmemory` sized so restarts normally complete within **30–60 seconds**.
  - Cache/Rate‑Limit Redis:
    - Sized for cache and rate‑limit workloads with eviction policies tuned for predictable behavior.
  - Tail‑loss SLOs and replay behavior are expected to match production‑like expectations, but at smaller scale.

- **`production_clustered`**
  - Use case: multi‑tenant or high‑scale deployments.
  - Coordination Redis:
    - Clustered or sharded deployments with AOF enabled.
    - Shard sizing aligned with tick workloads and tenant distributions.
    - Tail‑loss windows and restart budgets defined in SLOs and runbooks.
  - Cache/Rate‑Limit Redis:
    - Clustered or scaled deployments sized to keep cache and rate‑limit keys well within memory budgets.
    - Eviction policies tuned to preserve high‑value caches and token buckets.

### Environment Mappings

Each environment picks one of these profiles and documents the mapping:

- **Local development**
  - Approximates `dev_local`.
  - Always runs **two separate Redis deployments** (for example `redis-coord` and `redis-cache` in Docker Compose) so role separation is exercised even on laptops.
  - `docker-compose` and `./gradlew devUp` run:
    - `redis-coord` with AOF and a dedicated volume (Coordination Redis).
    - `redis-cache` without shared volumes (Cache/Rate‑Limit Redis).
  - This environment is **non‑ephemeral** for role separation: pointing `FIREMUD_REDIS_COORD_*` and `FIREMUD_REDIS_CACHE_*` to the same endpoint is treated as a misconfiguration.

- **CI and preview stacks**
  - Hosted `pr-preview` retains the normal role split and is not an exception to ADR 0171. Other preview-like stacks must document their environment class and topology explicitly.
  - One-shot test/CI stacks may use an explicit **ephemeral coordination** profile:
    - Coordination Redis may run with reduced or disabled AOF where tests are fully reset‑tolerant.
    - These stacks are **not** used to validate tail‑loss SLOs or replay guarantees.
  - In an explicitly labelled one-shot test/CI stack it is acceptable to collapse roles into a single Redis instance **only** when:
    - Tests are explicitly designed to be reset‑tolerant and do not exercise coordination tail‑loss behavior.
    - The environment is clearly labelled as “ephemeral / single-Redis” in its documentation and configuration.
    - Misconfiguration checks and dashboards still surface the fact that roles are sharing an endpoint so it cannot be mistaken for a production-like topology.

- **Staging and production**
  - Approximate `production_clustered`:
    - Coordination Redis with AOF and carefully sized shards.
    - Cache/Rate‑Limit Redis sized and monitored for cache and rate‑limit workloads.
  - These environments are **non‑ephemeral**:
    - Coordination and Cache/Rate‑Limit Redis must always be distinct deployments.
    - Any attempt to point both roles at the same endpoint is treated as a hard failure in configuration checks and health indicators.
  - Environment docs must record:
    - The chosen profile.
    - The concrete AOF, `maxmemory`, and clustering settings for each role.

When adding or modifying an environment, update its documentation to state:

- Which Redis profile it approximates.
- Whether it is allowed to behave as an ephemeral/single-Redis stack for tests.
- How its concrete settings align with the targets above.

---

## Maxmemory, Eviction, and Sizing

Coordination and Cache/Rate‑Limit Redis are sized and configured differently.

### Coordination Redis

- **Goal:** predictable restart behavior and bounded memory usage for coordination keys.
- **Recommendations:**
  - Keep peak memory for coordination prefixes (`tick:*`, `timer:*`, `retry:*`, `session:game:*`, `session:auth:token:*`, `session:auth:generation:*`, `tick-executor-lease:*`, etc.) within the canonical Coordination Redis budget from `system-architecture-redis-operations.md` (normally **≤ 30–40% of `maxmemory`**).
  - Use AOF preamble and rewrite settings that keep AOF size within the budgets described in **Redis Operations & Migrations**.
  - Avoid eviction for coordination keys whenever possible; if `maxmemory` is configured with eviction, treat eviction events as incidents rather than normal operation.

### Cache/Rate‑Limit Redis

- **Goal:** predictable cache and rate‑limit behavior under eviction.
- **Recommendations:**
  - Choose `maxmemory` and `maxmemory-policy` values that:
    - Keep eviction focused on low‑value caches first.
    - Preserve high‑value caches and token buckets as long as possible.
  - Treat eviction as normal:
    - Cache writers must tolerate keys disappearing early.
    - Rate‑limit bucketing must tolerate dropped or reset bucket keys.
  - Use metrics to track:
    - Cache hit/miss rates by prefix.
    - Eviction counts and memory usage over time.

Concrete eviction policies and sizing guidelines are detailed in **Redis Cache & Rate Limiting**.

---

## Configuration Wiring and Misconfiguration Guards

All services and tools select Redis roles via configuration, not hard‑coded URLs:

- Coordination Redis:
  - `FIREMUD_REDIS_COORD_HOST` / `FIREMUD_REDIS_COORD_PORT`  
    or `FIREMUD_REDIS_COORD_URL`.
- Cache/Rate‑Limit Redis:
  - `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT`  
    or `FIREMUD_REDIS_CACHE_URL`.

Target-state shared configuration helpers (for example in `firemud-common`) will expose:

- `RedisCoordConfig` + `createCoordinationRedisClient(...)`
- `RedisCacheConfig` + `createCacheRedisClient(...)`

Target-state requirements once those helpers exist:

- **Role explicitness**
  - Every service and ops script must accept a specific role config (`RedisCoordConfig` or `RedisCacheConfig`), not arbitrary host/port strings.
  - Multi‑role tools that speak to both deployments must tag logs and metrics with `redis_role` (for example `coordination` vs `cache`).

- **Misconfiguration detection**
  - Configuration helpers should:
    - Detect when `FIREMUD_REDIS_COORD_*` and `FIREMUD_REDIS_CACHE_*` resolve to the same endpoint.
    - Emit a clear log warning and a failing health indicator in all **non‑ephemeral** environments (`dev_local`, staging/prod, long‑lived hobby/self‑hosted); services must not treat a single shared Redis instance as a valid topology for those roles.
    - In explicitly marked ephemeral CI stacks, still log and surface this sharing in metrics so it is visible, but do not fail health checks solely for that reason.
  - Dashboards should include a simple “Redis role endpoints” view showing both roles per environment.

- **Test wiring**
  - Integration and cross‑service tests should:
    - Obtain endpoints from the same style of configuration (`FIREMUD_REDIS_COORD_*` / `FIREMUD_REDIS_CACHE_*`) but point them at Testcontainers.
    - Avoid hard‑coding secrets or production endpoints.

These wiring rules are enforced for ops scripts and maintenance tooling via **Coordination Redis Ops Access & Tooling** and CI checks.

---

## Related Documentation

- `system-architecture-redis.md` – conceptual hub for Redis roles, invariants, and key naming.
- `system-architecture-redis-cache.md` – cache and rate‑limit design, prefixes, and eviction guidance.
- `system-architecture-redis-operations.md` – AOF management, reset flows, and migration runbooks.
- `system-architecture-redis-ops-access.md` – ACLs, allowed commands, and CI checks for ops tooling.
- `system-architecture-redis-design-checklist.md` – concrete design checklist for Redis changes.
- `system-architecture-testing.md` – test strategy, including Redis usage in integration and cross‑service tests.

# FireMUD System Architecture: Transaction Strategies

This document explains how FireMUD coordinates data consistency across its independent microservices. It distinguishes between **real-time gameplay commands** (executed within ticks using Redis), **short synchronous cross-service orchestration** (executed through the shared `common-saga` helper), and **long-running durable control-plane workflows** (executed through Temporal). It clarifies when each pattern is needed — and when it is not.

---

## Terminology Clarification

| Term | Meaning |
| --- | --- |
| **Command** | A gameplay action issued by a player or AI (e.g., attack, move, use item). Executed inside a tick as a **self-contained gameplay unit** that may touch multiple services but is coordinated via Redis and idempotent domain handlers. |
| **Transaction** | A unit of work that must either fully succeed or be rolled back **within a single service boundary** (for example, a PostgreSQL transaction in Entity Management). Gameplay commands are composed of one or more such local transactions plus idempotent retries; there is **no global, cross-service ACID transaction** for a command. |
| **Tick** | A scheduled gameplay loop slice. Each tick processes at most one command per entity and uses Redis for coordination, staging/cleanup, and fairness. Ticks are not atomic across all commands — each command is executed as an independent composition of local transactions and retries. |
| **Saga** | A short-lived, synchronous cross-service orchestration composed of multiple local transactions plus optional compensation. Used only for **non-gameplay** workflows that can complete in one caller-owned execution path and do not need durable timers, restart-safe continuation, or operator-visible in-flight state independent of one JVM lifetime. |
| **Temporal workflow** | A long-running, durable control-plane workflow. Used when orchestration must survive restarts, wait durably for time or external events, or expose explicit operator-visible workflow state/history. |

---

## In-Game Command Transactions (No Sagas Needed)

All real-time gameplay logic — movement, combat, item use, AI — is executed inside **ticks**. Each command is:

- Pulled from the command queue
- Executed using deterministic game logic
- Staged in Redis with Lua-based staging and cleanup/abandon semantics
- Applied via one or more **service-local transactions** guarded by effect identity
- Automatically retried on failure (for example, lock contention or transient errors)
- Reported through a durable command-status surface keyed by `(tenantId, gameInstanceId, commandId)` that persists both execution convergence (`executionOutcome`) and player-facing result (`gameplayResult`) independently of Redis coordination state

From the player’s perspective, a command appears atomic (“either my move happens or it does not”), but the implementation relies on:

- **Per-service atomicity** – each participating service wraps its own changes in a local database transaction.
- **At-least-once delivery + idempotency** – tick effects may be retried; idempotent guards ensure repeated applications converge to the same logical outcome.
- **Eventual cross-service convergence** – if different services commit at slightly different times, domain invariants converge as retries and reconciliation complete; there is no single ACID boundary spanning them.

This model provides:

- **Per-command logical atomicity from the player’s perspective**
- **Tick-level fairness and isolation**
- **Crash-safe, replayable execution through idempotency**
- **No need for Saga orchestration inside the tick loop**

For cross-region gameplay commands, this also means:

- The origin tick commits once its own local effects and durable remote follow-up creation have reached terminal tick-batch outcomes.
- Any waiting for remote completion, timeout handling, or aggregation of multi-region results lives in a separate durable coordinator record and is resolved by later tick work, not by keeping the original origin tick open.

> 🔗 See [Tick System and Runtime Design](./system-architecture-ticks.md) and [Redis Architecture](./system-architecture-redis.md) for detail on how ticks provide per-service transactional guarantees and cross-service convergence via idempotency.

### When Gameplay Commands Must *Not* Depend on Global Atomicity

Gameplay features must **not** assume that a command is a single, all-or-nothing ACID transaction across services. In particular:

- Cross-service invariants (for example, “both inventories updated or neither is”) must be enforced via:
  - Idempotent handlers and effect guards in each owning service.
  - Clearly defined reconciliation behavior if some services succeed and others fail.
- Designs must tolerate small windows where some, but not all, side effects of a command have committed, as long as retries and reconciliation converge to the intended state.

If a proposed gameplay feature truly requires stronger semantics than this model (for example, a hard guarantee that a multi-service trade never produces a momentary imbalance), it should either:

- Be redesigned to fit the idempotent, eventually consistent tick model, or
- Be treated as a **tick-adjacent workflow** that uses the outbox/saga patterns below and accepts higher latency and operational complexity.

### Tick Effects Are At-Least-Once: Idempotency Is Mandatory

Tick execution is replayable: retries, failover, and Redis AOF replay can cause the same logical effect to be attempted more than once. For gameplay commands this is expected and safe only because tick-invoked domain mutations are required to be idempotent with respect to a canonical `EffectId`.

- The Game Session Service computes and propagates a stable `EffectId` derived from the region-scoped tick context (`tenantId`, `gameInstanceId`, `regionId`, `regionEpoch`, `tickId`, `effectKey`) plus the target aggregate identity.
- Owning services must implement durable idempotency guards (unique constraints, monotonic updates, transactional outbox) so duplicate `EffectId` attempts become OK/no-op outcomes rather than double-applying side effects.
- For gameplay-visible mutations, `EffectId`-backed guard rows are the default idempotency boundary. Simpler `last_tick_id` watermark patterns are allowed only for aggregates explicitly documented as receiving at most one logical mutation per tick.
- To keep this contract consistent across services, tick-driven handlers use a shared idempotency helper from `common-data-runtime` (for example an `IdempotentEffectExecutor`) instead of ad-hoc “check or insert” patterns. The helper:
  - Accepts `EffectId` plus callbacks for “apply-if-first” and “handle-replay”.
  - Encapsulates the canonical guard pattern (insert-if-absent, treat conflicts as replay) and throws well-defined exceptions on guard violations.
  - Emits a simple, standardized counter such as `tick_effect_outcome_total{service, effect_type, outcome}` so operators can distinguish first-apply vs replay behavior across services without per-tenant configuration.

> 🔗 The canonical `EffectId` contract and per-side-effect patterns are defined in [Tick Effect Identity and Idempotency Contract](./system-architecture-ticks.md#tick-effect-identity-and-idempotency-contract).

### Spatial Effects: Location vs Containment (World ↔ Entity)

Movement, drops, pickups, and room presence are cross-service by design:

- **World Management Service** is authoritative for character/NPC location and occupancy, scoped to a `RoomInstanceRef` `(tenantId, gameInstanceId, roomInstanceId)`.
- **Entity Management Service** is authoritative for inventories, containment, and ground items, including synthetic room-ground containers keyed by the same `RoomInstanceRef`.

To prevent cross-instance collisions and make retries safe, spatial tick effects must follow these invariants:

- Every spatial effect includes the `RoomInstanceRef` it targets (and, where applicable, `fromRoomInstanceRef` and `toRoomInstanceRef`), not a bare `roomId`.
- The same `EffectId` is propagated to both World Management and Entity Management mutations for the effect, and both services implement durable idempotency guards so partial success can be safely retried.
- A participant acknowledgement is emitted only after that service has durably committed the `EffectId` guard and the effect-visible rows required for its side of the contract. Redis-staged or in-memory state alone is never sufficient to acknowledge convergence.
- Game Session persists (or can deterministically reconstruct) the intended pre/post state for the effect so a reconciliation pass can re-drive the missing side if one service commits and another fails.
- Reconciliation behavior is documented per effect type. The default policy is “retry until convergence” using the original `EffectId`, not “best-effort compensate” with a new effect identity.

Ambient world mutations (doors, hazards, weather) are treated as spatial effects for replay and idempotency purposes:

- All durable ambient mutations must be issued as effect-shaped commands carrying `EffectId` plus the appropriate instance scope (`RoomInstanceRef` for room-scoped changes).
- World Management is authoritative for ambient state used by gameplay (including hazard activation/inactivation state). Game Logic reads this state through World Management snapshot APIs and must not maintain an independent hazard-authority store.
- Operator tooling and scripts must not bypass this contract by writing instance tables directly; they emit the same effect-shaped commands so retries and crash recovery remain safe.

Concrete per-effect required writes and reconciliation rules live in `design/architecture/system-architecture-spatial-and-ambient-effects-catalog.md`. Any new effect must add an entry there before it is used by runtime gameplay.

### Reconciliation Owner of Record (Spatial/Ambient Effects)

Cross-service effect convergence has a single owner of record:

- **Game Session Service** owns reconciliation orchestration and backlog durability for spatial/ambient effects.
- World Management and Entity Management remain owners of their domain writes and idempotency guards, but they do not own cross-service retry scheduling.

Durable backlog contract:

- Game Session persists one row per logical effect in a durable backlog table (for example `effect_reconciliation_backlog`) keyed by `(tenantId, gameInstanceId, effectId)`.
- Minimum persisted fields:
  - `effectType`
  - `targetScope` (`RoomInstanceRef` or region scope)
  - `expectedParticipants` (for example `WORLD`, `ENTITY`)
  - `participantAckState` (per participant applied/pending/final-failure)
  - `firstObservedAt`, `lastAttemptAt`, `attemptCount`, `nextAttemptAt`
  - `status` (`PENDING`, `CONVERGED`, `DEAD_LETTER`)
  - `lastErrorCode` / `lastErrorMessage`
- Inserts and status transitions must be idempotent on `(tenantId, gameInstanceId, effectId)` so duplicate scheduling does not create duplicate backlog rows.
- Backlog rows must be indexed at minimum by `(status, nextAttemptAt)` and `(tenantId, status, firstObservedAt)` for retry scans and operator triage.
- For participant ack semantics, `applied` means the owning service can serve the effect through its documented durable read surface for the corresponding fence token. It does not mean `accepted for later batch flush`.

Retry and dead-letter policy:

- Default retry strategy is bounded exponential backoff with jitter and no mutation of `EffectId`.
- Effects remain `PENDING` until all required participants acknowledge applied/no-op for the same `EffectId`.
- Effects move to `DEAD_LETTER` only after retry exhaustion or explicit operator action; no destructive compensation is issued from this path.
- Dead-letter rows remain replayable via explicit operator/API actions; replay must preserve original `EffectId`.

Retention and lifecycle policy:

- `CONVERGED` rows are retained for 24 hours, then deleted by background GC.
- `DEAD_LETTER` rows are retained for 30 days minimum (or longer by policy) for incident analysis.
- GC jobs must be idempotent and rate-limited per tenant to avoid write spikes.

Required control-plane interfaces:

- `ListEffectReconciliationBacklog(tenantId, status, olderThan, page)` for diagnostics.
- `RetryEffectReconciliation(tenantId, gameInstanceId, effectId)` for explicit replay from `DEAD_LETTER` or stuck `PENDING`.
- `AcknowledgeEffectDeadLetter(tenantId, gameInstanceId, effectId, reason)` for audited operator decisions.
- Logging & Admin dashboards should consume these APIs; operators should not mutate backlog tables directly.

Operational SLOs and alerts:

- Alert when `PENDING` age exceeds 60 seconds for player-visible effect types (`MOVE`, `DROP`, `PICKUP`, `AMBIENT_PATCH`).
- Alert when backlog depth per tenant exceeds configured threshold (for example 1,000 pending rows) or dead-letter count is non-zero.
- Expose metrics at minimum:
  - `effect_reconciliation_pending_total{effect_type}`
  - `effect_reconciliation_age_seconds{effect_type}`
  - `effect_reconciliation_retries_total{effect_type}`
  - `effect_reconciliation_dead_letter_total{effect_type}`

### Tick-Adjacent Workflows (Outbox Boundary)

Some player actions conceptually trigger both in-world effects and “business” side effects such as billing, email, or external webhooks. These **tick-adjacent workflows** must still respect the tick replay model:

- Tick handlers are allowed to:
  - Apply deterministic, idempotent domain mutations guarded by `EffectId` (for example HP changes, inventory moves).
  - Enqueue durable outbox records keyed by `EffectId` into the owning service’s database.
- Tick handlers must not:
  - Call external systems with irreversible side effects (payment processors, email providers, third-party APIs) directly from tick-driven endpoints.
  - Depend on external acknowledgements to decide whether a tick effect was “applied”.

Instead, the recommended pattern is:

- **Inside the tick** – Game Session invokes a domain handler that:
  - Uses the standard idempotency guards (`last_tick_id` or `tick_effect_guard`) for in-world state.
  - Writes a single outbox/event row keyed by `EffectId` when an external workflow should be started.
- **Outside the tick** – A background worker or saga step consumes the outbox row and:
  - Performs the external call(s), with its own idempotency and retry strategy.
  - Updates saga and/or outbox state independently of tick replay.

This keeps tick execution fast, bounded, and safely replayable, while synchronous saga steps, Temporal workflows, and outbox processors own longer-running cross-service or external side effects. New designs that mix tick-driven state changes with external side effects must explicitly document this boundary and reference both this section and the tick idempotency rules in `system-architecture-tick-failures-and-operations.md`.

---

## When Short Synchronous Sagas *Are* Used

Short synchronous sagas are used for **non-tick, multi-service workflows** involving persistent state changes that cannot be coordinated via Redis when the orchestration does not need durable workflow execution. These include:

| Use Case | Description |
| --- | --- |
| **Account Creation** | Create account → provision default character → initialize baseline state when the caller can synchronously own retry/failure behavior |
| **Short admin remediation** | Limited control-plane actions that touch more than one service but still complete in a single caller-driven request/worker pass |
| **Tick-adjacent outbox follow-through** | Background orchestration around an outbox event when the work is still synchronous and restart-safe continuation is not required |

These workflows:

- Happen **outside the tick loop**
- Modify **persistent storage (PostgreSQL)** across multiple services
- Require compensation and persisted step status, but not durable workflow execution

If a workflow needs restart-safe continuation, durable waits/timers, or operator-visible in-flight state that survives one service lifetime, it should use the shared Temporal substrate described in [Temporal Control-Plane Workflows](./system-architecture-temporal-workflows.md) instead of extending `SagaRunner` toward durable workflow behavior.

### Rollback Boundaries by Operation Class

Cross-service workflows must explicitly choose one of the following rollback classes before implementation:

- **Class A (Pre-Activation Compensating Workflow):**
  - Scope: publish-time and pre-runtime workflows where outputs are not yet active for gameplay (for example `PublishVersion` before a version is activated, or world-creation before admission opens).
  - Contract: compensating actions are allowed; workflow failure may roll back durable writes or mark the target version/workflow as `FAILED` with deterministic retry/repair. The implementation may use short synchronous Saga orchestration or an owning Temporal workflow according to the durable-wait and recovery requirements above.
- **Class B (Post-Activation Runtime Convergence):**
  - Scope: tick-driven gameplay and any mutation visible to live players (movement, containment, ambient mutations, live script-trigger side effects).
  - Contract: no destructive cross-service rollback. Effects are retried with the same `EffectId` until convergence; partial success is resolved by reconciliation, not compensation deletes.

Designs that cross this boundary (for example, activation and live mutations in one flow) must split into two phases with an explicit hand-off point from Class A to Class B.

For world creation and similar activation flows, this hand-off point must be a persisted, monotonic status transition (for example `world_instance_status: PREPARING -> ACTIVE`, with `FAILED_PRE_ACTIVATION` as the non-admitted failure terminal state). Compensation is valid only before the transition commits.

### State Ownership and Mutation Boundaries

To keep responsibilities clear across design-time, domain, and runtime services:

- Game Design Service owns version metadata, branches, commits, and revision history but does not own canonical schemas or template rows for worlds, entities, or assets.
- Domain services such as World Management, Entity Management, and Game Design’s asset storage tables own their respective schemas and all versioned/template rows keyed by `(tenantId, versionId)`. They must be able to load every non-Retired version they own even if Game Design Service is unavailable.
- Runtime services such as Game Session and Automation & Scripting own transient tick state (primarily in Redis) and any persistent instance data they create via domain-service APIs (for example world instance rows keyed by `(tenantId, gameInstanceId)`), but they must never write template rows directly.
- All cross-service workflows that change persistent state across more than one service database must either:
  - execute inside a short synchronous `common-saga` flow when caller-owned retry/compensation is sufficient,
  - execute as a durable Temporal workflow when restart-safe continuation, durable waits, or operator-visible in-flight state matter, or
  - be modeled as tick-adjacent outbox-driven flows when initiated from gameplay commands.

In particular:

- Design-time writes to template tables are only allowed via domain services’ Draft APIs invoked from Game Design Service workflows.
- Published templates for a given `(tenantId, versionId)` are immutable; changing behavior for a live game means creating a new version and new game instances (or, for script-only fixes, changing the **script patch selection** according to the hot-reload and pinning rules described below rather than editing templates in place).

### Live Script Patch Boundary

Transactional guarantees for tick execution assume deterministic scripts for the `(versionId, scriptPatchVersion)` pair that was in effect when a given effect was applied:

- The Game Session Service records the `scriptPatchVersion` that is active for each `gameInstanceId` and includes it in the context for every tick effect (for example via the EffectId metadata and per-effect audit/log records).
- Tick handlers and script runners must treat `(versionId, scriptPatchVersion)` as part of the effect identity: replays and retries use the same pair that was originally logged for that effect, even if the instance later moves to a different patch.
- Operational tooling is allowed to change the pinned `scriptPatchVersion` for a running instance at well-defined boundaries (for example between ticks or during maintenance), but that change only affects **future** effects. Previously applied effects remain tied to the patch version recorded alongside their EffectIds in logs and audit tables.

---

## FireMUD Short Synchronous Saga Architecture

FireMUD uses a **shared short synchronous saga orchestration library**, not a separate microservice.

### Characteristics:

- **Orchestration**:
  - Centralized in the **common-saga** library located under
    `services/common-saga`
  - The engine and its shared Flyway migrations live in `services/common-saga/src/main/resources/db/migration/saga`
  - Consuming services expose those migrations through the shared `classpath:db/migration/saga` Flyway location alongside their service-local `classpath:db/migration` chain
  - Hosts define short, synchronous compensation-aware flows declaratively using the fluent API
  - Saga execution is initiated by services that can own synchronous retry/failure handling, but **coordination logic lives in the library**
  - Participating services include **Account**, **Game Design**, **Game Session**, **World Management**, **Automation Scripting**, **Social Groups**, and **Logging & Admin**
  
- **State Management**:
  - All saga state is persisted in the `saga_instance` and `saga_step` tables provided by the common library.
  - These tables reside inside the owning service schema (for example `${serviceSchema}.saga_instance` and `${serviceSchema}.saga_step`) inside **each service’s own database**. Flyway migrations from `common-saga` are applied per service database so saga state stays local to the service that owns the workflow.
  - Tracks in-progress, completed, and failed synchronous orchestration attempts.
  - Supports compensation.
  - Flyway migrations bundled with the library create these tables automatically when consuming services start.
  - `SagaRunner` emits a `sagas.active` metric and attaches a `correlationId` to logs for each orchestration using MDC.
  - Operators monitor progress via the Saga Dashboard (`/sagas` and `/sagas/{id}/steps` endpoints) provided by the [Logging & Admin Service](./microservices/logging-admin-service/README.md), which queries saga status via service APIs rather than directly reading every database.

This library is intentionally not FireMUD's durable workflow engine. World lifecycle, publish, and script-patch readiness now use Temporal because they need restart-safe continuation, stable workflow identity, and operator-visible runtime state independent of one service process.
  
- **Execution Model**:
  - Steps are gRPC calls to owning services
  - Helper `GrpcSagaSteps.callWithRetry` wraps gRPC calls with basic retry logic
  - All steps are **idempotent**
  - Each step uses a durable idempotency guard recorded in the owning service’s database keyed by business identity plus step identity (for example `(tenantId, gameInstanceId, worldCreationRequestId, stepName)` or `(tenantId, gameInstanceId, terminationRequestId, stepName)`), with `sagaInstanceId` retained as execution trace only, so retries can safely no-op or reconcile without duplicating persistent rows.
  - For externally retryable workflows (operator retries, compensating replays, or workflow restarts), services must use a stable **business idempotency key** in addition to saga execution identity. `sagaInstanceId` is an execution-trace identifier and must not be the sole dedupe key for business effects.
  - Each step runs inside a local `@Transactional` method for atomicity
  - Compensation logic is registered via hooks
  - Retried automatically or flagged for manual review

### Fluent API Example:

```java
sagaBuilder("accountCreation")
    .step(
        "createAccount",
        accountClient::createAccount,
        accountClient::deleteAccount)
    .step("provisionCharacter", entityClient::createPlayer)
    .step("assignStartingRoom", worldClient::placeInWorld)
    .run();
```

This design centralizes logic, improves visibility, and avoids coupling orchestration directly into gameplay services.
The `common-saga` module provides a `SagaBuilder` class implementing this pattern. See [Shared Libraries Overview](./system-architecture-shared-libraries.md) for additional details.
Services include the library and the accompanying Flyway migrations exposed via
`classpath:db/migration/saga` to persist saga state in the owning service schema's
`saga_instance` and `saga_step` tables.
Example saga flows are documented in [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md)
and in the Logging & Admin Service README.

### Saga vs Temporal Boundary

FireMUD now has an explicit shared boundary:

- `common-saga` is for short synchronous orchestration that can run inline and does not require durable waiting or restart-safe continuation.
- `common-temporal` is for long-running durable control-plane workflows that must survive process restarts, support durable timers/signals/queries/updates, and expose operator-visible workflow lifecycle.
- Gameplay ticks and Redis-backed runtime coordination remain outside both of these workflow substrates and continue to use the tick/idempotency/reconciliation model.

---

## When Not to Use Sagas

Do **not** use sagas for:

- Gameplay commands (combat, move, cast spell, AI)
- Anything inside a tick
- Transient state managed via Redis
- Tasks that are already retryable via tick rescheduling

Use Redis staging/cleanup + tick retries for fast, fair, and consistent gameplay handling.

---

## Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [Shared Libraries Overview](./system-architecture-shared-libraries.md)
- [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md)

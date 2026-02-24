# FireMUD System Architecture: Transaction Strategies

This document explains how FireMUD coordinates data consistency across its independent microservices. It distinguishes between **real-time gameplay commands** (executed within ticks using Redis) and **long-running business workflows** (executed via a Saga pattern using a shared orchestration library). It clarifies when sagas are needed — and when they are not.

---

## Terminology Clarification

| Term | Meaning |
| --- | --- |
| **Command** | A gameplay action issued by a player or AI (e.g., attack, move, use item). Executed inside a tick as a **self-contained gameplay unit** that may touch multiple services but is coordinated via Redis and idempotent domain handlers. |
| **Transaction** | A unit of work that must either fully succeed or be rolled back **within a single service boundary** (for example, a PostgreSQL transaction in Entity Management). Gameplay commands are composed of one or more such local transactions plus idempotent retries; there is **no global, cross-service ACID transaction** for a command. |
| **Tick** | A scheduled gameplay loop slice. Each tick processes at most one command per entity and uses Redis for coordination, staging/cleanup, and fairness. Ticks are not atomic across all commands — each command is executed as an independent composition of local transactions and retries. |
| **Saga** | A long-running, cross-service workflow composed of multiple local transactions. Used only for **non-gameplay**, out-of-band operations (e.g., account creation, game publishing) or rare tick-adjacent workflows that must coordinate persistent state across services over time. Sagas rely on compensating actions for rollback and eventual consistency. |

---

## In-Game Command Transactions (No Sagas Needed)

All real-time gameplay logic — movement, combat, item use, AI — is executed inside **ticks**. Each command is:

- Pulled from the command queue
- Executed using deterministic game logic
- Staged in Redis with Lua-based staging and cleanup/abandon semantics
- Applied via one or more **service-local transactions** guarded by effect identity
- Automatically retried on failure (for example, lock contention or transient errors)

From the player’s perspective, a command appears atomic (“either my move happens or it does not”), but the implementation relies on:

- **Per-service atomicity** – each participating service wraps its own changes in a local database transaction.
- **At-least-once delivery + idempotency** – tick effects may be retried; idempotent guards ensure repeated applications converge to the same logical outcome.
- **Eventual cross-service convergence** – if different services commit at slightly different times, domain invariants converge as retries and reconciliation complete; there is no single ACID boundary spanning them.

This model provides:

- **Per-command logical atomicity from the player’s perspective**
- **Tick-level fairness and isolation**
- **Crash-safe, replayable execution through idempotency**
- **No need for Saga orchestration inside the tick loop**

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

- The Game Session Service computes and propagates a stable `EffectId` derived from the region-scoped tick context (`tenantId`, `regionId`, `regionEpoch`, `tickId`, `effectKey`) plus the target aggregate identity.
- Owning services must implement durable idempotency guards (unique constraints, monotonic updates, transactional outbox) so duplicate `EffectId` attempts become OK/no-op outcomes rather than double-applying side effects.
- To keep this contract consistent across services, tick-driven handlers use a shared idempotency helper from `firemud-common` (for example an `IdempotentEffectExecutor`) instead of ad-hoc “check or insert” patterns. The helper:
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
- Game Session persists (or can deterministically reconstruct) the intended pre/post state for the effect so a reconciliation pass can re-drive the missing side if one service commits and another fails.
- Reconciliation behavior is documented per effect type. The default policy is “retry until convergence” using the original `EffectId`, not “best-effort compensate” with a new effect identity.

Ambient world mutations (doors, hazards, weather) are treated as spatial effects for replay and idempotency purposes:

- All durable ambient mutations must be issued as effect-shaped commands carrying `EffectId` plus the appropriate instance scope (`RoomInstanceRef` for room-scoped changes).
- World Management is authoritative for ambient state used by gameplay (including hazard activation/inactivation state). Game Logic reads this state through World Management snapshot APIs and must not maintain an independent hazard-authority store.
- Operator tooling and scripts must not bypass this contract by writing instance tables directly; they emit the same effect-shaped commands so retries and crash recovery remain safe.

Concrete per-effect required writes and reconciliation rules live in `design/architecture/system-architecture-spatial-and-ambient-effects-catalog.md`. Any new effect must add an entry there before it is used by runtime gameplay.

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

This keeps tick execution fast, bounded, and safely replayable, while sagas and outbox processors own long-running, cross-service workflows. New designs that mix tick-driven state changes with external side effects must explicitly document this boundary and reference both this section and the tick idempotency rules in `system-architecture-tick-failures-and-operations.md`.

---

## When Sagas *Are* Used (Out-of-Band Workflows)

Sagas are only used for **non-tick, multi-service workflows** involving persistent state changes that cannot be coordinated via Redis. These include:

| Use Case | Description |
| --- | --- |
| **Account Creation** | Create account → provision default character → initialize world state |
| **Game Publishing** | Validate and persist design → push to World Service → toggle publish flags |
| **Admin Operations** | Issue bans, content revocation, or entity cleanup with audit logging |
| **In-Game Purchase (rare)** | Only if involving external billing or cross-service coordination beyond Redis tick safety |

These workflows:

- Happen **outside the tick loop**
- Modify **persistent storage (PostgreSQL)** across multiple services
- Require durable coordination and rollback capabilities

### Rollback Boundaries by Operation Class

Cross-service workflows must explicitly choose one of the following rollback classes before implementation:

- **Class A (Pre-Activation Saga Rollback):**
  - Scope: publish-time and pre-runtime workflows where outputs are not yet active for gameplay (for example `PublishVersion` before a version is activated, or world-creation before admission opens).
  - Contract: compensating actions are allowed; saga failure may roll back durable writes or mark the target version/workflow as `FAILED` with deterministic retry/repair.
- **Class B (Post-Activation Runtime Convergence):**
  - Scope: tick-driven gameplay and any mutation visible to live players (movement, containment, ambient mutations, live script-trigger side effects).
  - Contract: no destructive cross-service rollback. Effects are retried with the same `EffectId` until convergence; partial success is resolved by reconciliation, not compensation deletes.

Designs that cross this boundary (for example, activation and live mutations in one flow) must split into two phases with an explicit hand-off point from Class A to Class B.

### State Ownership and Mutation Boundaries

To keep responsibilities clear across design-time, domain, and runtime services:

- Game Design Service owns version metadata, branches, commits, and revision history but does not own canonical schemas or template rows for worlds, entities, or assets.
- Domain services such as World Management, Entity Management, and Game Design’s asset storage tables own their respective schemas and all versioned/template rows keyed by `(tenantId, versionId)`. They must be able to load every non-Retired version they own even if Game Design Service is unavailable.
- Runtime services such as Game Session and Automation & Scripting own transient tick state (primarily in Redis) and any persistent instance data they create via domain-service APIs (for example world instance rows keyed by `(tenantId, gameInstanceId)`), but they must never write template rows directly.
- All cross-service workflows that change persistent state across more than one service database must either:
  - execute inside a Saga defined via `firemud-common` (for example account creation, version publish, world creation), or
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

## FireMUD Saga Architecture

FireMUD uses a **shared saga orchestration library**, not a separate microservice.

### Characteristics:

- **Orchestration**:
  - Centralized in the **firemud-common** library (saga package) located under
    `services/common-library`
  - The engine and its Flyway migrations live in `services/common-library/src/main/resources/db/migration/saga`
  - Hosts define saga flows declaratively using a fluent API or YAML/annotation declarations
  - Saga execution is initiated by services like Account or Game Design, but **coordination logic lives in the library**
  - Participating services include **Account**, **Game Design**, **Game Session**, **World Management**, **Automation Scripting**, **Social Groups**, and **Logging & Admin**
  
- **State Management**:
  - All saga state is persisted in the `saga_instance` and `saga_step` tables provided by the common library.
  - These tables reside in a dedicated `saga` schema inside **each service’s own database**. Flyway migrations from `firemud-common` are applied per service database so saga state is local to the service that owns the workflow.
  - Tracks in-progress, completed, and failed workflows.
  - Supports compensation.
  - Flyway migrations bundled with the library create these tables automatically when consuming services start.
  - `SagaRunner` emits a `sagas.active` metric and attaches a `correlationId` to logs for each workflow using MDC.
  - Operators monitor progress via the Saga Dashboard (`/sagas` and `/sagas/{id}/steps` endpoints) provided by the [Logging & Admin Service](./microservices/logging-admin-service/README.md), which queries saga status via service APIs rather than directly reading every database.
  
- **Execution Model**:
  - Steps are gRPC calls to owning services
  - Helper `GrpcSagaSteps.callWithRetry` wraps gRPC calls with basic retry logic
  - All steps are **idempotent**
  - Each step uses a durable idempotency guard recorded in the owning service’s database (for example keyed by `(tenantId, sagaInstanceId, stepName)` plus any workflow-specific scope such as `gameInstanceId`) so retries can safely no-op or reconcile without duplicating persistent rows.
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
The `firemud-common` library provides a `SagaBuilder` class implementing this pattern. See [Shared Libraries Overview](./system-architecture-shared-libraries.md) for additional details.
Services include the library and the accompanying Flyway migrations located in
`services/common-library/src/main/resources/db/migration/saga` to persist saga state
in the `saga_instance` and `saga_step` tables.
Example saga flows are documented in [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md)
and in the Logging & Admin Service README.

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

# FireMUD System Architecture: Transaction Strategies

This document explains how FireMUD coordinates data consistency across its independent microservices. It distinguishes between **real-time gameplay commands** (executed within ticks using Redis) and **long-running business workflows** (executed via a Saga pattern using a shared orchestration library). It clarifies when sagas are needed — and when they are not.

---

## Terminology Clarification

| Term | Meaning |
| --- | --- |
| **Command** | A gameplay action issued by a player or AI (e.g., attack, move, use item). Executed inside a tick as a **self-contained transaction**, backed by Redis. |
| **Transaction** | A unit of work that must either fully succeed or be rolled back. Each in-game command is treated as an atomic transaction. |
| **Tick** | A scheduled gameplay loop slice. Each tick processes one command per entity and uses Redis for coordination, rollback, and fairness. Ticks are not atomic across all commands — each command is executed as an independent transaction. |
| **Saga** | A long-running, cross-service workflow composed of multiple local transactions. Used only for **non-gameplay**, out-of-band operations (e.g., account creation, game publishing). Sagas rely on compensating actions for rollback and eventual consistency. |

---

## In-Game Command Transactions (No Sagas Needed)

All real-time gameplay logic — movement, combat, item use, AI — is executed inside **ticks**. Each command is:

- Pulled from the command queue
- Executed using deterministic game logic
- Staged in Redis with rollback support via Lua
- Committed only if successful
- Automatically retried on failure (e.g., lock contention)

This model provides:

- **Per-command atomicity**
- **Tick-level fairness and isolation**
- **Crash-safe, replayable execution**
- **No need for Saga orchestration**

> 🔗 See [Tick System and Runtime Design](./system-architecture-ticks.md) and [Redis Architecture](./system-architecture-redis.md) for detail on how ticks provide transactional guarantees.

### Tick Effects Are At-Least-Once: Idempotency Is Mandatory

Tick execution is replayable: retries, failover, and Redis AOF replay can cause the same logical effect to be attempted more than once. For gameplay commands this is expected and safe only because tick-invoked domain mutations are required to be idempotent with respect to a canonical `EffectId`.

- The Game Session Service computes and propagates a stable `EffectId` (derived from `tenantId`, `tickId`, `effectKey`, and the target aggregate identity).
- Owning services must implement durable idempotency guards (unique constraints, monotonic updates, transactional outbox) so duplicate `EffectId` attempts become OK/no-op outcomes rather than double-applying side effects.
- To keep this contract consistent across services, tick-driven handlers use a shared idempotency helper from `firemud-common` (for example an `IdempotentEffectExecutor`) instead of ad-hoc “check or insert” patterns. The helper:
  - Accepts `EffectId` plus callbacks for “apply-if-first” and “handle-replay”.
  - Encapsulates the canonical guard pattern (insert-if-absent, treat conflicts as replay) and throws well-defined exceptions on guard violations.
  - Emits a simple, standardized counter such as `tick.effect_outcome_total{service, effect_type, outcome}` so operators can distinguish first-apply vs replay behavior across services without per-tenant configuration.

> 🔗 The canonical `EffectId` contract and per-side-effect patterns are defined in [Tick Effect Identity and Idempotency Contract](./system-architecture-ticks.md#tick-effect-identity-and-idempotency-contract).

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

Use Redis rollback + tick retries for fast, fair, and consistent gameplay handling.

---

## Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [Shared Libraries Overview](./system-architecture-shared-libraries.md)
- [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md)

# ⚙️ FireMUD System Architecture: Transaction Strategies

This document explains how FireMUD coordinates data consistency across its independent microservices. It distinguishes between **real-time gameplay commands** (executed within ticks using Redis) and **long-running business workflows** (executed via a Saga pattern using a shared orchestration library). It clarifies when sagas are needed — and when they are not.

---

## 🧠 Terminology Clarification

| Term         | Meaning |
|--------------|---------|
| **Command**  | A gameplay action issued by a player or AI (e.g., attack, move, use item). Executed inside a tick as a **self-contained transaction**, backed by Redis. |
| **Transaction** | A unit of work that must either fully succeed or be rolled back. Each in-game command is treated as an atomic transaction. |
| **Tick**     | A scheduled gameplay loop slice. Each tick processes one command per entity and uses Redis for coordination, rollback, and fairness. Ticks are not atomic across all commands — each command is executed as an independent transaction. |
| **Saga**     | A long-running, cross-service workflow composed of multiple local transactions. Used only for **non-gameplay**, out-of-band operations (e.g., account creation, game publishing). Sagas rely on compensating actions for rollback and eventual consistency. |

---

## 🎮 In-Game Command Transactions (No Sagas Needed)

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

---

## 🧩 When Sagas *Are* Used (Out-of-Band Workflows)

Sagas are only used for **non-tick, multi-service workflows** involving persistent state changes that cannot be coordinated via Redis. These include:

| Use Case              | Description |
|-----------------------|-------------|
| **Account Creation**  | Create account → provision default character → initialize world state |
| **Game Publishing**   | Validate and persist design → push to World Service → toggle publish flags |
| **Admin Operations**  | Issue bans, content revocation, or entity cleanup with audit logging |
| **In-Game Purchase (rare)** | Only if involving external billing or cross-service coordination beyond Redis tick safety |

These workflows:

- Happen **outside the tick loop**
- Modify **persistent storage (PostgreSQL)** across multiple services
- Require durable coordination and rollback capabilities

---

## ✅ FireMUD Saga Architecture

FireMUD uses a **shared saga orchestration library**, not a separate microservice.

### Characteristics:

- **Orchestration**:
  - Centralized in the **firemud-common** library (saga package)
  - The engine and its Flyway migrations live in `services/common-library`
  - Hosts define saga flows declaratively using a fluent API
  - Saga execution is initiated by services like Account or Game Design, but **coordination logic lives in the library**
  - Participating services include **Account**, **Game Session**, **World Management**, **Social Groups**, and **Logging & Admin**
  
- **State Management**:
  - All saga state is persisted in the `saga_instance` and `saga_step` tables provided by the common library
  - Tracks in-progress, completed, and failed workflows
  - Supports retry, compensation, and alerting
  - `SagaRunner` emits a `sagas.active` metric and attaches a `correlationId` to logs for each workflow
  - Operators monitor progress in the Saga Dashboard provided by the Logging & Admin Service
  
- **Execution Model**:
  - Steps are gRPC calls to owning services
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
The `firemud-common` library provides a `SagaBuilder` class implementing this pattern.
Services include the library and the accompanying Flyway migrations to persist
saga state in the `saga_instance` and `saga_step` tables.
Example saga flows are documented in [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md)
and in the Logging & Admin Service README.

---

## 🔁 When Not to Use Sagas

Do **not** use sagas for:

- Gameplay commands (combat, move, cast spell, AI)
- Anything inside a tick
- Transient state managed via Redis
- Tasks that are already retryable via tick rescheduling

Use Redis rollback + tick retries for fast, fair, and consistent gameplay handling.

---

## 🔭 Future Enhancements

- **Saga Dashboard** is already available in the [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- **Timeout detection** and auto-recovery of stalled workflows
- **Declarative flow definitions** via YAML or annotations
- **Integration with logging/metrics** for saga observability

---

## 📚 Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [Shared Libraries Overview](./system-architecture-shared-libraries.md)
- [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md)
- [Logging & Admin Service README](./microservices/logging-admin-service/README.md)

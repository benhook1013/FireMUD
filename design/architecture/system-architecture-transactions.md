# ⚙️ FireMUD System Architecture: Transaction Strategies

This document explains how FireMUD coordinates data consistency across its independent microservices. It complements the [System Architecture Overview](./system-architecture-overview.md) by outlining patterns for cross-service workflows. Traditional distributed transactions are impractical in a polyglot environment, so the platform favors lighter approaches. Tick execution itself is handled atomically in Redis; see [Redis Architecture](./system-architecture-redis.md) and [Tick System](./system-architecture-ticks.md) for details on those local transactions.

---

## 📚 Options Considered

- **Two-Phase Commit (2PC)**
  - Guarantees atomicity across services but adds heavy coordination overhead.
  - Requires all services to share a transaction coordinator and can block progress during failures.
- **Event-Driven Saga Pattern**
  - Orchestrates a multi-step workflow as a sequence of local transactions.
  - Each service emits an event after completing its step; compensating actions undo work on failure.
- **Choreography with Asynchronous Messaging**
  - Services react to published events without a centralized orchestrator.
  - Simpler but harder to trace; compensations still required for rollbacks.

## ✅ FireMUD Approach

FireMUD adopts the **Saga pattern** executed over **gRPC** calls rather than a message broker. Each service owns its data and exposes gRPC endpoints for local transaction steps. A lightweight orchestrator invokes these services in sequence, propagating transaction context between them. If a step fails, the orchestrator triggers compensating gRPC calls to roll back prior work.

- **Consistency Level** — eventual consistency is acceptable for gameplay state that spans services.
- **Failure Handling** — compensating calls roll back partial state, while the orchestrator logs unfinished sagas for manual review.
- **Implementation** — the orchestrator stores Saga state in PostgreSQL and uses standard gRPC retry semantics to ensure commands are delivered.

This design keeps services loosely coupled while providing a clear strategy for multi-step operations such as account creation, game publishing, or in-game purchases.

## 📚 Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Game Session Service](./microservices/game-session-service/README.md) — atomic command execution using Redis transactions
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)


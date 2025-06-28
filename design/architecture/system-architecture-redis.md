# 🧠 FireMUD System Architecture: Redis

This document outlines FireMUD’s usage of Redis as a **transient, high-performance, distributed coordination layer**. It focuses on Redis's responsibilities, safety guarantees, key patterns, and operational practices.

> 🔗 For full tick execution, retries, and lock behavior, see [Tick System and Runtime Flow](./system-architecture-ticks.md).

---

## ⚠️ Redis as a Volatile State Layer

Redis is used **exclusively for non-authoritative, transient data**, including:

- WebSocket session bindings and live gameplay context
- In-flight command queues
- Tick locks and staged results
- Cooldowns and timer expirations
- Retry metadata and **inter-tick conflict tracking**
- AI/scripted action injection

All **canonical game data** — accounts, entities, items, rooms — resides in **PostgreSQL**, owned by domain-specific services.

Redis acts as a **coordinated real-time buffer**, not a source of truth — but is still treated as **critical** for game availability and consistency.

> The **Game Session Service depends on Redis** to coordinate safe, concurrent tick execution across multiple instances.

### ✅ Benefits

- Low-latency access for gameplay-critical state
- Enables stateless, horizontally scalable services
- Supports safe concurrent ticks and session handling
- Facilitates reconnection, failover, and replay

---

## 🛡️ Redis Availability, Consistency, and Safety Guarantees

Redis is a **non-persistent** layer — but FireMUD treats it as **critical to gameplay correctness**. Availability and consistency are essential despite its transient nature.

### Cluster Deployment

FireMUD runs Redis as a **clustered, replicated setup**:

- Multiple **shards and replicas** for horizontal partitioning
- Partitioning aligned to tick regions and sessions
- Redis Sentinel or **Kubernetes-native failover**
- **Automatic failover** behavior tested under load

### Replication and Durability

- Writes are **asynchronously replicated** to reduce latency
- **AOF (Append-Only File)** enabled for crash recovery
- Critical writes use `WAIT 1 100` to ensure **replica acknowledgment**

---

## 🗂️ Key Naming and Shard Discipline

Redis keys follow a strict naming convention to support:

- Efficient debugging and monitoring
- Shard-aware distribution (avoiding cross-shard ops)
- Conflict isolation and atomicity
- Avoiding **cross-node inconsistencies**

### Key Format Examples

| Redis Key                         | Description                              |
|----------------------------------|------------------------------------------|
| `session:{playerId}`             | Active session context and socket binding |
| `tick:lock:{entityId}`           | Lock for entity during tick execution    |
| `tick:pending:{regionId}`        | Staged results for a tick region         |
| `room:{roomId}:occupants`        | Room occupancy snapshot                  |
| `retry:{regionId}`               | Retry queue for failed actions           |
| `timer:{entityId}:{effectId}`    | Cooldown/effect timer metadata           |

> ⚠️ **FireMUD avoids cross-shard operations.** Tick regions and player sessions are scoped to a **single Redis shard** to maintain atomicity and simplify logic.

---

## 🔒 Atomicity and Concurrency Control

Redis’s single-threaded model is extended via **Lua scripts** to enable atomic operations such as:

- Lock acquisition and validation (`tick:lock:*`)
- Tick staging, **rollback**, and commit coordination (`tick:pending:*`)
- Session and timer lifecycle management
- Retry queue manipulation (`retry:*`)
- AI/scripting action injection

All scripts are:

- **Idempotent** and **retry-safe**
- **Shard-isolated** to avoid **cross-node inconsistencies**
- Protected against cross-tick contamination

> 🔗 See [Tick Locking](./system-architecture-ticks.md#🔐-distributed-entity-locking) for usage during ticks.

---

## ⏱️ Tick Integration (Resilience, Locking, Staging)

Redis is essential for **coordinating safe tick execution** across distributed workers.

It provides:

- Centralized **command queues**
- Distributed **lock and retry control**
- Durable **tick staging** with rollback and partial commit support
- **Cooldowns and timers** tied to real-world time

> 🔗 Tick execution logic is fully defined in [Tick System and Runtime Flow](./system-architecture-ticks.md#🔄-tick-execution-model).
> 🔁 Redis enables replayable, deterministic ticks even after crashes — with recovery powered by Lua, AOF, and `WAIT`.

### 💥 In Case of Interruption or Crash

- Redis retains all necessary transient state
- The tick can be retried, resumed, or skipped deterministically
- Double-processing is avoided using Lua and lock validation

---

## 📈 Observability and Reliability

FireMUD actively monitors Redis health and tick performance via:

- **Prometheus metrics**:
  - Script execution latency
  - Lock contention and retry counts
  - Memory and keyspace usage
- **Grafana dashboards** for real-time trends
- **Backoff and retry logic** at service level
- **Graceful degradation** if Redis becomes briefly unavailable

FireMUD does **not use in-memory service caches** — all volatile state is centralized in Redis to support reconnection and failover.

---

## ✅ Summary

Redis in FireMUD is:

- A **volatile gameplay layer**, not a persistent datastore
- Essential for **ticks, timers, sessions, rollback, and conflict coordination**
- Backed by **AOF and `WAIT` guarantees** for resilience
- Scripted via **Lua** for atomicity and correctness
- **Shard-isolated** to prevent cross-node inconsistencies
- Tightly integrated with [Tick System and Runtime Flow](./system-architecture-ticks.md) for deterministic multiplayer execution

---

📚 **Related Documentation**

- [Tick System and Runtime Flow](./system-architecture-ticks.md)
- [System Architecture Overview](./system-architecture-overview.md)

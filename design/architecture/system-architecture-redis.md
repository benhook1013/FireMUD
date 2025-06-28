# 🧠 FireMUD System Architecture: Redis

This document outlines FireMUD’s usage of Redis as a transient, high-performance, distributed state layer. It focuses on responsibilities, safety guarantees, usage patterns, and operational practices.

> 🔗 For game logic, tick execution, retries, and lock behavior, see [Tick System and Runtime Flow](./system-architecture-ticks.md).

---

## ⚠️ Redis as a Volatile State Layer

Redis is used **exclusively for transient, non-authoritative data**, such as:

- Player session context and WebSocket bindings
- In-flight command queues
- Tick region locks and state staging
- Cooldowns, timers, and volatile effects
- Retry metadata and inter-tick conflict tracking
- Automation triggers and AI behavior injection

All **canonical game data** (accounts, entities, world, items) resides in **PostgreSQL**, owned by domain-specific services. Redis acts as a real-time coordination layer, not a source of truth.

**Benefits:**

- High-throughput, low-latency state access
- Stateless and horizontally scalable services
- Safe concurrency for tick cycles and player sessions
- Seamless reconnection and crash recovery

---

## 🛡️ Redis Availability, Consistency, and Safety Guarantees

Redis is treated as a **critical gameplay layer**, where availability and consistency are essential despite being non-persistent.

### Cluster Deployment

FireMUD uses a **Redis Cluster** setup with:

- Multiple masters (shards) and replicas
- **Horizontal partitioning** across rooms and sessions
- **Automatic failover** via Redis Sentinel or Kubernetes-native tooling

### Replication and Persistence

- All writes are **asynchronously replicated** to replicas
- **Append-Only File (AOF)** persistence is enabled
- After critical operations (e.g., staging or lock writes), FireMUD executes:

  WAIT 1 100

  This blocks until at least one replica acknowledges the write, reducing the risk of mid-tick data loss during failover

---

## 🔒 Atomicity and Concurrency Control with Lua

Redis’s single-threaded model is leveraged using **Lua scripts** for atomic operations such as:

- Lock acquisition and validation (`tick:lock:{entityId}`)
- Tick state staging and rollback (`tick:pending:{regionId}`)
- Session management (`session:{playerId}`)
- Retry queue manipulation (`retry:{regionId}`)
- AI or scripted action injection

All Lua operations are:

- **Idempotent**
- **Retry-safe**
- **Shard-isolated** to avoid cross-node inconsistencies

For behavior under contention and failure, see [Tick System and Runtime Flow](./system-architecture-ticks.md#🔐-distributed-entity-locking).

---

## ⏱️ Tick Execution Resilience

The **Game Session Service** depends on Redis to coordinate safe, concurrent tick execution across multiple instances.

Redis stores:

- Command queues per entity
- Tick locks and staged results
- Timer expirations and cooldown durations
- Smart retry metadata and conflict tracking

In case of interruption or crash:

- Redis retains all necessary transient state
- The tick can be retried, resumed, or skipped deterministically
- Double-processing is avoided using Lua and lock validation

> 🔗 See [Tick System and Runtime Flow](./system-architecture-ticks.md#💥-crash-safety-and-recovery) for gameplay-side crash recovery.

---

## 📈 Operational Guarantees and Monitoring

Redis health and tick performance are actively monitored through:

- **Prometheus metrics**:
  - Script execution time
  - Lock contention duration
  - Memory and keyspace usage
- **Grafana dashboards** for real-time observability
- **Service-level retry and backoff logic** in case of Redis delays
- **Graceful degradation** strategies if Redis becomes temporarily unavailable

All volatile state is stored in Redis — FireMUD avoids reliance on in-memory caching inside services.

---

## 🗂️ Key Naming and Shard Discipline

Redis keys follow a **strict namespace convention** to support:

- Efficient querying and debugging
- Conflict isolation
- Shard-friendly distribution

### Example Key Formats

| Redis Key                         | Description                              |
|----------------------------------|------------------------------------------|
| `session:{playerId}`             | Active session context and bindings      |
| `tick:lock:{entityId}`           | Lock for tick execution involving entity |
| `tick:pending:{regionId}`        | Staged tick results for a region         |
| `room:{roomId}:occupants`        | Entities currently in the room           |
| `retry:{regionId}`               | Retry queue for failed or deferred actions |
| `timer:{entityId}:{effectId}`    | Cooldown or effect timer state           |

> ⚠️ FireMUD avoids cross-shard operations. Tick regions and player sessions are scoped to a single Redis shard to maintain atomicity and simplify logic.

---

## ✅ Summary

Redis in FireMUD is:

- A **volatile coordination layer**, not a persistent datastore
- Essential for **ticks, timers, locks, and session tracking**
- **Backed by AOF and replica sync** (`WAIT`) for resilience
- Driven by **Lua scripting** for atomicity and concurrency control
- **Horizontally scalable**, shard-aware, and fault-tolerant
- Closely integrated with [Tick System and Runtime Flow](./system-architecture-ticks.md) for deterministic gameplay

---

📚 **Related Documentation**

- [Tick System and Runtime Flow](./system-architecture-ticks.md)
- [System Architecture Overview](./system-architecture-overview.md)

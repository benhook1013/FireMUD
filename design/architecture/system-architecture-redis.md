# 🧠 FireMUD System Architecture: Redis

This document outlines FireMUD’s usage of Redis as a **transient, high-performance, distributed coordination layer**. It focuses on Redis's responsibilities, safety guarantees, key patterns, and operational practices.

> 🔗 For full tick execution, retries, and lock behavior, see [Tick System and Runtime Design](./system-architecture-ticks.md).

---

## ⚠️ Redis as a Volatile State Layer

Redis is used **exclusively for non-authoritative, transient data**, including:

- WebSocket session bindings and live gameplay context
- In-flight command queues
- Tick locks and staged results
- Cooldowns and timer expirations (stored in milliseconds)
- Retry metadata and **inter-tick conflict tracking**
- AI/scripted action injection

All **canonical game data** — accounts, entities, items, rooms — resides in **PostgreSQL**, owned by domain-specific services.

Redis acts as a **coordinated real-time buffer**, not a source of truth — but is still treated as **critical** for game availability and consistency.

The **Game Session Service** is responsible for coordinating tick and session behavior using Redis as its execution substrate.

### ✅ Benefits

- Low-latency access for gameplay-critical state
- Enables stateless, horizontally scalable services
- Supports safe concurrent ticks and session handling
- Facilitates reconnection, failover, and replay

---

## 🛡️ Redis Availability, Consistency, and Safety Guarantees

Redis is a **non-persistent** layer — but FireMUD treats it as **essential** for consistent multiplayer behavior. Availability and deterministic recovery are prioritized.

### Cluster Deployment

FireMUD runs Redis in a **clustered, replicated configuration**:

- Multiple **shards and replicas** for tick region and session partitioning
- Partitioning aligns with tick region boundaries (typically per-room or per-segment)
- Redis Sentinel or **Kubernetes-native failover**
- **Failover behavior is tested under live tick loads**

### Replication and Durability

- Writes are **asynchronously replicated**
- **AOF (Append-Only File)** enabled for durability and crash recovery
- Critical Lua writes use `WAIT 1 100` for **replica acknowledgment**

---

## 🗂️ Key Naming and Shard Discipline

Redis keys follow strict naming conventions to ensure:

- Shard-aware key locality
- Conflict and retry isolation
- Debuggable and traceable behavior
- Clean atomic execution across tick regions

### Key Format Examples

| Redis Key                         | Description                              |
|----------------------------------|------------------------------------------|
| `session:{playerId}`             | Active session context and socket binding |
| `tick:lock:{entityId}`           | Lock for entity during tick execution    |
| `tick:pending:{regionId}`        | Staged results for a tick region         |
| `room:{roomId}:occupants`        | Room occupancy snapshot                  |
| `retry:{regionId}`               | Retry queue for failed actions           |
| `timer:{entityId}:{effectId}`    | Cooldown/effect timer metadata (in ms)   |

> ⚠️ Tick regions and player sessions are **always scoped to a single Redis shard** to preserve atomicity. Cross-shard operations are avoided.

---

## 🔒 Atomicity and Concurrency Control

Redis’s single-threaded model is extended using **Lua scripts** for atomic operations:

- Entity lock acquisition (`tick:lock:*`)
- Tick staging, commit, and rollback (`tick:pending:*`)
- Session lifecycle management
- Timer lifecycle management
- Retry queue updates
- AI/scripted action injection

All Lua scripts are:

- **Idempotent**
- **Shard-local**
- **Retry-safe**
- Designed to avoid cross-tick contamination

> 🔗 For use during tick execution, see [Tick Locking](./system-architecture-ticks.md#🔐-distributed-locking)

---

## ⏱️ Tick Integration (Resilience, Locking, Staging)

Redis is essential for **coordinating tick execution** across distributed worker services.

It provides:

- Per-entity **command queues**
- Distributed **locks** and **retry tracking**
- Durable **tick staging**
- Accurate **cooldown and timer tracking**
- **Conflict metadata** for retry prioritization

> 🔁 Ticks are replayable and deterministic due to Lua-based staging, lock control, and AOF durability.  
> 🔗 See [Tick Execution Flow](./system-architecture-ticks.md#🔄-tick-execution-flow)

### 💥 Crash and Recovery Safety

If a tick is interrupted:

- Redis retains:
  - Locks
  - Staged updates
  - Timers
  - Retry metadata
- Game Session Service can:
  - Retry or roll forward incomplete ticks
  - Prevent double-processing via lock validation

All recovery is deterministic and safe.

---

## 📈 Observability and Reliability

FireMUD actively monitors Redis performance and tick health:

- **Prometheus metrics** (via Redis exporters):
  - Lua script latency
  - Lock contention
  - Retry queue depth
  - Keyspace and memory usage
- **Grafana dashboards** visualize tick throughput and hotspots
- **Graceful degradation** logic reduces gameplay interruption if Redis temporarily stalls
- Redis is the **only** volatile coordination layer — no per-service caches are used

> 🔗 Redis observability is connected to system-wide dashboards. See [System Overview](./system-architecture-overview.md#📊-observability-and-monitoring)

---

## ✅ Summary

Redis in FireMUD is:

- A **transient, high-performance coordination layer**
- Used for **ticks, timers, locks, retries, and in-flight state**
- Not a source of truth — but treated as **critical infrastructure**
- Durable via **AOF** and `WAIT` guarantees
- Scripted via **Lua** for atomic tick control
- Always **shard-local** to avoid cross-node inconsistencies
- Tightly coupled with the **Game Session Service**, which orchestrates all tick-related flow

---

📚 **Related Documentation**

- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [System Architecture Overview](./system-architecture-overview.md)

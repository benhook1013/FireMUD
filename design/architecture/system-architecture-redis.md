# 🧠 FireMUD System Architecture: Redis

This document outlines FireMUD’s usage of Redis as a transient, high-performance, distributed state layer. It covers responsibilities, safety guarantees, usage patterns, and operational practices.

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

Benefits:

- High-throughput, low-latency state access  
- Stateless and horizontally scalable services  
- Safe concurrency and deterministic tick cycles  
- Seamless reconnection and crash recovery  

---

## 🛡️ Redis Availability, Consistency, and Safety Guarantees

Redis is treated as a **critical gameplay layer**, where availability and consistency are essential, even though the data is not permanent.

### Cluster Deployment

FireMUD uses a **Redis Cluster** setup with:

- Multiple masters (shards) and replicas  
- **Horizontal partitioning** across rooms and sessions  
- **Automatic failover** using Redis Sentinel or Kubernetes-native tooling  

### Replication and Persistence

- All writes are **asynchronously replicated** to replicas  
- **Append-Only File (AOF)** persistence is enabled  
- After critical tick/session writes, FireMUD executes:  
  `WAIT 1 100`  
  This blocks until at least one replica acknowledges the write, reducing the chance of mid-operation data loss during failover  

---

## 🔒 Atomicity and Concurrency Control with Lua

Redis’s single-threaded model is leveraged through **Lua scripts** for:

- Lock acquisition and validation  
- Tick staging and rollback  
- Session updates and command queue ops  
- Retry queue management  

FireMUD uses structured Redis keys for clear sharding, efficient queries, and debug visibility. Examples:

- `tick:lock:{entityId}` – Lock for actions involving this entity  
- `tick:pending:{regionId}` – Tick-staged state pending commit  
- `session:{playerId}` – Active session and WebSocket bindings  
- `retry:{regionId}` – Deferred or failed actions  
- `room:{roomId}:occupants` – Current occupants in a room  

Locks are short-lived and released automatically. All operations are idempotent and safe to retry.

---

## ⏱️ Tick Execution Resilience

The **Game Session Service** depends on Redis to coordinate safe, concurrent tick execution across multiple instances.

Redis stores:

- Command queues per entity  
- Locks per room or entity  
- Tick-stage state (HP, status, timers, inventory diffs)  
- Timer expirations and time-scaled effects  
- Smart retry metadata and conflict tracking  

If a **tick is interrupted or a service crashes**:

- Redis retains all necessary transient state  
- The tick can be retried, resumed, or skipped deterministically  
- No double-processing or rollback risk  

See the [Tick System and Runtime Design](./system-architecture/system-architecture-ticks.md) for more.

---

## 📈 Operational Guarantees and Monitoring

Redis health and contention are actively monitored using:

- **Prometheus** metrics (e.g., script load, lock wait time, memory usage)  
- **Grafana** dashboards for real-time tick performance  
- Service-level **retry and backoff logic** for resilience  
- Graceful degradation if Redis becomes temporarily unavailable  
- **No dependency on local memory** — all volatile state lives in Redis  

---

## 🗂️ Key Naming and Shard Discipline

Redis keys are **strictly namespaced** and follow structured patterns for:

- Debugging ease  
- Shard-friendly distribution  
- Conflict isolation  

Examples:

| Redis Key                         | Description                              |
|----------------------------------|------------------------------------------|
| `session:{playerId}`             | Active session context                   |
| `tick:lock:{entityId}`           | Lock for tick execution involving entity |
| `tick:pending:{regionId}`        | Staged tick results for a region         |
| `room:{roomId}:occupants`        | Current entity occupancy                 |
| `retry:{regionId}`               | Retry queue for that tick region         |
| `timer:{entityId}:{effectId}`    | Cooldown or effect timer state           |

> ⚠️ FireMUD avoids cross-shard operations. Ticks and sessions are scoped to a single Redis shard to maintain atomicity and simplify logic.

---

## ✅ Summary

Redis in FireMUD is:

- A **volatile coordination layer**, not a persistent datastore  
- Essential for **ticks, timers, locks, and session tracking**  
- **Backed by AOF and replication** for resilience  
- Coordinated through **Lua scripts and `WAIT` commands** for safety  
- **Horizontally scalable** with structured key patterns and partitioning  
- Designed for **elastic, fault-tolerant execution** of the game loop  

---

📚 **Related Documentation**

- [Tick System and Runtime Design](./system-architecture/system-architecture-ticks.md)  
- [System Architecture Overview](./system-architecture-overview.md)

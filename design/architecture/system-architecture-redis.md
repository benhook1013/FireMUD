# 🧠 FireMUD System Architecture: Redis

This document outlines FireMUD’s usage of Redis as a **transient, high-performance, distributed coordination layer**. It focuses on Redis's responsibilities, safety guarantees, key patterns, and operational practices.

> 🔗 For full tick execution, retries, and lock behavior, see [Tick System and Runtime Design](./system-architecture-ticks.md). Out-of-band workflows rely on the **gRPC-based Saga approach** described in [Transaction Strategies](./system-architecture-transactions.md).

---

## ⚠️ Redis as a Volatile State Layer

Redis is used **exclusively for non-authoritative, transient data**, including:

- In-flight command queues
- Tick locks and staged results
- Cooldowns and timer expirations (stored in milliseconds)
- Gameplay session state and real-time coordination data
  _(e.g., command queues, timers, tick participation — see [Session Keys](#-session-keys-and-gameplay-binding))_
- Retry metadata and inter-tick conflict tracking
- TTL-based service caches such as hot room lookups and recent chat history
  _(see [Performance Optimization Guidelines](./performance-optimization.md))_
- Automation queue keys for script events (`automation_queue:{tenantId}:{entityId}`)

Services connect to Redis using the `FIREMUD_REDIS_HOST` and `FIREMUD_REDIS_PORT`
environment variables described in
[Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#redis-connection).

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
- Kubernetes-native failover
- **Failover behavior is tested under live tick loads**
- Tick lock and retry keys are **retained across failover** due to AOF and synchronous Lua-based commit policies, ensuring ticks can resume safely after leadership handoff.

> For operational context on Docker Compose vs Kubernetes, see [Deployment Environments](./infrastructure/deployment-environments.md).

### Replication and Durability

- Writes are **asynchronously replicated**
- **AOF (Append-Only File)** enabled for durability and crash recovery
- AOF files are wiped on each Helm upgrade to start with a clean state
  (see [Backup & Recovery](./system-architecture-backup-recovery.md#redis-aof-reset-on-deployment))
- Critical Lua writes use `WAIT 1 100` for **replica acknowledgment**

---

## 🗂️ Key Naming and Shard Discipline

Redis keys follow strict naming conventions to ensure:

- Shard-aware key locality
- Clean atomic execution across tick regions
- Conflict and retry isolation
- Debuggable and traceable behavior
- Tenant-based prefixes for multi-tenant isolation
  _(see [Multi-Tenancy](./system-architecture-multi-tenancy.md))_

### Key Format Examples

| Redis Key                      | Description                              |
|-------------------------------|------------------------------------------|
| `tick:lock:{tenantId}:{entityId}` | Lock for entity during tick execution    |
| `tick:pending:{tenantId}:{regionId}` | Staged results for a tick region         |
| `room:{tenantId}:{roomId}`               | Hot room cache as JSON (occupants and metadata)                  |
| `retry:{tenantId}:{regionId}`            | Retry queue for failed actions           |
| `timer:{tenantId}:{entityId}:{effectId}` | Cooldown/effect timer metadata (in ms)   |
| `remote:{tenantId}:{entityId}` | Queue for cross-region command follow-ups |

> 🔗 `remote:{tenantId}:{entityId}` keys route cross-region commands. See [Cross-Region Command Execution and Result Relay](./system-architecture-ticks.md#📡-cross-region-command-execution-and-result-relay)
> for details.
> 📌 For session-related keys and structure, see [Session Keys and Gameplay Binding](#-session-keys-and-gameplay-binding)
> ⚠️ Tick regions and player sessions are **always scoped to a single Redis shard** to preserve atomicity. Cross-shard operations are avoided.

---

## 🔒 Atomicity and Concurrency Control

Redis’s single-threaded model is extended using **Lua scripts** for atomic operations:

- Entity lock acquisition (`tick:lock:*`)
- Tick staging, commit, and rollback (`tick:pending:*`)
- Timer lifecycle management
- Session rebinding and deduplication (`session:*` keys)
- Retry queue updates

All Lua scripts are:

- Stored under `services/game-session-service/src/main/resources/redis/`

- **Idempotent**
- **Shard-local**
- **Retry-safe**
- Designed to avoid cross-tick contamination

> 🔗 For use during tick execution, see [Distributed Locking](./system-architecture-ticks.md#🔐-distributed-locking)

### Example Lock Workflow

1. Acquire `tick:lock:{tenantId}:{entityId}` using `SET NX PX` with a TTL equal to the tick duration.
2. Stage updates under `tick:pending:{tenantId}:{regionId}` via Lua script while the lock is held.
3. On successful commit the lock is released and staged data is flushed.
4. If the lock expires, the next tick replays `tick:pending:{tenantId}:{regionId}` and attempts the workflow again.

### 🔀 Shard Locality and Cross-Region Behavior

Redis **does not support cross-shard operations**. All tick locks, Lua scripts,
and queued commands execute on a **single shard** aligned to the tick region.
When an action crosses regional boundaries (for example a player moving between
rooms on different shards) the Game Session Service decomposes the transition
into **two sequential ticks**:

1. **Tick&nbsp;A** on _Shard&nbsp;X_ performs exit logic and clears local state.
2. **Tick&nbsp;B** on _Shard&nbsp;Y_ applies entry logic and rebinds the
   session in the new region.

No lock, Lua script, or tick context may span shards. The Game Session Service
guarantees these ticks execute sequentially without overlap so no effect runs
simultaneously across shard boundaries. See
[Cross-Region Command Execution and Result Relay](./system-architecture-ticks.md#📡-cross-region-command-execution-and-result-relay)
for how follow-up commands are routed.

### 🌀 Global Effects and Region-Wide Coordination

Tick regions **do not execute unless explicitly triggered**. Idle regions will
never see scheduled global events on their own. To apply a world-wide effect —
for example, a server-wide freeze or weather change — the **Game Session
Service** identifies every active region and **fan-outs tick tasks**:

1. Commands are injected into each region’s shard-local keyspace.
2. A tick is triggered in that region to apply the effect atomically.

This ensures global events are processed by all active regions, even if those
regions would otherwise remain idle. The approach preserves shard-local atomicity
and deterministic recovery without cross-shard locks or speculative polling. It
also avoids scheduling global keys that might wake otherwise idle regions.

Regions still run a lightweight background tick (for example every second) so
queued timers, cooldowns, or delayed events progress even when no players are
present.

---

## ⏱️ Tick Integration (Resilience, Locking, Staging)

Redis is essential for **coordinating tick execution** across distributed worker services.

It provides:

- Per-entity **command queues**
- Durable **tick staging**
- Distributed **locks** and **retry tracking**
- **Conflict metadata** for retry prioritization
- Accurate **cooldown and timer tracking**

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
- Metrics are scraped via a [`redis-exporter`](../../k8s/monitoring/redis-exporter.yaml) deployment
  (applyable via [`k8s/README.md`](../../k8s/README.md))
- **Grafana dashboards** visualize tick throughput and hotspots
- **Prometheus Alertmanager** sends alerts if metrics exceed thresholds
- **Graceful degradation** logic reduces gameplay interruption if Redis temporarily stalls
- Redis is the **single shared** volatile coordination layer — services do not maintain separate in-memory caches or alternative cache technologies

> 🔗 Redis observability feeds into the common stack described in [Logging & Monitoring](./system-architecture-logging-monitoring.md)

---

## 🧠 Session Keys and Gameplay Binding

Redis stores transient gameplay session state for each connected player, including:

- Socket binding metadata
- Active `playerId` and `tenantId` context
- Tick region participation and queued commands
- Timer and cooldown data
- Conflict and retry metadata

Session entries expire after `FIREMUD_AUTH_SESSION_EXPIRATION_MS` milliseconds as
configured in [Environment & Secrets](./infrastructure/environment-and-secrets.md#authentication).

This state is used by the **Game Session Service** to:

- Resume gameplay after disconnects
- Rebind gameplay context to a new socket
- Deduplicate reconnect attempts
- Handle character takeovers (one session per character)

> 🔐 Key formats are internal and subject to change. Services treat Redis as a coordination layer, not a persistent or public contract.

---

## ✅ Summary

Redis in FireMUD is:

- A **transient, high-performance coordination layer**
- Used for **ticks, timers, locks, retries, and gameplay session state**
  _(see [Session Keys](#-session-keys-and-gameplay-binding))_
- Scripted via **Lua** for atomic tick and session control
- Durable via **AOF** and `WAIT` guarantees
- Always **shard-local** to avoid cross-node inconsistencies
- Tightly coupled with the **Game Session Service**, which orchestrates all tick-related flow
- Not a source of truth — but treated as **critical infrastructure**

---

## 📚 Related Documentation

- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Transaction Strategies](./system-architecture-transactions.md)

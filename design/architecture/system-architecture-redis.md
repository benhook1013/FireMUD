# FireMUD System Architecture: Redis

This document outlines FireMUD’s usage of Redis as a **transient, high-performance, distributed coordination layer**. It focuses on Redis's responsibilities, safety guarantees, key patterns, and operational practices.

> 🔗 For full tick execution, retries, and lock behavior, see [Tick System and Runtime Design](./system-architecture-ticks.md). Out-of-band workflows rely on the **gRPC-based Saga approach** described in [Transaction Strategies](./system-architecture-transactions.md).

---

## Redis as a Volatile State Layer

Redis is used **exclusively for non-authoritative, transient data**, including:

- In-flight command queues
- Tick locks and staged results
- Cooldowns and timer expirations (stored in milliseconds)
- Gameplay session state and real-time coordination data (e.g., command queues, timers, tick participation — see [Session Keys](#session-keys-and-gameplay-binding))
- Retry metadata and inter-tick conflict tracking
- TTL-based service caches such as hot room lookups and recent chat history (see [Performance Optimization Guidelines](./performance-optimization.md))
- Automation queue keys for script events (`automation_queue:{tenantId}:{entityId}`)

Services connect to Redis using the `FIREMUD_REDIS_HOST` and `FIREMUD_REDIS_PORT`
environment variables described in
[Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#redis-connection).

All **canonical game data** — accounts, entities, items, rooms — resides in **PostgreSQL**, owned by domain-specific services.

Redis acts as a **coordinated real-time buffer**, not a source of truth — but is still treated as **critical** for game availability and consistency.

The **Game Session Service** is responsible for coordinating tick and session behavior using Redis as its execution substrate.

### Benefits

- Low-latency access for gameplay-critical state
- Enables stateless, horizontally scalable services
- Supports safe concurrent ticks and session handling
- Facilitates reconnection, failover, and replay

---

## Redis Availability, Consistency, and Safety Guarantees

Redis is a **non-persistent** layer — but FireMUD treats it as **essential** for consistent multiplayer behavior. Availability and deterministic, idempotent recovery are prioritized rather than strict exactly-once semantics.

### Cluster Deployment

FireMUD runs Redis in a **clustered, replicated configuration**:

- Multiple **shards and replicas** for tick region and session partitioning
- Partitioning aligns with tick region boundaries (typically per-room or per-segment)
- Kubernetes-native failover
- **Failover behavior is tested under live tick loads**
- Tick lock and retry keys are **retained across failover** due to AOF and synchronous Lua-based commit policies, allowing ticks to be safely replayed or completed after leadership handoff without corrupting state.

> For operational context on Docker Compose vs Kubernetes, see [Deployment Environments](./infrastructure/deployment-environments.md).

### Replication and Durability

- Writes are **asynchronously replicated**
- **AOF (Append-Only File)** enabled for durability and crash recovery
- AOF files are wiped on each Helm upgrade to start with a clean state
  (see [Backup & Recovery](./system-architecture-backup-recovery.md#redis-aof-reset-on-deployment))
- Critical Lua writes use `WAIT 1 100` for **replica acknowledgment**
- Development uses [config/redis/redis.conf](../../config/redis/redis.conf) for the single-node instance and can
  persist the AOF via the `redis-data` volume. See
  [Developer Setup](../../DEVELOPER_SETUP.md#optional-redis-persistence) for details and the
  RedisInsight debugging UI.
- Production wipes the AOF before pods start using
  [`redis-aof-reset-job.yaml`](../../k8s/helm/firemud/templates/redis-aof-reset-job.yaml).

`WAIT 1 100` is issued by the Game Session Service immediately after **critical** Lua scripts complete (see below for what counts as critical). Its semantics and limitations are:

- If at least one replica acknowledges the write within the timeout, the write is considered **durably replicated for operational purposes**.
- `WAIT` only confirms that a replica has processed the write; it does **not** guarantee that the AOF has been fsynced on disk.
- `WAIT` does **not** protect against failover to a replica that did not receive the write; correctness in those rare cases relies on idempotent tick and domain logic plus replay from PostgreSQL.

If `WAIT 1 100` returns with fewer than one acknowledgement:

- The write is still treated as **committed** on the primary.
- The service logs a structured warning with shard, tick, and region context.
- A metric (for example `redis.critical_write_replica_acks_missing`) is incremented so operators can detect sustained replication issues.
- The TickScheduler marks the affected `{tenantId, regionId}` as **degraded**:
  - Tick frequency for that region may be reduced.
  - Additional high-risk operations (for example large-scale region fan-outs) may be skipped or delayed until acknowledgements recover.

Taken together, AOF and `WAIT` provide **at-least-once durability** for tick-related writes. Any replay that occurs after crash or failover is absorbed by the idempotent tick and domain logic described in [Crash Recovery and Replay](./system-architecture-ticks.md#crash-recovery-and-replay).

### Critical Write Policy and Centralization

Not every Redis command uses `WAIT`. To keep latency predictable, FireMUD applies `WAIT 1 100` only to **critical writes**, defined as:

- Tick coordination and state:
  - Staging and committing `tick:{tenantId}:{regionId}:pending` entries.
  - Updating tick-related retry metadata and conflict markers that influence replay and scheduling.
- Lock and timer coordination:
  - Changes to `tick:{tenantId}:{regionId}:lock:*` keys that gate entity updates.
  - Timer scheduling and cancellation where loss would materially affect gameplay progression.
- Session binding and rebinding:
  - Updates to `session:{tenantId}:{sessionId}` that control which player socket owns a given gameplay session.

Best-effort caches, rate limits, and other non-authoritative data (for example room view caches, chat history TTLs, and gateway rate-limiter tokens) **do not** use `WAIT` and rely instead on normal asynchronous replication behavior.

To make it hard to forget `WAIT`, the Game Session Service wraps critical operations in a **central helper**:

- Lua scripts that perform critical updates are invoked through a helper that:
  - Executes the script.
  - Immediately issues `WAIT 1 100`.
  - Records logs and metrics when acknowledgements are missing.
- New tick-critical scripts are required to use this helper rather than calling `EVAL`/`EVALSHA` directly.

---

## Key Naming and Shard Discipline

Redis keys follow strict naming conventions to ensure:

- Shard-aware key locality
- Clean atomic execution across tick regions
- Conflict and retry isolation
- Debuggable and traceable behavior
- Tenant-based prefixes for multi-tenant isolation (see [Multi-Tenancy](./system-architecture-multi-tenancy.md))

Tenant and identifier rules:

- `tenantId` and other identifier components used in keys are sanitized and drawn from stable identifiers (for example numeric IDs or UUIDs), not raw user-provided strings.
- Human-readable values such as character names or room titles are never embedded directly into Redis keys; they are stored in PostgreSQL and referenced by IDs in Redis to keep keys short, stable, and free from unexpected characters.

### Key Format Examples

| Redis Key | Description |
| --- | --- |
| `tick:{tenantId}:{regionId}:lock:{entityId}` | Lock for entity during tick execution within a region |
| `tick:{tenantId}:{regionId}:pending` | Staged results for a tick region (single in-flight tick) |
| `tick:{tenantId}:{regionId}:queue:{entityId}` | Per-entity command queue within a region |
| `room:{tenantId}:{roomId}` | Hot room cache as JSON (occupants and metadata) |
| `retry:{tenantId}:{regionId}` | Retry queue for failed actions |
| `timer:{tenantId}:{regionId}` | Sorted set of timers for a region; score is expiration timestamp (ms), members encode entity/effect metadata |
| `remote:{tenantId}:{entityId}` | Queue for cross-region command follow-ups |

> 🔗 `remote:{tenantId}:{entityId}` keys route cross-region commands. See [Cross-Region Command Execution and Result Relay](./system-architecture-ticks.md#📡-cross-region-command-execution-and-result-relay)
> for details.
> 📌 For session-related keys and structure, see [Session Keys and Gameplay Binding](#session-keys-and-gameplay-binding)
> ⚠️ Tick regions and player sessions are **always scoped to a single Redis shard** to preserve atomicity. Cross-shard operations are avoided.

### Hash Tags and Redis Cluster Slotting

FireMUD runs Redis in **Cluster mode**, so all keys used inside a single Lua script must map to the **same hash slot**. Tick-related keys (locks, queues, pending state, retries, timers) therefore share a common **hash tag** derived from `{tenantId, regionId}`:

- `tick:{tenantId}:{regionId}:lock:{entityId}`
- `tick:{tenantId}:{regionId}:pending`
- `tick:{tenantId}:{regionId}:queue:{entityId}`
- `retry:{tenantId}:{regionId}`
- `timer:{tenantId}:{regionId}`

The substring inside the braces (`{tenantId}:{regionId}`) forms the hash tag and is identical for all keys that a script may touch during a tick. This guarantees that:

- Lua scripts can atomically read/write locks, queues, and pending state without `CROSSSLOT` errors.
- Each tick region’s keys remain **shard-local** while still supporting multi-key operations.

Session and timer keys do not participate in tick multi-key scripts and may use simpler patterns as long as they preserve `tenantId` prefixes and avoid cross-shard assumptions.

---

## Atomicity and Concurrency Control

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

### Lua Script Complexity and Runtime Guidelines

Redis executes Lua scripts on the same single-threaded event loop that serves normal commands. To protect shard latency, FireMUD applies the following guidelines to all tick-related scripts:

- **Bounded work per script**
  - Scripts must not iterate over unbounded lists, sets, or streams.
  - Operations should be `O(1)` or `O(log n)` relative to key cardinality wherever possible.
  - Any looping logic must be bounded by explicit, small limits (for example, “process at most N commands/timers per invocation”), with the remainder handled in future ticks.

- **Limited keys and arguments**
  - Scripts should operate on a small, fixed set of keys per invocation (for example, one lock key, one pending key, and a handful of queues/timers for a single region).
  - Bulk fan-out or large multi-key operations should be decomposed into multiple smaller calls instead of a single monolithic script.

- **Runtime expectations**
  - Under normal load, scripts should complete in **under 5–10 ms** on their shard.
  - The Game Session Service monitors Lua runtime metrics (see [Observability and Reliability](#📈-observability-and-reliability)); scripts that consistently exceed these targets are candidates for refactoring or further decomposition.

- **Abort-early behavior**
  - Every script should check simple preconditions first (for example, presence of lock keys, correct tokens, expected `pending` state) and abort quickly if they are not met.
  - Scripts must not fall back to scanning large keyspaces or reconstructing complex state when preconditions are missing; instead, they return a result that signals the caller to retry or perform higher-level recovery.

### Lock TTL and Example Lock Workflow

**Lock TTL formula and guardrails**

Tick locks use a TTL derived from the region’s soft tick budget to balance safety and recovery:

- The Game Session Service computes a default lock TTL as:
  - `lock_ttl_ms = clamp(tick_budget_ms * 3, MIN_LOCK_TTL_MS, MAX_LOCK_TTL_MS)`
  - `MIN_LOCK_TTL_MS` defaults to 500 ms.
  - `MAX_LOCK_TTL_MS` defaults to 5_000 ms.
- Configuration rules:
  - Operators may adjust `tick_budget_ms`, `MIN_LOCK_TTL_MS`, and `MAX_LOCK_TTL_MS` via configuration, but the Game Session Service **always applies the clamp** to avoid accidentally tiny or excessively long lock durations.
  - A lock TTL must never exceed the maximum region-level recovery window defined in the Tick System design; if configuration attempts to raise it further, the Game Session Service caps it at `MAX_LOCK_TTL_MS` and logs a warning.

### Example Lock Workflow

1. Generate a unique lock token (for example, a UUID) and acquire `tick:{tenantId}:{regionId}:lock:{entityId}` using `SET NX PX` with that token as the value and the computed TTL (`lock_ttl_ms`). This ensures transient pauses do not cause the lock to expire while normal work is still in progress, but stale locks are automatically cleared after a bounded window.
2. Stage updates under `tick:{tenantId}:{regionId}:pending` via Lua script while the lock is held.
3. On successful commit, release the lock using a Lua script that:
   - Verifies `GET tick:{tenantId}:{regionId}:lock:{entityId}` still matches the original token.
   - Deletes the lock key only if the token matches.
   - Flushes or clears the staged `tick:{tenantId}:{regionId}:pending` entry for the committed tick.
4. If the lock expires before commit:
   - The next tick cycle detects the presence of `tick:{tenantId}:{regionId}:pending` and replays the staged effects using the idempotency rules described in the Tick System design.
   - Any worker that finds `pending` present but fails to reacquire the lock (because another worker has already taken it with a new token) aborts its work for that tick and returns a retry outcome; it does **not** attempt to apply domain updates without first holding a valid lock token.

### Pending Tick Value Model

Each `tick:{tenantId}:{regionId}:pending` entry stores a **single in-flight tick** for that region. Its value includes:

- A `tickId` that is monotonically increasing per `{tenantId, regionId}` tick stream
- The staged effects for that tick (for example, serialized entity mutations or event descriptors)

Lua scripts treat the presence of `tick:{tenantId}:{regionId}:pending` as meaning **“this tick may need to be (re)applied”**, regardless of whether a previous attempt completed. Domain updates are designed to be idempotent, so reapplying the same `tickId` after a crash or failover does not corrupt state. The `pending` key is created **without a TTL** so it survives primary crashes and failover; it is only removed when the tick has been successfully applied or explicitly abandoned.

On successful completion, the same Lua script that releases locks also deletes `tick:{tenantId}:{regionId}:pending` so there is no follow-up work for that tick on the next cycle.

### Shard Locality and Cross-Region Behavior

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

### Global Effects and Region-Wide Coordination

Tick regions **do not execute unless explicitly triggered**. Idle regions never
see scheduled global events on their own. To apply a world-wide effect — for
example, a server-wide freeze or weather change — the **Game Session Service**
identifies every active region and **fan-outs tick tasks**:

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

## Tick Integration (Resilience, Locking, Staging)

Redis is essential for **coordinating tick execution** across distributed worker services.

It provides:

- Per-entity **command queues**
- Durable **tick staging**
- Distributed **locks** and **retry tracking**
- **Conflict metadata** for retry prioritization (TTL controlled by the `FIREMUD_CONFLICT_TTL_SECONDS` environment variable; see [Game Session Service variables](./microservices/game-session-service/README.md#environment-variables))
- Accurate **cooldown and timer tracking**

> 🔁 Ticks are replayable and deterministic due to Lua-based staging, lock control, and AOF durability.
> 🔗 See [Tick Execution Flow](./system-architecture-ticks.md#🔄-tick-execution-flow)

### Crash and Recovery Safety

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

### Runbook: Stuck Pending Entries and Unbounded Queues

In rare cases where domain code is faulty, a `tick:{tenantId}:{regionId}:pending` entry may remain present even though repeated replays cannot complete successfully. Operators handle this as follows:

- Detect stuck ticks via metrics and alerts:
  - A region whose `pending` entry persists beyond a configurable threshold (for example several tick intervals) is flagged as **stuck**.
  - Dashboards highlight stuck regions and their `tickId` values.
- Use a runbook action in the admin/operations tooling to:
  - Mark the corresponding tick as failed or skipped in PostgreSQL (for example via a `tick_recovery` table or a per-service recovery endpoint).
  - Optionally apply a corrective migration or manual fix to affected aggregates.
  - Explicitly clear `tick:{tenantId}:{regionId}:pending` once the operator is satisfied that the tick will not be retried.

Timers and retry queues are protected against unbounded growth:

- Retry queues (`retry:{tenantId}:{regionId}`) are populated with bounded backoff and retry caps:
  - Each failed action includes metadata such as a retry count and last-failure timestamp.
  - The Game Session Service enforces a maximum retry budget per action; once exceeded, the action is marked as permanently failed and removed from the Redis retry structure, with details recorded in PostgreSQL or an error log for offline inspection.
- Timer keys (`timer:{tenantId}:{regionId}`) are periodically trimmed:
  - Expired timers are removed as ticks progress.
  - Defensive limits (for example a maximum number of timers per region) may trigger alerts or automatic throttling if exceeded so that a bug cannot silently create unbounded timer growth.

---

## Observability and Reliability

FireMUD actively monitors Redis performance and tick health:

- **Prometheus metrics** (via Redis exporters):
  - Lua script latency
  - Lock contention
  - Retry queue depth
  - Keyspace and memory usage
  - Keyspace hits/misses and eviction counts (especially important once read-side caches are enabled)
  - Per-command latency percentiles for tick-related scripts and commands
  - Basic connection health via the `redis.up` gauge exposed in
    `DatabaseAutoConfiguration`
- Metrics are scraped via a [`redis-exporter`](../../k8s/monitoring/redis-exporter.yaml) deployment
  (deployable via the instructions in [`k8s/README.md`](../../k8s/README.md))
- **Grafana dashboards** visualize tick throughput and hotspots
- **Prometheus Alertmanager** sends alerts if metrics exceed thresholds
  - Alerts include thresholds on Redis latency percentiles for tick-related commands (for example, p95/p99 of Lua script runtimes) and on eviction rates so operators can detect when caches or rate limiting begin to impact coordination workloads.
- **Graceful degradation** logic reduces gameplay interruption if Redis temporarily stalls
- Redis is the primary volatile coordination and cache layer. Services do not introduce competing in-memory cache technologies, but deployments may run **separate Redis clusters or logical instances** for coordination vs caching/rate limiting to protect tick latency.
- Local debugging tools such as the Redis CLI and RedisInsight are described in
  [Developer Setup](../../DEVELOPER_SETUP.md#redis-debugging)

> 🔗 Redis observability feeds into the common stack described in [Logging & Monitoring](./system-architecture-logging-monitoring.md)

---

## Session Keys and Gameplay Binding

Redis stores transient gameplay session state for each connected player, including:

- Socket binding metadata
- Active `playerId` and `tenantId` context
- Tick region participation and queued commands
- Timer and cooldown data
- Conflict and retry metadata

Session keys use the prefix `session:{tenantId}:{sessionId}` as described in the
[Game Session Service README](./microservices/game-session-service/README.md#redis-keys).

Session entries expire after `FIREMUD_AUTH_SESSION_EXPIRATION_MS` milliseconds as
configured in [Environment & Secrets](./infrastructure/environment-and-secrets.md#authentication).

This state is used by the **Game Session Service** to:

- Resume gameplay after disconnects
- Rebind gameplay context to a new socket
- Deduplicate reconnect attempts
- Handle character takeovers (one session per character)

All session bind, rebind, and takeover flows are performed via a **Lua compare-and-set script** that:

- Reads the current binding for `session:{tenantId}:{sessionId}` (for example, current socket identifier and a generation counter).
- Verifies that the binding is still compatible with the requested operation (for example, same session attempting a rebind, or an authorized takeover flow).
- Applies the new binding atomically (updating the socket identifier and incrementing a generation/version counter).
- Optionally emits structured metadata for audit and debugging (for example, `previousSocketId`, `newSocketId`, `reason`).

Clients never update session keys directly with plain `SET`; they always go through this Lua-based compare-and-set helper to avoid races where two clients attempt to bind to the same session concurrently. The generation/version counter in the value allows the Game Session Service to reason about which binding is the latest and to detect out-of-order rebind attempts.

> 🔐 Key formats are internal and subject to change. Services treat Redis as a coordination layer, not a persistent or public contract.

## Future Cache Design and Versioned Aggregates

Redis is already used for transient coordination (ticks, sessions, locks), and will eventually back selected read-side caches as a performance optimization. This section outlines the future design principles for deciding what to cache and how to validate those caches; it does not describe an implemented feature set yet.

### Core Principles

- Database and domain services remain authoritative. PostgreSQL and the owning microservices define the source of truth for entities, rooms, inventories, and configuration; Redis is a helper, not a primary store.
- Static/topology vs dynamic/runtime state:
  - Static or topology data (world geometry, room/zone graphs, published templates, configuration) changes infrequently and is a good fit for aggressive caching with long TTLs or manual invalidation.
  - Dynamic runtime state (inventories, room occupants, transient effects, in-progress combat) changes frequently and must use careful invalidation rules and short-lived caches, if cached at all.
- Purpose-driven caching only. Objects should be cached because they are expensive to compute or fetch and appear on hot paths, not “just in case.” Profiling and production telemetry will drive what actually lands in Redis.

### Candidate Cacheable Object Types

This list is planning-only; it documents candidate categories rather than a final decision. Concrete cache choices will be revisited once profiling data and cross-service load metrics are available.

- Static or rarely changing data:
  - World topology slices (room adjacency, precomputed path segments, region metadata that rarely changes).
  - Published templates and configuration (ability definitions, item templates, world-generation parameters) copied from the Game Design Service into runtime schemas.
  - Feature-flag and version metadata that is read frequently but updated only at publish/activation time.
- Dynamic aggregates that are expensive to compute:
  - “Current view” of a room or instance: an aggregate combining world topology, visible entities, and room-ground inventory assembled for LOOK and similar commands.
  - Cross-entity aggregates such as “all entities in a shard/region with a given tag” used for scripted world events or analytics-style queries.
- Dynamic but localized aggregates:
  - Inventories and containers (player inventory, equipment, banks, room-ground containers) where reads are common and writes are scoped to a single aggregate root.
  - Per-entity views (for example, “effective stats” after applying all modifiers) that are expensive to recompute but naturally tied to a single character or NPC.

### Version-Based Cache Validation

Some dynamic aggregates will be easier to cache if the authoritative store exposes a version or `lastModified` field per aggregate root:

- The owning service (for example Entity Management or World Management) maintains a version counter or timestamp on the aggregate root (such as a container, character effective stats, or a room’s dynamic state row) and increments or updates it whenever the aggregate changes.
- Redis entries for that aggregate store both version and payload together, typically inside a single serialized object.
- When fetching, callers can:
  - Read the current version from the authoritative store or a lighter-weight index.
  - Compare it with the version in Redis.
  - Reuse the cached payload if versions match, or recompute and overwrite the cache if they differ.
- Versioning is applied per aggregate root (for example `inventory:{tenantId}:{containerId}` or `roomDynamic:{tenantId}:{roomId}`) rather than being added indiscriminately to every table or DTO.

This pattern keeps cache correctness bounded to clearly defined aggregates and avoids random, hard-to-reason-about version fields scattered across the schema.

### Invalidation Strategies

Future cache layers are expected to combine several invalidation mechanisms, tuned per aggregate:

- TTL-based expiry:
  - Primary role is as a safety valve and memory-bloat control, not the main correctness mechanism.
  - Long TTLs may be acceptable for static/topology slices; short TTLs can bound staleness for dynamic aggregates in low-risk flows.
- Event-based invalidation:
  - When authoritative state changes, the owning service emits domain events (for example: inventory changed, room-dynamic state changed, entity moved, template version activated).
  - A cache layer or a dedicated listener reacts to those events and either deletes affected keys or overwrites them with fresh values.
- Version check (where applicable):
  - For aggregates that expose versions, application code may choose to read version and payload from Redis and compare with the current authoritative version.
  - On mismatch, the cache entry is recomputed and updated atomically (value plus TTL) before being reused.

For correctness-critical dynamic data (movement, inventories, visibility), the design will favor events plus versions as the primary correctness mechanisms and treat TTL as a backstop for forgotten keys or operational anomalies.

### Key Naming and Overwrite Expectations

Cached aggregates in Redis will follow structured, namespaced key patterns to keep responsibilities clear and enable targeted invalidation. Examples (subject to refinement):

- `inventory:{tenantId}:{containerId}` – cached view of a single inventory or container (including room-ground containers).
- `worldDynamic:{tenantId}:{aggregateId}` – cached view of room-level dynamic state or other world-scoped aggregates.
- `view:roomLook:{tenantId}:{roomId}` – cached rendered or pre-assembled room “view” data serving LOOK or similar commands.

Expectations:

- Writing a new value for a key overwrites the previous entry. Cache writers do not attempt to merge old and new payloads inside Redis.
- Writers are encouraged to set TTLs as part of the same write operation (for example using `SET key value EX ttl` or a Lua script) so value and expiry are updated atomically.
- Future implementations should prefer single atomic commands or scripts (set value and TTL together, optionally with version) over multi-step delete plus set sequences unless there is a very specific, documented reason (such as maintaining backward-compatible behavior during a migration).

### Workload Segmentation: Coordination vs Caching

Redis workloads in FireMUD fall into three broad categories:

- **Coordination:** tick locks, staging (`tick:{tenantId}:{regionId}:pending`), command queues, timers, retry metadata, and session state. These keys are latency-sensitive, relatively small, and must not be evicted.
- **Rate limiting:** Spring Cloud Gateway’s `RequestRateLimiter` tokens and similar per-client throttles. These writes are bursty and less latency-sensitive than ticks.
- **Read-side caching:** optional room views, inventory aggregates, and other precomputed results that can be recomputed on a cache miss.

Recommended deployment patterns:

- For **development and small deployments**, a single Redis cluster can serve all three workloads as long as cache value sizes and TTLs remain conservative and monitoring stays in place for memory pressure and latency.
- For **production and large worlds**, operators are encouraged to **separate Redis responsibilities**:
  - A **Coordination Redis** cluster dedicated to ticks, locks, timers, and sessions with strict latency SLOs, reserved memory headroom, and no large aggregates.
  - A **Cache/Rate-Limit Redis** cluster for gateway rate limiting and read-side caches, where eviction and higher latency variance are acceptable.

This separation keeps gameplay-critical tick coordination isolated from noisy cache or rate-limiter traffic while still standardizing on Redis as the shared volatile state technology.

Rate limiting keys (for example those used by Spring Cloud Gateway’s `RequestRateLimiter`) should be designed to avoid **hot keys** under heavy load:

- Per-client or per-token prefixes are preferred over global counters so that no single key receives a disproportionate share of traffic.
- When high-cardinality shared credentials are unavoidable, deployments may use simple hashing or bucketing in key naming to spread load across multiple keys within the rate-limit Redis cluster.

### Future Work / TODO

This section captures design intent only; concrete decisions are explicitly deferred. Before caching is implemented broadly, we need to:

- Decide which aggregates (if any) are actually cached for each service (Entity Management, World Management, Game Session, Game Logic, and others).
- Decide which aggregates receive a dedicated version or `lastModified` field for cache validation and how those fields are surfaced in their APIs.
- Define the domain events required to drive event-based invalidation (including payload shape, routing, and delivery guarantees).
- Add concrete examples, diagrams, and per-service subsections that show exactly how the chosen aggregates use Redis (key shapes, TTLs, version semantics, and listeners) once profiling and production telemetry justify their introduction.

---

## Related Documentation

- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Transaction Strategies](./system-architecture-transactions.md)

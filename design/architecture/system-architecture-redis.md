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

### Network Security and Access Control

Redis is treated as an **internal infrastructure component**, not a public-facing service:

- Cluster nodes are deployed on **private networks** and exposed only inside the Kubernetes cluster or VPC. There is no direct Internet access to Redis.
- Application access is restricted to trusted FireMUD services. No front-end client, browser, or untrusted component communicates with Redis directly; all access flows through authenticated, authorized backend services.
- Production deployments enable:
  - Authentication using Redis passwords or ACL users, stored as Kubernetes Secrets and injected via environment variables or configuration files.
  - TLS encryption between application pods and Redis, following the same certificate and key rotation practices described in [System Architecture: Security](./system-architecture-security.md).

Operational runbooks and Helm charts document the exact `requirepass`/ACL/TLS configuration per environment. Development profiles may relax some of these settings (for example, plaintext on localhost) but must never expose Redis outside the developer’s machine or bypass authentication on shared environments.

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
- Critical Lua writes use `WAIT 1 100` for **replica acknowledgment**
- Development uses [config/redis/redis.conf](../../config/redis/redis.conf) for the single-node instance and can
  persist the AOF via the `redis-data` volume. See
  [Developer Setup](../../DEVELOPER_SETUP.md#optional-redis-persistence) for details and the
  RedisInsight debugging UI.
- In **development and ephemeral test environments**, Helm may reset the AOF on install/upgrade via
  [`redis-aof-reset-job.yaml`](../../k8s/helm/firemud/templates/redis-aof-reset-job.yaml) to guarantee a clean
  slate between runs (see [Backup & Recovery](./system-architecture-backup-recovery.md#redis-aof-reset-on-deployment)).
- In **production**, Redis AOF files and volumes are **preserved across application deployments**. Resetting Redis
  (and thereby terminating active sessions and discarding all volatile tick state) is treated as an explicit
  operational action, not part of normal CI/CD rollout.

The `redis-aof-reset-job` is **strictly scoped**:

- It is only enabled in **ephemeral** dev/test namespaces where losing volatile coordination state is acceptable.
- Production and long-lived staging environments:
  - Do not include the reset Job in their Helm values.
  - Treat any AOF reset or Redis flush as a manual, audited operation with clear runbooks and impact analysis.
- Helm values and CI/CD pipelines must not reuse dev/test Redis values for production namespaces; production values files are separate so misconfiguration cannot silently enable AOF resets outside ephemeral environments.

Redis provides **best-effort durability** for volatile coordination state, not absolute guarantees:

- `WAIT 1 100` only ensures that **at least one replica** has processed the write within the timeout; if both the primary and that replica are lost before their AOFs are flushed to disk, the most recent writes can still be lost.
- AOF rewrite policy (for example Redis’s `auto-aof-rewrite-percentage` and `auto-aof-rewrite-min-size` settings) affects how much recent state can be lost if a node crashes between an in-memory update and the next fsync or AOF rewrite. FireMUD’s coordination Redis configuration:
  - Favors `appendfsync everysec` (or equivalent) so operators can assume a worst-case loss window on the order of one second of volatile tick/session state under crash conditions.
  - Keeps `auto-aof-rewrite-percentage` conservative to avoid overly frequent rewrites while still preventing unbounded file growth.
- Operators should assume that, under rare compound failures (for example primary + replica loss or crash during rewrite), a **small tail window** of volatile Redis state may disappear. Correctness in those cases is preserved by:
  - Treating Redis as non-authoritative and replaying ticks based on PostgreSQL and any surviving `tick:{tenantId}:{regionId}:pending` keys.
  - Relying on the idempotent tick and domain logic described in [Crash Recovery and Replay](./system-architecture-ticks.md#crash-recovery-and-replay) so replays never apply new logical effects twice.

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

These conventions provide **logical tenant isolation** at the keyspace level. Even when multiple tenants share the same Redis cluster:

- Every coordination and cache key is namespaced by `tenantId`, and many by both `tenantId` and `regionId`.
- Operational tooling, dashboards, and runbooks use these prefixes when inspecting or modifying Redis state so actions are scoped to the intended tenant and region.
- Services never expose raw Redis keys or provide “arbitrary command” capabilities to end users or external systems; all access goes through well-defined APIs that interpret and manipulate keys on behalf of tenants.

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
| `automation:tick:{tenantId}:{scriptId}:lock` / `queue` / `pending` | Per-script automation tick locks and staging (Automation & Scripting Service) |

> 🔗 `remote:{tenantId}:{entityId}` keys route cross-region commands. See [Cross-Region Command Execution and Result Relay](./system-architecture-ticks.md#📡-cross-region-command-execution-and-result-relay)
> for details.
> 📌 For session-related keys and structure, see [Session Keys and Gameplay Binding](#session-keys-and-gameplay-binding)
> ⚠️ Tick regions and player sessions are **always scoped to a single Redis shard** to preserve atomicity. Cross-shard operations are avoided.

### Region Leadership and Tick Executor Lease

Each `{tenantId, regionId}` is owned by a **single authoritative tick executor** at any point in time. Leadership is coordinated via a lightweight lease key:

- `tick-executor-lease:{tenantId}:{regionId}` – stores the current executor identity (for example a `nodeId` or `instanceId`) and an expiry.

The lease is acquired and renewed using `SET NX PX lease_ttl_ms`:

- Only the holder of a valid lease may:
  - Consume commands from region queues.
  - Drive `tick:{tenantId}:{regionId}:pending` and commit/rollback flow.
  - Update region timers (`timer:{tenantId}:{regionId}`) and retry queues (`retry:{tenantId}:{regionId}`).
- The active executor periodically refreshes the lease as long as it remains healthy.
- If the lease expires or is deliberately allowed to lapse:
  - Other Game Session instances may compete to acquire the lease.
  - The new leader resumes tick processing for `{tenantId, regionId}` using the existing Redis state (`pending`, queues, timers, retry metadata) and the idempotency rules described in the Tick System design.

This lease acts as the **macro-level lock** for a region: it prevents multiple executors from driving ticks concurrently for the same `{tenantId, regionId}` while still allowing fast failover and rebalancing. Fine-grained entity locks (`tick:{tenantId}:{regionId}:lock:{entityId}`) are used *within* a leader to coordinate per-command work and crash recovery; they do not replace the leadership lease.

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

These key-shape and hash-tag rules are **enforced**, not just conventions:

- All tick-related keys are constructed via shared **Key Naming** helpers in the common library; direct string concatenation of `tick:`, `retry:`, or `timer:` keys in application code is not allowed.
- Unit tests in the Game Session Service (or a shared Redis test suite):
  - Construct keys using the same helpers used in production.
  - Assert that all keys passed to a given Lua script share the same hash tag substring (the content inside `{}`).
  - In dev/test profiles, verify hash-slot alignment via `CLUSTER KEYSLOT` checks so regressions are caught early.
- A lightweight lint/test step validates Lua script definitions: any script that accepts multiple keys must either:
  - Use keys built from the canonical helpers (for example, the first key is always a `tick:{tenantId}:{regionId}:...` key), or
  - Explicitly document why it is shard-local without a hash tag (for example, a script that operates on a single `session:{tenantId}:{sessionId}` key).

Pull requests that introduce new tick-related keys or Lua scripts must add or update the corresponding helpers and tests; scripts that hard-code non-hash-tagged tick keys or produce `CROSSSLOT` errors under tests are rejected.

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

Tick-related multi-key operations **must not** be implemented as ad-hoc sequences of plain Redis commands outside these scripts. All staging, commit, rollback, and lock manipulation that touches multiple tick keys (locks, `pending`, queues, timers, or retry metadata) is performed exclusively via the Lua scripts in `services/game-session-service/src/main/resources/redis/` so transactional behavior remains consistent and replay-safe.

> 🔗 For use during tick execution, see [Distributed Locking](./system-architecture-ticks.md#🔐-distributed-locking)

### Script Loading, `EVALSHA`, and Failover Behavior

Lua scripts are loaded and invoked using a **SHA-first** approach:

- On service startup, the Game Session Service:
  - Enumerates all master nodes in the Redis Cluster via the client library’s cluster metadata and loads each Lua script on **every master** using `SCRIPT LOAD`.
  - Caches the resulting SHA fingerprints in memory, keyed by a logical script identifier; a script has one logical identifier but may be loaded on multiple masters.
- At runtime, scripts are invoked via `EVALSHA` with the appropriate SHA and `KEYS`/`ARGV` lists instead of raw `EVAL` calls, avoiding repeated parsing overhead and keeping multi-key operations aligned with the hash-tag rules described in [Key Naming and Shard Discipline](#key-naming-and-shard-discipline).

Redis Cluster does **not** guarantee that loaded scripts survive `SCRIPT FLUSH`, upgrades, or some failover events. The client logic therefore treats `NOSCRIPT` as a normal runtime condition:

- If `EVALSHA` returns a `NOSCRIPT` error on a given node:
  - The Game Session Service reloads the script on that node using `SCRIPT LOAD` against the master responsible for the key slot of the first `KEYS[1]` argument.
  - It updates the cached SHA for that script if Redis reports a new fingerprint.
  - It immediately retries the call using `EVALSHA` with the refreshed SHA value.
- All tick-related script helpers:
  - Encapsulate this `NOSCRIPT` handling so callers do not need to implement retries themselves.
  - Ensure that the **first key argument** for each script is a tick key whose hash tag (`{tenantId}:{regionId}`) matches all other keys in the call, so Redis Cluster routes the script to the correct slot.

If a script repeatedly fails to reload or `NOSCRIPT` errors persist beyond a short, configurable retry window, the Game Session Service:

- Logs a structured error with script name, region, and tenant context.
- Emits a metric such as `redis.lua.script_load_failures` for alerting.
- Treats the affected tick region as **degraded** until the underlying Redis issue is resolved.

This loading and retry behavior ensures that transient `SCRIPT FLUSH` events or node failovers do not permanently break tick processing while still surfacing persistent misconfiguration or Redis instability to operators.

### Lua Script Complexity and Runtime Guidelines

Redis executes Lua scripts on the same single-threaded event loop that serves normal commands. To protect shard latency, FireMUD applies the following guidelines to all tick-related scripts:

- **Bounded work per script**
  - Scripts must not iterate over unbounded lists, sets, or streams.
  - Operations should be `O(1)` or `O(log n)` relative to key cardinality wherever possible.
  - Any looping logic must be bounded by explicit, small limits (for example, “process at most N commands/timers per invocation”), with the remainder handled in future ticks.

- **Limited keys and arguments**
  - Scripts should operate on a small, fixed set of keys per invocation (for example, one lock key, one pending key, and a handful of queues/timers for a single region).
  - Bulk fan-out or large multi-key operations should be decomposed into multiple smaller calls instead of a single monolithic script.
   - Implementations must define and enforce a small constant upper bound on the number of keys any tick-related script may touch (for example, `MAX_TICK_SCRIPT_KEYS` on the order of a handful of keys per region). This bound is captured in configuration and validated via unit tests so “just one more key” changes are deliberate and reviewed; scripts that exceed the configured bound are rejected during testing.

- **Runtime expectations**
  - Under normal load, scripts should complete in **under 5–10 ms** on their shard; SLOs treat runtimes above this as outliers.
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

**Runtime validation and observability**

The safety of this TTL formula depends on how long **end-to-end tick work** takes under load, including:

- Redis script runtime.
- Downstream PostgreSQL transactions.
- Cross-service gRPC calls made during a tick.
- Occasional JVM pauses (GC, safepoints) on the Game Session Service.

To make sure locks do not routinely expire while legitimate work is still in flight, FireMUD enforces the following validation and monitoring practices:

- Load and stress tests measure the distribution of tick execution time (from lock acquisition through final commit) and confirm that:
  - The **p99 tick duration** for a region remains comfortably below `lock_ttl_ms` (for example, less than 50–70% of the configured TTL).
  - Regions that violate this during testing are treated as misconfigured; either the tick budget is raised, or work is decomposed so individual ticks become lighter.
- At runtime, the Game Session Service:
  - Records per-tick metrics such as `tick.execution_time_ms` and `tick.lock_ttl_ms` labeled by `{tenantId, regionId}`.
  - Emits a derived signal like `tick.lock_ttl_headroom_ratio` or a counter `tick.near_lock_ttl` whenever a tick’s execution time exceeds a configurable fraction of `lock_ttl_ms` (for example, >80%).
  - Surfaces regions with frequent near-TTL ticks on dashboards so operators can spot hot or overloaded regions early.
- Regions that **routinely exhaust or exceed** their lock TTLs are treated as explicitly degraded:
  - A tick is considered **over-TTL** if `tick.execution_time_ms >= lock_ttl_ms`.
  - A region enters a **degraded** state if, over a rolling window (for example 5 minutes), either:
    - More than a configurable percentage of its ticks are over-TTL (for example >5%), or
    - It produces a configurable number of consecutive over-TTL ticks (for example 3 in a row).
  - When a region is marked degraded, the TickScheduler:
    - Reduces that region’s effective tick fan-out (for example, lowers the maximum commands processed per tick) and may modestly increase the tick interval to create headroom.
    - Continues to complete in-flight ticks but avoids starting new ticks more aggressively than the configured pacing, rather than silently allowing long-running work to compete with healthy regions.
    - Emits explicit “region degraded” metrics and alerts so operators can investigate scripts, domain calls, and configuration.
  - If a region remains degraded beyond a hard, configurable window, the scheduler may:
    - Halt new ticks for that region and treat it as temporarily unavailable.
    - Disconnect or deny new commands for affected sessions with a clear error indicating that the region is overloaded.
    - Require operator intervention or configuration changes before the region is allowed to resume normal tick rates.

### Graceful Degradation & Redis Outage Policy

Redis is **required** for tick coordination, sessions, and automation. When Redis becomes slow or unavailable, FireMUD prefers **explicit, bounded failure** over silently accepting work that cannot be coordinated or recovered deterministically.

- **What we never do**
  - The Game Session Service and other coordination clients **do not buffer authoritative commands purely in process memory** when Redis is unavailable. Commands that cannot be enqueued or coordinated in Redis are rejected rather than accepted and “replayed later,” because doing so would break idempotent replay guarantees and make partial failures hard to reason about.
  - Region executors do not attempt to continue ticks against stale or partially visible Redis state after connection failures or timeouts.

- **Short-lived latency spikes / partial degradation**
  - When coordination round-trips to Redis slow down but still succeed within bounded time, regions may temporarily enter the **degraded** state described above:
    - Tick fan-out is reduced and tick intervals may be modestly increased.
    - Commands continue to flow, but per-command latency rises and retry rates may increase.
  - If coordination latency recovers before the “degraded” window elapses, regions return to normal without disconnecting players.

- **Sustained high latency or partial outages**
  - When Redis coordination operations for a `{tenantId, regionId}` repeatedly:
    - exceed configured latency SLOs,
    - fail with timeouts or connection errors, or
    - hit critical error conditions such as `OOM` or missing replica acknowledgements for critical writes,
    the TickScheduler escalates beyond the basic degraded behavior:
    - New ticks for that region are **halted**.
    - New gameplay commands for that region are **rejected** with a clear error (for example, “region temporarily unavailable”) rather than accepted and lost.
    - Existing sessions in the region may be disconnected or moved to a safe error state by the Game Session Service.
  - Other regions and tenants that are not hitting Redis issues continue to operate normally; degradation is scoped as narrowly as the failure allows (per-region where possible, per-cluster only when unavoidable).

- **Full coordination-cluster outage**
  - If the Coordination Redis cluster is unreachable for **all** tick regions, the platform treats **gameplay as unavailable**:
    - Tick scheduling stops globally.
    - New gameplay sessions and commands that depend on ticks or sessions are rejected until Redis connectivity recovers.
  - Non-gameplay services that do not depend on Redis (for example, Account, Game Design, Logging & Admin) may remain available; this is the primary form of “graceful degradation” at the product level.

This policy applies consistently across the conditions described elsewhere in this document: replica-ack failures after `WAIT`, `OOM`/evictions of coordination keys, repeated over-TTL ticks, and client-level connection timeouts all drive regions through the same **healthy → degraded → halted** progression, rather than each caller inventing its own ad-hoc behavior.

### Operational SLOs & Alert Thresholds

To keep behavior consistent across environments, FireMUD defines **recommended SLOs and red-line thresholds** for Redis and tick coordination. Exact values are configurable per deployment, but implementations and dashboards should start from these defaults:

- **Lua script runtime (coordination scripts only)**
  - Target: `p95` runtime for tick/lock/timer scripts under **5 ms**, `p99` under **10 ms** on the Coordination Redis cluster.
  - Warning alert: `p99` runtime > **10 ms** for **5 minutes** on any shard.
  - Critical alert: `p99` runtime > **25 ms** for **1 minute** or more, or sustained `p95` > **15 ms**. Affected regions should be marked degraded and investigated immediately.

- **Redis round-trip latency (coordination commands)**
  - Target: median latency under **1 ms**, `p99` under **5 ms** for commands that drive ticks, locks, timers, and sessions.
  - Warning alert: `p99` coordination latency > **5 ms** for **5 minutes** on any shard.
  - Critical alert: `p99` > **15 ms** or connection timeouts on more than a small percentage of coordination operations (for example, >1% over 5 minutes). Regions using the affected shard are candidates for automatic degradation or temporary tick halts.

- **Tick over-TTL behavior**
  - As described above, a region is considered degraded if, over a 5-minute window, either:
    - >5% of its ticks are **over-TTL**, or
    - it produces **3 consecutive** over-TTL ticks.
  - Warning alert: region enters degraded state.
  - Critical alert: region remains degraded for more than a configurable window (for example **10–15 minutes**); at this point the Game Session Service may halt new ticks and reject new commands for that region until operators intervene.

- **Coordination Redis memory and eviction**
  - On the Coordination Redis deployment (which uses `maxmemory-policy noeviction`):
    - Warning alert: `used_memory` exceeds **70%** of `maxmemory` for more than **10 minutes**.
    - Critical alert: any `OOM`/`OUT OF MEMORY` error on coordination commands, or any eviction event for keys with `tick:`, `session:`, `timer:`, or `retry:` prefixes. These are treated as immediate incidents; affected regions should be marked degraded or halted until memory pressure is resolved.

- **Cache/Rate-Limit Redis eviction**
  - For the Cache/Rate-Limit Redis deployment where eviction is expected:
    - Warning alert: sustained eviction rate above a configured baseline (for example, >**1,000 evictions per minute** for >10 minutes) or a step-change relative to recent history.
    - Critical alert: eviction rate that grows without bound or consistently exceeds a fraction of the keyspace per hour (for example, evicting more than **5%** of average key count per hour), indicating mis-sized caches or runaway writers.

These thresholds are intended to be **explicit starting points**, not immutable rules. Operators may tighten or relax them per environment, but architecture and implementation should treat the classes of behavior above—high script runtimes, elevated coordination latency, over-TTL ticks, coordination `OOM`/evictions, and runaway cache eviction—as the canonical “red lines” that justify automated degradation and paging.

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

Domain services treat PostgreSQL as the **source of truth** for business state during recovery:

- If a replay of `tick:{tenantId}:{regionId}:pending` encounters idempotency state in PostgreSQL that indicates the effects have already been applied (for example, `last_tick_id >= tickId` or an existing `tick_effect_guard` row), handlers must treat the call as a replay:
  - They return success without applying new logical effects.
  - They may optionally verify that current state is consistent with the previously applied effect and emit a warning if invariants appear broken.
- If a handler detects an **impossible combination** (for example, missing or contradictory tick-state rows that make it unsafe to decide whether an effect has already been applied), it must:
  - Avoid “best effort” reapplication; it should not attempt to re-run the effect blindly.
  - Return a clear error or status that causes the Game Session Service to treat the tick as failed for that region.
  - Log structured details sufficient to reconstruct and repair the affected aggregates offline.
- The Game Session Service then:
  - Treats the tick as **stuck** or **failed** (see the runbook below).
  - Surfaces this via metrics and alerts so operators can apply the manual or automated recovery flows (for example, marking the tick as `FAILED` in a `tick_recovery` table and clearing the pending key).

### Runbook: Stuck Pending Entries and Unbounded Queues

In rare cases where domain code is faulty, a `tick:{tenantId}:{regionId}:pending` entry may remain present even though repeated replays cannot complete successfully. Operators handle this as follows:

- Detect stuck ticks via metrics and alerts:
  - A region whose `pending` entry persists beyond a configurable threshold (for example several tick intervals) is flagged as **stuck**.
  - Dashboards highlight stuck regions and their `tickId` values.
- Use a runbook action in the admin/operations tooling to:
  - Mark the corresponding tick as failed or skipped in PostgreSQL (for example via a `tick_recovery` table or a per-service recovery endpoint).
  - Optionally apply a corrective migration or manual fix to affected aggregates.
  - Explicitly clear `tick:{tenantId}:{regionId}:pending` once the operator is satisfied that the tick will not be retried.

Over time, this manual runbook will be backed by a small **tick recovery automation**:

- The Logging & Admin Service (or a dedicated operations component) exposes a gRPC/HTTP admin endpoint that:
  - Accepts an explicit `{tenantId, regionId, tickId}` and an operator principal.
  - Marks the tick as `SKIPPED` or `FAILED` in a `tick_recovery` table owned by the relevant domain service(s).
  - Invokes a Game Session Service helper to clear `tick:{tenantId}:{regionId}:pending` and any associated retry metadata in Redis.
- A background watcher may propose candidate stuck ticks automatically when:
  - A `pending` key has existed for longer than a configured threshold (for example, multiple tick intervals).
  - Retry limits have been exhausted without successful completion.
- Final skip/clear actions always require positive operator approval via the admin UI or CLI; no tick is auto-skipped without a human acknowledgement.

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
  - Lua script latency and execution time distributions
  - Lock contention
  - Retry queue depth
  - Keyspace and memory usage
  - Keyspace hits/misses and eviction counts (especially important once read-side caches are enabled)
  - Per-command latency percentiles for tick-related scripts and commands, labeled by **key prefix** (for example, `tick:`, `session:`, `inventory:`, `rate_limit:`) so coordination workloads can be separated from caches and rate limiting.
  - Blocked client counts and blocked time on Redis nodes to highlight when long-running Lua scripts or slow operations impact other traffic.
  - `WAIT 1 100` acknowledgement results and failure counts (for example, `redis.critical_write_replica_acks_missing`) to detect replication issues affecting critical tick writes.
  - Basic connection health via the `redis.up` gauge exposed in
    `DatabaseAutoConfiguration`
- Metrics are scraped via a [`redis-exporter`](../../k8s/monitoring/redis-exporter.yaml) deployment
  (deployable via the instructions in [`k8s/README.md`](../../k8s/README.md))
- **Grafana dashboards**:
  - Visualize tick throughput, Lua runtimes, lock contention, and stuck `pending` entries.
  - Provide **separate panels** for coordination key prefixes (`tick:*`, `session:*`, timers, locks) versus cache and rate-limit prefixes so cache latency or misses cannot mask problems with tick coordination.
  - Highlight trends in blocked clients, `WAIT` failures, and eviction events to show when cache or rate-limit activity begins to interfere with coordination workloads.
- **Prometheus Alertmanager** sends alerts if metrics exceed thresholds
  - Alerts include thresholds on Redis latency percentiles for tick-related commands (for example, p95/p99 of Lua script runtimes) and on eviction rates so operators can detect when caches or rate limiting begin to impact coordination workloads.
- **Graceful degradation** logic reduces gameplay interruption if Redis temporarily stalls
- Redis is the primary volatile coordination and cache layer. Services do not introduce competing in-memory cache technologies, but deployments may run **separate Redis clusters or logical instances** for coordination vs caching/rate limiting to protect tick latency.
- Local debugging tools such as the Redis CLI and RedisInsight are described in
  [Developer Setup](../../DEVELOPER_SETUP.md#redis-debugging)

> 🔗 Redis observability feeds into the common stack described in [Logging & Monitoring](./system-architecture-logging-monitoring.md)

---

### Graceful Degradation Modes and Alert Thresholds

Redis outages or sustained high latency on the **coordination cluster** are treated as explicit failure modes with well-defined behavior:

- **Healthy:** Redis latency and error rates are within normal bounds.
  - Game Session and other services process commands and ticks normally.
  - Observability dashboards show p95/p99 Lua runtimes comfortably below the 5–10 ms SLO, low blocked-client counts, and zero coordination-key evictions.

- **Degraded:** Latency or errors exceed soft thresholds but Redis is still reachable.
  - Example triggers (exact values tuned per environment):
    - p99 Lua runtime for tick-related scripts exceeds ~10–20 ms for several minutes.
    - Blocked-client counts or blocked time on coordination nodes spike above a small configured threshold.
    - Repeated `WAIT 1 100` failures or coordination OOM errors are observed without total outage.
  - Behavior:
    - Game Session slows down affected regions by reducing per-tick fan-out and/or slightly increasing tick intervals (as described in the Lock TTL section).
    - New non-essential commands (for example expensive or cosmetic actions) may be rejected with clear “region under load” errors instead of being enqueued.
    - Read-only queries that do not depend on coordination Redis (for example DB-backed status views) may remain available where practical, but tick progression and command intake are prioritized over optional features.

- **Unavailable / Fail-closed:** Coordination Redis is unreachable or returning pervasive errors.
  - Example triggers:
    - `redis.up` gauge reports down for the coordination cluster.
    - High error rates for basic commands (for example `GET`/`SET` on tick/session keys) over a short window.
  - Behavior:
    - Game Session **hard-fails new gameplay commands** that require ticks, returning a clear “service unavailable” style error.
    - It **freezes tick scheduling** for affected regions rather than attempting to buffer commands in memory or continue without coordination guarantees.
    - It does not attempt to run ad-hoc in-memory fallbacks for ticks, locks, or sessions; correctness takes precedence over partial gameplay.
    - Operators are alerted via high-severity alerts so they can restore Redis; gameplay resumes only once coordination Redis is healthy again.

For the **cache/rate-limit cluster**, alert thresholds focus on:

- Eviction rates and keyspace memory usage (for example, sustained high eviction rate or `used_memory` approaching `maxmemory`).
- Latency percentiles for cache/rate-limit commands that threaten to interfere with coordination workloads if clusters share resources.

Caches and rate limiting are allowed to degrade more aggressively (for example, higher p99 latencies or eviction spikes) as long as they do not impact the Coordination Redis SLOs. Emergency actions for the cache/rate-limit cluster may include temporarily reducing cache TTLs, disabling specific caches, or relaxing rate limits, but **coordination behavior is never emulated in memory** when Redis is unhealthy.

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
configured in [Environment & Secrets](./infrastructure/environment-and-secrets.md#authentication). The Game Session
Service sets the Redis TTL for each `session:{tenantId}:{sessionId}` key to this value when the session is created or
refreshed. Once the TTL elapses:

- The session key is removed from Redis.
- Reconnect / resume flows for that `sessionId` are rejected as **expired**, and the player must perform a fresh `LOGIN`.
- Any associated volatile coordination state for that session (queued commands, conflict metadata) is treated as
  abandoned and will not be replayed.

In practice, `FIREMUD_AUTH_SESSION_EXPIRATION_MS` therefore defines the **maximum reconnection window** for gameplay
sessions. Deployments should keep this value aligned with or slightly longer than `FIREMUD_AUTH_JWT_EXPIRATION_MS` so
that JWT and server-side session lifetimes remain coherent. Configuration validation enforces this relationship in
non-dev profiles:

- `FIREMUD_AUTH_SESSION_EXPIRATION_MS` must be **greater than or equal to** `FIREMUD_AUTH_JWT_EXPIRATION_MS` (optionally plus a small safety margin). This ensures that, under normal conditions, a JWT never outlives its corresponding Redis session entry.
- If configuration attempts to set a shorter Redis session TTL than the JWT lifetime in production or staging, the authentication and Game Session services treat it as a misconfiguration: startup fails or falls back to a safe, derived TTL (while logging a clear error) rather than silently allowing “JWT still valid but session state already expired” behavior.
- Operators who intentionally want a shorter reconnection window than the JWT lifetime should make that choice explicit via environment profiles and documentation; in that case, reconnect flows will reject resumptions once the Redis TTL has elapsed even if the JWT remains technically valid.

This state is used by the **Game Session Service** to:

- Resume gameplay after disconnects
- Rebind gameplay context to a new socket
- Deduplicate reconnect attempts
- Handle character takeovers (one session per character)

The **authoritative lifecycle** of a game instance remains in PostgreSQL (for example the `game_instances` table and related state), not in Redis. If Redis drops a `session:{tenantId}:{sessionId}` key while the underlying game instance is still `RUNNING`:

- Background operations and gameplay ticks continue based on tenant/game-instance identifiers; they do not depend on the presence of a specific `session:*` key.
- A reconnect attempt with a valid JWT but a missing session key is treated as “no active binding” rather than “game instance missing”:
  - The Game Session Service verifies the JWT first.
  - It then either binds a **new** `session:{tenantId}:{sessionId}` record to the existing game instance (subject to ownership rules) or rejects the reconnect if the reconnection window has intentionally elapsed.
  - The absence of the old `session:*` key never implies that the underlying `game_instances` row has stopped; it only affects how quickly a user can resume their previous socket binding.
- Metrics such as “reconnect attempts missing session key but targeting a running game instance” surface misconfiguration or Redis instability so operators can adjust TTLs or investigate state loss.

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
- Coordination workload isolation. By default, read-side caches and gateway rate limits are placed on a **separate Redis cache/rate-limit cluster** rather than sharing the **coordination Redis** used for ticks, locks, timers, and sessions. Only small, tightly bounded aggregates may be considered for the coordination cluster, and even then only with explicit justification and review.

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
  - Implementations must enforce **per-key TTL budgets** in configuration so caches cannot silently accumulate effectively permanent entries; long-lived keys should be rare, documented exceptions.
- Event-based invalidation:
  - When authoritative state changes, the owning service emits domain events (for example: inventory changed, room-dynamic state changed, entity moved, template version activated).
  - A cache layer or a dedicated listener reacts to those events and either deletes affected keys or overwrites them with fresh values.
- Version check (where applicable):
  - For aggregates that expose versions, application code may choose to read version and payload from Redis and compare with the current authoritative version.
  - On mismatch, the cache entry is recomputed and updated atomically (value plus TTL) before being reused.

For correctness-critical dynamic data (movement, inventories, visibility), the design will favor events plus versions as the primary correctness mechanisms and treat TTL as a backstop for forgotten keys or operational anomalies.

To avoid noisy-neighbor effects on the coordination workload, cache writers must also enforce **per-value size limits** (for example via serialization-size checks) and avoid unbounded lists or blobs in Redis. Large or streaming-style responses should remain in PostgreSQL or object storage and be accessed through dedicated APIs rather than copied wholesale into Redis.

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
  - Coordination keys (`tick:*`, `session:*`, locks, timers, retries) must remain a **small, bounded fraction** of `maxmemory` (for example, <30%), and cache writers must enforce strict per-value size and TTL limits so caches cannot grow unbounded.
  - Operators should treat eviction of coordination keys (for example `tick:` or `session:` prefixes) as a **hard incident** even in small deployments; alerting on eviction counters by prefix is required.
- For **production and large worlds**, operators are encouraged to **separate Redis responsibilities**:
  - A **Coordination Redis** cluster dedicated to ticks, locks, timers, and sessions with strict latency SLOs, reserved memory headroom, and no large aggregates.
  - A **Cache/Rate-Limit Redis** cluster for gateway rate limiting and read-side caches, where eviction and higher latency variance are acceptable.

This separation keeps gameplay-critical tick coordination isolated from noisy cache or rate-limiter traffic while still standardizing on Redis as the shared volatile state technology.

**Eviction policy and memory configuration**

To honor the “coordination keys must not be evicted” rule:

- The **Coordination Redis** deployment:
  - Uses a `maxmemory-policy` of `noeviction` so that coordination keys are never removed to make room for caches.
  - Is sized with sufficient `maxmemory` (and headroom) to accommodate expected tick, lock, timer, and session state plus operational buffers.
  - Does **not** store large cache payloads or unbounded aggregates; those belong in the Cache/Rate-Limit cluster or in PostgreSQL/object storage.
  - Treats any `OOM`/`OUT OF MEMORY` write error for coordination commands as a **critical failure condition**: the Game Session Service and other coordination clients:
    - Detect write failures from Redis clients (including Lua script results) instead of ignoring them.
    - Log structured errors and increment metrics (for example `redis.coordination_oom_errors`).
    - Mark affected regions as degraded or temporarily halt new ticks/lock acquisitions until operators resolve the underlying memory issue.
- The **Cache/Rate-Limit Redis** deployment:
  - May use an eviction policy such as `allkeys-lru` or `volatile-lru`, since entries are recomputable or best-effort.
  - Enforces strict limits on value size and TTL so cache growth does not starve rate limiting or degrade performance.

For **small or development deployments** that share all workloads on a single Redis cluster:

- The configuration should still avoid mixing large, eviction-driven caches with critical coordination keys under `allkeys-*` policies. This is considered a **hard no** because it can silently evict locks, timers, or staging keys.
- If a single-node instance must serve both coordination and cache traffic, prefer:
  - `maxmemory-policy noeviction`, conservative cache TTLs, and tight cache size limits, or
  - Separate logical Redis instances (for example, two containers or pods) so coordination and cache eviction policies can diverge even on the same host.

Operational dashboards track `used_memory`, `maxmemory`, and eviction counters for each deployment. Alert thresholds are tuned so approaching memory pressure or unexpected eviction activity is visible well before it threatens coordination workloads.

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

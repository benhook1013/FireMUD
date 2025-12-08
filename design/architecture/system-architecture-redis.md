# FireMUD System Architecture: Redis

This document outlines FireMUD’s usage of Redis as a **transient, high-performance, distributed coordination layer**. It focuses on Redis's responsibilities, safety guarantees, key patterns, and operational practices.

## Table of Contents

- [FireMUD System Architecture: Redis](#firemud-system-architecture-redis)
  - [Table of Contents](#table-of-contents)
  - [Redis as a Volatile State Layer](#redis-as-a-volatile-state-layer)
  - [Redis Availability, Consistency, and Safety Guarantees](#redis-availability-consistency-and-safety-guarantees)
  - [Key Naming and Shard Discipline](#key-naming-and-shard-discipline)
  - [Atomicity and Concurrency Control](#atomicity-and-concurrency-control)
  - [Tick Integration (Resilience, Locking, Staging)](#tick-integration-resilience-locking-staging)
  - [Observability and Reliability](#observability-and-reliability)
  - [Session Keys and Gameplay Binding](#session-keys-and-gameplay-binding)
  - [Future Cache Design and Versioned Aggregates](#future-cache-design-and-versioned-aggregates)
  - [Related Documentation](#related-documentation)

---

Redis is always treated as **non-authoritative for game data**: all canonical game data (accounts, entities, items, rooms, game instances) lives in **PostgreSQL**, owned by domain-specific services. Redis provides **volatile coordination state** — ticks, locks, timers, sessions, queues — that participates in gameplay availability and recovery in two distinct deployment modes:

- In **development and ephemeral test environments**, Redis behaves as a **disposable coordination cache**. Helm may wipe its AOF between runs, and no guarantees are made about preserving in-flight ticks, sessions, or timers across deployments.
- In **staging and production-equivalent environments**, Redis behaves as a **long-lived coordination log**. AOF is preserved across normal rollouts and node restarts; crash recovery and replay semantics described in this document apply and must not be bypassed by automatic AOF resets.

All subsequent sections assume the **staging/production coordination mode** unless explicitly labeled as “dev/ephemeral only.” Deployment and runbook docs call out where behavior differs by environment.

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
- Automation queue keys for script events (`automation_queue:{tenantId}:{entityId}`) that are treated as **single-key operations** from Redis’s perspective (see [Hash Tags and Redis Cluster Slotting](#hash-tags-and-redis-cluster-slotting) for guidance if future automation scripts need to combine these keys with tick-region keys atomically)

FireMUD distinguishes between **Coordination Redis** and **Cache/Rate‑Limit
Redis**:

- Coordination Redis handles ticks, locks, timers, sessions, and other
  gameplay‑critical coordination state. Services that participate in tick
  execution or session management (for example, Game Session Service, Automation
  & Scripting Service, Social & Groups Service) connect to the **Coordination
  Redis** deployment using `FIREMUD_REDIS_COORD_HOST` and
  `FIREMUD_REDIS_COORD_PORT`.
- Cache/Rate‑Limit Redis handles gateway rate limiting and best‑effort caches.
  Services that only perform rate limiting or read‑side caching (for example,
  Spring Cloud Gateway) connect using `FIREMUD_REDIS_CACHE_HOST` and
  `FIREMUD_REDIS_CACHE_PORT`.

For local development and other single‑node setups, all of these variables may
point at the same instance. When the `*_COORD_*` or `*_CACHE_*` variables are
unset, services fall back to `FIREMUD_REDIS_HOST` and `FIREMUD_REDIS_PORT` as
documented in
[Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#redis-connection).
In **player‑facing environments**, Coordination Redis and Cache/Rate‑Limit Redis
should be configured as distinct logical Redis deployments even if they share the
same underlying host. Sharing a single deployment for both workloads is strongly
discouraged because misconfiguration (for example, eviction policies or memory
limits) can silently impact coordination keys and gameplay availability.

Redis acts as a **coordinated real-time buffer**, not a source of truth, but is still treated as **critical** for game availability and consistency.

The **Game Session Service** is responsible for coordinating tick and session behavior using Redis as its execution substrate.

### Benefits

- Low-latency access for gameplay-critical state
- Enables stateless, horizontally scalable services
- Supports safe concurrent ticks and session handling
- Facilitates reconnection, failover, and replay

---

## Redis Availability, Consistency, and Safety Guarantees

Redis is a **non-authoritative, AOF-persistent coordination layer**, not a durable source of truth. It persists **volatile coordination state** (locks, `pending` entries, timers, retry metadata, and queues) via AOF so interrupted ticks can be **replayed** in staging/production environments. Availability and **idempotent, replay-based recovery** are prioritized rather than strict exactly-once semantics. The goal is to guarantee specific invariants (for example, “no double‑apply of tick effects” and “at‑most‑one active tick executor per region”) even though some recent volatile coordination state may be lost around crashes or failover. Losing that coordination state is acceptable as long as:

- Domain effects in PostgreSQL are never applied twice for the same `tickId`.
- The system detects and surfaces any gaps so operators can decide whether to repair or accept them for a given tenant/region.

From a player and tenant perspective, this translates into an **at-least-once but not exactly-once** command model for gameplay:

- Under normal operation, commands are processed exactly once and tick progression is deterministic.
- Under rare infrastructure failures (Redis node crashes, AOF tail loss, or failovers between out-of-sync replicas), FireMUD may:
  - **Lose a tail window of volatile coordination state** (for example, the most recent ticks worth of command queues or timers) and advance `tickId` past work that is no longer present in Redis, or
  - **Replay a tick** whose staged effects were already partially applied in PostgreSQL.
- Domain idempotency rules ensure that replays do not apply additional logical effects, but lost coordination state can result in:
  - The last few commands for a character or region not taking effect.
  - Some timer expirations being skipped or delayed.

These behaviors are treated as acceptable trade-offs for a high-performance tick system, provided that:

- Truly non-loss-tolerant flows (for example, payments or cross-service sagas) avoid Redis-based tick coordination and instead use the stronger guarantees described in [Transaction Strategies](./system-architecture-transactions.md).
- Metrics and alerts make any **skipped or replayed ticks** visible so operators can quantify impact and decide on tenant-specific remediation when needed.

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
- Tick coordination state (locks, `pending`, timers, retry metadata) is **generally preserved across routine failover** thanks to AOF and Lua-based commit/staging policies, but this is **best-effort rather than absolute**:
  - Lock keys use TTLs; they survive only while their TTL has not expired and the relevant updates have been durably written to AOF.
  - Retry and `pending` keys do not rely on TTLs and are expected to survive typical failovers, subject to whatever AOF tail-loss window the deployment’s Redis configuration produces.
  - Under rare compound failures or long pauses, some recent coordination keys (including locks) may be lost; recovery logic is therefore designed around idempotent replay and at-most-once guarantees rather than assuming locks are always durable across failover.

Architecture and implementation must therefore treat Redis as:

- **Best-effort durable for coordination state** (you can usually replay recent ticks from surviving `pending` entries), and
- **Explicitly allowed to lose a window of the most recent coordination writes**, whose exact size depends on Redis configuration and infrastructure behavior. PostgreSQL and idempotent tick processing remain the only authorities for long-term correctness.

> For operational context on Docker Compose vs Kubernetes, see [Deployment Environments](./infrastructure/deployment-environments.md).

### Replication, Durability, and Loss Characteristics

- Writes are **asynchronously replicated**.
- **AOF (Append-Only File)** is enabled for durability and crash recovery on coordination Redis deployments in staging and production.
- Development uses [config/redis/redis.conf](../../config/redis/redis.conf) for the single-node instance and can
  persist the AOF via the `redis-data` volume. See
  [Developer Setup](../../DEVELOPER_SETUP.md#optional-redis-persistence) for details and the
  RedisInsight debugging UI.
- In **development and ephemeral test environments**, Helm may reset the AOF on install/upgrade via
  [`redis-aof-reset-job.yaml`](../../k8s/helm/firemud/templates/redis-aof-reset-job.yaml) to guarantee a clean
  slate between runs (see [Backup & Recovery](./system-architecture-backup-recovery.md#redis-aof-reset-on-deployment)).
- In **staging and production**, Redis AOF files and volumes are **preserved across application deployments**. Resetting Redis
  (and thereby terminating active sessions and discarding all volatile tick state) is treated as an explicit
  operational action, not part of normal CI/CD rollout or default Helm behavior.

The `redis-aof-reset-job` is **strictly scoped** and must be treated as **dev/ephemeral-only tooling**:

- It is only enabled in **ephemeral** dev/test namespaces where all games are disposable and losing volatile coordination state (sessions, pending ticks, timers, queues) on each deploy is acceptable.
- Staging, pre-production, and production environments:
  - Must not include the reset Job in their Helm values.
  - Treat any AOF reset or Redis flush as a manual, audited operation with clear runbooks and explicit player-impact notes.
- Helm values and CI/CD pipelines must not reuse dev/test Redis values for production namespaces; production values files are separate so misconfiguration cannot silently enable AOF resets outside ephemeral environments.

Redis provides **best-effort durability** for volatile coordination state, not absolute guarantees:

- AOF rewrite policy (for example Redis’s `auto-aof-rewrite-percentage` and `auto-aof-rewrite-min-size` settings), `appendfsync` behavior, disk performance, replication lag, and failover timing all influence how much recent state can be lost if a node crashes between an in-memory update and the next durable write.
- FireMUD does not currently specify a precise target for the AOF tail-loss window (for example, “no more than N seconds” or “no more than M ticks”). Instead, the design assumes that:
  - Some **bounded tail window** of recent volatile coordination writes may be lost under failure.
  - The exact window depends on deployment‑specific Redis configuration and infrastructure characteristics and may evolve over time.
- Correctness in the presence of such loss is preserved by:
  - Treating Redis as non-authoritative and replaying ticks based on PostgreSQL and any surviving `tick:{tenantId}:{regionId}:pending` keys.
  - Relying on the idempotent tick and domain logic described in [Crash Recovery and Replay](./system-architecture-ticks.md#crash-recovery-and-replay) so replays never apply new logical effects twice.

Coordination Redis deployments in staging and production are expected to:

- Run with AOF enabled (`appendonly yes`) and an fsync policy at least as strong as Redis’s `appendfsync everysec` so that the tail-loss window is bounded to a small number of ticks under normal conditions.
- Avoid disabling fsync entirely for coordination workloads (`appendfsync no` is not acceptable), even in performance experiments; test profiles that relax durability must be clearly labeled as such and not reused for player-facing environments.

Future operational work may introduce concrete observability around tail-loss behavior (for example, measuring effective loss windows in staging/production and tightening Redis configuration accordingly), but those targets are intentionally left unspecified at this stage.

FireMUD does **not** use the Redis `WAIT` command in application code. Replication remains asynchronous; the system assumes that:

- The primary may acknowledge a write before any given replica has applied it.
- Failover can, in rare cases, promote a replica that has not yet seen the latest coordination updates.

Correctness across those scenarios is achieved by treating Redis as a volatile coordination layer and relying on PostgreSQL and idempotent replay for authoritative state, rather than on synchronous replication acks for tick safety.

Cluster operators **may** additionally configure Redis replication safety knobs such as `min-replicas-to-write` and `min-replicas-max-lag` on Coordination Redis deployments to avoid accepting writes when no reasonably up-to-date replicas are available. These are **infrastructure-level safeguards only**:

- Gameplay logic does not depend on them for correctness.
- Enabling them trades write availability for additional replication guarantees and must be evaluated per environment.

#### AOF Truncation and Corruption

Operationally, AOF files are treated as **all-or-nothing** coordination history for a given node:

- If Redis detects AOF corruption on startup or if an operator suspects a damaged AOF, the node is considered **untrustworthy as a source of coordination state**.
- Preferred recovery is to:
  - Fail over to a healthy replica whose AOF is intact, promoting it to primary for the affected slots, and
  - Keep the corrupted node offline until its AOF has been discarded and the node has been reprovisioned or resynchronized from a clean source.
- When no clean replica exists and a compromised AOF is the only copy:
  - Operators may deliberately discard the AOF (for example by deleting it or starting Redis with AOF disabled), effectively treating Redis as if it had lost all volatile coordination state for the affected slots.
  - The platform then rebuilds coordination state from PostgreSQL and fresh tick/session activity using the idempotent replay rules described above.

Manual “surgery” on AOF contents is **not supported** in FireMUD runbooks. Either the AOF is trusted and used as-is, or it is discarded and the node restarts with an empty (or cleanly resynchronized) coordination keyspace.

#### Replica Promotion and Missed Writes

Because replication is asynchronous:

- A promoted replica may legitimately be missing some of the latest coordination writes from the former primary.
- After promotion, the **new primary’s keyspace becomes authoritative** for coordination state, even if tail keys (recent locks, timers, `pending` entries, queues, or sessions) were never replicated.

From the tick system’s perspective, this is indistinguishable from a larger AOF tail-loss window:

- Missing coordination keys are treated as if they never existed; ticks, retries, and timers that depended on them either:
  - Are re-enqueued based on surviving state and PostgreSQL, or
  - Are skipped, with impact bounded to the same best-effort window described above.
- Stale coordination keys that survived failover cannot cause double-apply or split-brain behavior because all mutating scripts validate lease tokens, lock tokens, `tickId`, and `generation` fields before making changes.

Replication-lag and health metrics (documented under Observability) are used to detect environments where promotion would routinely imply unacceptably large coordination gaps so operators can tune Redis or adjust tick budgets accordingly.

#### Lua Scripts and AOF Replay

On restart, Redis replays AOF entries in order, including `EVAL`/`EVALSHA` calls for Lua scripts. FireMUD’s Lua patterns are designed so that:

- Replaying a script from AOF is equivalent to **re-invoking it with the same `KEYS` and `ARGV`**.
- Tick and session scripts are required to be idempotent with respect to their inputs and to:
  - Validate the current lease and lock tokens before writing.
  - Enforce monotonic guards such as `tickId` and `generation` counters.
  - Use set-style semantics for staged effects and queues so duplicate inserts become no-ops.

As a result:

- AOF replay cannot double-apply tick effects or session mutations; stale replays see token or `tickId`/`generation` mismatches and exit without writes.
- Long-running scripts are already bounded by SLOs and runtime limits described under Observability; the design does not rely on AOF replaying arbitrarily slow or heavy scripts for correctness.

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

Lease management is split into two distinct operations:

- **Acquire:** When no lease exists for `{tenantId, regionId}`, a Game Session instance competes to acquire leadership using:
  - `SET tick-executor-lease:{tenantId}:{regionId} <leaseToken> NX PX lease_ttl_ms`
  - `leaseToken` is a random, opaque value (for example, a UUID) chosen by the prospective leader.
  - A successful `SET` indicates that this instance holds the lease for the current epoch; a failed `SET` means another executor already owns it.
- **Renew:** While holding the lease, the active executor extends its TTL using a **Lua-based compare-and-extend helper**, not `SET NX`:
  - Inputs:
    - `KEYS[1] = tick-executor-lease:{tenantId}:{regionId}`
    - `ARGV[1] = expectedLeaseToken`
    - `ARGV[2] = lease_ttl_ms`
  - Behavior (sketch):
    - Read `KEYS[1]`; if it is missing or its stored token does not equal `expectedLeaseToken`, return a `"STALE_LEASE"` outcome and perform **no writes**.
    - If the stored token matches, update the TTL via `PEXPIRE` (or `SET ... PX ... XX`) and return `"RENEWED"`.
  - Callers interpret `"STALE_LEASE"` as “this instance no longer owns the lease” and must immediately stop acting as leader for that `{tenantId, regionId}`. They may later attempt a fresh acquisition with a new `leaseToken` via the normal `SET NX` path.
  - No other component (including ad-hoc operational scripts) may write `tick-executor-lease:{tenantId}:{regionId}` directly; all renewals go through the compare-and-extend helper so token semantics remain consistent.

- Only the holder of a valid lease may:
  - Consume commands from region queues.
  - Drive `tick:{tenantId}:{regionId}:pending` and commit/rollback flow.
  - Update region timers (`timer:{tenantId}:{regionId}`) and retry queues (`retry:{tenantId}:{regionId}`).
- The active executor periodically refreshes the lease as long as it remains healthy.
- If the lease expires or is deliberately allowed to lapse:
  - Other Game Session instances may compete to acquire the lease.
  - The new leader resumes tick processing for `{tenantId, regionId}` using the existing Redis state (`pending`, queues, timers, retry metadata) and the idempotency rules described in the Tick System design.

This lease acts as the **macro-level lock** for a region: it prevents multiple executors from driving ticks concurrently for the same `{tenantId, regionId}` while still allowing fast failover and rebalancing. Fine-grained entity locks (`tick:{tenantId}:{regionId}:lock:{entityId}`) are used *within* a leader to coordinate per-command work and crash recovery; they do not replace the leadership lease.

**Lease token / epoch semantics**

To avoid “split-brain” scenarios during GC pauses or network stalls (for example, executor A holds the lease, pauses until its TTL expires, executor B acquires the lease, and then A resumes), the lease value stores a **random, opaque token** (for example, a UUID) in addition to the executor identity. The Game Session Service:

- Treats this token as a **lease epoch** for `{tenantId, regionId}` and passes it as an argument to all tick-related Lua scripts along with `tickId`.
- Requires every mutating script (staging, commit, rollback, timer updates, retry queue updates) to:
  - Read the current `tick-executor-lease:{tenantId}:{regionId}` value, and
  - Abort immediately if the stored lease token does not match the token it was invoked with.
- Re-validates the lease token at key points in the tick lifecycle:
  - Before starting a new tick (to ensure the worker still holds leadership).
  - Before committing staged work or releasing locks (to ensure leadership has not moved since staging began).

Combined with the **lock token semantics** described in the Tick System design, this ensures that:

- A worker that resumes after losing the lease cannot commit or roll back tick state, even if it still holds local lock tokens.
- Only the current lease holder (epoch) can progress `tick:{tenantId}:{regionId}:pending`, timers, and retry metadata for that region; any stale workers see a lease-token mismatch and abort, returning a retry outcome instead of applying stateful changes.

**TTL envelopes and worst-case pauses**

Lease TTLs and lock TTLs are chosen and monitored as part of a single envelope:

- `lock_ttl_ms` is derived from the soft tick budget using a configurable multiplier and bounds as described in the Tick System design (`lock_ttl_ms = clamp(tick_budget_ms * LOCK_TTL_MULTIPLIER, MIN_LOCK_TTL_MS, MAX_LOCK_TTL_MS)`).
- `lease_ttl_ms` is configured **strictly greater than** both the soft tick budget and `lock_ttl_ms` (for example, on the order of multiple ticks) so that:
  - Under healthy conditions, an executor refreshes the lease several times during normal operation and **lease expiry is not expected**.
  - Lock expiry during an in-flight tick is an **exceptional condition**, not part of the normal execution path.
  - A lock acquired under a given lease epoch is never refreshed across epochs; once its TTL starts, it can block work for that entity for at most `lock_ttl_ms` from acquisition, even if leadership changes.

`MAX_LOCK_TTL_MS` is therefore treated as a **hard upper bound** on per-entity failover stall caused purely by stale locks from a previous leader. Region-level progress is defined as “some entities advance”: a region may temporarily skip work for entities behind stale locks, but it should not be considered globally stuck unless the number or duration of such stalled entities crosses the degradation thresholds described under Observability.

Capacity planning and SLOs therefore assume:

- `p99` end-to-end tick execution time (lock acquisition → commit/rollback + lock release) remains within a conservative fraction of `lock_ttl_ms` (for example ≤50% in steady state, with alerts when sustained runtime exceeds 70%).
- Over-TTL ticks (where `tick.execution_time_ms >= lock_ttl_ms`) are rare outliers; dashboards and alerts treat a sustained over-TTL rate above a small threshold as a **degradation signal** that requires investigation (GC tuning, tick-budget adjustment, or load shedding).

When rare, worst-case pauses still occur:

- If a pause exceeds `lock_ttl_ms` but the executor retains the lease:
  - Locks may expire and be reacquired by the same or another worker.
  - Lock tokens and `pending`/`tickId` checks in Lua ensure that any late work from the original worker fails safely (token or epoch mismatch) and is retried instead of double-applying effects.
  - The original executor must treat the loss of a lock as a **hard failure** for the in-flight work on that entity:
    - It does not attempt to “complete” the current attempt without first reacquiring the lock via the shared helper.
    - If it chooses to retry, it does so by reacquiring the lock and re-running the workflow from a clean state, not by partially reusing stale local state.
- If a pause exceeds `lease_ttl_ms`:
  - Another executor may acquire the region lease and resume ticks from the surviving Redis state.
  - When the original worker resumes, lease-token checks prevent it from committing or rolling back tick state; its in-flight work is treated as failed and rescheduled via the normal retry mechanisms.
  - The new leader may encounter `tick:{tenantId}:{regionId}:lock:{entityId}` keys whose tokens do not match its current lease epoch. These are treated as **stale locks** from the previous epoch:
    - The new leader does **not** refresh or forcibly clear those locks.
    - It skips work for the affected entities while continuing to process entities that are not behind stale locks.
    - The stale locks naturally expire within their original `lock_ttl_ms`, after which the new leader can reacquire locks and resume work for those entities.

In both cases, the design optimizes for **safety and fairness under load**:

- Tick effects are not applied twice.
- Regions with repeated over-TTL behavior are automatically marked degraded and, if necessary, halted until operators correct the underlying cause.

**Configuration knobs and startup validation**

Lease and lock TTLs are controlled via explicit configuration properties:

- `game.tick-budget-ms` – soft tick execution budget per region.
- `game.tick-min-lock-ttl-ms` / `game.tick-max-lock-ttl-ms` – bounds for computing `lock_ttl_ms`.
- `game.tick-lease-ttl-ms` – region lease TTL used for `tick-executor-lease:{tenantId}:{regionId}` keys.

Internally, the Game Session Service also defines a `MAX_LEASE_TTL_MS` constant that bounds how long a region can remain “logically owned” by a single lease epoch even if the executor disappears. Region leases are intentionally **short-lived coordination hints** (seconds, not minutes), not long-duration ownership records; if an executor vanishes and its lease remains valid for longer than a small multiple of the tick budget, that is treated as a misconfiguration rather than a supported steady state.

At startup, the Game Session Service derives `lock_ttl_ms` from `game.tick-budget-ms` and validates:

- `game.tick-min-lock-ttl-ms <= game.tick-max-lock-ttl-ms`.
- `lock_ttl_ms` (computed) satisfies `lock_ttl_ms >= game.tick-budget-ms` and remains within `[MIN_LOCK_TTL_MS, MAX_LOCK_TTL_MS]` as documented in the Tick System design.
- `game.tick-lease-ttl-ms` is **strictly greater than** both `game.tick-budget-ms` and the computed `lock_ttl_ms` (for example, at least 2–3× `lock_ttl_ms`), but also bounded above:
  - `game.tick-lease-ttl-ms <= MAX_LEASE_TTL_MS`, where `MAX_LEASE_TTL_MS` is on the order of a small multiple of the tick budget (for example, a few seconds to tens of seconds, not minutes).
  - Optionally, `game.tick-lease-ttl-ms <= LEASE_TO_LOCK_TTL_MULTIPLIER * lock_ttl_ms` for a small fixed multiplier (for example 3–5×), so leases never outlive lock TTLs by more than a modest factor.

Because a lock from a previous lease epoch can stall only the entity it guards and only until its TTL expires, `MAX_LOCK_TTL_MS` also defines the **maximum tolerated per-entity failover stall window**. Configurations that attempt to increase `lock_ttl_ms` beyond this envelope are rejected; stall budgets longer than `MAX_LOCK_TTL_MS` must be justified by an explicit design change rather than ad-hoc configuration.

If any of these invariants are violated in any profile (dev, test, staging, or production), **startup fails fast** with a clear configuration error. There is no automatic fallback to “safe defaults”; running with an unsafe TTL envelope is treated as a configuration bug that must be corrected before the service can accept gameplay traffic.

These checks make misconfigured TTL envelopes visible early and keep all environments within the intended safety margins.

Implementations may also use local monotonic clocks or Redis’s `TIME` command **for diagnostics only**, for example to:

- Log warnings when an executor believes it has held a lease significantly longer than `game.tick-lease-ttl-ms` would suggest under normal renewal cadence, or
- Detect pathological environments where wall-clock time jumps dramatically.

However, **correctness never depends on wall-clock time**: lease safety is defined purely in terms of token equality and TTL expiry. Time-based checks are treated as guardrails that surface misconfigurations and unhealthy environments to operators; they are not part of the core coordination protocol.

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

To keep hash tags unambiguous and compatible with Redis Cluster’s parsing rules, FireMUD relies on a **versioned normalization scheme**:

- A canonical normalization function (for example `normalizeTenantIdV1(...)`, `normalizeRegionIdV1(...)`) produces the internal `tenantId` / `regionId` used in Redis keys and related database fields.
- The current hash-tag format is:
  - `"{tenantId}:{regionId}"` under `NORMALIZATION_V1`, where `tenantId` / `regionId` are the outputs of the V1 normalization functions.
- All services that construct keys or shard assignments must:
  - Use shared helper functions from the common library to obtain normalized IDs and hash tags.
  - Treat changes to normalization behavior (for example, different case folding or slug rules) as **schema changes** that require a migration plan, not as local implementation details.

Under a given normalization version, the `tenantId` and `regionId` segments that appear inside hash tags are **normalized identifiers**, not arbitrary external IDs. They must:
  - Omit `{`, `}`, and `:` characters (since `{}` delimit hash tags and `:` is used as a separator in key names).
  - Use a restricted character set such as lowercase letters, digits, and `-` / `_` (for example `[a-z0-9_-]+`).
- If an external tenant or region identifier does not meet these constraints (for example, it contains Unicode, braces, or colons), it is first mapped to a stable normalized form (for example, a slug, hex-encoded UUID, or other opaque token) before being used in Redis keys.
- The same normalized `tenantId` / `regionId` values are reused consistently across all tick-related keys so that a given `{tenantId, regionId}` hash tag always represents the same shard-local region.

#### Normalization Migration Strategy

Changing how identifiers are normalized or how hash tags are formed (for example, updating slug rules or moving from `NORMALIZATION_V1` to `NORMALIZATION_V2`) is treated as an explicit **schema and sharding change**:

- Any such change must:
  - Be implemented in the shared normalization helpers and documented with a new version constant (for example, `NORMALIZATION_V2`).
  - Be accompanied by a migration plan that accounts for:
    - Existing Redis keys that still use the old normalization.
    - Any coupling between hash tags and region placement in Redis Cluster.
- Typical migration phases:
  - **Phase 1 – Dual-compatibility and shadowing**
    - Compute both old and new normalized IDs in application code.
    - Continue reading existing keys using the old normalization while writing new keys using the new normalization (or vice versa), depending on the migration strategy.
    - Keep all tick and region assignments consistent across both schemes by draining or halting affected regions where necessary.
  - **Phase 2 – Key migration and cleanup**
    - Use background processes and/or scheduled drains to:
      - Move or rewrite keys from old hash tags to new ones where feasible, or
      - Allow old keys to expire naturally if they are strictly transient and do not need to be carried forward.
    - Remove compatibility code and old normalization paths once the keyspace has converged.
- At no point should two different normalization versions be used concurrently for new keys without an explicit compatibility layer; doing so risks splitting a logical `{tenantId, regionId}` across shards and breaking the assumption that all tick keys for a region are co-located.

Session and timer keys do not participate in tick multi-key scripts and may use simpler patterns as long as they preserve `tenantId` prefixes and avoid cross-shard assumptions. In particular:

- **Session-scope Lua scripts are strictly single-key**: a session script operates on one `session:{tenantId}:{sessionId}` key per invocation and must not touch tick-prefixed keys or other shards in the same `EVALSHA` call.
- If a future script legitimately needs to combine a session key with other keys (for example, a per-region index), it must:
  - Either encode a hash tag that keeps all involved keys in the same slot, or
  - Be refactored into separate calls so each script remains shard-local and single-key for sessions.

Automation and scripting keys follow a similar pattern:

- `automation_queue:{tenantId}:{entityId}` keys are intentionally treated as **single-key, per-tenant/per-entity queues**:
  - They are decoupled from tick-region hash tags so that automation work items can be enqueued independently of region ownership and sharding.
  - Scripts that operate on `automation_queue` keys treat them as single-key operations from Redis Cluster’s perspective and do not attempt to combine them atomically with tick-region keys inside the same Lua script.
- When automation needs to interact with tick keys, it does so via:
  - Dedicated staging keys such as `automation:tick:{tenantId}:{scriptId}:...`, which are designed to be region-agnostic, and
  - Follow-up tick commands enqueued into region-local queues (`tick:{tenantId}:{regionId}:queue:{entityId}`) once the appropriate region is known.

If a future feature legitimately requires **region-local, atomic coordination** between automation queues and tick keys, it must first extend the key model rather than repurposing existing single-key queues:

- One possible extension is to introduce new automation keys that adopt the `{tenantId}:{regionId}` hash tag (for example `automation_queue:{tenantId}:{regionId}:{entityId}`) and gradually migrate relevant workloads and scripts to those keys.
- Such changes are treated as architectural shifts:
  - They require review of sharding implications, migration cost, and failure modes.
  - They must be implemented via shared key-building helpers and Lua Script Registry descriptors, not ad-hoc key patterns in a single service.

These key-shape and hash-tag rules are **enforced**, not just conventions:

- All tick-related keys are constructed via shared **Key Naming** helpers in the common library; direct string concatenation of `tick:`, `retry:`, or `timer:` keys in application code is not allowed.
- Unit tests in the Game Session Service (or a shared Redis test suite):
  - Construct keys using the same helpers used in production.
  - Assert that all keys passed to a given Lua script share the same hash tag substring (the content inside `{}`).
  - In dev/test profiles, verify hash-slot alignment via `CLUSTER KEYSLOT` checks so regressions are caught early.
- A structured **Lua Script Registry** and descriptor format keeps Java key builders and Lua `KEYS[...]` ordering in sync:
  - Each script is described by a small machine-readable descriptor (for example a JSON/YAML file or Java annotation) that declares:
    - The logical script name.
    - The required number and order of `KEYS` (with symbolic names such as `lockKey`, `pendingKey`, `leaseKey`, `timerKey`).
    - The allowed key prefixes for each position (for example `tick:`/`retry:`/`timer:` for tick scripts, `session:` only for session scripts).
    - Whether the script is expected to be **multi-key shard-local** or **single-key**.
    - How many entity lock keys (`tick:{tenantId}:{regionId}:lock:{entityId}`) it is allowed to touch (for example `max_entity_locks = 1` for the default case).
    - Whether the script participates in an **ordered multi-lock** pattern (rare) where more than one entity lock is acquired under a deterministic ordering, as described in the tick design.
  - Java key-builder APIs for each script are generated from (or validated against) this descriptor so that:
    - Builders construct `KEYS[...]` in exactly the declared order.
    - Callers cannot assemble `KEYS` arrays manually; they must call the generated builders.
  - A CI step parses descriptors, loads the Lua source, and verifies:
    - The script’s `KEYS` count matches the descriptor.
    - All sample `KEYS` produced by the Java builders share the same hash tag for multi-key scripts.
    - Single-key scripts receive exactly one key.
    - The number of entity lock keys referenced by the script (for example via `lockKey` entries and `tick:{tenantId}:{regionId}:lock:` prefixes) does not exceed the declared `max_entity_locks`.
    - Scripts that declare `max_entity_locks > 1` also opt in to the ordered multi-lock mode and include tests that prove they acquire and release locks in a deterministic order.
- Static analysis and linting enforce prefix and shard-locality rules at the script level:
  - A Lua linter scans each script for literal key prefixes (`"tick:"`, `"session:"`, `"retry:"`, `"timer:"`, `"remote:"`, cache prefixes) and compares them with the script’s descriptor.
  - Tick coordination scripts are allowed to use only tick/coordination prefixes (`tick:`, `retry:`, `timer:`, `remote:`) and are rejected in CI if they reference `session:`, automation, or cache prefixes.
  - Session CAS scripts are allowed to use only `session:` keys and are rejected if they touch `tick:`, automation, or other coordination prefixes.
  - Automation scripts are explicitly registered as **single-key** scripts whose allowed prefixes are `automation_queue:` and `automation:tick:`. CI rejects automation scripts that reference `tick:`, `session:`, or any other coordination prefixes, preventing mixed `automation:*` + `tick:*` key usage that could trigger `CROSSSLOT` errors.

Pull requests that introduce new tick-related keys or Lua scripts must:

- Add or update the corresponding script descriptor and key-builder APIs.
- Extend the shared Redis test suite to prove:
  - Correct `KEYS` ordering and hash-tag alignment.
  - Enforcement of the declared prefix constraints (for example, tests that intentionally supply a mismatched prefix and assert a fast failure or no-op).
- Avoid ad-hoc `EVAL`/`EVALSHA` calls; all script invocations go through registry-backed helpers that resolve the SHA and build `KEYS` from descriptors.

Scripts that hard-code non-hash-tagged tick keys, mix forbidden prefixes, or produce `CROSSSLOT` errors under tests are rejected.

---

## Atomicity and Concurrency Control

Redis’s single-threaded model is extended using **Lua scripts** for atomic operations:

- Entity lock acquisition (`tick:lock:*`)
- Tick staging, commit, and rollback (`tick:pending:*`)
- Timer lifecycle management
- Session rebinding and deduplication (`session:*` keys; strictly single-key scripts that operate on one session key per call)
- Retry queue updates

All Lua scripts are:

- Stored under `services/game-session-service/src/main/resources/redis/`
- **Idempotent**
- **Shard-local**
- **Retry-safe**
- Designed to avoid cross-tick contamination

Tick-related multi-key operations **must not** be implemented as ad-hoc sequences of plain Redis commands outside these scripts. All staging, commit, rollback, and lock manipulation that touches multiple tick keys (locks, `pending`, queues, timers, or retry metadata) is performed exclusively via the Lua scripts in `services/game-session-service/src/main/resources/redis/` so transactional behavior remains consistent and replay-safe.

Additional guardrails for script authors:

- Scripts must **never mix** tick-prefixed keys (`tick:*`, `retry:*`, `timer:*`, `remote:*`) and `session:*` keys in the same `EVALSHA` call. Tick coordination and session management are kept separate:
  - Tick scripts operate on region-scoped tick keys plus their associated timers/retries.
  - Session scripts operate on per-session keys only (and are therefore single-key from Redis Cluster’s perspective).
- Any script that accepts **multiple keys** must be bound to a single `{tenantId}:{regionId}` hash tag via its `KEYS[1]` argument. Cross-region or “global” multi-key scripts (for example, scripts that operate on keys without a region hash tag or that span multiple regions) are **not allowed**; those flows must either be decomposed into multiple single-region scripts or use database-level coordination instead.

> 🔗 For use during tick execution, see [Distributed Locking](./system-architecture-ticks.md#🔐-distributed-locking)

### Lua Script Author Checklist

Every new or modified Lua script that participates in tick coordination, sessions, or automation must satisfy the following checklist before it is accepted:

- **Registered contract**
  - The script is registered in the central **Lua Script Registry** with:
    - A logical script name.
    - A descriptor that declares the exact `KEYS` count, ordering, and allowed prefixes.
    - A flag indicating whether it is multi-key shard-local or strictly single-key.
  - Java callers invoke the script only via registry-backed helpers and generated key builders; there are no ad-hoc `EVAL`/`EVALSHA` calls.

- **Key shape and shard-locality**
  - For multi-key scripts:
    - `KEYS[1]` is a tick-region key whose `{tenantId}:{regionId}` hash tag defines shard locality.
    - All other keys share the same hash tag.
    - Unit tests use the generated builders and `CLUSTER KEYSLOT` (in dev/test) to verify alignment.
  - For single-key scripts:
    - Exactly one key is defined in the descriptor and used at runtime.

- **Prefix discipline**
  - The script’s descriptor and the Lua linter agree on which key prefixes are allowed:
    - Tick scripts: `tick:`, `retry:`, `timer:`, `remote:` only.
    - Session scripts: `session:` only.
    - Cache/rate-limit scripts: their own non-coordination prefixes and **never** `tick:`/`session:`.
  - CI fails if the script text references forbidden prefixes.

- **Idempotency and safety**
  - The script is idempotent with respect to its inputs (`KEYS`/`ARGV`):
    - Re-invocation with the same arguments does not apply new logical effects.
    - Where applicable, token and `tickId`/`generation` checks are performed before any mutation.
  - Unit tests cover:
    - First invocation from a clean state.
    - Second invocation with identical `KEYS`/`ARGV` (asserting no additional changes).
    - Replay after partial progress (by prepopulating keys to simulate a crash mid-way).

- **Schema awareness (where applicable)**
  - Scripts that operate on structured values (for example, session bindings or `tick:{tenantId}:{regionId}:pending` payloads) understand an explicit **versioned schema** (`schemaVersion`) and:
    - Treat unknown versions conservatively (no mutation + clear error/metric).
    - Support at least the **current** and **previous** schema versions during migrations so they can read data written by both old and new service code.
    - Follow a simple rollout rule: **scripts first, then writers**. New scripts that understand multiple schema versions are deployed cluster‑wide before services start writing the new version; services are updated next to emit the new `schemaVersion`. Cleanup (dropping old‑version branches in scripts) happens only after metrics show the old version has effectively drained from the keyspace.
    - Include tests that exercise behavior against representative values for each supported `schemaVersion` so incompatibilities between script logic and stored payloads are caught in CI rather than at runtime.

Scripts that do not meet this checklist—mismatched descriptors, prefix violations, non-idempotent behavior, or missing tests—must be rejected during review and CI until they are brought into compliance.

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

To avoid **thundering herds** of `SCRIPT LOAD` operations when cluster topology changes (for example, resharding or adding a new master), the client-side loader applies additional safeguards:

- **Per-node, per-script single-flight**
  - The script loader maintains an in-memory “loading in progress” map keyed by `(nodeId, scriptId)`.
  - When multiple workers concurrently see `NOSCRIPT` for the same `(nodeId, scriptId)`, the **first** caller issues `SCRIPT LOAD` and records a future/promise in the map.
  - Subsequent callers wait on that future rather than issuing their own `SCRIPT LOAD`; once it completes, they reuse the resulting SHA and proceed with `EVALSHA`.

- **Topology-aware background preload**
  - The Redis client observes cluster metadata (for example, via periodic refresh of the cluster slot map).
  - When it detects new masters or slot movements, it **opportunistically preloads** all tick-related scripts onto the affected nodes in the background:
    - Preload operations are rate-limited (for example, a small fixed concurrency and backoff) so they do not contend with normal tick traffic.
    - Preload failures increment `redis.lua.script_load_failures` but do not block tick execution; on-demand `NOSCRIPT` handling still provides correctness, just with slightly higher latency.

- **Bounded retry and backoff**
  - If `SCRIPT LOAD` for a given `(nodeId, scriptId)` repeatedly fails, the loader:
    - Applies exponential backoff for subsequent attempts to avoid hammering an unhealthy node.
    - Marks the corresponding regions as degraded if retries exceed a configured threshold, so operators see the impact in dashboards.

These measures ensure that `NOSCRIPT` handling remains correct under failover and resharding while keeping the load profile predictable, even when many workers hit a freshly promoted node at once. During topology changes, each `(nodeId, scriptId)` pair sees at most one `SCRIPT LOAD` in the hot path, and any additional reload work is shifted into the background preload cycle instead of all workers rediscovering missing scripts independently.

If a script repeatedly fails to reload or `NOSCRIPT` errors persist beyond a short, configurable retry window, the Game Session Service:

- Logs a structured error with script name, region, and tenant context.
- Emits a metric such as `redis.lua.script_load_failures` for alerting.
- Treats the affected tick region as **degraded** until the underlying Redis issue is resolved.

### Script Evolution and Deployment Order

Lua scripts change over time as bugs are fixed, behavior is refined, and new coordination flows are added. To keep rollouts predictable without over-constraining teams, FireMUD distinguishes between **compatible** and **breaking** script changes and applies simple sequencing rules:

- **Compatible script changes (no special order required)**
  - Internal bugfixes or performance improvements that do not change:
    - The script’s logical identifier.
    - The declared `KEYS`/`ARGV` count or ordering.
    - The documented outcome contract for existing callers.
  - Adding new scripts under **new logical identifiers** that no existing caller references.
  - These changes can be rolled out with normal application deployments; `NOSCRIPT` handling and background preload keep behavior correct even under frequent releases.

- **Breaking script changes (require coordination)**
  - Any change that:
    - Alters the expected `KEYS`/`ARGV` shape for an existing logical script.
    - Changes semantics in a way that would break existing callers’ expectations.
    - Removes a script that may still be referenced by some callers.
  - For these changes, deployments follow a lightweight “scripts first, callers second” rule, mirroring the `schemaVersion` rollout pattern:
    - **Phase 1 – Script compatible with old and new callers**
      - Introduce a new script body (under the same or a new logical identifier) that can handle both the old and new calling conventions safely (for example, treats missing arguments conservatively or understands both old and new payload variants).
      - Deploy this updated script set cluster-wide so all Redis nodes can execute it.
    - **Phase 2 – Caller rollout**
      - Update services to send only the **new** `KEYS`/`ARGV` shape or to invoke a new logical script identifier.
      - Monitor `NOSCRIPT` and script failure metrics to confirm that calls are consistently hitting the expected behavior.
    - **Phase 3 – Cleanup**
      - Once metrics and code search show that no callers use the old behavior or logical identifier, remove compatibility branches and/or delete the obsolete script from the registry.

- **Separate logical identifiers for incompatible behavior**
  - When behavior cannot be made backward-compatible for a period of time, the preferred pattern is to introduce a **new logical script identifier** (for example `tick_commit_v2`) rather than mutating the existing one in place.
  - Old callers continue to use `*_v1` while new callers move to `*_v2`; once all callers have migrated, `*_v1` can be removed from the registry and codebase.

These rules are intentionally minimal: they do not require strict global coordination, but they ensure that:

- Callers never depend on a script behavior that has not yet been deployed cluster-wide.
- Mixed versions of a script remain safe (by design) during a rollout window.
- `NOSCRIPT` handling continues to provide correctness under churn, while deployment order and logical versioning prevent confusing “old vs new behavior” splits.

This loading and retry behavior ensures that transient `SCRIPT FLUSH` events or node failovers do not permanently break tick processing while still surfacing persistent misconfiguration or Redis instability to operators.

When Lua scripts are versioned or changed as part of a deployment, services either:

- Restart (clearing any in-memory mapping from “logical script name” to SHA), or
- Refresh their cached SHA values explicitly as part of the rollout,

so that a given logical script identifier never silently points at a stale SHA with incompatible behavior.

### Lua Script Complexity and Runtime Guidelines

Redis executes Lua scripts on the same single-threaded event loop that serves normal commands. To protect shard latency without over-specifying hard numeric limits, FireMUD applies the following guidelines to all tick-related scripts:

- **Bounded work per script**
  - Scripts must not iterate over unbounded lists, sets, or streams.
  - Operations should be `O(1)` or `O(log n)` relative to key cardinality wherever possible.
  - Any looping logic must be bounded by explicit, small limits (for example, “process at most N commands/timers per invocation”), with the remainder handled in future ticks.
  - Scripts must not build large in-memory aggregates (for example, assembling full copies of large hashes, lists, or sets into Lua tables); any aggregation beyond a small, bounded slice belongs in service code outside Redis rather than inside Lua.

- **Limited keys and arguments**
  - Scripts should operate on a small, fixed set of keys per invocation (for example, one lock key, one pending key, and a handful of queues/timers for a single region).
  - Bulk fan-out or large multi-key operations should be decomposed into multiple smaller calls instead of a single monolithic script.
  - Implementations define and enforce a small constant upper bound on the number of keys any tick-related script may touch (for example, a `MAX_TICK_SCRIPT_KEYS` configuration on the order of a handful of keys per region). This bound is captured in configuration and validated via unit tests so “just one more key” changes are deliberate and reviewed; scripts that exceed the configured bound are rejected during testing.

- **Per-script runtime observability**
  - The Game Session Service and other coordination clients record **per-script latency metrics** (for example, histograms keyed by logical script name) so operators can see which Lua scripts dominate time on each shard and tune the system based on observed behavior.

- **Abort-early behavior**
  - Every script should check simple preconditions first (for example, presence of lock keys, correct tokens, expected `pending` state) and abort quickly if they are not met.
  - Scripts must not fall back to scanning large keyspaces or reconstructing complex state when preconditions are missing; instead, they return a result that signals the caller to retry or perform higher-level recovery.
  - Scripts that mutate domain-facing tick state (for example, committing staged effects or releasing locks) **must re-validate** both the relevant lock token(s) and the current lease token for `{tenantId, regionId}` within the **same script invocation** that performs the mutation; callers must not “check then act” across multiple, separate Lua calls.

Operational runbooks treat **long-running or stuck Lua scripts** as production issues:

- Monitoring tracks Redis `slowlog`, blocked-client counts, command/runtime latency distributions, and the per-script metrics above. Sustained outliers trigger alerts so operators can investigate which script or workload is responsible and adjust scripts or configuration as needed.
- In emergencies where a script is known to mutate only Redis state and is demonstrably stuck, operators may use `SCRIPT KILL` on the affected node to unblock the event loop. This is reserved for last-resort scenarios and must be followed by verification that callers correctly handle partial progress (for example, by re-running idempotent staging or commit scripts).
- Runbooks emphasize **fixing the underlying script or workload** (for example, tightening bounds, reducing per-call work, or refactoring hot paths) rather than relying on `SCRIPT KILL` as a routine control mechanism.

### Idempotent Script Patterns and Examples

Tick-related scripts must be idempotent: **re-running the same script with the same `KEYS` and `ARGV` must not apply new logical effects**. Detailed patterns (lease/lock validation, tickId guards, effect-key sets, queue uniqueness, and idempotency test templates), along with a worked lock-acquire example, are documented in [Redis Lua Patterns](./system-architecture-redis-lua-patterns.md). Scripts referenced in this document are expected to follow those patterns or motivated variants.

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
  - Tracks per-region **stale-lock** metrics when a leader observes locks whose token does not match the current lease epoch, such as:
    - `tick.stale_locks_current` – number of entities in the region currently behind stale locks from a previous epoch.
    - `tick.stale_lock_max_remaining_ttl_ms` – maximum remaining TTL among those stale locks, approximated via `PTTL`.
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
- Separately, regions that remain impacted by stale locks from previous epochs are tracked and surfaced:
  - A region is considered **stale-lock degraded** if, over a rolling window (for example 5 minutes), either:
    - `tick.stale_locks_current` remains non-zero, or
    - `tick.stale_lock_max_remaining_ttl_ms` repeatedly approaches `lock_ttl_ms`, indicating that new leaders are frequently waiting out nearly full lock TTLs.
  - Warning alerts fire when a region enters the stale-lock degraded state; critical alerts fire when it remains in that state beyond a hard, configurable window.
  - In extreme cases where stale locks effectively block most entities in a region until expiry, the scheduler may treat the region as temporarily halted and refuse new commands until the lock TTL window has passed or operators intervene.

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

This policy applies consistently across the conditions described elsewhere in this document: replication failures or sustained lag, `OOM`/evictions of coordination keys, repeated over-TTL ticks, and client-level connection timeouts all drive regions through the same **healthy → degraded → halted** progression, rather than each caller inventing its own ad-hoc behavior.

To avoid flapping when metrics hover around thresholds, transitions between these states apply simple hysteresis and cooldown rules:

- Entry thresholds are evaluated over rolling windows (for example, 5–10 minutes of elevated latency or over-TTL ticks) before marking a region degraded or halted.
- Exit thresholds are deliberately stricter and longer-lived than entry thresholds; for example, a region may require multiple consecutive “good” windows (lower latency, no over-TTL ticks) before it is eligible to move from halted → degraded → healthy.
- Implementations treat degraded/halted as **minimum residency states**: once a region enters one of these states, it remains there for at least a short, configurable cooldown period even if metrics briefly improve, to avoid rapid oscillation.
- Operators can override thresholds and cooldowns per environment; in borderline conditions, environments may prefer to keep a region pinned in degraded (throttled) rather than repeatedly toggling between halted and healthy.

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
    - Coordination footprints are sized using the approximate model from the Redis Cache design:
      - `coord_bytes ≈ regions * (locks_per_region * avg_lock_bytes + timers_per_region * avg_timer_bytes + pending_bytes_per_region + session_bytes_per_region)`.
      - Deployments should keep `coord_bytes` under a target fraction of `maxmemory` (for example, <30–40%) after applying a **2–3× safety factor** to account for spikes, fragmentation, and unforeseen growth.
    - Gameplay services are expected to expose **admission-control guardrails** tied to Redis capacity, such as:
      - Maximum active regions per tenant or shard.
      - Maximum concurrent sessions per tenant.
      - Maximum timers or outstanding commands per region.
    - When those guardrails are reached, services should:
      - Reject new regions or sessions with clear “capacity reached” errors instead of continuing to allocate coordination state, and/or
      - Shed lower-priority work (for example, background automation) before core gameplay, so Redis is not driven into `OOM`.

- **Cache/Rate-Limit Redis eviction**
  - For the Cache/Rate-Limit Redis deployment where eviction is expected:
    - Warning alert: sustained eviction rate above a configured baseline (for example, >**1,000 evictions per minute** for >10 minutes) or a step-change relative to recent history.
    - Critical alert: eviction rate that grows without bound or consistently exceeds a fraction of the keyspace per hour (for example, evicting more than **5%** of average key count per hour), indicating mis-sized caches or runaway writers.

These thresholds are intended to be **explicit starting points**, not immutable rules. Operators may tighten or relax them per environment, but architecture and implementation should treat the classes of behavior above—high script runtimes, elevated coordination latency, over-TTL ticks, coordination `OOM`/evictions, and runaway cache eviction—as the canonical “red lines” that justify automated degradation and paging.

### Example Lock Workflow

Lock acquisition follows a **single allowed pattern** and is always performed via a shared helper in the Game Session Service:

1. Generate a unique lock token (for example, a UUID) and acquire `tick:{tenantId}:{regionId}:lock:{entityId}` using `SET NX PX` with that token as the value and the computed TTL (`lock_ttl_ms`), via the shared lock helper. This ensures transient pauses do not cause the lock to expire while normal work is still in progress, but stale locks are automatically cleared after a bounded window.
2. Stage updates under `tick:{tenantId}:{regionId}:pending` via Lua script while the lock is held.
3. On successful commit or rollback, call a **single canonical Lua script entrypoint** for “tick commit/rollback + lock release” that:
   - Receives `KEYS` in a fixed order such as `[lockKey, pendingKey, leaseKey, …]`.
   - Receives `ARGV` values that include both the `lockToken` and the current `leaseToken` for `{tenantId, regionId}`.
   - Verifies `GET tick:{tenantId}:{regionId}:lock:{entityId}` still matches the original `lockToken`.
   - Reads and verifies the `tick-executor-lease:{tenantId}:{regionId}` value still matches the provided `leaseToken`.
   - Deletes the lock key and clears or updates the staged `tick:{tenantId}:{regionId}:pending` entry only when both tokens match and the expected `tickId` is present.
4. If the lock expires before commit:
   - The next tick cycle detects the presence of `tick:{tenantId}:{regionId}:pending` and replays the staged effects using the idempotency rules described in the Tick System design.
   - Any worker that finds `pending` present but fails to reacquire the lock (because another worker has already taken it with a new token) aborts its work for that tick and returns a retry outcome; it does **not** attempt to apply domain updates without first holding a valid lock token.

Tick locks are **never** released or modified via raw `DEL`, `PEXPIRE`, or similar commands from application code. The only allowed non-Lua operation on `tick:{tenantId}:{regionId}:lock:{entityId}` keys is acquisition via the shared `SET NX PX` helper. All lock validation and release flows run through the shared Lua scripts that enforce token checks, so no worker can accidentally release or reuse another worker’s lock after a TTL expiry or leadership change. Code review and CI guardrails (for example, grep-based checks that forbid `DEL tick:{tenantId}:{regionId}:lock:*` outside the canonical scripts) enforce this policy so ad-hoc lock manipulation cannot slip into the codebase.

To keep this behavior enforceable in a growing codebase:

- A small **“lock contract” test suite** exercises the shared helper and canonical Lua scripts together:
  - Acquires a lock via the helper and asserts that the TTL and key shape match expectations.
  - Verifies that only the canonical release script can successfully clear the lock key under normal conditions.
  - Simulates loss of lock keys (for example, via TTL expiry) and asserts that late commit attempts fail with the appropriate status and do not mutate domain-facing state.
- Static checks and tests prevent ad-hoc manipulation of lock keys:
  - Build-time checks scan for direct `DEL`/`PEXPIRE` operations on `tick:{tenantId}:{regionId}:lock:*` outside the shared helper and canonical Lua scripts, and fail the build if any are found.
  - Any future “force unlock” or administrative behavior must be implemented via dedicated, reviewed scripts or admin endpoints, not raw Redis commands, and must be documented with their operational semantics and risks.

### Canonical Commit/Rollback Script API

The canonical “tick commit/rollback + lock release” script is treated as a stable API that all tick executors use. Its logical interface is:

- **Logical name:** `tick_commit_and_release` (exact filename may vary, but callers refer to this logical identifier when resolving the script SHA).
- **Keys (`KEYS`):**
  - `KEYS[1]` – `tick:{tenantId}:{regionId}:lock:{entityId}` (entity lock key).
  - `KEYS[2]` – `tick:{tenantId}:{regionId}:pending` (region-level pending entry for the current tick).
  - `KEYS[3]` – `tick-executor-lease:{tenantId}:{regionId}` (region leadership lease).
  - `KEYS[4]` (optional) – tick-local retry/timer structure if the script needs to adjust retry metadata as part of commit/rollback; when present it must share the same `{tenantId}:{regionId}` hash tag (for example `retry:{tenantId}:{regionId}` or `timer:{tenantId}:{regionId}`).
- **Arguments (`ARGV`):**
  - `ARGV[1]` – `tickId` being committed or rolled back.
  - `ARGV[2]` – `lockToken` that the caller believes it holds for `KEYS[1]`.
  - `ARGV[3]` – `leaseToken` (epoch) that the caller believes is current for `KEYS[3]`.
  - `ARGV[4]` – `mode` (`"commit"` or `"rollback"`), indicating whether staged effects should be applied or discarded.

The script validates in this order:

1. Lease epoch: read `KEYS[3]` and ensure its token matches `ARGV[3]` (the expected `leaseToken`); abort if mismatched or missing.
2. Lock token: read `KEYS[1]` and ensure its value matches `ARGV[2]` (the expected `lockToken`); abort if mismatched or missing.
3. Pending tick: read `KEYS[2]` and ensure it corresponds to `ARGV[1]` (the expected `tickId`); abort if the key is absent or belongs to a different tick.

If any validation fails, the script returns a structured status that callers treat as “no-op + retry” rather than attempting any domain mutation. A simple status convention is:

- `0` – success: commit/rollback was applied, lock released, and `pending` cleared or updated as requested.
- `1` – lease mismatch or missing.
- `2` – lock mismatch or missing.
- `3` – pending tick mismatch or missing.

Callers:

- Must treat **any non-zero status** as “no state change was applied” and schedule a retry via the normal tick conflict/retry machinery; they must not attempt to release locks or modify `pending` via ad-hoc Redis commands.
- May log and increment metrics tagged with the status code to distinguish lease/lock/pending issues.

This API ensures that all commit/rollback behavior flows through a single, easily-audited Lua entrypoint and that re-validation of lease and lock tokens always happens in the same script invocation that mutates tick state.

### Pending Tick Value Model

Each `tick:{tenantId}:{regionId}:pending` entry stores a **single in-flight tick** for that region. Its value includes:

- A `tickId` that is monotonically increasing per `{tenantId, regionId}` tick stream
- The staged effects for that tick (for example, serialized entity mutations or event descriptors)

Lua scripts treat the presence of `tick:{tenantId}:{regionId}:pending` as meaning **“this tick may need to be (re)applied”**, regardless of whether a previous attempt completed. Domain updates are designed to be idempotent with respect to `tickId` and effect identity, so reapplying the same `tickId` after a crash or failover does not corrupt state. The `pending` key is created **without a TTL** so it survives primary crashes and failover; it is only removed when the tick has been successfully applied or explicitly abandoned.

On successful completion, the same Lua script that releases locks also deletes `tick:{tenantId}:{regionId}:pending` so there is no follow-up work for that tick on the next cycle.

This **single `pending` per region** rule is a **hard architectural constraint** for the current design:

- The TickScheduler in the Game Session Service treats a region as **busy** while `tick:{tenantId}:{regionId}:pending` exists for its current `tickId` and does not start a new tick for that `{tenantId, regionId}` until the entry is removed.
- There is intentionally **no support** for overlapping ticks, pipelines, or speculative pre-staging of the next tick within the same region keyspace. Any future change that requires concurrent ticks in a region must first extend this Redis model (for example with multiple `pending` slots and a different locking scheme) and update the canonical Lua scripts accordingly.
- Implementations must not bypass this constraint by creating ad-hoc `pending`-like keys or alternative staging flows; doing so would undermine the replay and idempotency guarantees documented here.

#### Idempotency Contract with Domain Services

Redis-based replay and crash recovery depend on a **shared, explicit idempotency pattern** in domain services:

- **Scope – which operations are in play**
  - Any handler that is invoked as part of tick execution and **persists state outside Redis** (for example PostgreSQL rows, durable queues, or external side effects) is considered **tick-driven** and must obey this contract.
  - Examples include: HP changes, inventory moves, room occupancy changes, quest progress, cooldown/application of effects, and any other mutation driven by commands read from `tick:{tenantId}:{regionId}:queue:{entityId}`.
  - Read-only queries and best-effort observability (metrics, logs) are exempt; they may be “at least once” without an idempotency guard.
  - Flows that cannot be made idempotent or compensatable at the domain layer (for example, billing events, emails, webhooks to third-party systems) **must not** be wired directly into tick scripts; they must use stronger patterns such as outbox tables and sagas as described in [Transaction Strategies](./system-architecture-transactions.md).

- Each domain service that applies tick-driven effects (for example Entity Management, World Management, Social Groups) maintains an **idempotency guard** keyed by a composite such as `(tenantId, regionId, tickId, effectKey)`:
  - `tickId` is the monotonically increasing identifier from `tick:{tenantId}:{regionId}:pending`.
  - `effectKey` uniquely identifies a logical effect within that tick from the domain service’s perspective (for example, `"entity:{entityId}:hp"` or `"inventory:{containerId}"`).
- When handling a tick-driven request, the service:
  - Starts a local database transaction.
  - Checks or inserts the corresponding idempotency guard row identified by `(tenantId, regionId, tickId, effectKey)`.
  - Applies the effect only if the guard row is newly created; if the guard already exists, the handler treats the call as a **replay** and returns the same logical outcome without re-applying state changes.
  - Commits both the guard and the state changes atomically.
- Services must not rely on “best-effort” checks (for example, reading state and inferring whether an effect has already been applied) in place of this explicit guard; idempotency must be enforceable by a single, durable key per effect.
To keep this contract enforceable across services and teams, FireMUD treats it as a **platform-level API**, not a per-service convention:

- A shared **Idempotency Guard library** (a common code module) defines:
  - The canonical idempotency table schemas and indexes (for example `tick_effect_guard` with `(tenantId, regionId, tickId, effectKey)`).
  - Helper APIs such as `recordEffectIfNew(...)` / `hasEffectBeenApplied(...)` that encapsulate the “insert-or-detect-replay” logic.
  - Standard metrics (`tick.effects_applied_total`, `tick.effects_duplicate_suppressed_total`) and log fields that domain services emit when applying or suppressing effects.
- Domain services that participate in tick-driven mutations:
  - Must depend on this shared library rather than implementing their own ad-hoc guards.
  - Must implement contract tests that:
    - Apply the same tick/effect combination twice and assert that database state changes only once.
    - Apply ticks out of order (for example, `tickId+1` then `tickId`) and assert that guards prevent corruption.
- Static checks and code review guidance reinforce this requirement:
  - New tick handlers are not considered complete unless they use the shared idempotency APIs for every domain mutation they perform.
  - PR templates and review checklists explicitly call out:
    - “Tick-driven handler declares its idempotency key (`tickId` + `effectKey` or per-aggregate `last_tick_id`).”
    - “Idempotency guard implemented via shared library” for any tick-driven feature.
    - “No non-idempotent external side effects (billing, email, third-party calls) are performed directly inside tick-driven handlers.”

This pattern ensures that:

- Replaying `tickId` with the same staged payload is safe even if some effects were applied before a crash and others were not.
- Adding a new tick-driven handler in a domain service always comes with a concrete, verifiable idempotency guard, rather than ad-hoc logic that could diverge across services.

The Tick System design describes this contract from the scheduler’s perspective; the Redis design captures the requirement so that any change to domain idempotency patterns is evaluated against the replay guarantees that depend on it and must be reflected in the shared library and tests.

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

> 🔁 Ticks are **replayable and idempotent** due to Lua-based staging, lock control, and AOF-backed durability for volatile state. Recovery guarantees focus on **not applying tick effects more than once** and on preserving region leadership rules, not on preventing all forms of tick loss under extreme failure windows.
> 🔗 See [Tick System and Runtime Design](./system-architecture-ticks.md#tick-execution-and-redis-integration) for the canonical three-phase tick commit pattern, failure scenarios, and recovery behavior.

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
  - Replication lag and replica health metrics (for example, `master_link_status`, replica offset/lag gauges, or `redis.critical_replication_issues`) to detect replication problems affecting coordination workloads.
  - Basic connection health via the `redis.up` gauge exposed in
    `DatabaseAutoConfiguration`
- Metrics are scraped via a [`redis-exporter`](../../k8s/monitoring/redis-exporter.yaml) deployment
  (deployable via the instructions in [`k8s/README.md`](../../k8s/README.md))
- **Grafana dashboards**:
  - Visualize tick throughput, Lua runtimes, lock contention, and stuck `pending` entries.
  - Provide **separate panels** for coordination key prefixes (`tick:*`, `session:*`, timers, locks) versus cache and rate-limit prefixes so cache latency or misses cannot mask problems with tick coordination.
  - Highlight trends in blocked clients, replication issues, and eviction events to show when cache or rate-limit activity begins to interfere with coordination workloads.
- **Prometheus Alertmanager** sends alerts if metrics exceed thresholds
  - Alerts include thresholds on Redis latency percentiles for tick-related commands (for example, p95/p99 of Lua script runtimes) and on eviction rates so operators can detect when caches or rate limiting begin to impact coordination workloads.
- **Graceful degradation** logic reduces gameplay interruption if Redis temporarily stalls
- Redis is the primary volatile coordination and cache layer. Services do not introduce competing in-memory cache technologies, but deployments may run **separate Redis clusters or logical instances** for coordination vs caching/rate limiting to protect tick latency.
- Local debugging tools such as the Redis CLI and RedisInsight are described in
  [Developer Setup](../../DEVELOPER_SETUP.md#redis-debugging)

> 🔗 Redis observability feeds into the common stack described in [Logging & Monitoring](./system-architecture-logging-monitoring.md)

---

### Graceful Degradation Modes and Alert Thresholds

Redis outages or sustained high latency on the **coordination cluster** are treated as explicit failure modes with well-defined behavior for ticks, locks, leases, and sessions.

**Health states for Coordination Redis**

- **Healthy:** Redis latency and error rates are within normal bounds.
  - Game Session and other services process commands and ticks normally.
  - Observability dashboards show p95/p99 Lua runtimes comfortably below the 5–10 ms SLO, low blocked-client counts, and zero coordination-key evictions.

- **Degraded (partial impact):** Latency or errors are elevated but Redis is still reachable, or only a subset of shards/regions are affected.
  - Example triggers (exact values tuned per environment):
    - p99 Lua runtime for tick-related scripts exceeds ~10–20 ms for several minutes.
    - Blocked-client counts or blocked time on coordination nodes spike above a small configured threshold.
    - Replication lag or coordination OOM errors are observed repeatedly without total outage.
  - Behavior:
    - Game Session slows down affected regions by reducing per-tick fan-out and/or slightly increasing tick intervals (as described in the Lock TTL section).
    - Regions that consistently exceed lock TTL headroom or experience repeated lock/lease errors are marked **degraded**; new gameplay commands into those regions may be rejected with “region under load” errors instead of being enqueued.
    - Read-only queries and administrative flows that do not depend on coordination Redis (for example DB-backed status views) may remain available where practical, but tick progression and command intake are prioritized over optional features.

- **Unavailable / Fail-closed (cluster or shard outage):** Coordination Redis is unreachable or returning pervasive errors for one or more shards.
  - Example triggers:
    - `redis.up` gauge reports down for the coordination cluster or for the shard that owns a given `{tenantId, regionId}` hash tag.
    - High error rates for basic commands (for example `GET`/`SET` on tick/session keys) over a short window.
  - Behavior for affected regions:
    - Game Session **hard-fails new gameplay commands** that require ticks, returning a clear “service unavailable” style error for those regions.
    - It **freezes tick scheduling** for affected `{tenantId, regionId}` pairs rather than attempting to buffer commands in memory or continue without coordination guarantees.
    - Existing locks and leases are treated as **lost** for scheduling purposes; the scheduler does not assume they survived the outage and simply waits for Redis to return before attempting further work in those regions.
  - Behavior for unaffected regions:
    - Regions whose hash tags map to healthy shards may continue processing ticks, as long as global SLOs (latency, error rates) remain acceptable.
  - In all cases:
    - The system does not attempt to run ad-hoc in-memory fallbacks for ticks, locks, or sessions; correctness takes precedence over partial gameplay.
    - Operators are alerted via high-severity alerts so they can restore Redis; gameplay for affected regions resumes only once Coordination Redis is healthy again.

**Lock, lease, and session behavior on reconnect**

When Coordination Redis recovers after an outage or severe degradation, tick executors and session flows:

- Rely solely on surviving Redis keys and PostgreSQL idempotency guards to decide what work needs replay.
- Do **not** attempt to “resume” in-flight locks, leases, or sessions based on in-memory state alone.
- Treat missing `pending` or session keys as “lost coordination state” rather than trying to reconstruct it locally.

Runbooks in [System Architecture: Runbooks](./system-architecture-runbooks.md) describe concrete recovery steps, health checks, and alert-driven actions for these scenarios, including how to interpret metrics and when to advance `tickId`, reacquire leases, or require a fresh `LOGIN`.

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
- Metrics such as “reconnect attempts missing session key but targeting a running game instance” surface misconfiguration or Redis instability so operators can adjust TTLs or investigate state loss. Operators can use these metrics to spot patterns such as:
  - Occasional isolated reconnects missing a session key (expected when TTLs expire naturally).
  - Clusters of reconnect attempts across many tenants or regions that suddenly lack session keys (often a sign of Redis instability, misconfigured TTLs, or an operational incident) and warrant investigation.

All session bind, rebind, and takeover flows are performed via a **Lua compare-and-set script** that operates on a **versioned value schema**:

- The value stored under `session:{tenantId}:{sessionId}` is a structured payload that includes at least:
  - A `schemaVersion` field (for example, `1`, `2`), which allows the script and Java code to evolve the shape of the value over time.
  - A `generation` counter used to detect stale rebind attempts.
  - The current binding information (for example, `socketId`, transport metadata, and any additional session attributes).
- The CAS script:
  - Reads the current binding for `session:{tenantId}:{sessionId}`.
  - Verifies that the binding is still compatible with the requested operation (for example, same session attempting a rebind, or an authorized takeover flow) and that the `generation`/`schemaVersion` values are understood.
  - Applies the new binding atomically (updating binding fields, incrementing the `generation` counter, and preserving or migrating any schema-versioned fields as needed).
  - Optionally emits structured metadata for audit and debugging (for example, `previousSocketId`, `newSocketId`, `reason`, `oldSchemaVersion`, `newSchemaVersion`).

Value schema versioning and backward compatibility are treated as explicit contracts:

- New fields in the session payload must be added in a backward-compatible way:
  - Existing scripts and services must treat missing fields as “unset” rather than failing.
  - Defaulting and optionality rules are documented alongside the schema.
- Removing or renaming fields requires a deliberate migration plan:
  - Lua scripts are updated **first** to understand both the old and new `schemaVersion` values (for example, reading old fields and writing new ones while continuing to handle existing payloads safely).
  - Once those scripts are deployed cluster‑wide, services that create or update session keys are updated next to start writing the new `schemaVersion` and payload shape.
  - Background migrations or dual-read/dual-write strategies may be used to converge the keyspace onto the new schema.
- The CAS script treats unknown or unsupported `schemaVersion` values conservatively:
  - It refuses to modify the value (returning an explicit `UNSUPPORTED_SCHEMA_VERSION` outcome) rather than making best-effort guesses.
  - It logs and surfaces metrics (for example, `session.cas_unsupported_schema_total{schemaVersion=...}`) so operators can detect out-of-date services or partially rolled-out schema changes. In normal operation with the “scripts first, writers second” rollout rule, this outcome is treated as a **deployment mismatch signal**, not a steady-state condition.

Clients never update session keys directly with plain `SET`; they always go through this Lua-based compare-and-set helper to avoid races where two clients attempt to bind to the same session concurrently and to ensure that versioned schema rules are consistently enforced. The generation/version counters in the value allow the Game Session Service to reason about which binding is the latest and to detect out-of-order rebind attempts.

To make conflict handling and reconnect behavior predictable:

- The CAS helper exposes explicit outcomes for callers (for example, `OK`, `STALE_GENERATION`, `CONFLICTING_BINDING`, `UNSUPPORTED_SCHEMA_VERSION`, `TAKEOVER_APPLIED`), rather than a generic success/failure flag.
- When **two servers race** to bind the same session:
  - Both may read the same `generation` value initially, but only the first CAS to succeed increments `generation` and updates the binding.
  - The second CAS sees the updated `generation` (or a different binding) when it re-reads state inside the script and returns a `STALE_GENERATION` or `CONFLICTING_BINDING` outcome without modifying the key.
  - Callers interpret these outcomes as “this reconnect attempt lost a race” and can respond by:
    - Prompting the client to retry, or
    - Informing the client that another connection has taken over the session, depending on the operation type.
- Takeover flows (for example, a deliberate “log in from another device” or an admin-enforced disconnect) follow a dedicated path:
  - The caller passes explicit takeover intent and, where applicable, authenticated identity or token information.
  - The CAS script uses a deterministic rule to decide whether a takeover is allowed (for example, only when initiated by an authenticated principal or a newer auth token).
  - If allowed, the script updates the binding and increments `generation`, returning `TAKEOVER_APPLIED`. If not allowed, it leaves the binding unchanged and returns a conflict outcome.

Metrics such as `session.cas_conflict_total` or more granular counters by outcome help operators and developers understand how often reconnect races or takeovers occur and whether behavior matches expectations over time, without imposing any fixed availability or uptime requirements.

When `UNSUPPORTED_SCHEMA_VERSION` appears in metrics or logs, runbooks treat it as:

- A sign that session schema or deployment versions are out of sync (for example, services writing a newer `schemaVersion` than the CAS script supports, or an incomplete rollback).
- A trigger to:
  - Align deployments so all Game Session Service instances run scripts that understand the highest `schemaVersion` currently in use, and
  - Optionally run a **session schema cleanup** tool for specific tenants that scans `session:{tenantId}:*` keys for unknown versions and deletes or aggressively expires those entries. Because Redis sessions are non-authoritative and bounded by `FIREMUD_AUTH_SESSION_EXPIRATION_MS`, removing unknown-version sessions is acceptable; affected players simply need to perform a fresh `LOGIN`.

### Session Schema Cleanup and Large Keyspaces

Session schema cleanup is a **hygiene and recovery tool**, not a required part of normal operation:

- The primary safety mechanisms for sessions are:
  - TTL-based expiry governed by `FIREMUD_AUTH_SESSION_EXPIRATION_MS`.
  - The CAS script’s conservative behavior for unknown `schemaVersion` values (no mutation + explicit `UNSUPPORTED_SCHEMA_VERSION` outcome and metrics).
- In steady state, it is acceptable to:
  - Rely on TTL for natural drainage of older or unknown-version sessions.
  - Treat occasional `UNSUPPORTED_SCHEMA_VERSION` results as “deployment mismatch” signals that prompt rollout fixes rather than continuous keyspace scrubbing.

When runbooks call for explicit cleanup (for example, after a major schema change or to address a large number of unknown-version sessions in a specific tenant), cleanup tools must be designed for **large keyspaces**:

- Scope:
  - Operate on a **per-tenant prefix** such as `session:{tenantId}:*`; avoid scanning the entire Redis keyspace when only some tenants are affected.
  - Prefer targeted selectors (for example, `session:{tenantId}:*` with filters inside the tool) over global `SCAN` patterns.
- Bounded SCAN usage:
  - Use Redis `SCAN` with modest `COUNT` values (for example, 100–1000) to avoid long blocking periods.
  - Enforce a maximum runtime per invocation (for example, 10–30 seconds) and exit cleanly when the time budget is exhausted; subsequent runs resume from the last cursor or continuation token.
  - Insert small delays between batches when running in continuous jobs so scans do not monopolize CPU or I/O on the Redis node.
- Observability:
  - Emit metrics such as `session.cleanup_scanned_total`, `session.cleanup_deleted_total`, and `session.cleanup_duration_seconds` to capture how much work the cleaner performs and its impact.
  - Log tenant identifiers and approximate key counts so operators can correlate cleanup activity with changes in memory usage and `UNSUPPORTED_SCHEMA_VERSION` metrics.

Default runbooks should prefer **fixing deployments** (aligning scripts and writers) and relying on TTL over running aggressive cleanup jobs. Session cleanup tools remain available for exceptional cases where operators explicitly choose to trade short-lived overhead for faster convergence of session keyspace to a new schema.

> 🔐 Key formats are internal and subject to change. Services treat Redis as a coordination layer, not a persistent or public contract.

## Future Cache Design and Versioned Aggregates

Coordination Redis (ticks, locks, timers, sessions) is the focus of this document. Future use of Redis for read-side caching and rate limiting—including cacheable object types, version-based validation, invalidation strategies, memory/eviction rules, and workload separation—is described in [System Architecture: Redis Cache & Rate Limiting](./system-architecture-redis-cache.md).

## Related Documentation

- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [System Architecture: Redis Cache & Rate Limiting](./system-architecture-redis-cache.md)
- [FireMUD Redis Lua Patterns](./system-architecture-redis-lua-patterns.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Transaction Strategies](./system-architecture-transactions.md)

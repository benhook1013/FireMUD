# Coordination Redis Ops Access & Tooling

This document expands on the access control and operational guardrails described in `system-architecture-redis.md`. It focuses on how human operators and maintenance tooling are allowed to interact with **coordination prefixes** and how those tools participate in the [Coordination Reset Model](./system-architecture-redis-reset-and-recovery.md#coordination-reset-model).

## Implementation Notes

The target control-plane and CLI contract in this document is ahead of the currently shipped runtime surface:

- Game Session currently ships `PauseTicksForScope` / `ResumeTicksForScope` plus `GetRuntimeOwnershipStatus` on the control-plane gRPC surface.
- The live implementation supports the current `{tenantId, gameInstanceId}` queue boundary; `region_id` is present in the proto contract but is currently rejected by the service implementation.
- The documented `GetRegionTickStatus(scope)` surface and the `coordination-maintenance ...` CLI verbs remain the intended target-state operator contract, not a description of fully implemented repo-local tooling today.

Use this doc as the canonical target-state contract for later reset/replay tooling, but do not assume every verb below is already available in the running codebase.

## Default Operator Surface

- Using the **read-only ops user** for inspection and the **application user** (via supported tooling) for any coordination writes.
- Running coordination maintenance exclusively through the **versioned maintenance CLI** and its documented commands.

Defining additional Redis users, ACL variations, or ad-hoc tools is considered **advanced** and should be avoided unless existing roles and tooling are clearly insufficient for a documented operational requirement.

## Coordination Redis Access Rules

- Coordination Redis is treated as an **application-only write surface**:
  - All writes to coordination prefixes (`tick:*`, `retry:*`, `timer:*`, `remote:*`, `session:*`, `tick-executor-lease:*`, and related keys) go through registered Lua scripts and key-builder helpers in `firemud-common`.
  - Application services (Game Session, Automation & Scripting, and any future tick participants) never bypass those helpers with raw Redis commands.
- Human operators and ad-hoc tools:
  - May use `redis-cli`, RedisInsight, or similar tools with **read-only ops users** to inspect coordination state.
  - Must not issue raw `EVAL`/`EVALSHA`, `SET`, `DEL`, or TTL-changing commands against coordination prefixes in normal operation.

These rules keep the script registry’s invariants and hash-tag discipline meaningful by ensuring there is only one code path for mutating coordination keys.

## Ops User vs Application User

Redis ACLs enforce a clear split between application and operations clients:

- Application user (for example `coord_app`):
  - Used only by application services and shared maintenance tools that import `firemud-common` helpers.
  - Permitted to execute `EVALSHA`/`SCRIPT LOAD` and write commands on coordination databases.
  - Not used from interactive shells or general-purpose admin tooling in production.
- Read-only ops user (for example `coord_ops_ro`):
  - Used by human operators and generic tools.
  - Restricted to read-only capabilities; explicitly denied `EVAL`, `EVALSHA`, `SCRIPT LOAD`, and write commands for coordination deployments.
  - Must not rely on `@read` alone: many essential diagnostics commands are not in `@read` and need explicit allowlisting. The recommended baseline is `@read` plus an incident-response allowlist that remains strictly non-mutating, for example:
    - Keyspace-safe inspection: `+scan +sscan +hscan +zscan +type +ttl +pttl +exists`
    - Latency/health diagnostics: `+info +slowlog|get +slowlog|len +latency|latest +latency|doctor`
    - Client/memory diagnostics (read-only subcommands only): `+client|list +client|id +memory|usage +memory|stats`
    - Cluster topology visibility (read-only subcommands only): `+cluster|info +cluster|nodes`

In addition, coordination deployments must ensure that **configuration-changing commands** (such as `CONFIG *`, `SLAVEOF`/`REPLICAOF`, `CLUSTER MEET`/`ADDSLOTS`/`DELSLOTS`/resharding operations, and `SHUTDOWN`) are reserved for infrastructure automation or dedicated admin roles, not everyday ops users:

- Standard read-only ops users (`coord_ops_ro`) must **not** have access to configuration commands in production; they focus solely on inspection.
- Any tooling that legitimately needs configuration access (for example, Kubernetes operators or controlled maintenance jobs) must:
  - Use a distinct, tightly scoped Redis user.
  - Be treated as part of the infrastructure control plane, not general incident response.

Other Redis roles (for example cache/rate-limit clients) connect to separate deployments or logical databases that do not contain coordination prefixes.

### Redis ACL Roles Overview

To keep ACL usage consistent across services and documentation, Redis deployments should expose a small, shared set of ACL users and map them to service roles:

| ACL User (example) | Intended Role | Typical Consumers |
| --- | --- | --- |
| `coord_app` | Coordination application client – may write and run Lua scripts against **Coordination Redis** only | Game Session Service, Automation & Scripting Service, shared coordination maintenance CLI |
| `coord_ops_ro` | Read-only coordination ops user – may inspect coordination keys but never write or run Lua on them | Human operators using `redis-cli`/RedisInsight, monitoring/exporter agents for Coordination Redis |
| `cache_app` | Cache/Rate-Limit application client – may read/write **only** cache/rate-limit prefixes on Cache/Rate-Limit Redis | Spring Cloud Gateway, Entity Management, World Management, Social & Groups, Game Session (for `view:room-look:*`), Automation & Scripting (for `automation:queue:{tenantInstanceTag}:*` / `automation:quota:*`) |
| `cache_ops_ro` | Read-only cache ops user – may inspect cache/rate-limit keys but never write to them | Human operators inspecting Cache/Rate-Limit Redis, cache-focused monitoring/exporters |

Per-service READMEs are expected to state which ACL user(s) each service uses and which Redis role(s) it connects to so configuration drift is easy to detect during reviews. CI and configuration checks should ensure that:

- Services that only participate in coordination (for example Game Session) never use cache ACL users.
- Services that only use Cache/Rate-Limit Redis (for example Spring Cloud Gateway) never use coordination ACL users.
- No service is configured with an ACL user that can read/write both coordination and cache prefixes on the same deployment.

## Configuration and Redis Role Selection

All tools and services refer to Redis deployments via **role-specific configuration**, not hard-coded URLs:

- Coordination clients and ops tools read connection settings from `FIREMUD_REDIS_COORD_HOST` / `FIREMUD_REDIS_COORD_PORT` (or an equivalent `FIREMUD_REDIS_COORD_URL`), which identify the **Coordination Redis** deployment.
- Cache/rate-limit clients and tools read from `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT` (or `FIREMUD_REDIS_CACHE_URL`), which identify the **Cache/Rate-Limit Redis** deployment.

A small shared configuration module (in `firemud-common` or a dedicated tooling library) exposes **typed configs and helpers** such as:

- `RedisCoordConfig` + `createCoordinationRedisClient(...)`
- `RedisCacheConfig` + `createCacheRedisClient(...)`

All ops scripts and maintenance tools must:

- Accept an explicit `RedisCoordConfig` when they touch coordination prefixes.
- Accept an explicit `RedisCacheConfig` when they operate only on cache/rate-limit prefixes.
- Never construct Redis host/port or URLs by hand.

This makes the target Redis role part of the tool’s type signature and configuration, reducing the chance that coordination tooling accidentally points at Cache Redis (or vice versa).

Some health checks and observability tools legitimately need to talk to **both** roles in a single process (for example, a composite “Redis health” check or a diagnostic CLI). These multi-role tools must:

- Accept both `RedisCoordConfig` and `RedisCacheConfig` explicitly and label logs/metrics with a `redis_role` tag (for example `coordination` vs `cache`) so misconfigurations are easy to spot.
- Avoid sharing Redis client instances between roles; each role gets its own client configuration and connection pool.
- Keep any write operations role-specific and minimal; cross-role flows (for example, verifying that a prefix truly lives only on Cache Redis) should be implemented as read-only checks, not cross-writing scripts.

## Supported Maintenance Tooling

Operators interact with coordination state through **supported tools**, not raw Redis commands:

- A small “coordination maintenance client” or CLI (implemented in the codebase, not ad-hoc scripts) provides:
  - High-level operations such as “inspect region”, “dump pending”, “list locks for entity”, “trigger scoped reset”.
  - Guarantees that all keys and scripts are invoked via the shared descriptors and registry.
- Runbooks and Helm hooks:
  - Call into this maintenance tooling for any write operation on coordination prefixes.
  - Use raw Redis commands only for:
    - Node-level operations such as `FLUSHALL`/AOF reset during a coordinated reset (already covered by the Redis Operations doc).
    - Read-only inspection via the ops user.

### Canonical Control-Plane and CLI Contract

To keep reset/replay behavior implementation-safe, the maintenance/tooling surface is not left to per-runbook invention. The first implementation must expose one canonical control-plane contract, whether that is delivered as a CLI, an admin API, or both:

- Required control-plane operations:
  - `PauseTicks(scope)`
  - `ResumeTicks(scope)`
  - `GetRegionTickStatus(scope)`
  - `RunScopedCoordinationReset(scope)`
  - `ReconcileTickLedger(scope, oldRegionEpoch)`
  - `ConvergeCommandRecords(scope, oldRegionEpoch)`
  - `InitializeRegionMeta(scope, regionEpoch, currentTickId, currentTickState, currentTickTerminalAtMs)`
  - `RebindRegionSessions(scope, regionEpoch)`
  - `RunPostResetSmokeCheck(scope)`
- Required CLI verbs:
  - `coordination-maintenance pause`
  - `coordination-maintenance status`
  - `coordination-maintenance reset`
  - `coordination-maintenance reconcile-ledger`
  - `coordination-maintenance converge-commands`
  - `coordination-maintenance init-meta`
  - `coordination-maintenance rebind-sessions`
  - `coordination-maintenance smoke-check`
  - `coordination-maintenance resume`
- Scope grammar:
  - `--scope region --tenant <tenantId> --region <regionId>`
  - `--scope tenant --tenant <tenantId>`
  - `--scope cluster`
- Scope inventory source:
  - The authoritative affected-region set comes from the durable Game Session control/status store, not Redis key enumeration.
  - The first fully region-scoped implementation must use a PostgreSQL-backed `RegionStatus` or equivalent runtime ownership table as the inventory source for every tenant and cluster operation.
  - The affected-region snapshot is taken after `pause` blocks new command intake, batch allocation, and region creation for the selected scope; later-created regions are rejected or queued until the maintenance operation completes.
  - Tenant scope includes every active, paused, degraded, stalled, or draining region owned by that tenant at the inventory snapshot.
  - Cluster scope includes every active, paused, degraded, stalled, or draining region assigned to the Coordination Redis deployment at the inventory snapshot.
  - Redis `SCAN` is used only to enumerate keys for deletion/inspection after the durable scope has been established; it must not decide which regions exist.
  - Commands that auto-discover epoch maps must derive them from the same durable affected-region snapshot and emit that snapshot in audit output.
- Required argument contract:
  - `coordination-maintenance pause`
    - accepts only the scope grammar above.
    - blocks until the scope reaches the control-plane `PAUSED` state or exits non-zero on timeout/failure.
  - `coordination-maintenance status`
    - accepts the scope grammar above.
    - returns the control-plane status payload defined below for every affected region.
  - `coordination-maintenance reset`
    - accepts the scope grammar above.
    - accepts `--preserve-sessions` / `--invalidate-sessions` where session policy allows an operator choice.
    - never infers session invalidation from scope alone when the design says it is optional.
    - is the canonical operator entrypoint that performs and audits the mandatory PostgreSQL `region_epoch` bump before clearing Redis coordination state for the selected scope.
    - must emit the resulting bumped epoch per affected region in its audit output so downstream reconcile/init-meta steps consume one authoritative old/new epoch record.
  - `coordination-maintenance reconcile-ledger`
    - accepts the scope grammar above.
    - accepts either `--old-region-epoch <epoch>` for `--scope region` or `--old-region-epoch-map <path>` for tenant/cluster scopes.
    - is the canonical operator entrypoint for `replay_first` convergence as well as old-epoch reset convergence:
      - without an epoch bump, it drives in-epoch `SCHEDULED` ledger rows toward `APPLIED` or `ABANDONED` for the selected current-epoch scope.
      - after an epoch bump, it drives old-epoch rows toward terminal reset outcomes for the selected reset scope.
    - may support `--discover-old-epochs` as an implementation convenience, but only if it resolves epochs from PostgreSQL and emits the discovered map in its audit output.
  - `coordination-maintenance converge-commands`
    - accepts the same epoch arguments and discovery behavior as `reconcile-ledger`.
    - remains a distinct command in first implementation:
      - operators run `reconcile-ledger` first and `converge-commands` second when both effect-ledger and command-status convergence are required.
      - a future combined verb is intentionally out of scope for this contract unless the canonical CLI section is updated.
  - `coordination-maintenance init-meta`
    - accepts the scope grammar above.
    - accepts either `--region-epoch <epoch> --current-tick-id <tickId>` for `--scope region` or `--region-epoch-map <path> --current-tick-id <tickId>` for tenant/cluster scopes.
  - `coordination-maintenance smoke-check`
    - accepts the scope grammar above.
    - for tenant/cluster scopes, accepts an optional explicit sample-set argument; otherwise the tool must auto-select one representative region per affected executor/shard group and print which regions were sampled.
  - `coordination-maintenance resume`
    - accepts only the scope grammar above.
    - exits non-zero unless the scope currently satisfies the resume gate: reset complete, old-epoch ledger converged, command convergence complete, and smoke check passing.
- Required execution rule:
  - The CLI subcommands above are the only supported write-path entrypoints for coordinated reset/recovery flows. Helm hooks, Jobs, and admin dashboards call these verbs rather than re-encoding reset logic themselves.
- Epoch-bump ownership rule:
  - `RunScopedCoordinationReset(scope)` / `coordination-maintenance reset` is the canonical owner of the PostgreSQL `region_epoch` bump for reset and restore flows.
  - No separate runbook-only or ad hoc SQL step is allowed to silently bump `region_epoch` out of band from that reset operation.
  - Backup restore automation and reset runbooks must record the epoch bump evidence emitted by this operation rather than inventing a second audit trail.
- Required version rule:
  - The CLI and control-plane implementation must ship from the same build/version set as the services and Lua registry they operate on. Mixed-version reset orchestration is unsupported.

Canonical epoch-map examples:

```yaml
# old-region-epoch-map.yaml
regions:
  - tenantId: T1
    regionId: R7
    oldRegionEpoch: 12
  - tenantId: T1
    regionId: R8
    oldRegionEpoch: 4
```

```yaml
# region-epoch-map.yaml
regions:
  - tenantId: T1
    regionId: R7
    regionEpoch: 13
  - tenantId: T1
    regionId: R8
    regionEpoch: 5
currentTickId: -1
```

```yaml
# cluster-region-epoch-map.yaml
regions:
  - tenantId: T1
    regionId: R7
    regionEpoch: 13
  - tenantId: T1
    regionId: R8
    regionEpoch: 5
  - tenantId: T2
    regionId: R2
    regionEpoch: 21
currentTickId: -1
```

Minimum audit output for any command that auto-discovers or consumes an epoch map must include the resolved `<tenantId, regionId, oldRegionEpoch|regionEpoch>` tuples so operators can verify exactly which timeline coordinates were acted on.

### Pause/Status/Resume State Contract

`PauseTicks`, `GetRegionTickStatus`, and `ResumeTicks` are the control-plane safety boundary for all reset, failover-recovery, and topology-change flows. First implementation must expose one shared state model rather than per-runbook interpretations.

- Canonical per-region states:
  - `RUNNING`
  - `PAUSING`
  - `PAUSED`
  - `RESETTING`
  - `DEGRADED`
  - `STALLED`
- `PauseTicks(scope)` required behavior:
  - Reject new gameplay command intake for the scope before returning `PAUSED`.
  - Prevent new durable tick-batch allocation for the scope before returning `PAUSED`.
  - Wait for any in-flight executor work in the scope to drain, fail, or lose lease so no executor can create new coordination state under the old epoch.
- `GetRegionTickStatus(scope)` minimum fields per affected region:
  - `tenantId`
  - `regionId`
  - `status`
  - `pauseRequested`
  - `commandIntakeBlocked`
  - `batchAllocationBlocked`
  - `activeExecutorCount`
  - `inFlightBatchCount`
  - `currentRegionEpoch`
  - `lastCommittedTickId`
  - `lastSmokeCheckAt` and `lastSmokeCheckResult` when applicable
- Authoritative `PAUSED` pass criteria for a region:
  - `commandIntakeBlocked = true`
  - `batchAllocationBlocked = true`
  - `activeExecutorCount = 0`
  - `inFlightBatchCount = 0`
  - no control-plane path remains that can create new durable tick batches or new coordination keys for that region under the pre-pause epoch
- Scope-level `PAUSED` rule:
  - region scope: the target region satisfies the pass criteria above.
  - tenant scope: every region owned by the tenant satisfies the pass criteria above.
  - cluster scope: every active region on the deployment satisfies the pass criteria above.
- `ResumeTicks(scope)` required behavior:
  - Refuse to resume any region that has not passed the canonical post-reset resume gate.
  - Transition regions back to `RUNNING` only after the reset workflow has completed for the scope.

Jobs, wrappers, and dashboards may present this state differently, but they must all consume this same underlying contract and must not invent alternate quiescence criteria.

`RunPostResetSmokeCheck(scope)` minimum assertions:

| Check | Required pass criteria |
| --- | --- |
| Lease | A region lease for every sampled region in the scope can be acquired and renewed without stale-epoch or lock-conflict errors that persist beyond normal retry budget. |
| Redis metadata baseline | `tick:{tenantRegionTag}:meta` exists or is created during the smoke run with the expected `region_epoch` and baseline `current_tick_id` for the sampled region. |
| Batch allocation | The smoke tick allocates exactly one durable batch for the sampled `(tenantId, regionId, region_epoch, tickId)` and records the expected lease/fencing token. |
| Redis staging | The smoke tick stages at least one no-op or synthetic smoke-test effect into `pending`, and `pending` correlates back to the durable `tick_batch_id`. |
| Ledger convergence | The staged smoke effect reaches a terminal ledger outcome (`APPLIED` or explicit smoke-test `ABANDONED`) without leaving `SCHEDULED` rows stranded. |
| Cleanup | `pending` is cleared, per-region locks are released, and the region is no longer considered in-flight after the smoke tick completes. |
| Durable advancement | Durable commit/cleanup counters advance as expected and no inconsistent-state alert or duplicate-batch condition is raised. |
| Scope sampling | For tenant- or cluster-scoped resets, the smoke check samples at least one representative region per affected executor/shard group rather than only one global region. |

Runbooks may compose these verbs, but they must not invent alternate write paths or omit required steps such as command-record convergence.

The table above is the canonical post-reset verification checklist. Other runbooks should reference this checklist directly rather than restating a partial subset of assertions in different words.

Direct `redis-cli` writes to coordination prefixes are reserved for **break-glass scenarios** and must follow the incident guidelines in `system-architecture-redis.md` (auditing, post-incident reset, and verification). As an additional guardrail:

- Any break-glass write that mutates `tick:*`, `timer:*`, `retry:*`, `remote:*`, `session:*`, or `tick-executor-lease:*` must be followed by a reset/cleanup scope that actually covers the mutated prefix before normal tick processing resumes:
  - For region-scoped families (`tick:*`, `timer:*`, `retry:*`, `tick-executor-lease:*`), run a region- or tenant-scoped coordination reset as appropriate.
  - For tenant-scoped `remote:*`, run a tenant-scoped reset or an explicit tenant-scoped `remote:<tenantId>:*` cleanup workflow (with audit trail), not a region-only reset.
  - For session prefixes, follow session reset policy (region resets preserve `session:game:*` and current `sessionctx:*` bootstrap/session-context keys by default; tenant resets always invalidate `session:auth:*` and preserve gameplay/session-context keys only when an explicit `--preserve-sessions` option is invoked; cluster resets invalidate both by default).
- Operators must treat such writes as equivalent to “coordination state may be inconsistent” and use the Coordination Reset Model to bring the region/tenant/cluster back to a known-good state, rather than leaving ad-hoc edits in place as a permanent fix.
- Break-glass flows should go through a small wrapper (CLI or Logging & Admin action) that:
  - Executes the minimal required Redis mutation.
  - Immediately triggers the appropriate scoped coordination reset.
  - Emits a structured audit event (for example `coordination_break_glass`) recording:
    - A unique event identifier and timestamp.
    - The affected tenants/regions and reset scope (region/tenant/cluster).
    - The operator or automation identity that initiated the change.
    - The Redis role and deployment (for example `coordination`, cluster name, node ID).
    - A free-form reason string describing why break-glass was used.
- After the scoped reset completes, operators run the standard post-reset health checks (for example, verifying that core Lua scripts load successfully and that sample ticks can schedule and commit for the affected regions) before unpausing ticks. Larger, more formal deployments may additionally link these audit events to external incident tracking systems, but hobby and self-hosted setups can rely on the built-in audit log alone.

### Tooling Maintenance and Versioning

The coordination maintenance client/CLI is treated as a first-class part of the system, not an ad-hoc script:

- It lives in the same repository and modules as:
  - The shared key builders and Lua Script Registry descriptors (`firemud-common`).
  - The integration tests that exercise script behavior and key shapes.
- It is **versioned alongside the main services**; there is no separate, free-floating versioning scheme for tooling.
- Any change to coordination key formats or Lua script contracts must:
  - Update the shared descriptors and key-builder helpers.
  - Update the maintenance CLI code that uses those helpers.
  - Extend or adjust the shared integration tests so both services and tooling are validated against the same expectations.

This ensures that operators use the same abstractions as application code and reduces the risk that maintenance tools silently drift away from the main coordination design.

## Discovery and Version Discipline

- The coordination maintenance CLI is shipped as part of the normal build/release pipeline under the canonical command name `coordination-maintenance`; environment packaging may wrap that command in a Gradle task, container entrypoint, or `dev-tools/` script, but runbooks and Helm hooks should reference the canonical command/verb names above so operators do not need to guess how to invoke it.
- Operators must only use a CLI version that matches the deployed services and Lua registry:
  - If the CLI build version does **not** match the image tag or Git commit used for the running deployment, do not attempt coordination recovery actions; instead, run the CLI from the same artifact version that produced the deployment or perform a coordinated upgrade.
  - Break-glass or manual `redis-cli` operations are not an acceptable substitute for a mismatched maintenance CLI; they still require a scoped coordination reset afterwards and should be treated as incident-only paths.

Runbooks that reference coordination recovery or resets should always call the maintenance CLI entrypoint explicitly and avoid embedding raw Redis commands.

## Static Checks for Ops Scripts

To keep operational scripts aligned with application code:

- Repository-wide checks are extended to cover:
  - `dev-tools/` and other maintenance directories.
  - Helm hooks and Kubernetes jobs that interact with Redis.
- CI fails when:
  - Ops scripts contain raw `EVAL`/`EVALSHA` against coordination deployments.
  - Scripts construct `tick:*`, `timer:*`, `retry:*`, `remote:*`, or `session:*` keys by hand instead of calling shared helpers.
  - Scripts hard-code Redis host/port or URLs instead of using the shared `RedisCoordConfig` / `RedisCacheConfig` helpers and role-specific environment variables.
  - Cache/rate-limit scripts introduce Redis prefixes that are not listed in the Cache/Rate-Limit Redis key catalog maintained in the Redis cache design docs (Redis cheat sheet plus `system-architecture-redis-cache.md`), or misuse those prefixes against the wrong Redis role.
  - Automation-related Lua or tooling scripts reference both `automation:*` and `tick:*` prefixes in a single operation, violating the automation cluster slotting rules in `system-architecture-redis-lua-patterns.md` and the Automation & Scripting service design.

Maintenance scripts that genuinely need to work with coordination keys must:

- Import the same key-builder APIs and Lua registry helpers used by services.
- Document their scope (which prefixes/tenants/regions they touch) and the runbook they implement.

This keeps human-driven maintenance and automation under the same discipline as regular application code, reducing the chance that debugging or emergency fixes introduce silent hash-tag or lock/lease violations.

In addition, scripts and runbooks are labelled by **target Redis role**:

- “Coordination ops” scripts may only use the coordination config and helpers; CI fails if they import cache-only helpers or reference cache URLs.
- Cache/rate-limit tooling may only use the cache config; CI fails if it imports coordination helpers.

This role-aware labelling keeps coordination and cache tooling clearly separated in both code and configuration.

For reset and migration procedures tied to these roles, see [Redis Operations & Migrations](./system-architecture-redis-operations.md), which defines the versioned coordination reset flows and AOF maintenance commands that ops users are expected to run.

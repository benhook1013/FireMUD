# Coordination Redis Ops Access & Tooling

This document expands on the access control and operational guardrails described in `system-architecture-redis.md`. It focuses on how human operators and maintenance tooling are allowed to interact with **coordination prefixes** and how those tools participate in the [Coordination Reset Model](./system-architecture-redis-reset-and-recovery.md#coordination-reset-model).

## Implementation Status

The target control-plane and maintenance contract in this document is ahead of the currently shipped runtime surface:

- Game Session currently ships `PauseTicksForScope` / `ResumeTicksForScope` plus `GetRuntimeOwnershipStatus` on the control-plane gRPC surface.
- The live implementation supports the current `{tenantId, gameInstanceId}` queue boundary; `region_id` is present in the proto contract but is currently rejected by the service implementation.
- The bounded `coordination-maintenance ...` public surface remains target state, not a description of fully implemented repo-local tooling today. Recovery phases may be implemented and tested behind the high-level operation without becoming separate public commands.

Use this doc as the canonical target-state contract for later reset/replay tooling, but do not assume every operation below is already available in the running codebase.

## Default Operator Surface

- Using the **read-only ops user** for inspection and the **application user** (via supported tooling) for any coordination writes.
- Running coordination maintenance exclusively through the **versioned supported maintenance tooling** and its documented commands.

Defining additional Redis users, ACL variations, or ad-hoc tools is considered **advanced** and should be avoided unless existing roles and tooling are clearly insufficient for a documented operational requirement.

## Coordination Redis Access Rules

- Coordination Redis is treated as an **application-only write surface**:
  - All writes to coordination prefixes (`tick:*`, `retry:*`, `timer:*`, `remote:*`, `session:*`, `tick-executor-lease:*`, and related keys) go through owned typed key and mutation helpers in `firemud-common`. Registered Lua scripts are required when an atomic multi-key mutation needs them, not for every ordinary single-key operation.
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
| `cache_app` | Cache/Rate-Limit application client – may read/write **only** cache/rate-limit prefixes on Cache/Rate-Limit Redis | Spring Cloud Gateway, Entity Management, World Management, Social & Groups, Game Session (for `view:room-look:*`), Automation & Scripting (for `automation:queue:{tenantInstanceTag}:*` / `automation:quota:*` / `automation:tenant-budget:*` / `automation:test:capacity:*`) |
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

To keep reset/replay behavior implementation-safe, the maintenance/tooling surface is not left to per-runbook invention. Its initial supported public contract is deliberately small, whether delivered as a CLI, an admin API, or both:

- `status(scope, operationId)` reports the canonical state, affected inventory, and recovery progress.
- `recover(scope, sessionPolicy, policy, operation)` starts the one public recovery/reset operation and returns its durable `operationId` and `maintenanceLockToken`. Its canonical CLI mapping is `coordination-maintenance recover --mode <policy> --scope <scope> (--preserve-sessions|--invalidate-sessions) [--operation <operation>] ...`: `policy` maps to `--mode` (`replay-first`, `reset`, or `session-schema-cleanup`), `sessionPolicy` maps explicitly to exactly one preserve/invalidate choice and is never inferred from scope, `scope` maps to `--scope` plus its scope arguments, and `operation` maps to the optional `--operation` maintenance compatibility class (for example `migration`), never to an internal phase or generated `operationId`. Its internal pause-and-lock phase fences new work and acquires the maintenance lock; after the post-reset smoke check it remains fenced in `ready_to_reopen` until public continuation.
- `session-schema-cleanup` is a tenant-only `recover` mode: request validation accepts only `--scope tenant --tenant <tenantId>` and rejects `--scope region` or `--scope cluster` before creating an operation record, acquiring the maintenance lock, pausing a scope, or starting workflow phases.
- `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` resumes that same operation after a controller restart or an external infrastructure step. Its CLI mapping is `coordination-maintenance continue-recovery --operation-id <operationId> --expected-phase <expectedPhase> --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>`. The release-boundary invocation uses canonical `expectedPhase=ready_to_reopen`, compare-and-sets the expected durable phase, and may reconcile only into `AWAITING_RESUME`; it cannot select or bypass an internal phase, is not the post-recovery `resume` safety gate, and does not accept a new scope argument.
- `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` is the safety gate for an existing recovery operation in `AWAITING_RESUME`. It uses canonical `expectedPhase=awaiting_resume`, resolves and validates the same operation's recorded scope, lock, authenticated actor, and immutable evidence reference, persists the audit result, and atomically records `RESUME_AUTHORIZED` only after the selected recovery, cleanup, Account projection, replay-domain, and smoke gates pass while retaining the recovery lock and traffic fence; it cannot create a pause, release the recovery lock, reopen traffic, or create a new operation identity.
- `releaseMaintenanceLock(operationId, scope, maintenanceLockToken, reason, evidenceRef)` is an audited exceptional operation and does not imply that the scope is safe to resume.

The high-level recovery operation internally owns durable epoch handling, Account authority-projection rebuild, ledger and command convergence, Redis clearing, metadata initialization, session invalidation or rebinding, post-reset verification, and the final atomic success release. Its operation record lives in a durable control store outside the target Redis deployment and records the scope inventory, current and expected phase, phase evidence, lock identity, and terminal status. Internal phases may expose APIs for orchestration, resumability, and focused proof, but they are not public operator verbs. After the selected workflow's recovery, projection, cleanup, and smoke proofs, the operation atomically records `ready_to_reopen` while retaining its lock and fence. Public `continueRecovery(... expectedPhase=ready_to_reopen ...)` advances that same operation to `AWAITING_RESUME`; public `resume` records `RESUME_AUTHORIZED`; only the internal final phase may then reopen traffic, release the lock, and record `SUCCEEDED`.

The Account projection-rebuild phase explicitly includes every affected issued-token projection at `session:auth:token:<tokenHash>` in addition to issuer, account, tenant, and membership generation projections. Recovery must verify exact-token state before protected admission or representative-region smoke can proceed.

The tool advertises and accepts only scope forms implemented and proved by the runtime. Unsupported region, tenant, or cluster scope must be rejected explicitly. A wider scope becomes supported only when its authoritative durable affected-region inventory, pause fencing, recovery ordering, audit output, and resume gate have end-to-end proof.

- Required internal control-plane operations:
  - `PauseTicks(operationId, scope, maintenanceLockToken)`
  - `ResumeTicks(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`; the controller derives scope from the durable operation record
  - `GetRegionTickStatus(scope, operationId)`
  - `RunScopedCoordinationReset(operationId, scope, maintenanceLockToken)`
  - `ReconcileTickLedger(operationId, scope, maintenanceLockToken, oldRegionEpoch | oldRegionEpochMap)`
  - `ConvergeCommandRecords(operationId, scope, maintenanceLockToken, oldRegionEpoch | oldRegionEpochMap)`
  - `InitializeRegionMeta(operationId, scope, maintenanceLockToken, regionEpoch | regionEpochMap, currentTickId, currentTickState, currentTickTerminalAtMs)`
  - `RebindRegionSessions(operationId, scope, maintenanceLockToken, regionEpoch | regionEpochMap)`
  - `RebuildAccountAuthorityAndIssuedTokenProjections(operationId, scope, maintenanceLockToken)`
  - `RunPostResetSmokeCheck(operationId, scope, maintenanceLockToken)`
- The public CLI surface is the high-level `recover`, `status`, `continueRecovery`, `resume`, and audited `release-lock` contract defined above; the detailed operations listed here are internal phases rather than separate public verbs.
- Scope grammar:
  - `--scope region --tenant <tenantId> --game-instance <gameInstanceId> --region <regionId>`
  - `--scope tenant --tenant <tenantId>`
  - `--scope cluster`
- Scope exceptions:
  - The shared Gateway replay domain (`gateway:connect-token:jti:<jti>` and `replayAdmissionFence`) is intentionally not tenant- or region-tagged and is not modified by region- or tenant-scoped coordination resets. Only a Coordination Redis replay-continuity loss trigger, including a cluster-domain reset that invalidates the shared replay state, may apply the gameplay-connect quarantine of 30 seconds plus two configured clock-skew intervals.
  - Region- and tenant-scoped coordination resets preserve Account-owned `session:auth:token:<tokenHash>` records and the shared Gateway replay domain. A cluster-scoped reset must invalidate those records as part of the documented Account repair/reset cutover and replay-readiness recovery; protected traffic remains closed until that cutover and the required re-registration/reauthentication complete. This policy is independent of `--preserve-sessions`.
- Maintenance-lock token contract:
  - `maintenanceLockToken` is accepted only as the server-issued capability described in [`system-architecture-redis-operations.md`](./system-architecture-redis-operations.md); it is not a caller-supplied assertion of operation, environment, scope, or operator identity.
  - CLI invocations must read the plaintext token through protected stdin, a file descriptor, or a permissioned `0600` token file, shown below as `--maintenance-lock-token-file <permissioned-token-file>`; the token must never be a command-line value or appear in shell history, process listings, logs, URLs, or evidence.
  - Every internal mutating phase after lock acquisition must present the returned `operationId` and `maintenanceLockToken` with the authenticated operator principal. The control plane must resolve the token against the durable active operation record and validate the environment/deployment boundary, operation, scope, compatibility class, operator, expiry, and current phase before mutating state.
  - Reuse of a token is limited to the same active operation's durable retry/phase records. Duplicate requests return their recorded result without repeating external effects; stale, expired, terminal, or mismatched requests fail closed.
  - Durable state, audit records, metrics, status responses, and ordinary operator listings retain only a one-way token digest or opaque lock reference. The plaintext `maintenanceLockToken` may be returned only in the protected issue response to the authenticated actor that created or was explicitly authorized for the operation, over the protected control-plane channel; it must not appear in logs, shell transcripts, tickets, evidence exports, URLs, or general status output. A lost plaintext token cannot be reconstructed from the digest or opaque reference.
- Scope inventory source:
  - The authoritative affected-region set comes from the durable Game Session control/status store, not Redis key enumeration.
  - The first fully region-scoped implementation must use a PostgreSQL-backed `RegionStatus` or equivalent runtime ownership table as the inventory source for every tenant and cluster operation.
  - The selected scope's region-creation fence is installed in durable controller state before the final affected-region snapshot. Region creation admission and the fence use one durable transaction or equivalent CAS on a monotonic scope generation: a creation that wins before the fence is committed is included in the snapshot, while a fence that wins first rejects or queues the creation. The final snapshot records that generation and the immutable region inventory; no region can be created under the fenced generation and then be omitted from the operation.
  - The affected-region snapshot is taken only after the recover operation's internal pause-and-lock phase blocks new command intake and batch allocation and the creation fence is committed; later-created regions are rejected or queued until the maintenance operation completes.
  - Tenant scope includes every active, paused, degraded, stalled, or draining region owned by that tenant at the inventory snapshot.
  - Cluster scope includes every active, paused, degraded, stalled, or draining region assigned to the Coordination Redis deployment at the inventory snapshot.
  - Redis `SCAN` is used only to enumerate keys for deletion/inspection after the durable scope has been established; it must not decide which regions exist.
  - Commands that auto-discover epoch maps must derive them from the same immutable affected-region snapshot and scope generation and emit both in audit output.
  - Epoch arguments are scope-dependent and use one typed contract across the control plane and CLI: region scope accepts scalar `oldRegionEpoch`/`regionEpoch`; tenant and cluster scopes accept `oldRegionEpochMap`/`regionEpochMap` containing one entry for every region in the durable affected-region snapshot. `RunScopedCoordinationReset(operationId, scope, maintenanceLockToken)` does not accept a caller-supplied epoch; it must return the corresponding scalar old/new epoch evidence for region scope or complete old/new epoch maps for tenant/cluster scope, and all downstream reconcile, command-convergence, metadata-initialization, and session-rebind calls consume that exact evidence. A map must never be collapsed to one scalar, and a scalar must never be reused for multiple regions.
- Internal recovery phase contract:
  - The following detailed phase semantics apply inside the high-level `recover` workflow. Equivalent internal methods or resumable steps may implement them; names and option spellings shown here are descriptive and are not public compatibility requirements. References to scope mean one of the forms the runtime currently advertises as supported.
  - Internal pause-and-lock phase
    - accepts only an advertised supported scope.
    - consumes the recover request's optional `--operation <backup|restore|reset|migration|topology-change>` value as the canonical maintenance-lock compatibility class; `session-schema-cleanup` derives the internal `cleanup` compatibility class from `--mode` and does not accept a separate cleanup operation flag. `backup` is reserved for exceptional backup-related maintenance that actually pauses or mutates coordination state.
    - acquires the deployment maintenance lock for the recover workflow's multi-step restore, reset, cleanup, migration, topology-changing scaling, and exceptional backup-related maintenance work. Routine online PostgreSQL backup neither invokes recovery nor pauses ticks.
    - blocks until the scope reaches the control-plane `PAUSED` state or exits non-zero on timeout/failure.
    - must emit the `operationId`, server-issued `maintenanceLockToken`, immutable resolved affected-region inventory, and scope generation in audit output; later internal phases in the same workflow consume those values rather than reacquiring the lock independently.
  - `coordination-maintenance status` (public read-only control)
    - accepts an advertised supported scope.
    - returns the control-plane status payload defined below for every affected region.
  - Internal epoch-bump and coordination-reset phase
    - accepts an advertised supported scope.
    - consumes the `maintenanceLockToken` emitted by the recover operation's internal pause-and-lock phase.
    - consumes the recover request's explicit `--preserve-sessions` or `--invalidate-sessions` choice; it never infers session retention or invalidation from scope.
    - is the internal phase that performs and audits the mandatory PostgreSQL `region_epoch` bump before clearing Redis coordination state for the selected scope.
    - must emit the resulting bumped epoch per affected region in its audit output so later internal phases consume one authoritative old/new epoch record.
    - This option never controls the Account-owned issued-token registry: region- and tenant-scoped resets preserve `session:auth:token:<tokenHash>` records, while a cluster-scoped reset closes protected admission and invalidates them through the Account repair/reset cutover.
    - for tenant and cluster scopes, resolves every affected `gameInstanceId` from the durable affected-region snapshot, then enumerates and removes `remote:{tenantInstanceTag}:*` for each resolved instance using bounded `SCAN`/`UNLINK` batches; it must not attempt a tenant-only pattern.
  - Internal ledger-reconciliation phase
    - consumes the advertised supported scope selected by the recovery workflow and its maintenance lock token.
    - accepts either `--old-region-epoch <epoch>` for `--scope region` or `--old-region-epoch-map <path>` for tenant/cluster scopes.
    - owns `replay_first` convergence as well as old-epoch reset convergence:
      - without an epoch bump, it drives in-epoch `SCHEDULED` ledger rows toward `APPLIED` or `ABANDONED` for the selected current-epoch scope.
      - after an epoch bump, it drives old-epoch rows toward terminal reset outcomes for the selected reset scope.
    - may support `--discover-old-epochs` as an implementation convenience, but only if it resolves epochs from PostgreSQL and emits the discovered map in its audit output.
  - Internal command-convergence phase
    - consumes the same maintenance lock token as the prior internal phase.
    - accepts the same epoch arguments and discovery behavior as `reconcile-ledger`.
    - remains ordered after ledger reconciliation when both effect-ledger and command-status convergence are required, even when one high-level recovery operation invokes both.
  - Internal metadata-initialization phase
    - consumes the advertised supported scope selected by the recovery workflow.
    - consumes the same maintenance lock token as the prior internal phase.
    - accepts either `--region-epoch <epoch>` for `--scope region` or `--region-epoch-map <path>` for tenant/cluster scopes, together with `--current-tick-id <tickId>`, `--current-tick-state <STAGED|RESOLVING|APPLIED|ABANDONED>`, and `--current-tick-terminal-at-ms <epochMillis>`.
    - For reset initialization, `--current-tick-id` defaults to `-1`, `--current-tick-state` defaults to `APPLIED`, and `--current-tick-terminal-at-ms` defaults to the init-meta write time. A terminal timestamp is `NULL` for `STAGED` or `RESOLVING`; callers may override the reset defaults only with a state/timestamp combination accepted by the control-plane signature. The CLI passes these normalized values to `InitializeRegionMeta(scope, regionEpoch | regionEpochMap, currentTickId, currentTickState, currentTickTerminalAtMs)` and must not invent a separate default or argument shape.
  - Internal Account authority and issued-token projection-rebuild phase
    - runs for reset mode after the coordination reset and recorded session-policy selection, before any internal session rebind or post-reset smoke.
    - consumes the same `operationId`, advertised scope, and server-issued `maintenanceLockToken`; it does not acquire a second lock.
    - rebuilds and verifies the issuer, account, tenant, and membership generation projections from Account durable authority, plus the affected `session:auth:token:<tokenHash>` issued-token projections. Region- and tenant-scoped resets preserve Account-owned records but still re-project and validate them; cluster resets verify the Account repair/reset cutover that preceded physical cleanup, register replacement token projections, and prove exact-token validation before representative-region smoke. Protected admission remains closed through these projection and token gates.
    - emits immutable per-scope generation and exact-token projection evidence. Missing, stale, malformed, mismatched, or ambiguous projection state fails the workflow and keeps the scope fenced.
  - Internal session-rebind phase
    - accepts either `--region-epoch <epoch>` for `--scope region` or `--region-epoch-map <path>` for tenant/cluster scopes.
    - is permitted when the reset's explicitly recorded session policy is `--preserve-sessions`; every region, tenant, and cluster request must record either `--preserve-sessions` or `--invalidate-sessions` rather than relying on scope inference. It recreates region-authoritative bindings from the durable affected-session inventory and must refresh the same maintenance lock rather than acquiring another lock.
    - Before recreating a binding, it validates the complete canonical preserved-session rebind predicate: a complete target `schemaVersion=2` session payload with `rebindHandleEnvelope`, `continuityBindingExpiresAt`, an unexpired `resumeDeadline`, `membershipVersion`, and `membershipAuthorityGeneration`; an exact active, unrevoked, unexpired `session:auth:token:<tokenHash>` registry record matching token, account, profile, audience, and time identity; current account identity, entitlement, revocation, authority-freshness lease, and committed checkpoint; exact equality of every applicable authority tuple (`issuerAuthGeneration`, `accountAuthorityGeneration`, `tenantAuthorityGeneration`, caller-bound `{accountId, tenantId}` `membershipAuthorityGeneration`, and private-realm `grantVersion` when applicable); and the expected `binding_generation`, current operation/region epoch, and lease fence from durable owner state. `session:game:*` and `sessionctx:*` are not authority substitutes. A missing, stale, ambiguous, mismatched, or expired `resumeDeadline` value leaves the session connected but not gameplay-admitted and produces the terminal/non-applied `REGION_REBIND_REQUIRED` outcome until fresh `LOGIN` / `PLAY` succeeds.
    - A failed preserved-session predicate never implicitly changes the recorded session policy. The operation remains paused and fenced under the same `operationId` and `maintenanceLockToken`; an explicit audited preserve-to-invalidate transition may compare-and-set the policy under that same lock, recording actor, reason, and immutable evidence before invalidation. If that same-lock transition is unavailable, complete audited `release-lock` abandonment and start an explicit new `recover` operation with `--invalidate-sessions`. Rebind failure alone is never invalidation proof.
  - Internal session-schema-cleanup phase
    - is owned by the bounded high-level `recover` operation; `session-schema-cleanup` is a mode/internal phase, not a separate public operation or command. Its continuation, abort, and release behavior use the parent operation's durable identity and lock lifecycle.
    - accepts only `--mode session-schema-cleanup --scope tenant --tenant <tenantId>` in the first implementation. Request validation must reject `--scope region` and `--scope cluster` before acquiring the maintenance lock, creating an operation record, pausing a scope, or starting any workflow; broader cleanup scopes are out of contract until explicitly designed.
    - requires `--operation-id <operationId>` and a protected token input such as `--maintenance-lock-token-file <permissioned-token-file>` for continuation, abort, or release controls.
    - accepts `--dry-run`, `--batch-size <n>`, and `--max-runtime-seconds <n>`.
    - walks only the documented tenant-scoped gameplay-session and bootstrap/session-context families for that tenant, using bounded `SCAN` windows and `UNLINK` where applicable.
    - must emit immutable structured progress and completion evidence containing the parent `operationId`, tenant, prefixes visited, scanned count, deleted count, final cursor or continuation state, schema disposition, concrete abort/completion reason, and the evidence reference bound to the operation. The cursor or continuation state is durable operation-owned state; callers do not supply a resume token.
    - must fail closed when Redis latency/health exceeds the documented cleanup budget, when the maintenance lock is lost, or when the workflow encounters an unsupported schema family outside the explicit cleanup contract.
  - Internal post-reset smoke-check phase
    - consumes the advertised supported scope selected by the recovery workflow.
    - consumes the same maintenance lock token as the prior internal phase.
    - for tenant/cluster scopes, accepts an optional explicit sample-set argument; otherwise the tool must auto-select one representative region per affected executor/shard group and print which regions were sampled. Sampling occurs only after the Account projection and exact-token gates pass, while protected admission remains closed.
  - `coordination-maintenance resume` (public safety control)
    - derives the operation-owned scope from `--operation-id` and rejects an operation whose recorded scope is not advertised as supported. The public API and CLI accept no scope selectors for resume.
    - requires `--operation-id <operationId>`, `--expected-phase <expectedPhase>`, a protected token input such as `--maintenance-lock-token-file <permissioned-token-file>`, and `--evidence-ref <evidenceRef>`, all matching the active durable workflow. The control resolves the operation-owned scope; callers do not supply scope selectors to resume.
    - resolves the presented token against the active operation and validates the authenticated actor, deployment boundary, operation, expected phase, scope, mode, compatibility class, expiry, and immutable evidence reference before any mutation. A mismatch, missing evidence, stale phase, lost lock, or incomplete gate fails closed and leaves the scope fenced.
    - exits non-zero unless the durable operation is exactly `AWAITING_RESUME` and the scope satisfies the selected workflow's resume gate: reset complete, Account issuer/account/tenant/membership generation and issued-token projection rebuild evidence complete, replay-domain quarantine/fence and durable consume proof complete where applicable, old-epoch ledger converged, command convergence complete, a passing smoke check, and session rebinding when the effective policy preserved gameplay sessions; `session-schema-cleanup` additionally requires immutable completion evidence for the exact tenant, prefixes, scan/delete counts, final cursor or continuation state, schema disposition, and completion reason; replay-first requires in-epoch ledger and command convergence without an epoch bump and a passing replay budget/status check.
    - durably audits the matching operation tuple, authenticated actor, evidence reference, gate result, and pre-resume fence state, then atomically records `RESUME_AUTHORIZED` while retaining the recovery lock and traffic fence. The recover operation's internal success-release phase is the only transition that may then reopen traffic, release the lock, and record terminal `SUCCEEDED`.
  - `coordination-maintenance release-lock` (public audited failure control)
    - requires `--operation-id <operationId>`, the matching scope, a protected token input such as `--maintenance-lock-token-file <permissioned-token-file>`, `--reason <reason>`, and `--evidence-ref <evidenceRef>`.
    - is the canonical failure or operator-abort control for releasing the deployment maintenance lock when a recover workflow stops before its internal success-release phase.
    - rejects any request whose operation identity, token, scope, mode, or compatibility class does not match the active workflow and emits structured audit output recording the partial workflow state, actor, release reason, and whether the scope remains paused.
- Required execution rule:
  - The bounded high-level maintenance operations are the only supported write-path entrypoints for coordinated reset/recovery flows. Helm hooks, Jobs, and admin dashboards call them rather than re-encoding reset logic or directly invoking internal recovery phases.
- Epoch-bump ownership rule:
  - `RunScopedCoordinationReset(operationId, scope, maintenanceLockToken)` is the internal canonical owner of the PostgreSQL `region_epoch` bump for reset and restore flows.
  - No separate runbook-only or ad hoc SQL step is allowed to silently bump `region_epoch` out of band from that reset operation.
  - Backup restore automation and reset runbooks must record the epoch bump evidence emitted by this operation rather than inventing a second audit trail.
- Required version rule:
  - The CLI and control-plane implementation must ship from the same build/version set as the services and Lua registry they operate on. Mixed-version reset orchestration is unsupported.
- Maintenance-lock lifecycle rule:
  - One recover workflow owns one deployment maintenance lock until its internal success release or the separately audited `coordination-maintenance release-lock` abandonment control completes.
  - Later phases in that workflow refresh the lock TTL with the same `maintenanceLockToken`; they do not acquire independent locks. Controller restarts recover the token binding and expected phase from the external durable operation record rather than from target Redis.
  - A phase failure retains the lock and paused fence. The workflow remains resumable through `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)`; it never auto-releases merely because a process exited or an infrastructure step timed out.
  - If a phase loses the lock, every later mutation must fail closed until an operator explicitly restores the same fenced operation or abandons it through audited `release-lock`; abandonment does not authorize resume.
  - A `replay_first` workflow starts with compatibility class `cleanup`. Escalation to `reset_first` must atomically compare-and-match that same token and upgrade the class to `reset` without releasing or reacquiring the lock. The upgrade audit record, including scope, old/new class, token/workflow lineage, actor, reason, and resulting epoch transition, must be durable before the epoch bump or reset-key mutation is allowed.
  - If the same-token upgrade or its audit write cannot complete, the workflow remains paused and no reset mutation may proceed; the operator must use the explicit failure/abort path. A second lock cannot be used to bypass the failed upgrade.

### Canonical Pre-Wipe Gates

The external AOF/deployment reset handoff may execute only after the same durable recover operation and maintenance lock have passed these named internal evidence gates:

- `scope_paused_and_locked` – the scope is canonical `PAUSED`, command and batch intake are blocked, in-flight executor work is drained, and no old-epoch writer can create coordination state.
- `account_authority_token_cutover` – protected admission is closed and Account's durable authority/token identity cutover and required immutable evidence are complete for the operation's scope.
- `replay_domain_quarantine_fence` – the replay domain is verified untouched for a narrower reset or quarantined/fenced for the destructive reset, with immutable fence evidence recorded.
- `immutable_external_handoff_evidence` – old and intended new deployment identities, the fenced old endpoint, authorized operator/action/time, tooling digest, and independent replacement verification are recorded.

These names identify evidence groups, not additional public CLI verbs. Redis key absence, a new empty endpoint, or a caller-supplied scope cannot satisfy any gate, and every gate remains bound to the same `operationId` and server-issued `maintenanceLockToken`.

Canonical epoch-map examples:

```yaml
# old-region-epoch-map.yaml
regions:
  - tenantId: 7b3b074e-d597-4e9b-b96f-4f5946d26120
    gameInstanceId: 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78
    regionId: R7
    oldRegionEpoch: 12
  - tenantId: 7b3b074e-d597-4e9b-b96f-4f5946d26120
    gameInstanceId: 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78
    regionId: R8
    oldRegionEpoch: 4
```

```yaml
# region-epoch-map.yaml
regions:
  - tenantId: 7b3b074e-d597-4e9b-b96f-4f5946d26120
    gameInstanceId: 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78
    regionId: R7
    regionEpoch: 13
  - tenantId: 7b3b074e-d597-4e9b-b96f-4f5946d26120
    gameInstanceId: 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78
    regionId: R8
    regionEpoch: 5
currentTickId: -1
```

```yaml
# cluster-region-epoch-map.yaml
regions:
  - tenantId: 7b3b074e-d597-4e9b-b96f-4f5946d26120
    gameInstanceId: 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78
    regionId: R7
    regionEpoch: 13
  - tenantId: 7b3b074e-d597-4e9b-b96f-4f5946d26120
    gameInstanceId: 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78
    regionId: R8
    regionEpoch: 5
  - tenantId: 6c0bb04d-bdcb-45a4-a1bc-a7ee7432b461
    gameInstanceId: 2e3ee139-a6e8-44ad-b840-891b22c2255b
    regionId: R2
    regionEpoch: 21
currentTickId: -1
```

Minimum audit output for any command that auto-discovers or consumes an epoch map must include the resolved `<tenantId, gameInstanceId, regionId, oldRegionEpoch|regionEpoch>` tuples so operators can verify exactly which timeline coordinates were acted on.

### Pause/Status/Resume State Contract

The recover operation's internal `PauseTicks` phase, `GetRegionTickStatus`, and `ResumeTicks` are the control-plane safety boundary for all reset, failover-recovery, and topology-change flows. First implementation must expose one shared state model rather than per-runbook interpretations. `PauseTicks` is not a standalone public maintenance operation.

- Canonical per-region states:
  - `RUNNING`
  - `PAUSING`
  - `PAUSED`
  - `RESETTING`
  - `DEGRADED`
  - `STALLED`
- Internal `PauseTicks(operationId, scope, maintenanceLockToken)` phase required behavior:
  - Install the durable region-creation fence and scope generation before taking the final affected-region snapshot; the fence/CAS rule above defines which racing creation is included or deferred.
  - Reject new gameplay command intake for the scope before returning `PAUSED`.
  - Prevent new durable tick-batch allocation for the scope before returning `PAUSED`.
  - Wait for any in-flight executor work in the scope to drain, fail, or lose lease so no executor can create new coordination state under the old epoch.
- `GetRegionTickStatus(scope, operationId)` minimum fields per affected region:
  - `tenantId`
  - `gameInstanceId`
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
- `ResumeTicks(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` required behavior:
  - Reject requests that do not match the active operation, expected phase, operation-owned scope, immutable evidence reference, and maintenance lock; the operation record supplies the scope.
  - Resolve the server-issued token against the durable operation record, persist the authenticated actor and gate result in the operation audit, and keep the affected scope fenced until the internal success-release phase observes every release postcondition.
  - Refuse to resume any region that has not passed the canonical post-reset resume gate.
  - Transition regions back to `RUNNING` only after the reset workflow has completed for the scope.

Jobs, wrappers, and dashboards may present this state differently, but they must all consume this same underlying contract and must not invent alternate quiescence criteria.

`RunPostResetSmokeCheck(operationId, scope, maintenanceLockToken)` minimum assertions:

| Check | Required pass criteria |
| --- | --- |
| Lease | A region lease for every sampled region in the scope can be acquired and renewed without stale-epoch or lock-conflict errors that persist beyond normal retry budget. |
| Redis metadata baseline | `tick:{tenantRegionTag}:meta` exists or is created during the smoke run with the expected `region_epoch` and baseline `current_tick_id` for the sampled region. |
| Batch allocation | The smoke tick allocates exactly one durable batch for the sampled `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` and records the expected lease/fencing token. |
| Redis staging | The smoke tick stages at least one no-op or synthetic smoke-test effect into `pending`, and `pending` correlates back to the durable `tick_batch_id`. |
| Ledger convergence | The staged smoke effect reaches a terminal ledger outcome (`APPLIED` or explicit smoke-test `ABANDONED`) without leaving `SCHEDULED` rows stranded. |
| Cleanup | `pending` is cleared, per-region locks are released, and the region is no longer considered in-flight after the smoke tick completes. |
| Durable advancement | Durable commit/cleanup counters advance as expected and no inconsistent-state alert or duplicate-batch condition is raised. |
| Replay-domain readiness | When the workflow affects the shared replay domain, `replayAdmissionFence` is advanced, the lifetime-plus-skew quarantine is observed, and a disposable marker write has durable consume acknowledgement before the operation may reach `AWAITING_RESUME`. |
| Scope sampling | For tenant- or cluster-scoped resets, the smoke check samples at least one representative region per affected executor/shard group rather than only one global region. |

The high-level recovery workflow must compose these phases without inventing alternate write paths or omitting required steps such as command-record convergence.

The table above is the canonical post-reset verification checklist. Other runbooks should reference this checklist directly rather than restating a partial subset of assertions in different words.

Direct `redis-cli` writes to coordination prefixes are reserved for **break-glass scenarios** and must follow the incident guidelines in `system-architecture-redis.md` (auditing, post-incident reset, and verification). As an additional guardrail:

- Any break-glass write that mutates `tick:*`, `timer:*`, `retry:*`, `remote:*`, `session:*`, or `tick-executor-lease:*` must be followed by a reset/cleanup scope that actually covers the mutated prefix before normal tick processing resumes:
  - For region-scoped families (`tick:*`, `timer:*`, `retry:*`, `tick-executor-lease:*`), run a region- or tenant-scoped coordination reset as appropriate.
  - For instance-scoped `remote:{tenantInstanceTag}:*`, run a tenant-scoped reset so the tooling resolves every affected game instance and removes each instance pattern with an audit trail; do not use a region-only reset or invent a tenant-only pattern.
  - For gameplay session prefixes, follow the canonical reset-policy matrix: every region, tenant, and cluster reset must explicitly record either `--preserve-sessions` or `--invalidate-sessions`; the canonical region example uses `--preserve-sessions`, the canonical cluster example uses `--invalidate-sessions`, and preserved sessions require the rebind flow before admission. Account-owned `session:auth:token:<tokenHash>` records are a separate authority domain: region- and tenant-scoped resets preserve them, while a cluster reset must close protected admission and complete the Account repair/reset cutover before deleting them as cleanup and requiring re-registration. Preserved sessions must pass current identity, membership, and revocation validation before `rebind-sessions`; a generic region/tenant reset is never a repair for a token-registry mutation.
  - A manual mutation of `session:auth:token:<tokenHash>` is never repaired by a region- or tenant-scoped coordination reset. The operator must invoke Account-owned token repair/revocation and rotation tooling. If token custody, replay continuity, or the affected token set cannot be proven, the safe escalation is the documented cluster reset, replay-readiness quarantine, and forced re-registration/reauthentication; direct Redis edits are not a substitute for either path.
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

- The coordination maintenance CLI is shipped as part of the normal build/release pipeline under the canonical command name `coordination-maintenance`; environment packaging may wrap that command in a Gradle task, container entrypoint, or `dev-tools/` script, but runbooks and Helm hooks should reference the bounded public operation names above so operators do not need to guess how to invoke it.
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

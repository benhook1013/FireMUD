# Coordination Redis Ops Access & Tooling

This document expands on the access control and operational guardrails described in `system-architecture-redis.md`. It focuses on how human operators and maintenance tooling are allowed to interact with **coordination prefixes** and how those tools participate in the [Coordination Reset Model](./system-architecture-redis-reset-and-recovery.md#coordination-reset-model).

## Implementation Status

The target control-plane and maintenance contract in this document is ahead of the currently shipped runtime surface:

- Game Session currently ships `PauseTicksForScope` / `ResumeTicksForScope` plus `GetRuntimeOwnershipStatus` on the control-plane gRPC surface.
- The live implementation supports the current `{tenantId, gameInstanceId}` queue boundary; `regionId` is present in the proto contract but is currently rejected by the service implementation.
- The bounded `coordination-maintenance ...` public surface remains target state, not a description of fully implemented repo-local tooling today. No public recovery scope is currently implemented and proven; recovery phases may be implemented and tested behind the high-level operation without becoming separate public commands.

Use this doc as the canonical target-state contract for later reset/replay tooling, but do not assume every operation below is already available in the running codebase. Current operators, runbooks, Helm hooks, Jobs, and dashboards must not invoke the `coordination-maintenance` commands described below until that surface is implemented and end-to-end proven.

## Default Operator Surface

- Using the **read-only ops user** for inspection and the **application user** (via supported tooling) for any coordination writes.
- Target-state rule: once the versioned maintenance tooling is implemented and proven, run coordination maintenance exclusively through that supported tooling and its documented commands. This is not a current invocation path.

Defining additional Redis users, ACL variations, or ad-hoc tools is considered **advanced** and should be avoided unless existing roles and tooling are clearly insufficient for a documented operational requirement.

## Coordination Redis Access Rules

- Coordination Redis is treated as an **application-only write surface**:
  - All mutations to coordination prefixes (`tick:*`, `retry:*`, `timer:*`, `remote:*`, `session:*`, `tick-executor-lease:*`, and related keys) always go through owned typed key and mutation helpers in `firemud-common`. Lua is mandatory for atomic multi-key behavior and may be used for an explicitly documented single-key atomic guard or compare-and-set contract; every registered script is invoked through the same typed helper and registry path. Ordinary single-key mutations use the typed helpers without Lua.
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
  - Has the exact `coord_ops_ro/v2` allowlist, not a broad `@read` grant. The effective ACL contains only the explicitly approved non-mutating commands: `GET`, `MGET`, `HGET`, `HMGET`, `HGETALL`, `SMEMBERS`, `SCARD`, `ZRANGE`, `ZRANGEBYSCORE`, `ZCARD`, `XRANGE`, `XLEN`, `SSCAN`, `HSCAN`, `ZSCAN`, `TYPE`, `TTL`, `PTTL`, `EXISTS`, `SLOWLOG|LEN`, `CLIENT|ID`, `MEMORY|USAGE`, and `CLUSTER|INFO`. It must not include `CLIENT|LIST`, `SLOWLOG|GET`, `INFO`, `MEMORY|STATS`, `LATENCY|LATEST`, `LATENCY|DOCTOR`, or `CLUSTER|NODES`. No `@all`, `@read`, wildcard command grant, database-wide `SCAN`, or unreviewed subcommand is part of this contract.
  - The exact policy denies every command outside that allowlist, including keyspace-wide `SCAN`, all writes and mutating expiry commands (`SET*`, `DEL`, `UNLINK`, `HSET`, `SADD`, `ZADD`, `XADD`, `EXPIRE`, `PEXPIRE`, `EXPIREAT`, `PEXPIREAT`, and `PERSIST`), `EVAL`, `EVALSHA`, every `SCRIPT` subcommand including `SCRIPT LOAD`, every `CONFIG` subcommand including `CONFIG GET`, `CLIENT LIST`, `SLOWLOG GET`, `INFO`, `MEMORY STATS`, `LATENCY LATEST`, `LATENCY DOCTOR`, `CLUSTER NODES`, `REPLICAOF`/`SLAVEOF`, mutating `CLUSTER` subcommands, and `SHUTDOWN`. Redis ACL key patterns do not turn database-wide `SCAN` into prefix-filtered enumeration. Human and generic read-only tooling therefore uses known exact keys, container-local cursor commands, or an audited trusted prefix-scoped control-plane API; bounded recovery/deletion scans remain inside the authenticated maintenance application path and its durable scope/fence contract. Any needed server-wide telemetry is routed through an authenticated, filtered control-plane endpoint that is scoped to the authorized deployment/operation and does not expose shared-deployment data through Redis. ACL key patterns are read-only coordination patterns and do not grant access to unrelated deployments.
  - The policy identifier and digest (`coord_ops_ro/v2` plus the SHA-256 of its canonical sorted allow/deny command and key-pattern lists) are the expected deployment values. A verifier must prove positive probes for every allowed command and negative probes for database-wide `SCAN`, writes, mutating expiry, `EVAL`/`EVALSHA`, `SCRIPT LOAD`, `CONFIG`, `CLIENT LIST`, `SLOWLOG GET`, `INFO`, `MEMORY STATS`, `LATENCY LATEST`, `LATENCY DOCTOR`, and `CLUSTER NODES`; a caller assertion or a `@read` category check is insufficient.

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
| `coord_ops_ro` | Read-only coordination ops user – may inspect known coordination keys and container members but cannot enumerate the database, write, or run Lua | Human operators using `redis-cli`/RedisInsight, monitoring/exporter agents for Coordination Redis |
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

## Target-State Supported Maintenance Tooling

The target operator model interacts with coordination state through **supported tools**, not raw Redis commands. The tooling described in this section is not currently shipped, so current operators must not invoke its commands:

- Target state: a small “coordination maintenance client” or CLI (implemented in the codebase, not ad-hoc scripts) would expose only the bounded public contract below:
  - `inspect region` is represented by the public `status(scope, operationId)` projection; it is not a separate public verb.
  - `dump pending` and `list locks for entity` are internal-only diagnostic views, not public capabilities. Any needed evidence is returned through the canonical status/progress or operation audit surfaces.
  - `trigger scoped reset` maps to the public `recover(scope, sessionPolicy, mode=reset)` operation; it is not a separate public verb. Continuation, release authorization, and audited abandonment map to `continueRecovery`, `resume`, and `release-lock` respectively.
  - Guarantees that all keys and scripts are invoked via the shared descriptors and registry.
- Target-state integration rule, not a current invocation instruction: after this tooling is implemented and proven, runbooks and Helm hooks may call it for any write operation on coordination prefixes. Current runbooks and Helm hooks must not invoke the unavailable CLI. Raw Redis access is limited to:
  - Read-only inspection via the ops user.
  - The external infrastructure step of a bounded `recover` operation for node-level destructive actions such as `FLUSHALL` or an AOF reset. A node-level action must resolve its actual cluster blast radius as `cluster`, or carry fresh `physical-dedication-proof/v1` with an independent single-use challenge/nonce, expiry, trusted verifier, and exact `operationId`, `operationFence`, deployment identity, node identity/node set, and resolved region/tenant scope. Whole-deployment destructive actions, including `FLUSHALL` and AOF replacement, are allowed only for resolved `cluster` scope or under that exact proof or equivalent independently verified node isolation. Region- or tenant-scoped requests must be rejected before the external handoff when the proof is absent, expired, replayed, unverifiable, or mismatched; a logical scope label or caller assertion never proves physical dedication. Before either the external destructive handoff or startup, the controller must own the durable `operationId` and maintenance lock, have reached `scope_paused_and_locked`, completed the protected Account authority/token cutover and replay-domain quarantine/fence, retained immutable pre-wipe evidence bound to the exact deployment and scope, and create and durably record `replacementVerificationChallenge/v1` with source/target deployment identities, mode, nonce, expiry, and the same operation tuple. After startup, only the trusted deployment attestor may atomically consume that challenge for signed `post_reset_replacement_verification/v1`; `SAME_DEPLOYMENT`, including a fresh logical-database reset on the same deployment, requires both a new boot/startup generation and a new storage/AOF generation plus independent `postWipeEmptyStartupAttestation/v1`, while `REPLACEMENT_DEPLOYMENT` requires a distinct target deployment identity. No lesser same-deployment reset path is valid. The controller verifies the exact `coord_ops_ro/v2` and `coord_redis_config/v1` digests plus negative probes for database-wide `SCAN`, writes, `EVAL`, `SCRIPT LOAD`, `CONFIG`, `CLIENT LIST`, `SLOWLOG GET`, `INFO`, `MEMORY STATS`, `LATENCY LATEST`, `LATENCY DOCTOR`, and `CLUSTER NODES` before internal recovery continuation, `continueRecovery`, or protected admission can reopen.
  - Incident-only break-glass writes under the documented incident procedure, followed by the required scoped reset and verification; they are not normal-operation commands and cannot bypass the durable operation, maintenance-lock, cutover, evidence, or post-reset gates above.

### Canonical Control-Plane and CLI Contract

To keep reset/replay behavior implementation-safe, the target maintenance/tooling surface is not left to per-runbook invention. Its initial public contract is deliberately small, whether eventually delivered as a CLI, an admin API, or both; it is not currently available for operator invocation:

This recovery contract is role-bound to Coordination Redis and to the exact scope advertised and proved by that runtime. It never targets Cache/Rate-Limit Redis. Cache or rate-limit clearing uses separate owner-supported tooling bound to the Cache/Rate-Limit deployment and an exact registered prefix inventory; it cannot accept coordination region, tenant, or cluster scope or invoke gameplay epoch, session, Account-projection, or replay recovery. Any future mutating cache reset must independently prove its actor/workload authorization, deployment and prefix scope, immutable evidence, cache-maintenance exclusion lock, and apply/readback postconditions before it is advertised.

- `status(scope, operationId)` reports the canonical state, affected inventory, and recovery progress.
- `recover(scope, sessionPolicy, mode)` starts the one public recovery/reset operation. Its protected issuance response returns the durable `operationId` and plaintext `maintenanceLockToken` only to the authenticated actor or workload principal authorized for and durably bound to that operation. That principal must retain the one-time plaintext token in protected custody, such as a permissioned token file, protected workload secret, or secret manager, because the controller stores only its digest and cannot reconstruct it after restart. Its canonical CLI mapping is `coordination-maintenance recover --mode <mode> --scope <scope> <session-policy-option> ...`: `mode` is `replay-first`, `reset`, or `session-schema-cleanup`; exactly one of `--preserve-sessions` or `--invalidate-sessions` is required where the mode supports session policy, and the choice is never inferred from scope. `scope` maps to `--scope` plus its scope arguments. Maintenance compatibility is derived from the selected mode and any durable migration, topology-change, restore, or exceptional-backup contract already bound to the operation; it is not a caller-selectable public flag. `--mode replay-first` always persists the dedicated `compatibilityClass=replay-first`. Its internal pause-and-lock phase fences new work and acquires the maintenance lock; after the post-reset smoke check it remains fenced in `ready_to_reopen` until public continuation.
The no-mutation `--dry-run` exception is the only recover path that does not acquire or return a gameplay fence/maintenance lock: it performs discovery and validation only, produces no mutation or release effect, records `maintenanceLockApplicability=NOT_APPLICABLE`, omits `maintenanceLockToken`, and may finalize directly as `SUCCEEDED`. Every mutating recovery records `maintenanceLockApplicability=REQUIRED` and returns a non-empty server-issued token through the protected issuance response. Null, blank, caller-chosen, or sentinel token representations are invalid, and `continueRecovery` or `resume` may be called only for a `REQUIRED` operation. Any dry run that acquires a fence/lock or mutates state follows the ordinary lock, continuation, release, and finalization lifecycle.
- `session-schema-cleanup` is a tenant-only `recover` mode: request validation requires `--scope tenant --tenant <tenantId> --invalidate-sessions` and rejects a missing session-policy flag, `--preserve-sessions`, `--scope region`, or `--scope cluster` before creating an operation record, acquiring the maintenance lock, pausing a scope, or starting workflow phases.
- `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` resumes that same operation after a controller restart or an external infrastructure step. Its CLI mapping is `coordination-maintenance continue-recovery --operation-id <operationId> --expected-phase <expectedPhase> --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>`. The only release-boundary invocation uses canonical `expectedPhase=ready_to_reopen`, compare-and-sets the expected durable phase, and may advance only into `AWAITING_RESUME`; it cannot select or bypass an internal phase, is not the post-recovery `resume` safety gate, and does not accept a new scope argument.
- `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` is the safety gate for an existing recovery operation in `AWAITING_RESUME`. It uses canonical `expectedPhase=awaiting_resume`, resolves and validates the same operation's recorded scope, re-verifies the active maintenance-lock state (token digest, owner, expiry, fence, and phase), bound authenticated actor or workload principal, and every required immutable evidence reference, persists that principal and the audit result, and atomically records `RESUME_AUTHORIZED` only after the selected recovery, cleanup, Account projection, replay-domain, and smoke gates pass while retaining the recovery lock and traffic fence. Only after that durable authorization may the controller's internal release phase run; the public call cannot create a pause, release the recovery lock, reopen traffic, or create a new operation identity.
- `releaseMaintenanceLock(operationId, scope, maintenanceLockToken, reason, evidenceRef)` is an audited exceptional operation and does not imply that the scope is safe to resume.

Public `expectedPhase` inputs are limited to `ready_to_reopen` for `continueRecovery` and `awaiting_resume` for `resume`. `partial_release_reconciling` is persisted-only internal failure-reconciliation state; it is never a public input or CLI-selectable phase.

The high-level recovery operation internally owns durable epoch handling, Account authority-projection rebuild, ledger and command convergence, Redis clearing, metadata initialization, session invalidation or rebinding, post-reset verification, and the final atomic success release. Its operation record lives in a durable control store outside the target Redis deployment and records the scope inventory, current and expected phase, phase evidence, lock identity, and terminal status. Internal phases may expose APIs for orchestration, resumability, and focused proof, but they are not public operator verbs. After the selected workflow's recovery, projection, cleanup, and smoke proofs, the operation atomically records `ready_to_reopen` while retaining its lock and fence. Public `continueRecovery(... expectedPhase=ready_to_reopen ...)` advances that same operation to `AWAITING_RESUME`; public `resume(... expectedPhase=awaiting_resume ...)` records `RESUME_AUTHORIZED`; only the controller's internal `releasing -> finalized` phase may then apply and observe release effects. `SUCCEEDED` is a separate terminal operation status and may be recorded only after `finalized` and all applicable release postconditions. The narrow no-mutation dry-run exception may finalize directly as `SUCCEEDED` only when discovery/validation acquires neither a gameplay fence nor a maintenance lock and produces no mutation or release effect. A dry-run request that acquires either fence/lock or performs any mutation must use the normal continuation and release lifecycle and satisfy the ordinary finalization postconditions.

The Account projection-rebuild phase explicitly includes every affected issued-token projection at `session:auth:token:<tokenHash>` in addition to issuer, account, tenant, and membership generation projections. Recovery must verify exact-token state before protected admission or representative-region smoke can proceed.

#### Public Recovery Scope Status

The public CLI surface listed above is target state only; it is not currently implemented and proven, and the internal operations below do not authorize current operator invocation.

- No `coordination-maintenance recover` scope is currently implemented and proven. The shipped control-plane pause/status surface is narrower and supports the current `{tenantId, gameInstanceId}` queue boundary only; that support is not recovery proof.
- The target tool must advertise and accept only scope forms implemented and proved by the runtime. Unsupported region, tenant, or cluster recovery scope must be rejected explicitly. A wider scope becomes supported only when its authoritative durable affected-region inventory, pause fencing, recovery ordering, audit output, and public continuation/release gate have end-to-end proof.

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
- Target scope grammar (not current public support):
  - `--scope region --tenant <tenantId> --game-instance <gameInstanceId> --region <regionId>`
  - `--scope tenant --tenant <tenantId>`
  - `--scope cluster`
- Scope exceptions:
  - The shared Gateway replay domain (`gateway:connect-token:jti:<jti>` and `replayAdmissionFence`) is intentionally not tenant- or region-tagged and is not modified by region- or tenant-scoped coordination resets. Only a Coordination Redis replay-continuity loss trigger, including a cluster-domain reset that invalidates the shared replay state, may apply the gameplay-connect quarantine of 30 seconds plus two configured clock-skew intervals.
  - Region- and tenant-scoped coordination resets preserve Account-owned `session:auth:token:<tokenHash>` records and the shared Gateway replay domain. A cluster-scoped reset must invalidate those records as part of the documented Account repair/reset cutover and replay-readiness recovery; protected traffic remains closed until that cutover and the required re-registration/reauthentication complete. Cluster scope therefore requires explicit `--invalidate-sessions` and rejects `--preserve-sessions`.
- Maintenance-lock token contract:
  - `maintenanceLockToken` is accepted only as the server-issued capability described in [`system-architecture-redis-operations.md`](./system-architecture-redis-operations.md); it is not a caller-supplied assertion of operation, environment, scope, or operator identity.
  - CLI invocations must read the plaintext token through protected stdin, a file descriptor, or a permissioned `0600` token file, shown below as `--maintenance-lock-token-file <permissioned-token-file>`; the token must never be a command-line value or appear in shell history, process listings, logs, URLs, or evidence.
  - Every internal mutating phase after lock acquisition must present the returned `operationId` and `maintenanceLockToken` with the authenticated actor or workload principal bound to the operation. The control plane must resolve the token against the durable active operation record and validate the environment/deployment boundary, operation, scope, compatibility class, actor/workload binding, expiry, and current phase before mutating state.
  - Reuse of a token is limited to the same active operation's durable retry/phase records. Duplicate requests return their recorded result without repeating external effects; stale, expired, terminal, or mismatched requests fail closed.
  - Durable state, audit records, metrics, status responses, and ordinary operator listings retain only a one-way token digest or opaque lock reference. The plaintext `maintenanceLockToken` may be returned only in the protected issuance response to the authenticated actor or workload principal that created or was explicitly authorized for the operation, over the protected control-plane channel; it must not appear in logs, shell transcripts, tickets, evidence exports, URLs, or general status output. A lost plaintext token cannot be reconstructed from the digest or opaque reference.
  - After a controller restart, the operation remains fenced and no mutating phase resumes from the digest or opaque reference alone. An authenticated operator or workload principal explicitly authorized for the operation must resupply the original plaintext token through the protected continuation input; the controller hashes or otherwise verifies that supplied capability against the durable digest and exact operation tuple before mutation. A missing, lost, stale, expired, or mismatched token leaves the operation paused and requires the audited abandonment or incident path; the controller never reconstructs or reissues the plaintext token from durable state.
- Scope inventory source:
  - The authoritative affected-region set comes from the durable Game Session control/status store, not Redis key enumeration.
  - The first fully region-scoped implementation must use a PostgreSQL-backed `RegionStatus` or equivalent runtime ownership table as the inventory source for every tenant and cluster operation.
  - The selected scope's region-creation fence is installed in durable controller state before the final affected-region snapshot. Region creation admission and the fence use one durable transaction or equivalent CAS on a monotonic scope generation: a creation that wins before the fence is committed is included in the snapshot, while a fence that wins first rejects or queues the creation. The final snapshot records that generation and the immutable region inventory; no region can be created under the fenced generation and then be omitted from the operation.
  - The affected-region snapshot is taken only after the recover operation's internal pause-and-lock phase blocks new command intake and batch allocation and the creation fence is committed; later-created regions are rejected or queued until the maintenance operation completes.
  - Account-wide coverage must additionally acquire an account-scoped admission/creation fence, `accountAdmissionFence`, before capturing its immutable `inventorySnapshotRevision`. The fence blocks or queues new bindings for that exact account, is held through complete exact full-key readback and durable recording of the later `coverageGeneration`, and is released only after the account-wide coverage result is proven or the operation is explicitly abandoned; a missing, stale, or mismatched fence fails closed.
  - Tenant scope includes every active, paused, degraded, stalled, or draining region owned by that tenant at the inventory snapshot.
  - Cluster scope includes every active, paused, degraded, stalled, or draining region assigned to the Coordination Redis deployment at the inventory snapshot.
  - Redis `SCAN` is used only to enumerate keys for deletion/inspection after the durable scope has been established; it must not decide which regions exist.
  - Commands that auto-discover epoch maps must derive them from the same immutable affected-region snapshot and scope generation and emit both in audit output.
  - Epoch arguments are scope-dependent and use one typed contract across the control plane and CLI: region scope accepts scalar `oldRegionEpoch`/`regionEpoch`; tenant and cluster scopes accept `oldRegionEpochMap`/`regionEpochMap` containing one entry for every region in the durable affected-region snapshot. `RunScopedCoordinationReset(operationId, scope, maintenanceLockToken)` does not accept a caller-supplied epoch; it must return the corresponding scalar old/new epoch evidence for region scope or complete old/new epoch maps for tenant/cluster scope, and all downstream reconcile, command-convergence, metadata-initialization, and session-rebind calls consume that exact evidence. A map must never be collapsed to one scalar, and a scalar must never be reused for multiple regions.

### Canonical Active-Binding Recovery Evidence Contract

- Recovery uses the canonical Game Session-owned durable active-binding inventory, repair-obligation, snapshot, capacity, and coverage contract in [Redis Architecture](./system-architecture-redis.md#issuer-active-binding-index); this operations document does not redefine that contract.
- `NOT_APPLICABLE` is an exact typed union arm, not a nullable field value or string convention. The recovery carrier uses `RecoveryEvidenceField<T> = { kind: VALUE, value: T } | { kind: NOT_APPLICABLE }`, where `kind` is the closed enum `{VALUE, NOT_APPLICABLE}`. The persisted and wire representation of the non-applicable arm is exactly `{kind: "NOT_APPLICABLE"}`; null, omission, blank strings, `""`, `"N/A"`, `"NONE"`, numeric or boolean sentinels, arbitrary identifiers, and any unrecognized enum value are rejected before persistence, comparison, acknowledgement, or recovery. The shorthand `field=NOT_APPLICABLE` below means that exact tagged union arm.
- Rebind `membershipVersion` has one canonical carrier shape everywhere: `type MembershipVersionMap = { [tenantId: TenantId]: MembershipVersion }`, with runtime cardinality exactly one, the sole key equal to the binding's `tenantId`, and the value equal to the Account-issued version. The preserved `schemaVersion=2` payload, Account's opaque `rebindHandle` binding (shown only as Account-side evidence), the Game Session binding, rebind request/validation/readback, and every example must use this exact map. A scalar, null, omitted map, blank or differently keyed tenant, extra tenant, aggregate/latest value, or a map merged from separate carriers fails closed. Account's `GetTenantMembershipForRuntime` response remains scalar; only the persisted and cross-service gameplay carrier wraps that value in this exact one-tenant map, independently from `authorityTuple.membershipAuthorityGeneration`.
- Canonical rebind carrier example (the handle remains opaque; `handleBinding` is conceptual Account-side evidence):

  ```json
  {
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "preservedPayload": { "membershipVersion": { "11111111-1111-4111-8111-111111111111": 17 } },
    "handleBinding": { "membershipVersion": { "11111111-1111-4111-8111-111111111111": 17 } },
    "gameSessionBinding": { "membershipVersion": { "11111111-1111-4111-8111-111111111111": 17 } },
    "rebindValidation": { "membershipVersion": { "11111111-1111-4111-8111-111111111111": 17 } }
  }
  ```

  `membershipVersion: 17`, `membershipVersion: null`, and `membershipVersion: {"otherTenant": 17}` are rejected shapes, not alternate encodings.
  - Every active-binding recovery inventory proof, post-fence transition, request/digest, repair/readback result, acknowledgement, exact retry, parent-consumption record, and preserved-session rebind decision must persist and validate one complete recovery evidence carrier. For a preserved region/tenant parent and linked global child, that carrier contains `parentRecoveryOperationId`, `parentLifecycle`, the parent `operationFence`, a distinct `parentCoverageFence`, the linked child operation identity and current child lifecycle, the child operation fence, the child's separate `coverageFence`, the exact `inventorySnapshotRevision`, and a later `coverageGeneration`. Binding-local character, account-tenant, tenant, and realm-grant recovery sets every parent/child-only field to the exact `NOT_APPLICABLE` union arm; it never fabricates a parent or child identity or fence. A standalone issuer-wide coverage operation uses the same carrier with `parentRecoveryOperationId=NOT_APPLICABLE`, `parentLifecycle=NOT_APPLICABLE`, `parentOperationFence=NOT_APPLICABLE`, and `parentCoverageFence=NOT_APPLICABLE`; it self-binds `issuerCoverageOperationId=operationId`, `issuerCoverageLifecycle=lifecycle`, and `issuerCoverageOperationFence=operationFence`, plus its own distinct `coverageFence`. A standalone account-wide coverage operation uses the identical four typed `NOT_APPLICABLE` parent markers and self-binds `accountCoverageOperationId=operationId`, `accountCoverageLifecycle=lifecycle`, and `accountCoverageOperationFence=operationFence`, plus its own distinct `coverageFence` and account-scoped `accountAdmissionFence`; its `inventorySnapshotRevision` is a complete all-tenant snapshot for the exact `accountId`, captured only after that fence is acquired. For non-account-wide recovery, `accountAdmissionFence` is the exact typed `NOT_APPLICABLE` arm. Those parent markers and the account fence arm are exact typed values, never null, blank, arbitrary sentinels, or fabricated identities. A standalone operation is not a linked child and has no parent lifecycle or parent-fence dependency. Every applicable field must match the same operation attempt, immutable snapshot, resolved scope, binding/transition identity, digest, and readback; a local operation fence, narrow snapshot, high-water mark, Redis-derived result, or coverage fence in place of `accountAdmissionFence` never substitutes for this carrier.
  - After the internal pause and region-creation fence, Game Session captures one immutable `GameplayBindingInventory` snapshot identified by `inventorySnapshotRevision`. For account-wide coverage, it must first acquire the account-scoped admission/creation fence `accountAdmissionFence`; new bindings for the account are rejected or queued until exact full-key readback and `coverageGeneration` are durably recorded. The account fence is held for that entire interval and is distinct from the parent operation fence, child operation fence, coverage fence, and deployment maintenance lock. The snapshot is the complete durable inventory of every `ACTIVE`, `PROVISIONAL`, and unresolved account/issuer-repair binding and every non-terminal issuer reservation in the selected scope, read under repeatable-snapshot semantics at or before that revision. Issuer indexes, reservations, and capacity are global to the issuer: a region- or tenant-scoped operation may acknowledge binding-local recovery, but it must not acknowledge issuer coverage from that narrow snapshot. Issuer coverage requires a separate issuer-wide inventory and admission fence that captures every binding, reservation, repair obligation, partition, and capacity contribution for that issuer; absent that global operation, issuer coverage remains unacknowledged and affected admission stays closed. The scoped parent operation's admission/creation fence governs only its binding-local snapshot and repair. For the issuer-wide child, the child uses the immutable issuer-wide inventory; its admission/creation fence is the distinct `coverageFence`, and its maintenance fence is `issuerCoverageOperationFence`, neither substituted by the parent `operationFence`. Only that child may perform issuer reservation-capacity CAS/readback and construct issuer-partition evidence. Every later activation, replacement, terminalization, removal, reservation, or repair transition must carry the complete recovery evidence carrier, including the owning operation's fence and a later `coverageGeneration`, before that operation's coverage can be acknowledged.
- When preserved-session recovery needs issuer coverage, the scoped region/tenant recovery is the durable **parent** operation and creates or links one issuer-wide coverage **child** operation per affected issuer. The global singleton is keyed by coverage family, exact `issuerId`, and compatible immutable scope/layout/snapshot identity. The child owns its `issuerCoverageOperationId`, lifecycle, `issuerCoverageOperationFence`, `coverageFence`, inventory snapshot, and later `coverageGeneration`; once it is coverage-proven, its immutable result is globally shareable. Each parent creates an independent `recovery-parent-edge/v1` carrying `parentRecoveryOperationId`, parent lifecycle and fences, the child tuple, resolved scope, issuer, and `linkRevision`; the edge and every child request/digest, readback, acknowledgement, retry, and parent-consumption record are persisted atomically or accepted only through exact idempotent compare-and-set. The parent owns the deployment maintenance lock for its workflow. A linked child does not acquire, own, transfer, or refresh that lock; its mutations run only under the current lock owner and its own child/coverage fences. A standalone child operation owns its own lock. One controller may build a singleton child; an overlapping compatible parent may wait for or attach to that exact child, but never creates a second child or mutates a coverage-proven result. Exact retries replay the same compatible edge and child tuple. A parent-fence change invalidates only that parent's edge; it does not invalidate the immutable child or prevent another compatible edge. A conflicting scope, lifecycle, fence, snapshot, layout, or identity fails closed. Each edge is consumed exactly once by its parent after the child is coverage-proven; the child has no global consumed bit and remains immutable/auditable. A paused, superseded, aborted, or terminally mismatched child or edge keeps the relevant parent fenced and cannot authorize a parent by itself.
  - When preserved-session recovery needs account-wide coverage, the same parent links one singleton account-wide coverage **child** operation for the exact `accountId` under the same `recovery-parent-edge/v1` model. Before the child captures `inventorySnapshotRevision`, it acquires the account-scoped admission/creation fence `accountAdmissionFence`, blocks or queues new bindings for that account, and holds the fence through exact full-key readback and durable `coverageGeneration`. The child owns `accountCoverageOperationId`, lifecycle, `accountCoverageOperationFence`, `accountAdmissionFence`, `coverageFence`, complete-account `inventorySnapshotRevision`, and later `coverageGeneration`; the independent edge carries `parentRecoveryOperationId`, `parentLifecycle`, parent `operationFence`, distinct `parentCoverageFence`, child identity/lifecycle/fences, resolved scope, account, and `linkRevision`. The parent owns its deployment maintenance lock; a linked child does not own or nest under it, while a standalone account operation owns its own lock and acquires and holds its own account-scoped admission/creation fence under the same rules. Only one controller mutates the globally serialized singleton, and only an already coverage-proven immutable child result is reusable by another compatible parent. Every account binding creation/reconciliation CAS, child creation or link update, exact retry, global-index repair/readback, acknowledgement, and parent-consumption CAS carries and validates the exact `accountAdmissionFence` alongside the complete operation, lifecycle, snapshot, coverage, scope, and link tuple. Exact retries replay the same edge; a parent-fence change invalidates only that edge; an incompatible or ambiguous tuple, including an account-fence mismatch, fails closed; and each parent edge consumes the child result exactly once without a global child-consumed state. The child enumerates every tenant's complete `GameplayBindingInventory`, including active, provisional, and unresolved account-index obligations, repairs the untagged `session:game:index:account:<accountId>` key, reads back the exact complete generation-qualified member set, and publishes durable acknowledgements only after every row, transition, and obligation is covered and a later `coverageGeneration` is recorded. The parent validates the exact identities, lifecycles, operation fences, account admission/creation fence, both coverage fences, snapshot, readback, acknowledgements, retries, and later generation, then consumes only the complete child result after the child reaches its coverage-proven lifecycle. A stale, partial, unavailable, contradictory, duplicate, narrow-scope, or fence-mismatched result fails closed; the parent never substitutes a local snapshot, affected-member readback, empty key, aggregate count, or `SCAN` for account-wide coverage.
  - For both issuer-wide and account-wide coverage, each durable parent-child recovery link tuple and corresponding global-index edge/readback record persists and validates `resolvedScope` and `linkRevision` alongside the applicable operation identities and lifecycles, parent/child operation and coverage fences, the account-scoped `accountAdmissionFence` for account-wide coverage, immutable `inventorySnapshotRevision`, and later `coverageGeneration`. Child creation, link updates, retries, global-index repair/readback, acknowledgements, and parent-consumption CAS all validate that complete tuple; an exact retry returns only the matching edge, while an older link revision, stale scope, stale account fence, or any other mismatch fails closed and cannot overwrite a later edge revision.
- Preserved-session recovery may consume only that exact Game Session-owned snapshot and its bound coverage evidence. The preserved-session rebind decision, region-bridge transition, and exact readback must persist and validate the complete recovery evidence carrier, including the parent operation fence, distinct `parentCoverageFence`, applicable linked operation IDs/lifecycles and child fences or their explicit `NOT_APPLICABLE` markers, `inventorySnapshotRevision`, and later `coverageGeneration`. It rejects a stale, partial, unavailable, ambiguous, duplicate, or Redis-derived snapshot; a current `session:game:*` query, region binding key, Redis `SCAN`, key presence/absence, or partial index is never inventory or coverage proof. Any post-fence transition not represented by the complete carrier, or any unresolved binding, reservation, or repair obligation, keeps the scope fenced and blocks preserved-session rebind and reopen.
- A standalone issuer-wide operation or the linked issuer-wide child maps its immutable issuer-wide inventory to the immutable active issuer layout, performs the expected-value repair/removal and reservation-capacity CAS, and reads back every expected finite partition and exact reservation entry. The scoped parent performs binding-local repair only and may consume the complete linked-child result only after the child reaches its coverage-proven lifecycle. An issuer-partition acknowledgement is published by the standalone issuer operation or linked issuer-wide child, never by the scoped region/tenant parent. Before any standalone operation capacity CAS, readback, or acknowledgement, it validates `parentRecoveryOperationId=NOT_APPLICABLE`, `parentLifecycle=NOT_APPLICABLE`, `parentOperationFence=NOT_APPLICABLE`, `parentCoverageFence=NOT_APPLICABLE`, and the self-bound `issuerCoverageOperationId=operationId`, `issuerCoverageLifecycle=lifecycle`, `issuerCoverageOperationFence=operationFence`, and distinct `coverageFence`. Before any linked-child capacity CAS, readback, or acknowledgement, it validates the exact `parentRecoveryOperationId` and parent lifecycle, the exact `issuerCoverageOperationId` and child lifecycle, the parent `operationFence` and `parentCoverageFence`, the child `issuerCoverageOperationFence`, and the child's separate `coverageFence`. Each durable per-partition acknowledgement must then bind the complete applicable tuple: for a standalone operation, the four exact `NOT_APPLICABLE` parent fields and self-bound issuer-operation fields; for a linked child, the exact parent and child identities, lifecycles, both parent fields, both child fences, and all applicable inventory/snapshot evidence; in both cases, `issuerId`, issuer-wide `inventorySnapshotRevision`, later `coverageGeneration`, pinned active layout `{issuerIndexLayoutVersion, issuerIndexPartitionCount, issuerIndexPartitionCapacity, partitionId}`, exact `issuer/<issuerId>` cutoff including issuer generation and `issuanceFence`, complete applicable `outboxCheckpoints`, exact reservation identities with owner, transition, lifecycle, fence, and revision, and `{partitionId, reservationCount, partitionCapacity, aggregateReservationCount, aggregateCapacity}`. The capacity tuple must prove both the partition-local and issuer-wide aggregate bounds; aggregate capacity alone is insufficient. Every post-fence transition and acknowledgement must carry this complete carrier. An acknowledgement from an older layout, cutoff, snapshot, coverage generation, operation identity, lifecycle, maintenance fence, partition identity, reservation set, or capacity tuple is stale and cannot authorize recovery. No acknowledgement is published for an unavailable read, malformed/conflicting value, missing partition or reservation, partial partition set, excess capacity, unresolved obligation, or narrow-scope inventory.
- Issuer layout migration contract:
  - Recovery follows the immutable versioned issuer-layout migration and pointer-cutover contract in [Redis Architecture](./system-architecture-redis.md#issuer-active-binding-index). It never changes partition count in place or infers a layout from Redis; until the durable pointer compare-and-set succeeds, the old layout remains authoritative, and missing, mixed, or ambiguous migration evidence keeps the issuer scope closed.
  - Post-cutover cleanup remains a separate fenced operation after the documented drain/reconciliation proof; an interrupted migration resumes or is abandoned through its durable record rather than selecting whichever layout appears most complete.
- Internal recovery phase contract:
  - The following detailed phase semantics apply inside the high-level `recover` workflow. Equivalent internal methods or resumable steps may implement them; names and option spellings shown here are descriptive and are not public compatibility requirements. References to scope mean one of the forms the runtime currently advertises as supported.
  - Internal pause-and-lock phase
    - accepts only an advertised supported scope.
    - derives the canonical maintenance-lock compatibility class from the selected mode and any durable workflow contract bound to the operation before lock acquisition. `--mode replay-first` derives `replay-first`, `session-schema-cleanup` derives `cleanup`, and reset-based migration, topology-change, restore, or exceptional backup maintenance derives its class from the corresponding immutable operation contract. Callers cannot choose or override the class through a public flag. `backup` remains reserved for exceptional backup-related maintenance that actually pauses or mutates coordination state.
    - acquires the deployment maintenance lock for the recover workflow's multi-step restore, reset, cleanup, migration, topology-changing scaling, and exceptional backup-related maintenance work. Routine online PostgreSQL backup neither invokes recovery nor pauses ticks.
    - blocks until the scope reaches the control-plane `PAUSED` state or exits non-zero on timeout/failure.
    - must emit the `operationId`, one-way maintenance-lock token digest or opaque lock reference, immutable resolved affected-region inventory, and scope generation in audit output; later internal phases in the same workflow consume the protected token binding rather than reacquiring the lock independently. Audit output never contains the plaintext `maintenanceLockToken`; only the protected issuance response carries it.
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
    - owns `replay-first` convergence as well as old-epoch reset convergence:
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
    - The Account-validated opaque `rebindHandle` must bind and match the binding's exact one-tenant `membershipVersion` map `{tenantId: version}`, independently from `authorityTuple.membershipAuthorityGeneration`, and the complete stream-key-ordered `outboxCheckpoints` set. Missing, extra, scalar, differently keyed, stale, regressed, contradictory, or aggregate-only membership or checkpoint evidence, or a mismatch in either independent predicate, fails closed; rebind never merges handle and binding values, reconstructs one carrier from the other, or drops checkpoint entries.
    - is permitted when a region- or tenant-scoped reset's explicitly recorded session policy is `--preserve-sessions`; those scopes must record either `--preserve-sessions` or `--invalidate-sessions` rather than relying on scope inference. Cluster requests accept only explicit `--invalidate-sessions` and never enter this phase. For an eligible scope, the phase reads only the immutable `GameplayBindingInventory` snapshot identified by the operation's exact `inventorySnapshotRevision` and validates the complete recovery evidence carrier: parent operation fence, distinct `parentCoverageFence`, applicable linked child IDs/lifecycles/fences or explicit `NOT_APPLICABLE` markers, and later `coverageGeneration`. It recreates region-authoritative bindings through the durable inventory and session-to-region bridge, persists and validates that carrier on the rebind transition and readback, and must refresh the same maintenance lock rather than acquiring another lock. Ordinary gameplay mutation intake remains blocked until this phase succeeds; only an actually attempted valid-identity command with an established command record may be terminalized as `REGION_REBIND_REQUIRED` without gameplay mutation.
    - Before recreating a binding, it validates the complete [canonical preserved-session rebind predicate](./system-architecture-redis.md#session-and-region-binding-contract), including only a supported `schemaVersion=2` payload (missing, `schemaVersion=1`, unsupported, or incomplete versions fail closed), the payload's exact `issuanceFence`, `authorityTuple.membershipAuthorityGeneration`, exact one-tenant `membershipVersion` map, complete `outboxCheckpoints` set, exact equality of `authTokenIssuedAt`/`authTokenExpiresAt`/`tokenGeneration` to the registry `iat`/`exp`/`tokenGeneration`, and every token-registry, identity, authority, expiry, generation, epoch, and lease-fence predicate defined there. The rebind predicate is realm-specific: public production requires current membership and entitlement, and its bounded entitlement fallback is permitted only for the public-production entitlement read; private/playtest requires existing membership and the exact current Account-owned realm-grant identity and `grantVersion`, with no entitlement fallback or grant substitution. Missing, unavailable, stale, contradictory, or mismatched private/playtest grant evidence fails closed. `session:game:*` and pre-auth transport context are not authority substitutes. A reachable token-registry or Account-authority result of `AUTH_SESSION_REVOKED` or an invalid-token outcome, including missing, expired, malformed, revoked, or mismatched token authority, is a security failure: the phase marks the preserved session auth-revoked, closes its socket, and ends the current resume episode without emitting `REGION_REBIND_REQUIRED`. Unavailable or ambiguous Account evidence remains fenced under the outage policy and is not a region rebind gap. Only after every Account identity, token, authority, membership, lease, and delegation-JWT predicate has succeeded, with the applicable realm predicate satisfied by either a fresh public-production entitlement read or its qualifying bounded fallback, may a solely non-security region gap leave the session connected but not gameplay-admitted; a gameplay or admission command actually attempted after valid identity and command-record establishment may then receive terminal, non-applied `executionOutcome = REGION_REBIND_REQUIRED`. No command record or identity is synthesized for a missing, invalid, or unauthenticated request, and this command outcome does not close or terminalize the connected session. Any non-security predicate evidence that is missing, stale, ambiguous, mismatched, expired, partial, Redis-derived, or otherwise non-exact blocks rebind and reopen.
    - A failed non-security preserved-session predicate never implicitly changes the recorded session policy. The operation remains paused and fenced under the same `operationId` and `maintenanceLockToken`; an explicit audited preserve-to-invalidate transition may compare-and-set the policy under that same lock only after persisting and validating the complete recovery evidence carrier, including the parent operation fence, distinct `parentCoverageFence`, applicable linked child IDs/lifecycles/fences or explicit `NOT_APPLICABLE` markers, `inventorySnapshotRevision`, and later `coverageGeneration`. If that same-lock transition is unavailable, complete audited `release-lock` abandonment and start an explicit new `recover` operation with `--invalidate-sessions`. Rebind failure alone is never invalidation proof; the security failure above is the explicit auth-revocation transition. Any public-production entitlement-read-only outage fallback must also satisfy the canonical same-binding predicate; private/playtest never uses that fallback and requires the exact current Account realm-grant identity and `grantVersion`. A disconnected binding must carry its immutable `resumeDeadline`; a still-connected binding with no `resumeDeadline` must carry the trusted `connectedSessionFallbackProof` and immutable `connectedSessionDeadline` defined by the Redis session contract. The applicable bound is `min(continuityBindingExpiresAt, resumeDeadline)` or `min(continuityBindingExpiresAt, connectedSessionDeadline)`, respectively, followed by the `fallbackAt + 5 minutes` and `fallbackAt + session_expiration_ms` caps; ops tooling must not widen any bound.
  - Internal session-schema-cleanup phase
    - is owned by the bounded high-level `recover` operation; `session-schema-cleanup` is a mode/internal phase, not a separate public operation or command. Its continuation, abort, and release behavior use the parent operation's durable identity and lock lifecycle.
    - accepts only `--mode session-schema-cleanup --scope tenant --tenant <tenantId>` in the first implementation. Request validation must reject `--scope region` and `--scope cluster` before acquiring the maintenance lock, creating an operation record, pausing a scope, or starting any workflow; broader cleanup scopes are out of contract until explicitly designed.
    - requires `--operation-id <operationId>` and a protected token input such as `--maintenance-lock-token-file <permissioned-token-file>` for continuation, abort, or release controls.
    - accepts `--dry-run`, `--batch-size <n>`, and `--max-runtime-seconds <n>`.
    - When `--dry-run` is set, it may take the direct exception path only if discovery and validation acquire neither a gameplay fence nor a maintenance lock and produce no mutation or release effect. Only that path transitions directly to terminal phase `finalized` with terminal operation status `SUCCEEDED`; it never enters `AWAITING_RESUME`, invokes `continueRecovery` or public `resume`, or uses `release-lock` to finish, and it is not a release or traffic-open authorization. If a dry-run request acquires either fence/lock or performs any mutation, it is not eligible for the exception and must use the normal `continueRecovery(... expectedPhase=ready_to_reopen ...)` / `resume(... expectedPhase=awaiting_resume ...)` and internal release lifecycle, including all `finalized` postconditions.
    - walks only the documented tenant-scoped gameplay-session and pre-auth transport-context families for that tenant, using bounded `SCAN` windows; mutating runs may use `UNLINK` where applicable.
    - must emit immutable structured progress and completion evidence containing the parent `operationId`, tenant, prefixes visited, scanned count, deleted count, final cursor or continuation state, schema disposition, concrete abort/completion reason, and the evidence reference bound to the operation. The cursor or continuation state is durable operation-owned state; callers do not supply a resume token.
    - must fail closed when Redis latency/health exceeds the documented cleanup budget, when the maintenance lock is lost, or when the workflow encounters an unsupported schema family outside the explicit cleanup contract.
  - Internal post-reset smoke-check phase
    - consumes the advertised supported scope selected by the recovery workflow.
    - consumes the same maintenance lock token as the prior internal phase.
    - for tenant/cluster scopes, accepts an optional explicit sample-set argument; otherwise the tool must auto-select one representative region per affected executor/shard group and print which regions were sampled. Sampling occurs only after the Account projection and exact-token gates pass, while protected admission remains closed.
  - `coordination-maintenance resume` (public safety control)
    - derives the operation-owned scope from `--operation-id` and rejects an operation whose recorded scope is not advertised as supported. The public API and CLI accept no scope selectors for resume.
    - requires `--operation-id <operationId>`, `--expected-phase <expectedPhase>`, a protected token input such as `--maintenance-lock-token-file <permissioned-token-file>`, and `--evidence-ref <evidenceRef>`, all matching the active durable workflow. The control resolves the operation-owned scope; callers do not supply scope selectors to resume.
    - resolves the presented token against the active operation and validates the bound authenticated actor or workload principal, deployment boundary, operation, expected phase, scope, mode, compatibility class, expiry, and immutable evidence reference before any mutation. A principal mismatch, missing evidence, stale phase, lost lock, or incomplete gate fails closed and leaves the scope fenced.
    - exits non-zero unless the durable operation is exactly `AWAITING_RESUME`, the common safety gate proves the exact operation/scope/token/evidence tuple, retained maintenance and traffic fences, complete affected-region inventory, and the applicable post-workflow health/smoke result, and the selected mode's gate passes:
      - `reset` requires reset completion, applicable Account issuer/account/tenant/membership generation and issued-token projection rebuild evidence, replay-domain quarantine/fence and durable consume proof where the reset affects that domain, old-epoch ledger and command convergence, and session rebinding when the recorded policy preserved gameplay sessions.
      - `replay-first` requires in-epoch ledger and command convergence without an epoch bump, the applicable authority/replay evidence for its scope, and a passing replay budget/status check; it does not require reset-only epoch-bump or keyspace-replacement evidence.
      - mutating `session-schema-cleanup` requires immutable completion evidence for the exact tenant, prefixes, scan/delete counts, final cursor or continuation state, schema disposition, and completion reason, plus proof that the affected session-schema condition is reconciled; it does not require reset-only epoch, Account projection-rebuild, ledger, or replay gates unless that cleanup actually executed the corresponding phase. A strictly observational dry run finalizes through its separate no-effect exception and never enters this resume gate.
    - durably audits the matching operation tuple, bound authenticated actor or workload principal, evidence reference, gate result, and pre-resume fence state, then atomically records `RESUME_AUTHORIZED` while retaining the recovery lock and traffic fence. The controller's internal `releasing -> finalized` phase is the only transition that may then apply and observe release effects; `SUCCEEDED` is recorded only after `finalized` and all release postconditions.
  - `coordination-maintenance release-lock` (public audited failure control; target-state only until implemented and proven)
    - requires `--operation-id <operationId>`, the exact recorded scope, a protected token input such as `--maintenance-lock-token-file <permissioned-token-file>`, `--reason <reason>`, and `--evidence-ref <evidenceRef>`; the request has no public `expectedPhase` because the controller resolves the exact current durable phase.
    - accepts only `PAUSED`, `collecting`, `ready_to_reopen`, or `AWAITING_RESUME`; it rejects `RESUME_AUTHORIZED`, `releasing`, `PARTIAL_RELEASE_RECONCILING`/`partial_release_reconciling`, `finalized`, unknown or ambiguous phases, and any operation whose identity, scope, token, mode, compatibility class, or evidence does not match. `RUNNING` is rejected by default because it is an operation status, not a phase; the only exception is an explicit authorized running-operation abandonment gate.
    - For that running-operation exception, the controller must atomically persist the abandonment intent, advance the fencing generation, stop or fence every worker lease, and durably observe that no worker can make further progress before release. It then records nonterminal `PENDING_RELEASE` with the exact request tuple, observed phase, and fencing generation before invoking the fenced lock-release effect. A failed or ambiguous fence, worker-stop observation, or release effect leaves the lock and gameplay fence in place.
    - The release effect is idempotent on the exact operation, recorded scope, maintenance-lock identity, bound authenticated actor or workload principal, reason digest, and evidence identity tuple. An exact retry resumes or reconciles the same `PENDING_RELEASE` effect; a different tuple returns an idempotency conflict without a second release effect. Only an observed and durably committed release may produce terminal `ABANDONED`; release-lock never authorizes resume, traffic reopening, or a new operation.
- Target-state execution rule, not a current invocation instruction:
  - Once the bounded high-level maintenance operations are implemented and proven, they are the only supported write-path entrypoints for coordinated reset/recovery flows. Helm hooks, Jobs, and admin dashboards may then call them rather than re-encoding reset logic or directly invoking internal recovery phases. Current runbooks, Helm hooks, Jobs, and dashboards must not invoke the unavailable surface.
- Epoch-bump ownership rule:
  - `RunScopedCoordinationReset(operationId, scope, maintenanceLockToken)` is the internal canonical owner of the PostgreSQL `region_epoch` bump for reset and restore flows.
  - No separate runbook-only or ad hoc SQL step is allowed to silently bump `region_epoch` out of band from that reset operation.
  - Backup restore automation and reset runbooks must record the epoch bump evidence emitted by this operation rather than inventing a second audit trail.
- Required version rule:
  - The CLI and control-plane implementation must ship from the same build/version set as the services and Lua registry they operate on. Mixed-version reset orchestration is unsupported.
- Maintenance-lock lifecycle rule:
  - One recover workflow owns one deployment maintenance lock until its internal success release or the separately audited `coordination-maintenance release-lock` abandonment control completes.
  - Later phases in that workflow refresh the lock TTL with the same `maintenanceLockToken`; they do not acquire independent locks. Controller restarts recover the token binding and expected phase from the external durable operation record rather than from target Redis.
  - A phase failure retains the lock and paused fence. Failures before `ready_to_reopen` resume through controller-owned internal retry and reconciliation for the same operation; they are not public `continueRecovery` inputs. Public `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` is available only after the operation durably reaches `ready_to_reopen`, with that exact expected phase. No failure auto-releases merely because a process exited or an infrastructure step timed out.
  - If a phase loses the lock, every later mutation must fail closed until an operator explicitly restores the same fenced operation or abandons it through audited `release-lock`; abandonment does not authorize resume.
  - A `replay-first` workflow starts with dedicated compatibility class `replay-first`, derived from `--mode` and bound into the maintenance lock and operation record. Escalation to `reset` must atomically compare-and-match that same token and upgrade the class to `reset` without releasing or reacquiring the lock. The upgrade audit record, including scope, old/new class, lock-token digest or opaque lock reference, workflow lineage, actor, reason, and resulting epoch transition, must be durable before the epoch bump or reset-key mutation is allowed and must never contain the plaintext token.
  - If the same-token upgrade or its audit write cannot complete, the workflow remains paused and no reset mutation may proceed; the operator must use the explicit failure/abort path. A second lock cannot be used to bypass the failed upgrade.

### Canonical Pre-Wipe Gates

The external AOF/deployment reset handoff may execute only after the same durable recover operation and maintenance lock have passed these named internal evidence gates. A node-level destructive action (`FLUSHALL` or AOF reset) must resolve its actual cluster blast radius as `cluster`, or provide a fresh `physical-dedication-proof/v1`: an independently generated single-use challenge/nonce, issued and expiry times, an independently verifiable trusted deployment-attestor signature and verifier identity, and exact equality for `operationId`, `operationFence`, resolved scope, deployment identity, node identity/node set, and physical-dedication scope. The proof is not transferable from cluster to region/tenant, and the controller rejects a region/tenant handoff before external action when that exact proof is absent, expired, replayed, unverifiable, or mismatched. A whole-deployment `FLUSHALL` or AOF replacement is permitted only for resolved `cluster` scope or under that same fresh proof or an equivalent independently verified node-isolation protocol; logical scope labels and operator assertions are not proof.

- `scope_paused_and_locked` – the scope is canonical `PAUSED`, command and batch intake are blocked, in-flight executor work is drained, and no old-epoch writer can create coordination state.
- `account_authority_token_cutover` – protected admission is closed and Account's durable authority/token identity cutover and required immutable evidence are complete for the operation's scope.
- `replay_domain_quarantine_fence` – the replay domain is verified untouched for a narrower reset or quarantined/fenced for the destructive reset, with immutable fence evidence recorded.
- `immutable_external_handoff_evidence` – old and intended new deployment identities, the fenced old endpoint, authorized operator/action/time, and tooling digest are recorded before the wipe. This is pre-wipe authorization and fencing evidence only; it must not contain facts that can be observed only after replacement startup.

These names identify pre-wipe evidence groups, not additional public CLI verbs. Redis key absence, a new empty endpoint, or a caller-supplied scope cannot satisfy any gate, and every gate remains bound to the same `operationId` and server-issued `maintenanceLockToken`.

### Post-Reset Replacement Verification Gate

`post_reset_replacement_verification/v1` is a separate post-startup continuation/resume gate, not a canonical pre-wipe gate or an operator assertion. Before either destructive handoff or startup, the controller creates and durably records `replacementVerificationChallenge/v1` with an independently generated high-entropy nonce, `challengeId`, `issuedAt`, `expiresAt`, single-use state, `operationId`, `operationFence`, resolved scope, source deployment identity, intended target deployment identity, verification mode, and the trusted attestor/verifier binding. The challenge is consumed atomically exactly once through the authenticated deployment-control channel; it is never caller-created, replayed, or renewed after expiry.

The accepted signed record contains the challenge/nonce identity, operation tuple, exact source and target deployment identities, verification mode, target startup/deployment generation, attestor identity and verifier, and the expected `coord_ops_ro/v2` ACL/config contract and digest. For `SAME_DEPLOYMENT`, including a fresh logical-database reset on the same deployment, source and target have the same immutable deployment identity but a new boot/startup generation and a new storage/AOF generation; the trusted attestor must independently emit `postWipeEmptyStartupAttestation/v1` proving that the target booted from an empty post-wipe data/AOF state after the destructive action. No lesser same-deployment reset path is valid. An operator observing an empty database or a key-count query is not sufficient. For `REPLACEMENT_DEPLOYMENT`, source and target identities must be distinct and the trusted attestor must prove the exact target deployment identity, deployment generation, node set, Redis build, Lua registry identity, and startup state; replacement identity alone is not an empty-keyspace proof.

In both modes the controller independently verifies the deployment boundary, challenge freshness/one-time consumption, exact operation/scope/fence binding, empty-keyspace evidence, health, and the canonical ACL/config contract. The ACL/config verification must prove the exact policy digest plus negative probes showing that `coord_ops_ro` cannot run database-wide `SCAN`, write, mutate expiry, run `EVAL`/`EVALSHA`, run `SCRIPT LOAD`, execute any `CONFIG` command, or run `CLIENT LIST`, `SLOWLOG GET`, `INFO`, `MEMORY STATS`, `LATENCY LATEST`, `LATENCY DOCTOR`, or `CLUSTER NODES`. Needed server-wide telemetry is obtained only through an authenticated filtered control-plane endpoint, never through the ordinary Redis ops user. Missing, replayed, expired, caller-supplied, mismatched, or ambiguous evidence leaves admission closed. The accepted evidence is bound to the same operation and resolved scope and is validated with the pre-wipe evidence before recovery continuation, public `continueRecovery`/`resume`, or protected admission can authorize release. Replacement verification never authorizes the pre-wipe handoff, and pre-wipe evidence cannot substitute for it.

The paired deployment configuration contract is `coord_redis_config/v1`: the trusted deployment attestor signs the exact normalized allowlist and denylist of expected non-secret Redis settings and the SHA-256 digest over that canonical serialization. The verifier compares that digest to the operation's expected value and separately proves that the `coord_ops_ro/v2` principal has neither database-wide `SCAN`, any `CONFIG` command grant, nor access to the server-wide telemetry commands `CLIENT LIST`, `SLOWLOG GET`, `INFO`, `MEMORY STATS`, `LATENCY LATEST`, `LATENCY DOCTOR`, or `CLUSTER NODES`; a successful startup, an operator-supplied config dump, or an ACL category name is not configuration evidence.

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
  - cluster scope: every region in the immutable recorded affected-region snapshot, including regions recorded as active, paused, degraded, stalled, or draining, satisfies the applicable pass criteria above.
- Internal release path (`ResumeTicks` or equivalent) required behavior:
  - Consume only a prior `RESUME_AUTHORIZED` record from the same active operation; no public call invokes this internal path directly.
  - Resolve the server-issued token against the durable operation record, persist the authenticated actor or workload principal and gate result in the operation audit, and keep the affected scope fenced until the internal `releasing -> finalized` transition observes every release postcondition.
  - Resolve the immutable affected-region snapshot and refuse release unless every recorded region, including paused, degraded, stalled, and draining entries, has passed the gate applicable to the selected workflow. A current-active-region query cannot narrow this inventory.
  - Apply `RUNNING` only to a region whose recorded lifecycle and workflow disposition authorize that transition; preserve or reconcile paused, degraded, stalled, and draining regions to their recorded safe disposition rather than promoting them implicitly. Record `SUCCEEDED` only after every snapshot entry's release disposition is observed and the controller reaches `finalized`.

Jobs, wrappers, and dashboards may present this state differently, but they must all consume this same underlying contract and must not invent alternate quiescence criteria.

`RunPostResetSmokeCheck(operationId, scope, maintenanceLockToken)` minimum assertions:

| Check | Required pass criteria |
| --- | --- |
| Deployment boundary | The accepted `post_reset_replacement_verification/v1` binds the exact operation, scope, source/target deployment identities, mode, startup/deployment generation, challenge consumption, and operation fence; `SAME_DEPLOYMENT` includes both new boot/startup and storage/AOF generations plus independent `postWipeEmptyStartupAttestation/v1`, while `REPLACEMENT_DEPLOYMENT` includes trusted target deployment attestation. |
| ACL/config contract | The target matches the expected `coord_ops_ro/v2` ACL/config digest and its negative probes prove no database-wide `SCAN`, writes, expiry mutation, `EVAL`/`EVALSHA`, `SCRIPT LOAD`, `CONFIG`, `CLIENT LIST`, `SLOWLOG GET`, `INFO`, `MEMORY STATS`, `LATENCY LATEST`, `LATENCY DOCTOR`, or `CLUSTER NODES` access; needed server-wide telemetry comes only from the authenticated filtered control-plane endpoint. |
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
  - For gameplay session prefixes, follow the canonical reset-policy matrix: every region- and tenant-scoped reset must explicitly record either `--preserve-sessions` or `--invalidate-sessions`; the canonical region example uses `--preserve-sessions`, and preserved sessions require the rebind flow before admission. Every cluster reset must explicitly record `--invalidate-sessions` and rejects `--preserve-sessions`. Account-owned `session:auth:token:<tokenHash>` records are a separate authority domain: region- and tenant-scoped resets preserve them, while a cluster reset must close protected admission and complete the Account repair/reset cutover before deleting them as cleanup and requiring re-registration. Preserved region/tenant sessions must pass current identity, membership, and revocation validation before `rebind-sessions`; a generic region/tenant reset is never a repair for a token-registry mutation.
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

- Target state: the coordination maintenance CLI is shipped as part of the normal build/release pipeline under the canonical command name `coordination-maintenance`; environment packaging may wrap that command in a Gradle task, container entrypoint, or `dev-tools/` script. Once implemented and proven, target-state runbooks and Helm hooks may reference the bounded public operation names above. Current runbooks and Helm hooks must not invoke this unavailable command.
- Target-state version rule: once the CLI is implemented and proven, operators must use only a version that matches the deployed services and Lua registry:
  - If the target CLI build version does **not** match the image tag or Git commit used for the running deployment, the target-state operator must not attempt coordination recovery actions; the operator must use the same artifact version or perform a coordinated upgrade.
  - In the target state, break-glass or manual `redis-cli` operations are not an acceptable substitute for a mismatched maintenance CLI; they still require a scoped coordination reset afterwards and should be treated as incident-only paths. This does not make the unavailable CLI a current operator path.

Target-state runbooks may call the maintenance CLI entrypoint explicitly, after the implementation and proof gates above are complete, and should avoid embedding raw Redis commands. Current runbooks must not call the unavailable entrypoint.

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

For target-state reset and migration procedures tied to these roles, see [Redis Operations & Migrations](./system-architecture-redis-operations.md), which defines the versioned coordination reset flows and external AOF-maintenance handoff that may be used only after the bounded maintenance surface is implemented and proved. Current operators must follow the documented incident-only fallback and must not invoke the unavailable `coordination-maintenance` CLI, target-state runbook commands, or Helm hooks.

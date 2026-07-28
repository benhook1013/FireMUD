# FireMUD Redis Operations & Migrations

This document captures the canonical operator model for Coordination Redis. It complements the conceptual guarantees in [`system-architecture-redis.md`](./system-architecture-redis.md) and the Lua authoring patterns in [`system-architecture-redis-lua-patterns.md`](./system-architecture-redis-lua-patterns.md).

The invariants and contracts in [`system-architecture-redis.md`](./system-architecture-redis.md) remain authoritative. This doc focuses on named operational flows and migration posture.

## Implementation Status

The canonical reset/recovery contract below is target state, not a claim that the complete durable recovery controller is already shipped. The repository currently has partial control-plane pause/status support and supporting reset/reconciliation components, but does not yet expose the complete public `recover`, `continueRecovery`, `resume`, and audited abandonment surface end to end. The target remains one durable operation with one server-issued maintenance lock: `continueRecovery(... expectedPhase=ready_to_reopen ...)` reconciles pre-release work into `AWAITING_RESUME`, public `resume(... expectedPhase=awaiting_resume ...)` records `RESUME_AUTHORIZED`, and only the internal release phase reaches `finalized`. Current implementation and proof status must be reported separately from this target contract.

## Default Operator Flows

- select the appropriate AOF profile (`dev_local`, `hobby_self_hosted`, or `production_clustered`) and watch the associated size/restart targets
- run named coordination reset and script-upgrade flows when metrics or the Lua Compatibility Registry indicate they are required

Other procedures and tuning advice here are advanced and should not be expanded into bespoke one-off sequences. New remediation paths should be expressed in terms of these named flows wherever possible.

## Documentation Map

- [`system-architecture-redis-metrics-catalog.md`](./system-architecture-redis-metrics-catalog.md)
  - Redis SLO metrics, tick/coordination metrics, cache metrics, alerting signals, and coordination size/complexity budgets
- [`system-architecture-redis-script-rollout-and-compatibility.md`](./system-architecture-redis-script-rollout-and-compatibility.md)
  - Lua compatibility modes, rollout matrix, registry expectations, and script upgrade runbooks

## Canonical Coordination Reset Sequence

This section is the normative source for the multi-step Coordination Redis reset/recovery workflow. Other runbooks should point here and then describe only scope choice, session policy, evidence, and scenario-specific abort or storage steps.

Canonical public operation:

`coordination-maintenance recover --mode reset --scope ... (--preserve-sessions|--invalidate-sessions)`

This one public operation acquires the maintenance lock, fences the scope, and runs these ordered phases. The public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` safety gate is required between pre-release continuation and the internal success release:

1. internal pause-and-lock phase
2. internal epoch-bump and scope-safe coordination-reset phase
3. internal ledger-reconciliation phase
4. internal command-convergence phase
5. internal protected-domain cutover-fencing phase for Account durable authority/token identity and replay-domain quarantine, with immutable evidence
6. external AOF/deployment reset handoff, when the selected reset requires destructive storage cleanup
7. internal metadata-initialization phase
8. internal Account authority and issued-token projection-rebuild phase
9. internal session-policy phase, including invalidation or preserved-session rebind according to the selected policy
10. internal post-reset smoke-check phase, with protected admission still closed until this phase passes
11. `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` with canonical `expectedPhase=ready_to_reopen`, reconciling the controller into `AWAITING_RESUME`
12. public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` with canonical `expectedPhase=awaiting_resume`, which resolves the operation's recorded scope and records `RESUME_AUTHORIZED` without releasing the lock or traffic fence
13. internal resume-and-success-release phase

The internal pause-and-lock phase is not a public command or a standalone operation. Only `recover` creates the durable `operationId` and maintenance-lock identity; an interrupted workflow resumes through that same operation or is explicitly abandoned through the audited maintenance-lock release control.

Supported external controls use the following canonical API-to-CLI mapping:

- `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` maps to the continuation control for retrying the same durable operation after a controller restart or an external infrastructure step. The release-boundary invocation uses canonical `expectedPhase=ready_to_reopen`, may reconcile only into `AWAITING_RESUME`, and must match the active operation, phase, server-issued lock, and immutable evidence. It is not the public release authorization.
- `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` maps to `coordination-maintenance resume --operation-id <operationId> --expected-phase <expectedPhase> --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>` and is a separate post-recovery safety gate. It uses canonical `expectedPhase=awaiting_resume`, resolves the operation's recorded scope, validates the exact operation, lock, and evidence, durably audits the authenticated actor, atomically records `RESUME_AUTHORIZED`, and does not release the lock or reopen traffic. Any mismatch or missing evidence fails closed.
- `releaseMaintenanceLock(operationId, scope, maintenanceLockToken, reason, evidenceRef)` maps to `coordination-maintenance release-lock --operation-id <operationId> --scope <scope> ... --maintenance-lock-token-file <permissioned-token-file> --reason <reason> --evidence-ref <evidenceRef>` for audited operator abandonment. The concrete scope selector (for example, `--tenant <tenantId> --region <regionId>` for a region scope) must match the durable operation exactly. It retains the paused/fenced state and never reopens the scope.

No public command may select or invoke an internal phase. The CLI exposes the same controls as `continue-recovery`, `resume`, and `release-lock`; the API names above remain the canonical control-plane names.

The audited abandonment control never runs automatically; an operator must supply the matching operation, scope, maintenance-lock token, reason, and immutable evidence reference.

Rules:

- The operation record and maintenance-lock authority live in a durable control store outside the target Redis deployment so the workflow remains resumable after that deployment is replaced or emptied.
- The internal pause-and-lock phase must drive the chosen scope to canonical `PAUSED` before storage-level wipe or prefix deletion occurs.
- Capture the `maintenanceLockToken` returned by that phase and pass it to every subsequent internal phase; no phase reacquires the deployment lock independently.
- The internal epoch-bump and coordination-reset phase is the only phase that bumps `region_epoch` and emits authoritative old/new epoch evidence for downstream reconciliation.
- The early epoch-bump/reset phase may perform only scope-safe coordination cleanup after the scope is fenced. It must not delete or recreate a full Coordination Redis deployment or AOF volume.
- Internal ledger reconciliation and command convergence are required before traffic resumes; replay-first workflows use those same phases without a preceding epoch bump, but reset workflows must not skip them.
- Internal metadata initialization re-establishes `tick:{tenantRegionTag}:meta` from the durable baseline after scope-safe cleanup and, where applicable, the external AOF/deployment reset; `{tenantRegionTag}` is the opaque full-scope tag for `<tenantId, gameInstanceId, regionId>`.
- Reset-mode recovery requests Account Service to rebuild and verify the Account issuer, account, tenant, and membership generation projections from Account durable authority and to rebuild and verify the affected `session:auth:token:<tokenHash>` issued-token projections before the smoke phase. Recovery awaits the durable Account result and verifies its returned freshness/generation evidence; it is not a writer of Account-owned projections. Region- and tenant-scoped resets preserve those Account-owned records but still require an idempotent re-projection and exact-generation validation; a cluster reset verifies the Account repair/reset cutover that preceded physical cleanup, then registers replacement issued-token projections and proves exact-token validation before representative-region smoke. The phase emits immutable projection evidence and fails closed on any missing, stale, malformed, or mismatched generation or token record.
- Internal session rebinding is conditional and occurs only after the Account projection phase succeeds. Every region-, tenant-, and cluster-scoped reset records either `--preserve-sessions` or `--invalidate-sessions`; only the former permits preserved-session rebind, and no scope infers the policy.
- A fresh Coordination Redis keyspace reset, including a destructive full-deployment/AOF reset that produces an empty keyspace, is permitted only after the protected Account authority/token cutover and replay-domain quarantine/fence have completed and their immutable evidence has been recorded. It is the external AOF/deployment-reset phase in the canonical sequence, never an early pause/reset step; the external handoff is part of the same durable operation, is not authorized by an empty keyspace, and does not add another public continuation phase.
- The internal post-reset smoke-check phase proves the new epoch can acquire leases, stage work, converge, and clean up correctly, proves replay-domain quarantine/fencing and durable consume acknowledgement where the reset affects replay admission, and for tenant/cluster scopes samples the required representative regions only after the Account projection and exact-token gates pass. Protected admission remains closed through those projection, token, and smoke gates; the operation then atomically enters `ready_to_reopen` without dropping the maintenance lock or traffic fence. Its evidence must include the completed Account projection rebuild and any required session-schema-cleanup completion evidence. Only the public `continueRecovery(... expectedPhase=ready_to_reopen ...)` transition may advance it to `AWAITING_RESUME`.
- The internal resume-and-success-release phase is unreachable until the external public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` control records `RESUME_AUTHORIZED` for the same operation, expected phase, recorded scope, lock, immutable evidence, Account projection evidence, and replay-domain proof where applicable. Its durable controller transition is atomic, but the external Game Session, Coordination Redis, ingress, affected-scope, and maintenance-lock effects are not one distributed transaction. The phase must durably audit the authorization, retain the fence and lock until each release postcondition is observed, idempotently apply and read back each required postcondition, and may record terminal `SUCCEEDED` only after every current observation succeeds. If a later effect fails, is missing, stale, or ambiguous after an earlier effect was released, the operation enters durable `PARTIAL_RELEASE_RECONCILING`: unreleased effects remain fenced, already-released workloads are re-fenced or otherwise contained, and traffic and normal side effects stay closed until complete containment is observed. Retry uses the same operation and per-effect identities, reconciles already-applied effects without duplication, and may resume release only after the partial-release inventory and containment evidence are durable.
- If the workflow aborts before terminal success, operators must use the audited `coordination-maintenance release-lock ...` control rather than inventing an alternate unlock sequence.

### External AOF-Reset Handoff

An AOF reset or replacement of the Coordination Redis deployment is an external infrastructure step inside the durable recovery operation. It must not race the recovery controller or use the empty keyspace as evidence that the operation is paused:

1. The recover operation first durably records the resolved operation, scope inventory, maintenance-lock digest/fence, expected target deployment identity, and a paused/fenced phase in the external control store. Protected admission and affected coordination writes remain closed; any early reset work is limited to scope-safe cleanup.
2. Before destructive reset, the operation establishes the protected Account authority/token cutover and replay-domain quarantine/fence, records their immutable evidence, and verifies that the cutovers are bound to the same operation and scope. An AOF wipe must not occur before these fences and evidence exist.
3. The authorized operator performs the AOF reset or replacement only after recording an immutable handoff evidence reference. That evidence identifies the old and new deployment identities, scope, operator, action and time, tooling digest, and proof that the old endpoint is fenced and the replacement endpoint is the intended target.
4. The replacement starts with the required empty keyspace and protected credentials/ACLs. Before continuation, the operator records independent verification of endpoint identity, ACL/configuration, empty-keyspace state, and deployment health. Redis key absence alone is not the pause or ownership proof.
5. The operator binds that evidence to the same durable `operationId`. The controller validates the stored pause/fence, operation-owned scope, lock, target deployment identity, protected-domain cutover evidence, and immutable handoff evidence before its internal recovery worker resumes rebuild phases; it never reconstructs the operation or releases the fence from the new empty keyspace. This handoff is durable operation evidence, not an additional public `continueRecovery` phase.
6. A missing, stale, mismatched, or ambiguous handoff or verification record leaves the operation paused and gameplay admission closed. Internal retries use the same operation-owned state and do not repeat the AOF reset or begin rebuild concurrently. The only public `continueRecovery` invocation remains the later `expectedPhase=ready_to_reopen` transition into `AWAITING_RESUME`; public `resume` with `expectedPhase=awaiting_resume` and the separate internal release are still required before reopening.

## Redis SLOs & Budgets

This section centralizes the normative targets for Redis behavior that other docs reference. Individual environments may tune concrete values, but changes should be treated as deliberate SLO updates rather than silent drift.

### Coordination Redis Core Targets

- **Tail-loss window**
  - production-like profiles target `tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)`
  - ephemeral profiles may accept wider or unbounded tail loss but must be clearly labeled as such and must not validate tail-loss SLOs
  - a sustained breach of this envelope is a tick-SLO violation, not just a Redis anomaly: coordination state inside the breach window can no longer be trusted for automatic replay decisions
  - even under breach, domain-level idempotency and `EffectId` rules still prevent double-application; what degrades is the size of the trusted replay window and the amount of manual or tooling-driven reconciliation required
- **Restart time**
  - planned restarts for `hobby_self_hosted` and `production_clustered` nodes should typically complete within 30–60 seconds
- **Script runtime**
  - tick- and session-related Lua scripts are expected to complete within roughly 10–20 ms per invocation under normal load
- **Coordination memory share**
  - coordination prefixes should normally occupy no more than about 30–40% of `maxmemory` on Coordination Redis with `noeviction`

### Cache/Rate-Limit Redis Core Targets

- cache/eviction pressure should drive resizing or cache-design review, not become accepted steady-state behavior
- rate-limit and TTL-only cache key counts should remain within modest, documented per-tenant envelopes
- operators should track per-prefix hit/miss behavior, backing DB/service load correlation, chat-cache health, and automation-cache usage after resets or major cache changes so cache behavior remains visible without treating Cache Redis as a correctness boundary

## AOF Size and Restart Budget

Goal: keep Coordination Redis restart behavior predictable and avoid unbounded AOF growth.

Targets:

- soft AOF size limit per node of roughly 1–2 GiB for small/self-hosted deployments
- typical restart time of 30–60 seconds during planned maintenance
- steady-state daily AOF growth normally below about 250–500 MiB/day per node

Operators should wire alerts directly to these metrics and treat sustained growth or restart-time breach as a signal to resize, split load, or stop misusing Coordination Redis as a general-purpose data store.

### Runbook: AOF Too Large or Restarts Too Slow

1. Confirm via metrics or `INFO` that AOF size, restart time, or daily growth is outside the agreed budget.
2. Schedule a maintenance window.
3. Keep the control-plane path and maintenance tooling alive long enough to execute the canonical reset handshake; do not stop the very components required to pause, fence, audit, and verify the workflow.
4. Start the [Canonical Coordination Reset Sequence](#canonical-coordination-reset-sequence) for the affected scope with exactly one explicit session-policy choice, `--preserve-sessions` or `--invalidate-sessions`.
5. Complete every protected-domain cutover owned by Account Service and any replay-domain quarantine/fence before destructive storage cleanup; the recover operation must receive and verify the returned Account projection and replay evidence while admission remains closed.
6. Perform the storage-level reset only in the external AOF/deployment reset handoff after those cutovers and fences are established, by stopping Redis, deleting or recreating the AOF volume, and restarting Redis with the desired AOF configuration. The earlier internal reset phase may perform only scope-safe cleanup; a full-AOF deletion must never precede the protected-domain cutovers or be treated as their authorization boundary.
7. Allow the single recover operation to complete its internal reconciliation and smoke-check phases to `ready_to_reopen`, then require public `continueRecovery(... expectedPhase=ready_to_reopen ...)` to reach `AWAITING_RESUME`, followed by public `resume(... expectedPhase=awaiting_resume ...)` before the separate internal release phase may finalize and permit ticks or player traffic.
8. If the workflow aborts, use only the separately audited maintenance-lock release control; do not invoke an internal recovery phase as a public command.

Manual AOF surgery is not supported. Either the AOF is trusted and replayed as-is, or it is discarded and Redis restarts from a clean keyspace.

## Cache/Rate-Limit Redis Reset

Goal: provide a simple, explicit runbook for resetting Cache/Rate-Limit Redis without entangling it with Coordination Redis resets.

Cache/Rate-Limit Redis is fully reset-tolerant for the prefixes listed in [`system-architecture-redis-cache.md`](./system-architecture-redis-cache.md) and the reset policy matrix in [`system-architecture-redis-reset-and-recovery.md`](./system-architecture-redis-reset-and-recovery.md). A reset:

- drops cache and rate-limit keys such as `inventory:*`, `character-cache:*`, `world-dynamic:*`, `room:*`, `view:room-look:*`, `chat:*`, `automation:*`, and `ratelimit:*`
- does not affect Coordination Redis keys such as `tick:*`, `timer:*`, `retry:*`, `session:*`, or `tick-executor-lease:*`
- increases load on backing services temporarily but must not lose authoritative game data

### Runbook: Environment-Scoped Cache Reset

1. Identify the Cache/Rate-Limit deployment and verify it is distinct from Coordination Redis.
2. Assess impact and communicate expected temporary DB/service load and rate-limit reset effects.
3. Perform the reset:
   - single-node: stop or disconnect clients, `FLUSHDB` or `FLUSHALL` only if dedicated, restart Redis
   - clustered: use bounded prefix-scoped deletion over known cache families
4. Monitor cache hit/miss behavior, DB/service load, and rate-limit behavior after reset.
5. Fix the underlying key-shape, TTL, or cache-design issue if the reset was triggered by design drift.

## Reset Tolerance Classes

FireMUD classifies coordination-backed workloads by reset tolerance:

- **reset-tolerant**
  - tick locks, `pending` entries, timers, retry queues, and conflict metadata
- **reset-sensitive**
  - gameplay/auth session prefixes such as `session:game:*` and `session:auth:*`
  - certain automation queues or non-critical analytics that can be recomputed or re-enqueued
- **reset-forbidden**
  - future workloads that would treat Redis as a durable component of a long-lived contract

Any new feature that wants to use Coordination Redis must declare its reset tolerance class in design docs and, where necessary, use separate deployments/prefixes or stronger durable stores.

## Replica Promotion and Missed Writes

Goal: handle Redis replica promotion without violating tick and replay guarantees.

Facts:

- Coordination Redis uses asynchronous replication.
- A promoted replica may be missing recent coordination writes.
- The new primary’s keyspace is authoritative after promotion.

Behavior:

- modest promotion lag is equivalent to a small AOF tail-loss window
- replay safety is preserved by lease/lock/epoch validation and PostgreSQL-backed effect ledgers

Runbook:

1. Monitor `redis_replication_lag_ms{redis_role="coordination",nodeId,upstreamNodeId}` as the canonical promotion-lag metric, with `redis_replication_offset_lag_bytes{...}` as supporting evidence.
2. Compare the worst candidate-promotion lag against the same tail-loss SLO used elsewhere:
   - acceptable: `redis_replication_lag_ms <= 0.5 * tail_loss_budget_ms`
   - warning: `0.5 * tail_loss_budget_ms < redis_replication_lag_ms < tail_loss_budget_ms`
   - red: `redis_replication_lag_ms >= tail_loss_budget_ms`
3. If lag is in the acceptable band, promotion is acceptable from a replay perspective.
4. If lag is in the warning band, investigate immediately and delay promotion unless the failover risk of waiting is worse than accepting a wider tail-loss window.
5. If lag crosses the red line, either wait for recovery or treat promotion as a deliberate drop-recent-coordination-state event handled by one bounded `coordination-maintenance recover --mode reset --scope <scope> (--preserve-sessions|--invalidate-sessions)` operation under the normal maintenance-lock and epoch-fencing workflow.

## Key Shape Mistakes and Coordination Resets

Coordination keys are treated as reset-tolerant, volatile, and backed by PostgreSQL plus replay.

Before performing any coordination reset, operators should walk a short pre-reset validation checklist:

- confirm PostgreSQL is healthy
- verify tick effect ledger status for the target scope
- ensure game traffic is quiesced for the affected scope
- record operator intent and affected scope

### Scoped Tick Effect Ledger Reconcile

Every coordination reset that affects tick execution must include a tick-effect-ledger reconcile step that drives old-epoch rows to `APPLIED` or `ABANDONED` and ensures new executors do not resume `SCHEDULED` work from the old epoch.

### Runbook: Mis-Sharded Coordination Keys

1. detect the issue through CI, logs, or metrics
2. choose the smallest safe scope
3. execute the [Canonical Coordination Reset Sequence](#canonical-coordination-reset-sequence) for that scope
4. resume traffic only according to the chosen scope’s session policy

### Key Enumeration Strategy for Scoped Resets

Cluster-safe scoped resets rely on prefix-scoped `SCAN` per master under strict operational preconditions:

1. pause the target region or scope
2. acquire a scoped reset lock
3. enumerate only known prefix families
4. scan each master with modest `COUNT` and strict time budgets
5. delete via `UNLINK` where possible
6. repeat until stable

### Unknown-Prefix Detection and Hygiene

A lightweight unknown-prefix scanner periodically scans with conservative budgets, compares observed prefixes against the canonical catalogs, emits unknown-prefix metrics, and never mutates keys. It exists to surface drift between implementation and design before it becomes a larger incident.

## Session Schema Cleanup and Large Keyspaces

Session schema cleanup is a hygiene and recovery tool, not a normal steady-state path. When cleanup is required after a schema change or persistent unsupported-schema drift:

- operate on tenant-scoped gameplay/bootstrap prefixes such as `session:game:{tenantGameplayTag}:*` and the current `sessionctx:<tenantId>:*` family
- run at most one cleanup worker at a time per Coordination Redis deployment
- use bounded `SCAN` with modest `COUNT` values and strict time budgets
- delete via `UNLINK` where possible to avoid blocking the event loop
- acquire a short-lived per-tenant cleanup lock such as `session-cleanup-lock:<tenantId>`
- yield between batches and abort early when Redis latency or load is elevated
- resume from durable operation cursor/continuation state across bounded runs; callers do not supply an independent resume token
- emit cleanup metrics such as `session.cleanup_scanned_total`, `session.cleanup_deleted_total`, and `session.cleanup_duration_seconds`, with tenant context in logs
- provide a dry-run mode before modifying keys in operator-driven cleanup tooling

Canonical cleanup operation:

`coordination-maintenance recover --mode session-schema-cleanup --scope tenant --tenant <tenantId> --invalidate-sessions [--dry-run]`

The bounded high-level `recover` operation owns the lock, durable cursor/continuation state, internal session-cleanup phase, continuation, abort, and release behavior. Ad hoc cleanup Jobs must call this operation rather than encoding their own lock, continuation, or abort behavior. `session-cleanup` is an internal phase name, not a public command; retry uses the same `operationId` and server-issued `maintenanceLockToken` through `continueRecovery`, not a caller-supplied resume token.

When the cleanup workflow reaches `AWAITING_RESUME`, the public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` gate must use `expectedPhase=awaiting_resume`, resolve the operation's recorded tenant scope, and verify the immutable cleanup completion evidence for the exact tenant and operation, including visited prefixes, scanned/deleted counts, final cursor or continuation state, schema disposition, and completion reason. Missing, partial, ambiguous, or mismatched cleanup evidence retains the lock and fence and fails closed.

Default runbooks should still prefer fixing deployments and relying on TTL over aggressive keyspace scrubbing.

### Remote Hint Cleanup Scope

Remote hints use the complete instance scope `remote:{tenantInstanceTag}:<entityId>`, where `{tenantInstanceTag}` is derived from `<tenantId, gameInstanceId>`. There is no tenant-only remote key family. A tenant-scoped coordination reset is the canonical cleanup path: after the durable affected-region inventory resolves every game instance for the tenant, the reset tooling builds and scans one `remote:{tenantInstanceTag}:*` pattern per game instance and removes the matched keys with bounded `SCAN`/`UNLINK` batches. Cluster resets apply the same process to the cluster inventory; region resets do not remove instance-wide hints.

The reset audit must include the resolved game-instance inventory and scanned/deleted remote-hint counts. Operators must not invent a raw tenant-wide Redis pattern or use a region-only reset to clean instance-scoped hints.

## Maintenance Job Coordination

Redis maintenance flows such as session cleanup, scoped resets, normalization migrations, unknown-prefix scanning, split-brain recovery, restore coordination recovery, and topology-changing scaling can place non-trivial load on Coordination Redis and can invalidate each other if they overlap. Routine online PostgreSQL backups do not use this pause/status/epoch control plane. To keep mutating coordination work predictable:

- one control-plane actor orchestrates heavy maintenance per deployment
- one deployment-wide maintenance lock serializes incompatible restore, reset, cleanup, migration, and topology-changing scale operations
- an exceptional backup-related maintenance operation that explicitly pauses or mutates coordination state must acquire the lock, but the routine online backup CronJob neither acquires it nor pauses ticks
- restore coordination recovery, scoped resets, normalization migrations, split-brain recovery, session cleanup, and topology-changing scale changes must acquire this lock before they pause or mutate coordination state
- read-only low-impact scanners may run only when they are declared compatible with the active operation and still back off on Redis health degradation
- dashboards and health endpoints should expose a simple “maintenance in progress” signal while such a job is active
- fine-grained locks such as `session-cleanup-lock:<tenantId>` and `coord-reset:{tenantRegionTag}` should still be used inside the broader deployment-wide rule, but they do not replace it
- maintenance jobs must back off or abort when Redis health signals show elevated latency, `used_cpu_sys`, `used_memory`, or elevated error rates

Canonical maintenance-lock behavior:

- lock identity: one active record per Coordination Redis deployment / gameplay environment boundary
- minimum fields: `operationId`, `environmentId` (the canonical deployment/gameplay boundary), `operation`, `scope_type`, `tenantId`, `gameInstanceId`, `regionId`, `actor`, `startedAt`, `expiresAt`, `compatibilityClass`, and an evidence or incident reference; `tenantId`, `gameInstanceId`, and `regionId` are nullable or omitted for a deployment-wide lock, and each is required when its corresponding tenant, game-instance, or region scope is included
- token contract: `maintenanceLockToken` is an opaque, high-entropy, server-issued capability. The durable operation/lock record stores its token digest together with the operation, environment, scope, authenticated operator principal, expiry, and any absolute operation deadline; callers cannot mint the token or change those bindings by supplying matching-looking fields.
- trust and validation: the token is trusted only after the control plane resolves it to the active durable operation record and validates the presented `operationId`, environment, operation, scope, compatibility class, and authenticated operator against that record. The token value alone is never authorization.
- expiry and replay protection: the token is valid only while that exact operation remains active and unexpired. Mutating retries use the same operation/token and durable phase or idempotency record; a duplicate returns the recorded outcome without repeating an external effect, while a stale phase, terminal operation, expired token, or mismatched binding fails closed. Public-transition idempotency records are scoped by verb plus the exact operation-owned tuple, not by `operationId` alone. Refresh may extend the lease only before expiry and within the operation deadline; it does not create a new lock or revive an expired token.
- acquisition is fail-closed for incompatible operations; operators may only break the lock with an explicit stale-lock or break-glass evidence record
- acquisition owner: the single `coordination-maintenance recover --mode ...` operation acquires the lock for multi-step restore, reset, cleanup, migration, topology-change, and exceptional backup-related maintenance workflows
- refresh owner: every subsequent internal phase in that workflow refreshes the same lock using `maintenanceLockToken`; lock refresh is not a second independent acquisition or a public phase command
- success release owner: the recover operation's internal release phase is the canonical success-path release once the scope has safely returned to `RUNNING`
- failure disposition owner: a failed workflow retains its fence and maintenance lock while its durable operation record remains resumable. `coordination-maintenance release-lock ...` is the explicit audited abandonment step when an operator decides not to resume; it never runs automatically and does not make the scope safe to reopen
- exceptional backup-related maintenance treats lock-acquisition failure as a skipped/failed maintenance attempt; routine online backup health is independent of this lock and is measured through artifact freshness, lineage, integrity, and restore readability
- restore recovery and reset tooling must refresh or complete the lock before TTL expiry so another actor cannot start a conflicting maintenance workflow mid-flow

Canonical maintenance-active signal:

- metric: `coordination_maintenance_active{scope_type,scope_bucket,operation}`
- health/readiness projection: environments may expose an equivalent health field, but the metric name above is the canonical observability contract used by dashboards and Logging & Admin.

## Dual-Leader Detection and Coordination Reset

Goal: detect Redis split-brain or conflicting primaries and recover through a coordinated reset before duplicate logical effects can escape the tick subsystem.

Signals include:

- repeated stale-lease or unsupported-epoch outcomes for the same region
- PostgreSQL epoch validation rejecting conflicting writes
- Redis/Sentinel/Cluster alerts showing simultaneous primaries
- explicit dual-leader metrics such as `redis_coordination_dual_leader_detected_total`

Runbook:

1. fence every conflicting Redis primary at the infrastructure or network layer and retain external evidence that only the selected primary can accept coordination traffic; do not ask the affected Redis deployment to prove its own fencing
2. verify PostgreSQL authority and the surviving Redis primary have converged on one authoritative epoch
3. invoke one `coordination-maintenance recover --mode reset --scope region ... --preserve-sessions` operation for each safely isolated affected region; it clears region-local bindings and blocks normal command intake until preserved sessions complete rebind
4. if region isolation cannot be proved, retain the external primary fence and invoke one cluster-scoped `recover --mode reset --scope cluster --invalidate-sessions` operation; the cluster fallback keeps traffic blocked and invalidates gameplay sessions according to its explicit policy
5. let the recover operation own its internal pause/fencing, reset, reconciliation, rebind or invalidation, and smoke verification; then require the external public `resume(operationId, expectedPhase=awaiting_resume, maintenanceLockToken, evidenceRef)` gate before the separate internal success-release phase permits ticks or command intake to resume

## Normalization and Hash-Tag Migration

Goal: change how `tenantId` / `gameInstanceId` / `regionId` normalization and hash tags are formed without breaking shard-local assumptions.

### Runbook: Normalization Migration via Reset

1. define an immutable migration contract containing the old and new normalization/hash-tag versions, affected scope, maintenance CLI and control-plane build digests, every participating service image digest, and the Lua Script Registry version/digest
2. explicitly upgrade the maintenance CLI, control plane, services, and Lua registry as one coordinated version set; mixed-version migration is unsupported
3. schedule a maintenance window and persist the migration contract in the durable recovery operation before mutating Coordination Redis
4. invoke one bounded `coordination-maintenance recover --mode reset --operation migration --scope ... (--preserve-sessions|--invalidate-sessions)` operation and require the controller to validate that every participant reports the persisted version set and migration contract before reset begins
5. complete the protected Account authority/token cutover and establish replay-domain quarantine/fencing for that same durable recovery operation; persist immutable evidence binding both protections to its operation identity, migration contract, and affected scope, and keep admission closed while either result is missing or ambiguous
6. only after validating that bound evidence, start a fresh Coordination Redis deployment or logical database with an empty keyspace as the operation's recorded external-infrastructure step
7. rebuild coordination state from PostgreSQL plus fresh activity, then validate normalization, shard locality, Lua registry compatibility, and migration evidence before calling `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` with canonical `expectedPhase=ready_to_reopen`; continuation reaches `AWAITING_RESUME`, after which public `resume(... expectedPhase=awaiting_resume ...)` and the internal release phase are required
8. if the migration cannot safely continue, call the audited abort control with the exact recorded scope selectors: for a region migration, `coordination-maintenance release-lock --operation-id <operationId> --scope region --tenant <tenantId> --game-instance <gameInstanceId> --region <regionId> --maintenance-lock-token-file <permissioned-token-file> --reason <reason> --evidence-ref <evidenceRef>`; tenant migrations use `--scope tenant --tenant <tenantId>`, and cluster migrations use `--scope cluster`. It retains the fence and does not reopen traffic.

### Runbook: In-Place Normalization Migration

In-place normalization migration is not a first-implementation operator path. Use the reset-based migration above until a future slice ships dedicated rewrite tooling with scope inventory, follow-up handling, audit output, and post-migration verification.

This remains a future advanced option when dropping all coordination state is unacceptable:

1. freeze topology
2. pause or drain ticks and new commands for affected scope
3. rewrite keys from old hash tags to new ones using explicit-prefix tooling
4. validate shard-locality and smoke behavior
5. resume ticks and commands
6. perform any later cluster resharding as a separate maintenance step

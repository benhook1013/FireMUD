# FireMUD Redis Operations & Migrations

This document captures the canonical operator model for Coordination Redis. It complements the conceptual guarantees in [`system-architecture-redis.md`](./system-architecture-redis.md) and the Lua authoring patterns in [`system-architecture-redis-lua-patterns.md`](./system-architecture-redis-lua-patterns.md).

The invariants and contracts in [`system-architecture-redis.md`](./system-architecture-redis.md) remain authoritative. This doc focuses on named operational flows and migration posture.

## Implementation Status

The evidence-gated coordination recovery decision is accepted target state, but the complete durable recovery controller is not yet shipped or proven. The repository currently has partial control-plane pause/status support and supporting reset/reconciliation components, but does not yet expose the complete public `recover`, `continueRecovery`, `resume`, and audited abandonment surface end to end. The target remains one durable operation with one server-issued maintenance lock: `continueRecovery(... expectedPhase=ready_to_reopen ...)` may advance only to `AWAITING_RESUME`, public `resume(... expectedPhase=awaiting_resume ...)` re-verifies the evidence and active maintenance-lock state, durably records `RESUME_AUTHORIZED`, and only then permits the internal release phase to reach `finalized`. [Redis Reset and Recovery](./system-architecture-redis-reset-and-recovery.md) owns automatic replay/reset selection, external fencing, and scope escalation; this document owns the concrete operator sequence and tooling consequence. [ADR 0085](./decisions/adr-0085-evidence-gated-coordination-replay-and-fenced-reset.md) records the accepted rationale. The Account-owned authority-generation projection repair/replacement workflow described below is likewise target state and is not currently implemented and proven end to end. Current implementation and proof status must be reported separately from this accepted target contract.

## Default Operator Flows

Recovery mode is never selected from surviving Redis keys or an operator guess. The controller selects `replay_first` only from coherent durable epoch and batch evidence, with a bounded convergence budget, and selects `reset_first` for missing, contradictory, orphaned, duplicated, stalled, or non-progressing evidence. Disposable hints may be lost; `ACCEPTED_VOLATILE` command records may be intentionally lost only when unbound and no durable batch survives; `ACCEPTED_DURABLE` records require owner-defined replay or reconciliation. One durable maintenance operation and fence outside the Redis deployment serializes conflicting maintenance operations; Redis-local locks are execution aids, not recovery authority. The complete decision and proof gates are in [ADR 0085](./decisions/adr-0085-evidence-gated-coordination-replay-and-fenced-reset.md).

- select the appropriate AOF profile (`dev_local`, `hobby_self_hosted`, or `production_clustered`) and watch the associated size/restart targets
- once the bounded recovery controller is implemented and proved, run the named coordination reset and script-upgrade flows when metrics or the Lua Compatibility Registry indicate they are required

Other procedures and tuning advice here are advanced and should not be expanded into bespoke one-off sequences. New remediation paths should be expressed in terms of these named flows wherever possible.

Current operator boundary: every Coordination Redis `recover`, `continueRecovery`, `resume`, `release-lock`, destructive AOF reset, scoped reset, split-brain recovery, and normalization migration flow below is target-state-only. Because the complete Account-owned authority-generation and issued-token projection repair/replacement phase is not implemented or proven, current reset/reopen cannot enter that phase or treat its target evidence as present. Current operators must use the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback): preserve the AOF and incident evidence, keep affected admission and mutation fenced, use only the shipped pause/status and read-only inspection surfaces, and escalate rather than attempting an unavailable reset or unlock. Cache/Rate-Limit Redis is outside the Coordination Redis auth-repair restriction because it is a separate disposable deployment, but that separation is not permission for direct mutating resets: its current mutating reset is also unavailable until owner-supported tooling and the exact deployment/prefix inventory plus bounded apply/readback contract and proof exist.

## Documentation Map

- [`system-architecture-redis-metrics-catalog.md`](./system-architecture-redis-metrics-catalog.md)
  - Redis SLO metrics, tick/coordination metrics, cache metrics, alerting signals, and coordination size/complexity budgets
- [`system-architecture-redis-script-rollout-and-compatibility.md`](./system-architecture-redis-script-rollout-and-compatibility.md)
  - Lua compatibility modes, rollout matrix, registry expectations, and script upgrade runbooks

### Current Execution Boundary

Every numbered procedure and runbook below—including recovery, reset, promotion escalation, mis-sharded-key cleanup, dual-leader recovery, and normalization migration—is target-state design, not a current operator instruction. Its imperative wording does not authorize execution. Until the durable controller, owner tooling, and end-to-end proof exist, use the [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback): preserve AOF and incident evidence, keep affected admission and writers fenced, use only the shipped instance pause/status and read-only inspection surfaces, and escalate rather than mutating or unlocking Redis.

This target-state blanket explicitly excludes the linked **Current Operator Fallback**, which remains the current operator guidance while the target controller and owner tooling are unavailable or unproven. The fallback is preservation, fencing, read-only inspection, and escalation; it is not an executable reset or unlock procedure.

## Canonical Coordination Reset Sequence

This section is the normative source for the multi-step Coordination Redis reset/recovery workflow. Other runbooks should point here and then describe only scope choice, session policy, evidence, and scenario-specific abort or storage steps.

Canonical public operation:

`coordination-maintenance recover --mode reset --scope ... <session-policy-option>`

Before compare-and-match, normalize the operator proposal/expected mode to the canonical internal classification: CLI `--mode replay-first` maps to internal classification `replay_first` and the serialized operation/maintenance-lock field `compatibilityClass=replay-first`, CLI `--mode reset` maps to internal classification `reset_first` and the serialized operation/maintenance-lock field `compatibilityClass=reset-first`, and CLI `--mode session-schema-cleanup` maps to internal and serialized `cleanup` (`compatibilityClass=cleanup`). Unknown or noncanonical mode values fail closed before a durable operation record or maintenance lock is created. This normalization does not select the recovery mode; the controller remains the selection authority and compares the normalized proposal with its evidence-derived classification.

`--mode reset` is the operator's proposed/expected classification for compare-and-match, not selection authority. The controller derives `replay_first` or `reset_first` from durable evidence and rejects a proposal that contradicts that result; a bounded replay that later loses progress upgrades the same operation and maintenance lock to `reset_first` under the documented audit rule.

Choose exactly one session-policy option for each reset: `--preserve-sessions` or `--invalidate-sessions`. These are separate valid command forms, not a shell alternation expression.

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
11. `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` with canonical `expectedPhase=ready_to_reopen`, advancing only into `AWAITING_RESUME`
12. public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` with canonical `expectedPhase=awaiting_resume`, which re-verifies the recorded scope, immutable evidence, and active maintenance-lock state, then durably records `RESUME_AUTHORIZED` without releasing the lock or traffic fence
13. internal resume-and-success-release phase

Public `resume(... expectedPhase=awaiting_resume ...)` revalidates the full current reopen predicate at the time of the call, not merely the phase evidence recorded by `continueRecovery`: recorded scope and affected inventory, active maintenance lock and gameplay fence, all applicable reset/cleanup and Account projection gates, replay quarantine/consume proof, the recorded session policy with complete coverage plus either complete invalidation evidence for `--invalidate-sessions` or complete preserved-session rebind evidence for `--preserve-sessions`, the complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist), representative smoke, and every release precondition must still pass. Any unavailable, stale, regressed, contradictory, or ambiguous dependency returns `AUTH_UNAVAILABLE` or the owning fail-closed outcome and leaves the operation fenced.

The internal pause-and-lock phase is not a public command or a standalone operation. Only `recover` creates the durable `operationId` and maintenance-lock identity. An interrupted workflow below `ready_to_reopen` retries through controller-owned internal phase reconciliation for that same operation; public continuation is unavailable until the controller durably reaches canonical `ready_to_reopen`. Before release authorization, an eligible workflow may instead be explicitly abandoned through the audited maintenance-lock release control.

Scope-safe coordination cleanup is separate from Account-owned auth, generation, and connect-token projection repair. Completing the cleanup phase, or observing an empty Redis keyspace, never proves those projections were repaired. The operation must receive Account's exact durable repair result and read back every affected projection before `ready_to_reopen` or any release authorization; if Account repair or proof is unavailable, stale, malformed, or ambiguous, the affected scope remains fenced and cannot continue or release.

Supported external controls use the following canonical API-to-CLI mapping:

- `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` maps to the sole public continuation control and is valid only after the same durable operation has reached canonical `ready_to_reopen`. It uses canonical `expectedPhase=ready_to_reopen`, may advance only into `AWAITING_RESUME`, and must match the active operation, phase, server-issued lock, and immutable evidence. Controller restart, external infrastructure work, or failure in an earlier phase is resumed by controller-owned internal retry under the same operation rather than by this public control. It is not the public release authorization.
- `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` maps to `coordination-maintenance resume --operation-id <operationId> --expected-phase <expectedPhase> --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>` and is a separate post-recovery safety gate. It uses canonical `expectedPhase=awaiting_resume`, resolves the operation's recorded scope, re-verifies the active maintenance-lock state and every required immutable evidence group, validates the exact operation, lock, and authenticated actor, durably audits the result, and atomically records `RESUME_AUTHORIZED`. Only then may the internal release phase run; the public call does not release the lock or reopen traffic. Any mismatch or missing evidence fails closed.
- `releaseMaintenanceLock(operationId, scope, maintenanceLockToken, reason, evidenceRef)` maps to `coordination-maintenance release-lock --operation-id <operationId> --scope <scope> ... --maintenance-lock-token-file <permissioned-token-file> --reason <reason> --evidence-ref <evidenceRef>` for audited operator abandonment. The concrete scope selector (for example, `--tenant <tenantId> --region <regionId>` for a region scope) must match the durable operation exactly. It retains the paused/fenced state and never reopens the scope.

No public command may select or invoke an internal phase. The CLI exposes the same controls as `continue-recovery`, `resume`, and `release-lock`; the API names above remain the canonical control-plane names.

The audited abandonment control never runs automatically; an operator must supply the matching operation, scope, maintenance-lock token, reason, and immutable evidence reference.

Rules:

- The operation record and maintenance-lock authority live in a durable control store outside the target Redis deployment so the workflow remains resumable after that deployment is replaced or emptied.
- The internal pause-and-lock phase must drive the chosen scope to canonical `PAUSED` before storage-level wipe or prefix deletion occurs.
- Capture the `maintenanceLockToken` returned by that phase and pass it to every subsequent internal phase; no phase reacquires the deployment lock independently.
- The internal epoch-bump and coordination-reset phase is the only phase that bumps `region_epoch` and emits authoritative old/new epoch evidence for downstream reconciliation.
- The early epoch-bump/reset phase may perform only scope-safe coordination cleanup after the scope is fenced. It must not delete or recreate a full Coordination Redis deployment or AOF volume.
- Internal ledger reconciliation and command convergence are required before traffic resumes; `replay_first` workflows use those same phases without a preceding epoch bump, but reset workflows must not skip them.
- Internal metadata initialization re-establishes `tick:{tenantRegionTag}:meta` from the durable baseline after scope-safe cleanup and, where applicable, the external AOF/deployment reset; `{tenantRegionTag}` is the opaque full-scope tag for `<tenantId, gameInstanceId, regionId>`. A cold-start hot-path exception requires explicit proof that the exact environment and deployment are a documented non-reset profile; without that proof, metadata initialization remains recovery-owned and the scope stays fenced.
- Reset-mode recovery requests Account Service to rebuild and verify the live legacy `session:auth:account:*` and `session:auth:tenant:*` projections used by the current runtime, the target `session:auth:generation:*` issuer/account/tenant/membership projections from Account durable authority, and the target-only affected `session:auth:token:<tokenHash>` issued-token projections when that registry cutover is implemented; before the smoke phase it must preserve and read back the unversioned legacy `session:connect-token:tenant:<tenantId>:account:<accountId>:scope:<sha256(connectScopeId)>:request:<sha256(requestId)>` map through its configured TTL/retry horizon, then have Account owner-clean it from exact inventory/readback. The legacy map is never parsed, promoted, or used as repair input. Exact repair and readback apply only to the target `session:connect-token:v1:tenant:<tenantId>:account:<accountId>:scope:<scopeHash>:request:<requestHash>` projection, from the durable Account operation/envelope; recovery awaits that durable result and verifies its returned freshness/generation and exact target projection evidence, and it is not a writer of Account-owned projections. Target values require `schemaVersion: "connect-token-issuance-result-v1"`; target readers never fall back to the legacy map. Account-owned generation-projection repair may use idempotent set-if-greater only for a missing or lower Redis generation projection; this allowance excludes target `session:connect-token:v1:*` exact-result projections, which require exact durable operation/envelope repair. If a Redis generation is greater than Account durable authority, it is poisoned; the workflow must quarantine or replace that exact scope through an Account-owned audited workflow, recreate it from durable authority, and emit immutable per-scope repair evidence and verification rather than preserving it with set-if-greater. Region- and tenant-scoped resets preserve unaffected Account-owned records but still require exact-generation and connect-token projection validation; a cluster reset verifies the Account repair/reset cutover that preceded physical cleanup, then registers replacement issued-token and exact connect-token issuance-result projections and proves exact-token/connect-result validation before representative-region smoke. A missing or untrusted target connect-token result must be recovered exactly or remain fail-closed until its connect-scope/request identity expires; it must not be reminted from Redis or a JWT. The phase emits immutable projection evidence and fails closed on any missing, stale, malformed, mismatched, or poisoned generation, token, or connect-token result record.
- Internal session rebinding is conditional and occurs only after the Account projection phase succeeds. Every region- and tenant-scoped reset records either `--preserve-sessions` or `--invalidate-sessions`; only the former permits preserved-session rebind, and neither scope infers the policy. A cluster-scoped reset accepts only explicit `--invalidate-sessions`, because its Account token-registry cutover drops the old exact-token records and requires reauthentication before replacement registration.
- Account-wide recovery must acquire the account-scoped admission/creation fence `accountAdmissionFence` before capturing `inventorySnapshotRevision`, reject or queue new bindings for that account while the fence is held, and retain it through exact full-key readback and durable `coverageGeneration`. Child-owned account binding creation/reconciliation CAS, retry, acknowledgement, and readback carry the live fence while coverage is active; the independent parent edge and parent-consumption record carry the parent tuple plus the child result's fence proof. At the coverage-proven transition, the live fence is released and its exact value is retained as `historicalAccountAdmissionFence`; child results, edge creation/update and retries, readbacks, acknowledgements, and parent-consumption CAS exact-compare that historical value. The fence is distinct from the deployment maintenance lock, parent/child operation fences, and `coverageFence`.
- Coverage carriers use `coverageGeneration=NOT_YET_ISSUED` from child creation through pre-coverage readback and assign a concrete generation only in the atomic coverage-proven transition. A linked child result is parent-independent; the parent tuple remains only on the independent parent edge and the parent's exact consumption record. The canonical account acknowledgement uses `accountCoverageState=ACTIVE` with the live `accountAdmissionFence` while coverage is active, then `accountCoverageState=HISTORICAL` with the exact released `historicalAccountAdmissionFence` after success. A flow that acquired account-wide fencing never changes to `accountCoverageState=NO_ACTIVE_ACCOUNT_WIDE_FLOW`; only flows that never acquired account-wide fencing carry that state and both account-fence fields as `NOT_APPLICABLE`.
- A fresh Coordination Redis keyspace reset, including destructive `FLUSHALL` or AOF reset, is permitted only after the protected Account authority/token cutover and replay-domain quarantine/fence have completed and their immutable pre-wipe evidence has been recorded. A node-level action must resolve blast radius as `cluster` or carry fresh `physical-dedication-proof/v1` with an independent single-use challenge/nonce, expiry, trusted verifier, and exact `operationId`, `operationFence`, deployment identity, node identity/node set, resolved scope, and physical-dedication binding; region/tenant handoff is rejected before external action without that proof. The pre-wipe external evidence is limited to the canonical immutable handoff fields: old deployment identity, intended target identity, authorized operator, action, timestamp/time, tooling digest, authorization, exact resolved scope, and old-deployment fencing, all bound to the same durable `operationId` and maintenance-lock digest or opaque reference; post-reset facts are not required or available at this gate. The destructive action is the external AOF/deployment-reset phase, never an early pause/reset step, and an empty keyspace cannot authorize it or substitute for pre-wipe evidence.
- Before destructive handoff or startup, the controller creates `replacementVerificationChallenge/v1` with an independent nonce, issue/expiry times, single-use state, source/target deployment identities, mode, operation tuple, and resolved scope. After startup, only the trusted deployment attestor atomically consumes that challenge and emits the signed result. The selected mode and target evidence must pass the complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist), including its exact target identity/generation, canonical node set, Redis build, Lua registry, startup state, separate digests, mode-specific startup attestation, positive probes, destructive negative probes, and cleanup/readback evidence. Protected admission remains closed through these gates; only `continueRecovery(... expectedPhase=ready_to_reopen ...)` may advance to `AWAITING_RESUME`.
- The internal resume-and-success-release phase is unreachable until the external public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` control records `RESUME_AUTHORIZED` for the same operation, expected phase, recorded scope, lock, both immutable external evidence groups, Account projection evidence, and replay-domain proof where applicable. Its durable controller transition is atomic, but the external Game Session, Coordination Redis, ingress, affected-scope, and maintenance-lock effects are not one distributed transaction. The phase must durably audit the authorization, retain the fence and lock until each release postcondition is observed, idempotently apply and read back each required postcondition, and may record terminal `SUCCEEDED` only after durable observation evidence exists for every applicable release postcondition. If a later effect fails, is missing, stale, or ambiguous after an earlier effect was released, the operation enters durable `PARTIAL_RELEASE_RECONCILING` with `status=RUNNING`: unreleased effects remain fenced, already-released workloads are re-fenced or otherwise contained, and traffic and normal side effects stay closed until complete containment is observed. Retry uses the same operation and per-effect identities, reconciles already-applied effects without duplication, and may resume release only after the partial-release inventory and containment evidence are durable. This phase is not eligible for audited abandonment or `release-lock`; remediation continues through the same durable operation until every release postcondition has durable successful observation evidence.
- If the workflow aborts before `RESUME_AUTHORIZED`, operators may use the audited `coordination-maintenance release-lock ...` control rather than inventing an alternate unlock sequence. Once release authorization is recorded, `release-lock` is prohibited even before `PARTIAL_RELEASE_RECONCILING`; every failure is retried or reconciled by the internal release worker through the same durable operation until containment and every release postcondition have durable successful observation evidence.

### External AOF-Reset Handoff

The trusted attestor's single-use challenge consumption and signed-result publication follow the durable idempotent `challengeId` contract in [Coordination Redis Ops Access & Tooling](./system-architecture-redis-ops-access.md#post-reset-replacement-verification-gate). The recovery operation remains fenced until it durably accepts the persisted result; a lost response is retrieved by `challengeId`, never replayed or replaced.

The post-reset gate is the complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist), including separate ACL and observed-configuration digests, mode-specific startup attestations, all positive and destructive negative probes, and cleanup/readback evidence. This handoff does not copy or shorten that checklist.

An AOF reset or replacement of the Coordination Redis deployment is an external infrastructure step inside the durable recovery operation. It must not race the recovery controller or use the empty keyspace as evidence that the operation is paused:

This handoff uses the complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist). The selected `SAME_DEPLOYMENT` or `REPLACEMENT_DEPLOYMENT` mode must pass its exact target-bound startup attestation and every checklist item before continuation; a copied probe subset or combined digest value cannot satisfy the gate.

1. The recover operation first durably records the resolved operation, scope inventory, maintenance-lock digest/fence, expected target deployment identity, and a paused/fenced phase in the external control store. Protected admission and affected coordination writes remain closed; any early reset work is limited to scope-safe cleanup.
2. Before destructive reset, the operation establishes the protected Account authority/token cutover and replay-domain quarantine/fence, records their immutable evidence, and verifies that the cutovers are bound to the same operation and scope. It also records one immutable pre-wipe handoff record containing the old deployment identity, intended target identity, authorized operator, action, timestamp/time, tooling digest, authorization, exact resolved scope, and old-deployment fencing, with every field bound to the same durable `operationId` and maintenance-lock digest or opaque reference; the plaintext token is never recorded. For a node-level action, the handoff additionally requires the fresh exact `physical-dedication-proof/v1` described above; a region/tenant scope without it is rejected. Before external action or target startup, the controller durably creates `replacementVerificationChallenge/v1`; the nonce, expiry, mode, source/target identities, operation tuple, and scope are not caller-supplied. An AOF wipe must not occur before `scope_paused_and_locked` and these same-operation/same-lock fences and evidence exist.
3. The authorized operator performs the AOF reset or replacement only after the pre-wipe authorization/fencing evidence has been accepted. That evidence cannot include endpoint, observed configuration, empty-keyspace, or health facts that do not exist until replacement startup.
4. The target starts with the required empty keyspace and protected credentials/ACLs. Only the trusted deployment attestor, through the authenticated deployment-control channel, atomically consumes the single-use challenge and emits the signed result; no controller, operator, or other caller may consume it or emit a substitute record. The target identity/generation, canonical node set, Redis build, Lua registry, startup state, and mode-specific empty-startup attestation must bind to the same challenge, operation fence, target, and scope. The operation must pass the complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist); Redis key absence or an operator observation alone is not the pause, ownership, or empty-startup proof.
5. The recovery controller never consumes the challenge. It independently verifies the attestor-produced evidence, binds both evidence groups to the same durable `operationId`, exact resolved scope, deployment boundary, operation fence, and maintenance-lock digest or opaque reference derived from the same server-issued `maintenanceLockToken`, and durably records that verification before the operation reaches `ready_to_reopen`; public `resume(... expectedPhase=awaiting_resume ...)` re-verifies the evidence and active maintenance-lock state, then durably records `RESUME_AUTHORIZED` as the release gate without consuming the challenge or re-emitting the attestation. Only after that durable authorization may the internal release phase run. It never reconstructs the operation or releases the fence from the new empty keyspace. This handoff is durable operation evidence, not an additional public `continueRecovery` phase.
6. A missing, stale, mismatched, or ambiguous evidence group or maintenance-lock state leaves the operation paused and gameplay admission closed. Internal retries use the same operation-owned state and do not repeat the AOF reset or begin rebuild concurrently. The controller must re-verify both evidence groups and the active maintenance-lock state before public `resume` can record `RESUME_AUTHORIZED`; the only public `continueRecovery` invocation remains the later `expectedPhase=ready_to_reopen` transition into `AWAITING_RESUME`, and the separate internal release is still required before reopening.

An actual destructive PostgreSQL rewind into the authoritative player-facing target is also under the deployment maintenance-lock contract. Its complete controller-owned restore-attempt binding must retain the same `operationId`, `environmentId`, resolved scope, lock identity, and authenticated actor as the active lock record, with the lock identity represented by the controller-issued `maintenanceLockToken` and its durable `maintenanceLockTokenDigest` (or the canonical opaque lock reference where that representation is selected), together with the complete restore-attempt tuple defined by [Backup & Disaster Recovery](./system-architecture-backup-recovery.md). Immediately before decompressing the artifact, and again immediately before invoking `psql`, the controller must resolve the issued token to one active, unexpired durable lock and exact-match its operation ID, environment, operation, scope, compatibility class, actor, deadline, and token digest/opaque reference to that restore-attempt binding. A missing, expired, replaced, or otherwise mismatched lock fails closed without decompression or `psql`. This actual-loss gate does not add authorization to per-artifact readability checks or isolated production-equivalent drills; those non-authorizing paths retain their existing artifact, tooling, and isolated-boundary checks.

The restore-attempt worker must maintain and renew that same maintenance lock and gameplay fence for the entire destructive window, including artifact decompression and the complete `psql` invocation; it may not acquire a second lock, release the lock between those steps, or treat a new token as continuity. Renewal and ownership checks must re-resolve the issued `maintenanceLockToken` and exact `maintenanceLockTokenDigest`/opaque lock identity at bounded intervals and immediately before each destructive write window. Expiry, replacement, release, or any renewal failure is an ownership-uncertain result: stop decompression or terminate `psql` before further destructive writes where possible, do not start or resume another attempt, and classify the attempt using the existing restore-attempt uncertainty contract (`not started`, `failed before apply`, a specific resumable checkpoint, or `unknown/partially applied`) rather than treating tuple matching as retry authority. Quarantine and the gameplay fence remain closed on every ambiguous result. The controller must retain the maintenance-lock record and fence through durable terminalization of that restore-attempt state; it must not release the lock or admit a competing maintenance operation before that terminal state and the required readback are durable.

## Redis SLOs & Budgets

This section centralizes the normative targets for Redis behavior that other docs reference. Individual environments may tune concrete values, but changes should be treated as deliberate SLO updates rather than silent drift. The loss-window and replica-promotion comparisons are scoped to one Coordination Redis deployment, its canonical environment class, and its active configuration/ruleset; an environment may claim a production-like SLO only when its profile is eligible and its evidence explicitly permits that claim. Ephemeral preview/CI stacks and explicitly opt-out or otherwise non-eligible environments must not be used to validate the measured SLO; use their declared reset-tolerance class and latency/recovery evidence instead.

### Coordination Redis Core Targets

- **Unreplicated coordination-write window**
  - only an eligible profile may compare the measured `redis_unreplicated_write_window_ms{scope}` exposure or replica-promotion evidence with `redis_unreplicated_write_window_slo_ms` or declare its breach; ephemeral or opt-out profiles use reset-tolerance and latency/recovery evidence rather than this measured-SLO breach path
  - production-like profiles define `redis_unreplicated_write_window_slo_ms` from measured AOF, replication, promotion, and failover evidence; tick cadence does not set the value
  - `ticks_exposed = ceil(redis_unreplicated_write_window_slo_ms / tick_interval_ms)` is diagnostic only and is not a product RPO
  - ephemeral profiles may accept wider or unbounded exposure but must be clearly labeled and cannot validate production-like loss-window SLOs
  - a sustained breach is a coordination SLO violation: automation widens durable reconstruction/terminalization checks and reports affected command, effect, retry, and correctness-timer counts
  - class-specific outcomes in [ADR 0058](./decisions/adr-0058-class-specific-redis-loss-outcomes.md) remain mandatory during breach; the window never authorizes silent loss or double application
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

This destructive AOF-reset runbook is target-state-only and unavailable until the bounded recovery controller and its protected-domain evidence gates are implemented and proved. Current operators must use the [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback) and must not execute the steps below.

1. Confirm via metrics or `INFO` that AOF size, restart time, or daily growth is outside the agreed budget.
2. Schedule a maintenance window.
3. Keep the control-plane path and maintenance tooling alive long enough to execute the canonical reset handshake; do not stop the very components required to pause, fence, audit, and verify the workflow.
4. Start the [Canonical Coordination Reset Sequence](#canonical-coordination-reset-sequence) for the affected scope with exactly one explicit session-policy choice, `--preserve-sessions` or `--invalidate-sessions`.
5. Complete every protected-domain cutover owned by Account Service and any replay-domain quarantine/fence before destructive storage cleanup; the recover operation must receive and verify the returned Account projection and replay evidence while admission remains closed.
6. Perform the storage-level reset only in the external AOF/deployment reset handoff after `scope_paused_and_locked`, the protected-domain cutovers, replay fencing, immutable pre-wipe authorization/fencing evidence, and any required `physical-dedication-proof/v1` are established for the same operation and lock, by stopping Redis, deleting or recreating the AOF volume, and restarting Redis with the desired AOF configuration. Create `replacementVerificationChallenge/v1` before the handoff; after startup require the trusted attestor's mode-specific `post_reset_replacement_verification/v1` and the complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist). No lesser same-deployment logical-database reset path is valid. The earlier internal reset phase may perform only scope-safe cleanup; a full-AOF deletion must never precede the protected-domain cutovers or be treated as their authorization boundary.
7. Allow the single recover operation to complete its internal reconciliation and smoke-check phases to `ready_to_reopen`, then require public `continueRecovery(... expectedPhase=ready_to_reopen ...)` to reach `AWAITING_RESUME`, followed by public `resume(... expectedPhase=awaiting_resume ...)` before the separate internal release phase may finalize and permit ticks or player traffic.
8. If the workflow aborts, use only the separately audited maintenance-lock release control; do not invoke an internal recovery phase as a public command.

Manual AOF surgery is not supported. Either the AOF is trusted and replayed as-is, or it is discarded and Redis restarts from a clean keyspace.

## Cache/Rate-Limit Redis Reset

Goal: provide a simple, explicit runbook for resetting Cache/Rate-Limit Redis without entangling it with Coordination Redis resets.

Cache/Rate-Limit Redis is fully reset-tolerant for the prefixes listed in [`system-architecture-redis-cache.md`](./system-architecture-redis-cache.md) and the reset policy matrix in [`system-architecture-redis-reset-and-recovery.md`](./system-architecture-redis-reset-and-recovery.md). A reset:

- applies deletion only to the exact registered key patterns in the immutable Cache/Rate-Limit prefix inventory, under the bounded apply/readback contract; catalog family labels such as inventory, character-cache, world-dynamic, room, view:room-look, chat, and ratelimit are not executable deletion selectors, and broad `automation:*` matching remains prohibited
- does not affect Coordination Redis keys such as `tick:*`, `timer:*`, `retry:*`, `session:*`, `tick-executor-lease:*`, `automation:timer:*`, or `script-scheduler:*`
- increases load on backing services temporarily but must not lose authoritative game data

The current `automation:queue:{tenantInstanceTag}:*` family is a non-authoritative Cache/Rate-Limit projection. The target rebuild/index operation may index only safe durable `PENDING_EVALUATION` work, or an `EVALUATING` row whose owner lease was reconciled/reclaimed as non-stale by the owner under a fenced compare-and-set. Status, lease-owner, freshness, and fence checks must be one owner CAS/reconciliation boundary; stale, unresolved, or ambiguous rows remain excluded. Current `rebuildPendingWorkItemIndex` unconditionally selects both statuses and has no stale, owner, or compare-and-set gate, so queue-prefix reset/rebuild and resume are unavailable and must fail closed; a non-atomic status preflight cannot authorize a safe PENDING-only path. Redis queue payloads are never recovery authority. The reserved `automation:timer:{tenantRegionTag}` and `script-scheduler:{tenantRegionTag}:lastTickId` timer/checkpoint projections remain target-only and unavailable.

Do not use the generic Cache/Rate-Limit reset procedure below for the current Automation queue family. A queue-prefix flush, safe PENDING-only rebuild, and resume sequence is target-only until the owner-reconciled status/CAS recovery contract is implemented and proven; current queue incidents remain paused and fail closed under the [Automation queue incident runbook](./system-architecture-redis-incident-runbook.md#automation-queue-schema-mistakes).

### Runbook: Environment-Scoped Cache Reset

Mutating Cache/Rate-Limit reset is currently unavailable: the repository does not ship the owner-supported tooling that can bind a reset to the Cache/Rate-Limit deployment, an exact registered prefix inventory, and immutable apply/readback evidence. Do not turn the catalog labels or fallback below into ad-hoc `redis-cli`, `SCAN`, prefix-delete, `FLUSHDB`, or `FLUSHALL` instructions. The required target contract is owned by [Coordination Redis Ops Access & Tooling](./system-architecture-redis-ops-access.md#canonical-control-plane-and-cli-contract), which requires separate owner tooling and exact inventory/apply/readback postconditions for any future cache mutation.

Current safe fallback:

1. Preserve the Cache/Rate-Limit keyspace and incident evidence; identify the deployment and verify that it is distinct from Coordination Redis.
2. Stop or make the affected cache/rate-limit use non-accepting through an existing deployment/readiness control where one is available; if no such control exists, leave the deployment untouched, assess the temporary backing-service and rate-limit impact, and escalate to the cache owner/authorized infrastructure operator.
3. Leave the Cache/Rate-Limit deployment untouched: a current clean replacement or reset is not a supported fallback mutation. Keep the affected use non-accepting where an existing readiness control permits, preserve evidence, and escalate until owner-supported tooling provides exact deployment/prefix inventory, authorization, bounded apply/readback evidence, and verification. No disposable-environment assertion creates a separate replacement exception.
4. Monitor cache hit/miss behavior, DB/service load, and rate-limit behavior during the incident and after any future owner-supported replacement, and fix the underlying key-shape, TTL, or cache-design issue if it triggered the incident.

Once owner-supported tooling exists, it must enumerate only the exact registered cache-family inventory, apply bounded deletion under its deployment/exclusion lock, and read back the declared absence/result before completion. It must not match `automation:queue:{tenantInstanceTag}:*`; that current family follows the unavailable queue recovery path above.

## Reset Tolerance Classes

FireMUD classifies coordination-backed workloads by reset tolerance:

- **reset-tolerant**
  - tick locks, `pending` entries, timers, retry queues, and conflict metadata
- **reset-sensitive**
  - gameplay session prefixes such as `session:game:*`; current live Account auth uses the legacy `session:auth:account:*` and `session:auth:tenant:*` projections, while target-only Account auth families are `session:auth:token:*` and `session:auth:generation:*`; the Account-owned unversioned legacy `session:connect-token:*` result map and target `session:connect-token:v1:*` issuance-result projection are also reset-sensitive
  - future automation queues explicitly assigned to Coordination Redis by their owner contract (the current `automation:queue:{tenantInstanceTag}:*` family is a Cache/Rate-Limit projection and follows the unavailable queue-reset path above), or non-critical analytics that can be recomputed or re-enqueued
- **reset-forbidden**
  - future workloads that would treat Redis as a durable component of a long-lived contract

Any new feature that wants to use Coordination Redis must declare its reset tolerance class in design docs and, where necessary, use separate deployments/prefixes or stronger durable stores.

## Replica Promotion and Missed Writes

> This runbook inherits the [current execution boundary](#current-execution-boundary); its imperative steps are target-state only.

Goal: handle Redis replica promotion without violating tick and replay guarantees.

Facts:

- Coordination Redis uses asynchronous replication.
- A promoted replica may be missing recent coordination writes.
- The new primary’s keyspace is authoritative after promotion.

Behavior:

- modest promotion lag contributes to the measured unreplicated-write exposure
- replay safety is preserved by lease/lock/epoch validation and PostgreSQL-backed effect ledgers

Runbook:

1. Monitor the bounded `redis_replication_lag_ms{redis_role="coordination",scope=~"$scope"}` metric as the canonical promotion-lag metric, with `redis_replication_offset_lag_bytes{redis_role="coordination",scope=~"$scope"}` as supporting evidence. `scope` is the documented deployment/environment/ruleset mapping; exact `nodeId` and `upstreamNodeId` values belong in structured logs or control-plane evidence, not Prometheus labels.
2. Use the pre-aggregated worst eligible candidate-promotion lag already exported for this deployment/environment/ruleset scope and compare it against that scope's measured unreplicated-write-window SLO; the exact candidate and node identities remain control-plane or structured-log evidence:
   - acceptable: `redis_replication_lag_ms <= 0.5 * redis_unreplicated_write_window_slo_ms`
   - warning: `0.5 * redis_unreplicated_write_window_slo_ms < redis_replication_lag_ms < redis_unreplicated_write_window_slo_ms`
   - red: `redis_replication_lag_ms >= redis_unreplicated_write_window_slo_ms`
3. If lag is in the acceptable band, promotion is acceptable from a replay perspective.
4. If lag is in the warning band, investigate immediately and delay promotion unless the failover risk of waiting is worse than accepting a wider measured exposure.
5. If lag crosses the red line, either wait for recovery or treat promotion as a deliberate drop-recent-coordination-state event handled by one bounded `coordination-maintenance recover --mode reset --scope <scope> <session-policy-option>` operation under the normal maintenance-lock and epoch-fencing workflow, with exactly one of `--preserve-sessions` or `--invalidate-sessions` selected.

## Key Shape Mistakes and Coordination Resets

> This runbook inherits the [current execution boundary](#current-execution-boundary); its imperative steps are target-state only.

Coordination keys are treated as reset-tolerant, volatile, and backed by PostgreSQL plus replay.

Before performing any coordination reset, operators should walk a short pre-reset validation checklist:

- confirm PostgreSQL is healthy
- verify tick effect ledger status for the target scope
- ensure game traffic is quiesced for the affected scope
- record operator intent and affected scope

### Scoped Tick Effect Ledger Reconcile

Every coordination reset that affects tick execution must include the Game Session–owned tick-effect-ledger reconcile step. Old-epoch rows may become `APPLIED` or `ABANDONED` only with the evidence and authority-fenced attestation required by the [Inconclusive Old-Epoch Reconciliation Policy](./system-architecture-tick-failures-and-operations.md#inconclusive-old-epoch-reconciliation-policy); inconclusive work remains a reconciliation-required non-terminal marker under its original `EffectId`, and reset tooling must not bulk-terminalize it or let new executors resume it as current-epoch `SCHEDULED` work.

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
- do not include the Account-owned legacy `session:auth:account:*` or `session:auth:tenant:*` families; their reset, repair, and revocation consequences remain in the [canonical reset matrix](./system-architecture-redis-reset-and-recovery.md#global-index-family-recovery-consequence) and Account-owned auth workflow
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

Request validation requires the exact tenant scope, tenant identifier, and explicit `--invalidate-sessions` policy. It rejects a missing policy, `--preserve-sessions`, region scope, and cluster scope before creating an operation record, acquiring a maintenance lock, pausing the tenant, or starting any workflow phase.

The bounded high-level `recover` operation owns the lock, durable cursor/continuation state, internal session-cleanup phase, continuation, abort, and release behavior for a mutating cleanup. Ad hoc cleanup Jobs must call this operation rather than encoding their own lock, continuation, or abort behavior. `session-cleanup` is an internal phase name, not a public command; retry uses the same `operationId` and server-issued `maintenanceLockToken` through `continueRecovery`, not a caller-supplied resume token.

`--dry-run` is a direct terminal no-mutation exception only when discovery and validation acquire neither a gameplay fence nor a maintenance lock and produce no mutation or release effect. Only that path transitions directly to terminal phase `finalized` with terminal operation status `SUCCEEDED`; it never enters `AWAITING_RESUME`, invokes `continueRecovery` or public `resume`, or uses `release-lock` to finish, and it is not a release or traffic-open authorization. If a dry-run request acquires either a fence or lock, or performs any mutation, it is not eligible for the exception and must use the normal `continueRecovery(... expectedPhase=ready_to_reopen ...)` / `resume(... expectedPhase=awaiting_resume ...)` and internal release lifecycle, including all `finalized` postconditions.

When a mutating cleanup workflow reaches `AWAITING_RESUME`, the public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` gate must use `expectedPhase=awaiting_resume`, resolve the operation's recorded tenant scope, and verify the immutable cleanup completion evidence for the exact tenant and operation, including visited prefixes, scanned/deleted counts, final cursor or continuation state, schema disposition, and completion reason. Missing, partial, ambiguous, or mismatched cleanup evidence retains the lock and fence and fails closed. This completion-evidence gate does not apply to the terminal dry-run path.

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
- minimum fields: `operationId`, `environmentId` (the canonical deployment/gameplay boundary), `operation`, `scope_type`, `tenantId`, `gameInstanceId`, `regionId`, `actor`, `maintenanceLockTokenDigest` (or the canonical opaque lock reference where that representation is selected), `startedAt`, `expiresAt`, `compatibilityClass`, and an evidence or incident reference; `tenantId`, `gameInstanceId`, and `regionId` are nullable or omitted for a deployment-wide lock, and each is required when its corresponding tenant, game-instance, or region scope is included
- token contract: `maintenanceLockToken` is an opaque, high-entropy, server-issued capability. The durable operation/lock record stores its token digest together with the operation, environment, scope, authenticated operator principal, expiry, and any absolute operation deadline; callers cannot mint the token or change those bindings by supplying matching-looking fields.
- trust and validation: the token is trusted only after the control plane resolves it to the active durable operation record and validates the presented `operationId`, environment, operation, scope, compatibility class, and authenticated operator against that record. The token value alone is never authorization.
- expiry and replay protection: the token is valid only while that exact operation remains active and unexpired. Mutating retries use the same operation/token and durable phase or idempotency record; a duplicate returns the recorded outcome without repeating an external effect, while a stale phase, terminal operation, expired token, or mismatched binding fails closed. Public-transition idempotency records are scoped by verb plus the exact operation-owned tuple, not by `operationId` alone. Refresh may extend the lease only before expiry and within the operation deadline; it does not create a new lock or revive an expired token.
- acquisition is fail-closed for incompatible operations. Overriding an active lock held by a competing operation requires an explicit stale-lock or break-glass evidence record. The owning active resumable operation instead uses normal audited `release-lock` abandonment with the exact operation, token, scope, reason, evidence, phase, and fencing checks; that is not a competing-lock override.
- acquisition owner: the single `coordination-maintenance recover --mode ...` operation acquires the lock for multi-step restore, reset, cleanup, migration, topology-change, and exceptional backup-related maintenance workflows
- refresh owner: every subsequent internal phase in that workflow refreshes the same lock using `maintenanceLockToken`; lock refresh is not a second independent acquisition or a public phase command
- success release owner: the recover operation's internal release phase is the canonical success-path release once every affected region has durably reached its operation-owned safe disposition: `RUNNING`, or an explicitly permitted retained paused, degraded, stalled, or draining disposition
- failure disposition owner: a failed workflow retains its fence and maintenance lock while its durable operation record remains resumable. Before `RESUME_AUTHORIZED`, `coordination-maintenance release-lock ...` is the explicit audited abandonment step when an operator decides not to resume; it never runs automatically and does not make the scope safe to reopen. After `RESUME_AUTHORIZED`, abandonment is prohibited and the same operation's internal release worker must retry or reconcile to finalization
- exceptional backup-related maintenance treats lock-acquisition failure as a skipped/failed maintenance attempt; routine online backup health is independent of this lock and is measured through artifact freshness, lineage, integrity, and restore readability
- restore recovery and reset tooling must refresh or complete the lock before TTL expiry so another actor cannot start a conflicting maintenance workflow mid-flow

Canonical maintenance-active signal:

- metric: `coordination_maintenance_active{scope_type,scope_bucket,operation}`
- health/readiness projection: environments may expose an equivalent health field, but the metric name above is the canonical observability contract used by dashboards and Logging & Admin.

## Dual-Leader Detection and Coordination Reset

> This runbook inherits the [current execution boundary](#current-execution-boundary); its imperative steps are target-state only.

Goal: detect Redis split-brain or conflicting primaries and recover through a coordinated reset before duplicate logical effects can escape the tick subsystem.

Signals include:

- repeated stale-lease or unsupported-epoch outcomes for the same region
- PostgreSQL epoch validation rejecting conflicting writes
- Redis/Sentinel/Cluster alerts showing simultaneous primaries
- explicit dual-leader metrics such as `redis_coordination_dual_leader_detected_total`

Runbook:

1. use external infrastructure and PostgreSQL authority evidence to select and record the authoritative Redis primary before changing fences. Preserve that selected primary's ability to accept coordination traffic, fence every disqualified or conflicting primary at the infrastructure or network layer, and retain external evidence for both the selection and each fencing decision; do not ask the affected Redis deployment to prove its own fencing
2. verify PostgreSQL authority and the surviving Redis primary have converged on one authoritative epoch; this agreement is insufficient to authorize new tick progress
3. invoke one `coordination-maintenance recover --mode reset --scope region ... --preserve-sessions` operation for each safely isolated affected region; it clears region-local bindings and blocks normal command intake until preserved sessions complete rebind
4. if region isolation cannot be proved, retain the external primary fence and invoke one cluster-scoped `recover --mode reset --scope cluster --invalidate-sessions` operation; the cluster fallback keeps traffic blocked and invalidates gameplay sessions according to its explicit policy
5. let the recover operation own its internal pause/fencing, reset, reconciliation, rebind or invalidation, and smoke verification; before new tick progress, the successor must complete the [tick executor contract](./system-architecture-ticks.md#region-authority-and-tick-executor)'s acquire/new durable-fence/same-token revalidation and reconcile unfinished work from prior fences; then require the external public `resume(operationId, expectedPhase=awaiting_resume, maintenanceLockToken, evidenceRef)` gate before the separate internal success-release phase permits ticks or command intake to resume

## Normalization and Hash-Tag Migration

> This runbook inherits the [current execution boundary](#current-execution-boundary); its imperative steps are target-state only.

Goal: change how `tenantId` / `gameInstanceId` / `regionId` normalization and hash tags are formed without breaking shard-local assumptions.

### Runbook: Normalization Migration via Reset

The migration reset gate must pass the complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist), including the two independent exact digests, mode-specific startup attestation, all positive and destructive negative probes, and isolated-target cleanup/readback evidence. A combined or caller-asserted digest value or copied probe subset cannot satisfy this gate.

1. define an immutable migration contract containing the old and new normalization/hash-tag versions, affected scope, maintenance CLI and control-plane build digests, every participating service image digest, and the Lua Script Registry version/digest
2. explicitly upgrade the maintenance CLI, control plane, services, and Lua registry as one coordinated version set; mixed-version migration is unsupported
3. schedule a maintenance window and persist the migration contract in the durable recovery operation before mutating Coordination Redis
4. invoke one bounded `coordination-maintenance recover --mode reset --scope ... <session-policy-option>` operation, selecting exactly one of `--preserve-sessions` or `--invalidate-sessions`, and require the controller to validate the persisted migration contract from the durable operation record and every participant's reported version set before reset begins
5. complete the protected Account authority/token cutover and establish replay-domain quarantine/fencing for that same durable recovery operation; persist immutable evidence binding both protections and the pre-wipe authorization/fencing evidence to its operation identity, migration contract, exact scope, and server-issued maintenance lock, and keep admission closed while any result is missing or ambiguous
6. only after validating that bound pre-wipe evidence, any required `physical-dedication-proof/v1`, and the single-use `replacementVerificationChallenge/v1`, start a fresh Coordination Redis deployment or logical database with an empty keyspace as the operation's recorded external-infrastructure step; a `SAME_DEPLOYMENT` logical-database reset must produce both a new boot/startup generation and a new storage/AOF generation and must include independent `postWipeEmptyStartupAttestation/v1`
7. after startup, only the trusted deployment attestor, through the authenticated deployment-control channel, atomically consumes the challenge and emits mode-specific `post_reset_replacement_verification/v1`. The selected mode must provide its exact target-bound startup attestation and pass the complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist); the recovery controller independently verifies and durably records that evidence before `ready_to_reopen`, and public `resume(... expectedPhase=awaiting_resume ...)` re-verifies it and the active maintenance-lock state before recording `RESUME_AUTHORIZED`. It never consumes the challenge or re-emits the attestation. Rebuild coordination state from PostgreSQL plus fresh activity, then validate normalization, shard locality, Lua registry compatibility, and migration evidence before calling `continueRecovery(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` with canonical `expectedPhase=ready_to_reopen`, after which the internal release phase is required
8. if the migration cannot safely continue, call the audited abort control with the exact recorded scope selectors: for a region migration, `coordination-maintenance release-lock --operation-id <operationId> --scope region --tenant <tenantId> --game-instance <gameInstanceId> --region <regionId> --maintenance-lock-token-file <permissioned-token-file> --reason <reason> --evidence-ref <evidenceRef>`; tenant migrations use `--scope tenant --tenant <tenantId>`, and cluster migrations use `--scope cluster`. It retains the fence and does not reopen traffic.

### Runbook: In-Place Normalization Migration

> This runbook inherits the [current execution boundary](#current-execution-boundary); its imperative steps are target-state only.

In-place normalization migration is not a first-implementation operator path. Use the reset-based migration above until a future slice ships dedicated rewrite tooling with scope inventory, follow-up handling, audit output, and post-migration verification.

This remains a future advanced option when dropping all coordination state is unacceptable:

1. freeze topology
2. pause or drain ticks and new commands for affected scope
3. rewrite keys from old hash tags to new ones using explicit-prefix tooling
4. validate shard-locality and smoke behavior
5. resume ticks and commands
6. perform any later cluster resharding as a separate maintenance step

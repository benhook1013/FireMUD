# FireMUD Redis Incident Runbook

This runbook summarizes operator actions for **Redis-related incidents**, including coordination outages, cache issues, and AOF problems.

For the full design and invariants, see:

- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-redis-operations.md` (including the Redis Metrics Catalog section for metric names and alerting guidance)

## Implementation Notes

The incident flows below describe the target operator model. In the current implementation:

- Game Session already exposes durable command-status lookup and current-boundary runtime ownership inspection through the control-plane gRPC surface.
- `ackLevel` = `ACCEPTED_VOLATILE` command records in `RECEIVED`/`ENQUEUED` with no surviving batch/binding already converge to `LOST_BEFORE_STAGING` during startup recovery; `ACCEPTED_DURABLE` records follow their feature-specific durable replay or post-abandon re-drive contract, and inconclusive batch-bound work remains non-terminal/reconciliation-required. See the [canonical command outcome convergence](./system-architecture-tick-execution-flows.md#command-outcome-convergence-required).
- The full `coordination-maintenance` replay/reset orchestration referenced below is not yet shipped as a complete repo-local CLI/control-plane surface. Every command example naming that tooling is target-state contract text only, not an instruction for current operators, runbooks, Helm hooks, Jobs, or dashboards to invoke it; current operators must not run those commands until the surface is implemented and end-to-end proven. In the target state, `recover` issues the server-side maintenance lock and every subsequent control presents that lock through `--maintenance-lock-token-file <permissioned-token-file>` (or the documented protected FD form), never as a command-line token value.

Region-scoped coordination key examples use `{tenantRegionTag}`, the canonical opaque tag for `<tenantId, gameInstanceId, regionId>`; instance-scoped automation examples use `{tenantInstanceTag}`, the canonical opaque tag for `<tenantId, gameInstanceId>`. The tag carries the game-instance identity even when the raw identifier is not repeated in the key.

## Incident Types

- **Coordination Redis outage or high latency**
- **Cache/Rate-Limit Redis degradation**
- **AOF corruption, truncation, or disk pressure**
- **Session schema or TTL problems**
- **Mis-sharded or mis-keyed coordination keys**

## Coordination Redis Outage or Degradation

1. **Detect**
   - Alerts fire on tick duration, Redis latency, or error rates.
   - Logs show failures acquiring locks or writing tick entries.
2. **Stabilize**
   - Close new login, join, `PLAY`, reconnect/rebind, token issuance/refresh, and protected control-plane admission while required authority cannot be established.
   - If gameplay coordination remains healthy but token authority alone is unavailable, existing admitted bindings may continue only through their last renewed authority-freshness lease and terminate at the 60-second maximum. Do not add per-command registry reads.
   - If the complete Coordination Redis role is unavailable, halt correctness-sensitive gameplay mutations; bounded socket recovery does not authorize local-only processing.
   - Pause non-critical background scripts that depend heavily on Coordination Redis.
3. **Recover**
   - Follow cluster or node failover procedures documented in `design/architecture/system-architecture-redis-operations.md`.
   - If a reset is required, use the coordination reset model and key enumeration strategy from `design/architecture/system-architecture-redis-reset-and-recovery.md` to clear affected prefixes safely.
4. **Inspect durable command outcomes**
   - After replay/reset work, use the canonical `GetGameplayCommandStatus` surface described in `design/architecture/system-architecture-tick-failures-and-operations.md` to confirm commands converged to their final durable outcomes.
   - Do not treat raw Redis queue/key inspection as the primary operator answer for player-visible command status after remediation.

### Authority-Freshness Lease Contract

When token authority is unavailable but gameplay coordination remains healthy, the existing-binding exception is governed by an explicit Account-owned authority-freshness lease:

- **Record** – The authoritative binding record stores the exact account, tenant, game-instance, and gameplay-binding identity together with the applicable issuer/account/tenant/membership/grant authority generations, the committed authority checkpoint, a monotonic lease fence, and `authorityLeaseExpiresAt`.
- **Issuer** – Account Service issues and renews the lease only after reading the authoritative issued-token registry and applicable current authority generations. Game Session accepts and persists only that exact lease evidence; a JWT claim, local process state, or cached role is not an issuer or substitute.
- **Renewal linearization** – Renewal uses the same Account-owned exact-binding lease/fence protocol as admission. Game Session first records a non-admissible provisional renewal for the exact binding without extending its admitted `authorityLeaseExpiresAt`. Account then compare-and-sets that lease to `COMMITTED` against the current applicable authority-generation/checkpoint tuple and an Account-owned monotonic lease fence that serializes with every applicable revocation or generation advance. Only a matching committed lease may be consumed by one Game Session compare-and-replace that requires the exact binding identity, authority tuple, cutoff checkpoint, and a strictly newer lease fence and replaces the fence, tuple, and deadline together. Ordinary gameplay commands, socket activity, reconnect attempts, local heartbeats, and retries cannot renew or recreate the lease.
- **Concurrent revocation and invalidation ordering** – If an Account generation or revocation advance linearizes before lease finalization, it advances the Account-owned fence, finalization fails, and the provisional renewal cannot extend the deadline. If lease finalization linearizes first, the renewal was valid at that point; a later authority advance invalidates the accepted binding through the normal bounded active-revocation path. Game Session must remove or leave blocked every rejected, expired, stale, gapped, ambiguous, or non-committed provisional renewal. Race tests must prove both orderings, including that an authority advance before Account finalization cannot mutate or extend `authorityLeaseExpiresAt`; an equivalent implementation may replace the provisional protocol only if it proves the same Account-owned serialization and fail-closed deadline behavior without claiming a cross-store atomic transaction.
- **Absolute bound and fail-closed behavior** – The deadline is measured from the last successful authoritative renewal and is never extended beyond 60 seconds by local activity. New admission, token issuance/refresh, and reconnect/rebind remain closed while renewal is unavailable; an existing binding terminates at its stored deadline, or earlier when invalidated. No per-command registry read or cached-authority fallback changes this contract.

### Coordination Redis Recovery Behaviour

When Coordination Redis recovers after an outage or severe degradation:

- **Tick executors**
  - Do not attempt to resume in-flight locks or leases based on in-memory state.
  - Re-establish the authoritative recovery baseline from PostgreSQL tick-batch records, the tick effect ledger, follow-up tables, and `RegionStatus`; surviving Redis keys are inspected only as coordination residue and hints.
  - If `pending` survives for a region, the next executor validates each immutable pending envelope against the durable `tick_batch_id` and complete expected root-`EffectId`/participant set (or equivalent exact ledger-row identities), then replays the tick as described in the tick system design; a count, digest, or descriptor-only correlation is insufficient.
  - If `pending` is missing (for example due to AOF tail loss), treat this as “coordination state may have been partially lost” rather than silently skipping work:
    - Advance to the next `tickId` only after running the tick effect ledger replay controller / reconcile tooling for the affected scope so any lingering `SCHEDULED` effects converge to `APPLIED` or `ABANDONED` with an explicit tail-loss reason where the evidence policy permits. For `reset_first`, once the old timeline is durably fenced, the epoch bump is a separate fencing step and does not wait for conclusive ledger outcomes. Inconclusive old-epoch effects remain quarantined, non-terminal, owner-fenced reconciliation work under their original root `EffectId`; current-epoch executors must never replay that `EffectId`, and the work blocks reset-scope convergence, next-tick progress, and reopening, not the epoch bump itself.
    - Converge only command records with `ackLevel` = `ACCEPTED_VOLATILE` in `RECEIVED`/`ENQUEUED` and no surviving batch/binding to `executionOutcome = LOST_BEFORE_STAGING`; `ACCEPTED_DURABLE` records follow their feature-specific durable replay or post-abandon re-drive contract, while inconclusive batch-bound work remains non-terminal/reconciliation-required. See the [canonical command outcome convergence](./system-architecture-tick-execution-flows.md#command-outcome-convergence-required).
    - Use the resulting evidence-qualified ledger outcomes plus service-level idempotency guards to validate that no effect is silently terminalized or left without an owned reconciliation path; inconclusive old-epoch work remains visible as non-terminal.
- **Leases**
  - Discard any in-memory lease tokens; executors must reacquire `tick-executor-lease:{tenantRegionTag}` in Redis and treat previously held leases as invalid.
- **Sessions**
  - If `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` keys survive, reconnect flows behave normally.
  - If session keys are lost while game instances remain `RUNNING` in PostgreSQL, treat reconnect attempts as “no active binding” (clients may need to perform a fresh `LOGIN` or be rebound to the existing instance depending on ownership rules).

## Cache/Rate-Limit Redis Issues

1. **Detect**
   - Elevated cache miss rates, eviction spikes, or Redis memory alerts.
2. **Stabilize**
   - Throttle non-essential cache usage or temporarily reduce cache TTLs if documented.
   - Confirm that coordination keys are not co-located with caches.
3. **Recover**
   - Scale the cache deployment or provision additional nodes.
   - Flush or reset specific cache prefixes if needed; do not reset coordination prefixes from the cache deployment.

## Session Schema and TTL Cleanup

When session-related metrics indicate schema or TTL problems, use this scoped cleanup procedure instead of ad-hoc `DEL` commands:

1. **Detect the issue**
   - Watch `session.cas_unsupported_schema_total` and reconnect error rates for non-zero values outside brief rollout windows.
   - Interpretation: services and Lua scripts are out of sync on the highest `schemaVersion` in use for `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` keys, session payloads have been corrupted, or a major TTL reduction has left an undesirable tail of long-lived sessions.
2. **Align deployments**
   - Verify and correct deployments so all Game Session Service instances run a version whose CAS script understands the highest `schemaVersion` currently present in Redis (follow the “scripts first, writers second” rule from the Redis architecture docs).
3. **Run the canonical session cleanup workflow**
   - Use the session schema/TTL cleanup flow described in [Session Schema Cleanup and Large Keyspaces](./system-architecture-redis-operations.md#session-schema-cleanup-and-large-keyspaces). The lifecycle differs by operation kind: a strictly observational dry run ends directly after its no-effect evidence is recorded, while a mutating cleanup follows the continuation, authorization, release, and finalization path:
     - Target state only, not a current operator instruction: once the bounded surface is implemented and proven, the future workflow would use `coordination-maintenance recover --mode session-schema-cleanup --scope tenant --tenant <tenantId> --invalidate-sessions [--dry-run]`, using `--dry-run` first if the blast radius is uncertain. `session-schema-cleanup` requires the explicit `--invalidate-sessions` policy in the initial supported contract: it cannot preserve or infer gameplay sessions because unsupported or corrupted schema cannot be safely rebound. The mutating operation owns the maintenance lock and invokes session cleanup as an internal recovery phase, not a separately supported public verb. A dry run may transition directly to terminal phase `finalized` with terminal operation status `SUCCEEDED` only when it performs discovery and validation without acquiring a gameplay fence or maintenance lock and produces no mutation or release effect; it never enters `AWAITING_RESUME`, calls `continueRecovery`/public `resume`, or uses `release-lock` to finish. A dry run that acquires a fence/lock or mutates state must use the normal continuation/release lifecycle. A mutating operation records its bounded cursor/continuation state durably and may be retried through the canonical continuation controls with its server-issued lock rather than a caller-created resume token.
     - In the target state, configure the future recovery request to delete keys with unsupported `schemaVersion` values or aggressively reduce their TTL so they expire quickly when performing a TTL cut-over.
     - Target state only, not a current operator instruction: after the cleanup and its evidence are complete, the future workflow would use `coordination-maintenance continue-recovery --operation-id <operationId> --expected-phase ready_to_reopen --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>` for the same durable operation. It may only reconcile `ready_to_reopen` into `AWAITING_RESUME`; it does not authorize release. The future workflow would then use the external public `coordination-maintenance resume --operation-id <operationId> --expected-phase awaiting_resume --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>` gate, which validates the operation-owned scope, server-issued lock, immutable evidence, and any applicable Account projection or replay-domain evidence and records `RESUME_AUTHORIZED`. Only after that authorization may the recover operation run its separate internal success-release phase. After an operator abort or failed mutating workflow, `coordination-maintenance release-lock --operation-id <operationId> --scope tenant --tenant <tenantId> --maintenance-lock-token-file <permissioned-token-file> --reason <reason> --evidence-ref <evidenceRef>` is available only while the matching active workflow remains before `RESUME_AUTHORIZED`. Once `RESUME_AUTHORIZED` is recorded, every failure stays on the same operation's internal release/reconciliation path and `release-lock` is prohibited.
4. **Verify recovery**
   - Monitor `session.cas_unsupported_schema_total`, reconnect error rates, and Redis key counts for the affected tenant(s) to confirm the issue has cleared.
   - Affected players may need to log in again; no authoritative PostgreSQL data is lost.

## AOF and Persistence Problems

- See the detailed AOF guidance in `design/architecture/system-architecture-redis-operations.md` and the coordination reset model in `design/architecture/system-architecture-redis-reset-and-recovery.md`.
- When disk pressure, corruption, or replay issues are detected:
  - Any target-state reset gate must pass the complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist), including its separate exact `coord_ops_ro/v2` ACL digest, exact `coord_redis_config/v1` observed-configuration digest, mode-specific startup attestation, positive probes, destructive negative probes, and cleanup/readback evidence.
  - Challenge consumption and signed-result retrieval use the durable idempotent `challengeId` contract in [Coordination Redis Ops Access & Tooling](./system-architecture-redis-ops-access.md#post-reset-replacement-verification-gate); the operation remains fenced until the controller durably accepts the exact attestor result, and a consumed challenge is never replayed.
  - Capture diagnostics and snapshots where safe.
  - Follow only the documented AOF recovery paths:
    - Replay the existing AOF as-is when it is trusted.
    - The scoped reset/full-wipe flow in `system-architecture-redis-operations.md` is target state only until its durable controller, Account projection-repair workflow, immutable evidence gates, and bounded maintenance tooling are implemented and proven. It must use the [canonical coordination reset sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence), including the [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist); node-level scope still requires the documented cluster-blast-radius or physical-dedication proof before handoff. The incident-specific fallback remains fail-closed when those target-state controls are unavailable.
    - Current fail-closed fallback: if the AOF cannot be trusted, keep protected admission, gameplay mutation, and affected coordination writers stopped; preserve the AOF and incident evidence; use the shipped pause/status surface and read-only inspection where available; and escalate through the incident process. Abort any destructive wipe, recovery continuation, or reopen attempt when the required durable controller, Account projection repair, or immutable evidence path is unavailable. There is no supported current full-wipe substitute.
  - Do not perform manual AOF truncation or file editing.
  - Use idempotent replay and tick system rules to rebuild necessary state.
  - During Account projection repair, Account's snapshot field `issuerAuthGeneration` maps exactly to canonical persisted `lastAppliedIssuerGeneration` and that mapping is used consistently during bootstrap, persistence, exact readback, contiguous replay, and recovery; it is not a second persisted alias or a local fallback. Set-if-greater is permitted only for a missing or lower Redis generation. A Redis generation greater than Account durable authority is poisoned and must be quarantined or replaced by an Account-owned audited workflow, recreated from durable authority, and verified with immutable evidence for each affected scope before protected traffic reopens. The current repository does not ship and prove this projection-repair workflow end to end.

## Redis Incident Scenarios

The following Redis-focused incident flows build on the general recovery steps above.

### Coordination AOF Tail-Loss SLO Breach

1. **Detect**
   - Tail-loss indicators such as `redis_coordination_tail_loss_ms` regularly exceed the measured `redis_unreplicated_write_window_slo_ms` from [Redis Operations & Migrations](./system-architecture-redis-operations.md#redis-slos--budgets) for one or more `<tenantId, gameInstanceId, regionId>` shards.
   - Region health shows sustained `DEGRADED` or `STALLED` state for those shards.
2. **Decide**
   - For short-lived degradations where gameplay impact is minimal, investigate disk/replication performance, but keep serving traffic.
   - For sustained violations or `STALLED` regions, treat this as a **tick SLO breach** for the affected `<tenantId, gameInstanceId, regionId>` shards and choose exactly one recovery mode first:
     - `replay-first`
       - Use when the region is still on one coherent `regionEpoch`, there is no evidence of mixed-epoch state, no duplicate durable batches, and surviving coordination residue can still be correlated to the durable batch/ledger timeline.
       - Goal: preserve as much in-epoch work as possible by driving lingering `SCHEDULED` rows to `APPLIED` or `ABANDONED` without bumping `regionEpoch`, only where durable evidence permits; inconclusive old-epoch rows remain reconciliation-required.
     - `reset_first`
       - Use when the region is already `STALLED`, when mixed-epoch or orphaned coordination state is suspected, when duplicate/inconsistent durable batches are detected, or when replay-first fails to make bounded progress within the replay convergence budget.
       - Goal: fence the old timeline with an epoch bump and use the canonical reset handshake to reconcile old-epoch work explicitly, terminalizing only where the evidence policy permits and retaining inconclusive work as non-terminal reconciliation under its original root `EffectId`.
3. **Act**
   - The account-wide branch uses one canonical lifecycle for linked and standalone flows. Before inventory capture, each flow acquires and holds the live `accountAdmissionFence` through exact full-key readback and durable `coverageGeneration`, and carries `accountCoverageState=ACTIVE` with that concrete fence. The atomic coverage-proven transition releases the live fence and retains its exact value as `historicalAccountAdmissionFence`, changes the result to `accountCoverageState=HISTORICAL`, and makes the parent-independent child or standalone result reusable only after that proof. A flow that acquired the fence never uses `accountCoverageState=NO_ACTIVE_ACCOUNT_WIDE_FLOW` or `NOT_APPLICABLE` for either fence. A linked parent edge and its consumption CAS exact-compare the active state/live fence while coverage is active, then exact-compare the historical state/released fence after success; a standalone operation applies the same checks without a parent-consumption path. Missing, mismatched, or lifecycle-inappropriate live/historical values fail closed while preserving the existing parent-independent global child result and historical fence contract.
   1. If the chosen mode is `replay-first`:
      - Target state only, not a current operator instruction: once the orchestration is implemented and proven, the future workflow would run `coordination-maintenance recover --mode replay-first ... <session-policy-option>` for the affected region or tenant scope without bumping `region_epoch`, selecting exactly one of `--preserve-sessions` or `--invalidate-sessions`. Cluster scope accepts only explicit `--invalidate-sessions`. The high-level workflow owns ledger and command convergence; those phases are not separate public operator verbs, the session policy is always explicit, and the acquired lock/operation records dedicated `compatibilityClass=replay-first` derived from `--mode`.
      - Watch `tick_effects_pending_oldest_age_seconds`, `tick_effects_replay_slo_breached`, and command convergence for one emitted replay-convergence budget window. Verify that command outcomes settle into the canonical terminal vocabulary described in `system-architecture-tick-execution-flows.md` rather than inventing a replay-only local interpretation.
      - If the replay budget/status gate passes and a region- or tenant-scoped operation recorded `--preserve-sessions`, use the [canonical active-binding recovery evidence contract](./system-architecture-redis-ops-access.md#canonical-active-binding-recovery-evidence-contract), not Redis-derived session or index state. The scoped parent operation may use binding-local evidence only: every preserved binding must be enumerated from the immutable Game Session-owned `GameplayBindingInventory` snapshot identified by the exact `inventorySnapshotRevision`, with the parent `operationFence`, the distinct parent `parentCoverageFence` carried on every binding-local acknowledgement, transition, retry, and exact readback, and later `coverageGeneration` proving the selected binding coverage. A region- or tenant-scoped parent must perform binding-local repair only; it must not construct, validate, publish, or acknowledge an issuer-partition tuple or perform issuer reservation-capacity CAS/readback, even when its local evidence is complete. If issuer-wide coverage is required, the parent must create or consume only its atomically linked issuer-wide child operation with its separate `issuerCoverageOperationId`, lifecycle, maintenance fence, immutable issuer-wide inventory snapshot, and separate child `coverageFence`. Only that child may perform issuer reservation-capacity CAS/readback and construct or publish issuer-partition evidence, under its `issuerCoverageOperationFence` and child `coverageFence`; every child request, digest, transition, readback, acknowledgement, and exact retry is parent-independent and exact-compares only the child identity/lifecycle, child fences, `inventorySnapshotRevision`, later `coverageGeneration`, and full issuer snapshot/layout/cutoff/reservation/capacity tuple. The independent parent edge and the parent's consumption CAS carry and exact-compare the parent identity/lifecycle/fences, child identity/linkage, and the complete child proof; they are the only carriers of the parent tuple. The parent may consume the complete finite acknowledgement set only after those exact comparisons succeed and the child reaches its coverage-proven lifecycle. Until that separate child proof exists, issuer coverage remains unacknowledged and affected issuer admission remains fenced. A standalone issuer-wide operation uses the same carrier with `parentRecoveryOperationId=NOT_APPLICABLE`, `parentLifecycle=NOT_APPLICABLE`, `parentOperationFence=NOT_APPLICABLE`, and `parentCoverageFence=NOT_APPLICABLE`; it self-binds `issuerCoverageOperationId=operationId`, `issuerCoverageLifecycle=lifecycle`, and `issuerCoverageOperationFence=operationFence`, with a distinct `coverageFence`, and validates its own complete finite acknowledgement set without a parent-consumption path. The issuer-partition tuple includes the pinned active layout `{issuerIndexLayoutVersion, issuerIndexPartitionCount, issuerIndexPartitionCapacity, partitionId}`, exact `issuer/<issuerId>` cutoff including issuer generation and `issuanceFence`, complete applicable `outboxCheckpoints`, exact reservation identities with owner, transition, lifecycle, fence, and revision, and `{partitionId, reservationCount, partitionCapacity, aggregateReservationCount, aggregateCapacity}`. Stale, partial, unavailable, conflicting, or Redis-derived evidence blocks preserved-session rebind and reopen. The binding itself must also satisfy the complete [canonical preserved-session rebind predicate](./system-architecture-redis.md#session-and-region-binding-contract), including the target `schemaVersion=2` payload's exact `issuanceFence`, `authorityTuple.membershipAuthorityGeneration`, complete `outboxCheckpoints` set, and every token-registry, identity, authority, expiry, generation, epoch, and lease-fence predicate defined there. `session:game:*` and pre-auth transport context are not authority substitutes.
      - If account-wide coverage is required, the global account-index gate may pass only through the exact result of one linked account-wide child consumed through its parent edge or one valid standalone account-wide operation. For the linked path, the same parent creates or consumes one globally serialized account child for the exact `accountId` and persisted `resolvedScope`. Before capturing its complete-account snapshot, the child acquires and holds `accountAdmissionFence` through exact full-key readback and durable `coverageGeneration`. Every child request, digest, transition, readback, acknowledgement, and exact retry is parent-independent and carries only the child identity/lifecycle, child `accountCoverageOperationFence`, live `accountAdmissionFence` while coverage is active, child `coverageFence`, persisted `resolvedScope`, resolved `accountId`, exact complete-account `inventorySnapshotRevision`, and later `coverageGeneration`. The independent edge and the parent's consumption CAS carry and exact-compare the parent identity/lifecycle/fences, child identity/linkage, and the complete child result; they are the only carriers of the parent tuple. The child enumerates every tenant's complete `GameplayBindingInventory`, repairs the untagged `session:game:index:account:<accountId>` key, reads back the exact complete generation-qualified full-key member set, and publishes acknowledgements only after every inventory row, transition, and unresolved obligation is accounted for under that child tuple with a later `coverageGeneration`. The parent validates both exact operation identities and lifecycles, both operation fences, the child account fence, both coverage fences, persisted `resolvedScope`, resolved `accountId`, the exact snapshot and full-key readback evidence, every acknowledgement and retry, and later `coverageGeneration`, then consumes the child result only after the child reaches its coverage-proven lifecycle. A valid standalone account-wide operation instead uses the exact typed `NOT_APPLICABLE` parent markers, self-binds its account operation identity/lifecycle/fence, acquires and holds its own `accountAdmissionFence`, and independently proves the complete all-tenant inventory, exact full-key readback, complete acknowledgements, `operationFence`, `coverageFence`, and later `coverageGeneration`; it has no parent-consumption path. A stale, partial, unavailable, contradictory, duplicate, empty-key, aggregate, `SCAN`, narrow-scope, scope/account mismatch, or fence-mismatched result fails closed; it cannot authorize account-wide admission or parent reopen.
      - A failed preserved-session predicate never implicitly changes the policy. Before `RESUME_AUTHORIZED`, the operation remains paused and fenced under the same `operationId` and `maintenanceLockToken`; the operator must either perform an explicit audited preserve-to-invalidate transition under that same lock, recording actor, reason, and immutable evidence before invalidation, or complete audited `release-lock` abandonment and start an explicit new recover operation with `--invalidate-sessions`. The invalidation proof must then complete before continuation; rebind failure alone is not proof. After `RESUME_AUTHORIZED`, `release-lock` and replacement-operation abandonment are prohibited; failures remain on the same operation's internal release/reconciliation path.
      - Target state only, not a current operator instruction: after the applicable preserved-session or invalidation proof passes, the future workflow would run `coordination-maintenance continue-recovery --operation-id <operationId> --expected-phase ready_to_reopen --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>` for the exact operation. It may only reconcile into `AWAITING_RESUME`. The future workflow would then run the external public `coordination-maintenance resume --operation-id <operationId> --expected-phase awaiting_resume --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>` gate for the operation's recorded scope, with the required replay-domain and Account authority/projection evidence; it records `RESUME_AUTHORIZED` but does not release the active recovery lock. Only afterward may the recover operation perform its separate internal success-release phase without bumping `regionEpoch`.
      - Escalate to `reset_first` immediately if replay cannot make bounded progress, if inconsistent-state signals appear, or if the region transitions to `STALLED`. Escalation atomically upgrades the existing maintenance lock's compatibility class from dedicated `replay-first` to `reset` by compare-and-match on the same `maintenanceLockToken`, retains the original recovery audit lineage, and writes the upgrade audit record before bumping `regionEpoch` or mutating reset keys. The audit record must include the scope, old/new compatibility class, lock-token digest or opaque lock reference, workflow lineage, actor, reason, and resulting epoch transition; it must never contain the plaintext token. Do not release and reacquire the lock between recovery modes; if the tooling cannot perform that atomic same-token upgrade and audit ordering, it must leave the scope paused for an explicit operator failure path rather than starting a second recovery lock.
      - Worked example:
        1. Region `(T1, G3, R7)` remains on `region_epoch = 13`, `tick_effects_pending_oldest_age_seconds` exceeds budget, and there is no evidence of mixed-epoch state or duplicate durable batches.
        2. In the target state, an operator would run `coordination-maintenance recover --mode replay-first --scope region --tenant T1 --game-instance G3 --region R7 --preserve-sessions`; this is not a current executable command.
        3. The recovery workflow runs its internal ledger and command-convergence phases, converging lingering epoch-13 `SCHEDULED` rows to `APPLIED` or `ABANDONED` without bumping `region_epoch` only where durable evidence permits; inconclusive rows remain reconciliation-required, and command records follow the canonical terminal vocabulary.
        4. If pending age and stalled signals recover within one emitted budget window, the region stays on epoch `13`; otherwise the operator escalates to `reset_first`.
   2. If the chosen mode is `reset_first`:
      - Execute the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) for the same scope.
      - Keep only the incident-specific choices local to this runbook: scope selection, whether replay-first was exhausted first, whether gameplay sessions are preserved, and what evidence justified escalation.
   3. Verify region health returns to `RUNNING` or bounded `DEGRADED` and `redis_coordination_tail_loss_ms` drops back into the SLO envelope after the chosen recovery mode completes. If replay cannot complete before `RESUME_AUTHORIZED`, the operation remains paused and quarantined until reset escalation or audited `release-lock`. A failure after `RESUME_AUTHORIZED` is reconciled only through the same operation's internal release worker; `release-lock` is prohibited.

Alerts based on `redis_coordination_tail_loss_ms` should follow the conventions in `design/observability/grafana/redis-alerts-snippets.md` so they carry `owner` and `runbook` annotations that point back to this section.

### Mis-Sharded or Mis-Keyed Tick/Coordination Keys

1. **Detect**
   - CI or observability flags keys with unexpected hash tags (for example, multiple `{}` segments or missing `{tenantRegionTag}`).
   - Redis key inspections show coordination prefixes that do not match the documented patterns.
2. **Decide**
   - If mis-keyed data is purely coordination state (no unique business data), prefer a reset over in-place fixes.
   - If the mistake involves non-coordination prefixes that cannot be safely discarded, plan a one-off migration tool.
3. **Act**
   1. For coordination prefixes: follow the region/tenant/cluster reset flow from [Coordination Reset Model](./system-architecture-redis-reset-and-recovery.md#coordination-reset-model) and rely on PostgreSQL/idempotent ticks to rebuild state.
   2. For non-coordination prefixes: write a small migration Job that:
      - Iterates the affected prefix (for example `automation:queue:{tenantInstanceTag}:*`).
      - Writes corrected keys using shared builders.
      - Deletes or expires the old keys once consumers have been updated.
   3. Use the `tick-region-logs.json` Kibana saved search to confirm that tick/coordination-related logs for the affected regions no longer show mis-keyed or unknown-prefix warnings after the migration or reset completes.

### Automation Queue Schema Mistakes

1. **Detect**
   - Automation consumers log deserialization errors or unknown `schemaVersion` values for `automation:queue:{tenantInstanceTag}:*` keys.
   - Metrics show sustained failures processing automation work items.
2. **Decide**
   - If automation queues are purely best-effort, consider treating affected items as lost and flushing the prefix.
   - If the workflow requires guaranteed preservation, do not migrate from Redis queue contents; rebuild from the durable PostgreSQL trigger/effect tables and idempotent handlers.
3. **Act**
   1. Pause automation processing for the affected tenants or globally, depending on blast radius.
   2. Choose one explicit remediation path:
      - Best-effort path: flush `automation:queue:{tenantInstanceTag}:*` and restart consumers.
      - Durable path: run the Automation rebuild workflow that re-enqueues from PostgreSQL-backed triggers/quotas, not from Redis queue payload migration.
   3. Resume automation processing and monitor error rates and queue depths until they stabilize.

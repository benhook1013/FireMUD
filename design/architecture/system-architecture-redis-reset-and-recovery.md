# FireMUD Redis Reset & Recovery

This document is the canonical owner of FireMUD's **coordination reset model**: reset scope, gameplay-session policy, derived-index consequences, Account authority/token handling, metadata gating, and recovery release prerequisites. It complements the conceptual hub (`system-architecture-redis.md`) and links to the concrete command mechanics in `system-architecture-redis-operations.md` without redefining the reset lifecycle.

## Implementation Status

The evidence-gated coordination recovery decision is accepted target state, but the full region/tenant/cluster `coordination-maintenance recover` workflow is not currently shipped or proven. Current runtime support is limited to the `{tenantId, gameInstanceId}` pause/status boundary and partial startup/command convergence; canonical gameplay bindings, derived-index repair, Account projection repair, and the live legacy session/auth families remain implementation drift. This document owns replay/reset selection, external maintenance fencing, scope escalation, reset policy, and ordering. [ADR 0085](./decisions/adr-0085-evidence-gated-coordination-replay-and-fenced-reset.md) explains why that contract was accepted.

Every `coordination-maintenance` command in this document is unavailable for current operator invocation. Current handling is the fail-closed [Current Operator Fallback](#current-operator-fallback); this status does not change the target reset contract below.

---

## Table of Contents

- [Implementation Status](#implementation-status)
- [Coordination Reset Model](#coordination-reset-model)
- [Reset vs Accept Loss](#reset-vs-accept-loss)
- [Common Reset Scenarios](#common-reset-scenarios)
- [Current Operator Fallback](#current-operator-fallback)
- [Interaction with Measured Coordination Exposure and Replay](#interaction-with-measured-coordination-exposure-and-replay)
- [Operator Expectations](#operator-expectations)
- [Related Documentation](#related-documentation)

---

## Coordination Reset Model

Recovery selection is automatic and evidence-gated. `replay_first` selection requires coherent durable region-epoch and batch-timeline evidence only; bounded convergence constrains the selected replay attempt and need not already be complete before selection. Missing, contradictory, orphaned, or duplicated evidence selects `reset_first` immediately; a replay attempt that fails, stalls, or otherwise loses bounded progress transitions to the same reset path. Accept-loss is limited to disposable hints after durable reconciliation proves that no authoritative work remains unresolved. One durable maintenance operation and fence, stored outside the Redis deployment under repair, serializes conflicting recovery, reset, cleanup, restore, and topology-changing operations. The reset scope is the smallest scope whose complete affected keys, durable work, producers, and consumers can be proven; otherwise recovery escalates before clearing state. These are target-state gates, not claims that the current CLI implements them.

Coordination Redis is treated as a **long‑lived, measured-exposure coordination buffer** in persistent environments, **not** as a durable log of record; it remains volatile and reset‑tolerant under controlled conditions. Authoritative history for gameplay outcomes always lives in PostgreSQL tick effect ledgers and domain stores as described in `system-architecture-redis.md`, and neither coordination keys nor AOF contents are ever treated as the primary log of record. The reset model centers on three scopes:

- **Region‑scoped reset** – affects a single `<tenantId, gameInstanceId, regionId>`:
  - Clears tick queues, timers, retry structures, and region‑scoped locks/leases for one region.
  - Does not clear Account-owned issued-token registry records or authority-generation projections because neither is region-scoped coordination state.
  - Leaves other regions and tenants untouched.
  - Typically used when:
    - A mis‑keyed script or bug has polluted tick state for one region.
    - An incident is confined to a subset of the world.

- **Tenant‑scoped reset** – affects a single `tenantId`:
  - Clears the affected tenant-scoped gameplay coordination prefixes for all regions under one tenant.
  - Preserves only `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` records when operators explicitly choose `--preserve-sessions`; affected tenant-owned `session:game:index:*` projections are derived and may be dropped, but must reconcile before release. Untagged global account and issuer indexes are not narrow reset targets; replacement requires their complete owner-defined durable inventory, coverage proof, and exact readback before release. Pre-auth transport context is not covered by session preservation and is invalidated or rebuilt.
  - Does not clear Account-owned `session:auth:token:<tokenHash>` records or authority-generation projections. Region- and tenant-scoped resets preserve those records because they are not region-scoped coordination state; a tenant reset may terminate affected gameplay bindings, but it must not turn a tenant-region coordination reset into account-wide token revocation.
  - Often combined with an in‑game maintenance window or a revert/repin of tenant‑specific published content.
  - Used when:
    - A full in‑game reset is acceptable for a single tenant.
    - Cross‑region coordination problems cannot be repaired region by region.

- **Cluster-scoped reset** – affects an entire Coordination Redis deployment:
  - Clears coordination state for all tenants and regions on that deployment.
  - Establishes protected-traffic quarantine and pauses and epoch-fences all affected regions. For a reset that can delete the Account-owned auth prefixes `session:auth:token:*` or `session:auth:generation:*`, Account-owned authority/token cutover and immutable pre-wipe handoff evidence complete before the destructive reset; after reset, Account repairs the affected projections and durably reads them back. Replay admission remains quarantined while `replayAdmissionFence` advances, at least the maximum gameplay-connect lifetime plus two configured clock-skew intervals elapse from the recorded detection cutoff, and durable replay-consume proof completes before replacement issued-token projections are registered. Only after registration are replacement records validated against the exact-token registry and exercised by representative smoke; Gateway remains blocked throughout and until the complete recovery release gate succeeds. Physical deletion is cleanup, not the authorization boundary.
  - Reserved for extreme cases:
    - Catastrophic corruption or misconfiguration.
    - Planned migrations where coordination state cannot be incrementally migrated.

Every region- or tenant-scoped reset requires exactly one explicit gameplay-session policy: `--preserve-sessions` or `--invalidate-sessions`. That choice applies only to the scope-selected `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` records. Derived gameplay indexes are independent of that choice: affected tenant-owned `session:game:index:*` projections may be dropped under either policy, then must be reconciled and read back before release. Untagged global account and issuer indexes are not narrow reset targets; replacement requires their complete owner-defined durable inventory, coverage proof, and exact readback before the maintenance lock can release. Pre-auth transport context is never preserved by `--preserve-sessions` and is always invalidated or rebuilt. Account-owned `session:auth:token:*` and `session:auth:generation:*` authority remains outside session policy: region and tenant resets preserve it, and no session-policy option advances, deletes, or recreates it. Every cluster-scoped reset requires explicit `--invalidate-sessions` and rejects `--preserve-sessions`: it first closes protected admission and pauses and epoch-fences the affected regions; when the reset can delete either Account-owned auth prefix, Account-owned authority/token cutover and immutable pre-wipe handoff evidence complete before the destructive reset, Account projection repair and durable readback occur after reset, and replay quarantine plus durable replay-consumption proof complete before replacement issued-token registration, so old gameplay bindings cannot remain authoritative through that cutover.

For any cluster reset or cold start that can drop the Account-owned auth prefixes `session:auth:token:*` or `session:auth:generation:*`, the required ordering is: close Gateway protected admission and command intake; complete the internal pause/maintenance-lock and epoch-fencing prerequisites and the owner-defined pre-wipe gates, including Account-owned authority/token cutover and immutable pre-wipe handoff evidence; perform the destructive reset; after reset, request Account-owned projection repair or replacement and verify its durable result and readback; advance `replayAdmissionFence`, keep replay quarantine closed for at least the maximum gameplay-connect lifetime plus two configured clock-skew intervals from the recorded detection cutoff, and prove durable replay consumption; only then register replacement issued-token records. If a Redis projection mutation is unavailable, the Account-owned idempotent durable generation-mutation/repair operation is the fallback: it may be retried by the same operation identity and the projection remains fail-closed until Account reprojects and verifies the committed durable value. Reset tooling and downstream services must never synthesize or advance the Redis generation locally. An unavailable or ambiguous Account durable mutation keeps the operation fenced and aborts destructive handoff or release. Validate each replacement against the exact-token registry and run representative smoke after registration, while Gateway remains blocked throughout. A missing issued-token registry record is a denial condition for every protected route that requires that record. Validators must not recreate a record from a still-valid JWT, and a valid signature, `exp`, or gameplay/session key is not sufficient authority; unresolved cutover, replay proof, exact-token validation, projection, or smoke state keeps admission closed.

For a destructive full-deployment or logical-database reset, including whole-deployment `FLUSHALL`, the actual blast radius must be independently evidenced as `cluster`; FLUSHALL does not require physical dedication. A node-local AOF reset or replacement is a separate action and requires a fresh canonical `physical-dedication-proof/v1` binding the exact `operationId`, deployment identity, node set, and resolved scope; the proof is not transferable from cluster to region/tenant. A node-local handoff is rejected before external action when the required proof is absent, expired, replayed, unverifiable, or mismatched. Logical scope labels and operator assertions are not proof. The complete [canonical pre-wipe gates](./system-architecture-redis-ops-access.md#canonical-pre-wipe-gates) are `scope_paused_and_locked`, `account_authority_token_cutover`, `replay_domain_quarantine_fence`, and `immutable_external_handoff_evidence`; this reset document does not redefine them. Here `immutable_external_handoff_evidence` means only immutable pre-wipe authorization and fencing evidence: the authorized action, target scope, old deployment identity, intended replacement target, and proof that the old endpoint is fenced. These gates are internal evidence gates, not public commands, and must all be bound to the same durable `operationId` and server-issued lock binding before the external storage action; evidence retains only the lock digest or opaque lock reference, never the plaintext token. Before either the external destructive handoff or startup, the controller creates and durably records `replacementVerificationChallenge/v1` with a fresh nonce, issue/expiry times, single-use state, source and target deployment identities, mode, operation tuple, and intended scope. Post-reset verification is a separate evidence group recorded only after startup: only the trusted deployment attestor, through the authenticated deployment-control channel, atomically consumes the challenge and emits it as signed `post_reset_replacement_verification/v1`; no controller, operator, or other caller may consume the challenge or emit a substitute record. The recovery controller verifies and durably records that attestor evidence before entering `ready_to_reopen` and again at the canonical recovery resume boundary, without consuming the challenge again, as defined in the [canonical post-reset replacement verification gate](./system-architecture-redis-ops-access.md#post-reset-replacement-verification-gate). An empty keyspace never proves any gate.

The post-reset evidence has exactly two deployment-boundary modes, as defined by the [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist). `SAME_DEPLOYMENT` requires the independent `postWipeEmptyStartupAttestation/v1`; `REPLACEMENT_DEPLOYMENT` requires the distinct `replacementEmptyStartupAttestation/v1`. The signed target evidence and selected mode must exact-bind target identity/generation, canonical node set, Redis build, Lua registry, startup state, challenge, operation fence, target, and scope. The checklist separately owns the exact ACL digest, observed-configuration digest, all positive probes, destructive negative probes, and cleanup/readback evidence; operator observations, Redis key absence, and a raw key-count query are not attestations.

The accepted post-reset gate is the complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist). Generic startup or target identity evidence is insufficient: the selected mode's signed empty-startup attestation must carry the same challenge, operation fence, target identity/generation, canonical node set, Redis build, Lua registry, startup state, and resolved scope as the signed target record. `SAME_DEPLOYMENT` retains its independent `postWipeEmptyStartupAttestation/v1`; `REPLACEMENT_DEPLOYMENT` retains its distinct `replacementEmptyStartupAttestation/v1`.

The two protected cleanup authorities are independent and neither substitutes for the other:

- **Account token-projection deletion gate:** the Account repair/reset cutover must advance the issuer authority generation and durably attest the cutover before old Account-issued token projections may be physically deleted. This Account evidence authorizes only the token-projection deletion boundary; it does not authorize replay-marker cleanup or replacement-token registration.
- **Replay-marker cleanup gate:** the replay domain must advance `replayAdmissionFence`, remain quarantined for the full token lifetime plus configured clock-skew allowance, and provide durable consume proof before replay markers may be cleaned up and replacement replay admission can proceed. This replay evidence authorizes only the replay cleanup boundary; it does not authorize Account token-projection deletion.

Both gates remain required, operation-bound, and fail closed when incomplete or ambiguous.

Resets are always executed via **versioned coordination tooling** (for example, a maintenance CLI), not ad‑hoc `redis-cli` commands. Every reset:

- Identifies the exact scope (region/tenant/cluster).
- Uses shared key builders and descriptors for the relevant prefixes.
- Emits audit events documenting who initiated the reset, why, and what was affected.

Concrete command mechanics live in [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence); this document owns the reset consequences and release prerequisites rather than defining a second lifecycle.

Coverage lifecycle, child/parent carriers, and the account admission fence are owned by the [canonical active-binding recovery evidence contract](./system-architecture-redis-ops-access.md#canonical-active-binding-recovery-evidence-contract). Reset-specific behavior is limited to requiring complete, exact owner evidence before the affected scope can release; this document does not restate that carrier or lifecycle schema.

The public recovery continuation and release boundary are canonical in [Backup & Disaster Recovery](./system-architecture-backup-recovery.md#recovery-controller-continuation), while recovery release/evidence is owned by [Backup Recovery Evidence and Compliance](./system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record). For a reset, that boundary is admissible only with reset-specific evidence: the affected inventory reference, active lock/fence, current reset and cleanup results, Account projection and exact-token state, replay quarantine/consume proof, explicit session-policy result, and the complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist). A prior readiness result or incomplete/ambiguous reset evidence never authorizes reopening.

### Canonical Reset Sequence Boundary

The ordered reset lifecycle is linked from [Redis Operations](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence). This document retains the canonical reset consequences below; it does not define a second lifecycle.

- Every reset first durably advances the affected `regionEpoch`/`region_epoch` and invalidates or advances durable ownership before coordination state is cleared or rebuilt, so old executors, locks, heartbeats, and tick identifiers cannot continue on the new timeline. The [tick executor contract](./system-architecture-ticks.md#region-authority-and-tick-executor) owns that durable fence ordering; an ordinary executor handoff is fence-only and does not bump the epoch or constitute a reset.
- Scoped reset tooling uses versioned key builders and descriptors, reconciles old-epoch ledgers and accepted-but-unstaged commands under the evidence and authority-fenced rules owned by the [Inconclusive Old-Epoch Reconciliation Policy](./system-architecture-tick-failures-and-operations.md#inconclusive-old-epoch-reconciliation-policy), and, while the scope remains fenced, has its recovery-owned metadata-initialization phase establish `tick:{tenantRegionTag}:meta` with `current_tick_id = -1` and `current_tick_state = APPLIED`. Storage wipes and ad-hoc `DEL`/`FLUSH*` commands are not substitutes for that sequence. Outside that phase, no scheduler or hot-path writer may create or recreate `tick:{tenantRegionTag}:meta` while recovery is active; missing metadata keeps the scope fenced until the canonical recovery release.
- Account authority, token-projection, replay, session-policy, and immutable evidence gates remain fail-closed. Reset recovery invokes the Account-owned repair/replacement workflow and verifies its durable result; it never writes or reconstructs Account-owned projections itself. When a destructive wipe can remove the Account-owned auth prefixes `session:auth:token:*` or `session:auth:generation:*`, Account-owned authority/token cutover and immutable pre-wipe handoff evidence complete before the wipe, while Account projection repair/replacement and durable readback occur after reset. Physical deletion is cleanup, not authorization: cleanup removes only old or stale projections, replay quarantine and durable replay-consumption proof complete before replacement registry records are registered, and those replacement records remain after registration. A poisoned or ambiguous projection cannot authorize protected traffic.
- Tick IDs are epoch-scoped. The reset handshake durably establishes PostgreSQL RegionStatus/tick-ledger `lastCommittedTickId = -1` and, separately, the fenced Redis `tick:{tenantRegionTag}:meta` baseline `current_tick_id = -1`, `current_tick_state = APPLIED`; these baselines are semantically distinct, and no prior epoch tick ID is carried forward. After the owner-defined recovery release, the first fenced staging CAS must advance exactly `-1 → 0` and `APPLIED → STAGED`; later ticks advance only from the immediate prior terminal tick. Synthetic smoke traffic does not consume or authorize that real tick ID.
- After a successful reset, consumers key progress by `(tenantId, gameInstanceId, regionId, regionEpoch)` and rebuild derived state when the epoch changes.

#### Reset Smoke and Release Consequences

- Any smoke tick exercised by the reset workflow is synthetic maintenance traffic only. It is not the first real tick, must not be exposed to players, and must not authorize player ingress or real `tickId=0` admission; real `tickId=0` and player ingress remain blocked until the recovery owner's release boundary is complete.
- Reset-local release consequences remain fail-closed: an effect failure, ambiguous readback, or partial application keeps the affected scope fenced and requires the recovery owner to reconcile the durable operation before normal admission can resume.
- The traffic fence remains active until the recovery owner's canonical lifecycle has complete apply-and-readback evidence for the reset-specific release prerequisites. This document does not define the controller's phases or continuation calls.

#### Session-Policy and Preserved-Session Consequences

- Every region- or tenant-scoped reset records exactly one session policy: `--preserve-sessions` or `--invalidate-sessions`; the choice is never inferred from scope. Cluster scope accepts only explicit `--invalidate-sessions`. The policy applies only to affected canonical `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` records; pre-auth transport context is always invalidated or rebuilt, while clearing `tick:{tenantRegionTag}:*` also clears region-authoritative `tick:{tenantRegionTag}:session-binding:<entityId>` keys.
- With `--preserve-sessions`, retained gameplay sessions remain fenced from gameplay admission until Game Session completes the canonical [session and region-binding contract](./system-architecture-redis.md#session-and-region-binding-contract) and the complete [active-binding recovery evidence contract](./system-architecture-redis-ops-access.md#canonical-active-binding-recovery-evidence-contract). Pre-auth transport context is rebuilt separately and is never preserved. Those owner documents define the inventory, child/parent carrier, reservation/capacity, rebind, and realm-specific admission predicates; this reset document does not duplicate them. A fresh `LOGIN` / `PLAY` remains subject to the owner-defined admission contract.
- With `--invalidate-sessions`, affected gameplay sessions and pre-auth context are invalidated, and complete invalidation evidence is required before release. A cluster reset cannot use preserved-session rebind.
- A missing, stale, or ambiguous preserved-session result keeps the affected scope fenced and does not change the recorded policy. Any explicit preserve-to-invalidate transition or audited abandonment follows the canonical recovery controller contract.

1. **Run reset-local smoke and release consequences**
   - Smoke, immutable evidence, replay quarantine, Account projection, and session-policy results remain prerequisites to reopening; synthetic smoke traffic never authorizes player ingress or real `tickId=0`.
   - Player traffic and ordinary mutation intake remain fenced until the recovery owner's canonical lifecycle has complete current evidence and durable readback for the reset-specific release prerequisites. Partial or ambiguous release remains quarantined and is reconciled under the same durable operation.

Worked example (illustrative, non-normative, reset-local): region-scoped reset for `<tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120, gameInstanceId=9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, regionId=R7>` (target-state only; unavailable today)

> **Illustrative reset-local contract sketch; non-normative and unavailable today.** The `coordination-maintenance` commands below are not current operator instructions. Canonical reset ordering remains owned by [Redis Operations](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence); recovery release/evidence remains owned by [Backup Recovery Evidence and Compliance](./system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record) and the linked session/recovery evidence contracts.

1. The target-state command records `--preserve-sessions` and applies the reset-local effects for this region: it advances `region_epoch` from `12` to `13`, clears `tick:{tenantRegionTag}:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, and `tick-executor-lease:{tenantRegionTag}`, and runs old-epoch ledger and accepted-but-unbound command reconciliation under the linked evidence policy. `APPLIED` or `ABANDONED` is permitted only when that policy proves the corresponding outcome; inconclusive effects remain reconciliation-required under their original `EffectId` rather than being bulk-terminalized. Preserved sessions remain fenced until the owner-defined session and recovery evidence contracts qualify them.
2. The first real tick in the new epoch is `tickId=0` only after the owner-defined recovery and release boundary; this example does not prescribe that boundary.

Worked example (illustrative, non-normative, reset-local): tenant-scoped reset for `<tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120>` with `--preserve-sessions` (target-state only; unavailable today)

1. The target-state command records `--preserve-sessions` and applies reset-local effects across the complete affected-region scope: it advances each `region_epoch`, clears affected tenant-scoped gameplay coordination prefixes while retaining only canonical `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` records, drops affected tenant-owned `session:game:index:*` projections for reconciliation, always invalidates or rebuilds pre-auth transport context, removes affected `remote:{tenantInstanceTag}:*` hints from the durable game-instance scope, and runs old-epoch ledger and accepted-but-unbound command reconciliation under the linked evidence policy. `APPLIED` or `ABANDONED` is permitted only when that policy proves the corresponding outcome; inconclusive effects remain reconciliation-required under their original `EffectId` rather than being bulk-terminalized. Preserved sessions remain fenced until the owner-defined session and recovery evidence contracts qualify them and derived indexes reconcile; Account-owned token records and authorities are not tenant-reset state. The tenant reset also excludes untagged Account auth/generation families (`session:auth:token:*`, `session:auth:account:*`, `session:auth:tenant:*`, and `session:auth:generation:*`), the Game Session issuer-generation consumer projection (`session:game:auth:issuer-generation:v1:*`), and the shared Gateway replay domain (`gateway:connect-token:jti:*` and `replayAdmissionFence`); those remain under their owner-specific validation and reprojection contracts.
2. This example is reset-local only: the canonical sequence, complete evidence, and release boundary are defined by the owner documents linked above.

Worked example (illustrative, non-normative, reset-local): cluster-scoped reset (target-state only; unavailable today)

1. The target-state command records `--invalidate-sessions` and represents the reset-local cluster effect: all affected coordination state, including leases, queues, timers, retries, remote hints, and observer streams, is replaced while protected admission remains fenced.
2. Account-owned authority/token cutover and immutable pre-wipe handoff evidence complete before the destructive wipe. After reset, Account repairs the affected projections and durably reads them back; replay quarantine and durable replay-consumption proof complete before replacement registry records are registered. Physical deletion removes only old or stale projections as cleanup, and replacement registry records remain after registration. Affected gameplay sessions and pre-auth context remain invalidated until fresh authentication/play is permitted by the owner-defined release contract.
3. The complete [canonical pre-wipe gates](./system-architecture-redis-ops-access.md#canonical-pre-wipe-gates), post-reset verification, recovery evidence, and continuation/release semantics are intentionally not restated in this reset-local example.

### Reset-Specific Ordering Consequences

The canonical ordered reset sequence is [Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence). Recovery release/evidence remains owned by [Backup Recovery Evidence and Compliance](./system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record). This section retains only consequences local to reset scope, protected-domain evidence, and preserved-session fencing:

- A scope remains fenced until the owner-defined pause, epoch, storage, durable-state, projection, replay, session, smoke, and release gates have completed; an empty keyspace or storage-level wipe is never safe-resume evidence.
- Storage-level wipes, PVC deletion, `FLUSH*`, or prefix deletion before epoch fencing are invalid because stale executors could repopulate empty coordination state under the old epoch.
- A destructive cluster/full-wipe path retains the same operation and maintenance-lock binding through protected admission closure, replay quarantine, Account issuer-generation cutover, pre-wipe authorization evidence, post-startup replacement verification, current projection checks, and durable replay-consume evidence. Coordination Redis deletion is not a substitute for those protected-domain proofs.
- Full-wipe runbooks use the canonical owner sequence rather than defining an alternate order.
- Any reset scope that preserves gameplay sessions but clears region-local `tick:{tenantRegionTag}:session-binding:*` keys keeps the affected scope fenced until the Account-owned authority/token result, the canonical [session and region-binding contract](./system-architecture-redis.md#session-and-region-binding-contract), and the complete [active-binding recovery evidence contract](./system-architecture-redis-ops-access.md#canonical-active-binding-recovery-evidence-contract) qualify. This document retains only that reset consequence; it does not restate the inventory, carrier, reservation, rebind, or admission predicates.
- A cluster reset must keep protected admission closed through the Account issuer-generation repair/reset cutover, token cleanup, current-projection repair/replacement proof, replacement-token registration/proof, post-reset replacement verification, and post-reset smoke check. Account-owned authority/token cutover and immutable pre-wipe handoff evidence precede the destructive reset; Account projection repair and durable readback follow reset; replay quarantine and durable replay-consumption proof precede replacement-token registration. Projection proof alone is insufficient; a reauthentication-only opening must continue to reject every registry-gated protected route. A Redis generation greater than Account durable authority is poisoned, not a valid newer value: quarantine or replace it through the Account-owned audited workflow, recreate from durable authority, and require immutable per-scope repair evidence before reopening that scope.
- Replay markers and `replayAdmissionFence` are separate from ordinary coordination keys. A reset that clears or makes them untrusted must advance the fence and keep replay-domain quarantine closed until the canonical replay evidence and durable consume proof qualify; none of the Account projection gates substitutes for that replay-domain proof.

### Failover vs Cold Start vs Reset

Do not collapse all Redis events into “Redis repopulates from PostgreSQL.” Failover, cold start, and explicit reset have different safety properties:

The Redis-only cold-start/reset flow below is distinct from ADR 0015's PostgreSQL-rewind modes. If PostgreSQL has been rewound while Coordination Redis survives, `scoped_reset_restore` is deferred and quarantined; player-facing recovery must replace or clear Redis and use the environment-wide `cold_start_restore` controller contract instead.

- **Failover** (node crash, leader change, pod restart with intact AOF/PVCs)
  - Coordination Redis retains its AOF/replication history.
  - Keys such as `tick:{tenantRegionTag}:pending` and timers may survive.
  - Tick executors can replay or complete in‑flight ticks using idempotent domain logic and PostgreSQL guards.
  - This is the normal “Redis recovered” path; the unreplicated-write exposure is evaluated against the measured environment SLO and ADR 0058 class-specific outcomes.

Worked example (illustrative, non-normative, local failover consequences): `<tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120, gameInstanceId=9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, regionId=R7>`

1. Redis leader fails, but the replacement node replays intact AOF state and restores `tick:{tenantRegionTag}:pending`, `tick:{tenantRegionTag}:meta`, and the region lease key for `(7b3b074e-d597-4e9b-b96f-4f5946d26120, 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, R7)`.
2. The old executor loses its lease heartbeat and stops acting on in-memory state.
3. A new executor reacquires `tick-executor-lease:{tenantRegionTag}`, reads PostgreSQL `RegionStatus(regionEpoch=13, lastCommittedTickId=41)`, and inspects surviving coordination state only as residue to correlate against the durable `tick_batch_id`.
4. If `pending` still matches the surviving durable batch, the executor replays or completes that batch under normal idempotent rules.
5. If `pending` is missing or inconsistent despite the intact failover, the executor does not guess from Redis alone; it runs the normal ledger replay/reconcile path for the affected scope, then advances the region using the durable timeline.
6. No epoch bump or explicit reset is required unless the incident escalates into an actual cold start or corruption event.

- **Cold start** (empty Coordination Redis because the data directory/PVC is missing, wiped, or corrupted)
  - Treat as a **coordination reset event**, not a normal failover.
  - There is no durable coordination history to replay; all coordination keys start empty.
  - Services re‑establish leases/locks as new activity occurs, but any coordination intent that existed only in Redis (timers, retry schedules, in‑flight queues, session bindings) is dropped unless it is also represented durably elsewhere.
  - For a Redis-only cold start with PostgreSQL still authoritative, empty-start recovery is not a separate operator path:
    - Target state only: the bounded recovery controller, Account-owned projection repair/replacement workflow, replay quarantine/fence, and immutable evidence path must be implemented and proven before the owner-defined reset sequence can authorize reopening. The future `coordination-maintenance recover --mode reset --scope ... <session-policy-option>` operation is unavailable today and is not a current operator instruction.
    - Current operators must use the [Current Operator Fallback](#current-operator-fallback); the target-state recovery operation is unavailable and an empty keyspace is not safe-resume evidence.
    - Lazy recreation of `tick:{tenantRegionTag}:meta` by hot-path staging is prohibited until the canonical recovery release. A hot-path cold-start exception requires explicit proof that the exact environment and deployment are a documented non-reset profile; a profile label or empty keyspace is not proof. Without that proof, recovery and fencing apply, and only the recovery-owned metadata-initialization phase may establish the new baseline.

Worked example (illustrative, non-normative, reset-local): cold start for `<tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120, gameInstanceId=9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, regionId=R7>` (target-state only; unavailable today)

> **Illustrative reset-local contract sketch; non-normative and unavailable today.** The command below is not a current operator instruction. Current operators must keep the affected scope fenced and use the current fail-closed fallback above instead; the canonical recovery and release owners are not restated here.

1. Coordination Redis starts empty after loss of its data directory, while PostgreSQL still shows `RegionStatus(regionEpoch=13, lastCommittedTickId=41)` for `(7b3b074e-d597-4e9b-b96f-4f5946d26120, 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, R7)`.
2. The target-state command records `--preserve-sessions`, advances `region_epoch` from `13` to `14`, establishes PostgreSQL `lastCommittedTickId = -1` separately from the fenced Redis `current_tick_id = -1`, `current_tick_state = APPLIED` baseline, and runs old-epoch durable-work and accepted-but-unbound-command reconciliation under the linked evidence policy. `APPLIED` or `ABANDONED` is permitted only when that policy proves the corresponding outcome; inconclusive effects remain reconciliation-required under their original `EffectId` rather than being bulk-terminalized. Preserved sessions remain fenced until the owner-defined evidence qualifies them.
3. After the owner-defined recovery boundary, the first fenced staging CAS advances exactly `-1 → 0` and `APPLIED → STAGED`, making the first real committable tick in epoch `14` `tickId=0`; later ticks use the immediate-next terminal transition. This example does not define that boundary.

- **Reset** (intentional operational action)
  - A deliberate, scoped choice to discard volatile coordination state (region/tenant/cluster) and resume from PostgreSQL state plus new activity.
  - Target state only: apply a scoped reset through supported, implemented, and proven tooling for that exact scope and an explicit session policy.
  - Until that tooling exists, current handling is the [Current Operator Fallback](#current-operator-fallback): preserve evidence, keep the affected scope fenced, and escalate.
  - Must follow the reset model and runbooks; ad‑hoc `redis-cli` edits are treated as “unknown resets” and require a follow‑up scoped reset.

Design implications:

- Only coordination intent that is required for correctness is persisted durably (for example, via effect ledgers, transaction tables, or schedule tables in PostgreSQL).
- Best‑effort hints such as `remote:*` are explicitly not relied on for correctness; losing them affects latency only.
- When Redis and PostgreSQL disagree after split‑brain or data loss, **PostgreSQL wins**:
  - Operators do not attempt to “pick the right Redis side.”
  - Coordination histories in Redis for affected scopes are treated as disposable and rebuilt from durable state plus new commands after a reset.

### Reset Policy Matrix (Prefix Summary)

This table is the **canonical reset-policy catalog** for the main Redis prefixes. It is authoritative for:

- Prefix naming and the associated Redis **role** (Coordination vs Cache/Rate-Limit).
- The **reset policy** (reset-tolerant, reset-sensitive, or reset-forbidden) used by coordination reset tooling.
- A brief description of **what happens to gameplay or behavior if the prefix is dropped** during a reset.

Service design docs and per-service READMEs should link to this matrix (or any future expanded key catalog derived from it) instead of duplicating their own reset-policy tables; when a service introduces new prefixes, the catalog is extended here first.

The six gameplay index families below are Game Session-owned derived coordination state, independent of the explicit gameplay-session policy and not authorization state or preserved-session state. A reset may drop affected tenant-owned families under either policy only after the affected scope is paused and fenced. Untagged global account and issuer families are not narrow reset targets; replacement or rebuild requires complete account- or issuer-wide durable inventory, coverage proof, exact readback, and the maintenance-lock release gate. The one untagged global account index is an explicit exception because each member carries a complete tenant-qualified binding reference; it remains a single account-wide projection rather than a tenant-local selector. All affected families remain non-admissible until Game Session completes the owner-defined inventory reconciliation and exact readback; release is not authorized while any affected index family is missing, stale, partial, or ambiguous. The exact index shapes, child/parent carriers, reservation/capacity proof, and admission predicates are owned by [Session and Region-Binding Contract](./system-architecture-redis.md#session-and-region-binding-contract), [Global Account Active-Binding Index](./system-architecture-session-behavior.md#global-account-active-binding-index), and the [canonical active-binding recovery evidence contract](./system-architecture-redis-ops-access.md#canonical-active-binding-recovery-evidence-contract). This reset catalog records only scope, drop behavior, and fencing consequences.

### Global Index-Family Recovery Consequence

A region- or tenant-scoped reset parent cannot claim global account- or issuer-index coverage from a local reset. The untagged account index and issuer partitions are not narrow cleanup targets and remain fenced until the complete owner-defined recovery result is consumed; unrelated global members remain untouched by a narrow reset. Cluster scope rebuilds the complete global families from their authoritative inventories. The child/parent carrier, account admission fence, reservation/capacity, and exact readback rules are defined by the linked owner contracts above rather than repeated here.

| Prefix / Family | Role | Reset Policy (Coordination Reset) | Behavior When Dropped | Notes |
| --- | --- | --- | --- | --- |
| `tick:{tenantRegionTag}:pending` and `tick:{tenantRegionTag}:queue:*` | Coordination | **Reset-tolerant** | In-flight ticks and queued commands for affected regions are discarded; future ticks process only new commands. | `pending` effects are reconciled through the tick effect ledger under the [Inconclusive Old-Epoch Reconciliation Policy](./system-architecture-tick-failures-and-operations.md#inconclusive-old-epoch-reconciliation-policy): evidence may permit `APPLIED`/`ABANDONED`, while inconclusive work remains a reconciliation-required non-terminal marker under its original `EffectId`; reset does not bulk-terminalize it. Idempotency prevents double-apply. Only command records with `ackLevel` = `ACCEPTED_VOLATILE` in `RECEIVED`/`ENQUEUED` and no surviving batch/binding are intentionally **lost** and not reconstructed from PostgreSQL; `ACCEPTED_DURABLE` records follow their feature-specific durable replay or post-abandon re-drive contract, while inconclusive batch-bound work remains non-terminal/reconciliation-required. See the [canonical command outcome convergence](./system-architecture-tick-execution-flows.md#command-outcome-convergence-required). |
| `tick:{tenantRegionTag}:meta` | Coordination | **Reset-tolerant** | Epoch/tick guard metadata is dropped; reset tooling re-establishes the new epoch baseline from durable RegionStatus state before normal progress. | The Redis metadata is a coordination view only; the reset handshake establishes the same new epoch and first-real-tick baseline in PostgreSQL and Redis. |
| `tick:{tenantRegionTag}:session-binding:*` | Coordination | **Reset-tolerant with preserved-session rebind** | Region-local gameplay admission bindings are dropped. Preserved sessions remain fenced until the owner-defined session/recovery evidence contract qualifies them; invalidated sessions require fresh `LOGIN` / `PLAY`. | These keys are region-authoritative for gameplay command admission. The exact rebind, inventory, carrier, reservation, and admission predicates are owned by the [session and region-binding contract](./system-architecture-redis.md#session-and-region-binding-contract) and [active-binding recovery evidence contract](./system-architecture-redis-ops-access.md#canonical-active-binding-recovery-evidence-contract). |
| `timer:{tenantRegionTag}` and `retry:{tenantRegionTag}` | Coordination | **Reset-tolerant** | Timers and retries for affected regions are discarded; future ticks process only newly scheduled timers/retries. | Only timers/retries that are also represented durably elsewhere (for example, PostgreSQL-backed automation schedules or durable follow-ups) are re-discovered after a reset; region-scoped timer/retry coordination keys themselves are not treated as reconstructible logs. |
| `tick-executor-lease:{tenantRegionTag}` and tick lock keys (`tick:{tenantRegionTag}:lock:*`) | Coordination | **Reset-tolerant** | Existing leases/locks vanish; new executors reacquire leadership and locks as ticks resume. | Leases and locks are transient; executors reacquire leases and lock state after reset. |
| `session:game:index:character:{tenantGameplayTag}:<playableStateNamespaceId>:<characterId>` | Coordination | **Reset-sensitive with mandatory binding reconciliation** | The target `{tenantId, playableStateNamespaceId, characterId}` character-to-session controller uniqueness entry is dropped; takeover, resume, and new admission using that character remain fenced until Game Session rebuilds the exact entry from the durable binding inventory and validates the server-derived `playableStateScope` binding/routing evidence plus value/evidence `{sessionId, gameInstanceId, bindingGeneration}` against the session record. An unbound-character decision requires that durable inventory readback to prove there is no active binding; an empty Redis key alone never proves availability. See [ADR 0132](./decisions/adr-0132-namespace-scoped-single-character-controller.md) and [Session Behavior](./system-architecture-session-behavior.md#session-and-identity-management). | Tenant-scoped and namespace-qualified, with the shard-local session CAS; `playableStateScope` remains binding/routing context. Rebuild removes stale entries, recreates missing entries, and rejects conflicting identities. The existing `<gameInstanceId>:<characterId>` index key is current implementation drift, not the target key. |
| `session:game:index:account:<accountId>` | Coordination | **Reset-sensitive with mandatory account-wide reconciliation** | Account-wide lookup is unavailable while the global index is dropped; narrow parents do not rebuild the untagged key, and account-wide access remains fenced until the complete owner-defined recovery result is consumed. | This is the one retained untagged global account exception: every member is a complete tenant-qualified `accountIndexMember`, so the key is not a tenant-local selector. Its physical layout and member contract are owned by [Global Account Active-Binding Index](./system-architecture-session-behavior.md#global-account-active-binding-index); narrower resets preserve unrelated tenants and cluster reset rebuilds the complete key. |
| `session:game:index:account-tenant:{tenantGameplayTag}:<accountId>` | Coordination | **Reset-sensitive with mandatory binding reconciliation** | The tenant-qualified account lookup is dropped; tenant-scoped resume, takeover, and bounded revocation remain fenced until Game Session rebuilds it from the durable inventory and the matching session/index obligations are acknowledged. Each index member remains an active `sessionId` value under the owner-defined schema. Reconciliation is inventory-led: it derives the exact expected active `sessionId` set from authoritative durable `GameplayBindingInventory` rows; each row's complete `bindingRef` and tenant/game/session identity supply `gameInstanceId` and directly address its canonical session record. Before deriving or writing a member or accepting readback, it exact-compares the row's canonical binding-owner `accountId` with the key's `<accountId>`; a missing, malformed, or mismatched owner is rejected or quarantined and keeps the affected account/index scope fenced. It independently validates the exact session record, including controller identity `{tenantId, playableStateNamespaceId, characterId}`, server-derived `playableStateScope`, `gameInstanceId`, `bindingGeneration`, and lifecycle, then reads back the expected `sessionId` set before acknowledging the matching index/session obligations. Reconciliation never infers `gameInstanceId` from a bare member and does not use wildcard scans or invent a separate resolver. This consumes the existing owner-defined schema and producers; it does not change them. | Tenant-scoped and part of the shard-local session CAS. Reconciliation is limited to the gameplay tag and validates the canonical session record rather than treating index membership as authoritative. |
| `session:game:index:tenant:{tenantGameplayTag}` | Coordination | **Reset-sensitive with mandatory binding reconciliation** | Tenant-wide active-session lookup is dropped; tenant revocation, inspection, and repair remain fenced until Game Session rebuilds and reads back the tenant index from durable inventory. | Tenant-scoped and shard-local. Reconciliation must not use the global account index as a substitute or admit a binding from tenant-local key presence alone. |
| `session:game:index:realm-grant:{tenantGameplayTag}:<worldSlug>:<realmSlug>:<playtestLifecycleId>:<accountId>` | Coordination | **Reset-sensitive with mandatory binding reconciliation** | Lifecycle-bound private/playtest grant lookup is dropped; realm admission and grant-driven revocation remain fenced until Game Session rebuilds the exact tenant/lifecycle-qualified entries and validates current Account grant authority. | Tenant-scoped and shard-local. Redis entries are only bounded lookup evidence; Account-owned grant state remains authoritative, and stale, wrong-lifecycle, or cross-tenant entries are removed during reconciliation. Public-production and unscoped bindings have no entry. |
| `session:game:index:issuer:{issuerIndexLayoutTag}:<issuerId>:<partitionId>` | Coordination | **Reset-sensitive with mandatory issuer-coverage rebuild** | Issuer cutoff and issuer-wide lookup remain fenced until the complete owner-defined issuer recovery result qualifies; region and tenant resets never flush a whole issuer partition. | Global per issuer across tenants and partitioned by the immutable issuer layout, not tenant-tagged. The inventory, capacity, acknowledgement, and coverage contract is owned by the linked Game Session evidence documents. |
| `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` | Coordination | **Reset-sensitive** | Region and tenant resets retain or invalidate this record only under their explicit session policy. Cluster scope requires `--invalidate-sessions`; preserved-session rebind is not a cluster option. | Authoritative for connection identity and reconnect/session CAS state, but not a substitute for the owner-defined rebind/admission contract. |
| `sessionctx:*` | Coordination | **Reset-sensitive, not preservable** | Current Game Session bootstrap and lookup context is invalidated or rebuilt by any reset; it is never covered by `--preserve-sessions` and cannot authorize gameplay. | Game Session owns this live implementation-local family. Its reset consequence is local to the family; the canonical reset lifecycle and session policy remain defined above. |
| Pre-auth transport context | Coordination | **Reset-sensitive, not preservable** | Region and tenant resets invalidate or rebuild this context regardless of `--preserve-sessions`; cluster scope requires invalidation. | Pre-auth context is not gameplay authority and is outside the canonical gameplay-session preservation domain. |
| `session:auth:token:<tokenHash>` | Coordination | **Reset-sensitive by scope** | Region and tenant resets preserve these Account-owned records. For a destructive cluster reset, Account-owned authority/token cutover and immutable pre-wipe handoff evidence complete before the wipe; after reset, Account repairs and durably reads back the affected projections; replay quarantine and durable replay-consumption proof complete before replacement registration. Cleanup removes only old or stale projections, and replacement registry records remain after registration. Missing records deny protected use and require fresh authentication. | The exact-token registry is the runtime authority for that token, while Account durable generations remain the authority for issuer, compromise, and reset invalidation. Reset tooling must not infer scope from key names or recreate a record from a JWT. See `system-architecture-jwt-and-token-contracts.md` for full semantics. |
| `session:auth:account:<accountId>:<tokenHash>` | Coordination | **Reset-sensitive, Account-owned legacy projection** | Region- and tenant-scoped coordination resets preserve the live Account account-session record; a cluster reset or cold start may discard it only through the Account-owned cutover and repair path, with protected admission closed until replacement auth state is verified. | This legacy family is outside gameplay session policy. Account owns repair, revocation, and eventual convergence to `session:auth:token:<tokenHash>`; a coordination reset or Game Session must not delete or recreate it. |
| `session:auth:tenant:<tenantId>:<tokenHash>` | Coordination | **Reset-sensitive, Account-owned legacy projection** | Region- and tenant-scoped coordination resets preserve the live Account tenant-session record, including when a gameplay reset affects that tenant; a cluster reset or cold start may discard it only through the Account-owned cutover and repair path. | This companion legacy family is outside gameplay session policy. Account owns repair and revocation; a tenant reset must not delete only one auth projection or turn gameplay scope into account-wide token invalidation. |
| `session:auth:generation:issuer:<issuerId>`, `session:auth:generation:account:<accountId>`, `session:auth:generation:tenant:<tenantId>`, and `session:auth:generation:membership:<accountId>:<tenantId>` | Coordination | **Reset-sensitive, fail-closed by scope** | Region- and tenant-scoped coordination resets do not reset these untagged Account-owned projections; they leave unaffected scopes intact and re-project/verify every affected exact scope. A missing or lower Redis projection may be repaired with Account-owned idempotent set-if-greater. If that projection mutation is unavailable, the same Account-owned durable generation-mutation/repair operation is the fallback; an unavailable or ambiguous durable result keeps the scope fenced. A projection greater than Account durable authority is poisoned and must be quarantined or replaced through an Account-owned audited workflow, recreated from durable authority, and accompanied by immutable per-scope repair evidence before protected traffic reopens. A cluster-scoped reset may discard projections only after protected admission is closed and Account advances the issuer authority generation; Account then rebuilds and verifies every affected projection and current issued-token record before reopen. | Account's durable transactional generation is the sole authority. Redis is only a bounded projection/cache and must never become an alternate writer or reset baseline. No scoped coordination reset advances, deletes, or recreates these projection families; they remain outside gameplay session policy. |
| `session:game:auth:issuer-generation:v1:<issuerId>` | Coordination | **Reset-sensitive, fail-closed by scope** | Region- and tenant-scoped coordination resets do not reset the Game Session issuer-generation consumer projection; affected scopes are reinstalled or reconciled from the exact Account-owned issuer checkpoint and read back before issuer-gated admission, reconnect, or revocation consumption. A cluster-scoped reset may discard it only after the Account repair/reset cutover and issuer-generation projection gates close protected admission; Game Session then installs the exact derived projection and verifies its complete source checkpoint before reopen. | Game Session owns only this derived consumer projection; Account's durable issuer generation and `session:auth:generation:issuer:<issuerId>` remain authoritative. No scoped coordination reset advances or recreates this projection from local state. |
| `gateway:connect-token:jti:<jti>` and `replayAdmissionFence` | Coordination | **Reset-sensitive, fail-closed** | These shared Gateway replay-domain keys are an explicit exception to tenant/region key tagging and are untouched by region- or tenant-scoped resets. After a shared reset drops or makes replay markers untrusted, the reset advances `replayAdmissionFence` and rejects all new first-party gameplay handshakes for at least the maximum gameplay-connect lifetime (`30 seconds`) plus two configured clock-skew intervals. Reopen requires a disposable marker write on the canonical same Redis connection, a same-connection `WAITAOF` local/replica durability acknowledgement after that write, marker consumption, and a second same-connection `WAITAOF` acknowledgement; only after both acknowledgements may the probe return `DURABLE_REPLAY_CONSUME_ACK`. This replay gate is in addition to, and cannot bypass, the Account cutover, issuer-generation projection rebuild/proof, and post-reset smoke ordering. Resetting this replay state alone does not close already admitted WebSockets. A cluster reset closes affected gameplay sockets under its required explicit invalidation policy. | Consumed markers are security-critical and non-evicting. The fence is advanced only after the old marker state is dropped or rejected; quarantine and both durability acknowledgements must complete before `replayAdmissionFence` reopens admission. Existing admitted WebSockets are unaffected by replay-state reset alone. |
| `remote:{tenantInstanceTag}:<entityId>` | Coordination | **Reset-tolerant** | Cross-region follow-ups rely solely on durable tables; hints may be temporarily missing, increasing latency only. Region-scoped coordination resets leave instance-scoped hints intact; tenant- and cluster-scoped resets enumerate every affected game instance from the durable scope inventory and remove each instance's `remote:{tenantInstanceTag}:*` keys. | The complete key scope is `<tenantId, gameInstanceId>` because durable remote follow-up identity is instance-aware. These are best-effort cross-region wake-up hints only; dropping them affects latency, not correctness. Hint keys are TTL-bounded (default `remote_hint_ttl_ms = 60_000`) so stale hints age out automatically. |
| `ratelimit:<tenantId>:<subjectHash>:*` and separately declared shared heuristics | Cache/Rate-Limit | **Reset-tolerant** | Rate-limit counters reset; future requests rebuild subject or shared-policy state from zero. | Each limiter declares whether reset temporarily weakens or tightens its heuristic and its unavailable-store behavior. Hard authorization, financial, durable-quota, or security invariants remain outside evictable Redis. |
| `inventory:<tenantId>:*` | Cache | **Reset-tolerant** | Cached inventory/container aggregates are flushed; subsequent reads recompute views from PostgreSQL and repopulate Redis. | Inventories remain authoritative in PostgreSQL; resets may temporarily increase load but do not lose inventory data. |
| `character-cache:<tenantId>:*` | Cache | **Reset-tolerant** | Cached character graphs are dropped; hot paths fall back to Entity Management and repopulate caches on demand. | Character state lives in PostgreSQL; cache loss affects latency only. |
| `world-dynamic:<tenantId>:*` | Cache | **Reset-tolerant** | Cached dynamic world aggregates are flushed; subsequent reads recompute views from PostgreSQL. | World topology/dynamic state remains authoritative in PostgreSQL; resets may increase load temporarily. |
| `room:<tenantId>:*` | Cache | **Reset-tolerant** | Cached room topology snapshots are dropped; callers reload rooms from PostgreSQL and repopulate caches. | Used for LOOK/navigation snapshots; resets never affect canonical topology in PostgreSQL. |
| `view:room-look:<tenantId>:*` | Cache | **Reset-tolerant** | Cached rendered room views are dropped; Game Session recomputes views on demand for affected rooms. | Strictly Class B, TTL-only caches; correctness-critical flows (combat, visibility, movement) do not read from this prefix, and Game Session is the sole writer/reader of these keys. |
| `chat:say:<tenantId>:*`, `chat:tell:<tenantId>:*`, `chat:guild:<tenantId>:*`, `chat:account:<tenantId>:*` | Cache | **Reset-tolerant** | Short-lived chat buffers are cleared; subsequent reads fall back to PostgreSQL or rebuild windows from persisted history. | Treated as TTL-only rolling windows; resets drop recent in-memory history but do not lose persisted moderation logs where required. Clients must tolerate gaps and non-contiguous windows after resets. |
| `script-scheduler:{tenantRegionTag}:lastTickId` | Coordination | **Reset-tolerant** | Automation scheduler treats the next heartbeat as its baseline and may re-scan due interval boundaries, but durable trigger-instance uniqueness prevents duplicate logical trigger creation. | Automation scheduler checkpoint for “every N ticks” triggers; losing it causes the scheduler to re-establish its baseline from the heartbeat stream while PostgreSQL-backed trigger-instance rows remain the de-duplication boundary. |
| `automation:timer:{tenantRegionTag}` | Coordination | **Reset-tolerant** | Automation timer indexes for affected regions are discarded and rebuilt from durable schedules, trigger-instance rows, and heartbeat progress. | Region-scoped coordination index for script timers/intervals. Entries must remain instance-aware in payload and rebuild logic (`gameInstanceId`, and plugin identifiers when applicable) even though the Redis key is region-scoped for slotting/locality. |
| `automation:queue:{tenantInstanceTag}:*`, `automation:quota:<tenantId>:*`, `automation:tenant-budget:<tenantId>:tier:<tier>`, `automation:test:capacity:<tenantId>:*`, `automation:test:capacity:cluster*` and other automation caches | Cache/Rate-Limit | **Reset-tolerant** | Queued work and quotas restart from an empty state; automation re-enqueues work based on durable triggers and budgets. | Best-effort buffers and counters; resets clear them but do not affect authoritative state. Repeated resets may temporarily relax fairness/throughput limits but must not change which work eventually runs. |
| `tick-events-lease:{tenantRegionTag}` | Coordination | **Reset-tolerant** | Observer leases are dropped; consumers reacquire leases and may duplicate best-effort processing until offsets are re-established. | Used only to avoid duplicate tick-event consumption work. Losing it is safe because tick events are observers/hints; correctness derives from the committed heartbeat/RegionStatus timeline and durable domain state. |
| `tick-events:{tenantRegionTag}` and all `tick-events-offset:{tenantRegionTag}:<consumerId>` keys | Coordination | **Reset-tolerant** | Tick event streams and every consumer's offset are dropped; observers re-establish their baselines from the gRPC heartbeat and domain state. Any surviving per-consumer offset value is reusable only when its stored `regionEpoch` matches the current control-plane epoch; otherwise it is discarded before resume. | Tick event streams are best-effort observer/wakeup hints (for example, reconnection hints and faster scheduler discovery). Each offset value stores `{tenantId, gameInstanceId, regionId, consumerId, regionEpoch, latestTickId, streamOffset}`. Streams are retention-capped (default `tick_events_maxlen = 2048` per region). Correctness derives from the committed heartbeat/RegionStatus timeline plus durable PostgreSQL schedules/effects; missing or duplicated events must not change which schedules eventually fire. |

When introducing a new prefix, service designs must extend this matrix (or a directly linked, expanded key catalog) with:

- Prefix pattern and Redis role.
- Reset policy (reset-tolerant, reset-sensitive, or reset-forbidden).
- A concise statement of what happens to gameplay or behavior if the prefix is dropped during a reset.

Reset tooling and runbooks are expected to consume this catalog to enforce reset behavior.

---

For the account-wide and issuer-wide child workflows above, the canonical child/parent carriers, coverage lifecycle, account admission fence, and exact readback rules are owned by the [canonical active-binding recovery evidence contract](./system-architecture-redis-ops-access.md#canonical-active-binding-recovery-evidence-contract). Reset-specific behavior is limited to keeping the affected scope fenced until that owner-defined result qualifies; this document does not define a second carrier or lifecycle schema.

## Reset vs Accept Loss

Once the bounded recovery controller and its coordination-maintenance CLI are implemented and proven, the target-state operator workflow presents two supported strategies. This is target-state-only guidance, not current operator behavior: the CLI is unavailable today. Current operators have only the fail-closed [Current Operator Fallback](#current-operator-fallback); an empty keyspace is never safe-resume evidence.

1. **Can you safely accept the loss?**
   - Choose **Accept loss** when:
     - Metrics show the measured unreplicated-write exposure stayed within the documented environment SLO, and
     - Invariants (no double‑apply of critical effects, no cross‑tenant leaks, no broken financial flows) remain intact.
   - Behavior:
     - Acknowledge only the class-specific coordination consequences in ADR 0058; loss of correctness-bearing commands, effects, retries, or timers still requires durable reconstruction or explicit terminalization, not silent acceptance.
   - Examples:
     - Short Redis outage where replication/promotion evidence confirms the measured exposure remained within the environment SLO and only reset-tolerant hints were affected.
     - Eviction of cache‑like coordination hints that are inherently best‑effort.

2. **Otherwise, reset at the smallest safe scope**
   - Choose **Reset** when:
     - The loss is outside the accepted envelope, or
     - The data corruption or bug is not known to be safe to ignore, or
     - You cannot confidently prove that doing nothing preserves the documented invariants.
   - Behavior:
     - Intentionally clear coordination keys for a scope and allow services to rebuild from durable domain state.
   - Examples:
     - Region‑scoped reset after mis‑keyed `tick:*` data affecting many entities.
     - Tenant‑scoped reset after unrecoverable script bugs affecting multiple regions.
   - Rules:
     - In the target state, performed through the versioned coordination maintenance CLI after that tooling is implemented and proved.
     - Always accompanied by post‑reset health checks (ticks can be scheduled, sessions can be created/resumed, automation works).
     - Region‑ and tenant‑scoped resets should prefer **smaller scopes first**; cluster‑scoped reset is reserved for catastrophic or planned migration scenarios where finer scopes are ineffective.

General in-place repair of coordination keys is intentionally **not** a first-implementation operator path. A future repair path may be added only by defining named maintenance CLI verbs with scope rules, fencing/quiescence requirements, audit output, and mandatory post-repair verification. In the current fallback, any direct coordination-prefix mutation is break-glass activity: preserve the mutation evidence, keep the affected scope fenced, and escalate when no supported scoped reset or cleanup path exists. In the target state, a direct mutation must be followed by the owner-defined scoped reset or documented cleanup flow that covers the mutated prefix before normal processing resumes.

Target-state design reviews should explicitly state which of these strategies is expected to be safe for each coordination structure.

---

## Common Reset Scenarios

This section outlines representative scenarios and recommended reset scopes. Detailed step‑by‑step flows live in `system-architecture-redis-operations.md`.

All reset actions in these scenarios are target-state guidance for the future durable recovery controller. Until that controller and its supported scopes are implemented and proved, current operators must use the [Current Operator Fallback](#current-operator-fallback) and must not execute the referenced reset, continuation, release-lock, migration, or destructive storage steps.

### Mis-keyed Tick Data for a Single Region

Symptoms:

- Tick processing for one region stalls or repeatedly fails.
- Pending and retry queues show malformed or unexpected entries.

Recommended actions:

- Target state only: once the bounded recovery controller and region scope are implemented and proved, it executes the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) at region scope with an explicit session policy; this example chooses `--preserve-sessions`.
- Apply the explicitly recorded region reset session policy:
  - Leave sessions and other non-region-scoped keys intact unless a broader documented workflow is explicitly chosen.
  - Keep preserved sessions fenced until the owner-defined session and recovery evidence contracts qualify region-local gameplay bindings.

Expected impact:

- Players may see delay, replay, or explicit non-application according to ADR 0058 class-specific outcomes; no correctness-bearing work is silently discarded.
- No permanent loss of authoritative game data in PostgreSQL.

### Buggy Coordination Script Affecting Multiple Regions for One Tenant

Symptoms:

- Multiple regions for a tenant show inconsistent pending/retry structures.
- Metrics indicate repeated script failures or unexpected error codes.

Recommended actions:

- Roll out a fixed script version.
- Target state only: once the bounded recovery controller and tenant scope are implemented and proved, it executes the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) at tenant scope.
- The future controller records exactly one tenant session policy, `--preserve-sessions` or `--invalidate-sessions`, for the affected tenant reset; it never infers the policy from tenant scope.

Expected impact:

- In-progress actions follow ADR 0058 durable reconstruction/terminalization outcomes; Redis loss does not authorize silent discard.
- Long‑lived domain state remains safe; scripts and tick processing resume in a clean coordination environment.

### Manual Break-Glass Edits to Coordination Keys

Symptoms:

- An operator used `redis-cli` or a raw script to mutate `tick:*`, `timer:*`, `retry:*`, `remote:*`, `session:game:*`, `session:auth:token:*`, `session:auth:generation:*`, or `tick-executor-lease:*`.

Recommended actions:

- Treat the affected scope as “coordination state may be inconsistent”.
- Select the smallest valid domain-specific recovery workflow for every mutated prefix rather than assuming a region or tenant reset can repair all keys. Region-local coordination keys use the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) with an explicit session-policy choice. Account-owned `session:auth:token:*` or `session:auth:generation:*` edits require Account-owned projection repair or cluster reset, and replay-marker edits require replay quarantine, fencing, and durable consume acknowledgement. Because those target workflows are not yet shipped end to end, current operators must preserve evidence, keep all affected scopes fenced, and use the [Current Operator Fallback](#current-operator-fallback) instead of attempting a reset.
- Record the incident using the standard audit fields (who, when, why, which prefixes/tenants/regions).

Expected impact:

- Coordination state is rebuilt from domain data; the risk from manual edits is removed.

### Full Cluster Rebuild or Migration

Symptoms:

- Coordination Redis must be replaced or re‑sharded in a way that invalidates existing keys.

Recommended actions:

- Target state only: once the bounded recovery controller and cluster scope are implemented and proved, it plans a **cluster-scoped reset** as part of a controlled maintenance window.
  - The future controller executes the canonical reset and [complete canonical pre-wipe gates](./system-architecture-redis-ops-access.md#canonical-pre-wipe-gates) at cluster scope with explicit `--invalidate-sessions`; this scenario does not restate those gates or the recovery controller's order. Replacement startup verification remains separate post-reset evidence.
- Communicate expected impact to tenants and players.

Expected impact:

- All coordination state is reset; ticks restart from the new epoch baseline. The required explicit `--invalidate-sessions` policy keeps protected admission fenced; Account-owned authority/token cutover and immutable pre-wipe handoff evidence complete before the wipe, Account projection repair and durable readback occur after reset, and replay quarantine plus durable replay-consumption proof complete before replacement registration. Physical deletion is cleanup only for old or stale projections; replacement registry records remain after registration, and fresh authentication/play is required after owner-defined release. Cluster scope rejects `--preserve-sessions`. By contrast, a tenant-scoped reset preserves Account-owned `session:auth:token:*` records while its `session:game:*` policy is determined by the explicitly recorded session policy; pre-auth transport context is always invalidated or rebuilt.
- Domain data (PostgreSQL) remains authoritative.

---

## Current Operator Fallback

For a current Coordination Redis cold start, incomplete recovery, or reset incident:

- Preserve the AOF, operation records, and incident evidence. Keep Gateway protected admission, gameplay mutation, command intake, and affected coordination writers fenced or stopped; an empty keyspace is not safe-resume evidence.
- No current scoped reset is supported. The target-state scope grammar and `coordination-maintenance` CLI are unavailable today, and raw coordination-prefix mutations are not a reset path.
- Use only the shipped `PauseTicksForScope` pause and `GetRuntimeOwnershipStatus` status surface for the supported `{tenantId, gameInstanceId}` boundary, plus read-only `coord_ops_ro` inspection. Follow the current failover/AOF procedures and escalation path in the [Redis incident runbook](./system-architecture-redis-incident-runbook.md) and [Redis Operations](./system-architecture-redis-operations.md); do not invoke the target-state CLI or use raw coordination-prefix mutations.
- No fallback or future recovery release may rely on an empty keyspace, script loading, sample ticks, or local operator observations. The complete [canonical post-reset verification checklist](./system-architecture-redis-ops-access.md#canonical-post-reset-verification-checklist) and applicable recovery release gates are required; script-load and sample-tick checks are supplemental only.
- If the durable recovery controller, Account projection repair/replacement, replay quarantine/fence, or immutable evidence path is unavailable, stale, or ambiguous, abort any destructive wipe, recovery continuation, `resume`, `release-lock`, or reopen attempt. Leave the fence in place and escalate; there is no supported current full-wipe or unlock substitute.

---

## Interaction with Measured Coordination Exposure and Replay

Coordination resets interact with measured coordination exposure and replay in predictable ways:

- A **reset** is a deliberate loss of all **reset-eligible** coordination state for the chosen scope:
  - The system discards all reset-eligible coordination state rather than the bounded measured unreplicated-write exposure of an ordinary failover; it does not discard all coordination state. Account-owned auth token/generation projections and the Gateway replay authority remain under their owner-specific exclusions and cutover rules defined earlier in this document. ADR 0058 defines class-specific durable outcomes; no tick-derived loss formula applies.
  - This is only safe when:
    - All critical outcomes are recorded durably in PostgreSQL or another authoritative store.
    - Double‑apply is prevented via idempotency guards (for example, effect IDs, transaction IDs).

- In-place **repair** is not part of the first-implementation operator model:
  - Local mutations of coordination keys bypass the normal script/key-builder path unless they are wrapped in future dedicated maintenance tooling.
  - Until such tooling exists, direct mutation is break-glass activity and is followed by the reset/cleanup rules above rather than treated as a durable fix.

- **Replay** behavior must remain safe regardless of resets:
  - Lua scripts must be idempotent with respect to their `KEYS` and `ARGV`.
  - Replaying a subset of surviving entries after a reset should not violate core invariants or double‑apply domain effects.

Designers should use the **Redis Design Checklist** to confirm that new flows remain safe under:

- Normal measured coordination-write exposure and replay.
- Scoped resets at region/tenant/cluster levels.

---

## Operator Expectations

Operators interacting with Coordination Redis should assume:

- **Target-state resets are normal tools**, not last‑resort hacks once the bounded controller is implemented and proven:
  - Region‑ and tenant‑scoped resets are standard responses to certain classes of incidents.
  - Cluster‑scoped resets are rare but documented for extreme scenarios.

- **Break‑glass writes require follow‑up resets**:
  - Any manual mutation of core coordination prefixes is considered equivalent to corruption for that scope.
  - Runbooks must include clear guidance to reset and verify affected regions/tenants afterwards.

- **Metrics drive decisions**:
  - Measured unreplicated-write-window observability (described in `system-architecture-redis-operations.md`) surfaces when exposure exceeds the environment SLO.
  - Tick watermarks, retry depths, and script error codes inform whether to accept loss or reset at the smallest safe scope.

- **Auditability matters**:
  - All resets and break‑glass actions should emit structured audit events with:
    - A unique identifier and timestamp.
    - Affected prefixes, tenants, and regions.
    - Initiator identity (human or automation).
    - Rationale and incident links where applicable.

---

## Related Documentation

- `system-architecture-redis.md` – conceptual hub for roles, invariants, and key naming.
- `system-architecture-redis-operations.md` – concrete reset and migration runbooks.
- `system-architecture-redis-design-checklist.md` – checklist for assessing reset tolerance and measured coordination-write exposure compatibility.
- `system-architecture-redis-lua-patterns.md` – Lua script requirements for idempotency and replay safety.
- `system-architecture-redis-ops-access.md` – ACL and tooling expectations for operators.

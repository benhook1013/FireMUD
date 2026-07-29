# FireMUD Redis Reset & Recovery

This document defines the **coordination reset model** for FireMUD: when and how Coordination Redis can be reset, how tail‑loss interacts with recovery, and what operators should expect during incidents. It complements the conceptual hub (`system-architecture-redis.md`) and the concrete runbooks in `system-architecture-redis-operations.md`, which owns the canonical reset command sequence.

## Implementation Notes

This document describes the intended reset/recovery end state. The currently shipped runtime is narrower:

- Game Session already has a durable current-boundary ownership row, epoch/fence bumping on pause/resume, durable command status lookup, and startup convergence of accepted-but-unstaged commands to `LOST_BEFORE_STAGING`.
- Those live surfaces operate on the current `{tenantId, gameInstanceId}` queue boundary rather than the full region/tenant/cluster reset grammar described below.
- The full `coordination-maintenance recover --mode reset --scope <scope> <session-policy-option>` operation, with exactly one of `--preserve-sessions` or `--invalidate-sessions`, and its internal pause, epoch/reset, ledger-reconciliation, command-convergence, metadata, smoke-check, and release phases remain the target-state operator model; it should not be read as fully implemented tooling in this repository today. Every reset request records one explicit session policy; it is never inferred from scope. Those internal phases are not separate public commands; continuation and abort use the supported controls for the same durable operation. Initial `recover` issues the server-side maintenance lock; every subsequent control in the examples below presents it through `--maintenance-lock-token-file <permissioned-token-file>` (or the documented protected FD form), never as a command-line token value.
- The canonical preservation vocabulary is `session:game:*` plus pre-auth transport context. The current implementation still stores some pre-auth and lookup details in transitional `sessionctx:*` records/indexes; that implementation mapping is not a separate preservation domain or target-state contract.
- The Account-owned authority-generation projections and their repair/replacement workflow are also target state and are not currently implemented and proven end to end. The rules below are the required behavior: set-if-greater is valid only for a missing or lower Redis projection; a projection greater than Account durable authority is poisoned and must be quarantined or replaced through an Account-owned audited workflow.

Operator invocation boundary: every `coordination-maintenance recover`, `continue-recovery`, `resume`, and `release-lock` command shown in this document, including the cold-start and recovery worked examples below, is a target-state-only future example and is unavailable today. The CLI is not currently shipped or proven, so current operators must not invoke it; use the fail-closed fallback below, the shipped Redis recovery procedures in [Redis Operations](./system-architecture-redis-operations.md), and the normal incident escalation path instead.

## Current Operator Fallback

For a current Coordination Redis cold start, incomplete recovery, or reset incident:

- Keep Gateway protected admission, gameplay mutation, command intake, and affected coordination writers fenced or stopped. An empty keyspace is not evidence that the scope is safe to resume.
- Use only the shipped `PauseTicksForScope` pause and `GetRuntimeOwnershipStatus` status surface for the supported `{tenantId, gameInstanceId}` boundary, plus read-only `coord_ops_ro` Redis inspection. Follow the current failover/AOF procedures and escalation path in the [Redis incident runbook](./system-architecture-redis-incident-runbook.md) and [Redis Operations](./system-architecture-redis-operations.md); do not invoke the target-state CLI or use raw coordination-prefix mutations.
- If the durable recovery controller, Account projection repair/replacement, replay quarantine/fence, or immutable evidence path is unavailable, stale, or ambiguous, abort any destructive wipe, recovery continuation, `resume`, `release-lock`, or reopen attempt. Preserve the AOF and incident evidence, leave the fence in place, and escalate. There is no supported current full-wipe or unlock substitute.

---

## Table of Contents

- [Coordination Reset Model](#coordination-reset-model)
- [Current Operator Fallback](#current-operator-fallback)
- [Reset vs Accept Loss](#reset-vs-accept-loss)
- [Common Reset Scenarios](#common-reset-scenarios)
- [Interaction with Tail-Loss and Replay](#interaction-with-tail-loss-and-replay)
- [Operator Expectations](#operator-expectations)
- [Related Documentation](#related-documentation)

---

## Coordination Reset Model

Coordination Redis is treated as a **long‑lived, tail‑loss‑bounded coordination buffer** in persistent environments, **not** as a durable log of record; it remains volatile and reset‑tolerant under controlled conditions. Authoritative history for gameplay outcomes always lives in PostgreSQL tick effect ledgers and domain stores as described in `system-architecture-redis.md`, and neither coordination keys nor AOF contents are ever treated as the primary log of record. The reset model centers on three scopes:

- **Region‑scoped reset** – affects a single `<tenantId, gameInstanceId, regionId>`:
  - Clears tick queues, timers, retry structures, and region‑scoped locks/leases for one region.
  - Does not clear Account-owned issued-token registry records or authority-generation projections because neither is region-scoped coordination state.
  - Leaves other regions and tenants untouched.
  - Typically used when:
    - A mis‑keyed script or bug has polluted tick state for one region.
    - An incident is confined to a subset of the world.

- **Tenant‑scoped reset** – affects a single `tenantId`:
  - Clears coordination keys for all regions under one tenant.
  - Preserves `session:game:*` records and pre-auth transport context only when operators explicitly choose `--preserve-sessions`; otherwise those gameplay-session and transport-context records are invalidated.
  - Does not clear Account-owned `session:auth:token:<tokenHash>` records or authority-generation projections. Region- and tenant-scoped resets preserve those records because they are not region-scoped coordination state; a tenant reset may terminate affected gameplay bindings, but it must not turn a tenant-region coordination reset into account-wide token revocation.
  - Often combined with an in‑game maintenance window or a revert/repin of tenant‑specific published content.
  - Used when:
    - A full in‑game reset is acceptable for a single tenant.
    - Cross‑region coordination problems cannot be repaired region by region.

- **Cluster-scoped reset** – affects an entire Coordination Redis deployment:
  - Clears coordination state for all tenants and regions on that deployment.
  - Establishes protected-traffic quarantine, pauses and epoch-fences all affected regions, and runs the ordinary coordination reset. The Account-owned repair/reset cutover then advances the issuer authority generation before old Account-issued-token projections are physically deleted. Replay admission remains quarantined while `replayAdmissionFence` advances, the full lifetime-plus-skew window elapses, and durable consume proof completes; replacement issued-token projections cannot be registered before those replay proofs pass. Only after registration are replacement records validated against the exact-token registry and exercised by representative smoke; Gateway remains blocked throughout and until the complete recovery release gate succeeds. Physical deletion is cleanup, not the authorization boundary.
  - Reserved for extreme cases:
    - Catastrophic corruption or misconfiguration.
    - Planned migrations where coordination state cannot be incrementally migrated.

Every region- or tenant-scoped reset requires exactly one explicit gameplay-session policy: `--preserve-sessions` or `--invalidate-sessions`. That choice applies only to the scope-selected `session:game:*` records and pre-auth transport context; it never changes issued-token registry retention or handling. Region and tenant resets preserve Account-owned `session:auth:token:<tokenHash>` records. Every cluster-scoped reset requires explicit `--invalidate-sessions` and rejects `--preserve-sessions`: it first closes protected admission, pauses and epoch-fences the affected regions, and runs the ordinary coordination reset; the Account repair/reset cutover then gates physical deletion of old token records and later replacement registration, so old gameplay bindings cannot remain authoritative through that cutover.

For any cluster reset or cold start that can drop `session:auth:*`, the required ordering is: close Gateway protected admission and command intake; complete the internal pause/maintenance-lock and epoch-fencing phases; run the ordinary coordination reset; complete the Account restore/reset cutover and advance the issuer authority generation before physically deleting old Account token projections; advance `replayAdmissionFence`, keep replay quarantine closed for the full lifetime-plus-skew window, and prove durable replay consumption; only then rebuild the current generation projection and register replacement issued-token records. Validate each replacement against the exact-token registry and run representative smoke after registration, while Gateway remains blocked throughout. A missing issued-token registry record is a denial condition for every protected route that requires that record. Validators must not recreate a record from a still-valid JWT, and a valid signature, `exp`, or gameplay/session key is not sufficient authority; unresolved cutover, replay proof, exact-token validation, projection, or smoke state keeps admission closed.

For a destructive full-deployment or AOF reset, the canonical pre-wipe gates are `scope_paused_and_locked`, `account_authority_token_cutover`, `replay_domain_quarantine_fence`, and `immutable_external_handoff_evidence`, as defined in [Coordination Redis Ops Access & Tooling](./system-architecture-redis-ops-access.md#canonical-pre-wipe-gates). Here `immutable_external_handoff_evidence` means only immutable pre-wipe authorization and fencing evidence: the authorized action, target scope, old deployment identity, intended replacement target, and proof that the old endpoint is fenced. These gates are internal evidence gates, not public commands, and must all be bound to the same durable `operationId` and server-issued lock binding before the external storage action; evidence retains only the lock digest or opaque lock reference, never the plaintext token. Post-reset replacement verification is a separate evidence group recorded only after the replacement starts; it is not a pre-wipe prerequisite. An empty keyspace never proves any gate.

The two protected cleanup authorities are independent and neither substitutes for the other:

- **Account token-projection deletion gate:** the Account repair/reset cutover must advance the issuer authority generation and durably attest the cutover before old Account-issued token projections may be physically deleted. This Account evidence authorizes only the token-projection deletion boundary; it does not authorize replay-marker cleanup or replacement-token registration.
- **Replay-marker cleanup gate:** the replay domain must advance `replayAdmissionFence`, remain quarantined for the full token lifetime plus configured clock-skew allowance, and provide durable consume proof before replay markers may be cleaned up and replacement replay admission can proceed. This replay evidence authorizes only the replay cleanup boundary; it does not authorize Account token-projection deletion.

Both gates remain required, operation-bound, and fail closed when incomplete or ambiguous.

Resets are always executed via **versioned coordination tooling** (for example, a maintenance CLI), not ad‑hoc `redis-cli` commands. Every reset:

- Identifies the exact scope (region/tenant/cluster).
- Uses shared key builders and descriptors for the relevant prefixes.
- Emits audit events documenting who initiated the reset, why, and what was affected.

Concrete commands live in [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence); this document explains when and why to choose each scope.

### Tick Reset Handshake (Timeline View)

Because ticks treat `(region_epoch, tickId)` as the canonical coordination timeline (see `system-architecture-ticks.md` and `system-architecture-tick-concepts-and-invariants.md`), every coordination reset must follow a simple internal handshake with the tick control plane. The numbered phases below describe work owned by one public recover operation; they are not a sequence of public CLI invocations:

1. **Internal pause-and-lock phase for the chosen scope**
   - The `coordination-maintenance recover --mode reset --scope <scope> <session-policy-option>` operation, with exactly one explicit session-policy option, asks the Game Session control plane (or equivalent admin service) to pause tick scheduling and new command intake for the affected `<tenantId, gameInstanceId, regionId>` pairs (region/tenant) or all regions (cluster).
   - This pause step is complete only once the scope reaches the control-plane `PAUSED` state defined in `system-architecture-redis-ops-access.md`: no executor in the target scope is allowed to create new durable tick batches or new Redis coordination state under the old epoch. The resulting `scope_paused_and_locked` evidence must identify the same durable `operationId` and server-issued lock binding that authorize every later reset phase, using only its lock digest or opaque lock reference; a paused scope under another operation or lock is not sufficient.

2. **Bump `region_epoch` in PostgreSQL**
   - For each affected `<tenantId, gameInstanceId, regionId>`, the internal epoch-bump phase of the canonical recover operation updates `region_epoch` in the coordination metadata table so that any surviving executors and locks become stale by definition.
   - This step is authoritative: new executors always treat the highest `region_epoch` as the only valid timeline, and tick heartbeat streams (`StreamTickHeartbeats`) will begin emitting the new `regionEpoch` for those regions so consumers can distinguish pre- and post-reset ticks.
3. **Run the scoped reset tooling**
   - Use the versioned coordination maintenance CLI to clear keys in Coordination Redis for the chosen scope, using shared key builders and descriptors.
   - No ad-hoc `DEL`/`FLUSH*` commands are used; all prefixes and key shapes are driven from the same catalogs used by the Lua Script Registry.
4. **Reconcile durable tick and command state**
   - For the affected scope, `SCHEDULED` ledger rows tied to the old `region_epoch` converge to terminal outcomes (typically `ABANDONED` with a reset-specific reason) via a scoped tick-effect-ledger reconcile step in the reset tooling, as described in `system-architecture-tick-failures-and-operations.md`.
   - New executors do not resume old-epoch `SCHEDULED` rows; any re-drive or migration across epochs is performed only by dedicated maintenance tooling that explicitly re-creates effects in the new epoch.
   - In the same reset scope, accepted command records that never became durably tied to a surviving `tick_batch_id` converge to `TERMINAL` with `executionOutcome = LOST_BEFORE_STAGING` and the command-type-appropriate `gameplayResult` (shared default `NOT_APPLIED`); reset tooling must not leave dedupe rows stranded in `RECEIVED` or `ENQUEUED`. For the canonical shared command terminal mapping table, see `system-architecture-tick-execution-flows.md` under `Canonical Command Terminal Mapping Table`.
5. **Reset per-region metadata keys**
   - Using the same maintenance CLI and key-builder helpers, initialize or update `tick:{tenantRegionTag}:meta` for each affected `<tenantId, gameInstanceId, regionId>` so that:
     - `region_epoch` reflects the new epoch recorded in PostgreSQL.
     - `current_tick_id` is set to the RegionStatus commit baseline sentinel (default `-1` immediately after a reset); while the traffic fence is active no real tick is committable, and only after internal release may the first committable tick in the new epoch be `tickId=0`, unless an explicit maintenance baseline is documented.
     - `current_tick_state` is initialized to the terminal baseline `APPLIED` for that sentinel `current_tick_id` so the next real tick may advance cleanly under the Lua state machine.
     - `current_tick_terminal_at_ms` is set to the reset/init-meta write timestamp for observability and bounded cleanup only; it is not a correctness input.
   - This keeps Lua monotonic guards (`region_epoch`, `current_tick_id`) in Redis consistent with the durable timeline used by schedulers and operators.
6. **Rebuild Account authority and token projections**
   - Before applying the session policy or attempting any preserved-session rebind, recovery requests Account Service to rebuild and verify the issuer, account, tenant, membership-generation, and affected issued-token projections from Account durable authority, awaits the durable result, and verifies its returned freshness/generation evidence.
   - Recovery is not an Account projection writer. Account-owned projection repair may use idempotent set-if-greater only when the Redis projection is missing or lower than Account durable authority. If Redis reports a generation greater than the durable value, recovery must not use set-if-greater to preserve that poisoned value or treat it as authority; Account must quarantine or replace the affected exact scope through an audited workflow, recreate the projection from durable authority, and emit immutable per-scope repair evidence and verification. Region- and tenant-scoped resets preserve unaffected Account-owned records but still require exact-generation validation; a cluster reset verifies the Account repair/reset cutover that preceded physical cleanup, then registers replacement issued-token projections and proves exact-token validation before representative-region smoke.
   - Missing, stale, malformed, mismatched, ambiguous, or poisoned authority, token, generation, or durable freshness evidence keeps the affected scope fenced and prevents protected traffic or session-policy application. Protected traffic may reopen only after the controller accepts the required per-scope repair and verification evidence.
7. **Apply the session-policy phase for the affected regions**
   - The selected session policy either invalidates gameplay sessions or preserves them and requires the internal rebind flow below before traffic resumes.
   - The session-policy flag controls gameplay-session retention explicitly; the canonical region flow chooses `--preserve-sessions`, and no region or tenant scope may infer its choice. Cluster scope accepts only explicit `--invalidate-sessions`. Clearing `tick:{tenantRegionTag}:*` also clears region-authoritative `tick:{tenantRegionTag}:session-binding:<entityId>` keys.
   - Before normal command intake resumes, Game Session runs the same session-to-region bridge flow used by reconnect/`PLAY` for any preserved authenticated session that still intends to control an entity in the reset region.
   - The complete canonical preserved-session rebind predicate requires all of the following before the region bridge may recreate a binding:
     - A complete target `schemaVersion=2` authenticated gameplay session payload with `rebindHandleEnvelope`, `continuityBindingExpiresAt`, `membershipVersion`, and `membershipAuthorityGeneration`; a `schemaVersion=1` or incomplete record is storage-only and cannot be rebound.
     - An exact `session:auth:token:<tokenHash>` registry record addressed by the payload's `authTokenHash` that is present, active, unrevoked, and unexpired, plus Account validation of the opaque `rebindHandleEnvelope` proving its bound token hash, signed `jti` and `nbf`, token lineage, account identity, profile, and audience match the registry record and gameplay binding exactly.
     - A separate current Account-owned authority-freshness lease for the exact authenticated binding, including `authorityLeaseExpiresAt`, its committed authority checkpoint, and the applicable lease fence.
     - Current Account authority for the exact account and tenant, including `issuerAuthGeneration`, `accountAuthorityGeneration`, `tenantAuthorityGeneration`, caller-bound `membershipAuthorityGeneration`, and private-realm `grantVersion` when applicable, plus current `membershipVersion`, entitlement, revocation, and committed checkpoint evidence.
     - A valid `continuityBindingExpiresAt` and applicable `resumeDeadline` that the rebind does not extend, together with the expected monotonic `binding_generation` and the operation's current region epoch and lease-fence evidence.
     - A successful invocation of the same session-to-region bridge used by `PLAY` / reconnect; `session:game:*` and pre-auth transport context are not authority substitutes.
   - Stale or unverifiable sessions remain connected but are not gameplay-admitted to that region until fresh `LOGIN` / `PLAY` succeeds.
   - During the gap between reset and successful rebind, command admission must fail closed with the terminal/non-applied outcome `"REGION_REBIND_REQUIRED"` rather than treating `session:game:*` or pre-auth transport context advisory fields as region-local authority.
   - A failed preserved-session predicate never implicitly changes the recorded policy. The operation remains paused and fenced under the same `operationId` and `maintenanceLockToken`; an explicit audited preserve-to-invalidate transition may compare-and-set the policy under that lock, recording actor, reason, and immutable evidence before invalidation. If that transition is unavailable, complete audited abandonment and start an explicit new recover operation with `--invalidate-sessions`. Rebind failure alone is not invalidation proof.
8. **Run the post-reset smoke check**
   - The internal post-reset smoke-check phase is required before normal traffic resumes. It must include replay-domain quarantine/fence and durable consume proof where the reset affects replay admission, plus the verified Account projection-rebuild evidence. Passing it atomically records `ready_to_reopen` while retaining the maintenance lock and traffic fence; only `continueRecovery(... expectedPhase=ready_to_reopen ...)` may then advance the operation to `AWAITING_RESUME`.
   - Any smoke tick exercised by the recovery or `continueRecovery` path is synthetic maintenance traffic only. It is not the first real tick, must not be exposed to players, and must not authorize player ingress or real `tickId=0` admission; real `tickId=0` and player ingress remain blocked until the internal release phase clears the traffic fence.
   - The smoke check must satisfy the canonical checklist in `system-architecture-redis-ops-access.md`, including lease acquisition, exactly-one batch allocation, Redis staging correlation, ledger convergence, and cleanup.
9. **Resume ticks on the new epoch**
   - The public `resume(operationId, expectedPhase, maintenanceLockToken, evidenceRef)` safety control must use `expectedPhase=awaiting_resume`, resolve the operation's recorded scope, and first atomically move the matching operation from `AWAITING_RESUME` to `RESUME_AUTHORIZED` without releasing its lock or fence.
   - The internal final phase retains the traffic fence while it applies each required release effect under the recorded operation fence and reads back that effect's durable/current state. A release effect is not complete merely because an apply call returned successfully; every effect must have a matching idempotency identity and a successful, current readback before the controller can release the fence, release the maintenance lock, reach `finalized`, or record terminal `SUCCEEDED`.
   - If any release effect fails or has an ambiguous readback after another effect may have applied, the controller durably enters `PARTIAL_RELEASE_RECONCILING` (persisted as `partial_release_reconciling`), inventories every effect, keeps unreleased work fenced, and contains or re-fences every already-released effect before retrying. It may return to `releasing` only after that containment is durably observed; it must not skip an effect, repeat a confirmed effect, or treat partial release as success.
   - Through `AWAITING_RESUME`, `RESUME_AUTHORIZED`, `releasing`, and `PARTIAL_RELEASE_RECONCILING`, the traffic fence remains active: player traffic and real `tickId=0` remain blocked. Only complete per-effect apply-and-readback verification may clear the fence and reopen normal admission.
   - The canonical recovery phase enumeration is `PAUSED`, `collecting`, `ready_to_reopen`, `AWAITING_RESUME`, `RESUME_AUTHORIZED`, `releasing`, `PARTIAL_RELEASE_RECONCILING`, and `finalized`; `PARTIAL_RELEASE_RECONCILING` is represented by the persisted lower-snake-case value `partial_release_reconciling`. The public `resume` request must send the exact lowercase wire value `expectedPhase=awaiting_resume`. `AWAITING_RESUME` is the internal control-plane state reached by `continueRecovery`, while `RESUME_AUTHORIZED` is the internal authorization transition/audit event produced by `resume`; neither uppercase name is a public wire-form alias. Only after the internal release phase reaches `finalized` may new ticks start from the **new (bumped) `region_epoch`** with first committable tick `tickId=0` for each affected region (`lastCommittedTickId` remains at the sentinel `-1` until tick `0` commits); all subsequent coordination state is written under that new epoch.

Heartbeat consumers that track progress or offsets must key their state by `(tenantId, gameInstanceId, regionId, regionEpoch)` (with `lastCommittedTickId` / offsets stored as values) and treat any observed epoch change on the stream as a reset boundary, rebuilding their own derived state from domain stores instead of assuming continuity of `tickId` alone.

This handshake ensures that resets move regions forward on the coordination timeline instead of trying to “repair” mixed-epoch state in place.

Worked example: region-scoped reset for `<tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120, gameInstanceId=9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, regionId=R7>` (target-state only; unavailable today)

> **Target-state examples only; unavailable today.** The `coordination-maintenance` commands in the reset examples below are illustrative contract examples, not current operator instructions. Current operators must not invoke `recover`, `continue-recovery`, `resume`, or `release-lock` from this document.

1. In the target-state workflow, `coordination-maintenance recover --mode reset --scope region --tenant 7b3b074e-d597-4e9b-b96f-4f5946d26120 --game-instance 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78 --region R7 --preserve-sessions` creates one durable operation, rejects new command intake, and runs the ordered internal phases. In this example those phases bump `region_epoch` from `12` to `13`, clear `tick:{tenantRegionTag}:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, and `tick-executor-lease:{tenantRegionTag}`, converge old-epoch ledger and accepted-but-unbound command records, initialize the Redis metadata baseline, rebuild and verify Account authority/token projections with durable freshness evidence, then rebind still-valid preserved sessions and pass the smoke gate while traffic and real tick admission remain fenced.
2. In the target-state workflow, run `coordination-maintenance continue-recovery --operation-id <operationId> --expected-phase ready_to_reopen --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>`. It reconciles only into `AWAITING_RESUME` and cannot select or skip phases.
3. In the target-state workflow, run public `coordination-maintenance resume --operation-id <operationId> --expected-phase awaiting_resume --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>`; it records `RESUME_AUTHORIZED`, after which only the internal release phase may clear the fence, reopen the scope after `finalized`, and permit the first real `tickId=0`.
4. In the target-state workflow, the audited failure control is `coordination-maintenance release-lock --operation-id <operationId> --scope region --tenant 7b3b074e-d597-4e9b-b96f-4f5946d26120 --game-instance 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78 --region R7 --maintenance-lock-token-file <permissioned-token-file> --reason <reason> --evidence-ref <evidenceRef>`. It records audited abandonment, retains the paused/fenced state, and does not reopen traffic.

Worked example: tenant-scoped reset for `<tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120>` with `--preserve-sessions` (target-state only; unavailable today)

1. In the target-state workflow, `coordination-maintenance recover --mode reset --scope tenant --tenant 7b3b074e-d597-4e9b-b96f-4f5946d26120 --preserve-sessions` creates one durable operation and runs the ordered internal phases across the complete affected-region inventory. It bumps each `region_epoch`, clears tenant coordination prefixes except preserved `session:game:*` records and pre-auth transport context, including every `remote:{tenantInstanceTag}:*` pattern from the durable game-instance inventory, converges ledger and accepted-but-unbound command records, initializes metadata, rebuilds and verifies Account authority/token projections with durable freshness evidence, then rebinds preserved sessions and samples the required representative regions before success release. Account-owned `session:auth:token:<tokenHash>` records and authority generations remain retained.
2. In the target-state workflow, run `coordination-maintenance continue-recovery --operation-id <operationId> --expected-phase ready_to_reopen --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>`. The operation retains its scope and session policy; the control cannot select or skip an internal phase and reconciles only into `AWAITING_RESUME`.
3. In the target-state workflow, run public `coordination-maintenance resume --operation-id <operationId> --expected-phase awaiting_resume --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>`; it records `RESUME_AUTHORIZED`, after which only the internal release phase may then reopen the scope after `finalized`.
4. In the target-state workflow, the audited failure control is `coordination-maintenance release-lock --operation-id <operationId> --scope tenant --tenant 7b3b074e-d597-4e9b-b96f-4f5946d26120 --maintenance-lock-token-file <permissioned-token-file> --reason <reason> --evidence-ref <evidenceRef>`. The scope remains fenced and does not reopen.

Worked example: cluster-scoped reset (target-state only; unavailable today)

1. In the target-state workflow, `coordination-maintenance recover --mode reset --scope cluster --invalidate-sessions` creates one durable operation that closes Gateway protected admission and command intake, completes the internal pause/maintenance-lock and epoch-fencing phases, and runs the ordinary coordination reset for leases, queues, timers, retries, remote hints, and observer streams. Before any external cluster storage wipe, the operation must establish `scope_paused_and_locked`, `account_authority_token_cutover`, `replay_domain_quarantine_fence`, and `immutable_external_handoff_evidence` for that same durable `operationId` and server-issued lock binding, retaining only its lock digest or opaque lock reference in evidence. The last group is immutable pre-wipe authorization/fencing evidence only; it does not include facts that can be observed only after the replacement starts. The Account issuer-generation repair/reset cutover then gates physical deletion of old Account-issued token registry projections; physical deletion is cleanup and not the authorization boundary. Replay markers are handled separately from the ordinary reset, and Gateway cannot admit first-party gameplay handshakes until the replay proofs and all other pre-wipe gates complete.
2. In the target-state workflow, the same operation internally converges old-epoch ledger rows and accepted-but-unbound command records, rebuilds and proves the current Account issuer-generation projection, and initializes every region's Redis metadata baseline. When the replacement starts, the operator records separate immutable `post_reset_replacement_verification` for the replacement endpoint identity, ACL/configuration, empty keyspace, and health after startup. The controller validates both the pre-wipe authorization/fencing group and this post-reset verification, bound to the same operation, scope, and maintenance lock, before `continueRecovery` can reach `AWAITING_RESUME` and before public `resume` can authorize release. Only after replay quarantine, the lifetime-plus-skew window, and durable consume proof pass does it register replacement issued-token projections; it then validates each replacement against the exact-token registry contract and proves the required representative-region smoke gate. Gateway remains blocked through registration, exact-token validation, and representative-region smoke. The required `--invalidate-sessions` policy invalidates every affected gameplay session and forces fresh authentication/play against the replacement token registry; cluster scope does not support preserved-session rebind.
3. In the target-state workflow, run `coordination-maintenance continue-recovery --operation-id <operationId> --expected-phase ready_to_reopen --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>`. It reconciles only into `AWAITING_RESUME`; then run public `coordination-maintenance resume --operation-id <operationId> --expected-phase awaiting_resume --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>`, which must record `RESUME_AUTHORIZED` before the internal release phase can reopen the cluster. The target-state audited failure control is `coordination-maintenance release-lock --operation-id <operationId> --scope cluster --maintenance-lock-token-file <permissioned-token-file> --reason <reason> --evidence-ref <evidenceRef>`; it retains quarantine and the fence, and neither control can bypass the Account, replay, or smoke proofs.

### Reset Ordering Is Normative

The nine-step handshake above is the authoritative order for all scoped resets and full wipes:

- No runbook may clear Coordination Redis for a scope before the pause-and-epoch-bump steps complete for that same scope.
- Storage-level wipes, PVC deletion, `FLUSH*`, or prefix deletion that happen before epoch fencing are treated as an invalid reset sequence because stale executors could repopulate empty coordination state under the old epoch.
- A destructive cluster/full-wipe path must close protected admission and command intake, establish `scope_paused_and_locked` under the same operation and maintenance lock as the wipe, quarantine and fence the shared replay domain, and complete the Account issuer-generation cutover before deleting protected Account token projections or replay markers. Its immutable pre-wipe authorization/fencing evidence must exist before the wipe; its separate post-reset replacement verification is recorded only after replacement startup. The controller must validate both evidence groups, plus current Account projections and durable replay-consume evidence, before public resume; ordinary Coordination Redis deletion is never a substitute for those protected-domain proofs.
- Full-wipe runbooks in `system-architecture-redis-operations.md` are required to embed this same order rather than defining an alternate sequence.
- Any reset scope that preserves gameplay sessions but clears region-local `tick:{tenantRegionTag}:session-binding:*` keys must complete the Account authority/token projection rebuild and verified durable freshness gate before applying the preserve policy or starting rebind, and must complete rebind before normal command intake resumes.
- A cluster reset must keep protected admission closed through the Account issuer-generation repair/reset cutover, token cleanup, current-projection rebuild/proof, replacement-token registration/proof, post-reset replacement verification, and post-reset smoke check. Projection proof alone is insufficient; a reauthentication-only opening must continue to reject every registry-gated protected route. A Redis generation greater than Account durable authority is poisoned, not a valid newer value: quarantine or replace it through the Account-owned audited workflow, recreate from durable authority, and require immutable per-scope repair evidence before reopening that scope.
- Replay markers and `replayAdmissionFence` are separate from ordinary coordination keys. A reset that clears or makes them untrusted must advance the fence, complete the full lifetime-plus-skew replay-domain quarantine, and prove durable replay consumption before replay-marker cleanup/recreation, replacement-token registration, smoke checks, or admission reopening. None of the Account projection gates substitutes for this replay-domain proof.

### Failover vs Cold Start vs Reset

Do not collapse all Redis events into “Redis repopulates from PostgreSQL.” Failover, cold start, and explicit reset have different safety properties:

The Redis-only cold-start/reset flow below is distinct from ADR 0015's PostgreSQL-rewind modes. If PostgreSQL has been rewound while Coordination Redis survives, `scoped_reset_restore` is deferred and quarantined; player-facing recovery must replace or clear Redis and use the environment-wide `cold_start_restore` controller contract instead.

- **Failover** (node crash, leader change, pod restart with intact AOF/PVCs)
  - Coordination Redis retains its AOF/replication history.
  - Keys such as `tick:{tenantRegionTag}:pending` and timers may survive.
  - Tick executors can replay or complete in‑flight ticks using idempotent domain logic and PostgreSQL guards.
  - This is the normal “Redis recovered” path; tail‑loss is bounded by the configured SLO.

Worked example: normal failover for `<tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120, gameInstanceId=9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, regionId=R7>`

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
    - Target state only: once the bounded recovery controller, Account-owned projection repair/replacement workflow, replay quarantine/fence, and immutable evidence path are implemented and end-to-end proven, the future operation is `coordination-maintenance recover --mode reset --scope ... <session-policy-option>`, with exactly one of `--preserve-sessions` or `--invalidate-sessions`. Its internal phases include pause/fencing, epoch bump, Redis clearing, ledger and command convergence, metadata initialization, Account authority/token projection rebuild with verified durable freshness, session-policy application and preserved-session rebind where applicable, smoke verification, and success release. This command is unavailable today and is not a current operator instruction.
    - Current operators must use the [Current Operator Fallback](#current-operator-fallback); the target-state recovery operation is unavailable and an empty keyspace is not safe-resume evidence.
    - Lazy recreation of `tick:{tenantRegionTag}:meta` by hot-path staging may still occur as an implementation detail after the reset completes, but it is not a substitute for the reset handshake and operators must not treat an empty keyspace as “safe to resume automatically”.

Worked example: cold start for `<tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120, gameInstanceId=9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, regionId=R7>` (target-state only; unavailable today)

> **Target-state example only; unavailable today.** The commands in this cold-start example describe the future controller contract. Current operators must keep the affected scope fenced and use the current fail-closed fallback above instead.

1. Coordination Redis starts empty after loss of its data directory, while PostgreSQL still shows `RegionStatus(regionEpoch=13, lastCommittedTickId=41)` for `(7b3b074e-d597-4e9b-b96f-4f5946d26120, 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, R7)`.
2. In the target-state workflow, `coordination-maintenance recover --mode reset --scope region --tenant 7b3b074e-d597-4e9b-b96f-4f5946d26120 --game-instance 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78 --region R7 --preserve-sessions` establishes `PAUSED`, bumps `region_epoch` from `13` to `14`, clears and rebuilds the Redis metadata baseline, converges old-epoch durable work and accepted-but-unbound commands, rebinds still-valid preserved sessions, and proves a fresh tick can stage and clear before success release.
3. In the target-state workflow, `coordination-maintenance continue-recovery --operation-id <operationId> --expected-phase ready_to_reopen --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>` reconciles into `AWAITING_RESUME` and does not release the fence. The future workflow then runs public `coordination-maintenance resume --operation-id <operationId> --expected-phase awaiting_resume --maintenance-lock-token-file <permissioned-token-file> --evidence-ref <evidenceRef>`; the first committable tick in epoch `14` begins only after the recover operation's internal release reaches `finalized`. Its audited failure path is `coordination-maintenance release-lock --operation-id <operationId> --scope region --tenant 7b3b074e-d597-4e9b-b96f-4f5946d26120 --game-instance 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78 --region R7 --maintenance-lock-token-file <permissioned-token-file> --reason <reason> --evidence-ref <evidenceRef>`; none of these commands is available to current operators.

- **Reset** (intentional operational action)
  - A deliberate, scoped choice to discard volatile coordination state (region/tenant/cluster) and resume from PostgreSQL state plus new activity.
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

| Prefix / Family | Role | Reset Policy (Coordination Reset) | Behavior When Dropped | Notes |
| --- | --- | --- | --- | --- |
| `tick:{tenantRegionTag}:pending` and `tick:{tenantRegionTag}:queue:*` | Coordination | **Reset-tolerant** | In-flight ticks and queued commands for affected regions are discarded; future ticks process only new commands. | `pending` effects converge via the tick effect ledger (replay/reconcile to `APPLIED`/`ABANDONED`) and idempotency prevents double-apply. Queued commands that were not yet staged are intentionally **lost**; they are not reconstructed from PostgreSQL. Their accepted command records must still converge to terminal command status (`executionOutcome = LOST_BEFORE_STAGING`) during reset handling. |
| `tick:{tenantRegionTag}:meta` | Coordination | **Reset-tolerant** | Epoch/tick guard metadata is dropped; scripts reinitialize metadata under the region lease and/or reset tooling re-establishes it from durable RegionStatus baselines for the new epoch. | `tick:{tenantRegionTag}:meta` is a monotonic guard and coordination helper only; authoritative baselines for `(region_epoch, tickId)` come from PostgreSQL RegionStatus/ledger plus heartbeats. Reset tooling reinitializes `region_epoch` and `current_tick_id` during the tick reset handshake. |
| `tick:{tenantRegionTag}:session-binding:*` | Coordination | **Reset-tolerant with preserved-session rebind** | Region-local gameplay admission bindings are dropped for affected regions. Preserved sessions are not gameplay-admitted again until the reset workflow satisfies the complete canonical rebind predicate above: exact active and unrevoked token-registry state, current issuer/account authority and identity, current membership generation, revocation state, and expected `binding_generation`. It then recreates binding keys through the session-to-region bridge, or the client completes fresh `LOGIN` / `PLAY`. | These keys are region-authoritative for gameplay command admission, but their source intent lives in the authenticated `session:game:*` record and pre-auth transport context. Reset workflows that preserve sessions must complete the internal preserved-session rebind phase before reopening normal command intake; missing or mismatched authority produces terminal/non-applied `REGION_REBIND_REQUIRED`. |
| `timer:{tenantRegionTag}` and `retry:{tenantRegionTag}` | Coordination | **Reset-tolerant** | Timers and retries for affected regions are discarded; future ticks process only newly scheduled timers/retries. | Only timers/retries that are also represented durably elsewhere (for example, PostgreSQL-backed automation schedules or durable follow-ups) are re-discovered after a reset; region-scoped timer/retry coordination keys themselves are not treated as reconstructible logs. |
| `tick-executor-lease:{tenantRegionTag}` and tick lock keys (`tick:{tenantRegionTag}:lock:*`) | Coordination | **Reset-tolerant** | Existing leases/locks vanish; new executors reacquire leadership and locks as ticks resume. | Leases and locks are transient; executors reacquire leases and lock state after reset. |
| `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` | Coordination | **Reset-sensitive** | Every region- and tenant-scoped reset explicitly records `--preserve-sessions` or `--invalidate-sessions`; the canonical region example preserves sessions. Every cluster-scoped reset explicitly records `--invalidate-sessions` and rejects preservation. | Non-authoritative but player-visible. Preserved region/tenant sessions require explicit rebind validation before admission; invalidated sessions require fresh authentication/play. |
| Pre-auth transport context | Coordination | **Reset-sensitive** | Region- and tenant-scoped resets record `--preserve-sessions` or `--invalidate-sessions` explicitly. Preserved pre-auth transport context is retained only under the former and invalidated under the latter. Cluster scope requires explicit invalidation. | Canonical pre-auth transport context is not region-local gameplay authority; preserved context must still pass authenticated rebind validation before gameplay admission resumes. The current implementation mapping is recorded in Implementation Notes. |
| `session:auth:token:<tokenHash>` | Coordination | **Reset-sensitive by scope** | Region- and tenant-scoped coordination resets preserve issued-token registry records because they are Account-owned and not region-local. A cluster-scoped reset requires explicit `--invalidate-sessions`, first closes protected admission and completes the Account repair/reset cutover, then drops the old records as physical cleanup; missing records deny protected control-plane/admission calls, so callers must re-authenticate and obtain newly registered tokens before reopen. | The exact-token registry is the active/revocation authority for that token: protected use requires the matching registry record and its active, unrevoked state. Durable Account generation records remain the separate authority for issuer, compromise, and reset generations; reset tooling must not infer token scope from key names, recreate a record from a JWT, or delete account-wide tokens as a side effect of a narrower coordination reset. See `system-architecture-jwt-and-token-contracts.md` for full semantics. |
| Account authority-generation projections | Coordination | **Reset-sensitive, fail-closed by scope** | Region- and tenant-scoped coordination resets leave unaffected Account-owned projections outside their key scope but verify every affected scope. A missing or lower Redis projection may be repaired with Account-owned idempotent set-if-greater. A projection greater than Account durable authority is poisoned and must be quarantined or replaced through an Account-owned audited workflow, recreated from durable authority, and accompanied by immutable per-scope repair evidence before protected traffic reopens. A cluster-scoped reset may discard projections only after protected admission is closed and Account advances the issuer authority generation; Account then rebuilds and verifies every affected projection and current issued-token record before reopen. | Account's durable transactional generation is the sole authority. Redis is only a bounded projection/cache and must never become an alternate writer or reset baseline. |
| `gateway:connect-token:jti:<jti>` and `replayAdmissionFence` | Coordination | **Reset-sensitive, fail-closed** | These shared Gateway replay-domain keys are an explicit exception to tenant/region key tagging and are untouched by region- or tenant-scoped resets. After a shared reset drops or makes replay markers untrusted, the reset advances `replayAdmissionFence` and rejects all new first-party gameplay handshakes for at least the maximum gameplay-connect lifetime (`30 seconds`) plus two configured clock-skew intervals. Reopen requires a disposable marker write followed by `WAITAOF` proof of `DURABLE_REPLAY_CONSUME_ACK`; this replay gate is in addition to, and cannot bypass, the Account cutover, issuer-generation projection rebuild/proof, and post-reset smoke ordering. Resetting this replay state alone does not close already admitted WebSockets. A cluster reset closes affected gameplay sockets under its required explicit invalidation policy. | Consumed markers are security-critical and non-evicting. The fence is advanced only after the old marker state is dropped or rejected; quarantine and the durable consume-ack proof must complete before `replayAdmissionFence` reopens admission. Existing admitted WebSockets are unaffected by replay-state reset alone. |
| `remote:{tenantInstanceTag}:<entityId>` | Coordination | **Reset-tolerant** | Cross-region follow-ups rely solely on durable tables; hints may be temporarily missing, increasing latency only. Region-scoped coordination resets leave instance-scoped hints intact; tenant- and cluster-scoped resets enumerate every affected game instance from the durable scope inventory and remove each instance's `remote:{tenantInstanceTag}:*` keys. | The complete key scope is `<tenantId, gameInstanceId>` because durable remote follow-up identity is instance-aware. These are best-effort cross-region wake-up hints only; dropping them affects latency, not correctness. Hint keys are TTL-bounded (default `remote_hint_ttl_ms = 60_000`) so stale hints age out automatically. |
| `ratelimit:<tenantId>:*` (and optional `:<shard>`) | Cache/Rate-Limit | **Reset-tolerant** | Rate-limit counters reset; future requests rebuild bucket state from zero. | Token buckets are best-effort; resets clear buckets and counters but do not affect authoritative state. Temporary post-reset bursts are acceptable as long as gateway policies still enforce global abuse limits. |
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

## Reset vs Accept Loss

When coordination state appears incorrect or unhealthy, first-implementation operators choose between two supported strategies. Think of this as the **minimal decision tree** for a single‑admin operator:

1. **Can you safely accept the loss?**
   - Choose **Accept loss** when:
     - Metrics show tail‑loss stayed within the documented SLO window, and
     - Invariants (no double‑apply of critical effects, no cross‑tenant leaks, no broken financial flows) remain intact.
   - Behavior:
     - Acknowledge that some coordination state (timers, pending effects, non‑critical queues) has been lost within the tail‑loss envelope and **do nothing** beyond monitoring.
   - Examples:
     - Short Redis outage where `tail_loss_ms` and tick metrics confirm only the last 1–2 seconds of activity were affected.
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
     - Performed through the versioned coordination maintenance CLI.
     - Always accompanied by post‑reset health checks (ticks can be scheduled, sessions can be created/resumed, automation works).
     - Region‑ and tenant‑scoped resets should prefer **smaller scopes first**; cluster‑scoped reset is reserved for catastrophic or planned migration scenarios where finer scopes are ineffective.

General in-place repair of coordination keys is intentionally **not** a first-implementation operator path. A future repair path may be added only by defining named maintenance CLI verbs with scope rules, fencing/quiescence requirements, audit output, and mandatory post-repair verification. Until that exists, any direct mutation of coordination prefixes is break-glass activity and must be followed by a scoped reset or documented cleanup flow that covers the mutated prefix before normal processing resumes.

Design reviews should explicitly state which of these strategies is expected to be safe for each coordination structure.

---

## Common Reset Scenarios

This section outlines representative scenarios and recommended reset scopes. Detailed step‑by‑step flows live in `system-architecture-redis-operations.md`.

All reset actions in these scenarios are target-state guidance for the future durable recovery controller. Until that controller and its supported scopes are implemented and proved, current operators must use the [Current Operator Fallback](#current-operator-fallback) and must not execute the referenced reset, continuation, release-lock, migration, or destructive storage steps.

### Mis-keyed Tick Data for a Single Region

Symptoms:

- Tick processing for one region stalls or repeatedly fails.
- Pending and retry queues show malformed or unexpected entries.

Recommended actions:

- Execute the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) at region scope with an explicit session policy; this example chooses `--preserve-sessions`.
- Apply the explicitly recorded region reset session policy:
  - Leave sessions and other non-region-scoped keys intact unless a broader documented workflow is explicitly chosen.
  - Recreate region-local gameplay bindings for preserved sessions through the rebind step before normal command intake resumes.

Expected impact:

- Players in that region may see some actions dropped or replayed within the tail‑loss envelope.
- No permanent loss of authoritative game data in PostgreSQL.

### Buggy Coordination Script Affecting Multiple Regions for One Tenant

Symptoms:

- Multiple regions for a tenant show inconsistent pending/retry structures.
- Metrics indicate repeated script failures or unexpected error codes.

Recommended actions:

- Roll out a fixed script version.
- Execute the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) at tenant scope.
- Record exactly one tenant session policy, `--preserve-sessions` or `--invalidate-sessions`, when executing the affected tenant reset; never infer it from tenant scope.

Expected impact:

- In‑progress actions for that tenant may be dropped/replayed within the tail‑loss envelope.
- Long‑lived domain state remains safe; scripts and tick processing resume in a clean coordination environment.

### Manual Break-Glass Edits to Coordination Keys

Symptoms:

- An operator used `redis-cli` or a raw script to mutate `tick:*`, `timer:*`, `retry:*`, `remote:*`, `session:game:*`, `session:auth:*`, or `tick-executor-lease:*`.

Recommended actions:

- Treat the affected scope as “coordination state may be inconsistent”.
- Select the smallest valid domain-specific recovery workflow for every mutated prefix rather than assuming a region or tenant reset can repair all keys. Region-local coordination keys use the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) with an explicit session-policy choice. Account-owned `session:auth:*` edits require Account-owned projection repair or cluster reset, and replay-marker edits require replay quarantine, fencing, and durable consume acknowledgement. Because those target workflows are not yet shipped end to end, current operators must preserve evidence, keep all affected scopes fenced, and use the [Current Operator Fallback](#current-operator-fallback) instead of attempting a reset.
- Record the incident using the standard audit fields (who, when, why, which prefixes/tenants/regions).

Expected impact:

- Coordination state is rebuilt from domain data; the risk from manual edits is removed.

### Full Cluster Rebuild or Migration

Symptoms:

- Coordination Redis must be replaced or re‑sharded in a way that invalidates existing keys.

Recommended actions:

- Plan a **cluster‑scoped reset** as part of a controlled maintenance window.
- Execute the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) at cluster scope with an explicit session policy; this example chooses `--invalidate-sessions`. The recover operation performs the storage-level wipe only after `scope_paused_and_locked`, the protected Account cutover, replay-fence advance, full lifetime-plus-skew replay quarantine, durable replay-consume proof, and immutable pre-wipe external authorization/fencing evidence are complete for the same operation and maintenance lock. After replacement startup, it must record and validate separate post-reset replacement verification before continuation or reopen; the replacement facts are not pre-wipe evidence and are not part of the earlier epoch-bump or scope-safe coordination-reset phase.
- Communicate expected impact to tenants and players.

Expected impact:

- All coordination state is reset; ticks restart from a clean slate. For this cluster-scoped action, the required explicit `--invalidate-sessions` policy closes protected admission, completes the required Account issuer-generation cutover, replay-fence advance, full lifetime-plus-skew quarantine, durable replay-consume proof, and immutable pre-wipe external authorization/fencing proof before the wipe. It then requires post-reset replacement verification for endpoint, ACL/configuration, empty keyspace, and health before the controller permits continuation or public `resume`; it drops issued-token records as cleanup, denies missing-record token use, and keeps gameplay sessions invalid until re-registration completes. Cluster scope rejects `--preserve-sessions`. By contrast, a tenant-scoped reset preserves Account-owned `session:auth:token:*` records while its `session:game:*` and pre-auth transport-context policy is determined only by the explicitly recorded `--preserve-sessions` or `--invalidate-sessions` flag.
- Domain data (PostgreSQL) remains authoritative.

---

## Interaction with Tail-Loss and Replay

Coordination resets interact with tail‑loss and replay in predictable ways:

- A **reset** is effectively a deliberate, large tail‑loss event for the chosen scope:
  - Instead of losing up to `tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)` of state, the system discards **all** coordination state for that scope.
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

- Normal tail‑loss and replay.
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
  - Tail‑loss SLO observability (described in `system-architecture-redis-operations.md`) surfaces when loss windows exceed acceptable bounds.
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
- `system-architecture-redis-design-checklist.md` – checklist for assessing reset‑tolerance and tail‑loss compatibility.
- `system-architecture-redis-lua-patterns.md` – Lua script requirements for idempotency and replay safety.
- `system-architecture-redis-ops-access.md` – ACL and tooling expectations for operators.

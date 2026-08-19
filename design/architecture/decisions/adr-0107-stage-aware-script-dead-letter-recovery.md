# ADR 0107: Stage-Aware Script Dead-Letter Recovery

## Status

Accepted

## Implementation Status

Stage-aware dead-letter recovery with frozen-input retry and stored-dispatch continuation is target state. The current `ReplayDeadLetteredWorkItems` path requeues eligible rows as `PENDING_EVALUATION`, returns aggregate counts, and does not prove stage-specific recovery, same-epoch fencing, stored-output continuation, or idempotent per-request outcomes.

## Canonical Design

- [Scripting cross-service contracts](../system-architecture-scripting-contracts.md)
- [Scripting control-plane API](../system-architecture-scripting-control-plane-api.md)
- [Scripting control-plane operations](../system-architecture-scripting-control-plane-operations.md)
- [Scripting runtime execution](../system-architecture-scripting-runtime-execution.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-07`
- Primary capability: `AS-1.5` durable script execution and recovery
- Affected capabilities: `PO-1.4`, `AR-3.3`, `SF-2.3`, `GR-1.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of dead-letter re-evaluation, stored-output replay, version and runtime fencing, operator selection, purge, and audit behavior
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `SCRIPT-07`

## Context

Script work can dead-letter before DSL evaluation completes or after evaluated output and child dispatches already exist. Treating both cases as one transition back to `PENDING_EVALUATION` can re-evaluate an original trigger after runtime state has changed, regenerate output after some children were accepted, or execute work under a later pin epoch that happens to name the same patch.

The current implementation performs that generic transition. Recovery needs to preserve exact admitted identity and resume from the last durable stage rather than interpreting every dead letter as a new evaluation opportunity.

## Decision

Dead-letter recovery is stage-aware. Every recoverable dead letter records an immutable failure stage and the durable evidence required by that stage. A row with missing, contradictory, or unreadable stage evidence remains dead-lettered.

Automation owns a monotonic `failureGeneration` for each durable parent work item, keyed by `(tenantId, outboxWorkItemId)`. The parent retains its `EVALUATED_COMMITTED` descriptor marker and complete output/child ledger as recovery evidence, while a separate parent recovery aggregate records whether that evidence is currently dead-lettered. The first atomic transition of that parent recovery aggregate into `DEAD_LETTERED` initializes `failureGeneration=1`; every later transition into `DEAD_LETTERED`, including a permanent required-child failure after a successful post-evaluation recovery, advances it exactly once in the same parent-status transition. An exact retry or no-op of that transition does not advance it. Dead-letter evidence, each recovery request's per-item result, recovery claim/attempt identity and committed evidence, terminal attempt results, successful-recovery evidence, and any `already_recovered` result are immutable records bound to `(tenantId, outboxWorkItemId, failureGeneration)`. Lease/lifecycle status and owner-fence fields may advance only through the defined atomic expiry/reclaim compare-and-set, which reuses the same attempt identity. Selectors remain `workItemId`-based; the recovery mutation resolves the current generation atomically with the current parent state. A new request may recover the current generation when it is eligible even if an older generation succeeded; older-generation evidence remains immutable and cannot satisfy or block the current generation. One active claim is permitted per current generation, and stale owners or generations fail the existing compare-and-set fence. `failureGeneration` is not `rowVersion` or `admissionEpoch`, and recovery does not create a new Trigger Identity or Command-Handoff Identity.

An evaluation-stage retry for recoverable instance-bound gameplay/runtime work may run DSL evaluation again only from the original persisted trigger identity and payload, a frozen dependency/manifest snapshot, and the exact immutable compiled graph admitted for that work. It retains the same work-item and `scriptEventId` identity; before a descriptor commit it allocates no `automationDispatchId` or command-child identity. If the evaluation emits commands, the first atomic durable evaluated-descriptor/outbox commit allocates and persists the opaque `automationDispatchId` and deterministic command ordinals; retries after that winning commit reuse those identities. A valid zero-command evaluation creates no dispatch identity. It must not resolve the latest graph, refresh semantic inputs from mutable defaults, or substitute a current read for unavailable frozen evidence or mint a new logical trigger.

Post-evaluation recovery does not invoke the DSL evaluator. It resumes the stored evaluated-output and child-dispatch ledger, preserving each child’s complete Command-Handoff Identity: required source runtime scope, optional distinct target runtime scope, persisted `automationDispatchId`, and deterministic `commandOrdinal` from canonical emitted-command order. The parent Trigger Identity, `outboxWorkItemId`, and `scriptEventId` remain correlation-only; plugin `bindingId` remains diagnostic/correlation metadata and is not a child-selection identity. Payload digest and prior acknowledgement are also preserved. Already accepted or terminal children no-op. Only `PENDING` and `INDEXED` children are eligible for direct dispatch. An existing `HANDOFF_IN_FLIGHT` child is fenced and reconciled against the durable downstream outcome using the complete Command-Handoff Identity; it is never blindly redispatched and remains active/unresolved when that outcome is ambiguous. A permanent required-child failure atomically moves the parent recovery aggregate to `DEAD_LETTERED` while retaining the `EVALUATED_COMMITTED` descriptor marker and ledger, making the parent work-item selector eligible for `resumed_dispatch` recovery under the current `failureGeneration`. A dead letter without a complete, internally consistent output ledger, including that child identity, cannot use this path and remains dead-lettered.

`ReplayDeadLetteredWorkItems` does not re-enter tenant-readiness `onLoad`. A stale readiness execution is fenced and terminalized under the separate Automation readiness owner; after that terminalization, retry requires republishing a new immutable `scriptPatchVersion`, not recovering the stale readiness identity.

For recoverable instance-bound gameplay/runtime work, both paths require an exact current match for the admitted `scriptPatchVersion`, `scriptPinEpoch`, the exact `(pluginId, pluginVersionId, bindingId)` tuple when applicable, the captured `pluginActivationGeneration` when applicable, plus fresh binding and lifecycle evidence, runtime region and `regionEpoch`, and admitted routing bundle including playable-state scope, world/realm identity, and pointer version. For plugin work, the captured generation must be revalidated against the current Automation-owned generation immediately before evaluation and immediately before each post-evaluation dispatch; applicable current plugin activation/lifecycle and component-policy, capability-grant, and signer evidence must also be fresh and valid. `DRAINING` blocks new trigger admission but does not invalidate already-admitted work, so it remains eligible for recovery when these exact fences and policy checks pass; `DISABLED`, revocation, or policy-driven disablement invalidates that work and uses the applicable disabled/lifecycle rejection. Matching patch text under a different pin epoch is ineligible. Unavailable, stale, revoked, or mismatched authoritative fence or plugin-policy state fails closed rather than allowing recovery from a projection guess.

The initial operator mutation accepts bounded explicit dead-letter work-item IDs only and returns one exact per-row `outcome`: `retried_evaluation`, `resumed_dispatch`, `already_recovered`, `recovery_failed`, or `rejected`. Only `outcome=rejected` carries a bounded `rejectionReason`, such as `not_found_or_not_owned`, `stage_evidence_unavailable`, `work_item_not_dead_lettered`, or a specific fence mismatch. When a recovery claim/attempt was created but reached terminal `FAILED`, the result is `outcome=recovery_failed` and must carry a bounded `failureReason`; `rejectionReason` is absent. `failureReason` reuses the established stage-aware failure-reason vocabulary appropriate to the failed recovery stage rather than introducing a parallel taxonomy. `replayedCount` counts only rows whose stored outcome is `retried_evaluation` or `resumed_dispatch`; `already_recovered`, `recovery_failed`, and `rejected` do not contribute. An exact retry returns the stored results, reasons, and derived count without creating another attempt. A new request may create another attempt only after that attempt is terminal `FAILED` and fresh eligibility and fence checks pass. Eligible rows may progress independently, but an ineligible row remains `DEAD_LETTERED`; counts are not a substitute for per-row results.

Recovery requests carry a stable `controlPlaneRequestId` bound to the canonical normalized request digest, actor, and reason. The service persists the request outcome and audit evidence so duplicate requests return the same result without repeating evaluation or dispatch; reusing that request identity with a different normalized digest returns an idempotency conflict before evaluation or dispatch.

Purge is a separate bounded, authorized, and audited operation with its own `controlPlaneRequestId` bound to the canonical normalized request digest, actor, reason, and per-row outcomes. Reusing that request identity with a different normalized digest returns an idempotency conflict and performs no purge. Purge never masquerades as successful recovery. Operators and automation do not repair, requeue, rewrite, or delete dead-letter rows through direct SQL.

## Consequences

- Partial post-evaluation failure cannot duplicate already accepted child effects by regenerating the parent output.
- Evaluation-stage failures remain recoverable when the exact graph, frozen inputs, and original identity are available.
- A repin to the same patch creates a new epoch and intentionally leaves old-epoch dead letters ineligible.
- Work-item, manifest, evaluated-output, child-ledger, fence, request-result, and audit evidence require explicit retention and integrity controls.
- More rows can remain dead-lettered when exact evidence is unavailable; operators must purge them explicitly or repair the missing authoritative evidence rather than weaken fences.
- Explicit IDs and per-row results make initial recovery deliberate and auditable but are less convenient for large bulk remediation.

## Alternatives Considered

### Requeue Every Eligible Dead Letter to DSL Evaluation

Rejected because it conflates pre-evaluation retry with post-evaluation replay. It can regenerate output after partial handoff, read different state, and duplicate or alter effects under the original work identity.

### Never Re-evaluate Any Dead Letter

Rejected because evaluation-stage failures have no completed output ledger to resume. Requiring precomputed output for every admitted trigger would move evaluation ahead of the durable work boundary and would not recover failures that occur during evaluation itself.

### Re-fire the Original Trigger as New Work

Rejected because a new trigger identity loses idempotent continuity and can legitimately observe different state, budgets, bindings, or handler output. That is a new business action, not recovery of the dead letter.

### Repair or Requeue Rows Directly in SQL

Rejected because it bypasses ownership, version and runtime fences, request idempotency, per-child acknowledgement, authorization, and audit evidence.

## Implementation and Proof Obligations

For recoverable instance-bound gameplay/runtime work, persist the failure stage; the current `failureGeneration`; original Trigger Identity and payload digest; frozen manifest/input references; exact graph identity and digest; patch and pin epoch; the complete plugin recovery tuple `(pluginId, pluginVersionId, bindingId)` and captured `pluginActivationGeneration` when applicable; applicable plugin activation/lifecycle, component-policy, capability-grant, and signer-policy evidence with its integrity and freshness metadata; region identity/epoch; routing bundle; evaluated-output digest; deterministic child identities and payloads; child acknowledgement state; and recovery/purge request-result ledgers with each `controlPlaneRequestId` durably bound to its canonical normalized request digest. Retention must keep this evidence coherent for the advertised recovery window. The atomic parent recovery-aggregate transition that enters `DEAD_LETTERED` must initialize or advance `failureGeneration` exactly once, including when a permanent required-child failure follows an `EVALUATED_COMMITTED` evaluation; the descriptor marker and output/child ledger remain retained for post-evaluation recovery. Later recovery records retain that generation rather than deriving it from `rowVersion` or `admissionEpoch`.

Proof for recoverable instance-bound gameplay/runtime work must cover evaluation retry with the exact frozen graph and inputs; refusal when either is missing or changed; post-evaluation recovery without invoking the evaluator; partial child acceptance and replay of unfinished children only; duplicate and concurrent recovery requests; changed-digest recovery and purge requests returning conflict without evaluation, dispatch, or purge; lost responses after commit; explicit-ID batch bounds; tenant ownership; per-row outcomes; and separate audited purge. It must also prove one-time initialization/advancement of `failureGeneration` in the same parent recovery-aggregate `DEAD_LETTERED` transition, including a permanent required-child failure after the `EVALUATED_COMMITTED` marker is retained; no advancement for exact transition retries/no-ops or failed recovery attempts that leave the parent in the same generation, a later increment after the parent leaves and re-enters `DEAD_LETTERED`, current-generation-only `already_recovered`, terminal `recovery_failed` with its bounded `failureReason`, exact retries returning that stored failure without a new attempt, immutable older-generation evidence, one active claim per current generation, and stale-generation/owner compare-and-set rejection. Plugin recovery proof must verify the persisted activation/lifecycle, captured `pluginActivationGeneration`, component-policy, capability-grant, and signer-policy evidence, including integrity and freshness metadata, before any evaluation and again before each child dispatch, while allowing `DRAINING` for already-admitted work and rejecting only lifecycle states that invalidate it. Readiness-owner proof separately covers stale `ONLOAD_RUNNING` terminalization, stale-identity replay rejection, republishing a new immutable `scriptPatchVersion` before retry, and no fabricated `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `entityId`, or `scriptPinEpoch` fields.

Fence proof for recoverable instance-bound gameplay/runtime work must cover changed patch, same patch with a new `scriptPinEpoch`, changed plugin binding, lifecycle, or captured `pluginActivationGeneration`, disabled or revoked plugin version/binding, component-policy, capability-grant, or signer-policy mismatches, changed region or `regionEpoch`, changed playable-state scope or routing pointer, and unavailable/stale authoritative fence reads. Every mismatch must leave the row dead-lettered without evaluation or dispatch.

The current implementation and focused proof do not satisfy this decision. `ReplayDeadLetteredWorkItems` currently requeues eligible rows as `PENDING_EVALUATION`, returns aggregate counts, and does not prove stage-specific recovery, same-epoch fencing, stored-output continuation, or idempotent per-request outcomes. This ADR records the target contract and does not claim those gaps are closed.

## Reversibility and Revisit Triggers

Batch limits, evidence-retention windows, outcome vocabulary, and operator UX may evolve while preserving stage-aware recovery and exact fences. Revisit filter-based bulk selection only after a dry-run preview and stable per-row result contract prove that bulk recovery remains bounded and auditable. Revisit epoch equivalence only if a separate accepted contract can prove two pin epochs are semantically and operationally interchangeable; matching patch names alone is insufficient.

# ADR 0103: Epoch-Fenced Script Rollback Without Routine Gameplay Pause

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-06`
- Primary capability: `AR-3.3` immutable runtime version selection
- Affected capabilities: `AS-1.6`, `GR-1.4`, `SF-2.3`, `PO-1.4`, `AA-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of rollback cutover, Automation and gameplay pause boundaries, epoch fencing, in-flight work, cleanup, timeout behavior, and player-visible availability

## Context

The previous rollback workflow pauses both Automation admission and all gameplay ticks for an instance, repins the script patch, cancels and purges displaced work, waits for cross-service convergence and drain, and only then resumes gameplay. This conservatively prevents mixed-version effects, but it freezes unrelated player commands for the duration of Automation loading, schedule reconciliation, cleanup, and control-plane recovery. The current pause path also advances the broader region epoch, invalidating more than script-originated work.

ADR 0100 establishes a narrower authority: Game Session owns an exact per-instance `(scriptPatchVersion, scriptPinEpoch)`, and every script-derived unit of work can be fenced by that tuple. Rollback should use that script-specific boundary rather than making a routine script change a whole-instance gameplay outage.

## Decision

Script rollback is an explicit repin to a previously published, tenant-`READY`, base-compatible immutable patch. Before changing authority, the rollback workflow asks Automation & Scripting to prepare or preload the exact target artifact while the current Game Session pin remains active. Candidate preparation admits no target-version gameplay work. Failure at this stage leaves the current pin and epoch unchanged.

Automation admission is then paused for the affected `(tenantId, gameInstanceId)` scope. The pause stops new external and scheduler triggers while already-admitted work reaches its exact-version fence and while schedules, timers, and derived runtime state are reconciled. Automation pause state and drain counters are workflow details; they do not become script-selection authority and do not replace `scriptPinEpoch` as the correctness fence.

At a safe serialized authority boundary, Game Session atomically replaces the pinned `scriptPatchVersion` and advances `scriptPinEpoch`. The commit is idempotent by `controlPlaneRequestId`; retry returns the same version and epoch. Concurrent pin mutations use compare-and-set or equivalent storage fencing so only one exact tuple becomes authoritative.

Every gameplay/runtime trigger, durable script work item, schedule or timer firing, emitted command, remote follow-up, Game Session command, staged tick effect, and retry carries the exact `scriptPatchVersion` and `scriptPinEpoch` admitted for it. Each new side-effecting apply at a persistence, handoff, enqueue, staging, replay, or final effect-application boundary rejects a displaced epoch. A sealed historical idempotent retry or reconciliation under the original tuple may only read and confirm the already durable result; it must not mint new work, reapply an effect, or create another gameplay effect. Work already evaluating may finish computation against its captured immutable graph, but it cannot create a gameplay effect after its epoch is displaced. Effects committed before the repin remain valid history and are not compensated by rollback.

Ordinary gameplay ticks and player-command admission continue through the script rollback. They do not wait for Automation convergence, cleanup, or rollback workflow completion. After Automation observes the committed tuple, loads that exact graph, reconciles schedules and timers, and proves its scoped admission state is aligned, it resumes new script admission under the new epoch.

Cancellation of old Automation work and purge of stale queue entries run as bounded, idempotent asynchronous cleanup. They control resource growth and improve operator clarity but are not the correctness barrier; the exact epoch fence is. Rollback completion reports cleanup progress and any retained terminal evidence without delaying unrelated player gameplay.

If Automation cannot load or reconcile the committed rollback target before the bounded timeout, Automation remains fail-closed for that instance and exposes the failure for repair or another explicit repin. Game Session does not silently restore an older graph, and gameplay ticks are not left frozen merely because scripting convergence timed out. Script timers or NPC triggers missed during the Automation pause are dropped under their declared delivery rules unless a schedule explicitly defines bounded catch-up.

A full tick pause is an exceptional rollback mode, not the routine path. It is required only for a declared effect family, migration, or compatibility transition that cannot enforce the final `scriptPinEpoch` check before mutation. The workflow must identify that unfenced boundary, pause the smallest complete gameplay scope, prove active tick/effect quiescence, perform the transition, and resume explicitly. An implementation may not use routine full-instance pause as a substitute for adding the required epoch field and fence.

## Consequences

- Players can continue ordinary gameplay while script rollback prepares, converges, and cleans up.
- Script behavior can be temporarily absent: NPC actions, timers, and other triggers may be rejected or dropped during the Automation pause, and rollback does not promise a quiet snapshot or general backfill.
- A cutover has one precise boundary: effects committed before it remain, while displaced-epoch effects not yet committed are rejected.
- Every script-derived durable and remote path must retain and enforce the exact pin epoch through final gameplay mutation.
- Cancellation and purge no longer extend the player-visible outage, but cleanup lag requires bounded workers, retention, metrics, and alerts.
- Unfenced effect families make rollback more expensive because they require an explicitly justified exceptional gameplay pause until they are migrated.

## Alternatives Considered

### Pause Gameplay and Automation Until Full Drain and Cleanup

This provides a quiet operational window and is the safest fallback when effects lack a final epoch fence. It is rejected as the routine path because a script-only rollback would freeze unrelated player activity while multiple services load, reconcile, scan, purge, and recover. It remains the explicit exceptional mode for unfenced effects and migrations.

### Repin Without Pausing Automation

Game Session could advance the epoch and rely entirely on downstream fences while Automation continues admitting work. This can be correct with complete fencing, but it wastes evaluation and queue capacity during graph and schedule reconciliation and makes trigger outcomes harder to explain. A scoped Automation pause provides bounded backpressure without stopping gameplay.

### Make Cancel and Purge Complete Before Repin or Gameplay Resume

Rejected because complete epoch fencing makes stale work non-applicable. Synchronous cleanup adds latency and failure coupling without improving mutation correctness; cleanup remains required operational work but can converge asynchronously.

### Fall Back to Automation's Previously Loaded Graph

Rejected because it would execute behavior different from the authoritative Game Session pin and make rollback state depend on worker-local cache contents. Recovery is repair or another explicit repin.

## Implementation and Proof Obligations

Persist the authoritative Game Session pin and monotonic `scriptPinEpoch`, enforce idempotent compare-and-set mutation, and propagate the exact tuple through all trigger, work-item, schedule, timer, command, remote, tick-effect, retry, replay, and audit contracts. Every gameplay-affecting handler must check the epoch at its final mutation boundary or declare why it requires exceptional tick pause. Automation pause, candidate preparation, exact-artifact reconciliation, timeout, asynchronous cleanup, and operator readback must be durable and scoped to the same instance.

Proof must cover preparation failure under the old pin; concurrent and repeated rollback requests; triggers admitted immediately before, during, and after repin; evaluation that finishes after displacement; commands already handed off, queued, staged, drained, retried, or routed remotely; final effect rejection under the old epoch; ordinary player commands and ticks continuing throughout; Automation restart and convergence timeout without gameplay freeze; explicit repair or second repin; schedule reconciliation and declared missed-trigger behavior; asynchronous cancel/purge retry and backlog bounds; and the exceptional full-pause path with proven tick/effect quiescence.

Current implementation has an Automation admission pause, a separate Automation `admissionEpoch`, scoped drain reads, late-handoff cancellation, Game Session tick pause, region-epoch fencing, and queue purge seams. It does not yet provide the authoritative `scriptPinEpoch`, carry it through every durable contract, enforce it at final effect application, validate and atomically commit the complete rollback target, or implement the durable rollback workflow and timeout behavior described here. This decision records the target contract and does not claim those gaps are closed.

## Reversibility and Revisit Triggers

Preparation, reconciliation, cleanup scheduling, timeout values, and missed-trigger policy may evolve while preserving exact epoch fencing and uninterrupted ordinary gameplay. Revisit routine tick-pause policy if measured evidence shows that final effect fencing cannot provide an intelligible cutover, cleanup backlog threatens runtime stability, or product requirements demand a quiet snapshot with no dropped script triggers. Any broader pause must state its player-visible cost and the specific invariant that the script epoch alone cannot protect.

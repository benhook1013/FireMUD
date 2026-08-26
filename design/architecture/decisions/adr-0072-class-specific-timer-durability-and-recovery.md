# ADR 0072: Class-Specific Timer Durability and Recovery

## Status

Accepted

## Implementation Status

`AS-1.4` remains partial. Durable schedule definitions, instance materialization, runtime-progress observation, due processing, and bounded catch-up are live, while authored clock/recovery-class declarations, durable resume-window identity, one-coalesced-firing enforcement across repeated observations, and Redis-loss reconstruction are not yet proven end to end. See the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

## Canonical Design

- [Scripting scheduler and timers: target-state timer design](../system-architecture-scripting-scheduler-and-timers.md#script-timers-vs-tick-timers)
- [Scripting normative tables: timer semantics matrix](../system-architecture-scripting-normative-contract-tables.md#table-3-timer-semantics-matrix)
- [Scripting runtime execution: timer failure semantics](../system-architecture-scripting-runtime-execution.md#timer-failure-semantics)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-07`
- Primary capability: `AS-1.4` scheduled automation and timer recovery
- Affected capabilities: `GR-1.2`, `GR-2.3`, `AS-1.5`, `SF-1.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of clock units, durability classes, resume mathematics, and replay-all and universal-skip alternatives
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `TICK-07`

## Context

Timers do not all carry the same product promise. A correctness-bearing expiry cannot silently disappear, while replaying every missed periodic firing after downtime can create an unsafe burst of damage, healing, automation, or external work. Treating every timer as one class also obscures whether its cadence follows real time or the game tick timeline.

Redis timer indexes are disposable coordination projections and cannot be the only record of a timer whose execution or explicit non-execution matters.

## Decision

Every authored timer declares both its clock unit and its recovery class.

Clock units are:

- **Wall clock** – eligibility uses a persisted absolute time such as `due_ms` or `nextDueAt`. Changing tick cadence does not rescale an already scheduled deadline.
- **Tick/game time** – eligibility and cadence use committed tick identifiers. Changing tick cadence changes the real-world duration of future tick intervals while preserving their game-time cadence.

Time scaling is applied explicitly when a new duration or due point is calculated. A later cadence or time-scale change does not silently rewrite an already persisted due point.

Recovery classes are:

1. **Correctness-bearing one-shot** – intent is durable outside Redis before acknowledgement; physical execution may be at least once and replay-safe, while recovery converges to one logical terminal outcome. Retry preserves its logical identity and must not duplicate the logical effect. A runtime-scoped one-shot that crosses a region scope or epoch fence remains under its original identity for authority-fenced reconciliation; it is never rebound or ordinarily re-driven on the new timeline, and any authorized fresh identity requires conclusive old-lineage terminalization, current-scope revalidation, and durable lineage under [ADR 0067](./adr-0067-abandon-old-epoch-work-and-reschedule-with-new-lineage.md).
2. **Durable recurring** – the authored schedule declares exactly one missed-occurrence policy:
   - `SKIP_MISSED` advances to the next valid future occurrence without executing missed occurrences; or
   - `COALESCE_ONE` may create at most one synthetic firing for the complete stable schedule-instance identity in one durable resume-window identity.
3. **Advisory or cosmetic** – missed occurrences may drop, and the timer resumes with a future occurrence under its declared cadence.

Redis timer keys, due indexes, leader checkpoints, and wake-up hints are rebuildable and disposable for every class. Durable timer identity, due state, recovery policy, resume-window identity, and terminal or skipped outcomes live outside Redis whenever the class requires recovery or audit.

For target-state `COALESCE_ONE` recovery, Automation owns one durable timer-recovery resume-window record per runtime scope, epoch, and `isDryRun` mode. Its `resumeWindowId` is the exact tuple `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, isDryRun, resumeGeneration>`. A durable compare-and-set creates or returns the one `OPEN` window for that scope, epoch, and mode; leader, worker, takeover, and attempt IDs never participate in the identity. The record freezes the recovery start and through observations and retains the mode as immutable identity evidence. Same-window retries and takeovers reuse the record and its mode-specific `resumeWindowId`; each `CATCH_UP` firing claim and candidate audit includes that ID in uniqueness and comparison, and the tenant-local catch-up cap applies within that one-tenant, one-mode window. The window becomes `COMPLETE` only after the selected candidates' firing or shared-admission-denial outcomes and all cap-excluded candidate outcomes are durable. Only a later recovery episode after completion allocates the next `resumeGeneration`, producing a distinct mode-specific `resumeWindowId`.

A scope-level durable atomic epoch transition must move each prior epoch's `OPEN` resume window, independently for each `isDryRun` mode, out of `OPEN` before any corresponding new-epoch `OPEN` window can be created. Claims from the prior window remain fenced to, and are reconciled under, their original `regionEpoch`, `isDryRun`, and resume-window identity; they cannot be admitted through the new window. The transition follows the existing evidence-based completion and terminalization rules and does not invent a new terminal status or recovery policy.

`COALESCE_ONE` never replays a burst of missed cadence boundaries. One configurable tenant-local cap bounds synthetic firings across complete stable schedule-instance identities within one mode-specific resume window, which is already scoped to one tenant and one `isDryRun` mode. Selection is deterministic and fair across those identities, including target-scope and plugin-binding dimensions. Candidates excluded by the cap are not deferred into an unbounded backlog; their skipped outcomes and reasons are audited. Candidates selected within the tenant-local cap still enter the shared scheduler/cluster admission layer, where [ADR 0166](./adr-0166-attributable-script-breakers-and-tenant-first-fairness.md) tenant-first fairness precedes priority; catch-up cannot bypass per-tenant or cluster ceilings. A selected catch-up candidate denied there by a tenant or cluster capacity ceiling is terminally skipped for that due candidate and mode-specific `resumeWindowId`: it consumes its deterministic selection slot, records the applicable bounded capacity-denial reason in the candidate audit, creates no firing claim or `scriptEventId`, and is not deferred, retried, backfilled, or reminted in that or a later mode-specific resume window. This terminal skip does not suppress the schedule's next ordinary future occurrence.

If a feature needs elapsed downtime to affect gameplay, it computes one deterministic feature effect from the elapsed interval under its own bounded rules. It does not model that aggregation as replay of every missed firing.

For a schedule preserved across reload or rollback, the next due tick is calculated from `previousDueTick`, `resumeTick`, and positive `intervalTicks`:

```text
if previousDueTick >= resumeTick:
    nextDueTick = previousDueTick
else:
    remainder = (resumeTick - previousDueTick) % intervalTicks
    nextDueTick = resumeTick if remainder == 0
                  else resumeTick + intervalTicks - remainder
```

During a version transition, due state may be carried forward only when `scheduleDefinitionId`, resolved `playableStateScope`, and `scheduleSemanticsHash` match. The replacement version-owned schedule row must be created or confirmed before the prior row is retired, as one atomic durable reconciliation or a resumable idempotent operation keyed by `controlPlaneRequestId`.

This preserved-schedule calculation maintains cadence and permits an occurrence exactly on the resume boundary. Reload or rollback preservation is distinct from downtime catch-up: preservation chooses the next valid due point for the retained schedule, while downtime recovery separately applies the declared `SKIP_MISSED` or `COALESCE_ONE` policy under a durable resume-window identity.

## Consequences

- Correctness-bearing one-shot timers cannot vanish with Redis or silently duplicate on recovery.
- Periodic work resumes without a post-outage burst, while designers can choose explicit skip or one-coalesced-effect semantics.
- Players can distinguish real-time deadlines from tick/game-time cadence; tick-cadence changes intentionally affect only the latter's real-world pace.
- Durable schedules require policy, identity, audit, and terminal-state storage outside Redis.
- Fair tenant-local capped catch-up bounds recovery load within each tenant/mode resume window, while admitted work remains subject to shared scheduler/cluster fairness and ceilings.
- Features needing elapsed-time accumulation must implement and prove one deterministic aggregate effect.
- Existing timers must be classified and any ambiguous clock or recovery behavior removed.

## Alternatives Considered

### Replay Every Missed Occurrence

Rejected because long downtime or overload could create an unbounded recovery burst, starve live ticks, and apply old periodic effects after their gameplay context changed.

### Universally Skip Missed Occurrences

Rejected because correctness-bearing one-shots would disappear and recurring features that deliberately promise one bounded recovery effect could not meet that contract.

## Implementation and Proof Obligations

Proof must cover mandatory clock-unit and recovery-class declarations; wall-clock stability across tick-cadence changes; tick/game-time cadence under changed real-world tick duration; explicit time-scale behavior; durable one-shot reconstruction with at-least-once replay-safe physical execution, one logical terminal outcome, and no duplicate logical effect; old-scope/epoch one-shot reconciliation without rebinding or unproven new lineage; duplicate recovery; `SKIP_MISSED`; one `COALESCE_ONE` firing per complete stable schedule-instance identity and mode-specific durable resume-window identity; the tenant-local cap within each tenant/mode resume window; deterministic fair selection across complete stable schedule-instance identities within that window; audited exclusions; shared scheduler/cluster admission under [ADR 0166](./adr-0166-attributable-script-breakers-and-tenant-first-fairness.md) tenant-first fairness and per-tenant or cluster ceilings; a shared capacity denial consuming the selected catch-up slot and terminalizing without a firing claim, retry, deferral, backfill, or remint while leaving future ordinary cadence eligible; no burst or deferred missed-firing backlog; deterministic elapsed-time aggregation; corrected exact-boundary resume mathematics; distinction between reload preservation and downtime recovery; Redis loss and index rebuild; and player/operator-visible skipped and terminal outcomes.

The current implementation and runtime proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Caps, fairness weights, and audit retention may be calibrated without changing the class model. Revisit when a concrete feature needs more than one bounded catch-up effect, when measured recovery load invalidates the tenant-local cap model, or when a new clock domain cannot be expressed as wall-clock or committed tick/game time. Any extension must retain explicit durability, missed-occurrence, identity, and bounded-recovery semantics.

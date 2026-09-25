# ADR 0091: Class-Specific Script Timer Clocks and Recovery

## Status

Accepted

## Implementation Status

The class-specific script timer clock and recovery boundary is target state. The current implementation does not expose the declared per-schedule missed-firing policy, can admit multiple catch-up firings for one schedule across selection passes, and advances strictly beyond an exact observed boundary; complete leader-failover, reload/rollback, wall-clock, and correctness-one-shot proof is not claimed. See the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

## Canonical Design

- [Scripting scheduler and timers: Script Timers vs Tick Timers](../system-architecture-scripting-scheduler-and-timers.md#script-timers-vs-tick-timers)
- [Scripting scheduler and timers: Timer Resume Rule](../system-architecture-scripting-scheduler-and-timers.md#timer-resume-rule-normative)
- [Scripting normative contract tables: Timer Semantics Matrix](../system-architecture-scripting-normative-contract-tables.md#table-3-timer-semantics-matrix)
- [Scripting runtime execution: Timer Failure Semantics](../system-architecture-scripting-runtime-execution.md#timer-failure-semantics)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `TIMER-01`
- Primary capability: `AS-1.4` script scheduling and timers
- Affected capabilities: `GR-1.2`, `SF-2.3`, `SF-1.4`, `AR-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of tick cadence, wall-clock deadlines, missed-firing recovery, catch-up fairness, and correctness-bearing timers
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `TIMER-01`

## Context

The prior scripting contract described all public cadence as tick-based while also defining wall-clock due points and an equivalent wall-clock resume formula. It also said timers had no eventual-execution guarantee without separating disposable recurring firings from correctness-bearing one-shot work. That ambiguity can make authored behavior freeze unexpectedly, replay an unbounded backlog, or silently lose a required consequence.

The core timer decision already distinguishes clock units and recovery classes. Script timers need the same classification while preserving their bounded scheduling and version-fence behavior.

## Decision

Every script timer declares both its clock and recovery class.

`onInterval` and other gameplay-cadence schedules use tick/game time by default. Their cadence advances with the authoritative committed tick timeline and freezes or stretches when that timeline does. An explicitly real-time script timer may use a wall-clock deadline when its product semantics require elapsed real time, but reaching the deadline only makes work eligible; the resulting gameplay work enters at the next eligible canonical tick and never creates a parallel off-tick mutation path.

The three recovery classes are explicit in this decision. The `Correctness-bearing one-shot` class preserves durable intent and terminalization for a consequence that must occur once. The best-effort recurring policy below is the `Durable recurring` recovery class. `Advisory or cosmetic` is distinct: missed occurrences may be dropped, the schedule resumes only at a future cadence boundary, and it does not consume `COALESCE_ONE` recovery capacity; it still requires durable schedule/cadence identity and observable bounded skip/drop proof.

Durable recurring schedules declare exactly one missed-firing policy:

- `SKIP_MISSED` emits no synthetic firing for missed boundaries.
- `COALESCE_ONE` may emit at most one synthetic firing for one logical schedule in a durable resume window.

One durable, mode-scoped resume-window identity covers the complete recovery attempt across repeated scans or progress observations: live and dry-run/test recovery use separate windows and separate caps, with `isDryRun` included in the window identity and every candidate-selection, firing-claim, candidate-audit, and proof comparison. The authoritative `playableStateNamespaceId` is part of the due and resume-window identity; server-derived `playableStateScope` remains immutable policy/routing/authorization/migration-fence evidence, is exact-validated alongside that identity, and is not a uniqueness discriminator. An authoritative scope transition starts a new playable-state lifecycle and namespace; stale presented scope with unchanged authority may reuse the existing identity. Each window also binds the exact `(scriptPatchVersion, scriptPinEpoch)` as immutable execution-fence evidence without adding the pin tuple to `resumeWindowId`, and at creation freezes the effective numeric tenant-local cap plus the selection-policy revision that defines grouping, ordering, round-robin passes, and tie breaks. Repeated observations, retries, and takeovers reuse those frozen values unchanged and cannot remint another catch-up firing for the same schedule and window; a cap or policy change applies only to a later window after the current one reaches `COMPLETE`. The deterministic fair tenant-local cap applies within each mode-specific resume window, which is already scoped to one tenant, and selects coalesced candidates across complete stable schedule-instance identities; cap-excluded candidates are dropped with bounded audit and metrics under that window's frozen policy rather than deferred into an unbounded backlog. Selected candidates still enter the shared scheduler/cluster admission layer, where [ADR 0166](./adr-0166-attributable-script-breakers-and-tenant-first-fairness.md) tenant-first fairness precedes priority; catch-up cannot bypass per-tenant or cluster ceilings. A selected candidate denied by a tenant or cluster capacity ceiling is terminally skipped under the same due-candidate, `isDryRun`, and `resumeWindowId` identity with bounded audit, consumes its deterministic selection slot, creates no firing claim or `scriptEventId`, and is never deferred, retried, backfilled, or reminted as catch-up. Its next ordinary future cadence remains eligible.

Every exact script-pin selection change is a resume-window transition, including a same-patch repin that advances `scriptPinEpoch`, even when the runtime region and region epoch are unchanged. Before any new exact-pin timer admission, durable reconciliation must move every prior mode-specific `OPEN` window for the affected scope out of `OPEN`, fence its claims to the prior exact pin and `resumeWindowId`, and preserve those claims and candidate audits for settlement under their original identities. They cannot consume the next window's cap or be rewritten for the target pin. After the target exact `(scriptPatchVersion, scriptPinEpoch)` and its schedules reconcile, a still-required catch-up episode allocates the next monotonic `resumeGeneration` independently for each mode and therefore a distinct mode-specific `resumeWindowId`; retries of this transition are idempotent under the initiating `controlPlaneRequestId`.

Preserved schedules across reload, rollback, or an allowed owner-version transition use the canonical modulo resume formula. If the previous due point is at or after the resume point it is retained. If the resume point lands exactly on a later cadence boundary, the schedule fires at that boundary at most once under the durable schedule-instance plus tagged due-point identity, adding `resumeWindowId` only when `triggerMode=CATCH_UP`. Otherwise, it advances to the first cadence boundary strictly after resume. The exact `(scriptPatchVersion, scriptPinEpoch)` tuple and `playableStateNamespaceId` remain part of the due identity, while runtime scope remains separately captured and exact-validated as fence evidence.

The lack of an eventual-execution guarantee applies to each best-effort recurring firing. It does not mean that the recurring schedule disappears: after recovery, future eligible boundaries continue under the preserved cadence unless the schedule is removed, disabled, fenced, or terminally invalid.

A gameplay consequence that must occur once cannot be modeled as a skippable generic script interval. The `Correctness-bearing one-shot` class uses the durable timer path, with stable identity, authoritative durable due state, idempotent dispatch, and an explicit applied, canceled, superseded, expired, or feature-defined terminal outcome under the class-specific timer contract in ADR 0072.

## Consequences

- Gameplay intervals retain deterministic tick alignment, while explicitly real-time behavior can use wall-clock eligibility without mutating outside ticks.
- Downtime and failover cannot create unbounded recurring catch-up work or let repeated scans bypass the per-schedule cap.
- Individual best-effort firings may be skipped permanently, but recurring schedules continue at future boundaries.
- Correctness-bearing one-shot consequences receive stronger durability and terminalization instead of inheriting best-effort interval loss.
- Durable resume-window identity, clock/recovery classification, fair candidate selection, audit, and proof add scheduler complexity.

## Alternatives Considered

### Make Every Script Timer Wall-Clock Based

Rejected because gameplay cadence would continue independently of paused, stalled, or deliberately slowed game time and would require translating real-time deadlines into mutations on a separate timeline. Explicit real-time timers remain available where those semantics are intended.

### Replay Every Missed Firing

Rejected because long downtime or overload could create an unbounded burst, starve current gameplay, and replay behavior whose original context is no longer relevant.

### Treat Every Timer as Best Effort

Rejected because some one-shot consequences are correctness-bearing and require a durable terminal outcome rather than silent loss.

## Implementation and Proof Obligations

Proof must cover explicit clock and recovery-class declaration; tick-time pause, cadence change, and resume; wall-clock eligibility entering only through a canonical tick; `SKIP_MISSED`; one `COALESCE_ONE` firing per schedule and mode-specific durable resume window across repeated observations and failover; every exact script-pin selection change, including same-patch/new-`scriptPinEpoch` repin, moving prior mode-specific `OPEN` windows out of `OPEN` before target admission, allocating the next generation, and preserving old-window fencing and settlement; independent live/dry-run `isDryRun` window identity, cap accounting, candidate selection, firing claims, audits, and proof comparisons; deterministic fair tenant-local capping within each mode-specific resume window across complete stable schedule-instance identities; shared scheduler/cluster admission under [ADR 0166](./adr-0166-attributable-script-breakers-and-tenant-first-fairness.md) tenant-first fairness and per-tenant or cluster ceilings; a shared capacity denial consuming the selected catch-up slot and terminalizing without a firing claim, retry, deferral, backfill, or remint while leaving future ordinary cadence eligible; exact-boundary modulo resume; advisory/cosmetic future-boundary resume with observable bounded skip/drop evidence; version and runtime-scope changes; future recurring firings after a skipped occurrence; and durable one-shot terminalization.

The current implementation does not expose the declared per-schedule missed-firing policy, can admit multiple catch-up firings for one schedule across its selection passes, and advances strictly beyond an exact observed boundary rather than proving the accepted resume rule. The complete leader-failover, reload/rollback, wall-clock, and correctness-one-shot proof is not claimed by this decision.

Select and report the required checks and evidence under [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md); record execution results in PR/CI evidence or implementation-tracking documents rather than in this decision record.

## Reversibility and Revisit Triggers

Catch-up limits and fairness weights may be tuned without changing the clock/recovery classes or durable resume-window identity. Revisit if a measured feature requires replaying more than one missed occurrence, exact real-time execution rather than next-tick eligibility, or a new timer class that cannot map to best-effort recurring or correctness-bearing one-shot semantics.

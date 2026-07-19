# ADR 0091: Class-Specific Script Timer Clocks and Recovery

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `TIMER-01`
- Primary capability: `AS-1.4` script scheduling and timers
- Affected capabilities: `GR-1.2`, `SF-2.3`, `SF-1.4`, `AR-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of tick cadence, wall-clock deadlines, missed-firing recovery, catch-up fairness, and correctness-bearing timers

## Context

The prior scripting contract described all public cadence as tick-based while also defining wall-clock due points and an equivalent wall-clock resume formula. It also said timers had no eventual-execution guarantee without separating disposable recurring firings from correctness-bearing one-shot work. That ambiguity can make authored behavior freeze unexpectedly, replay an unbounded backlog, or silently lose a required consequence.

The core timer decision already distinguishes clock units and recovery classes. Script timers need the same classification while preserving their bounded scheduling and version-fence behavior.

## Decision

Every script timer declares both its clock and recovery class.

`onInterval` and other gameplay-cadence schedules use tick/game time by default. Their cadence advances with the authoritative committed tick timeline and freezes or stretches when that timeline does. An explicitly real-time script timer may use a wall-clock deadline when its product semantics require elapsed real time, but reaching the deadline only makes work eligible; the resulting gameplay work enters at the next eligible canonical tick and never creates a parallel off-tick mutation path.

Best-effort recurring schedules declare exactly one missed-firing policy:

- `SKIP_MISSED` emits no synthetic firing for missed boundaries.
- `COALESCE_ONE` may emit at most one synthetic firing for one logical schedule in a durable resume window.

One durable resume-window identity covers the complete recovery attempt across repeated scans or progress observations. Repeated observations cannot remint another catch-up firing for the same schedule and window. A deterministic fair global cap orders and admits coalesced candidates across schedules; cap-excluded candidates are dropped with bounded audit and metrics rather than deferred into an unbounded backlog.

Preserved schedules across reload, rollback, or an allowed owner-version transition use the canonical modulo resume formula. If the previous due point is at or after the resume point it is retained. If the resume point lands exactly on a cadence boundary, the schedule may fire there; otherwise it advances to the next future boundary. Version and runtime-scope fencing remains part of the due identity.

The lack of an eventual-execution guarantee applies to each best-effort recurring firing. It does not mean that the recurring schedule disappears: after recovery, future eligible boundaries continue under the preserved cadence unless the schedule is removed, disabled, fenced, or terminally invalid.

A gameplay consequence that must occur once cannot be modeled as a skippable generic script interval. It uses the durable correctness-bearing one-shot timer path, with stable identity, authoritative durable due state, idempotent dispatch, and an explicit applied, canceled, superseded, expired, or feature-defined terminal outcome under the class-specific timer contract in ADR 0072.

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

Proof must cover explicit clock and recovery-class declaration; tick-time pause, cadence change, and resume; wall-clock eligibility entering only through a canonical tick; `SKIP_MISSED`; one `COALESCE_ONE` firing per schedule and durable resume window across repeated observations and failover; deterministic fair global capping; exact-boundary modulo resume; version and runtime-scope changes; future recurring firings after a skipped occurrence; and durable one-shot terminalization.

The current implementation does not expose the declared per-schedule missed-firing policy, can admit multiple catch-up firings for one schedule across its selection passes, and advances strictly beyond an exact observed boundary rather than proving the accepted resume rule. The complete leader-failover, reload/rollback, wall-clock, and correctness-one-shot proof is not claimed by this decision.

## Reversibility and Revisit Triggers

Catch-up limits and fairness weights may be tuned without changing the clock/recovery classes or durable resume-window identity. Revisit if a measured feature requires replaying more than one missed occurrence, exact real-time execution rather than next-tick eligibility, or a new timer class that cannot map to best-effort recurring or correctness-bearing one-shot semantics.

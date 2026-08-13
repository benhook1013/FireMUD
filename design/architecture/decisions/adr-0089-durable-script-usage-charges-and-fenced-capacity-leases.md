# ADR 0089: Durable Script Usage Charges and Fenced Capacity Leases

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-03`
- Primary capability: `AS-1.6` automation quotas and operations
- Affected capabilities: `AS-1.1`, `AR-2.3`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of admission charges, execution usage, queued work, duplicate and recovery accounting, sandbox capacity leases, cancellation and crash behavior, and publish-readiness isolation

## Context

Script execution crosses several retryable boundaries: event admission, durable queueing, sandbox start, command handoff, cancellation, and recovery. Charging on every delivery or attempt would bill duplicate infrastructure work as new logical usage. Holding scarce sandbox capacity while work waits durably in a queue would reduce throughput without executing anything. Charging only after execution completes would make failure and timeout effective ways to consume resources without accounting for them.

Quota accounting and concurrency therefore need related but distinct durable semantics. A usage charge records that a logical unit crossed a charge point; a capacity lease controls who may currently occupy a sandbox slot.

## Decision

Each resolved handler has one full Trigger Identity and one durable Trigger-keyed charge record. The record persists its `quotaClass` and separate admission and execution charge-point state so duplicate delivery, worker retry, failover, and recovery reuse the prior accounting result rather than charging again.

Per-handler admission quota is charged exactly once when that handler is admitted. The admission charge is durable and nonrefundable. Later queue delay, cancellation, failure, timeout, or terminal outcome does not reverse it.

Durably queued work holds no sandbox concurrency capacity. A queue entry may wait, be canceled, or be recovered without reserving a sandbox slot and without incurring tenant or cluster execution usage.

Tenant and cluster execution usage is charged exactly once when execution begins. A cancellation that becomes effective before execution begins incurs no execution-usage charge. Once execution has begun, its usage charge is durable and nonrefundable even if evaluation, handoff, cancellation, timeout, or later processing fails.

Sandbox concurrency is represented by a fenced capacity lease, not by a refundable charge. Execution may occupy capacity only while holding the matching live lease and fence. The lease is always released on terminal completion or cancellation and is reclaimed under the same fencing contract after worker crash or timeout. Release or reclamation makes capacity available again; it does not refund either admission or execution usage.

The automation tick scheduler's estimated-cost reservation is a separate admission mechanism. It uses each immutable artifact-pinned estimated millisecond cost and admits a deterministic ordered prefix while cumulative reserved cost fits `AUTOMATION_TICK_BUDGET_MS`; unadmitted remainder is deferred. This reservation is neither a durable admission/execution usage charge nor a sandbox occupancy lease. Actual runtime is calibration telemetry only and does not refund or reopen the same tick; releasing a sandbox lease likewise does not alter scheduler reservations or durable usage history.

Duplicate deliveries and recovery attempts look up and reuse the Trigger-keyed charge record. They may reacquire a new fenced capacity lease when execution must safely resume, but they do not create another admission or execution charge for the same logical handler trigger.

`PUBLISH_READINESS` is an isolated quota class for readiness `onLoad` work. Its admission accounting, execution usage, and sandbox-capacity policy do not consume or compete with live gameplay script budgets, and live traffic does not consume the readiness allocation. The persisted charge record and work item retain this class rather than inferring it from event type during execution.

## Consequences

- Duplicate delivery, worker retry, and recovery do not multiply logical usage charges.
- Admission pressure remains visible even when admitted work is later canceled or never begins execution.
- Durable queues can absorb bursts without pinning sandbox concurrency while work waits.
- Tenant and cluster execution usage reflects work that actually started, including failed and timed-out attempts, rather than post-success accounting.
- Fenced lease recovery returns capacity after crashes and timeouts without rewriting durable usage history.
- Readiness validation cannot exhaust live gameplay script budgets, and live load cannot starve the isolated readiness allocation.
- Durable charge records, execution-start transitions, lease fencing, reclamation, and separate readiness controls add persistence and operational complexity.

## Alternatives Considered

### Two-Phase Usage Reservation with Refund

Rejected because reservation, commit, refund, crash recovery, and duplicate delivery would create a distributed accounting state machine at each charge point. A lost or repeated refund could undercharge or overcharge logical work, while a capacity lease already models the temporary resource that must be returned.

### Charge Only after Execution Completes

Rejected because failed, timed-out, or deliberately expensive executions would consume real tenant and cluster resources without usage accounting. It would also make accounting depend on terminal delivery rather than the durable execution-start boundary.

## Implementation and Proof Obligations

Implement a durable full-Trigger-Identity charge record with persisted `quotaClass` and idempotent admission and execution charge markers; admission-time charging; queueing without a capacity reservation; execution-start charging; fenced sandbox-capacity acquisition, release, timeout, and crash reclamation; and isolated `PUBLISH_READINESS` accounting and capacity.

Proof must cover duplicate ingress before and after admission charging; concurrent admission attempts; durable queue delay with no capacity held; cancellation before execution with no execution charge; cancellation, failure, and timeout after execution start without refund; duplicate workers racing to start; one execution charge under retry and recovery; stale lease-holder rejection; terminal release; crash and timeout reclamation; capacity reuse without charge reversal; reuse of the durable charge record after restart; and bidirectional isolation between `PUBLISH_READINESS` and live gameplay budgets.

The current durable charge-record coverage, exact execution-start accounting boundary, duplicate and recovery reuse, fenced lease lifecycle, crash and timeout reclamation, readiness isolation, and focused proof are not claimed by this decision.

### Supplemental clarification (2026-08-13)

Estimated-cost tick reservations, durable usage charges, and fenced sandbox occupancy are three distinct states: scheduling admission, consumed usage, and temporary capacity. Only the latter is released for reuse, and no actual-runtime measurement creates a same-tick refund.

## Reversibility and Revisit Triggers

Quota values, capacity sizes, lease durations, and readiness allocations may be calibrated without changing charge-point identity or refund semantics. Revisit the accounting model only if a billing or product contract requires reversible reservations or outcome-based charging and can provide an auditable idempotent protocol that remains correct across duplicate delivery, cancellation, crash, timeout, and recovery.

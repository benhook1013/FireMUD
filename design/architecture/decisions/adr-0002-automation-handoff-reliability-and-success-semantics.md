# ADR 0002: Automation Handoff Reliability and Success Semantics

## Status

Accepted

## Implementation Status

This decision is partially implemented. Stage-qualified outcomes exist, but the database-save/Redis-stage gap, durable per-dispatch custody, and state-sensitive duplicate handling do not yet satisfy the accepted handoff boundary. See the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status) for current implementation and proof status.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-21
- Human review disposition: Accepted
- Review source: `AUTO-02`

## Context

Scripting produces commands that must flow into the tick system. The historical design included Redis-based staging (`automation:queue:*` and the former `automation:tick:*` path) and internal gRPC handoff to Game Session. The `automation:tick:*` path is retired and not live; current automation stages work through `automation:queue:*` and hands gameplay enqueue to Game Session. Without a clear contract, “success” can mean either “DSL ran” or “effects will happen”, which undermines rollback semantics and operator trust.

## Decision

- The historical generic label `script_event_audit.finalOutcome=success` is superseded **only as a label** by [ADR 0064](adr-0064-stage-qualified-script-outcomes.md). For a live command-producing handler, `finalStage=TICK_HANDOFF` and `finalOutcome=handoff_accepted` mean every required child dispatch is in Game Session custody: each child is either durably adopted/admitted, or covered by a committed per-child durable re-drive obligation that Game Session owns for outcome convergence. This is not a claim that gameplay effects were applied. The canonical stage-qualified outcomes are defined in the [scripting normative outcome table](../system-architecture-scripting-normative-contract-tables.md#canonical-finaloutcome-values-normative).
- `completed_no_commands`, `readiness_success`, and `dry_run_success` remain distinct non-handoff outcomes in their declared branches.
- Local `automation:queue:*` staging is only pending Automation work-item discovery and is not handoff acceptance. A durable remote coordinator or follow-up schedule counts as the per-child re-drive obligation only when Game Session transactionally accepts it under stable identities; acceptance never means that the target tick has executed.
- The pipeline must record stage-aware outcomes so operators can see where a trigger failed to progress (DSL evaluation vs persistence vs handoff/enqueue).
- Duplicate lookup compares the immutable dispatch digest and returns accepted only from a state that proves handoff custody. A received-but-unstaged volatile row must be re-driven, reported pending or not accepted, or exposed as terminal `NOT_APPLIED`; it must never become an unconditional accepted no-op.
- If Redis staging queues are reset/lost, the system must either:
  - re-drive delivery from a durable outbox of script work items, or
  - record explicit “dropped before tick enqueue” outcomes and never report `handoff_accepted`.

## Consequences

- The Automation & Scripting Service must have a durable representation of “pending script work items” if the target behavior is “do not drop silently”.
- Any best-effort Redis buffer (`automation:queue:*`) cannot be the sole record of admitted work if `handoff_accepted` implies tick acceptance.
- A handler may be `handoff_accepted` even when one of its commands later becomes `NOT_APPLIED` or `ABANDONED`; the authoritative per-command status explains that later result.

## References

- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`

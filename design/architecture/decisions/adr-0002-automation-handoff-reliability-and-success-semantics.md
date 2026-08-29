# ADR 0002: Automation Handoff Reliability and Success Semantics

## Status

Accepted

## Implementation Status

This decision is partially implemented. Stage-qualified outcomes exist, but durable outbox persistence and queue-pointer publication, durable per-dispatch custody, and state-sensitive duplicate handling do not yet satisfy the accepted handoff boundary. See the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status) for current implementation and proof status.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-21
- Human review disposition: Accepted
- Review source: `AUTO-02`

## Context

Scripting produces commands that must flow into the tick system. The current design records script work items in a durable PostgreSQL outbox and publishes instance-aware `automation:queue:*` discovery pointers in Cache/Rate-Limit Redis, then uses internal gRPC handoff to Game Session. Queue pointers are disposable discovery indexes, not work truth; durable outbox rows and Game Session handoff custody determine recovery and success. Without a clear contract, “success” can mean either “DSL ran” or “effects will happen”, which undermines rollback semantics and operator trust.

## Decision

- The historical generic label `script_event_audit.finalOutcome=success` is superseded **only as a label** by [ADR 0064](adr-0064-stage-qualified-script-outcomes.md). For a live command-producing handler, `finalStage=TICK_HANDOFF` and `finalOutcome=handoff_accepted` mean every required child dispatch is in Game Session custody: each child is either durably adopted/admitted, or covered by a committed per-child durable re-drive obligation that Game Session owns for outcome convergence. This is not a claim that gameplay effects were applied. The canonical stage-qualified outcomes are defined in the [scripting normative outcome table](../system-architecture-scripting-normative-contract-tables.md#canonical-finaloutcome-values-normative).
- `completed_no_commands`, `readiness_success`, and `dry_run_success` remain distinct non-handoff outcomes in their declared branches.
- The durable PostgreSQL outbox/work-item row is the pending-work record. An instance-aware `automation:queue:*` pointer in Cache/Rate-Limit Redis is only a disposable discovery index; it is neither handoff acceptance nor work truth. A durable remote coordinator or follow-up schedule counts as the per-child re-drive obligation only when Game Session transactionally accepts it under stable identities; acceptance never means that the target tick has executed.
- The pipeline must record stage-aware outcomes so operators can see where a trigger failed to progress (DSL evaluation vs persistence vs handoff/enqueue).
- Duplicate lookup compares the immutable dispatch digest and returns accepted only from a state that proves handoff custody. A received-but-not-yet-handed-off durable outbox/work-item row must be re-driven, reported pending or not accepted, or exposed as terminal `NOT_APPLIED`; it must never become an unconditional accepted no-op because a discovery pointer is absent.
- If `automation:queue:*` discovery pointers in Cache/Rate-Limit Redis are reset or lost, the system must rebuild them from durable PostgreSQL outbox/work-item rows and re-drive delivery; pointer loss is not a dropped-work outcome. If durable outbox persistence fails before Game Session handoff, the system must record an explicit persistence/drop outcome and never report `handoff_accepted`.

## Consequences

- The Automation & Scripting Service must have a durable representation of “pending script work items” if the target behavior is “do not drop silently”.
- Any Cache/Rate-Limit Redis discovery pointer (`automation:queue:*`) cannot be the sole record of admitted work if `handoff_accepted` implies tick acceptance.
- A handler may be `handoff_accepted` even when one of its commands later becomes `NOT_APPLIED` or `ABANDONED`; the authoritative per-command status explains that later result.

## References

- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`

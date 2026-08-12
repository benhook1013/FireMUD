# ADR 0002: Automation Handoff Reliability and Success Semantics

## Status

Accepted

## Context

Scripting produces commands that must flow into the tick system. The design includes Redis-based staging (`automation:queue:*`, `automation:tick:*`) and internal gRPC handoff to Game Session. Without a clear contract, “success” can mean either “DSL ran” or “effects will happen”, which undermines rollback semantics and operator trust.

## Decision

- The historical generic label `script_event_audit.finalOutcome=success` is superseded **only as a label** by [ADR 0064](adr-0064-stage-qualified-script-outcomes.md). The retained handoff decision is that a handoff-success outcome means the trigger’s resulting commands were accepted into the tick system (handoff/enqueue succeeded), not merely that the DSL evaluated. The canonical stage-qualified outcomes, including `handoff_accepted` and `completed_no_commands`, are defined in the [scripting normative outcome table](../system-architecture-scripting-normative-contract-tables.md#canonical-finaloutcome-values-normative).
- The pipeline must record stage-aware outcomes so operators can see where a trigger failed to progress (DSL evaluation vs persistence vs handoff/enqueue).
- If Redis staging queues are reset/lost, the system must either:
  - re-drive delivery from a durable outbox of script work items, or
  - record explicit “dropped before tick enqueue” outcomes and never report `handoff_accepted`.

## Consequences

- The Automation & Scripting Service must have a durable representation of “pending script work items” if the target behavior is “do not drop silently”.
- Any best-effort Redis buffer (`automation:queue:*`) cannot be the sole record of admitted work if `handoff_accepted` implies tick acceptance.

## References

- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`

# ADR 0002: Automation Handoff Reliability and Success Semantics

## Status

Accepted

## Context

Scripting produces commands that must flow into the tick system. The design includes Redis-based staging (`automation:queue:*`, `automation:tick:*`) and internal gRPC handoff to Game Session. Without a clear contract, “success” can mean either “DSL ran” or “effects will happen”, which undermines rollback semantics and operator trust.

## Decision

- `script_event_audit.finalOutcome=success` must mean: the trigger’s resulting commands were accepted into the tick system (handoff/enqueue succeeded), not merely that the DSL evaluated.
- The pipeline must record stage-aware outcomes so operators can see where a trigger failed to progress (DSL evaluation vs persistence vs handoff/enqueue).
- If Redis staging queues are reset/lost, the system must either:
  - re-drive delivery from a durable outbox of script work items, or
  - record explicit “dropped before tick enqueue” outcomes and never report `success`.

## Consequences

- The Automation & Scripting Service must have a durable representation of “pending script work items” if the target behavior is “do not drop silently”.
- Any best-effort Redis buffer (`automation:queue:*`) cannot be the sole record of admitted work if `success` implies tick acceptance.

## References

- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`

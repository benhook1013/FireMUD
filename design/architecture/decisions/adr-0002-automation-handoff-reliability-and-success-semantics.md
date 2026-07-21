# ADR 0002: Automation Handoff Reliability and Success Semantics

## Status

Accepted

Human adversarial review affirmed and clarified this decision for `AUTO-02` on 2026-07-21.

## Context

Scripting produces commands that must flow into the tick system. The design includes Redis-based staging (`automation:queue:*`, `automation:tick:*`) and internal gRPC handoff to Game Session. Without a clear contract, “success” can mean either “DSL ran” or “effects will happen”, which undermines rollback semantics and operator trust.

## Decision

- Live command-producing handlers do not use a generic `success` outcome. `finalStage=TICK_HANDOFF` and `finalOutcome=handoff_accepted` mean every deterministic child dispatch has been durably adopted by Game Session, which now owns its outcome convergence. This is not a claim that gameplay effects were applied.
- `completed_no_commands`, `readiness_success`, and `dry_run_success` remain distinct non-handoff outcomes in their declared branches.
- Local handoff acceptance requires proof that the command is staged/admitted or that Game Session has committed an explicit durable re-drive obligation. Durable remote coordinator/follow-up scheduling counts as handoff acceptance when Game Session transactionally accepts that obligation under stable identities; it does not mean the target tick has executed.
- The pipeline must record stage-aware outcomes so operators can see where a trigger failed to progress (DSL evaluation vs persistence vs handoff/enqueue).
- Duplicate lookup compares the immutable dispatch digest and returns accepted only from a state that proves handoff custody. A received-but-unstaged volatile row must be re-driven, reported pending/not accepted, or exposed as terminal `NOT_APPLIED`; it must never become an unconditional accepted no-op.
- If Redis staging queues are reset/lost, the system must either:
  - re-drive delivery from a durable outbox of script work items, or
  - record explicit “dropped before tick enqueue” outcomes and never report `success`.

## Consequences

- The Automation & Scripting Service must have a durable representation of “pending script work items” if the target behavior is “do not drop silently”.
- Any best-effort Redis buffer (`automation:queue:*`) cannot be the sole record of admitted work if `success` implies tick acceptance.
- A handler may be `handoff_accepted` even when one of its commands later becomes `NOT_APPLIED` or `ABANDONED`; the authoritative per-command status explains that later result.
- Current code's database-save/Redis-stage gap and unconditional duplicate acceptance do not yet satisfy this clarified contract.

## References

- `design/architecture/system-architecture-scripting-contracts.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`

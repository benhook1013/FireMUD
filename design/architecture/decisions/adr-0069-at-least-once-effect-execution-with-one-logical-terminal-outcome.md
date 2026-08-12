# ADR 0069: At-Least-Once Effect Execution with One Logical Terminal Outcome

## Status

Accepted

## Implementation Status

The command/effect boundary and single logical terminal outcome are target state; complete durable identity guards, evidence-based reconciliation, and focused crash/replay proof remain incomplete.

## Canonical Design

- [Tick Execution Flows](../system-architecture-tick-execution-flows.md)
- [Tick Failure and Operations](../system-architecture-tick-failures-and-operations.md)
- [Transaction Strategies](../system-architecture-transactions.md)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-16`
- Primary capability: `GR-1.2` tick scheduling, execution, and deterministic command resolution
- Affected capabilities: `GR-1.4`, `SF-2.3`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent analysis of literal physical exactly-once, universal replay, silent-drop, and manual-only alternatives
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `TICK-16`

## Context

Command acceptance and effect execution are different lifecycle boundaries. A command may be durably accepted but lost before any effect is claimed or staged. Once an effect is durably staged, crashes and uncertain acknowledgements may cause the same handler to be invoked more than once even though the logical gameplay mutation must occur no more than once.

Describing this as “exactly-once execution” would wrongly imply one physical invocation. Describing every accepted command as an effect would invent ledger state for work that never entered the execution window. The contract must identify precisely what terminates at each boundary.

## Decision

An accepted command lost before durable staging terminates at the command lifecycle with:

- `executionOutcome = LOST_BEFORE_STAGING`; and
- `gameplayResult = NOT_APPLIED`.

No effect ledger row is invented for a command that never produced a durably claimed or staged effect.

Before any participant verification, the canonical Game Session context binds each durably claimed or staged effect's root `EffectId` to its typed operation, immutable request digest, required-participant context, and sealed manifest. Participants validate that sealed binding before their local guard/effect work; a conflicting operation, digest, or participant binding fails closed. Before terminal aggregation, Game Session must also verify that the returned participant projections exactly equal the sealed expected participant set and that every projection matches that same root, operation, digest, participant context, and manifest binding. A missing, extra, duplicate, partial, or conflicting projection fails closed and remains reconciliation-required rather than producing a terminal aggregate. Physical execution is at least once: retries may invoke the handler multiple times after crashes, lost acknowledgements, or replay.

The owning domain's durable idempotency guard permits at most one logical authoritative state mutation for that identity and digest. Reuse of the identity with a conflicting digest fails closed.

Every staged effect ledger row reaches exactly one terminal status:

- `APPLIED` when authoritative domain evidence proves that the logical mutation committed; or
- `ABANDONED` only when authoritative evidence proves the effect was unapplied, or an approved explicit auditable exception authorizes terminalization, with an explicit reason. Retry exhaustion or technical failure alone never qualifies.

Literal one-time physical invocation is not promised. Duplicate presentation feedback may occur around retries or connection failure, but it does not authorize duplicate authoritative mutation.

`REPLAY_NOOP` is not a third terminal effect status. When an authoritative owner guard or equivalent durable evidence proves that the original effect already committed, the ledger terminalizes as `APPLIED` with `REPLAY_NOOP` as its outcome or reason.

Command gameplay results are derived from the terminal results of their required effects and remain distinct from effect status. The canonical command result vocabulary is `SUCCESS`, `PARTIAL`, `FAILED`, `TIMEOUT`, and `NOT_APPLIED`.

The system does not silently drop staged effects, leave them permanently ambiguous, or permit more than one logical application of the same immutable effect.

## Consequences

- Command intake truth remains accurate when no effect was ever staged.
- Recovery may retry physical work without duplicating authoritative gameplay state.
- Operators and callers can distinguish command results, effect terminal states, and replay reasons.
- Owner services require durable identity-and-digest guards and authoritative evidence queries.
- The ledger and recovery controller must retain unresolved staged effects until they reach a justified terminal outcome.
- Presentation output can duplicate even while authoritative state remains logically single-apply.

## Alternatives Considered

### Literal Physical Exactly Once

Rejected because a caller cannot distinguish a handler that failed before commit from one that committed and lost its acknowledgement without distributed transactional coupling. Enforcing one physical invocation would sacrifice recovery or require a cross-service transaction while still not proving player observation.

### Replay Every Uncertain Item

Rejected because stale or no-longer-valid gameplay intent may become unsafe. Replay is permitted only under the original identity and digest, with current validity checks and owner idempotency guards; otherwise the effect becomes `ABANDONED`.

### Silent Drop

Rejected because a staged effect could disappear without a durable player or operator explanation and leave command outcome ambiguous.

### Manual-Only Terminalization

Rejected as the normal contract because unresolved effects could remain ambiguous indefinitely and recovery would depend on operator availability. Service-owned operator intervention remains available for exceptional cases without replacing bounded automatic convergence.

## Implementation and Proof Obligations

Proof must cover accepted-command loss before staging without an invented effect row; sealed root/operation/digest/participant/manifest binding before participant verification and conflict rejection; crashes before and after domain commit; lost acknowledgements; duplicate physical invocation with one logical mutation; authoritative `REPLAY_NOOP` evidence terminalizing as `APPLIED`; evidence-qualified or explicitly approved auditable `ABANDONED` outcomes with retry-exhaustion and technical-failure rejection; command-result derivation across zero, one, and multiple required effects; duplicate presentation feedback without duplicate state; replay and reset convergence; and absence of silent drop or permanently ambiguous staged rows.

The current implementation and runtime proof are not claimed to satisfy this decision.

## Reversibility and Revisit Triggers

Reason vocabularies and recovery timing may evolve without changing the authority model. Revisit physical execution semantics only if all participating state moves behind one transactional authority or a proven distributed commit mechanism supplies materially better product behavior without unacceptable availability and latency cost. Revisit presentation delivery separately if a client protocol introduces explicit acknowledgement and replay semantics.

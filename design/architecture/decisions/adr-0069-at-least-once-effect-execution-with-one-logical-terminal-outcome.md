# ADR 0069: At-Least-Once Effect Execution with One Logical Terminal Outcome

## Status

Accepted

## Implementation Status

The command/effect boundary, deterministic command-plan root identity, and single logical terminal outcome are target state; complete durable identity guards, evidence-based reconciliation, and focused crash/replay proof remain incomplete.

## Canonical Design

- [Tick Execution Flows](../system-architecture-tick-execution-flows.md)
- [Tick Failure and Operations](../system-architecture-tick-failures-and-operations.md)
- [Transaction Strategies](../system-architecture-transactions.md)
- [Identifier Glossary](../system-architecture-identifier-glossary.md#cross-service-effect-identity)

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

### Deterministic command plans and root identity

An admitted command has one deterministic effect plan: the ordered set of logical root effects admitted for that command. The order is supplied by the frozen typed command or `ResolvedEffectPlan` semantic order owned by the command/action contract; Game Session does not infer it from scheduler selection. Built-in commands declare an equivalent stable semantic order. An ambiguous or duplicate root order fails before admission, and Game Session persists the ordered plan manifest and its digest before tick staging. Each logical operation in the plan has exactly one root `EffectId`, with any affected participants represented by guards beneath that root. A conserved multi-participant operation normally shares one root across its participants, but still follows [ADR 0053](./adr-0053-command-atomicity-by-invariant-class.md)'s required/optional classification and its co-location or reservation/escrow rules; one root does not claim global atomicity.

The plan assigns a stable zero-based `planOrdinal` in that supplied semantic order. A single-root plan uses `planOrdinal=0`; a multi-root plan uses `0..n-1`; a zero-effect plan allocates no ordinal and no root `EffectId`. Before allocation, the durable gameplay command row freezes/binds its request fingerprint and resolved runtime scope. Game Session owns an opaque root `EffectId` allocation row unique on `(tenantId, gameInstanceId, commandId, planOrdinal)`; that row also binds the ordered plan manifest digest and validates the command row's frozen binding. Reuse requires the same command row identity, frozen command/runtime binding, `planOrdinal`, and manifest digest; any mismatch fails closed. For Automation, the complete applicable Command-Handoff Identity, including any optional distinct target runtime fields, is the admission/deduplication identity and must exact-map to one durable target `commandId`; after that mapping, root allocation uses the target command row's same canonical key. Trigger Identity and `scriptEventId` remain handler/correlation identities, not root-allocation inputs. Ordinary retries, replay, and reconciliation read and reuse the binding; the opaque ID is not derived by participants.

`planOrdinal` and this allocation binding apply only to command-root work. Passive, inbound/remote, timer, retry, and already-generated effects retain their owner/source-specific root identity contracts and do not synthesize a command plan unless they materialize as an admitted command; generated children remain beneath the enclosing root. Random `tickBatchId`, mutable command text, `effectKey`, `sourceOrdinal`/`enqueueSeq`, participant tuples, Trigger Identity or `scriptEventId`, and child ordinals cannot substitute for the durable command row identity or allocation binding; they retain their existing staging, ordering, descriptor, participant, handler, and lineage roles.

The plan root is distinct from the command ingress identity, source-claim identity, Automation Command-Handoff Identity, participant guard identity, and generated child identity. A post-abandon re-drive may receive a fresh root only under the existing conclusive `ABANDONED` and source-terminalization rules; an ordinary retry or replay never allocates one.

Every staged effect ledger row reaches exactly one terminal status:

- `APPLIED` when authoritative domain evidence proves that the logical mutation committed; or
- `ABANDONED` only when authoritative evidence proves the effect was unapplied and any already-declared applicable feature rule permits it to be no longer valid, with an explicit reason. Inconclusive execution remains `SCHEDULED`/reconciliation-required; timeout, retry exhaustion, missing coordination, age, or technical failure alone never proves `ABANDONED`.

Literal one-time physical invocation is not promised. Duplicate presentation feedback may occur around retries or connection failure, but it does not authorize duplicate authoritative mutation.

`REPLAY_NOOP` is not a third terminal effect status. When an authoritative owner guard or equivalent durable evidence proves that the original effect already committed, the ledger terminalizes as `APPLIED` with `REPLAY_NOOP` as its outcome or reason.

Command gameplay results are derived from the terminal results of their required effects and remain distinct from effect status. The canonical command result vocabulary is `SUCCESS`, `PARTIAL`, `FAILED`, `TIMEOUT`, and `NOT_APPLIED`.

The system does not silently drop staged effects, leave them permanently ambiguous, or permit more than one logical application of the same immutable effect.

## Consequences

- Command intake truth remains accurate when no effect was ever staged.
- Recovery may retry physical work without duplicating authoritative gameplay state.
- Operators and callers can distinguish command results, effect terminal states, and replay reasons.
- Owner services require durable identity-and-digest guards and authoritative evidence queries.
- Deterministic plans make command-root admission and replay stable without deriving the allocation binding from scheduler selection, staging coordinates, source ordering, or handler identity.
- Multi-participant conservation remains governed by command atomicity/co-location or reservation semantics; a shared root is not a distributed transaction.
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

Rejected as the normal contract because unresolved effects could remain ambiguous indefinitely and recovery would depend on operator availability. Service-owned operator intervention may investigate and drive evidence-based reconciliation without replacing bounded automatic convergence.

## Implementation and Proof Obligations

Proof must cover accepted-command loss before staging without an invented effect row; semantic zero-, single-, and multi-root plan ordering supplied by the frozen typed command/action contract, including built-in stable order and ambiguous/duplicate-order rejection; freezing/binding of command request fingerprint and runtime scope; persistence of the ordered plan manifest/digest and opaque allocation-row binding; uniqueness on `(tenantId, gameInstanceId, commandId, planOrdinal)`; reuse only with the same command row identity, frozen command/runtime binding, `planOrdinal`, and manifest digest, with mismatch rejection; Automation handoff admission exact-mapping to one durable target command; preservation of separate source-ordering, participant, handler, and child identities; command-root-only scope; conserved multi-participant handling under ADR 0053; sealed root/operation/digest/participant/manifest binding before participant verification and conflict rejection; crashes before and after domain commit; lost acknowledgements; duplicate physical invocation with one logical mutation; authoritative `REPLAY_NOOP` evidence terminalizing as `APPLIED`; evidence-qualified `ABANDONED` outcomes with timeout, retry-exhaustion, missing-coordination, age, and technical-failure rejection; command-result derivation across zero, one, and multiple required effects; duplicate presentation feedback without duplicate state; replay and reset convergence; and absence of silent drop or permanently ambiguous staged rows.

The current implementation and runtime proof are not claimed to satisfy this decision.

## Reversibility and Revisit Triggers

Reason vocabularies and recovery timing may evolve without changing the authority model. Revisit physical execution semantics only if all participating state moves behind one transactional authority or a proven distributed commit mechanism supplies materially better product behavior without unacceptable availability and latency cost. Revisit presentation delivery separately if a client protocol introduces explicit acknowledgement and replay semantics.

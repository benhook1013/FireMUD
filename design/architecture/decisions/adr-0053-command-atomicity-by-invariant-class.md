# ADR 0053: Command Atomicity by Invariant Class

## Status

Accepted

## Implementation Status

The decision is accepted; command-family classification, stronger-atomicity routing, and focused proof remain partial. Existing durable command, batch, effect, and remote-follow-up foundations prove only migrated families and per-effect convergence.

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-03`
- Primary capability: `SF-2.4` Transaction, idempotency, and workflow patterns
- Affected capabilities: `GR-1.2`, `SF-2.3`, `GR-4.1`, `GR-2.2`, `GR-3.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `TICK-03`, including default-model validation and stronger-atomicity alternative passes
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `TICK-03`

## Context

Global ACID across gameplay services would put distributed locks and coordinator availability in the tick path. Service-local transactions plus stable at-least-once effects are the correct general model, but per-effect terminal ledger rows do not by themselves prove a correct command result. One required effect could be `APPLIED` while another becomes `ABANDONED`.

Some command classes tolerate bounded internal convergence. Others involve unique ownership, conserved value, conditional exchange, irreversible consumption, premium entitlements, or external commitments for which partial visibility is exploitable or irreversible.

## Decision

Local transactions plus stable `EffectId`-guarded at-least-once effects remain the default gameplay execution model. Every command type must additionally declare its required effects, optional effects, permitted terminal combinations, whether `PARTIAL` is an intentional player-visible result, and which stronger-atomicity routing test, if any, applies.

Canonical command outcomes are:

- `SUCCESS`: every required authoritative effect is durably `APPLIED` or confirmed as an idempotent replay/no-op, and the player-visible result is committed.
- `PENDING`: any required effect, remote leg, external handoff, or reconciliation remains unresolved.
- `FAILURE`: the command's defined terminal outcome proves no required mutation succeeded, or its single authoritative transaction rejected without committing.
- `PARTIAL`: only an explicitly designed terminal subset permitted by the game rules; it must never conceal an invariant breach.

Optional-effect failure can coexist with success only when that effect was classified optional before execution. Lost or timed-out responses retain `PENDING/UNKNOWN`; clients query or retry using the original `commandId`, and owners reuse the original `EffectId`.

Independent tick effects are forbidden when temporary partial state could violate unique ownership, value conservation or non-negative balance, mutually conditional exchange, irreversible consumption, premium entitlement, or an external commitment. Such commands route to one authoritative service-local transaction when the invariant can be co-located, or to a tick-adjacent durable reservation/escrow workflow with idempotent steps and transactional outbox delivery.

Routine synchronous distributed two-phase commit is not used. Real-money/provider operations remain outside ordinary tick convergence. Examples include local atomic inventory transfer where one service owns both sides, escrow/reservation for multi-party trade, and a durable accounting/entitlement workflow for premium-value debit plus grant.

## Consequences

- Ordinary movement, combat, and independent gameplay effects avoid global transaction coordination.
- Command success aggregates required participant outcomes rather than inferring success from queueing or individual effect terminality.
- High-value or mutually conditional actions have higher latency and workflow complexity.
- Temporary internal partial state remains possible only where declared command semantics tolerate it and player success remains pending.
- Feature designers must classify invariants and effect optionality explicitly.

## Alternatives Considered

### Global Two-Phase Commit for Gameplay

Rejected because it couples tick latency and availability to every participant and complicates crash recovery through held distributed resources.

### Eventual Convergence for Every Command

Rejected because reconciliation cannot make temporary duplicate ownership, lost conserved value, or irreversible external commitment safe.

### Compensating Inverse Effects by Default

Rejected because inverse effects can fail, create new player-visible actions, and cannot reliably undo consumption or external commitments.

## Implementation and Proof Obligations

Each command family must prove required/optional classification, command-level aggregation, lost-response reuse, no premature success, permitted partial outcomes, and the stronger-routing test. Trade, conserved currency, premium entitlement, unique-item transfer, and external-effect examples require focused invariant and crash-window proof before release.

## Reversibility and Revisit Triggers

Individual command classes can move between ordinary effects, co-located transactions, and reserved workflows without changing the general tick model. Revisit global coordination only if a measured class cannot meet its invariant through co-location or reservation and its product value justifies the latency and availability cost.

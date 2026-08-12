# ADR 0070: Bounded Within-Tick Visibility by Semantic Phase

## Status

Accepted

## Implementation Status

Semantic phase visibility and parent-local generated-effect rules are target state; persisted causal bases, phase barriers, and focused replay proof remain implementation gaps.

## Canonical Design

- [Tick System and Runtime Design](../system-architecture-ticks.md)
- [Tick Execution Flows](../system-architecture-tick-execution-flows.md)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-17`
- Primary capability: `GR-1.2` tick scheduling, execution, and deterministic command resolution
- Affected capabilities: `GR-2.1`, `GR-4.1`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of strict-snapshot, sequential-read, universal-overlay, and bounded-hybrid visibility models
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `TICK-17`

## Context

Within-tick visibility determines whether one action can depend on another action whose domain mutation is not yet durably confirmed. A universal speculative overlay could support arbitrary same-tick chains, but it would have to reproduce owner invariants and cascade failures when an earlier projected result is rejected. Independently fresh reads can instead expose whichever partial commits happen to be visible at call time, making replay and gameplay depend on service timing.

FireMUD needs deterministic phase semantics without distributed MVCC or a second authoritative model of the whole world.

## Decision

Within-tick visibility uses a bounded hybrid organized by semantic phase, not a universal same-tick overlay.

Each tick begins from a stable committed pre-tick causal base scoped by tenant, game instance, region, region epoch, and the prior committed tick. Start-of-tick passive and inbound effects execute first and must be authoritatively confirmed before actor resolution begins.

Root actor actions use one persisted, stable post-passive resolution basis. They may observe the confirmed start-passive and inbound outcomes, but they do not observe other root actor actions from the same tick. This basis is logical tick-resolution evidence; it is neither distributed MVCC nor ADR 0059's presentation-read causal floor.

Generated effects may depend only on their own parent's durable confirmed result and deterministic child ordinals. They do not acquire arbitrary visibility into other root actions or their generated effects.

Resolution never consumes raw Redis `pending` state, uncommitted intent, or independently fresh mixed-fence reads as gameplay truth. Exact owner-specific scope, epoch, location, holder, aggregate version, and other required preconditions still decide whether a mutation commits.

When root actions conflict over one owner invariant, the recorded selected-manifest order determines which attempt is admitted first. A later stale loser fails, retries, or terminalizes as not applied under its command policy; it is not re-resolved against a partially changed mixed-fence view.

Arbitrary cross-actor consequences become visible to other root actions in the next tick. A future feature may introduce same-tick cross-actor dependency only by explicitly cataloguing its dependency and failure semantics.

Replay uses the recorded resolution basis, confirmed parent results, deterministic child ordinals, and selected-manifest order. Missing required evidence causes a bounded wait followed by explicit failure, retry, or not-applied handling; it never permits speculative mixed-fence continuation.

Concrete semantics include:

- confirmed start-passive poison can prevent an actor's action in that tick;
- lifesteal or another generated child can apply immediately from its own confirmed parent result; and
- one actor opening a door, dropping an item, buffing an ally, or stunning another actor does not change that other actor's root action until the next tick.

## Consequences

- Gameplay has deterministic, explainable phase boundaries rather than timing-dependent same-tick visibility.
- Start-passive confirmation adds a phase barrier before actor resolution.
- Root actions can share one stable resolution basis instead of serially performing fresh distributed reads after every earlier action.
- Parent-local generated effects retain useful immediate behavior without a general-purpose speculative world model.
- Cross-actor combos may take one additional tick unless a feature earns an explicit dependency contract.
- Persisting the post-passive basis and confirmed results adds storage and replay-proof work, but avoids the larger state, invalidation, and dependency cost of a universal overlay.

## Alternatives Considered

### Strict Distributed Tick-Start Snapshot

Rejected because it would hide even confirmed start-passive outcomes and would require frozen cross-owner versions, historical reads, or distributed snapshot machinery that FireMUD does not otherwise provide.

### Fully Sequential Fresh Domain Reads and Commits

Rejected because every root action would sit behind the prior action's distributed read and commit latency. Partial commits and crash recovery would also make later resolution depend on service timing unless every intermediate result were durably captured.

### Universal Deterministic Same-Tick Overlay

Rejected because it would duplicate owner invariants in a speculative model and require durable dependency tracking and cascading invalidation whenever an earlier projected mutation failed. Explicitly catalogued future features may adopt narrower dependencies without making that overlay universal.

## Implementation and Proof Obligations

Proof must cover stable pre-tick scoping, authoritative start-passive/inbound confirmation before actor resolution, one persisted post-passive basis, isolation between root actor actions, parent-result and child-ordinal visibility, poison preventing an action, immediate parent-local lifesteal, next-tick visibility for door/drop/buff/stun cross-actor effects, rejection of Redis pending and uncommitted intent, mixed-fence rejection, exact owner preconditions, deterministic manifest-order conflict resolution, stale-loser outcomes, crash and replay from recorded evidence, and bounded missing-evidence handling.

The current implementation and runtime proof are not claimed by this decision.

## Reversibility and Revisit Triggers

The persisted evidence shape may evolve without changing the phase semantics. Revisit when a concrete gameplay feature requires same-tick cross-actor visibility and can declare its dependency graph, authoritative validation, partial-failure behavior, replay record, and bounded cost, or when measured phase-barrier latency makes the hybrid operationally unsuitable.

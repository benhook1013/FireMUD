# ADR 0186: Volatile Combat Bookkeeping and Per-Tick HP Effects

## Status

Proposed - Pending Human Review

## Implementation Status

Not implemented. Combat currently writes one guarded durable HP effect per hit, and aggro, round and swing-timer state has no declared loss class.

## Canonical Design

- [Transaction Strategies](../system-architecture-transactions.md)
- [Tick System and Runtime Design](../system-architecture-ticks.md)
- [Entity Management Operations](../microservices/entity-management-service/operations.md)

## Decision Record

- Human review status: Pending
- Human review date: Not yet reviewed
- Human review disposition: Pending
- Review source: `AI-AUTHORED-PENDING`
- Decision date: 2026-11-18
- Primary capability: `GR-4.2` Combat and conflict resolution
- Affected capabilities: `GR-1.2`, `GR-1.3`, `GR-3`, `SF-2.2`, `SF-2.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-directed impact study with an adversarial review on 2026-11-18; the human owner agreed the direction, pending formal review

## Context

Under [ADR 0053](./adr-0053-command-atomicity-by-invariant-class.md), [ADR 0071](./adr-0071-durable-tick-commit-before-fenced-coordination-cleanup.md) and the damage guard in Entity Management, every hit writes an effect ledger row, a `tick_effect_guard` row and an HP update, because Entity Management cannot assume one HP mutation per entity per tick. Aggro, threat, round and swing-timer state has never been classified under [ADR 0058](./adr-0058-class-specific-redis-loss-outcomes.md) or [ADR 0072](./adr-0072-class-specific-timer-durability-and-recovery.md), so it defaults to durable. On small hosts this spends the write budget on per-hit history that nothing reads after a fight.

Making HP itself volatile was considered and rejected for now. It voids fights on every crash or deploy, lets mid-fight outcomes such as boss-phase loot fire twice, can re-create a spawn whose death is still being committed, and races player death against HP checkpoints.

## Decision

1. Combat AI bookkeeping is a declared volatile class: aggro and threat, target choice, round and turn order, swing timers, a bounded per-spawn damage-share log, and cooldowns that gate no durable outcome. Each feature declares which of its state is volatile. The state is held by the region executor and bound to `(tenantId, gameInstanceId, regionId, regionEpoch, executorFence)`; a new fence starts empty. Loss is allowed, and its only consequence is that NPCs pick targets again and rounds restart.
2. No durable effect may be triggered by, keyed on, or targeted at volatile state, and authoritative script handlers may not read it. Volatile data reaches durable state only when frozen into the request digest of an outcome whose identity is already durable, such as damage shares in an NPC death effect.
3. HP, mana, combat buffs, spawn lifecycle, death, loot, XP, gold, boss phases, quest flags and any timer that gates a durable outcome stay durable under the existing effect path. Mid-fight durable outcomes are one-shots keyed by spawn, trigger and ordinal.
4. The region executor combines each entity's damage and healing in a tick into one net HP effect owned by Entity Management. With at most one HP mutation per entity per tick, the per-entity `entity_tick_state` watermark is a valid guard for it.

## Consequences

- Combat writes drop from per hit to per fighter per tick, estimated at about 600 to 250 row writes per second for 50 fights at the 1000 ms tick. A load probe must confirm this.
- Crash and failover behavior for HP and outcomes is unchanged; after a failover an NPC may switch target.
- On acceptance, the tick design goal against in-service volatile state and the Entity Management damage example are amended to match.

## Alternatives Considered

### Durable Effect Per Hit

Rejected because it writes per-hit history with no lasting value and puts several commits on every hit.

### Durable Snapshots Every N Seconds

Rejected because it still needs durable spawn death to stop repeat loot and adds a fenced restore protocol.

### Volatile HP With Outcome Commits

Deferred. It would also need a voided command result in ADR 0016 and ADR 0053, fenced player HP checkpoints, a scripting read class and one-shot identity for every mid-fight outcome, for an estimated further 2x saving.

## Implementation and Proof Obligations

Proof covers one HP effect per entity per tick across replay and failover, rejection of a second HP mutation in the same tick, no influence of stale-fence volatile state on a successor, mid-fight one-shots firing once across crash and retry, no script handler reading volatile state, and a load probe comparing per-hit and per-tick HP at 1000 ms and 250 ms ticks that counts every write.

## Reversibility and Revisit Triggers

Revisit volatile HP if the load probe shows per-tick HP cannot hold the target tick cadence on the target host, or if a feature needs volatile state to drive a durable outcome.

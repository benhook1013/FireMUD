# ADR 0186: Volatile Combat Bookkeeping and Per-Tick HP Effects

## Status

Accepted

## Implementation Status

Not implemented. No combat damage path exists yet; under the existing rules it would write one guarded durable HP effect per hit, and aggro, round and swing-timer state has no declared loss class.

## Canonical Design

- [Transaction Strategies](../system-architecture-transactions.md)
- [Tick System and Runtime Design](../system-architecture-ticks.md)
- [Entity Management Operations](../microservices/entity-management-service/operations.md)

## Decision Record

- Human review status: Completed
- Human review date: 2026-11-18
- Human review disposition: Accepted
- Review source: `COMBAT-STATE-01`
- Decision date: 2026-11-18
- Decision key: `COMBAT-STATE-01`
- Primary capability: `GR-4.2` Combat and conflict resolution
- Affected capabilities: `GR-1.2`, `GR-1.3`, `GR-3`, `SF-2.2`, `SF-2.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-directed impact study with an adversarial review on 2026-11-18; the human owner accepted the design on 2026-11-18

## Context

Under [ADR 0053](./adr-0053-command-atomicity-by-invariant-class.md), [ADR 0071](./adr-0071-durable-tick-commit-before-fenced-coordination-cleanup.md) and the damage guard in Entity Management, every hit would write an effect ledger row, a `tick_effect_guard` row and an HP update, because Entity Management cannot assume one HP mutation per entity per tick. Aggro, threat, round and swing-timer state has never been classified under [ADR 0058](./adr-0058-class-specific-redis-loss-outcomes.md) or [ADR 0072](./adr-0072-class-specific-timer-durability-and-recovery.md), so it defaults to durable. On small hosts this spends the write budget on per-hit history that nothing reads after a fight.

Making HP itself volatile was considered and rejected for now. It voids fights on every crash or deploy, lets mid-fight outcomes such as HP-threshold loot fire twice, can re-create a spawn whose death is still being committed, and races player death against HP checkpoints.

## Decision

1. Combat AI bookkeeping is a declared volatile class: aggro and threat, target choice, round and turn order, swing timers, a bounded per-spawn damage-share log, and cooldowns that gate no durable outcome. Each feature declares which of its state is volatile. The state is held by the region executor and bound to `(tenantId, gameInstanceId, regionId, regionEpoch, executorFence)`; a new fence starts empty. Loss is allowed: NPCs pick targets again, rounds restart, and damage shares logged before the loss no longer count toward that NPC's death reward credit.
2. Volatile state may choose NPC actions (target and timing); their HP results enter only through the net HP effect in decision 4, whose identity comes from the tick, not from the volatile state. No other durable effect may be triggered by, keyed on, or targeted at volatile state, and authoritative script handlers may not read it. Volatile data reaches durable state only when frozen into the request digest of an outcome whose identity is already durable, such as damage shares in an NPC death effect. The frozen payload and digest are recorded with that outcome before submission, and a retry resubmits the recorded payload rather than rebuilding it from volatile state.
3. HP, mana, combat buffs, spawn lifecycle, death, loot, XP, gold, HP-threshold triggers, quest flags and any timer that gates a durable outcome other than combat HP stay durable under the existing effect path. Mid-fight durable outcomes are one-shots keyed by spawn, trigger and ordinal.
4. The region executor combines each entity's damage and healing in a tick into one net HP effect owned by Entity Management. That effect keeps its mutation `EffectId` ledger entry and one `tick_effect_guard` row, written in the same transaction as the HP update; the disposable `entity_tick_state` watermark alone does not give replay safety across runtime replacement. Like the death payload, the net HP request and digest are recorded before submission and a retry resubmits them. Any HP-threshold trigger is evaluated on the net end-of-tick HP, so a threshold crossed and healed back within one tick does not fire. A per-entity, per-tick uniqueness check alongside the effect guard rejects a second, distinct HP effect while still accepting a replay of the first. Once an NPC's action for a tick has committed, a replayed tick reuses it rather than choosing a new one.

## Consequences

- Combat writes drop from an effect ledger row, guard row and HP update per hit to the same three rows per fighter per tick. A load probe must measure the saving.
- Crash and failover behavior for HP and outcomes is unchanged; after a failover an NPC may switch target.
- The tick design goal against in-service volatile state and the Entity Management damage example are amended to match.

## Alternatives Considered

### Durable Effect Per Hit

Rejected because it writes per-hit history with no lasting value and puts several commits on every hit.

### Durable Snapshots Every N Seconds

Rejected because it still needs durable spawn death to stop repeat loot and adds a fenced restore protocol.

### Volatile HP With Outcome Commits

Deferred. It would also need a voided command result in ADR 0016 and ADR 0053, fenced player HP checkpoints, a scripting read class and one-shot identity for every mid-fight outcome, for an estimated further 2x saving.

## Implementation and Proof Obligations

Proof covers one HP effect per entity per tick across replay and failover, rejection of a second HP mutation in the same tick, no influence of stale-fence volatile state on a successor, mid-fight one-shots firing once across crash and retry, an NPC death retry after a lost response resubmitting the recorded payload and digest, each contributing command's own outcome remaining recoverable from a combined HP effect, a replayed tick reusing a committed NPC action, no script handler reading volatile state, and a load probe comparing per-hit and per-tick HP at 1000 ms and 250 ms ticks that counts every write.

## Reversibility and Revisit Triggers

Revisit volatile HP if the load probe shows per-tick HP cannot hold the target tick cadence on the target host, or if a feature needs volatile state to drive a durable outcome.

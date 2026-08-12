# ADR 0055: Durable Cross-Region Effects with Static Live Topology

## Status

Accepted

## Implementation Status

Current durable remote execution covers bounded payload families and partial recovery only. Durable cross-region follow-up coordination, complete epoch/fence handling, and the operator-controlled split/merge maintenance cutover with producer barriers, migration, rollback, and focused fault-injection proof remain target-state gaps.

## Canonical Design

- [Tick System and Runtime Design](../system-architecture-ticks.md)
- [Spatial and Ambient Effects Catalog](../system-architecture-spatial-and-ambient-effects-catalog.md)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-06`
- Primary capability: `GR-2.1` World topology, rooms, regions, and runtime instances
- Affected capabilities: `GR-1.3`, `GR-1.4`, `SF-2.3`, `AA-3.3`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `TICK-06`, including protocol validation and synchronous/no-cross-region/live-topology alternative passes
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `TICK-06`

## Context

Synchronous locks or transactions across tick regions would make network tail latency and partitions stall otherwise independent gameplay. Prohibiting cross-region effects entirely would expose implementation boundaries to players and constrain combat, parties, pursuit, scripts, and world design.

Durable asynchronous follow-ups preserve regional isolation but introduce pending, timeout, late-result, and epoch-change outcomes. Separately, the documented split/merge sequence is a disruptive maintenance cutover, not a proven live elasticity feature. Current durable remote execution covers bounded payload families, while live entity/queue/timer/effect migration and rollback are not implemented.

## Decision

### Cross-Region Effects

Cross-region gameplay uses durable asynchronous legs and never shared region locks.

- The origin commits its local required effects and one durable remote coordinator/follow-up identity.
- PostgreSQL follow-up rows are authority; Redis markers are bounded wake-up hints only.
- The target durably claims and executes under its current region epoch and executor fence using stable effect identity and idempotent results.
- Duplicate scheduling, claiming, execution, and result delivery converge to the same stored outcome.
- Origin tick commit means the remote leg was durably scheduled, not that the whole player command succeeded.
- Command status remains pending until the coordinator derives `SUCCESS`, a specifically permitted `PARTIAL`, or `FAILURE` under ADR 0053.
- Durable results are consumed before timeout evaluation. Feature contracts define whether a genuinely late result is ignored or reconciled; paired/conserved consequences cannot default to ignore.

Old-epoch follow-ups do not silently carry into a new epoch. They converge to an explicit abandoned/maintenance outcome unless dedicated tooling creates a new lineage-preserving request. Features that cannot tolerate abandonment use a stronger tick-adjacent workflow.

### Topology Boundary

Region topology is static while an active game instance is normally open. An unchanged region may be reassigned between executors under a new executor fence without changing its epoch.

Initial split/merge support is an operator-controlled maintenance cutover:

1. establish an admission barrier covering player front ends, automation, timers, and remote-follow-up producers;
2. freeze intake and ticks for affected regions;
3. drain and reconcile until a declared bounded deadline;
4. explicitly terminalize remaining commands, effects, coordinators, follow-ups, and results rather than waiting indefinitely;
5. durably install the new topology mapping and bump affected region epochs;
6. rebuild Redis coordination from durable state rather than moving live keys;
7. either create lineage-linked new follow-ups for the new mapping or abandon old rows with an explicit reason; and
8. validate health and reopen.

The maintenance record exposes downtime, terminalization counts, old/new mapping generations, and affected player outcomes. Accepted-but-unstaged work and old-epoch work receive explicit not-applied or maintenance-canceled outcomes.

Automatic live split/merge is not an initial capability. It requires a new acceptance decision after stale-router rejection, complete producer barriers, queue/timer/effect migration, follow-up lineage, rollback, and fault-injected recovery are proven.

## Consequences

- Fixed regions execute and fail independently and can move between workers.
- Cross-region actions may be slower and expose pending or explicit partial/failure outcomes.
- Maintenance split/merge causes visible downtime and may terminalize legitimate in-flight work.
- FireMUD does not initially provide seamless hotspot repartitioning; a single oversized region remains a scale ceiling until maintenance or a future live-topology capability.
- Durable follow-up storage, scans, claims, result retention, admission shedding, and reconciliation add database and operational cost.

## Alternatives Considered

### Synchronous Global Coordination

Rejected because it couples region progress and availability to every participant and creates a global throughput/failure boundary.

### No Cross-Region Gameplay

Rejected because region boundaries would become visible product restrictions and make future repartitioning gameplay-breaking.

### Implement Live Split/Merge Immediately

Rejected because migration and rollback proof does not exist across all producers and work families. A defect would create duplicate or silently lost gameplay state.

## Implementation and Proof Obligations

Current remote execution proves only bounded families and partial recovery. Each supported payload must prove lost hints, duplicate scheduling/claim/result, crash after claim, target delay, timeout race, epoch change, and coordinator-derived player outcome. Maintenance topology proof must cover every producer barrier, bounded drain expiry, terminalization, stale routing, mapping installation, epoch order, follow-up lineage/abandonment, Redis rebuild, failed cutover recovery, and controlled reopen.

## Reversibility and Revisit Triggers

Durable follow-up identities and mapping generations provide a migration path to live topology. Revisit when a fixed region cannot meet its measured tick budget, maintenance downtime becomes materially harmful, and complete topology migration and rollback fault-injection evidence exists.

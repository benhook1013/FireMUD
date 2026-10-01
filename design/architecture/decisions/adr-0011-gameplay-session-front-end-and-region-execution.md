# ADR 0011: Gameplay Session Front-End and Region Execution Routing

## Status

Superseded by [ADR 0170](./adr-0170-fenced-command-forwarding-and-authoritative-region-transition.md)

## Supersession

- Replacement ADR: [ADR 0170](./adr-0170-fenced-command-forwarding-and-authoritative-region-transition.md)

## Context

FireMUD already makes two high-level decisions:

- Spring Cloud Gateway does not own a gameplay shard-routing plane.
- Gameplay execution is partitioned internally inside the Game Session layer by `<tenantId, gameInstanceId, regionId>` leases.

Those decisions leave an important internal question open: when a player is connected to a stable `/ws/game/**` session surface but gameplay work for that player's current region is owned by a different Game Session pod, which component owns the socket and which component owns execution?

Without an explicit answer, docs and implementations risk drifting toward incompatible models:

- socket affinity and region affinity being treated as the same thing,
- silent cross-pod forwarding without fencing rules,
- or forced reconnects on ordinary lease rebalancing.

## Historical Rationale

This ADR separated stable socket ownership from region execution ownership so ordinary lease movement would not force player reconnects, while rejecting silent, unfenced cross-pod mutation. The detailed decision has been superseded: [ADR 0170](./adr-0170-fenced-command-forwarding-and-authoritative-region-transition.md) owns the current contract for authoritative region transitions, owner forwarding, identity, fences, retries, and failure handling. The former mandatory forwarding details in this ADR no longer define a current contract.

## Historical Non-Goals

- This ADR does not introduce a Gateway-owned gameplay shard-routing plane.
- This ADR does not define multi-cluster gameplay execution.
- This ADR does not introduce a client-visible shard-handoff close category.

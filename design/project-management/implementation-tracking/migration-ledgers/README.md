# Migration Coverage Ledgers

The [global source allocation map](./SOURCE_ALLOCATION_MAP.md) is the refactor source of truth. Each tracker ledger is the loss-control record for completed migration work, mapping its allocated vertical-slice ranges to their destination or an explicit non-migration disposition under the [migration protocol](../MIGRATION_PROTOCOL.md).

Keep source and destination open side by side while filling a row. The legacy slice files remain the delivery-history source of truth until the ledger and independent review confirm that every current fact is represented or explicitly retained.

## Ledgers

- [Global Source Allocation Map](./SOURCE_ALLOCATION_MAP.md)

- [Player Access and Session](./player-access-and-session.md)
- [Player Experience, Commands, and Communication](./player-experience-commands-and-communication.md)
- [Gameplay Rules, Entities, and Effects](./gameplay-rules-entities-and-effects.md)
- [World Runtime and Movement](./world-runtime-and-movement.md)
- [Game Authoring, Publishing, and Activation](./game-authoring-publishing-and-activation.md)
- [Realm Routing and Playable State](./realm-routing-and-playable-state.md)
- [Automation and Scheduler Runtime](./automation-and-scheduler-runtime.md)
- [Game Session Runtime and Tick Coordination](./game-session-runtime-and-tick-coordination.md)
- [Shared Runtime, Service Contracts, and Persistence](./shared-runtime-contracts-and-persistence.md)
- [Platform Operations and Delivery](./platform-operations-and-delivery.md)

# Migration Coverage Ledgers

Each tracker ledger is the loss-control record for its migration. It maps exact vertical-slice source ranges to their tracker destination or an explicit non-migration disposition under the [migration protocol](../MIGRATION_PROTOCOL.md).

Keep source and destination open side by side while filling a row. The legacy slice files remain the delivery-history source of truth until the ledger and independent review confirm that every current fact is represented or explicitly retained.

## Ledgers

- [Player Access and Session](./player-access-and-session.md)
- [Player Experience, Commands, and Communication](./player-experience-commands-and-communication.md)
- [Gameplay Rules, Entities, and Effects](./gameplay-rules-entities-and-effects.md)
- [World Runtime and Movement](./world-runtime-and-movement.md)
- [Game Authoring, Publishing, and Activation](./game-authoring-publishing-and-activation.md)
- [Realm Routing and Playable State](./realm-routing-and-playable-state.md)
- [Automation and Scheduler Runtime](./automation-and-scheduler-runtime.md)
- [Platform Operations and Delivery](./platform-operations-and-delivery.md)

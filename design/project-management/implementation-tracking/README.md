# Domain Implementation Tracking

These files are the current domain-oriented implementation tracking surface. They do not define product or architecture target state; canonical design remains under [`design/architecture`](../../architecture/README.md).

During the migration from vertical slices, each tracker will contain only:

- canonical design-source links;
- verified live implementation;
- active implementation gaps;
- high-level items still to discuss; and
- links to relevant legacy delivery records.

Existing files under [`../vertical-slices`](../vertical-slices/README.md) remain historical delivery records until their facts have been reconciled into the appropriate tracker. Do not delete, rename, or treat those records as migrated solely because this scaffold exists.

Follow the [migration protocol](./MIGRATION_PROTOCOL.md) and the per-domain [coverage ledgers](./migration-ledgers/README.md). Each allocated legacy source range must be migrated or have an explicit retained/superseded disposition before a tracker can claim migration completion.

## Trackers

- [Player Access and Session](./player-access-and-session.md)
- [Player Experience, Commands, and Communication](./player-experience-commands-and-communication.md)
- [Gameplay Rules, Entities, and Effects](./gameplay-rules-entities-and-effects.md)
- [World Runtime and Movement](./world-runtime-and-movement.md)
- [Game Authoring, Publishing, and Activation](./game-authoring-publishing-and-activation.md)
- [Realm Routing and Playable State](./realm-routing-and-playable-state.md)
- [Automation and Scheduler Runtime](./automation-and-scheduler-runtime.md)
- [Platform Operations and Delivery](./platform-operations-and-delivery.md)

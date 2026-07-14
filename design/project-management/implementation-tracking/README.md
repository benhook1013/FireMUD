# Domain Implementation Tracking

These files are the current domain-oriented implementation tracking surface. They do not define product or architecture target state; canonical design remains under [`design/architecture`](../../architecture/README.md).

Each tracker is scan-first: its `Implementation Record Index` names the domain capabilities, source-declared state, and direct evidence link before the detailed material. The tracker then retains exact source evidence in the same file, so readers can audit a claim without searching a separate ledger or depending on a remembered slice number.

During migration, each tracker contains:

- canonical design-source links;
- a named implementation-record index;
- source-backed implementation state, active gaps, and items still to discuss; and
- lossless source evidence linked to the relevant legacy delivery records.

Existing files under [`../vertical-slices`](../vertical-slices/README.md) remain historical delivery records. Do not delete, rename, or rewrite them as part of this refactor. The implementation trackers preserve exact allocated source ranges and the migration validator verifies that preservation.

Follow the [migration protocol](./MIGRATION_PROTOCOL.md) and the per-domain [coverage ledgers](./migration-ledgers/README.md). Each allocated legacy source range must be migrated or have an explicit retained/superseded disposition before a tracker can claim migration completion.

## Trackers

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

# Domain Implementation Tracking

These files are the current domain-oriented implementation tracking surface. They do not define product or architecture target state; canonical design remains under [`design/architecture`](../../architecture/README.md).

Each tracker is scan-first. Its `Consolidated Implementation Record` describes the current domain capability, authority boundaries, active gaps, and future decisions in domain language. The `Implementation Record Index` maps those claims to source-declared delivery records, and the source-evidence appendix retains exact transposed material in the same file for lossless audit.

Each tracker contains:

- canonical design-source links;
- a reader-facing consolidated implementation record, active gaps, and items still to discuss;
- a named implementation-record index; and
- lossless source evidence linked to the relevant legacy delivery records.

Existing files under [`../vertical-slices`](../vertical-slices/README.md) remain historical delivery records. Do not delete, rename, or rewrite them as part of this refactor. The implementation trackers preserve exact allocated source ranges and the migration validator verifies that preservation.

Follow the [migration protocol](./MIGRATION_PROTOCOL.md) and the per-domain [coverage ledgers](./migration-ledgers/README.md). The mechanical evidence layer and semantic consolidation layer are both required before a tracker can claim migration completion; Spark then audits coverage and semantic fidelity.

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

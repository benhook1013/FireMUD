# Domain Implementation Tracking

These files are the current domain-oriented implementation tracking surface. They do not define product or architecture target state; canonical design remains under [`design/architecture`](../../architecture/README.md).

Each tracker is scan-first. Its `Consolidated Implementation Record` describes the current domain capability, authority boundaries, active gaps, and future decisions in domain language. During the migration audit, the `Implementation Record Index` and source-evidence appendix map those claims to exact legacy material so reviewers can prove that consolidation is lossless.

Each tracker contains:

- canonical design-source links;
- a reader-facing consolidated implementation record, active gaps, and items still to discuss;
- a named implementation-record index; and
- lossless source evidence linked to the relevant legacy delivery records.

Files under [`../vertical-slices`](../vertical-slices/README.md), the generated evidence appendices, and migration ledgers are temporary audit inputs. Keep them unchanged until independent coverage and semantic-fidelity review is complete. Once all findings are resolved, remove that temporary material and repair links so these domain trackers are the sole implementation-tracking source.

Follow the [migration protocol](./MIGRATION_PROTOCOL.md) and the per-domain [coverage ledgers](./migration-ledgers/README.md) while migration is active. The mechanical evidence layer and exhaustive semantic consolidation are required before Spark audits coverage and fidelity; migration completes only after audit findings are fixed and the temporary evidence/source scaffolding is removed.

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

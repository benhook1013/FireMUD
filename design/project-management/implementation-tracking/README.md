# Domain Implementation Tracking

These files are the permanent domain-oriented implementation tracking surface. They do not define product or architecture target state; canonical design remains under [`design/architecture`](../../architecture/README.md).

Each tracker is scan-first. Its `Consolidated Implementation Record` describes current domain capabilities, authority boundaries, active gaps, and future decisions in reader-facing domain language.

Each tracker contains:

- canonical design-source links;
- a consolidated implementation record organized by capability and ownership;
- validation and operational proof for the implemented boundaries;
- active gaps and follow-up work; and
- decisions and service-map context where the domain boundary requires them.

Use the relevant tracker as the reader-facing account of implementation status. Keep its status, implementation record, validation, gaps, decisions, and service-map sections aligned when a domain boundary changes, while keeping target-state design in the canonical architecture documents.

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

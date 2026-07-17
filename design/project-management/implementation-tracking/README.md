# Domain Implementation Tracking

These files are the permanent domain-oriented implementation tracking surface. They do not define product or architecture target state; canonical design remains under [`design/architecture`](../../architecture/README.md). The [Capability Allocation](./capability-allocation.md) assigns every leaf in the [Product Capability Taxonomy](../../architecture/product-capability-taxonomy.md) to exactly one primary tracker and records secondary handoffs.

Each tracker is scan-first. Its `Consolidated Implementation Record` describes current domain capabilities, authority boundaries, active gaps, and future decisions in reader-facing domain language.

Each tracker contains:

- canonical design-source links;
- a consolidated implementation record organized by capability and ownership;
- validation and operational proof for the implemented boundaries;
- active gaps and follow-up work; and
- decisions and service-map context where the domain boundary requires them.

Use the relevant tracker as the reader-facing account of implementation status. Keep its status, implementation record, validation, gaps, decisions, and service-map sections aligned when a domain boundary changes, while keeping target-state design in the canonical architecture documents.

## Capability Status Contract

Each tracker contains one `Capability Status` row for every capability it primarily owns. Existing narrative records remain useful context, but the capability rows are the complete scan-first status surface.

Implementation states:

- `implemented`: the complete currently designed capability boundary exists in production code and contracts;
- `partial`: a bounded implementation exists but designed behavior remains missing;
- `not-implemented`: no credible production implementation of the designed capability exists;
- `design-unresolved`: competing or insufficient canonical target states prevent implementation classification; and
- `not-applicable`: the capability is intentionally outside this product boundary, with a recorded rationale.

Verification states:

- `proven`: current executable proof covers the claimed implemented boundary;
- `audited`: implementation and tests were inspected, but current executable proof was not established for the whole claimed boundary;
- `unverified`: no adequate proof or completed audit supports the claim;
- `drift-found`: proof or inspection contradicts the tracker/design claim; and
- `not-applicable`: no verification is required because implementation is intentionally not applicable.

Every row links canonical design, names concrete production anchors, names focused proof anchors, records the most relevant secondary tracker handoffs, and states the remaining gap or decision. Evidence targets must be repository-local and resolvable: design anchors stay under `design/architecture`, production anchors stay outside test-only and documentation-only surfaces, and executable proof anchors target tests or canonical validation/CI/smoke tooling. The self-validating `dev-tools/tests/architecture-doc-contracts.sh` may also appear as the implementation anchor for the verification capability it enforces. An `audited` row may additionally link a local `README.md` or `package.json` only when the surrounding text explicitly records that focused executable proof is absent; that link is audit context, not proof. The [Capability Allocation](./capability-allocation.md) is the exhaustive handoff ledger; status rows may name the smaller operational subset needed to understand that capability. `implemented` does not imply `proven`, and a historical broad test run does not substitute for focused proof.

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

# ADR 0027: Single Realm Admission Target

## Status

Accepted

## Implementation Status

Current routing has one required target and a read-then-write expected-version check, but cannot represent `CLOSED`, uses a non-tenant-qualified uniqueness constraint, lacks database-level CAS, records prepared-cutover execution after the pointer transaction, and does not implement the accepted bounded source-drain lifecycle. Focused sequential routing proof is not concurrent CAS or crash-boundary proof.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-3.3` Runtime target identity, admission pointers, and routing freshness
- Affected capabilities: `AA-3.1`, `AA-3.2`, `AR-3.2`, `GR-2.1`, `GR-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `TENANT-01`

## Context

A player-facing realm such as `production` is a stable logical destination. A `gameInstanceId` identifies one concrete running world timeline, not a Game Session pod. FireMUD can run multiple pods and distribute fenced region execution inside one game instance without presenting players with separate copies of the world.

The prior design correctly routed each realm to one Game Session-owned instance, but described every player-addressable realm as always having exactly one target. That cannot represent deliberate closure or maintenance cleanly. It also did not separate admission routing from display metadata or from the continuing authority of an already admitted session.

## Decision

### Zero Or One Admission Target

- Each realm has one durable routing record keyed by `{tenantId, worldSlug, realmSlug}` and owned solely by Game Session.
- The routing state is either `OPEN(admissibleGameInstanceId)` or `CLOSED`. An open realm has exactly one admission target; a closed realm has none.
- `CLOSED` is an ordinary explicit product/operational state and produces a stable realm-unavailable outcome. Missing, malformed, or ambiguous routing state remains `ADMISSION_POINTER_UNAVAILABLE` and is treated as an authority failure rather than as closure.
- Clients select stable world and realm identifiers and never select a raw `gameInstanceId`, version, or member of an instance pool.
- Several realms may intentionally exist for separate worlds, shards, playtests, canaries, or isolated state. They remain separately player-addressable instead of being hidden behind one realm.

### Routing And Catalog Revisions

- `pointerVersion` is a monotonic admission-routing CAS version. It advances when the realm changes between `OPEN` and `CLOSED`, changes its target instance, or changes execution-namespace facts that make the admitted target materially different.
- Display names, descriptive metadata, visibility presentation, and other catalog-only fields use a separate monotonic catalog revision. Catalog edits must not masquerade as a runtime-route change or invalidate an active gameplay binding.
- New discovery selections, public-join policy checks, connect-token issuance, `PLAY`, and reconnect validate the current catalog/admission facts appropriate to their operation. Stale selection state returns a bounded refresh/reselection outcome rather than falling back to a guessed target.

### Atomic Mutation And Cutover

- Creating a route uses the equivalent of expected version `0`; every change to an existing route requires its current positive `pointerVersion`.
- The database mutation performs a real conditional write against `{tenantId, worldSlug, realmSlug, pointerVersion}`. A prior read followed by an unconditional row update is not compare-and-set.
- Pointer state, the append-only audit event, and the idempotent control-plane request outcome are one transaction boundary when they share the Game Session database. A replacement cutover that changes `gameInstanceId` additionally commits the applicable prepared-cutover execution state in that same transaction; ordinary `OPEN` or `CLOSED` pointer updates do not require a `prepared_version_upgrade`. The current target requires every applicable record to commit or roll back together. If a later deployment separates those stores, this ADR does not silently substitute an “equivalent” protocol: a new accepted architecture decision must first define and prove the replacement recovery, retry, and visibility semantics.
- A closure or replacement cutover that leaves source sessions draining also creates a durable drain-enforcement work item in that transaction. The work item records the source drain identity, source instance, absolute deadline, and desired lifecycle actions. A Game Session reconciler and startup-recovery path claim those items idempotently, retry notice delivery, socket closure, command fencing, and `InstanceTermination` after crashes, and retain the item until the persisted source lifecycle reaches its terminal state. A committed pointer or drain record is not evidence that those runtime effects already occurred.
- A replacement instance remains non-admissible until preparation and compatibility checks pass. Cutover atomically moves `OPEN(source)` to `OPEN(target)`; failure leaves the source as the sole target. Stopping a realm without a replacement first moves it to `CLOSED` and persists the same source drain identity, source instance, absolute deadline, and lifecycle state before the old runtime drains; it does not use an untracked shutdown path. A zero-duration policy sets the persisted deadline at that closure/cutover commit and immediately applies the same notice, socket-closure, command-fence, and idempotent `STOPPING` transition as any other expired drain.

### Existing Sessions And Scaling

- The admission pointer governs new or renewed gameplay bindings. It is not routine per-action authorization for a session already bound to a concrete instance.
- After cutover, new `PLAY` and reconnect flows use the new target. The cutover transaction persists a unique `sourceDrainId`, the source instance, and an absolute `sourceDrainDeadlineAt` resolved from `firemud.game-session.cutover-drain.duration-ms`; closure without replacement persists the same fields and uses the same bounded drain lifecycle. The platform default and hard maximum are five minutes; tenant/game settings may shorten the duration or set it to zero but cannot extend it. A zero-duration deadline is immediate, not an exemption from persistence or drain enforcement.
- Existing connected sessions may continue on the source only before the persisted deadline. The source may terminate early when no sessions remain. At the deadline Game Session sends one bounded update notice, closes remaining source sockets, rejects further source commands through the instance lifecycle fence, and idempotently transitions the source to `STOPPING` through `InstanceTermination`; the pointer change alone does not perform a database lookup or eject a player on their next action.
- Source-session commands remain fenced by that source instance's lifecycle and region ownership. Hard account, membership, moderation, or security revocation remains independently enforceable and is not extended by the drain rule.
- Horizontal capacity for one shared world comes from Game Session pod scaling, region partitioning, and fenced lease rebalancing within the same `gameInstanceId`. It does not come from randomly placing players into independent game instances behind one realm.

## Consequences

- Players entering one realm share one explicit world timeline instead of being silently divided among copies.
- Closed and maintenance states are representable without retaining a misleading pointer to a stopped instance.
- Display/catalog edits do not interrupt active gameplay.
- Cutover has an explicit bounded period in which existing sessions may remain on the draining source while new or reconnected sessions use the target.
- True atomic CAS and cutover bookkeeping add control-plane implementation and concurrency proof, but no cryptography, distributed lookup, or routine per-action routing cost.
- A single realm cannot provide matchmaking-style placement across independent world copies. Such a product requires a later explicit placement and cross-instance-state decision.

## Alternatives Considered

### Realm Points To An Instance Pool

A placement service could choose among several compatible game instances for each player. This supports matchmaking, geographic copies, canaries, or population scaling, but current instance boundaries also divide world state, presence, commands, automation, and reconnect identity. A credible pool therefore requires sticky placement, party co-location, health/capacity authority, cross-instance communication, migration, and shared-state or replication contracts.

### Resolve The Latest Running Version

Inferring a target from the newest or latest-active instance reduces explicit control-plane state but makes concurrent launch, rollback, partial failure, and version readiness ambiguous. It also allows an operational ordering accident to change player routing.

### Client-Selected Instance

Exposing raw instance IDs makes routing explicit to the client but leaks replaceable internals, creates stale bookmarks, and lets clients attempt incompatible or non-admissible targets.

## Implementation and Proof Obligations

- Add explicit `OPEN`/`CLOSED` routing state and a tenant-qualified uniqueness constraint.
- Implement database-level CAS with required expected versions and prove concurrent writers yield one winner and strictly increasing committed versions.
- Atomically persist pointer state, audit, request replay, and the applicable drain-enforcement work item in one transaction, plus prepared-cutover execution for a replacement cutover that changes `gameInstanceId`, including crash/retry proof at every applicable boundary. Prove reconciler and startup recovery idempotency through notice delivery, socket closure, command fencing, and terminal `InstanceTermination`. If those records cannot share a transaction in a future topology, stop and obtain a new accepted decision before implementing or claiming the split-store cutover contract.
- Separate catalog revisioning from admission routing and prove display-only edits do not invalidate connect or active gameplay state unnecessarily.
- Remove pointer-currentness checks from routine action authority for an already admitted binding; prove cutover admits new/reconnecting players only to the target while source sessions end through the bounded drain.
- Persist and prove the resolved drain policy, unique drain identity, absolute deadline, early-empty completion, deadline enforcement, socket closure, command rejection, and idempotent source termination.
- Prove closing a realm blocks new admission before source draining and distinguishes explicit closure from corrupt/unavailable pointer authority.

## Required Documentation Alignment

- [Multi-tenancy](../system-architecture-multi-tenancy.md)
- [Runtime versioning](../system-architecture-versioning-runtime.md)
- [Session behavior](../system-architecture-session-behavior.md)
- [Creator journeys](../user-journeys-creators.md)
- [Game Session API contracts](../microservices/game-session-service/api-contracts.md)
- [Realm routing and playable state tracker](../../project-management/implementation-tracking/realm-routing-and-playable-state.md)

## Reversibility and Revisit Triggers

The player-facing realm selector remains stable, so a future placement model would not require changing ordinary client addressing. Internally, `gameInstanceId` is embedded throughout session, world-state, presence, command, automation, and reconnect authority, so instance pooling would still be a major runtime migration. Revisit only when a concrete matchmaking, geographic, or population-scaling requirement cannot be met through region partitioning or explicit separate realms.

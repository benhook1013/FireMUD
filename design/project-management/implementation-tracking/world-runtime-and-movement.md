# World Runtime and Movement

## Current Status

This tracker is the current implementation record for world runtime, room reads, movement, lifecycle orchestration, and Draft topology mutation. The source appendix is retained unchanged for audit; it is not required to discover implementation facts in the sections above. Canonical target-state design remains under [design/architecture](../../architecture/README.md).

## Implementation Record Index

Use this index to locate the current domain capability. The detailed evidence preserves every allocated legacy source line and is intentionally kept in the same document for comparison.

| Capability and ownership focus | Source-declared status | Source range | Evidence |
| --- | --- | --- | --- |
| [Movement and Topology Settings Vertical Slice Task List](../vertical-slices/02.12-task-list-movement-and-topology-settings-vertical-slice.md) - Runtime movement and topology settings | first settings boundary implemented | 1-19, 31-55, 65-69 | [source evidence](#source-02-12-task-list-movement-and-topology-settings-vertical-slice-1-19-31-55-65-69) |
| [`02.18.15` World and Session Lifecycle Concurrency Hardening](../vertical-slices/02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md) - World lifecycle transaction boundary and termination ownership | complete | 25-27, 44 | [source evidence](#source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-25-27-44) |
| [World Lifecycle Temporal Migration Vertical Slice](../vertical-slices/02.20.2-task-list-world-lifecycle-temporal-migration-vertical-slice.md) - Audited primary runtime or service owner | implemented at the current world lifecycle boundary | 1-26 | [source evidence](#source-02-20-2-task-list-world-lifecycle-temporal-migration-vertical-slice-1-26) |
| [Data-Driven LOOK Vertical Slice Task List](../vertical-slices/03-task-list-data-driven-look-vertical-slice.md) - Authoritative room snapshot and exit topology | core flow live; later overlays remain separate work | 1-40 | [source evidence](#source-03-task-list-data-driven-look-vertical-slice-1-40) |
| [`03.1` Same-Fence LOOK Read Consistency](../vertical-slices/03.1-task-list-same-fence-look-read-consistency-vertical-slice.md) - Same-fence room-read consistency | complete | 1-50 | [source evidence](#source-03-1-task-list-same-fence-look-read-consistency-vertical-slice-1-50) |
| [03.2 Task List: Runtime Room Identity Contract Convergence Vertical Slice](../vertical-slices/03.2-task-list-runtime-room-identity-contract-convergence-vertical-slice.md) - Canonical runtime room identity and world mapping | implementation-complete at the current touched branch boundary; reopen only if fresh proof exposes a remaining public/runtime room identity drift seam | 1-218 | [source evidence](#source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218) |
| [Movement Vertical Slice Task List](../vertical-slices/05-task-list-movement-vertical-slice.md) - Movement topology and authoritative location mutation | end-to-end directional movement complete; broader travel remains later work | 1-67 | [source evidence](#source-05-task-list-movement-vertical-slice-1-67) |
| [World Design Mutation API Surface Vertical Slice](../vertical-slices/08.5-task-list-world-design-mutation-api-surface-vertical-slice.md) - World-owned draft topology and mutation enforcement | complete | 1-9, 11-23, 27-48, 52-61, 63-84 | [source evidence](#source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84) |

## Canonical Design Sources

- [World Management service architecture](../../architecture/microservices/world-management-service/README.md), [API contracts](../../architecture/microservices/world-management-service/api-contracts.md), [runtime and data](../../architecture/microservices/world-management-service/runtime-and-data.md), and [world creation workflow](../../architecture/microservices/world-management-service/world-creation-workflow.md) define world ownership, lifecycle, and topology boundaries.
- [Game Logic service architecture](../../architecture/microservices/game-logic-service/README.md) and [API contracts](../../architecture/microservices/game-logic-service/api-contracts.md) define authoritative gameplay aggregation over world and entity reads.
- [Game Session protocols](../../architecture/microservices/game-session-service/protocols.md) and [runtime and data](../../architecture/microservices/game-session-service/runtime-and-data.md) define player command ingress, session binding, and client-facing room refresh.
- [Entity Management service architecture](../../architecture/microservices/entity-management-service/README.md) defines room-scoped entity and containment reads.
- The [identifier glossary](../../architecture/system-architecture-identifier-glossary.md) defines the distinction between runtime room identity and World Management storage identity.
- [System settings model](../../architecture/system-architecture-settings-model.md) remains the target-state authority for configurable movement and topology policy.

## Consolidated Implementation Record

### Runtime Room Identity and Boundary Validation

- `roomInstanceId` is the canonical opaque runtime identity at cross-service boundaries. The current canonical wire examples use `R-<roomInstanceRowId>`, but consumers must not infer World Management storage semantics from the token or treat it as a database key.
- `RoomInstanceRef` and the room-scoped gameplay context carry the runtime identity as text. World Management owns the mapping from that identity to its internal numeric room row and record references.
- World Management keeps internal identity names distinct: runtime room values use `roomInstanceId`, room table storage uses `roomInstanceRowId` and `room_instance_row_id`, and exit foreign keys use `from_room_instance_record_id` and `to_room_instance_record_id`. The runtime room bridge resolves exits using the fetched room row key rather than assuming the public runtime value equals the row key.
- World Management has one runtime-room codec boundary. Public room reads require canonical `R-...` values, emit canonical runtime ids for the current room, exit targets, and the `worldSnapshotId` fence, and fail closed on malformed, bare numeric, or legacy `room-...` values. Its runtime read DTO and gRPC payload are distinct from design-time room DTOs; `GetRoom` returns typed runtime state rather than an opaque `room_json` shim.
- The correctness-critical World Management room snapshot cache is keyed by `room:<tenantId>:<gameInstanceId>:R-<roomInstanceRowId>`, while internal repository and persistence seams retain row/record terminology.
- Game Session's `game.logic.default-room-id` defaults to `R-1021` in both bound properties and service configuration, keeping fresh `PLAY`, `LOOK`, movement, and communication fallback paths on the canonical runtime identity family.
- Shared runtime-room readers are used by World Management, Game Logic, Entity Management, and Game Session. They reject malformed or legacy storage-shaped values before downstream calls, attestation issuance, replay, persistence, or transport dispatch. This covers LOOK, movement, communication, room-ground inventory, session routing, movement idempotency, and automation enter/leave event publication.
- Gameplay-session and internal-probe attestation issuance and claim validation reject legacy room ids. Persisted legacy gameplay bindings are treated as stale input and cleared by session-routing normalization; they are not silently upgraded. Game Logic maps malformed-room rejection at the `ResolveLook` envelope to `INVALID_ARGUMENT` rather than `LOOK_UNAVAILABLE`.

### Authoritative Room Reads and LOOK

- World Management is authoritative for the runtime room snapshot, room metadata, descriptions, and exit topology through its `GetRoom`/`GetRoomSnapshot` runtime read surfaces. Entity Management supplies visible occupants and room-ground state from the room-attached ground container; nested container contents are not part of the base room view.
- Game Logic owns the structured `LookResult`. Its composition includes room/world snapshot data, exits, visible occupants, and visible room-ground items. The structured result is the shared source for `LOOK` and `QUICKLOOK`; `QUICKLOOK` omits room-description prose for a faster redraw.
- World Management and Entity Management stamp LOOK-related responses with the same practical read fence, currently `tenantId:gameInstanceId:roomInstanceId`. Game Logic requires both fences to be present and equal, propagates the component fences into `LookResult`, and returns an explicit read-fence error for missing or mixed fences instead of silently composing inconsistent state. The current `lookSnapshotId` still copies the World component identity rather than combining `worldSnapshotId` and `entitySnapshotId` into the canonical final LOOK identity.
- `LOOK` requires an authenticated gameplay session. Game Session owns command ingress, transcript replay, final text rendering, and prompt emission; pre-login gameplay-like requests return `ERROR LOGIN_REQUIRED`, while post-login requests made before `PLAY` return `ERROR PLAY_REQUIRED`.
- Reconnect restoration emits bounded transcript context, then a fresh authoritative `LOOK`, then a fresh prompt. Successful movement likewise performs a fresh destination `LOOK`; rendered-room cache content is never the authority for either refresh.
- The current renderer defaults are surfaced as settings for color mode, brief mode, prompt enablement, reconnect prompt emission, and prompt coalescing: `firemud.presentation.default-color-mode`, `firemud.presentation.brief-enabled-by-default`, `firemud.presentation.prompt.enabled`, `firemud.presentation.prompt.emit-after-reconnect-restore`, and `firemud.presentation.prompt.coalesce-window-ms`.

### Movement and Location Mutation

- The directional `MOVE`/`GO` loop is live over both WebSocket and Telnet. Game Session accepts the authenticated gameplay command, Game Logic normalizes and resolves the requested direction against authoritative World Management exit data, and Game Session updates its session room binding before emitting the destination `LOOK`. Authoritative World Management character/session location mutation remains an open seam; the current runtime path resolves the destination through World data but does not persist the transition through a World Management mutation contract.
- The movement result is structured and includes enough destination context for Game Session to refresh without guessing from cached state. Invalid directions, missing exits or rooms, and downstream failures remain stable application errors and do not disconnect the client.
- Game Logic has a named `MovementTravelService` pathfinding/travel substrate, with World Management supplying authoritative geometry and versioned navmesh/path-graph artifacts. The live command still uses existing primitives only for the directional step; broader travel and full pathfinding behavior are not implemented. Durable movement idempotency validates expected, current, replayed, and destination room state against the canonical runtime-room contract before replay or write-through.
- Game Session records `gamesession.command.move.invocations` and `gamesession.command.move.failures` with high-level error categories, and the cross-service path is covered for success, invalid exit, unauthenticated input, backend failure, and reconnect after movement.

### Movement and Topology Settings

- Game Session owns the first settings seam through `MovementProperties` for post-move redraw and `WorldTopologyProperties` for typed topology configuration. The canonical groups are `movement.postMoveView`, `worldTopology.scopeModel`, and `worldTopology.regionBehavior`.
- Generation-ready configuration metadata and documented defaults are present for `firemud.movement.post-move-look-enabled`, `firemud.world-topology.scope-model`, and `firemud.world-topology.regions-enabled`. Games with or without explicit regions, areas, or maps express those capabilities through settings rather than hard-coded fallback branches.
- The settings surface gives future scope-sensitive communication such as `SHOUT` a canonical home, but does not implement that command or otherwise change movement ownership.

### World Lifecycle

- World creation, activation, failure, termination, and the retry/repair lifecycle use the canonical `world-lifecycle` Temporal workflow when Temporal is enabled. The workflow owns prepare, activate, fail, and terminate orchestration and follows the shared `common-temporal` workflow identity and task-queue conventions.
- When Temporal is not enabled, `WorldInstanceActivationService` delegates to the same extracted command service so local non-Temporal application contexts still boot and use the same lifecycle commands.
- `GetWorldInstanceLifecycle` exposes deterministic workflow identity and Temporal execution status to operators through the control-plane read surface. The existing class-A pre-activation versus class-B gameplay boundary is preserved; gameplay runtime, tick execution, and live in-world mutation remain outside the Temporal path.
- Lifecycle orchestration preserves business idempotency keys and activation fencing, and fences current epoch/version/lifecycle validation, activation validation, and remote cleanup ordering. Termination stages local state around remote cleanup, but prepare and activate still perform blocking Game Design attestation calls inside transactional command methods; the no-open-transaction-across-blocking-gRPC invariant remains incomplete there.
- World Management owns world-change notification production for Game Session and Automation, while Game Session owns gameplay-session/tick sharding, leases, and coordination. The current notification path and broader shard/load-balancing behavior are not a completed substitute for those ownership boundaries.

### Draft World Topology, Generation, and Mutation Authority

- World Management is the canonical owner of version-scoped Draft mutation through typed `ApplyWorldDesignMutation` operations for regions, zones, rooms, room exits, generation rules, world-entity spawn bindings, and generated world subtrees. Runtime instance mutation and live Published-version editing are not routed through this design-time API.
- Each successful Draft mutation uses an applied-revision ledger or equivalent guard in the same transaction as the write. The idempotency identity includes `(tenantId, versionId, commitId, revisionId, operationType, aggregateType, aggregateId)`. Replaying a `revisionId` is a no-op; stale `expectedDraftRevisionEpoch` or `expectedDraftScopeRevisionEpoch` values fail closed as `DRAFT_WRITE_CONFLICT` rather than being rebased or merged. The contract also exposes typed application outcomes including `APPLIED`, `NO_OP_ALREADY_APPLIED`, `DRAFT_WRITE_CONFLICT`, `UNRESOLVED_REFERENCE`, `OUT_OF_SYNC`, `INVALID_VERSION_STATE`, and `UNSUPPORTED_SCOPE`.
- Generation targets are typed scope enums: `REGION_SUBTREE`, `ZONE_SUBTREE`, and `NEW_EMPTY_REGION`. Scope epochs are keyed by tenant, version, scope type, and positive scope id. One canonical positive scope-id reader and normalized scope-id representation are used across region, zone, room, room-exit, generation-rule, spawn-binding, and generated-subtree validation; declared scope is validated before topology upserts reach the repository.
- `REPLACE_SCOPE` deterministically clears and replaces prior generated rooms, exits, generation rules, and spawn bindings within a supported region or zone scope. Generated exits and spawn bindings may target only rooms created by the same payload. Spawn-binding replacement clears prior in-scope bindings, rejects out-of-scope bindings, and rejects unsupported `NEW_EMPTY_REGION` combinations.
- `SEED_APPEND_ONLY` fails closed when a revision would delete or rewrite existing authored rows, including spawn bindings. Topology mutations must prove that changed rows belong to the declared region or zone subtree; generation-rule mutations persist and validate their declared scope and participate in digest hashing.
- `WORLD_GENERATION_SUBTREE` applies generated rooms, exits, generation rules, and spawn bindings in one declared subtree request. Entity references for spawn bindings are validated through the canonical Entity Management RPC. Design-time population writes World-owned declarative bindings only; Automation and Scripting cannot author template topology, bindings, or live entities as a generation side effect.
- `GetDraftDesignDigest(versionId)` currently hashes the six implemented version-scoped families: regions, zones, rooms, room exits, generation rules including scoped metadata, and world-entity spawn bindings. The response does not expose a row-family manifest, so Game Design cannot independently prove that every applicable family was included; fail-closed family-manifest completeness remains a publish-gate requirement rather than a live check.
- World room and topology caches are derived, versioned Class A state: room/world mutations and activation changes require explicit invalidation or refresh of affected keys, and reset/rebuild behavior must recompute from authoritative storage rather than treating TTL as correctness. The current implementation still has cache-hardening work around reset tolerance and complete event-driven invalidation.
- The applied-revision ledger now exists and is written transactionally with successful Draft mutations. The current World digest read still computes version-scoped template inputs and emits synthetic `appliedCommitId = "version:<versionId>"`; the digest must derive a commit-level token only after that commit's complete revision set is durably applied. Revision-ledger identities remain replay and idempotency evidence, not the publish-convergence token. World currently emits digest schema `2`, while Game Design's live publish gate accepts only schema `1`, so full-version publish cannot admit the current World digest until coordinated schema adoption lands. Publish convergence also remains an obligation for newly introduced topology/generation families and separately exported navmesh/path-graph artifacts.

### Validation and Proof

- LOOK proof covers deterministic World room snapshots and exits, visible Entity room-ground data, authenticated command behavior, structured same-fence success, stale/mixed-fence rejection, dependency failure propagation, and WebSocket/Telnet rendering parity. Movement proof covers valid directional exits, invalid or missing exits, missing-room behavior, downstream failures, unauthenticated commands, Game Session binding mutation, destination auto-LOOK, non-disconnecting failures, pipeline traversal, and reconnect-after-move; completed manual checks also cover one successful move and one invalid exit on both transports. Authoritative World Management location mutation is not yet proven because that write contract is not implemented.
- Runtime-room convergence proof covers canonical World Management emission and malformed-id rejection, row/record exit mapping, canonical room cache keys, shared readers at Game Logic and Entity Management ingress, attestation issuance and claims, session-routing scrubbing, movement idempotency, automation event payloads, and Game Session outbound validation before transport dispatch. Ordinary fixtures use `R-...`; legacy `room-...`, bare numeric, uppercase, or malformed forms remain only in negative tests.
- Lifecycle proof covers the durable world creation, activation, termination, and failure path plus the operator-facing lifecycle read surface; the extracted command path is the local non-Temporal fallback.
- Draft mutation and digest proof passed with the focused World Management tests for mutation, gRPC, and digest services. Additional focused proof passed for zero declared scope ids on room, room-exit, region, and zone mutations and for generation-rule scope-id normalization; focused Game Design revision and gRPC caller proof also passed. The current World Management module proof passed through `dev-tools/validation/run-locked-gradle.sh :world-management-service:check -PfullCheck`.

## Active Gaps

- Broader travel, pathfinding, combat adjacency, richer movement failure semantics, and additional client polish are outside the completed directional-movement baseline.
- World Management still needs the authoritative character/session location-transition mutation contract used by live movement; the current Game Session binding update is not a substitute for that ownership boundary.
- Prepare and activate lifecycle commands still hold local transactions across blocking Game Design attestation reads; they need the same staged/fenced transaction separation already used around termination cleanup.
- The applied-revision ledger is live, but `GetDraftDesignDigest` still emits a synthetic version-derived `appliedCommitId` instead of deriving the highest commit whose complete revision set is durably applied.
- World digest schema `2` is not accepted by the current Game Design publish gate, which still accepts only schema `1`.
- The World digest response has no explicit row-family manifest, so the publish gate cannot independently detect an omitted applicable family even though the six current families are hashed.
- Game Logic enforces equal World and Entity LOOK fences, but the final `lookSnapshotId` is still the World component identity rather than a combined identity for both component snapshots.
- Room-view overlays such as hazards or combat state, richer visibility policy, and nested-container inspection remain later work. Any overlay must extend the authoritative `LookResult` and same-fence model rather than introduce an independent room view.
- `worldTopology` provides the settings home for scope-sensitive actions such as `SHOUT`; those actions are not implemented here.
- Later world-generation stages, delayed repair, richer generation payloads, generator provenance, and broader editor callers can extend the live lifecycle and Draft contracts. They must not create parallel lifecycle orchestration or Draft mutation paths.
- The current public runtime encoding is `R-<roomInstanceRowId>` even though consumers must treat it as opaque. Replacing that encoding with a non-storage-derived opaque identifier would be a separate contract decision; no current public/runtime identity drift is recorded at the completed 03.2 boundary.

## To Discuss

No competing implementation state is recorded for the current runtime-room identity, same-fence `LOOK`, directional movement, lifecycle, or Draft-mutation boundaries. Future decisions are limited to:

- a new room-view overlay or visibility fact that changes the authoritative `LookResult` composition contract;
- new topology-sensitive player actions beyond the settings homes already in place;
- travel or pathfinding semantics that cannot be expressed as an ordinary authoritative exit transition; or
- a new generation or repair phase that materially changes the durable world-lifecycle workflow; or
- whether and how to replace the current `R-<roomInstanceRowId>` encoding while preserving World Management's exclusive mapping authority.

## Service and Contract Map

| Owner | Current responsibility | Public or shared contract | Evidence focus |
| --- | --- | --- | --- |
| World Management | Runtime room mapping, topology, lifecycle, and Draft world mutation; authoritative character/session location mutation remains an open contract seam | `RoomInstanceRef`, room snapshot/read APIs, lifecycle control-plane reads, `ApplyWorldDesignMutation`, `GetDraftDesignDigest` | Runtime-id rejection and mapping, exit resolution, missing location-transition contract, lifecycle fencing, mutation idempotency, scope epochs, digest coverage |
| Game Logic | Same-fence room/entity aggregation and movement resolution | Structured `LookResult`, read-fence errors, movement result | Equal-fence composition, legacy-room rejection, valid and invalid exit handling |
| Entity Management | Visible room-ground state and room-scoped containment | Room-scoped entity and inventory reads | Canonical runtime-room ingress, room-ground scope, matching entity read fence |
| Game Session | Text command ingress, gameplay room binding, rendering, replay, and post-move refresh | `LOOK`, `QUICKLOOK`, `MOVE` / `GO`, session routing and attestation inputs | WebSocket/Telnet parity, fresh room read after movement or reconnect, malformed-room rejection before transport |
| Game Design and shared platform contracts | Settings publication and Draft-world callers | `movement.*`, `worldTopology.*`, scoped settings, revision caller path | Effective settings resolution and caller proof without alternate write paths |

## Source Evidence

The following records are the unchanged line-preserving transposition used to audit the consolidated implementation record above. Heading depth is shifted by three levels and same-directory Markdown links are rebased only so the combined tracker remains valid and navigable.

### source-02-12-task-list-movement-and-topology-settings-vertical-slice-1-19-31-55-65-69

#### Movement and Topology Settings Vertical Slice Task List - Runtime movement and topology settings (source lines 1-19, 31-55, 65-69)

##### Preserved Source Text: source-02-12-task-list-movement-and-topology-settings-vertical-slice-1-19-31-55-65-69

<!-- migration-source path="design/project-management/vertical-slices/02.12-task-list-movement-and-topology-settings-vertical-slice.md" lines="1-19, 31-55, 65-69" sha256="95ad3b5582c9eb5987c1d2c3f342795cf95848fb0154ca393401496c4a80829f" heading-offset="3" -->
#### source-02-12-task-list-movement-and-topology-settings-vertical-slice-1-19-31-55-65-69: Movement and Topology Settings Vertical Slice Task List

##### source-02-12-task-list-movement-and-topology-settings-vertical-slice-1-19-31-55-65-69: Goal and Status

Goal: fold movement-related and topology-dependent behavior into the platform settings model so exit/movement policy and later scope-sensitive communication behaviors such as `shout` have a clean configuration home instead of ad hoc code constants. Status: implemented for pre-06 scope.

##### source-02-12-task-list-movement-and-topology-settings-vertical-slice-1-19-31-55-65-69: Implementation Notes

- The first settings seam now exists in Game Session:
  - `MovementProperties` surfaces the post-move redraw rule.
  - `WorldTopologyProperties` establishes the typed topology/config home for later scope-sensitive communication work.
- The first canonical settings groups for this domain are now explicit:
  - `movement.postMoveView`
  - `worldTopology.scopeModel`
  - `worldTopology.regionBehavior`
- Game Session now carries generation-ready configuration metadata and documented defaults for:
  - `firemud.movement.post-move-look-enabled`
  - `firemud.world-topology.scope-model`
  - `firemud.world-topology.regions-enabled`
<!-- source-gap: lines 20-30 -->

This slice follows `02.9`. It should not redesign the movement ownership model. It should surface movement/topology policy into the canonical settings framework and make room for later topology-sensitive communication scopes.

##### source-02-12-task-list-movement-and-topology-settings-vertical-slice-1-19-31-55-65-69: 1. Domain Inventory

- [x] Audit the currently surfaced movement and topology assumptions currently expressed in code, docs, and tests across World Management, Game Logic, and Game Session.
- [x] Classify the surfaced operator-default rules as:
  - operator/runtime tuning;
  - tenant/game behavior policy;
  - or internal-only detail for now.

##### source-02-12-task-list-movement-and-topology-settings-vertical-slice-1-19-31-55-65-69: 2. Movement Settings Surface

- [x] Define the first canonical `movement` settings groups, with the first live group centered on:
  - `movement.postMoveView`
- [x] Surface the baseline movement behavior already agreed in the slice docs, including automatic post-move `LOOK` refresh and bounded player-facing failure categories.

##### source-02-12-task-list-movement-and-topology-settings-vertical-slice-1-19-31-55-65-69: 3. Topology Settings Surface

- [x] Define the first canonical `worldTopology` settings groups needed for later features, with the first live groups centered on:
  - `worldTopology.scopeModel`
  - `worldTopology.regionBehavior`
- [x] Explicitly create the settings home needed for future `shout` and similar scope-sensitive communication actions, without implementing `shout` in this slice.
- [x] Document how games with and without explicit regions/areas/maps express those topology capabilities in config instead of hardcoded fallback logic.

<!-- source-gap: lines 56-64 -->

##### source-02-12-task-list-movement-and-topology-settings-vertical-slice-1-19-31-55-65-69: 6. Final QA Checklist

- [x] Confirm the resulting settings model gives future `shout`/scope work a clean home without reopening the architecture discussion.
- [x] Confirm the movement slice behavior and later topology-sensitive actions can be expressed through one repeatable settings pattern.
<!-- /migration-source -->

### source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-25-27-44

#### `02.18.15` World and Session Lifecycle Concurrency Hardening - World lifecycle transaction boundary and termination ownership (source lines 25-27, 44)

##### Preserved Source Text: source-02-18-15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice-25-27-44

<!-- migration-source path="design/project-management/vertical-slices/02.18.15-task-list-world-and-session-lifecycle-concurrency-hardening-vertical-slice.md" lines="25-27, 44" sha256="43afec2e15691b1ad3d1c1d1a8d8309ac3cf7049a31427ab4f552701d5d26c36" heading-offset="3" -->
- World Management lifecycle transaction boundaries
- remote cleanup and activation validation ordering
- atomic enforcement for current epoch/version/lifecycle checks
<!-- source-gap: lines 28-43 -->
- world lifecycle methods no longer hold the local transaction open across blocking gRPC calls where a cleaner staged/fenced flow is possible.
<!-- /migration-source -->

### source-02-20-2-task-list-world-lifecycle-temporal-migration-vertical-slice-1-26

#### World Lifecycle Temporal Migration Vertical Slice - Audited primary runtime or service owner (source lines 1-26)

##### Preserved Source Text: source-02-20-2-task-list-world-lifecycle-temporal-migration-vertical-slice-1-26

<!-- migration-source path="design/project-management/vertical-slices/02.20.2-task-list-world-lifecycle-temporal-migration-vertical-slice.md" lines="1-26" sha256="e6fa7bcd51c9ae15f2bfec180532409cc5fda3cada2937dd310576823cc740a6" heading-offset="3" -->
#### source-02-20-2-task-list-world-lifecycle-temporal-migration-vertical-slice-1-26: World Lifecycle Temporal Migration Vertical Slice

##### source-02-20-2-task-list-world-lifecycle-temporal-migration-vertical-slice-1-26: Goal and Status

Goal: migrate world creation / activation / termination orchestration onto Temporal so the most clearly workflow-shaped FireMUD control-plane lifecycle becomes crash-proof, timer-aware, resumable, and operator-visible on a real durable workflow substrate. Status: implemented at the current world lifecycle boundary.

##### source-02-20-2-task-list-world-lifecycle-temporal-migration-vertical-slice-1-26: Scope

- migrate world creation / activation from the current saga-oriented design to Temporal;
- include termination and retry/repair lifecycle where it shares the same durable control-plane semantics;
- preserve the existing class-A pre-activation versus class-B gameplay boundary;
- keep gameplay runtime, tick execution, and live in-world mutation outside the Temporal path.

##### source-02-20-2-task-list-world-lifecycle-temporal-migration-vertical-slice-1-26: Checklist

- [x] Map current world lifecycle states and step identities onto Temporal workflow state and activity boundaries.
- [x] Preserve the existing business idempotency keys and activation fencing semantics under the new workflow substrate.
- [x] Expose operator-visible world workflow status through the canonical control-plane read surface.
- [x] Re-prove world creation / activation / termination behavior on the new durable workflow path.

##### source-02-20-2-task-list-world-lifecycle-temporal-migration-vertical-slice-1-26: Implementation Notes

- World Management now hosts a canonical `world-lifecycle` Temporal workflow that owns prepare, activate, fail, and terminate orchestration through shared `common-temporal` workflow identity and task-queue conventions.
- The public `WorldInstanceActivationService` surface now delegates to durable workflow execution when Temporal is enabled and falls back to the same extracted command service when the runtime is not enabled, so local non-Temporal app contexts still boot cleanly.
- Operator-facing lifecycle reads now expose deterministic workflow identity plus Temporal execution status through `GetWorldInstanceLifecycle`.
- This slice is closed at the current boundary even though later world-generation or delayed-repair stages may extend the same workflow family; those are additive follow-throughs, not a reason to keep the base adopter seam marked planned.
<!-- /migration-source -->

### source-03-task-list-data-driven-look-vertical-slice-1-40

#### Data-Driven LOOK Vertical Slice Task List - Authoritative room snapshot and exit topology (source lines 1-40)

##### Preserved Source Text: source-03-task-list-data-driven-look-vertical-slice-1-40

<!-- migration-source path="design/project-management/vertical-slices/03-task-list-data-driven-look-vertical-slice.md" lines="1-40" sha256="453637cf37341b1ff9ddd06777ed8f8b83c227bd29658ff5289121804a860782" heading-offset="3" -->
#### source-03-task-list-data-driven-look-vertical-slice-1-40: Data-Driven LOOK Vertical Slice Task List

##### source-03-task-list-data-driven-look-vertical-slice-1-40: Goal and Status

Goal: replace the original hard-coded `LOOK` path with a data-driven flow that uses World and Entity services via Game Logic, producing canonical text output and observability for both WebSocket and Telnet clients. Status: the main LOOK flow and several regression tests are implemented; this document continues to describe the target-state behaviour, with implementation details and current coverage reflected in service design docs and test suites.

Follow-up design direction agreed after the main slice landed:

- Game Logic remains the owner of the authoritative structured `LookResult`.
- Game Session owns transcript replay and final client rendering, but a fresh `LOOK` remains authoritative rather than being served from a stale rendered room cache.
- Reconnect redraw remains: bounded transcript context, then fresh `LOOK`, then fresh prompt.
- The target-state `LOOK` shape should stay explicitly sectioned: room/world snapshot data, exits, visible occupants, visible room-ground items, and later optional overlays such as hazards or combat state.
- Visible room-ground items should come from the Entity Management containment model for the room-attached ground container, while nested container contents remain out of scope for base `LOOK` and should be inspected through later item/container commands.
- The standard `QUICKLOOK` command reuses the same room-view structure as `LOOK` while omitting the room-description prose so players can get a fast redraw of occupants, items, exits, and prompt/status context.
- The current operator-default renderer policy for `LOOK`/`QUICKLOOK` and prompts is surfaced through:
  - `firemud.presentation.default-color-mode`
  - `firemud.presentation.brief-enabled-by-default`
  - `firemud.presentation.prompt.enabled`
  - `firemud.presentation.prompt.emit-after-reconnect-restore`
  - `firemud.presentation.prompt.coalesce-window-ms`

This checklist builds on the **Telnet to Gameplay** and **Login and Session** slices by replacing the hard-coded `LOOK` behavior in Game Session with a fully data-driven implementation that pulls room descriptions, exits, and visible entities from the World, Entity, and Game Logic services. Each task is intentionally scoped so it can be handed to Codex (or a developer) as a single, self-contained chunk of work.

##### source-03-task-list-data-driven-look-vertical-slice-1-40: 1. Protocol, UX, and Design Alignment for LOOK

- [x] Re-read the [Minimal Text Command Protocol](../../architecture/microservices/game-session-service/README.md#minimal-text-command-protocol), [Game Session Service](../../architecture/microservices/game-session-service/README.md), [World Management Service](../../architecture/microservices/world-management-service/README.md), and [Entity Management Service](../../architecture/microservices/entity-management-service/README.md) docs to confirm the intended sources of truth for room layout, entities, and gameplay state.
- [x] Decide and document the canonical `LOOK` output shape for this slice. The target-state text renderer should emit the room name/title, then one composed descriptive block that combines room prose with visible characters and room-ground items, followed by exits; player health/status remains part of the prompt rather than the `LOOK` body.
- [x] Update the `Minimal Text Command Protocol` section so `LOOK` is explicitly documented as a data-driven command, including at least one Telnet and one WebSocket transcript that show a realistic room with exits and a couple of entities (players and/or NPCs).
- [x] Add a short subsection to the Game Session Service design doc describing how `LOOK` requests flow through Game Session → Game Logic → World/Entity, and where rendering or reconnect transcript policy sits in that pipeline.
- [x] Ensure the design docs clearly state that `LOOK` requires an authenticated session (reusing the login/session guard from the previous slice) and that unauthenticated clients still receive `ERROR NOT_AUTHENTICATED` when attempting `LOOK`.

##### source-03-task-list-data-driven-look-vertical-slice-1-40: 2. World Management Service: Minimal Room Data for the Slice

- [x] Before changing this service for the slice, run `./gradlew :world-management-service:test` and either get the existing tests passing or clearly document/temporarily disable any failing tests so the baseline is stable. *(ran successfully prior to these edits; see build log above or `./gradlew :world-management-service:test` locally).*
- [x] Define or refine a minimal gRPC API in the World Management Service that can return room metadata needed for `LOOK` (e.g., `GetRoomSnapshot` or equivalent) including `roomInstanceId`, name, descriptions, and exits for a given `tenantId` and `roomInstance`.
- [x] Add or update the World Management proto files so the room snapshot response includes everything the vertical slice needs but nothing extra (for example, omit combat or scripting hooks that are not yet used by LOOK).
- [x] Provide a tiny deterministic test world in World Management (for example, 3–5 rooms connected in a simple loop) via fixtures or a test-only data initializer referenced by integration tests.
- [x] Add unit and/or integration tests in `services/world-management-service` that exercise the room snapshot API for the deterministic test world, verifying correct exits and descriptions for at least one room used by the vertical slice scenarios.
- [x] Update the World Management Service README/design docs with a short section that explains how the `LOOK` slice uses the room snapshot API and how to extend the sample world for future slices.

<!-- /migration-source -->

### source-03-1-task-list-same-fence-look-read-consistency-vertical-slice-1-50

#### `03.1` Same-Fence LOOK Read Consistency - Same-fence room-read consistency (source lines 1-50)

##### Preserved Source Text: source-03-1-task-list-same-fence-look-read-consistency-vertical-slice-1-50

<!-- migration-source path="design/project-management/vertical-slices/03.1-task-list-same-fence-look-read-consistency-vertical-slice.md" lines="1-50" sha256="90baf06ed07f68e29cb9a7e953ecff297b441a779cb88cfe66f76e73c14496a6" heading-offset="3" -->
#### source-03-1-task-list-same-fence-look-read-consistency-vertical-slice-1-50: `03.1` Same-Fence LOOK Read Consistency

Goal: make the live `LOOK` path consume one same-fence room/entity snapshot instead of composing independently fetched world and entity reads that may reflect different runtime ticks. Status: complete.

##### source-03-1-task-list-same-fence-look-read-consistency-vertical-slice-1-50: Implementation Notes

World Management and Entity Management now stamp LOOK-related responses with the same practical read fence, currently `tenantId:gameInstanceId:roomInstanceId`. Game Logic requires the two fences to be present and equal before building `LookResult`, propagates the component fences into the result, and maps mixed or missing fence failures to a read-fence error instead of silently composing inconsistent state.

##### source-03-1-task-list-same-fence-look-read-consistency-vertical-slice-1-50: Why This Slice Exists

The main `LOOK` slice is live, but one important target-state contract is still only documented:

- World and Entity surfaces should participate in one same-fence read boundary;
- `LOOK` should propagate `asOfTickId` or equivalent same-fence metadata;
- mixed-fence reads should fail with explicit stale/read-fence errors rather than silently returning a composed success response.

Right now `LookAggregationService` still fetches room snapshot and visible entities independently and returns success even when the two reads may reflect different runtime moments.

##### source-03-1-task-list-same-fence-look-read-consistency-vertical-slice-1-50: Scope

- same-fence metadata on World and Entity `LOOK`-related RPCs
- Game Logic aggregation behavior for same-fence reads
- explicit stale/read-fence failure handling
- docs/proto/service alignment for the live `LOOK` path

##### source-03-1-task-list-same-fence-look-read-consistency-vertical-slice-1-50: Out of Scope

- broader transcript/presentation work already covered by the main `03` slice
- deep combat/actor overlay additions unrelated to read consistency
- later durable tick/effect ledger work except where it provides the required read-fence source

##### source-03-1-task-list-same-fence-look-read-consistency-vertical-slice-1-50: Locked Direction

- `LOOK` should not silently succeed with mixed-fence room/entity composition.
- same-fence metadata must be part of the real RPC contract, not docs-only commentary.
- Game Logic remains the owner of `LookResult`, but it must aggregate on one coherent read fence.

##### source-03-1-task-list-same-fence-look-read-consistency-vertical-slice-1-50: Acceptance Shape

- world/entity `LOOK`-related proto surfaces carry the required same-fence metadata.
- live service implementations populate and validate that metadata.
- `LookAggregationService` returns explicit stale/read-fence errors instead of silently composing mixed snapshots.
- docs and proto/service behavior match.

##### source-03-1-task-list-same-fence-look-read-consistency-vertical-slice-1-50: Checklist

- [x] Define the canonical same-fence metadata and failure codes for the live `LOOK` seam.
- [x] Update World and Entity proto/service implementations to populate and validate the fence.
- [x] Update Game Logic `LOOK` aggregation to require same-fence composition.
- [x] Add focused tests for same-fence success, stale-fence rejection, and existing dependency failure propagation.
<!-- /migration-source -->

### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218

#### 03.2 Task List: Runtime Room Identity Contract Convergence Vertical Slice - Canonical runtime room identity and world mapping (source lines 1-218)

##### Preserved Source Text: source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218

<!-- migration-source path="design/project-management/vertical-slices/03.2-task-list-runtime-room-identity-contract-convergence-vertical-slice.md" lines="1-218" sha256="ed7c7d38c7869e47f9ba71b1153902573abda5dd7e62df590fbaff389adb5062" heading-offset="3" -->
#### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: 03.2 Task List: Runtime Room Identity Contract Convergence Vertical Slice

##### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: Goal and Status

Goal: converge FireMUD onto one canonical cross-service runtime room identity contract so `roomInstanceId` means exactly one thing at service boundaries, while any World Management numeric storage surrogate remains an explicitly internal implementation detail with a different name. Status: implementation-complete at the current touched branch boundary; reopen only if fresh proof exposes a remaining public/runtime room identity drift seam.

##### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: Why This Slice Exists

The repo currently has a real contract split hidden behind shared naming:

- gameplay/session/auth/read-fence flows treat `roomInstanceId` as an opaque runtime routing identity carried through `RoomInstanceRef`, session attestation, gameplay reads, and room-scoped entity surfaces;
- World Management persistence still uses a numeric `room_instance.room_instance_id` shape for internal topology joins and row selection.

That is not just a type mismatch. It is one boundary name currently representing two different concepts:

1. canonical runtime room identity used across services; and
2. internal World Management storage key used for topology persistence.

The correct target-state design is not "pick string or long everywhere because consistency is nice." The correct design is:

- `roomInstanceId` stays the one canonical runtime room identity at cross-service boundaries;
- it is opaque to consumers and may be string-shaped;
- World Management may still keep numeric row ids for joins, but those ids must not leak as the shared contract or keep the same name.

##### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: Scope

- the canonical runtime room identity contract across World Management, Entity Management, Game Logic, Game Session, shared proto/API surfaces, attestation/read-fence helpers, and related docs;
- explicit separation of public/runtime identity versus internal World Management storage identity;
- fail-closed boundary behavior where one identity family is mistakenly supplied in place of the other;
- migration proof for the touched boundary set.

##### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: Out of Scope

- broader region/zone identity redesign unless directly required by room identity convergence;
- unrelated gameplay routing, admission-pointer, or auth guardrail work outside touched room-identity seams;
- deep world-topology authoring redesign beyond the internal naming and mapping needed for this slice;
- presentation or transcript changes unrelated to room identity semantics.

##### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: Locked Direction

- `roomInstanceId` must become the canonical runtime room identity everywhere outside World Management internals.
- Cross-service callers must treat `roomInstanceId` as opaque and must not infer database semantics from its shape.
- World Management internal numeric keys may remain, but they must use distinct names such as `roomInstanceRowId` / `roomInstanceDbId` rather than sharing `roomInstanceId`.
- World Management owns the authoritative mapping from canonical runtime room identity to internal storage rows.
- No boundary should silently coerce one identity family into the other.

##### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: Implementation Notes

- Completed the first bounded audit-and-doc batch before internal renames. Current live repository classification:
  - Canonical runtime room identity already uses opaque text at shared boundaries:
    - `protos/shared/v1/instance_refs.proto` defines `RoomInstanceRef.room_instance_id` as `string`.
    - `services/common-security` gameplay session attestation and probe helpers compare `roomInstanceId` as text, not as a numeric database key.
    - `services/game-session-service`, `services/game-logic-service`, and `services/entity-management-service` already consume `RoomInstanceRef` and room-scoped gameplay context as the cross-service contract.
  - World Management still has internal numeric storage identities that reuse the shared `roomInstanceId` name:
    - `services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/entity/RoomInstance.java`
    - `services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/repository/RoomInstanceRepository.java`
    - `services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/repository/RoomInstanceExitRepository.java`
    - `services/world-management-service/src/main/resources/db/migration/V14__room_instance_topology.sql`
    - `services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/data/TestDataSeeder.java`
  - The main bridge seam is `WorldManagementGrpcService`, which accepts canonical text `RoomInstanceRef.roomInstanceId`, then maps it onto numeric internal storage selection and read-fence composition. That bridge should remain authoritative, but the numeric side needs distinct naming before later boundary/code migration batches.
- Updated the shared identifier and World Management architecture docs so the repo now explicitly teaches one distinction: external/runtime `roomInstanceId` is the canonical cross-service contract, while World Management numeric topology keys are an internal storage concern still awaiting code/database renaming under `03.2`.
- Completed the first World Management code migration batch on the internal `room_instance.room_instance_id` seam:
  - renamed the World Management entity/repository/service-side numeric field naming from `roomInstanceId` to `roomInstanceRowId` where it represents the internal `room_instance.room_instance_id` storage key;
  - preserved `room_instance_exit.from_room_instance_id` and `to_room_instance_id` as separate room-row foreign keys rather than conflating them with the runtime room identity;
  - fixed `RoomServiceImpl.getRoomSnapshot(...)` to resolve exits by the fetched room row primary key instead of assuming the public runtime room id equals the room table row id;
  - refreshed focused unit proof in `RoomServiceImplTest` and `TestDataSeederTest`.
- Continued the World Management bridge seam so runtime room ids now have one explicit codec boundary instead of open-coded positive-long parsing:
  - added `RuntimeRoomInstanceIds` as the authoritative World Management bridge helper for runtime room identity parsing and canonical emission;
  - `GetRoomSnapshot` now emits canonical runtime ids as `R-<roomInstanceRowId>` for the current room, exits, and `worldSnapshotId` read fence;
  - `getRoom` / `getRoomSnapshot` now require canonical `R-<roomInstanceRowId>` runtime room ids at the public boundary and fail closed on legacy storage-shaped forms or malformed opaque text that World Management cannot map;
  - refreshed focused gRPC proof in `WorldManagementGrpcServiceTest` for canonical output and malformed-id rejection.
- Aligned Entity Management's local demo/runtime seed data with the canonical runtime room id contract:
  - room-ground starter fixtures now seed `roomInstanceId` as `R-1021` instead of a bare numeric storage-shaped token;
  - added focused `TestDataSeederTest` proof so room-ground items and containers stay on the canonical runtime room identity shape.
- Converged Game Session's default gameplay-room fallback onto the canonical runtime room identity shape:
  - `game.logic.default-room-id` now defaults to `R-1021` in both bound properties and service config instead of the numeric storage-shaped `1021`;
  - this keeps fresh `PLAY`/`LOOK`/movement/communication fallback paths on the same canonical room identity family as World Management and Entity Management.
- Converged the remaining Game Session canonical-room proof surfaces away from the old storage-shaped example:
  - client, websocket, presence-lifecycle, logout/AFK, and routing-bundle tests that model admitted gameplay room identity now use `R-1021` instead of bare `1021`;
  - this leaves only explicitly transitional World Management bridge docs mentioning bare numeric input forms, rather than routine Game Session proofs continuing to teach the old shape.
- Converged the Game Logic `LOOK` aggregation proof seam onto canonical runtime room ids:
  - `LookAggregationServiceTest` now models room snapshots, entity read fences, and exit targets with `R-...` room ids instead of storage-shaped numeric examples;
  - this keeps the rendered/read-fence gameplay proof aligned with the same canonical room identity family already taught by World Management, Entity Management, and Game Session.
- Converged the shared gameplay attestation proof seam onto canonical runtime room ids:
  - `GameplaySessionAttestationServiceTest` now uses `R-...` room ids across gameplay-session and internal-probe proof paths instead of continuing to mix routine examples with the old storage-shaped `1021` token;
  - this keeps the shared auth/read-fence contract teaching the same canonical runtime room identity already used across the other converged `03.2` gameplay surfaces.
- Tightened the World Management exit-topology repository seam so internal topology joins stop reusing the public runtime-room label:
  - `RoomInstanceExitRepository.findByTenantIdAndGameInstanceIdAndFromRoomInstanceRecordId(...)` now names the `room_instance.id` foreign-key lookup as a record-id seam instead of another overloaded `roomInstanceId`;
  - `JooqWorldManagementRepositorySupport.partialRoomInstanceRecord(...)` and exit-row hydration now also name the same internal `room_instance.id` seam explicitly as a record id;
  - refreshed the focused `RoomServiceImplTest` and `TestDataSeederTest` proof that reads/materializes exits through that internal record-id path.
- Converged the internal runtime room snapshot DTO/service seam onto explicit row-id naming:
  - `RoomSnapshotDto` now carries `roomInstanceRowId` / `targetRoomInstanceRowId` instead of ambiguous `roomId` / `targetRoomId` labels for the internal World Management runtime snapshot path;
  - `RoomService`, `RoomServiceImpl`, and `WorldManagementGrpcService` now keep the same distinction end to end before gRPC re-emits canonical `R-...` runtime room ids at the public boundary.
- Split the runtime `GetRoom` DTO seam away from design-time room DTO semantics:
  - added `RuntimeRoomDto` for the runtime room-read path so `RoomDto` no longer needs to carry both template-room and runtime-room meanings;
  - `GetRoom` now returns a typed `RuntimeRoom` gRPC payload carrying canonical runtime `roomInstanceId` plus explicit tenant/game/region ids instead of serializing runtime state through an opaque `room_json` shim.
- Converged the authoritative World Management `room:*` cache key onto canonical runtime room identity:
  - `RoomServiceImpl` now keys the correctness-critical room snapshot cache as `room:<tenantId>:<gameInstanceId>:R-<roomInstanceRowId>` instead of leaking the internal numeric room row id directly into the cache contract;
  - refreshed focused `RoomServiceImplTest` proof for cache hit, cache miss, and cache-write failure behavior on the canonical runtime room key shape.
- Converged the gameplay room-ground ingress readers onto one shared canonical runtime room validator:
  - `RequestIdValidation` now owns the shared `R-...` runtime room id reader used by service boundaries instead of leaving local regex or nonblank-text checks to drift apart;
  - World Management now delegates its runtime room row-id bridge parse through that shared canonical reader;
  - Entity Management room-ground/list-entities ingress and Game Logic room-ground inventory/mutation readers now reject legacy `room-...` or bare numeric room ids as `INVALID_ARGUMENT` before attestation, replay, or downstream entity lookups.
- Tightened gameplay session attestation room identity handling onto the same canonical contract:
  - gameplay-session and internal-probe attestation issuance now reject legacy storage-shaped runtime room ids instead of minting tokens that preserve `room-...` drift;
  - attestation claim validation now rejects legacy runtime room id claims as `SESSION_ATTESTATION_INVALID` before downstream equality matching can treat them as normal routed room identifiers.
- Converged the remaining Game Logic room-scoped aggregation ingress readers onto one shared canonical room-id helper:
  - `LookAggregationService`, `MoveAggregationService`, and `CommunicationAggregationService` now validate `RoomInstanceRef.room_instance_id` through a shared `RuntimeRoomInstanceRefs` reader instead of local nonblank-only checks;
  - legacy `room-...` or bare numeric room ids now fail closed as `INVALID_ARGUMENT` before downstream world/entity/social calls in those gameplay aggregation seams.
- Tightened the remaining Entity Management room-ground inventory service readers onto the canonical runtime room contract:
  - `InventoryServiceImpl.listRoomGroundItems(...)`, `pickupItemFromRoom(...)`, and `dropItemToRoom(...)` now reject legacy storage-shaped room ids through the shared canonical runtime room reader instead of generic nonblank text checks;
  - focused unit proof now covers those internal room-ground reader seams failing closed before repository, character, or item lookups.
- Preserved the canonical invalid-room contract at the Game Logic `ResolveLook` gRPC envelope seam:
  - `GameLogicGrpcService.mapLookError(...)` now keeps malformed runtime room ids on `INVALID_ARGUMENT` instead of degrading them to `LOOK_UNAVAILABLE` when `LookAggregationService` rejects legacy `room-...` or numeric room identifiers;
  - focused gRPC proof now covers the malformed-room transport mapping explicitly.
- Tightened the remaining Game Session outbound room-scoped client readers onto one shared canonical runtime room helper:
  - `GameLogicClient` and `EntityManagementClient` now validate room-scoped outbound `roomInstanceId` values through `GameplayRuntimeRoomIds` before attestation issuance or gRPC stub dispatch;
  - focused client proof now covers legacy `room-...` rejection before downstream transport on both Game Logic and Entity Management room-scoped calls.
- Converged the Game Session persisted-session reader seam onto fail-closed runtime room handling:
  - `SessionContext` no longer silently upgrades legacy `room-...` or bare numeric room ids at record construction time;
  - `SessionRoutingNormalizationService` now treats legacy gameplay room ids as stale session-state input and clears the gameplay binding before pointer checks instead of silently normalizing them into current state;
  - focused auth/routing proof now distinguishes canonical `R-...` gameplay bindings from legacy persisted room ids that must be scrubbed on read.
- Tightened the durable movement idempotency seam onto the canonical runtime room contract:
  - `RedisMovementEffectIdempotencyService` and the in-memory integration-test twin now reject legacy or malformed gameplay room ids on expected/current/replayed/destination movement state instead of replaying or persisting them as generic text;
  - `GameplayRuntimeRoomIds` now exposes the shared canonical-room predicate used by both session-routing normalization and movement idempotency guardrails;
  - focused movement proof now covers legacy room ids failing closed before replay or write-through in the durable movement path.
- Tightened the automation script movement-lifecycle payload seam onto the canonical runtime room contract:
  - `AutomationScriptEventPublisher` now rejects legacy or malformed gameplay room ids before publishing `onEnterRegion` / `onLeaveRegion` payloads instead of forwarding trimmed arbitrary room text under `fromRegionId` / `toRegionId`;
  - focused publisher proof now covers canonical `R-...` payload emission plus legacy-room negative paths, and the adjacent remote followup/control-plane proof examples now teach the same canonical runtime room id family.
- Converged the remaining optional gameplay-room attestation readers onto the canonical runtime room helper:
  - `GameLogicClient` and `EntityManagementClient` now canonicalize optional `roomInstanceId` values before non-room-scoped attestation issuance instead of open-coding raw `context.roomInstanceId()` passthrough in those request paths;
  - focused client proof now covers legacy runtime room ids failing closed before `queryInventory(...)` and `findCharacterByName(...)` can dispatch with malformed attestation room state.
- Converged current-state gameplay proof fixtures onto the canonical runtime room family:
  - command, communication, presence, auth, session-persistence, integration, and adjacent item-transfer audit proofs that are not explicitly testing legacy-room rejection now use `R-...` room ids instead of continuing to teach `room-...` or generic `room` placeholders as ordinary gameplay state;
  - explicit stale/legacy guardrail tests still keep legacy `room-...` examples where malformed runtime-room input is itself the subject under test.
- Converged the shared Game Session look-fixture exit topology onto canonical runtime room ids:
  - `LookTestFixtures` now uses canonical `R-3042` for its secondary exit target instead of a stray `room-inst-3042` example that kept teaching a noncanonical runtime room shape in shared stub and transcript proof.
- Converged the remaining incidental uppercase room-id fixtures onto the canonical runtime family:
  - dispatch-plumbing and item-transfer helper proofs that were only using `ROOM-*` as inert fixture data now use canonical `R-*` room ids, leaving uppercase/legacy forms only where malformed-room handling is itself the seam under test.
- Converged the logging-admin remote followup payload proof examples onto canonical runtime room ids:
  - remote followup and remote followup result controller/service tests now use canonical `R-...` `fromRegionId` / `toRegionId` payload examples instead of teaching `room-a` / `room-b` as ordinary runtime room identifiers;
  - explicit malformed-room rejection seams still keep legacy `room-...` examples where malformed runtime room input is itself the subject under test.
- Converged the Entity Management room-read API contract example onto the canonical runtime room identity:
  - the illustrative `ListRoomEntities` success fragment in the Entity Management architecture doc now uses canonical `R-...` `roomInstanceId` and matching `entitySnapshotId` fence text instead of teaching `room-antechamber` as an ordinary runtime room identifier.
- Converged the remaining World Management room-row storage column seam onto explicit internal naming:
  - the `room_instance` topology table now names its internal sortable room-row key `room_instance_row_id` instead of reusing the public `room_instance_id` label at the SQL/jOOQ seam;
  - `RoomInstanceRepository` and `RoomInstanceExitRepository` now read/write the same internal row-id field through `ROOM_INSTANCE_ROW_ID`, keeping the runtime `roomInstanceId` contract split explicit all the way down to the persisted room-row column.
- Converged the World Management room-exit record-key columns onto explicit internal naming:
  - the `room_instance_exit` table now names its foreign keys `from_room_instance_record_id` / `to_room_instance_record_id` instead of reusing `room_instance_id`-style labels for `room_instance.id` record references;
  - `RoomInstanceExitRepository` now reads and writes those record-key columns through `FROM_ROOM_INSTANCE_RECORD_ID` / `TO_ROOM_INSTANCE_RECORD_ID`, matching the existing service/repository distinction between runtime room ids and internal record ids.

##### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: Planned Work

###### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: 1. Canonical Contract and Naming Audit

- [x] Audit every live `roomInstanceId` / `room_instance_id` / `RoomInstanceRef` surface across proto, service DTOs, repository/entity models, read fences, attestation claims, and gameplay/session helpers.
- [x] Classify each occurrence as one of:
  - canonical runtime room identity;
  - internal World Management row/storage identity; or
  - stale ambiguous naming that still needs migration.
- [x] Update the identifier glossary and nearby architecture docs so the canonical distinction is explicit and repo-wide.

###### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: 2. World Management Internal Identity Separation

- [x] Rename the first World Management internal numeric `room_instance.room_instance_id` code seam away from shared contract naming.
- [x] Keep repository/entity/persistence semantics explicit so internal joins still work without leaking the numeric key as the boundary contract.
- [x] Introduce or harden the explicit mapping seam from canonical runtime `roomInstanceId` to World Management internal row identity where needed.

###### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: 3. Boundary Contract Convergence

- [x] Ensure all cross-service proto/API contracts expose only the canonical runtime `roomInstanceId`.
- [x] Remove or repair any touched boundary that still treats the numeric World Management key as if it were the shared room identity.
- [x] Keep read-fence, attestation, `LOOK`, movement, communication, and room-scoped entity consumers on the same canonical runtime identity contract.

###### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: 4. Fail-Closed Guardrails

- [x] Add focused validation or explicit mapping guards where ambiguous identity mixing can still occur.
- [x] Reject or fail closed when a touched boundary receives an internal storage id where a canonical runtime room identity is required, or vice versa.
- [x] Avoid convenience fallback coercions that preserve the ambiguity.

###### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: 5. Focused Proof and Documentation

- [x] Add or refresh focused proof across the touched surfaces proving one canonical runtime room identity contract and explicit internal mapping ownership.
- [x] Update slice docs and service architecture docs that currently imply `roomInstanceId` and numeric room storage keys are interchangeable.
- [x] Re-run Markdown/link proof plus the focused touched service checks.

##### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: Acceptance Shape

- `roomInstanceId` means one canonical runtime room identity across service boundaries.
- World Management internal numeric room storage identity remains allowed only as an explicitly internal concept with a different name.
- Cross-service callers no longer need to guess whether `roomInstanceId` means routing token or database row key.
- Touched boundaries fail closed instead of silently mixing the two identity families.
- Repo docs teach one contract instead of preserving ambiguous shared naming.

##### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: Suggested Starting Surfaces

- `design/architecture/system-architecture-identifier-glossary.md`
- `services/world-management-service`
- `services/entity-management-service`
- `services/game-logic-service`
- `services/game-session-service`
- shared proto/API surfaces carrying `RoomInstanceRef`

##### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: Spark Delegation Notes

- Start with a repository-wide audit and classification table before editing code.
- Keep the migration on runtime room identity only; do not widen into generic region/zone/template identity redesign.
- Return exact boundary files that still leak internal numeric room identity as shared contract.
- Keep the canonical answer fixed: external/runtime `roomInstanceId`, internal World Management numeric storage key with different naming.

##### source-03-2-task-list-runtime-room-identity-contract-convergence-vertical-slice-1-218: Validation

- `./gradlew spotlessApply`
- `./gradlew :world-management-service:check -PfullCheck`
- `./gradlew :entity-management-service:check -PfullCheck`
- `./gradlew :game-logic-service:check -PfullCheck`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-05-task-list-movement-vertical-slice-1-67

#### Movement Vertical Slice Task List - Movement topology and authoritative location mutation (source lines 1-67)

##### Preserved Source Text: source-05-task-list-movement-vertical-slice-1-67

<!-- migration-source path="design/project-management/vertical-slices/05-task-list-movement-vertical-slice.md" lines="1-67" sha256="b9b35028ebe5464f757515fd8997b1b2f367225db57cdb5e9f007dd4bfe7ca17" heading-offset="3" -->
#### source-05-task-list-movement-vertical-slice-1-67: Movement Vertical Slice Task List

##### source-05-task-list-movement-vertical-slice-1-67: Goal and Status

Goal: extend the current playable text-command loop so authenticated players can move between rooms using data-driven exits, have their location updated authoritatively, and automatically receive a fresh `LOOK` result after successful movement across both WebSocket and Telnet transports. Status: completed for this PR. Movement is now wired end-to-end through Game Session, Game Logic, and World Management, with WebSocket and Telnet parity coverage plus reconnect-after-move coverage.

This checklist builds on the **Login and Session**, **Data-Driven LOOK**, and **Chat & SAY** slices. It turns the existing room snapshots, exit metadata, and movement primitives into a real player-facing `MOVE` / `GO` loop that changes location and immediately reflects the new room state.

##### source-05-task-list-movement-vertical-slice-1-67: 1. Protocol, UX, and Design Alignment for Movement

- [x] Re-read the [Game Session Service protocols](../../architecture/microservices/game-session-service/protocols.md#minimal-text-command-protocol), [Game Logic Service](../../architecture/microservices/game-logic-service/README.md), and [World Management Service](../../architecture/microservices/world-management-service/README.md) docs to confirm the intended ownership split for movement, exit validation, and room-state refresh.
- [x] Decide and document the canonical text protocol for movement, including whether the MVP surface is `MOVE <direction>`, directional aliases (`NORTH`, `SOUTH`, etc.), and/or a short `GO <direction>` alias.
- [x] Add at least one Telnet and one WebSocket transcript showing successful movement and failed movement (`ERROR INVALID_EXIT` or equivalent), with successful movement automatically followed by the new room's `OK LOOK` payload.
- [x] Update the Game Session and Game Logic design docs so they explicitly describe the movement request flow: authenticated command ingress -> Game Logic movement resolution -> world/location update -> refreshed `LOOK` output.

##### source-05-task-list-movement-vertical-slice-1-67: 2. World Management Service: Exit and Location Mutation Contract

- [x] Before changing this service for the slice, run `./gradlew :world-management-service:test` and stabilize the baseline if necessary.
- [x] Review the current room snapshot / exit contract and define the smallest authoritative movement-facing API needed for this slice, such as validating an exit from the current room and returning the destination room instance plus any required metadata.
- [x] If the current APIs are insufficient, add or refine a gRPC method in World Management that performs or supports authoritative room-transition updates for a character/session within the current tenant/game instance.
- [x] Ensure the movement-facing contract keeps room topology authoritative in World Management and does not shift exit validation logic into Game Session.
- [x] Add unit/integration tests in World Management covering at least: valid directional exit, invalid direction, and missing-room / missing-exit behavior for the deterministic test world used in current slices.

##### source-05-task-list-movement-vertical-slice-1-67: 3. Game Logic Service: Movement Resolution

- [x] Before changing this service for the slice, run `./gradlew :game-logic-service:test` and stabilize the baseline if necessary.
- [x] Introduce or refine a movement-oriented gRPC method (for example `ResolveMove`) that accepts `tenantId`, `gameInstanceId`, `sessionId`, `characterId`, current room identity, and the requested direction or exit selector.
- [x] Implement the Game Logic movement handler so it normalizes player input (`MOVE north`, `GO NORTH`, bare `north` if supported), validates it against authoritative world exit data, and returns a structured movement result containing destination room information or an application error.
- [x] Reuse the existing movement/pathfinding primitives only where appropriate for this MVP movement step; do not let this slice balloon into full travel/pathfinding or combat adjacency logic.
- [x] Ensure a successful movement result includes enough context for Game Session to trigger an immediate fresh `LOOK` for the destination room without guessing at cached room state.
- [x] Add unit tests covering successful movement, invalid direction, blocked/missing exit, and downstream World Management failures.

##### source-05-task-list-movement-vertical-slice-1-67: 4. Game Session Service: Text Command Wiring and Auto-LOOK

- [x] Before changing this service for the slice, run `./gradlew :game-session-service:test` and stabilize the baseline if necessary.
- [x] Extend the current text command interpreter so movement commands are treated as authenticated gameplay commands and flow through the same session guard already used by `LOOK` and `SAY`.
- [x] Add a dedicated movement handler that calls the Game Logic movement API, maps application failures into stable text errors, and on success updates the session's current room binding before emitting the destination room's `LOOK` result.
- [x] Keep the success transcript canonical and simple: movement acknowledgement if needed, followed by the destination `OK LOOK` payload. Do not invent a second competing room-description format for movement.
- [x] Emit movement-related metrics and logs (for example `gamesession.command.move.invocations` and `gamesession.command.move.failures`) with high-level error tags so operators can distinguish invalid exits from backend failures.
- [x] Add unit/integration tests in Game Session for successful movement, invalid exit, unauthenticated movement, and auto-LOOK behavior after a successful room change.

##### source-05-task-list-movement-vertical-slice-1-67: 5. Cross-Service End-to-End Tests (WebSocket and Telnet)

- [x] Add a WebSocket-focused cross-service regression that performs `LOGIN` / `PLAY` / `LOOK`, issues a movement command, and asserts the returned room description now reflects the destination room rather than the origin room.
- [x] Add a Telnet-focused variant through TCP Proxy and Gateway that exercises the same movement path and confirms protocol parity with the WebSocket flow.
- [x] Cover at least one success case and one failure case (`ERROR INVALID_EXIT`, `ERROR ROOM_NOT_FOUND`, or equivalent) and ensure failures do not disconnect the client.
- [x] Assert the movement path traverses the intended pipeline (Game Session -> Game Logic -> World Management) using logs, metrics, or gRPC interceptors similar to the existing LOOK/SAY slices.
- [x] Wire these regressions into the existing `crossServiceTest` targets and mention them in the relevant docs so the slice can be rerun easily.

##### source-05-task-list-movement-vertical-slice-1-67: 6. Developer Workflows, Smoke Tests, and Documentation Updates

- [x] Add or update a smoke test script (or documented manual sequence) that demonstrates `LOGIN` / `PLAY` / `LOOK` / movement over WebSocket, including the expected destination-room transcript.
- [x] Add a second Telnet-oriented example showing the same movement flow through TCP Proxy and Gateway.
- [x] Update the Game Session, Game Logic, and World Management design docs with a short implementation-status note for the movement slice, clarifying what is live, stubbed, and deferred.
- [x] Update any existing gameplay examples that imply room state is static once `LOOK` works; after this slice, examples should reflect that room state changes through movement and is refreshed immediately.

##### source-05-task-list-movement-vertical-slice-1-67: 7. Final QA Checklist

- [x] Run the relevant Game Session, Game Logic, World Management, and cross-service test targets for the movement slice and confirm they pass.
- [x] Manually verify one happy-path move and one invalid-exit move over both WebSocket and Telnet.
- [x] Confirm successful movement immediately yields the destination-room `LOOK` transcript and that metrics/logs make it easy to distinguish player mistakes from backend failures.

---

##### source-05-task-list-movement-vertical-slice-1-67: Deferred Follow-Up

- A future follow-up slice can extend movement beyond directional room travel into broader travel/pathfinding, richer failure semantics, or additional transport/UI polish if those become priorities.
<!-- /migration-source -->

### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84

#### World Design Mutation API Surface Vertical Slice - World-owned draft topology and mutation enforcement (source lines 1-9, 11-23, 27-48, 52-61, 63-84)

##### Preserved Source Text: source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84

<!-- migration-source path="design/project-management/vertical-slices/08.5-task-list-world-design-mutation-api-surface-vertical-slice.md" lines="1-9, 11-23, 27-48, 52-61, 63-84" sha256="d627442cf36deef89c16392c6b86e7bd754f78b2fc380c7a3cbf603a3260f110" heading-offset="3" -->
#### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84: World Design Mutation API Surface Vertical Slice

##### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84: Goal and Status

Goal: define and implement the first canonical World Management design-time mutation surface used by Game Design to apply version-scoped world revisions, so room editing, topology changes, generation revisions, and spawn-binding authoring share one idempotent conflict model instead of growing as ad hoc REST or opaque JSON paths. Status: complete.

##### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84: Implementation Notes

- World Management now exposes `ApplyWorldDesignMutation` for typed Draft region, zone, room, room-exit, generation-rule, world-entity-spawn-binding, and scoped world-generation-subtree mutations with revision-ledger idempotency plus aggregate/scope epoch enforcement.
<!-- source-gap: lines 10-10 -->
- World Management now validates spawn-binding entity references through a canonical Entity Management RPC, includes version-scoped spawn bindings in `GetDraftDesignDigest(versionId)`, and enforces the first generation-scope mutation policies:
  - generation-target scope types are now first-class proto enums (`REGION_SUBTREE`, `ZONE_SUBTREE`, `NEW_EMPTY_REGION`) instead of raw string-only ingress conventions;
  - `REPLACE_SCOPE` is carried as an explicit scope mutation policy on the canonical request shape.
  - `SEED_APPEND_ONLY` now fails closed when a revision would delete or rewrite already-present authored rows, including existing spawn bindings.
  - spawn-binding `REPLACE_SCOPE` now actively clears prior bindings within declared `ZONE_SUBTREE` and `REGION_SUBTREE` scopes, rejects bindings that fall outside the declared scope, and fails closed for unsupported `NEW_EMPTY_REGION` usage instead of silently drifting scope semantics.
  - topology mutations now also validate declared generation scopes: scoped region, zone, room, and room-exit mutations must prove the changed topology is inside the declared `REGION_SUBTREE` or `ZONE_SUBTREE`, while unsupported `NEW_EMPTY_REGION` combinations fail closed instead of advancing a scope epoch for out-of-scope rows.
  - declared subtree `scope_id` now uses one canonical positive-id reader across region, zone, room, room-exit, generation-rule, spawn-binding, and generated-subtree scope checks; generation-rule/scope-epoch readers normalize declared scope ids to one canonical string form; and region/zone/room/room-exit upserts validate declared scope before repository save instead of relying on transaction rollback after a late scope failure.
  - generation-rule mutations now persist `scopeType` / `scopeId`, validate declared `REGION_SUBTREE` and `ZONE_SUBTREE` targets, enforce same-scope `REPLACE_SCOPE` and `SEED_APPEND_ONLY`, and hash scoped generation-rule metadata into the World digest schema.
  - `WORLD_GENERATION_SUBTREE` now applies generated rooms, room exits, generation rules, and spawn bindings in one declared region/zone subtree request; `REPLACE_SCOPE` clears prior generated room, exit, spawn-binding, and generation-rule rows inside the declared scope before applying the replacement payload, while generated exits and spawn bindings can only target rooms created by the same payload.
- The remaining follow-through is outside this slice rather than inside the foundational seam: broader editor caller rollout and richer generation payload breadth can now build on the live canonical contract instead of inventing parallel mutation paths.

##### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84: Why This Slice Exists

<!-- source-gap: lines 24-26 -->

##### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84: Scope

- World Management design-time gRPC or equivalent service APIs for version-scoped Draft mutation.
- Region, zone, room, and room-exit create/update/delete or patch operations for the first editor path.
- Declarative spawn/population binding mutation keyed by `(tenantId, versionId, regionTemplateId/roomTemplateId, entityTemplateId)` or the concrete first-slice table names.
- Design-time generation revision execution for declared `REGION_SUBTREE`, `ZONE_SUBTREE`, or new-empty-container scopes.
- Idempotency keyed by at least `(tenantId, versionId, commitId, revisionId, operationType, aggregateType, aggregateId)`.
- Optimistic concurrency using `expectedDraftRevisionEpoch` for single aggregates and `expectedDraftScopeRevisionEpoch` for generation-addressable subtree scopes.
- Typed application-level outcomes including `APPLIED`, `NO_OP_ALREADY_APPLIED`, `DRAFT_WRITE_CONFLICT`, `UNRESOLVED_REFERENCE`, `OUT_OF_SYNC`, `INVALID_VERSION_STATE`, and `UNSUPPORTED_SCOPE`.
- `GetDraftDesignDigest(versionId)` coverage and tests proving new row families participate in the documented digest manifest when they affect publish semantics.

##### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84: Out of Scope

- Multi-branch merge semantics beyond optimistic concurrency and deterministic replay order.
- Bulk import/export of world content.
- Runtime instance mutation, live editing of Published versions, or compatibility windows for legacy mutation paths.
- Full creator UI polish beyond proving one editor/service path uses the canonical API.
- Entity Management design APIs except for the cross-service reference validation needed by world spawn bindings.

##### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84: Locked Direction

<!-- source-gap: lines 49-51 -->
- Replays of the same `revisionId` must no-op after the first successful application.
- Stale expected epochs fail closed as `DRAFT_WRITE_CONFLICT`; the service must not silently rebase or merge edits.
- Generation revisions use the scope epoch keyed by `(tenantId, versionId, scopeType, scopeId)` so subtree generation cannot overwrite newer manual edits without an explicit successful `REPLACE_SCOPE` write against the current epoch.
- Design-time population writes only declarative World-owned spawn/population bindings. Automation & Scripting must not author template topology, spawn bindings, or live entities as a side effect of design-time generation.

##### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84: Acceptance Shape

- Proto/OpenAPI contracts expose the first canonical World design mutation methods with stable request/response fields for `tenantId`, `versionId`, `commitId`, `revisionId`, expected epoch, aggregate/scope identity, and typed result/error details.
- World Management persists an applied-revision ledger or equivalent idempotency guard in the same transaction as each successful Draft mutation.
- World Management persists and enforces Draft aggregate/scope epochs so manual edits and generation revisions share one conflict model.
<!-- source-gap: lines 62-62 -->
- `GetDraftDesignDigest(versionId)` reflects the resulting Draft world graph and fails publish gating when applicable row families are missing from the digest manifest.
- Focused tests cover first apply, duplicate replay, stale epoch conflict, invalid version state, unresolved entity reference in a spawn binding, `REPLACE_SCOPE` subtree clearing and conflict behavior, unsupported-scope rejection, and `SEED_APPEND_ONLY` out-of-sync behavior.

##### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84: Follow-On Work

- Add richer generation payload details if later editor work needs authored zones/regions, external room references, or generator-run provenance beyond the first scoped subtree replacement/append contract.
- Broaden creator/editor UX callers beyond the service-level `SaveRevision` proof as real editor surfaces are built.

##### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the remaining breadth beyond the first live seam.
- [x] Verify and close follow-ups.

##### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-1-9-11-23-27-48-52-61-63-84: Verification Notes

- Focused mutation and digest proof passed with `./gradlew :world-management-service:test --tests '*WorldDesignMutationServiceImplTest' --tests '*WorldManagementGrpcServiceTest' --tests '*WorldDraftDesignDigestServiceImplTest'`.
- Additional declared-scope reader follow-through passed with `./gradlew :world-management-service:test --tests 'net.firedevops.firemud.worldmanagement.service.impl.WorldDesignMutationServiceImplTest.scopedRoomMutationRejectsZeroDeclaredScopeIdBeforeSave' --tests 'net.firedevops.firemud.worldmanagement.service.impl.WorldDesignMutationServiceImplTest.scopedRoomExitRejectsZeroDeclaredScopeIdBeforeSave'`.
- Remaining topology upsert adopters passed with `./gradlew :world-management-service:test --tests 'net.firedevops.firemud.worldmanagement.service.impl.WorldDesignMutationServiceImplTest.scopedRegionMutationRejectsZeroDeclaredScopeIdBeforeSave' --tests 'net.firedevops.firemud.worldmanagement.service.impl.WorldDesignMutationServiceImplTest.scopedZoneMutationRejectsZeroDeclaredScopeIdBeforeSave'`.
- Declared scope-id normalization proof passed with `./gradlew :world-management-service:test --tests 'net.firedevops.firemud.worldmanagement.service.impl.WorldDesignMutationServiceImplTest.scopedGenerationRuleNormalizesDeclaredScopeIdAcrossLookupAndEpochWrites'`.
- Focused caller proof passed with `./gradlew :game-design-service:test --tests '*RevisionServiceImplTest' --tests '*GameDesignGrpcServiceTest'`.
- Current world-management module proof passed with `dev-tools/validation/run-locked-gradle.sh :world-management-service:check -PfullCheck`.
<!-- /migration-source -->

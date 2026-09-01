# Game Logic Service API Contracts

This document defines the Game Logic Service REST and gRPC surfaces, exposure class, and command-specific contracts for the current gameplay slice. Game Logic owns gameplay/domain outcomes and causal-read orchestration and proof acceptance; each participant owner validates its own local proof. Game Logic does not own player-facing rendering or reconnect context. Those local consequences follow [Input, Output, and Presentation](../../system-architecture-input-output-and-presentation.md) and [Reconnection](../../system-architecture-reconnection.md).

## Implementation Status

- The current Game Logic adapters still return `shared.v1.ErrorDetail` for many failures and record `grpc.app_error`; that is implementation drift and a migration/proof gap, not a second contract.
- Causal-read status is target-only: the complete `CausalReadFence` and participant-proof enforcement described below are not current behavior. The live `ResolveLook` request/proto and World/Entity adapters remain floor-free, so protobuf propagation, adapter evidence, and focused causal-floor proof are implementation gaps.
- The publish-gate digest handler currently has no owner/method authorization or exact Game Design publication binding beyond shared bearer parsing. It also accepts a blank tenant through the service layer and returns an empty manifest digest independent of tenant/version. The target workload, tenant/scope/request/workflow/digest context, exact response identity comparison, and denial proof are canonical in [Game Design Version Control](../game-design-service/version-control.md#owner-to-owner-digest-authorization-and-tenant-identity).

### Player execution context

- Target: `SendCommunication` and every other player-delegated gameplay RPC propagate one complete, validated typed `PlayerExecutionContext` from Game Session through Game Logic and onward. The context includes the applicable player, tenant, active game-instance, session, room, region/epoch, admitted-bundle, realm, pointer, or playable-state scope, plus a stable request, command, or effect identity where required; Game Logic validates request/context equality and owner evidence rather than treating caller-supplied identifiers as authority. This is the ADR 0024 target contract.
- Current implementation gap: the live `SendCommunicationRequest` remains a flat schema (`tenant_id`, `session_id`, `character_id`, `account_id`, communication/text and target metadata, `room_instance`, `game_instance_id`, `speaker_name`, and `effect_id`) plus legacy `session_attestation`. The current proto does not yet carry typed `playableStateNamespaceId`, `playableStateScope`, region/epoch, pointer, or admitted-bundle fields, so complete typed-context propagation is not current behavior or proof.

### LOOK Slice

- Live: `ResolveLook` is wired into the command pipeline, orchestrates World Management snapshots and Entity Management listings, hands the structured `LookResult` to `LookResultRenderer`, and publishes the telemetry captured in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- Stubbed: room and entity context still comes from the deterministic LOOK fixtures so the canonical transcript remains deterministic; scripted descriptions, complex lighting, and dynamic hazard cues are not yet integrated.
- Deferred: future slices will enrich prose, annotate `LookResult` with combat and effect metadata, and surface additional visibility hints once the core text shape proves stable.

### Communication Slice

- Live: `SendCommunication` accepts authenticated gameplay `SAY`, `WHISPER`, and `TELL` payloads, validates length, resolves room-scoped or direct-target delivery metadata, and forwards the normalized gameplay request projection to the Social & Groups stub with explicit type and recipient information. The current API returns speaker/target delivery metadata, structured per-recipient view metadata, and `shared.v1.ErrorDetail` codes so Game Session can render the canonical transcript and later recipient-delivery slices can consume the same gameplay result. This response-level error shape is current implementation drift against the owner classification above; focused migration proof is not yet complete.
- Stubbed: downstream delivery still uses the Social & Groups regression stub that records gameplay `SendMessage` calls and echoes success while cross-service WebSocket and Telnet tests assert the canonical actor transcript and explicit recipient metadata.
- Deferred: direct Social & Groups ingress for account messaging, ordinary group/channel communication, and ordinary account/social mail; published closed observer-view declarations; authored partial-observation mechanics; cross-pod delivery; type-specific history/acknowledgement; and profile-defined bounded `SHOUT`. These classes must not inherit a universal Game Logic dependency merely because an in-game adapter can invoke them.

## Exposure Class

- gRPC gameplay APIs are internal-only service-to-service contracts invoked from Game Session and other trusted backend services.
- The documented REST endpoints are local-dev/test conveniences only and are not part of the Gateway allowlist or the production external API surface.

## REST

- `GET /ping` returns `ApiResponse` with the string `pong` in `data`.
- `POST /command` submits a gameplay command body as plain text and receives an `ApiResponse<String>` result.

These are the only REST endpoints; gameplay commands are primarily processed through the gRPC interface. There is no separate service-local OpenAPI contract file for this slice today, so any change to these REST semantics should update this document and the implementation/tests in the same change.

```bash
curl http://localhost:8080/ping
```

Expected response:

```json
{
  "status": "SUCCESS",
  "data": "pong",
  "error": null
}
```

## gRPC

- `Ping(PingRequest) returns (PingResponse)` is the basic connectivity check defined in [`game_logic_service.proto`](../../../../protos/game-logic/v1/game_logic_service.proto).
- `ExecuteCommand(ExecuteCommandRequest) returns (ExecuteCommandResponse)` evaluates a parsed gameplay command and returns the outcome.
- `SendCommunication` is the target gameplay communication contract: it inherits the complete validated `PlayerExecutionContext` from Game Session, classifies a world/gameplay communication type, resolves topology/perception/capability/effect facts, and forwards one bounded plan with stable identity, authorized candidate views, and owner handoffs. The current Game Logic adapter first requires `account_id` to be present, positive, and numeric; malformed, missing, or non-positive account identity returns `INVALID_ARGUMENT` before Entity Management or Social calls. It then projects the flat request to Social & Groups as `tenant_id`, that validated account identity as `sender_id`, mapped communication `type`, normalized text as `content`, the resolved recipient when applicable, and `effect_id`; it never falls back to `character_id` for sender authority. It does not forward `session_id`, room context, target kind/name, or legacy `session_attestation` unchanged. Game Logic owns validating the incoming request fields against the target context, but focused tenant/session/character/target mismatch and projection proof remains an implementation gap. Until that target context is propagated, the live request remains the flat field set documented under [Player execution context](#player-execution-context), including legacy `session_attestation`; those request fields are data to validate against the target context, never caller authority. Account messaging, ordinary group/channel communication, browser social actions, and ordinary account/social mail are Social-owned classes and may use an authenticated in-game adapter without becoming Game Logic actions or exposing private content to tenant-authored scripts. Deliberately world-specific mail is a gameplay communication class under [ADR 0147](../../decisions/adr-0147-explicit-communication-classes-and-owner-delivery.md); [ADR 0148](../../decisions/adr-0148-social-relationship-authority-and-entity-owned-value.md) remains the value and attachment authority. See active ADR 0147, mapped from reviewed archive record `archive-ADR-0134`.
- `PickupVisibleRoomItem` and `DropCarriedItem` are the player-facing item selector RPCs for the current `GET` and `DROP` command path. Game Session sends the current session/game/room context, raw item reference, and quantity; Game Logic resolves names, visible refs, container identities, and stack-family refs against the appropriate visible holder before delegating the concrete mutation to Entity Management.
- `ApplyActorCondition` is the current self-scoped gameplay orchestration path for the first actor-state mutation. It is not the target-state generic cross-actor effect API.
- gRPC outcome selection follows the [canonical outcome and transport classification](../../system-architecture-grpc.md#outcome-and-transport-classification): successfully produced gameplay/domain outcomes use the typed response contract, while a failure that prevents producing that result uses canonical non-OK status with bounded details. Exact RPC mappings remain implementation work.

```bash
grpcurl -plaintext localhost:6565 game_logic.v1.GameLogicService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

## Actor Effect Targeting

The target-state cross-actor effect API is a Game Logic-owned `ResolvedEffectPlan` contract. Game Session provides the authenticated source context and a raw player selector where the command syntax permits one; it must not select final actor ids.

This is the local consequence of [ADR 0112](../../decisions/adr-0112-typed-bounded-gameplay-effect-extension.md): target declarations and the idempotent effect identity are bound to the release-pinned action/release, while Game Logic resolves the bounded plan and typed effect outcome at runtime from current owner-scoped fact snapshots. It does not evaluate arbitrary script predicates, accept caller-selected target ids as authority, or expose an unbounded extension path; Entity Management remains the mutation owner. If owner validation detects a mismatch, the plan is discarded and re-resolved under that same effect identity rather than retaining stale targets. Re-resolution is bounded by the existing effect retry/age policy; exhaustion, unavailable authority, or contradictory evidence returns a non-mutating outcome rather than applying the stale plan or a fallback target.

- Game Logic resolves each declared action target set at durable execution time against the source actor, frozen release declaration, Entity Management actor state, and World Management occupancy state. `SOURCE` resolves implicitly; each authored set loads its release-pinned DML `TargetingPolicy`, uses its typed platform candidate selector, evaluates its referenced `ObservationPolicy.observableWhen`, then evaluates `eligibleWhen` for observable candidates. Initial authored sets resolve relative to the action source only.
- Game Logic compiles the frozen target-set policy predicates into one bounded `TargetingFactSnapshot` request per documented fact owner for the source and bounded candidate sets. A snapshot returns only the referenced facts and its owner-specific version or fence token; it is not a generic entity, relationship, or world-state dump, and resolution must not make per-predicate or per-candidate RPC calls. A provider read failure or unavailable snapshot fails closed.
- The resulting plan contains the source actor, resolved canonical target actor ids by target-set key, action/release snapshot, idempotent effect id, typed effect declarations bound to those sets, and target-resolution evidence, including frozen policies, fact snapshots, owner tokens, stage results, and decisive predicate evidence. A rejected action outcome retains the same internal policy snapshot, failed stage, and evidence when no plan can be formed.
- Before any source cost, cooldown, or effect mutation commits, each owner of a material targeting fact validates its recorded version or fence token. A mismatch discards the unresolved plan and re-resolves it under the same `effectId`, rebinding the release-pinned declarations and target selection against the original canonical `requestDigest`; it must not refresh to a later request, retain a stale selected target, or substitute a fallback candidate. When the bounded retry/age policy is exhausted, the result is non-mutating. Entity Management applies only a validated approved plan and never parses player-facing target text or recreates target policy from a partial request.
- Required same-region targets are applied atomically as one local Entity Management mutation. Cross-region target legs use durable coordinator/follow-up outcomes; before a remote leg mutates, its region re-reads and validates the facts it owns against current local authority rather than treating origin-side evidence as permanent authorization. The final gameplay outcome reports each leg rather than claiming a distributed transaction.
- Platform targeting modes are typed candidate-selection grammar. A reusable published `TargetingPolicy` owns its selector, observation-policy reference, and game-specific range/relationship/status/targetability rules; a reusable `TargetSelectionPolicy` owns cardinality and typed selection strategy, while each `ActionTargetSet` owns optional-target handling and optional player-input binding. A game's standard target paths resolve through named release-pinned default target-set bindings. Visibility and targetability are optional game facts, never universal actor fields.
- A failed observation predicate is always non-disclosing to the player: it has the same safe unavailable outcome as an absent target. A failed eligibility predicate may emit only the policy's approved safe feedback. Internal target-resolution evidence remains available for outcome/audit reasoning and must never be reconstructed from player output.
- Each authored `ActionTargetSet` or standard-path default target-set binding references a release-pinned `TargetSelectionPolicy` over its targeting policy's eligible candidates. The policy declares `EXACTLY_ONE`, bounded `UP_TO_N`, or `ALL_ELIGIBLE` within an operator ceiling and selects by `PLAYER_SELECTED`, `CANONICAL_ORDER`, typed `RANKED`, or effect-id-seeded `RANDOM_SEEDED` strategy. It must explicitly declare truncation behavior for an `ALL_ELIGIBLE` ceiling. Target-resolution evidence includes each set's policy snapshots, canonical candidate order, selected targets, applied ceiling, and any ranking values or random seed. Effects apply only to their declared resolved target set; a required unresolved set rejects before commit, while an optional unresolved set has an explicit no-mutation outcome.

`ApplyActorCondition` may remain as the narrow self-target compatibility seam until the generic plan is implemented, but new cross-actor action behavior must use the resolved-plan pathway rather than extending that RPC ad hoc.

## Action Cost and Cooldown Lifecycle

Game Logic applies a frozen action declaration only at durable execution time. Text parsing and queue admission do not consume actor resources or start cooldowns.

- The declaration contains typed `costs[]` and `cooldowns[]`, each keyed to the published actor-state catalog and carrying `ON_EXECUTION` or `ON_EFFECT_SUCCESS` commit semantics.
- Entity Management is authoritative for conditional resource consumption and actor cooldown records. The one idempotent effect id protects costs, cooldown creation, and same-region effect mutation from replay or concurrent double-spend.
- Game Session maintains only reconstructible region-timer scheduling projections for expiry/wakeup. Reconnect, idle-region recovery, and timer rebuild always consult authoritative actor state before allowing an action.
- Cross-region legs report durable target outcomes after source commit. They do not silently refund an already committed source cost or cooldown.

## Gameplay Action Outcomes

The target-state effect pathway returns and persists a structured `GameplayActionOutcome`, rather than a single text result or boolean. It contains the action/release/effect identity, source actor, cost/cooldown `commitState`, execution `completionState`, and ordered target-leg outcomes.

- `commitState` distinguishes no source mutation from an idempotently committed source action.
- `completionState` distinguishes local finality, remote-pending execution, and remote-final completion. A committed action with remote legs must not be flattened to generic success before those legs finish.
- Each target outcome includes its canonical actor id, required/optional classification, result code, and remote-leg identity where relevant.
- Game Logic derives idempotent semantic presentation events from the outcome for resolved authorized audiences. Events contain message keys, typed arguments, visibility classification, replay policy, and stable ids derived from the effect lifecycle.
- Game Session receives those events as presentation data and renders/delivers `PlayerOutput`; it does not recreate outcome semantics from command tables or remote result rows.

Call `ExecuteCommand` with:

```bash
grpcurl -plaintext -d '{"tenant_id":"demo","session_id":"demo","command":"look"}' \
  localhost:6565 game_logic.v1.GameLogicService/ExecuteCommand
```

## LOOK Aggregation and Formatting

- `ResolveLook` orchestrates World Management and Entity Management: World provides room topology, ambient state, and the authoritative occupant set for the target room or instance, while Entity enriches those caller-supplied occupant references with live entity and ground-item display data to build a deterministic `LookResult` that Game Session renders for clients.
- In the target causal-read path, Game Session allocates the complete `CausalReadFence` from durable region commit authority before invoking `ResolveLook`. Its runtime fence identity is at least `{tenantId, gameInstanceId, regionId, roomInstanceId, regionEpoch, committedTickId}`. `playableStateNamespaceId` and `playableStateScope` may remain broader validated request/admission context, but they are not part of the runtime fence identity. Game Logic propagates the fence unchanged to World and Entity and forwards the validated preferred locale unchanged to World Management for localized room snapshot resolution. World Management owns stored-variant selection; fallback and provider rules remain owned by [Input, Output, and Presentation](../../system-architecture-input-output-and-presentation.md#localization-and-translation) and [ADR 0136](../../decisions/adr-0136-future-compatible-localization-boundary.md), not this service. Game Logic accepts only participant proofs matching the requested fence scope and epoch, with `servedThroughTickId >= requestedFloor` (the committed-tick floor), and requires each participant's own opaque component version. It rejects or retries behind-floor, missing-version, or mixed-region/scope/epoch responses. Component versions are not globally comparable numeric values: equality is not required, numeric skew is not evaluated, and the floor/epoch proof is the correctness fence. The floor and composite-identity details remain owned by the [Identifier Glossary](../../system-architecture-identifier-glossary.md#cross-service-causal-read-fence-identity) and [ADR 0059](../../decisions/adr-0059-causal-floor-cross-service-presentation-reads.md). The current `ResolveLook` request/proto path remains floor-free, so this propagation and proof are target behavior rather than current implementation.
- The target-state `LookResult` should stay explicitly sectioned rather than collapsing everything into one mixed list. At minimum it should preserve distinct sections for room/world snapshot data, exits, visible occupants, visible room-ground items from the room-attached container, and later optional overlays such as combat, hazards, or ambient scripted notices.
- A dedicated `LookResultRenderer` remains useful for local development, diagnostics, and test fixtures, but the canonical player-facing transcript is owned by Game Session. Game Logic's durable contract is the structured `LookResult`, not a rendered text payload. The default text renderer should flatten room prose, visible occupants, and visible room-ground items into one classic descriptive block beneath the room title rather than rendering a sparse line-by-line inventory of sections.
- Downstream errors from World or Entity services are labeled (`WorldManagement`, `EntityManagement`) so they surface as precise error codes such as `ROOM_NOT_FOUND`, `WORLD_UNAVAILABLE`, and `ENTITY_UNAVAILABLE` when Game Session formats Telnet and WebSocket replies.
- Game Logic is the orchestration boundary for these gameplay reads; downstream services on the hot path should answer from owned state, caches, or caller-supplied references rather than recursively building additional steady-state fan-out trees.
- Game Logic must not depend on reconnect-oriented rendered transcript state. Reconnect transcript restoration is a Game Session presentation concern. No separate Game Logic-owned room-view cache or cache-key family is defined here; Game Session retains ownership of the presentation-only `view:room-look:*` cache.
- `LOOK` should describe what is immediately visible in the current room. Visible bags, corpses, chests, or similar containers may appear as room-ground items, but nested container contents should not be expanded inline by default; later item/container commands can inspect those contents explicitly.
- The standard `QUICKLOOK` command should be treated as another built-in room-view rendering mode over the same structured result: it keeps occupants, room-ground items, exits, and later overlays, but omits the room-description prose for faster redraws.

## Communication Flow

- Game Session channels authenticated world/gameplay communication commands through Game Logic, supplying the same gameplay identity and world context that guard `LOOK`. This path is not a universal communication ingress.
- `SendCommunication` is the gameplay communication action for types whose meaning depends on topology, perception, abilities, effects, authored interception, or other gameplay state. It produces one bounded resolved plan rather than claiming end-to-end delivery. Plan creation enforces the [ADR 0147 audience ceiling](../../decisions/adr-0147-explicit-communication-classes-and-owner-delivery.md#world-and-gameplay-communication): `recipient_views`, including the actor and every ordinary-recipient or observer view, count under the shared platform ceiling. Complete authoritative resolution above the effective cap returns `AUDIENCE_LIMIT_EXCEEDED` before Social persistence or Game Session delivery; it is never truncated or partially committed. The current implementation does not yet enforce or prove this cap.
- Game Logic validates message length and the published communication-type contract, resolves the target/scope and candidate audience, applies gameplay interception/perception rules, and dispatches the plan to Social & Groups for social authorization, moderation, history, and delivery-state consequences. Game Session owns final delivery to connected gameplay transports.
- The gameplay plan contains stable identity, type/version, authorized audience or target facts, candidate-specific view classes and safe fields, and freshness/fence evidence. Social & Groups cannot broaden a view, infer spatial observers, or treat a plan/history commit as connected-player delivery.
- The standard gameplay built-ins are:
  - `say` targeting the current room;
  - `whisper` targeting one character in the current room, with interception only through a published mechanic; and
  - gameplay `tell` targeting one character directly outside room scope by default and non-observable unless a distinct type explicitly says otherwise.
- `shout` is a future built-in. A published game profile must define a named, bounded topology scope and operator fanout caps before it is implemented. The platform does not choose area-wide, region-wide, radius-based, or map-wide semantics, and no area/region policy is invented here.
- Observer views use a closed type-declared vocabulary such as `NONE`, `METADATA_ONLY`, a named redacted/partial class, or `FULL`. Unknown fields are excluded and a capability or authored mechanic may select only a view permitted by the exact communication type version. Missing, stale, contradictory, or oversized topology/effect evidence fails without over-delivery. Active [ADR 0150](../../decisions/adr-0150-closed-observer-views-and-profile-scoped-shout.md) records this mapping from reviewed archive record `archive-ADR-0137`.
- Account messaging, ordinary group/channel communication, browser social actions, and ordinary account/social mail enter Social & Groups directly after authentication, membership/privacy, and moderation checks. An in-game adapter may call Social but does not route private platform content through Game Logic or make it visible to tenant-authored scripts. Operator/system communication enters through the service owning the originating operation.
- The resulting gameplay metadata (candidate views, NPC echoes, speaker/target metadata, recipient roles, and perception classification) is returned to Game Session. The current adapter populates `shared.v1.ErrorDetail` for delivery failures so the text protocol can emit `ERROR COMMUNICATION_NOT_DELIVERED` or equivalent stable responses; target transport/result classification follows the gRPC owner contract, with exact mappings still an implementation/proof gap.
- For current room-local `say`, that returned metadata includes the actor plus each live player listener in the resolved room audience so Game Session can render canonical sender and listener prose from one shared communication result instead of treating `say` as actor-only success text.
- This gameplay path validates authenticated context before `SendCommunication`; Social-channel availability is independently owned and does not require a running Game Logic path. See active [ADR 0147](../../decisions/adr-0147-explicit-communication-classes-and-owner-delivery.md), mapped from reviewed archive record `archive-ADR-0134`.

### Current scope versus future communication semantics

- The live communication contract is now `SendCommunication`, which carries a communication type plus target metadata through one shared gameplay path.
- This contract should not be treated as the final completed abstraction for all communication types. Future work should continue to separate:
  - the communication act (`say`, `whisper`, `shout`, `tell`, guild/channel/system message, emote-like narration),
  - the audience scope or target object (same room, directed target, named profile topology, account/group/channel),
  - gameplay candidate resolution in Game Logic versus social audience/membership resolution in Social & Groups,
  - and presentation/rendering style (for example, `Alice whispers to Bob...` versus a generic room broadcast).
- In particular, `whisper` and `tell` preserve target-directed delivery semantics rather than collapsing into generic room chat, gameplay `tell` is non-observable by default, and future `shout` behavior depends on a profile-defined named topology rather than platform-global scope.
- When later slices land, prefer evolving this pathway with richer target/scope resolution and presentation metadata rather than adding one bespoke pipeline per verb.

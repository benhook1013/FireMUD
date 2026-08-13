# Game Logic Service API Contracts

This document defines the Game Logic Service REST and gRPC surfaces, exposure class, and command-specific contracts for the current gameplay slice.

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
- `SendCommunication` accepts `tenant_id`, `session_id`, `character_id`, speaker metadata, normalized `text`, and explicit target/scope metadata. It is the shared gameplay communication contract for the current built-in modes and should evolve toward richer communication-intent handling rather than splintering into one bespoke API per verb.
- `PickupVisibleRoomItem` and `DropCarriedItem` are the player-facing item selector RPCs for the current `GET` and `DROP` command path. Game Session sends the current session/game/room context, raw item reference, and quantity; Game Logic resolves names, visible refs, container identities, and stack-family refs against the appropriate visible holder before delegating the concrete mutation to Entity Management.
- `ApplyActorCondition` is the current self-scoped gameplay orchestration path for the first actor-state mutation. It is not the target-state generic cross-actor effect API.
- All application-level failures are returned via `shared.v1.ErrorDetail` while the gRPC status remains `OK`; `grpc.app_error` must be recorded with the error code.

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

- Game Logic resolves each declared action target set at durable execution time against the source actor, frozen release declaration, Entity Management actor state, and World Management occupancy state. `SOURCE` resolves implicitly; each authored set loads its release-pinned DML `TargetingPolicy`, uses its typed platform candidate selector, evaluates its referenced `ObservationPolicy.observableWhen`, then evaluates `eligibleWhen` for observable candidates. Initial authored sets resolve relative to the action source only.
- Game Logic compiles the frozen target-set policy predicates into one bounded `TargetingFactSnapshot` request per documented fact owner for the source and bounded candidate sets. A snapshot returns only the referenced facts and its owner-specific version or fence token; it is not a generic entity, relationship, or world-state dump, and resolution must not make per-predicate or per-candidate RPC calls. A provider read failure or unavailable snapshot fails closed.
- The resulting plan contains the source actor, resolved canonical target actor ids by target-set key, action/release snapshot, idempotent effect id, typed effect declarations bound to those sets, and target-resolution evidence, including frozen policies, fact snapshots, owner tokens, stage results, and decisive predicate evidence. A rejected action outcome retains the same internal policy snapshot, failed stage, and evidence when no plan can be formed.
- Before any source cost, cooldown, or effect mutation commits, each owner of a material targeting fact validates its recorded version or fence token. A mismatch discards the unresolved plan and re-resolves it under the same effect id; it must not retain a stale selected target or substitute a fallback candidate. Entity Management applies only a validated approved plan and never parses player-facing target text or recreates target policy from a partial request.
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
- `ResolveLook` consumes the causal floor issued by Game Session, including the operational `regionId` obtained from durable region authority. Below-floor or unavailable evidence is retried as a whole composition within the original request deadline using configurable attempt-count and backoff settings; an epoch change restarts the composition with a fresh Game Session floor and region, contradictory tenant/game/region/room/epoch scope fails closed, and deadline expiry returns retryable unavailable without stale composition.
- The target-state `LookResult` should stay explicitly sectioned rather than collapsing everything into one mixed list. At minimum it should preserve distinct sections for room/world snapshot data, exits, visible occupants, visible room-ground items from the room-attached container, and later optional overlays such as combat, hazards, or ambient scripted notices.
- A dedicated `LookResultRenderer` remains useful for local development, diagnostics, and test fixtures, but the canonical player-facing transcript is owned by Game Session. Game Logic's durable contract is the structured `LookResult`, not a rendered text payload. The default text renderer should flatten room prose, visible occupants, and visible room-ground items into one classic descriptive block beneath the room title rather than rendering a sparse line-by-line inventory of sections.
- Downstream errors from World or Entity services are labeled (`WorldManagement`, `EntityManagement`) so they surface as precise error codes such as `ROOM_NOT_FOUND`, `WORLD_UNAVAILABLE`, and `ENTITY_UNAVAILABLE` when Game Session formats Telnet and WebSocket replies.
- Game Logic is the orchestration boundary for these gameplay reads; downstream services on the hot path should answer from owned state, caches, or caller-supplied references rather than recursively building additional steady-state fan-out trees.
- Game Logic must not depend on reconnect-oriented rendered transcript state. Reconnect transcript restoration is a Game Session presentation concern. If FireMUD later needs a validated reusable room-view read cache for normal `LOOK` performance, that cache should sit near Game Logic orchestration and must be guarded by the same room/entity fence and version checks that protect fresh `ResolveLook` output.
- `LOOK` should describe what is immediately visible in the current room. Visible bags, corpses, chests, or similar containers may appear as room-ground items, but nested container contents should not be expanded inline by default; later item/container commands can inspect those contents explicitly.
- The standard `QUICKLOOK` command should be treated as another built-in room-view rendering mode over the same structured result: it keeps occupants, room-ground items, exits, and later overlays, but omits the room-description prose for faster redraws.

## Communication Flow

- Communication uses the explicit ingress classes in [ADR 0134](../../decisions/adr-0134-explicit-communication-classes-and-owner-delivery.md). Game Session channels authenticated world/gameplay communication commands through Game Logic, supplying the same gameplay identity and world context that guard `LOOK`. Account messaging, ordinary guild/group channels, mail, and browser social interactions enter Social & Groups directly; an in-game command may adapt to those APIs without making the operation a Game Logic action.
- `SendCommunication` is the current shared communication action rather than a permanent `say`-only API surface.
- Game Logic validates message length and gameplay rules, resolves the communication target/scope, applies gameplay interception/perception rules, and emits a bounded resolved communication plan rather than rendering chat or opening player transports locally. Social & Groups applies relevant social-audience, moderation, history, and durable-delivery responsibilities; Game Session owns final connected-client transport delivery.
- The longer-term communication model should generalize this flow from a `BroadcastSay`-style API to a communication-intent pathway with:
  - a game-configured communication type definition,
  - explicit target/scope objects such as room, area, region, group, or direct target,
  - recipient resolution owned by those targets/scopes,
  - and per-recipient presentation metadata.
- In-world communication should therefore target the room/area/etc. itself rather than precomputing a final flat recipient list in the sender path. That allows target-owned resolution to include ordinary listeners plus observers/interceptors such as eavesdroppers, spies, magical listeners, or other game-specific mechanics.
- The first standard built-ins should be:
  - `say` targeting the current room,
  - `whisper` targeting one character in the current room,
  - gameplay `tell` targeting one character directly outside room scope by default.
- Gameplay `tell` may remain on this path when game abilities or world rules apply, but the standard type has no observer path. Interception requires a deliberately distinct published communication type and mechanic. Account-to-account direct messaging and mail are Social & Groups operations even when invoked through an in-game adapter. Tenant-authored DSL cannot reclassify private platform communication for gameplay-script inspection.
- `shout` remains unimplemented until a game or default profile declares a named, published, bounded topology scope for that communication type. It has no universal area, region, radius, or map meaning: one profile may define area-wide `SHOUT`, while another defines map-wide `SHOUT`. The area-versus-region taxonomy can be settled when concrete profiles require it rather than being inferred now.
- Only world/gameplay communication is eligible for gameplay observation. Account messages, mail, ordinary social channels, and browser interactions never acquire observer paths through this API. Gameplay `tell` has no observer path by default.
- Each communication type version declares a small closed set of allowed observer-view classes such as none, metadata-only, concretely defined redacted/partial, or full, plus the exact metadata fields safe for each non-full view. This is a ceiling rather than a grant and is not a free-form policy DSL. Add a partial-content class only when one concrete authored mechanic defines it.
- Game Logic resolves authoritative topology, capabilities, senses, effects, and authored mechanics into bounded candidate-specific authorized views. `WHISPER` interception requires an explicit published eavesdropping, magical-listening, or equivalent mechanic; the current boolean and entity-flag seam is only initial implementation.
- Missing, stale, contradictory, or oversized resolution fails without over-delivery. Game Session delivers the authorized views, while Social applies social, moderation, and history constraints without inferring spatial observers or broadening a view.
- Named topology scopes and resolved audiences are subject to operator fanout caps. Large permitted audiences use stable bounded/chunkable delivery with backpressure and explicit completion or partial-failure state; over-limit paths emit typed outcomes, diagnostics, and metrics rather than silently truncating recipients. See [ADR 0137](../../decisions/adr-0137-closed-observer-views-and-profile-scoped-shout.md).
- The resulting delivery metadata (recipient list, NPC echoes, speaker/target metadata, recipient roles, and perception classification) is returned to Game Session, while failures populate `shared.v1.ErrorDetail` so the text protocol can emit `ERROR COMMUNICATION_NOT_DELIVERED` or equivalent stable responses.
- For current room-local `say`, that returned metadata includes the actor plus each live player listener in the resolved room audience so Game Session can render canonical sender and listener prose from one shared communication result instead of treating `say` as actor-only success text.
- This pathway mirrors the `LOOK` guard: unauthenticated requests never reach `SendCommunication`, and Social & Groups outages surface as structured `PERMISSION_DENIED` or `UNAVAILABLE` errors so Game Session can keep stage-aware gating predictable.

### Current scope versus future communication semantics

- The live communication contract is now `SendCommunication`, which carries a communication type plus target metadata through one shared gameplay path.
- This contract should not be treated as the final completed abstraction for all communication types. Future work should continue to separate:
  - the communication act (`say`, `whisper`, `shout`, `tell`, guild/channel/system message, emote-like narration),
  - the audience scope or target object (same room, directed target, nearby area, map/region, continent/world, account/group/channel),
  - the recipient-resolution rules owned by that scope, including observer/interceptor resolution,
  - and presentation/rendering style (for example, `Alice whispers to Bob...` versus a generic room broadcast).
- In particular, `whisper` and `tell` preserve target-directed delivery semantics rather than collapsing into generic room chat. Gameplay `tell` has no observer path by default. A future `shout` derives its named bounded topology scope from the selected communication type/profile, so area-wide in one profile and map-wide in another are both valid rather than aliases for one platform-global scope.
- When later slices land, prefer evolving this pathway with richer target/scope resolution and presentation metadata rather than adding one bespoke pipeline per verb.
- Operator and platform-system communication enters through the service that owns the originating authorization and audit contract. It uses typed owner handoffs and enters Game Logic only when it deliberately creates a gameplay-world effect.

## Implementation Status

### LOOK Slice

- Live: `ResolveLook` is wired into the command pipeline, orchestrates World Management snapshots and Entity Management listings, hands the structured `LookResult` to `LookResultRenderer`, and publishes the telemetry captured in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- Stubbed: room and entity context still comes from the deterministic LOOK fixtures so the canonical transcript remains deterministic; scripted descriptions, complex lighting, and dynamic hazard cues are not yet integrated.
- Deferred: future slices will enrich prose, annotate `LookResult` with combat and effect metadata, and surface additional visibility hints once the core text shape proves stable.

### Chat Slice

- Live: `SendCommunication` accepts authenticated `SAY`, `WHISPER`, and `TELL` payloads, validates length, resolves room-scoped or direct-target delivery metadata, and forwards the normalized message to the Social & Groups stub with explicit type and recipient information. The API returns speaker/target delivery metadata, structured per-recipient view metadata, and `shared.v1.ErrorDetail` codes. The present boolean and entity observer flag are only an initial seam, not proof of the versioned closed-view, authored-mechanic, safe-metadata, or freshness contract in ADR 0137.
- Stubbed: downstream delivery still uses the Social & Groups regression stub that records `SendMessage` calls and echoes success while cross-service WebSocket and Telnet tests assert the canonical actor transcript and explicit recipient metadata.
- Deferred: first-party/MCP-aware recipient presentation, richer NPC replies, authored `WHISPER` interception, closed observer-view declarations, authoritative freshness fencing, cross-pod and chunked recipient delivery, profile-defined area/map/region propagation including `SHOUT`, channel filters, and profanity-escalation behavior will land in later slices once the foundational communication flow proves stable.

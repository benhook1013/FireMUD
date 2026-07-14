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

- Game Logic resolves targets at durable execution time against the source actor, frozen release declaration, Entity Management actor state, and World Management occupancy state. It loads the release-pinned DML `TargetingPolicy`, uses its typed platform candidate selector, evaluates its referenced `ObservationPolicy.observableWhen`, then evaluates `eligibleWhen` for observable candidates.
- Game Logic compiles the frozen policy predicates into one bounded `TargetingFactSnapshot` request per documented fact owner for the source and bounded candidate set. A snapshot returns only the referenced facts and its owner-specific version or fence token; it is not a generic entity, relationship, or world-state dump, and resolution must not make per-predicate or per-candidate RPC calls. A provider read failure or unavailable snapshot fails closed.
- The resulting plan contains the source actor, canonical target actor ids, action/release snapshot, idempotent effect id, typed effect declarations, and target-resolution evidence, including the frozen policy, fact snapshots, owner tokens, stage results, and decisive predicate evidence. A rejected action outcome retains the same internal policy snapshot, failed stage, and evidence when no plan can be formed.
- Before any source cost, cooldown, or effect mutation commits, each owner of a material targeting fact validates its recorded version or fence token. A mismatch discards the unresolved plan and re-resolves it under the same effect id; it must not retain a stale selected target or substitute a fallback candidate. Entity Management applies only a validated approved plan and never parses player-facing target text or recreates target policy from a partial request.
- Required same-region targets are applied atomically as one local Entity Management mutation. Cross-region target legs use durable coordinator/follow-up outcomes; before a remote leg mutates, its region re-reads and validates the facts it owns against current local authority rather than treating origin-side evidence as permanent authorization. The final gameplay outcome reports each leg rather than claiming a distributed transaction.
- Platform targeting modes are typed candidate-selection grammar. A reusable published `TargetingPolicy` owns its selector, observation-policy reference, and game-specific range/relationship/status/targetability rules; action or standard-path binding DML owns selection cardinality and optional-target handling. A game's standard target paths resolve through named release-pinned default-policy bindings. Visibility and targetability are optional game facts, never universal actor fields.
- A failed observation predicate is always non-disclosing to the player: it has the same safe unavailable outcome as an absent target. A failed eligibility predicate may emit only the policy's approved safe feedback. Internal target-resolution evidence remains available for outcome/audit reasoning and must never be reconstructed from player output.
- The action or standard-path binding owns a release-pinned `TargetSelection` over the policy's eligible candidates: `EXACTLY_ONE`, `UP_TO_N` with explicit bounds, or `ALL_ELIGIBLE` within an operator ceiling. Candidate ordering is canonical and stable; later randomized selection must be effect-id-seeded. The selected targets, selection declaration, and applied ceiling are included in target-resolution evidence.

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
- The target-state `LookResult` should stay explicitly sectioned rather than collapsing everything into one mixed list. At minimum it should preserve distinct sections for room/world snapshot data, exits, visible occupants, visible room-ground items from the room-attached container, and later optional overlays such as combat, hazards, or ambient scripted notices.
- A dedicated `LookResultRenderer` remains useful for local development, diagnostics, and test fixtures, but the canonical player-facing transcript is owned by Game Session. Game Logic's durable contract is the structured `LookResult`, not a rendered text payload. The default text renderer should flatten room prose, visible occupants, and visible room-ground items into one classic descriptive block beneath the room title rather than rendering a sparse line-by-line inventory of sections.
- Downstream errors from World or Entity services are labeled (`WorldManagement`, `EntityManagement`) so they surface as precise error codes such as `ROOM_NOT_FOUND`, `WORLD_UNAVAILABLE`, and `ENTITY_UNAVAILABLE` when Game Session formats Telnet and WebSocket replies.
- Game Logic is the orchestration boundary for these gameplay reads; downstream services on the hot path should answer from owned state, caches, or caller-supplied references rather than recursively building additional steady-state fan-out trees.
- Game Logic must not depend on reconnect-oriented rendered transcript state. Reconnect transcript restoration is a Game Session presentation concern. If FireMUD later needs a validated reusable room-view read cache for normal `LOOK` performance, that cache should sit near Game Logic orchestration and must be guarded by the same room/entity fence and version checks that protect fresh `ResolveLook` output.
- `LOOK` should describe what is immediately visible in the current room. Visible bags, corpses, chests, or similar containers may appear as room-ground items, but nested container contents should not be expanded inline by default; later item/container commands can inspect those contents explicitly.
- The standard `QUICKLOOK` command should be treated as another built-in room-view rendering mode over the same structured result: it keeps occupants, room-ground items, exits, and later overlays, but omits the room-description prose for faster redraws.

## Communication Flow

- Game Session channels authenticated communication commands through Game Logic, supplying the same gameplay identity and world context that guard `LOOK`.
- `SendCommunication` is the current shared communication action rather than a permanent `say`-only API surface.
- Game Logic validates message length and communication rules, resolves the communication target/scope, applies gameplay interception/perception rules, and dispatches to Social & Groups rather than rendering chat locally.
- The longer-term communication model should generalize this flow from a `BroadcastSay`-style API to a communication-intent pathway with:
  - a game-configured communication type definition,
  - explicit target/scope objects such as room, area, region, group, or direct target,
  - recipient resolution owned by those targets/scopes,
  - and per-recipient presentation metadata.
- In-world communication should therefore target the room/area/etc. itself rather than precomputing a final flat recipient list in the sender path. That allows target-owned resolution to include ordinary listeners plus observers/interceptors such as eavesdroppers, spies, magical listeners, or other game-specific mechanics.
- The first standard built-ins should be:
  - `say` targeting the current room,
  - `whisper` targeting one character in the current room,
  - `tell` targeting one character directly outside room scope by default.
- `shout` should remain a future built-in and should not be implemented until the game-settings model can describe topology-dependent scope such as region-wide versus map-wide propagation.
- Observer perception should be determined by layered rules rather than by one owner alone:
  - the communication type defines the baseline observability contract and what kinds of recipient views are even possible,
  - the target/scope determines which ordinary listeners and observer/interceptor candidates qualify in this location or social scope,
  - and recipient capabilities or effects determine whether that qualifying recipient receives full content, partial content, or only metadata such as “someone whispered here.”
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
- In particular, `whisper` and `tell` preserve target-directed delivery semantics rather than collapsing into generic room chat, and future `shout` behavior may depend on world-topology concepts such as area, map, or region propagation.
- When later slices land, prefer evolving this pathway with richer target/scope resolution and presentation metadata rather than adding one bespoke pipeline per verb.

## Implementation Status

### LOOK Slice

- Live: `ResolveLook` is wired into the command pipeline, orchestrates World Management snapshots and Entity Management listings, hands the structured `LookResult` to `LookResultRenderer`, and publishes the telemetry captured in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- Stubbed: room and entity context still comes from the deterministic LOOK fixtures so the canonical transcript remains deterministic; scripted descriptions, complex lighting, and dynamic hazard cues are not yet integrated.
- Deferred: future slices will enrich prose, annotate `LookResult` with combat and effect metadata, and surface additional visibility hints once the core text shape proves stable.

### Chat Slice

- Live: `SendCommunication` accepts authenticated `SAY`, `WHISPER`, and `TELL` payloads, validates length, resolves room-scoped or direct-target delivery metadata, and forwards the normalized message to the Social & Groups stub with explicit type and recipient information. The API returns speaker/target delivery metadata, structured per-recipient view metadata, and `shared.v1.ErrorDetail` codes so Game Session can render the canonical transcript and later recipient-delivery slices can consume the same authoritative recipient-view model.
- Stubbed: downstream delivery still uses the Social & Groups regression stub that records `SendMessage` calls and echoes success while cross-service WebSocket and Telnet tests assert the canonical actor transcript and explicit recipient metadata.
- Deferred: first-party/MCP-aware recipient presentation, richer NPC replies, area/map/region propagation rules, channel filters, and profanity-escalation behavior will land in later slices once the foundational communication flow proves stable.

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
- `BroadcastSay` accepts `tenant_id`, `session_id`, `character_id`, and a `RoomInstanceRef` (`tenant_id`, `game_instance_id`, `room_instance_id`), plus normalized `text` and an alias indicator (`SAY` / `YELL` / `WHISPER`). The handler validates length, enforces room chat controls, and returns delivery metadata (recipient identifiers, NPC echoes, optional acknowledgements) along with structured status codes so Game Session can render the canonical response.
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
- Game Logic must not own or read the reconnect-oriented rendered room-view cache. That cache is a Game Session presentation concern and is always derived from a successful `ResolveLook`.
- `LOOK` should describe what is immediately visible in the current room. Visible bags, corpses, chests, or similar containers may appear as room-ground items, but nested container contents should not be expanded inline by default; later item/container commands can inspect those contents explicitly.
- A future standard `QUICKLOOK` command should be treated as another built-in room-view rendering mode over the same structured result: it keeps occupants, room-ground items, exits, and later overlays, but omits the room-description prose for faster redraws.

## SAY Broadcast Flow

- Game Session channels authenticated commands through `BroadcastSay`, supplying the same `RoomInstanceRef` context (`tenantId`, `gameInstanceId`, `roomInstanceId`) that guards `LOOK`.
- The command parser normalizes `SAY`, `YELL`, and `WHISPER` aliases before forwarding trimmed text so downstream services can enforce one validation contract.
- Game Logic validates message length and room-chat rules, determines the occupied room, and delegates delivery to Social & Groups rather than rendering chat locally.
- The resulting delivery metadata (recipient list, NPC echoes) is returned to Game Session, while failures populate `shared.v1.ErrorDetail` so the text protocol can emit `ERROR SAY_NOT_DELIVERED` or equivalent stable responses.
- This pathway mirrors the `LOOK` guard: unauthenticated requests never reach `BroadcastSay`, and Social & Groups outages surface as structured `PERMISSION_DENIED` or `UNAVAILABLE` errors so Game Session can keep `ERROR NOT_AUTHENTICATED` gating predictable.

### Current scope versus future speech semantics

- The current `BroadcastSay` contract is intentionally scoped to room-local speech and preserves only a lightweight alias distinction (`SAY`, `YELL`, `WHISPER`) so the first chat slice can prove the end-to-end gameplay path.
- This contract should not be treated as the final abstraction for all communication types. Future speech work should separate:
  - the communication act (`say`, `whisper`, `shout`, `tell`, guild/channel/system message, emote-like narration),
  - the audience scope (same room, directed target, nearby area, map/region, continent/world, account/group/channel),
  - and presentation/rendering style (for example, `Alice whispers to Bob...` versus a generic room broadcast).
- In particular, future `WHISPER` and `TELL` behavior must preserve target-directed delivery semantics rather than collapsing into generic room chat, and future `SHOUT`-style behavior may depend on world-topology concepts such as area, map, or region propagation.
- When those later slices land, prefer evolving this pathway toward a more generic communication envelope plus explicit audience/propagation metadata rather than adding one bespoke pipeline per verb.

## Implementation Status

### LOOK Slice

- Live: `ResolveLook` is wired into the command pipeline, orchestrates World Management snapshots and Entity Management listings, hands the structured `LookResult` to `LookResultRenderer`, and publishes the telemetry captured in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- Stubbed: room and entity context still comes from the deterministic LOOK fixtures so the canonical transcript remains deterministic; scripted descriptions, complex lighting, and dynamic hazard cues are not yet integrated.
- Deferred: future slices will enrich prose, annotate `LookResult` with combat and effect metadata, and surface additional visibility hints once the core text shape proves stable.

### Chat Slice

- Live: `BroadcastSay` accepts authenticated `SAY` / `YELL` / `WHISPER` payloads, validates length, aggregates recipient and NPC metadata, and forwards the normalized message to the Social & Groups stub. The API returns delivery metadata and `shared.v1.ErrorDetail` codes so Game Session can render the canonical transcript and surface `gamesession.command.say.*` instrumentation.
- Stubbed: delivery currently uses the Social & Groups regression stub that records `SendMessage` calls and echoes success while cross-service WebSocket and Telnet tests assert the structured response before adding a richer narrative layer.
- Deferred: richer NPC replies, directed/private delivery semantics, localized listening areas, area/map/region propagation rules, channel filters, and profanity-escalation behavior will land in later slices once the foundational flow proves stable.

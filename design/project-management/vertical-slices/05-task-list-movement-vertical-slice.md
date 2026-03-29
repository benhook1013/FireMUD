# Movement Vertical Slice Task List

## Goal and Status

Goal: extend the current playable text-command loop so authenticated players can move between rooms using data-driven exits, have their location updated authoritatively, and automatically receive a fresh `LOOK` result after successful movement across both WebSocket and Telnet transports. Status: completed for this PR. Movement is now wired end-to-end through Game Session, Game Logic, and World Management, with WebSocket and Telnet parity coverage plus reconnect-after-move coverage.

This checklist builds on the **Login and Session**, **Data-Driven LOOK**, and **Chat & SAY** slices. It turns the existing room snapshots, exit metadata, and movement primitives into a real player-facing `MOVE` / `GO` loop that changes location and immediately reflects the new room state.

## 1. Protocol, UX, and Design Alignment for Movement

- [x] Re-read the [Game Session Service protocols](../../architecture/microservices/game-session-service/protocols.md#minimal-text-command-protocol), [Game Logic Service](../../architecture/microservices/game-logic-service/README.md), and [World Management Service](../../architecture/microservices/world-management-service/README.md) docs to confirm the intended ownership split for movement, exit validation, and room-state refresh.
- [x] Decide and document the canonical text protocol for movement, including whether the MVP surface is `MOVE <direction>`, directional aliases (`NORTH`, `SOUTH`, etc.), and/or a short `GO <direction>` alias.
- [x] Add at least one Telnet and one WebSocket transcript showing successful movement and failed movement (`ERROR INVALID_EXIT` or equivalent), with successful movement automatically followed by the new room's `OK LOOK` payload.
- [x] Update the Game Session and Game Logic design docs so they explicitly describe the movement request flow: authenticated command ingress -> Game Logic movement resolution -> world/location update -> refreshed `LOOK` output.

## 2. World Management Service: Exit and Location Mutation Contract

- [x] Before changing this service for the slice, run `./gradlew :world-management-service:test` and stabilize the baseline if necessary.
- [x] Review the current room snapshot / exit contract and define the smallest authoritative movement-facing API needed for this slice, such as validating an exit from the current room and returning the destination room instance plus any required metadata.
- [x] If the current APIs are insufficient, add or refine a gRPC method in World Management that performs or supports authoritative room-transition updates for a character/session within the current tenant/game instance.
- [x] Ensure the movement-facing contract keeps room topology authoritative in World Management and does not shift exit validation logic into Game Session.
- [x] Add unit/integration tests in World Management covering at least: valid directional exit, invalid direction, and missing-room / missing-exit behavior for the deterministic test world used in current slices.

## 3. Game Logic Service: Movement Resolution

- [x] Before changing this service for the slice, run `./gradlew :game-logic-service:test` and stabilize the baseline if necessary.
- [x] Introduce or refine a movement-oriented gRPC method (for example `ResolveMove`) that accepts `tenantId`, `gameInstanceId`, `sessionId`, `characterId`, current room identity, and the requested direction or exit selector.
- [x] Implement the Game Logic movement handler so it normalizes player input (`MOVE north`, `GO NORTH`, bare `north` if supported), validates it against authoritative world exit data, and returns a structured movement result containing destination room information or an application error.
- [x] Reuse the existing movement/pathfinding primitives only where appropriate for this MVP movement step; do not let this slice balloon into full travel/pathfinding or combat adjacency logic.
- [x] Ensure a successful movement result includes enough context for Game Session to trigger an immediate fresh `LOOK` for the destination room without guessing at cached room state.
- [x] Add unit tests covering successful movement, invalid direction, blocked/missing exit, and downstream World Management failures.

## 4. Game Session Service: Text Command Wiring and Auto-LOOK

- [x] Before changing this service for the slice, run `./gradlew :game-session-service:test` and stabilize the baseline if necessary.
- [x] Extend the current text command interpreter so movement commands are treated as authenticated gameplay commands and flow through the same session guard already used by `LOOK` and `SAY`.
- [x] Add a dedicated movement handler that calls the Game Logic movement API, maps application failures into stable text errors, and on success updates the session's current room binding before emitting the destination room's `LOOK` result.
- [x] Keep the success transcript canonical and simple: movement acknowledgement if needed, followed by the destination `OK LOOK` payload. Do not invent a second competing room-description format for movement.
- [x] Emit movement-related metrics and logs (for example `gamesession.command.move.invocations` and `gamesession.command.move.failures`) with high-level error tags so operators can distinguish invalid exits from backend failures.
- [x] Add unit/integration tests in Game Session for successful movement, invalid exit, unauthenticated movement, and auto-LOOK behavior after a successful room change.

## 5. Cross-Service End-to-End Tests (WebSocket and Telnet)

- [x] Add a WebSocket-focused cross-service regression that performs `LOGIN` / `PLAY` / `LOOK`, issues a movement command, and asserts the returned room description now reflects the destination room rather than the origin room.
- [x] Add a Telnet-focused variant through TCP Proxy and Gateway that exercises the same movement path and confirms protocol parity with the WebSocket flow.
- [x] Cover at least one success case and one failure case (`ERROR INVALID_EXIT`, `ERROR ROOM_NOT_FOUND`, or equivalent) and ensure failures do not disconnect the client.
- [x] Assert the movement path traverses the intended pipeline (Game Session -> Game Logic -> World Management) using logs, metrics, or gRPC interceptors similar to the existing LOOK/SAY slices.
- [x] Wire these regressions into the existing `crossServiceTest` targets and mention them in the relevant docs so the slice can be rerun easily.

## 6. Developer Workflows, Smoke Tests, and Documentation Updates

- [x] Add or update a smoke test script (or documented manual sequence) that demonstrates `LOGIN` / `PLAY` / `LOOK` / movement over WebSocket, including the expected destination-room transcript.
- [x] Add a second Telnet-oriented example showing the same movement flow through TCP Proxy and Gateway.
- [x] Update the Game Session, Game Logic, and World Management design docs with a short implementation-status note for the movement slice, clarifying what is live, stubbed, and deferred.
- [x] Update any existing gameplay examples that imply room state is static once `LOOK` works; after this slice, examples should reflect that room state changes through movement and is refreshed immediately.

## 7. Final QA Checklist

- [x] Run the relevant Game Session, Game Logic, World Management, and cross-service test targets for the movement slice and confirm they pass.
- [x] Manually verify one happy-path move and one invalid-exit move over both WebSocket and Telnet.
- [x] Confirm successful movement immediately yields the destination-room `LOOK` transcript and that metrics/logs make it easy to distinguish player mistakes from backend failures.

---

## Deferred Follow-Up

- A future follow-up slice can extend movement beyond directional room travel into broader travel/pathfinding, richer failure semantics, or additional transport/UI polish if those become priorities.

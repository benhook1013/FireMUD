# Data-Driven LOOK Vertical Slice Task List

This checklist builds on the **Telnet to Gameplay** and **Login and Session** slices by replacing the hard-coded `LOOK` behavior in Game Session with a fully data-driven implementation that pulls room descriptions, exits, and visible entities from the World, Entity, and Game Logic services. Each task is intentionally scoped so it can be handed to Codex (or a developer) as a single, self-contained chunk of work.

## 1. Protocol, UX, and Design Alignment for LOOK

- [ ] Re-read the [Minimal Text Command Protocol](../architecture/microservices/game-session-service/README.md#minimal-text-command-protocol), [Game Session Service](../architecture/microservices/game-session-service/README.md), [World Management Service](../architecture/microservices/world-management-service/README.md), and [Entity Management Service](../architecture/microservices/entity-management-service/README.md) docs to confirm the intended sources of truth for room layout, entities, and gameplay state.
- [ ] Decide and document the canonical `LOOK` output shape for this slice (room name, short description, long description, exits, and visible entities) including a rough ordering and any truncation rules for large rooms.
- [ ] Update the `Minimal Text Command Protocol` section so `LOOK` is explicitly documented as a data-driven command, including at least one Telnet and one WebSocket transcript that show a realistic room with exits and a couple of entities (players and/or NPCs).
- [ ] Add a short subsection to the Game Session Service design doc describing how `LOOK` requests flow through Game Session → Game Logic → World/Entity, and where caching or projection layers (if any) sit in that pipeline.
- [ ] Ensure the design docs clearly state that `LOOK` requires an authenticated session (reusing the login/session guard from the previous slice) and that unauthenticated clients still receive `ERROR NOT_AUTHENTICATED` when attempting `LOOK`.

## 2. World Management Service: Minimal Room Data for the Slice

- [ ] Define or refine a minimal gRPC API in the World Management Service that can return room metadata needed for `LOOK` (e.g., `GetRoomSnapshot` or equivalent) including room id, name, descriptions, and exits for a given `tenantId` and `roomId`.
- [ ] Add or update the World Management proto files so the room snapshot response includes everything the vertical slice needs but nothing extra (for example, omit combat or scripting hooks that are not yet used by LOOK).
- [ ] Seed a tiny test world in World Management (for example, 3–5 rooms connected in a simple loop) via Flyway migrations, a test-only data initializer, or fixtures referenced by integration tests.
- [ ] Add unit and/or integration tests in `services/world-management-service` that exercise the room snapshot API for the seeded test world, verifying correct exits and descriptions for at least one room used by the vertical slice scenarios.
- [ ] Update the World Management Service README/design docs with a short section that explains how the `LOOK` slice uses the room snapshot API and how to extend the sample world for future slices.

## 3. Entity Management Service: Visible Entity Listings

- [ ] Define or refine a minimal gRPC API in the Entity Management Service that can list entities visible in a room for `LOOK` (players, NPCs, key items), keyed by `tenantId` and `roomId` at minimum.
- [ ] Ensure the entity listing response is structured for gameplay text rendering (e.g., includes stable display names and simple flags like `isPlayer` / `isNpc` / `isItem` instead of eagerly exposing internal stats).
- [ ] Seed a minimal set of entities in the rooms used by the test world (e.g., one demo player character, one NPC, and one visible item) with deterministic IDs and names to keep transcripts stable.
- [ ] Add unit and/or integration tests in `services/entity-management-service` that verify the entity listing API returns the expected entities for the seeded rooms, including empty-room and multi-entity cases.
- [ ] Update the Entity Management Service README/design docs with a subsection describing the `LOOK`-oriented entity listing API and how it fits with broader character and inventory design.

## 4. Game Logic Service: LOOK Aggregation and Formatting

- [ ] Introduce or refine a `LOOK`-oriented gRPC method in the Game Logic Service (for example `ResolveLook` or `GetLookDescription`) that accepts identity context (`tenantId`, `sessionId`, `playerId`, and `roomId`) and returns a high-level `LookResult` DTO.
- [ ] Implement the Game Logic `LOOK` handler so it orchestrates calls to World Management (room snapshot) and Entity Management (visible entities), applies any simple rules needed for this slice (for example, hiding entities the player should not see), and produces a structured `LookResult`.
- [ ] Implement a straightforward text rendering routine in Game Logic that converts `LookResult` into a minimal but pleasant textual description (room name, blank line, room long description, exits line, then entity list), leaving hooks for richer formatting in future slices.
- [ ] Add unit tests around the Game Logic `LOOK` handler and text rendering to cover at least: empty room, room with exits only, room with multiple entities, and error propagation when World or Entity services fail.
- [ ] Document the new Game Logic `LOOK` API and its responsibilities in the Game Logic design docs, clearly distinguishing it from future combat/movement systems.

## 5. Game Session Service: Wiring Text LOOK to Game Logic

- [ ] Replace the current hard-coded `LookCommandHandler` (or equivalent) in Game Session with a flow that calls Game Logic’s `LOOK`/`ResolveLook` gRPC method, passing along `tenantId`, `sessionId`, and `playerId` from the Redis session context.
- [ ] Ensure that the text command interpreter continues to enforce authentication before invoking the `LOOK` gRPC call and that unauthenticated requests still return `ERROR NOT_AUTHENTICATED` without hitting downstream services.
- [ ] Map Game Logic `LookResult` and error codes into the text protocol response format so clients see a consistent `LOOK` description or `ERROR <CODE> <message>` when the call fails (for example, `ERROR ROOM_NOT_FOUND`, `ERROR WORLD_UNAVAILABLE`, `ERROR ENTITY_UNAVAILABLE`).
- [ ] Add Micrometer metrics and structured logs in Game Session for `LOOK` commands (for example `gamesession.command.look.invocations`, `gamesession.command.look.failures`) including tags for `tenantId` and high-level error codes.
- [ ] Add unit and/or integration tests in `services/game-session-service` that exercise the text `LOOK` path end-to-end against a stubbed Game Logic client, verifying correct handling of success, room-not-found, and downstream failure scenarios.

## 6. Cross-Service End-to-End Tests (Telnet and WebSocket)

- [ ] Extend or add a cross-service integration test that starts Game Session, Game Logic, World Management, and Entity Management together (using Testcontainers or in-memory stubs where appropriate) and runs a `LOGIN` + `LOOK` flow over WebSocket, asserting that the response lines match the expected data-driven room description.
- [ ] Add a Telnet-focused cross-service test that reuses the existing TCP Proxy + Gateway harness to perform `SESSION` + `LOGIN` + `LOOK` and confirms that the Telnet transcript matches the WebSocket `LOOK` output aside from transport-specific framing.
- [ ] Ensure the cross-service tests cover at least one happy-path room and one error path (for example, attempting `LOOK` in an invalid or uninitialized room and receiving an appropriate `ERROR` code).
- [ ] Wire the new cross-service tests into Gradle so they can be run explicitly (for example, via a `crossServiceTest` naming convention) without slowing down the default unit test suite.
- [ ] Add a short note in `design/project-management/testing-focus-areas.md` under the command parsing / game logic sections pointing to these data-driven `LOOK` cross-service tests as examples.

## 7. Developer Workflows, Smoke Tests, and Documentation Updates

- [ ] Add or update a smoke test script (or documented curl/WebSocket sequence) that demonstrates `LOGIN` + `LOOK` against the sample world over WebSocket, including the expected room description in the script output or comments.
- [ ] Add a second smoke test or example transcript that demonstrates `SESSION` + `LOGIN` + `LOOK` via Telnet through TCP Proxy and Gateway, verifying that the same room description is returned.
- [ ] Update the Game Session, Game Logic, World Management, and Entity Management design docs to include a short "Implementation status" note for the `LOOK` slice, clarifying what is live, what is stubbed, and what is deferred to future slices (for example, dynamic lighting, line-of-sight, or script-driven room text).
- [ ] Revisit the `Minimal Text Command Protocol` and any existing gameplay examples to ensure they reference the data-driven `LOOK` behavior instead of the original hard-coded room stub, updating examples where necessary.
- [ ] Ensure logging and monitoring docs (including relevant sections under Logging & Admin) mention the new `LOOK`-related metrics and logs so operators know how to debug issues in this path.

---

Note: After completing tasks in this checklist, go back and update the existing per-service task list documents (such as `design/project-management/task-list-game-session-service.md`, `design/project-management/task-list-game-logic-service.md`, `design/project-management/task-list-world-management-service.md`, and `design/project-management/task-list-entity-management-service.md`) and the relevant design docs so duplicated items are reconciled and the architecture documentation reflects the completed vertical slice.

<!--
Prompt for Codex to generate the next vertical slice task list after these items are done:

"Context: We just completed the Login and Session vertical slice described in design/project-management/task-list-login-and-session-vertical-slice.md. Please inspect the current code and design docs, then propose a new markdown task list file under design/project-management/ focused on the next smallest playable/demo slice that follows this flow deeper into the system (for example, data-driven LOOK that integrates World and Entity services, the SAY/chat path through Social & Groups, or more advanced reconnection edge cases). Each task should be small enough to hand to Codex as a single chunk, and the file should end with a note reminding us to reconcile any duplicated items in existing task lists and design docs."
-->


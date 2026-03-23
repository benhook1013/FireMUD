# Data-Driven LOOK Vertical Slice Task List

## Goal and Status

Goal: replace the original hard-coded `LOOK` path with a data-driven flow that uses World and Entity services via Game Logic, producing canonical text output and observability for both WebSocket and Telnet clients. Status: the main LOOK flow and several regression tests are implemented; this document continues to describe the target-state behaviour, with implementation details and current coverage reflected in service design docs and test suites.

This checklist builds on the **Telnet to Gameplay** and **Login and Session** slices by replacing the hard-coded `LOOK` behavior in Game Session with a fully data-driven implementation that pulls room descriptions, exits, and visible entities from the World, Entity, and Game Logic services. Each task is intentionally scoped so it can be handed to Codex (or a developer) as a single, self-contained chunk of work.

## 1. Protocol, UX, and Design Alignment for LOOK

- [x] Re-read the [Minimal Text Command Protocol](../../architecture/microservices/game-session-service/README.md#minimal-text-command-protocol), [Game Session Service](../../architecture/microservices/game-session-service/README.md), [World Management Service](../../architecture/microservices/world-management-service/README.md), and [Entity Management Service](../../architecture/microservices/entity-management-service/README.md) docs to confirm the intended sources of truth for room layout, entities, and gameplay state.
- [x] Decide and document the canonical `LOOK` output shape for this slice (room name, short description, long description, exits, and visible entities) including a rough ordering and any truncation rules for large rooms.
- [x] Update the `Minimal Text Command Protocol` section so `LOOK` is explicitly documented as a data-driven command, including at least one Telnet and one WebSocket transcript that show a realistic room with exits and a couple of entities (players and/or NPCs).
- [x] Add a short subsection to the Game Session Service design doc describing how `LOOK` requests flow through Game Session → Game Logic → World/Entity, and where caching or projection layers (if any) sit in that pipeline.
- [x] Ensure the design docs clearly state that `LOOK` requires an authenticated session (reusing the login/session guard from the previous slice) and that unauthenticated clients still receive `ERROR NOT_AUTHENTICATED` when attempting `LOOK`.

## 2. World Management Service: Minimal Room Data for the Slice

- [x] Before changing this service for the slice, run `./gradlew :world-management-service:test` and either get the existing tests passing or clearly document/temporarily disable any failing tests so the baseline is stable. *(ran successfully prior to these edits; see build log above or `./gradlew :world-management-service:test` locally).*
- [x] Define or refine a minimal gRPC API in the World Management Service that can return room metadata needed for `LOOK` (e.g., `GetRoomSnapshot` or equivalent) including room id, name, descriptions, and exits for a given `tenantId` and `roomId`.
- [x] Add or update the World Management proto files so the room snapshot response includes everything the vertical slice needs but nothing extra (for example, omit combat or scripting hooks that are not yet used by LOOK).
- [x] Provide a tiny deterministic test world in World Management (for example, 3–5 rooms connected in a simple loop) via fixtures or a test-only data initializer referenced by integration tests.
- [x] Add unit and/or integration tests in `services/world-management-service` that exercise the room snapshot API for the deterministic test world, verifying correct exits and descriptions for at least one room used by the vertical slice scenarios.
- [x] Update the World Management Service README/design docs with a short section that explains how the `LOOK` slice uses the room snapshot API and how to extend the sample world for future slices.

## 3. Entity Management Service: Visible Entity Listings

- [x] Before changing this service for the slice, run `./gradlew :entity-management-service:test` and either get the existing tests passing or clearly document/temporarily disable any failing tests so the baseline is stable. *(ran successfully prior to these edits; see build log above or `./gradlew :entity-management-service:test` locally).*
- [x] Define or refine a minimal gRPC API in the Entity Management Service that can list entities visible in a room for `LOOK` (players, NPCs, key items), keyed by `tenantId` and `roomId` at minimum.
- [x] Ensure the entity listing response is structured for gameplay text rendering (e.g., includes stable display names and simple flags like `isPlayer` / `isNpc` / `isItem` instead of eagerly exposing internal stats).
- [x] Seed a minimal set of entities in the rooms used by the test world (e.g., one demo player character, one NPC, and one visible item) with deterministic IDs and names to keep transcripts stable.
- [x] Add unit and/or integration tests in `services/entity-management-service` that verify the entity listing API returns the expected entities for the target rooms, including empty-room and multi-entity cases.
- [x] Update the Entity Management Service README/design docs with a subsection describing the `LOOK`-oriented entity listing API and how it fits with broader character and inventory design.

Sample data for this slice lives in the test fixtures referenced above: the World Management room fixtures define the rooms and exits, while `services/entity-management-service/src/main/resources/application.yml` holds the `firemud.look.rooms` entries that drive the entity transcripts. When future slices or transcripts need different rooms, add or edit entries in those fixtures and configuration so the documented scenarios stay in sync.

## 4. Game Logic Service: LOOK Aggregation and Formatting

- [x] Before changing this service for the slice, run `./gradlew :game-logic-service:test` and either get the existing tests passing or clearly document/temporarily disable any failing tests so the baseline is stable.
- [x] Introduce or refine a `LOOK`-oriented gRPC method in the Game Logic Service (for example `ResolveLook` or `GetLookDescription`) that accepts identity context (`tenantId`, `session_id`, `characterId`, and `roomId`) and returns a high-level `LookResult` DTO.
- [x] Implement the Game Logic `LOOK` handler so it orchestrates calls to World Management (room snapshot) and Entity Management (visible entities), applies any simple rules needed for this slice (for example, hiding entities the player should not see), and produces a structured `LookResult`.
- [x] Implement a straightforward text rendering routine in Game Logic that converts `LookResult` into a minimal but pleasant textual description (room name, blank line, room long description, exits line, then entity list), leaving hooks for richer formatting in future slices.
- [x] Add unit tests around the Game Logic `LOOK` handler and text rendering to cover at least: empty room, room with exits only, room with multiple entities, and error propagation when World or Entity services fail.
- [x] Document the new Game Logic `LOOK` API and its responsibilities in the Game Logic design docs, clearly distinguishing it from future combat/movement systems.

## 5. Game Session Service: Wiring Text LOOK to Game Logic

- [x] Before changing this service for the slice, run `./gradlew :game-session-service:test` and either get the existing tests passing or clearly document/temporarily disable any failing tests so the baseline is stable.
- [x] Replace the current hard-coded `LookCommandHandler` (or equivalent) in Game Session with a flow that calls Game Logic’s `LOOK`/`ResolveLook` gRPC method, passing along `tenantId`, `sessionId`, and `characterId` from the Redis session context.
- [x] Add a dedicated Spring client/component that encapsulates the World and Entity service calls, captures Micrometer timers for the remote invocations, and can fall back to the existing dev-isolated stub when `game-session.dev-isolated=true` so developers can run without dependencies.
- [x] Ensure that the text command interpreter continues to enforce authentication before invoking the `LOOK` gRPC call and that unauthenticated requests still return `ERROR NOT_AUTHENTICATED` without hitting downstream services.
- [x] Keep the textual `LOOK` output compatible with the existing smoke tests (same `OK LOOK` framing) while expanding the body to include the new data-driven content.
- [x] Map Game Logic `LookResult` and error codes into the text protocol response format so clients see a consistent `LOOK` description or `ERROR <CODE> <message>` when the call fails (for example, `ERROR ROOM_NOT_FOUND`, `ERROR WORLD_UNAVAILABLE`, `ERROR ENTITY_UNAVAILABLE`).
- [x] Add Micrometer metrics and structured logs in Game Session for `LOOK` commands (for example `gamesession.command.look.invocations`, `gamesession.command.look.failures`) including tags for `tenantId` and high-level error codes.
- [x] Add unit and/or integration tests in `services/game-session-service` that exercise the text `LOOK` path end-to-end against a stubbed Game Logic client, verifying correct handling of success, room-not-found, and downstream failure scenarios.
- [x] Replace the temporary Redis stub used by `services/tcp-proxy-service/src/test/java/crossservice/net/firedevops/firemud/TelnetGatewayGameSessionAccountCrossServiceIntegrationTest.java` with shared Redis test utilities (or direct Testcontainers wiring) so the slice exercises the same Redis configuration used in production.
- [x] Document the LOOK instrumentation (metrics/logs) in `design/project-management/look-instrumentation.md` so operators know what to monitor while the slice stabilizes.

## 6. Cross-Service End-to-End Tests (Telnet and WebSocket)

- [x] Extend or add a WebSocket-focused cross-service integration test that boots Game Session, Game Logic, World Management, and Entity Management (Testcontainers or lightweight stubs as needed), executes `LOGIN` + `LOOK`, and asserts the multiline response (`OK LOOK` plus room details, exits, and entity list) matches the canonical transcript defined in Section 1.
- [x] Add a Telnet-focused cross-service variant (TCP Proxy + Gateway harness) that performs `SESSION` + `LOGIN` + `LOOK` and asserts the Telnet transcript is semantically identical to the WebSocket output except for the framing/prompt lines.
- [x] Cover at least one happy-path room and one failure path (for example, a tenant attempting `LOOK` in a missing room) in these tests, verifying that the recorded `ERROR ROOM_NOT_FOUND` / `ERROR WORLD_UNAVAILABLE` lines appear exactly once without dropping Telnet/WebSocket connections.
- [x] Instrument the cross-service flows so we can assert the `LOOK` command traversed the pipeline (e.g., via log capture, metrics tags, or gRPC interceptors) and ensure `gamesession.command.look.*` counters increment for both success and failure paths.
- [x] Wire the new cross-service tests into a dedicated Gradle target (e.g., `crossServiceTest`) so they can run without slowing down the default unit suite, and reference the target in README/test docs.
- [x] Re-enable `services/tcp-proxy-service/src/test/java/crossservice/net/firedevops/firemud/TelnetGatewayGameSessionAccountCrossServiceIntegrationTest.java` (and add an analogous WebSocket harness) once the true Game Logic → World → Entity pipeline is available so we can replay the documented transcripts end-to-end.
- [x] Refer to `design/project-management/look-cross-service-tests.md` for detailed automation steps, metrics assertions, and Gradle wiring when implementing these tests.
- [x] Add a short note in `design/project-management/testing-focus-areas.md` under the command parsing / game logic sections pointing to these data-driven `LOOK` cross-service tests as examples.
- [x] Implement the WebSocket cross-service regression test placeholder (`LookWebSocketCrossServiceTest`) using the new LOOK fixtures/stubs and the documented metrics/log assertions before expanding to the Telnet flow.

Implementation notes for wiring the stubbed World/Entity/Account services, capturing the canonical transcripts, and validating the `gamesession.command.look.*` meters/logs live in `design/project-management/look-cross-service-tests.md#implementation-notes`; follow them while building the WebSocket and Telnet flows so the automation exercises both success and error paths as documented.

## 7. Developer Workflows, Smoke Tests, and Documentation Updates

- [x] Add or update a smoke test script (or documented curl/WebSocket sequence) that demonstrates `LOGIN` + `LOOK` against the sample world over WebSocket, including the expected room description in the script output or comments (`design/project-management/look-smoke-tests.md`) and sample transcript (`look-ws-sample.log`).
- [x] Add a second smoke test or example transcript that demonstrates `SESSION` + `LOGIN` + `LOOK` via Telnet through TCP Proxy and Gateway, verifying that the same room description is returned (same doc) with the sample transcript (`look-telnet-sample.log`).
- [x] Update the Game Session, Game Logic, World Management, and Entity Management design docs to include a short "Implementation status" note for the `LOOK` slice, clarifying what is live, what is stubbed, and what is deferred to future slices (for example, dynamic lighting, line-of-sight, or script-driven room text).
- [x] Expand the World Management Service design doc to describe the `/ws/game/**` `LOOK` contract fields, how Game Session aggregates entity/world context before replying to WebSocket/Telnet clients, and what configuration toggles (such as `WORLD_SERVICE_ENDPOINT`) developers can use locally.
- [x] Revisit the `Minimal Text Command Protocol` and any existing gameplay examples to ensure they reference the data-driven `LOOK` behavior instead of the original hard-coded room stub, updating examples where necessary.
- [x] Ensure logging and monitoring docs (including relevant sections under Logging & Admin) mention the new `LOOK`-related metrics and logs so operators know how to debug issues in this path.

## 8. Optional Follow-up: Reconnection Experience

- [x] (If time permits) Cache the most recent `LOOK` response in Redis (or the existing session context) and replay it automatically to reconnecting clients before buffered commands, so bridges can redraw the room even when the original `LOOK` happened pre-reconnect.
- [x] Design a cache/store for serialized `LOOK` payloads keyed by `tenantId`/`sessionId` (plus TTL) and add a service that exposes `getCachedLook`/`cacheLook`.
- [x] Wire the cache into `LookCommandHandler` so every successful `LOOK` pushes the rendered text and optional metadata into Redis immediately after rendering.
- [x] On reconnect (WebSocket `GameSessionWebSocketHandler`, TCP Proxy `TelnetServerHandler`, etc.) deliver the cached `LOOK` text before processing buffered commands, defaulting to fetching from Redis when the cache is stale or missing.
- [x] Add focused regression tests that replay a `LOOK`, simulate a reconnect, and assert the cached text is emitted instantly while the downstream `LOOK` path still works when the cache misses.

---

Note: After completing tasks in this checklist, go back and update the existing per-service status documents (such as `design/project-management/service-status-game-session-service.md`, `design/project-management/service-status-game-logic-service.md`, `design/project-management/service-status-world-management-service.md`, and `design/project-management/service-status-entity-management-service.md`) and the relevant design docs so duplicated items are reconciled and the architecture documentation reflects the completed vertical slice.

<!--
Prompt for Codex to generate the next vertical slice task list after these items are done:

"Context: We just completed the Login and Session vertical slice described in design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md. Please inspect the current code and design docs, then propose a new markdown task list file under design/project-management/ focused on the next smallest playable/demo slice that follows this flow deeper into the system (for example, data-driven LOOK that integrates World and Entity services, the SAY/chat path through Social & Groups, or more advanced reconnection edge cases). Each task should be small enough to hand to Codex as a single chunk, and the file should end with a note reminding us to reconcile any duplicated items in existing per-service status docs and design docs."
-->

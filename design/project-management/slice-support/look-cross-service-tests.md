# LOOK Cross-Service Test Plan

This plan documents how the cross-service WebSocket and Telnet tests exercise the data-driven `LOOK` path end-to-end. See `design/project-management/slice-support/look-and-say-regressions.md` for the shared LOOK/SAY regression catalog and metrics notes.

## Goals

1. Verify `LOGIN` + `LOOK` across Game Session → Game Logic → World / Entity using the shared TLS-enabled gRPC endpoints.
2. Confirm both success and failure paths (e.g., missing room, downstream UNAVAILABLE) surface the documented transcripts (`OK LOOK` and `ERROR <CODE>`) on both transports.
3. Ensure `gamesession.command.look.invocations` and `.failures` metrics increment with tenant/error tags during the flows.
4. Keep tests runnable via the dedicated `crossServiceTest` Gradle target so they can be executed without slowing down the default suite.

## Test data and prerequisites

- Provide the sample world and entities through dedicated test fixtures or stub gRPC servers so room `R-1021` and NPCs like `Kobold Scout` exist.
- Capture the tenant/session IDs from a `POST /sessions` call (Game Session service) when exercising advanced "attach to existing session" flows; reuse them in both WebSocket and Telnet flows that explicitly use the optional `SESSION` envelope.
- Ensure TLS certificates (or plaintext overrides) are available by mounting `certs/` or setting `firemud.grpc.plaintext=true` for local execution.

## Implementation notes

- Stub the World and Entity services via lightweight gRPC servers that return the deterministic room snapshot and entity list referenced earlier so the tests control the `LOOK` response and failure modes. Point Game Logic at these stubs (`firemud.services.world-management-service`, `firemud.services.entity-management-service`) and the Game Session service at the sprung-up Game Logic instance (`firemud.services.game-logic-service`). For WebSocket/Telnet runs driven by Testcontainers, expose the stub ports via dynamic properties so each test can create reproducible transcripts.
- Exercise the full `LOGIN` → `LOOK` flow: authenticate via `AccountService` (stubbed to accept `demo@example.com`/`swordfish`), send the authenticated `LOOK` request through WebSocket or Telnet, and assert both the structured `LookResult` and rendered text match the documented transcript in Section 1. Toggle `game.logic.default-room-id` to a missing room so the failure paths (`ERROR ROOM_NOT_FOUND`, `ERROR WORLD_UNAVAILABLE`, `ERROR ENTITY_UNAVAILABLE`) are also covered.
- Capture the new observability signals before/after each attempt using `design/project-management/slice-support/look-instrumentation.md`: hit `/actuator/prometheus` to verify `gamesession.command.look.invocations` increments and `gamesession.command.look.failures{error=<CODE>}` tags the expected code, and tail Game Session/Game Logic logs to ensure `LookCommandHandler`/`LookAggregationService` emit the `Rendered LOOK text`/`LOG WARN LOOK failed <ERROR>` lines.

## Implementation checklist

1. Create or reuse `LookTestFixtures` (already committed) so the stubs can return the canonical transcript used by both the Game Logic renderer and the cross-service assertions.
2. Add a WebSocket-focused cross-service integration test that:
   - Starts Game Session, Game Logic (pointed at stall world/entity stubs), Redis, Postgres, and the Gateway stub.
   - Performs `POST /sessions` → `LOGIN` → `LOOK`.
   - Validates the multi-line `LOOK` response matches `LookTestFixtures.canonicalLookText()`.
   - Toggles `game.logic.default-room-id`/`firemud.services.world-management-service` to trigger `ERROR ROOM_NOT_FOUND` and ensures the error transcript plus metrics/log tags match the instrumentation doc.
3. Add a Telnet/TCP Proxy variant that reuses the same service stack, sending `LOGIN` + `LOOK` over the proxy for the baseline flow (no `SESSION`), and a second variant that sends `SESSION` + `LOGIN` + `LOOK` to exercise the optional attach-to-session path. Compare the Telnet transcripts to the WebSocket output (ignoring prompts).
4. Capture the relevant metrics/logs in both tests (via `/actuator/prometheus` or log tailing) and assert `gamesession.command.look.*` increments as documented.
5. Wire both tests into a dedicated Gradle source set/task (for example `crossServiceTest`) so they can be run independently from the default suite and referenced in README/test docs.

## WebSocket Test

1. Start Testcontainers for Game Session, Game Logic, World Management, Entity Management, Redis, and Postgres (reuse existing service test setup/configs).
2. Launch a Gateway stub proxying `ws://localhost:<gateway>/ws/game`.
3. Send `LOGIN demo@example.com swordfish` via WebSocket, expect `OK LOGIN`, then `LOOK` and assert the multiline response matches the canonical transcript (room name, descriptions, exits, entities).
4. Override `game.logic.default-room-id` to a missing room ID and confirm the next `LOOK` yields `ERROR ROOM_NOT_FOUND`.
5. Capture `gamesession.command.look.*` via `/actuator/prometheus` or Micrometer, asserting `invocations` increments for each attempt and `failures` tags the exact error code.
6. Optionally tail Game Session/Game Logic logs to verify they include `WorldManagement`/`EntityManagement` labels in the error description.

## Telnet Test

1. Start the same service stack plus the TCP Proxy pointing to Game Session and the Gateway pointing to the proxy.
2. Use a raw socket to send:
   - `LOGIN demo@example.com swordfish` → expect `OK LOGIN` for the baseline flow where the Game Session Service creates/binds the session.
   - Optionally, for attach-to-existing-session scenarios, first create a session via REST `POST /sessions`, then send `SESSION <sessionId> <tenantId>` followed by `LOGIN demo@example.com swordfish` to bind the Telnet connection to that existing session.
   - `LOOK` → compare the multiline response to the WebSocket transcript (ignore prompt/transport framing).
3. Trigger a failure by requesting a non-existent room and assert the Telnet client receives `ERROR ROOM_NOT_FOUND` without disconnecting.
4. Capture the same metrics/logs to ensure instrumentation is consistent across transports.
5. Save the transcript as `look-telnet-<timestamp>.log` for regression comparisons.

## Gradle Integration

- Each module now exposes a `crossServiceTest` task that only runs the `crossservice` test packages so Game Session and TCP Proxy can bootstrap their respective flows without affecting the default test suite.
- A root-level `./gradlew crossServiceTest` task aggregates `:game-session-service:crossServiceTest` and `:tcp-proxy-service:crossServiceTest`, making the entire regression suite easy to run in CI or locally while isolating it from the standard unit tests.

## Follow-up

- Once these tests exist, update `design/project-management/testing-focus-areas.md`, `look-smoke-tests.md`, `look-instrumentation.md`, and the new transcripts folder with links to `./gradlew crossServiceTest`, any new artifacts, and the observed error codes so the monitoring docs stay in sync.

## Current implementation

- `LookWebSocketCrossServiceTest` now boots Game Session, Game Logic (pointed at the stubbed World/Entity servers), Redis, Postgres, and the Gateway stub, then runs `POST /sessions` → `LOGIN` → `LOOK`, validates `LookTestFixtures.canonicalLookText()`, and captures the failure metrics/logs when `ROOM_NOT_FOUND` is triggered.
- `TelnetGatewayGameSessionAccountCrossServiceIntegrationTest` runs the same stack via the TCP Proxy/Gateway, drives `SESSION`/`LOGIN`/`LOOK`, ensures the Telnet transcript matches the WebSocket output, then triggers a missing room failure so the instrumentation docs capture the `ERROR ROOM_NOT_FOUND` path.
- Each module exposes a `crossServiceTest` task and the root `./gradlew crossServiceTest` aggregates them, so the automation only runs when explicitly requested.

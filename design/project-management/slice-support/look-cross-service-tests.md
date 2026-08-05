# LOOK Cross-Service Test Plan

This plan documents how the cross-service WebSocket and Telnet tests exercise the data-driven `LOOK` path end-to-end. See [LOOK and communication regressions](./look-and-say-regressions.md) for the shared LOOK/SAY regression catalog and metrics notes.

## Current Implementation

- `LookWebSocketCrossServiceTest` boots Game Session, Game Logic (pointed at stubbed World/Entity servers), Redis, Postgres, and the Gateway stub, then runs `POST /sessions` → `LOGIN` → `LOOK`, validates `LookTestFixtures.canonicalLookText()`, and captures failure metrics/logs when `ROOM_NOT_FOUND` is triggered.
- `TelnetGatewayGameSessionAccountCrossServiceIntegrationTest` runs the same stack via TCP Proxy/Gateway, drives `WORLDS` / `LOGIN` / `PLAY` / `LOOK`, ensures the Telnet transcript matches the WebSocket output, then triggers a missing-room failure so the instrumentation docs capture `ERROR ROOM_NOT_FOUND`.
- Each module exposes a `crossServiceTest` task and the root `./gradlew crossServiceTest` aggregates them, so the automation runs only when explicitly requested.

## Goals

1. Verify `LOGIN` + `LOOK` across Game Session → Game Logic → World / Entity using the shared TLS-enabled gRPC endpoints.
2. Confirm both success and failure paths (e.g., missing room, downstream UNAVAILABLE) surface the documented transcripts (`OK LOOK` and `ERROR <CODE>`) on both transports.
3. Ensure `gamesession.command.look.invocations` and `.failures` metrics increment during the flows, with failures tagged by bounded error code.
4. Keep tests runnable via the dedicated `crossServiceTest` Gradle target so they can be executed without slowing down the default suite.

## Test data and prerequisites

- Provide the sample world and entities through dedicated test fixtures or stub gRPC servers so room `R-1021` and NPCs like `Kobold Scout` exist.
- Capture the normal `WORLDS` / `LOGIN` / `PLAY` / `LOOK` flow in both WebSocket and Telnet variants. Typed attach metadata is not part of the cross-service parity contract.
- Ensure TLS certificates (or plaintext overrides) are available by mounting `certs/` or setting `firemud.grpc.plaintext=true` for local execution.

## Implementation notes

- Stub the World and Entity services via lightweight gRPC servers that return the deterministic room snapshot and entity list referenced earlier so the tests control the `LOOK` response and failure modes. Point Game Logic at these stubs (`firemud.services.world-management-service`, `firemud.services.entity-management-service`) and the Game Session service at the sprung-up Game Logic instance (`firemud.services.game-logic-service`). For WebSocket/Telnet runs driven by Testcontainers, expose the stub ports via dynamic properties so each test can create reproducible transcripts.
- Exercise the full `LOGIN` → `LOOK` flow: authenticate via `AccountService` (stubbed to accept `demo@example.com`/`swordfish`), send the authenticated `LOOK` request through WebSocket or Telnet, and assert both the structured `LookResult` and rendered text match the documented transcript in Section 1. Toggle `game.logic.default-room-id` to a missing room so the failure paths (`ERROR ROOM_NOT_FOUND`, `ERROR WORLD_UNAVAILABLE`, `ERROR ENTITY_UNAVAILABLE`) are also covered.
- Capture the observability signals before/after each attempt using [LOOK instrumentation](./look-instrumentation.md): hit `/actuator/prometheus` to verify `gamesession.command.look.invocations` increments and `gamesession.command.look.failures{error=<CODE>}` tags the expected code, and tail Game Session/Game Logic logs to ensure `LookCommandHandler`/`LookAggregationService` emit the `Rendered LOOK text`/`LOG WARN LOOK failed <ERROR>` lines.

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
   - Use the same `WORLDS` + `LOGIN` + `PLAY` flow on both transports; typed attach metadata is intentionally out of scope for parity coverage.
   - `LOOK` → compare the multiline response to the WebSocket transcript (ignore prompt/transport framing).
3. Trigger a failure by requesting a non-existent room and assert the Telnet client receives `ERROR ROOM_NOT_FOUND` without disconnecting.
4. Capture the same metrics/logs to ensure instrumentation is consistent across transports.
5. Save the transcript as `look-telnet-<timestamp>.log` for regression comparisons.

## Gradle Integration

- Each module now exposes a `crossServiceTest` task that only runs the `crossservice` test packages so Game Session and TCP Proxy can bootstrap their respective flows without affecting the default test suite.
- A root-level `./gradlew crossServiceTest` task aggregates `:game-session-service:crossServiceTest` and `:tcp-proxy-service:crossServiceTest`, making the entire regression suite easy to run in CI or locally while isolating it from the standard unit tests.

## Maintenance

- Keep [testing focus areas](../testing-focus-areas.md), [LOOK smoke tests](./look-smoke-tests.md), and [LOOK instrumentation](./look-instrumentation.md) aligned with `./gradlew crossServiceTest`, its artifacts, and observed error codes.

# LOOK Cross-Service Test Plan

This plan documents how the cross-service WebSocket and Telnet tests exercise the data-driven `LOOK` path end-to-end. See [LOOK and communication regressions](./look-and-say-regressions.md) for the shared LOOK/SAY regression catalog and metrics notes.

## Current Implementation

- `LookWebSocketCrossServiceTest` boots Game Session, Game Logic (pointed at stubbed World/Entity servers), Redis, Postgres, and the Gateway stub. Its fixture provisions a running game instance directly; the gameplay path is `LOGIN` → `PLAY` → `LOOK`, with the required readiness wait and `WORLDS` discovery step where that scenario uses discovery. It retains the end-to-end assertion of `LookTestFixtures.canonicalLookText()`. Focused proof assigns structured `LookResult` and typed-failure assertions/logs to Game Logic and `PlayerOutput` plus deterministic text-projection assertions/logs to Game Session; current cross-service failure proof covers `ROOM_NOT_FOUND`.
- `TelnetGatewayGameSessionAccountCrossServiceIntegrationTest` provisions its running instance directly through the shared fixture, waits for the documented connection/readiness guidance, and drives `LOGIN` → `PLAY` → `LOOK` (with `WORLDS` only where the scenario requires discovery). It ensures the Telnet transcript matches the WebSocket output, then triggers a missing-room failure so the instrumentation docs capture `ERROR ROOM_NOT_FOUND`.
- Each module exposes a `crossServiceTest` task and the root `./gradlew crossServiceTest` aggregates them, so the automation runs only when explicitly requested.

## Goals

1. Verify `LOGIN` → `PLAY` → `LOOK` across Game Session → Game Logic → World / Entity using the shared TLS-enabled gRPC endpoints.
2. Confirm the successful path and the currently proven missing-room failure surface the documented transcripts (`OK LOOK` and `ERROR ROOM_NOT_FOUND`) on both transports; retain `WORLD_UNAVAILABLE` and `ENTITY_UNAVAILABLE` as unit/target mappings until distinct stub-`UNAVAILABLE` cross-service cases exist.
3. Ensure `gamesession.command.look.invocations` and `.failures` metrics increment during the flows, with failures tagged by bounded error code.
4. Keep tests runnable via the dedicated `crossServiceTest` Gradle target so they can be executed without slowing down the default suite.

## Test data and prerequisites

- Provide the sample world and entities through dedicated test fixtures or stub gRPC servers so room `R-1021` and NPCs like `Kobold Scout` exist.
- Capture the normal `LOGIN` → `PLAY` → `LOOK` flow in both WebSocket and Telnet variants, including the required readiness wait and `WORLDS` discovery step where the transport scenario uses it. Typed attach metadata is not part of the cross-service parity contract.
- Ensure TLS certificates (or plaintext overrides) are available by mounting `certs/` or setting `firemud.grpc.plaintext=true` for local execution.

## Implementation notes

- Stub the World and Entity services via lightweight gRPC servers that return the deterministic room snapshot and entity list referenced earlier so the tests control the `LOOK` response and failure modes. Point Game Logic at these stubs (`firemud.services.world-management-service`, `firemud.services.entity-management-service`) and the Game Session service at the sprung-up Game Logic instance (`firemud.services.game-logic-service`). For WebSocket/Telnet runs driven by Testcontainers, expose the stub ports via dynamic properties so each test can create reproducible transcripts.
- Exercise the full `LOGIN` → `PLAY` → `LOOK` flow after the fixture provisions a running instance directly; perform the documented readiness/discovery steps required by the transport scenario, authenticate via `AccountService` (stubbed to accept `demo@example.com`/`swordfish`), and retain the end-to-end assertion that the player-visible text matches the documented transcript in Section 1. In focused service proof, Game Logic logs/asserts the structured `LookResult` and typed failures; Game Session logs/asserts the mapped `PlayerOutput` and deterministic text projection. Toggle `game.logic.default-room-id` to a missing room for the current `ERROR ROOM_NOT_FOUND` proof. `ERROR WORLD_UNAVAILABLE` and `ERROR ENTITY_UNAVAILABLE` remain unit/target scenarios until distinct stub-`UNAVAILABLE` cross-service cases exist. The player-facing renderer remains Game Session's `PlayerOutput`/text projection; any Game Logic `Rendered LOOK text`/`LookResultRenderer` output is local fixture evidence only.
- A future cross-service expansion should use separate World-stub `UNAVAILABLE` and successful-World/Entity-stub `UNAVAILABLE` fixtures for `WORLD_UNAVAILABLE` and `ENTITY_UNAVAILABLE`; those cases are not current cross-service proof.
- Capture the observability signals before/after each attempt using [LOOK instrumentation](./look-instrumentation.md): hit `/actuator/prometheus` to verify `gamesession.command.look.invocations` increments and `gamesession.command.look.failures{error=<CODE>}` tags the expected code, and tail Game Session/Game Logic logs for the owner-bound proof. The current Game Logic `Rendered LOOK text`/`LookResultRenderer` fixture diagnostic may remain local evidence; expected player-facing renderer attribution is Game Session's `PlayerOutput`/text projection.

## WebSocket Test

1. Start Testcontainers for Game Session, Game Logic, World Management, Entity Management, Redis, and Postgres (reuse existing service test setup/configs).
2. Launch a Gateway stub proxying `ws://localhost:<gateway>/ws/game`.
3. After the fixture provisions a running instance and the stack is ready, complete the gameplay path `LOGIN demo@example.com swordfish` → `PLAY demo` → `LOOK` (perform `WORLDS` when this scenario uses discovery), and assert the multiline response matches the canonical transcript (room name, descriptions, exits, entities).
4. Override `game.logic.default-room-id` to a missing room ID and confirm the next `LOOK` yields `ERROR ROOM_NOT_FOUND`.
5. Capture `gamesession.command.look.*` via `/actuator/prometheus` or Micrometer, asserting `invocations` increments for each attempt and `failures` tags the exact error code.
6. Optionally tail Game Logic diagnostics for typed `LookResult` failures and source labels, and Game Session logs/proof for the mapped `PlayerOutput` and deterministic text projection. The client-facing assertion remains the canonical text response.

## Telnet Test

1. Start the same service stack plus the TCP Proxy pointing to Game Session and the Gateway pointing to the proxy.
2. Use a raw socket to wait for the documented connection/readiness guidance, perform `WORLDS` where discovery is required, then send:
   - `LOGIN demo@example.com swordfish` → expect `OK LOGIN`.
   - `PLAY demo` → expect `OK PLAY`.
   - `LOOK` → compare the multiline response to the WebSocket transcript (ignore prompt/transport framing).
3. Trigger a failure by requesting a non-existent room and assert the Telnet client receives `ERROR ROOM_NOT_FOUND` without disconnecting.
4. Capture the same metrics/logs to ensure instrumentation is consistent across transports.
5. Save the transcript as `look-telnet-<timestamp>.log` for regression comparisons.

## Gradle Integration

- Each module now exposes a `crossServiceTest` task that only runs the `crossservice` test packages so Game Session and TCP Proxy can bootstrap their respective flows without affecting the default test suite.
- A root-level `./gradlew crossServiceTest` task aggregates `:game-session-service:crossServiceTest` and `:tcp-proxy-service:crossServiceTest`, making the entire regression suite easy to run in CI or locally while isolating it from the standard unit tests.

## Maintenance

- Keep [testing focus areas](../testing-focus-areas.md), [LOOK smoke tests](./look-smoke-tests.md), and [LOOK instrumentation](./look-instrumentation.md) aligned with `./gradlew crossServiceTest`, its artifacts, and observed error codes.

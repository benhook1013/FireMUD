# LOOK Cross-Service Test Plan

This plan documents how the cross-service WebSocket and Telnet tests exercise the data-driven `LOOK` path end-to-end. See [LOOK and communication regressions](./look-and-say-regressions.md) for the shared LOOK/SAY regression catalog and metrics notes.

## Target-State Contract

The cross-service suite is target-state proof of the player-visible `LOOK` contract across Game Session, Game Logic, World, and Entity for WebSocket and Telnet. Each scenario performs the required readiness and discovery steps, authenticates and binds gameplay, verifies the canonical transcript, and records bounded `LOOK` metrics. Controlled participant failures must produce the canonical error without an unintended transport close and must prove recovery where the scenario requires it. Game Logic owns structured aggregation and Game Session owns player-facing output; local diagnostics are supplementary and do not become cross-service authority.

## Current Implementation

- `LookWebSocketCrossServiceTest` boots Game Session, Game Logic (pointed at stubbed World/Entity servers), Redis, Postgres, and the Gateway stub. Its fixture provisions a running game instance directly; the gameplay path is `LOGIN` → `PLAY` → `LOOK`, with the required readiness wait and `WORLDS` discovery step where that scenario uses discovery. It retains the end-to-end assertion of `LookTestFixtures.canonicalLookText()`. Structured `LookResult`, typed-failure, `PlayerOutput`, and text-projection records are optional owner-local diagnostics; the cross-service assertion is the player-visible transcript and, for the controlled `ROOM_NOT_FOUND` case, connection continuity with a follow-up command.
- `TelnetGatewayGameSessionAccountCrossServiceIntegrationTest` provisions its running instance directly through the shared fixture, waits for the documented connection/readiness guidance, and drives `LOGIN` → `PLAY` → `LOOK` (with `WORLDS` only where the scenario requires discovery). It currently proves successful Telnet transcript parity with the WebSocket output; an isolated missing-room Telnet case using a controlled World stub remains future coverage.
- Each module exposes a `crossServiceTest` task and the root `./gradlew crossServiceTest` aggregates them, so the automation runs only when explicitly requested.

## Goals

1. Verify `LOGIN` → `PLAY` → `LOOK` across Game Session → Game Logic → World / Entity using the shared TLS-enabled gRPC endpoints.
2. Confirm the successful path and the WebSocket-controlled missing-room failure surface the documented transcripts (`OK LOOK` and `ERROR ROOM_NOT_FOUND`); Telnet currently proves successful transcript parity only, with an isolated missing-room Telnet case remaining future coverage. Retain `WORLD_UNAVAILABLE` and `ENTITY_UNAVAILABLE` as unit/target mappings until distinct stub-`UNAVAILABLE` cross-service cases exist.
3. Ensure `gamesession.command.look.invocations` and `.failures` metrics increment during the flows, with failures tagged by bounded error code.
4. Keep tests runnable via the dedicated `crossServiceTest` Gradle target so they can be executed without slowing down the default suite.

## Test data and prerequisites

- Provide the sample world and entities through dedicated test fixtures or stub gRPC servers so room `R-1021` and NPCs like `Kobold Scout` exist.
- Capture the normal `LOGIN` → `PLAY` → `LOOK` flow in both WebSocket and Telnet variants, including the required readiness wait and `WORLDS` discovery step where the transport scenario uses it. Typed attach metadata is not part of the cross-service parity contract.
- Ensure TLS certificates (or plaintext overrides) are available by mounting `certs/` or setting `firemud.grpc.plaintext=true` for local execution.

## Implementation notes

- Stub the World and Entity services via lightweight gRPC servers that return the deterministic room snapshot and entity list referenced earlier so the tests control the `LOOK` response and failure modes. Point Game Logic at these stubs (`firemud.services.world-management-service`, `firemud.services.entity-management-service`) and the Game Session service at the sprung-up Game Logic instance (`firemud.services.game-logic-service`). For WebSocket/Telnet runs driven by Testcontainers, expose the stub ports via dynamic properties so each test can create reproducible transcripts.
- Exercise the full `LOGIN` → `PLAY` → `LOOK` flow after the fixture provisions a running instance directly; perform the documented readiness/discovery steps required by the transport scenario, authenticate via `AccountService` (stubbed to accept `demo@example.com`/`swordfish`), and retain the end-to-end assertion that the player-visible text matches the canonical transcript in the [LOOK Command Regression Flow](./look-and-say-regressions.md#look-command-regression-flow). Owner-local diagnostics may record Game Logic's structured `LookResult`/typed failures and Game Session's `PlayerOutput`/deterministic text projection, but those records are not cross-service proof. The WebSocket test calls `worldStub().triggerNotFound()` after a successful `LOOK` for the current `ERROR ROOM_NOT_FOUND` proof; it does not mutate `game.logic.default-room-id` at runtime. Telnet currently has no missing-room assertion; add that later with an isolated controlled World-stub case. `ERROR WORLD_UNAVAILABLE` and `ERROR ENTITY_UNAVAILABLE` remain unit/target scenarios until distinct stub-`UNAVAILABLE` cross-service cases exist. The player-facing renderer remains Game Session's `PlayerOutput`/text projection; any Game Logic `Rendered LOOK text`/`LookResultRenderer` output is local fixture evidence only.
- A future cross-service expansion should use separate World-stub `UNAVAILABLE` and successful-World/Entity-stub `UNAVAILABLE` fixtures for `WORLD_UNAVAILABLE` and `ENTITY_UNAVAILABLE`; those cases are not current cross-service proof.
- Capture the observability signals before/after each attempt using [LOOK instrumentation](./look-instrumentation.md): hit `/actuator/prometheus` to verify `gamesession.command.look.invocations` increments and `gamesession.command.look.failures{error=<CODE>}` tags the expected code, and tail Game Session/Game Logic logs for the owner-bound proof. The current Game Logic `Rendered LOOK text`/`LookResultRenderer` fixture diagnostic may remain local evidence; expected player-facing renderer attribution is Game Session's `PlayerOutput`/text projection.

## WebSocket Test

1. Start Testcontainers for the real Game Session, Game Logic, Redis, and Postgres services, and start controlled World and Entity gRPC stub servers (reuse the existing service test setup/configs).
2. Launch a Gateway stub proxying `ws://localhost:<gateway>/ws/game`.
3. After the fixture provisions a running instance and the stack is ready, complete the gameplay path `LOGIN demo@example.com swordfish` → `PLAY demo` → `LOOK` (perform `WORLDS` when this scenario uses discovery), and assert the multiline response matches the canonical transcript (room name, descriptions, exits, entities).
4. After the successful `LOOK`, call the controlled World stub's `worldStub().triggerNotFound()` and confirm the next `LOOK` yields `ERROR ROOM_NOT_FOUND` without closing the WebSocket. Clear the stub failure, send a follow-up `LOOK`, and assert the canonical success transcript to prove transport continuity after the error.
5. Capture `gamesession.command.look.*` via `/actuator/prometheus` or Micrometer, asserting `invocations` increments for each attempt and `failures` tags the exact error code.
6. Optionally tail Game Logic diagnostics for typed `LookResult` failures and source labels, and Game Session diagnostics for the mapped `PlayerOutput` and deterministic text projection. These diagnostics are supplementary; the automated cross-service proof is the client-facing text response and the post-error follow-up.

## Telnet Test

1. Start the real Game Session, Game Logic, Redis, and Postgres stack plus controlled World and Entity gRPC stub servers, the TCP Proxy pointing to Game Session, and the Gateway stub/flow pointing to the proxy.
2. Use a raw socket to wait for the documented connection/readiness guidance, perform `WORLDS` where discovery is required, then send:
   - `LOGIN demo@example.com swordfish` → expect `OK LOGIN`.
   - `PLAY demo` → expect `OK PLAY`.
   - `LOOK` → compare the multiline response to the WebSocket transcript (ignore prompt/transport framing).
3. Current proof ends after successful transcript parity; no Telnet missing-room case is currently implemented.
4. Add a future isolated controlled World-stub missing-room case and assert the Telnet client receives `ERROR ROOM_NOT_FOUND` without disconnecting; clear the stub failure, send a follow-up `LOOK`, and assert the canonical success transcript to prove transport continuity.
5. Capture the same metrics/logs to ensure instrumentation is consistent across transports.
6. Save the transcript as `look-telnet-<timestamp>.log` for regression comparisons.

## Gradle Integration

- Each module now exposes a `crossServiceTest` task that only runs the `crossservice` test packages so Game Session and TCP Proxy can bootstrap their respective flows without affecting the default test suite.
- A root-level `./gradlew crossServiceTest` task aggregates `:game-session-service:crossServiceTest` and `:tcp-proxy-service:crossServiceTest`, making the entire regression suite easy to run in CI or locally while isolating it from the standard unit tests.

## Maintenance

- Keep [testing focus areas](../testing-focus-areas.md), [LOOK smoke tests](./look-smoke-tests.md), and [LOOK instrumentation](./look-instrumentation.md) aligned with `./gradlew crossServiceTest`, its artifacts, and observed error codes.

# Telnet to Gameplay Vertical Slice Task List

## Goal and Status

Goal: describe the end-to-end Telnet → Gateway → Game Session pipeline as a playable, testable slice, including echo paths, envelopes, reconnection, and a minimal text command protocol. Status: parts of this slice are implemented and under active refinement; where behavior is not yet live, this document still describes the target-state flow, with implementation details tracked in the relevant service design docs and tests.

This checklist focuses on turning the Telnet TCP Proxy + Gateway + Game Session path into a playable, testable vertical slice. Each task is intentionally scoped so it can be handed to Codex (or a developer) as a single, self-contained chunk of work.

## 1. Dev Echo Path and Local Telnet Loop

- [x] Add a short "Dev Echo Path" section to `services/tcp-proxy-service/README.md` documenting how to run the proxy with the `dev` profile, which WebSocket URL is used (`/dev/echo`), and how to connect via Telnet locally.
- [x] Extend or add a Spring Boot integration test in `services/tcp-proxy-service` that runs with the `dev` profile and verifies a Telnet client can send a line and receive the same line back via the `DevEchoWebSocketHandler` (no gateway or game-session involvement).
- [x] Add a small helper script or documented curl/telnet commands alongside `smoke-test.sh` to manually exercise the Telnet → WebSocket dev echo flow on a developer machine.

## 2. Telnet Session Envelope and Event Metrics

- [x] Document the Telnet session envelope format in the TCP Proxy design so MUD tools and scripts know how to bind a Telnet connection to a session, using the canonical [Telnet Session Envelope & Event Metrics](../../architecture/microservices/tcp-proxy-service/README.md#telnet-session-envelope--event-metrics) section as the single source of truth. The space-separated form (`SESSION <sessionId> <tenantId>`) is canonical for all new clients and examples; the historical compact form (`SESSION <sessionId>:<tenantId>`) remains accepted on the wire for backwards compatibility but is treated as deprecated and may be removed once remaining callers are migrated.
- [x] Add focused unit tests for `TelnetSessionContext` covering valid envelopes (space-separated, colon-separated) and invalid/malformed cases, asserting sessionId/tenantId handling and log behaviour.
- [x] Add a Spring Boot test for `TelnetServerHandler` that opens a Netty channel, sends a valid `SESSION` envelope followed by a command, and asserts that `TcpProxyEventService.recordConnectEvent` is invoked with the expected sessionId, tenantId, and client IP.

## 3. Reconnection and Buffered Input Behaviour

- [x] Add unit or component tests for `TelnetServerHandler` that simulate a dropped WebSocket connection (e.g., triggering `onClose`/`onError`) and verify that reconnect backoff, reconnect counter metrics, and buffer preservation behave as designed.
- [x] Add a test that populates the buffer with several commands, forces a reconnect, and verifies `pushBufferedInputAsync` calls `TcpProxyEventService.pushBufferedInput` with the correct sessionId, tenantId, and ordered command list.
- [x] Add tests around the buffer depth limit (`MAX_BUFFER_DEPTH`) to ensure the handler closes the Telnet connection and increments the discarded command counter when the buffer is exhausted.

## 4. Game Session gRPC TcpProxyService Implementation

- [x] Scaffold a `TcpProxyServiceImpl` gRPC server in `services/game-session-service` implementing the `TcpProxyService` proto (`NotifyDisconnect`) and register it with the existing gRPC server configuration.
- [x] Implement `NotifyDisconnect` in `TcpProxyServiceImpl` to validate inputs, map `proxyConnectionId` to the authenticated session when available, and mark the appropriate session as disconnected/suspended in Redis using the existing session repository or service layer, returning an `ErrorDetail` code of `OK` on success.
- [x] Add unit tests for `TcpProxyServiceImpl` covering happy paths and validation failures for `NotifyDisconnect`, ensuring `ErrorDetail` codes and `grpc.app_error` metrics are set correctly.

## 5. Telnet → Gateway → Game Session Cross-Service Flow

- [x] Add a cross-service integration test (in `services/tcp-proxy-service` or a shared test module) that starts tcp-proxy-service, Spring Cloud Gateway, and game-session-service together using Testcontainers or Spring Boot test harnesses.
- [x] In that test, open a Telnet socket, send a valid `SESSION` envelope and a simple command, and assert that the command arrives at the Game Session command queue (or an observable stub) and that an expected response can be read back over the Telnet connection.
- [x] Ensure this cross-service test is wired into Gradle (e.g., via a dedicated `crossServiceTest` or naming convention) and is documented so it can be run locally and in CI (run via `./gradlew :tcp-proxy-service:test --tests net.firedevops.firemud.TelnetGatewayGameSessionCrossServiceIntegrationTest`).

## 6. Minimal Text Command Protocol and Gameplay Slice

### 6.1 Protocol definition and docs

Link to the [Minimal Text Command Protocol](../../architecture/microservices/game-session-service/README.md#minimal-text-command-protocol) section, which defines the initial MVP gameplay command set shared by Telnet and WebSocket clients in this vertical slice.

- [x] Add a "Minimal Text Command Protocol" section to `design/architecture/microservices/game-session-service/README.md` describing a line-based command protocol for Telnet/WebSocket clients (for example `LOGIN <user> <password>`, `LOOK`, `SAY <text>`), including at least one concrete example per command.
- [x] In that section, define the expected response format for commands (plain text lines, how errors are reported, behavior for unknown commands, and how multiple responses are separated).
- [x] Update this vertical slice doc (`design/project-management/task-list-telnet-to-gameplay-vertical-slice.md`) to link to the new protocol section and explicitly call it the initial MVP command set for gameplay.

### 6.2 Command model and parser in game-session-service

- [x] Introduce a minimal text command model in `services/game-session-service` (for example a `TextCommand` record with fields like `type`, `args`, `rawLine`, plus a `CommandType` enum including `LOGIN`, `LOOK`, `SAY`, `UNKNOWN`).
- [x] Implement a `TextCommandParser` (or similar) in `services/game-session-service` that takes a raw text line and returns a `TextCommand`, handling trimming, case-insensitive command names, and falling back to `UNKNOWN` for unrecognized commands.
- [x] Add unit tests for `TextCommandParser` covering valid commands, extra whitespace, empty lines, malformed input, and the `UNKNOWN` command path.

### 6.3 Interpreter and dispatch into existing tick/command flow

- [x] Add a minimal `TextCommandInterpreter` (or equivalent service) in `services/game-session-service` that takes a `TextCommand` and enqueues the appropriate internal command into the existing tick/command queue (reusing the same enqueue logic used for current WebSocket/game commands).
- [x] Wire the WebSocket/Game Session entry point to call `TextCommandParser` + `TextCommandInterpreter` for each incoming text line so that text commands follow the same tick-based processing path as any other gameplay command.
- [x] Add tests (unit or small Spring test) that simulate a WebSocket message containing a text line and assert that the correct internal command is enqueued for a given session/tenant.

### 6.4 Minimal LOOK gameplay command

- [x] Implement a minimal `LOOK` command handler in `services/game-session-service` that produces a static or test-seeded room description string (it can ignore real world state for this slice as long as the output is deterministic).
- [x] Connect the `LOOK` handler into the interpreter so that a parsed `LOOK` `TextCommand` results in the handler being invoked and its output being sent back to the client via the existing outbound messaging mechanism.
- [x] Add tests within `services/game-session-service` that exercise the `LOOK` command end-to-end inside the service (without Telnet or Gateway), asserting that a `LOOK` input results in the expected response text being produced.

### 6.5 Telnet and WebSocket parity for LOOK

- [x] Ensure the Telnet path (TCP Proxy ? Gateway ? Game Session) forwards raw text command lines into the same `TextCommandParser` / interpreter pipeline used by direct WebSocket clients, with no Telnet-specific command parsing beyond the `SESSION` envelope already defined earlier in this checklist (the cross-service stub demonstrates the shared response).
- [x] Add an integration test that exercises `LOOK` over a direct WebSocket connection using a lightweight Gateway stub and asserts the response text equals the Telnet response by reusing the same deterministic constant.
- [x] Add an integration test that exercises `LOOK` over a direct WebSocket connection through Spring Cloud Gateway (no Telnet) and asserts the response text matches the Telnet path exercised by the cross-service test in section 5 (for example by sharing a helper that asserts the `LOOK` response string is identical); implemented by `services/spring-cloud-gateway/src/test/java/integration/net/firedevops/firemud/GatewayLookCommandIntegrationTest.java`, which spins up a lightweight stubbed route and compares the payload to `LookCommandConstants.LOOK_RESPONSE`.

## 7. Additional Infrastructure Tasks

- [x] Document the production WebSocket bridge between the TCP proxy and Spring Cloud Gateway (expected `GATEWAY_WS_URL` and Gateway route path such as `/ws/game/**`) so the Telnet and web client paths are explicitly aligned and easy to configure.
- [x] Ensure the `game-session-service` Gradle configuration generates and compiles gRPC stubs from `protos/game-session/v1/game_session_service.proto` into the module, and add a short note in the Game Session design docs describing where the generated stubs are used.
- [x] Implement a minimal `GameSessionService` gRPC server in `game-session-service` based on the `game_session.v1` proto (at least the `Ping` RPC), reusing existing service-layer logic where possible.
- [x] Add tests that exercise the `GameSessionService` gRPC `Ping` endpoint and verify it returns a successful `ErrorDetail` code and message.
- [x] Add a smoke test that starts `game-session-service` in dev-isolated mode (matching the `bootRunDevIsolated` configuration) and verifies a simple request flow (for example starting a session or enqueuing a command) is accepted and logged without hitting external dependencies.
- [x] Update `services/game-session-service/README.md` or the Game Session design docs with a brief "Dev-isolated mode" section that links to the smoke test, explains when to use it, and clarifies that it avoids database and external service calls.

## 8. Cross-Service Test Stabilization Follow-Up

Work on the tcp-proxy cross-service test has drifted: the current `TelnetGatewayGameSessionCrossServiceIntegrationTest` is burdened with ad-hoc bean overrides (mocked gRPC runners, custom route builders, Redis template stubs, etc.) and still fails to compile because `ReactiveRedisTemplate` is not on the test classpath. Before resuming, carve out a clean plan to simplify this area:

- [x] Remove the reactive Redis references (and other recent hacks) from `TelnetGatewayGameSessionCrossServiceIntegrationTest` so the file compiles again with the original dependencies — verified in `services/tcp-proxy-service/src/test/java/crossservice/net/firedevops/firemud/TelnetGatewayGameSessionCrossServiceIntegrationTest.java` where the imports/config now exclude Redis and mocked route builders (`@EnableAutoConfiguration` excludes `GRpcAutoConfiguration` / `GatewayRedisAutoConfiguration` only for the stub contexts).
- [x] Build a lightweight gateway stub app (either inline or as a separate `GatewayStubApplication`) that only exposes the `/ws/game` WebSocket route and requires no Redis/JWT/gRPC configuration — implemented in `services/tcp-proxy-service/src/test/java/crossservice/net/firedevops/firemud/stub/GatewayStubApplication.java`, which proxies `/ws/game/**` traffic via `ReactorNettyWebSocketClient`.
- [x] Update the cross-service test to launch just the stub gateway + the existing game-session stub, wiring the ports through `@DynamicPropertySource` without extra bean or component-scan overrides — `TelnetGatewayGameSessionCrossServiceIntegrationTest` now spins up only `GameSessionStubApplication` + `GatewayStubApplication` and registers `GATEWAY_WS_URL`/`TCP_PROXY_PORT` via `@DynamicPropertySource`.
- [x] Re-run `./gradlew :tcp-proxy-service:test --tests crossservice.net.firedevops.firemud.TelnetGatewayGameSessionCrossServiceIntegrationTest` and log any remaining failures as follow-up items (e.g., LOOK handler expectations) rather than piling on mocks — command executed successfully (latest run in this workspace, see shell history) with no remaining failures.
- [x] Document the new helper stub and wiring approach in this file and the per-service status summaries once it's stable, so future slices can reuse the simplified pattern — this checklist plus the TCP Proxy and Spring Cloud Gateway service-status docs now include references to the stub/test harness where relevant.

---

Note: After completing tasks in this checklist, go back and update the existing per-service status documents (such as `design/project-management/service-status-tcp-proxy-service.md`, `design/project-management/service-status-game-session-service.md`, and `design/project-management/service-status-spring-cloud-gateway.md`) and the relevant design docs so duplicated items are reconciled and the architecture documentation reflects the completed vertical slice.

<!--
Prompt for Codex to generate the next vertical slice task list after these items are done:

"Context: We just completed the Telnet to Gameplay vertical slice described in design/project-management/vertical-slices/01-task-list-telnet-to-gameplay-vertical-slice.md. Please inspect the current code and design docs, then propose a new markdown task list file under design/project-management/ focused on the next smallest playable/demo slice that follows this flow deeper into the system (e.g., richer gameplay commands, reconnection edge cases, or related services). Each task should be small enough to hand to Codex as a single chunk, and the file should end with a note reminding us to reconcile any duplicated items in existing per-service status docs and design docs."
-->

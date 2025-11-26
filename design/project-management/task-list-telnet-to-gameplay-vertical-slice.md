# Telnet to Gameplay Vertical Slice Task List

This checklist focuses on turning the Telnet TCP Proxy + Gateway + Game Session path into a playable, testable vertical slice. Each task is intentionally scoped so it can be handed to Codex (or a developer) as a single, self-contained chunk of work.

## 1. Dev Echo Path and Local Telnet Loop

- [ ] Add a short "Dev Echo Path" section to `services/tcp-proxy-service/README.md` documenting how to run the proxy with the `dev` profile, which WebSocket URL is used (`/dev/echo`), and how to connect via Telnet locally.
- [ ] Extend or add a Spring Boot integration test in `services/tcp-proxy-service` that runs with the `dev` profile and verifies a Telnet client can send a line and receive the same line back via the `DevEchoWebSocketHandler` (no gateway or game-session involvement).
- [ ] Add a small helper script or documented curl/telnet commands alongside `smoke-test.sh` to manually exercise the Telnet → WebSocket dev echo flow on a developer machine.

## 2. Telnet Session Envelope and Event Metrics

- [ ] Document the Telnet session envelope format (`SESSION <sessionId> <tenantId>` and `SESSION <sessionId>:<tenantId>`) in the TCP Proxy design or README so MUD tools and scripts know how to bind a Telnet connection to a session.
- [ ] Add focused unit tests for `TelnetSessionContext` covering valid envelopes (space-separated, colon-separated) and invalid/malformed cases, asserting sessionId/tenantId handling and log behaviour.
- [ ] Add a Spring Boot test for `TelnetServerHandler` that opens a Netty channel, sends a valid `SESSION` envelope followed by a command, and asserts that `TcpProxyEventService.recordConnectEvent` is invoked with the expected sessionId, tenantId, and client IP.

## 3. Reconnection and Buffered Input Behaviour

- [ ] Add unit or component tests for `TelnetServerHandler` that simulate a dropped WebSocket connection (e.g., triggering `onClose`/`onError`) and verify that reconnect backoff, reconnect counter metrics, and buffer preservation behave as designed.
- [ ] Add a test that populates the buffer with several commands, forces a reconnect, and verifies `pushBufferedInputAsync` calls `TcpProxyEventService.pushBufferedInput` with the correct sessionId, tenantId, and ordered command list.
- [ ] Add tests around the buffer depth limit (`MAX_BUFFER_DEPTH`) to ensure the handler closes the Telnet connection and increments the discarded command counter when the buffer is exhausted.

## 4. Game Session gRPC TcpProxyService Implementation

- [ ] Scaffold a `TcpProxyServiceImpl` gRPC server in `services/game-session-service` implementing the `TcpProxyService` proto (`NotifyDisconnect`, `PushBufferedInput`) and register it with the existing gRPC server configuration.
- [ ] Implement `NotifyDisconnect` in `TcpProxyServiceImpl` to validate inputs and mark the appropriate session as disconnected/suspended in Redis using the existing session repository or service layer, returning an `ErrorDetail` code of `OK` on success.
- [ ] Implement `PushBufferedInput` in `TcpProxyServiceImpl` to validate inputs and enqueue the provided commands into the per-session command queue in Redis, reusing the existing command enqueue logic used for WebSocket-driven input.
- [ ] Add unit tests for `TcpProxyServiceImpl` covering happy paths and validation failures for both `NotifyDisconnect` and `PushBufferedInput`, ensuring `ErrorDetail` codes and `grpc.app_error` metrics are set correctly.

## 5. Telnet → Gateway → Game Session Cross-Service Flow

- [ ] Add a cross-service integration test (in `services/tcp-proxy-service` or a shared test module) that starts tcp-proxy-service, Spring Cloud Gateway, and game-session-service together using Testcontainers or Spring Boot test harnesses.
- [ ] In that test, open a Telnet socket, send a valid `SESSION` envelope and a simple command, and assert that the command arrives at the Game Session command queue (or an observable stub) and that an expected response can be read back over the Telnet connection.
- [ ] Ensure this cross-service test is wired into Gradle (e.g., via a dedicated `crossServiceTest` or naming convention) and is documented so it can be run locally and in CI.

## 6. Minimal Text Command Protocol and Gameplay Slice

- [ ] Define and document a minimal line-based command protocol for the Telnet/WebSocket path (for example `LOGIN <user> <password>`, `LOOK`, `SAY <text>`), updating the Game Session design docs to reference these commands as the initial MVP.
- [ ] Implement a minimal command interpreter in `game-session-service` that parses these text commands from the existing WebSocket/Game Session entry point and dispatches them into the existing tick/command handling flow.
- [ ] Implement at least one simple gameplay command end-to-end (for example `LOOK` returning a static or test-seeded room description) that works both via WebSocket (through the Gateway) and via Telnet (through the TCP Proxy).
- [ ] Add integration tests that exercise the same command protocol over a direct WebSocket connection through Spring Cloud Gateway (no Telnet) and verify behaviour matches the Telnet path for the implemented commands.

---

Note: After completing tasks in this checklist, go back and update the existing per-service task list documents (such as `design/project-management/task-list-tcp-proxy-service.md`, `design/project-management/task-list-game-session-service.md`, and `design/project-management/task-list-spring-cloud-gateway.md`) and the relevant design docs so duplicated items are reconciled and the architecture documentation reflects the completed vertical slice.

<!--
Prompt for Codex to generate the next vertical slice task list after these items are done:

"Context: We just completed the Telnet to Gameplay vertical slice described in design/project-management/task-list-telnet-to-gameplay-vertical-slice.md. Please inspect the current code and design docs, then propose a new markdown task list file under design/project-management/ focused on the next smallest playable/demo slice that follows this flow deeper into the system (e.g., richer gameplay commands, reconnection edge cases, or related services). Each task should be small enough to hand to Codex as a single chunk, and the file should end with a note reminding us to reconcile any duplicated items in existing task lists and design docs."
-->

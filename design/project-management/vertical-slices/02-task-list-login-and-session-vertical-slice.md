# Login and Session Vertical Slice Task List

## Goal and Status

Goal: define a cohesive login and session-management slice that layers authenticated flows, reconnection behaviour, and cross-service tests on top of the Telnet to gameplay pipeline. Status: core flows and several tests are implemented; this document captures the target-state behaviour, while individual task checkboxes and design docs indicate what is currently live vs. stubbed or deferred.

This checklist builds on the **Telnet to Gameplay** slice by wiring the `LOGIN` text command end-to-end through Game Session and Account services, enforcing authenticated sessions for gameplay commands, and exercising basic session resumption behaviour. As before, each task should be small enough to hand to Codex (or a developer) as a single, self-contained chunk of work.

## 1. Minimal LOGIN Protocol Behaviour and Docs

- [x] Review the [Minimal Text Command Protocol](../../architecture/microservices/game-session-service/README.md#minimal-text-command-protocol) and the [Authentication & Authorization](../../architecture/system-architecture-authentication.md#login-and-session-flow) docs to confirm the intended `LOGIN` / `LOGON` semantics (prompt-based vs parameterized logins, one supplied secret, error codes such as `INVALID_CREDENTIALS` and `ACCOUNT_LOCKED`).
- [x] Update the Game Session Service design doc so the `Minimal Text Command Protocol` section explicitly documents `LOGIN` and `LOGON` behaviour for both Telnet and WebSocket clients, including at least one success and one failure transcript that show the `OK LOGIN` / `ERROR <CODE>` response format.
- [x] Add a short subsection under the Authentication & Authorization doc describing how plain-text `LOGIN` commands map onto the Account Service `/auth/login` API (or gRPC equivalent), including how OTP values are forwarded when present.
- [x] Ensure docs clearly state that once this slice is complete, gameplay commands such as `LOOK` and `SAY` require an authenticated session, except in explicitly documented dev/test bypass modes.

## 2. Game Session LOGIN Command Handling

- [x] Implement a dedicated login handler in `services/game-session-service` (for example `LoginCommandHandler` or a focused method on `TextCommandInterpreter`) that processes `TextCommandType.LOGIN` / `LOGON`, distinguishes between prompt-based and parameterized forms, and produces a structured result that includes success/failure and optional response text.
- [x] On successful login, create or update a Redis-backed session context record storing at least `accountId`, `tenantId`, `characterId`, and a reference to the active `GameInstance`, using key conventions consistent with the [Redis Architecture](../../architecture/system-architecture-redis.md#session-keys-and-gameplay-binding).
- [x] Integrate the login handler into the existing WebSocket entry point so that `LOGIN` commands received over `/ws/game/**` flow through the same `TextCommandParser` / interpreter pipeline as gameplay commands, returning `OK LOGIN ...` responses or `ERROR <CODE> <message>` for failures.
- [x] Enforce an "authenticated session required" check before processing gameplay commands in the Game Session Service (e.g., `LOOK`, `SAY`) so that unauthenticated clients receive `ERROR NOT_AUTHENTICATED` until `LOGIN` succeeds, with a configuration flag allowing this guard to be disabled for local development if needed.
- [x] Add unit tests for the login handler and authentication guard covering at least: successful parameterized login, invalid credentials, missing arguments triggering a "prompt-mode not yet implemented" placeholder, repeated `LOGIN` attempts, and the `NOT_AUTHENTICATED` path for `LOOK`.

## 3. Account Service Integration for LOGIN

- [x] Implement a Game Session Service client for the Account Service login API (REST or gRPC, per the current Account Service design), including username, one supplied secret, and any tenant/game selection identifiers required for session binding.
- [x] Map Account Service responses into a small internal DTO (e.g., `LoginResult`) containing `accountId`, allowed tenant/game identifiers, and the issued JWT (if applicable), and store the JWT and identity details in Redis alongside the gameplay session entry as described in the Authentication & Authorization docs.
- [x] Ensure Game Session converts Account Service failures into appropriate `ErrorDetail` codes (e.g., `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`, `UPSTREAM_FAILURE`) and that these are surfaced in both the gRPC response and the text `ERROR <CODE> <message>` line returned to clients.
- [x] Add an integration test that starts Game Session Service with a lightweight Account Service stub (Spring Boot test configuration or Testcontainers-based stub), issues a `LOGIN` command over a direct WebSocket connection, and asserts that the stub receives the expected login request and that the client sees the correct `OK LOGIN ...` or `ERROR ...` text.
- [x] Document the dependency on the Account Service in `services/game-session-service/README.md`, including example `grpcurl` or REST calls that demonstrate the login path in isolation.

## 4. Session Resumption and Takeover Basics

- [x] Extend session persistence so that Game Session can look up an existing session by `accountId` and `characterId` on `LOGIN`, and perform session takeover when a second client logs in as the same character, in line with the [Multi-Client Behaviour and Session Takeover](../../architecture/system-architecture-authentication.md#multi-client-behavior-and-session-takeover) rules.
- [x] Implement basic session resumption behaviour so that when a previously connected client disconnects and later sends `LOGIN` again with valid credentials for an existing Redis session, Game Session reuses the existing tick/command queues instead of starting a fresh game instance.
- [x] Ensure that session takeover and resumption paths emit Micrometer metrics (for example `gamesession.session.takeover` and `gamesession.session.resume`) and structured logs including `tenantId`, `accountId`, and `characterId` to support debugging.
- [x] Add focused tests (unit or Spring Boot integration) that simulate two WebSocket connections using the same character: the first performs `LOGIN` and `LOOK`, the second performs `LOGIN` and is granted control while the first receives a disconnect or error, and subsequent `LOOK` calls continue to operate on the same underlying game state.
- [x] Update the [Reconnection Strategy](../../architecture/system-architecture-reconnection.md) doc with a short "implemented status" note describing which parts of the reconnect flow are now live (e.g., session takeover and basic resume after TCP/WebSocket loss) and which remain future work.

## 5. Telnet and WebSocket LOGIN Parity

The canonical Telnet admission semantics now live in the TCP Proxy and authentication docs as `WORLDS` (optional), `LOGIN`, and `PLAY`, with hidden proxy or MCP metadata reserved for future smart-client hints only. This slice focuses on verifying that Telnet and WebSocket clients share the same login pipeline and observed behaviour rather than redefining the protocol.

- [x] Confirm that Telnet connections sending normal login flow lines are forwarded by the TCP Proxy Service into the same WebSocket `/ws/game/**` route and Game Session login pipeline used by direct WebSocket clients, with no Telnet-specific gameplay parsing.
- [x] Add a cross-service integration test (reusing the lightweight gateway and game-session stubs where appropriate) that starts TCP Proxy Service, Spring Cloud Gateway, Game Session Service, and an Account Service stub together, performs the normal browse/login/play/look flow over a Telnet socket, and asserts that the observed responses match those from a direct WebSocket client hitting Gateway.
- [x] Verify that `TelnetServerHandler` continues to redact `LOGIN` arguments in logs while still forwarding the full command to Game Session, and add tests that assert logging behaviour for sensitive vs non-sensitive commands.
- [x] Document the Telnet `LOGIN` flow in both the TCP Proxy Service design doc and the Spring Cloud Gateway design doc, making it clear that Telnet and WebSocket clients share the same authentication path and that the gateway route (`/ws/game/**`) is the single entry point for gameplay login.

## 6. Developer Workflows and Smoke Tests

- [x] Add or update a smoke test script (alongside existing ones) that demonstrates a full `LOGIN` + `LOOK` flow over a direct WebSocket connection to Game Session Service, using sample credentials and clearly marking any required Account Service/dev environment setup.
- [x] Add a second smoke test or documented telnet or curl sequence that exercises the normal `WORLDS` + `LOGIN` + `PLAY` + `LOOK` flow through TCP Proxy Service and Spring Cloud Gateway. Use the same credentials and assert that the responses match the direct WebSocket flow.
- [x] Update the relevant per-service status docs so Game Session, Account, TCP Proxy, and Spring Cloud Gateway summarize the current login/session slice without duplicating the detailed task list.

---

## 7. Dev Mode Stubs and Real-Service Rollout

- [x] Historical note: this slice originally introduced temporary dependency-light session and game-instance stubs to keep `LOGIN` runnable before the real local stack existed. Those shortcuts have since been removed in favor of the canonical Redis/Postgres/downstream-service path.
- [x] Historical note: tests that once depended on those shortcuts were either rewritten against the real infrastructure-backed path or removed when they stopped representing the maintained runtime.
- [x] Historical note: the associated developer documentation was later cleaned up so the canonical local guidance now points at the real stack and smoke scripts rather than temporary stubbed services.

Note: After completing tasks in this checklist, go back and update the existing per-service status documents (such as `design/project-management/service-status-game-session-service.md`, `design/project-management/service-status-account-service.md`, `design/project-management/service-status-tcp-proxy-service.md`, and `design/project-management/service-status-spring-cloud-gateway.md`) and the relevant design docs so duplicated items are reconciled and the architecture documentation reflects the completed vertical slice.

<!--
Prompt for Codex to generate the next vertical slice task list after these items are done:

"Context: We just completed the Login and Session vertical slice described in design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md. Please inspect the current code and design docs, then propose a new markdown task list file under design/project-management/ focused on the next smallest playable/demo slice that follows this flow deeper into the system (for example, data-driven LOOK that integrates World and Entity services, the SAY/chat path through Social & Groups, or more advanced reconnection edge cases). Each task should be small enough to hand to Codex as a single chunk, and the file should end with a note reminding us to reconcile any duplicated items in existing per-service status docs and design docs."
-->

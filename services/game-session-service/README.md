# Game Session Service

Design details are kept in the architecture docs:
[Game Session Service Design](../../design/architecture/microservices/game-session-service/README.md)

This README is only a stub. **Do not place design information here.**

## Account Service dependency

- The Game Session Service delegates credential verification to the Account Service `/auth/login` (or gRPC `Authenticate`) endpoint and stores the issued JWT/session data per the “Login and Session Flow” section of `design/architecture/system-architecture-authentication.md`.
- The login smoke test and local workflows expect either the lightweight Account Service stub or a local Account Service instance to be reachable; without it the `LOGIN <username> <password>` step cannot complete and later `PLAY` / gameplay commands cannot be admitted.

## Local development

- `./gradlew :game-session-service:bootRunDevIsolated` starts the service with `spring.profiles.active=dev`, sets `GAME_SESSION_DEV_ISOLATED=true`, and runs in dev-isolated mode for local testing without external dependencies.

## Environment variables

- `GAME_SESSION_DEV_ISOLATED`: When set to `true`, the service acknowledges requests and logs actions without persisting state or contacting external systems. The `bootRunDevIsolated` Gradle task enables this by default.

## Dev-isolated stubs and migration notes

- While `game-session.dev-isolated=true`, the service supplies in-memory replacements for Redis-backed session context persistence (`DevIsolatedSessionContextService`) and fake `GameInstance` records (`DevIsolatedGameInstanceService` + `DevIsolatedGameInstanceRegistry`) so that the LOGIN flow, session takeover/resume metrics, and smoke tests keep running without a database or Redis. This profile is meant purely for developer experimentation and **must be removed once the real Account/Redis/GameInstance wiring is available**.
- Several tests currently rely on the dev-isolated behavior (e.g., `DevIsolatedGameSessionSmokeTest`, `GameSessionLoginIntegrationTest`, `GameSessionWebSocketHandlerIntegrationTest`, and `SessionResumptionFlowTest`), so revisit them when replacing the stubs: either run them against the real infrastructure, wrap them with an explicit `dev`/`dev-isolated` profile, or temporarily disable them with a TODO note until the corresponding services are ready.

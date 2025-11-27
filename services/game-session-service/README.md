# Game Session Service

Design details are kept in the architecture docs:
[Game Session Service Design](../../design/architecture/microservices/game-session-service/README.md)

This README is only a stub. **Do not place design information here.**

## Account Service dependency

- The Game Session Service delegates credential verification to the Account Service `/auth/login` (or gRPC `Authenticate`) endpoint and stores the issued JWT/session data per the “Login and Session Flow” section of `design/architecture/system-architecture-authentication.md`.
- The login smoke test and local workflows expect either the lightweight Account Service stub or a local Account Service instance to be reachable; without it the `LOGIN <username> <password>` flow cannot complete and `LOOK` will continue to report `ERROR NOT_AUTHENTICATED`.

## Local development

- `./gradlew :game-session-service:bootRunLogOnly` starts the service with `spring.profiles.active=dev`, sets `GAME_SESSION_LOG_ONLY=true`, and runs in log-only mode for local testing without external dependencies.

## Environment variables

- `GAME_SESSION_LOG_ONLY`: When set to `true`, the service acknowledges requests and logs actions without persisting state or contacting external systems. The `bootRunLogOnly` Gradle task enables this by default.

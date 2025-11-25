# Game Session Service

Design details are kept in the architecture docs:
[📄 Game Session Service Design](../../design/architecture/microservices/game-session-service/README.md)

This README is only a stub. **Do not place design information here.**

## Local development

- `./gradlew :game-session-service:bootRunLogOnly` starts the service with `spring.profiles.active=dev`, sets `GAME_SESSION_LOG_ONLY=true`, and runs in log-only mode for local testing without external dependencies.

## Environment variables

- `GAME_SESSION_LOG_ONLY`: When set to `true`, the service acknowledges requests and logs actions without persisting state or contacting external systems. The `bootRunLogOnly` Gradle task enables this by default.

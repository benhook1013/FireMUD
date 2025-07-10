# Game Logic Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/game-logic/v1](../../protos/game-logic/v1)

The service exposes a minimal REST endpoint for testing command parsing:

```bash
curl -X POST http://localhost:8080/command -d "attack goblin"
```

## Running Locally

```bash
./gradlew :game-logic-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

## Environment Variables

This service relies on the standard `FIREMUD_` prefixed variables for
PostgreSQL and Redis configuration. Common settings in development are:

| Variable | Purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | Database password |
| `SPRING_REDIS_HOST` | Redis hostname |
| `SPRING_REDIS_PORT` | Redis port |

See the [Environment Variables & Secrets Management](../../design/architecture/infrastructure/environment-and-secrets.md)
doc for defaults and profile-based configuration.

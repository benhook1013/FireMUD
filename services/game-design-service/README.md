# Game Design Service

Refer to [design/README.md](design/README.md) for architecture details. A new
document describing game templates and configuration tools is available at
[design/game-templates.md](design/game-templates.md).

- **Proto definitions**: [../../protos/game-design/v1](../../protos/game-design/v1)

## Running Locally

```bash
./gradlew :game-design-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

## Environment Variables

The service reads configuration from standard Spring Boot variables. Typical
settings when running locally are:

| Variable | Purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | Database password |
| `SPRING_PROFILES_ACTIVE` | Spring profile (`dev` or `prod`) |

See the [Environment Variables & Secrets Management](../../design/architecture/infrastructure/environment-and-secrets.md)
document for details on how these values are supplied in different environments.

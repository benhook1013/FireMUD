# Automation Scripting Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/automation-scripting/v1](../../protos/automation-scripting/v1) (uses shared `ErrorDetail`)

## Running Locally

```bash
./gradlew :automation-scripting-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

## Environment Variables

The service relies on standard Spring Boot properties for database and Redis
connections. Typical variables include:

| Variable | Purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | Database password |
| `SPRING_REDIS_HOST` | Redis hostname |
| `SPRING_REDIS_PORT` | Redis port |

Values can also be specified in `application.yml` profiles for different
environments.

## Redis Key Pattern

Automation events are stored in Redis using the following format:

```text
automation_queue:{tenantId}:{entityId}
```

These ephemeral keys queue triggered events until a script runs.

## Tenant Handling and Dependencies

Each script row includes a `tenantId` column to keep data isolated between
games. The service receives events from the Game Session Service and sends
commands to the Game Logic Service for rule evaluation.

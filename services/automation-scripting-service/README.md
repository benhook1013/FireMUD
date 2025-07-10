# Automation Scripting Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/automation-scripting/v1](../../protos/automation-scripting/v1) (uses shared `ErrorDetail`)
- **OpenAPI spec**: [src/main/resources/openapi.yaml](src/main/resources/openapi.yaml)

## Running Locally

```bash
./gradlew :automation-scripting-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

## Configuration

Connection settings are provided by `DatabaseAutoConfiguration` and
`RedisProperties` from the common library. See
[Deployment Environments](../../design/architecture/infrastructure/deployment-environments.md)
for defaults. Typical values from `.env.sample`:

```bash
FIREMUD_POSTGRES_HOST=postgres
FIREMUD_POSTGRES_PORT=5432
FIREMUD_POSTGRES_USER=firemud
FIREMUD_POSTGRES_PASSWORD=firemud
FIREMUD_POSTGRES_DB=firemud
FIREMUD_REDIS_HOST=redis
FIREMUD_REDIS_PORT=6379
```

| Variable | Purpose |
| --- | --- |
| `FIREMUD_POSTGRES_HOST` | PostgreSQL hostname |
| `FIREMUD_POSTGRES_PORT` | PostgreSQL port |
| `FIREMUD_POSTGRES_USER` | Database user |
| `FIREMUD_POSTGRES_PASSWORD` | Database password |
| `FIREMUD_POSTGRES_DB` | Database name |
| `FIREMUD_REDIS_HOST` | Redis hostname |
| `FIREMUD_REDIS_PORT` | Redis port |

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

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

After starting the services you can insert minimal test data with:

```bash
./dev-tools/seed-automation-scripting-data.sh
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

NPCs consult player reputation to decide whether to fight, flee, or surrender.
The underlying `faction` and `faction_standing` tables live in the Social &
Groups Service; see its
[documentation](../../design/architecture/microservices/social-groups-service/README.md#data-model)
for details.

## Tenant Handling and Dependencies

Each script row includes a `tenantId` column to keep data isolated between
games. The service receives events from the Game Session Service and sends
commands to the Game Logic Service for rule evaluation.

### AutomationQueueService

`AutomationQueueService` stores triggered events using Redis lists. Events are pushed to `automation_queue:{tenantId}:{entityId}` and drained when the script engine runs. Metrics `automation_queue_enqueued_total` and `automation_queue_drained_total` record queue activity.

### Procedural Generation

A lightweight dungeon generator is provided for early world creation. It generates a simple tree of rooms which can be persisted by the World Management Service. See [System Architecture: Procedural Generation](../../design/architecture/system-architecture-procedural-generation.md) for details.

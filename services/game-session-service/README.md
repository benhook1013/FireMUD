# Game Session Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/game-session/v1](../../protos/game-session/v1)

New lifecycle endpoints manage running sessions:

```bash
POST /sessions/{id}/stop    # stop a running session
POST /sessions/{id}/restart # restart a stopped session
```

## Configuration

The service relies on `DatabaseAutoConfiguration` and `RedisProperties` from the
common library. Connection details are supplied via environment variables as
described in the [Deployment Environments](../../design/architecture/infrastructure/deployment-environments.md)
doc. Most setups can use the defaults from `.env.sample`:

```bash
FIREMUD_POSTGRES_HOST=postgres
FIREMUD_POSTGRES_PORT=5432
FIREMUD_POSTGRES_USER=firemud
FIREMUD_POSTGRES_PASSWORD=firemud
FIREMUD_POSTGRES_DB=firemud
FIREMUD_REDIS_HOST=redis
FIREMUD_REDIS_PORT=6379
```

Typical environment variables are summarized below:

| Variable | Purpose |
| --- | --- |
| `FIREMUD_POSTGRES_HOST` | PostgreSQL hostname |
| `FIREMUD_POSTGRES_PORT` | PostgreSQL port |
| `FIREMUD_POSTGRES_USER` | Database user |
| `FIREMUD_POSTGRES_PASSWORD` | Database password |
| `FIREMUD_POSTGRES_DB` | Database name |
| `FIREMUD_REDIS_HOST` | Redis hostname |
| `FIREMUD_REDIS_PORT` | Redis port |

Each request includes a `tenantId` that identifies the game instance. Database
rows and Redis keys are prefixed with this value to keep game data isolated as
outlined in the [Multi-Tenancy](../../design/architecture/system-architecture-multi-tenancy.md)
document. The service coordinates with the Game Logic, Entity Management and
World Management services over gRPC.

## Running Locally

```bash
./gradlew :game-session-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

## Tenant Handling and Dependencies

Every session row contains a `tenantId` column, and all Redis keys include this
prefix. This isolates game data for each hosted title. The service communicates
with the Game Logic, Entity Management, and World Management services over gRPC
to coordinate ticks and world state. Lifecycle events are also sent to the
Logging & Admin Service for auditing.

Redis stores active session details under keys following the pattern
`session:{tenantId}:{sessionId}`. These entries enable reconnect recovery when
clients disconnect temporarily.

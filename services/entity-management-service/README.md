# Entity Management Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/entity-management/v1](../../protos/entity-management/v1)
- **OpenAPI spec**: [src/main/resources/openapi.yaml](src/main/resources/openapi.yaml)

## Running Locally

```bash
./gradlew :entity-management-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

## Environment Variables

The service reads database and Redis settings from the common
`DatabaseAutoConfiguration` and `RedisProperties` classes. Override defaults
using the following variables (see [Environment Variables & Secrets Management](../../design/architecture/infrastructure/environment-and-secrets.md)):

| Variable | Purpose | Default |
| --- | --- | --- |
| `FIREMUD_POSTGRES_HOST` | PostgreSQL host | `postgres` |
| `FIREMUD_POSTGRES_PORT` | PostgreSQL port | `5432` |
| `FIREMUD_POSTGRES_DATABASE` | Database name | `firemud` |
| `FIREMUD_POSTGRES_USERNAME` | Database user | `firemud` |
| `FIREMUD_POSTGRES_PASSWORD` | Database password | `firemud` |
| `FIREMUD_REDIS_HOST` | Redis host | `redis` |
| `FIREMUD_REDIS_PORT` | Redis port | `6379` |

## Cross-Service Dependencies

Entity data is scoped by `tenantId` as described in the
[Multi-Tenancy design](../../design/architecture/system-architecture-multi-tenancy.md).
The service relies on the **Game Design Service** for character templates and
item definitions and coordinates runtime persistence with the
**Game Session Service**.

## Proto Contracts

See [`entity_management_service.proto`](../../protos/entity-management/v1/entity_management_service.proto)
for RPC definitions. Responses return `shared.v1.ErrorDetail` on failure.

## Redis Key Pattern

Transient gameplay state is stored in Redis using the conventions from the
[Redis Architecture](../../design/architecture/system-architecture-redis.md)
document. Keys are prefixed with the `tenantId` to keep game data isolated. A
few examples are:

```text
tick:lock:{tenantId}:{entityId}
tick:pending:{tenantId}:{regionId}
timer:{tenantId}:{entityId}:{effectId}
```

These keys coordinate tick execution and cooldown timers but contain no
persistent data.

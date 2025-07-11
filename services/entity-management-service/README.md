# Entity Management Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/entity-management/v1](../../protos/entity-management/v1)
- **OpenAPI spec**: [src/main/resources/openapi.yaml](src/main/resources/openapi.yaml)
- Supports cross-game account linking and complex crafting recipes.

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

## Authentication

All REST endpoints require a JWT issued by the Account Service. The token must
contain either `platformAdmin` or `moderator` in the `globalRoles` claim, or an
`admin`/`moderator` role within the `scopedRoles` map. Include the token using
the standard `Authorization: Bearer <token>` header.


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

## Metrics & Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. Service methods emit
`@Timed` metrics and traces are exported to the collector configured in
`application.yml`. Redis command latency is recorded via Micrometer and
exposed under the `redis` metric namespace.

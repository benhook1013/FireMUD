# Entity Management Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/entity-management/v1](../../protos/entity-management/v1)

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
using the following variables:

- `FIREMUD_POSTGRES_HOST`
- `FIREMUD_POSTGRES_PORT`
- `FIREMUD_POSTGRES_DATABASE`
- `FIREMUD_POSTGRES_USERNAME`
- `FIREMUD_POSTGRES_PASSWORD`
- `FIREMUD_REDIS_HOST`
- `FIREMUD_REDIS_PORT`

## Cross-Service Dependencies

Entity data is scoped by `tenantId` as described in the
[Multi-Tenancy design](../../design/architecture/system-architecture-multi-tenancy.md).
The service relies on the **Game Design Service** for character templates and
item definitions and coordinates runtime persistence with the
**Game Session Service**.

## Proto Contracts

See [`entity_management_service.proto`](../../protos/entity-management/v1/entity_management_service.proto)
for RPC definitions. Responses return `shared.v1.ErrorDetail` on failure.

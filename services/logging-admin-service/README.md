# Logging Admin Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/logging-admin/v1](../../protos/logging-admin/v1)

## Running Locally

```bash
./gradlew :logging-admin-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

## Environment Variables

The service relies on standard Spring Boot properties for PostgreSQL and Redis connections.
Typical variables when running locally are:

| Variable | Purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | Database password |
| `SPRING_REDIS_HOST` | Redis hostname |
| `SPRING_REDIS_PORT` | Redis port |

Values may also be provided through `application.yml` profiles.

## Tenant Handling and Dependencies

All log and moderation records contain a `tenantId` column to ensure operators only
access data for their games. The service consumes account events from the
[Account Service](../../design/architecture/microservices/account-service/README.md)
and chat logs from the
[Social & Groups Service](../../design/architecture/microservices/social-groups-service/README.md).
Game Session metrics are streamed from the
[Game Session Service](../../design/architecture/microservices/game-session-service/README.md).

## Feature Flags

Toggle runtime flags via REST:

```bash
curl -X POST http://localhost:8080/feature-flags/toggle \
  -H "Content-Type: application/json" \
  -d '{"tenantId":1,"name":"double_xp","enabled":true}'
```

## Saga Dashboard

Query saga instances and steps:

```bash
curl http://localhost:8080/sagas
curl http://localhost:8080/sagas/1/steps
```

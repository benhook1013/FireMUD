# Account Service

Manages user accounts and authentication.

- **Design docs**: [design/README.md](design/README.md)
- **Proto definitions**: [../../protos/account/v1](../../protos/account/v1)

## Running Locally

Run the service only:

```bash
./gradlew :account-service:bootRun
```

Or start all services:

```bash
./gradlew devUp
```

## REST Authentication Endpoint

Request a JWT token using the `/auth/login` route:

```bash
curl -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"username":"demo","password":"secret"}'
```

Sample response:

```json
{
  "status": "SUCCESS",
  "data": {
    "authToken": "<token>"
  }
}
```

## Environment Variables

The service relies on standard Spring Boot properties for database and Redis
connections. Typical variables when running locally are:

| Variable | Purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | Database password |
| `SPRING_REDIS_HOST` | Redis hostname |
| `SPRING_REDIS_PORT` | Redis port |
| `FIREMUD_AUTH_JWT_SECRET` | Secret key for JWT signing |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | JWT expiration time in ms |
| `FIREMUD_AUTH_SESSION_EXPIRATION_MS` | Session token TTL in ms |

Values can also be configured via `application.yml` profiles for different
environments.

## Redis Key Pattern

Account session tokens are stored in Redis using the following format:

```text
session:{tenantId}:{token}
```

These keys expire automatically and allow the Game Session Service to restore a
player's connection without requiring another login.

## Tenant Handling and Dependencies

Every account row includes a `tenantId` column. This ensures data isolation for
games hosted on the platform. Other microservices verify JWT tokens issued by
this service and include the tenant identifier in all requests.

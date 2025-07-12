# Account Service

Manages user accounts and authentication.

- **Design docs**: [design/README.md](design/README.md)
- **Proto definitions**: [../../protos/account/v1](../../protos/account/v1)
- **OpenAPI spec**: [src/main/resources/openapi.yaml](src/main/resources/openapi.yaml)

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

The `/auth/login` route establishes a session for meta/control services. The JWT returned is used internally for service calls.

```bash
curl -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"username":"demo","password":"secret","otp":"123456"}'
# `otp` is only needed if two-factor authentication is enabled
```

Sample response (token shown for debugging only):

```json
{
  "status": "SUCCESS",
  "data": {
    "authToken": "<token>"
  }
}
```

## Profile Endpoints

Retrieve a profile:

```bash
curl http://localhost:8080/profiles/2?tenantId=1
```

Update a profile:

```bash
curl -X PUT http://localhost:8080/profiles/2 \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"accountId":2,"displayName":"demo","bio":"bio"}'
```

Export all account data for GDPR compliance:

```bash
curl http://localhost:8080/accounts/2/export?tenantId=1
```

Delete an account:

```bash
curl -X DELETE http://localhost:8080/accounts/2?tenantId=1
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

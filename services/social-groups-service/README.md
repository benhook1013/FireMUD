# Social Groups Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/social-groups/v1](../../protos/social-groups/v1)

## Running Locally

```bash
./gradlew :social-groups-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

## Environment Variables

This service uses the standard `FIREMUD_` prefixed variables for PostgreSQL and
Redis connectivity. See the
[Environment Variables & Secrets Management](../../design/architecture/infrastructure/environment-and-secrets.md)
doc for defaults.

`firemud.services.logging-admin-service` specifies the gRPC endpoint for the
Logging & Admin Service. The default value is `logging-admin-service:6565`.

## API Documentation

REST endpoints are documented in `openapi.yaml` within the resources directory. A Swagger UI can be generated using any OpenAPI toolchain.

### Example Request

Create a friend link:

```bash
curl -X POST http://localhost:8080/friends \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"accountId":100,"friendAccountId":200}'
```

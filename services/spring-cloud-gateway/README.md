# Spring Cloud Gateway Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/spring-cloud-gateway/v1](../../protos/spring-cloud-gateway/v1)
  - `gateway_management_service.proto` exposes remote route APIs
- **OpenAPI spec**: [src/main/resources/openapi.yaml](src/main/resources/openapi.yaml)

## Running Locally

```bash
./gradlew :spring-cloud-gateway:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

## Environment Variables

This gateway is mostly stateless. It accepts the standard `FIREMUD_` prefixed
variables for consistency with other services, though PostgreSQL and Redis
settings are ignored. Important variables include:

| Variable | Purpose | Default |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Spring profile (`dev` or `prod`) | `dev` |
| `SERVER_PORT` | HTTP port exposed by the service | `8080` |

See the [Environment Variables & Secrets Management](../../design/architecture/infrastructure/environment-and-secrets.md)
document for the full list of options.

## Tenant Handling and Dependencies

Routes map hostnames or path prefixes to a `tenantId` as explained in the
[Spring Cloud Gateway design](design/README.md). The service forwards traffic to
several backends:

- **Game Session Service** – gameplay WebSocket and REST endpoints.
- **Logging & Admin Service** – secured admin APIs.
- **Game Design Service** – content management routes.
- **TCP Proxy Service** – Telnet clients connect through this proxy.

All game data remains isolated by tenant; the gateway simply passes the
identifier to downstream services.

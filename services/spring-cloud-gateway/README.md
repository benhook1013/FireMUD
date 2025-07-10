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

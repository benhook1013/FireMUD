# 🔗 Design Document for Social Groups Service

The design for this service is located here:

[📄 Central Architecture: Social Groups Service Design](../../../design/architecture/microservices/social-groups-service/README.md)

This stub exists to make the design easy to find from the service source tree.
An OpenAPI specification is available at `src/main/resources/openapi.yaml`.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

- `POST /friends` – create a friend link.

```bash
curl -X POST http://localhost:8080/friends \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"accountId":100,"friendAccountId":200}'
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`social_groups_service.proto`](../../../protos/social-groups/v1/social_groups_service.proto).

```bash
grpcurl -plaintext localhost:6565 social_groups.v1.SocialGroupsService/Ping
```

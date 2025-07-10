# 🔗 Design Document for Game Design Service

The design for this service is located here:

[📄 Central Architecture: Game Design Service Design](../../../design/architecture/microservices/game-design-service/README.md)

Additional details on template management can be found in
[game-templates.md](game-templates.md).

This stub exists to make the design easy to find from the service source tree.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_design_service.proto`](../../../protos/game-design/v1/game_design_service.proto).
- `SaveRevision(SaveRevisionRequest) returns (SaveRevisionResponse)` – persists a design change.
- `PublishVersion(PublishVersionRequest) returns (PublishVersionResponse)` – publishes a frozen version.
- `ListVersions(ListVersionsRequest) returns (ListVersionsResponse)` – lists available versions.

```bash
grpcurl -plaintext localhost:6565 game_design.v1.GameDesignService/Ping
```

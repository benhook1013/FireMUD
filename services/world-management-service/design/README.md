# 🔗 Design Document for World Management Service

The design for this service is located here:

[📄 Central Architecture: World Management Service Design](../../../design/architecture/microservices/world-management-service/README.md)

This stub exists to make the design easy to find from the service source tree.

The service creates temporary **instances** of zones for dungeons or housing.
Instances expire automatically based on the `world.instance.expiration-hours`
property.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.

The service exposes an OpenAPI specification under `/v3/api-docs` with a Swagger
UI at `/swagger-ui.html` when running locally.

```bash
curl http://localhost:8080/ping
```

All requests must include a valid JWT in the `Authorization` header. See the
[Security Architecture](../../../design/architecture/system-architecture-security.md)
for accepted claims.

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – basic connectivity check defined in [`world_management_service.proto`](../../../protos/world-management/v1/world_management_service.proto).
- `GetRoom(GetRoomRequest) returns (GetRoomResponse)` – fetches a room's JSON representation.
- `UpdateWorldState(UpdateWorldStateRequest) returns (UpdateWorldStateResponse)` – applies pending world updates.

Call the `Ping` method with:

```bash
grpcurl -plaintext localhost:6565 world_management.v1.WorldManagementService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

## World Events

World events are persisted in the `world_event` table and processed
periodically by `WorldEventService`. A weather change event updates the
`region.weather` column before notifying other services.

## Saga Participation

World creation for a new tenant runs as a Saga using the helper utilities from
`firemud-common`. Each step is described in
[world-creation-workflow.md](../../../design/architecture/microservices/world-management-service/world-creation-workflow.md)
and can be rolled back if a later step fails. This ensures worlds are created
consistently even when the workflow spans multiple services.

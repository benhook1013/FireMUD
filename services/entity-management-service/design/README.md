# 🔗 Design Document for Entity Management Service

The design for this service is located here:

[📄 Central Architecture: Entity Management Service Design](../../../design/architecture/microservices/entity-management-service/README.md)

This stub exists to make the design easy to find from the service source tree.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`entity_management_service.proto`](../../../protos/entity-management/v1/entity_management_service.proto).
- `CreateCharacter(CreateCharacterRequest) returns (CreateCharacterResponse)` – builds a new player character.
- `UpdateEntity(UpdateEntityRequest) returns (UpdateEntityResponse)` – updates stats or equipment.
- `QueryInventory(QueryInventoryRequest) returns (QueryInventoryResponse)` – lists items for an entity.

```bash
grpcurl -plaintext localhost:6565 entity_management.v1.EntityManagementService/Ping
```

# 🔗 Design Document for Game Logic Service

The design for this service is located here:

[📄 Central Architecture: Game Logic Service Design](../../../design/architecture/microservices/game-logic-service/README.md)

This stub exists to make the design easy to find from the service source tree.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_logic_service.proto`](../../../protos/game-logic/v1/game_logic_service.proto).

```bash
grpcurl -plaintext localhost:6565 game_logic.v1.GameLogicService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

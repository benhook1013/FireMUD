# 🔗 Design Document for Game Logic Service

The design for this service is located here:

[📄 Central Architecture: Game Logic Service Design](../../../design/architecture/microservices/game-logic-service/README.md)

This stub exists to make the design easy to find from the service source tree.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /command` – submit a gameplay command body as plain text.

```bash
curl http://localhost:8080/ping
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_logic_service.proto`](../../../protos/game-logic/v1/game_logic_service.proto).
- `ExecuteCommand(ExecuteCommandRequest) returns (ExecuteCommandResponse)` – process a command and return the result.

```bash
grpcurl -plaintext localhost:6565 game_logic.v1.GameLogicService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

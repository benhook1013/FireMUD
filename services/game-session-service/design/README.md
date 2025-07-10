# 🔗 Design Document for Game Session Service

The design for this service is located here:

[📄 Central Architecture: Game Session Service Design](../../../design/architecture/microservices/game-session-service/README.md)

This stub exists to make the design easy to find from the service source tree.

## Configuration

Environment variables configure the PostgreSQL and Redis connections via
`DatabaseAutoConfiguration` and `RedisProperties`. Refer to the
[Deployment Environments](../../../design/architecture/infrastructure/deployment-environments.md)
document for details. The `.env.sample` file contains example values.

The service enforces multi-tenant isolation. All tables include a `tenant_id`
column and Redis keys are prefixed with this value as outlined in the
[Multi-Tenancy design](../../../design/architecture/system-architecture-multi-tenancy.md).

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /sessions` – create a new game session from a published version.
- `POST /sessions/{id}/stop` – stop a running session.
- `POST /sessions/{id}/restart` – restart a stopped session.

```bash
curl http://localhost:8080/ping
```

To start a session via REST:

```bash
curl -X POST http://localhost:8080/sessions \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"demo","versionId":1}'
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_session_service.proto`](../../../protos/game-session/v1/game_session_service.proto).
- `StartSession(StartSessionRequest) returns (StartSessionResponse)` – creates a new game instance.
- `EnqueueCommand(EnqueueCommandRequest) returns (EnqueueCommandResponse)` – queues a player action.
- `QueryState(QueryStateRequest) returns (QueryStateResponse)` – retrieves current game or player state.

```bash
grpcurl -plaintext localhost:6565 game_session.v1.GameSessionService/Ping
```

Start a session via gRPC:

```bash
grpcurl -plaintext -d '{"tenantId":"demo","versionId":1}' \
  localhost:6565 game_session.v1.GameSessionService/StartSession
```

## Additional Notes

- See the "Cross-Region Sharding and Session Handoff" section in the central
  [Game Session Service design](../../../design/architecture/microservices/game-session-service/README.md)
  for how sessions migrate between clusters.
- Metrics emitted by this service feed the operator
  [Analytics Dashboards](../../../design/architecture/microservices/logging-admin-service/analytics-dashboards.md).
Prometheus scrapes metrics from `/actuator/prometheus`.

## Saga Participation

Game startup and shutdown are coordinated using the shared `Saga` helpers from
`firemud-common`. Each dependent service (World Management, Entity Management
and Game Logic) confirms its part of the workflow before the session becomes
active. Failures trigger compensating steps, ensuring consistent rollbacks. See
[Transaction Strategies](../../../design/architecture/system-architecture-transactions.md)
for background.

## Redis Keys

Session state needed for reconnect recovery is stored under
`session:{tenantId}:{sessionId}`. Keys are removed when a session stops.

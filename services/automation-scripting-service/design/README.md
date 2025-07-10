# 🔗 Design Document for Automation Scripting Service

The design for this service is located here:

[📄 Central Architecture: Automation Scripting Service Design](../../../design/architecture/microservices/automation-scripting-service/README.md)

This stub exists to make the design easy to find from the service source tree.

## Configuration

PostgreSQL and Redis connections are configured via the common
`DatabaseAutoConfiguration` and `RedisProperties` classes. Refer to
[Deployment Environments](../../../design/architecture/infrastructure/deployment-environments.md)
for default values. Local development typically uses the settings from
`.env.sample`.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`automation_scripting_service.proto`](../../../protos/automation-scripting/v1/automation_scripting_service.proto).

```bash
grpcurl -plaintext localhost:6565 automation_scripting.v1.AutomationScriptingService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

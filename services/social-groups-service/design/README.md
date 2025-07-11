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

- `POST /mail` – send an asynchronous in-game mail message.

```bash
curl -X POST http://localhost:8080/mail \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"senderAccountId":100,"recipientAccountId":200,"subject":"Hi","content":"Hello"}'
```

- `POST /guilds/storage` – add an item to guild storage.

```bash
curl -X POST http://localhost:8080/guilds/storage \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"guildId":10,"itemName":"Sword","quantity":1}'
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`social_groups_service.proto`](../../../protos/social-groups/v1/social_groups_service.proto).

```bash
grpcurl -plaintext localhost:6565 social_groups.v1.SocialGroupsService/Ping
```

- `POST /chat` – send a chat message filtered for profanity.

```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"senderAccountId":100,"content":"hello"}'
```

Profanity violations are automatically reported to the Logging & Admin Service
via gRPC.

## Metrics & Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. OpenTelemetry spans are
exported to the collector defined in the shared configuration. No additional
setup is required when running `./gradlew bootRun`.

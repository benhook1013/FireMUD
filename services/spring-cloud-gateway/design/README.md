# 🔗 Design Document for Spring Cloud Gateway

The design for this service is located here:

[📄 Central Architecture: Spring Cloud Gateway Design](../../../design/architecture/microservices/spring-cloud-gateway/README.md)

This stub exists to make the design easy to find from the service source tree.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`gateway_management_service.proto`](../../../protos/spring-cloud-gateway/v1/gateway_management_service.proto).
- `UpsertRoute(RouteDefinition) returns (RouteResponse)` – adds or updates a gateway route.
- `RemoveRoute(RouteRequest) returns (RouteResponse)` – deletes a route.

```bash
grpcurl -plaintext localhost:6565 spring_cloud_gateway.v1.GatewayManagementService/Ping
```

# Spring Cloud Gateway API Contracts

## Dynamic Route Management

Gateway route mutation APIs are internal-only control-plane surfaces:

- REST and gRPC upsert/remove operations are explicitly enabled dev/test surfaces that apply process-local in-memory overrides on top of the released baseline.
- The version-controlled declarative route catalog remains the sole player-facing authority; production changes use deployment rollout or a predeclared bounded failover switch.
- Mutation components and endpoints are absent or disabled by default, unreachable through player-facing ingress, and cause player-facing startup to fail if enabled.
- Protected routes and unsafe route IDs, destinations, predicates, and filters are rejected even in dev/test.
- Production runtime mutation requires a separate future decision; adding persistence, convergence, audit, or readiness fields does not promote this API in place.

## Management Plane Security

Spring Cloud Gateway exposes both HTTP and gRPC management interfaces for operators and tooling. These endpoints are strictly internal and secured separately from player-facing traffic.

### Reachability

- REST management endpoints such as `POST /routes` and `DELETE /routes/{routeId}` are reachable only via cluster-internal Services or a dedicated admin ingress. They are never published on the public Internet-facing load balancer.
- The gRPC `GatewayManagementService` runs on port `6565` and is exposed only on internal network surfaces such as `ClusterIP` Services and private admin ingress.

### Authentication and Authorization

- gRPC management calls use mutual TLS with cert-manager-issued client certificates. Only clients presenting trusted admin certificates can connect.
- HTTP management endpoints are authenticated and authorized at the gateway boundary, not delegated to downstream services.
- The recommended model for gateway-owned management HTTP endpoints is mTLS client certificates plus `NetworkPolicy` allowlists restricting which pods or namespaces may reach the endpoint.
- JWT-based roles apply to product and admin APIs routed through the gateway but are not the primary authorization mechanism for gateway-owned management endpoints.
- Operator client certificates should be issued by cert-manager under ClusterIssuer `firemud-ca-issuer`, must include the `clientAuth` EKU, and should be distributed as a dedicated Kubernetes Secret readable only by operator tooling service accounts.

### Data Plane vs Control Plane

- Port `8080` hosts the gateway HTTP and WebSocket server. Public ingress exposes only data-plane routes on this port.
- Management endpoints on port `8080` are reachable only via internal-only Services or a dedicated private ingress.
- Port `6565` is reserved for internal gRPC management.
- Kubernetes `Service` and `Ingress` objects must keep these planes separate so exposing gameplay routes does not accidentally publish management endpoints.

## REST & gRPC Endpoints

### REST

- `GET /ping` -> basic health check returning `"pong"`.
- `POST /routes` -> add or upsert an in-memory route override.
- `DELETE /routes/{routeId}` -> remove an in-memory route override.

These endpoints are internal-only in production. The examples below are for local development or trusted operator contexts.

```bash
curl http://localhost:8080/ping
```

```bash
curl -X POST http://localhost:8080/routes \
  -H 'Content-Type: application/json' \
  -d '{"routeId":"demo","uri":"http://example.com","predicates":[],"filters":[]}'
```

```bash
curl -X DELETE http://localhost:8080/routes/demo
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` -> connectivity check defined in [`gateway_management_service.proto`](../../../../protos/spring-cloud-gateway/v1/gateway_management_service.proto).
- `UpsertRoute(UpsertRouteRequest) returns (UpsertRouteResponse)` -> add or replace an in-memory route override on the active gateway instance.
- `RemoveRoute(RemoveRouteRequest) returns (RemoveRouteResponse)` -> remove an in-memory route override from the active gateway instance.

```bash
# Local development only (no mTLS)
grpcurl -plaintext localhost:6565 gateway.v1.GatewayManagementService/Ping

# Production / operator contexts (mTLS)
grpcurl \
  -cacert "$FIREMUD_GRPC_CA_CERT_PATH" \
  -cert "$FIREMUD_GRPC_CERT_CHAIN_PATH" \
  -key "$FIREMUD_GRPC_PRIVATE_KEY_PATH" \
  spring-cloud-gateway:6565 \
  gateway.v1.GatewayManagementService/Ping
```

## Proto Files

Gateway-related proto definitions are stored in [../../../../protos/spring-cloud-gateway/v1](../../../../protos/spring-cloud-gateway/v1).

- `gateway_management_service.proto` defines the gateway management and health RPCs used by operators and tooling, including `Ping`, `UpsertRoute`, and `RemoveRoute`.
- After proto edits, run `./gradlew generateProto` to regenerate gateway stubs.

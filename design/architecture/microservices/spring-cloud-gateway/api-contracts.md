# Spring Cloud Gateway API Contracts

## Dynamic Route Management (Target State)

Gateway route mutation APIs are explicitly enabled dev/test-only control-plane surfaces. The current implementation is disabled by default, permits enablement only with exclusively active `dev`/`test` profiles, fails startup for enabled mutation under any other profile set, and guards both REST and gRPC writes. Current hosted Helm deployments render the Gateway with the `prod` profile and do not enable mutation, so this is not a current hosted player-ingress authorization bypass. Complete private ingress, mTLS/network-policy isolation, deployment proof, and route-input allowlist safeguards remain target-state gaps; an explicitly enabled dev/test or dev-demo deployment on a public listener is unsupported and must not be treated as safe merely because a privileged JWT guards REST. See the [Gateway implementation status](./README.md#implementation-status) and [operational guardrails](./operations.md#dynamic-route-operational-guardrails).

- Target-state: REST and gRPC upsert/remove operations apply process-local in-memory overrides on top of the released baseline only in explicitly classified dev/test environments.
- Target-state: the version-controlled declarative route catalog remains the sole player-facing authority; the production operator surface is diagnostics only, while route changes use the separately accepted reviewed declarative deployment workflow or a predeclared bounded failover switch.
- Target-state: mutation components and endpoints are absent or disabled by default, unreachable through player-facing ingress, and cause player-facing startup to fail if enabled; a future generic production runtime control plane requires a separate accepted decision.
- Target-state: protected routes and unsafe route IDs, destinations, predicates, and filters are rejected even in dev/test.
- Production runtime mutation requires a separate future decision; adding persistence, convergence, audit, or readiness fields does not promote this API in place.

## Management Plane Security

Spring Cloud Gateway exposes both HTTP and gRPC management interfaces for operators and tooling. The route-mutation reachability, authentication, and plane-separation requirements below are target-state safeguards; the [Gateway implementation status](./README.md#implementation-status) records the current enforcement gap.

### Reachability

- Target-state: dev/test REST mutation endpoints such as `POST /routes` and `DELETE /routes/{routeId}` are reachable only via cluster-internal Services or a dedicated test/admin surface. They are absent or disabled in player-facing environments and are never published on the public Internet-facing load balancer.
- Target-state: the gRPC `GatewayManagementService` route-mutation surface runs on port `6565` and is exposed only on internal network surfaces such as `ClusterIP` Services and private admin ingress.

### Authentication and Authorization

- gRPC management calls use mutual TLS with cert-manager-issued client certificates. Only clients presenting trusted admin certificates can connect.
- Target-state: HTTP diagnostics and explicitly enabled dev/test mutation endpoints are authenticated and authorized at the gateway boundary, not delegated to downstream services. Production operator access is limited to diagnostics.
- Target-state: the recommended model for gateway-owned management HTTP endpoints is mTLS client certificates plus `NetworkPolicy` allowlists restricting which pods or namespaces may reach the endpoint.
- JWT-based roles apply to product and admin APIs routed through the gateway but are not the primary authorization mechanism for gateway-owned management endpoints.
- Operator client certificates should be issued by cert-manager under ClusterIssuer `firemud-ca-issuer`, must include the `clientAuth` EKU, and should be distributed as a dedicated Kubernetes Secret readable only by operator tooling service accounts.

### Data Plane vs Control Plane

- Port `8080` hosts the gateway HTTP and WebSocket server. Target-state public ingress exposes only data-plane routes on this port.
- Target-state: management endpoints on port `8080` are reachable only via internal-only Services or a dedicated private ingress.
- Port `6565` is reserved for internal gRPC management.
- Target-state: Kubernetes `Service` and `Ingress` objects must keep these planes separate so exposing gameplay routes does not accidentally publish management endpoints.

## REST & gRPC Endpoints

### REST

- `GET /ping` -> basic health check returning `"pong"`.
- `POST /routes` -> add or upsert an in-memory route override (target-state dev/test only).
- `DELETE /routes/{routeId}` -> remove an in-memory route override (target-state dev/test only).

These mutation endpoints are not a production surface. The examples below are target-state examples for local development or explicitly classified trusted test contexts; production operator access is diagnostic-only. The HTTP mTLS paths use the dedicated `FIREMUD_GATEWAY_HTTP_*` operator-tooling contract from the [environment and secrets catalog](../../infrastructure/environment-and-secrets-catalog.md#gateway-http-management-plane-tls-target-state), not the Gateway service's `FIREMUD_GRPC_*` workload identity.

```bash
curl http://localhost:8080/ping
```

```bash
# Trusted dev/test admin tooling only. The operator client certificate is authorized
# at the gateway boundary, and NetworkPolicy must allow this pod/namespace to reach
# the internal management Service; this endpoint is never public ingress.
curl --fail-with-body \
  --cacert "$FIREMUD_GATEWAY_HTTP_CA_CERT_PATH" \
  --cert "$FIREMUD_GATEWAY_HTTP_CLIENT_CERT_CHAIN_PATH" \
  --key "$FIREMUD_GATEWAY_HTTP_CLIENT_PRIVATE_KEY_PATH" \
  -X POST https://spring-cloud-gateway-management:8080/routes \
  -H 'Content-Type: application/json' \
  -d '{"routeId":"demo","uri":"http://game-session-service:8080","predicates":[],"filters":[]}'
```

```bash
# Trusted dev/test admin tooling only; use the same internal mTLS and
# NetworkPolicy reachability requirements as the upsert example above.
curl --fail-with-body \
  --cacert "$FIREMUD_GATEWAY_HTTP_CA_CERT_PATH" \
  --cert "$FIREMUD_GATEWAY_HTTP_CLIENT_CERT_CHAIN_PATH" \
  --key "$FIREMUD_GATEWAY_HTTP_CLIENT_PRIVATE_KEY_PATH" \
  -X DELETE https://spring-cloud-gateway-management:8080/routes/demo
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` -> connectivity check defined in [`gateway_management_service.proto`](../../../../protos/spring-cloud-gateway/v1/gateway_management_service.proto).
- `UpsertRoute(UpsertRouteRequest) returns (UpsertRouteResponse)` -> add or replace an in-memory route override on the active gateway instance (dev/test only).
- `RemoveRoute(RemoveRouteRequest) returns (RemoveRouteResponse)` -> remove an in-memory route override from the active gateway instance (dev/test only).

```bash
# Local development only (no mTLS)
grpcurl -plaintext localhost:6565 gateway.v1.GatewayManagementService/Ping

# Production diagnostic context (mTLS; route mutation is not enabled)
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

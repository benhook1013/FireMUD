# Spring Cloud Gateway API Contracts

## Dynamic Route Management (Target State)

Gateway route mutation APIs are target-state, explicitly enabled dev/test-only control-plane surfaces. The current implementation does not enforce all of the profile isolation, startup rejection, ingress isolation, authorization, or route-input allowlist safeguards below; see the [Gateway implementation status](./README.md#implementation-status) and [operational guardrails](./operations.md#dynamic-route-operational-guardrails).

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

- gRPC management calls use mutual TLS with client credentials resolved from the canonical [`operator-credential-binding/v1`](../../system-architecture-deploy-preflight-policy.md#canonical-target-operator-credential-binding-record) record. `CERT_MANAGER` is one accepted binding type; `SECRET_BACKED` resolves its dedicated Secret, while `WORKLOAD_IDENTITY` resolves the provider-projected client identity and trust bundle without a substitute Secret. Only clients presenting credentials that match the record's closed `bindingType`, positive generation, active/allowed overlap leaf fingerprints, URI SAN/profile, endpoint/workload audience, operation identity, and immutable readback evidence can connect. Missing, stale, contradictory, ambiguous, or unavailable binding evidence fails closed; a CA-valid certificate alone is insufficient.
- Target-state: HTTP diagnostics and explicitly enabled dev/test mutation endpoints are authenticated and authorized at the gateway boundary, not delegated to downstream services. Production operator access is limited to diagnostics.
- Target-state: the recommended model for gateway-owned management HTTP endpoints is mTLS client certificates plus `NetworkPolicy` allowlists restricting which pods or namespaces may reach the endpoint. Both controls are necessary but neither replaces the exact operator binding-record checks above.
- JWT-based roles apply to product and admin APIs routed through the gateway but are not the primary authorization mechanism for gateway-owned management endpoints.
- For `SECRET_BACKED` and `CERT_MANAGER` bindings, operator client certificates must include the `clientAuth` EKU and use the dedicated Secret/output Secret selected by the binding record; cert-manager under ClusterIssuer `firemud-ca-issuer` is the `CERT_MANAGER` issuance example. For `WORKLOAD_IDENTITY`, the provider supplies the projected client identity and trust bundle instead of a substitute Secret. Generation advancement, routine bounded overlap, incident revocation, and accepted/rejected-leaf readback remain owned by the linked binding record; the current endpoint does not yet provide that target enforcement proof.

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

# Production diagnostic context (mTLS; route mutation is not enabled). This
# file-based grpcurl form applies to SECRET_BACKED and CERT_MANAGER bindings:
# resolve the paths, endpoint, and TLS authority from the selected canonical
# operator-credential-binding/v1 record during deployment preflight; they are
# not FireMUD service environment variables. For WORKLOAD_IDENTITY, do not
# invent certificate/key files or another grpcurl representation: invoke the
# provider/platform-native operator tooling for the projected identity and
# trust bundle, preserving the same binding-resolved endpoint and authority.
# See the [binding record](../../system-architecture-deploy-preflight-policy.md#canonical-target-operator-credential-binding-record)
# and [operator credentials runbook](../../system-architecture-operator-credentials-runbook.md#operator-client-certificates-mtls).
grpcurl \
  -cacert "/secure/operator/<binding-resolved-grpc-ca-bundle>.crt" \
  -cert "/secure/operator/<binding-resolved-client-cert-chain>.crt" \
  -key "/secure/operator/<binding-resolved-client-private-key>.key" \
  -authority "<binding-resolved-grpc-authority>" \
  "<binding-resolved-grpc-endpoint>" \
  gateway.v1.GatewayManagementService/Ping
```

## Proto Files

Gateway-related proto definitions are stored in [../../../../protos/spring-cloud-gateway/v1](../../../../protos/spring-cloud-gateway/v1).

- `gateway_management_service.proto` defines the gateway management and health RPCs used by operators and tooling, including `Ping`, `UpsertRoute`, and `RemoveRoute`.
- After proto edits, run `./gradlew generateProto` to regenerate gateway stubs.

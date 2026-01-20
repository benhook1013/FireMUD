# FireMUD System Architecture: Security

This document outlines how FireMUD secures service communication, manages authentication keys, protects network traffic, and tracks abuse attempts. It complements the [Authentication & Authorization](./system-architecture-authentication.md) document by focusing on secret management, TLS usage, abuse resistance, and operational trust guarantees.

Kubernetes Secrets has been selected as the platform's unified secret
storage solution. This keeps credential management simple while working
seamlessly with cert-manager for automatic rotation of TLS certificates.
JWT signing keys rotate manually or through cert-manager automation.

---

## Token Issuance & Secret Storage

- The **Account Service** signs JWTs used for internal gRPC authorization.
- Signing keys are stored as **Kubernetes Secrets**. Rotation is can be performed manually or via **cert-manager** automation.
- Keys are **never committed** to the repository and can be rotated without redeploying other services.
- A **JWKS endpoint** exposes public keys for internal services to validate tokens. The
  Account Service serves these keys at `/.well-known/jwks.json`. The JWKS file is static and
  must be updated manually when signing keys rotate.

### Key and Certificate Rotation

- cert-manager issues the mTLS certificates used between services. JWT signing keys are stored as Secrets and rotated manually; can also be rotated by cert-manager.
- All services support **hot reload** of mounted secrets using the `TlsCertificateWatcher`, `JwtSecretWatcher`, and `GrpcServerTlsReloader` utilities from the `firemud-common` library. JWT secrets can be mounted from a file defined by `FIREMUD_AUTH_JWT_SECRET_PATH`. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) and [Shared Libraries](./system-architecture-shared-libraries.md) for variable definitions and watchers.
- The environment variables `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`, and `FIREMUD_AUTH_JWT_SECRET_PATH` control the file locations that these watchers monitor.
- During rotation, services reload credentials when files change.
- The JWKS endpoint serves a static key file located at `services/account-service/src/main/resources/jwks.json`. Rotation is automated with cert-manager.

---

## TLS Termination & Internal Encryption

- External client `https://` / `wss://` traffic is terminated at the Internet-facing load balancer.
- The **Spring Cloud Gateway** routes client traffic to backend services over in-cluster `http://` / `ws://` targets. Internal service-to-service traffic (for example, Game Session Service to other microservices) uses **mutual TLS (mTLS)** gRPC.
- All internal gRPC calls between microservices use **mutual TLS (mTLS)**:
  - Certificates are issued by **cert-manager**
  - Distributed via **Kubernetes Secrets**
  - Trusted using the Kubernetes CA chain

### TLS Termination for Gateway

TLS for player and Telnet flows is applied hop-by-hop so traffic stays protected while keeping the DMZ boundary explicit. Unless otherwise noted, this section describes the **target-state** production configuration; see individual microservice design docs for implementation status details.

- **Browser / Web client path**
  - Browser client → external load balancer over `https://` / `wss://` using a certificate issued by cert-manager (for example, via an Ingress or `LoadBalancer` Service).
  - The external load balancer terminates Internet-facing TLS and forwards plain `http://` / `ws://` traffic to Spring Cloud Gateway pods in the DMZ namespace.
  - Spring Cloud Gateway then routes requests to backend services over in-cluster `http://` / `ws://` endpoints (typically on port `8080`). Internal service-to-service calls (for example, Game Session Service to other microservices) use mTLS-protected gRPC channels configured via `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, and `FIREMUD_GRPC_CA_CERT_PATH` as described in [Environment & Secrets Management](./infrastructure/environment-and-secrets.md#grpc-tls-certificates).
- **Telnet gameplay path**
  - Telnet client → TCP Proxy Service over raw TCP by default (`TCP_PROXY_PORT`), or over TLS on a dedicated port when `TCP_PROXY_TLS_ENABLED=true` and `TCP_PROXY_TLS_CERT` / `TCP_PROXY_TLS_KEY` (and `TCP_PROXY_TLS_PORT`) are provided. Raw Telnet remains supported even in production so that classic clients which do not understand TLS can connect, but operators should treat this as an intentionally legacy, plaintext channel and apply appropriate hardening (for example strong credential policies, careful IP throttling, and network-level filtering) rather than assuming it provides the same confidentiality guarantees as the HTTPS/WebSocket path. The preferred production deployment pattern is to terminate public Telnet on a dedicated Telnet edge proxy (for example HAProxy) that forwards to the TCP Proxy Service using PROXY protocol on an internal-only listener, as described in the TCP Proxy design’s PROXY protocol section; exposing the TCP Proxy’s raw Telnet port directly to the Internet should be reserved for tightly controlled dev/test setups.
  - TCP Proxy Service → Spring Cloud Gateway uses `wss://` with mutual TLS by connecting to a dedicated internal-only Gateway WebSocket mTLS listener (for example a `spring-cloud-gateway-mtls` `ClusterIP` Service on a separate TLS port). The proxy presents a client certificate and key from `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH` / `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`, and validates the gateway certificate against `FIREMUD_GATEWAY_WS_CA_CERT_PATH` with hostname verification enabled using the host from `GATEWAY_WS_URL`. Spring Cloud Gateway promotes `X-Proxy-*` headers only after authenticating this hop via the client certificate identity; see [Gateway Architecture](./system-architecture-gateway.md#tcp-proxy--gateway-authentication). The full Proxy → Gateway WebSocket TLS configuration is summarized in the **TLS Config Matrix** section below.
  - Spring Cloud Gateway forwards gameplay to the Game Session Service over the `/ws/game/**` WebSocket route. Game Session Service then communicates with other microservices over mTLS gRPC using the same `FIREMUD_GRPC_*` variables that all services share.
  - For a compact view of all TLS and trust surfaces specific to the TCP Proxy (Telnet plaintext/TLS, WebSocket mTLS to Gateway, and internal gRPC mTLS), see the TCP Proxy Service design’s **TLS & Trust Surfaces (Summary)** section in `design/architecture/microservices/tcp-proxy-service/README.md`.

Local Docker Compose environments may use plain `http://` / `ws://` for simplicity, but the production Kubernetes profile is expected to follow this termination chain so that only the Internet edge terminates TLS and all intra-cluster hops to and from the gateway are either mTLS (gRPC) or `wss://` with mTLS for the Telnet bridge.

---

### TLS Config Matrix: TCP Proxy ↔ Spring Cloud Gateway (WebSocket)

This matrix is the authoritative reference for configuring the Proxy → Gateway WebSocket TLS hop; other docs should link here instead of re-listing the variables.

| Aspect | Env vars / expectation | Dev profile | Prod profile |
| --- | --- | --- | --- |
| WebSocket target URL | `GATEWAY_WS_URL` (for example `ws://spring-cloud-gateway:8080/ws/game` in local Docker, `wss://spring-cloud-gateway-mtls:8443/ws/game` in cluster). The host component is used for SNI and hostname verification in mTLS mode. | May use `ws://` without client certificates; hostname/SAN verification is best-effort and may be relaxed in throwaway dev environments. | Must use `wss://` pointing at the internal mTLS listener; host must match a SAN on the Gateway certificate. No plaintext fallback. |
| Proxy → Gateway WebSocket client cert | `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH` | Optional when `GATEWAY_WS_URL` uses `ws://` for local dev. When provided for `wss://`, may reuse the same files as gRPC in small setups. | Required; must present a client certificate with `clientAuth` EKU that chains to the cluster CA. Managed independently from the gRPC server identity even if files are shared. |
| Proxy → Gateway WebSocket CA bundle | `FIREMUD_GATEWAY_WS_CA_CERT_PATH` | Optional when using `ws://`. When using `wss://` in dev, should point at the Gateway’s issuing CA or a test CA bundle. | Required; must contain the CA(s) that issue the Gateway’s mTLS listener certificate so the proxy can validate the server cert and SANs. |
| Gateway WebSocket mTLS listener | Gateway Service/Ingress configuration; typically a `spring-cloud-gateway-mtls` `ClusterIP` Service on a dedicated TLS port. | May be omitted; Gateway can expose only the plain WebSocket route for local stacks. | Required; player-facing stacks must expose an internal-only, mTLS-protected WebSocket listener that accepts only TCP Proxy clients. |
| gRPC mTLS (Proxy ↔ Game Session and other services) | `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH` as documented in [Environment & Secrets Management](./infrastructure/environment-and-secrets.md#grpc-tls-certificates). | May reuse the same files as WebSocket mTLS in small dev clusters, or run with relaxed TLS in constrained local setups as described in service-specific docs. | Required for all internal gRPC calls. Certificates must use `serverAuth` / `clientAuth` EKUs as appropriate for each service role. |

---

## Cross-Service Trust

- Internal JWT validation uses the Account Service’s JWKS endpoint.
- All internal traffic is authenticated using **mTLS** with cert-manager-issued certificates.
- Peer-level trust is enforced via **Kubernetes NetworkPolicies**, which restrict ingress and egress paths between services.

---

## Network Security & Boundary Design

- The **Spring Cloud Gateway** and **TCP Proxy Service** reside in the **network DMZ** and serve as the only ingress points for client traffic.
- Internal microservices are not directly exposed externally.
- Traffic flow is controlled via **NetworkPolicies**, which whitelist internal service access.
   A baseline policy restricts **ingress** for all microservice pods (except the Gateway and TCP proxy) so they only accept traffic from other pods in the namespace. The manifests are provided under [`k8s/network-policies/`](../../k8s/network-policies).
- **Zero-trust** principles are enforced through mTLS and JWT-based validation, providing a foundation for ongoing hardening efforts.

---

## Brute-Force Defense and Abuse Handling

- The **Game Session Service** monitors login attempts **per IP** and enforces connection limits and per-session command rate limiting via Redis.
  - Repeated failures result in **connection closure** and **temporary IP blacklisting**.
  - Global login spikes introduce **artificial delay** to slow brute-force attempts.
  - Suspicious login activity triggers **notification emails** to the account holder.
- The **TCP Proxy Service** and **Spring Cloud Gateway** forward client IP headers so throttling applies uniformly across protocols. The Gateway also uses a Redis-backed `RequestRateLimiter` to restrict excessive requests per IP.

- Abuse detection includes **heuristics** around spam commands, hotspot behaviors, and abnormal tick patterns.

---

## Audit Logging and Abuse Visibility

- All failed logins, suspicious activity, and abuse attempts are captured in:
  - **Elasticsearch-backed logs**
  - The **Logging & Admin Service dashboard** ([design](./microservices/logging-admin-service/README.md))
  - Admin actions such as bans are recorded by the Logging & Admin Service for auditability.
  - Role changes are tracked for audit purposes.

---

## Telnet Command Handling and Controls

This section is the authoritative reference for plaintext Telnet security invariants (2FA requirements, per-account opt-in, and landing-menu warnings) and how the TCP Proxy Service and Game Session Service enforce them.

- Telnet clients connect through the **TCP Proxy Service**, which is sandboxed in the DMZ. It forwards **all gameplay traffic** to the backend exclusively via WebSocket through Spring Cloud Gateway and uses a narrow, mTLS-protected gRPC link to the **Game Session Service** only to emit `NotifyDisconnect` lifecycle events (no gameplay payloads). These gRPC endpoints are internal-only and are never published through the gateway.
- The proxy **enforces a whitelisted subset of Telnet protocol commands** and **sanitizes** incoming input to protect against malformed sequences, using a dedicated Telnet pipeline in the TCP Proxy Service (currently implemented by `TelnetServerHandler`).
- Telnet-derived flows are tagged with a **connection security** attribute at the TCP Proxy (“plaintext Telnet” vs “TLS Telnet”). This attribute is propagated via Spring Cloud Gateway to the Game Session Service, which uses it to:
  - Include a **landing menu security warning** in the pre-login menu for plaintext Telnet sessions, advising players to prefer the TLS Telnet port or the web client.
  - Include the transport context in `/auth/login` calls to the Account Service so deployment-wide rules such as `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` and per-account “allow plaintext Telnet login” flags can be enforced consistently.
- Client IP headers on Telnet-derived traffic follow the trust model described in [Protocol Bridging](./system-architecture-protocol-bridging.md#bridging-to-the-backend): the TCP Proxy Service supplies `X-Proxy-Client-IP` on its internal WebSocket hop and Spring Cloud Gateway sets the canonical `X-Client-IP` header after stripping spoofable headers from public ingress and authenticating the TCP Proxy identity. In production, the preferred deployment places a Telnet edge proxy (HAProxy) in front of the TCP Proxy Service and enables PROXY protocol so the TCP Proxy can recover the true client IP even when Kubernetes would otherwise SNAT the TCP peer address. When PROXY protocol is not enabled (or source IP is not preserved), per-IP limits and throttles should be treated as best-effort.

---

## Admin Interface Access Model

- Product admin functionality (creator/moderator APIs exposed via Spring Cloud Gateway) is **entirely controlled through JWT `roles`**, issued and managed by the **Account Service**.
- There is **no special network-level access or infrastructure isolation** for product admin APIs — this is an intentional design decision to rely on scoped authorization in the owning services rather than IP allowlists or private network access.
- Operator control-plane endpoints (for example Spring Cloud Gateway dynamic route management and diagnostics) are treated separately from product admin APIs: they are **internal-only** and require mutual TLS (mTLS) client certificates, with reachability restricted by `ClusterIP` Services, private ingress, and `NetworkPolicy` allowlists.
  - **Certificate trust root**: operator clients must present a certificate that chains to the cluster CA used for gRPC mTLS, issued by cert-manager (ClusterIssuer `firemud-ca-issuer`) and configured via `FIREMUD_GRPC_CA_CERT_PATH`.
  - **Client certificate profile**: operator certificates must include the `clientAuth` extended key usage and should be provisioned as a dedicated Kubernetes Secret that is not mounted by normal workloads.
  - **Distribution and access**: Kubernetes RBAC and Secret scoping must restrict which service accounts can read/mount the operator client certificate Secret; NetworkPolicies restrict which pods can reach the management endpoints so that holding a valid certificate alone is not sufficient outside the approved operator surface.
- Admin and moderator accounts can enable **two-factor authentication** using TOTP codes. When enabled, login requests must supply an `otp` field to the Account Service. See [Account Service – Two-Factor Authentication](./microservices/account-service/README.md#two-factor-authentication) for implementation details.

---

## Summary

| Topic | Strategy |
| --- | --- |
| JWT Secret Storage | Kubernetes Secrets with hot reload via `JwtSecretWatcher`; rotation can be manual or automated via cert-manager |
| Key & Cert Rotation | Hot reload via `TlsCertificateWatcher`; automatic JWKS rotation with credential caching |
| TLS Termination | Load balancer |
| Internal Encryption | mTLS via Kubernetes Secrets; server certificate hot reload enabled |
| Trust Enforcement | JWT + mTLS + Kubernetes NetworkPolicies |
| Brute-Force Defense | Spring Cloud Gateway enforces Redis-backed request rate limiting for HTTP/WebSocket/Telnet-bridged traffic; Game Session Service enforces per-IP connection and command rate limits |
| Abuse Detection | Login tracking and command-level heuristics enforce usage patterns |
| Telnet Controls | TCP Proxy Service applies Telnet protocol command whitelisting, sanitization, idle timeouts, and per-connection buffer depth limits; rate-limit policy lives in Gateway and Game Session Service. Plaintext Telnet logins are further constrained by `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` and per-account “allow plaintext Telnet login” flags. |
| Admin Role Access | Product admin APIs are JWT-only with no special network-level restrictions; operator control-plane endpoints are internal-only and require mTLS client certificates |
| Zero Trust | Enforced via mTLS and JWT-based validation |
| 2FA | Available for admin and moderator accounts via TOTP codes and, when `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is enabled, required for any account that logs in over plaintext Telnet |

---

## Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)

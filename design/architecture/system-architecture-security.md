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

- External `https/wss` traffic is terminated at the load balancer.
- The **Spring Cloud Gateway** communicates with backend services over **TLS** to protect gameplay traffic.
- All internal gRPC calls between microservices use **mutual TLS (mTLS)**:
  - Certificates are issued by **cert-manager**
  - Distributed via **Kubernetes Secrets**
  - Trusted using the Kubernetes CA chain

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

- Telnet clients connect through the **TCP Proxy Service**, which is sandboxed in the DMZ. It forwards **all gameplay traffic** to the backend exclusively via WebSocket through Spring Cloud Gateway and uses a narrow, mTLS-protected gRPC link to the **Game Session Service** only to emit `NotifyDisconnect` lifecycle events (no gameplay payloads).
- The proxy **enforces a whitelisted subset of Telnet protocol commands** and **sanitizes** incoming input to protect against malformed sequences. See [`TelnetServerHandler`](../../services/tcp-proxy-service/src/main/java/net/firedevops/firemud/tcpproxy/telnet/TelnetServerHandler.java) for the implementation.

---

## Admin Interface Access Model

- Admin functionality is **entirely controlled through JWT `roles`**, issued and managed by the **Account Service**.
- There is **no special network-level access or infrastructure isolation** for admin features — this is an intentional design decision to rely solely on internal authentication and scoped authorization.
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
| Telnet Controls | TCP Proxy Service applies Telnet protocol command whitelisting, sanitization, idle timeouts, and per-connection buffer depth limits; rate-limit policy lives in Gateway and Game Session Service |
| Admin Role Access | JWT-only; no special network-level restrictions |
| Zero Trust | Enforced via mTLS and JWT-based validation |
| 2FA | Available for admin and moderator accounts via TOTP codes |

---

## Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)

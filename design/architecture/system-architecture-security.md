# 🛡️ FireMUD System Architecture: Security

This document outlines how FireMUD secures service communication, manages authentication keys, protects network traffic, and tracks abuse attempts. It complements the [Authentication & Authorization](./system-architecture-authentication.md) document by focusing on secret management, TLS usage, abuse resistance, and operational trust guarantees.

Kubernetes Secrets has been selected as the platform's unified secret
storage solution. This keeps credential management simple while working
seamlessly with cert-manager for automatic rotation.

---

## 🔑 Token Issuance & Secret Storage

- The **Account Service** signs JWTs used for internal gRPC authorization.
- Signing keys are stored as **Kubernetes Secrets**, provisioned and rotated using **cert-manager**.
- Keys are **never committed** to the repository and can be rotated without redeploying other services.
- A **JWKS endpoint** exposes public keys for internal services to validate tokens. The
  Account Service serves these keys at `/.well-known/jwks.json`.

### Key and Certificate Rotation

- cert-manager issues both JWT signing keys and mTLS certificates, backed by an internal CA.
- All services poll their mounted secrets for updates and support **hot reload** via the shared `GrpcServerTlsReloader`, `TlsCertificateWatcher`, and `JwtSecretWatcher` utilities. JWT secrets can be mounted from a file defined by `FIREMUD_AUTH_JWT_SECRET_PATH`. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) and [Shared Libraries](./system-architecture-shared-libraries.md) for variable definitions and watchers.
- The environment variables `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`, and `FIREMUD_AUTH_JWT_SECRET_PATH` control the file locations that these watchers monitor.
- During rotation, services cache both current and previous credentials to allow for **seamless transition**.
- The JWKS endpoint currently serves a static key file located at `services/account-service/src/main/resources/jwks.json`. Rotation requires updating this file manually, though automated JWKS generation is planned.

---

## 🔒 TLS Termination & Internal Encryption

- External `https/wss` traffic is terminated at the load balancer.
- The **Spring Cloud Gateway** communicates with backend services over **TLS** to protect gameplay traffic.
- All internal gRPC calls between microservices use **mutual TLS (mTLS)**:
  - Certificates are issued by **cert-manager**
  - Distributed via **Kubernetes Secrets**
  - Trusted using the Kubernetes CA chain

---

## 🤝 Cross-Service Trust

- Internal JWT validation uses the Account Service’s JWKS endpoint.
- All internal traffic is authenticated using **mTLS** with cert-manager-issued certificates.
- Peer-level trust is enforced via **Kubernetes NetworkPolicies**, which restrict ingress and egress paths between services.

---

## 🌐 Network Security & Boundary Design

- The **Spring Cloud Gateway** and **TCP Proxy Service** reside in the **network DMZ** and serve as the only ingress points for client traffic.
- Internal microservices are not directly exposed externally.
- Traffic flow is controlled via **NetworkPolicies**, which whitelist internal service access.
   A baseline policy restricts **ingress** for all microservice pods (except the Gateway and TCP proxy) so they only accept traffic from other pods in the namespace. The manifests are provided under [`k8s/network-policies/`](../../k8s/network-policies).
- **Zero-trust** principles are **not currently required** or implemented beyond mTLS and JWT-based validation, but may be reconsidered in future hardening efforts.

---

## 🔐 Brute-Force Defense and Abuse Handling

- The **Game Session Service** is planned to monitor login attempts **per IP** (not yet implemented):
  - Repeated failures result in **connection closure** and **temporary IP blacklisting**.
  - Global login spikes introduce **artificial delay** to slow brute-force attempts.
  - Suspicious login activity triggers **notification emails** to the account holder.

- Abuse detection is planned to expand to include **heuristics** around spam commands, hotspot behaviors, or abnormal tick patterns.
  - These heuristics are **future additions**, intended to detect unusual command frequencies, teleportation loops, or flooding patterns.

---

## 🧾 Audit Logging and Abuse Visibility

- All failed logins, suspicious activity, and abuse attempts are captured in:
  - **Elasticsearch-backed logs**
  - The **Logging & Admin Service dashboard** ([design](./microservices/logging-admin-service/README.md))
- Admin actions (e.g., bans, role changes) are tracked for auditability.

---

## 🔌 Telnet Command Handling and Future Controls

- Telnet clients connect through the **TCP Proxy Service**, which is sandboxed in the DMZ and **never contacts internal services directly**.
- The proxy **enforces a whitelisted subset of Telnet protocol commands** and **sanitizes** incoming input to protect against malformed sequences. See [`TelnetServerHandler`](../../services/tcp-proxy-service/src/main/java/net/firedevops/firemud/telnet/TelnetServerHandler.java) for the implementation.

---

## 🧰 Admin Interface Access Model

- Admin functionality is **entirely controlled through JWT `roles`**, issued and managed by the **Account Service**.
- There is **no special network-level access or infrastructure isolation** for admin features — this is an intentional design decision to rely solely on internal authentication and scoped authorization.
- Admin and moderator accounts can enable **two-factor authentication** using TOTP codes. When enabled, login requests must supply an `otp` field to the Account Service. See [Account Service – Two-Factor Authentication](./microservices/account-service/README.md#two-factor-authentication) for implementation details.

---

## ✅ Summary

| Topic                     | Strategy                                                                 |
|---------------------------|--------------------------------------------------------------------------|
| JWT Secret Storage        | Kubernetes Secrets via cert-manager                                      |
| Key & Cert Rotation       | Hot-reload with caching of old credentials; JWKS rotation still manual |
| TLS Termination           | Load balancer                                                 |
| Internal Encryption       | mTLS via Kubernetes Secrets                                              |
| Trust Enforcement         | JWT + mTLS + Kubernetes NetworkPolicies                                  |
| Brute-Force Defense       | Planned per-IP tracking and throttling (not yet implemented) |
| Abuse Detection           | Current: login only; Future: command-level heuristics                    |
| Telnet Controls           | Telnet protocol command whitelist + sanitization implemented                                     |
| Admin Role Access         | JWT-only; no special network-level restrictions                          |
| Zero Trust                | Not currently adopted; mTLS and JWTs provide strong internal identity    |
| 2FA                       | Available for admin and moderator accounts via TOTP codes               |

---

## 📚 Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)

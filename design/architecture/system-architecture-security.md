# 🛡️ FireMUD System Architecture: Security

This document outlines how FireMUD secures service communication, manages authentication keys, protects network traffic, and tracks abuse attempts. It complements the [Authentication & Authorization](./system-architecture-authentication.md) document by focusing on secret management, TLS usage, abuse resistance, and operational trust guarantees.

---

## 🔑 Token Issuance & Secret Storage

- The **Account Service** signs JWTs used for internal gRPC authorization.
- Signing keys are stored as **Kubernetes Secrets**, provisioned and rotated using **cert-manager**.
- Keys are **never committed** to the repository and can be rotated without redeploying other services.
- A **JWKS endpoint** exposes public keys for internal services to validate tokens.

### Key and Certificate Rotation

- cert-manager issues both JWT signing keys and mTLS certificates, backed by an internal CA.
- All services poll their mounted secrets for updates and support **hot reload** of keys and certificates.
- During rotation, services cache both current and previous credentials to allow for **seamless transition**.
- JWKS is updated automatically to reflect the current public keyset.

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
- **Zero-trust** principles are **not currently required** or implemented beyond mTLS and JWT-based validation, but may be reconsidered in future hardening efforts.

---

## 🔐 Brute-Force Defense and Abuse Handling

- The **Game Session Service** monitors login attempts **per IP**:
  - Repeated failures result in **connection closure** and **temporary IP blacklisting**.
  - Global login spikes introduce **artificial delay** to slow brute-force attempts.
  - Suspicious login activity triggers **notification emails** to the account holder.

- Abuse detection is planned to expand to include **heuristics** around spam commands, hotspot behaviors, or abnormal tick patterns.
  - These heuristics are **future additions**, intended to detect unusual command frequencies, teleportation loops, or flooding patterns.

---

## 🧾 Audit Logging and Abuse Visibility

- All failed logins, suspicious activity, and abuse attempts are captured in:
  - **Elasticsearch-backed logs**
  - The **Logging & Admin Service dashboard**
- Admin actions (e.g., bans, role changes) are tracked for auditability.

---

## 🔌 Telnet Command Handling and Future Controls

- Telnet clients connect through the **TCP Proxy Service**, which is sandboxed in the DMZ and **never contacts internal services directly**.
- Future plans include:
  - Enforcing a **whitelisted subset** of Telnet commands (legacy compatibility only)
  - Applying **sanitization** to Telnet input to mitigate malformed command injection

These controls are not yet implemented but are expected to strengthen security against legacy protocol edge cases.

---

## 🧰 Admin Interface Access Model

- Admin functionality is **entirely controlled through JWT `roles`**, issued and managed by the **Account Service**.
- There is **no special network-level access or infrastructure isolation** for admin features — this is an intentional design decision to rely solely on internal authentication and scoped authorization.
- Future enhancements may include **2FA** support for admin roles via TOTP or hardware keys, but this is not currently required.

---

## ✅ Summary

| Topic                     | Strategy                                                                 |
|---------------------------|--------------------------------------------------------------------------|
| JWT Secret Storage        | Kubernetes Secrets via cert-manager                                      |
| Key & Cert Rotation       | Hot-reload with caching of old credentials                               |
| TLS Termination           | Load balancer                                                 |
| Internal Encryption       | mTLS via Kubernetes Secrets                                              |
| Trust Enforcement         | JWT + mTLS + Kubernetes NetworkPolicies                                  |
| Brute-Force Defense       | Per-IP tracking, blacklisting, global throttle delays                    |
| Abuse Detection           | Current: login only; Future: command-level heuristics                    |
| Telnet Controls           | Future: whitelist + sanitization                                         |
| Admin Role Access         | JWT-only; no special network-level restrictions                          |
| Zero Trust                | Not currently adopted; mTLS and JWTs provide strong internal identity    |
| 2FA                       | Not implemented; optional future enhancement for elevated roles          |

---

📚 Related:

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Gateway Architecture](./infrastructure/gateway-architecture.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)

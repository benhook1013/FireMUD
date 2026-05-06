# FireMUD System Architecture: Security

This document outlines how FireMUD secures service communication, manages authentication keys, protects network traffic, and tracks abuse attempts. It complements the [Authentication & Authorization](./system-architecture-authentication.md) document by focusing on secret management, TLS usage, abuse resistance, and operational trust guarantees.

Kubernetes Secrets has been selected as the platform's unified secret storage solution. This keeps credential management simple while working seamlessly with cert-manager for automatic rotation of TLS certificates and with Kubernetes Jobs and utilities that handle JWT signing key rotation and other sensitive credentials. FireMUD applies a tiered governance policy on top of this storage choice: high-impact credentials (JWT keys, DB credentials, object-store credentials, operator credentials) require explicit rotation SLAs, age/missed-rotation alerts, incident runbooks, and measurable compliance evidence even when the underlying store remains Kubernetes Secrets.

---

## Token Issuance & Secret Storage

- The **Account Service** signs JWTs for both control-plane browser/API sessions (`/auth/login` profile) and internal service authorization (Service JWT profile).
- Signing keys are stored as **Kubernetes Secrets** and rotated by dedicated Kubernetes Jobs. See [JWT Key & JWKS Rotation Workflow](#jwt-key--jwks-rotation-workflow) for details.
- In player-facing environments, signing keys must be mounted from files and consumed via `FIREMUD_AUTH_JWT_SECRET_PATH`; inline-only JWT secret configuration is restricted to local/dev or explicitly ephemeral stacks.
- Keys are **never committed** to the repository and can be rotated without redeploying other services.
- A **JWKS endpoint** exposes public keys for internal services to validate tokens. The Account Service serves these keys at `/.well-known/jwks.json`. In player-facing environments (`hobby-self-hosted`, staging, production), JWKS is supplied from a mounted Kubernetes Secret (`jwt-jwks`). Non-player-facing environments may use a ConfigMap when keys are explicitly non-sensitive test material.

### Key and Certificate Rotation

- cert-manager issues the mTLS certificates used between services. These TLS certificates are rotated automatically by cert-manager.
- JWT signing keys are stored as Secrets and rotated by dedicated Kubernetes Jobs that update both the signing key Secret and the JWKS document. See [JWT Key & JWKS Rotation Workflow](#jwt-key--jwks-rotation-workflow) and [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) for how services consume these Secrets.
- All services support **hot reload** of mounted TLS materials using the `TlsCertificateWatcher` and `GrpcServerTlsReloader` utilities from the `firemud-common` library. Services that consume JWT signing key material (primarily the Account Service) hot-reload that key file via `JwtSecretWatcher` so signing-key rotation does not require restarts.
- The environment variables `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, and `FIREMUD_GRPC_CA_CERT_PATH` control the TLS file locations that the TLS watchers monitor. Services materialize those files through Spring Boot SSL bundles under `spring.ssl.bundle.pem.*` and bind gRPC server TLS via `spring.grpc.server.ssl.bundle` and `spring.grpc.server.ssl.client-auth`. The Account Service additionally uses `FIREMUD_AUTH_JWT_SECRET_PATH` to point `JwtSecretWatcher` at the mounted signing-key file.
- During rotation, services reload credentials when files change.
- The JWKS endpoint serves a key file that is mounted into the Account Service pod (from a `jwt-jwks` Secret in player-facing environments). The same JWT rotation Jobs that update signing key Secrets also regenerate this JWKS document so validators always see the current public keys.

### JWT Key & JWKS Rotation Workflow

JWT signing keys and JWKS metadata are rotated inside the Kubernetes cluster using dedicated Jobs. The goals are:

- Keep signing keys in Kubernetes Secrets, never in the repository.
- Allow safe, repeatable rotation with a short overlap period for old tokens.
- Let the Account Service hot-reload signing keys via `JwtSecretWatcher` without code changes.

Data model:

- A `jwt-signing-keys` Secret (per environment) stores private keys:
  - `current.key` – PEM (or JKS/JSON) material for the active signing key.
  - `previous.key` – PEM material for the previous key, kept for overlap.
  - Optional `metadata.json` – timestamps, key IDs, and rotation history.
- A `jwt-jwks` resource stores:
  - `jwks.json` – the JWKS document with public keys for both `current` and `previous`, each with a stable `kid`.

The Account Service mounts both resources:

- `jwt-signing-keys` is mounted as a file referenced by `FIREMUD_AUTH_JWT_SECRET_PATH`. `JwtSecretWatcher` reads this file and hot-reloads signing keys when it changes.
- `jwt-jwks` is mounted as `jwks.json`; the Account Service serves `/.well-known/jwks.json` directly from this file. Other services continue to validate JWTs by calling the JWKS endpoint.
  - In player-facing environments, `jwt-jwks` is a Secret.
  - In non-player-facing environments, `jwt-jwks` may be a ConfigMap when test-only key material is acceptable.

Rotation is handled by a Kubernetes `CronJob` template (for example `jwt-rotation`) and a dedicated service account (for example `sa-jwt-rotation`) with narrow RBAC (`get` / `update` on `jwt-signing-keys` and `jwt-jwks`, and optional `patch` on the Account Service Deployment for rollout annotations). Each rotation Job:

1. Reads `jwt-signing-keys` and parses `current.key`, `previous.key`, and any `metadata.json`.
2. Generates a new signing keypair.
3. Moves the existing `current.key` to `previous.key` (if present) and writes the new private key to `current.key`.
4. Derives public keys for both `current` and `previous` and regenerates `jwks.json` with both keys and distinct `kid` values.
5. Updates the `jwt-signing-keys` Secret and `jwt-jwks` resource with the new data.
6. Optionally patches the Account Service Deployment with a `jwt-rotation/<timestamp>` annotation to trigger a rollout; in normal operation `JwtSecretWatcher` reloads keys without needing restarts.

Environment behavior:

- Production: the `jwt-rotation` CronJob is defined with `spec.suspend: true`. Rotation is triggered by creating a one-off Job from this template as part of an explicit operator runbook.
- Staging: the same CronJob template may be enabled on a low-frequency schedule (for example monthly) to exercise the rotation path; leaving it operator-triggered is also acceptable when staging is being kept closer to production change control than to rotation-path testing.
- Development: rotation may be disabled or configured to run frequently with throwaway keys, depending on how closely the environment mirrors production.

Operations requirements for this workflow:

- Define and track a maximum allowed age for JWT signing keys per environment class.
- Alert when the maximum age is exceeded or when scheduled/expected rotation evidence is missing.
- Record rotation evidence (timestamp, triggering operator/automation, resulting key IDs) in incident/change history.
- Treat missing evidence as a compliance failure for player-facing environments (`hobby-self-hosted`, staging, production) until remediated.

Rotation keeps `previous.key` in JWKS for a configurable overlap window so existing tokens continue to validate. A follow-up pruning step (either part of the same Job or a separate Job) drops keys whose timestamps fall outside this window. Metrics and logs record the last successful rotation time and any failures so operators can monitor the process.

Restore-hardening exception:

- When rotating keys during post-restore hardening for a player-facing environment, use restore-mode cutover semantics instead of overlap semantics.
- Restore mode must publish only uncompromised keys in JWKS, advance revocation watermarks, and require validator-convergence evidence before traffic reopen.
- This avoids re-trusting snapshot-era keys resurrected by restore.

Post-restore certificate policy:

- A restore of a player-facing environment is treated as a **trust-boundary reset** for leaf identities.
- Post-restore hardening must reissue:
  - workload mTLS certificates used for service-to-service gRPC,
  - TCP Proxy → Gateway WebSocket mTLS client/server certificates,
  - operator client certificates used for internal control-plane access.
- The default restore flow does **not** rotate the cluster CA or cert-manager issuer root automatically; CA rotation is a separate incident-response path reserved for suspected CA compromise or trust-root loss.
- Traffic must not reopen until validators and peers have converged on the reissued leaf identities.

### JWT Key Compromise Response

When a JWT signing key is suspected to be compromised, operators follow a more aggressive rotation and cleanup flow than the normal `jwt-rotation` run:

- Immediately run compromise-mode key rotation to generate a new signing keypair and update `jwt-signing-keys`.
- Regenerate `jwt-jwks` with **only uncompromised keys**. Do not retain compromised keys in overlap slots (`previous.key`) during compromise response.
- Invalidate active sessions for affected scope by advancing revocation watermarks (`session:auth:revoked_after:*`) via Account Service authority, then perform bounded indexed cleanup as background hygiene so reconnects require a fresh `LOGIN`.
- Restart or force reload JWT validators where needed, then verify validator cache convergence by checking that no service is accepting tokens signed by the compromised `kid`.
- Optionally tighten `FIREMUD_AUTH_JWT_EXPIRATION_MS` for a short containment window after cutover to reduce exposure from any residual stale clients.
- Monitor authentication and authorization metrics/logs (failed validations, unexpected 401/403 patterns) to confirm new-key adoption and incident stabilization.

Normal rotations use the overlap-preserving `jwt-rotation` path without session invalidation. Compromise rotations must use hard cutover semantics and be tracked in incident records, including affected tenants, replaced key IDs, invalidation scope, and validation-convergence evidence.
Compromise-mode rotation is a mandatory runbook-driven process and must include explicit evidence (rotated key IDs, invalidation scope, and validator-convergence checks) before reopening player-facing traffic.
Compromise response must not rely on wildcard key scans/deletes in hot paths; revocation correctness comes from watermark checks, with cleanup performed by bounded background workflows.

### SessionAttestation Key Lifecycle

Gameplay `SessionAttestation` is now also the canonical internal carry-through for the admitted gameplay bundle, not only for raw session identity. When Game Session delegates gameplay-owned gRPC work, the attestation can preserve:

- `tenantId`
- `sessionId`
- `accountId`
- `characterId`
- resolved `gameInstanceId`
- optional `roomInstanceId`
- optional admitted `worldSlug`
- optional admitted `realmSlug`
- optional `pointerVersion`
- optional resolved `playableStateScope`

Downstream gameplay services should validate any of those dimensions they depend on instead of reintroducing narrower local trust shortcuts from standalone request fields.

Gameplay `SessionAttestation` signing keys are managed independently from JWT signing keys:

- **Issuer and publication**
  - Game Session signs attestations and publishes verification keys as a versioned key set containing explicit `kid` values.
  - Verification keys are exposed through one authoritative discovery interface (JWKS-like endpoint or gRPC equivalent) owned by Game Session control-plane.
  - Gameplay services cache this key set with a bounded max-age and must fail closed if an attestation references an unknown `kid`.
- **Rotation**
  - Rotation keeps old and new keys overlapped for at least `2 x max_attestation_ttl` so in-flight attestations remain verifiable.
  - After overlap, retired keys are removed from the published set.
- **Replay-defense storage**
  - Replay guards for attestation `jti`/nonce values use a bounded shared store (Coordination Redis or equivalent) with TTL set to `expiresAt + bounded_skew`.
  - Replay storage must declare capacity quotas per trust domain and deterministic eviction policy (`oldest-expiry-first` or equivalent).
  - Services emit overload metrics when replay-store capacity limits are reached.
- **Compromise response**
  - On suspected compromise, rotate attestation keys immediately, remove compromised keys from the published set, and force revalidation on downstream gameplay services.
  - Incident records must capture rotated `kid` values, invalidation scope, and post-rotation convergence checks.
  - Consumers that encounter unknown `kid` must perform one forced key refresh and a single validation retry before failing closed.

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
  - Telnet client → TCP Proxy Service over raw TCP by default (`TCP_PROXY_PORT`), or over TLS on a dedicated port when `TCP_PROXY_TLS_ENABLED=true` and `TCP_PROXY_TLS_CERT` / `TCP_PROXY_TLS_KEY` (and `TCP_PROXY_TLS_PORT`) are provided. Raw Telnet remains supported even in production so that classic clients which do not understand TLS can connect, but operators should treat this as an intentionally legacy, plaintext channel and apply appropriate hardening (for example strong credential policies, careful IP throttling, and network-level filtering) rather than assuming it provides the same confidentiality guarantees as the HTTPS/WebSocket path. The preferred production deployment pattern is to terminate public Telnet on a dedicated Telnet edge proxy (for example HAProxy) that forwards to the TCP Proxy Service using PROXY protocol on an internal-only listener, as described in the TCP Proxy design’s PROXY protocol section; exposing the TCP Proxy’s raw Telnet port directly to the Internet should be reserved for tightly controlled dev/test setups. On the PROXY-protocol listener, malformed or truncated PROXY headers are treated as a hard failure by the proxy: the connection is closed, `tcpproxy.telnet.discarded{reason="proxy_protocol"}` is incremented, and the proxy never silently falls back to using the TCP peer IP.
  - TCP Proxy Service → Spring Cloud Gateway uses `wss://` with mutual TLS by connecting to a dedicated internal-only Gateway WebSocket mTLS listener (for example a `spring-cloud-gateway-mtls` `ClusterIP` Service on a separate TLS port). The proxy presents a client certificate and key from `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH` / `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`, and validates the gateway certificate against `FIREMUD_GATEWAY_WS_CA_CERT_PATH` with hostname verification enabled using the host from `GATEWAY_WS_URL`. Spring Cloud Gateway promotes `X-Proxy-*` headers only after authenticating this hop via the client certificate identity; see [Gateway Architecture](./system-architecture-gateway.md#tcp-proxy--gateway-authentication). The full Proxy → Gateway WebSocket TLS configuration is summarized in the **TLS Config Matrix** section below.
  - Spring Cloud Gateway forwards gameplay to the Game Session Service over the `/ws/game/**` WebSocket route. Game Session Service then communicates with other microservices over mTLS gRPC using the same `FIREMUD_GRPC_*` variables that all services share.
  - For a compact view of all TLS and trust surfaces specific to the TCP Proxy (Telnet plaintext/TLS, WebSocket mTLS to Gateway, and internal gRPC mTLS), see the TCP Proxy Service design’s **TLS & Trust Surfaces (Summary)** section in `design/architecture/microservices/tcp-proxy-service/README.md`.

Local Docker Compose environments may use plain `http://` / `ws://` for simplicity, but the production Kubernetes profile is expected to follow this termination chain so that only the Internet edge terminates TLS and all intra-cluster hops to and from the gateway are either mTLS (gRPC) or `wss://` with mTLS for the Telnet bridge.

Implementation note:

- The target-state rule for non-local environments remains unchanged: internal service-to-service gRPC is mTLS using Spring Boot SSL bundles and Spring gRPC server SSL bundle binding.
- Any preview-specific transport exception must be documented in preview operator docs and removed once the preview rollout proves the bundle-based configuration.

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
- Hosted preview may temporarily relax internal gRPC to plaintext while the SSL-bundle migration and preview re-proof are in flight. That exception is explicitly preview-only and must be documented in preview design docs; the canonical target state remains mTLS for non-local environments.
- Spring Cloud Gateway is the canonicalization point for gateway-owned identity headers:
  - It strips inbound `X-Client-IP`, `X-Tenant-Id`, and `X-Game-Instance-Id` from public ingress and rewrites them to canonical values before forwarding to backend services.
  - For HTTP and WebSocket clients, it derives the canonical `X-Client-IP` from load-balancer forwarded headers (`Forwarded`, `X-Forwarded-For`, `X-Real-IP`) only when the immediate peer address is a configured trusted proxy CIDR. Otherwise it falls back to the direct TCP peer address. The trusted-proxy CIDRs are configured on the gateway via `firemud.gateway.header-trust.forwarded-client-ip.trusted-proxy-cidrs`.
  - For Telnet traffic, it promotes `X-Proxy-*` inputs into canonical headers only on the authenticated TCP Proxy → Gateway hop as described in [Gateway Architecture](./system-architecture-gateway.md#header-trust-model). In the target-state production model, the gateway identifies the TCP Proxy Service by allowlisting its mTLS peer certificate URI SAN (SPIFFE-style identity); DNS SAN allowlists are transitional only and fingerprint pinning is break-glass.

---

## Brute-Force Defense and Abuse Handling

Abuse defense follows a layered ownership model:

- **Edge transport controls (Spring Cloud Gateway and TCP Proxy Service)**:
  - Per-IP/per-connection request and handshake throttling.
  - Connection caps, idle timeouts, and protocol-level safety guards.
  - No credential-level account lockout decisions.
- **Credential/login abuse controls (Account Service)**:
  - Monitors failed login attempts per account and per source IP.
  - Applies account lockouts/throttles and emits canonical auth errors (for example `AUTH_ACCOUNT_LOCKED`).
  - Owns suspicious-login notifications and account-security policy enforcement.
- **Post-auth gameplay abuse controls (Game Session Service)**:
  - Enforces per-session and per-tenant gameplay command budgets after authentication.
  - Applies gameplay-side abuse heuristics (spam commands, hotspot behavior, abnormal tick patterns).

TCP Proxy Service and Spring Cloud Gateway forward canonical client identity headers so edge and account-service controls can apply consistently across Telnet and WebSocket paths.

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
  - Include the transport context in internal `Authenticate` calls to the Account Service so deployment-wide rules such as `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` and per-account “allow plaintext Telnet login” flags can be enforced consistently. Gameplay authentication must not use `/auth/login`, which remains a browser/control-plane endpoint.
- Client IP headers on Telnet-derived traffic follow the trust model described in [Protocol Bridging](./system-architecture-protocol-bridging.md#bridging-to-the-backend): the TCP Proxy Service supplies `X-Proxy-Client-IP` on its internal WebSocket hop and Spring Cloud Gateway sets the canonical `X-Client-IP` header after stripping spoofable headers from public ingress and authenticating the TCP Proxy identity. In production, the preferred deployment places a Telnet edge proxy (HAProxy) in front of the TCP Proxy Service and enables PROXY protocol so the TCP Proxy can recover the true client IP even when Kubernetes would otherwise SNAT the TCP peer address. When PROXY protocol is not enabled (or source IP is not preserved), per-IP limits and throttles should be treated as best-effort.

### Plaintext Telnet safety matrix (design-time expectations)

`FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` and the per-account “allow plaintext Telnet login” flag combine to gate which accounts may authenticate over raw Telnet. The intended matrix is:

| Env toggle `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` | Per-account “allow plaintext Telnet login” | 2FA on account? | Plaintext Telnet login allowed? | Intended use |
| --- | --- | --- | --- | --- |
| `true` (default) | `false` | either | ❌ | Safe default; plaintext logins are disabled for this account. |
| `true` (default) | `true` | `false` | ❌ | Misconfigured account; UI should prevent enabling this combination. |
| `true` (default) | `true` | `true` | ✅ | Only this combination is permitted for plaintext Telnet in player-facing environments. |
| `false` (override) | `false` | either | ❌ | Telnet plaintext remains disabled for this account even if the env guard is relaxed. |
| `false` (override) | `true` | `true` | ✅ | Permitted but less strict; acceptable only in tightly controlled or non-production environments. |
| `false` (override) | `true` | `false` | ❌ (design intent) | Implementations should continue to reject this combination to avoid silently weakening the 2FA requirement. |

In other words:

- The **per-account flag is always required** for plaintext Telnet, regardless of the environment toggle.
- The **environment toggle controls whether 2FA is mandatory** for plaintext Telnet (`true` = required; `false` = may be relaxed only in non-prod, but the recommended implementation still enforces 2FA where possible).
- Production deployments should keep `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP=true` and rely on the per-account flag plus 2FA to gate plaintext Telnet, treating any other combination as misconfiguration.

Putting this together:

- Local dev and single-operator hobby environments may temporarily relax `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` while flows are being built, but should still require the per-account “allow plaintext Telnet login” flag for any plaintext use.
- Player-facing environments should treat plaintext Telnet as a hardened legacy channel: keep `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP=true`, require both the per-account flag and 2FA for plaintext logins, and prefer Telnet-over-TLS or the web client for normal play.
- Recommended Telnet deployment modes by environment (including the Telnet edge proxy and PROXY-protocol expectations) are summarized in [Protocol Bridging](./system-architecture-protocol-bridging.md#recommended-telnet-deployment-modes); this section remains the canonical source for the safety invariants that all implementations must enforce.

---

## Admin Interface Access Model

- Product admin functionality (creator/moderator APIs exposed via Spring Cloud Gateway) is **entirely controlled through JWT `roles`**, issued and managed by the **Account Service**.
- There is **no special network-level access or infrastructure isolation** for product admin APIs — this is an intentional design decision to rely on scoped authorization in the owning services rather than IP allowlists or private network access.
- Operator control-plane endpoints (for example Spring Cloud Gateway dynamic route management and diagnostics) are treated separately from product admin APIs: they are **internal-only** and require mutual TLS (mTLS) client certificates, with reachability restricted by `ClusterIP` Services, private ingress, and `NetworkPolicy` allowlists.
  - **Certificate trust root**: operator clients must present a certificate that chains to the cluster CA used for gRPC mTLS, issued by cert-manager (ClusterIssuer `firemud-ca-issuer`) and configured via `FIREMUD_GRPC_CA_CERT_PATH`.
  - **Client certificate profile**: operator certificates must include the `clientAuth` extended key usage and should be provisioned as a dedicated Kubernetes Secret that is not mounted by normal workloads.
  - **Distribution and access**: Kubernetes RBAC and Secret scoping must restrict which service accounts can read/mount the operator client certificate Secret; NetworkPolicies restrict which pods can reach the management endpoints so that holding a valid certificate alone is not sufficient outside the approved operator surface.
- Admin and moderator accounts can enable **two-factor authentication** using TOTP codes. When enabled, login requests must supply an `otp` field to the Account Service. See [Account Service – Two-Factor Authentication](./microservices/account-service/README.md#two-factor-authentication) for implementation details.

### Operator Client Certificate Lifecycle

Operator control-plane access relies on mTLS client certificates. To keep this access auditable and revocable:

- **Issuance**: operator client certificates are issued by cert-manager using a dedicated issuer and a profile that includes `clientAuth` EKU. Operator certificates are not reused as workload identities.
- **Storage**: the operator client certificate and private key live in a dedicated Kubernetes Secret that is readable/mountable only by the minimal set of operator-facing tools (or job/service accounts) that require it.
- **Rotation**: operator certificates are rotated on a fixed cadence (or immediately after personnel/device changes). Rotation updates the operator Secret and distributes the new credential to approved operator surfaces; the previous credential is revoked or removed from allowed trust paths.
- **Revocation/incident response**: if an operator credential is suspected compromised, rotate immediately and tighten NetworkPolicy allowlists so that possessing a valid certificate is not sufficient without approved network placement.

See `design/architecture/system-architecture-operator-credentials-runbook.md` for the concrete operator workflow.

---

## Summary

| Topic | Strategy |
| --- | --- |
| JWT Secret Storage | Kubernetes Secrets with hot reload via `JwtSecretWatcher`; rotation handled by dedicated Kubernetes Jobs that also refresh JWKS |
| Key & Cert Rotation | TLS certificates auto-rotated by cert-manager with hot reload via `TlsCertificateWatcher`; JWT keys rotated via Jobs with JWKS updates and credential caching |
| TLS Termination | Load balancer |
| Internal Encryption | mTLS via Kubernetes Secrets; server certificate hot reload enabled |
| Trust Enforcement | JWT + mTLS + Kubernetes NetworkPolicies |
| Brute-Force Defense | Layered model: Gateway/TCP Proxy enforce edge transport throttles; Account Service enforces credential/login abuse lockouts; Game Session enforces post-auth gameplay command abuse limits |
| Abuse Detection | Login tracking and command-level heuristics enforce usage patterns |
| Telnet Controls | TCP Proxy Service applies Telnet protocol command whitelisting, sanitization, idle timeouts, and per-connection buffer depth limits; rate-limit policy lives in Gateway and Game Session Service. Plaintext Telnet logins are further constrained by `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` and per-account “allow plaintext Telnet login” flags. |
| Admin Role Access | Product admin APIs are JWT-only with no special network-level restrictions; operator control-plane endpoints are internal-only and require mTLS client certificates |
| Zero Trust | Enforced via mTLS and JWT-based validation |
| 2FA | Available for admin and moderator accounts via TOTP codes and, when `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is enabled, required for any account that logs in over plaintext Telnet |

CI/CD trust boundaries:

- CI workflows use environment-scoped credentials for Kubernetes clusters and registries. Development credentials are available to broader workflows; staging and production credentials are limited to deployment paths and protected branches/tags.
- Any future GitHub Actions workflows that apply staging or production manifests must use per-environment credentials, GitHub Environments, and manual approvals so CI cannot modify production clusters without explicit operator consent.

---

## Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)

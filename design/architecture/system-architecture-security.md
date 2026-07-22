# FireMUD System Architecture: Security

This document outlines how FireMUD secures service communication, manages authentication keys, protects network traffic, and tracks abuse attempts. It complements the [Authentication & Authorization](./system-architecture-authentication.md) document by focusing on secret management, TLS usage, abuse resistance, and operational trust guarantees.

Kubernetes Secrets is the baseline store for exportable platform credentials and the controlled interim fallback for Account JWT private material. Target-state JWT custody delegates private-key operations to a non-exportable signer in every environment while Account Service remains the sole issuer and lifecycle authority. Cert-manager continues to rotate TLS certificates, and Kubernetes Jobs may observe Account-owned public JWKS transitions and record evidence. Rotation automation does not receive private material or write the public `jwt-jwks` resource. FireMUD applies tiered governance to high-impact credentials (JWT keys, DB credentials, object-store credentials, operator credentials), including explicit rotation SLAs, age/missed-rotation alerts, incident runbooks, and measurable compliance evidence.

## Implementation Notes

- Account Service currently publishes `/.well-known/jwks.json` by reading the configured mounted JWKS file on each request, but the runtime still permits a classpath resource fallback when that file is absent. Target state restricts that fallback to explicit local/test profiles; player-facing startup must fail closed when the configured JWKS path or file is missing or unreadable, the JWKS is malformed, or its public JWK does not match the Account signing key and `kid`.
- Common Security has a live reusable `ReloadableJwtUtil` and `JwtSecretWatcher` path for `FIREMUD_AUTH_JWT_SECRET_PATH`, but the current implementation replaces one shared HMAC secret immediately. It does not implement asymmetric `kid`/JWKS validation, overlap, or Account-only signing authority, and the current Kubernetes baseline distributes signing material beyond Account Service.
- The target Account-only asymmetric/JWKS boundary, phased rotation workflow, dedicated rotation-job automation, key-overlap/pruning operations, projected-volume reload proof, deployment-wide validator convergence, and player-facing readiness gate below remain target/operational design rather than completed live capability. Current deployment preflight also validates signing paths and mounts across primary workloads, so the preflight/shared-signing topology is implementation drift rather than enforcement of this target. This document does not change runtime, preflight, or manifest behavior.

---

## Token Issuance & Secret Storage

- The **Account Service** signs JWTs for both control-plane browser/API sessions (`/auth/login` profile) and internal service authorization (Service JWT profile).
- The Account JWT key ring is asymmetric and per environment. Only Account Service may access its private signing keys; validators use public JWKS and must never receive a private Account JWT key.
- Target-state signing keys remain in **non-exportable signer custody** under the phased protocol in [ADR 0014](./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md). Account Service owns key-generation requests, validation, promotion, JWKS publication, and public/private pruning; the signer performs only private-key operations Account delegates. Until signer delegation is implemented, `jwt-signing-keys` is an Account-only Kubernetes Secret fallback that rotation automation may neither read nor update.
- In player-facing environments, inline-only JWT secret configuration and HMAC-only signing or verification are forbidden. `FIREMUD_AUTH_JWT_SECRET_PATH` is the controlled Account-only file-mount fallback, not the target non-exportable signer interface.
- Keys are **never committed** to the repository and can be rotated without redeploying other services.
- A **JWKS endpoint** exposes public keys for internal services to validate tokens. The Account Service serves these keys at `/.well-known/jwks.json`. In player-facing environments (`hobby-self-hosted`, staging, production), `jwt-jwks` is mounted read-only at `/var/run/secrets/firemud/jwks`, and `FIREMUD_AUTH_JWKS_PATH` points to `/var/run/secrets/firemud/jwks/jwks.json`. Non-player-facing environments may use a ConfigMap or classpath resource when keys are explicitly non-sensitive test material; those fallbacks are not permitted for player-facing traffic.

### Key and Certificate Rotation

- cert-manager issues the mTLS certificates used between services. These TLS certificates are rotated automatically by cert-manager.
- Account Service remains the sole JWKS publication and pruning authority. Dedicated Kubernetes Jobs request Account-owned transitions through the single Account JWT rotation control/status interface, observe Account publication and validator convergence, and record evidence; the rotation Job/CronJob must never read or update `jwt-signing-keys` or write `jwt-jwks`. See [JWT Key & JWKS Rotation Workflow](#jwt-key--jwks-rotation-workflow) and [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) for how Account and validators consume these resources.
- All services support **hot reload** of mounted TLS materials using the `TlsCertificateWatcher` and `GrpcServerTlsReloader` utilities from the `firemud-common` library. Account Service is the only application workload that consumes Account JWT signing material; it may use `JwtSecretWatcher` to detect a new bundle, but it promotes that signer only through the validated phased protocol.
- The environment variables `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, and `FIREMUD_GRPC_CA_CERT_PATH` control the TLS file locations that the TLS watchers monitor. Services materialize those files through Spring Boot SSL bundles under `spring.ssl.bundle.pem.*` and bind gRPC server TLS via `spring.grpc.server.ssl.bundle` and `spring.grpc.server.ssl.client-auth`. The Account Service additionally uses `FIREMUD_AUTH_JWT_SECRET_PATH` for its mounted signing-key file and `FIREMUD_AUTH_JWKS_PATH` for the mounted `jwt-jwks/jwks.json` file.
- During the interim fallback, Account Service may reload a validated signing generation when its files change. Filesystem watching is an implementation option, not the rotation contract; malformed or mismatched material must leave the old signer active and fail readiness.
- The JWKS endpoint serves Account-published public material. Rotation automation requests Account-owned publication or pruning through the single Account JWT rotation control/status interface, proves validator visibility, and observes the result. Account validates signer/public-key correspondence, promotes the signer, and remains the only authority that mutates public JWKS; the non-exportable signer performs only the delegated signing/private-key operation.

### JWT Key & JWKS Rotation Workflow

JWT rotation is coordinated inside the Kubernetes cluster using one Account-owned JWT rotation control/status interface and dedicated Jobs that invoke it, observe publication/convergence, and record evidence. Account Service owns private-key generation, validation, promotion, JWKS publication, and private/public pruning; a non-exportable signer may perform only the private-key operations Account delegates. [ADR 0014](./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md) defines the accepted rotation and readiness boundary. The goals are:

- Keep target-state signing keys non-exportable and never in the repository; restrict any interim Kubernetes Secret fallback to Account Service.
- Allow planned rotation without invalidating tokens or interrupting callers.
- Remove compromised or restored trust material immediately during a hard cutover.
- Make signer promotion, validator convergence, pruning, and retained evidence explicit.

Controlled interim fallback data model:

- A `jwt-signing-keys` Secret (per environment) stores an Account-only versioned signing bundle only until non-exportable signer delegation is implemented:
  - the private key for the active signing generation and its stable `kid`;
  - a pending key during prepublication, or a retired key retained only as an explicit rollback slot during normal overlap;
  - required generation, phase, timestamps, and key identifiers.
- A `jwt-jwks` resource stores:
  - `jwks.json` – the public keys for every signing generation whose tokens may still be valid, plus the common rotation generation and active/pending identifiers.

Under the interim fallback, Account Service mounts both resources:

- `jwt-signing-keys` is mounted only into Account Service as a file referenced by `FIREMUD_AUTH_JWT_SECRET_PATH`. If `JwtSecretWatcher` is used, Account validates the complete bundle and matching published JWK before atomically replacing its signer.
- `jwt-jwks` is mounted as `jwks.json`; the Account Service serves `/.well-known/jwks.json` directly from this file. Other services continue to validate JWTs by calling the JWKS endpoint.
  - In player-facing environments, `jwt-jwks` is a Secret.
  - In non-player-facing environments, `jwt-jwks` may be a ConfigMap when test-only key material is acceptable.

Rotation is coordinated by a Kubernetes `CronJob` template (for example `jwt-rotation`) and a dedicated service account (for example `sa-jwt-rotation`) with narrow authority to request Account-owned rotation transitions and status, run validator-convergence probes, and write the dedicated `jwt-rotation-status` evidence resource. It has no write access to `jwt-jwks`, no read or update access to `jwt-signing-keys`, and no `patch` authority on the Account Service Deployment; rollout/restart remains under Account or normal deployment control. The single Account JWT rotation control/status interface carries the requested transition, current Account-owned phase/generation/publication status, and convergence-observation correlation; `jwt-rotation-status` carries the automation's immutable evidence record rather than a second rotation state machine. A planned rotation is an ordered state machine rather than two resource updates treated as atomic:

1. Account Service requests generation of a new asymmetric keypair and unique `kid`; the delegated non-exportable signer performs the private-key operation, or Account performs it inside the controlled Secret fallback, without changing the active signer. The private key never enters rotation automation.
2. Rotation automation asks Account to publish the resulting public JWK alongside every still-valid old public key, carrying a common generation and pending-key phase across rotation resources.
3. Wait at least the configured validator JWKS maximum cache age and actively prove that every required validator accepts both the dedicated non-authorizing pending-key canary and a short-lived representative probe for each production JWT family applicable to that validator. The validator inventory is an explicit validator-to-token-profile applicability matrix: the harness submits only the families and audiences that validator is designed to accept and also proves that inapplicable audiences remain rejected. Representative probes use the normal issuer, audience, algorithm, required-claim, key-use, and JWKS validation path, but carry a reserved probe subject with no entitlement, membership, role, or scope and run only through a readiness harness that denies authorization and side effects after authentication. Any expected acceptance or rejection mismatch, inability to validate a pending `kid`, or probe that authorizes an operation fails readiness.
4. Account Service validates correspondence among the delegated signer (or interim private bundle), `kid`, and matching published JWK, then promotes the new Account signer only after both probe families converge across the complete required validator inventory. Signer promotion is the commit point; a malformed bundle, mismatch, incomplete inventory, failed probe, or observed probe authorization leaves the old signer active and fails readiness.
5. Retain the old public JWK until the final token actually signed with it has expired plus allowed validation clock skew. An old private key may be retained only in the Account-owned signing state for explicit rollback during this window.
6. Account-owned rotation prunes the retired public key; automation proves active-key acceptance and retired/expired-key rejection, writes the dedicated status/evidence resource, and never reads or updates `jwt-signing-keys`.

Validators cache JWKS for a configured bounded maximum age and refresh proactively. An unknown `kid` causes one forced refresh and one validation retry, then fails closed. A temporarily unavailable JWKS endpoint does not invalidate a still-fresh cached known key, but validators must not extend cache age or accept an unknown key to preserve availability.

Environment behavior:

- Production: the `jwt-rotation` CronJob is defined with `spec.suspend: true`. Rotation is triggered by creating a one-off Job from this template as part of an explicit operator runbook.
- Staging: the exact production artifact and phased protocol must be exercised periodically. The trigger may be a low-frequency schedule or an explicit operator drill.
- Development: rotation may be disabled or configured to run frequently with throwaway keys, depending on how closely the environment mirrors production.

Operations requirements for this workflow:

- Define and track a maximum allowed age for JWT signing keys per environment class.
- Alert when the maximum age is exceeded or when scheduled/expected rotation evidence is missing.
- Record rotation evidence (timestamp, triggering operator/automation, resulting key IDs) in incident/change history.
- Treat missing evidence as a compliance failure for player-facing environments (`hobby-self-hosted`, staging, production) until remediated.

Normal rotation does not invalidate sessions. The mandatory public-key overlap is derived from actual issuance: retain a retiring key until the last token signed by it has expired plus allowed clock skew. The prepromotion validator-cache wait is a separate convergence condition. Rollback after signer promotion must retain public keys used by both application versions until tokens under both keys satisfy that expiry condition.

Restore-hardening exception:

- When rotating keys during post-restore hardening for a player-facing environment, use restore-mode cutover semantics instead of overlap semantics.
- Restore mode must quarantine JWT issuance and JWT-protected traffic, have Account publish only fresh uncompromised keys in JWKS, invalidate environment-wide issuer authority, and require validator-convergence evidence before traffic reopen.
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

- Quarantine new JWT issuance and JWT-protected admission/control-plane traffic before changing trust material.
- Immediately have Account Service initiate compromise-mode rotation and promotion; the non-exportable signer performs only delegated private-key operations, or Account uses the controlled Secret fallback. Rotation automation must not receive private material or read/update `jwt-signing-keys`.
- Through the single Account JWT rotation control/status interface, request Account to publish **only uncompromised public keys**. Do not retain a compromised key in any overlap or rollback slot during compromise response; automation must observe the Account-published result rather than writing `jwt-jwks`.
- Treat compromise of the environment-wide Account signing key as global for that issuer. Advance the issuer-wide revocation watermark and perform required session/allowlist invalidation so reauthentication is mandatory; tenant-selective cleanup is not sufficient containment.
- Restart or force reload JWT validators where needed, then verify validator cache convergence by checking that no service is accepting tokens signed by the compromised `kid`.
- Optionally tighten `FIREMUD_AUTH_JWT_EXPIRATION_MS` for a short containment window after cutover to reduce exposure from any residual stale clients.
- Monitor authentication and authorization metrics/logs (failed validations, unexpected 401/403 patterns) to confirm new-key adoption and incident stabilization.

Normal rotations use the overlap-preserving `jwt-rotation` path without session invalidation. Compromise rotations must use hard cutover semantics and be tracked in incident records, including the environment-wide issuer scope, replaced key IDs, invalidation scope, and validation-convergence evidence.
Compromise-mode rotation is a mandatory runbook-driven process and must include explicit evidence (rotated key IDs, invalidation scope, and validator-convergence checks) before reopening player-facing traffic.
Compromise response must not rely on wildcard key scans/deletes in hot paths. Watermarks and bounded background cleanup are defense in depth, but they cannot replace removal of the compromised public key and proof that every validator rejects it.

### Player-Facing JWT Readiness

Mounted file paths and a served JWKS document do not establish JWT readiness. Before any player-facing environment is described as promotable or traffic-open, startup and deployment gates must prove Account-only asymmetric signing, no private-key distribution to validators, `kid`/JWKS validation with HMAC fallback disabled, a successful planned-rotation drill through prune, a successful compromise hard-cutover drill, and retained validator-convergence evidence. Until those conditions are implemented and proved, the current shared-HMAC topology is non-production implementation debt rather than an alternate supported design.

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

This section is the authoritative reference for Telnet transport controls and the current preference for Telnet-over-TLS or the web client over plaintext Telnet.

- Telnet clients connect through the **TCP Proxy Service**, which is sandboxed in the DMZ. It forwards **all gameplay traffic** to the backend exclusively via WebSocket through Spring Cloud Gateway and uses a narrow, mTLS-protected gRPC link to the **Game Session Service** only to emit `NotifyDisconnect` lifecycle events (no gameplay payloads). These gRPC endpoints are internal-only and are never published through the gateway.
- The proxy **enforces a whitelisted subset of Telnet protocol commands** and **sanitizes** incoming input to protect against malformed sequences, using a dedicated Telnet pipeline in the TCP Proxy Service (currently implemented by `TelnetServerHandler`).
- Plaintext Telnet sessions should receive a landing-menu security warning that recommends the TLS Telnet port or web client. The current authentication RPC does not carry a transport-security field or enforce a TOTP/per-account plaintext gate; future transport admission hardening must introduce and enforce one complete contract rather than relying on documentation-only configuration.
- Client IP headers on Telnet-derived traffic follow the trust model described in [Protocol Bridging](./system-architecture-protocol-bridging.md#bridging-to-the-backend): the TCP Proxy Service supplies `X-Proxy-Client-IP` on its internal WebSocket hop and Spring Cloud Gateway sets the canonical `X-Client-IP` header after stripping spoofable headers from public ingress and authenticating the TCP Proxy identity. In production, the preferred deployment places a Telnet edge proxy (HAProxy) in front of the TCP Proxy Service and enables PROXY protocol so the TCP Proxy can recover the true client IP even when Kubernetes would otherwise SNAT the TCP peer address. When PROXY protocol is not enabled (or source IP is not preserved), per-IP limits and throttles should be treated as best-effort.

### Plaintext Telnet policy

Plaintext Telnet is a legacy compatibility transport, not a protected account-factor path. Local development may use it for protocol iteration. Player-facing deployments should expose Telnet-over-TLS or the web client and avoid public plaintext ingress until a complete transport-admission contract is implemented and verified end to end.

---

## Admin Interface Access Model

- Product admin functionality (creator/moderator APIs exposed via Spring Cloud Gateway) is **entirely controlled through JWT `roles`**, issued and managed by the **Account Service**.
- There is **no special network-level access or infrastructure isolation** for product admin APIs — this is an intentional design decision to rely on scoped authorization in the owning services rather than IP allowlists or private network access.
- Operator control-plane diagnostics are treated separately from product admin APIs: they are **internal-only** and require mutual TLS (mTLS) client certificates, with reachability restricted by `ClusterIP` Services, private ingress, and `NetworkPolicy` allowlists. Generic Gateway route-mutation components and endpoints are dev/test-only and absent or disabled in player-facing environments; production route changes use the separately accepted declarative deployment workflow, so the production operator surface remains diagnostics-only.
  - **Certificate trust root**: operator clients must present a certificate that chains to the cluster CA used for gRPC mTLS, issued by cert-manager (ClusterIssuer `firemud-ca-issuer`) and configured via `FIREMUD_GRPC_CA_CERT_PATH`.
  - **Client certificate profile**: operator certificates must include the `clientAuth` extended key usage and should be provisioned as a dedicated Kubernetes Secret that is not mounted by normal workloads.
  - **Distribution and access**: Kubernetes RBAC and Secret scoping must restrict which service accounts can read/mount the operator client certificate Secret; NetworkPolicies restrict which pods can reach the management endpoints so that holding a valid certificate alone is not sufficient outside the approved operator surface.

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
| JWT Secret Storage | Account-only asymmetric private keys in Kubernetes Secrets; validators receive only Account-published JWKS; filesystem hot reload is optional and must atomically validate a complete signing generation |
| Key & Cert Rotation | TLS certificates auto-rotated by cert-manager with hot reload via `TlsCertificateWatcher`; planned JWT rotation prepublishes, proves convergence, promotes, overlaps through token expiry, and prunes, while compromise/restore uses quarantined hard cutover |
| TLS Termination | Load balancer |
| Internal Encryption | mTLS via Kubernetes Secrets; server certificate hot reload enabled |
| Trust Enforcement | JWT + mTLS + Kubernetes NetworkPolicies |
| Brute-Force Defense | Layered model: Gateway/TCP Proxy enforce edge transport throttles; Account Service enforces credential/login abuse lockouts; Game Session enforces post-auth gameplay command abuse limits |
| Abuse Detection | Login tracking and command-level heuristics enforce usage patterns |
| Telnet Controls | TCP Proxy Service applies Telnet protocol command whitelisting, sanitization, idle timeouts, and per-connection buffer depth limits; rate-limit policy lives in Gateway and Game Session Service. Player-facing deployments prefer Telnet-over-TLS or the web client over plaintext ingress. |
| Admin Role Access | Product admin APIs are JWT-only with no special network-level restrictions; operator control-plane endpoints are internal-only and require mTLS client certificates |
| Zero Trust | Enforced via mTLS and JWT-based validation |
| Account Factors | Current account modes are password and verified-email login codes; authenticator-app factors are future work |

CI/CD trust boundaries:

- CI workflows use environment-scoped credentials for Kubernetes clusters and registries. Development credentials are available to broader workflows; staging and production credentials are limited to deployment paths and protected branches/tags.
- Any future GitHub Actions workflows that apply staging or production manifests must use per-environment credentials, GitHub Environments, and manual approvals so CI cannot modify production clusters without explicit operator consent.

---

## Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)

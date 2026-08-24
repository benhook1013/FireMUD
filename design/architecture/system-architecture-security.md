# FireMUD System Architecture: Security

This document outlines how FireMUD secures service communication, manages authentication keys, protects network traffic, and tracks abuse attempts. It complements the [Authentication & Authorization](./system-architecture-authentication.md) document by focusing on secret management, TLS usage, abuse resistance, and operational trust guarantees.

**Target state:** scripting uses one DSL/compiler/sandbox security boundary for embedded scripts and linked plugins. Provenance or plugin origin does not grant trust: instance-bound gameplay/runtime script or plugin admission and execution require the exact Game Session `(scriptPatchVersion, scriptPinEpoch)` execution fence. Plugin-backed work additionally requires the exact immutable `(pluginId, pluginVersionId, bindingId)` tuple, fresh instance-scoped activation/lifecycle, component-policy, capability-grant, and signer evidence when runtime activation is instance-bound. Tenant-readiness `onLoad` retains its pre-instance-pin identity, while design-time, tenant-readiness, and plugin-publication/readiness checks use their declared publication/readiness evidence without fabricating a Game Session instance tuple. Missing, stale, unavailable, or mismatched applicable evidence fails closed. The [DSL lifecycle reference](./system-architecture-scripting-dsl-reference-and-lifecycle.md), [plugin fencing contract](./system-architecture-scripting-contracts.md#8-plugin-version-fencing-and-control-plane-scope), and [Game Session version-fence contract](./system-architecture-scripting-contracts.md#3-version-fencing-rollback-safety) own those details; this document owns only security, workload identity, and sandbox consequences.

FireMUD has one application-facing contract for exportable secrets: Kubernetes workloads consume narrowly scoped Kubernetes Secrets through fixed mounted-file or bounded `secretKeyRef` interfaces, while local tooling consumes equivalent read-only files outside the repository. FireMUD does not bundle or require Vault or another external secret-manager service; external infrastructure may populate canonical Kubernetes Secret and ConfigMap names for current legacy wiring and other credentials. The checked-in player-facing Kustomize path uses legacy Secret-backed signing plus a public `jwt-jwks` Secret, while hosted preview Helm uses its separate diagnostic public `jwt-jwks` ConfigMap path; both are implementation state and not accepted player-facing custody modes. The only accepted player-facing custody states are the interim mounted fallback and the target non-exportable signer state. Rotation automation is observation-only, except for the separately bounded interim materialization controller operation authorized by Account. High-impact credentials require explicit rotation SLAs, age/missed-rotation alerts, incident runbooks, and measurable compliance evidence.

## Implementation Status

- Account Service currently publishes `/.well-known/jwks.json` by reading the configured mounted JWKS file on each request, but the runtime still permits a classpath resource fallback when that file is absent. Target state restricts that fallback to explicit local/test profiles; player-facing startup must fail closed when the configured JWKS path or file is missing or unreadable, the JWKS is malformed, or its public JWK does not match the Account signing key and `kid`.
- Common Security has a live reusable `ReloadableJwtUtil` and `JwtSecretWatcher` path for `FIREMUD_AUTH_JWT_SECRET_PATH`, but the current implementation replaces one shared HMAC secret immediately. It does not implement asymmetric `kid`/JWKS validation, overlap, or Account-only signing authority, and the current Kubernetes baseline distributes signing material beyond Account Service.
- The target Account-issued asymmetric/JWKS boundary, phased rotation workflow, dedicated rotation-job automation, key-overlap/pruning operations, projected-volume reload proof, deployment-wide validator convergence, and player-facing readiness gate below remain target/operational design rather than completed live capability. The current player-facing Kustomize runtime reads a legacy shared-HMAC signing Secret and public `jwt-jwks` Secret file while still permitting the documented classpath fallback drift; current deployment preflight requires the explicit Secret binding, resource/path/mount shape, and Account path/mount as an advisory wiring check. Hosted preview Helm remains ConfigMap-backed and diagnostic-only. Neither result proves target publication authority or validator convergence. This document does not change runtime, preflight, or manifest behavior.
- The live `EnqueueAutomationCommandIfAbsentRequest` carries `scriptPatchVersion` but not `scriptPinEpoch`, so the current boundary cannot reject same-version work from an older epoch; this is an implementation gap, not a relaxation of the target fence.

### JWT Custody States

- **Checked-in current deployment paths: legacy Secret-backed signing plus public JWKS diagnostics.** The player-facing Kustomize runtime uses `jwt-signing-keys` and `jwt-jwks` as Secrets, with the shared-HMAC and classpath-fallback drift described above. Hosted preview Helm uses a ConfigMap-backed `jwt-jwks` diagnostic path. The executable's `PREFLIGHT-JWT-001` and advisory `PREFLIGHT-JWKS-001` check the player-facing legacy paths, while `helm-jwks-contract.sh` checks the preview resource and Account mount. These diagnostic results are not custody proof or player-facing readiness evidence.
- **Interim accepted state: `INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK`.** The materialization controller may materialize or generate the private bundle and prune private slots only through its name-scoped RBAC and an Account-authorized operation. Account is the only application workload that consumes the private bundle. The interim public JWKS resource is the Account-owned `jwt-jwks` ConfigMap projection coupled to that mounted bundle and Account's resource-version CAS publication.
- **Target accepted state: non-exportable signer custody.** No application workload mounts or receives private signing material. Account delegates private-key operations to the approved signer and publishes the target public JWKS through its Account-owned `jwt-jwks` ConfigMap and resource-version CAS publication. The target public JWKS publication authority/projection is distinct from the interim projection and from the current externally provisioned diagnostic ConfigMap; the fixed namespace-local resource name does not make those contracts interchangeable.

The interim materialization controller is not a second issuer, signer selector, promoter, JWKS publisher, or independent pruning authority. It is excluded from the general observation-only rotation automation rule only for its bounded Account-authorized private-material operation; it may not choose slots, decide desired key-ring state, or perform an operation without Account authorization. Account remains the sole JWT issuer, lifecycle, promotion, and public-JWKS authority under [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative).

Compatibility-only profiles are explicit exceptions, not player-facing custody states: the packaged classpath JWKS fallback is allowed only in explicit local/test profiles, and shared-HMAC signing or verification is allowed only in explicit local/dev or explicitly ephemeral CI profiles. The checked-in `LEGACY_SECRET_DIAGNOSTIC` Kubernetes/preview behavior remains implementation drift; its resources and diagnostic preflight checks never establish accepted player-facing custody or readiness.

### Browser Cookie Mutation Boundary

Browser-authenticated HTTP state-changing requests that rely on cookies require `Secure`, `HttpOnly`, `SameSite=Strict` cookies, a valid anti-CSRF token in the designated request header, and an `Origin` that exactly matches the configured first-party allowlist. Missing, malformed, mismatched, or unallowlisted `Origin` or CSRF proof fails closed; `SameSite`, `Referer`, user-agent classification, or a caller-supplied header alone is not sufficient. Browser gameplay WebSocket admission cannot carry an arbitrary request header, so its browser-compatible proof is the exact allowlisted `Origin` together with the `SameSite=Strict` HttpOnly one-time connect-token cookie and Gateway's signed-token/replay checks; a separate CSRF header is not required or expected on the WebSocket upgrade. This policy includes `POST /ws/game/connect-token/revoke`, the exact Gateway route for revoking the HttpOnly gameplay connect token, and browser gameplay WebSocket admission. It does not weaken the separate authenticated transport boundaries for non-browser clients or Telnet.

---

## Token Issuance & Secret Storage

- JWT profiles, issuance, registry, generation, signing, and JWKS authority are defined in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). In target non-exportable-signer mode, no application workload consumes private signing material: Account delegates private-key operations to the signer, validators consume the target Account-owned public JWKS, and observation-only rotation automation receives no private material or signer authority. In the interim mounted fallback, only Account consumes the private signing material; validators consume the separate interim Account-owned public JWKS projection. HMAC compatibility profiles do not acquire this private-key custody rule.
- In player-facing environments, inline-only JWT secret configuration and HMAC-only signing or verification are forbidden. `FIREMUD_AUTH_JWT_SECRET_PATH` is the controlled Account-only application file-mount fallback, not the target non-exportable signer interface.
- Keys are **never committed** to the repository and can be rotated without redeploying other services.
- A **JWKS endpoint** exposes public keys for internal services. In the checked-in player-facing Kustomize path, the configured path resolves to the externally provisioned public `jwt-jwks` `Secret`; hosted preview Helm remains on its separate diagnostic public `jwt-jwks` `ConfigMap` path. In the interim mounted fallback, it resolves to the separate interim Account-owned `jwt-jwks` `ConfigMap` projection; in target non-exportable signer custody, it resolves to the separate target Account-owned `jwt-jwks` `ConfigMap` projection. Both accepted projections use `/var/run/secrets/firemud/jwks/jwks.json` selected by `FIREMUD_AUTH_JWKS_PATH`; current resources are diagnostic-only and externally provisioned. JWKS never contains the private key, and classpath fallback is local/test only.

### Secret Delivery Boundary

- FireMUD application code and deployment contracts support one consumption model: fixed mounted-file paths for private key/certificate material and bounded Secret-backed values for credentials that libraries require as configuration. Applications do not call Vault, cloud secret-manager, or provider-specific APIs.
- Secret values must not appear in Git, container images, ConfigMaps, rendered Helm values, logs, traces, or compliance evidence. Versioned evidence records contain only bounded identifiers, ages, digests, and outcomes.
- Staging and production clusters must enable and prove Kubernetes Secret encryption at rest, minimal service-account RBAC, namespace isolation, and Kubernetes API audit logging before they qualify as promotion evidence or player-facing production.
- Each workload receives only the credentials required by its exact identity and function. In the interim mounted JWT fallback, Account is the only application workload that mounts or uses the Account JWT private key; target non-exportable-signer mode has no application private-key mount or distribution. Every mTLS workload has a distinct private identity.
- JWT/JWKS Kubernetes permissions are mode-specific:
  - **Current legacy Secret-backed mode:** the checked-in player-facing Kustomize `jwt-signing-keys` and public `jwt-jwks` are pre-created Secret resources supplied by external environment setup; hosted preview Helm uses a separate ConfigMap-backed `jwt-jwks` diagnostic resource. The current baseline also mounts `jwt-signing-keys` beyond Account; that is implementation drift, not interim custody. Neither current resource has an Account-owned writer contract; current mounts and advisory `PREFLIGHT-JWKS-001` are legacy wiring evidence only and do not imply interim or target permissions.
  - **`INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK`:** the fixed `jwt-signing-keys` `Secret` is materialized or generated by the materialization controller only through name-scoped `get`, `update`, and `patch` authority and an Account-authorized operation; private-slot pruning is subject to the same Account authorization and RBAC. Account has no Kubernetes API authority for that private Secret and is the only application workload that mounts or uses it. The separate interim public `jwt-jwks` `ConfigMap` is written only by Account through name-scoped `get`, `update`, and `patch` resource-version CAS authority. Neither actor has list, create, or delete authority for those pre-created resources.
  - **Target non-exportable signer mode:** no application workload mounts or receives `jwt-signing-keys` or any other private signing material. The fixed public `jwt-jwks` `ConfigMap` is written only by Account through the same name-scoped `get`, `update`, and `patch` resource-version CAS authority; the signer and validators have no JWKS write authority. No other actor has list, create, or delete authority for the pre-created public resource.
  The materialization controller is an additional interim private-material custodian and bounded executor, not an issuer, signer selector, promoter, JWKS publisher, or independent pruning authority. It may materialize or generate private slots only under Account authorization and its name-scoped RBAC, and may execute private-slot pruning only under the same authorization; it may not choose slots or mutate desired key-ring state. It returns only the authenticated CAS/materialization/pruning evidence required by [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#signing-key-rotation-contract-normative). It is excluded from the general observation-only rotation automation rule only for this bounded operation. Rotation Jobs and the rotation-evidence workload have no read/write authority over private signing material and no write authority over either Account-owned public `jwt-jwks` projection.
- Re-creatable leaf certificates and routine service credentials are reissued after loss. Irreplaceable recovery material, such as backup-decryption keys or an intentionally retained offline CA root, must be encrypted and held out of cluster and must not have the live cluster as its only copy.
- Local development may use ignored `.env` values and generated credentials. When Docker mounts real private material, it uses read-only files outside the repository with restrictive host permissions; FireMUD does not start a companion Vault container.
- External secret infrastructure may populate other canonical Kubernetes objects or mounted paths, but it may only perform one-time bootstrap of the pre-created reserved `jwt-signing-keys` Secret and current diagnostic `jwt-jwks` resource (Secret in player-facing Kustomize, ConfigMap in hosted preview Helm) when those resources are part of the checked-in legacy wiring; that legacy bootstrap is drift, never an accepted custody state, and setup must never remain the writer for an accepted mode. It may not populate the interim `jwt-signing-keys` Secret or the Account-owned `jwt-jwks` ConfigMap; those use only their documented Account-authorized materialization-controller and Account CAS contracts. FireMUD neither deploys external secret infrastructure nor makes workload readiness depend on its API. Synchronization should preserve already-materialized healthy workload secrets during an upstream outage.
- The accepted topology remains one cluster per deployment. Independent future regions use independent credential authorities by default. A future active-active multi-datacenter control plane that requires shared signing or secret authority must make a separate decision among KMS/HSM, managed secret service, Vault, or another system; FireMUD does not build cross-cluster secret replication itself.

In the checked-in current deployment mode, environment setup may perform one-time bootstrap of a pre-created reserved legacy Secret only as implementation drift; it must never remain a writer. The accepted interim state uses only the Account-authorized materialization controller for private-slot materialization, generation, and pruning, while Account alone owns the interim public-JWKS publication. Pre-apply bootstrap evidence proves only trusted resource, binding, and RBAC setup; post-apply live signer, public-JWKS, and validator convergence evidence remains owner-produced. A custody-mode transition must fail closed if the documented one-writer boundaries cannot be established.

### Key and Certificate Rotation

- cert-manager issues the mTLS certificates used between services. These TLS certificates are rotated automatically by cert-manager.
- JWT publication, generation, pruning, and validator convergence follow [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). Dedicated rotation Jobs and the rotation-evidence workload are observation-only: they may read public JWKS and bounded validator metadata solely for convergence evidence, but must not read private material or the `jwt-signing-keys` Secret, write either Account-owned public `jwt-jwks` projection, mutate signing state, or select or promote a signer. The interim materialization controller is excluded from that general rule only for its bounded Account-authorized `jwt-signing-keys` Secret materialization/generation/pruning execution role described above; Account alone remains the owner of rotation decisions, reconciliation, advancement, and public-JWKS publication.
- All services support **hot reload** of mounted TLS materials using the `TlsCertificateWatcher` and `GrpcServerTlsReloader` utilities from the `firemud-common` library. In the interim mounted JWT fallback, Account is the only application workload that consumes asymmetric Account JWT signing material; it may use `JwtSecretWatcher` to detect a new bundle, but it promotes that signer only through the validated phased protocol.
- The environment variables `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, and `FIREMUD_GRPC_CA_CERT_PATH` control the TLS file locations that the TLS watchers monitor. Services materialize those files through Spring Boot SSL bundles under `spring.ssl.bundle.pem.*` and bind gRPC server TLS via `spring.grpc.server.ssl.bundle` and `spring.grpc.server.ssl.client-auth`. In the interim mounted JWT fallback, the Account Service additionally uses `FIREMUD_AUTH_JWT_SECRET_PATH` for its mounted signing-key file; Account and validators use `FIREMUD_AUTH_JWKS_PATH` for the mounted `jwt-jwks/jwks.json` file.
- In the interim mounted asymmetric fallback, Account may reload signing material when mounted files change; malformed or mismatched material is rejected and fails closed, quarantining player-facing issuance and protected traffic. Target non-exportable-signer mode has no application signing-file watcher or private-key mount; it proves signer health and Account-owned public-JWKS convergence instead. The current runtime does not yet provide this asymmetric validation or quarantine guarantee. A retained rollback signer is rollback material only and must not serve player traffic until the canonical rotation and trust-convergence proof passes. The file watcher is implementation plumbing, not a second rotation contract.

### JWT Key & JWKS Rotation Workflow

The canonical JWT registry, authority-generation, outage, signing-key, JWKS, and rotation contract is [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). This section retains only Security-owned custody, readiness, and certificate consequences.

JWT rotation follows the owner contract and [ADR 0014](./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md). For asymmetric JWT profiles, Security must preserve non-exportable or Account-only private-key custody, fail closed during trust-boundary uncertainty, and retain operational evidence for player-facing readiness.

The local delivery boundary for the interim asymmetric fallback is one read-only private-key mount for Account through `FIREMUD_AUTH_JWT_SECRET_PATH`; target non-exportable-signer mode has no private-key mount or distribution and uses the Account-owned public JWKS for Account and validators. Neither mode permits private-key distribution to validators or rotation automation. Detailed mount/resource behavior remains in [Environment and Secrets Overview](./infrastructure/environment-and-secrets-overview.md#authentication--jwt).

Rotation jobs and evidence must be observation-only. The interim materialization controller is the sole exception, and only for its bounded Account-authorized `jwt-signing-keys` Secret materialization/generation/pruning execution path described above; it is not rotation authority. Rotation-evidence workloads may read public JWKS and bounded validator metadata for convergence evidence, but must not read private signing Secrets or other private material, mutate signing state, write either public `jwt-jwks` projection, or select or promote a signer. Account alone owns JWT reconciliation and advancement. In target state only, the recovery controller persists the operation/evidence, invokes Account-owned rotation, and observes returned convergence; it cannot select, promote, prune, reconcile, or advance rotation. The exact lifecycle, convergence, overlap, pruning, and readiness evidence remains defined by [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md) and the environment-specific deployment docs.

Validator cache behavior, environment modes, evidence age, planned overlap, and compromise cutover are defined by the JWT owner. Security treats missing or uncertain convergence as a readiness failure and does not provide a local fallback.

Restore-hardening exception:

- When rotating keys during post-restore hardening for a player-facing environment, use restore-mode cutover semantics instead of overlap semantics.
- For `rotated`, `reissued`, or applicable `rebound` dispositions, and for any `compromiseClassified=true` hard cutover, quarantine JWT issuance and JWT-protected traffic, have Account publish only fresh uncompromised keys in JWKS, invalidate environment-wide issuer authority, and require validator-convergence evidence before traffic reopen. A same-boundary PostgreSQL-only rewind with a proved-current, unchanged trust boundary may retain the current JWKS set and issuer generation under the `verified_not_restored` disposition defined by post-restore hardening.
- This avoids re-trusting snapshot-era keys resurrected by restore.

Post-restore certificate policy:

- A restore that replaces or compromises the player-facing trust boundary is treated as a **trust-boundary reset** for leaf identities. A same-boundary PostgreSQL-only rewind may retain current leaf identities only when their resources and issuer binding are proved outside the restored artifact, as defined by post-restore hardening.
- For `rotated`, `reissued`, or applicable `rebound` dispositions, and for any `compromiseClassified=true` hard cutover, post-restore hardening must reissue:
  - workload mTLS certificates used for service-to-service gRPC,
  - TCP Proxy → Gateway WebSocket mTLS client/server certificates,
  - operator client certificates used for internal control-plane access.
- The default restore flow does **not** rotate the cluster CA or cert-manager issuer root automatically; CA rotation is a separate incident-response path reserved for suspected CA compromise or trust-root loss.
- Traffic must not reopen until validators and peers have converged on the applicable unchanged or reissued leaf identities.

### JWT Key Compromise Response

Compromise response is canonical in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). Security's local consequence is to quarantine player-facing issuance/admission until the owner proves replacement-key publication, registry/generation convergence, validator rejection of the compromised key, and required reauthentication. It must not rely on wildcard scans or local key cleanup.

### Player-Facing JWT Readiness

Mounted file paths and a served JWKS document do not establish JWT readiness. In target non-exportable-signer mode, readiness requires signer health, Account-owned public-JWKS convergence, and proof that no private key is mounted or distributed. In the interim mounted fallback, readiness also requires private/public projection proof. The owner defines the signing, registry, generation, rotation, and validator-convergence proof; Security retains the local gate that validators receive no private key, HMAC fallback is disabled in player-facing environments, and uncertain trust convergence keeps traffic closed. Current shared-HMAC and fallback behavior remains implementation debt, not an alternate supported design. Select and report the required checks through the shared [validation and runtime proof workflow](../developer-workflows/validation-and-runtime-proof.md); this document does not create a second validation ledger.

### Gameplay Workload Trust

Routine internal gameplay delegation uses concrete mTLS workload identity, method-level caller allowlists, and the typed unsigned `PlayerExecutionContext` defined by [Authentication & Authorization](./system-architecture-authentication.md#gameplay-player-execution-context-contract-normative). It does not have a separate gameplay-attestation signing key, verification-key publication surface, or replay store.

Every gameplay workload certificate must resolve to one approved service identity. Consumers reject a generic internal-service claim, an unknown certificate identity, a caller not allowlisted for the exact RPC, missing context scope, or context that does not match the request and owning domain data. Mutation replay remains governed by command/effect/request idempotency.

Compromise response revokes or replaces the affected workload certificate, removes its method permissions, and audits calls made under that identity. Account JWT and Gateway connect-context keys retain their separate rotation contracts. Administrative and financial operations use their own control-plane and payment security boundaries rather than introducing gameplay-wide per-action signatures.

---

## TLS Termination & Internal Encryption

- External client `https://` / `wss://` traffic is terminated at the Internet-facing load balancer.
- The **Spring Cloud Gateway** routes client traffic to backend services over in-cluster `http://` / `ws://` targets. Internal service-to-service traffic (for example, Game Session Service to other microservices) uses **mutual TLS (mTLS)** gRPC.
- All internal gRPC calls between microservices use **mutual TLS (mTLS)**:
  - Certificates are issued by **cert-manager**
  - Each workload identity receives a distinct private key and certificate in a dedicated Kubernetes Secret; a shared CA bundle is allowed, but application services do not share one private mTLS identity
  - Trusted using the Kubernetes CA chain

### TLS Termination for Gateway

TLS for player and Telnet flows is applied hop-by-hop so traffic stays protected while keeping the DMZ boundary explicit. Unless otherwise noted, this section describes the **target-state** production configuration; see individual microservice design docs for implementation status details.

- **Browser / Web client path**
  - Browser client → external load balancer over `https://` / `wss://` using a certificate issued by cert-manager (for example, via an Ingress or `LoadBalancer` Service).
  - The external load balancer terminates Internet-facing TLS and forwards plain `http://` / `ws://` traffic to Spring Cloud Gateway pods in the DMZ namespace.
  - Spring Cloud Gateway then routes requests to backend services over in-cluster `http://` / `ws://` endpoints (typically on port `8080`). Internal service-to-service calls (for example, Game Session Service to other microservices) use mTLS-protected gRPC channels configured via `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, and `FIREMUD_GRPC_CA_CERT_PATH` as described in [Environment & Secrets Management](./infrastructure/environment-and-secrets.md#grpc-tls-certificates).
- **Telnet gameplay path**
- Telnet client → public Telnet termination point uses TLS in every player-facing deployment. TLS may terminate at a dedicated Telnet edge proxy (for example HAProxy) or at TCP Proxy Service when `TCP_PROXY_TLS_ENABLED=true` and `TCP_PROXY_TLS_CERT` / `TCP_PROXY_TLS_KEY` are provided. A dedicated edge forwards to TCP Proxy through an authenticated, cryptographically protected internal listener (for example mTLS) using PROXY protocol where client-IP preservation is required. Network restriction and PROXY framing alone do not authenticate the sender; until the edge-to-TCP Proxy channel is authenticated, the recovered address is advisory only and must not drive per-IP throttles, abuse controls, or admission decisions. Raw Telnet remains available only for local development, automated proof, and explicitly private networks; it is not exposed directly to the public Internet or accepted as player-facing production evidence. On the PROXY-protocol listener, malformed or truncated PROXY headers are treated as a hard failure by the proxy: the connection is closed, `tcpproxy.telnet.discarded{reason="proxy_protocol"}` is incremented, and the proxy never silently falls back to using the TCP peer IP.
  - TCP Proxy Service → Spring Cloud Gateway uses `wss://` with mutual TLS by connecting to a dedicated internal-only Gateway WebSocket mTLS listener (for example a `spring-cloud-gateway-mtls` `ClusterIP` Service on a separate TLS port). The proxy presents a client certificate and key from `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH` / `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`, and validates the gateway certificate against `FIREMUD_GATEWAY_WS_CA_CERT_PATH` with hostname verification enabled using the host from `GATEWAY_WS_URL`. Spring Cloud Gateway promotes `X-Proxy-*` headers only after authenticating this hop via the client certificate identity; see [Gateway Architecture](./system-architecture-gateway.md#tcp-proxy--gateway-authentication). The full Proxy → Gateway WebSocket TLS configuration is summarized in the **TLS Config Matrix** section below.
  - Spring Cloud Gateway forwards gameplay to the Game Session Service over the `/ws/game/**` WebSocket route. Game Session Service then communicates with other microservices over mTLS gRPC using the same `FIREMUD_GRPC_*` variables that all services share.
  - For a compact view of all TLS and trust surfaces specific to the TCP Proxy (Telnet plaintext/TLS, WebSocket mTLS to Gateway, and internal gRPC mTLS), see the TCP Proxy Service design’s **TLS & Trust Surfaces (Summary)** section in `design/architecture/microservices/tcp-proxy-service/README.md`.

Local Docker Compose environments may use plain `http://` / `ws://` for simplicity. In production Kubernetes, browser TLS terminates at the external load balancer, while player-facing Telnet TLS terminates at either the dedicated Telnet edge proxy or TCP Proxy Service itself; both are Internet-edge termination points for their respective transports. Subsequent Telnet bridge and service hops follow the authenticated `wss://`/mTLS contracts above.

Implementation note:

- The target-state rule for non-local environments remains unchanged: internal service-to-service gRPC is mTLS using Spring Boot SSL bundles and Spring gRPC server SSL bundle binding.
- Any preview-specific transport exception must be documented in preview operator docs and removed once the preview rollout proves the bundle-based configuration.

---

### TLS Config Matrix: TCP Proxy ↔ Spring Cloud Gateway (WebSocket)

This matrix is the authoritative reference for configuring the Proxy → Gateway WebSocket TLS hop; other docs should link here instead of re-listing the variables.

| Aspect | Env vars / expectation | Dev profile | Prod profile |
| --- | --- | --- | --- |
| WebSocket target URL | `GATEWAY_WS_URL` (for example `ws://spring-cloud-gateway:8080/ws/game` in local Docker, `wss://spring-cloud-gateway-mtls:8443/ws/game` in cluster). The host component is used for SNI and hostname verification in mTLS mode. | May use `ws://` without client certificates; hostname/SAN verification is best-effort and may be relaxed in throwaway dev environments. | Must use `wss://` pointing at the internal mTLS listener; host must match a SAN on the Gateway certificate. No plaintext fallback. |
| Proxy → Gateway WebSocket client cert | `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH` | Optional when `GATEWAY_WS_URL` uses `ws://` for local or throwaway test use. When mTLS is enabled, use a distinct Proxy-to-Gateway client identity rather than reusing the gRPC identity. | Required; must present a dedicated client certificate with `clientAuth` EKU that chains to the cluster CA. Managed independently from the gRPC server identity. |
| Proxy → Gateway WebSocket CA bundle | `FIREMUD_GATEWAY_WS_CA_CERT_PATH` | Optional when using `ws://`. When using `wss://` in dev, should point at the Gateway’s issuing CA or a test CA bundle. | Required; must contain the CA(s) that issue the Gateway’s mTLS listener certificate so the proxy can validate the server cert and SANs. |
| Gateway WebSocket mTLS listener | Gateway Service/Ingress configuration; typically a `spring-cloud-gateway-mtls` `ClusterIP` Service on a dedicated TLS port. | May be omitted; Gateway can expose only the plain WebSocket route for local stacks. | Required; player-facing stacks must expose an internal-only, mTLS-protected WebSocket listener that accepts only TCP Proxy clients. |
| gRPC mTLS (Proxy ↔ Game Session and other services) | `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH` as documented in [Environment & Secrets Management](./infrastructure/environment-and-secrets.md#grpc-tls-certificates). | Plaintext may be used only in explicitly local or throwaway test profiles. When mTLS is enabled, it uses its own workload identity rather than the WebSocket client identity. | Required for all internal gRPC calls. Certificates must use `serverAuth` / `clientAuth` EKUs as appropriate for each service role. |

---

## Cross-Service Trust

- Exact-profile JWT validation uses the Account Service’s JWKS endpoint; backend delegation uses the receiver-specific private player-delegation profile for that route.
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
  - No credential decisions or durable account-state mutations.
- **Credential/login abuse controls (Account Service)**:
  - Applies one policy across password and verified-email-code login over REST, gRPC, Telnet, and WebSocket-derived paths using trusted server-derived source context.
  - Uses graduated source/account-candidate throttles and stable retry outcomes. Ordinary failed attempts never place an account into durable `security_locked`, because that would let an attacker lock a victim by username.
  - Reserves `security_locked` for verified or high-confidence compromise, explicit security policy, or audited operator action, with revocation and recovery. Public failure behavior avoids account enumeration.
  - Fails new credential-bearing authentication closed when shared abuse enforcement is unavailable in a player-facing environment; existing authenticated sessions continue under their normal authority.
- **Post-auth gameplay abuse controls (Game Session Service)**:
  - Enforces ordinary per-session command budgets with a local token bucket on the current session front end rather than a Redis operation per command.
  - May use coarse shared account/tenant/reconnect windows as reset-tolerant defense in depth outside the per-command fast path.
  - Applies gameplay-side abuse heuristics (spam commands, hotspot behavior, abnormal tick patterns).

TCP Proxy Service and Spring Cloud Gateway forward canonical source context so edge and Account controls apply consistently across Telnet and WebSocket paths. Clients cannot supply that trusted context themselves. Per-IP policy is never the only credential signal because shared and carrier-grade NAT can place many legitimate players behind one address. [ADR 0034](./decisions/adr-0034-layered-abuse-controls-without-attacker-triggered-account-locks.md) records the availability, lockout, privacy, and gameplay-fast-path tradeoffs.

---

## Audit Logging and Abuse Visibility

- All failed logins, suspicious activity, and abuse attempts are captured in:
  - **Elasticsearch-backed logs**
  - The **Logging & Admin Service dashboard** ([design](./microservices/logging-admin-service/README.md))
  - Admin actions such as bans are recorded by the Logging & Admin Service for auditability.
  - Role changes are tracked for audit purposes.

---

## Telnet Command Handling and Controls

This section is the authoritative reference for Telnet transport controls and the requirement for Telnet-over-TLS or the web client on public player-facing paths.

- Telnet clients connect through the **TCP Proxy Service**, which is sandboxed in the DMZ. It forwards **all gameplay traffic** to the backend exclusively via WebSocket through Spring Cloud Gateway and uses a narrow, mTLS-protected gRPC link to the **Game Session Service** only to emit `NotifyDisconnect` lifecycle events (no gameplay payloads). These gRPC endpoints are internal-only and are never published through the gateway.
- The proxy **enforces a whitelisted subset of Telnet protocol commands** and **sanitizes** incoming input to protect against malformed sequences, using a dedicated Telnet pipeline in the TCP Proxy Service (currently implemented by `TelnetServerHandler`).
- Permitted local or private-network plaintext Telnet sessions should receive a landing-menu security warning that recommends Telnet-over-TLS or the web client. The authentication RPC does not need a transport-specific TOTP field because plaintext is not a supported public admission path; stronger factors for elevated control-plane actions remain separate from ordinary gameplay login.
- Client IP headers on Telnet-derived traffic follow the trust model described in [Protocol Bridging](./system-architecture-protocol-bridging.md#bridging-to-the-backend): the TCP Proxy Service supplies `X-Proxy-Client-IP` on its internal WebSocket hop and Spring Cloud Gateway sets the canonical `X-Client-IP` header after stripping spoofable headers from public ingress and authenticating the TCP Proxy identity. In production, the preferred deployment places a Telnet edge proxy (HAProxy) in front of the TCP Proxy Service and enables PROXY protocol so the TCP Proxy can recover the true client IP even when Kubernetes would otherwise SNAT the TCP peer address. The recovered address may drive per-IP security controls only when the edge-to-TCP Proxy channel is authenticated and cryptographically protected; otherwise it is advisory and per-IP limits and throttles must use the direct peer address or be treated as best-effort.

### Plaintext Telnet policy

Plaintext Telnet is a local/private compatibility transport, not a protected account-factor path. Local development, automated protocol proof, and explicitly private networks may use it, with the canonical warning when real credentials could be entered. Public player-facing hobby/self-hosted, staging, and production deployments require Telnet-over-TLS or the web client and must not expose raw Telnet ingress. TOTP is not a substitute for channel protection. [ADR 0033](./decisions/adr-0033-public-player-facing-telnet-requires-tls.md) records the accepted tradeoff and the trigger for reconsidering a separate legacy-admission design.

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
| Secret Delivery | One Kubernetes Secret/mounted-file workload contract; no bundled or mandatory Vault; transparent external provisioning allowed except the reserved `jwt-signing-keys` materialization-controller writer and `jwt-jwks` Account-owned CAS writer contracts |
| JWT Signer Custody | Checked-in player-facing Kustomize is legacy Secret-backed signing plus public `jwt-jwks` Secret wiring; hosted preview Helm retains separate ConfigMap-backed diagnostic wiring. Both are non-authorizing drift. Target for asymmetric profiles: Account delegates private-key operations to non-exportable signer custody; interim asymmetric fallback: the materialization controller materializes/generates `jwt-signing-keys` and executes private-slot pruning only under Account authorization and name-scoped RBAC, Account is the only application workload that mounts or uses it, validators receive the separate interim Account-published JWKS projection, and rotation-evidence workloads receive no private material. HMAC compatibility remains explicit local/dev or ephemeral CI only |
| Key & Cert Rotation | TLS certificates auto-rotated by cert-manager with hot reload via `TlsCertificateWatcher`; planned JWT rotation prepublishes, proves convergence, promotes, overlaps through token expiry, and prunes, while compromise/restore uses quarantined hard cutover |
| TLS Termination | Browser `https://`/`wss://` terminates at the Internet-facing load balancer; player-facing Telnet TLS terminates at the dedicated Telnet edge proxy or TCP Proxy Service |
| Internal Encryption | Per-workload mTLS identities delivered via dedicated Kubernetes Secrets; shared CA trust and server certificate hot reload enabled |
| Trust Enforcement | JWT + mTLS + Kubernetes NetworkPolicies |
| Brute-Force Defense | Layered model: Gateway/TCP Proxy enforce edge transport throttles; Account Service enforces graduated credential/login throttles and high-confidence security locks; Game Session enforces local fast-path post-auth gameplay command limits |
| Abuse Detection | Login tracking and command-level heuristics enforce usage patterns |
| Telnet Controls | TCP Proxy Service applies Telnet protocol command whitelisting, sanitization, idle timeouts, and per-connection buffer depth limits; rate-limit policy lives in Gateway and Game Session Service. Player-facing deployments require Telnet-over-TLS or the web client and do not expose public plaintext ingress. |
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

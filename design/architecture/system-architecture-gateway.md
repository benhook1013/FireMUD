# Gateway Architecture

This document describes the role and configuration of **Spring Cloud Gateway** in the FireMUD platform, including routing, filtering, WebSocket support, and how it integrates with both modern and legacy clients.

## Normative Target Contract

Gateway does not validate ordinary REST or admin JWTs; the consuming Account, admin, and other meta services validate those tokens with the shared middleware and Account JWKS. The explicit exception is the short-lived `gameplay-connect` JWT: Gateway validates its signature, issuer, audience, required claims, lifetime, selected-target scope, and single-use replay state during the `/ws/game/**` handshake. That edge validation is transport admission and replay protection, not general account or gameplay authorization; Game Session remains responsible for the signed-context handoff and `LOGIN`/`PLAY` admission checks.

## Implementation Status

Unless otherwise noted, this document describes target-state gateway behavior. Current rollout and implementation boundaries are:

- The target-state declarative `routes.yml` catalog has not yet converged as the implemented route authority. The current authority is the Java `CanonicalGatewayRoutesConfiguration`, with route-specific environment overrides `FIREMUD_GATEWAY_ROUTE_SESSION_URI`, `FIREMUD_GATEWAY_ROUTE_ADMIN_URI`, `FIREMUD_GATEWAY_ROUTE_DESIGN_URI`, `FIREMUD_GATEWAY_ROUTE_ACCOUNT_URI`, and `FIREMUD_GATEWAY_ROUTE_SOCIAL_URI`. These are distinct from the `FIREMUD_SERVICES_*` service-discovery variables used by other microservices. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#service-discovery).
- **Current Game Design route consequence:** the Java route catalog's coarse `/api/design/**` entry forwards `/api/design/assets` through `StripPrefix=2` to Game Design's live `POST /assets` controller. The controller currently checks only privileged JWT/tenant access and has no Account hosted-terms/currentness gate; official-hosted asset-upload readiness is blocked until Gateway denies this route or the exact Account-owned gate is implemented and proved.
- Published `/assets/**` delivery is target-only pending a separate approved public origin/provisioner. The current Gateway route catalog has no `/assets/**` route or asset-store route ID, and private MinIO is not public delivery. The reserved `/assets/**` family remains separate from the first-party static-host `/frontend-assets/**` family.
- The target TCP Proxy → Gateway trust boundary selects exactly one environment-bound trust profile from [ADR 0169](./decisions/adr-0169-exclusive-environment-bound-tcp-proxy-trust.md): certificate-bound profiles use mTLS with exact URI SAN for steady-state player-facing traffic, named expiring DNS migration, or named expiring leaf-fingerprint break-glass; development/test-only `development_cidr` uses TLS without client authentication plus the exact configured source-CIDR predicate. Current hosted values still use plaintext plus pod-CIDR trust, the nominal base mTLS Service does not prove a distinct TLS listener, and configured matchers are not yet mutually exclusive; internal-only network exposure and NetworkPolicies do not make that player-facing compliant.
- The target replay domain is a Gateway-owned Coordination Redis contract defined by the Redis ownership and recovery documents. Gateway carries its non-secret domain pin in `FIREMUD_GATEWAY_REPLAY_DOMAIN`, which must equal exactly `gateway-connect-token-replay-v1` and is rendered as the fixed `{gateway-connect-token-replay-v1}` hash tag. Startup, target preflight, and migration/reset tooling must reject any other value and prove the same tag across Gateway instances and shared Coordination Redis readback. The current `GameplayHandshakeFilter` still writes only a legacy replay marker through the generic Cache-bound client; it has no browser-deny, readiness, or replay-fence writer. This is migration-only drift: before target admission, the named Coordination client/ACL must be cut over, the legacy state must be inventoried and read back under quarantine, the target contract must be verified, and legacy markers must be retained or explicitly cleaned only after their original acceptance windows expire.
- Dynamic REST and gRPC route-management APIs remain local/dev/test-only ephemeral capabilities. The current implementation does not yet enforce the target profile, endpoint isolation, validation, or startup boundary, so these APIs must not be treated as production-safe merely because they exist.
- Connect-token carrier parsing, required routing claims, expiry, and Redis replay checks are implemented for `/ws/game/**`, but current claims and the emitted signed context do not carry and prove durable `realmId`, the current carrier path still does not reject duplicate header values or duplicate token cookies, and it does not strip the cookie carrier after successful cookie authentication. The protected-cookie `/ws/game/**` variant and bootstrap-backed bare `LOGIN` handoff are implemented but non-promotable in player-facing environments until the named Gateway readiness evidence is complete: exact selector-to-`{tenantId, realmId}` resolution and claim/context equality, carrier requirements, replay-marker durability, replay-quarantine continuity, and Account planned-rotation/compromise-cutover evidence. The target contract also requires explicit future-`iat` tolerance, `exp` tolerance, and the 30-second `iat`-to-`exp` hard maximum; the current shared `JwtUtil` parser uses JJWT defaults (no configured clock skew), and `GameplayHandshakeFilter` does not yet explicitly enforce those target `iat`/lifetime bounds. These readiness gaps do not mean that the existing signature, claim, expiry, carrier, or replay checks are absent.
- The target bounded typed Game Session → Gateway lifecycle-intent contract is not implemented or versioned yet. Current Gateway behavior infers lifecycle intent from upstream close/error behavior; the target and its implementation/proof boundary are recorded in [ADR 0131](./decisions/adr-0131-lifecycle-distinct-gameplay-close-taxonomy.md#implementation-status). Gateway remains the sole authority for translating that intent into external WebSocket close semantics.

## Gateway Pattern

**Spring Cloud Gateway** serves as the **single HTTP(S) and WebSocket ingress for external API, admin, and gameplay traffic**. The public site router sends frontend documents and application files to the independently released static frontend host and published game assets to their approved asset origin; traditional Telnet/TCP clients enter via the dedicated TCP Proxy Service as described in [Protocol Bridging](./system-architecture-protocol-bridging.md). Together, the public site router/static frontend host, Spring Cloud Gateway (for API, admin, and gameplay HTTP/WebSocket traffic), the published-asset origin, and the TCP Proxy Service (for Telnet/TCP) form the public edge of the platform. The behaviour of this edge – including ordering guarantees, backpressure, and reconnection semantics for gameplay command streams – is defined canonically in [Protocol Bridging](./system-architecture-protocol-bridging.md); this document focuses on gateway responsibilities and defers to that design for detailed client-path invariants.

- Built as a Spring Boot microservice
- Handles **client** request routing, filtering, CORS, rate limiting, retries, and monitoring
- For admin APIs the Gateway forwards JWTs to backend services without validating them. Player login and session binding are processed by the **Game Session Service**; see [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow) for the detailed flow and the [Tenant Authorization Contract](./system-architecture-authentication.md#tenant-authorization-contract) for how downstream services enforce tenant access.
- Supports both HTTP and WebSocket protocols
- Deployed in both development and production environments
- **Stateless and horizontally scalable** – no cookie-based session affinity is required. The gateway does not own gameplay lease state or shard-mapping state (those are owned by Game Session and stored in Coordination Redis). `/ws/game/**` routes to a stable Game Session service surface; lease ownership and any shard coordination remain internal to the Game Session layer per `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`.
- Auto‑scaling policies handle high concurrency
  - Telnet clients keep a **persistent TCP connection** to the TCP Proxy Service; Spring Cloud Gateway itself does not hold session state between reconnects
  - Spring Cloud Gateway restarts **disconnect WebSocket clients**; browsers and other WebSocket tools must open a fresh WebSocket connection, issue `LOGIN`, and re-bind gameplay scope with `PLAY`. Once reconnected, the gateway resumes routing and the Game Session Service uses Redis-backed state to decide whether to resume or start fresh as described in [Reconnection Strategy](./system-architecture-reconnection.md#resume-vs-reload-scenarios). The gateway does not maintain hidden, long‑lived WebSocket tunnels across its own restarts or attempt to replay in‑flight messages; edge delivery remains per‑connection FIFO and at‑most‑once as defined in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).
  - The Gateway and TCP Proxy Service run in the **network DMZ** and are the only ingress points for their API/admin/gameplay and Telnet/TCP request families. The public site router/static frontend host and published-asset origin are separate ingress points for their respective delivery families. NetworkPolicies restrict direct access to internal services. See [Security Architecture](./system-architecture-security.md#network-security--boundary-design) for details.

> **Important:**
> Spring Cloud Gateway is responsible for routing **only external client requests**.
> **Internal microservice-to-microservice communication does not pass through the Gateway**.
> Microservices use Kubernetes native service discovery and DNS for direct communication.
> Internal synchronous RPCs use **gRPC**; asynchronous contracts (for example audit/saga events and lifecycle signals) use dedicated event flows documented in [System Architecture Overview](./system-architecture-overview.md#asynchronous-and-event-flows).
> See [System Architecture Overview](./system-architecture-overview.md) and [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow) for the complete login and gRPC flow.

- Target-state baseline routes are loaded on startup from the version-controlled declarative `routes.yml` catalog imported through `application.yml` via `spring.config.import`.
- Baseline route targets use in-environment DNS names by default and may be overridden explicitly with environment variables when a deployment needs different upstream targets.
- Dynamic route management is not part of the player-facing target-state route authority. The released declarative catalog remains the target source of truth for route definitions, and production route changes use the separately accepted reviewed declarative deployment workflow or a predeclared bounded failover switch.

### Public Site Routing and First-Party Frontend Boundary

The first-party frontend is an independently released static application artifact served by its own unprivileged static host. Gateway does not serve `index.html`, compiled frontend files, SPA fallback, frontend runtime configuration, or frontend release metadata, and it does not own browser sessions or product-specific orchestration. The frontend host and its public site router are described in [Frontend Architecture](./system-architecture-frontend.md#canonical-first-party-frontend-boundary-front-01).

The public site router keeps these families distinct:

| Request family | Canonical target | Gateway responsibility |
| --- | --- | --- |
| Frontend documents and non-reserved client routes | First-party static frontend host | No API/gameplay authority; Gateway is not a fallback host. |
| `/frontend-assets/**` | First-party static frontend host | Reserved compiled first-party asset family; not a Gateway route and never an SPA fallback. |
| `/auth/**` and `/api/**` | Spring Cloud Gateway and the allowlisted authoritative service | API ingress, coarse route policy, and forwarding; consuming services retain auth/domain authority. |
| `/ws/game/**` | Spring Cloud Gateway and Game Session | Gameplay connect-token admission, WebSocket routing, and edge lifecycle/close translation. |
| `/assets/**` | Approved published asset origin (target-only; object store/CDN or Gateway-backed asset route) | Separate read-only published game-asset family; pending a separate approved public origin/provisioner and never a frontend release or design-time mutation surface. |

The frontend route contract must preserve reserved API, gameplay, compiled-asset, and published-asset paths when applying SPA fallback. The frontend host must not serve `/assets/**`, and the published-asset origin must not serve frontend documents or `/frontend-assets/**`. A static-host release, rollback, cache policy, CSP, runtime configuration, and health contract remain independent of Gateway's API/gameplay release lifecycle. The `/frontend-assets/**` family is intentionally absent from the Java Gateway route catalog and any future Gateway route catalog.

For the Telnet-to-WebSocket bridge – including the `GATEWAY_WS_URL` contract, Proxy → Gateway mutual TLS, and how gameplay traffic is normalized through `/ws/game/**` – treat [Protocol Bridging](./system-architecture-protocol-bridging.md) as the **canonical specification**. This document summarizes the gateway’s routing and configuration responsibilities and defers to the protocol-bridging design for detailed edge semantics.

### Authentication Responsibilities

- Spring Cloud Gateway does not parse or validate ordinary REST/admin JWTs. It only enforces the presence of an `Authorization` header on selected admin routes and forwards those tokens unchanged; the consuming services validate them with the shared middleware and Account JWKS.
- The gameplay-connect JWT is the explicit exception: Gateway validates it during the `/ws/game/**` handshake according to [Tenant-aware Edge Connect Token](#tenant-aware-edge-connect-token-gameplay-handshake), including its required claims, bounded lifetime, selected-target scope, and single-use replay state.
- `/ws/game/**` must not require an `Authorization` header at the HTTP handshake layer. Gameplay authentication and tenant selection are performed inside the Game Session Service via `LOGIN` and `PLAY` after the WebSocket is established (see `system-architecture-authentication.md`).

### Header Trust Model

Spring Cloud Gateway acts as the canonicalizer for client identity and session/tenant headers. It defines a strict trust boundary between **public ingress**, the **Telnet TCP Proxy**, and **internal services**:

- Headers that may carry client, session, or routing identity (`X-Client-IP`, `X-Game-Instance-Id`, `X-Tenant-Id`, `X-World-Slug`, `X-Realm-Slug`, `X-Pointer-Version`, and all `X-Proxy-*` headers) are **never trusted directly from public clients**. The gateway strips or overwrites any such headers that arrive from the external load balancer or browser/WebSocket clients before routing to backend services.
- For HTTP and WebSocket clients, the gateway derives `X-Client-IP` from a small, environment‑specific set of load-balancer headers (for example, `X-Forwarded-For` and `X-Real-IP`) plus the immediate peer address. The exact header list and precedence rules are defined in the [Security Architecture](./system-architecture-security.md#network-security--boundary-design), but the invariant is that downstream services treat only the gateway-produced `X-Client-IP` as authoritative.
- For Telnet traffic, the TCP Proxy Service supplies `X-Proxy-Client-IP`, `X-Proxy-Game-Instance-Id`, `X-Proxy-Tenant-Id`, `X-Proxy-Connection-Id`, and the optional coherent `X-World-Slug` / `X-Realm-Slug` / positive `X-Pointer-Version` routing bundle on the mTLS-authenticated WebSocket hop. After verifying the TCP Proxy client certificate on the internal WebSocket mTLS listener, the gateway treats those values as bridge-only inputs, strips the raw `X-Proxy-*` values before downstream forwarding, and:
  - Derives canonical `X-Client-IP` from `X-Proxy-Client-IP` (and any PROXY-provided metadata) and overwrites any existing `X-Client-IP`.
  - Generates and forwards only canonical `X-Game-Instance-Id` and `X-Tenant-Id` from the corresponding authenticated proxy inputs where appropriate for gameplay routes; raw `X-Proxy-Game-Instance-Id` and `X-Proxy-Tenant-Id` never reach downstream services.
  - Accepts the routing bundle only when all three fields are present and valid or all three are absent, rejects a partial or malformed bundle, and forwards the validated advisory `X-World-Slug`, `X-Realm-Slug`, and `X-Pointer-Version` values to Game Session.
  - Preserves the authenticated TCP Proxy's `X-Proxy-Connection-Id` value unchanged as the **sole mTLS-bound raw-name correlation exception**: a server-owned opaque Telnet-socket identity for disconnect correlation and observability. Gateway does not generate a replacement identifier or treat it as authorization material. The TCP Proxy's `NotifyDisconnect` event carries this same `proxyConnectionId` with its per-connection `disconnectSequence`; a public or untrusted raw value is never forwarded.
- Downstream services must treat `X-Client-IP`, `X-Game-Instance-Id`, `X-Tenant-Id`, `X-World-Slug`, `X-Realm-Slug`, and `X-Pointer-Version` as **gateway-owned or gateway-validated** headers. Services must ignore or overwrite any attempts by callers to spoof these values via gRPC metadata or additional HTTP headers and should rely on their own Redis/session keys and authoritative pointer reads for identity and admission as described in [Authentication & Authorization](./system-architecture-authentication.md) and [Multi-Tenancy](./system-architecture-multi-tenancy.md).
- All JWT validation and authorization logic lives in downstream admin and meta services (such as the Logging & Admin Service and Account Service), which must treat Spring Cloud Gateway as a dumb proxy and may not assume it has performed any authentication checks.

---

## Header Trust Model Details

Spring Cloud Gateway is the **canonicalization point** for any client-identity and session-hint headers. Downstream services (including the Game Session Service) treat these headers as meaningful only because **the gateway produced them after applying trust rules**, not because an upstream client provided them.

### Upstream Inputs (`X-Proxy-*`)

These headers are treated as **untrusted inputs** unless the gateway has authenticated the upstream hop as the TCP Proxy Service:

- `X-Proxy-Client-IP` – the Telnet client IP address as observed by the TCP Proxy Service (ideally recovered via PROXY protocol from the Telnet edge proxy in Kubernetes SNAT scenarios).
- `X-Proxy-Connection-Id` – the TCP Proxy's server-generated stable identifier for the Telnet socket. This is the sole mTLS-bound raw-name correlation exception: Gateway preserves this authenticated value unchanged so it remains the `proxyConnectionId` used with the TCP Proxy's `disconnectSequence` to correlate `NotifyDisconnect` events; it is not a Gateway-generated session, scope, or authorization identifier.
- `X-Proxy-Game-Instance-Id` / `X-Proxy-Tenant-Id` – advisory context captured from server-owned proxy defaults or a future explicitly selected transport adapter. These values are never semantic protocol negotiation or gameplay authority.

### Public Ingress Strip/Drop Rules

For any connection that arrives from the public player/admin ingress, Spring Cloud Gateway **strips** all spoofable client/session headers before routing:

- `X-Client-IP`
- `X-Game-Instance-Id`, `X-Tenant-Id`, `X-World-Slug`, `X-Realm-Slug`, `X-Pointer-Version`
- `X-Proxy-Client-IP`, `X-Proxy-Connection-Id`, `X-Proxy-Game-Instance-Id`, `X-Proxy-Tenant-Id`
- `X-Firemud-Connect-Context`, `X-Firemud-Connection-Mode`, and every other gateway-owned `X-Firemud-*` context/mode or gameplay-admission header; `X-Firemud-Connect-Token` is handled only by the carrier exception below

The current first-party public gameplay carrier is the protected `Firemud-Connect-Token` cookie on `first_party_web`. The dedicated `X-Firemud-Connect-Token` header is a separate target-only carrier for the proven `non_first_party_public` variant on canonical `/ws/game/**`; until that variant is registered and proved, Gateway rejects the header as `CONNECT_TOKEN_REJECTED` with reason `unsupported_carrier_or_route`, strips it, and does not fall back to the cookie or infer a connection mode from its presence.

### TCP Proxy → Gateway Authentication

The TCP Proxy → Gateway hop uses **mutual TLS (mTLS)** for the certificate-bound profiles by connecting to a dedicated **internal-only** Gateway WebSocket mTLS listener (for example a `spring-cloud-gateway-mtls` `ClusterIP` Service on a separate TLS port). For `production_uri`, `migration_dns`, and `breakglass_fingerprint`, Spring Cloud Gateway first requires the presented client certificate to chain to the trust bundle or issuer assigned to this deployment environment and to have client-auth usage. It then applies exactly one predicate selected by the configured trust profile:

- **`production_uri`:** the certificate contains the exact environment-specific URI SAN/SPIFFE identity allowlisted for the TCP Proxy Service.
- **`migration_dns`:** the certificate contains the exact allowlisted DNS SAN.
- **`breakglass_fingerprint`:** the leaf certificate's SHA-256 fingerprint is the one explicitly pinned for the named, expiring incident.
- **`development_cidr`:** local development or isolated automated tests use a TLS (`wss://`) listener without client authentication and only the exact configured source CIDR predicate; this is an explicitly insecure exception, does not claim certificate identity, and never authorizes a plain-transport bridge.

For the three certificate-bound profiles, missing peer-certificate data or a failed chain/client-auth check rejects the handshake; for every profile, a failed selected predicate rejects it, strips/discards the raw `X-Proxy-*` inputs, and does not promote them. The profiles are mutually exclusive: configured identities from another profile are not fallback matchers. Hosted, staging, hobby/self-hosted player-facing, and production profiles must not select `development_cidr`.

Gateway config selects exactly one trust profile; settings from another profile make startup or admission fail closed:

- **`production_uri`:** exact environment-specific URI SAN/SPIFFE identity; required for steady-state player-facing traffic.
- **`migration_dns`:** exact DNS SAN during a named, owned, expiring migration only.
- **`breakglass_fingerprint`:** one leaf SHA-256 fingerprint under a named, expiring incident only.
- **`development_cidr`:** insecure source-CIDR trust for local development and isolated automated tests only; prohibited in hosted, hobby/self-hosted player-facing, staging, and production profiles.

The profiles are alternatives, not an ordered any-of matcher. Public ingress strips all proxy-provided and gateway-owned identity/admission headers before any consumer; certificate-bound profiles rebuild canonical values only after mTLS authentication and their selected identity predicate, while `development_cidr` does so only after TLS and the exact configured source-CIDR predicate. The separately selected non-mTLS local-development/test mechanism is documented below and is not `development_cidr`. This policy is normative per [ADR 0169](./decisions/adr-0169-exclusive-environment-bound-tcp-proxy-trust.md).

Non-mTLS acceptance of `X-Proxy-*` headers is a separately selected local-development/test mechanism, not a `development_cidr` transport and not a player-facing fallback. If enabled, it must use a dedicated non-player-facing listener bound only to loopback or an explicitly configured, exact source-CIDR allowlist for the isolated test network; an absent/empty allowlist, an unlisted source, or a listener reachable through any public/player-facing ingress rejects the request. Startup or admission must fail closed if this mode is combined with any certificate-bound profile, `development_cidr`, a non-local environment profile, or a transport/listener that is not the explicitly selected non-mTLS test listener; it must never be reached as fallback after TLS or certificate validation fails. Hosted, staging, hobby/self-hosted player-facing, and production deployments must prohibit this mode entirely. Network isolation remains defense in depth and never substitutes for authenticated workload identity in a player-facing environment.

### Gateway Output Rules (Downstream-Trusted)

After applying strip/authentication rules, the gateway sets or forwards the downstream-facing headers:

- `X-Client-IP` – canonical client IP address:
  - If the upstream hop is authenticated as the TCP Proxy Service and `X-Proxy-Client-IP` is present, set `X-Client-IP` from `X-Proxy-Client-IP`.
  - Otherwise derive `X-Client-IP` from the trusted load balancer forwarded headers (for example `X-Forwarded-For`) using the gateway’s configured trusted-proxy rules.
- `X-Proxy-Connection-Id` – preserved unchanged only after TCP Proxy authentication as the sole gateway-authenticated, server-owned opaque raw-name correlation field so downstream services can correlate lifecycle signals with the TCP Proxy's `NotifyDisconnect {proxyConnectionId, disconnectSequence}`; the raw inbound header is stripped before this authenticated value is re-added. It is not authentication or session authority.
- `X-Game-Instance-Id` / `X-Tenant-Id` – downstream game/tenant context generated from authenticated TCP Proxy metadata when the corresponding `X-Proxy-Game-Instance-Id` / `X-Proxy-Tenant-Id` inputs are present. Raw proxy names are stripped and never forwarded.
- `X-World-Slug` / `X-Realm-Slug` / `X-Pointer-Version` – one all-or-none routing bundle derived from verified connect-token claims on non-proxy admission or accepted from the authenticated TCP Proxy bridge. A partial, malformed, or non-positive-version bundle is rejected. These values and the game/tenant fields remain advisory admission context; Game Session validates them against current session, entitlement, catalog, and admission-pointer authority.
- `X-Firemud-Connect-Context` – for non-proxy `/ws/game/**` handshakes that pass connect-token validation, gateway emits a short-lived signed context payload containing the complete verified connect scope: `accountId`, `tenantId`, `realmId`, `worldSlug`, `realmSlug`, `playableStateNamespaceId`, `playableStateScope`, `gameInstanceId`, `pointerVersion`, `catalogRevision`, `connectScopeId`, `connectRequestId`, `audience: game-session`, `recipient: game-session-service`, the bounded selected-target `authorityTuple` including its applicable caller-membership generation, exact `membershipVersion: {tenantId: version}` map containing only the selected tenant, `replayAdmissionFence`, `connectTokenJti`, `issuedAt`, `verifiedAt`, `expiresAt`, and `gatewayRequestId`. Multi-Tenancy resolves the tenant-local `{tenantId, realmSlug}` selectors to the durable `realmId`; Account carries that exact resolved realm snapshot and admission evidence in the `gameplay-connect` token. Private/playtest targets additionally carry the exact `playtestLifecycleId` and positive `playtestStateGeneration`; public-production contexts omit both and reject either field if present. The lifecycle ID and generation must equal the verified source claim and selected-target context exactly, and remain distinct from the admission-pointer `pointerVersion`. The context `expiresAt` is no later than the source `gameplay-connect` JWT `exp` and no later than `issuedAt + 30 seconds`; Gateway never extends the source token lifetime or grants a post-consumption grace period. The `audience` and `recipient` values are registered context bindings, not client input. Gateway carries these Account-produced selectors, durable realm identity, and authority evidence unchanged without reinterpretation, independent realm resolution, or rescoping; its provenance and Game Session's joint continuation validation are defined by the [canonical JWT and token contract](./system-architecture-jwt-and-token-contracts.md#canonical-authority-tuple) and [ADR 0137](./decisions/adr-0137-isolated-playtest-state-modes-and-reset.md). Game Session must validate the complete context schema, signature, expiry bounds, exact audience/recipient and claim/context equality, then validate the durable realm identity, lifecycle ID, positive generation, namespace, and current caller grant together for private/playtest admission before using this context; public-production admission rejects those playtest fields. Replay protection for `connectTokenJti` remains Gateway-owned and is not re-implemented as a second authority in Game Session. `connectTokenJti` is internal replay/audit evidence and is never returned in client metadata or protocol output.
- `X-Firemud-Connection-Mode` – gateway-owned marker identifying the gameplay admission path. `first_party_web` identifies public non-proxy WebSockets admitted through the protected first-party cookie path; `trusted_tcp_proxy` identifies only the authenticated internal TCP Proxy mTLS bridge and is not a public WebSocket mode. A future `non_first_party_public` value is reserved but unavailable. The current `first_party_web` value covers browser/mobile-browser and native or other nonbrowser Account clients using a protected cookie jar; it identifies the verified connect-token path rather than a browser-only client class. It does not authorize a tokenless generic WebSocket path or imply current support for `X-Firemud-Connect-Token`. Game Session must treat this header as meaningful only when produced by the gateway after trust/canonicalization filters run, and must reject `/ws/game/**` admissions that present neither supported mode.
- `X-Session-Id` is not part of the canonical header contract and must not be emitted or consumed for gameplay/session binding decisions.

Gateway must overwrite any inbound values for these gateway-owned `X-Firemud-*` gameplay-admission headers; they are never forwarded from external callers verbatim and are meaningful downstream only when re-issued by the gateway after successful handshake validation. The same rule applies to raw `X-Proxy-*` inputs: after the authenticated TCP Proxy hop is canonicalized, only gateway-owned canonical headers plus the single authenticated `X-Proxy-Connection-Id` correlation exception are forwarded.
Game Session must treat `X-Proxy-Connection-Id` as authoritative only when `X-Firemud-Connection-Mode=trusted_tcp_proxy`. On all other gameplay paths, gateway must drop or overwrite `X-Proxy-Connection-Id`, and downstream services must ignore it if present.

Any lifecycle or suspend effect caused by `NotifyDisconnect` must match both the exact current proxy connection and the current binding generation. A missing, stale, or mismatched `bindingGeneration` is a no-op; a matching `proxyConnectionId` alone is insufficient to change session or game-instance state. The current event producer emits `proxyConnectionId` and `disconnectSequence` (with optional game/tenant hints) but no binding generation, and the current consumer deduplicates by proxy ID/sequence and can suspend from game/tenant context without this current-binding fence. That producer/consumer gap is known implementation drift; the session lifecycle and event-shape owners remain the linked session/TCP contracts, and this requirement does not rename the header or redesign that transport.

For identity, session, and admission metadata, Gateway forwards only canonical gateway-owned headers after trust checks and canonicalization. Public ingress values are stripped or rejected and are never forwarded verbatim as authority. The ordinary `Authorization` header remains the carrier for ordinary REST/admin JWTs and is validated by the consuming service. For gameplay, the current first-party public carrier is the protected `Firemud-Connect-Token` cookie; the `X-Firemud-Connect-Token` header is only the separate target `non_first_party_public` carrier, and either supported carrier is validated and consumed by Gateway rather than forwarded downstream.

`X-Game-Instance-Id`, `X-Tenant-Id`, and the coherent `X-World-Slug` / `X-Realm-Slug` / `X-Pointer-Version` bundle are not authentication material and must never be treated as reconnect tokens or proof of session ownership. On the trusted TCP Proxy path, they carry server-owned proxy bootstrap metadata or future explicitly selected transport hints into the gameplay WebSocket handshake:

- `X-Game-Instance-Id` is a hint for the desired game instance (`gameInstanceId`), not a gameplay “player session” identifier.
  - `X-Tenant-Id` is a hint for the desired tenant and must be validated against the authenticated account’s allowed tenants and entitlements during the canonical `LOGIN` + lobby selection (`PLAY`) flow.
  - The world/realm/pointer bundle is a coherent advisory snapshot selector and must be validated against current catalog and admission-pointer authority before use.
- Any mismatch between these hints and Redis-backed session bindings or authenticated claims must result in the enter-game request being rejected with a canonical admission error and should be logged as a suspicious attach attempt. Game Session must not silently ignore an inadmissible trusted-proxy attach hint once it has been promoted into canonical admission context. The canonical `PLAY` admission error set lives in [Authentication & Authorization](./system-architecture-authentication.md#play-returns-canonical-stable-error-codes-so-clients-can-recover-deterministically); gateway and proxy docs must reference that table rather than inventing a Telnet-only error name for inadmissible trusted-proxy attach hints.

After `PLAY` succeeds, Redis-backed session binding is authoritative for tenant/gameplay scope. Header hints (`X-Game-Instance-Id`, `X-Tenant-Id`) remain admission-only context and must not override already-bound session scope.

Typed Telnet `SESSION` lines are not part of the player-facing or advanced-client target contract. For canonical tenant-selection behavior, see [Tenant Selection for Gameplay](./system-architecture-authentication.md#tenant-selection-for-gameplay).

---

## WebSocket Support

- WebSocket is used by modern clients (e.g., browser-based interfaces) for real-time interaction
- Spring Cloud Gateway supports **WebSocket proxying**, allowing connections to be routed to backend services (e.g., `game-session-service`)
- WebSocket connections benefit from:
  - Logging and metrics
  - Route-based filtering
  - Consistent handling across all clients

At the target-state configuration level, Spring Cloud Gateway defines WebSocket routes in the declarative `routes.yml` catalog imported by `application.yml` and applies filters (such as rate limiting and retries) before forwarding to backend services. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) for the authoritative description of route configuration, service discovery overrides, and gateway-related environment variables. For gameplay login and session semantics, this document defers to the canonical flow in [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow); this doc focuses on transport-level responsibilities only.

### Gameplay WebSocket Route

- **Canonical route path** – `/ws/game/**` is the canonical public gameplay WebSocket entry point for first-party gameplay clients. Telnet clients reach the same backend surface only through the TCP Proxy Service's separately authenticated internal mTLS bridge.
- **Single route policy** – `/ws/game/**` is the only supported gameplay WebSocket entry point. Gameplay admission through alternate legacy routes is not supported. The future non-first-party/public header carrier is a guarded authentication variant on this same path, not a second entry point.
- **Carrier-variant policy** – The current public non-proxy variant is `first_party_web` plus exactly one protected `Firemud-Connect-Token` cookie. `trusted_tcp_proxy` is not a public WebSocket variant; it is the separately authenticated internal mTLS TCP Proxy bridge. A future `non_first_party_public` variant may accept `X-Firemud-Connect-Token` only after a dedicated route-matrix variant, issuance/replay record, signed-context, response, and carrier proof is implemented. Until that proof exists, Gateway rejects the header with `CONNECT_TOKEN_REJECTED` and reason `unsupported_carrier_or_route`, strips it, and does not fall back to the cookie or infer a connection mode from its presence.
- **Connect-token admission marker** – successful public non-proxy `/ws/game/**` handshakes must emit `X-Firemud-Connection-Mode=first_party_web` as defined in [Gateway Output Rules (Downstream-Trusted)](#gateway-output-rules-downstream-trusted). The current value names the verified connect-token path, not the client owner or carrier, so downstream services can distinguish it using the same positive discriminator model as the trusted TCP Proxy path.
- **Telnet bridge usage** – The TCP Proxy Service connects to Spring Cloud Gateway using the explicit `GATEWAY_WS_URL` environment variable. Shared and player-facing environments must set it to a `wss://.../ws/game` URL that targets the Gateway’s internal-only WebSocket mTLS listener (for example `wss://spring-cloud-gateway-mtls:8443/ws/game`) so the proxy–gateway hop uses mTLS as described in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway). Local Compose smoke sets the same variable explicitly to the canonical in-stack target. TCP Proxy bootstrap metadata and header propagation rules are defined in the TCP Proxy design; this document intentionally summarizes only the routing side.
- **Required headers** – Spring Cloud Gateway preserves or sets:
  - `X-Client-IP` with the originating client address. For web clients this is derived from the external load balancer’s forwarded headers. For Telnet clients this is derived by the gateway from `X-Proxy-Client-IP` after authenticating the TCP Proxy identity (see [Header Trust Model](#header-trust-model)).
  - `X-Proxy-Game-Instance-Id`, `X-Proxy-Tenant-Id`, and `X-Proxy-Connection-Id` are bridge-only inputs on the TCP Proxy → Gateway hop when the proxy supplies server-owned bootstrap metadata or future explicitly selected transport hints, or when the proxy needs disconnect correlation. The authenticated bridge may also carry the all-or-none `X-World-Slug` / `X-Realm-Slug` / positive `X-Pointer-Version` routing bundle. Gateway strips raw `X-Proxy-*` names, emits canonical `X-Game-Instance-Id` / `X-Tenant-Id`, validates and forwards the coherent routing bundle, and preserves the same `X-Proxy-Connection-Id` value only for correlation (see [Header Trust Model](#header-trust-model)).
  - Standard correlation and trace headers defined in the logging/observability guidelines.
- **TLS expectations**
  - External clients connect over `wss://` to the public load balancer, which forwards to Spring Cloud Gateway as described in [Security Architecture](./system-architecture-security.md#tls-termination--internal-encryption).
  - The TCP Proxy Service connects to `/ws/game/**` using `wss://` with mutual TLS to the Gateway’s internal-only WebSocket mTLS listener in production; plain `ws://` is reserved for local/dev-only flows.
- **Admission discriminator** – `X-Firemud-Connection-Mode` is the sole positive discriminator for gameplay admission path. Its supported values and downstream meaning are defined in [Gateway Output Rules (Downstream-Trusted)](#gateway-output-rules-downstream-trusted). Game Session must accept the trusted-proxy bypass only when this gateway-issued header is `trusted_tcp_proxy`; it must not infer trusted-proxy admission from the absence of `X-Firemud-Connect-Context` or from raw `X-Proxy-*` headers.

### Gameplay Sharding (Routing Boundary)

Gateway is the gameplay admission surface for `/ws/game/**`, but it is not the owner of shard leases, shard mapping, or gameplay sharding policy. `/ws/game/**` routes to a stable Game Session service endpoint, and any sharding/lease moves are handled within the Game Session layer and its coordination mechanisms.

The edge contract does not define a distinct client-visible “shard handoff” close category. If a future architecture introduces explicit lease-aware routing or handoff semantics at the edge, it must be defined as a dedicated design update and integrated into the close-code and reconnection contracts described in this document and [Protocol Bridging](./system-architecture-protocol-bridging.md).

While shard routing stays internal to Game Session, the internal session front-end → lease-owner forwarding leg inherits the same user-visible safety constraints as the edge session:

- it must preserve per-connection FIFO for gameplay commands,
- it must apply bounded buffering/backpressure rather than unbounded queues,
- and if that forwarding path fails in a way that makes gameplay impossible, Game Session is responsible for surfacing the failure upstream so Gateway can emit the canonical client-visible close or error.

### WebSocket Liveness and Idle Timeouts

Gameplay WebSocket connections are long-lived but not unbounded. To avoid half-open sessions and to make idle behaviour predictable:

- Spring Cloud Gateway (or the underlying WebSocket container) sends periodic WebSocket `ping` frames on `/ws/game/**` (for example every 30 seconds) while connections are idle.
- If no `pong` or other traffic is observed for a configured idle window (for example 90 seconds), the connection is closed with a clear close reason. Load balancers or CDNs in front of the gateway must be configured with idle timeouts greater than this window so they do not terminate connections more aggressively than the gateway itself.
- Web clients are not required to send their own application-level heartbeats, but they may do so; they must be prepared for the gateway to close idle or unreachable connections according to these limits and to follow the reconnection rules in [Reconnection Strategy](./system-architecture-reconnection.md).

The canonical handshake-failure classification and client backoff policy for `/ws/game/**` is defined in [Reconnection Strategy](./system-architecture-reconnection.md#http-handshake-failures-on-ws-game). This document must not introduce a conflicting retry/backoff matrix.

For gameplay WebSocket sessions, FireMUD standardises a small set of close codes and reasons so clients and operators can interpret failures consistently. Spring Cloud Gateway is the **only component that emits WebSocket close frames to external clients**; backend services (including Game Session) express their intent via upstream failures or closes, and Gateway maps those outcomes into the standard close codes below. Telnet disconnect messages on the TCP Proxy Service map directly onto the same categories as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons):

- `1000` with reason `logout` – terminal gameplay logout or forced session termination (for example, user-initiated logout, administrator/security termination, or revocation-driven session end).
- `1000` with reason `session_replaced` – the displaced transport from a successful controller takeover. The replacement connection remains authoritative; this close does not terminate the gameplay identity.
- `1012` with reason `service_restart` – planned Gateway drain or restart. This is a retryable maintenance outcome, not terminal gameplay logout.
- `1001` with reason `idle_timeout` – idle-connection timeout where the gateway or Game Session has not observed traffic within its configured idle window.
- `1008` with reason `policy_violation` – client behaviour that violates platform policies (for example, sustained command‑rate abuse, malformed frames, repeated protocol violations, or sustained slow-client behaviour at the network edge where send buffers repeatedly overflow or time out).
- `1011` with reason `internal_error` – unexpected server‑side failures that are not attributable to the client and are not clearly a backend‑unavailable condition.
- `1013` with reason `backend_unavailable` – Spring Cloud Gateway has concluded that backend services needed for gameplay are unavailable or overloaded beyond a short tolerance window (see [Reconnection Strategy](./system-architecture-reconnection.md#backend-unavailable-scenarios)).

Target status is tracked in [Implementation Status](#implementation-status) and [ADR 0131](./decisions/adr-0131-lifecycle-distinct-gameplay-close-taxonomy.md#implementation-status): the bounded typed Game Session → Gateway lifecycle intent is target-only and is not implemented or versioned yet, and current Gateway behavior infers intent from upstream close/error behavior. Once that target contract is implemented, Gateway and Game Session must map platform‑initiated closures into one of these categories. Game Session emits `gamesession.connection.closed{reason=...}` for its owned lifecycle outcome; Gateway emits `gateway.websocket.closes{reason=...,subreason=...}` and `gateway.tcp_proxy_bridge.closes{reason=...,subreason=...,bridge_shutdown_class=...}` and remains the sole owner of translating that outcome into client-facing close codes/reasons. The bridge-only metric is emitted only for authenticated TCP Proxy bridge observations; ordinary public WebSocket closes do not carry a `bridge_shutdown_class` label. TCP Proxy translates the authenticated top-level bridge outcome into the equivalent Telnet token; it does not choose an independent external taxonomy. Until the typed contract exists, any missing, unknown, or malformed upstream lifecycle intent is bounded as `unknown_intent` and fails closed to `1011/internal_error` for the external WebSocket; it must not be inferred from an optional subreason.

In addition to the bounded top-level reason taxonomy, implementations may emit a bounded close `subreason` for client policy tuning and operations correlation without creating new top-level categories. Supported values are `user_logout`, `takeover`, `gateway_restart`, `admin_termination`, `edge_backpressure`, and `none`. The `subreason` field must be present in structured logs and close metrics and must remain low-cardinality. It is optional wire metadata and never lifecycle authority: missing, unknown, or conflicting subreason values do not change the top-level behavior.

Wire compatibility for `subreason` is explicit:

- The WebSocket close code and top-level reason remain the only mandatory client-facing transport contract.
- `subreason` is emitted as a best-effort hint on the wire when transport/framework constraints allow it, and is always emitted in structured logs/metrics.
- Clients and services must treat missing wire `subreason` as `none` (backward-compatible default), and must not fail protocol handling when only the top-level reason is present. Clients must choose terminal, displaced, planned-restart, retry, or policy behavior from the top-level reason alone.
- When emitted on-wire, `subreason` is encoded as a close-reason suffix in the form `;subreason=<value>` appended to the top-level reason token.
- If transport/framework limits would exceed the WebSocket close-reason payload limit, producers must keep the top-level reason and omit the `subreason` suffix (equivalent to `none`) instead of truncating mid-token.

The close-reason parser splits the reason at the first `;`: the first segment must be one exact top-level reason token, and the remaining text may contain at most one exact `subreason=<bounded-value>` suffix. A missing, unknown, omitted, malformed, duplicate, or conflicting subreason is normalized to `none` and never changes a valid top-level class. The parser must validate the complete canonical `(close code, top-level reason)` pair from the matrix below; a recognized reason paired with the wrong code is invalid top-level metadata. Only an unrecognized top-level token, missing code/reason, otherwise unparseable top-level close, or invalid code/reason pair is `unknown_intent` and fails closed to `1011/internal_error` for the external WebSocket. On the authenticated TCP Proxy bridge, those missing, unrecognized, or invalid top-level pairs use the observation-specific `backend_unavailable` outcome with `bridge_shutdown_class=unattributed_failure`; valid top-level pairs remain attributable even when optional subreason metadata is malformed. After parsing, apply failure precedence `policy_violation` > `backend_unavailable` > `internal_error` > `idle_timeout`, then lifecycle precedence `logout` > `session_replaced` > `service_restart`; suffixes are never inputs to either precedence rule.

To keep behaviour consistent and avoid double-closing sessions, ownership of these close codes is divided as follows:

- `logout` (`1000`) – Game Session owns terminal gameplay logouts and admin/security-initiated session ends; Gateway translates the typed terminal outcome without reclassifying it.
- `session_replaced` (`1000`) – Game Session owns controller takeover and identifies the displaced transport; Gateway translates it as `1000/session_replaced` and must not collapse it into terminal `logout`.
- `service_restart` (`1012`) – Gateway owns the client-visible signal for its planned drain/restart and emits it on affected gameplay sockets; the TCP Proxy maps the authenticated bridge outcome to Telnet `service_restart`.
- `idle_timeout` (`1001`) – The layer that observes the idle condition first closes the connection: Game Session for application‑level idle (for example no gameplay traffic from a client that is otherwise reachable), or Gateway/WebSocket container for network‑level idle (no frames or pongs within the configured idle window). Other layers treat the close as a peer shutdown and do not wrap it in a second close reason.
- `policy_violation` (`1008`) – The layer that detects the violation closes with `policy_violation`. Gateway uses this for HTTP or WebSocket protocol abuse (for example frame shape violations on `/ws/game/**`) and network-level slow-client/backpressure enforcement (subreason `edge_backpressure`); Game Session uses it for gameplay/content‑level abuse (for example sustained command‑rate or scripting violations); the TCP Proxy maps Telnet‑side `policy_violation` disconnects into this category via the Telnet reason taxonomy in [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons).
- `internal_error` (`1011`) – Any layer that encounters an unexpected server‑side error (not clearly attributable to the client and not covered by `backend_unavailable`) closes with `internal_error` and logs the underlying failure. Other layers treat it as a generic peer failure and avoid emitting a second, conflicting close reason for the same session.
- `backend_unavailable` (`1013`) – Gateway owns closing WebSocket sessions when core gameplay backends are continuously unavailable beyond the configured grace window. Game Session surfaces its own health via metrics and health checks; Gateway uses that information plus its own upstream connectivity failures to decide when to send `1013/backend_unavailable` as described in [Reconnection Strategy](./system-architecture-reconnection.md#backend-unavailable-scenarios). Telnet clients see the corresponding `backend_unavailable` Telnet reason from the TCP Proxy.

#### Canonical Close Translation Matrix

Gateway is the authoritative translation point for client-visible WebSocket closes. The following mapping is canonical for `/ws/game/**`:

| Upstream/session condition | Client-visible WebSocket close | Telnet reason token |
| --- | --- | --- |
| Explicit terminal logout or forced session termination | `1000` / `logout` | `logout` |
| Successful controller takeover displacing the old transport | `1000` / `session_replaced` | `session_replaced` |
| Planned Gateway drain or restart | `1012` / `service_restart` | `service_restart` |
| Idle timeout detected by first observing layer | `1001` / `idle_timeout` | `idle_timeout` |
| Edge or gameplay policy violation | `1008` / `policy_violation` | `policy_violation` |
| Unexpected non-policy server failure | `1011` / `internal_error` | `internal_error` |
| Sustained gameplay backend unavailability or unreachable upstream | `1013` / `backend_unavailable` | `backend_unavailable` |

When observations overlap, a close code or reason alone never proves lifecycle commitment. First classify only positively evidenced failure conditions and apply failure precedence: `policy_violation` > `backend_unavailable` > `internal_error` > `idle_timeout`. Only when no positively evidenced higher-priority failure exists, use authoritative lifecycle evidence from Game Session durable terminal logout or takeover evidence, or Gateway planned-drain evidence, and apply lifecycle precedence `logout` > `session_replaced` > `service_restart`: a committed logout stays terminal against competing lifecycle observations, but not against an independently positively evidenced higher-priority failure; otherwise a successful controller takeover beats a concurrent planned drain; select `service_restart` only when Gateway planned-drain evidence is positive. If lifecycle evidence is missing or ambiguous, use the observation-specific fallback: `internal_error` for a public WebSocket with no attributable close, or `backend_unavailable` for an established authenticated TCP Proxy bridge with no valid top-level close. A subreason never supplies proof.

For any top-level close, producers may attach the most specific supported `subreason` (`user_logout`, `takeover`, `gateway_restart`, `admin_termination`, `edge_backpressure`) when known, else `none`; it remains diagnostic metadata. When a graceful Gateway drain terminates the upstream gameplay WebSocket used by the TCP Proxy bridge, the proxy must preserve the top-level `service_restart` category rather than collapsing it into `logout` or `backend_unavailable`; detailed Telnet translation rules live in [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons).

For the internal authenticated TCP Proxy bridge path, graceful drain attribution is stricter than the public-client best-effort wire rule: Gateway must emit a machine-parseable bridge-drain signal that the proxy can rely on to classify the shutdown as `service_restart`. The canonical encoding is a WebSocket close with `1012/service_restart;subreason=gateway_restart` on the bridge itself. On this bridge, `bridge_shutdown_class` is bridge-only operational metadata, never lifecycle authority: `planned_drain` marks a clean `1012/service_restart`, `valid_upstream_close` marks every other valid authenticated top-level close, and `unattributed_failure` is reserved for absent, invalid, or otherwise unattributable bridge close metadata. The top-level close reason remains authoritative; only absent or invalid close metadata uses observation-specific `backend_unavailable` for the Telnet client. See [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons) and [TCP Proxy Service Operations](./microservices/tcp-proxy-service/operations.md).

If the bridge transport fails before a valid top-level close can be sent, producers must not substitute an ad hoc alternate signal. The outcome is treated as an unattributed bridge failure on the internal authenticated TCP Proxy bridge: for already-established Telnet sessions, the proxy closes immediately with `backend_unavailable`, while external/public WebSocket gameplay sessions continue to use `gateway.websocket.closes` and the normal Gateway `unreachable` / recovery-window and no-close-frame retry rules governed by `firemud.gateway.backendUnavailableGraceMs`. Only the authenticated TCP Proxy bridge observation emits the bounded bridge-only field `bridge_shutdown_class=planned_drain|valid_upstream_close|unattributed_failure`; external/public WebSocket sessions do not emit that field or label. It never changes the top-level lifecycle or retry behavior. This fallback is observation-specific: a WebSocket client that receives no close frame treats the loss as abnormal transport loss and follows the `internal_error` retry policy, whereas an established Telnet bridge with no valid top-level close surfaces `backend_unavailable`.

A close class reports connection/session lifecycle, not whether an in-flight gameplay command committed. Clients and tools reconcile any known `{tenantId, gameInstanceId, commandId}` through the authoritative command-status surface; they do not infer command success or failure from `logout`, `session_replaced`, `service_restart`, `internal_error`, or `backend_unavailable`.

### Backend-Unavailable Grace Window

Spring Cloud Gateway applies a small grace window before closing WebSocket sessions due to sustained backend outages so that brief flaps do not cause unnecessary reconnects:

- Gateway uses two explicit backend health states for gameplay routes:
  - `degraded_but_reachable` – upstream is connected and can still return bounded explicit gameplay/protocol errors for requests.
  - `unreachable` – upstream cannot be established or maintained (`UNAVAILABLE`, connect failures, handshake failures, or equivalent all-failed route state).
- The `firemud.gateway.backendUnavailableGraceMs` configuration property applies only to the `unreachable` state and implements [ADR 0013](./decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md)'s elapsed-time cutoff. It must be positive and no greater than 30,000 ms. Ordinary qualifying restart recovery targets no more than 10 seconds; retry-attempt counts do not replace either elapsed-time criterion.
- `firemud.gateway.backendUnavailableRecoverySuccessCount` defines recovery hysteresis for exiting `unreachable`. Gateway returns to `degraded_but_reachable` or healthy routing only after at least this many consecutive successful upstream connect/forward attempts (default `3`).
- Gateway enters `unreachable` when the `/ws/game/**` upstream cannot be established or maintained and route checks are continuously failing.
- While in `degraded_but_reachable`, sessions stay open and command outcomes are explicit backend errors. “Bounded explicit response” here means within the platform’s normal command/request timeout budget for the affected path; this state must not be used for stalled sessions that cannot produce a timely explicit gameplay/protocol response. Those sessions must transition to `unreachable`. While in `unreachable`, the backend unavailable timer runs and handshake/sending behavior follows the rules below.
- When the backend is currently unavailable, Gateway rejects new gameplay-route connections (`/ws/game/**`) with HTTP `503` so clients can apply the reconnection and backoff rules defined in [Reconnection Strategy](./system-architecture-reconnection.md#http-handshake-failures-on-ws-game). The grace window is an established-session behaviour; it does not create “maybe works” handshake outcomes.
- When the backend has been continuously unavailable beyond `firemud.gateway.backendUnavailableGraceMs` without any successful calls or healthy checks, Gateway must close affected gameplay WebSocket sessions with `1013/backend_unavailable`.
- Load balancers or CDNs in front of Gateway should be configured with idle and failure timeouts that do not undercut this grace window; otherwise, they may terminate connections before Gateway can emit the canonical `backend_unavailable` signal.
- Gateway startup must fail fast when `firemud.gateway.backendUnavailableGraceMs <= 0`, `firemud.gateway.backendUnavailableGraceMs > 30000`, or `firemud.gateway.backendUnavailableRecoverySuccessCount <= 0`.
- **Established-session input handling while backend is unavailable** – Gateway is a WebSocket proxy and does not generate gameplay-protocol error frames or interpret MCP/classic-client semantics when the upstream Game Session hop is down. It may apply generic WebSocket frame, buffering, and connection limits, but accepted client input that exceeds a limit or cannot be forwarded under backpressure receives an explicit bounded WebSocket error/close rather than a keep-open silent discard. To avoid silently discarding gameplay commands while a connection appears healthy:
  - An established `/ws/game/**` session in `unreachable` state retains its edge socket during bounded upstream rebind. Input accepted into its bounded stall buffer remains FIFO; only input not yet forwarded before the stall may be sent to the replacement upstream. An upstream write whose acceptance is ambiguous is not replayed: edge delivery remains at-most-once. Once that ambiguity occurs, Gateway stops forwarding all later buffered input to the replacement upstream and closes the session explicitly with `1013/backend_unavailable`; only a future trusted sequence/acknowledgement/deduplication contract could permit safe continuation. Buffer exhaustion also closes that session explicitly with `1013/backend_unavailable`; Gateway never silently drops stalled input while leaving the session open. A future retry of an ambiguous write would require trusted upstream acceptance/acknowledgement plus stable `commandId` deduplication; this contract does not define that future mechanism.
  - If the backend unavailable timer exceeds `firemud.gateway.backendUnavailableGraceMs`, Gateway closes remaining affected sessions with `1013/backend_unavailable` even if they are idle, so clients receive a clear canonical signal instead of sitting on half-open connections indefinitely.

Gateway-to-Game-Session close handling uses a bounded internal classification that distinguishes rebindable backend lifecycle or transport loss from session outcomes such as terminal logout, controller takeover (`session_replaced`), policy rejection, revocation, and loss of current authorization. A rebindable internal close is not forwarded to the client when recovery succeeds. This classification does not add a public close category; exhausted recovery still maps to `1013/backend_unavailable`.

Validation and runtime-proof selection follows [Validation and Runtime Proof](../developer-workflows/validation-and-runtime-proof.md); execution evidence remains in PR/CI records or implementation trackers, not this normative contract.

### Gateway Restart Semantics

Gateway restarts can be planned (for example, rolling deploys) or unplanned (for example, crashes or infrastructure failures). To keep client behaviour and operational signals consistent:

- **Planned, graceful restarts**
  - During a controlled drain or rolling restart, Gateway closes existing gameplay WebSocket sessions with code `1012`, reason `service_restart`, and optional subreason `gateway_restart`. Clients should treat this as an expected retryable maintenance outcome and reconnect with their normal backoff policy.
- **Unplanned internal failures at Gateway**
  - When Gateway encounters an unexpected internal error that forces it to drop gameplay WebSocket sessions independently of backend health (for example, container crashes, unrecoverable configuration errors), it should close affected sessions with code `1011` and reason `internal_error` when a close frame can be emitted before teardown. Clients should treat this like other internal errors and apply the standard exponential backoff rules described in [Reconnection Strategy](./system-architecture-reconnection.md#client-reconnection-behaviour).
  - Some hard failures (for example process crash, node restart, abrupt transport reset) can terminate the TCP/WebSocket transport before any close frame is emitted. Clients must treat missing close code/reason as abnormal transport loss and apply the `internal_error` retry policy.
- **Backend-unavailable vs restart**
  - `1013/backend_unavailable` remains reserved for cases where Gateway’s backend unavailable timer exceeds `firemud.gateway.backendUnavailableGraceMs` because core gameplay backends are failing or unreachable. Gateway must not emit `1013/backend_unavailable` solely because of its own planned process restart; that condition is `1012/service_restart`. An unplanned process crash may emit `1011/internal_error` when a frame can be sent, or no frame at all.

---

## Telnet / TCP Bridging

- Traditional MUD clients connect via **raw TCP** (Telnet protocol)
- These connections are terminated by a **dedicated TCP Proxy Service** outside of Spring Cloud Gateway
- The TCP Proxy Service acts as a **bridge**, creating a WebSocket connection through the Gateway to normalize legacy TCP traffic
  - Spring Cloud Gateway itself only handles **HTTP** and **WebSocket** traffic

This pattern ensures all real-time gameplay is unified through WebSocket on the backend, regardless of client type.

---

## Centralized Gateway Benefits

Spring Cloud Gateway provides centralized management of client traffic, offering:

- Ordinary REST/admin JWTs are forwarded for downstream validation by the consuming service; the `gameplay-connect` JWT is excluded from that path and is validated by Gateway only during the `/ws/game/**` handshake, using the connect-token handshake classifications below, including `CONNECT_TOKEN_REJECTED` for unsupported or rejected carrier/token content. Gameplay protocol clients do not otherwise provide JWTs; all non-proxy `/ws/game/**` WebSocket clients must provide this short-lived connect token for handshake-time edge policy as described below.
- Cross-cutting filters (e.g., rate limiting, logging, CORS)

> **Redis topology guidance:** Coordination Redis and Cache/Rate‑Limit Redis must
> use **separate processes and endpoints** in every non-ephemeral or player-facing
> environment, including `local-dev` and hosted `pr-preview` (for example, two
> containers on a single dev machine or distinct pods/clusters in Kubernetes).
> Sharing a single Redis process and endpoint for both roles is allowed only for
> an explicitly labelled one-shot ephemeral test/CI topology; hosted `pr-preview`
> is excluded. That exception must be reset-tolerant, visibly surface the shared
> endpoint, and provides no role-isolation, replay, tail-loss, or SLO evidence.
> For any player-facing environment, configure the Gateway to use the dedicated
> **Cache/Rate‑Limit Redis deployment** so rate limiting and cache activity cannot
> interfere with tick/session coordination. See
> [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#redis-connection)
> and [Redis Architecture](./system-architecture-redis.md#redis-profiles) for
> reference profiles.

- Service isolation through route-based access control
- Easy expansion of routes for new microservices
- TLS termination and mTLS between services are described in [Security Architecture](./system-architecture-security.md).

## Rate Limiting & Abuse Protection

Spring Cloud Gateway and the TCP Proxy Service share responsibility for protecting the platform from abusive traffic:

### Tenant-Aware Edge Connect Token (Gameplay Handshake)

Tenant-aware edge limiting on `/ws/game/**` depends on a short-lived server-minted connect token presented at WebSocket handshake time.
This section is the canonical source of truth for connect-token enforcement and handshake outcomes.

- **Issuer and audience**
  - Issued only by the account/authentication control-plane after account authentication and tenant entitlement checks.
  - Issuance is performed via the control-plane connect-token API (for example `POST /auth/connect-token`); gateway must not mint connect tokens.
  - Account may issue only while the shared replay-readiness record is `OPEN`, and each token carries the exact `replayAdmissionFence` observed for that decision. Missing, unreadable, quarantined, or changing replay readiness fails issuance closed.
  - Audience is exactly `gameplay-connect`, the token profile for the gateway gameplay route (`/ws/game/**`), and must not be accepted on unrelated routes.
  - Gateway validates signatures against the issuer's published verification key set with explicit `kid` selection and overlap handling during rotation.
- **Transport location**
  - Target-only/unavailable: after a guarded `non_first_party_public` route variant on the canonical `/ws/game/**` path is fully registered and its issuance, profile, replay, signed-context, response, and carrier proofs are complete, that variant may accept the connect token in the dedicated handshake header (`X-Firemud-Connect-Token`), with native clients storing it in OS secure storage where applicable. Until then Gateway rejects this header as `CONNECT_TOKEN_REJECTED` with reason `unsupported_carrier_or_route`; it is not current `first_party_web` support and cannot be used as a fallback carrier.
  - First-party browser and mobile-browser clients, and first-party native-mobile or other non-browser clients using a cookie jar, send the connect token through the `Firemud-Connect-Token` cookie set by `POST /auth/connect-token`. First-party status never creates a header fallback.
  - Required cookie attributes: `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/ws/game`, and `Max-Age` no longer than the connect-token TTL.
  - The cookie value is the connect token. Browser JavaScript must not read, copy, or persist the token outside the cookie; client code uses the non-secret response metadata from `POST /auth/connect-token` for retry and expiry UX.
  - Gateway must accept exactly one non-empty, single-valued currently supported connect-token carrier on non-proxy `/ws/game/**` handshakes. Duplicate header values, duplicate token cookies, or simultaneous header and cookie carriers are rejected as `CONNECT_TOKEN_REJECTED`; Gateway never chooses precedence among ambiguous credentials. The target header carrier remains unavailable until its dedicated route proof is complete.
  - Gateway must not accept connect tokens from query parameters in player-facing environments. Query-carried values do not satisfy the token requirement and are never promoted into a supported carrier.
- **Required claims**
  - `accountId`
  - `tenantId`
  - `realmId`
  - `playableStateNamespaceId`
  - `playableStateScope` (server-derived from the selected catalog policy)
  - `gameInstanceId`
  - `worldSlug`
  - `realmSlug`
  - `playtestLifecycleId` (required for private/playtest targets; absent and rejected for public production)
  - `playtestStateGeneration` (required and positive for private/playtest targets; absent and rejected for public production)
  - `pointerVersion` (admission-pointer fence, distinct from `playtestStateGeneration`)
  - `catalogRevision`
  - `connectScopeId`
  - `requestId` (the source connect-token issuance/request-operation identity; the signed Gateway → Game Session context names this field `connectRequestId`)
  - bounded selected-target `authorityTuple` including its applicable caller-membership generation and separate exact selected-tenant `membershipVersion: {tenantId: version}` map, carried as distinct fields under the [canonical JWT and token contract](./system-architecture-jwt-and-token-contracts.md#canonical-authority-tuple)
  - `iat` (absolute issuance time)
  - `exp` (absolute expiration)
  - `jti` (single-use nonce for replay defense)
  - `replayAdmissionFence` (shared replay-readiness fence observed at issuance)
- **Lifetime and replay**
  - Token lifetime has a platform hard maximum of 30 seconds from signed `iat` to `exp`. Issuers may choose a shorter lifetime; Gateway rejects a missing or future-skewed `iat`, an `exp` that does not follow `iat`, or a declared lifetime above the hard maximum even if the signature and current expiry are otherwise valid.
  - `firemud.gateway.connectTokenClockSkewMs` (environment variable `FIREMUD_GATEWAY_CONNECT_TOKEN_CLOCK_SKEW_MS`) is the one connect-token clock-skew setting. It defaults to `5000` ms and must be within `0..5000` ms. Gateway uses the same value for future-`iat` tolerance, expiry acceptance, replay-marker expiry, and replay-protection quarantine; no separate `small_skew`, JWT skew, or replay skew may be configured for this path. An invalid, unknown, or inconsistent value fails player-facing readiness.
  - Gateway must reject expired tokens and tokens whose `jti` has already been observed within the replay window.
  - After signature, issuer/audience, lifetime, required-claim, and request-scope validation succeeds, Gateway requires shared replay readiness to be `OPEN`, the signed `replayAdmissionFence` to equal the current fence, and the Gateway-owned browser deny marker for the token `jti` to be absent, then atomically repeats those checks while consuming `jti` before opening the upstream WebSocket. The token is spent even if the subsequent upgrade or backend connection fails; a retry obtains a newly issued token.
  - Replay cache entries must expire automatically at `exp + firemud.gateway.connectTokenClockSkewMs`. The replay marker covers the complete token acceptance window and must not be shortened to a fixed 30 seconds from consumption.
  - A hard Account or security revocation blocks new issuance for the revoked account or authority. A successful consume is terminal for that exact token `jti`. A deny-marker write becomes a confirmed terminal denial only after its configured same-connection `WAITAOF` acknowledgement succeeds; Gateway then never admits that `jti` again during its remaining acceptance window. Consuming one token does not prevent a later independently authorized issuance with a new request identity and `jti`. Gateway evaluates the token against its deployment-approved synchronized UTC epoch-millisecond wall clock: target future-`iat` tolerance permits acceptance no earlier than `iat - firemud.gateway.connectTokenClockSkewMs`, and target expiry tolerance permits acceptance no later than `exp + firemud.gateway.connectTokenClockSkewMs`. Therefore, when `exp - iat` is at the 30-second hard maximum, the complete possible acceptance interval is at most 30 seconds plus two configured skew intervals (40 seconds at the default 5-second skew); measured from the signed `iat` to the latest consume, the bound is 30 seconds plus one skew interval. An unavailable, timed-out, malformed, below-threshold, or otherwise ambiguous deny write is unconfirmed: the marker may have been written and Gateway must deny if it observes it, but the caller retains the bounded token-window outcome until the write is durably acknowledged or the token expires and must not report terminal no-grace revocation earlier.
  - Account owns two physically isolated connect-token issuance-result families for idempotent `/auth/connect-token` retries: the current unversioned legacy `session:connect-token:tenant:<tenantId>:account:<accountId>:scope:<sha256(connectScopeId)>:request:<sha256(requestId)>` map and the target `session:connect-token:v1:tenant:<tenantId>:account:<accountId>:scope:<scopeHash>:request:<requestHash>` projection. **Current runtime:** the legacy value contains the raw issued token or deterministic cached failure. **Target state:** the target value carries `schemaVersion: "connect-token-issuance-result-v1"` and a bounded Account-encrypted response envelope containing the exact result, opened for an exact retry only after current authority and issuance state are reconciled; it is not JWT reconstruction or admission authority. Target readers never fall back to the legacy family. Because the legacy writer lacks an issuance fence, target enablement requires full Account-cohort quiescence, in-flight drain, and the configured legacy TTL/retry horizon. The full persistence contract is owned by [Account connect-token issuance persistence](./microservices/account-service/runtime-and-data.md#connect-token-issuance-persistence-and-retention). Gateway alone owns the target replay-consumption, browser-revocation denial, and readiness/fence state in the Coordination Redis contract; an issuance-result projection, replay marker, deny marker, and readiness record never substitute for each other.
  - Replay and browser-revocation marker ownership is Gateway-only; downstream services do not participate in connect-token replay or browser-deny checks.
  - Replay checks must be backed by the player-facing Coordination Redis deployment (not Cache/Rate-Limit Redis or per-pod memory) so `jti` replay decisions are consistent across horizontally scaled gateway pods and cannot be selectively evicted. The deployment requires Redis 7.2+ with AOF enabled, `noeviction`, replay-key ACL isolation, and admission-blocking capacity thresholds. The atomic marker script is followed by `WAITAOF requiredLocalAofCount requiredReplicaAofCount timeout`; `DURABLE_REPLAY_CONSUME_ACK` succeeds only when the reported AOF-fsync counts meet the configured thresholds, with at least one local AOF acknowledgement. The browser-deny write uses the same pinned Coordination connection and configured timeout/threshold after the write; its propagation remains in the default `redis.REPL_ALL` mode. An unavailable, timed-out, malformed, below-threshold, or otherwise ambiguous deny write is unconfirmed and cannot be reported as terminal revocation. `WAIT`, read-back, or a successful client write is not a substitute, and this contract does not claim protection from disk or hardware destruction.
  - Replay and browser-revocation state use the canonical target representation defined by the Redis ownership and recovery documents, with bounded cardinality and deterministic expiry at `exp + firemud.gateway.connectTokenClockSkewMs`. The readiness/fence record and marker state must remain one script-compatible Coordination Redis atomicity domain under that contract. The legacy representation emitted by the current writer is migration-only drift and cannot be treated as target state.
  - The replay and deny-marker prefixes are security-critical, non-evicting coordination state. Gateway's one consume script checks readiness state, signed fence/cutoff, and deny-marker absence before creating the replay marker; consumption is successful only after that script and the configured Redis 7.2+ `WAITAOF` acknowledgement both succeed. A deny marker that linearizes before or during the consume race returns a denial without creating the replay marker; a consume already linearized is spent and cannot be retroactively undone by a later browser logout. A browser revocation is confirmed only after its deny write and same-connection durability acknowledgement succeed; an uncertain deny write remains unconfirmed. Capacity or acknowledgement failure rejects the handshake rather than accepting an uncertain marker. A crash or connection loss between marker creation and acknowledgement is treated as uncertain consumption, not as an opportunity to unconsume or retry the token.
  - Coordination Redis cold start, reset, unexpected eviction signal, failover, capacity-safety breach, or any other event that cannot prove replay-marker continuity starts a shared replay-protection quarantine for at least the 30-second token lifetime maximum plus two `firemud.gateway.connectTokenClockSkewMs` intervals. One interval covers a latest tolerated future `iat`; the second covers acceptance through `exp + skew`. Account stops issuing connect tokens and Gateway rejects admission until that barrier expires, configuration and capacity checks pass, and a disposable marker script followed by the configured `WAITAOF` thresholds proves `DURABLE_REPLAY_CONSUME_ACK`; tokens from the earlier fence and issuance races cannot survive reopening. The current replay deployment has one shared continuity domain, so an unscoped continuity failure intentionally blocks new connect-token issuance and admission platform-wide; a future partitioned deployment may narrow quarantine only when it can prove the exact affected marker and fence domain.
  - Replay-quarantine lifecycle is a monitored security state. Gateway must emit low-cardinality `gateway.connect_token.replay_quarantine_entries_total{reason=...}`, `gateway.connect_token.replay_quarantine_exits_total{outcome=...}`, `gateway.connect_token.replay_quarantine_active`, and `gateway.connect_token.replay_quarantine_duration_seconds`, plus structured records containing the shared `replayAdmissionFence`, `state`, `quarantineStartedAt`, `quarantineUntil`, and `openedAt` values. Alertmanager must alert when a player-facing quarantine is entered, remains active past `quarantineUntil` or has no observed exit, or exits without fresh `DURABLE_REPLAY_CONSUME_ACK` proof. Dashboards must show entry/exit counts and duration. Labels must exclude `jti`, tenant identifiers, and other high-cardinality values.
  - Replay-cache outage behavior:
    - Player-facing environments (`/ws/game/**` in `enforce` mode): fail closed with HTTP `403` and `CONNECT_REPLAY_PROTECTION_UNAVAILABLE` when replay protection is unavailable.
    - Non-player-facing dev/preview environments: fail-open is allowed only when explicitly configured for local iteration and must emit drift metrics.

Gameplay-connect is an explicit bounded revocation-freshness exception, not a second Account token-registry authority. A valid token may remain usable only before its one atomic exact-`jti` consume marker is committed and only within the target acceptance interval from `iat - firemud.gateway.connectTokenClockSkewMs` through `exp + firemud.gateway.connectTokenClockSkewMs`, whose maximum width is 30 seconds plus two configured skew intervals (40 seconds at the default 5-second skew). A hard Account or security revocation blocks new issuance and downstream admission; it may leave only that bounded pre-consumption window for an already-issued token. Once the marker is consumed, the token grants no further authority, and no `player-bootstrap` lifetime or `FIREMUD_AUTH_SESSION_EXPIRATION_MS=300000` gameplay-continuity setting extends this Gateway replay window.

- **Validation outcomes**
  - Invalid, expired, replayed, scope-mismatched, or replay-protection-unavailable token state is rejected with HTTP `403` and the bounded handshake error classes below.
  - Missing token is rejected with HTTP `403` for non-proxy gameplay clients.
  - Rate-limit exhaustion remains HTTP `429`.
  - Backend-unavailable remains HTTP `503`.

#### Browser pre-upgrade failure handling

Browser WebSocket APIs do not expose the response body or headers from a failed upgrade reliably, so failed-upgrade headers are not a browser error contract. A first-party browser that receives an error before the socket opens treats the connect token as potentially consumed, discards the failed discovery/connect-token bundle, reruns bootstrap discovery, obtains a fresh connect token, and retries with bounded exponential backoff. Repeated failure surfaces generic connection/session-recovery guidance rather than inferring a detailed handshake cause that the browser cannot observe. Gateway retains the bounded class and reason in its HTTP response for capable non-browser callers and in structured operator telemetry; no second browser preflight or status API is required.

- **Trusted TCP Proxy exception**
  - Connect-token checks are bypassed only for the TCP Proxy Service's `/ws/game/**` bridge handshake authorized by the active internal trust profile: certificate-bound profiles require the mTLS listener and selected identity predicate, while `development_cidr` requires TLS without client authentication and the exact configured source-CIDR predicate. This is the trusted TCP branch, not a public WebSocket mode.
  - This is a positive authenticated connection mode, not an inference from a proxy-shaped header. Public listeners strip untrusted proxy and connect-context headers before classification, and a public client cannot select the exception.
  - If the proxy identity or trust checks fail, the handshake is rejected with HTTP `403` and classified as `POLICY_DENY`.

This token is an edge admission/rate-limiting hint only. It does not replace `LOGIN` + lobby selection + `PLAY` and does not grant gameplay authorization by itself.

- **Verified context handoff (Gateway -> Game Session)**
  - After successful connect-token validation, Gateway must emit a signed, short-lived connect context (`X-Firemud-Connect-Context`) for the upgraded `/ws/game/**` connection.
  - Gateway strips every external connect-token carrier before forwarding. The raw header or cookie token never reaches Game Session or another upstream service.
  - Context payload must carry the registered schema: `accountId`, `tenantId`, `realmId`, `worldSlug`, `realmSlug`, `playableStateNamespaceId`, `playableStateScope`, `gameInstanceId`, `pointerVersion`, `catalogRevision`, `connectScopeId`, `connectRequestId`, `audience: game-session`, `recipient: game-session-service`, the bounded selected-target `authorityTuple` including its applicable caller-membership generation, exact `membershipVersion: {tenantId: version}` map containing only the selected tenant, `replayAdmissionFence`, `connectTokenJti`, `issuedAt`, `verifiedAt`, `expiresAt`, and `gatewayRequestId`; private/playtest contexts additionally carry the exact `playtestLifecycleId` and positive `playtestStateGeneration`, while public-production contexts omit and reject both. `playtestStateGeneration` is separate from the admission-pointer `pointerVersion`. Multi-Tenancy resolves the tenant-local `{tenantId, realmSlug}` selectors to the durable `realmId`, and Account carries that exact resolved realm snapshot and admission evidence in the `gameplay-connect` token. Gateway is the producer and must set the registered audience and recipient itself; it carries the Account-issued selected-target snapshot's durable realm identity, selectors, namespace/scope claims, and authority evidence unchanged, with exact equality through the signed context, and does not own or derive an independent realm, namespace, or scope authority. Gateway accepts those realm, namespace/scope, and runtime-target fields only as the server-resolved target already bound to the exact `catalogRevision`/`pointerVersion` pair; it must not select a newer pointer, substitute carrier fields, or resolve a separate realm/namespace/scope. The lifecycle ID and generation must match the verified source claims and selected-target context exactly. The evidence provenance, lifecycle/grant predicate, and Game Session continuation check are defined by the [canonical JWT and token contract](./system-architecture-jwt-and-token-contracts.md#canonical-authority-tuple) and [ADR 0137](./decisions/adr-0137-isolated-playtest-state-modes-and-reset.md); Game Session is the consumer and must validate those exact values before admission.
  - The producer/consumer contract proof must cover one representative signed context with every required field and exact `audience`/`recipient`, plus rejection of missing or wrong audience/recipient, altered claims, wrong field types, invalid signature/key, and expired context. Signature verification alone is not proof of the handoff contract.
  - Game Session validates the complete signed-context schema before using any field for `LOGIN`, `PLAY`, or scope comparison. Required fields are present with their declared scalar, map, or object types; `authorityTuple` and the selected-tenant `membershipVersion` map are structurally exact; `audience=game-session`, `recipient=game-session-service`, the positive `first_party_web` mode, signature (`kid` aware), bounded `issuedAt`/`expiresAt`, and all context bindings are valid. Private/playtest contexts require a positive `playtestStateGeneration` and exact `playtestLifecycleId`/generation equality with the verified source claims and selected target, followed by current lifecycle, namespace, and caller-grant validation; public-production contexts reject either playtest field. A missing field, wrong-typed field, altered claim, or field not bound to the Gateway-verified source token/selected target is rejected before any scope fallback or `PLAY` use. Replay protection for `connectTokenJti` is performed only at Gateway handshake time.
  - Missing/invalid/expired context on non-proxy handshakes that required connect-token validation must be rejected with the existing gameplay protocol error `ERROR CONNECT_CONTEXT_INVALID reason=<bounded-reason>` before `PLAY` (no scope fallback to raw headers). The bounded reasons are `missing_field`, `wrong_field_type`, `altered_claim`, `unbound_claim`, `invalid_signature`, `unknown_kid`, `expired`, `audience_mismatch`, and `recipient_mismatch`; they are machine-readable subreasons, not a second browser or handshake taxonomy.
  - If the failure occurs before HTTP 101, Gateway exposes the existing handshake class and reason surface to capable non-browser HTTP callers and structured operator telemetry; source-token failures use `CONNECT_TOKEN_REJECTED` with `reason=invalid_token_content` when that is the classified cause. If the signed context is rejected after HTTP 101, Game Session uses the existing `ERROR <CODE> <message>` protocol surface above and closes the socket. Browser clients use the conservative pre-upgrade recovery rule above and the established gameplay protocol for post-upgrade failures; they do not infer class or reason from inaccessible failed-upgrade headers.
  - Gateway must also emit `X-Firemud-Connection-Mode: first_party_web` for these first-party protected-cookie connect-token-validated handshakes, including native or other nonbrowser Account clients using a protected cookie jar. This current marker does not establish support for `X-Firemud-Connect-Token`; a future dedicated nonbrowser route may use the same marker only after its route and proof are complete.
  - Key ownership and rotation contract:
    - Gateway signs context with a dedicated asymmetric key set identified by stable issuer and `kid`.
    - Game Session trusts keys from a gateway verification-key source and must support overlap during rotation (old and new `kid` concurrently valid for a bounded window).
    - Verification-key fetch/cache policy must be explicit (bounded cache TTL, refresh-on-miss for unknown `kid`, fail-closed if no valid key set is available).
    - Metrics/logs must distinguish signer/verification failures with bounded reasons (for example `unknown_kid`, `signature_invalid`, `context_expired`, `verification_keys_unavailable`) so incidents are triageable without high-cardinality labels.

- **Trusted proxy handoff (Gateway -> Game Session)**
  - For authenticated TCP Proxy bridge handshakes, Gateway must emit `X-Firemud-Connection-Mode: trusted_tcp_proxy`.
  - Game Session must treat this mode as the only valid bypass of the first-party connect-context requirement.
  - Trusted proxy sessions must not rely on the absence of `X-Firemud-Connect-Context` as an implicit discriminator; the positive `trusted_tcp_proxy` mode marker is required so admission remains fail-closed and unambiguous.

- **Keying strategy**
  - Current Gateway rate limiting is primarily **per-client IP** with optional route-level differentiation. The default `RequestRateLimiter` configuration uses Cache/Rate-Limit Redis and derives keys from the raw client IP as seen after load-balancer and TCP Proxy headers. This is implementation drift: the target follows [ADR 0087](./decisions/adr-0087-isolated-subject-rate-limits-with-explicit-loss-semantics.md) and the canonical [rate-limit bucket design](./system-architecture-redis-cache.md#rate-limit-bucket-design), using one privacy-preserving opaque stable subject hash per individual subject and declared TTL, admission, memory, reset, and unavailable-store behavior. Route differentiation may be part of the subject/policy scope, but modulo collision pools and request-derived shards must not couple unrelated clients or multiply allowance.
  - Filter ordering is normative: gateway header trust and client-IP canonicalization must run before gameplay admission checks, handshake classification, and any rate-limit subject derivation. For trusted TCP Proxy bridge requests, the canonical HMAC subject helper defined by the [rate-limit bucket design](./system-architecture-redis-cache.md#rate-limit-bucket-design) receives the canonicalized client IP derived from authenticated `X-Proxy-Client-IP`, not the proxy pod/node source IP. Redis stores only the versioned opaque `subjectHash`, never the raw canonicalized IP or HMAC key material. If authenticated client-IP promotion fails, the handshake must fail closed as `POLICY_DENY` rather than falling back to the proxy hop address in player-facing environments.
  - Tenant-aware edge rate limiting for gameplay uses the connect-token contract above. `/ws/game/**` rejects gameplay handshakes without a connect token (`403`) unless the request is the authenticated TCP Proxy bridge exception described above.
  - Target Game Session behavior enforces ordinary **per-session and per-command** limits with an in-process token bucket owned by the current session front end. A restart or takeover must atomically reserve from the binding's shared bounded cumulative handoff budget before initializing the replacement bucket, so changing owners cannot reset the remaining allowance. Coarse shared abuse windows may use Cache/Rate-Limit Redis outside the per-command fast path, but target command-rate policy never uses Coordination Redis or performs a datastore operation for every command solely for limiting. The current `ratelimit:<sessionId>` Redis-backed limiter remains legacy drift and must follow the documented drain-or-versioned-prefix migration before target helpers consume the family. See [ADR 0034](./decisions/adr-0034-layered-abuse-controls-without-attacker-triggered-account-locks.md) and [ADR 0087](./decisions/adr-0087-isolated-subject-rate-limits-with-explicit-loss-semantics.md).
- **WebSocket vs HTTP semantics**
  - Spring Cloud Gateway’s Redis-backed `RequestRateLimiter` is applied to **connection establishment and discrete HTTP requests**, not to every WebSocket frame. This prevents Telnet and WebSocket gameplay traffic from being throttled as if each frame were a separate HTTP call.
  - Once a WebSocket connection is established to `/ws/game/**`, ongoing gameplay messages traverse the connection without additional gateway-level rate limiting; downstream services (especially Game Session Service) enforce per-session and per-command safety.
- **Gameplay WebSocket handshake errors**
  - HTTP `429` responses from gameplay routes indicate edge rate/connection policy boundaries.
  - HTTP `503` responses from gameplay routes represent backend unavailable outcomes.
  - HTTP `403` responses indicate handshake denial by policy or trust boundaries (for example internal-only listener, mTLS/client-identity mismatch, explicit route policy deny, or connect-token admission failures when token enforcement is enabled).
  - HTTP `401` is not part of the normal gameplay-route handshake taxonomy. If observed, treat as policy drift/misconfiguration and investigate.
  - Client retry/backoff handling is canonical in [Reconnection Strategy](./system-architecture-reconnection.md#http-handshake-failures-on-ws-game).
  - Gateway must emit a machine-readable handshake error class for all non-101 upgrades (for example response header `X-Firemud-Handshake-Error-Class` and mirrored structured log field) using the bounded set:
    - `POLICY_PRESSURE`
    - `BACKEND_UNAVAILABLE`
    - `CONNECT_TOKEN_MISSING`
    - `CONNECT_TOKEN_EXPIRED`
    - `CONNECT_TOKEN_REPLAYED`
    - `CONNECT_SCOPE_MISMATCH`
    - `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`
    - `CONNECT_TOKEN_REJECTED`
    - `POLICY_DENY`
    - `PROTOCOL_MISMATCH`
    - `INTERNAL_ERROR`
  - The specific connect-token classes above should be used when the gateway can classify the failure. `CONNECT_TOKEN_REJECTED` covers an unsupported or unregistered carrier/route and malformed, signature-invalid, missing-claim, wrong-audience, or otherwise rejected token content outside the narrower classes above. It must also carry exactly one bounded reason: `unsupported_carrier_or_route` for an unsupported, unregistered, duplicate, or ambiguous carrier/route, or `invalid_token_content` for a selected supported carrier whose token content is rejected. `CONNECT_SCOPE_MISMATCH` may also appear as a post-handshake Game Session admission error when the verified connect context no longer matches the selected gameplay target before `PLAY` completes. `POLICY_DENY` is reserved for other trust-boundary and route-policy denials after carrier/token classification is no longer the deciding factor.
  - For `CONNECT_TOKEN_REJECTED`, Gateway emits the bounded reason in `X-Firemud-Handshake-Error-Reason` and the mirrored structured log field. No other rejection reason is valid for this class, and clients must not infer the reason from HTTP status alone.
  - Reconnection/client policy must key on handshake error class first and HTTP status second.
- **Edge vs core responsibilities**
  - The **TCP Proxy Service** enforces **connection-level and per-socket safety** for Telnet clients: idle timeouts, per-IP connection caps, buffer depth limits, and basic abuse heuristics. It relies on Spring Cloud Gateway and Game Session Service for cross-tenant and content-aware rate limiting.
  - **Spring Cloud Gateway** enforces **request- and connection-creation limits** using the Cache/Rate‑Limit Redis instance configured via `FIREMUD_REDIS_CACHE_HOST` and `FIREMUD_REDIS_CACHE_PORT`, protecting backend services from floods of new connections or HTTP calls.
  - The **Account Service** exclusively owns credential-attempt throttling across transport paths. The **Game Session Service** applies **fine-grained post-authentication gameplay limits** (per-session command rates and region-level protections) so in-game abuse is handled close to business logic without duplicating credential policy.

This layered model avoids over-counting Telnet and WebSocket frames while still protecting the platform: the gateway guards connection churn and HTTP floods, the TCP Proxy Service governs raw Telnet behavior, and the Game Session Service enforces gameplay-specific policies.

## Multi-Tenancy at the Gateway

Spring Cloud Gateway is not the owner of tenant isolation policy or authorization decisions: backend services derive, validate, and enforce tenant access as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md) and [Authentication & Authorization](./system-architecture-authentication.md).

If gameplay execution is sharded across multiple Game Session instances, that sharding boundary is owned by the Game Session layer and its coordination mechanisms. The gateway remains a protocol edge and must not introduce an independent shard-routing plane unless explicitly designed and implemented as part of a dedicated sharding/routing architecture update.

- Tenant identity (`tenantId`) is derived and enforced by backend services as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md), not by Spring Cloud Gateway.
- Gameplay flows may include tenant markers such as:
  - `X-Proxy-Game-Instance-Id` / `X-Proxy-Tenant-Id` inputs and the coherent `X-World-Slug` / `X-Realm-Slug` / positive `X-Pointer-Version` bundle on the TCP Proxy → Gateway hop when the proxy supplies server-owned bootstrap metadata or future explicitly selected transport hints. Gateway strips raw `X-Proxy-*` names, generates canonical `X-Game-Instance-Id` / `X-Tenant-Id`, and validates/forwards the all-or-none routing bundle only after authenticating the TCP Proxy identity (see [Header Trust Model](#header-trust-model)).
  - Session and tenant context inferred by the Game Session Service from the `LOGIN` flow and Redis session keys.
- Spring Cloud Gateway strips bridge-only raw `X-Proxy-*` inputs, generates and forwards only gateway-owned canonical headers after trust checks, and does not derive tenant authority or route from untrusted values:
  - Must not derive `tenantId` from hostnames or URL paths.
  - Must not treat forwarded tenant/session hints as trusted without validation by the owning service (for example, Game Session validating tenant/session context against Redis).
  - Must not apply tenant-aware authorization rules on behalf of backend services.
- All tenant isolation, quotas, and policy enforcement (for example, per-tenant session limits or resource quotas) are implemented in domain services such as the Game Session Service and Account Service, following the rules in [Multi-Tenancy](./system-architecture-multi-tenancy.md).

## TLS Termination for Gateway

- **Browser / Web clients** – External `https://` / `wss://` connections terminate at the Internet-facing load balancer. The load balancer forwards `http://` / `ws://` traffic to Spring Cloud Gateway pods in the DMZ. The gateway then routes traffic to backend services using in-cluster `http://` and `ws://` targets (typically on port `8080`), while backend services communicate with each other over mTLS-protected gRPC as described in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway).
- **Telnet clients** – Telnet (plaintext) or Telnet-over-TLS connections terminate at the TCP Proxy Service. The proxy then connects to the canonical gameplay route `/ws/game/**` on Spring Cloud Gateway by dialing the Gateway’s internal-only WebSocket mTLS listener over `wss://` with mutual TLS. Spring Cloud Gateway forwards gameplay to the Game Session Service over the same WebSocket route (`/ws/game/**`). Detailed certificate and environment variable mappings are documented in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway) and [Protocol Bridging](./system-architecture-protocol-bridging.md#websocket-bridge-configuration).

## Management Plane Security

The following are target-state acceptance criteria, not current guarantees. The implementation-status section records that route-mutation isolation, validation, and startup boundaries are not yet enforced.

- Where explicitly enabled for local, development, or test use, REST and gRPC route-mutation endpoints must be **only on internal network surfaces**, never via player-facing ingress. Player-facing deployments must leave those mutation components and endpoints disabled or absent; the production operator surface is diagnostics and health inspection only.
- In Kubernetes, these endpoints must be reachable only from inside the cluster or a dedicated admin network segment via `ClusterIP` Services, private ingress, and `NetworkPolicy` rules; the public Service/Ingress must be limited to HTTP and WebSocket data-plane traffic.
- Authentication and authorization for diagnostics and any dev/test mutation endpoints must be enforced at the gateway boundary: operator tooling must connect using mutual TLS (mTLS) credentials resolved from the canonical `operator-credential-binding/v1` record. `SECRET_BACKED` and `CERT_MANAGER` bindings use client certificates with `clientAuth` EKU (cert-manager under ClusterIssuer `firemud-ca-issuer` is the `CERT_MANAGER` example), while `WORKLOAD_IDENTITY` uses the provider-projected client identity and trust bundle without a substitute Secret. Only credentials matching the record's exact binding type and approved operator identity may invoke approved internal operations. JWT-based admin roles apply to product/admin APIs behind the gateway, but gateway-owned diagnostics must not rely on downstream services for authorization. Production route changes use the separately accepted declarative deployment workflow, not these runtime mutation methods. Implementation details and the recommended internal-only exposure model are documented in the [Spring Cloud Gateway service README](./microservices/spring-cloud-gateway/README.md#management-plane-security).

### Dynamic Route Override Lifecycle

Player-facing routing has one authority: the version-controlled declarative route catalog released with environment-bound service endpoints. Route changes converge through the normal reviewed deployment and rollback workflow. Emergency routing uses an expedited declarative rollout or a predeclared bounded switch between approved targets.

Dynamic mutation is an explicitly enabled local/dev/test capability only, subject to the following target-state acceptance criteria:

- mutation components and endpoints are absent or disabled by default, and player-facing startup fails if they are enabled;
- overrides are process-local, non-durable, visibly non-convergent, and reset to baseline on restart;
- protected baseline, management, authentication, gameplay, and header-trust routes cannot be replaced or shadowed;
- route IDs, destinations, predicates, and filters are allowlisted;
- mutation endpoints remain outside player-facing ingress and use trusted test/operator authorization; and
- audit records include actor, authorization basis, before/after values, outcome, and correlation identity.

Persistence, multi-pod convergence, audit, and readiness predicates are not sufficient on their own to promote this developer API. A production runtime-routing control plane requires a separate decision covering versioned desired state, validation, staged activation, expiry, rollback, conflict handling, recovery, and fail-closed behavior.

## Observability

All gateway gRPC endpoints are instrumented with the shared `LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor`.
Gameplay WebSocket handshake and bridge paths emit the bounded connection metrics described below. By default, tracing for WebSocket sessions records **connection-level metadata only** (for example, route ID, tenant, session identifiers, and basic timing) without full text payloads.
Full request/response payload tracing for WebSocket sessions is treated as an **opt‑in diagnostic mode**: it is disabled in player‑facing environments and, when enabled for debugging, must use aggressive sampling and redaction as described in [Logging & Monitoring](./system-architecture-logging-monitoring.md).

Gateway must expose a small set of low-cardinality WebSocket meters so incidents can be triaged without relying on logs alone:

- `gateway.websocket.closes{reason="<reason>",subreason="<subreason>"}` – counter incremented whenever the gateway closes a public gameplay WebSocket, with `reason` drawn from the bounded close taxonomy (`logout`, `session_replaced`, `service_restart`, `idle_timeout`, `policy_violation`, `internal_error`, `backend_unavailable`) and `subreason` drawn from the bounded subreason taxonomy (`user_logout`, `takeover`, `gateway_restart`, `admin_termination`, `edge_backpressure`, `none`). The authenticated TCP Proxy bridge has the separate `gateway.tcp_proxy_bridge.closes{reason="<reason>",subreason="<subreason>",bridge_shutdown_class="<value>"}` counter, where bridge-only `bridge_shutdown_class` is restricted to `planned_drain`, `valid_upstream_close`, or `unattributed_failure`; it is not emitted for public WebSocket closes. For the authenticated bridge, clean `1012/service_restart` is `planned_drain`; every other valid authenticated top-level close is `valid_upstream_close`; and only absent, invalid, or otherwise unattributable close metadata is `unattributed_failure`. The top-level reason remains lifecycle authority; only absent or invalid close metadata maps the Telnet client to observation-specific `backend_unavailable`. See [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons).
- `gateway.websocket.handshake.rejected{route="<route>",status="<status>",error_class="<category>"}` – counter incremented when the gateway rejects a gameplay-route handshake (`/ws/game/**`), with bounded `status` values (`429`, `503`, `403`, `401`, `426`, and a small set of configured policy statuses) and bounded `error_class` values from the handshake taxonomy above.
- `gateway.websocket.slow_client_closes` – counter incremented when the gateway closes a WebSocket because the client is not reading fast enough (send timeout / outbound buffer pressure). This meter is a subset of `gateway.websocket.closes{reason="policy_violation"}` and exists so operators can distinguish slow-client/network backpressure from other policy enforcement without introducing high-cardinality labels.

## Internal gRPC Communication

Internal services communicate directly with each other over **gRPC**.
Spring Cloud Gateway does not handle these calls. Each service discovers
its peers via Docker or Kubernetes DNS and connects using the service name.
This approach minimizes latency and matches the protocol table in the
[System Architecture Overview](./system-architecture-overview.md#communication-flows).

---

## Dev vs. Prod Configuration

| Environment | Route Target Format | Discovery Mechanism |
| --- | --- | --- |
| Dev | `http://service:8080` | Docker Compose DNS |
| Prod | `http://service.namespace.svc.cluster.local:8080` | Kubernetes DNS |

Spring profiles are deployment and test plumbing only; they do not define player-facing route authority. Target-state baseline routing targets are defined by the released declarative `routes.yml` catalog imported through `application.yml`; environment-variable endpoint substitution may specialize that declared catalog. Dynamic route overrides never become player-facing route authority.

---

## Gateway Network Surfaces

The following table summarizes the main network surfaces exposed or used by Spring Cloud Gateway. Detailed TLS and authentication requirements are documented in [Security Architecture](./system-architecture-security.md) and the Spring Cloud Gateway service design.

| Surface | Direction | Protocol(s) | Typical Port(s) | Auth / TLS Expectations |
| --- | --- | --- | --- | --- |
| Public player/admin ingress → Spring Cloud Gateway | Inbound | `HTTP(S)`, `WS(S)` | Load balancer ports (for example, `80`/`443`) | TLS terminates at the Internet-facing load balancer; gateway receives `http://` / `ws://` as described in [TLS Termination for Gateway](./system-architecture-security.md#tls-termination-for-gateway). |
| TCP Proxy Service → Spring Cloud Gateway gameplay route (certificate-bound profiles) | Inbound (internal only) | `WS(S)` | Gateway internal mTLS port (for example, `8443`) | The internal-only listener applies the selected exclusive certificate-bound trust profile after certificate chain/client-auth checks: exact URI SAN, exact DNS SAN, or one leaf SHA-256 fingerprint. The gateway promotes `X-Proxy-*` inputs only after that profile succeeds. The host in `GATEWAY_WS_URL` must match the gateway certificate’s SANs. |
| TCP Proxy Service → Spring Cloud Gateway gameplay route (`development_cidr` only) | Inbound (development/test only) | `WSS` | Gateway internal TLS port (for example, `8443`) | The development-only listener uses TLS without client authentication and applies only the exact configured source-CIDR predicate; it does not claim certificate identity, is prohibited in player-facing environments, and never authorizes a plain-transport bridge. The host in `GATEWAY_WS_URL` must match the gateway certificate’s SANs. |
| Spring Cloud Gateway → backend services | Outbound | `HTTP`, `WS` | `8080` (typical) | In-cluster hop; protected by NetworkPolicies and namespace boundaries. Backend services handle JWT validation/authorization as applicable; gameplay traffic remains on the `/ws/game/**` WebSocket route to the Game Session Service. |
| Spring Cloud Gateway management plane (REST/gRPC) | Inbound (internal only) | `HTTP(S)`, gRPC | `8080` (REST), `6565` (gRPC) | Exposed only on internal surfaces (`ClusterIP` / private ingress); management operations require mTLS client certificates and are authorized at the gateway boundary (not delegated to downstream services). |

---

## Related Documentation

- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Spring Cloud Gateway Service Details](./microservices/spring-cloud-gateway/README.md)

---

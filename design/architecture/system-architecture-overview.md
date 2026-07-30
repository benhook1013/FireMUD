# FireMUD System Architecture: Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

## Normative Target Contract

FireMUD's target state is a shared multi-tenant platform with Account Service authoritative for global identity, authentication, tenant membership, subscriptions, and runtime entitlements; domain services authoritative for their own state; Game Session authoritative for gameplay admission, sessions, and runtime coordination; and Logging & Admin the external operator write ingress. Gameplay admission requires a server-resolved realm target, caller-bound membership/grant authority, current entitlement and authority-generation evidence, and single-use connect-token replay protection. Global roles do not create membership or gameplay authority, and internal service calls use exact typed contracts plus workload identity. The implementation status below records where the current runtime has not yet reached this contract.

Entitlement scope follows the product contract rather than payer identity: hosting and game entitlements that control a tenant runtime are tenant-scoped, while explicit account-scoped purchases, grants, and donations remain global account records without a fabricated `tenantId`. Applying an account-scoped grant to a tenant-scoped feature requires an explicit tenant binding or consumption record.

## Implementation Status

This overview is target-state canonical; implementation coverage is partial and must not be inferred from the target tables alone. The current operator-control boundary is:

- Runtime feature-flag overrides and scoped tick `PauseTicks`/`ResumeTicks` have implemented Logging & Admin forwarding paths, with Game Session retaining runtime and coordination mutation authority. These owner mutations are not canonically supportable or externally enableable until their action-family schemas and shared cross-language `mutationDigest/v1` golden vectors exist and are consumed by every participant.
- Admission-pointer reads, audit, and preparation have implemented Logging & Admin paths. Admission-pointer compare-and-set/cutover mutations, including explicit `CLOSED` handling, remain target-state and must not be read as a current implementation claim.
- Public administrative report persistence is currently unavailable. The unsafe Logging & Admin HTTP controller and `/api/admin/reports/**` Gateway route were removed because canonical authorization and live reference-validation checks are not implemented. The internal report gRPC contract remains separate behind its existing workload boundary; the target player-bootstrap submission route is also unavailable.
- Moderation action persistence and audit are currently gated/unavailable: `POST /moderation/actions` and `ApplyModerationAction` currently persist neither the `moderation_actions` record nor audit evidence and do not perform owner-side enforcement. The separate `EvaluateModerationPolicy` read remains live at the Game Session and Social & Groups owner boundaries (`GAMEPLAY_ADMISSION` and `CHAT_SEND`), with applicable high-risk decisions failing closed when fresh policy evidence is unavailable. Target state retains durable moderation action-input/audit persistence and adds versioned policy snapshots, monotonic invalidations, and durable transactional-outbox propagation to enforcement owners; that propagation and broader enforcement remain missing.
- Quota override is a hypothetical target overlay on Account entitlements; no current OpenAPI route or Account owner mutation contract exists.
- Broader tick/coordination remediation beyond pause/resume is hypothetical target coverage; no current OpenAPI route or Game Session owner RPC exists.
- Game Session's current `/sessions*` lifecycle routes are owner-local hooks, not a complete external Logging & Admin ingress family. The target operator path is Gateway ingress to Logging & Admin, then forwarding to Game Session owner RPCs; do not treat the current owner-local routes as current external coverage.
- Human operator authorization requires the current `control-ui` identity. Unattended automation uses Account's typed, versioned automation-policy authorization bound to the exact Logging & Admin workload mTLS identity, with no human identity or end-user token.
- Account security convergence remains incomplete: authority-generation enforcement is not yet complete across all token/session validation paths; issued-token registration is not yet complete across all issuance paths; scoped-role population is not yet complete for every tenant-scoped authorization projection; and tenant switching is not yet a complete implemented workflow. These are implementation gaps, not alternate target-state authority rules.
- The Telnet edge chain (Telnet client -> Telnet edge proxy with PROXY protocol -> TCP Proxy Service -> Spring Cloud Gateway with mTLS) and related certificate wiring are being rolled out incrementally. For current rollout and configuration details, see the [TCP Proxy Service design](./microservices/tcp-proxy-service/README.md), [Telnet Path Degraded Runbook](./system-architecture-telnet-degraded-runbook.md), [Security Architecture](./system-architecture-security.md), and [Protocol Bridging](./system-architecture-protocol-bridging.md).

---

## Architecture Decisions (Canonical)

The documents linked from this overview describe the target-state design, but the following decisions are treated as **canonical contracts** that other architecture docs must align to.

- **Gateway responsibility model:** Spring Cloud Gateway is the single ingress for HTTP and WebSocket traffic and the central place for routing, coarse route gating, rate limiting, and observability. It is not the platform’s authorization authority: JWT validation and role/tenant authorization are performed by the consuming meta/control services using shared middleware and the Account Service JWKS.
- **Gameplay sharding scope (edge vs Game Session):** Spring Cloud Gateway does not own a gameplay shard routing plane. `/ws/game/**` routes to a stable Game Session service surface; any lease ownership and region sharding are internal to the Game Session layer and its coordination mechanisms. See `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md` for the canonical scope decision.
- **Gameplay session routing inside Game Session:** Connected gameplay sockets attach to a stable Game Session session front-end pod, while region-scoped tick execution remains fenced to the current lease owner for `<tenantId, gameInstanceId, regionId>`. Session front-ends may forward region-owned work over internal gRPC, but only the lease owner may mutate tick coordination state. See `design/architecture/decisions/adr-0011-gameplay-session-front-end-and-region-execution.md`.
- **Non-edge failover contract:** Per [ADR 0013](./decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md), meaningful live gameplay state must be externalized into durable or shared coordination systems rather than hidden in single-process memory. When the edge socket, healthy same-type replacement capacity, and shared authority remain available, an ordinary Game Session or Game Logic instance loss must recover behind the established edge connection without fresh `LOGIN`/`PLAY`. The ordinary functional target is 10 seconds and the hard hidden-recovery cutoff is 30 seconds, after which affected sessions close with `1013/backend_unavailable`. In-flight bytes or one ambiguously delivered command may still be lost under the at-most-once edge contract.
- **Multi-cluster gameplay sharding scope:** FireMUD target state assumes single-cluster gameplay execution per deployment, with scale via lease-based in-cluster Game Session rebalancing. Cross-cluster gameplay sharding is out of scope until a dedicated end-to-end design package is accepted. See `design/architecture/decisions/adr-0008-multi-cluster-gameplay-sharding-scope.md`.
- **Lease moves and reconnect behavior:** The platform favors **close-and-reconnect** over mid-connection migration at the edge contract. The edge contract does not define a distinct “shard handoff” close category; client-visible outcomes remain limited to the standard close taxonomy (for example `backend_unavailable` after the bounded non-edge recovery window). Ordinary lease moves remain internal and fenced, while an attached Game Session process loss uses ADR 0013's bounded upstream rebind whenever its qualifying conditions hold. If a future design introduces explicit handoff semantics at the edge, it must be defined as a dedicated design update and integrated into the gateway + protocol bridging contracts (see `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`).
- **Quotas and entitlements source of truth:** Subscription entitlements and plan-driven quota values are owned by the Account Service (via `GetTenantEntitlementsForRuntime(tenantId, requestId)`). Logging & Admin provides dashboards, audit trails, and operator UX; any operator overrides must be represented as an overlay that is merged into the Account Service entitlement contract so enforcement points consume a single canonical view.
- **Operator control-plane availability split:** Logging & Admin may depend on Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, and Alertmanager for observability-heavy experiences, but core operator actions such as moderation, feature-flag requests, quota overrides, admission control, and tick-remediation controls must remain available when those backends are degraded once their owner contracts exist. Logging & Admin owns the operator UX, request validation, and audit trail for these actions, while the owning domain services remain the only components allowed to mutate runtime or policy state.
- **Operator write ingress policy:** External operator-initiated mutating actions for moderation, runtime feature-flag overrides, quota overrides, admission control, and tick remediation must enter through Logging & Admin APIs via Gateway so validation and audit capture are uniform. Edge-routable admin APIs exposed directly by Account, Game Session, Social & Groups, and Game Design are limited to read operations plus narrowly scoped service-owned workflows that are explicitly documented as bypass-safe; absent that explicit designation, external writes must not bypass Logging & Admin.
- **Bypass-safe workflow allowlist policy:** Service-level docs may not invent new classes of externally writable bypass-safe workflows on their own. A workflow is bypass-safe only when the overview or responsibility matrix explicitly names it as a bypass-safe class or explicitly delegates that class to a service-owned contract. Game Design creator writes for tenant-scoped assets and templates are delegated to the Game Design service contract as domain-local creator workflows. Otherwise, external writes are denied by default and require an architecture-doc update in the same change.
- **Durable async contract:** Best-effort edge hints may use internal gRPC event sinks, but durable cross-service business events and saga updates must use the transactional outbox/background-worker pattern described in `design/architecture/system-architecture-transactions.md`. High-level docs must not imply an unspecified shared event bus.
- **SQL persistence stack:** For SQL-backed services, FireMUD’s canonical persistence stack is `jOOQ + Flyway`. Flyway remains the schema authority, explicit SQL generation/execution is the runtime model, and repo-wide Hibernate/JPA platform support has been removed rather than preserved as a co-equal path.
- **Durable control-plane workflow stack:** For long-running control-plane workflows that need restart-safe execution, durable timers/waits, and operator-visible lifecycle, FireMUD’s target workflow substrate is Temporal. The shared `common-saga` layer remains only for short synchronous orchestration that does not need durable workflow behavior, and gameplay runtime/tick execution remains explicitly out of scope for Temporal.
- **Moderation propagation contract:** Logging & Admin owns target-state gameplay/chat moderation policy persistence, evaluation, definitions, and audit. Until versioned propagation is implemented, its live `EvaluateModerationPolicy` read is the only enforcement input; a local snapshot is never a competing policy authority. In the target state, Game Session consumes the resulting `GAMEPLAY_ADMISSION` decision at gameplay admission, and Social & Groups consumes the resulting `CHAT_SEND` decision at chat send; applicable high-risk decisions fail closed when fresh policy evidence is unavailable. Enforcement owners must consume a canonical versioned snapshot contract. Every propagation payload must include `{tenantId, policyScope, policyVersion, issuedAt, eventId}`, invalidation must be monotonic per `{tenantId, policyScope}`, and enforcement services must support both push invalidation and pull-on-miss refresh from Logging & Admin. If a local snapshot diverges from the authoritative version, the consumer invalidates it and refreshes rather than choosing between divergent values; `gameplay_ban` and `chat_ban` enforcement is fail-closed when no fresh policy snapshot can be obtained within the bounded staleness window; `chat_mute` may temporarily reuse the last valid snapshot within that same window but must fail closed once the window expires. Versioned snapshot/event propagation and broader enforcement remain the missing implementation work; target state requires `POST /moderation/actions` to persist policy input and audit evidence without directly enforcing a decision. Currently, `POST /moderation/actions` and `ApplyModerationAction` are gated/unavailable and persist neither the `moderation_actions` record nor audit evidence. The bounded staleness window is a deployment-configured maximum snapshot age sourced from one canonical environment-level configuration contract shared by all enforcement services; Game Session and Social & Groups must not use divergent values in the same environment.
  - Until a dedicated moderation-configuration contract exists, the canonical source for this deployment-level moderation staleness value is the shared environment configuration contract referenced by both enforcement services. Service READMEs may document how they consume the value, but they are not the canonical source of truth for the value itself. When a dedicated moderation-configuration contract is introduced, this fallback note must be replaced with a direct reference to that contract in the overview and any affected service READMEs.
- **Edge-route exposure default:** Besides `/ws/game/**`, only explicitly allowlisted external HTTP surfaces under the canonical `/api/{service}/**` namespace and the published asset surface `/assets/**` are edge-routable through Gateway. Account, Game Design, Game Session control-plane APIs, Social & Groups admin APIs, and Logging & Admin are edge-routable under their allowlisted `/api/{service}/**` families; World Management, Entity Management, Game Logic, and Automation & Scripting remain internal-only unless a dedicated design update expands the allowlist.
- **Redis topology policy:** In all non-ephemeral environments, Coordination Redis and Cache/Rate-Limit Redis are separate deployments. Local development is treated as non-ephemeral and should run two Redis deployments to exercise role separation. Truly ephemeral CI/preview stacks may collapse roles into a single Redis instance only when explicitly documented and guarded as an ephemeral topology.
- **Coordination Redis ownership boundary:** Coordination Redis prefixes are owner-governed (Game Session for gameplay coordination prefixes such as `session:game:*`, `tick:*`, `timer:*`, `retry:*`, and `tick-executor-lease:*`; Account Service for `session:auth:*`; Automation & Scripting for `automation:*`), and non-owner participation is allowed only through documented shared-helper contracts. See `design/architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md`.
- **TCP Proxy identity canonicalization:** For Gateway header trust on the TCP Proxy → Gateway mTLS hop, URI SAN identity is canonical in production; DNS SAN is transitional and fingerprint pinning is break-glass only. See `design/architecture/decisions/adr-0010-tcp-proxy-identity-canonicalization.md`.
- **Canonical room-read fence:** Canonical room-state reads that combine occupancy from World Management with containment/entity presentation from Entity Management must be served under a shared tick/read fence emitted by World Management on `GetRoomSnapshot` and propagated unchanged by Game Logic during same-fence room-view composition. World and Entity responses must echo the fence token they satisfied; if either side cannot satisfy the requested fence, Game Logic must retry with a new world snapshot or fail the room-view refresh explicitly rather than composing mixed-tick state. See [Canonical Room Runtime Contract](#canonical-room-runtime-contract).
- **Canonical room-read fence interoperability minimum:** `LOOK` and same-fence room reads use one logical read-fence value for the room scope. The current live proto seam carries that logical value as World Management `worldSnapshotId` / `world_snapshot_id` and Entity Management `entitySnapshotId` / `entity_snapshot_id`; both values must compare equal for Game Logic to compose a canonical room view. The current adapter implementation still derives these fields from the room scope alone, so that live value is a scope marker rather than a committed mutation-advancing fence and must not be described as complete target-state behavior. The target contract uses the existing `roomSnapshotVersion` authority as one opaque or epoch-bearing committed fence: World Management emits it, and Entity Management returns the identical value only when its durable room-visible entity state satisfies that fence. The value advances after every durable mutation included in the room view, not only after cache expiry or a read. Future tick-ledger work may expose `asOfTickId` only through a coordinated proto and architecture update that preserves this single-fence invariant. Services must not silently substitute a newer or best-effort snapshot.
- **Game Session region-transition contract:** The session front-end owns connection-local sequencing and the character’s current execution-region pointer, while the lease owner owns region-scoped mutation rights. Cross-region actions are serialized by the session front-end under a monotonically increasing per-session sequence; region transition commits are atomic from the caller’s perspective only after the old region owner has acknowledged release, the new region owner has accepted the fenced command, and the session front-end has durably updated the execution-region pointer. Multi-region effects must designate one primary execution region or be decomposed into ordered fenced sub-operations; they must not issue concurrent unfenced writes to multiple region owners. See [Session Sharding & Routing](#session-sharding--routing).

## Core Architecture Principles

- **Microservices-based** domain-driven architecture with clearly separated responsibilities
- **Spring Cloud Gateway** serves as the unified HTTP and WebSocket entry point for all clients
- **TCP Proxy Service** accepts Telnet connections and upgrades them to WebSocket for the Gateway (in production this is typically fronted by a Telnet edge proxy that forwards to the TCP Proxy using PROXY protocol). The Proxy → Gateway hop is secured with mutual TLS; see [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway), [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-edge-proxy-and-proxy-protocol), and the TCP Proxy Service design’s **Implementation Status** section for environment-specific wiring details.
- **Consistent end-to-end WebSocket flow**: Telnet (TCP) → TCP Proxy Service (WebSocket upgrade) → Spring Cloud Gateway → Game Session Service
- **All application-level gameplay and admin traffic is routed through the Spring Cloud Gateway**, ensuring centralized **traffic routing, monitoring, and observability**. External admin and creator APIs are HTTP(S) surfaces routed through the Gateway allowlist; external domain gRPC is not an edge contract unless a dedicated design update explicitly adds it. Raw Telnet TCP terminates at the Telnet edge proxy and TCP Proxy Service before being bridged to the Gateway over WebSocket. See [Gateway Architecture](./system-architecture-gateway.md) for deployment details and stateless behavior.
  - Ordering and delivery guarantees for the combined Telnet and WebSocket path (FIFO where delivered, at-most-once semantics, and explicit drop conditions) are documented in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).
  - Backpressure and slow-client behavior across the TCP Proxy and WebSocket layers are described in [Protocol Bridging](./system-architecture-protocol-bridging.md#backpressure--slow-clients).
   > 🛑 **Gameplay login is fronted by the Game Session Service**, which handles the `LOGIN` command and binds sessions in Redis. It calls the Account Service to verify credentials and obtain JWTs/tokens. The Gateway simply forwards any admin tokens, and JWTs are validated by the admin or logging services themselves; gameplay protocol clients do not present JWTs, while first-party WebSocket clients use a short-lived edge connect token on `/ws/game/**` before `LOGIN`/`PLAY`. See [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow) for the full login flow.
- **Telnet clients maintain sticky TCP connections only to the TCP Proxy Service**, which buffers **active input** but **discards it across reconnects**
- **Reconnection logic is handled in layers** to preserve gameplay continuity
- **Synchronous internal service-to-service communication from the Game Session Service onward uses gRPC**, with strict schema enforcement and low latency. All calls are encrypted with **mutual TLS**; see [Security Architecture](./system-architecture-security.md). Asynchronous cross-service signaling (for example edge disconnect hints and saga/domain events) uses documented event contracts and idempotency keys.
- **Gameplay session bindings and tick coordination state are stored in Redis**, while durable Game Session control-plane metadata remains in PostgreSQL; this keeps the gameplay coordination path stateless at the pod level and enables full reconnect recovery
- **Game definitions and rules are data-driven and editable via tooling without redeploying code**; see the [Game Design Service documentation](./microservices/game-design-service/README.md).
- **Game Session Service orchestrates live game instances**, handling tick execution and runtime configuration
- [**Feature flags**](./microservices/game-design-service/feature-flags.md) are defined at design-time in the Game Design Service; Logging & Admin provides the operator UI for runtime toggles, while Game Session owns the runtime override state and enforcement during gameplay.
- **One active gameplay binding per identity key is enforced** — logging in from another client forcibly transfers control to the new session and terminates the old one. The canonical uniqueness key is `{tenantId, gameInstanceId, characterId}`, as defined in [Authentication & Authorization](./system-architecture-authentication.md#contract-decisions-normative).
- **Multi-tenant architecture shares infrastructure across games; per-game resource quotas prevent one tenant from exhausting cluster capacity.**
- **Admin and operations tooling communicates with Spring Cloud Gateway over the infrastructure management plane** for route and health management; no gameplay traffic flows over this path.

### Admin Entry Points and Traffic Surfaces

All external admin and creator tools access the platform through the **Spring Cloud Gateway**; Logging & Admin Service is never exposed directly at the network edge.

The architecture uses four canonical traffic surfaces:

- **Player traffic plane** – player-facing Telnet, HTTP, and WebSocket traffic, including `/ws/game/**`.
- **External admin/creator API plane** – Gateway-routed HTTP(S) APIs used by operator and creator tools on explicitly allowlisted `/api/{service}/**` routes.
- **Published asset delivery plane** – Gateway- or CDN-fronted `/assets/**` reads for published assets and exported content; this is a read-only external surface, not a creator/control-plane write family.
- **Infrastructure management plane** – internal Gateway management gRPC used for diagnostics and infrastructure health checks. Generic route mutation is dev/test-only and is not a player-facing production operator surface.

These names are normative and should be used consistently in service docs and diagrams to avoid conflating infrastructure control, external admin APIs, and player gameplay traffic.

- **Infrastructure management plane:** Admin/ops tools use an internal **gRPC management API** on the Gateway for diagnostics and health checks. Dev/test may explicitly enable bounded route-mutation methods; the target player-facing production contract uses the released declarative `routes.yml` catalog imported through `application.yml` and does not expose a generic runtime route editor. Until that catalog converges, the current implemented route authority remains `CanonicalGatewayRoutesConfiguration`. This path is for infrastructure and routing concerns only; it does not directly perform moderation or gameplay actions.
- **External admin/creator API plane:** Admin and moderation UIs talk to **Logging & Admin Service and other domain services via the Gateway**, using standard HTTP(S) APIs routed through Gateway’s configuration. Under [ADR 0047](./decisions/adr-0047-logging-admin-as-external-operator-write-ingress.md), mutating operator workflows for moderation, quota overrides, runtime feature flags, admission control, and tick or coordination remediation must enter via Logging & Admin so audit capture and request validation remain canonical; the owning domain alone validates domain facts and commits authoritative state. Direct domain-admin routes are for reads and explicitly documented bypass-safe workflows only. JWT validation and fine-grained authorization are performed by the consuming services.
- **Published asset delivery plane:** Published assets are read through the canonical `/assets/**` surface. This surface is edge-routable for release artifact delivery and caching, but it is not an admin/creator ingress path and does not authorize design-time mutation.
- **Internal-only dependencies:** Logging & Admin Service calls Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, and Alertmanager directly from the internal network for analytics and dashboards. These observability backends are **not** exposed to clients and are treated as internal, operator-facing dependencies of Logging & Admin.

`Bypass-safe workflow` has a specific meaning in this architecture: it is an externally callable domain-owned admin operation whose correctness does not depend on Logging & Admin-owned policy definition, cross-domain audit orchestration, or operator availability-split guarantees. Bypass-safe workflows must still emit domain audit records and must be explicitly named in the owning service contract before they are exposed directly through an edge-routable domain API.

Minimum service-doc requirements for a bypass-safe workflow designation:

- Name the exact route shape and method.
- State why the workflow is domain-local and does not rely on Logging & Admin-owned policy.
- State why it does not require cross-domain write orchestration.
- State the required audit behavior emitted by the owning service.
- State explicitly that the route is bypass-safe rather than merely edge-routable.

Examples:

- `GET /api/session/game-sessions/{id}` and `GET /api/account/accounts/{id}` are bypass-safe reads.
- `POST /api/design/validation-runs/{runId}:cancel` is bypass-safe only if the owning service explicitly documents it as a domain-local operation that does not depend on Logging & Admin-owned policy or cross-domain write orchestration.
- `POST /api/design/assets` and `POST /api/design/templates` are bypass-safe creator writes when implemented by Game Design as tenant-scoped domain-local asset/template workflows with Game Design-owned validation and audit behavior.
- Quota override is a hypothetical target family and, when an owner-backed route exists, must enter through Logging & Admin rather than becoming a direct bypass.
- `POST /api/session/tick-remediation/pause` is not bypass-safe and must enter through Logging & Admin.
- `POST /api/session/game-sessions/{id}/feature-flags/{flagKey}:toggle` is not bypass-safe because runtime feature-flag overrides are operator writes governed by the canonical operator action contract.
- `POST /api/account/accounts/{tenantId}/entitlements/overrides` is not bypass-safe because quota and entitlement overrides must remain canonical at Account through the Logging & Admin ingress path and audit workflow.

Canonical bypass-safe workflow allowlist at the architecture layer:

| Workflow class | Direct external bypass-safe write allowed? | Authority |
| --- | --- | --- |
| External admin reads on allowlisted routes | Yes | Owning service read contract |
| Game Design tenant-scoped creator writes for assets and templates | Yes | Game Design service contract |
| Domain-local cancellation or similar write on an allowlisted route | Only when the overview/matrix/service contract explicitly marks that workflow class as bypass-safe | Owning service contract plus architecture allowlist |
| Moderation writes | No | Logging & Admin ingress only |
| Runtime feature-flag overrides | No | Logging & Admin ingress only |
| Quota or entitlement overrides | No | Logging & Admin ingress only |
| Admission-pointer open, close, or retarget writes | No | Logging & Admin ingress; Game Session remains state owner |
| Tick remediation or coordination-control writes | No | Logging & Admin ingress only |

If a proposed external write does not fit one of the explicitly allowlisted classes above, it is not bypass-safe and requires an architecture update before implementation.

Canonical route-review examples:

| Proposed route | Classification | Canonical decision | Why |
| --- | --- | --- | --- |
| `GET /api/account/accounts/{id}` | External admin read | Allowed when Account documents the read contract | Edge-routable read on an allowlisted service |
| `POST /api/design/templates` | Game Design creator write | Allowed when Game Design documents tenant-scoped validation and audit | Domain-local creator workflow owned by Game Design |
| `POST /api/design/validation-runs/{runId}:cancel` | Domain-local write | Allowed only when Game Design explicitly documents it as bypass-safe | Can qualify only if it is domain-local and does not depend on Logging & Admin-owned policy or cross-domain orchestration |
| `POST /api/session/game-sessions/{id}/feature-flags/{flagKey}:toggle` | External operator write | Not allowed as a direct external route | Runtime feature-flag overrides must enter through Logging & Admin |
| Quota override family (no current route) | Coverage drift | No executable route is accepted | The future owner contract must remain canonical at Account and enter through Logging & Admin |
| `POST /api/session/admission-pointers/{worldSlug}/{realmSlug}:set` | External operator write | Not allowed as a direct Game Session route | Admission changes enter through Logging & Admin; Game Session validates and commits the fenced pointer mutation |
| `POST /api/session/tick-remediation/pause` | External operator write | Not allowed as a direct external route | Tick remediation must enter through Logging & Admin with Game Session as the state-mutation owner |

#### External Admin Traffic Split (Canonical)

| Traffic category | Edge entry point | Allowed direct domain route behavior |
| --- | --- | --- |
| External admin reads | Gateway allowlisted domain/admin APIs | Allowed when the route is edge-routable and the owning service documents the read contract |
| Game Design creator writes for assets and templates | Gateway allowlisted Game Design APIs | Allowed when the owning Game Design contract documents tenant access, validation, and audit behavior |
| External operator writes for moderation, quota overrides, runtime feature flags, admission control, and tick remediation | Logging & Admin APIs via Gateway | Direct domain bypass not allowed unless a future design update explicitly amends the operator write ingress policy |
| Internal service-to-service control APIs | Internal-only service contracts | Not an edge contract; does not traverse Gateway unless the contract is explicitly defined as Gateway-managed infrastructure control traffic |

See [Service Responsibility Matrix](./service-responsibility-matrix.md) for the matching `Admin/creator API participation (edge-routable domain APIs)`, `External operator read/preparation ingress`, and `External operator write ingress` responsibilities used when reviewing new route proposals.

#### Edge Exposure Policy (Canonical)

Gateway-routed external surfaces are intentionally narrow:

| Surface | Edge-routable status | Notes |
| --- | --- | --- |
| `/ws/game/**` gameplay WebSocket | Yes | Canonical gameplay entry point for web clients and TCP Proxy bridged Telnet sessions. |
| `/api/session/**` Game Session HTTP admin/control | Yes | Separate HTTP control-plane family from `/ws/game/**`; target-state region/tick mutations must still forward to the current lease owner under a fenced internal contract. |
| Logging & Admin admin APIs | Yes | Routed through Gateway allowlist only. |
| Account external admin/creator APIs | Yes | Routed through Gateway allowlist only. |
| Game Design external admin/creator APIs | Yes | Routed through Gateway allowlist only. |
| Game Session external admin/creator APIs | Yes | Routed through Gateway allowlist only. |
| Social & Groups admin APIs | Yes | Routed through Gateway allowlist only. |
| `/assets/**` published asset reads | Yes | Read-only published artifact delivery surface, typically gateway- or CDN-fronted. |
| World Management, Entity Management, Game Logic, Automation & Scripting direct APIs | No by default | Internal-only service surfaces unless a dedicated design update explicitly adds an edge route group and auth model. |

Network policies and ingress configuration must reflect this model:

- Only Gateway and TCP Proxy Service are reachable from external networks.
- Logging & Admin Service accepts traffic only from Gateway (and from observability systems where necessary), not from the public internet or VPN clients directly.

#### External Route Family Matrix (Canonical)

The public edge contract is defined at the route-family level before any service-specific path details:

| External family | Canonical classification | Direct external writes allowed? | Required auth/audit notes |
| --- | --- | --- | --- |
| `/ws/game/**` | Player gameplay WebSocket ingress | No admin/control writes on this family | Connect-token handshake plus in-band `LOGIN` / `PLAY`; gameplay audit follows gameplay/session contracts. |
| `/api/admin/**` | Logging & Admin external operator ingress | Yes, but only as Logging & Admin-owned operator workflows | Protected by `Authorization` header presence at Gateway and consuming-service JWT validation; operator intent and audit must be captured by Logging & Admin. |
| `/api/session/**` | Game Session HTTP admin/control family | External reads and explicitly documented bypass-safe workflows only; operator/control writes otherwise route through Logging & Admin or remain internal-only | Distinct HTTP family from `/ws/game/**`; mutable runtime/tick-region actions must still execute through fenced internal ownership and must not treat the edge prefix as blanket permission for undocumented writes. |
| `/api/account/**` | Account external admin/bootstrap family | Reads plus explicitly documented bypass-safe account/bootstrap workflows only | Consuming service owns JWT/bootstrap token validation and subject binding; quota/entitlement override writes still require Logging & Admin ingress. |
| `/api/design/**` | Game Design external creator/admin family | Reads plus the explicitly allowlisted bypass-safe creator-write classes for assets/templates and other architecture-approved domain-local workflows | Game Design owns tenant checks, validation, and audit for these creator workflows. |
| `/api/social/**` | Social & Groups external admin/read family | Reads and explicitly documented bypass-safe workflows only | Moderation/operator writes remain Logging & Admin ingress unless a future architecture update explicitly allows more. |
| `/assets/**` | Published asset delivery family | No | Read-only published artifact delivery surface. URLs are stable release artifacts, gateway- or CDN-fronted, and are not design-time mutation or tenant-admin ingress paths. |

Route-family rules:

- A public `/api/{service}/**` prefix does not make every service-local subtree public.
- Internal-only service-local paths such as `/internal/**` remain non-edge contracts even if current gateway YAML still forwards a coarse family prefix.
- Service docs must publish the externally supported route inventory for their family instead of leaving exposure implied by the gateway path matcher alone.

#### Infrastructure Management Plane Capability Matrix (Canonical)

The management-plane contract must be explicit so operator tooling does not assume unsupported mutating behavior in production-like environments.

| Gateway capability | Intended use | Current production-like status | Notes |
| --- | --- | --- | --- |
| gRPC/REST health and route inspection | Internal diagnostics and control-plane health checks | Supported | Internal-only network surface; mTLS-authenticated operator clients. |
| Version-controlled baseline route catalog + environment endpoint bindings | Canonical target-state route definitions and controlled deployment-time changes | Target-state; current implementation not converged | The released declarative catalog is the sole player-facing route authority after convergence; current code remains `CanonicalGatewayRoutesConfiguration` until then. |
| Dynamic route override APIs (runtime mutating overrides) | Ephemeral mock, fault-injection, and integration-test changes | **Dev/test only; not an initial production capability** | Production enablement requires a separate future decision; persistence, convergence, and audit do not automatically unlock it. |

This matrix is canonical for high-level architecture docs and must remain aligned with [Gateway Architecture](./system-architecture-gateway.md#dynamic-route-override-lifecycle).

#### Core Operator Action Backing Contracts (Canonical)

Core operator actions must not rely on observability backends for write success. Logging & Admin is the operator-facing entry point for these actions, but it is not allowed to become the runtime state owner for other domains.

**Target ADR 0048 contract:** Logging & Admin first durably records actor, reason, scope, mutation, one `controlPlaneRequestId`, and the canonical digest of the normalized request, then forwards that same identity/digest pair to the owner. The owner alone validates current authority and durably commits the domain mutation and idempotent result. Success is reported only after that commit is confirmed. A lost response or final audit-update failure is reconciled only with the same identifier and exact digest; it never invites an uncorrelated or differently shaped duplicate mutation. If the initial intent record is unavailable, the mutation fails before forwarding. Protobuf contracts may use the wire spelling `control_plane_request_id`, which maps directly to the canonical `controlPlaneRequestId`; it is not a second request identity.

Current implementation is narrower than that ADR 0048 target: runtime feature flags, admission-pointer reads/audit/version-upgrade preparation/read, and scoped tick pause/resume have Logging & Admin forwarding paths, but those mutation families remain unsupported/nonconformant and externally disabled until their action schemas and shared `mutationDigest/v1` golden vectors are published and consumed. Admission-pointer CAS open/close and prepared cutover remain target-state; current forwarding does not prove ADR 0048 durable intent, full authority-bound idempotency, owner-result recovery, or lease-fenced reconciliation. Game Session's current `/sessions*` lifecycle hooks remain owner-local and are not a current external Logging & Admin family; the target external path is Gateway to Logging & Admin and then Game Session owner RPCs. Moderation uses a different live read path: the separate `EvaluateModerationPolicy` read remains live at the Game Session and Social & Groups owner boundaries, while `/moderation/actions` and `ApplyModerationAction` are gated/unavailable and currently persist neither the `moderation_actions` record nor audit evidence or perform owner-side enforcement. The operator action is not a forwarded enforcement mutation; target-state moderation action persistence/audit, versioned propagation, and broader policy coverage remain missing. Quota and broader coordination remediation remain target-only.

| Operator action | Operator-facing entry point | Runtime/policy owner | Required write path | Required durable store(s) for success | Observability dependency allowed for write success |
| --- | --- | --- | --- | --- | --- |
| Moderation action (`gameplay_ban`, `chat_mute`, `chat_ban`) | Logging & Admin HTTP(S) APIs via Gateway | Logging & Admin owns target-state policy and audit; Game Session and Social & Groups own runtime enforcement | Current: `/moderation/actions` and `ApplyModerationAction` are gated/unavailable and persist neither the `moderation_actions` record nor audit evidence or perform owner-side enforcement; the separate live `EvaluateModerationPolicy` read supplies decisions at `GAMEPLAY_ADMISSION` and `CHAT_SEND`. Target: durably persist policy/audit, then add versioned snapshot/event propagation and broader coverage without turning the operator action into a direct enforcement mutation. | Logging & Admin PostgreSQL policy/audit state; target versioned projections in enforcement owners | No |
| Runtime feature-flag override | Target: Logging & Admin HTTP(S) APIs via Gateway; current implementation path is externally disabled pending schema/vector conformance | Game Session | Logging & Admin records audit and calls Game Session `ToggleFeatureFlag`/equivalent control API | Game Session PostgreSQL plus Logging & Admin PostgreSQL audit state | No |
| Quota override (hypothetical target; no current route or Account owner contract) | Hypothetical target: Logging & Admin HTTP(S) APIs via Gateway | Account Service canonical entitlement contract | Hypothetical target: Logging & Admin records audit and calls the Account control-plane API so the merged entitlement view remains canonical at Account | Account PostgreSQL plus Logging & Admin PostgreSQL audit state | No |
| Admission-pointer preparation (implemented but nonconformant); open, close, or retarget CAS (target-state) | Target: Logging & Admin HTTP(S) APIs via Gateway; current implementation path is externally disabled pending schema/vector conformance | Game Session | Current implementation records intent and calls preparation/read surfaces. Target: Game Session validates current catalog/runtime authority and compare-and-sets the pointer under the current version/fence, including explicit `CLOSED` handling. | Current: Logging & Admin intent/audit state plus implemented Game Session preparation state. Target: version-fenced Game Session admission-pointer state as well. | No |
| Tick remediation (`PauseTicks`, `ResumeTicks`) | Target: Logging & Admin HTTP(S) APIs via Gateway; current implementation forwarding is externally disabled pending schema/vector conformance | Game Session | Logging & Admin records audit and calls Game Session control APIs; direct Redis mutation is reserved for documented runbooks, not UI/API request handlers | Game Session PostgreSQL/control-plane state plus Logging & Admin PostgreSQL audit state | No |
| Broader tick/coordination remediation (hypothetical target; no current route or Game Session owner RPC) | Hypothetical target: Logging & Admin HTTP(S) APIs via Gateway | Game Session | Hypothetical target: Logging & Admin records audit and calls a Game Session owner control API; no direct Redis mutation | Game Session PostgreSQL/control-plane state plus Logging & Admin PostgreSQL audit state | No |

External admin tooling must not invoke alternate direct write routes for the actions in this table. If a future design allows a direct external mutation path for one of these actions, it must define equivalent audit, validation, and availability guarantees and update this table explicitly.

Game Session control-plane APIs exposed behind the Gateway terminate on the stable Game Session service surface. In the current implementation they are ordinary service/control-plane handlers; in the target session-front-end plus lease-owner model, any control-plane request that mutates region-scoped coordination or tick-owned state must be forwarded to the current lease owner under the current gameplay fence and the same `controlPlaneRequestId`. A stale owner or stale fence rejects the write; the same identifier may be retried against the current owner only when the owner returns a typed `REJECTED_BEFORE_COMMIT` result for `STALE_OWNER` or `STALE_FENCE`, with the request ID, exact digest, owner identity, observed fence, and durable operation/effect evidence proving that no mutation transaction accepted or committed the request. A timeout, transport failure, missing or inconsistent evidence, or any result after mutation acceptance is ambiguous and requires read-only reconciliation of the original request before replay. The externally routable API surface must not imply that a session front end can directly write region-owned coordination keys.

### Authentication Modes and Boundaries

FireMUD uses two complementary authentication modes that share a common identity model but differ in how they are presented by clients:

- **Gameplay sessions (players)**  
  - Players authenticate using the `LOGIN` command handled by the **Game Session Service**.  
  - Game Session delegates credential verification (including 2FA, external identity providers, and lockout rules) to the **Account Service**, which owns all credential and account-security decisions.  
  - On success, Game Session creates and maintains a Redis-backed gameplay session binding (tenant, character, tick-region context) and enforces one active binding per `{tenantId, gameInstanceId, characterId}`. Gameplay traffic is authenticated by this Redis session context rather than by browser-style JWTs sent on each message.

- **Admin and creator sessions (external admin/creator API plane)**  
  - External admin and creator tools authenticate via HTTP(S) using JWTs issued by the **Account Service**, which publishes JWKS and remains the source of truth for token semantics. Internal service-to-service gRPC remains direct and is not an external admin/creator edge contract unless a dedicated design update explicitly adds one.  
  - Internal services validate JWTs using a shared library and JWKS; they do not make ad-hoc token-parsing decisions.  
  - Spring Cloud Gateway forwards auth headers and may require the presence of the `Authorization` header on protected admin/creator routes, but it does not parse JWT claims or act as the authority for token validity, tenant scope, or role authorization.

This split keeps gameplay session management and tick-sensitive orchestration in the Game Session Service while ensuring that account security, token issuance, and policy remain centralized in the Account Service. See [Authentication & Authorization](./system-architecture-authentication.md) for detailed flows.

For operator writes, human issuance uses Account's typed `IssueHumanOperatorAuthorizationReference` path and binds the current `control-ui` identity, applicable tenant or target-tenant generation, and any required `privileged_control` predicate. Unattended automation uses Account's typed `IssueAutomationOperatorAuthorizationReference` path with `exact_mtls_workload_plus_versioned_automation_policy`, bound to the exact Logging & Admin workload mTLS identity, policy version, and tenant generation; the current automation branch is tenant-scoped only, carries no human identity or end-user token, and cannot authorize global-role or `privileged_control` action families. Account durably replays the original opaque reference or its bounded encrypted envelope for the reference lifetime plus retry/reconciliation window; redemption returns only an authorization projection. The domain owner, including Game Session for launch or runtime control, owns the durable mutation, idempotency identity, and outcome.

> 🔗 See [System Architecture Diagram](./system-architecture-diagram.md) and [System Context Diagram](./system-context-diagram.md).

---

## Reconnection Strategy

FireMUD supports a layered reconnect and session-recovery model:

| Layer | Responsibility |
| --- | --- |
| TCP Proxy Service | Buffers Telnet input; clears on disconnect |
| Spring Cloud Gateway | Stateless edge router; enforces close-code taxonomy and should keep client connections stable across ordinary non-edge backend failover where upstream rebinding succeeds |
| Game Session Service | Restores gameplay session from shared state and supports same-type instance takeover rather than process-local recovery |

Certain failures can affect only the Telnet path while web clients remain healthy, such as misconfigured TLS or mTLS on the TCP Proxy → Gateway WebSocket bridge or issues in the Telnet edge proxy/PROXY-protocol chain. When Telnet is degraded but WebSocket remains healthy, operators should consult the [Telnet Path Degraded Runbook](./system-architecture-telnet-degraded-runbook.md) alongside the general [Reconnection Strategy](./system-architecture-reconnection.md).

> 🔗 See [Reconnection Strategy](./system-architecture-reconnection.md) for full details on session resumption, reauthentication, and failure handling.

---

## Redis Roles, Keyspace Partitioning, and Data Ownership

Persistent, authoritative data and transient coordination state are deliberately separated so gameplay remains consistent under load:

- **Authoritative data** (accounts, world topology, entities, chat history, moderation records, and similar) is stored in PostgreSQL by domain-aligned services.
- **Coordination Redis** holds volatile, gameplay-critical structures (session bindings, tick queues, locks, timers) owned primarily by the Game Session Service and a small number of cooperating services using shared helpers.
- **Cache/Rate-Limit Redis** is used for best-effort caches and rate limiting by Spring Cloud Gateway, the TCP Proxy Service, and selected backend services; these keys use dedicated prefixes and must not share a deployment with coordination keys in player-facing environments.

Within Redis, keys are further partitioned by responsibility and, in production, can be mapped onto different logical databases or clusters:

- **Coordination and session keys (Coordination Redis)**  
  - Examples: gameplay sessions, tick-region leases, command queues, timers, and automation tick coordination.  
  - Canonical prefixes include (non-exhaustive):  
    - `session:game:*` – gameplay session bindings and takeover metadata (Game Session-owned).
    - `session:auth:token:<tokenHash>` – exact issued-token registry records projected by Account; separate Account-owned authority generations provide bulk revocation/version authority.
    - `tick:*` – tick queues, region scheduling, and pacing-related state.  
    - `timer:*`, `retry:*`, `tick-executor-lease:*` – tick coordination helpers owned by Game Session.
    - `automation:*` – automation and scripting coordination keys owned by Automation & Scripting Service (other services interact via gRPC APIs rather than writing these keys directly).

- **Cache and rate-limit keys (Cache/Rate-Limit Redis)**  
  - Examples: read-side caches, rate-limit counters, and quota tracking for non-critical flows.  
  - Canonical prefixes include (non-exhaustive):  
    - `cache:*` – general-purpose caches for derived data, short-lived lookups, and infrequently updated views.  
    - `ratelimit:*` – per-account or per-IP rate limiting for APIs, login attempts, and abuse prevention.

Coordination Redis and Cache/Rate-Limit Redis are **separate Redis deployments in all non-ephemeral environments** so cache or rate-limit spikes cannot degrade tick execution or session coordination. Local development runs both roles as separate deployments to exercise role separation. Truly ephemeral CI/preview stacks may collapse roles into a single Redis instance only when explicitly documented as an ephemeral topology.

See [Redis Architecture](./system-architecture-redis.md) and [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md) for the detailed key structure, multi-tenant key design, and allowed patterns, and the [Service Responsibility Matrix](./service-responsibility-matrix.md) for which services participate in each Redis role.

---

## Major Components and Their Roles

| Component | Purpose |
| --- | --- |
| **Web Clients** | Modern browser clients using WebSocket or HTTP to access the platform |
| **MUD Clients** | Traditional Telnet clients connecting via TCP, proxied into the system |
| **[TCP Proxy Service](./microservices/tcp-proxy-service/README.md)** | Accepts Telnet connections, buffers input, forwards over WebSocket; proxy-to-gateway mTLS secures the link |
| **[Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md)** | Handles WebSocket termination, routing, and observability; enforces coarse-grained admin access controls but does not own gameplay authentication or authorization decisions |
| **[Game Session Service](./microservices/game-session-service/README.md)** | Fronts gameplay login commands and session binding, manages player sessions, tick orchestration, runtime flags, input validation, and durable game-instance/runtime control metadata |
| **[Account Service](./microservices/account-service/README.md)** | Manages player accounts, credentials, authentication, and JWT/JWKS issuance; handles subscriptions and account-security ban state; publishes the canonical tenant entitlement/quota contract consumed by enforcement points |
| **[Entity Management Service](./microservices/entity-management-service/README.md)** | Handles all runtime entity data: players, NPCs, items, stats, and all inventories/containment (player inventory/equipment, containers, and items on the ground held in room-ground container entities keyed by room/instance ID) |
| **[World Management Service](./microservices/world-management-service/README.md)** | Owns maps, rooms, and tick region structure; provides room/region geometry and snapshots, plus authoritative runtime location/occupancy and mutable room-environment state (doors, hazards, and persistent ambient flags) |
| **[Game Logic Service](./microservices/game-logic-service/README.md)** | Executes gameplay mechanics; resolves actions deterministically, including movement/travel cost computation |
| **[Automation & Scripting Service](./microservices/automation-scripting-service/README.md)** | Triggers AI and scripted behaviors |
| **[Social & Groups Service](./microservices/social-groups-service/README.md)** | Manages chat, mail, guilds, and social features, and enforces chat mutes/bans at message send time based on moderation decisions from Logging & Admin Service. The long-term communication model distinguishes communication type, target/scope, recipient resolution, and per-recipient presentation rather than permanently treating all speech as one room-broadcast abstraction. |
| **[Logging & Admin Service](./microservices/logging-admin-service/README.md)** | Provides admin tools, metrics dashboards, and audit logs; owns moderation policy definition and audit trails that downstream services enforce; provides operator UX and auditing for quota/limit overrides that are represented as an overlay on Account Service entitlements |
| **[Game Design Service](./microservices/game-design-service/README.md)** | Authoring tool for designing and publishing game data; defines feature flags; publishing workflow copies data to runtime services |

> 🔗 See [Microservices Documentation](./microservices/README.md) for the full list of responsibilities and APIs.

## Communication Flows

| Flow | Protocol |
| --- | --- |
| Web Clients → Spring Cloud Gateway | WebSocket (wss) / HTTP (https) (public ingress) |
| MUD Clients → TCP Proxy Service | Raw TCP (Telnet) |
| TCP Proxy Service → Spring Cloud Gateway | WebSocket (`ws://` in local/dev; `wss://` with mTLS in production) |
| Spring Cloud Gateway → Game Session Service | WebSocket (`ws://` in-cluster) |
| Game Session Service → Other Microservices | gRPC (internal synchronous RPCs) |

✅ Internal synchronous RPC communication from the Game Session Service onward uses **gRPC** with strict schema enforcement.

## Asynchronous and Event Flows

The architecture also relies on explicit asynchronous contracts that are separate from synchronous request/response RPCs:

| Flow | Delivery semantics | Authority and safety rules |
| --- | --- | --- |
| TCP Proxy Service → Game Session Service `NotifyDisconnect` | At-least-once best-effort gRPC event sink | Advisory only; dedupe key `{proxyConnectionId, disconnectSequence}`; Redis + gameplay activity remain liveness source of truth. |
| Account/Domain services → Logging & Admin (audit/moderation/saga events) | Durable domain events/saga-step updates with at-least-once delivery | Event envelopes must carry a stable dedupe identity (for example `{tenantId, producerService, eventType, eventId}`), `occurredAt`, and a schema version; consumers must process idempotently. Logging & Admin is a control-plane consumer; runtime enforcement still occurs in owning domain services. |
| Game Session Service → Logging & Admin (session lifecycle/coordination health signals) | Streaming metrics/events | Used for operator workflows and automation; does not transfer gameplay state authority away from Game Session. |

Durable domain-event delivery in FireMUD is implemented via the transactional outbox/background-worker pattern described in [Transaction Strategies](./system-architecture-transactions.md#tick-adjacent-workflows-outbox-boundary), not via an implicit shared event bus.

---

## Data and State Management

- **Persistent data** (accounts, entities, rooms) is stored in PostgreSQL by domain-aligned services
- **Volatile gameplay coordination state** (gameplay session bindings, command queues, timers, retries, and region leases) is stored in Redis and coordinated by the Game Session Service, while Game Session control-plane/runtime metadata remains in PostgreSQL
- **Redis** is a **non-authoritative coordination buffer** — but **critical** for consistency, ticks, retries, and recovery
- **Tick regions** are shard-aligned in Redis to preserve atomicity
- **DMZ services (TCP Proxy Service and Spring Cloud Gateway)** remain stateless with respect to PostgreSQL; they may use **Cache/Rate-Limit Redis** and always emit logs/metrics, but do not own persistent domain tables.
- Runtime services do **not** directly read or write another service’s PostgreSQL tables; cross-domain access is through owned APIs/contracts.

### Canonical Runtime State Boundaries

The following boundaries are canonical for first-slice implementation and are intentionally restated here so teams do not infer competing ownership from lower-level docs.

| Runtime concern | Canonical owner | Canonical persistence / mutation boundary |
| --- | --- | --- |
| Gameplay session bindings, tick queues, timers, retry metadata, region leases | Game Session | Coordination Redis only; owner-governed prefixes and Lua-scripted mutation paths |
| Game Session control-plane runtime metadata | Game Session | Game Session PostgreSQL tables for game instances, pinned runtime version/script patch state, active feature-flag overrides, disconnect dedupe state, and other operator/audit-relevant control metadata |
| Account/session token control metadata | Account Service | Account PostgreSQL plus Account-owned Coordination Redis prefixes such as `session:auth:*` |
| Runtime entity state, inventories, item containment, room-ground containers | Entity Management | Entity PostgreSQL/runtime tables only |
| Runtime room occupancy/location and mutable room environment state | World Management | World PostgreSQL/runtime tables only |

Within Game Session-owned PostgreSQL metadata, the default write split is also canonical:

- The **session front-end** writes connection-scoped control metadata such as disconnect dedupe state, session recovery markers, and front-end-owned operator bookkeeping.
- The **lease owner / executor** writes region-owned runtime control metadata such as remediation state, fenced coordination health records, and region-scoped cutover or lease-transition bookkeeping.
- Shared records must not use unconstrained last-writer-wins updates. When both roles can touch the same logical record, the service contract must define a single-writer owner or a fenced compare-and-swap rule keyed by lease epoch or equivalent monotonic version.

Minimal canonical Game Session PostgreSQL write split examples:

| Record category | Canonical writer |
| --- | --- |
| Disconnect dedupe markers and session recovery breadcrumbs | Session front-end |
| Front-end-owned operator/session bookkeeping | Session front-end |
| Region remediation state and fenced coordination health records | Lease owner / executor |
| Region-scoped cutover or lease-transition bookkeeping | Lease owner / executor |
| Shared records touched by both roles | Only via an explicit single-writer owner or fenced CAS rule |

### Canonical Room Runtime Contract

- World Management is the sole owner of runtime room occupancy/location and mutable room-environment state.
- Entity Management is the sole owner of inventories, containment, and room-ground containers keyed by `RoomInstanceRef`.
- Game Session orchestrates movement and other tick-owned actions, but it must not maintain a competing authoritative occupancy index.
- The execution-region pointer held by the session front-end is session-local coordination metadata for fenced routing; it is not an authoritative source of room occupancy or world state.
- World Management emits the room-read correlation value on `GetRoomSnapshot`, currently as a deterministic scope marker represented by `worldSnapshotId` / `world_snapshot_id`; Game Logic owns the `ResolveLook` composition. The target implementation must replace that marker with the committed `roomSnapshotVersion` token once the cross-service commit protocol exists.
- Target-state World Management and Entity Management must either serve the allocated committed fence and echo it in their responses or reject the read as unsatisfied; they must not silently downgrade a target committed-fence read to best-effort snapshots.
- Once the target protocol exists, if either dependency rejects the committed fence, Game Logic may retry with a fresh room snapshot when doing so preserves caller ordering semantics; otherwise it must fail the room-view refresh explicitly. Mixed-tick best-effort joins are not allowed for canonical room state.

Committed-fence room reads are target-state behavior, not a claim about the current adapter. The committed `roomSnapshotVersion` contract is deferred until the platform has a defined protocol for fence allocation, propagation to every participating room/entity read, acknowledgement of the requested fence, and commit/publication of the resulting snapshot. Until that allocation/propagation/acknowledgement/commit protocol exists, current reads retain their deterministic scope markers and must be documented and tested as scope-marker behavior rather than as committed-fence proof. No caller may infer committed room consistency from the current marker.

Minimal canonical room-read sequence:

1. Game Session receives `LOOK` and delegates the gameplay read to Game Logic `ResolveLook`.
2. Game Logic requests `GetRoomSnapshot` from World Management and receives the current scope marker; the target protocol will allocate and return a committed `worldSnapshotId`/`roomSnapshotVersion` fence.
3. Game Logic calls Entity Management `ListRoomEntities` for the same room scope and receives `entitySnapshotId`.
4. Under the current implementation, the marker remains a scope/read correlation value. Under the target protocol, World Management and Entity Management either acknowledge and return the allocated committed fence or Game Logic rejects the mixed read as a room-fence failure.
5. The current adapter composes according to the aligned scope-marker contract and does not claim committed-fence proof. Under the target protocol, Game Logic composes one `LookResult` only when both downstream reads acknowledge the same committed fence, then Game Session renders and caches the transcript.

Rendered room-view caching is intentionally a Game Session concern rather than a World or Game Logic responsibility:

- Game Logic owns the authoritative structured `LookResult`.
- Game Session owns only the derived player-facing transcript and any short-lived reconnect/UI replay cache built from that result.
- The rendered cache is presentation-only and must never become the canonical answer for fresh gameplay reads that require authoritative recomputation.
- This cache pattern is intentionally narrow and should grow only as a built-in gameplay view cache subsystem for canonical platform read commands such as `LOOK`, inventory views, equipment views, or similar redraw-oriented surfaces. It must not become a generic cache for arbitrary game-specific commands or transient action acknowledgements.
- The target-state room view should remain explicitly sectioned so later slices can extend it without turning `LOOK` into an unstable mixed entity dump. The canonical sections are room/world snapshot data, exits, visible occupants, visible room-ground items sourced from Entity Management containment, and later optional overlays such as hazards or combat state.
- Room-ground containers are part of Entity Management's containment model but are conceptually attached to the authoritative `RoomInstanceRef`. `LOOK` should surface those visible room-ground items as part of the composed room view without expanding nested container contents inline by default.

Minimal interoperability requirements for the fence token:

- The current scope marker and the target committed fence are valid only within one `(tenantId, gameInstanceId, roomInstanceId)` room-read scope.
- World Management is the canonical producer of the fence token for room snapshots.
- In the target contract, `roomSnapshotVersion` is one opaque or epoch-bearing committed token. Entity Management must return that exact token as `entitySnapshotId` after proving its durable entity state satisfies it; it must not mint or substitute an independent local version.
- The target token advances after every durable mutation included in the room view. The cross-service allocation, propagation, acknowledgment, and commit-ordering protocol for Entity Management-owned mutations is not yet selected; this target is not implementation-ready until one canonical protocol and focused failure/retry proof are defined. The current scope-derived adapter value does not provide mutation freshness and is therefore not sufficient as authoritative freshness proof.
- `STALE_READ_FENCE` and `READ_FENCE_UNAVAILABLE` are the canonical service rejection shapes for an unsatisfied fence; a participant fence difference is a caller-side retry condition, not a separate service error. Services must not silently upgrade to a newer snapshot.
- A retry obtains a fresh room snapshot and therefore a fresh fence value; an older fence is not reused across later room-refresh attempts.

Operator-facing command convergence reads must use the durable `GetGameplayCommandStatus` surface after replay/reset/remediation. Redis queue inspection is diagnostic only and must not be treated as the canonical post-remediation status answer.

Minimal canonical room-read example:

1. Game Session handling `LOOK` for `{tenantId=42, gameInstanceId=7, roomInstanceId=R-44, characterId=71}` calls Game Logic `ResolveLook`.
2. Game Logic calls World Management with `{tenantId:42, gameInstanceId:7, roomInstanceId:R-44}` and receives the target committed fence `worldSnapshotId=room-snapshot-epoch-17`.
3. Game Logic asks Entity Management to satisfy that same room fence and receives `entitySnapshotId=room-snapshot-epoch-17`.
4. Success path: both downstream reads return the same fence value, and only then may Game Logic compose canonical room state for Game Session to render.
5. Rejection path: if Entity Management returns a missing or different fence, Game Logic retries with a fresh world snapshot or returns an explicit room-view failure, but it must not join one fence value with another.

> 🔗 See [Redis Architecture](./system-architecture-redis.md) for key structure and durability strategies.

---

## Game Loop / Tick Model

FireMUD uses a **Hybrid Tick Model** to balance responsiveness and fairness:

- **One action per entity per tick** (pulled from command queues)
- **Region-scoped ticks** execute independently for parallelism
- **Tick state** (locks, queues, timers) is stored and coordinated via Redis

> 🔗 See [Tick System and Runtime Design](./system-architecture-ticks.md) for tick execution, staging/rollback, retry policies, and crash recovery.

---

## Scaling Model

FireMUD’s gameplay services are designed to scale horizontally:

- **Game Session Service** scales out across nodes and shards work by tick region, using Redis keys and Lua scripts to coordinate region-local ticks without a single authoritative process.
- **Game Logic Service** is stateless and horizontally scalable; each instance resolves actions deterministically based on the input state it receives from Game Session and Entity Management.
- Other microservices (Account, Entity, World, Social, Logging & Admin) scale independently behind Kubernetes `Deployment` objects and shared PostgreSQL/Redis infrastructure.

This model avoids single-node bottlenecks for ticks or session handling; see [Tick System and Runtime Design](./system-architecture-ticks.md) and [System Architecture – Scaling Runbook](./system-architecture-scaling-runbook.md) for detailed guidance on region sizing, pod counts, and operational tuning.

### Session Sharding & Routing

Game Session Service instances are deployed as a **pool of identical workers**. Ownership of region-scoped tick/gameplay execution is partitioned by `<tenantId, gameInstanceId, regionId>` using Coordination Redis leases as described in [Tick System and Runtime Design](./system-architecture-ticks.md). Connected gameplay sessions remain attached to stable session front-ends rather than being partitioned by region ownership.

Per `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`, shard/lease ownership remains internal to the Game Session layer: the edge does not implement lease-aware admission or a client-visible shard handoff signal. `/ws/game/**` is routed to a stable Game Session service surface and relies on the Game Session coordination model to respect tick ownership invariants.

Per `design/architecture/decisions/adr-0011-gameplay-session-front-end-and-region-execution.md`, this stable surface is implemented as a **session front-end + lease-owner execution** model:

- A gameplay socket binds to a Game Session **session front-end** pod that owns connection-local state and client I/O.
- Region-scoped execution remains fenced to the current **lease owner** for `<tenantId, gameInstanceId, regionId>`.
- Session front-ends may forward command execution or region-owned work over internal gRPC to the lease owner.
- Only the lease owner may mutate region-scoped coordination keys or commit tick-owned gameplay state for that region.
- The session front-end owns the character’s current execution-region pointer and advances it only after a fenced region-transition commit succeeds end-to-end.

Forwarded internal gameplay requests must include a lease/epoch fence plus session identity and sequencing metadata so stale front-ends cannot race or reorder region-owned mutations after lease loss. Stale-fence forwards are rejected at the application layer and require the front-end to refresh ownership before retrying.

Cross-region commands follow these canonical rules:

- The session front-end serializes forwarded work with a monotonically increasing per-session sequence so region owners observe one canonical command order.
- A region transition is externally visible only after the previous region owner has acknowledged release, the destination region owner has accepted the fenced command, and the session front-end has durably updated the execution-region pointer.
- Commands that touch multiple regions must either nominate one primary execution region with fenced sub-calls or be decomposed into ordered fenced sub-operations; they must not issue concurrent unfenced mutations against multiple region owners.
- If lease ownership changes before commit, the session front-end refreshes ownership and retries only the still-uncommitted command sequence; already committed region mutations are replayed or compensated according to the owning service contract, not reissued blindly.

Minimal canonical cross-region sequence:

1. The session front-end assigns the next `sessionSequence`.
2. The session front-end forwards the fenced command to the current lease owner.
3. If the action transitions regions, the source region owner acknowledges release, the destination region owner accepts the fenced continuation, and the session front-end durably updates the execution-region pointer.
4. If lease ownership changes before commit, the session front-end refreshes ownership and retries only the uncommitted sequence.

This preserves a stable edge contract while allowing in-cluster lease rebalancing without requiring client-visible shard routing.

If a future architecture introduces explicit edge shard routing or client-visible handoff semantics, it must be defined as a dedicated design update (routing-key transport, trust model, reconnection/backoff policy) and then integrated into:

- `design/architecture/system-architecture-gateway.md`
- `design/architecture/system-architecture-protocol-bridging.md`
- `design/architecture/system-architecture-reconnection.md`

---

## Authentication and Authorization Flow

Clients authenticate using the `LOGIN` command, processed by the **Game Session Service**.
On initial login, Game Session delegates full credential verification (including lockout and MFA rules) to the **Account Service**. Account completes issuance of the new backend token within its own authority boundary under one stable, high-entropy `requestId` bound to the normalized login and gameplay-binding tuple plus the calling Game Session workload. Account's idempotent issuance and issued-token registry contract, defined by [ADR 0031](./decisions/adr-0031-revocation-safe-session-token-rotation-and-logout.md) and [ADR 0035](./decisions/adr-0035-single-record-issued-token-registry.md), must return the same committed issuance result for a matching retry and reject reuse of that request ID with a different digest; it must not create multiple valid tokens for one logical attempt. Durable operation evidence contains no raw JWT; if response recovery requires the credential itself, Account uses a bounded Account-encrypted response envelope bound to the request, workload, binding, lineage, and token identity, with the same expiry and fail-closed rules as the accepted token-rotation contract. Game Session then stores the token's `authTokenHash`, `authTokenIssuedAt`, and `authTokenExpiresAt` through its gameplay-binding CAS using the expected old binding generation and the same operation identity. These are ordered cross-service steps, not one atomic transaction spanning Account and Game Session.
The receiver token is the exact `game-session-account-delegation` JWT profile with audience `account-service`; it is an Account-issued private backend credential, never a client gameplay credential. Its registry and rotation rules do not define the active gameplay-session lifetime or the bounded continuity/reconnect cache lifetime.

If the Account response is lost, Game Session retries with the same `requestId` and exact request digest. Account resumes matching pending work or returns the stored committed result/terminal outcome; it must not mint a second token merely because the first response was not observed. A confirmed binding-CAS conflict or superseded binding causes Game Session to keep the candidate unusable and issue Account's idempotent retire request for that exact operation and token identity. An ambiguous timeout instead remains fail-closed and provisional while Game Session reconciles the expected binding generation plus operation identity against the authoritative binding: it retires the candidate only after proving that the CAS did not commit, and completes installation or the canonical superseded outcome when the committed operation is found. Account performs bounded orphan cleanup after the operation's lease/expiry horizon; an orphan registry record is never sufficient to make a gameplay binding admissible. Proof must cover Account success followed by response loss, confirmed binding-CAS conflict, ambiguous CAS reconciliation for both committed and uncommitted outcomes, retry with the same request ID, retry with a conflicting digest, and cleanup/reconciliation after each cross-service failure window. The protocol uses ordered durable evidence, idempotent owner-side operations, and fail-closed provisional state; it does not claim a cross-store transaction.
After an actual client-visible edge disconnect, clients reconnect by issuing `LOGIN` again, then re-binding gameplay scope with `PLAY <world> [realm] [character]` before gameplay commands are accepted. This requirement does not apply while the edge connection is retained during an internal Game Session bridge or lease-owner rebind; the bounded non-edge recovery path keeps the existing `LOGIN`/`PLAY` state in that case. For Telnet and other credential-bearing transports this means `LOGIN <username> <secret>`, then `JOIN` when first-time public-production membership is required, followed by `PLAY`; returning members skip `JOIN`. For first-party `/ws/game/**` clients that already completed the bootstrap/connect-token handshake, this means bare `LOGIN` backed by the verified connect context. Game Session uses Coordination Redis to decide whether to resume an existing gameplay session for the selected `{tenantId, gameInstanceId, characterId}` binding or start a fresh session when gameplay state no longer permits resumption.
Session recovery/takeover is authorized by the gameplay binding plus current Account identity, membership, entitlement, and revocation authority; it does not require the previous backend token registry record to remain valid. When a fresh `LOGIN` is required, its newly issued token is installed before backend calls resume. A missing or expired previous token therefore causes replacement/re-authentication, not acceptance of stale token state.

> 🔗 See [Authentication & Authorization](./system-architecture-authentication.md) and [Reconnection Strategy](./system-architecture-reconnection.md) for detailed JWT format and session flow.

---

## Observability and Monitoring

See [Logging & Monitoring](./system-architecture-logging-monitoring.md) for the full pipeline, including Fluent Bit, Prometheus, and related dashboards.

From the perspective of admin and moderation tooling there are two broad classes of features:

- **Core admin actions** – Feature flag toggles, live admission-pointer reads/audit/preparation, scoped tick-remediation controls, and target-state admission CAS/cutover and session-lifecycle controls that primarily talk to domain microservices (for example, Account, Game Session, Social & Groups) via the Gateway. These are designed to remain available even if Elasticsearch, Prometheus, Jaeger, or Alertmanager are temporarily unavailable. Moderation action persistence/audit is currently gated/unavailable: `/moderation/actions` and `ApplyModerationAction` persist neither the `moderation_actions` record nor audit evidence or perform owner-side enforcement. Game Session and Social & Groups independently consume the separate live `EvaluateModerationPolicy` read at their owner boundaries. Target-state moderation persistence/audit and versioned propagation are not a shipped mutation path.
- **Observability-driven workflows** – Log search, metrics and trace dashboards, and alert-centric investigations that depend on Elasticsearch, Prometheus, Jaeger, and Alertmanager being healthy. These surfaces may degrade or become read-only during observability outages but should not block core admin actions.

Implementations of Logging & Admin must preserve this separation with independent readiness/degradation behavior and resource isolation so observability outages do not take down the operator-facing paths on the external admin/creator API plane.

See the [Implementation Status](#implementation-status) section above for the current Logging & Admin implementation boundary; this section defines the architecture separation and target behavior only.

Public administrative `/reports` persistence is unavailable: the HTTP controller, OpenAPI path, and Gateway exposure were removed because caller-bound authorization and live reference validation are not implemented. The separate target player-bootstrap route derives both identities from the validated player session and remains unavailable until that contract is implemented.

For gameplay/chat moderation specifically, the operator policy plane and enforcement plane must remain aligned under the canonical moderation propagation contract:

- In the target state, Logging & Admin will evaluate and emit versioned moderation policy snapshots and monotonic invalidations per `{tenantId, policyScope}` using durable outbox delivery for business-significant changes. This propagation path is not currently emitted and is not evidence that `POST /moderation/actions` directly enforces a decision.
- Game Session consumes `GAMEPLAY_ADMISSION` and Social & Groups consumes `CHAT_SEND`; both maintain bounded-staleness caches keyed by `{tenantId, policyScope}` and must record the `policyVersion` used for each enforcement decision that reaches an audit trail.
- When the bounded staleness window is exceeded and a fresh snapshot cannot be obtained, `gameplay_ban` and `chat_ban` decisions fail closed, while `chat_mute` may use the last valid snapshot only until the same window expires.

## Glossary

- **Session front-end** – The connected Game Session pod that owns socket I/O, connection-local state, and per-session sequencing.
- **Lease owner** – The Game Session execution owner currently holding the `<tenantId, gameInstanceId, regionId>` lease required to mutate region-scoped coordination state.
- **Canonical room state** – A room view assembled only from same-fence World Management occupancy data and Entity Management containment/presentation data.
- **Control-plane API** – An infrastructure or domain admin API that is not part of player gameplay traffic.
- **Bypass-safe workflow** – An explicitly documented external admin workflow allowed to bypass Logging & Admin ingress because it does not rely on Logging & Admin-owned policy, cross-domain write orchestration, or control-plane availability guarantees.
- **Infrastructure management plane** – Internal Gateway management and health-control traffic used for infrastructure operations such as route configuration and liveness checks; this is not an external product API surface.
- **External admin/creator API plane** – The HTTP(S) API surface exposed through Gateway for operator and creator tools on explicitly allowlisted domain routes.
- **Player traffic plane** – Player-facing HTTP, WebSocket, and Telnet traffic used for gameplay admission and live play.

## Gameplay Hot Path Policy (Canonical)

Common gameplay commands must use a bounded synchronous fan-out model:

- One service may orchestrate a hot-path read or command evaluation, but downstream participants on that path should avoid recursively building new cross-service fan-out trees.
- Read-heavy commands with stable transcript shapes (for example `LOOK`) should prefer pre-rendered or pre-aggregated gameplay read models where available, such as Game Session-owned `view:room-look:*` caches, with authoritative recomputation on miss.
- For `LOOK`-class reads, World Management remains the authority for room snapshot and occupancy, while Entity Management enriches caller-supplied occupant/entity references with entity-owned display state. Entity Management should not make a nested occupancy fetch back into World Management on the steady-state hot path.
- New command designs that require synchronous calls to more than two downstream domain services in steady-state must document latency budgets, fallback behavior, and why a read model or pre-aggregation approach is insufficient.
- Initial-slice movement and region-transition orchestration is the explicit exception to the two-downstream-service ceiling. That path may synchronously involve Game Logic, World Management, and Entity Management under Game Session orchestration only because occupancy, deterministic movement rules, and entity-side consequences must commit under one fenced tick/effect contract.
- This exception is narrow and must preserve a concrete budget/fallback contract:
  - target steady-state budget: one fenced movement orchestration attempt must complete within the command latency envelope for the active tick budget; if that budget cannot be met, the command fails closed rather than degrading into partial cross-service success;
  - fallback behavior: if any required participant is unavailable or rejects the current fence/effect identity, the movement command is not partially committed and must converge through the same retry/reconciliation path documented for spatial/ambient effects rather than inventing a second hot-path repair flow;
  - no additional downstream participant may be added to this steady-state synchronous movement path without a new architecture-level decision.

This section is normative for service-level API design. Service docs must treat the bounded fan-out rule as a contract, not as optional performance guidance.

> 🔗 See additional Redis metrics and SLO guidance in [Redis Operations & Migrations](./system-architecture-redis-operations.md).

---

## Deployment Layers

| Layer | Technology |
| --- | --- |
| Client Layer | Browser, Telnet MUD Clients |
| Proxy Layer | TCP Proxy Service (LoadBalancer Service) |
| API Gateway Layer | Spring Cloud Gateway (LoadBalancer Service) |
| Gameplay Session Layer | Game Session Service |
| Service Layer | Microservices (Account, Entity, World, Logic, etc.) |
| Infrastructure Layer | Kubernetes with IPVS, Docker Compose (for local development) |

Deployment health checks (readiness and liveness probes) for these layers are described in detail in [Deployment Environments](./infrastructure/deployment-environments.md).

Target-state environment-specific routing and transport targets are configured through the released declarative `routes.yml` catalog imported by `application.yml`, plus explicit environment-variable overrides. Spring profiles are deployment and test plumbing, not an alternative route authority. Until that target-state catalog converges, the current implemented Gateway authority is `CanonicalGatewayRoutesConfiguration`. See [Deployment Environments](./infrastructure/deployment-environments.md#spring-profile-configuration) for the remaining `test` profile behavior and deployment-specific overrides.

---

## Notes on Responsibility Alignment

- Functional responsibilities are defined in the [Service Responsibility Matrix](./service-responsibility-matrix.md)
- **Game Session Service** orchestrates tick lifecycles, retries, and session management
- **Game Logic Service** resolves individual actions deterministically based on input state
- **Redis** acts as a passive, high-speed execution substrate — storing volatile state and enabling atomic coordination via Lua scripts

**Movement/Travel** rules are part of **Game Logic Service**. World stores geometry and region metadata (e.g., `spacingMultiplier`), while **Game Logic** derives movement/travel costs at runtime.

🧠 **Why Game Session Service vs Game Logic Service?**
Game Logic Service is stateless and deterministic.
Game Session Service governs pacing, conflict handling, and orchestration across distributed tick regions.

### Command Fan-Out, Orchestration, and Scaling

Game Session Service is an **orchestrator**, not a business-logic owner. To avoid turning it into an accidental monolith and to keep latency predictable:

- Gameplay commands are represented as **coarse-grained operations** (for example, “execute command for character in region X”) rather than many fine-grained calls.
- Game Session may issue a small, bounded number of synchronous gRPC calls per command (for example, a single call to Game Logic plus at most one read-model fetch). If a feature would require more than this, the design must introduce read models, projections, or caching instead of adding further fan-out.
- Game Logic Service owns deterministic mechanics (combat, movement, progression). Game Session is responsible for ordering, conflict resolution, and deciding when to invoke Logic and when to defer or drop commands based on tick and quota state.
- Horizontal scaling for region-owned work is based on the complete **`<tenantId, gameInstanceId, regionId>` tick-region scope**. The canonical opaque `{tenantRegionTag}` in region-local Redis keys (for example, `tick:{tenantRegionTag}:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`) is derived from that complete tuple so all coordination for one tick region can execute locally on a single Game Session shard without colliding across game instances. Gameplay session bindings are not region-hash-scoped; they are tenant/instance scoped (for example, `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`) and follow authentication/reconnection lifecycles rather than region epochs.
- Session front-end to lease-owner forwarding is itself a coarse-grained Game Session internal call. It must use fenced identity, preserve per-session ordering, and must not devolve into ad hoc fan-out from front-end pods directly to multiple gameplay-domain services.

New APIs and Redis keys should be reviewed with this orchestration model in mind: Game Session should be able to drive gameplay using a small number of deterministic calls and region-local Redis operations for each tick, rather than building deep, ad hoc call graphs at runtime.

### Authoritative Data Ownership (Examples)

The following examples illustrate where key concepts live; the full matrix remains canonical:

| Concept | Owning service | Notes |
| --- | --- | --- |
| Accounts, login credentials, JWT issuance | Account Service | Issues and validates JWTs; manages subscriptions and bans. |
| Characters, NPCs, items, inventories | Entity Management Service | Owns persistent entity state, inventories, and stats. |
| World topology (rooms, regions, maps) | World Management Service | Stores published room graphs, regions, and pathfinding metadata; Game Design Service is the design-time authoring tool and publishes topology versions into World Management. |
| Dynamic room state (doors, hazards, persistent environment flags) | World Management Service | Owns mutable room-environment state keyed by `RoomInstanceRef` (for example door open/closed flags, persistent hazards, and ambient flags) while remaining the source of truth for world topology and snapshots. Entity Management never stores these flags as entity state; it consumes them for rendering/visibility as needed. |
| Room occupancy (entities present in each room) | World Management Service | Owns authoritative character/NPC location and the derived occupancy view per room instance; other services consume occupancy via World Management APIs or projections rather than persisting their own competing occupancy indexes. |
| Game assets (published content and exported artifacts) | Game Design Service | Owns game asset publishing to the S3-compatible object store; other services and clients consume published assets via configured URLs rather than writing to the store directly. |
| Gameplay mechanics (combat, movement, progression) | Game Logic Service | Implements deterministic rules; no persistent ownership. |
| Live sessions, ticks, command queues | Game Session Service | Owns Redis-backed coordination for active gameplay. |
| Chat, groups, social graph | Social & Groups Service | Manages chat channels, guilds, friends/blocks. |
| Moderation events, admin dashboards | Logging & Admin Service | Owns moderation policy and audit state while using logs/metrics/traces for supplemental investigation and dashboards. |

### Game-Authored Defaults and Starter Experience Profiles

FireMUD provides usable default gameplay experiences without embedding game mechanics as hidden platform behavior. Curated starter experience profiles, such as classic text-MUD, solo-RPG, or minimal sandbox baselines, are versioned DML packs owned by Game Design.

- A creator may select one base profile, optional extension packs, or no profile while building a Draft version.
- Selected packs materialize ordinary stat, condition, action, actor-disposition, observation/targeting/target-selection-policy/default-path binding, feedback, world, and starter-content DML into that Draft version. The resulting rows are game-owned and may be edited, replaced, or removed.
- Pack composition is explicit and reproducible: Game Design records pack identity, revision, hash, order, and any deliberate override. Duplicate keys fail unless a later pack explicitly declares the replacement; implicit last-writer-wins merging is not allowed.
- Published releases contain only the resulting single versioned design bundle. Runtime services never inherit changing profile content, and they must not substitute a platform default when a game removes or omits an imported definition.

This is an architectural invariant: platform code owns typed grammar, validation, and execution semantics; games own named mechanics and content through versioned DML. Details of template creation and pack provenance are defined in [Game Templates and Configuration Tools](./microservices/game-design-service/game-templates.md).

### Movement and Location Consistency Contract

To avoid drift between gameplay orchestration, entity state, and world occupancy, movement and location updates use one explicit write contract:

1. Game Session orchestrates the movement command under the tick timeline and supplies the idempotency/effect identity for downstream writes.
2. Game Logic computes deterministic movement/travel outcomes (valid destination, cost, and mechanics).
3. World Management performs the authoritative location/occupancy commit for the target room/region instance.
4. Entity Management applies entity-side state updates (stats/effects/inventory consequences) but does not maintain a competing authoritative occupancy index.
5. Retries/replays use the same effect identity and converge through idempotent handlers; no service may treat partial local updates as authoritative completion.

If a feature needs a different movement write order, it must be documented as a design change in tick + transactions docs before implementation.

### Moderation Policy Distribution and Enforcement Contract

Moderation behavior is split between policy ownership and enforcement points and must follow a single propagation contract:

1. Logging & Admin Service is the source of truth for moderation policy definitions and audit history.
2. Enforcement services (at minimum Game Session for gameplay bans and Social & Groups for chat mutes/bans) consume policy updates through versioned APIs/events and record the policy version used for each enforcement decision.
3. Enforcement caches must be bounded and invalidated by policy-version changes; enforcement on stale policy beyond the bounded window is an incident.
4. On policy-source or propagation outages, enforcement behavior must be explicit and fail-safe for high-risk actions (for example, deny message send or gameplay admission when required policy cannot be validated), while emitting clear operator-visible errors/metrics.
5. Cross-service moderation decisions must remain auditable end-to-end (policy version, actor, target, enforcement outcome, timestamp).

Service-specific APIs and TTL/eventing details belong in service docs, but any deviation from this contract is an architecture change.

### Ban and Moderation Taxonomy (Canonical)

To remove ambiguity around “bans,” FireMUD uses the following canonical taxonomy:

| Ban/Moderation Type | Policy Owner | Primary Enforcement Point | Scope |
| --- | --- | --- | --- |
| `account_security_ban` (for example compromised account, severe ToS account suspension) | Account Service | Account auth and token/session revocation surfaces | Account-wide across tenants |
| `gameplay_ban` (deny gameplay admission/actions for a tenant) | Logging & Admin Service | Game Session Service | Tenant gameplay scope |
| `chat_mute` / `chat_ban` | Logging & Admin Service | Social & Groups Service | Tenant chat and messaging scope |

Implementation notes:

- Account Service remains the sole writer for auth authority generations and account-security lockout/ban state.
- Logging & Admin defines moderation policy and audit trails for gameplay/chat moderation; enforcement services consume those policies through the moderation propagation contract above.
- “Bans” in docs and APIs must name the specific taxonomy type above instead of using an unqualified `ban` term.

### Design-Time vs Runtime World Data

World and room data flows through two distinct phases:

- **Design-time authoring (Game Design Service):** Creators edit rooms, zones, and world graphs using the Game Design Service and its web-based tools. All edits are versioned, and draft configurations can be validated and tested in isolation.
- **Published runtime topology (World Management Service):** When a version is published, the Game Design Service performs a copy/publish step that materializes the topology and region layout into World Management as read-optimized, immutable structures (per game version). World Management owns this published topology and any derived navigation data such as navmeshes.

Game Session Service controls **which published version is active** per tenant and game instance; region execution inherits that instance's pinned version. Game Design Service can request or schedule version changes, but activation ultimately happens via Game Session and runtime configuration flows (see [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md)).

---

### Multi-Tenancy Enforcement

Multi-tenant isolation is enforced both at the data layer and at specific enforcement points in the runtime:

- **Entitlements and quotas source of truth** – Tenant-scoped hosting/game entitlements and plan-driven quota values are owned by the Account Service (via `GetTenantEntitlementsForRuntime(tenantId, requestId)`). Explicit account-scoped purchases, grants, and donations remain outside that tenant runtime contract. Operator quota overrides are target-state only: no current route or Account-owned mutation contract applies such an override, so current entitlement responses contain only Account-owned plan and entitlement state. The target overlay must be surfaced and audited through Logging & Admin, committed by an Account-owned mutation, and merged into the Account entitlement contract before enforcement points may consume it.
- **Gateway enforcement (edge-safety)** – Spring Cloud Gateway enforces per-IP and per-connection request/handshake limits for HTTP and WebSocket traffic using Cache/Rate-Limit Redis and shared rate-limit helpers. For gameplay WebSockets, Gateway does not attempt to infer tenant identity from post-login traffic; tenant-aware limits are enforced by Game Session after `LOGIN` binds the session.
- **Game Session enforcement** – Game Session Service enforces a tenant-scoped cap on active gameplay sessions keyed by `tenantId` and a tick-region load cap keyed by the complete `<tenantId, gameInstanceId, regionId>` tuple. New logins are rejected or deferred when the tenant session cap or the selected region's load cap is exceeded; the active-session cap is not region-scoped.
- **Downstream services** – Where additional quotas are needed (for example, chat message volume in Social & Groups), services reuse the same quota configuration and Cache/Rate-Limit Redis helpers rather than introducing ad hoc mechanisms.

## Related Documentation

### Diagrams

- [System Architecture Diagram](./system-architecture-diagram.md)
- [System Context Diagram](./system-context-diagram.md)

### Infrastructure & Deployment

- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Multi-Tenancy Architecture](./system-architecture-multi-tenancy.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)

### Runtime & Security

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Security Architecture](./system-architecture-security.md)
- [Testing Strategy](./system-architecture-testing.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)

### Gameplay & Tools

- [Frontend Architecture](./system-architecture-frontend.md)
- [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md)
- [Procedural Generation](./system-architecture-procedural-generation.md)
- [Scripting & Automation Framework](./system-architecture-scripting.md)

### Responsibilities

- [Microservices Responsibility Matrix](./service-responsibility-matrix.md)

### Service-to-Module Mapping

Each microservice described in this overview is implemented as a Gradle module under `services/`:

- Game Session Service → `:game-session-service` (path: `services/game-session-service`)
- Account Service → `:account-service` (path: `services/account-service`)
- World Management Service → `:world-management-service` (path: `services/world-management-service`)
- Entity Management Service → `:entity-management-service` (path: `services/entity-management-service`)
- Game Logic Service → `:game-logic-service` (path: `services/game-logic-service`)
- Game Design Service → `:game-design-service` (path: `services/game-design-service`)
- Automation & Scripting Service → `:automation-scripting-service` (path: `services/automation-scripting-service`)
- Social & Groups Service → `:social-groups-service` (path: `services/social-groups-service`)
- Logging & Admin Service → `:logging-admin-service` (path: `services/logging-admin-service`)
- Spring Cloud Gateway → `:spring-cloud-gateway` (path: `services/spring-cloud-gateway`)
- TCP Proxy Service → `:tcp-proxy-service` (path: `services/tcp-proxy-service`)

# ADR 0144: Stateless First-Party Frontend Application Boundary

## Status

Accepted

## Implementation Status

This decision is not implemented. The independently released static artifact/host, deployment topology, public route split, security/runtime configuration, rollback, and bounded browser journey remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `FRONT-01`
- Decision date: 2026-07-20
- Decision key: `FRONT-01`
- Primary capability: `EA-3.1`
- Affected capabilities: `PO-3.1`, `PO-2.2`, `EA-3.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of browser hosting, public ingress, token handling, self-hosted deployment, frontend release ownership, proof sequencing, and future backend-for-frontend scope

## Context

FireMUD has a React/Vite `web-client` baseline and established Account, Gateway, and domain-service browser flows, but it does not yet have one deployed and independently released first-party frontend boundary. Existing documents also mixed three materially different models: serving product UI files from Spring Cloud Gateway, introducing a server-side browser token relay, and eventually using a dedicated web application service. That ambiguity prevents a stable public route topology and makes preview, hobby/self-hosted, and production packaging responsibilities unclear.

The first hosted proof was deliberately Telnet-first so core gameplay could be reviewed before browser bootstrap and presentation were complete. That milestone has now been achieved. It no longer justifies deferring the frontend boundary, but it also does not make a native mobile application or a rich browser product complete.

A static application host is attractive for hobby and self-hosted deployments because it keeps the whole supported baseline deployable from FireMUD artifacts. Production operators may later want a CDN or object-store origin closer to users. The application artifact and route contract should allow that optimization without turning the initial deployment into a stateful web tier or moving API authority out of the existing services.

## Decision

### One Independently Released Frontend Boundary Is Required Now

FireMUD has one first-party frontend application boundary for the player, creator, and operator browser experiences built from `web-client`. It produces its own immutable, version-identified release artifact and is deployed, promoted, rolled back, and observed independently from Spring Cloud Gateway and the domain services. A backend release must not be required merely to publish or roll back compatible frontend files.

This boundary is an application-file and browser-presentation boundary. Account, Gateway, Game Session, Game Design, Logging & Admin, Social & Groups, and the other domain services retain their existing authentication, admission, authorization, business, and data authority.

### The Supported Baseline Is a Small Static Host

The supported initial deployment is a small unprivileged static-file container running as its own Kubernetes `Deployment` and `Service` in preview and hobby/self-hosted environments. It serves the same immutable frontend artifact that was built and versioned for release. The container runs without root privileges and does not need a writable application filesystem.

Production may use the same container directly, place a CDN in front of it, or later publish the identical artifact contract to an object-store/CDN origin. That optimization must preserve release identity, immutable asset names, document/runtime-configuration freshness rules, rollback, security headers, and the public route topology. It does not create a different production-only application architecture.

### Public Site Routing Separates Files from APIs and Published Assets

One coherent public site and path-routing layer exposes the browser experience:

- frontend document and application-file requests, including client-side routes, go to the first-party frontend host;
- `/auth/**` and `/api/**` go to Spring Cloud Gateway and then to the allowlisted authoritative service;
- `/ws/game/**` goes to Spring Cloud Gateway for gameplay admission and WebSocket routing;
- `/assets/**` remains the separate read-only published game-asset delivery family and goes to its approved object-store, CDN, or Gateway-backed asset origin.

The site router may be an environment load balancer, Kubernetes Ingress, or later CDN routing layer. Spring Cloud Gateway remains the sole public API and gameplay ingress. It contains no frontend HTML, JavaScript, CSS, SPA fallback, UI runtime configuration, UI release lifecycle, or product-specific browser orchestration.

### Static-Host Responsibilities Are Deliberately Narrow

The frontend boundary owns:

- `index.html`, compiled JavaScript and CSS, fonts, icons, and application-owned images;
- SPA fallback for non-reserved browser routes;
- correct content types, compression, and cache policy, with immutable caching for content-hashed files and bounded freshness for `index.html` and public runtime configuration;
- a strict Content Security Policy and the other document-level browser security headers;
- public non-secret runtime configuration such as the API, gameplay WebSocket, and published-asset base paths plus frontend build identity;
- process liveness and readiness for the file-serving contract.

It owns no PostgreSQL or Redis data, secret material, server-held browser session, refresh-token store, cookie session authority, business authentication, authorization decision, domain logic, API aggregation, or gameplay execution. Its ordinary application surface is read-only. Runtime configuration must never become a way to deliver secrets to the browser.

### Browser Tokens Stay Short-Lived and Memory-Only

The static browser application calls the existing Account and domain APIs through Gateway. Admin and creator control-plane Browser JWTs and player-bootstrap JWTs remain short-lived bearer tokens held only in browser memory. They are never written to `localStorage`, `sessionStorage`, IndexedDB, service-worker caches, URLs, logs, or frontend configuration. The gameplay connect token retains its existing narrow secure HttpOnly cookie carrier for the `/ws/game/**` handshake; that cookie does not turn the static host into a session owner.

The frontend enforces a strict CSP, minimizes third-party script origins, clears in-memory authority on expiry or revocation, and provides explicit logout. Logout clears local state immediately and invokes the Account-owned revocation endpoint; logout-all and security generation changes remain Account-owned. Sensitive account, identity, billing, payment-instrument, deletion, and privileged administration actions complete in the HTTPS browser control plane with the Account-owned recent-reauthentication and, where required, TOTP step-up contract. The frontend never converts an ordinary gameplay session into elevated control-plane authority.

### No Stateful Backend for Frontend Is Accepted

FireMUD does not introduce a stateful backend for frontend (BFF), server-held browser session, SSR tier, or general API aggregation layer now. The static host must not grow those responsibilities implicitly.

A BFF requires a separate accepted decision justified by at least one concrete need for server-held browser sessions, SSR/SEO, or measured API aggregation that cannot be met cleanly by the current public APIs. That decision must define session security, CSRF, scaling, availability, authorization propagation, and migration. Any future BFF must never proxy gameplay WebSockets, become a domain-data or authorization authority, or make internal service APIs public by accident.

### Telnet-First Proof Is Complete; Browser Proof Is Next

The Telnet `LOGIN -> PLAY -> LOOK` hosted proof is achieved and remains useful protocol evidence. Frontend completion now requires a bounded automated browser journey against the deployed public topology: load the released application, authenticate through Account, discover and select an admissible realm/character, obtain the browser connect-token cookie, open `/ws/game/**`, complete `LOGIN -> PLAY -> LOOK`, recover once through the fresh-connect-token reconnect flow, explicitly log out, and prove the revoked or cleared authority is not reusable.

Focused proof must also cover SPA fallback without capturing reserved paths, cache and compression behavior, CSP/security headers, non-secret runtime configuration, unprivileged container execution, health, and independent frontend rollback. The browser proof must promote and roll back a frontend-only artifact while the backend release remains unchanged, exercising the same bounded compatibility matrix below. Sensitive-action UI work additionally proves browser reauthentication and required step-up at the owning API boundary.

### Bounded Compatibility Matrix for Independent Frontend Releases

A frontend artifact is independently promotable or rollbackable only when its declared compatibility remains within these bounded contracts:

| Boundary | Compatibility required for the frontend artifact | Proof boundary |
| --- | --- | --- |
| Artifact and API | The artifact uses only the deployed, supported browser-facing Account, Gateway, and domain API contracts; publishing or rolling back compatible frontend files does not require a backend release. | The browser journey succeeds against the unchanged backend release, and an incompatible API change is not hidden by the static host. |
| Artifact and runtime configuration | The artifact accepts only the documented non-secret public runtime configuration and preserves the `index.html`/configuration freshness rules; configuration never supplies authority or secrets. | The promoted and rolled-back artifact loads the intended release identity and public configuration, while missing, malformed, or secret-bearing configuration is rejected by the frontend/host contract. |
| Artifact and gameplay connect-token | The artifact uses the accepted browser bootstrap and narrow HttpOnly gameplay connect-token carrier; Account and Gateway remain responsible for issuance, validation, replay, expiry, and admission context. | The browser obtains and consumes a fresh connect-token cookie for `/ws/game/**` during both frontend-only release states without treating frontend state or configuration as token authority. |
| Artifact and `PlayerOutput` | The artifact consumes the explicitly supported versioned first-party `PlayerOutput` projection; its structured envelope is not replaced by generic JSON handling or made a universal classic-client contract. | The browser proof exercises the supported `PlayerOutput` version through `LOGIN -> PLAY -> LOOK` and fails the release proof for an unsupported projection rather than silently treating it as compatible. |

This matrix bounds frontend-only promotion and rollback. It does not authorize API, runtime-configuration, connect-token, or `PlayerOutput` contract changes; those remain owned by their canonical services and require their own compatibility and proof decisions.

This decision supports a first-party browser application. It does not promise a native mobile application, mobile release channel, or mobile-specific compatibility contract.

## Consequences

- Preview and hobby/self-hosted users gain one supported, self-contained frontend deployment rather than depending on external static hosting.
- Frontend files and releases no longer share Gateway's implementation or rollout lifecycle.
- Production can move file delivery closer to users later without rebuilding the application around a new server contract.
- The browser remains responsible for presentation and direct typed API use, so reload loses memory-only bearer tokens and re-enters the explicit authentication/bootstrap flow.
- FireMUD accepts the operational cost of another image, `Deployment`, `Service`, health surface, route target, and independent release identity.
- A future need for SSR, server-held sessions, or aggregation is not pre-approved and must justify the security and availability cost of a BFF.

## Alternatives Considered

### Serve the SPA from Spring Cloud Gateway

This would reduce the initial workload count, but it couples UI files, SPA fallback, caching, CSP, and frontend rollback to the public API/gameplay edge. It also invites product-specific browser logic into a component whose authority must remain routing and edge enforcement.

### Require External CDN or Object Storage from the First Deployment

This can improve global delivery, but it raises the setup cost for preview and hobby/self-hosted operators and makes a basic supported deployment depend on another external system. The static-container baseline preserves the same artifact contract while leaving that production optimization available.

### Add a Stateful BFF Now

A BFF could hide tokens or aggregate calls, but FireMUD has no accepted server-held-session, SSR/SEO, or measured aggregation requirement that offsets its CSRF, scaling, outage, and authorization-propagation costs. The existing short-lived memory-only token and Gateway-routed API contracts are sufficient for the accepted browser scope.

### Split Player, Creator, and Operator UIs into Separate Services Now

This could isolate release cadences later, but the current product has one `web-client` baseline and no measured deployment or team boundary requiring multiple public frontend services. One independently released boundary keeps the initial topology and self-hosted experience coherent.

## Implementation and Proof Obligations

The repository has a Vite/React `web-client`, typed-query foundations, browser bootstrap/connect-token contracts, and Telnet-first hosted proof. It does not yet demonstrate the required independently versioned frontend artifact and release metadata, unprivileged static container, preview or hobby `Deployment`/`Service`, public reserved-path routing, frontend health contract, runtime configuration, cache/compression/CSP behavior, independent rollback, or the bounded deployed browser journey.

The current frontend UX, admin UI, and Game Design editor are partial. Their presence does not prove the combined browser journey, production readiness, accessibility, or sensitive-action step-up. Existing backend browser APIs remain authoritative and are not reimplemented in the static host.

Implementation must add the frontend build/release artifact, container and deployment topology, path routing, health and security configuration, observability, and focused browser automation. Preview and hobby/self-hosted manifests must support the static-container baseline. Production CDN/object-store publication is optional until separately implemented, but any later implementation must consume the same artifact and preserve the accepted path and security contract.

## Reversibility and Revisit Triggers

The static serving implementation, container image, web server, cache durations, and production CDN vendor may change while preserving this decision. Revisit the single frontend boundary when independent teams, release SLOs, security isolation, or measured traffic justify multiple first-party applications. Revisit a BFF only when a concrete server-held-session, SSR/SEO, or measured aggregation requirement is ready for a separate security and availability decision. Revisit native mobile only through a separate product and compatibility decision.

## Required Documentation Alignment

- `design/architecture/system-architecture-frontend.md`
- `design/architecture/system-architecture-gateway.md`
- `design/architecture/infrastructure/deployment-environments.md`
- `design/architecture/microservices/game-design-service/web-visual-interface.md`

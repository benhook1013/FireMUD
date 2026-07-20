# FireMUD System Architecture: Frontend Architecture

This document describes the structure and tooling for FireMUD's browser-based user interfaces. The `web-client` module houses the React application built with **Vite** and **TypeScript**. Under [ADR 0144](./decisions/adr-0144-stateless-first-party-frontend-application-boundary.md), FireMUD has one independently versioned and released first-party frontend application boundary for player, creator, and operator browser experiences. It is a stateless application-file host and browser presentation boundary, not a backend for frontend or a home for authentication or domain authority. Additional React modules for the admin tools and Game Design interface are available.

Other UIs include a role-based admin interface and a game design editor. See [Role-Based Admin UI](./microservices/logging-admin-service/admin-ui.md) and [Web-Based Visual Design Interface](./microservices/game-design-service/web-visual-interface.md).

## Implementation Notes

- The current `web-client` baseline now uses `TanStack Query` plus local feature state rather than Redux starter scaffolding.
- The independently released frontend artifact, unprivileged static container, preview/hobby `Deployment` and `Service`, public path routing, runtime configuration, health contract, CSP/cache configuration, independent rollback, and bounded deployed browser proof remain implementation gaps.
- The Telnet-first hosted `LOGIN -> PLAY -> LOOK` proof milestone is achieved. It is protocol evidence, not proof that the browser application boundary or browser journey is complete.
- The current frontend baseline and its broader gaps are recorded in [Player Experience, Commands, and Communication implementation tracking](../project-management/implementation-tracking/player-experience-commands-and-communication.md); later frontend/editor work should treat Redux as an explicit exception rather than silently reintroducing it as default infrastructure.

---

## Application Boundary and Public Topology

The released frontend is an immutable Vite build served by a small unprivileged static-file container in the supported preview and hobby/self-hosted baseline. That container runs as its own Kubernetes `Deployment` and `Service`, without a database, Redis, secrets, server-held browser sessions, or a writable application filesystem. Production may serve the same artifact directly, place a CDN in front of it, or later publish it to an object-store/CDN origin without changing its release, cache, security, or route contract.

One coherent public site router keeps these path families distinct:

- frontend documents, compiled files, and client-side routes go to the frontend host;
- `/auth/**` and `/api/**` go to Spring Cloud Gateway and then to allowlisted authoritative services;
- `/ws/game/**` goes to Spring Cloud Gateway and Game Session;
- `/assets/**` remains the separate read-only published game-asset delivery family.

Spring Cloud Gateway is the sole public API and gameplay ingress. It contains no frontend HTML, JavaScript, CSS, SPA fallback, runtime configuration, release logic, or product-specific browser orchestration. The frontend host owns `index.html`, compiled application files, SPA fallback outside the reserved path families, content types, compression, immutable caching for content-hashed assets, bounded freshness for `index.html` and public runtime configuration, strict CSP and document security headers, and file-serving health. Runtime configuration contains only public values such as API, WebSocket, and asset base paths plus frontend build identity.

The frontend host is not a stateful backend for frontend. It performs no business authentication, authorization, domain logic, API aggregation, or gameplay execution. A future BFF requires a separate accepted decision justified by a concrete server-held-session, SSR/SEO, or measured aggregation need. It must never proxy gameplay WebSockets or become domain or authorization authority.

## Component Hierarchy

FireMUD uses React components with a **feature-first** organization. Each feature folder contains its own components, tests, and styling.

```text
web-client/
  src/
    features/
      account/
        AccountPage.tsx
        accountQueries.ts
        accountState.ts  # local UI/editor state when needed
      gameplay/
        GameplayPage.tsx
        gameplayQueries.ts
        gameplayState.ts
      ...
```

- **Pages** represent top-level routes and compose smaller **UI widgets**.
- Reusable UI elements live under a shared `components/` directory.
- Material-UI provides the base widgets and theme customization.

## State Management

FireMUD's default frontend state model is:

- **TanStack Query** for server state;
- local component/form/editor state close to the owning feature;
- shared global client-state layers only when later work proves they are needed.

This is deliberate. Large server-backed world or admin datasets are not automatically "complex client state." They are usually a query/caching concern first. FireMUD should add a richer browser-wide state layer only when the browser truly becomes a stateful editor/runtime that needs long-lived cross-screen draft orchestration, collaborative editing state, undo/redo, or similarly heavy client-owned behavior.

- The canonical shared browser data substrate is a `QueryClient` configured in `src/main.tsx`.
- Shared API/query helpers live under `src/api/` or feature-local query modules and expose typed hooks for reads and mutations.
- Cache invalidation, background refetching, retry policy, and mutation lifecycle should be expressed through `TanStack Query` rather than a repo-wide Redux default.
- Local UI state should stay local until concrete later work proves a true shared client-state need.
- Redux Toolkit remains an allowed later tool, but only by explicit exception once a real client-state complexity case exists; it is not the baseline house style.

## Authentication and Session Handling

Frontend flows are split between **player gameplay sessions** and **admin/creator tools** so that gameplay auth remains simple while control-plane operations use JWTs:

- **Player UI (gameplay)**  
  - First-party browser files are hosted by the dedicated stateless frontend application boundary, even when the first version is only a terminal-style browser client. The browser calls Account and gameplay surfaces through Spring Cloud Gateway; the static host does not issue, relay, or retain authentication material.
  - First-party clients must first call the dedicated player-bootstrap endpoint (`POST /auth/player-bootstrap` or equivalent) with player credentials to establish short-lived account identity for gameplay bootstrap only, then use bootstrap-authenticated HTTP discovery endpoints to choose a caller-visible world/realm/character target, then call `POST /auth/connect-token` using the discovery-provided `connectScopeId`. For browser clients, that call sets the short-lived `Firemud-Connect-Token` HttpOnly cookie used by the `/ws/game/**` handshake; then the client opens `/ws/game/**` and completes gameplay authentication by issuing `LOGIN` over the WebSocket channel using the already-verified bootstrap/connect context rather than replaying username/password/OTP from the browser, as described in [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow).
  - Browser transport constraints matter: a browser WebSocket cannot set arbitrary custom handshake headers the way Telnet smart clients, Mudlet integrations, or server-side clients can. The first-party browser flow therefore uses the `Firemud-Connect-Token` cookie set by `POST /auth/connect-token` with `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/ws/game`, and `Max-Age` no longer than the connect-token TTL. The React client must not read or persist the connect token itself; it only consumes non-secret response metadata such as `expiresAt`, `tenantId`, `realmSlug`, and `gameInstanceId`. Query parameters are not a supported carrier in player-facing environments, and clients must obtain a fresh token after any failed attempt that may have consumed it.
  - The React client does not store or expose control-plane Browser JWTs for gameplay. It may hold only the short-lived, memory-only `player-bootstrap` token described in the authentication design and use it solely for gameplay bootstrap surfaces such as bootstrap discovery and `POST /auth/connect-token`.  
  - Explicit player logout must revoke the current `player-bootstrap` token with Account Service `POST /auth/logout` in addition to closing gameplay sockets and clearing reconnect state. If the logout call cannot complete, the client must still clear local state immediately and rely on the bootstrap token's short server-side TTL or account-level revocation for eventual cleanup.
  - Canonical player logout order is: stop reconnect attempts, close the gameplay socket, call `POST /auth/logout` with the current `player-bootstrap` token when one exists, clear remembered world/character selection plus reconnect metadata, clear the in-memory `player-bootstrap` token, and render the logged-out player state.
  - Tenant membership, non-public realm-access-grant checks, and runtime entitlement checks occur after account bootstrap. For a first public-production join, the UI presents an explicit `Join & Play` action that creates the durable Account-owned membership before character creation and `POST /auth/connect-token`; connect-token issuance never creates membership implicitly. The selected target must come from canonical discovery and may be the public production realm or an explicitly authorized alternate realm such as a playtest fork.
  - The client must treat `connectScopeId` as an opaque short-lived selector, not as a stable saved identifier. If reconnect or retry reports `CONNECT_SCOPE_MISMATCH` or `ADMISSION_POINTER_UNAVAILABLE`, the client must rerun bootstrap discovery rather than guessing a replacement target from cached `tenantId` or `realmSlug` alone.  
  - Existing members can take the normal `PLAY <world> [realm] [character]` path directly. Brand-new accounts may discover the public production realm but complete one explicit `Join & Play` action before character creation/connect; the resulting membership powers subsequent “my games” and return flows. Game Session resolves world/realm/character identifiers, rechecks membership, routing, and entitlements, returns bundle metadata, and binds the socket in Redis.
  - On page reload or connectivity loss, the client requests a fresh connect token, reconnects the WebSocket, then replays `LOGIN` and `PLAY` without prompting for credentials again; `WORLDS`, `REALMS <world>`, and `CHARS <world> [realm]` remain optional discovery helpers when the intended target is no longer unambiguous. Game Session uses Redis gameplay session bindings, current membership authority, and fresh backend token rebinding to restore gameplay state when allowed by server-side TTLs and revocation rules.

- **Admin and creator UIs**  
  - Admin/creator interfaces authenticate through the Account Service (for example, via `/auth/login` exposed behind the Gateway). Successful login issues a short-lived JWT for control-plane APIs that represents a **control-plane browser session** for the current account.  
  - Admin JWTs are treated as **internal tokens** and are stored only in in-memory frontend state managed by the auth layer; they must not be written to `localStorage`, `sessionStorage`, IndexedDB, service-worker caches, URLs, frontend configuration, or third-party origins. The application does not define a hidden refresh token or server-held browser session.
  - The frontend sends these tokens on meta/control API calls by setting the `Authorization: Bearer <token>` header on shared query/mutation requests. Backend services validate these JWTs with the shared `AuthTokenInterceptor` and enforce tenant access via the Tenant Authorization Contract described in [Authentication & Authorization](./system-architecture-authentication.md#tenant-authorization-contract).  
  - Logout clears the in-memory auth state and calls Account Service `POST /auth/logout` to revoke only the presented browser/device token; subsequent requests from that device require re-authentication, while other devices remain active. Account-wide “logout all devices” uses `POST /auth/logout-all` and terminates the account's control-plane, bootstrap, and active gameplay bindings across tenants. Closing a browser tab does not revoke auth token sessions; explicit logout is required to force server-side revocation before TTL expiry.
  - Sensitive account, identity, billing, payment-instrument, deletion, and privileged administration actions remain HTTPS browser control-plane flows and require the recent ordinary reauthentication and, where applicable, TOTP step-up defined by the Account-owned authentication contract. The static host never elevates an ordinary gameplay session.

The application must ship a strict Content Security Policy and minimize third-party script origins. All new frontend features that interact with protected APIs should reuse the shared auth utilities and the canonical query/mutation transport helpers so token handling, logout, and error behavior remain consistent across player, admin, and creator experiences. In particular, the shared browser API layer should interpret canonical error codes from backend services as follows:

- `AUTH_TOKEN_EXPIRED` – Clear in-memory auth state, redirect to login, and show a “Session expired” message.
- `AUTH_SESSION_REVOKED` – Clear in-memory auth state, redirect to login, and show a security-focused message (for example, “You were signed out because your account security changed.”).
- `AUTH_UNAVAILABLE` – Keep in-memory auth state, show a retriable authentication-service availability message, and retry with bounded backoff. Do not convert this infrastructure failure into logout.
- `TENANT_BILLING_BLOCKED` – Keep the user logged in, but mark the affected tenant as billing-blocked in UI state, show a prominent billing banner, and disable gameplay or instance-management actions for that tenant while still allowing billing-safe operations (such as viewing invoices or updating payment details).
- `MEMBERSHIP_AUTH_UNAVAILABLE` – Keep the user logged in, surface a retriable billing-authorization availability message, and block billing-safe mutations until live membership authority recovers.
- `ENTITLEMENT_UNAVAILABLE` – Keep the current auth state, show a retriable availability banner, and apply bounded retry/backoff rather than logging the user out.
- `ADMISSION_POINTER_UNAVAILABLE` – Keep the current auth state, show a retriable gameplay-admission unavailable message, and retry lobby admission with bounded backoff.
- `CONNECT_CONTEXT_INVALID` – Keep the current auth state, force gameplay reconnect flow (`connect-token` refresh + new socket + `LOGIN`), and block `PLAY` retries on the current socket.
- `CONNECT_SCOPE_MISMATCH` – Keep the current auth state, prompt world/session re-selection, request a fresh connect token for the intended discovery-selected realm target, and retry on a new socket.

Client rule for discovery-issued selectors:

- Treat `connectScopeId`, `pointerVersion`, `evaluatedAt`, and `connectScopeExpiresAt` as one short-lived snapshot bundle for the selected realm target.
- Do not mix a stale `connectScopeId` with newly cached realm metadata or vice versa.
- When `connectScopeExpiresAt` has passed, rerun discovery before requesting another connect token even if the visible selection has not changed.

For gameplay WebSocket handshake failures on `/ws/game/**`, first-party clients must also differentiate HTTP `403` handshake classes:

- `CONNECT_TOKEN_MISSING`: request a fresh connect token and retry the socket open with bounded backoff.
- `CONNECT_TOKEN_EXPIRED`: request a fresh connect token and retry the socket open with bounded backoff.
- `CONNECT_TOKEN_REPLAYED`: request a fresh connect token and retry with bounded backoff; repeated replay failures should surface a session-recovery action instead of fast-looping.
- `CONNECT_SCOPE_MISMATCH`: rerun bootstrap discovery for the selected world/realm/character target, request a fresh connect token for that scope, and retry on a new socket.
- `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`: keep the current auth state, show a temporary edge-auth-unavailable message, and retry with slower bounded backoff.
- `CONNECT_TOKEN_REJECTED`: request a fresh connect token and retry with bounded backoff; if repeated after refresh, restart the first-party bootstrap flow.
- `POLICY_DENY`: treat as non-retriable until configuration is corrected and surface an actionable error.

These handshake classes are edge-handshake outcomes, not gameplay text-protocol `ERROR <CODE>` frames. Clients only start handling protocol-level `ERROR <CODE>` responses after the WebSocket has been established and `LOGIN`/`PLAY` exchange begins.

Canonical first-party browser reconnect sequence:

```text
POST /auth/player-bootstrap
-> { bootstrapToken, expiresAt }

GET /auth/bootstrap/worlds
Authorization: Bearer <bootstrapToken>
-> [{ worldSlug: "demo", displayName: "Demo World" }]

GET /auth/bootstrap/worlds/demo/realms
Authorization: Bearer <bootstrapToken>
-> [{
     realmSlug: "production",
     displayName: "Live Realm",
     connectScopeId: "cs_demo_production_v17"
   }]

GET /auth/bootstrap/worlds/demo/realms/production/characters
Authorization: Bearer <bootstrapToken>
-> [{ characterName: "Mara" }]

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_production_v17", requestId: "req-reconnect-1" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { expiresAt, tenantId: "tenant-demo", realmSlug: "production", gameInstanceId: "production", jti, issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response

LOGIN
OK LOGIN Logged in
PLAY demo production Mara
OK PLAY Entered world: Demo World / Live Realm as Mara

...socket drops...

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_production_v17", requestId: "req-reconnect-2" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { expiresAt, tenantId: "tenant-demo", realmSlug: "production", gameInstanceId: "production", jti, issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response

LOGIN
OK LOGIN Logged in
PLAY demo production Mara
OK PLAY Resumed session
```

This reconnect flow assumes the player still holds a valid in-memory `player-bootstrap` token. If the bootstrap token has expired or the page was reloaded, the client must obtain a new bootstrap token first, then repeat bootstrap discovery as needed and request a fresh connect token. There is no separate silent refresh/bootstrap-restoration mechanism in the current architecture; reload behaves like bootstrap-token loss and restarts the first-party bootstrap flow. In all cases, first-party reconnects must not prompt the browser to replay username/password/OTP after bootstrap has been re-established. Clients may skip visible `WORLDS` / `REALMS <world>` / `CHARS <world> [realm]` steps when they already retain a valid world/realm/character choice and still drive the same canonical `PLAY <world> [realm] [character]` selection on reconnect.

## API Usage Patterns

All API communication should be handled by **TanStack Query**-backed fetch/mutation helpers defined in `src/api/` or feature-local query modules. Endpoints such as bootstrap discovery, login/logout, world browsing, and admin control-plane reads should follow the same typed-query pattern.

`TanStack Query` is responsible for:

- Data caching and revalidation
- Request deduplication
- Error and loading state tracking
- Background polling and refetching

WebSocket interactions for real-time gameplay are handled by `src/websocket.ts`, which manages the connection lifecycle and message routing. Query-backed browser state should react to socket events through targeted cache updates or invalidation rather than an assumed global Redux store.

## Hosting Direction

FireMUD completed the intentionally first reviewer-usable TCP/Telnet `LOGIN -> PLAY -> LOOK` hosted proof. The next required boundary is the independently released stateless frontend described above; it is no longer deferred until another Telnet milestone.

Preview and hobby/self-hosted deployments support the small unprivileged static container, `Deployment`, and `Service` as the baseline. Production may use the same container or move the identical immutable artifact behind object storage or a CDN. The optimization must preserve release identity, rollback, document/runtime-configuration freshness, security headers, and the public reserved-path topology.

The first browser UI may be a terminal-style client before richer player, creator, and operator UX is complete. This browser decision does not promise a native mobile application or mobile release contract.

## Build Tooling

The frontend uses **Vite** for fast development and production builds:

- `npm run dev` starts the local development server with hot module replacement.
- `npm run build` produces an optimized bundle under `dist/`.
- `npm run preview` serves the production bundle locally for verification.
- `npm run test` runs unit tests with Jest and React Testing Library. The script runs the test suite.
- `npm run lint` and `npm run format` ensure consistent code style.
- `npm run format:fix` writes formatting changes back to disk.
- `npm run accessibility` audits the compiled site with axe-core. See [Developer Setup](../../DEVELOPER_SETUP.md#frontend-lint--accessibility) for Chrome requirements.

See `web-client/README.md` for additional setup tips.

TypeScript configuration lives in `tsconfig.json`, and ESLint/Prettier enforce coding standards consistent with the rest of the project.

`TanStack Query` should be the default browser server-state substrate for new frontend work. API code generation and mocking can be extended using **msw** (Mock Service Worker) for testing. If future editor/admin work truly needs a broader client-state layer, that work should document why local feature state plus `TanStack Query` is no longer sufficient before introducing Redux or another global state tool.

## Game-Specific Customization

See [Game Customization](./system-architecture-game-customization.md) for the broader design.
Game-specific themes rely on the multi-tenant model described in [Multi-Tenancy](./system-architecture-multi-tenancy.md).

FireMUD aims to let each hosted game supply its own UI styling and layout tweaks.

- When a game version is published, branding assets and a `manifest.json` are
  uploaded to tenant- and version-scoped object storage (e.g., S3, MinIO, or a
  CDN).
- Published version metadata stores the manifest URL. At runtime the React app
  fetches this manifest to load logos, favicons, theme JSON, and optional route
  definitions, then applies Material-UI overrides.
- The current admitted realm bundle is the source of truth for branding. `PLAY`
  success, reconnect resume, and any realm switch must provide the resolved
  bundle identity (`versionId`, optional `scriptPatchVersion`, and manifest
  location/hash or equivalent), and the client must swap theme assets whenever
  that resolved bundle changes.
- Assets are loaded directly from the CDN or via the gateway's `/assets/**`
  route when a self-hosted MinIO instance is used; the Game Design Service is
  never queried during gameplay.
- If the manifest omits an asset, the default platform styling is used.
- Core components remain shared so feature updates reach all games without
  forks.

## Internationalization Strategy

The React client uses **react-i18next** to load translation JSON files at runtime. Players select a language in the settings menu, and the UI strings update without a page reload. Locale files live under `src/i18n/` and can be extended by hosted games.

## End-to-End Testing

Frontend completion requires a bounded **Playwright** journey against the deployed public topology: load the released application, authenticate through Account, discover and select an admissible realm/character, obtain the browser connect-token cookie, open `/ws/game/**`, complete `LOGIN -> PLAY -> LOOK`, recover once through the fresh-connect-token reconnect flow, explicitly log out, and prove cleared or revoked authority is not reusable.

Focused tests also prove that SPA fallback does not capture `/auth/**`, `/api/**`, `/ws/game/**`, or `/assets/**`; content-hashed files and `index.html` use their distinct cache policies; compression, CSP, public non-secret runtime configuration, health, unprivileged execution, and independent rollback work as declared. This deployed browser proof is not yet complete.

---

This architecture keeps the web client modular and maintainable while aligning with the backend microservices. Browser files and presentation belong in the stateless frontend application boundary, while Account and the domain services retain browser-flow authority and Spring Cloud Gateway remains the sole public API/gameplay ingress.

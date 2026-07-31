# FireMUD System Architecture: Frontend Architecture

This document describes the structure and tooling for FireMUD's browser-based user interfaces. The `web-client` module houses the player-facing React application built with **Vite** and **TypeScript**. FireMUD's target architecture is a dedicated first-party web application service that owns browser asset hosting and first-party web bootstrap concerns rather than treating Spring Cloud Gateway as the long-term home for frontend assets or product-specific browser logic. The first practical version of that service may be a terminal-style browser client before richer web UX is added. Additional React modules for the admin tools and Game Design interface are available.

Other UIs include a role-based admin interface and a game design editor. See [Role-Based Admin UI](./microservices/logging-admin-service/admin-ui.md) and [Web-Based Visual Design Interface](./microservices/game-design-service/web-visual-interface.md).

## Implementation Status

Explicit `JOIN`/`Join & Play` and removal of implicit membership creation are target behavior, not implemented in the current runtime. Current connect-token and text `PLAY` paths may still create membership implicitly; that behavior is a live security-contract violation and known unsafe drift, and implementation proof that runtime admission rejects the implicit path is unavailable. Affected admission must therefore be marked unavailable/unsafe in the frontend rather than presented as a supported fail-closed path. The target flow below must not be read as runtime completion. Gateway `POST /ws/game/connect-token/revoke` and the dependent immediate edge-revocation step described below are also target-only and are not implemented in the current runtime. The supported current logout fallback is Account `POST /auth/logout` plus clearing only local reconnect/client state and relying on the bounded connect-token lifetime; it must not be described as immediate Gateway revocation, an edge deny/replay fence, or client-side clearing of the HttpOnly cookie. Edge replay/deny enforcement is claimed only when that Gateway revoke path is implemented and reachable.

## Implementation Notes

- The current `web-client` baseline now uses `TanStack Query` plus local feature state rather than Redux starter scaffolding.
- The current frontend baseline and its broader gaps are recorded in [Player Experience, Commands, and Communication implementation tracking](../project-management/implementation-tracking/player-experience-commands-and-communication.md); later frontend/editor work should treat Redux as an explicit exception rather than silently reintroducing it as default infrastructure.

---

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
  - First-party browser clients are expected to be hosted by the dedicated first-party web application service, even when the first version is only a terminal-style browser client. Spring Cloud Gateway remains the public gameplay/API edge, but it should not become the long-term host for the player web application itself.  
  - First-party clients use one of two explicit gameplay flows:
    - **Returning member:** `POST /auth/player-bootstrap` -> bootstrap discovery -> bootstrap-authenticated character discovery/creation -> `POST /auth/connect-token` with the discovery-provided `connectScopeId` -> `/ws/game/**` -> bare `LOGIN` -> `PLAY`. This flow requires the selected existing caller-bound membership and does not call `/auth/bootstrap/join`.
    - **First join:** `POST /auth/player-bootstrap` -> public-production discovery -> `POST /auth/bootstrap/join` -> bootstrap-authenticated character discovery/creation -> `POST /auth/connect-token` with the post-join discovery-provided `connectScopeId` -> `/ws/game/**` -> bare `LOGIN` -> `PLAY`. `/auth/bootstrap/join` must commit membership before character discovery, character creation, or connect-token issuance; no later step may create membership implicitly.
  - The first-join, returning-player, reconnect, resume, and related gameplay-admission descriptions in this section are target-state contracts, not confirmed current supported behavior. Until runtime proof demonstrates explicit membership enforcement and proves that connect-token issuance and text `PLAY` cannot create membership implicitly, the frontend must disable these paths or mark them unavailable/unsafe rather than expose them as working admission or recovery flows. The target descriptions remain the behavior to implement; they are not permission to fall back to current implicit creation.
  - Browser and mobile-browser clients receive the short-lived `Firemud-Connect-Token` HttpOnly cookie used by the `/ws/game/**` handshake; first-party native-mobile clients using a cookie jar remain cookie-only. Explicitly classified non-first-party/public native-mobile clients store the token in OS secure storage and present it through `X-Firemud-Connect-Token`. The client then opens `/ws/game/**` and completes gameplay authentication by issuing `LOGIN` over the WebSocket channel using the already-verified bootstrap/connect context rather than replaying username/password/OTP from the browser, as described in [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow).
  - Browser transport constraints matter: a browser WebSocket cannot set arbitrary custom handshake headers the way Telnet smart clients, Mudlet integrations, or server-side clients can. The first-party browser flow therefore uses the `Firemud-Connect-Token` cookie set by `POST /auth/connect-token` with `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/ws/game`, and `Max-Age` no longer than the connect-token TTL. The React client must not read or persist the connect token itself; it only consumes non-secret response metadata such as `expiresAt`, `tenantId`, `realmSlug`, and `gameInstanceId`. Query parameters must never carry connect tokens or other authentication credentials; non-secret bootstrap discovery selectors such as `connectScopeId` may be sent as query parameters. Clients must obtain a fresh token after any failed attempt that may have consumed it.
  - The React client does not store or expose `control-ui` JWTs for gameplay. It may hold only the short-lived, memory-only `player-bootstrap` token described in the authentication design and use it solely for gameplay bootstrap surfaces such as bootstrap discovery, `POST /auth/bootstrap/join`, bootstrap-authenticated character creation, and `POST /auth/connect-token`.
  - Explicit player logout must revoke the current `player-bootstrap` token with Account Service `POST /auth/logout`, clear local reconnect/client state, and use the supported bounded connect-token lifetime fallback because Gateway `POST /ws/game/connect-token/revoke` is not implemented yet. The browser cannot directly clear the HttpOnly gameplay cookie; when Gateway revoke is unavailable it relies on the bounded cookie/token lifetime, while replay/deny enforcement remains the responsibility of the Gateway revoke path. The target Gateway revoke operation is an HTTP operation, not a WebSocket upgrade: it would read the HttpOnly cookie server-side, record a bounded deny marker for its `jti` or equivalent edge revocation fence checked before token consumption, and return the clearing `Set-Cookie` response where that response surface supports it. The browser must satisfy the fail-closed `Origin` and anti-CSRF policy defined by [ADR 0021](./decisions/adr-0021-staged-player-authentication-and-gameplay-binding.md); the React client never reads the token. The client clears active player state immediately even if Account is unavailable. The shared frontend auth/session controller is the sole owner of one dedicated, memory-only pending-revocation slot and never persists, logs, or uses its credential for gameplay/bootstrap calls. The pending-revocation copy is never used as an alternate active credential: the active in-memory `player-bootstrap` token remains the sole usable bootstrap credential until logout atomically captures it, after which the captured copy is used only for the logout/revocation request and is never reused for gameplay or bootstrap.
  - The target canonical player logout order is: stop reconnect attempts, capture the current `player-bootstrap` token into the pending-revocation slot, ask Account to durably commit the exact-token `PENDING` intent and immediately advance the exact-token issuance fence to `PENDING_LOGOUT` in that same durable step, then send gameplay `LOGOUT` and close the gameplay socket, ask Gateway to deny the gameplay-connect `jti` and clear the HttpOnly cookie, and finally ask Account to invalidate the underlying token and commit the `COMMITTED` tombstone. Account must establish the durable issuance fence before socket closure or any racing `/auth/connect-token` issuance can succeed; every issuance path rejects the fenced lineage rather than returning a usable late credential. Once `PENDING` is durable, Account must reject racing issuance, refresh, rebind, installation, and reconciliation for that token lineage; `PENDING` is never success and remains resumable through Account's durable retry/reconciliation operation until terminal completion. If Gateway revoke is unavailable, Account must not report the edge deny/clear outcome as confirmed; clear only local reconnect/client state and rely on the bounded token lifetime; Gateway mitigation must not be reported as full logout when Account is unavailable or its fence is unconfirmed. The browser never directly clears the HttpOnly gameplay cookie; only a server response can clear that cookie. The target Gateway revoke call uses a two-second per-attempt timeout, at most two retries, and 250 ms then 500 ms backoff where time remains, but every request timeout and backoff is capped by the remaining five-second total deadline. This Gateway controller budget is independent and non-nested: it is not shared with or added to Account's revocation budget. Account revocation retains its separate policy of at most five attempts, a five-second timeout per attempt capped by a 30-second overall deadline, and full-jitter exponential backoff starting at 250 ms and capped at four seconds. The controller tracks Account PENDING, Gateway denial/clearing, and Account COMMITTED finalization as separate outcomes. It enters terminal `LOGGED_OUT_REVOKED` only after Account confirms the exact-token fence/Bootstrap-token revocation and Gateway confirms denial/clearing of the gameplay-connect token. Because Gateway gameplay-connect denial and cookie clearing are not currently routable, the current runtime cannot reach `LOGGED_OUT_REVOKED`; current logout flows terminate as `LOGGED_OUT_REVOCATION_UNCONFIRMED`. Account-only confirmation remains `LOGGED_OUT_REVOCATION_UNCONFIRMED` with edge revocation explicitly unconfirmed until Gateway confirms or the known gameplay-connect acceptance window ends; expiry closes that bounded edge risk but is reported as token-window expiry, not confirmed server revocation. Retry exhaustion, an unconfirmed Account result, or bootstrap-token expiry likewise remains `LOGGED_OUT_REVOCATION_UNCONFIRMED`, stays locally logged out, and requires a fresh `POST /auth/player-bootstrap` before any later gameplay flow. The frontend may retry only while the captured credential remains valid and its local retry deadline remains open. At the earlier of signed token expiry or that deadline, it wipes the raw credential and stops frontend retries while retaining only the opaque server-side logout handle and non-secret operation state needed to observe Account's durable reconciliation. Browser or process loss likewise destroys the memory-only credential and ends frontend retries, but neither local expiry nor frontend loss cancels the durable Account `PENDING` operation: Account's persisted operation and opaque logout handle are the authoritative revocation proof, and Account's reconciler continues exact-token revocation without the raw token. A raw credential may never be reused for gameplay/bootstrap calls, and retaining it in memory never extends its signed lifetime. Local clearing or process termination is not confirmed server revocation.
  - Logout invalidates the local bootstrap/connect-token request epoch and aborts every in-flight bootstrap, discovery, join, character, and connect-token request owned by that epoch. A late response is ignored for all client state transitions, but abort and epoch invalidation cannot guarantee that a late `Set-Cookie` was not already committed by the browser. The server-side logout issuance fence is therefore authoritative: Account rejects bootstrap or connect-token issuance that races a durable `PENDING` or `COMMITTED` logout for the same account/token lineage, and Gateway rejects a connect token whose deny/revocation fence wins before consumption. Where a logout response reaches the browser after the old issuance, the server must also return an explicit clearing or deny response so the late cookie cannot remain usable. The client must never treat ignored local state or local cookie clearing alone as proof of server revocation.
  - A server must reject an expired `Firemud-Connect-Token` cookie as `CONNECT_TOKEN_EXPIRED`, never fall back to a stale cookie, query parameter, or unapproved header, and return a clearing `Set-Cookie` response where the HTTP surface can do so. The browser cannot inspect the HttpOnly value; it discards the failed handshake attempt and obtains a fresh connect token only from a still-valid bootstrap session. If the bootstrap token is expired or revoked, the client restarts `POST /auth/player-bootstrap` and discovery instead of treating the expired cookie as a recoverable gameplay credential.
  - Tenant membership, non-public realm-access-grant checks, and runtime entitlement checks occur after account bootstrap. The `player-bootstrap` token is still a tenant-free, non-gameplay bootstrap credential: it authorizes caller-bound discovery and the explicit join/connect-token APIs, but it does not itself carry or grant gameplay scope. The bounded `gameplay-connect` token is the only edge-admission credential carrying the selected target snapshot. In target behavior, a first public-production join presents an explicit `Join & Play` action that creates the durable Account-owned membership before character creation and `POST /auth/connect-token`, and connect-token issuance never creates membership implicitly. Current connect-token and text `PLAY` paths may still create membership implicitly; that is a live security-contract violation and known unsafe drift, and runtime rejection is not proven. The frontend must mark the affected admission flow unavailable/unsafe rather than treating implicit creation as a supported fallback. The selected target must come from canonical discovery and may be the public production realm or an explicitly authorized alternate realm such as a playtest fork.
  - Gameplay admission has three separate applicability modes. For public-production onboarding, discovery may precede membership, but only explicit `JOIN`/`Join & Play` creates membership; `gameplay-connect`, trusted TCP Proxy admission, and `PLAY` are applicable only after that commit. For returning membership, those transports and `PLAY` require the existing caller-bound membership and current authority checks and do not perform onboarding. For a grant-backed private or playtest realm, they require the applicable grant and any separately required existing membership, skip public onboarding, and never create membership. The trusted TCP Proxy is only a transport trust path and does not replace the mode-specific downstream checks; a `gameplay-connect` token and `PLAY` command likewise do not establish membership or grant eligibility.
  - The client must treat `connectScopeId` as an opaque short-lived selector, not as a stable saved identifier. If reconnect or retry reports `CONNECT_SCOPE_MISMATCH`, it must discard the entire discovery snapshot bundle and its derived connect-token metadata, rerun bootstrap discovery, and obtain a new selector rather than reusing any old bundle field or guessing a replacement target from cached `tenantId` or `realmSlug`. `ADMISSION_POINTER_UNAVAILABLE` likewise requires fresh discovery before retry rather than substitution from cached target fields.
  - Returning players with an existing Account-owned membership use browser bootstrap, bootstrap discovery, and `POST /auth/connect-token` before taking the normal `PLAY <world> [realm] [character]` path; they skip only the membership-creation action because their membership already exists. Target behavior requires an account with no membership for the selected game, including an otherwise returning account whose membership is absent, to take the explicit `Join & Play` path before character creation/connect, and forbids a connect-token or `PLAY` path from creating that membership implicitly. Current implementation proof for this invariant is absent and the tracker records the path as unsafe/unavailable drift; the frontend must not present it as enforced. The resulting membership powers subsequent “my games” and return flows. Game Session resolves world/realm/character identifiers, rechecks membership, routing, and entitlements, returns bundle metadata, and binds the socket in Redis.
  - After connectivity loss while the in-memory `player-bootstrap` token survives, the client may request a fresh connect token, reconnect the WebSocket, then replay `LOGIN` and `PLAY` without prompting for credentials again. A full page reload loses that memory-only token and must restart at `POST /auth/player-bootstrap`, then rerun discovery before requesting a connect token; the architecture defines no hidden refresh credential. `WORLDS`, `REALMS <world>`, and `CHARS <world> [realm]` remain optional discovery helpers for returning members or grant-backed realms when the intended target is no longer unambiguous; first-time public-production character discovery follows the explicit `Join & Play` membership step. Game Session uses Redis gameplay session bindings, current membership authority, and fresh backend token rebinding to restore gameplay state when allowed by server-side TTLs and revocation rules.

- **Admin and creator UIs**  
  - Admin/creator interfaces authenticate through the Account Service (for example, via `/auth/login` exposed behind the Gateway). Successful login issues a short-lived `control-ui` JWT for control-plane APIs that represents a **control-plane browser session** for the current account.
  - A durable logout `PENDING` state rejects racing issuance, refresh, rebind, and installation for the fenced token lineage. It rejects every reconciliation except the matching Account lifecycle reconciliation that carries the exact opaque `logoutHandle`, request tuple, token identity/digest, and issuance fence for that pending operation. The handle never authenticates a request, restores authority, or returns a replacement credential.
  - `control-ui` JWTs are stored only in in-memory frontend state managed by the auth layer; they must not be written to `localStorage`, session storage, or exposed to third-party origins.
  - The frontend sends these tokens on meta/control API calls by setting the `Authorization: Bearer <token>` header on shared query/mutation requests. Backend services validate these JWTs with the shared `AuthTokenInterceptor` and enforce tenant access via the Tenant Authorization Contract described in [Authentication & Authorization](./system-architecture-authentication.md#tenant-authorization-contract).  
  - Logout clears the in-memory auth state and calls Account Service `POST /auth/logout` to revoke only the presented browser/device token; subsequent requests from that device require re-authentication, while other devices remain active. Account-wide “logout all devices” uses `POST /auth/logout-all` and terminates the account's control-plane, bootstrap, and active gameplay bindings across tenants. Closing a browser tab does not revoke auth token sessions; explicit logout is required to force server-side revocation before TTL expiry.

All new frontend features that interact with protected APIs should reuse the shared auth utilities and the canonical query/mutation transport helpers so token handling, logout, and error behavior remain consistent across player, admin, and creator experiences. In particular, the shared browser API layer should interpret canonical error codes from backend services as follows:

- `AUTH_TOKEN_EXPIRED` – Clear in-memory auth state, redirect to login, and show a “Session expired” message.
- `AUTH_SESSION_REVOKED` – Clear in-memory auth state, redirect to login, and show a security-focused message (for example, “You were signed out because your account security changed.”).
- `AUTH_UNAVAILABLE` – Keep in-memory auth state and show a retriable authentication-service availability message for ordinary API calls, including when live membership, entitlement, routing, or other required authority cannot be reached. Block only the affected authority-dependent or billing-unsafe mutation, including gameplay-admission mutations, until authority recovers; billing-safe operations that remain permitted under `TENANT_BILLING_BLOCKED` remain available unless they independently require the unavailable authority. Retry only idempotent reads, or mutations carrying the same stable request idempotency key that the service durably deduplicates, with at most one automatic retry inside a five-second total deadline and bounded backoff. The dedicated logout controller uses its separate bounded logout/revocation budget and is excluded from this generic retry rule. Never replay an unkeyed or non-idempotent mutation, and do not convert this infrastructure failure into logout.
- `TENANT_BILLING_BLOCKED` – Keep the user logged in, but mark the affected tenant as billing-blocked in UI state, show a prominent billing banner, and disable gameplay or instance-management actions for that tenant while still allowing billing-safe operations (such as viewing invoices or updating payment details). Apply this code only when authoritative Account evidence explicitly reports the billing block; `AUTH_UNAVAILABLE` takes precedence when that authority evidence cannot be obtained and must not be converted into a billing block.
- `ADMISSION_POINTER_UNAVAILABLE` – Keep the current auth state, show a retriable gameplay-admission unavailable message, rerun bootstrap discovery, and only then retry lobby admission with bounded backoff; never substitute a cached tenant or runtime target.
- `CONNECT_CONTEXT_INVALID` – Keep the current auth state, force gameplay reconnect flow (`connect-token` refresh + new socket + `LOGIN`), and block `PLAY` retries on the current socket.
- `CONNECT_SCOPE_MISMATCH` – Keep the current auth state, discard the entire discovery snapshot bundle and all connect-token metadata derived from it, rerun bootstrap discovery, prompt world/session re-selection when needed, request a fresh connect token only from the newly returned bundle, and retry on a new socket.

Client rule for discovery-issued selectors:

- Treat `connectScopeId`, `pointerVersion`, `evaluatedAt`, and `connectScopeExpiresAt` as one short-lived snapshot bundle for the selected realm target.
- Do not mix a stale `connectScopeId` with newly cached realm metadata or vice versa.
- When `connectScopeExpiresAt` has passed, rerun discovery before requesting another connect token even if the visible selection has not changed.

For gameplay WebSocket handshake failures on `/ws/game/**`, first-party clients must also differentiate HTTP `403` handshake classes:

- `CONNECT_TOKEN_MISSING`: request a fresh connect token and retry the socket open with bounded backoff.
- `CONNECT_TOKEN_EXPIRED`: request a fresh connect token and retry the socket open with bounded backoff.
- `CONNECT_TOKEN_REPLAYED`: request a fresh connect token and retry with bounded backoff; repeated replay failures should surface a session-recovery action instead of fast-looping.
- `CONNECT_SCOPE_MISMATCH`: discard the entire failed discovery snapshot bundle and all connect-token metadata derived from it, rerun bootstrap discovery, request a fresh connect token from the newly returned scope, and retry on a new socket. Do not reuse any failed bundle field or infer a target from cached tenant/realm fields.
- `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`: keep the current auth state, show a temporary edge-auth-unavailable message, and retry with slower bounded backoff.
- `CONNECT_TOKEN_REJECTED`: request a fresh connect token and retry with bounded backoff; if repeated after refresh, restart the first-party bootstrap flow.
- `POLICY_DENY`: treat as non-retriable until configuration is corrected and surface an actionable error.

These handshake classes are edge-handshake outcomes, not gameplay text-protocol `ERROR <CODE>` frames. Clients only start handling protocol-level `ERROR <CODE>` responses after the WebSocket has been established and `LOGIN`/`PLAY` exchange begins.

Canonical first-party browser reconnect sequence:

The internal `tenantId` and `gameInstanceId` values in this example use the UUID-governed target-state contract. Some current runtime DTOs remain numeric migration gaps; clients must use the stable world/realm selectors and opaque `connectScopeId` rather than depending on either representation.

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

GET /auth/bootstrap/worlds/demo/realms/production/characters?connectScopeId=cs_demo_production_v17
Authorization: Bearer <bootstrapToken>
-> [{ characterName: "Mara" }]

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_production_v17", requestId: "req-reconnect-1" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, expiresAt, tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120", realmSlug: "production", gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01", jti, issuedAt }

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
-> { accountId, expiresAt, tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120", realmSlug: "production", gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01", jti, issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response

LOGIN
OK LOGIN Logged in
PLAY demo production Mara
OK PLAY Resumed session
```

This reconnect flow assumes the player still holds a valid in-memory `player-bootstrap` token. If the bootstrap token has expired or the page was reloaded, the client must obtain a new bootstrap token first, then repeat bootstrap discovery as needed and request a fresh connect token. There is no separate silent refresh/bootstrap-restoration mechanism in the current architecture; reload behaves like bootstrap-token loss and restarts the first-party bootstrap flow. In all cases, first-party reconnects must not prompt the browser to replay username/password/OTP after bootstrap has been re-established. Clients may skip visible `WORLDS` / `REALMS <world>` / `CHARS <world> [realm]` steps when they already retain a valid world/realm/character choice and still drive the same canonical `PLAY <world> [realm] [character]` selection on reconnect.

If any reconnect attempt returns `CONNECT_SCOPE_MISMATCH`, the shortcut above is no longer valid: the client must discard the entire cached discovery snapshot bundle and all connect-token metadata derived from it, rerun bootstrap discovery, and use only the newly returned world/realm/character and scope values. No field from the failed bundle, including `connectScopeId`, `tenantId`, `realmSlug`, `gameInstanceId`, `pointerVersion`, `evaluatedAt`, or `connectScopeExpiresAt`, is sufficient to reconstruct or attest a target.

The logout ordering above uses `reconciliation` to mean an unauthenticated or identity-recreating attempt. A matching lifecycle reconciliation through the Account-issued opaque `logoutHandle`, exact request tuple, and current fence is explicitly permitted while `PENDING`; it may only complete or abort that logout operation and can never restore authority or issue a replacement credential.

## API Usage Patterns

All API communication should be handled by **TanStack Query**-backed fetch/mutation helpers defined in `src/api/` or feature-local query modules. Endpoints such as bootstrap discovery, login/logout, world browsing, and admin control-plane reads should follow the same typed-query pattern.

`TanStack Query` is responsible for:

- Data caching and revalidation
- Request deduplication
- Error and loading state tracking
- Background polling and refetching

WebSocket interactions for real-time gameplay are handled by `src/websocket.ts`, which manages the connection lifecycle and message routing. Query-backed browser state should react to socket events through targeted cache updates or invalidation rather than an assumed global Redux store.

## Hosting Direction

FireMUD has two different priorities in this area:

- The first reviewer-usable preview proof path should be **TCP/Telnet first**, because that is the lowest-friction manual proof path for core MUD play and does not require browser-bootstrap work before hosted validation is possible.
- The long-term first-party browser direction should still be a **dedicated first-party web application service**, not product-specific frontend/helper logic embedded permanently in Spring Cloud Gateway.

This means the intended sequence is:

1. Hosted preview deployment supports manual `LOGIN -> PLAY -> LOOK` proof over the TCP Proxy Service.
2. A dedicated first-party web application service is introduced after that preview proof path is working.
3. The first UI hosted by that service may simply be a terminal-style browser client, with richer first-party UI following later.

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

**Playwright** tests exercise key flows by starting the Docker Compose stack and running a headless browser against the web client.

---

This architecture keeps the web client modular and maintainable while aligning with the backend microservices. Additional frontend services or features should follow the same separation of concerns: browser assets and first-party web bootstrap belong in the dedicated web application service, while Spring Cloud Gateway remains the public API/gameplay edge.

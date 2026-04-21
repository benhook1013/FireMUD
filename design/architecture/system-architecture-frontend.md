# FireMUD System Architecture: Frontend Architecture

This document describes the structure and tooling for FireMUD's browser-based user interfaces. The `web-client` module houses the player-facing React application built with **Vite** and **TypeScript**. FireMUD's target architecture is a dedicated first-party web application service that owns browser asset hosting and first-party web bootstrap concerns rather than treating Spring Cloud Gateway as the long-term home for frontend assets or product-specific browser logic. The first practical version of that service may be a terminal-style browser client before richer web UX is added. Additional React modules for the admin tools and Game Design interface are available.

Other UIs include a role-based admin interface and a game design editor. See [Role-Based Admin UI](./microservices/logging-admin-service/admin-ui.md) and [Web-Based Visual Design Interface](./microservices/game-design-service/web-visual-interface.md).

---

## Component Hierarchy

FireMUD uses React components with a **feature-first** organization. Each feature folder contains its own components, tests, and styling.

```text
web-client/
  src/
    features/
      account/
        AccountPage.tsx
        accountSlice.ts  # local UI state
        accountApi.ts    # RTK Query endpoints
      gameplay/
        GameplayPage.tsx
        gameplaySlice.ts
        gameplayApi.ts
      ...
```

- **Pages** represent top-level routes and compose smaller **UI widgets**.
- Reusable UI elements live under a shared `components/` directory.
- Material-UI provides the base widgets and theme customization.

## State Management

Application state is handled by **Redux Toolkit**, with **RTK Query** used for data fetching and mutations. RTK Query auto-generates hooks for API access and manages caching, invalidation, and loading/error states declaratively.

- The global store is created in `src/store.ts` and injected via React's `<Provider>` in `src/main.tsx`.
- `setupListeners(store.dispatch)` enables automatic refetching on focus and reconnects.
- Components dispatch actions and select state using hooks (`useAppDispatch`, `useAppSelector`) from `src/hooks.ts`.
- RTK Query hooks expose typed endpoints that components call directly.

## Authentication and Session Handling

Frontend flows are split between **player gameplay sessions** and **admin/creator tools** so that gameplay auth remains simple while control-plane operations use JWTs:

- **Player UI (gameplay)**  
  - First-party browser clients are expected to be hosted by the dedicated first-party web application service, even when the first version is only a terminal-style browser client. Spring Cloud Gateway remains the public gameplay/API edge, but it should not become the long-term host for the player web application itself.  
  - First-party clients must first call the dedicated player-bootstrap endpoint (`POST /auth/player-bootstrap` or equivalent) with player credentials to establish short-lived account identity for gameplay bootstrap only, then use bootstrap-authenticated HTTP discovery endpoints to choose a caller-visible world/realm/character target, then obtain a short-lived connect token (`POST /auth/connect-token`) using the discovery-provided `connectScopeId` before opening `/ws/game/**`, then complete gameplay authentication by issuing `LOGIN` over the WebSocket channel using the already-verified bootstrap/connect context rather than replaying username/password/OTP from the browser, as described in [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow).  
  - Browser transport constraints matter: a browser WebSocket cannot set arbitrary custom handshake headers the way Telnet smart clients, Mudlet integrations, or server-side clients can. The first-party web flow therefore must use a browser-compatible gameplay handshake carrier such as query parameters, cookies, or another gateway-approved browser-safe mechanism rather than assuming custom WebSocket headers are available from JavaScript.  
  - The React client does not store or expose control-plane Browser JWTs for gameplay. It may hold only the short-lived, memory-only `player-bootstrap` token described in the authentication design and use it solely for gameplay bootstrap surfaces such as bootstrap discovery and `POST /auth/connect-token`.  
  - Explicit player logout must clear any in-memory `player-bootstrap` token immediately in addition to closing gameplay sockets and clearing reconnect state.
  - Canonical player logout order is: stop reconnect attempts, close the gameplay socket, clear remembered world/character selection plus reconnect metadata, clear the in-memory `player-bootstrap` token, and render the logged-out player state.
  - Tenant membership, non-public realm-access-grant checks, public-production first-join membership creation, and runtime entitlement checks for first-party gameplay happen during bootstrap discovery and `POST /auth/connect-token`, not during `POST /auth/player-bootstrap`. The first implementation is realm-aware: the chosen target must come from the canonical discovery contract and may be the production realm or an explicitly authorized alternate realm such as a playtest fork.  
  - The client must treat `connectScopeId` as an opaque short-lived selector, not as a stable saved identifier. If reconnect or retry reports `CONNECT_SCOPE_MISMATCH` or `ADMISSION_POINTER_UNAVAILABLE`, the client must rerun bootstrap discovery rather than guessing a replacement target from cached `tenantId` or `realmSlug` alone.  
  - After login, the client should be able to take the normal player happy path of `PLAY <world> [realm] [character]` directly, using `WORLDS`, `REALMS <world>`, and `CHARS <world> [realm]` only when discovery or ambiguity requires it. The Game Session Service resolves the selected world/realm/character into internal identifiers (`tenantId`, `gameInstanceId`, `characterId`), rechecks tenant authorization, public-production admission posture, and entitlements, returns the resolved realm bundle metadata, and then binds the socket to a gameplay session in Redis. Brand-new authenticated accounts can discover the default public production realm during this flow, and first-party membership creation is completed during `POST /auth/connect-token` before socket admission.  
  - On page reload or connectivity loss, the client requests a fresh connect token, reconnects the WebSocket, then replays `LOGIN` and `PLAY` without prompting for credentials again; `WORLDS`, `REALMS <world>`, and `CHARS <world> [realm]` remain optional discovery helpers when the intended target is no longer unambiguous. Game Session uses Redis gameplay session bindings, current membership authority, and fresh backend token rebinding to restore gameplay state when allowed by server-side TTLs and revocation rules.

- **Admin and creator UIs**  
  - Admin/creator interfaces authenticate through the Account Service (for example, via `/auth/login` exposed behind the Gateway). Successful login issues a short-lived JWT for control-plane APIs that represents a **control-plane browser session** for the current account.  
  - Admin JWTs are treated as **internal tokens** and are stored only in in-memory frontend state managed by the auth layer; they must not be written to `localStorage`, session storage, or exposed to third-party origins.  
  - The frontend sends these tokens on meta/control API calls by setting the `Authorization: Bearer <token>` header for RTK Query requests. Backend services validate these JWTs with the shared `AuthTokenInterceptor` and enforce tenant access via the Tenant Authorization Contract described in [Authentication & Authorization](./system-architecture-authentication.md#tenant-authorization-contract).  
  - Logout clears the in-memory auth state and calls the Account Service `POST /auth/logout` endpoint so server-side auth token sessions are revoked; subsequent requests require re-authentication. Account-wide “logout all devices” uses `POST /auth/logout-all`. Closing a browser tab does not revoke auth token sessions; explicit logout is required to force server-side revocation before TTL expiry.

All new frontend features that interact with protected APIs should reuse the shared auth utilities and RTK Query base configuration so token handling, logout, and error behavior remain consistent across player, admin, and creator experiences. In particular, the RTK Query base layer should interpret canonical error codes from backend services as follows:

- `AUTH_TOKEN_EXPIRED` – Clear in-memory auth state, redirect to login, and show a “Session expired” message.
- `AUTH_SESSION_REVOKED` – Clear in-memory auth state, redirect to login, and show a security-focused message (for example, “You were signed out because your account security changed.”).
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

- `CONNECT_TOKEN_REJECTED`: prompt a fresh gameplay handshake token acquisition and retry with bounded backoff.
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
-> { connectToken, expiresAt, tenantId: "tenant-demo", realmSlug: "production", gameInstanceId: "production" }

GET /ws/game/** with browser-compatible connect token carriage

LOGIN
OK LOGIN Logged in
PLAY demo production Mara
OK PLAY Entered world: Demo World / Live Realm as Mara

...socket drops...

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_production_v17", requestId: "req-reconnect-2" }
-> { connectToken, expiresAt, tenantId: "tenant-demo", realmSlug: "production", gameInstanceId: "production" }

GET /ws/game/** with browser-compatible connect token carriage

LOGIN
OK LOGIN Logged in
PLAY demo production Mara
OK PLAY Resumed session
```

This reconnect flow assumes the player still holds a valid in-memory `player-bootstrap` token. If the bootstrap token has expired or the page was reloaded, the client must obtain a new bootstrap token first, then repeat bootstrap discovery as needed and request a fresh connect token. There is no separate silent refresh/bootstrap-restoration mechanism in the current architecture; reload behaves like bootstrap-token loss and restarts the first-party bootstrap flow. In all cases, first-party reconnects must not prompt the browser to replay username/password/OTP after bootstrap has been re-established. Clients may skip visible `WORLDS` / `REALMS <world>` / `CHARS <world> [realm]` steps when they already retain a valid world/realm/character choice and still drive the same canonical `PLAY <world> [realm] [character]` selection on reconnect.

## API Usage Patterns

All API communication is handled by **RTK Query** services defined in `src/api/`. Endpoints like login and character retrieval are implemented in `firemudApi.ts`, and additional APIs follow the same pattern.

RTK Query automatically handles:

- Data caching and revalidation
- Request deduplication
- Error and loading state tracking
- Background polling and refetching

WebSocket interactions for real-time gameplay are handled by `src/websocket.ts`, which manages the connection lifecycle and message routing. Integration with RTK Query updates cached data in response to socket events.

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

RTK Query works out of the box with Redux Toolkit and TypeScript. API code generation and mocking can be extended using **msw** (Mock Service Worker) for testing.

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

# FireMUD System Architecture: Frontend Architecture

This document describes the structure and tooling for FireMUD's browser-based user interfaces. The `web-client` module houses the player-facing React application built with **Vite** and **TypeScript**. Compiled assets are served by the Spring Cloud Gateway so all frontends share a common entry point. Additional React modules for the admin tools and Game Design interface are available.

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
  - First-party clients must obtain a short-lived connect token (`POST /auth/connect-token`) before opening `/ws/game/**`, then authenticate to gameplay by issuing the `LOGIN` command over the WebSocket channel, as described in [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow).  
  - The React client does not store or expose internal JWTs; instead it maintains a WebSocket connection to the Game Session Service through the Gateway, and the Game Session Service binds that socket to a gameplay session in Redis.  
  - After login, the client participates in an explicit **lobby world-selection** step by issuing `WORLDS` / `CHARS <world>` and then `PLAY <world> [character]` as described in [Tenant Selection for Gameplay](./system-architecture-authentication.md#tenant-selection-for-gameplay-lobby-selection). The Game Session Service resolves the selected world/character into internal identifiers (`tenantId`, `gameInstanceId`, `characterId`), enforces tenant authorization and entitlements, and then binds the socket to a gameplay session in Redis.  
  - On page reload or connectivity loss, the client requests a fresh connect token, reconnects the WebSocket, then replays the `LOGIN` and tenant-selection flow; the Game Session Service uses Redis gameplay session bindings and auth token sessions to restore gameplay state when allowed by server-side TTLs and revocation rules.

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

For gameplay WebSocket handshake failures on `/ws/game/**`, first-party clients must also differentiate HTTP `403` causes:

- Connect-token missing/expired/replayed in enforced environments: prompt a fresh gameplay handshake token acquisition and retry with bounded backoff.
- Trust-boundary/policy denial (for example mTLS/internal-listener mismatch): treat as non-retriable until configuration is corrected and surface an actionable error.

## API Usage Patterns

All API communication is handled by **RTK Query** services defined in `src/api/`. Endpoints like login and character retrieval are implemented in `firemudApi.ts`, and additional APIs follow the same pattern.

RTK Query automatically handles:

- Data caching and revalidation
- Request deduplication
- Error and loading state tracking
- Background polling and refetching

WebSocket interactions for real-time gameplay are handled by `src/websocket.ts`, which manages the connection lifecycle and message routing. Integration with RTK Query updates cached data in response to socket events.

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

See [Game Customization Options](./game-customization-options.md) for the broader design.
Game-specific themes rely on the multi-tenant model described in [Multi-Tenancy](./system-architecture-multi-tenancy.md).

FireMUD aims to let each hosted game supply its own UI styling and layout tweaks.

- When a game version is published, branding assets and a `manifest.json` are
  uploaded to tenant- and version-scoped object storage (e.g., S3, MinIO, or a
  CDN).
- Published version metadata stores the manifest URL. At runtime the React app
  fetches this manifest to load logos, favicons, theme JSON, and optional route
  definitions, then applies Material-UI overrides.
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

This architecture keeps the web client modular and maintainable while aligning with the backend microservices. Additional frontend services or features can follow the same patterns for consistency.

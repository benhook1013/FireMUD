# 🎨 FireMUD System Architecture: Frontend Architecture

This document describes the structure and tooling for FireMUD's browser-based user interfaces. The `web-client` module houses the player-facing React application built with **Vite** and **TypeScript**. Compiled assets will ultimately be served by the Spring Cloud Gateway so all frontends share a common entry point. Additional React modules for the admin tools and Game Design interface are planned. (TODO: Not yet implemented)

Other planned UIs include a role-based admin interface and a game design editor. See [Role-Based Admin UI](./microservices/logging-admin-service/admin-ui.md) and [Web-Based Visual Design Interface](./microservices/game-design-service/web-visual-interface.md). (TODO: Not yet implemented)

---

## 📐 Component Hierarchy

FireMUD uses React components with a **feature-first** organization. Each feature folder contains its own components, tests, and styling. The current code base still uses a flatter structure under `web-client/src/` and will transition to this layout. (TODO: Not yet implemented)

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

- **Pages** represent top-level routes and compose smaller **UI widgets**. (TODO: Not yet implemented)
- Reusable UI elements live under a shared `components/` directory. (TODO: Not yet implemented)
- Material-UI provides the base widgets and theme customization.

## ⚛️ State Management

Application state is handled by **Redux Toolkit**, with **RTK Query** used for data fetching and mutations. RTK Query auto-generates hooks for API access and manages caching, invalidation, and loading/error states declaratively.

- The global store is created in `src/store.ts` and injected via React's `<Provider>` in `src/main.tsx`.
- `setupListeners(store.dispatch)` enables automatic refetching on focus and reconnects.
- Components dispatch actions and select state using hooks (`useAppDispatch`, `useAppSelector`) from `src/hooks.ts`.
- RTK Query hooks expose typed endpoints that components call directly.

## 🔗 API Usage Patterns

All API communication is handled by **RTK Query** services defined in `src/api/`. Currently only a few example endpoints live in `firemudApi.ts`; additional APIs such as login and character retrieval will follow the same pattern. (TODO: Not yet implemented)

RTK Query automatically handles:

- Data caching and revalidation
- Request deduplication
- Error and loading state tracking
- Background polling and refetching

WebSocket interactions for real-time gameplay are handled by `src/websocket.ts`, which manages the connection lifecycle and message routing. Integration with RTK Query to update cached data in response to socket events is planned. (TODO: Not yet implemented)

## 🛠️ Build Tooling

The frontend uses **Vite** for fast development and production builds:

- `npm run dev` starts the local development server with hot module replacement.
- `npm run build` produces an optimized bundle under `dist/`.
- `npm run preview` serves the production bundle locally for verification.
- `npm run test` will run unit tests with Jest and React Testing Library. The script is not yet defined. (TODO: Not yet implemented)
- `npm run lint` and `npm run format` ensure consistent code style.
- `npm run format:fix` writes formatting changes back to disk.
- `npm run accessibility` audits the compiled site with axe-core. See [Developer Setup](../../DEVELOPER_SETUP.md#frontend-lint--accessibility) for Chrome requirements.

See `web-client/README.md` for additional setup tips.

TypeScript configuration lives in `tsconfig.json`, and ESLint/Prettier enforce coding standards consistent with the rest of the project.

RTK Query works out of the box with Redux Toolkit and TypeScript. API code generation and mocking can be extended using **msw** (Mock Service Worker) for testing. (TODO: Not yet implemented)

## 🎨 Game-Specific Customization (Planned) (TODO: Not yet implemented)

See [Game Customization Options](./game-customization-options.md) for the broader design.
Game-specific themes rely on the multi-tenant model described in [Multi-Tenancy](./system-architecture-multi-tenancy.md).

FireMUD aims to let each hosted game supply its own UI styling and layout tweaks.

- When a game version is published, branding assets and a `manifest.json` are
  uploaded to tenant- and version-scoped object storage (e.g., S3, MinIO, or a
  CDN).
- Published version metadata stores the manifest URL. At runtime the React app
  fetches this manifest to load logos, favicons, theme JSON, and optional route
  definitions, then applies Material-UI overrides.
- Assets are loaded directly from the CDN; the Game Design Service is never
  queried during gameplay.
- If the manifest omits an asset, the default platform styling is used.
- Core components remain shared so feature updates reach all games without
  forks. (TODO: Not yet implemented)

## 🌍 Internationalization Strategy (TODO: Not yet implemented)

The React client uses **react-i18next** to load translation JSON files at runtime. Players select a language in the settings menu, and the UI strings update without a page reload. Locale files live under `src/i18n/` and can be extended by hosted games.

## 🧪 End-to-End Testing (TODO: Not yet implemented)

After the UI stabilizes, **Playwright** tests will exercise key flows by starting the Docker Compose stack and running a headless browser against the web client.

---

This architecture keeps the web client modular and maintainable while aligning with the backend microservices. Additional frontend services or features can follow the same patterns for consistency.

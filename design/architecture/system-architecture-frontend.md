# 🎨 FireMUD System Architecture: Frontend Architecture

This document describes the structure and tooling for FireMUD's browser-based user interfaces. FireMUD uses React and Material‑UI; this guide explains how the components, state management, and API calls are organized.

---

## 📐 Component Hierarchy

FireMUD uses React components with a **feature-first** organization. Each feature folder contains its own components, tests, and styling. The current code base still uses a flatter structure and will transition to this layout. (TODO: Not yet implemented)

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

- The global store is created in `src/store.ts` and provided via `<Provider>`.
- `setupListeners(store.dispatch)` enables automatic refetching on focus and reconnects.
- Components dispatch actions and select state using hooks (`useAppDispatch`, `useAppSelector`) from `src/hooks.ts`.
- RTK Query hooks expose typed endpoints that components call directly.

## 🔗 API Usage Patterns

All API communication is handled by **RTK Query** services defined in `src/api/`. These services generate typed React hooks (for example, `useLoginMutation` and `useFetchCharacterQuery`) that components call directly. (TODO: Not yet implemented)

RTK Query automatically handles:

- Data caching and revalidation
- Request deduplication
- Error and loading state tracking
- Background polling and refetching

WebSocket interactions for real-time gameplay are handled by `src/websocket.ts`, which manages the connection lifecycle and message routing. RTK Query hooks can be invalidated or updated in response to WebSocket messages for live state updates (TODO: Not yet implemented).

## 🛠️ Build Tooling

The frontend uses **Vite** for fast development and production builds:

- `npm run dev` starts the local development server with hot module replacement.
- `npm run build` produces an optimized bundle under `dist/`.
- `npm run test` runs unit tests with Jest and React Testing Library. (TODO: Not yet implemented)

TypeScript configuration lives in `tsconfig.json`, and ESLint/Prettier enforce coding standards consistent with the rest of the project.

RTK Query works out of the box with Redux Toolkit and TypeScript. API code generation and mocking can be extended using **msw** (Mock Service Worker) for testing. (TODO: Not yet implemented)

## 🎨 Game-Specific Customization (Planned) (TODO: Not yet implemented)

See [Game Customization Options](./game-customization-options.md) for the broader design.

FireMUD aims to let each hosted game supply its own UI styling and layout tweaks.

- The React app will load theme files and configuration based on the game's `tenantId`. (TODO: Not yet implemented)
- Creators can override Material-UI themes, logos, and optionally define extra routes. (TODO: Not yet implemented)
- Core components remain shared so feature updates reach all games without forks. (TODO: Not yet implemented)

## 🌍 Internationalization Strategy (TODO: Not yet implemented)

The React client uses **react-i18next** to load translation JSON files at runtime. Players select a language in the settings menu, and the UI strings update without a page reload. Locale files live under `src/i18n/` and can be extended by hosted games.

## 🧪 End-to-End Testing (TODO: Not yet implemented)

After the UI stabilizes, **Playwright** tests will exercise key flows by starting the Docker Compose stack and running a headless browser against the web client.

---

This architecture keeps the web client modular and maintainable while aligning with the backend microservices. Additional frontend services or features can follow the same patterns for consistency.

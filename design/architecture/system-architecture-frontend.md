# 🎨 FireMUD System Architecture: Frontend Architecture

This document describes the structure and tooling for FireMUD's browser-based user interfaces. React and Material-UI are assumed, but this guide explains how the components, state management, and API calls are organized.

---

## 📐 Component Hierarchy

FireMUD uses React components with a **feature-first** organization. Each feature folder contains its own components, tests, and styling:

```text
frontend/
  src/
    features/
      account/
        AccountPage.tsx
        accountSlice.ts
      gameplay/
        GameplayPage.tsx
        gameplaySlice.ts
      ...
```

- **Pages** represent top-level routes and compose smaller **UI widgets**.
- Reusable UI elements live under a shared `components/` directory.
- Material-UI provides the base widgets and theme customization.

## ⚛️ State Management

Application state is handled by **Redux Toolkit**, with **RTK Query** used for data fetching and mutations. RTK Query auto-generates hooks for API access and manages caching, invalidation, and loading/error states declaratively.

- The global store is created in `src/store.ts` and provided via `<Provider>`.
- Components dispatch actions and select state using hooks (`useAppDispatch`, `useAppSelector`).
- RTK Query hooks expose typed endpoints that components call directly.

## 🔗 API Usage Patterns

All API communication is handled by **RTK Query** services defined in `src/api/`. These services generate typed React hooks (for example, `useLoginMutation` and `useFetchCharacterQuery`) that components call directly.

RTK Query automatically handles:

- Data caching and revalidation
- Request deduplication
- Error and loading state tracking
- Background polling and refetching

WebSocket interactions for real-time gameplay are handled by `src/websocket.ts`, which manages the connection lifecycle and message routing. RTK Query hooks can be invalidated or updated in response to WebSocket messages for live state updates.

## 🛠️ Build Tooling

The frontend uses **Vite** for fast development and production builds:

- `npm run dev` starts the local development server with hot module replacement.
- `npm run build` produces an optimized bundle under `dist/`.
- `npm run test` runs unit tests with Jest and React Testing Library.

TypeScript configuration lives in `tsconfig.json`, and ESLint/Prettier enforce coding standards consistent with the rest of the project.

RTK Query works out of the box with Redux Toolkit and TypeScript. API code generation and mocking can be extended using **msw** (Mock Service Worker) for testing.

## 🎨 Game-Specific Customization (Planned)

FireMUD aims to let each hosted game supply its own UI styling and layout tweaks.

- The React app will load theme files and configuration based on the game's `tenantId`.
- Creators can override Material-UI themes, logos, and optionally define extra routes.
- Core components remain shared so feature updates reach all games without forks.

## 🌍 Internationalization Strategy

The React client uses **react-i18next** to load translation JSON files at runtime. Players select a language in the settings menu, and the UI strings update without a page reload. Locale files live under `src/i18n/` and can be extended by hosted games.

## 🧪 End-to-End Testing

After the UI stabilizes, **Playwright** tests will exercise key flows by starting the Docker Compose stack and running a headless browser against the web client.

---

This architecture keeps the web client modular and maintainable while aligning with the backend microservices. Additional frontend services or features can follow the same patterns for consistency.

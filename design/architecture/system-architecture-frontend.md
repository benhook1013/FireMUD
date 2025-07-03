# 🎨 FireMUD System Architecture: Frontend Architecture

This document describes the structure and tooling for FireMUD's browser-based user interfaces. React and Material-UI are assumed, but this guide explains how the components, state management, and API calls are organized.

---

## 📐 Component Hierarchy

FireMUD uses React components with a **feature-first** organization. Each feature folder contains its own components, tests, and styling:

```
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

Application state is handled by **Redux Toolkit** with slices per feature. Slices contain reducers, actions, and async thunks for API calls. This keeps business logic out of components and promotes predictable state updates.

- The global store is created in `src/store.ts` and provided via `<Provider>`.
- Components dispatch actions and select state using hooks (`useAppDispatch`, `useAppSelector`).
- Thunks interact with backend services through the API layer.

## 🔗 API Usage Patterns

All HTTP requests are made through a centralized wrapper built on **Axios**:

- `src/api/client.ts` configures the base URL and interceptors for authentication tokens and error handling.
- Feature slices call typed API helpers in `src/api/` (e.g., `loginUser`, `fetchCharacter`).
- Responses are converted into domain-specific models before updating state.

WebSocket interactions for real-time gameplay are handled by a single service in `src/websocket.ts` that manages connection lifecycle and message routing to Redux actions.

## 🛠️ Build Tooling

The frontend uses **Vite** for fast development and production builds:

- `npm run dev` starts the local development server with hot module replacement.
- `npm run build` produces an optimized bundle under `dist/`.
- `npm run test` runs unit tests with Jest and React Testing Library.

TypeScript configuration lives in `tsconfig.json`, and ESLint/Prettier enforce coding standards consistent with the rest of the project.

---

This architecture keeps the web client modular and maintainable while aligning with the backend microservices. Additional frontend services or features can follow the same patterns for consistency.

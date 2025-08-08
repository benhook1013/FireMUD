# Web Client Task List

## Core Features

- [ ] Build admin and Game Design interface modules alongside the player-facing client
- [ ] Transition to a feature-first folder structure under `src/features`
- [ ] Create `pages/` and shared `components/` directories
- [ ] Add RTK Query endpoints for login and character retrieval
- [ ] Integrate WebSocket events with RTK Query caches
- [ ] Implement `npm run test` with Jest and React Testing Library
- [ ] Extend API mocking using **msw**
- [ ] Display procedural generation previews with overlay layers

## Customization & Internationalization

- [ ] Load theme files via `manifest.json` per `tenantId` to support game-specific branding
- [ ] Allow Material-UI theme overrides and optional extra routes
- [ ] Keep core components shared so updates reach all games
- [ ] Add i18n support with `react-i18next`

## End-to-End Testing

- [ ] Add Playwright tests running against the Docker Compose stack

---

*Frontend tasks follow the same CI and linting conventions as backend services.*

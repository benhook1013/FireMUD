# FireMUD Web Client

This module contains FireMUD's browser client work. Today it is still a lightweight starter application, but the intended frontend baseline is already decided:

- **React**
- **TypeScript**
- **Vite**
- **Material UI**
- **TanStack Query** for server state

## Current Direction

The repo's canonical frontend direction is:

- use `TanStack Query` for reads, mutations, caching, invalidation, polling, and retry behavior;
- keep local component/form/editor state close to the owning feature;
- introduce Redux only if a later slice proves a real shared client-state problem that local feature state plus `TanStack Query` no longer solves cleanly.
- preserve the existing public base while emitting compiled assets under the reserved `/frontend-assets/**` prefix; published game assets remain under the separate `/assets/**` family.

See:

- [Frontend Architecture](../design/architecture/system-architecture-frontend.md)
- [Player Experience, Commands, and Communication implementation tracker](../design/project-management/implementation-tracking/player-experience-commands-and-communication.md)

The Vite production build writes compiled assets to `dist/frontend-assets/`, so generated compiled JavaScript/CSS references use `/frontend-assets/**`. The prefix is reserved for first-party compiled files, does not SPA-fallback, and is not a Gateway route. The static host/Ingress, origin separation, and full browser proof remain target-state work under [ADR 0144](../design/architecture/decisions/adr-0144-stateless-first-party-frontend-application-boundary.md).

The `/verify-email` and `/reset-password` browser routes are non-mutating email-link landings. They accept the link token only in a URL fragment, remove it from the visible address, and require an explicit submit before POSTing to Account through Gateway. This source-level flow does not make mailed links reachable in a deployed environment until the separate static host, SPA fallback, public site routing, and live browser-to-Gateway proof are in place. The existing Account token-consumption transaction remains the mutation authority.

## Local Development

Install dependencies from WSL/Linux-native Node.js:

```bash
npm ci
```

Common commands:

```bash
npm run dev
npm run test
npm run build
npm run preview
npm run lint
npm run format -- -c
npm run accessibility
```

The development server proxies `/api/**` to a locally running Gateway on port `8080`; its own landing pages are served on port `5173`. The mail URL defaults for local development point to that browser origin. A production deployment must configure the published static-site origin and preserve the fragment token without routing the browser GET to Account.

The accessibility audit depends on Google Chrome. See [Developer Setup](../DEVELOPER_SETUP.md#frontend-lint--accessibility) for the expected local toolchain and installation notes.

## Notes

- Treat this module as FireMUD application code, not a generic Vite template.
- Keep browser auth/session handling aligned with the canonical architecture docs.
- When frontend architecture changes, update both this README and the high-level docs in `design/architecture/`.

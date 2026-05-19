# FireMUD Web Client

This module contains FireMUD's browser client work. Today it is still a lightweight starter application, but the intended frontend baseline is already decided:

- **React**
- **TypeScript**
- **Vite**
- **Material UI**
- **TanStack Query** for server state

## Current Direction

The current source tree still carries a thin `Redux Toolkit + RTK Query` scaffold from the starter phase. That is not the long-term house style. The repo's canonical frontend direction is:

- use `TanStack Query` for reads, mutations, caching, invalidation, polling, and retry behavior;
- keep local component/form/editor state close to the owning feature;
- introduce Redux only if a later slice proves a real shared client-state problem that local feature state plus `TanStack Query` no longer solves cleanly.

See:

- [Frontend Architecture](../design/architecture/system-architecture-frontend.md)
- [02.21 Frontend Server-State Baseline and Query Convergence](../design/project-management/vertical-slices/02.21-task-list-frontend-server-state-baseline-and-query-convergence-vertical-slice.md)

## Local Development

Install dependencies from WSL/Linux-native Node.js:

```bash
npm ci
```

Common commands:

```bash
npm run dev
npm run build
npm run preview
npm run lint
npm run format -- -c
npm run accessibility
```

The accessibility audit depends on Google Chrome. See [Developer Setup](../DEVELOPER_SETUP.md#frontend-lint--accessibility) for the expected local toolchain and installation notes.

## Notes

- Treat this module as FireMUD application code, not a generic Vite template.
- Keep browser auth/session handling aligned with the canonical architecture docs.
- When frontend architecture changes, update both this README and the high-level docs in `design/architecture/`.

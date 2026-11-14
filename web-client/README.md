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

## Account email links

`/verify-email#token=…` and `/reset-password#token=…` capture and scrub the fragment before mounting. A user must explicitly submit the verification or new password; loading the document never dispatches a mutation. Completion uses the public Account POSTs via `/api/account/auth/`, with local Vite forwarding `/api` to Gateway on port 8080. Existing `/account/` hosting checkout routes remain separate. Run `npm run test:email-link` for the focused token, request and uncertain-result tests.

The owning [Account contract](../design/architecture/microservices/account-service/api-contracts.md) defines the behavior. Hosted static delivery and browser/SMTP proof are separate from these unit tests.

### Disabled static-host preparation

The recovered unprivileged static image and Helm host remain default-off behind both `previewStack.enabled` and `previewStack.frontend.enabled`. Only an explicit frontend enablement selects the SPA/API/WebSocket split; existing Gateway ingress is unchanged by default. Image publication and fresh browser/edge qualification remain prerequisites to activation. Nginx logs the path without query strings, emits no-referrer, refuses reserved API/asset/WebSocket paths and uses immutable content-hash caching.

The CSP permits only Stripe.js script/frame origins and its API connection needed by the existing next-action checkout adapter, following the [Stripe integration security guide](https://docs.stripe.com/security/guide#content-security-policy). It does not enable Link, Maps, Checkout-hosted or unrelated third-party scripts. These headers are prepared configuration, not deployed CSP/browser proof.

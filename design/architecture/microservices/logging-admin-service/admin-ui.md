# 🖥️ Role-Based Admin UI

This document outlines the current administration interface. There is
currently no dedicated **admin** React application in the repository. The
`web-client` module provides the main player-facing UI but does not expose the
tools described here. Moderators and administrators will eventually interact
with the service through a lightweight React interface.
Credentials are exchanged with the Account Service, which issues
JWTs for backend calls. These tokens remain server-side as described in
[Authentication & Authorization](../../system-architecture-authentication.md),
and permissions are enforced using the `globalRoles` claim.
The `scopedRoles` claim is supported.

## Features

- Search and filter logs with Kibana-like syntax.
- Review player reports and apply moderation actions.
- Toggle runtime feature flags for a specific tenant.
- Inspect saga workflows and view step details; ability to retry failed steps is available.
- Reference [Moderation Policies](./moderation-policies.md) when issuing bans or warnings.
- View analytics dashboards built with Grafana and Kibana.

These capabilities map to existing REST endpoints exposed by the service.
Planned routes include:

```text
GET  /logs
POST /reports
POST /moderation/actions
POST /feature-flags/toggle
GET  /sagas
GET  /sagas/{id}/steps
```

The backend implementations are present, but no admin user interface consumes them yet.

The UI will be packaged as a separate web module served by the Logging & Admin Service. Styling relies on Material‑UI components, and all API calls will be protected by the existing security interceptors described in the [Logging & Admin Service design](./README.md).

Backend endpoints for these features are already available as described in the [service design](./README.md), but the React interface itself has not been built.

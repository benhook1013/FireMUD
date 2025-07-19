# 🖥️ Role-Based Admin UI

This document outlines the **planned** administration interface. There is
currently no dedicated **admin** React application in the repository. The
`web-client` module provides the main player-facing UI but does not expose the
tools described here. Moderators and administrators will eventually interact
with the service through a lightweight React interface. (TODO: Not yet implemented)
Credentials are exchanged with the Account Service, which issues
JWTs for backend calls. These tokens remain server-side as described in
[Authentication & Authorization](../../system-architecture-authentication.md),
and permissions are enforced using the `globalRoles` and `scopedRoles` claims.
(TODO: Not yet implemented)

## Features

- Search and filter logs with Kibana-like syntax. (TODO: Not yet implemented)
- Review player reports and apply moderation actions. (TODO: Not yet implemented)
- Toggle runtime feature flags for a specific tenant. (TODO: Not yet implemented)
- Inspect saga workflows and view step details; ability to retry failed steps is planned. (TODO: Not yet implemented)
- Reference [Moderation Policies](./moderation-policies.md) when issuing bans or warnings. (TODO: Not yet implemented)

These capabilities map to existing REST endpoints exposed by the service.
Planned routes include:

```http
GET  /logs
POST /reports
POST /moderation/actions
POST /feature-flags/toggle
GET  /sagas
GET  /sagas/{id}/steps
```

The backend implementations are present, but no admin user interface consumes them yet. (TODO: Not yet implemented)

The UI will be packaged as a separate web module served by the Logging & Admin Service. Styling relies on Material‑UI components, and all API calls will be protected by the existing security interceptors described in the [Logging & Admin Service design](./README.md). (TODO: Not yet implemented)

Backend endpoints for these features are already available as described in the [service design](./README.md), but the React interface itself has not been built. (TODO: Not yet implemented)

# Role-Based Admin UI

This document outlines the administration interface delivered as a
lightweight React application. The `web-client` module provides the main
player-facing UI while the admin interface is served separately by the
Logging & Admin Service. Moderators and administrators interact with the
service through this interface, which exchanges credentials with the
Account Service. JWTs for backend calls remain server-side as described in
[Authentication & Authorization](../../system-architecture-authentication.md),
and permissions are enforced using the `globalRoles` claim. The
`scopedRoles` claim is supported.

## Features

- Search and filter logs with Kibana-like syntax.
- Review player reports and record moderation policy actions. The current moderation endpoint persists policy input and audit only; owner-side enforcement is a target-state path.
- Toggle runtime feature flags for a specific tenant.
- Issue tick-remediation pause and resume requests for operator-approved scopes.
- Inspect saga workflows and view step details.
- Reference [Moderation Policies](./moderation-policies.md) when recording policy actions and audit evidence; owner-side enforcement remains target coverage.
- View analytics dashboards built with Grafana and Kibana.

These capabilities map to REST endpoints exposed by the service. The route set includes both live read/observability surfaces and executable operator mutations; the current executable mutation set is limited to feature-flag, admission-pointer, and tick pause/resume forwarding.

Routes include:

```text
POST /logs/query
POST /reports
POST /moderation/actions
POST /feature-flags/toggle
POST /tick-remediation/pause
POST /tick-remediation/resume
GET  /sagas
GET  /sagas/{id}/steps
```

Current-state note: `POST /tick-remediation/pause` and `POST /tick-remediation/resume` are backed by live Logging & Admin forwarding endpoints. Quota override and broader remediation are coverage drift: no current routes or owner-side contracts exist, so the UI must not present placeholder controls.

`POST /moderation/actions` records moderation policy input and audit only. Owner-side gameplay/chat enforcement forwarding is target coverage and remains unavailable until the owning enforcement contracts exist. Log search, reports, saga inspection, admission-pointer reads/audit, and analytics dashboards remain read or investigation surfaces even when a corresponding mutation family is not implemented.

The UI is packaged as a separate web module served by the Logging & Admin Service. Styling relies on Material‑UI components, and all API calls are protected by the existing security interceptors described in the [API contracts](./api-contracts.md) and [runtime model](./runtime-and-data.md).

Backend endpoints for these features are available as described in the [API contracts](./api-contracts.md), and the React interface consumes them directly.

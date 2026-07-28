# Role-Based Admin UI

This document outlines the administration interface delivered as a lightweight React application. The `web-client` module provides the main player-facing UI while the admin interface is served separately by the Logging & Admin Service. Moderators and administrators interact with the service through this interface using the exact `control-ui` identity issued by Account. Every protected write must be authorized from the current server-side role and role-appropriate assurance plus the Account-issued authorization reference for that operation; JWT `globalRoles` or `scopedRoles` claims alone are insufficient. The short-lived JWT may be held in frontend memory, but authorization and authorization-reference validation remain server-side in the receiving service as described in [Authentication & Authorization](../../system-architecture-authentication.md).

## Implementation Status

- The admin UI is a lightweight operator surface. Its live scope is log search, saga inspection, analytics, feature-flag toggles, moderation policy/audit persistence, and tick-remediation pause/resume forwarding.
- Player report review is target coverage. The current `/reports` backend is an administrative persistence seam that trusts caller-supplied identities and returns a persisted report; this UI does not present it as player submission, and it is not evidence of a complete player report capability.
- Admission-pointer operations, session-lifecycle controls, broader remediation, owner-side moderation enforcement, and richer dashboards remain API-only or unavailable to this UI. The target inventory below remains canonical and must not be read as proof that those surfaces are live.

## Features

- Search and filter logs with Kibana-like syntax.
- Player report review is target coverage and is separate from the live moderation-action persistence path. The current administrative `/reports` persistence seam is not exposed by this UI as player submission; the target player route remains unavailable until player-bootstrap subject and tenant-membership binding exist. `POST /moderation/actions` independently records target-player moderation policy input and audit evidence only and does not issue bans or warnings or enforce owner-side moderation outcomes.
- Toggle runtime feature flags for a specific tenant.
- Issue tick-remediation pause and resume requests for operator-approved scopes.
- Inspect saga workflows and view step details.
- Reference [Moderation Policies](./moderation-policies.md) when recording policy input and audit evidence; owner-side enforcement remains target coverage.
- View analytics dashboards built with Grafana and Kibana.

These capabilities map to REST endpoints exposed by the service. The route set includes both live read/observability surfaces and executable operator mutations; the current UI-listed executable mutation set is limited to feature-flag and tick pause/resume forwarding.

Routes include:

```text
POST /logs/query
POST /moderation/actions
POST /feature-flags/toggle
POST /tick-remediation/pause
POST /tick-remediation/resume
GET  /sagas
GET  /sagas/{id}/steps
```

The live admission-pointer backend family is intentionally excluded from this UI inventory: no corresponding React/admin surface is implemented in this module. Operators must use the documented `/admission-pointers` API until that UI is implemented.

Current-state note: `POST /tick-remediation/pause` and `POST /tick-remediation/resume` are backed by live Logging & Admin forwarding endpoints. Game Session `/sessions*` lifecycle routes are current owner-local hooks, not current UI or Logging & Admin ingress. Quota override and broader remediation are coverage drift: no current routes or owner-side contracts exist, so the UI must not present placeholder controls.

`POST /moderation/actions` is a live persistence/audit-only support surface for a selected target player. It is independent of the current administrative `/reports` persistence seam and of the unavailable target player-submission route. The UI does not issue bans or warnings, and owner-side gameplay/chat enforcement forwarding remains unavailable until the owning enforcement contracts exist. Log search, saga inspection, admission-pointer reads/audit, and analytics dashboards remain read or investigation surfaces; this UI does not expose `POST /reports` as an investigation or player-submission surface.

The UI is packaged as a separate web module served by the Logging & Admin Service. Styling relies on Material‑UI components, and all API calls are protected by the existing security interceptors described in the [API contracts](./api-contracts.md) and [runtime model](./runtime-and-data.md).

Backend endpoints for the live UI-listed features are available as described in the [API contracts](./api-contracts.md). The React interface consumes only those documented UI routes directly. Other live backend surfaces, including administrative report persistence and admission-pointer operations, remain API-only until a UI is implemented; the target caller-bound player report route remains unavailable.

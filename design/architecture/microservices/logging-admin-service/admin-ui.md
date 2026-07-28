# Role-Based Admin UI

This document outlines the administration interface delivered as a lightweight React application. The `web-client` module provides the main player-facing UI while the admin interface is served separately by the Logging & Admin Service. Moderators and administrators interact with the service through this interface using the exact `control-ui` identity issued by Account. Every protected write must be authorized from the current server-side role and role-appropriate assurance plus the Account-issued authorization reference for that operation; JWT `globalRoles` or `scopedRoles` claims alone are insufficient. The short-lived JWT may be held in frontend memory, but authorization and authorization-reference validation remain server-side in the receiving service as described in [Authentication & Authorization](../../system-architecture-authentication.md).

## Implementation Status

- The admin UI is a lightweight operator surface. Its live scope is log search, saga inspection, analytics, feature-flag toggles, moderation policy evaluation/audit persistence, and per-instance tick-remediation pause/resume forwarding. Game Session consumes `GAMEPLAY_ADMISSION` decisions and Social & Groups consumes `CHAT_SEND` decisions at their owner boundaries; versioned propagation and broader enforcement remain unavailable.
- Player report review is target coverage. The current `/reports` backend is an administrative persistence seam that trusts caller-supplied identities and returns a persisted report; this UI does not present it as player submission, and it is not evidence of a complete player report capability.
- Admission-pointer reads, audit, and version-upgrade preparation/read remain API-only to this UI; same-target CAS open/close and prepared cutover mutations are target-only, as are session-lifecycle controls, broader remediation, versioned moderation propagation, and richer dashboards. Current runtime enforcement occurs in Game Session and Social & Groups from Logging & Admin policy evaluations; the target inventory below must not be read as proof that broader surfaces are live.

## Features

- Search and filter logs with Kibana-like syntax.
- Player report review is target coverage and is separate from the live moderation-action persistence path. The current administrative `/reports` persistence seam is not exposed by this UI as player submission; the target player route remains unavailable until player-bootstrap subject and tenant-membership binding exist. `POST /moderation/actions` independently persists and evaluates target-player moderation policy input and audit evidence only and does not issue bans or warnings or enforce owner-side moderation outcomes.
- Toggle runtime feature flags for a specific tenant.
- Issue per-instance tick-remediation pause and resume requests for a specific `<tenantId, gameInstanceId>` scope.
- Inspect saga workflows and view step details.
- Reference [Moderation Policies](./moderation-policies.md) when recording policy input and audit evidence; current owner-side enforcement uses synchronous policy evaluation, while versioned propagation remains target coverage.
- View analytics dashboards built with Grafana and Kibana.

These capabilities map to REST endpoints exposed by the service. The route set includes live read/observability surfaces and two distinct mutation categories: moderation persistence/audit and owner-forwarding mutations. Target or unavailable mutation families are not presented as UI controls.

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

Mutation inventory:

- Live persistence/audit mutation: `POST /moderation/actions` persists and evaluates moderation policy input and audit only; it does not call an enforcement owner or mutate `GAMEPLAY_ADMISSION`/`CHAT_SEND` enforcement state.
- Live owner-forwarding mutations: `POST /feature-flags/toggle` and per-instance `POST /tick-remediation/pause` and `POST /tick-remediation/resume` forward operator mutations to Game Session owner APIs.
- Live backend reads not presented by this UI: admission-pointer reads, audit, and version-upgrade preparation/read.
- Target-only or unavailable capabilities not presented by this UI: admission-pointer same-target CAS open/close and prepared cutover, session-lifecycle controls, quota overrides, broader remediation, and versioned moderation propagation.

The live admission-pointer reads, audit, and version-upgrade preparation/read endpoints are intentionally excluded from this UI inventory: no corresponding React/admin surface is implemented in this module. Operators must use the documented `/admission-pointers*` API family for those live reads and preparation operations. Same-target CAS open/close and prepared cutover mutations are target-only and are not current operator controls.

Current-state note: `POST /tick-remediation/pause` and `POST /tick-remediation/resume` are backed by live Logging & Admin forwarding endpoints for a specific `<tenantId, gameInstanceId>` scope. Game Session `/sessions*` lifecycle routes are current owner-local hooks, not current UI or Logging & Admin ingress. Quota override and broader remediation are coverage drift: no current routes or owner-side contracts exist, so the UI must not present placeholder controls.

`POST /moderation/actions` is a live policy-persistence/audit support surface for a selected target player. It is independent of the current administrative `/reports` persistence seam and of the unavailable target player-submission route. The UI does not directly perform runtime enforcement; Game Session and Social & Groups consume synchronous `EvaluateModerationPolicy` decisions, while versioned propagation remains unavailable. Log search, saga inspection, admission-pointer reads, audit, version-upgrade preparation/read, and analytics dashboards remain read or investigation surfaces; this UI does not expose `POST /reports` as an investigation or player-submission surface.

The UI is packaged as a separate web module served by the Logging & Admin Service. Styling relies on Material‑UI components, and all API calls are protected by the existing security interceptors described in the [API contracts](./api-contracts.md) and [runtime model](./runtime-and-data.md).

Backend endpoints for the live UI-listed features are available as described in the [API contracts](./api-contracts.md). The React interface consumes only those documented UI routes directly. Other live backend surfaces, including administrative report persistence and admission-pointer reads, audit, and version-upgrade preparation/read, remain API-only until a UI is implemented; same-target CAS open/close and prepared cutover remain target-only, and the target caller-bound player report route remains unavailable.

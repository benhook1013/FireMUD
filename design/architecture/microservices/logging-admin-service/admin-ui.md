# Role-Based Admin UI

This document defines the target administration interface. The repository does not currently contain a separate Logging & Admin React application or embedded-dashboard endpoints; the existing `web-client` remains the player-facing UI. When implemented, moderators and administrators interact with the admin interface using the exact `control-ui` identity issued by Account. Every protected write must be authorized from the current server-side role and role-appropriate assurance plus the Account-issued authorization reference for that operation; JWT `globalRoles` or `scopedRoles` claims alone are insufficient. The short-lived JWT may be held in frontend memory, but authorization and authorization-reference validation remain server-side in the receiving service as described in [Authentication & Authorization](../../system-architecture-authentication.md).

## Implementation Status

- The admin UI is target-only and unimplemented. Current backend investigation routes include the PostgreSQL-backed `/logs/query` read and saga inspection, but no current UI renders them and no Kibana/Grafana client or embedded-dashboard endpoint exists. The **Operator Mutation Support Gate** is a named three-part prerequisite: (1) action-family schema, (2) shared cross-language `mutationDigest/v1` golden vectors, and (3) Account-issued authorization-reference issuance. The gate has two redemption variants: the **receiving-service variant**, where Logging & Admin validates and redeems the reference for a Logging & Admin-owned persistence/audit mutation; and the **owner-side variant**, where the authoritative receiving owner validates and redeems it for a forwarded owner mutation. `/moderation/actions` uses the receiving-service variant; feature-flag toggles and per-instance tick-remediation pause/resume use the owner-side variant. The separate backend `EvaluateModerationPolicy` contract is consumed by Game Session for `GAMEPLAY_ADMISSION` and Social & Groups for `CHAT_SEND`; it is not an admin UI capability. Versioned propagation and broader enforcement remain unavailable.
- Player report review is target coverage. The unsafe former administrative `POST /reports` controller and Gateway route were removed, so public administrative `POST /admin/reports` persistence remains unavailable pending canonical authorization and live reference-validation checks. Any future administrative surface is reserved for `POST /admin/reports`; the separate player-bootstrap `POST /reports` route remains distinct, and this UI exposes neither route as a current capability.
- Admission-pointer reads, audit, and prepared-upgrade proof reads remain backend API-only; no current admin UI presents them. Public POST/operator mutation routes under `/admission-pointers`, `/cutover`, or `/version-upgrades` are target-only absent, including `POST /admission-pointers/version-upgrades`: no public HTTP handlers or Gateway write forwarding exist. Internal preparation/cutover implementation remains behind internal trust boundaries, with no current UI control. Session-lifecycle controls, future named typed owner recovery actions, versioned moderation propagation, and richer dashboards remain target-only. Current runtime enforcement occurs in Game Session and Social & Groups from Logging & Admin policy evaluations; the target inventory below must not be read as proof that broader surfaces are live.

## Target Features

- Search and filter logs through the selected profile's supported operator-query path. The current backend `/logs/query` seam is narrower: it queries the service-owned PostgreSQL `log_events` table by exact tenant and case-insensitive message containment and does not establish Kibana syntax or indexed-query support.
- Player report review and moderation policy input follow the availability and ownership described in [Implementation Status](#implementation-status) and [Moderation Policies](./moderation-policies.md); the UI does not expose them as current controls.
- Runtime feature-flag and per-instance tick-remediation capabilities are listed in [Implementation Status](#implementation-status) and are not rendered as controls until their owning mutation gate passes.
- Inspect saga workflows and view step details.
- Reference [Moderation Policies](./moderation-policies.md) for the target policy-input and audit contract when the gated action route is enabled; current owner-side enforcement uses synchronous policy evaluation, while versioned propagation remains target coverage.
- View the analytics dashboards advertised by the selected profile. Kibana and Grafana are the default indexed-profile target, not current embedded integrations or universal dependencies.

The backend routes below are current service APIs that a future UI may consume; they are not currently rendered by a separate admin application. Declared target or gated routes are documented separately as unavailable controls.

Current backend routes, not currently rendered by an admin UI:

```text
POST /logs/query
GET  /sagas
GET  /sagas/{id}/steps
```

Declared but not rendered by this UI:

```text
POST /moderation/actions (unavailable/gated)
POST /feature-flags/toggle (implemented fail-closed ingress stub, unavailable; no forwarding path)
POST /tick-remediation/pause (implemented fail-closed ingress stub, unavailable; no forwarding path)
POST /tick-remediation/resume (implemented fail-closed ingress stub, unavailable; no forwarding path)
POST /admission-pointers (target-only absent)
POST /admission-pointers/cutover (target-only absent)
POST /admission-pointers/version-upgrades (target-only absent)
```

Future recovery actions receive named typed endpoints only after their authoritative owner contracts exist; there is no reserved generic `remediate` endpoint or open-ended payload language.

Mutation inventory:

- Unavailable/gated mutation, not rendered: `POST /moderation/actions` currently hard fails closed with HTTP `503 Service Unavailable` and does not dispatch or persist policy input/audit. Once the receiving-service variant of the Operator Mutation Support Gate exists, it may persist the target policy input/audit; it does not require owner-side redemption, evaluate policy, call an enforcement owner, or mutate `GAMEPLAY_ADMISSION`/`CHAT_SEND` enforcement state. `EvaluateModerationPolicy` is the separate evaluation contract.
- Target-only absent public operator mutation: `POST /admission-pointers/version-upgrades` has no public HTTP handler or Gateway forwarding; internal preparation remains available behind internal trust boundaries. `GET /admission-pointers/version-upgrades/{tenantId}/{preparationId}` is the live read-only prepared-upgrade proof surface.
- Gated implemented fail-closed ingress stubs not presented as usable UI controls: `POST /feature-flags/toggle`, `POST /tick-remediation/pause`, and `POST /tick-remediation/resume` remain unavailable pending the owner-side variant of the Operator Mutation Support Gate; no Logging & Admin forwarding method is currently callable.
- Live backend reads not presented by this UI: admission-pointer reads, audit, and prepared-upgrade proof GET.
- Target-only absent public operator mutations not presented by this UI: admission-pointer open/close CAS and prepared cutover replacing `OPEN(old)` with `OPEN(new)`. Internal preparation/cutover implementation remains behind internal trust boundaries; there is no public HTTP handler, Gateway forwarding, or current UI control, and no separate retarget operation.
- Target-only or unavailable capabilities not presented by this UI: session-lifecycle controls, quota overrides, future named typed owner recovery actions, and versioned moderation propagation.

The live admission-pointer reads, audit, and prepared-upgrade proof GET are intentionally excluded from this UI inventory because no corresponding React/admin surface is implemented in this module. Operators must use the documented `/admission-pointers*` API family for those reads; the remaining route availability is defined in [Implementation Status](#implementation-status). There is no separate retarget operation.

Current-state ownership and availability for tick forwarding, Game Session `/sessions*` hooks, quota override, and future named typed owner recovery actions are defined in [Implementation Status](#implementation-status); the UI must not present placeholder or generic-remediation controls for those families.

`POST /moderation/actions` is an unavailable/gated policy-input persistence/audit route that currently hard fails closed before dispatch or persistence. Its availability, the separate `EvaluateModerationPolicy` owner-enforcement read, report-route distinctions, and the status of the remaining control-plane routes are defined in [Implementation Status](#implementation-status) and the [API contracts](./api-contracts.md#implementation-status). This UI does not expose either report route or target-only mutation as a current surface.

The target UI may be packaged as a separate web module served by the Logging & Admin Service. No such module or embedded-dashboard endpoint is currently implemented. Its API calls must use the security boundaries described in the [API contracts](./api-contracts.md) and [runtime model](./runtime-and-data.md).

Backend endpoint availability is defined in the [API contracts](./api-contracts.md). Any future React interface consumes only the routes explicitly admitted by this document. Availability and target-only route status are defined in [Implementation Status](#implementation-status); current backend availability is not evidence of a rendered admin UI.

# Realm Routing and Playable State

## Current Status

The canonical realm catalog, admission-pointer, bootstrap/connect-scope, session-freshness, runtime-projection, and currently live playable-state boundaries are implemented. The parent `09` family remains in progress only for later gameplay-state consumers, region-partitioned runtime ownership, and replacement-instance migration semantics; the `09.1`, `09.3`, `09.3.1`, and `09.4` boundaries listed in the index are complete at their recorded scope.

## Implementation Record Index

Use this index to locate the current domain capability. The detailed evidence preserves every allocated legacy source line and is intentionally kept in the same document for comparison.

| Capability and ownership focus | Source-declared status | Source range | Evidence |
| --- | --- | --- | --- |
| [`09` Multi-Tenancy, Realm Routing, and Runtime Boundaries](../vertical-slices/09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice.md) - Canonical realm-routing and playable-state source record | in progress | 1-72 | [source evidence](#source-09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice-1-72) |
| [Realm Catalog and Admission-Pointer Routing Vertical Slice](../vertical-slices/09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-170 | [source evidence](#source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170) |
| [Routing Bundle and Catalog Authority Follow-Through Vertical Slice](../vertical-slices/09.1.1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-80 | [source evidence](#source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80) |
| [Account Presence Runtime Authority Follow-Through Vertical Slice](../vertical-slices/09.1.10-task-list-account-presence-runtime-authority-follow-through-vertical-slice.md) - Runtime-routing and realm authority | complete at the current bounded boundary | 1-24, 40-73 | [source evidence](#source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73) |
| [Control-Plane Singular Routing Bundle Completeness Vertical Slice](../vertical-slices/09.1.11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-69 | [source evidence](#source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69) |
| [Command-Staging Current-Pointer Freshness Follow-Through Vertical Slice](../vertical-slices/09.1.12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-74 | [source evidence](#source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74) |
| [Websocket Bootstrap Ambiguity Collapse Follow-Through Vertical Slice](../vertical-slices/09.1.13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-74 | [source evidence](#source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74) |
| [Gameplay World Catalog Singular Authority Follow-Through Vertical Slice](../vertical-slices/09.1.14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-74 | [source evidence](#source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74) |
| [09.1.15 Task List: Complete Pointer Runtime-Target Follow-Through Vertical Slice](../vertical-slices/09.1.15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | implemented | 1-43 | [source evidence](#source-09-1-15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice-1-43) |
| [09.1.16 Task List: Control-Plane Complete Pointer Helper Follow-Through Vertical Slice](../vertical-slices/09.1.16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | implemented | 1-40 | [source evidence](#source-09-1-16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice-1-40) |
| [09.1.17 Task List: Shared Complete Pointer Helper Catalog Follow-Through Vertical Slice](../vertical-slices/09.1.17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | implemented | 1-42 | [source evidence](#source-09-1-17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice-1-42) |
| [09.1.18 Task List: Runtime Catalog Test Helper Convergence Vertical Slice](../vertical-slices/09.1.18-task-list-runtime-catalog-test-helper-convergence-vertical-slice.md) - Canonical realm-routing and playable-state source record | implemented | 1-47 | [source evidence](#source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47) |
| [09.1.19 Task List: Tick State Session Normalization Follow-Through Vertical Slice](../vertical-slices/09.1.19-task-list-tick-state-session-normalization-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-69 | [source evidence](#source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69) |
| [Pointer Authority Projection Follow-Through Vertical Slice](../vertical-slices/09.1.2-task-list-pointer-authority-projection-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-76 | [source evidence](#source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76) |
| [09.1.20 Task List: Session Authority Reader Convergence Vertical Slice](../vertical-slices/09.1.20-task-list-session-authority-reader-convergence-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-73 | [source evidence](#source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73) |
| [09.1.21 Task List: Login Failure Session Authority Follow-Through Vertical Slice](../vertical-slices/09.1.21-task-list-login-failure-session-authority-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-66 | [source evidence](#source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66) |
| [09.1.22 Task List: Gameplay Identity Reader Convergence Vertical Slice](../vertical-slices/09.1.22-task-list-gameplay-identity-reader-convergence-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-65 | [source evidence](#source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65) |
| [09.1.23 Task List: Websocket Bootstrap Session Authority Convergence Vertical Slice](../vertical-slices/09.1.23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-76 | [source evidence](#source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76) |
| [09.1.24 Task List: Canonical Admission Pointer Lookup Convergence Vertical Slice](../vertical-slices/09.1.24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-82 | [source evidence](#source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82) |
| [09.1.25 Task List: Fail-Closed Command Admission Without Session Authority Vertical Slice](../vertical-slices/09.1.25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-78 | [source evidence](#source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78) |
| [09.1.26 Task List: Fail-Closed TCP Proxy Disconnect Runtime Suspend Vertical Slice](../vertical-slices/09.1.26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-74 | [source evidence](#source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74) |
| [09.1.27 Task List: Fail-Closed Durable Command Routing Metadata Without Current Pointer Authority Vertical Slice](../vertical-slices/09.1.27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-74 | [source evidence](#source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74) |
| [09.1.28 Task List: Fail-Closed Remote Handoff Missing Durable IDs Vertical Slice](../vertical-slices/09.1.28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-71 | [source evidence](#source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71) |
| [09.1.29 Task List: Fail-Closed Remote Followup Scope-Only Routing Metadata Vertical Slice](../vertical-slices/09.1.29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-72 | [source evidence](#source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72) |
| [Communication Routing Availability Follow-Through Vertical Slice](../vertical-slices/09.1.3-task-list-communication-routing-availability-follow-through-vertical-slice.md) - Realm-routing availability and target authority | complete at the current bounded boundary | 1-14, 39-68 | [source evidence](#source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68) |
| [09.1.30 Task List: Fail-Closed Game Logic Outbound Attestation Normalization Vertical Slice](../vertical-slices/09.1.30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-72 | [source evidence](#source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72) |
| [09.1.31 Task List: Fail-Closed Session and First-Party Login Runtime-Target Authority Vertical Slice](../vertical-slices/09.1.31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-75 | [source evidence](#source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75) |
| [09.1.32 Task List: Authenticated TCP Proxy Disconnect Callbacks Vertical Slice](../vertical-slices/09.1.32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-70 | [source evidence](#source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70) |
| [09.1.33 Task List: Fail-Closed Login-Era Command Staging Runtime-Target Authority Vertical Slice](../vertical-slices/09.1.33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-75 | [source evidence](#source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75) |
| [09.1.34 Task List: Control-Plane Cutover Validator Canonical Pointer Read Vertical Slice](../vertical-slices/09.1.34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-65 | [source evidence](#source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65) |
| [09.1.35 Task List: Fail-Closed Tenant-Scoped Session Authority Reuse Vertical Slice](../vertical-slices/09.1.35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-71 | [source evidence](#source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71) |
| [09.1.36 Task List: Logout Runtime-Stop Selector Fallback Removal Vertical Slice](../vertical-slices/09.1.36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-69 | [source evidence](#source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69) |
| [09.1.37 Task List: Bootstrap Pointer Seed Model Isolation Vertical Slice](../vertical-slices/09.1.37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-71 | [source evidence](#source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71) |
| [09.1.38 Task List: Account Bootstrap Catalog Config Retirement Vertical Slice](../vertical-slices/09.1.38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-67 | [source evidence](#source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67) |
| [09.1.39 Task List: Admission-Pointer Selector Read API Removal Vertical Slice](../vertical-slices/09.1.39-task-list-admission-pointer-selector-read-api-removal-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-70 | [source evidence](#source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70) |
| [Tick State Routing Authority Follow-Through Vertical Slice](../vertical-slices/09.1.4-task-list-tick-state-routing-authority-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-63 | [source evidence](#source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63) |
| [09.1.40 Task List: World-Qualified Admission-Pointer Contract Repair Vertical Slice](../vertical-slices/09.1.40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-75 | [source evidence](#source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75) |
| [09.1.41 Task List: Operator Audit Pointer-Key Convergence Vertical Slice](../vertical-slices/09.1.41-task-list-operator-audit-pointer-key-convergence-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-72 | [source evidence](#source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72) |
| [09.1.42 Task List: Admission-Pointer Legacy Upsert Fallback Removal Vertical Slice](../vertical-slices/09.1.42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-68 | [source evidence](#source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68) |
| [09.1.43 Task List: Bootstrap Command Queue Target Convergence Vertical Slice](../vertical-slices/09.1.43-task-list-bootstrap-command-queue-target-convergence-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-73 | [source evidence](#source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73) |
| [09.1.44 Task List: TCP Proxy Disconnect Envelope Authority Follow-Through Vertical Slice](../vertical-slices/09.1.44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-71 | [source evidence](#source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71) |
| [09.1.45 Task List: Fail-Closed Remote Control-Plane Partial Routing Filters Vertical Slice](../vertical-slices/09.1.45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-72 | [source evidence](#source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72) |
| [09.1.46 Task List: Operator Runtime-State Read Convergence Vertical Slice](../vertical-slices/09.1.46-task-list-operator-runtime-state-read-convergence-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-89 | [source evidence](#source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89) |
| [Login Bootstrap Routing Authority Follow-Through Vertical Slice](../vertical-slices/09.1.5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-76 | [source evidence](#source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76) |
| [Runtime-State Routing Projection Follow-Through Vertical Slice](../vertical-slices/09.1.6-task-list-runtime-state-routing-projection-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-86 | [source evidence](#source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86) |
| [Command-Staging Runtime Authority Follow-Through Vertical Slice](../vertical-slices/09.1.7-task-list-command-staging-runtime-authority-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-76 | [source evidence](#source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76) |
| [Logout Runtime-Stop Authority Follow-Through Vertical Slice](../vertical-slices/09.1.8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-68 | [source evidence](#source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68) |
| [WebSocket Bootstrap Runtime Authority Follow-Through Vertical Slice](../vertical-slices/09.1.9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current bounded boundary | 1-78 | [source evidence](#source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78) |
| [Realm-Scoped Character and Playable State Policy Vertical Slice](../vertical-slices/09.3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at the current boundary | 1-77 | [source evidence](#source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77) |
| [09.3.1 Task List: Playable-State Family Namespace Follow-Through Vertical Slice](../vertical-slices/09.3.1-task-list-playable-state-family-namespace-follow-through-vertical-slice.md) - Canonical realm-routing and playable-state source record | complete at bounded target | 1-95 | [source evidence](#source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95) |
| [Bootstrap Discovery and Connect-Scope Resolution Vertical Slice](../vertical-slices/09.4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice.md) - Canonical realm-routing and playable-state source record | implemented at the current boundary | 1-64 | [source evidence](#source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64) |

## Canonical Design Sources

- [Multi-tenancy](../../architecture/system-architecture-multi-tenancy.md) defines global account identity, tenant membership, visible realms, admission, and realm-scoped gameplay state.
- [Authentication and authorization](../../architecture/system-architecture-authentication.md) defines bootstrap, connect-token, gameplay admission, and reconnect authority.
- [Game Session Service](../../architecture/microservices/game-session-service/README.md) owns the current realm catalog, admission pointers, session normalization, and runtime routing read/control-plane contracts.
- [Account Service](../../architecture/microservices/account-service/README.md) owns account membership, grants, bootstrap discovery, and connect-token issuance over Game Session authority.
- [Entity Management Service](../../architecture/microservices/entity-management-service/README.md) owns scoped playable-state resolution for character and stateful gameplay records.

## Consolidated Implementation Record

### Authority and Pointer Contract

Players address a visible `{tenantId, worldSlug, realmSlug}` realm, never a raw `gameInstanceId`. Game Session persists `gameplay_admission_pointer` rows and append-only audit events. A current pointer resolves one admissible runtime target and carries `pointerVersion` as the freshness token. Its authority data includes tenant, world, realm, runtime target, visibility/public-production classification, `stateScope`, character-selection requirements, and character-creation policy.

The canonical singular key is `{tenantId, worldSlug, realmSlug}`. `GetAdmissionPointer` reads direct pointer authority using that key; it does not reconstruct authority through browse projection. Account bootstrap admission, public-production membership creation, prepared cutover validation, live admission, and operator audit reads use the same world-qualified identity. Public membership replay/idempotency keys include `worldSlug`, so duplicate realm slugs in different worlds cannot collide. Pointer writes use the same tenant-qualified key, have no non-tenant upsert fallback, and use `expectedPointerVersion` compare-and-set protection.

Startup seeds pointers only when the persisted store is empty, using dedicated bootstrap pointer-seed properties. After seeding, runtime reads and mutations use persisted pointer authority. `GameplayCatalogProperties` is not production routing authority: Game Session's production catalog projection is pointer-backed, Account Service no longer ships the old local catalog config, and property-to-catalog construction remains only in explicit test support.

Game Session exposes canonical world, realm, and pointer reads plus pointer list, audit, runtime-state, prepared-upgrade, and cutover control-plane surfaces. The public selector-shaped singular helper and preferred runtime-target lookup have been removed; reverse runtime-target consumers use `listByRuntimeTarget(...)` so multiplicity is observable. Logging & Admin is an operator ingress over these contracts, not a second routing owner. Its audit route is `GET /admission-pointers/{tenantId}/{worldSlug}/{realmSlug}/audit`; authenticated tenant access is checked before the call, response tenant mismatches fail closed, and operator pointer mutation derives `actorPrincipal` from the authenticated session rather than caller input.

### Complete Authority and Ambiguity Rules

The shared `GameplayAdmissionPointerSnapshots` rule defines a complete pointer as one with positive `tenantId`, positive `gameInstanceId`, positive `pointerVersion`, nonblank `worldSlug`, `realmSlug`, and `stateScope`. Singular runtime-target authority exists only when the raw current-pointer result contains exactly one row and that row is complete. Any additional row, incomplete row, missing runtime identity, or missing scope makes the singular projection unavailable; incomplete rows are not discarded before multiplicity is assessed. Raw pointer lists still expose such rows for operator inspection.

`GameplayWorldCatalog` applies the shared completeness rule and additionally requires `characterCreationPolicy`. It groups rows by `{worldSlug, realmSlug}` and emits a visible catalog entry only for exactly one complete row. Reverse runtime-target lookup emits one visible identity only when exactly one realm survives for the tenant/runtime target; duplicate selector rows, duplicate runtime matches, and incomplete rows disappear from player-facing catalog projection rather than selecting an arbitrary row.

Admission-pointer `stateScope` is the canonical realm policy. Downstream `playableStateScope` is its resolved cross-service representation: `SHARED` maps to `PLAYABLE_STATE_SCOPE_SHARED` and `ISOLATED` maps to `PLAYABLE_STATE_SCOPE_ISOLATED`; it is not a second admission authority. If no single complete pointer can be proved, the singular downstream scope and routing bundle fail closed to unspecified/absent rather than reverse-inferring authority from `gameInstanceId`.

All routing-sensitive bundles preserve `tenantId`, `worldSlug`, `realmSlug`, resolved `gameInstanceId`, and `pointerVersion` together, with `characterSelection` and `playableStateScope` where applicable. Partial, blank, non-positive, stale, or selector-inconsistent bundles are not authoritative. At ingress, proxy and remote control-plane callers must provide `worldSlug`, `realmSlug`, and `pointerVersion` together or none; partial `ScheduleRemoteFollowup` input and partial remote coordinator/followup/result filters return `INVALID_ARGUMENT` instead of widening to an unscoped query. Persisted/read-model partial bundles collapse to absent rather than being projected as current identity.

### Admission, Membership, and Selection

Account-to-tenant membership is an explicit runtime substrate rather than an implicit `accounts.tenant_id` shortcut. The public default production realm is the only v1 public first-admission path. Account Service creates first-join membership through idempotent, auditable `EnsurePublicProductionPlayerMembership`; connect-token issuance and text `PLAY` use it. Non-public and non-production realms require the applicable existing membership or explicit Account-owned grant. Visibility, entitlement, membership/grant, and current-pointer truth are checked at admission, and failed or stale selection does not retain an earlier binding. Public-production classification is explicit pointer/catalog metadata, not `realmSlug == "production"`.

First-party bootstrap discovery exposes worlds, realms, and characters as the same catalog/admission projection used by `WORLDS`, `REALMS`, `CHARS`, and `PLAY`. Discovery returns opaque, short-lived `connectScopeId` plus `pointerVersion`, `evaluatedAt`, and `connectScopeExpiresAt`. `/auth/connect-token` revalidates current pointer and visibility/grant authority, treats `requestId` as an idempotency key, and replays the same token payload or deterministic failure for a live repeated attempt. Invalid scopes return `CONNECT_SCOPE_INVALID`; expired or cutover-mismatched scopes return `CONNECT_SCOPE_MISMATCH`, both directing the client to rerun discovery instead of using local target fallback.

Gateway and Game Session preserve and validate `worldSlug`, `realmSlug`, `connectScopeId`, `connectRequestId`, `pointerVersion`, and resolved target in first-party connect context. The durable bootstrap shell stores the selector identity and is the reconnect/login/`PLAY` fallback when transient registry state is absent. Generic websocket bootstrap preserves an incoming complete bundle, repairs a runtime-only shell only from one complete current pointer, and collapses ambiguous or incomplete authority to no bundle. If a reused shell had a bundle and the repaired incoming shell does not, it is a real route change: the `BOOTSTRAP_ROUTE_CHANGED` path clears authenticated/gameplay state instead of preserving the older route.

### Session Freshness and Runtime Entry Points

`SessionAuthenticationService` is the canonical higher-level session reader. Tenant-aware `resolveUnverifiedSessionContext(...)` applies stale-pointer normalization; raw `findBySessionId(...)` is only an index for tenant discovery and cannot revive a missing `{tenantId, sessionId}` authority row. Session-id replay, logout, tick-state, failed-login cleanup, and websocket bootstrap reuse consume the canonical helpers. Credential `LOGIN` resolves its bootstrap runtime only from that normalized shell and returns `SESSION_NOT_FOUND` when no bootstrap target survives; it never treats the transport `sessionId` as a runtime id. Gameplay-identity and gameplay-name helpers are also centralized there for `PLAY` existing-binding reuse, durable replay fallback, communication availability, and recipient delivery.

When a stored gameplay binding lacks the complete admitted bundle or no longer matches current pointer authority, normalization clears only the gameplay binding, keeps the account logged in, clears live gameplay presence, emits the bounded stale-pointer/region-exit lifecycle effect when applicable, and makes later gameplay fail closed at `PLAY_REQUIRED`. Reused-session relogin, account mismatch, missing first-party context, denied `LOGIN`, stale `PLAY`, and selector/route changes cannot preserve old authenticated or in-world state. A current runtime target is validated through `listByRuntimeTarget(...)` plus singular complete-pointer proof; ambiguous, missing, incomplete, or mismatched authority collapses binding or rejects first-party login rather than preserving continuity.

Command staging chooses an admitted gameplay runtime first, then an explicit bootstrap runtime only when the shell retains a complete bootstrap routing bundle; otherwise it fails closed with `NOT_FOUND`. It never treats a numeric `sessionId` as a runtime target. Current runtime-target authority repairs stale command metadata only when one complete pointer proves the target; missing, ambiguous, or incomplete authority persists `playableStateScope=UNSPECIFIED` with no world/realm/version bundle. Complete pre-`PLAY` shell metadata is freshness-checked before persistence. Durable command execution and selected-work manifests normalize/collapse the same bundle before replay or tick-batch sealing.

`QueryState(sessionId)` requires a normalized live session shell with positive tenant authority and never guesses a Redis key from a runtime id. Logout stops a runtime only when explicit scope or exactly one complete current isolated pointer positively proves that decision; missing, partial, or multiple pointers mean keep the runtime running. TCP Proxy disconnects suspend runtime state only with explicit `gameInstanceId`; advisory `sessionId` is never promoted, and missing runtime metadata remains best-effort session cleanup plus missing-context metering. Proxy producers and consumers preserve this envelope contract, and partial proxy routing headers are normalized to an unscoped bootstrap rather than forwarded as malformed authority. Authenticated TCP Proxy/Game Session callbacks and Game Session/Game Logic blocking clients use internal-service gRPC identity, with packaged-stack blocking stubs bound to the resolved customizer rather than a provider/no-op fallback.

### Durable Delegation and Control-Plane Projection

Game Session to Game Logic and Entity Management gameplay attestations carry `worldSlug`, `realmSlug`, `pointerVersion`, and `playableStateScope` as an all-or-none admitted claim; downstream guards reject partial claims. Gameplay-originated Automation ingress, timer and materialized schedule work, pin projections, script work/audit/handoff/dead-letter records, automation command handoff, and operator reads preserve the same bundle. Runtime-state projection also carries stored `runtimeRegionId` or `targetRegionId` hints where those consumers already own them, so region-aware plugin/policy preflight and handoff target reads do not collapse to game-instance-only lookup. Live and recent account/friend presence validates against singular current runtime authority; stale rows fall back to recent/offline projection rather than hiding a still-current session or inventing display metadata.

`GetGameInstanceRuntimeState` exposes `currentAdmissionPointers[]`. Its legacy singular `playableStateScope`, `worldSlug`, `realmSlug`, and `pointerVersion` fields are populated only for one complete current pointer; ambiguous or incomplete authority clears those fields while retaining the explicit list. Command-status and remote control-plane projections use the same rule and mark persisted routing stale when current singular authority cannot be proved. Logging & Admin exposes tenant-guarded `GET /admission-pointers/runtime-state/{tenantId}/{gameInstanceId}`, maps canonical runtime metadata, publication details, and current pointers, returns `404` for no runtime row, and rejects response-tenant mismatch as an internal contract failure.

Remote followup persistence and projection use one normalized `{playableStateScope, worldSlug, realmSlug, pointerVersion}` shape. Scope alone is not authoritative; partial stored rows, retry comparisons, coordinator/followup/result projections, and scheduling metadata collapse to no routing identity. Automation handoff success requires a non-error response with nonblank `coordinatorId` and `followupId`; otherwise the response is `REMOTE_RESPONSE_INVALID`, the work item becomes `DEAD_LETTERED`, and the audit outcome is `handoff_failed` rather than `HANDED_OFF`.

### Realm-Scoped Playable State

Tenant membership and character ownership do not imply a shared gameplay namespace. Each realm carries explicit `stateScope` and `characterCreationPolicy`; shared-state realms use a tenant-live `playableStateKey`, while isolated realms, including playtest forks, use an instance-local key. `CHARS` is a realm-local roster with explicit creation policy. The scope-aware roster contract is used by bootstrap character discovery, `REALMS`, `CHARS`, `PLAY`, and `TELL`; isolated realms cannot read or mutate the tenant's production roster through tenant-wide or bare-character shortcuts.

Account identity and account-level cross-game character discovery remain distinct from realm-local gameplay `CHARS`. The live `INVENTORY` surfaces are character gameplay inventory under the resolved playable-state namespace, not an Account-owned cross-game inventory; any future account inventory needs an explicit ownership and transfer contract rather than reuse of realm-local rows.

Entity Management provides shared `ScopedCharacterResolver` and `PlayableStateKeyResolver` boundaries and echoes the resolved scope to callers. The resolved `{tenantId, gameInstanceId, playableStateScope}` governs character reads, inventory, equipment, containers, room-ground items, friend links, progression/activity, actor resources, and active conditions. REST and gRPC state APIs carry the resolved target rather than bare tenant-plus-character IDs; cross-playable-state friend joins are rejected; actor state persists against the derived namespace rather than raw runtime id.

Automation faction reputation uses the same resolved namespace and requires `{tenantId, gameInstanceId, playableStateScope, characterId}`. Gameplay attestations preserve and validate scope in Entity Management. Automation ingress makes scope first-class, and durable work-item, ingress-audit, handler-audit, handoff-event, dead-letter, schedule-instance, and pin-projection records preserve it through timer refresh, requeue, control-plane readback, and presence/friend-presence projection. Shared and isolated behavior therefore remains distinct across the currently live roster, item, progression, resource/condition, faction, scripting, and presence surfaces.

### Recorded Proof

The evidence records focused proof for stale-pointer rejection at connect-token issuance, verified `LOGIN`, `PLAY`, reused relogin, websocket route/selector refresh, session normalization, command staging and replay, communication availability/delivery, presence, logout, tick-state, disconnect envelopes, remote routing metadata, complete-pointer catalog/control-plane projection, world-qualified duplicate-slug lookup, operator tenant guards, and shared-versus-isolated playable-state behavior. It also records touched-service `check -PfullCheck` runs, focused Game Session/Account/Entity Management/Automation/Logging & Admin/TCP Proxy/Game Logic tests, `linkCheck lintMarkdown`, and fresh rebuilt `bash dev-tools/verify-fresh-bootstrap.sh` proof covering WebSocket and Telnet bootstrap/login/play/LOOK, item/container/equipment reads, and authenticated disconnect cleanup. These are recorded validation facts, not a claim that this documentation pass reran all of them.

## Active Gaps

- Later gameplay-state families, especially loadouts, abilities, authored effects, richer resource tables, and any new character-creation or social/runtime consumer, still need explicit adoption of `{tenantId, gameInstanceId, playableStateScope}` before they become live. They must not reopen tenant-wide or bare-character shortcuts.
- Character ownership and account-to-character linkage remain an evolving Account/Entity Management seam. Character rows carry account and tenant identity, but neither membership nor ownership establishes one implicit gameplay namespace across realms.
- Future routing-sensitive delegated consumers, replay-sensitive caches, and new automation follow-up families need explicit proof that they preserve the complete bundle and fail closed; closed `09.1` seams do not imply unreviewed consumers are safe.
- Region-partitioned runtime ownership and broader region fencing are tracked separately from admission routing. Current runtime projections carry region hints where available but do not implement the final execution-partition topology.
- Replacement-instance migration/remap mechanics and domain-specific carry-forward rules remain unimplemented, including economy, guild, and combat-specific policy.
- The parent `09` source record still has an unchecked `./gradlew linkCheck lintMarkdown` item. It is a parent-family validation checkpoint, not evidence that the recorded completed child boundaries are unimplemented.

## To Discuss

No competing implementation is recorded for visible realm addressing, world-qualified admission-pointer authority, or shared-versus-isolated playable state. Design discussion is required before changing public admission policy, adding another isolated-state mode, defining replacement-instance migration/carry-forward rules, or introducing a gameplay-state family with different namespace semantics. The current open question is how future families and migration rules should preserve the established namespace and freshness fences without inventing a second authority model.

## Service and Contract Map

| Owner | Current responsibility | Primary contract boundary |
| --- | --- | --- |
| Game Session | Persisted realm catalog and admission pointers; audit/CAS/cutover; catalog projection; session normalization; runtime, tick, command, remote, and operator projections | World-qualified admission-pointer gRPC/control-plane APIs; persisted pointer authority; normalized session helpers |
| Account Service | Tenant membership/grants; bootstrap worlds/realms/characters; connect-token issuance; public-production membership | Bootstrap/auth REST and gRPC APIs; `EnsurePublicProductionPlayerMembership` |
| Gateway and TCP Proxy | Trusted first-party/Telnet routing propagation; explicit disconnect runtime metadata; authenticated callbacks | Connect context; all-or-none routing headers; `gameInstanceId` disconnect envelope |
| Entity Management | Scoped character, roster, item, friend, progression, resource, condition, and playable-state persistence | `{tenantId, gameInstanceId, playableStateScope}` APIs and attested gameplay calls |
| Automation Scripting | Scope- and routing-aware ingress, scheduling, durable work, handoff, dead-letter, and control-plane reads | Gameplay trigger/handoff contracts; normalized routing metadata |
| Social Groups | Live/recent friend-presence projection with realm and playable-state scope | Account-presence/friend-presence contracts |
| Game Logic | Preserve and validate complete routing/playable-state attestation for gameplay calls | Authenticated outbound gameplay gRPC |
| Logging & Admin | Tenant-guarded pointer audit, cutover, and runtime-state operator reads | REST over Game Session control-plane authority |

## Source Evidence

The following records are the unchanged line-preserving transposition used as the audit backstop for the consolidated record above. Heading depth is shifted by three levels and same-directory Markdown links are rebased only so the combined tracker remains valid and navigable.

### source-09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice-1-72

#### `09` Multi-Tenancy, Realm Routing, and Runtime Boundaries - Canonical realm-routing and playable-state source record (source lines 1-72)

##### Preserved Source Text: source-09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice-1-72

<!-- migration-source path="design/project-management/vertical-slices/09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice.md" lines="1-72" sha256="dc884cc0dd098858c8b6a8570a45a77ae69cf5033b6d50cd839fcf7dcf02133d" heading-offset="3" -->
#### source-09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice-1-72: `09` Multi-Tenancy, Realm Routing, and Runtime Boundaries

Goal: translate FireMUD's multi-tenancy and realm-routing architecture into one explicit slice family so tenant identity, player-addressable realms, admission-pointer resolution, public-production access, and realm-scoped runtime-state policy do not keep leaking across login, reconnect, bootstrap, and activation work as implicit assumptions. Status: in progress.

##### source-09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice-1-72: Implementation Notes

This domain is already materially designed:

- `tenantId`, `tenantSlug`, `gameInstanceId`, realm selection, and player-addressable routing rules are defined in the multi-tenancy architecture.
- public-production admission versus explicitly granted non-production realms is already called out.
- the runtime contracts for `GetAdmissionPointer`, `EnsurePublicProductionPlayerMembership`, bootstrap discovery, and connect-token issuance are already described.
- the distinction between tenant-scoped identity and realm- or instance-scoped playable state is already locked, including shared-state versus isolated-state realms.

The first implementation cut is now real:

- account-to-tenant membership is now an explicit runtime substrate instead of piggybacking on `accounts.tenant_id`;
- bootstrap discovery and in-band lobby discovery now share one canonical gameplay world/realm catalog model backed by persisted Game Session admission-pointer state;
- first-party connect-token issuance and text-client `PLAY` now resolve tenant authority from the selected realm rather than from the initial login tenant;
- active gameplay session context now carries the admitted `worldSlug`, `realmSlug`, `pointerVersion`, and resolved playable-state scope instead of forcing later consumers to reconstruct that bundle from only `{tenantId, gameInstanceId}`;
- public-production first join now exists as a concrete `EnsurePublicProductionPlayerMembership(...)` boundary in `account-service`;
- public-production membership checks now consume the same Game Session routing authority as bootstrap/connect-token issuance instead of local config copies;
- `CHARS`, `PLAY`, bootstrap character discovery, and `TELL` now resolve character lookup through a scope-aware gameplay roster contract, with shared-state realms reusing one tenant-live namespace and isolated-state realms using an instance-local roster namespace;
- live gameplay presence now also preserves admitted world/realm slugs, so account-presence and related reads do not have to reverse-map that identity from runtime ids alone when the session already knows the canonical routing choice;
- first-party reconnect/login/`PLAY` now also persist and reuse `connectScopeId` plus `connectRequestId` on the durable bootstrap shell, so reconnect-style consumers fail closed on the same selector freshness contract even when the transient connect-context registry entry is unavailable.
- stale-shell cleanup now also retires the matching live gameplay presence row instead of only clearing the Redis session shell, and reused websocket selector changes on the same route now fail closed the same way as route changes instead of preserving an older authenticated or in-world binding under a fresh first-party selector.
- queued and durable gameplay-command execution now also consumes the same stale-pointer shell normalization, so command staging, replay-time execution, and gameplay-scoped script-event publish do not silently preserve an older in-world binding once cutover or reconnect fencing has already collapsed the live session back to a logged-in shell.
- reconnect-facing websocket helpers, communication-recipient delivery, and operator effective-settings reads now also consume that same normalized shell path, so redraw/buffer recovery, recipient fan-out, and session-scoped settings inspection no longer bypass the admission fence by reading raw persisted gameplay bindings directly.
- disconnect lifecycle and account-recent presence projection now also consume that same normalized shell path, so logout/takeover/transport-loss lifecycle signals and recent-presence routing snapshots do not keep stale gameplay routing alive after the admission fence has already collapsed the session back to a login-only shell.
- credential and first-party `LOGIN` refresh paths now also consume that same normalized shell path before they preserve bootstrap or gameplay state, so relogin can continue a still-current in-world binding but can no longer silently carry stale gameplay routing forward just because the refresh happened through account authentication instead of later gameplay commands.
- `PLAY` now also normalizes any previously stored gameplay binding before it reuses room/runtime continuity or takeover state, so a stale prior binding can no longer be resurrected as the “existing session” just because the current realm still resolves to the same game instance.

The remaining work is to finish the deeper runtime/control-plane follow-through instead of leaving the new family as design-only.

Within this family, the docs are now good enough to use as the primary review surface for `02.1.6`, the current-boundary routing model in `09.1`, and the closed bootstrap/connect-scope contract in `09.4`, but the broader `09.x` family still has enough active follow-through that later consumers should not be treated as code-free review territory yet.

##### source-09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice-1-72: Why This Slice Exists

Without a dedicated family here:

- tenant membership and gameplay admission look flatter than they really are;
- realm routing risks being treated as a UI concern instead of a control-plane/runtime contract;
- shared-state versus isolated-state realm policy can leak into character, inventory, and progression work without one canonical planning home;
- bootstrap discovery and connect-token resolution can drift away from the same realm-routing contract used by text-clients and reconnect flows.

This family makes the multi-tenant gameplay boundary explicit before more systems grow against local assumptions.

##### source-09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice-1-72: Target State

- player-visible world and realm discovery resolves through one canonical tenant and realm catalog contract.
- each visible realm resolves to exactly one admissible `gameInstanceId` at a time through one authoritative admission-pointer contract.
- public-production admission, explicit non-production access, and membership creation rules are bounded and auditable.
- runtime systems consistently distinguish tenant-scoped identity from realm- or instance-scoped playable state.
- first-party bootstrap and connect-token issuance use the same realm-routing truth as text-client `PLAY` and reconnect flows.

##### source-09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice-1-72: Locked Direction

- Canonical gameplay admission/routing identity remains an explicit bundle rather than collapsing into a two-slot shortcut payload.
- Where routing freshness matters, callers should preserve `worldSlug`, `realmSlug`, resolved `gameInstanceId`, and `pointerVersion` together instead of replacing them with a narrower local surrogate.
- Downstream gameplay APIs may derive narrower scoped identities after admission, but they must derive them from the canonical routing bundle rather than redefining admission truth.
- Realm/world discovery and admission-pointer resolution must come from one canonical authority; no service may grow an independent local catalog as authority for player-facing routing decisions.
- Read-through or cached copies of routing data are acceptable only as explicit caches of the canonical Game Session authority and must fail closed when stale or unavailable.

##### source-09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice-1-72: Child Slices

- [09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md](../vertical-slices/09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md)
- [09.2-task-list-public-production-admission-and-membership-creation-vertical-slice.md](../vertical-slices/09.2-task-list-public-production-admission-and-membership-creation-vertical-slice.md)
- [09.3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice.md](../vertical-slices/09.3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice.md)
- [09.4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice.md](../vertical-slices/09.4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice.md)

##### source-09-task-list-multi-tenancy-realm-routing-and-runtime-boundaries-vertical-slice-1-72: Validation

- [ ] `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170

#### Realm Catalog and Admission-Pointer Routing Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-170)

##### Preserved Source Text: source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170

<!-- migration-source path="design/project-management/vertical-slices/09.1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice.md" lines="1-170" sha256="7beb6f6fe1fcec2e9dcb17fe87d8c6d97537dabcd81e6f5ccbc29f32eee6af84" heading-offset="3" -->
#### source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170: Realm Catalog and Admission-Pointer Routing Vertical Slice

##### source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170: Goal and Status

Goal: define and implement one canonical realm catalog plus admission-pointer contract so player-visible realms and gameplay admission both resolve through the same authoritative mapping from `{tenantId, worldSlug, realmSlug}` to the current admissible `{gameInstanceId}` rather than through duplicated lobby, bootstrap, or runtime-specific logic. Status: complete at the current bounded boundary.

##### source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170: Why This Slice Exists

Today the architecture already says that players do not address raw `gameInstanceId` values directly and that each visible realm resolves to one admissible runtime target at a time. This slice exists to make that routing contract a bounded implementation target instead of a diffuse assumption spread across `PLAY`, reconnect, and first-party bootstrap.

##### source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170: Implementation Notes

The target-state contract is now explicit in the architecture docs:

- Multi-Tenancy now names one canonical split between the visible realm catalog and the current admission pointer for one `{tenantId, worldSlug, realmSlug}` target.
- Authentication now explicitly requires `REALMS`, `CHARS`, `PLAY`, bootstrap discovery, connect-token issuance, and reconnect validation to consume the same routing truth.
- Account runtime-data now states that `pointerVersion` is the freshness token callers must carry when proving they are still binding against the same resolved realm target.

The first implementation cut now exists:

- `game-session-service` now persists canonical `gameplay_admission_pointer` rows plus append-only pointer audit events instead of serving routing purely from local config;
- startup now bootstraps that authority only when the pointer store is empty, after which runtime reads and control-plane mutations go through the persisted authority rather than the config binder;
- shared routing data carries `tenantId`, `gameInstanceId`, `pointerVersion`, visibility, character-selection requirements, and realm state policy through both text and first-party bootstrap paths;
- connect-token issuance, gateway connect context, and `PLAY` now preserve and validate `worldSlug`, `realmSlug`, resolved runtime target, and `pointerVersion` together rather than falling back to a caller-supplied target tuple.
- active gameplay session context now persists that same admitted `worldSlug`, `realmSlug`, and `pointerVersion` bundle together with the resolved realm state scope, so later gameplay consumers can reuse the admitted routing truth instead of reverse-mapping from only `{tenantId, gameInstanceId}`;
- downstream gameplay-session attestations now carry that admitted routing bundle too: internal Game Session -> gameplay-service RPCs can preserve `worldSlug`, `realmSlug`, and `pointerVersion` alongside the resolved runtime target instead of collapsing trust back to only `{tenantId, gameInstanceId, characterId}`;
- first-party `LOGIN` now re-validates the current admission pointer before preserving an existing gameplay binding, so reconnect-style session reuse fails closed when a realm cutover made the prior pointer stale;
- reused transport sessions now also fail closed on denied re-entry more broadly, not only on first-party stale-pointer `LOGIN`: credential-auth failures, account mismatch failures, missing first-party connect context, and other denied relogin paths now clear any stale authenticated/gameplay binding back to a bootstrap shell instead of leaving old admitted gameplay state live behind a failed `LOGIN`;
- Game Session now exposes `ListGameplayWorlds`, `ListGameplayRealms`, and `GetAdmissionPointer` over gRPC as the canonical routing read surface consumed by first-party bootstrap;
- Game Session control-plane now exposes mutable admission-pointer updates and audit reads instead of requiring config edits/restarts for cutover;
- admission-pointer control-plane writes now support compare-and-set guardrails via `expectedPointerVersion`, so stale cutover writes fail closed instead of silently overwriting a newer pointer generation;
- prepared admission-pointer cutover validation now reads the current pointer through the same canonical selector contract as live admission, and after `09.1.40` that singular current-pointer read is world-qualified as `tenantId + worldSlug + realmSlug` rather than the earlier under-specified tenant-plus-realm shape.
- `account-service` bootstrap world/realm discovery, connect-token validation, and public-production membership checks now consume that Game Session routing surface instead of reading local gameplay catalog config directly.
- `account-service` no longer even ships the old local gameplay catalog config copy in its default startup config, so bootstrap consumers no longer advertise a dead local fallback authority after converging on Game Session routing reads.
- Logging & Admin now exposes operator-facing admission-pointer list, audit, runtime-state readback, prepared-version-upgrade prepare/read, and cutover mutation routes on top of the Game Session control-plane APIs instead of leaving pointer management as an internal-only gRPC seam;
- that operator audit read now also uses the same canonical `{tenantId, worldSlug, realmSlug}` key as admission authority instead of guessing tenant ownership from `worldSlug + realmSlug` after the control-plane response arrives;
- operator-triggered pointer mutation now derives `actorPrincipal` from the authenticated Logging & Admin session context instead of trusting a caller-supplied actor string.
- reconnect/cutover proof now includes explicit tests that stale pointer generations fail closed at all three first-party seams: connect-token issuance after bootstrap discovery, verified `LOGIN` when the pointer changed before reconnect, and `PLAY` when the pointer changes after first-party login but before gameplay entry.
- reconnect/cutover proof now also covers reused-session relogin denial on the general text/websocket path, so a failed relogin cannot keep an older authenticated or in-world binding alive on the same transport session after admission truth changed or credentials/connect scope no longer validate.
- already-admitted gameplay sessions now also consume the same stale-pointer fence through the shared authenticated-session path: when a stored gameplay binding is missing the admitted routing bundle or its `{worldSlug, realmSlug, gameInstanceId, pointerVersion}` no longer matches the current admission pointer, Game Session clears only the gameplay binding, keeps the account logged in, and fails subsequent gameplay commands closed at `PLAY_REQUIRED` instead of continuing to trust stale in-world state.
- websocket bootstrap/session reuse now also refreshes that routing truth at the transport edge instead of preserving whatever bootstrap shell happened to be in Redis already: when a reused websocket transport reconnects with a different bootstrap target or first-party connect scope, Game Session updates the stored bootstrap `{tenantId, bootstrapGameInstanceId, worldSlug, realmSlug, pointerVersion}` bundle immediately and clears any stale authenticated/gameplay binding before later `LOGIN`, browse, or gameplay commands run.
- stale gameplay-binding resets now also clear live gameplay presence instead of only rewriting Redis session shells: stale admission-pointer normalization, denied reconnect-style `PLAY`, failed relogin cleanup, and websocket reused-transport bootstrap resets all retire the old in-world presence row and emit the same bounded region-exit lifecycle signal where that binding had still been in a concrete room, so cutover fencing no longer leaves ghost online/in-room projections behind after the session has already fallen back to a logged-in shell.
- staged and durable gameplay-command execution now consumes that same stale-pointer fence too: command queue admission normalizes resolved session shells before selecting queue targets or persisting script-event routing metadata, and durable item/move/communication/action-state execution normalizes replay-time session lookups before applying gameplay handlers, so cutover-sensitive queued work can no longer reuse a stale in-world binding just because it arrived through the durable queue instead of the interactive `LOGIN`/`PLAY` path.
- reconnect-facing transport/session helpers now consume that same stale-pointer shell normalization too: websocket screen-buffer append, reconnect replay/LOOK refresh, communication-recipient resolution, and operator effective-settings reads all normalize persisted session shells before projecting gameplay-scoped state, so cutover-sensitive reconnect redraw, recipient delivery, and session-scoped debug/settings inspection can no longer resurrect stale in-world bindings by bypassing the command interpreter.
- disconnect lifecycle and recent-presence projection now consume that same normalized shell path too: logout/takeover/transport-loss region-exit publication and account-recent routing snapshots project through the canonical stale-pointer fence instead of reading raw persisted gameplay shells directly, so cutover-sensitive disconnect evidence and recent-presence reads no longer preserve stale in-world routing after admission truth has already collapsed the session back to login-only state.
- credential and first-party `LOGIN` refresh now consume that same normalized shell path too: bootstrap game-instance resolution, persisted first-party fallback, relogin gameplay-binding preservation, and failed-login cleanup all project through the stale-pointer fence before carrying gameplay or routing state forward, so account re-authentication can no longer revive a stale admitted gameplay binding after cutover fencing has already invalidated it.
- `PLAY` existing-binding reuse now consumes that same normalized shell path too: when Game Session looks up a prior gameplay binding for resume/takeover continuity, it first normalizes that stored shell against current pointer authority, so stale prior room/runtime continuity collapses to a fresh entry instead of being treated as a still-valid existing binding.
- public-production realm classification is now explicit admission-pointer/catalog metadata carried through Game Session, Logging & Admin, Account bootstrap/admission consumers, and text command rendering; consumers no longer infer first-join/public-production behavior from `realmSlug == "production"`.
- live gameplay presence now preserves the admitted world/realm slugs as well, so account-presence and other runtime read models can surface that identity directly rather than reconstructing it from runtime ids first.
- account-presence and friend-presence routing reads now also carry `pointerVersion`, and their world/realm display metadata resolves from the admitted `{worldSlug, realmSlug}` bundle first instead of reverse-mapping solely from `gameInstanceId`.
- those social presence reads now also revalidate live gameplay presence against the current admission pointer before projecting an account as online in one realm target: stale-but-more-recent session rows no longer hide a still-current session for the same account, and stale cutover rows now fall back to recent/offline presence instead of continuing to project online-in-realm truth from an older pointer generation.
- recent/offline account presence now retains the last admitted `gameInstanceId`, `worldSlug`, `realmSlug`, and `pointerVersion` bundle too, so disconnect-adjacent presence reads no longer drop back to a routing-less “last seen” snapshot immediately after transport loss.
- first-party gateway websocket handshakes and Game Session websocket bootstrap now also preserve `worldSlug`, `realmSlug`, and `pointerVersion` as explicit edge/session metadata instead of discarding that admitted routing bundle before login/`PLAY` consume it.
- the explicit Telnet proxy bootstrap seam now preserves the same admitted routing bundle too: when TCP proxy is configured with hidden local/bootstrap routing metadata, it forwards `worldSlug`, `realmSlug`, and `pointerVersion` alongside resolved `tenantId` and `gameInstanceId` instead of recreating a narrower runtime-id-only shortcut at the transport edge.
- trusted TCP proxy gameplay handshakes now also fail closed on partial routing metadata at the gateway edge: proxy-routed websocket admission may carry the full `{worldSlug, realmSlug, pointerVersion}` bundle or no routing bundle at all, but it can no longer silently downgrade to a half-populated runtime-id-only bootstrap when only some of those headers are present.
- the Telnet proxy producer side now enforces that same all-or-none contract too: configured hidden bootstrap routing metadata is normalized before session state is seeded or websocket headers are forwarded, so partial `{worldSlug, realmSlug, pointerVersion}` defaults are dropped back to an unscoped runtime-only bootstrap instead of minting malformed proxy routing headers in the first place.
- delegated gameplay-service attestation guards now fail closed if internal Game Session -> Game Logic / Entity Management gameplay RPCs arrive without the admitted `worldSlug`, `realmSlug`, and `pointerVersion` bundle that gameplay admission minted, so later routing-sensitive service-boundary work cannot silently regress back to runtime-id-only trust.
- gameplay-originated Automation & Scripting ingress now preserves that same admitted routing bundle too: Game Session `TriggerScriptEvent` producers carry `worldSlug`, `realmSlug`, and `pointerVersion`; Automation ingress rejects gameplay-scoped Game Session triggers that drop it; and durable script ingress/work-item/audit/handoff records plus dead-letter/operator reads keep that bundle visible instead of collapsing later retries and operator inspection back to only `{tenantId, gameInstanceId}`.
- scheduler-owned Automation follow-up state now reuses the same admitted routing bundle once Game Session runtime-state projection has it: `GetGameInstanceRuntimeState` carries `worldSlug`, `realmSlug`, and `pointerVersion`; Automation pin projections and `script_schedule_instances` persist that bundle; and timer-owned `onInterval` / `onTimerExpire` work items plus skipped-audit rows no longer hardcode blank routing identity for reconnect/cutover-sensitive follow-up work.
- Automation -> Game Session gameplay-command handoff now preserves that same bundle too: `EnqueueAutomationCommandIfAbsent` carries `playableStateScope`, `worldSlug`, `realmSlug`, and `pointerVersion`, staged `GameplayCommand` rows keep those fields instead of dropping back to an automation-dispatch-only ledger record, and `GetGameplayCommandStatus` now exposes the same bundle for operator inspection instead of hiding it behind runtime-id-only ledger reads.
- automation and remote-followup command/script producers now also fail closed on partial routing metadata inside that handoff family: direct `EnqueueAutomationCommandIfAbsent` requests, remote gameplay-command admission, and target-side remote script-event execution must provide `worldSlug`, `realmSlug`, and `pointerVersion` together or leave the bundle absent entirely, so durable command rows and remote trigger requests cannot persist half-populated admission identity.
- Automation scheduler, handoff, and operator-read seams now enforce that same all-or-none contract too: pin projections, schedule instances, timer-owned work items, timer audit rows, script ingress/work-item/audit persistence, gameplay-command handoff requests, handoff history rows, dead-letter summaries, and control-plane read models all collapse partial `{worldSlug, realmSlug, pointerVersion}` state back to no routing bundle instead of preserving malformed fragments across retries, scheduling, or operator inspection.
- local gameplay-command staging and replay/inspection manifests now follow that same all-or-none contract too: when session context carries only part of the admitted `{worldSlug, realmSlug, pointerVersion}` bundle, Game Session repairs it from admission-pointer authority when it can and otherwise collapses the bundle to absent before saving the command ledger row; selected-work manifests for both gameplay commands and remote followups now likewise suppress half-populated routing metadata from older rows instead of projecting malformed bundle fragments into sealed tick-batch history.
- outbound gameplay-service attestations now fail closed on that same invariant too: Game Session -> Game Logic and Game Session -> Entity Management requests now require either the full admitted `{worldSlug, realmSlug, pointerVersion}` bundle or none of it, so in-world service-boundary calls cannot silently emit partial routing claims from corrupted session state.
- tcp-proxy telnet -> gateway -> game-session restart/logout proof now isolates the shared bridge per test method, so the gateway-restart clean-close seam is validated against a fresh websocket route each time instead of inheriting stale proxy/gateway stub state across reconnect-sensitive proof steps.
- the remaining live player-routing catalog surface inside Game Session no longer uses `GameplayCatalogProperties.World` / `Realm` as its runtime authority model either: WORLDS/REALMS/CHARS/PLAY and the gRPC routing read APIs now project through immutable Game Session catalog views derived from persisted admission-pointer rows, leaving `GameplayCatalogProperties` as a bounded test helper instead of the live routing shape.
- current production player-facing routing reads now already project through the persisted Game Session pointer authority rather than local config copies; the startup-only empty-store pointer seed path now uses dedicated bootstrap pointer entries instead of the gameplay catalog config schema, while direct `GameplayCatalogProperties` construction remains a bounded test/fallback helper rather than live routing authority.

What remains open is follow-through around broader consumers:

- later cutover consumers still need to consume the same persisted pointer truth directly instead of assuming older runtime-local selectors beyond the now-hardened first-party login/PLAY path;
- the current pointer audit and prepared-upgrade seams are authoritative and now have operator-facing tooling, explicit public-production metadata, session/presence-level routing bundle carry-through, fail-closed reused-session relogin cleanup, login-refresh stale-shell normalization, active-session gameplay-stage stale-pointer fencing, and reconnect/cutover proof across bootstrap, relogin, gameplay entry, and active gameplay reads; the remaining gap is broader runtime-change verification for future reconnect/cutover consumers beyond the current login/bootstrap/PLAY/gameplay-stage command path.
- reconnect/cutover proof now also covers websocket transport reuse when the incoming bootstrap route or verified first-party connect scope changes on the same logical session id, so route-change reconnects cannot inherit a stale bootstrap shell or in-world binding from the prior websocket attachment.
- queue and durable-execution proof now also covers stale-shell normalization after gameplay admission changed, so command staging and replay-time command application cannot silently keep publishing gameplay-scoped follow-up events or preserve a stale queue target after the live session has already fallen back to a logged-in shell.
- reconnect replay, gameplay recipient delivery, and operator settings proof now also covers that same stale-shell normalization boundary, so later transport-side redraw helpers, communication fan-out, or debug/settings projections cannot quietly keep reading raw gameplay shells after the admission fence has already collapsed the session back to login-only state.
- `PLAY` existing-binding proof now also covers stale-shell normalization, so later gameplay continuity/refill changes cannot quietly resume or take over from a stale prior room binding merely because the selected realm still maps to the same runtime id.
- gateway proof now also covers trusted TCP proxy routing-bundle hygiene, so proxy admission cannot regress into accepting partial `{worldSlug, realmSlug, pointerVersion}` metadata and thereby reopen runtime-id-only routing shortcuts at the edge.
- Telnet proxy proof now also covers partial hidden-bootstrap routing metadata, so the producer side cannot regress into forwarding only part of the admitted `{worldSlug, realmSlug, pointerVersion}` bundle before the gateway rejector even sees it.
- the first downstream service-boundary carry-through now exists on gameplay-session attestations, but later routing-sensitive consumers still need to validate and preserve that bundle when they add new delegated RPC seams or replay-sensitive caches.
- gameplay-originated scripting ingress plus the current scheduler-owned timer/work-item/audit path now preserve the bundle, but later runtime-owned follow-up families still need the same carry-through anywhere new replay- or cutover-sensitive scripting surfaces appear.
- the current automation handoff request and staged gameplay-command ledger now preserve the bundle too, but later execution-time consumers still need to reuse that truth instead of reintroducing routing-less synthetic context when automation-issued commands are actually applied.
- automation plugin activation/policy readers and handoff operator reads now also reuse the persisted current runtime scope they already store: region-aware plugin preflight and policy convergence queries carry stored `runtimeRegionId` hints back into `GetGameInstanceRuntimeState`, and script handoff target-runtime projection does the same with recorded `targetRegionId` instead of collapsing those current-runtime reads back to game-instance-only selectors.
- current runtime-state reverse projection now fails closed on ambiguous current pointer authority too: `GetGameInstanceRuntimeState` exposes `currentAdmissionPointers[]`, preserves the singular `playableStateScope` / `worldSlug` / `realmSlug` / `pointerVersion` fields only when one complete current pointer bundle targets the runtime, and otherwise clears those legacy singular fields instead of choosing one sorted or partial pointer row as if it were canonical.
- pre-`PLAY` gameplay-command staging now follows that same singular-proof rule when it needs to repair routing metadata from runtime-target scope: `CommandServiceImpl` only reverse-projects bootstrap/runtime-target authority when exactly one complete current pointer bundle exists for that runtime, and otherwise persists no routing bundle instead of choosing one preferred pointer row during login-era command admission.
- pre-`PLAY` gameplay-command staging now also treats complete shell routing bundles as freshness-sensitive instead of inherently authoritative: when a bootstrap or login-era shell already carries `{worldSlug, realmSlug, pointerVersion}`, `CommandServiceImpl` preserves that bundle only if current selector authority still proves the same runtime target and pointer generation, and otherwise repairs or drops it before persisting the command row.
- logout runtime-stop policy now follows that same singular-proof rule too: when selectors do not already prove shared scope, `LogoutCommandHandler` only stops the runtime if current runtime-target authority yields exactly one complete isolated pointer bundle, and otherwise fails closed to “keep running” instead of treating one preferred pointer row as canonical isolation proof.
- generic websocket bootstrap now follows that same singular-proof rule too: when a trusted transport provides only `tenantId + bootstrapGameInstanceId`, `GameSessionWebSocketHandler` repairs the bootstrap shell’s routing bundle from current runtime-target authority only when exactly one complete current pointer bundle exists, and otherwise leaves the bootstrap shell unscoped instead of preserving or inventing one arbitrary world/realm identity.
- reused generic websocket bootstrap now also treats ambiguity collapse itself as a real route change: when current runtime authority can no longer repair a bootstrap routing bundle for the same bootstrap runtime id, `GameSessionWebSocketHandler` clears the stored authenticated/gameplay binding and saves the collapsed no-bundle shell instead of preserving the older routed bootstrap shell by treating “same runtime id” as enough.
- gameplay-command and remote script-event proof now also covers partial routing-bundle rejection in the automation/remote-followup producer path, so later queueing or target-side execution changes cannot silently reintroduce half-populated admitted routing metadata after the earlier gateway and proxy hardening.
- the current remote-followup durable substrate and operator/read projections now also normalize routing metadata as one bundle: scheduler-owned coordinator/followup/result persistence collapses partial `{worldSlug, realmSlug, pointerVersion}` input back to no routing bundle, and gameplay-command/control-plane operator reads no longer project half-populated routing metadata from older rows or mixed command/followup/coordinator state.
- remote-followup scheduling retries and operator-facing control-plane entry points now enforce that same canonical bundle contract at read/query time too: retry-time metadata equality compares the normalized routing bundle instead of raw request fields, `ScheduleRemoteFollowup` now rejects partial request routing input instead of widening it to an unscoped schedule request, remote coordinator/followup/result list filters now reject partial routing selectors instead of querying on malformed fragments, and current origin/target runtime routing readback plus stale-routing detection still collapse partial pointer-authority/runtime bundles to absent instead of projecting half-populated “current” routing identity.
- Automation scheduler, ingress, handoff, and control-plane proof now also covers partial routing-bundle collapse on persistence and readback, so future automation-side retries, timers, dead-letter inspection, or handoff growth cannot silently reintroduce half-populated admitted routing metadata after the producer-side fail-closed guards.
- local gameplay-command staging and selected-work manifest proof now also covers partial routing metadata repair/collapse, so reconnect-era session drift or legacy durable rows cannot silently leak malformed routing bundles into newly accepted gameplay-command ledger rows or sealed tick-batch replay manifests.
- gameplay-service client proof now also covers partial routing-bundle rejection on outbound session attestations, so later client growth cannot silently degrade admitted routing truth back to field-by-field optional claims at the Game Logic or Entity Management boundary.
- tcp-proxy cross-service proof now also resets the shared gateway/game-session bridge between methods, so the gateway-restart logout seam remains deterministic under the heavier repo-wide check path instead of depending on reused stub websocket state.
- the remaining routing-sensitive command and bootstrap seams must keep `worldSlug`, `realmSlug`, resolved `tenantId`, resolved `gameInstanceId`, pointer freshness, and eventual character selection as first-class target dimensions instead of collapsing back to a smaller two-slot selector shape.
- no future player-facing routing consumer should regain local catalog authority after the persisted Game Session pointer cut; any new cache or projection must stay explicitly fail-closed over the Game Session pointer surface.
- account/friend presence projection plus shared-runtime logout now also consume pointer authority directly instead of revalidating runtime truth through `GameplayWorldCatalog`, and the stale local-catalog downstream dependency on `GameLogicClient` is gone.
- gameplay communication target availability now also respects stale-pointer normalization before treating a raw gameplay-name hit as “online”, and the stale local-catalog dependency on `CommunicationCommandHandler` is gone.
- tick-state introspection now also fails closed on session-shell authority instead of guessing tenant scope by treating a `sessionId` like a runtime `gameInstanceId`, so operator `QueryState` reads no longer bypass the same session-versus-runtime routing fence the gameplay paths already use.
- tick-state introspection now also reads that session authority through the normalized live-shell fence rather than the raw stored session row, so stale gameplay-bearing shells cannot remain authoritative for `QueryState(sessionId)` after routing normalization would already collapse them.
- the remaining in-scope normalized session readers now also converge on `SessionAuthenticationService` itself: logout no longer keeps a separate raw persisted authenticated-shell lookup, and durable gameplay execution no longer open-codes `findBySessionId(...).map(normalizeResolvedContext)` for session-backed replay/apply.
- reused websocket bootstrap shell reads now also consume that same normalized session-authority fence, so transport-edge bootstrap reuse no longer keeps one raw `{tenantId, sessionId}` session-row authority path alive ahead of route or selector refresh decisions.
- Game Session's packaged-stack blocking gameplay gRPC clients now also authenticate as internal-service traffic before those normalized reader paths trigger downstream runtime calls, so fresh rebuilt smoke no longer fails gameplay item/runtime follow-up reads on missing internal-service identity.
- `LOGIN` failure cleanup now also consumes the same normalized unverified-session authority instead of rebuilding a fallback shell from one raw stored session row, and stale-pointer collapse versus failed-login lifecycle clears now stay bounded to the correct side-effect path.
- the remaining gameplay-identity and gameplay-name consumers now also converge on canonical `SessionAuthenticationService` helpers: `PLAY` existing-binding reuse, durable gameplay replay fallback, communication target availability, and recipient delivery no longer each keep their own `findByGameplay…(...).map(normalizeResolvedContext)` copy.
- reused websocket bootstrap shell reads now also converge on canonical session authority: generic and first-party websocket bootstrap reuse no longer inspect one raw persisted `{tenantId, sessionId}` shell before route or selector refresh decisions, and instead read the existing shell through the normalized `SessionAuthenticationService` helper first.
- the admission-pointer read RPC now also converges on the slice's canonical target shape: Game Session resolves `GetAdmissionPointer` by `{tenantId, worldSlug, realmSlug}` through direct pointer authority, and Account bootstrap/public-production membership consumers no longer rely on the under-specified tenant-plus-realm singular lookup once duplicate realm slugs across worlds are possible under one tenant.
- admission-pointer mutation now also stays on that same canonical key end to end: `DatabaseGameplayAdmissionPointerAuthorityService` no longer falls back to the legacy non-tenant `worldSlug + realmSlug` selector when a tenant-qualified pointer row is absent.
- command admission now also fails closed on missing canonical session authority: `CommandServiceImpl` no longer probes `gameInstanceRepository.findById(sessionId)` and treats a colliding numeric id as enough queue-target proof, so pre-login/bootstrap command admission requires the normalized session shell that transport/bootstrap setup is supposed to create first.
- trusted TCP proxy disconnect handling now also fails closed on missing runtime metadata: Game Session no longer treats a proxy `sessionId` as enough proof to suspend one runtime state row, and instead keeps the missing-`gameInstanceId` path bounded to best-effort session disconnect cleanup plus missing-context metering.
- durable gameplay-command routing metadata now also fails closed on incomplete current pointer authority: `CommandServiceImpl` no longer persists `SHARED` or `ISOLATED` alone when current selector/runtime-target authority cannot still prove the full `{playableStateScope, worldSlug, realmSlug, pointerVersion}` bundle for the accepted command row.
- Automation remote gameplay-command handoff now also fails closed on success responses that omit durable followup identity: `ScriptGameplayCommandHandoffServiceImpl` no longer marks a work item `HANDED_OFF` unless `scheduleRemoteFollowup(...)` returns both `coordinatorId` and `followupId`, and invalid success-shaped responses now dead-letter instead of persisting an untraceable remote handoff.
- remote followup runtime scheduling/projection now also fails closed on scope-only routing truth: `RemoteFollowupRuntimeServiceImpl` no longer preserves `playableStateScope` when request, command, or stored followup/coordinator metadata cannot still prove the full `{playableStateScope, worldSlug, realmSlug, pointerVersion}` bundle.
- normalized session gameplay reads and verified first-party `LOGIN` now also fail closed on singular runtime-target authority: `SessionRoutingNormalizationService` and `LoginCommandHandler` no longer trust selector lookup once a runtime target is already known, and instead require one complete current pointer row whose `worldSlug`, `realmSlug`, and `pointerVersion` still match the stored or claimed routing bundle.
- trusted TCP proxy disconnect callbacks now also authenticate as canonical internal-service traffic, so Telnet transport teardown can clear session state deterministically instead of leaving stale bridge state behind after gameplay smoke or reconnect-style flows.
- login-era accepted gameplay-command staging now also fails closed on singular current runtime-target authority: `CommandServiceImpl` no longer treats selector lookup as enough to preserve or repair accepted command-row routing metadata once the bootstrap or gameplay runtime target is already known.
- credential `LOGIN` now also fails closed when the pre-login bootstrap shell no longer yields a runtime target, instead of guessing that the transport `sessionId` itself might also be the right bootstrap `gameInstanceId`.
- projected and unverified session reads now also fail closed once tenant-scoped session authority disappears: after the raw session index discovers a tenant, `SessionRoutingNormalizationService` and `SessionAuthenticationService` no longer revive `findBySessionId(...)` shells when the canonical `{tenantId, sessionId}` row is gone.
- login-era gameplay-command staging now also fails closed on ambiguous runtime-target authority, so queued pre-`PLAY` commands cannot persist one synthetic world/realm identity merely because multiple current admission pointers still target the same runtime id.
- logout lifecycle policy now also stays on canonical runtime-target authority end to end: deliberate logout no longer falls back to `findPointer(worldSlug, realmSlug)` once the runtime target is already known, and missing, partial, or multi-pointer current authority still fails closed to “do not stop the runtime.”
- generic websocket bootstrap now also fails closed on ambiguous runtime-target authority, so reused transport sessions cannot preserve or revive a stale bootstrap route merely because the bootstrap runtime id stayed the same while current pointer freshness changed or current routing became multi-pointer.
- account and friend presence projection now also validates and decorates runtime routing through singular current runtime-target authority, so current or recent presence reads no longer treat selector lookup as enough to mint one online or display-name identity when one runtime maps to multiple current pointers.
- control-plane runtime-state, command-status, and remote current-routing projection now also require one complete current pointer bundle rather than merely one row, so operator reads no longer mint singular current routing identity from a lone partial pointer record.
- Game Session startup bootstrap now also stops reusing the live gameplay catalog authority shape: `GameplayAdmissionPointerBootstrapInitializer` seeds empty-store admission-pointer rows from dedicated bootstrap pointer entries instead of `GameplayCatalogProperties.World` / `Realm`.
- the last public selector-shaped singular pointer read is gone too: `GameplayAdmissionPointerAuthorityService` no longer exposes `findPointer(worldSlug, realmSlug)`, and surviving cutover/bootstrap proof now inspects `listPointers()` explicitly instead of keeping the dead singular selector helper alive just for test convenience.
- player-facing world/realm catalog projection now follows that same singular-complete-pointer rule too: `GameplayWorldCatalog` drops incomplete pointer rows, collapses duplicate `{worldSlug, realmSlug}` authority to no visible selector row, and fails closed on reverse runtime-target lookup when multiple visible realms still share one runtime target.
- the remaining singular runtime-target helpers now follow that same complete-pointer rule too: websocket bootstrap repair, login-era command staging, account presence projection, and logout runtime-stop policy all reject singular pointer rows with blank `stateScope` instead of treating them as complete authority.
- the remaining control-plane/runtime helper copies now follow that same shared rule too: gameplay-command status, runtime-state projection, and remote current-routing reads all consume the shared complete-pointer helper and therefore reject singular rows that still lack runtime-target identity.
- the last player-facing completeness copy now follows that same shared rule too: `GameplayWorldCatalog` consumes the shared complete-pointer helper for selector/runtime identity and layers only its extra `characterCreationPolicy` requirement locally, while the dead preferred-pointer runtime lookup API has been removed from pointer authority entirely.
- the runtime world/realm catalog class now also sheds its last config-backed local-authority shape: `GameplayWorldCatalog` no longer imports `GameplayCatalogProperties` at all, and property-backed catalog projection now lives only in explicit test support instead of the production catalog type.

The next bounded follow-through was tracked explicitly in [`09.1.1`](../vertical-slices/09.1.1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice.md) and is now complete at its current boundary: fail-closed gameplay consumers no longer reverse-map `playableStateScope`, and accepted player-command rows now persist routing metadata through admitted context or canonical pointer authority instead of dropping that shape on pre-`PLAY` command admission.

##### source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170: Scope

- authoritative realm catalog for a tenant
- explicit public-production realm metadata versus explicit non-production realms
- `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` contract and versioning
- failure semantics for missing, unavailable, or non-admissible realms
- use of the same routing truth by text-client admission, reconnect, and first-party bootstrap/connect-token resolution

##### source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170: Out of Scope

- membership creation and public first-join policy beyond the narrow routing dependencies captured here
- realm-scoped character/state isolation policy beyond the requirement that routing exposes the target `gameInstanceId`
- launch/activation internals already covered by the `08` family

##### source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170: Locked Direction

- players address worlds and realms, not raw `gameInstanceId` values.
- each player-addressable realm resolves to exactly one admissible `gameInstanceId` at a time.
- Game Session owns the authoritative admission-pointer routing contract for gameplay admission.
- routing consumers must fail closed on missing or stale pointer state rather than guessing fallback instances.
- routing freshness-sensitive consumers should preserve and validate `worldSlug`, `realmSlug`, resolved `gameInstanceId`, and `pointerVersion` as one canonical routing bundle rather than inventing a smaller shortcut tuple.
- local gameplay catalog copies must not become authority again; bootstrap, connect-token, reconnect, and text-client admission should all read the same Game Session routing surface or an explicit fail-closed cache of it.
- Logging & Admin is operator ingress over the canonical routing authority, not a second owner of gameplay routing truth.
- singular current-pointer reads must stay world-qualified when tenant-local realm slugs are not globally unique across worlds.

##### source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170: Current Remaining Work

- [ ] Keep future `PLAY`-adjacent and reconnect/cutover-sensitive routing on the full canonical routing bundle rather than shrinking back to a world-plus-secondary selector shortcut.
- [ ] Keep future reconnect and cutover consumers on the same Game Session routing surface as bootstrap and in-band admission, including later operator read-model follow-through beyond the now-converged admission-pointer audit/runtime-state and prepared-cutover surfaces.
- [ ] Broaden runtime-change verification beyond the now-covered connect-token, verified login, reused-session relogin failure, login-refresh shell persistence, websocket reused-transport route or selector refresh, singular-proof plus ambiguity-collapse generic websocket bootstrap repair, reconnect replay/buffering, gameplay recipient delivery, communication target-availability reads, operator settings reads, `PLAY` selection plus existing-binding reuse, stale-shell presence cleanup, authenticated TCP proxy disconnect callbacks plus clean Telnet session teardown, durable command routing metadata fail-closed on incomplete current pointer authority, Automation remote handoff success classification on durable followup ids, remote followup durable routing metadata fail-closed on scope-only identity, fail-closed session normalization and first-party login runtime-target authority, fail-closed Game Logic / Entity Management outbound attestation normalization, durable command execution, disconnect lifecycle/recent-presence projection, active gameplay-stage command seams, fail-closed login-era command staging runtime-target authority, singular-proof logout runtime-stop policy, singular-proof account/friend presence projection, shared-helper control-plane singular routing projection, canonical world-qualified admission-pointer gRPC lookup, singular-complete-pointer world/realm catalog projection plus reverse runtime lookup, and complete-pointer-only singular runtime-target helper reads.

##### source-09-1-task-list-realm-catalog-and-admission-pointer-routing-vertical-slice-1-170: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80

#### Routing Bundle and Catalog Authority Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-80)

##### Preserved Source Text: source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80

<!-- migration-source path="design/project-management/vertical-slices/09.1.1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice.md" lines="1-80" sha256="01a6887b91aa33e45151a5ce63d742b80596c1bc7992ec62dd5484bd9320bcee" heading-offset="3" -->
#### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: Routing Bundle and Catalog Authority Follow-Through Vertical Slice

##### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: Goal and Status

Goal: finish the next bounded follow-through on top of `09.1` by eliminating remaining selector/copy shortcuts and keeping routing-sensitive seams on the full canonical routing bundle `{worldSlug, realmSlug, tenantId, gameInstanceId, pointerVersion, characterSelection}` instead of collapsing back to local catalog or partial-selector truth. Status: complete at the current bounded boundary.

##### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: Implementation Notes

- `CommunicationCommandHandler` and `GameLogicClient` now fail closed when `SessionContext` drops admitted `playableStateScope` instead of reverse-mapping it from `{tenantId, gameInstanceId}` through a local catalog copy.
- `CommandServiceImpl` now persists routing metadata on accepted player commands using the admitted session bundle when present and the canonical admission-pointer authority when a bootstrap session still only has pre-`PLAY` routing scope.
- Pre-admission commands now persist explicit routing metadata without regaining local catalog authority: when no admitted gameplay scope exists the command row records `UNSPECIFIED` scope instead of silently omitting the field.
- The remaining live Game Session catalog readers now also stop teaching the config binder schema as runtime authority: `GameplayWorldCatalog` projects immutable runtime world/realm views from pointer authority, and WORLDS/REALMS/CHARS/PLAY plus the routing gRPC reads consume those runtime views instead of `GameplayCatalogProperties.World` / `Realm`.
- Focused unit proof now covers both fail-closed consumer behavior and bootstrap-routing metadata persistence on accepted `LOGIN` commands.

##### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: Why This Slice Exists

`09.1` already established the core routing substrate:

- persisted Game Session admission-pointer authority;
- explicit direct admission-pointer reads for one selected world/realm target;
- shared bootstrap/connect-token/`PLAY` routing truth;
- pointer freshness and admitted routing-bundle carry-through in the main first-party and gameplay seams.

What remains is not the authority itself. The remaining risk is follow-through drift:

- new or leftover routing-sensitive seams can still shrink the target shape back to `world + optional secondary`;
- some code paths still carry config-backed or local-projection catalog assumptions even though the canonical authority is now elsewhere;
- later delegated consumers can accidentally keep only runtime ids and drop the rest of the admitted routing identity that reconnect/cutover-sensitive behavior needs.

This is the right next bounded child slice instead of leaving those concerns as a broad parent-slice “remaining work” note forever.

##### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: Scope

- preserve the full routing bundle in remaining `PLAY`-adjacent and reconnect/cutover-sensitive seams;
- remove or demote remaining config-backed/local catalog truth where live code should already consume canonical routing authority;
- add focused proof that later delegated or cached consumers fail closed instead of reconstructing or guessing routing identity.

##### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: Out of Scope

- first public-production membership creation beyond the routing/cutover dependencies already covered by `09.2`;
- later realm-scoped gameplay-state families that belong to `09.3` or future domain slices;
- launch/activation control-plane work in the `08` family.

##### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: Locked Direction

- world, realm, resolved runtime target, pointer freshness, and eventual character selection remain first-class routing dimensions end to end;
- no new gameplay or bootstrap consumer should regain local catalog authority when the Game Session routing surface already exists;
- reconnect/cutover-sensitive delegated consumers must preserve the admitted routing bundle instead of trusting runtime ids alone;
- fail closed is the default whenever pointer freshness or routing authority is missing.

##### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: Planned Work

###### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: 1. Selector-Shape Cleanup

- [x] Audit remaining player-facing and delegated routing-sensitive seams for `world + optional secondary` selector assumptions.
- [x] Replace any remaining partial-selector parsing or storage with the full canonical routing bundle shape where that seam owns routing-sensitive behavior.
- [x] Keep docs and examples aligned so `PLAY`, bootstrap, and reconnect all describe the same target dimensions.

###### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: 2. Catalog Authority Cleanup

- [x] Audit remaining live config-backed or local-projection world/realm catalog consumers.
- [x] Move in-scope live consumers onto the canonical Game Session routing surface or an explicit fail-closed cache of it.
- [x] Leave only bounded bootstrap/test helpers where config-backed catalog truth is still intentionally local.

###### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: 3. Proof and Guardrails

- [x] Add focused proof that delegated or cached consumers reject stale/missing pointer context rather than reconstructing routing from only `{tenantId, gameInstanceId}`.
- [x] Add follow-up proof that future reconnect/cutover-sensitive paths preserve `pointerVersion` together with the rest of the admitted routing bundle.

##### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: Acceptance Shape

- no in-scope routing-sensitive seam still treats gameplay target selection as a compressed two-slot shape;
- live consumers no longer keep their own local world/realm truth where the canonical routing surface should be authoritative;
- delegated/cached routing-sensitive paths fail closed when the admitted routing bundle is stale, incomplete, or missing.

##### source-09-1-1-task-list-routing-bundle-and-catalog-authority-follow-through-vertical-slice-1-80: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73

#### Account Presence Runtime Authority Follow-Through Vertical Slice - Runtime-routing and realm authority (source lines 1-24, 40-73)

##### Preserved Source Text: source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73

<!-- migration-source path="design/project-management/vertical-slices/09.1.10-task-list-account-presence-runtime-authority-follow-through-vertical-slice.md" lines="1-24, 40-73" sha256="d245b018446c20535610c156757466b54f67f41a10ab598981b42572f827238a" heading-offset="3" -->
#### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73: Account Presence Runtime Authority Follow-Through Vertical Slice

##### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73: Goal and Status

Goal: remove the remaining account-presence read-model shortcut that still validated current routing and decorated runtime display data through selector lookup, so account and friend presence now consume singular current runtime-target authority directly and fail closed when that authority is ambiguous. Status: complete at the current bounded boundary.

##### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73: Why This Slice Exists

Earlier `09.1` follow-through already fenced command, bootstrap, logout, and websocket reuse onto singular current pointer proof. One quieter read-model seam still drifted:

- `AccountPresenceQueryServiceImpl` already rejected obviously stale live presence rows, but still did that by asking `findPointer(worldSlug, realmSlug)` whether the admitted routing bundle looked current;
- online and offline presence decoration still relied on selector lookup for display names instead of proving one current pointer bundle for the runtime target itself;
- when multiple current admission pointers legitimately targeted one runtime, the account-level read model could still validate or decorate presence through one selector lookup instead of failing closed on runtime-target ambiguity.

This slice closes that remaining reverse-lookup seam so account and friend presence projections stay on the same singular runtime-target authority rule as the rest of the routing-fence work.

##### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73: Implementation Notes

- `AccountPresenceQueryServiceImpl` now caches singular current pointer proof per `{tenantId, gameInstanceId}` through `listByRuntimeTarget(...)` instead of revalidating runtime freshness through selector lookup.
- Live account presence now remains online only when exactly one complete current pointer bundle exists for the runtime target and it matches the admitted `{playableStateScope, worldSlug, realmSlug, pointerVersion}` bundle on the active presence row.
- Online presence decoration now uses that singular current pointer bundle directly for world/realm display names and canonical routing fields.
- Offline recent-presence decoration now only uses current pointer display names when singular current runtime authority still matches the stored recent routing bundle exactly; ambiguous or drifted current authority no longer invents display names for an older bundle.
- Focused proof now covers the new fail-closed ambiguous-runtime case in addition to the existing online/offline and stale-live-presence cases.

<!-- source-gap: lines 25-39 -->

##### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73: Planned Work

###### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73: 1. Live Presence Validation

- [x] Replace selector-based current-routing validation with singular runtime-target pointer proof.
- [x] Keep online presence only when the admitted live routing bundle matches the singular current pointer bundle exactly.

###### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73: 2. Offline Decoration

- [x] Use current runtime-target authority for display-name decoration only when it still matches the stored recent routing bundle exactly.
- [x] Fail closed on ambiguous or drifted current runtime authority instead of projecting one selector-derived realm identity.

###### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73: 3. Proof and Documentation

- [x] Extend focused account-presence unit proof to cover ambiguous runtime-target authority.
- [x] Align the parent `09.1` slice and queue/index docs with this narrower follow-through.

##### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73: Acceptance Shape

- account/friend presence no longer validates current routing by asking selector lookup for one pointer row;
- ambiguous current runtime-target authority does not keep a live presence online;
- offline recent-presence decoration does not invent display names from mismatched or multi-pointer runtime authority.

##### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.AccountPresenceQueryServiceImplTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`

##### source-09-1-10-task-list-account-presence-runtime-authority-follow-through-vertical-slice-1-24-40-73: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69

#### Control-Plane Singular Routing Bundle Completeness Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-69)

##### Preserved Source Text: source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69

<!-- migration-source path="design/project-management/vertical-slices/09.1.11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice.md" lines="1-69" sha256="5fad40327255facb4bba98fcf196887aec13d3d71cb4fefef138a59495cfa7ec" heading-offset="3" -->
#### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: Control-Plane Singular Routing Bundle Completeness Vertical Slice

##### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: Goal and Status

Goal: make Game Session control-plane and runtime read surfaces project singular current routing only when exactly one complete current pointer bundle exists, instead of treating any lone runtime-target row as authoritative even when its routing bundle is partial. Status: complete at the current bounded boundary.

##### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: Why This Slice Exists

Earlier `09.1` follow-through already taught most gameplay and operator seams to discard incomplete routing bundles and fail closed on ambiguous runtime-target authority. One quieter read-model inconsistency remained:

- runtime-state, gameplay-command-status, and remote followup/coordinator/result read helpers still treated “one pointer row returned” as singular current routing authority;
- those helpers could therefore project a current `playableStateScope/worldSlug/realmSlug/pointerVersion` bundle from a lone row even when one or more of those fields were blank or zero;
- the same branch already taught other routing-sensitive consumers to discard incomplete bundles before deciding whether current authority was singular.

This slice closes that remaining operator-read seam so singular current routing projection follows the same “one complete bundle survives” rule everywhere in scope.

##### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: Implementation Notes

- `GameSessionRuntimeControlPlaneReadService`, `GameSessionCommandControlPlaneService`, and `GameSessionRemoteControlPlaneService` now discard incomplete pointer bundles before deciding whether current runtime authority is singular.
- A lone runtime-target row no longer projects singular current routing when its `stateScope`, `worldSlug`, `realmSlug`, or `pointerVersion` is incomplete.
- Operator-facing current-admission-pointer lists still include the raw rows; only the singular current routing projection now fails closed.
- Focused grpc proof now covers the incomplete-single-pointer case for both runtime-state and gameplay-command-status reads.

##### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: Scope

- singular current-routing projection in Game Session runtime and command-status control-plane reads;
- shared helper logic that feeds remote control-plane current-routing projection for the same runtime-target rule.

##### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: Out of Scope

- mutation/control-plane write semantics for admission pointers;
- interactive gameplay routing fences already covered by earlier `09.1` children;
- raw pointer list reads, which intentionally continue to surface incomplete rows for operators.

##### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: Locked Direction

- current runtime-target authority is singular only when exactly one complete pointer bundle survives;
- a lone partial row is not enough to mint current `playableStateScope/worldSlug/realmSlug/pointerVersion`;
- operator read surfaces may still expose raw pointer rows separately, but their singular “current runtime routing” projection must fail closed on incomplete bundles.

##### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: Planned Work

###### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: 1. Singular Projection Rule

- [x] Filter control-plane current pointer candidates down to complete routing bundles before singular projection.
- [x] Fail closed when zero or multiple complete bundles remain, even if raw runtime-target rows still exist.

###### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: 2. Proof and Documentation

- [x] Add focused grpc proof for incomplete-single-pointer runtime-state projection.
- [x] Add focused grpc proof for incomplete-single-pointer gameplay-command-status projection.
- [x] Align parent `09.1` slice and queue/index docs with this narrower follow-through.

##### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: Acceptance Shape

- runtime-state and command-status current-routing fields do not project a singular bundle from one incomplete pointer row;
- raw current-admission-pointer entry lists still expose the underlying row for operator inspection;
- singular current routing now means “exactly one complete current bundle,” not “exactly one row.”

##### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcServiceTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`

##### source-09-1-11-task-list-control-plane-singular-routing-bundle-completeness-vertical-slice-1-69: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74

#### Command-Staging Current-Pointer Freshness Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-74)

##### Preserved Source Text: source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74

<!-- migration-source path="design/project-management/vertical-slices/09.1.12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice.md" lines="1-74" sha256="eda7406a0285e4b55ee86eda108f2f661df0030df5a1f767d239af5352c83d0c" heading-offset="3" -->
#### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: Command-Staging Current-Pointer Freshness Follow-Through Vertical Slice

##### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: Goal and Status

Goal: make queued gameplay-command staging fail closed when a non-gameplay session shell carries a complete but stale admitted routing bundle, instead of trusting that shell bundle until some later consumer notices the pointer drift. Status: complete at the current bounded boundary.

##### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: Why This Slice Exists

Earlier `09.1` follow-through already taught `CommandServiceImpl` to fail closed when it had to reconstruct routing identity from runtime-target authority and found multiple or incomplete current pointers. One quieter staging shortcut still remained:

- `SessionAuthenticationService.resolveUnverifiedSessionContext(...)` already normalizes stale gameplay bindings with side effects, but bootstrap or pre-`PLAY` shells without an active gameplay binding intentionally survive that normalization path;
- `CommandServiceImpl` therefore still accepted a complete `{playableStateScope, worldSlug, realmSlug, pointerVersion}` bundle from those shells as if freshness had already been re-proved;
- a stale pointer version on a login-era or bootstrap-era shell could therefore persist onto newly staged gameplay-command rows even though the same command path would already fail closed if the bundle were only partial.

This slice closes that last completeness-versus-freshness mismatch so command staging treats a complete shell routing bundle as current only when current pointer authority still proves it.

##### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: Implementation Notes

- `CommandServiceImpl.resolveRoutingMetadata(...)` now treats a complete shell routing bundle as authoritative only when the current pointer selected by `{worldSlug, realmSlug}` still matches the expected runtime target and `pointerVersion`.
- When that current selector pointer no longer matches the stored bundle, command staging re-enters the existing repair path and stamps the current authoritative bundle instead of preserving stale shell metadata.
- When current selector authority is missing entirely, staged gameplay-command rows now preserve `playableStateScope` only and collapse the routing bundle to absent instead of persisting a stale `{worldSlug, realmSlug, pointerVersion}` tuple.
- Focused proof now covers both bounded outcomes: stale complete shell metadata repairs forward to the current pointer when one exists, and otherwise fails closed to no routing bundle.

##### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: Scope

- pre-`PLAY` gameplay-command staging for bootstrap or login-era shells that already carry a complete routing bundle;
- freshness revalidation of that bundle against current pointer authority before the command row is persisted;
- focused proof for stale-complete-shell repair versus fail-closed collapse.

##### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: Out of Scope

- gameplay-binding shell normalization, already covered by earlier `09.1` stale-pointer fencing;
- runtime-target ambiguity handling, already covered by `09.1.7`;
- later durable command execution, remote followups, or operator read surfaces, already covered in their own follow-through batches.

##### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: Locked Direction

- a complete shell routing bundle is not enough on its own; command staging must still prove current pointer freshness before persisting it onward;
- when freshness cannot be proved, staging may preserve bounded scope facts but must drop the stale routing bundle itself;
- command staging must not be the quiet place where a stale pointer version survives just because the shell happened to carry all fields.

##### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: Planned Work

###### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: 1. Freshness Revalidation

- [x] Revalidate complete shell routing bundles against current pointer authority before preserving them on staged command rows.
- [x] Require the current selector pointer to still match both the expected runtime target and the stored `pointerVersion`.

###### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: 2. Fail-Closed Collapse

- [x] Repair stale complete shell bundles forward to the current authoritative pointer when one exists.
- [x] Drop the routing bundle entirely when current authority cannot prove freshness, while keeping bounded `playableStateScope` facts separate.

###### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: 3. Proof and Documentation

- [x] Add focused Game Session proof for stale-complete-shell repair and fail-closed collapse.
- [x] Align the parent `09.1` slice and queue/index docs with this narrower follow-through.

##### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: Acceptance Shape

- staging no longer persists a stale `{worldSlug, realmSlug, pointerVersion}` bundle just because a bootstrap or login-era shell already had one;
- current selector authority repairs that bundle forward when it is still knowable;
- missing current selector authority now yields a staged command row with no routing bundle rather than a stale one.

##### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`

##### source-09-1-12-task-list-command-staging-current-pointer-freshness-follow-through-vertical-slice-1-74: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74

#### Websocket Bootstrap Ambiguity Collapse Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-74)

##### Preserved Source Text: source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74

<!-- migration-source path="design/project-management/vertical-slices/09.1.13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice.md" lines="1-74" sha256="900836ced664c09273a934aca88ff063dd5a2557da4e75fd6dd2bed7ac67b2b3" heading-offset="3" -->
#### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: Websocket Bootstrap Ambiguity Collapse Follow-Through Vertical Slice

##### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: Goal and Status

Goal: make reused generic websocket bootstrap fail closed when singular runtime authority can no longer repair a bootstrap routing bundle, instead of preserving an older bootstrap route under the same runtime id just because the incoming shell now collapsed to “no bundle.” Status: complete at the current bounded boundary.

##### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: Why This Slice Exists

Earlier `09.1.9` already taught generic websocket bootstrap to repair `{worldSlug, realmSlug, pointerVersion}` from singular runtime authority and to clear stale authenticated/gameplay binding when that repaired route changed. One narrower reused-session seam still remained:

- when current runtime authority became ambiguous, `repairGenericBootstrapShell(...)` correctly collapsed the incoming shell to no routing bundle;
- but `sameBootstrapRoute(...)` still treated “same tenant + same bootstrap runtime id + no incoming bundle” as equivalent to the existing routed shell;
- a reused websocket transport could therefore keep an older bootstrap route and authenticated/gameplay carry-forward even after current authority had become ambiguous enough that fresh bootstrap would no longer mint that route.

This slice closes that last ambiguity-collapse gap so no-bundle repair is treated as a real route change when the stored shell still had a prior bootstrap routing bundle.

##### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: Implementation Notes

- `GameSessionWebSocketHandler.sameBootstrapRoute(...)` now compares complete-bundle presence first instead of only checking runtime id and opportunistic incoming fields.
- When the existing shell had a complete bootstrap routing bundle but the repaired incoming shell no longer has one, bootstrap reuse now fails closed to “route changed.”
- The existing `BOOTSTRAP_ROUTE_CHANGED` cleanup path then clears authenticated/gameplay binding and saves the collapsed bootstrap shell instead of preserving the older route.
- Focused handler proof now covers the ambiguity case where current runtime authority fans out to multiple complete pointers for the same bootstrap runtime id.

##### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: Scope

- generic websocket bootstrap session reuse for trusted transport sessions carrying only `{tenantId, bootstrapGameInstanceId}`;
- route-equivalence detection between the existing stored shell and the newly repaired incoming bootstrap shell;
- focused proof for the ambiguity-collapse case on reused bootstrap sessions.

##### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: Out of Scope

- first-party websocket bootstrap, which already carries explicit connect-context routing;
- singular runtime repair itself, already covered by `09.1.9`;
- later gameplay command or replay-time routing fences, already covered by separate `09.1` follow-through slices.

##### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: Locked Direction

- a reused bootstrap shell with no repaired routing bundle is not “the same route” as an older stored shell that still had one;
- ambiguity-induced bundle collapse must trigger the same fail-closed cleanup path as any other bootstrap route change;
- transport-edge session reuse must not preserve a bootstrap route that fresh bootstrap can no longer prove.

##### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: Planned Work

###### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: 1. Route Equivalence Tightening

- [x] Make bootstrap-route equality compare complete-bundle presence before field-level equality.
- [x] Treat “existing shell had a bundle, incoming repaired shell does not” as a real route change.

###### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: 2. Fail-Closed Reuse

- [x] Reuse the existing `BOOTSTRAP_ROUTE_CHANGED` cleanup path when ambiguity collapses current bootstrap authority.
- [x] Save the collapsed bootstrap shell instead of preserving the older routed shell on reused transports.

###### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: 3. Proof and Documentation

- [x] Add focused websocket-handler proof for ambiguity-driven bootstrap collapse on a reused session.
- [x] Align parent `09.1` slice and queue/index docs with this narrower transport-edge follow-through.

##### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: Acceptance Shape

- reused generic websocket sessions no longer preserve an older bootstrap route when singular current pointer authority collapses to no bundle;
- ambiguity now clears authenticated/gameplay carry-forward through the same bootstrap-route-changed cleanup path;
- fresh bootstrap and reused bootstrap apply the same fail-closed contract when current authority is no longer singular.

##### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.websocket.GameSessionWebSocketHandlerTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`

##### source-09-1-13-task-list-websocket-bootstrap-ambiguity-collapse-follow-through-vertical-slice-1-74: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74

#### Gameplay World Catalog Singular Authority Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-74)

##### Preserved Source Text: source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74

<!-- migration-source path="design/project-management/vertical-slices/09.1.14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice.md" lines="1-74" sha256="f17f7061cec16175b41574a255a03194a78056b3b1a6c356e9ff5ccae468fdfa" heading-offset="3" -->
#### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: Gameplay World Catalog Singular Authority Follow-Through Vertical Slice

##### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: Goal and Status

Goal: make the remaining player-facing world/realm catalog projection fail closed when persisted pointer authority is ambiguous or incomplete, instead of projecting visible selectors or reverse runtime-target identities from whichever pointer row happened to appear first. Status: complete at the current bounded boundary.

##### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: Why This Slice Exists

Earlier `09.1` follow-through already taught bootstrap, command staging, logout, account presence, websocket reuse, and control-plane runtime reads to require one complete current pointer bundle before they projected singular routing identity. One quieter catalog seam still remained:

- `GameplayWorldCatalog` already projected immutable runtime-facing world/realm views from pointer authority instead of config, but it still accepted every pointer row that looked superficially selector-shaped;
- duplicate current pointer rows for one `{worldSlug, realmSlug}` could therefore still produce one visible selector entry if they happened to share the same outward world/realm names;
- reverse runtime-target lookup still chose the first visible realm match when multiple visible realms legitimately shared one runtime target.

This slice closes that catalog-side drift so the same singular-complete-pointer rule now holds for WORLDS/REALMS/CHARS/PLAY-facing catalog views and the gRPC routing reads that reuse them.

##### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: Implementation Notes

- `GameplayWorldCatalog` now discards pointer rows that do not carry one complete authority bundle (`tenantId`, `gameInstanceId`, `pointerVersion`, `worldSlug`, `realmSlug`, `stateScope`, and `characterCreationPolicy`) before they reach visible catalog projection.
- World/realm selector projection now groups authority rows by `{worldSlug, realmSlug}` and only emits a visible realm when exactly one complete pointer row exists for that selector key.
- Reverse runtime-target lookup now returns a runtime realm target only when exactly one visible realm survives for that `{tenantId, gameInstanceId}` target; multi-realm collisions now fail closed to absent instead of picking the first match.
- Focused catalog proof now covers all three bounded outcomes: duplicate selector rows collapse the visible world, duplicate runtime-target matches collapse reverse lookup, and incomplete authority rows never become visible catalog entries at all.

##### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: Scope

- `GameplayWorldCatalog` projection from persisted admission-pointer rows into immutable world/realm browse views;
- reverse runtime-target lookup helpers used by later runtime-facing and player-facing routing reads;
- focused proof for duplicate selector rows, duplicate runtime-target matches, and incomplete authority rows.

##### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: Out of Scope

- websocket/bootstrap repair, already covered by `09.1.9` and `09.1.13`;
- control-plane singular current-routing projection, already covered by `09.1.11`;
- account/friend presence runtime authority, already covered by `09.1.10`.

##### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: Locked Direction

- player-facing world/realm catalog projection must not mint visible selector rows from ambiguous or incomplete pointer authority;
- reverse runtime-target lookup must return one canonical routing identity only when one complete visible realm survives for that runtime target;
- immutable catalog views and control-plane singular current-routing views must teach the same singular-complete-pointer contract.

##### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: Planned Work

###### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: 1. Complete-Bundle-Only Catalog Projection

- [x] Drop incomplete pointer rows before building visible world/realm catalog views.
- [x] Group pointer rows by `{worldSlug, realmSlug}` and emit selector rows only when exactly one complete row survives for that selector.

###### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: 2. Fail-Closed Reverse Lookup

- [x] Make reverse runtime-target lookup require exactly one visible realm match for the runtime target.
- [x] Collapse multi-realm runtime-target matches to absent instead of picking one preferred visible realm.

###### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: 3. Proof and Documentation

- [x] Add focused `GameplayWorldCatalog` proof for duplicate selector rows, duplicate runtime-target matches, and incomplete authority rows.
- [x] Align the parent `09.1` slice and queue/index docs with this catalog-side singular-authority follow-through.

##### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: Acceptance Shape

- ambiguous selector rows no longer leak into visible WORLDS/REALMS catalog projection;
- reverse runtime-target lookup no longer picks one world/realm identity when multiple visible realms share one runtime target;
- incomplete authority rows no longer become visible player-facing catalog entries just because they still have a world/realm slug.

##### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalogTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`

##### source-09-1-14-task-list-gameplay-world-catalog-singular-authority-follow-through-vertical-slice-1-74: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice-1-43

#### 09.1.15 Task List: Complete Pointer Runtime-Target Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-43)

##### Preserved Source Text: source-09-1-15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice-1-43

<!-- migration-source path="design/project-management/vertical-slices/09.1.15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice.md" lines="1-43" sha256="d978675d58f5d0e56cef005cfe255915903a906208316dde4f609ccc5e8bd222" heading-offset="3" -->
#### source-09-1-15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice-1-43: 09.1.15 Task List: Complete Pointer Runtime-Target Follow-Through Vertical Slice

Status: implemented

##### source-09-1-15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice-1-43: Goal

Finish the next narrow `09.1` follow-through by making the remaining singular runtime-target helpers require one complete admission-pointer bundle, not just one row with world/realm/version fields.

##### source-09-1-15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice-1-43: Why

After the earlier `09.1` batches, most routing-sensitive consumers already fail closed on missing, stale, ambiguous, or partial routing authority. The remaining gap was a small set of runtime-target readers that still accepted one row without requiring a non-blank `stateScope`, which let them treat incomplete authority as singular enough for bootstrap repair, login-era command staging, presence projection, or logout runtime-stop policy.

##### source-09-1-15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice-1-43: Scope

- shared helper for “complete singular runtime pointer” in Game Session
- runtime-target authority reads in:
  - websocket bootstrap repair
  - login-era command staging
  - account presence projection
  - logout runtime-stop policy
- focused unit proof for incomplete `stateScope` runtime-target rows

##### source-09-1-15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice-1-43: Out of Scope

- broader routing consumers already covered by earlier `09.1` slices
- control-plane/runtime read helpers already closed by `09.1.11`
- player-facing catalog projection already closed by `09.1.14`

##### source-09-1-15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice-1-43: Implemented

- Added shared `GameplayAdmissionPointerSnapshots` helper so singular runtime-target authority is defined once as exactly one complete pointer bundle.
- Hardened `GameSessionWebSocketHandler` generic bootstrap repair to reject singular pointer rows with blank `stateScope`.
- Updated `CommandServiceImpl` login-era bootstrap routing repair to reject singular pointer rows with blank `stateScope`.
- Tightened `AccountPresenceQueryServiceImpl` singular runtime-target decoration to reject incomplete pointer rows instead of treating them as current authority.
- Adjusted `LogoutCommandHandler` isolated-runtime detection to reject incomplete singular runtime-target rows instead of treating them as enough evidence to stop runtime.

##### source-09-1-15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice-1-43: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.AccountPresenceQueryServiceImplTest' --tests 'net.firedevops.firemud.gamesession.command.text.LogoutCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest' --tests 'net.firedevops.firemud.gamesession.websocket.GameSessionWebSocketHandlerTest'`

##### source-09-1-15-task-list-complete-pointer-runtime-target-follow-through-vertical-slice-1-43: Result

Singular runtime-target authority now means the same thing across the remaining Game Session bootstrap, staging, presence, and logout helpers: one complete bundle with `stateScope`, `worldSlug`, `realmSlug`, and `pointerVersion`, or no authority.
<!-- /migration-source -->

### source-09-1-16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice-1-40

#### 09.1.16 Task List: Control-Plane Complete Pointer Helper Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-40)

##### Preserved Source Text: source-09-1-16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice-1-40

<!-- migration-source path="design/project-management/vertical-slices/09.1.16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice.md" lines="1-40" sha256="4879c0b08f0170c2095ee36326d72bfa0f27baf1072c934aaf12e93824e99a37" heading-offset="3" -->
#### source-09-1-16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice-1-40: 09.1.16 Task List: Control-Plane Complete Pointer Helper Follow-Through Vertical Slice

Status: implemented

##### source-09-1-16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice-1-40: Goal

Finish the next narrow `09.1` follow-through by making the remaining Game Session control-plane/runtime readers consume the shared complete-pointer helper instead of carrying private copies of the singular runtime-target rule.

##### source-09-1-16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice-1-40: Why

After `09.1.15`, interactive helpers already agreed on what singular runtime-target authority means. The remaining drift was in operator/control-plane reads, where command status, runtime-state, and remote current-routing projections still duplicated the rule locally and only checked `stateScope`, `worldSlug`, `realmSlug`, and `pointerVersion`. That left them able to mint singular current routing from a row that was still missing runtime-target identity.

##### source-09-1-16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice-1-40: Scope

- shared complete-pointer helper adoption in:
  - `GameSessionCommandControlPlaneService`
  - `GameSessionRuntimeControlPlaneReadService`
  - `GameSessionRemoteControlPlaneService`
- gRPC proof that singular control-plane routing collapses when the only pointer row lacks runtime-target identity

##### source-09-1-16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice-1-40: Out of Scope

- interactive helpers already covered by `09.1.15`
- player-facing catalog projection already covered by `09.1.14`
- broader reconnect/cutover consumers outside these current control-plane readers

##### source-09-1-16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice-1-40: Implemented

- Replaced duplicate complete-pointer filters in command-status, runtime-state, and remote control-plane readers with `GameplayAdmissionPointerSnapshots.singularCompletePointer(...)`.
- Removed the now-redundant per-service `hasCompleteRoutingBundle(...)` helpers from those readers.
- Tightened control-plane singular routing so rows missing `tenantId` or `gameInstanceId` now collapse to “no singular current routing,” even if the rest of the bundle is populated.
- Added gRPC proof that runtime-state, gameplay-command-status, and remote coordinator reads all mark current routing stale when the only pointer row lacks runtime-target identity.

##### source-09-1-16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice-1-40: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcServiceTest'`

##### source-09-1-16-task-list-control-plane-complete-pointer-helper-follow-through-vertical-slice-1-40: Result

Game Session now defines singular runtime-target authority once for both interactive and operator/control-plane consumers: exactly one complete pointer bundle with runtime-target identity, or no singular routing at all.
<!-- /migration-source -->

### source-09-1-17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice-1-42

#### 09.1.17 Task List: Shared Complete Pointer Helper Catalog Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-42)

##### Preserved Source Text: source-09-1-17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice-1-42

<!-- migration-source path="design/project-management/vertical-slices/09.1.17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice.md" lines="1-42" sha256="8e9e16500a296e9fa39532f221aea3794f6cc519a9dabcb936db0312612a01c7" heading-offset="3" -->
#### source-09-1-17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice-1-42: 09.1.17 Task List: Shared Complete Pointer Helper Catalog Follow-Through Vertical Slice

Status: implemented

##### source-09-1-17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice-1-42: Goal

Finish the next narrow `09.1` follow-through by removing the last dead preferred-pointer API and making the player-facing world/realm catalog consume the shared complete-pointer helper instead of carrying its own near-copy of the rule.

##### source-09-1-17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice-1-42: Why

After `09.1.15` and `09.1.16`, singular runtime-target authority was already centralized for interactive and control-plane consumers. Two small drifts remained:

- the admission-pointer authority service still exposed `findByRuntimeTarget(...)`, even though all runtime-target consumers had already moved to explicit singular-proof `listByRuntimeTarget(...)` reads;
- `GameplayWorldCatalog` still duplicated the complete-pointer rule locally, which made the catalog the last player-facing projection not directly sharing the same core completeness predicate as the rest of `09.1`.

Leaving either seam behind would make the repo teach two slightly different ways to think about “complete pointer authority,” even though the runtime had already converged on one.

##### source-09-1-17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice-1-42: Scope

- remove the now-unused `findByRuntimeTarget(...)` API from the admission-pointer authority service and its database implementation
- make `GameplayWorldCatalog` delegate pointer completeness to `GameplayAdmissionPointerSnapshots.hasCompleteRoutingBundle(...)`
- keep the catalog-specific `characterCreationPolicy` requirement explicit with direct unit proof

##### source-09-1-17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice-1-42: Out of Scope

- broader runtime-target consumers already converted in `09.1.7`, `09.1.8`, `09.1.15`, and `09.1.16`
- any new routing behavior beyond de-duplicating the authority rule

##### source-09-1-17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice-1-42: Implemented

- Removed the dead `findByRuntimeTarget(...)` method from `GameplayAdmissionPointerAuthorityService` and `DatabaseGameplayAdmissionPointerAuthorityService`.
- Switched `GameplayWorldCatalog` to reuse `GameplayAdmissionPointerSnapshots.hasCompleteRoutingBundle(...)` for the shared authority portion of pointer completeness.
- Kept the catalog-specific `characterCreationPolicy` check layered on top of the shared helper instead of burying it inside the shared pointer rule.
- Added unit proof that the catalog still fails closed when the only pointer row is otherwise complete but omits `characterCreationPolicy`.

##### source-09-1-17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice-1-42: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalogTest'`

##### source-09-1-17-task-list-shared-complete-pointer-helper-catalog-follow-through-vertical-slice-1-42: Result

Game Session now defines complete admission-pointer authority once for both runtime-target helpers and player-facing catalog projection, while still keeping the catalog’s extra realm-entry policy requirement explicit and local.
<!-- /migration-source -->

### source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47

#### 09.1.18 Task List: Runtime Catalog Test Helper Convergence Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-47)

##### Preserved Source Text: source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47

<!-- migration-source path="design/project-management/vertical-slices/09.1.18-task-list-runtime-catalog-test-helper-convergence-vertical-slice.md" lines="1-47" sha256="0e4b613d5edabb512d48c18a81071aeaf55a5651132d5a7ba19df30dde000a0b" heading-offset="3" -->
#### source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47: 09.1.18 Task List: Runtime Catalog Test Helper Convergence Vertical Slice

Status: implemented

##### source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47: Goal

Finish the next narrow `09.1` follow-through by removing the last config-backed world/realm catalog construction path from the production `GameplayWorldCatalog` type while preserving the same lightweight setup shape for command and gRPC tests.

##### source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47: Why

The production `GameplayWorldCatalog` now uses persisted admission-pointer reads for runtime behavior; the remaining `GameplayCatalogProperties` path exists only for tests. Keeping that projection in production would retain a dual-source authority shape:

- pointer-backed runtime authority in normal application wiring;
- config-backed world/realm projection via a secondary constructor that no production path used anymore.

Leaving that constructor in the runtime class would keep the old local-catalog authority shape alive in exactly the place the slice was trying to de-authorize.

##### source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47: Scope

- remove `GameplayCatalogProperties` from the production `GameplayWorldCatalog` type
- move property-to-catalog projection into explicit test support
- update Game Session command and gRPC tests to use the test helper instead of the removed constructor

##### source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47: Out of Scope

- `09.1` startup bootstrap initialization still read `GameplayCatalogProperties` to seed pointer authority on an empty store; that seam remains out of scope for this slice.
- any further runtime routing behavior changes beyond relocating test-only projection

##### source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47: Implemented

- Removed the `GameplayCatalogProperties` constructor and property-copy helpers from `GameplayWorldCatalog`.
- Added `GameplayWorldCatalog.forWorldViews(...)` and `forWorldSupplier(...)` so the production type can still be built directly from already-shaped `WorldView` data without knowing about config classes.
- Added `TestGameplayWorldCatalogs.fromProperties(...)` in test fixtures, keeping the old mutable test setup behavior while confining property projection to explicit test support.
- Updated command and gRPC tests that previously instantiated `GameplayWorldCatalog` from `GameplayCatalogProperties` directly.

##### source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalogTest' --tests 'net.firedevops.firemud.gamesession.command.text.WorldsCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.command.text.WorldsTextCommandDispatchHandlerTest' --tests 'net.firedevops.firemud.gamesession.command.text.PlayCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.command.text.SessionResumptionFlowTest' --tests 'net.firedevops.firemud.gamesession.command.text.TextCommandInterpreterTest' --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionGrpcServiceTest' :game-session-service:integrationTest --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionGrpcServicePingIntegrationTest'`

##### source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47: Result

The production world/realm catalog now has one authority story only: pointer-backed runtime projection. Config-backed world/realm shaping still exists where it is genuinely needed, but only in explicit test support.

##### source-09-1-18-task-list-runtime-catalog-test-helper-convergence-vertical-slice-1-47: History

- `09.1.37` moved startup pointer-bootstrap seam setup onto dedicated pointer-seed configuration instead of reusing `GameplayCatalogProperties`.
- `09.1.18` removed the production `GameplayCatalogProperties` catalog constructor path because runtime behavior for realm/catalog selection had already moved to persisted admission-pointer reads.
<!-- /migration-source -->

### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69

#### 09.1.19 Task List: Tick State Session Normalization Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-69)

##### Preserved Source Text: source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69

<!-- migration-source path="design/project-management/vertical-slices/09.1.19-task-list-tick-state-session-normalization-follow-through-vertical-slice.md" lines="1-69" sha256="801982092708aa8c33319718f5c7f14b4bc5f655fd0826465db6287ea5781a26" heading-offset="3" -->
#### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: 09.1.19 Task List: Tick State Session Normalization Follow-Through Vertical Slice

##### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: Goal and Status

Goal: finish the remaining `09.1` tick-state routing seam by making `QueryState(sessionId)` derive tenant authority from the normalized live session shell rather than directly from the raw persisted session row. Status: complete at the current bounded boundary.

##### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: Why This Slice Exists

`09.1.4` already removed the worst `sessionId -> gameInstanceId` identity shortcut from tick-state introspection. One quieter routing-fence gap still remained:

- `TickQueueControlService.queryState(sessionId)` still read the stored session row through `SessionContextService.findBySessionId(...)` instead of the normalization path already used by other reconnect- and cutover-sensitive session reads;
- that meant tick-state introspection could still trust a stale gameplay-bearing shell that the routing fence would have already collapsed back to a safer login/bootstrap shell;
- the read would now fail closed when the row was missing or tenantless, but it still was not reading through the same live authority path the rest of the routing family already used.

This slice closes that narrower follow-through so tick-state reads stay on the same normalized live-shell authority as the rest of the session-scoped routing surfaces.

##### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: Implementation Notes

- `TickQueueControlService` now depends on `SessionAuthenticationService` instead of reading raw stored session rows directly.
- `queryState(sessionId)` now resolves tenant authority through `resolveUnverifiedSessionContext(...)`, which applies the same routing normalization fence used by other session-scoped read and command paths.
- Tick-state introspection therefore no longer treats a stale pre-normalized stored shell as enough authority to read Redis session state.
- Focused unit proof now covers the same query path after the collaborator swap so the fail-closed read remains explicit at the tick-service boundary.

##### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: Scope

- session-scoped tick-state introspection through `TickQueueControlService.queryState(...)`;
- the remaining raw-session-row authority seam in that read path;
- focused unit proof for the collaborator and fail-closed behavior.

##### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: Out of Scope

- the earlier `09.1.4` transport-id versus runtime-id shortcut removal, which remains closed;
- broader tick batching, ownership, replay, or region-fencing work tracked elsewhere;
- interactive gameplay routing fences already closed in other `09.1` follow-through slices.

##### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: Locked Direction

- session-scoped operator or debug reads should consume normalized live shell authority, not raw persisted shell rows;
- if routing normalization collapses a stale gameplay shell, later introspection should observe the collapsed shell rather than the stale one;
- tick-state reads must follow the same routing fence as later reconnect, presence, communication, and gameplay command paths.

##### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: Planned Work

###### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: 1. Normalized Session Authority

- [x] Replace the raw `SessionContextService` read in `TickQueueControlService.queryState(...)` with `SessionAuthenticationService.resolveUnverifiedSessionContext(...)`.
- [x] Keep the existing positive-tenant fail-closed requirement on the normalized shell before reading Redis state.

###### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: 2. Focused Proof and Documentation

- [x] Update focused tick-service tests for the collaborator swap and fail-closed query path.
- [x] Align the parent `09.1` slice, queue/index docs, and reusable observation log with the narrower normalization follow-through.

##### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: Acceptance Shape

- tick-state introspection no longer reads tenant authority from a raw stored session row;
- a shell that routing normalization would already collapse does not remain authoritative for `QueryState(sessionId)`;
- session-scoped tick-state reads now follow the same normalized live-shell rule as the rest of the closed `09.1` routing-fence seams.

##### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.TickQueueControlServiceTest' --tests 'net.firedevops.firemud.gamesession.service.impl.TickStagingServiceTest' --tests 'net.firedevops.firemud.gamesession.service.impl.TickBatchExecutionServiceTest' --tests 'net.firedevops.firemud.gamesession.service.impl.TickRuntimeProgressServiceTest' --tests 'net.firedevops.firemud.gamesession.service.impl.TickServiceImplTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`

##### source-09-1-19-task-list-tick-state-session-normalization-follow-through-vertical-slice-1-69: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76

#### Pointer Authority Projection Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-76)

##### Preserved Source Text: source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76

<!-- migration-source path="design/project-management/vertical-slices/09.1.2-task-list-pointer-authority-projection-follow-through-vertical-slice.md" lines="1-76" sha256="d95dd41630b9b3a8ca261e6093fb0b4d5fbb966dd0357df336e8061cb6df8226" heading-offset="3" -->
#### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: Pointer Authority Projection Follow-Through Vertical Slice

##### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: Goal and Status

Goal: remove the next remaining live runtime authority drift on top of `09.1` by keeping shared-runtime and presence/read-model projections on admitted routing bundle plus persisted admission-pointer authority, instead of consulting local catalog projections again for runtime truth. Status: complete at the current bounded boundary.

##### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: Why This Slice Exists

`09.1` and `09.1.1` already established the canonical routing substrate:

- persisted Game Session admission-pointer authority;
- one admitted routing bundle `{worldSlug, realmSlug, tenantId, gameInstanceId, pointerVersion, playableStateScope}`;
- fail-closed command, reconnect, queue, and replay paths when that bundle is stale or incomplete.

What still remained was a smaller but real drift seam in read and lifecycle projection paths:

- account/friend presence projection still validated live routing through `GameplayWorldCatalog` and decorated active presence with a runtime-id fallback instead of trusting the admitted bundle plus pointer authority;
- shared-runtime logout decisions still consulted the local runtime world/realm projection instead of pointer authority;
- one downstream client constructor still carried an unused `GameplayWorldCatalog` dependency after the routing-fence work had already made admitted session state authoritative for that path.

This slice closes that narrower follow-through without broadening back into another general routing audit.

##### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: Implementation Notes

- `AccountPresenceQueryServiceImpl` now validates live presence and decorates online/offline world/realm display names through `GameplayAdmissionPointerAuthorityService` rather than `GameplayWorldCatalog`.
- The online account-presence path no longer reverse-maps runtime display data from `{tenantId, gameInstanceId}` when the admitted live presence already carries `worldSlug`, `realmSlug`, and `pointerVersion`; stale or mismatched pointer authority now simply fails that presence closed.
- `LogoutCommandHandler` now uses pointer authority for both selector-based shared-runtime checks and runtime-target shared-scope checks, so shared logout does not depend on local runtime catalog projection.
- `GameLogicClient` no longer carries the dead `GameplayWorldCatalog` constructor dependency; admitted session scope plus attested routing bundle are sufficient for that path.
- Focused unit proof now covers pointer-authority-backed account-presence projection, shared-runtime logout without local catalog reads, and the slimmer Game Logic client constructor/test seam.

##### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: Scope

- live presence/read-model consumers that still treat local world/realm projection as runtime authority;
- lifecycle decisions that still need to distinguish shared versus isolated runtime scope after admitted gameplay entry;
- cleanup of stale routing-authority dependencies made obsolete by the current routing-fence work.

##### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: Out of Scope

- broader reconnect/cutover-sensitive command or replay paths already closed in `09.1` and `09.1.1`;
- future realm-scoped gameplay-state families owned by `09.3` and later domain slices;
- operator control-plane routing reads already covered by the canonical pointer authority surfaces.

##### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: Locked Direction

- admitted routing bundle plus persisted pointer authority remain the only runtime routing truth;
- local catalog projections may shape browse UX and bounded bootstrap/test helpers, but they do not get to revalidate live runtime state in shared projections once admitted routing data exists;
- read models and lifecycle decisions fail closed when pointer authority no longer matches admitted runtime identity.

##### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: Planned Work

###### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: 1. Projection Authority Cleanup

- [x] Move account-presence validation and display-name decoration off `GameplayWorldCatalog` and onto pointer authority.
- [x] Remove runtime-id fallback in the live presence path when admitted routing bundle data already exists.

###### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: 2. Shared-Runtime Lifecycle Cleanup

- [x] Move shared-runtime logout detection off local catalog reads and onto pointer authority.
- [x] Keep shared/isolated lifecycle decisions aligned with the same persisted scope truth used elsewhere in routing-fence work.

###### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: 3. Dependency Cleanup and Proof

- [x] Remove stale local-catalog dependencies from downstream clients that no longer use them.
- [x] Add focused unit proof for the updated projection and lifecycle seams.

##### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: Acceptance Shape

- live account-presence projection no longer revalidates runtime truth through `GameplayWorldCatalog`;
- shared-runtime logout no longer depends on local world/realm projection;
- stale local-catalog dependencies are gone from the in-scope client/lifecycle seams.

##### source-09-1-2-task-list-pointer-authority-projection-follow-through-vertical-slice-1-76: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73

#### 09.1.20 Task List: Session Authority Reader Convergence Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-73)

##### Preserved Source Text: source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73

<!-- migration-source path="design/project-management/vertical-slices/09.1.20-task-list-session-authority-reader-convergence-vertical-slice.md" lines="1-73" sha256="b040008d5011fc5bc3d952afc57742b067f18f8ff465a4dbb390eb5ff0f44f24" heading-offset="3" -->
#### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: 09.1.20 Task List: Session Authority Reader Convergence Vertical Slice

##### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: Goal and Status

Goal: converge the remaining normalized-session readers in scope onto `SessionAuthenticationService` itself instead of letting them reimplement “raw session row plus maybe-normalize” authority locally. Status: complete at the current bounded boundary.

##### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: Why This Slice Exists

Earlier `09.1` follow-through already taught most routing-sensitive gameplay, bootstrap, and operator seams to consume normalized session-shell authority directly. Two smaller readers still drifted:

- `LogoutCommandHandler` resolved the current normalized session shell for the main logout flow, but still separately read one raw persisted authenticated row when deciding the runtime-stop lifecycle policy;
- `DefaultDurableGameplayCommandExecutionService` already normalized session-backed execution context before replay/apply, but it still did that by reading `SessionContextService.findBySessionId(...)` directly and then calling `normalizeResolvedContext(...)` itself;
- both paths were conceptually already in the normalization lane, but they still kept an unnecessary second authority shape alive in code.

This slice closes that remaining entrypoint drift so later session-scoped readers in scope stop teaching “manual raw row plus normalization” as an alternative to the canonical session-authority service.

##### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: Implementation Notes

- `LogoutCommandHandler.resolvePersistedSessionContext(...)` now resolves through `SessionAuthenticationService.resolveSessionContext(...)` instead of reading raw stored rows directly.
- `DefaultDurableGameplayCommandExecutionService` now resolves session-backed durable execution context through `SessionAuthenticationService.resolveUnverifiedSessionContext(...)` instead of open-coding `findBySessionId(...).map(normalizeResolvedContext)`.
- Gameplay-identity fallback in durable execution remains unchanged; only the session-id entrypoint moved onto the canonical authority service.
- Fresh rebuilt runtime proof also exposed one remaining packaged-stack auth seam on Game Session's blocking gameplay gRPC clients, so the service now overrides the shared blocking-stub customizer with explicit internal-service auth before replayed or logout-adjacent gameplay handlers call downstream runtime services.
- Focused proof now covers both the logout lifecycle reader and durable gameplay execution under the converged collaborator path.

##### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: Scope

- logout lifecycle’s remaining persisted-session lookup;
- durable gameplay execution’s session-id-based execution-context resolution;
- Game Session’s packaged-stack blocking gameplay gRPC auth seam exposed by the same fresh runtime proof;
- focused unit proof for those two readers after the collaborator convergence.

##### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: Out of Scope

- login failure-cleanup state repair, which still has its own narrower persisted-shell rewrite seam;
- broader durable queue, tick, or replay architecture outside the reader entrypoint change;
- session-identity storage itself in `SessionContextService`.

##### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: Locked Direction

- once a session reader depends on normalized routing truth, it should consume `SessionAuthenticationService` directly instead of reimplementing raw-row lookup plus normalization;
- raw persisted-session reads are acceptable for storage or low-level normalization infrastructure, not as a second public authority path in higher-level gameplay or lifecycle readers;
- session-id-based replay, logout, and similar readers should share one canonical authority entrypoint.

##### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: Planned Work

###### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: 1. Reader Convergence

- [x] Move logout’s remaining persisted authenticated-session lookup onto `SessionAuthenticationService`.
- [x] Move durable gameplay execution’s session-id execution-context lookup onto `SessionAuthenticationService`.

###### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: 2. Focused Proof and Documentation

- [x] Update focused logout and durable execution unit proof for the collaborator shift.
- [x] Align the parent `09.1` slice, queue/index docs, and reusable observation log with the converged session-authority entrypoint.

##### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: Acceptance Shape

- logout no longer reads one separate raw persisted authenticated shell outside the canonical session-authority service;
- durable gameplay execution no longer open-codes `findBySessionId(...).map(normalizeResolvedContext)` for session-backed replay/apply;
- fresh rebuilt gameplay smoke no longer fails item/runtime follow-up reads on missing internal-service gRPC identity from Game Session;
- the in-scope remaining normalized readers now share one canonical session-authority entrypoint.

##### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'unit.net.firedevops.firemud.gamesession.config.InternalGrpcClientAuthConfigTest' --tests 'net.firedevops.firemud.gamesession.command.text.InventoryCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.command.text.LogoutCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.service.impl.DefaultDurableGameplayCommandExecutionServiceTest' :game-logic-service:test --tests 'net.firedevops.firemud.gamelogic.config.GameLogicGrpcClientConfigTest' --tests 'net.firedevops.firemud.gamelogic.service.ItemRuntimeServiceTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck :game-logic-service:check -PfullCheck linkCheck lintMarkdown check`
- `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down -v --remove-orphans && bash dev-tools/verify-fresh-bootstrap.sh && docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down -v --remove-orphans`

##### source-09-1-20-task-list-session-authority-reader-convergence-vertical-slice-1-73: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66

#### 09.1.21 Task List: Login Failure Session Authority Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-66)

##### Preserved Source Text: source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66

<!-- migration-source path="design/project-management/vertical-slices/09.1.21-task-list-login-failure-session-authority-follow-through-vertical-slice.md" lines="1-66" sha256="56e814ad67e6c903e1d6370e9d3051138154af8106ca2063651b87c92882b6e1" heading-offset="3" -->
#### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: 09.1.21 Task List: Login Failure Session Authority Follow-Through Vertical Slice

##### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: Goal and Status

Goal: move `LOGIN` failure cleanup onto the same normalized session-authority entrypoint as the other `09.1` routing-fence readers, instead of letting failed-login cleanup reconstruct fallback shell state from one raw persisted session row. Status: complete at the current bounded boundary.

##### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: Why This Slice Exists

Earlier `09.1` follow-through already converged most gameplay, reconnect, logout, replay, and operator session readers onto `SessionAuthenticationService` or the shared routing fence. One narrower seam still drifted:

- `LoginCommandHandler.clearFailedLoginSessionState(...)` still loaded `SessionContextService.findBySessionId(...)` directly, then normalized and preserved fallback shell state inline when clearing a failed login attempt.

That left `LOGIN` failure handling teaching a second “raw row plus local projection” authority path even though the rest of the routing-fence work had already settled on canonical normalized session reads.

##### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: Implementation Notes

- `LoginCommandHandler.clearFailedLoginSessionState(...)` now resolves the prior shell through `SessionAuthenticationService.resolveUnverifiedSessionContext(...)` instead of reading a raw stored session row directly.
- Failed-login cleanup now clears gameplay presence only when the normalized shell still positively carries gameplay binding; stale-pointer normalization continues to emit its own `STALE_ADMISSION_POINTER` lifecycle side effect when it collapses the shell earlier in the same path.
- The preserved fallback login shell still keeps locale, bootstrap runtime, and first-party connect metadata from the normalized shell when present; only the higher-level reader path changed.

##### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: Scope

- failed-login cleanup in credential and first-party `LOGIN` denial paths;
- focused proof for stale-shell and current-shell failure cleanup behavior after the collaborator convergence.

##### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: Out of Scope

- successful login persistence or replay behavior outside failed-login cleanup;
- broader `LOGIN` bootstrap/runtime authority beyond the failure-cleanup reader path;
- lower-level session storage or normalization infrastructure.

##### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: Locked Direction

- failed-login session repair should consume the same normalized session-authority path as other routing-sensitive readers;
- login denial cleanup must not reconstruct fallback shell state from a second raw persisted-session reader in higher-level command code;
- lifecycle side effects should remain bounded: stale-pointer collapse emits stale-pointer lifecycle, while failed-login cleanup emits its own gameplay clear only when gameplay binding still survives normalization.

##### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: Planned Work

###### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: 1. Failure-Cleanup Reader Convergence

- [x] Move failed-login cleanup onto `SessionAuthenticationService.resolveUnverifiedSessionContext(...)`.
- [x] Keep gameplay lifecycle clearing bounded to still-gameplay-bearing normalized shells.

###### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: 2. Focused Proof and Documentation

- [x] Update focused login/session tests for the new two-step stale-shell behavior.
- [x] Align the parent `09.1` slice, slice index, and proof ledger with the closed boundary.

##### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: Acceptance Shape

- `LOGIN` failure cleanup no longer reads one raw persisted session row directly in higher-level command code;
- stale-pointer login failures still collapse to a clean logged-in/bootstrap shell without double-emitting gameplay lifecycle;
- valid gameplay-bearing login failures still clear gameplay binding before persisting the fallback shell.

##### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'net.firedevops.firemud.gamesession.command.text.LoginCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.command.text.SessionResumptionFlowTest' --tests 'net.firedevops.firemud.gamesession.command.text.TextCommandInterpreterTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`
- `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down -v --remove-orphans && bash dev-tools/verify-fresh-bootstrap.sh && docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down -v --remove-orphans`

##### source-09-1-21-task-list-login-failure-session-authority-follow-through-vertical-slice-1-66: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65

#### 09.1.22 Task List: Gameplay Identity Reader Convergence Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-65)

##### Preserved Source Text: source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65

<!-- migration-source path="design/project-management/vertical-slices/09.1.22-task-list-gameplay-identity-reader-convergence-vertical-slice.md" lines="1-65" sha256="7a717b1201901c8fcb7b89a95cfdab5f27f0de71dd10e287d0476c9f31c832c4" heading-offset="3" -->
#### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: 09.1.22 Task List: Gameplay Identity Reader Convergence Vertical Slice

##### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: Goal and Status

Goal: converge the remaining gameplay-identity and gameplay-name session readers onto canonical `SessionAuthenticationService` helpers instead of letting higher-level gameplay consumers keep open-coding `findByGameplay…(...).map(normalizeResolvedContext)`. Status: complete at the current bounded boundary.

##### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: Why This Slice Exists

Earlier `09.1` follow-through already converged the session-id readers onto canonical session authority. A smaller but still repeated pattern remained:

- `PlayCommandHandler`, `DefaultDurableGameplayCommandExecutionService`, `CommunicationCommandHandler`, and `CommunicationRecipientDeliveryService` still resolved gameplay sessions by identity or name through direct `SessionContextService` lookups followed by local `normalizeResolvedContext(...)` calls.

That preserved one more duplicated normalization pattern in higher-level gameplay code even after the session-id entrypoints had already converged.

##### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: Implementation Notes

- `SessionAuthenticationService` now exposes canonical normalized gameplay-reader helpers for `{tenantId, gameInstanceId, characterId}` and `{tenantId, gameInstanceId, characterName}` resolution.
- `PLAY` existing-binding reuse, durable gameplay replay fallback, communication target-availability reads, and communication recipient delivery now consume those helpers instead of open-coding the normalization step locally.

##### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: Scope

- gameplay-identity and gameplay-name session readers in the current `09.1` gameplay delivery/admission lane;
- focused proof for the four helper adopters plus the shared session-authority helper itself.

##### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: Out of Scope

- lower-level `SessionContextService` storage APIs;
- unrelated session-id readers already converged in `09.1.20` and `09.1.21`;
- broader reconnect/bootstrap/runtime authority outside the gameplay-identity reader pattern.

##### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: Locked Direction

- higher-level gameplay consumers should not duplicate `findByGameplay…(...).map(normalizeResolvedContext)`;
- canonical normalization logic should remain centralized in `SessionAuthenticationService`;
- gameplay availability, reuse, replay, and recipient delivery should share the same normalized gameplay-reader entrypoints.

##### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: Planned Work

###### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: 1. Helper Convergence

- [x] Add canonical gameplay-identity and gameplay-name reader helpers to `SessionAuthenticationService`.
- [x] Move the current in-scope helper adopters onto those canonical entrypoints.

###### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: 2. Focused Proof and Documentation

- [x] Update focused unit proof for the helper itself and the four adopters.
- [x] Align parent/index docs and the final proof ledger once the broader validation closes.

##### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: Acceptance Shape

- `PLAY`, durable gameplay replay fallback, communication target availability, and recipient delivery no longer open-code gameplay-identity or gameplay-name normalization;
- canonical gameplay reader normalization lives in `SessionAuthenticationService`;
- focused and broad proof both confirm the helper swap without changing gameplay semantics.

##### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.SessionAuthenticationServiceTest' --tests 'net.firedevops.firemud.gamesession.service.CommunicationRecipientDeliveryServiceTest' --tests 'net.firedevops.firemud.gamesession.command.text.CommunicationCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.command.text.PlayCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.service.impl.DefaultDurableGameplayCommandExecutionServiceTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`
- `bash dev-tools/verify-fresh-bootstrap.sh`

##### source-09-1-22-task-list-gameplay-identity-reader-convergence-vertical-slice-1-65: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76

#### 09.1.23 Task List: Websocket Bootstrap Session Authority Convergence Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-76)

##### Preserved Source Text: source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76

<!-- migration-source path="design/project-management/vertical-slices/09.1.23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice.md" lines="1-76" sha256="f76f7b37b9aa1d2737b6dbf428015522c40a56128bd2cbb2ad43aafc84d127f1" heading-offset="3" -->
#### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: 09.1.23 Task List: Websocket Bootstrap Session Authority Convergence Vertical Slice

##### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: Goal and Status

Goal: converge reused-websocket bootstrap shell reads onto canonical `SessionAuthenticationService` authority instead of letting transport bootstrap reuse inspect one raw persisted session row before routing normalization runs. Status: complete at the current bounded boundary.

##### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: Why This Slice Exists

Earlier `09.1` follow-through already hardened websocket bootstrap routing itself:

- generic bootstrap repair now fails closed on ambiguous runtime-target authority;
- reused websocket bootstrap now treats ambiguity collapse as a real route change;
- broader session-id readers already converged onto canonical normalized-session authority in `09.1.20` through `09.1.22`.

One narrower transport-edge drift still remained:

- `GameSessionWebSocketHandler.bootstrapGenericSessionContext(...)` and `bootstrapFirstPartySessionContext(...)` still loaded the existing shell through `SessionContextService.findByTenantAndSessionId(...)` directly before deciding whether to preserve or rewrite the bootstrap shell.

That left one raw persisted-shell reader alive at the reconnect/bootstrap edge even though later gameplay, login, logout, replay, and operator paths already consumed canonical normalized session authority.

##### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: Implementation Notes

- `SessionAuthenticationService` now exposes a canonical tenant-aware `resolveUnverifiedSessionContext(long tenantId, long sessionId)` helper so callers that already know tenant scope can still consume the shared stale-pointer normalization fence.
- reused websocket bootstrap reads in both generic and first-party bootstrap flows now resolve the existing shell through that canonical helper instead of reading the raw stored shell directly.
- bootstrap route-change and first-party selector-change behavior stays unchanged; the convergence is only about which existing shell is treated as authority before those decisions are made.

##### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: Scope

- reused websocket bootstrap shell reads in `GameSessionWebSocketHandler`;
- canonical tenant-aware normalized-session helper coverage in `SessionAuthenticationService`;
- focused proof for the helper and websocket bootstrap reuse path;
- fresh rebuilt runtime smoke for websocket and telnet bootstrap/login/play behavior after the transport-edge change.

##### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: Out of Scope

- generic bootstrap route repair rules themselves;
- broader reconnect replay, communication, or logout session readers already converged in earlier `09.1.x` work;
- low-level session storage APIs in `SessionContextService`.

##### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: Locked Direction

- transport-edge bootstrap reuse should consume the same normalized session authority as later gameplay and login readers;
- reused websocket bootstrap must not preserve a stale gameplay-bearing shell merely because the raw persisted row was inspected before stale-pointer normalization;
- higher-level reconnect/bootstrap code should not keep separate raw session-reader authority when `SessionAuthenticationService` already owns that routing fence.

##### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: Planned Work

###### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: 1. Helper and Adopter Convergence

- [x] Add a canonical tenant-aware normalized-session helper to `SessionAuthenticationService`.
- [x] Move reused generic and first-party websocket bootstrap shell reads onto that helper.

###### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: 2. Focused Proof and Documentation

- [x] Update focused session-authority and websocket bootstrap tests for the collaborator shift.
- [x] Align parent/index docs and runtime proof notes with the closed boundary.

##### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: Acceptance Shape

- websocket bootstrap reuse no longer reads an existing shell from `SessionContextService.findByTenantAndSessionId(...)` in higher-level transport code;
- reused generic and first-party websocket bootstrap both consume canonical normalized-session authority before preserving or collapsing bootstrap shell state;
- focused and broad proof confirm the change without altering bootstrap/login/play happy-path behavior.

##### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.SessionAuthenticationServiceTest' --tests 'net.firedevops.firemud.gamesession.websocket.GameSessionWebSocketHandlerTest'`
- `./gradlew :game-session-service:check -PfullCheck`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down -v --remove-orphans`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-23-task-list-websocket-bootstrap-session-authority-convergence-vertical-slice-1-76: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82

#### 09.1.24 Task List: Canonical Admission Pointer Lookup Convergence Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-82)

##### Preserved Source Text: source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82

<!-- migration-source path="design/project-management/vertical-slices/09.1.24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice.md" lines="1-82" sha256="64f59a1e185c1f7afc62ce55510c24472044f4ce55a9439fd3d6c8d6c9a1386e" heading-offset="3" -->
#### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: 09.1.24 Task List: Canonical Admission Pointer Lookup Convergence Vertical Slice

##### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: Goal and Status

Goal: converge admission-pointer lookup onto direct pointer authority instead of letting Game Session and Account Service keep one `worldSlug + realmSlug` lookup path alive at the gRPC boundary. Status: complete at the current bounded boundary.

##### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: Why This Slice Exists

`09.1` already documented one canonical admission-pointer target key:

- the then-current routing contract was one `{tenantId, realmSlug}` target mapped to the current admissible runtime;
- later consumers are supposed to preserve the admitted `worldSlug`, `realmSlug`, resolved runtime target, and `pointerVersion` bundle rather than reconstructing authority from browse-time selectors.

One contract seam still drifted:

- `GetAdmissionPointerRequest` still took `worldSlug` plus `realmSlug`;
- `GameSessionGrpcService.getAdmissionPointer(...)` still re-resolved that pair through `GameplayWorldCatalog` instead of reading pointer authority directly;
- `account-service` bootstrap admission and public-production membership paths still consumed that older selector-shaped lookup rather than the then-current narrower singular pointer read.

That kept the routing RPC itself on an older selector shape even after most downstream admission and reconnect work had already converged.

##### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: Implementation Notes

- `GetAdmissionPointerRequest` in this bounded step moved from browse selectors onto direct pointer-authority lookup keyed by `tenantId` plus `realmSlug`; later `09.1.40` further repaired that singular key to the now-canonical world-qualified shape.
- Game Session pointer authority now supports direct singular lookup through repository and service helpers rather than round-tripping through browse projection.
- `GameSessionGrpcService.getAdmissionPointer(...)` now reads pointer authority directly instead of round-tripping through `GameplayWorldCatalog`.
- `account-service` bootstrap admission and public-production membership paths now consume that direct singular pointer read.
- bootstrap admission still fails closed if that narrower singular lookup returns a pointer whose `worldSlug` no longer matches the originally selected bootstrap world, so replayed bootstrap selectors cannot silently drift across worlds after discovery.

##### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: Scope

- the Game Session admission-pointer gRPC read contract;
- direct pointer-authority lookup instead of browse-selector lookup;
- Account Service bootstrap and public-production membership consumers of that lookup;
- focused proof across Game Session authority/service tests and Account Service admission tests;
- fresh rebuilt smoke for bootstrap/login/play after the contract cut.

##### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: Out of Scope

- pointer audit and control-plane mutation/list filters, which still legitimately address pointers by visible world plus realm identity;
- browse-time `WORLDS` and `REALMS` catalog UX;
- broader public-production policy beyond the canonical pointer lookup shape.

##### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: Locked Direction

- the admission-pointer lookup contract should resolve through direct pointer authority rather than browse selectors;
- higher-level consumers should read pointer authority directly rather than reconstructing it from browse-time world selection;
- bootstrap admission must stay fail-closed if the selected world no longer matches the currently admitted pointer identity for that tenant/realm target.

##### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: Planned Work

###### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: 1. Contract and Authority Convergence

- [x] Change the admission-pointer gRPC request shape to direct pointer-authority lookup rather than browse selectors.
- [x] Add direct pointer-authority lookup and move the gRPC handler onto it.
- [x] Move Account bootstrap admission and public-production membership consumers onto the converged lookup.

###### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: 2. Proof and Documentation

- [x] Update focused Game Session and Account proof for the new request shape and the world-mismatch fail-closed check.
- [x] Align parent/index docs and runtime proof notes with the converged contract.

##### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: Acceptance Shape

- Game Session no longer resolves `GetAdmissionPointer` by `worldSlug + realmSlug` through the browse catalog;
- Account bootstrap admission and public-production membership no longer depend on the older selector-shaped pointer read;
- bootstrap connect-token issuance still fails closed if the selected world no longer matches the direct singular pointer identity used at that bounded step;
- focused, broad, and fresh-bootstrap proof all stay green after the contract cut.

##### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.DatabaseGameplayAdmissionPointerAuthorityServiceTest' --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionGrpcServiceTest' --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionGrpcServicePingIntegrationTest' :account-service:test --tests 'net.firedevops.firemud.accountservice.service.impl.AccountServiceImplTest'`
- `./gradlew :game-session-service:check -PfullCheck :account-service:check -PfullCheck`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down -v --remove-orphans`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-24-task-list-canonical-admission-pointer-lookup-convergence-vertical-slice-1-82: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78

#### 09.1.25 Task List: Fail-Closed Command Admission Without Session Authority Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-78)

##### Preserved Source Text: source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78

<!-- migration-source path="design/project-management/vertical-slices/09.1.25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice.md" lines="1-78" sha256="808fe8c3fe2d9f05e4e2897396e66dedc113f6e7cf093daa17779264c0e822ff" heading-offset="3" -->
#### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: 09.1.25 Task List: Fail-Closed Command Admission Without Session Authority Vertical Slice

##### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: Goal and Status

Goal: keep pre-gameplay and gameplay command admission on canonical session authority instead of letting `CommandServiceImpl` guess a queue target by probing `gameInstanceRepository.findById(sessionId)` when no normalized session shell exists. Status: complete at the current bounded boundary.

##### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: Why This Slice Exists

`09.1` already hardened most reconnect, bootstrap, and gameplay consumers to fail closed when session routing truth is missing or stale. One admission seam still drifted:

- `CommandServiceImpl.resolveQueueTarget(...)` still accepted a raw numeric `sessionId`;
- when `SessionAuthenticationService.resolveUnverifiedSessionContext(...)` returned empty, it still looked up `gameInstanceRepository.findById(sessionId)` and treated that collision as enough authority to enqueue a command;
- that reopened one `sessionId -> runtime target` shortcut outside the canonical session shell and let tests or future callers succeed without the bootstrap shell that transport admission is supposed to create first.

That was a real routing-authority gap, not just a test nuisance, because accepted command rows and immediate tick kicks would still be minted from guessed runtime authority rather than a normalized session context.

##### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: Implementation Notes

- `CommandServiceImpl` now requires a normalized session context with a positive tenant id before it resolves any queue target.
- If no canonical session shell exists, command admission now fails closed with `NOT_FOUND` instead of probing `gameInstanceRepository.findById(sessionId)`.
- Existing authenticated or bootstrap-shell sessions still queue exactly as before:
  - gameplay-bound sessions still enqueue against their admitted runtime target;
  - bootstrap/login-era shells enqueue against their explicit bootstrap runtime target while the current bootstrap routing bundle remains intact.
- Focused unit and interpreter/resumption proof now seeds explicit bootstrap shells where that authority is supposed to exist, instead of relying on the old numeric-id fallback.

##### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: Scope

- `CommandServiceImpl` queue-target resolution for text-command admission;
- focused unit proof for command admission, login, logout, session normalization, interpreter, and resumption behavior;
- websocket integration and cross-service smoke-shaped proof to confirm transport admission still creates the expected bootstrap shell ahead of `LOGIN` / `PLAY`;
- fresh rebuilt Docker bootstrap smoke.

##### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: Out of Scope

- deeper tenant-scoped pointer-lookup convergence inside session normalization and login/logout selectors;
- browse catalog shape or public-production policy;
- durable queue execution semantics after a command has already been admitted.

##### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: Locked Direction

- accepted commands must originate from canonical session authority, not a guessed numeric collision between `sessionId` and `gameInstanceId`;
- transport and bootstrap admission remain responsible for creating the pre-login shell that later command admission consumes;
- when that shell is absent, command admission should fail closed rather than inventing a runtime target.

##### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: Planned Work

###### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: 1. Command-Admission Hardening

- [x] Remove the raw `gameInstanceRepository.findById(sessionId)` queue-target fallback from `CommandServiceImpl`.
- [x] Keep bootstrap-shell and gameplay-bound queue routing intact when canonical session authority is present.

###### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: 2. Proof and Documentation

- [x] Update focused unit/interpreter/resumption proof to rely on explicit bootstrap shells instead of the old fallback.
- [x] Re-run websocket integration, cross-service, full service check, and fresh-bootstrap smoke on the converged behavior.
- [x] Align parent/index docs with the fail-closed command-admission boundary.

##### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: Acceptance Shape

- Game Session no longer accepts commands by guessing that a raw numeric `sessionId` is also an admissible runtime target;
- callers that already have a canonical session shell continue to enqueue successfully without changing their queue target behavior;
- websocket and Telnet bootstrap/login/play flows remain green because transport admission still establishes the expected bootstrap shell before command admission runs;
- focused, broad, websocket/cross-service, and fresh-bootstrap proof all stay green after the fallback removal.

##### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.SessionAuthenticationServiceTest' --tests 'net.firedevops.firemud.gamesession.command.text.LoginCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.command.text.LogoutCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest' --tests 'net.firedevops.firemud.gamesession.command.text.SessionResumptionFlowTest' --tests 'net.firedevops.firemud.gamesession.command.text.TextCommandInterpreterTest'`
- `./gradlew :game-session-service:integrationTest --tests 'net.firedevops.firemud.gamesession.websocket.GameSessionWebSocketHandlerIntegrationTest' :game-session-service:crossServiceTest --tests 'net.firedevops.firemud.gamesession.LookWebSocketCrossServiceTest' --tests 'net.firedevops.firemud.gamesession.CommunicationWebSocketCrossServiceTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down -v --remove-orphans`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-25-task-list-fail-closed-command-admission-without-session-authority-vertical-slice-1-78: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74

#### 09.1.26 Task List: Fail-Closed TCP Proxy Disconnect Runtime Suspend Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-74)

##### Preserved Source Text: source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74

<!-- migration-source path="design/project-management/vertical-slices/09.1.26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice.md" lines="1-74" sha256="95ca13b09f6dfa349725c6102e4601af26a07ea48bea6c0cb7be1e39d77d339f" heading-offset="3" -->
#### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: 09.1.26 Task List: Fail-Closed TCP Proxy Disconnect Runtime Suspend Vertical Slice

##### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: Goal and Status

Goal: keep trusted TCP proxy disconnect handling on explicit runtime metadata instead of letting Game Session suspend runtime state by falling back from missing `gameInstanceId` to a raw `sessionId`. Status: complete at the current bounded boundary.

##### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: Why This Slice Exists

`09.1` already hardened bootstrap, reconnect, and command-admission seams against numeric-id shortcuts. One disconnect seam still drifted:

- `TcpProxyServiceImpl.notifyDisconnect(...)` accepted `sessionId` and `tenantId` from trusted TCP proxy events;
- when `gameInstanceId` was absent, it still substituted `sessionId` and validated that against `GameInstanceRepository`;
- that let runtime suspend state be written from a bare numeric collision instead of explicit proxy bootstrap metadata.

That was the same class of routing-authority shortcut as the earlier command-admission fallback, just on the transport-loss side instead of the command-ingress side.

##### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: Implementation Notes

- `TcpProxyServiceImpl` now treats `gameInstanceId` as the only valid runtime-suspend key for proxy disconnect state.
- Missing `gameInstanceId` now stays on the bounded advisory path: Game Session records best-effort session disconnect cleanup when `sessionId` is present, increments the existing missing-context metric, and skips runtime suspend-state persistence.
- Explicit proxy disconnect hints that already carry `gameInstanceId` continue to suspend runtime state exactly as before.
- Later proxy-side follow-through keeps the disconnect envelope on explicit runtime metadata too: `TcpProxyEventClient` now sends only `gameInstanceId` when that authority exists, and `TcpProxyGrpcService` no longer falls back from missing `gameInstanceId` to advisory `sessionId`.

##### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: Scope

- trusted TCP proxy disconnect handling inside Game Session;
- focused unit proof for explicit runtime suspend, invalid argument handling, duplicate/late disconnect deduplication, and missing-runtime-metadata fail-closed behavior;
- full Game Session validation and fresh bootstrap smoke to confirm the change did not disturb existing Telnet/WebSocket proof.

##### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: Out of Scope

- broader TCP proxy bootstrap header normalization, which was already covered by earlier `09.1` work;
- gameplay reconnect policy after disconnect state has already been persisted;
- later script-ingress or remote-followup routing-bundle validation seams.

##### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: Locked Direction

- trusted TCP proxy disconnect events must suspend runtime state only when they carry explicit runtime metadata;
- missing runtime metadata should remain a bounded no-op plus best-effort session cleanup, not a reason to guess a runtime target from `sessionId`;
- producer and consumer sides should keep the all-explicit contract aligned.

##### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: Planned Work

###### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: 1. Disconnect Runtime-Authority Hardening

- [x] Remove the `sessionId -> gameInstanceId` fallback from `TcpProxyServiceImpl`.
- [x] Preserve best-effort session disconnect cleanup and missing-context metering when runtime metadata is absent.

###### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: 2. Proof and Documentation

- [x] Update focused TCP proxy disconnect proof to require explicit `gameInstanceId` for runtime suspend and to assert the new fail-closed missing-metadata path.
- [x] Re-run full Game Session proof and fresh rebuilt smoke on the converged behavior.
- [x] Align parent/index docs with the new disconnect-side authority fence.

##### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: Acceptance Shape

- trusted TCP proxy disconnect handling no longer suspends runtime state from a bare numeric `sessionId`;
- producer-side disconnect events that already provide `gameInstanceId` still behave unchanged;
- missing runtime metadata still records bounded session cleanup and does not regress into a hard error;
- focused, broad, and fresh-bootstrap proof all stay green after the disconnect hardening.

##### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.TcpProxyServiceImplTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down -v --remove-orphans`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-26-task-list-fail-closed-tcp-proxy-disconnect-runtime-suspend-vertical-slice-1-74: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74

#### 09.1.27 Task List: Fail-Closed Durable Command Routing Metadata Without Current Pointer Authority Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-74)

##### Preserved Source Text: source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74

<!-- migration-source path="design/project-management/vertical-slices/09.1.27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice.md" lines="1-74" sha256="54cfb277d11b5af8634600086a2fd11c8d2cfb3266ff1a4df49e1c0310f42263" heading-offset="3" -->
#### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: 09.1.27 Task List: Fail-Closed Durable Command Routing Metadata Without Current Pointer Authority Vertical Slice

##### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: Goal and Status

Goal: ensure durable gameplay-command rows only persist gameplay routing identity when current admission-pointer authority can still prove the complete `{playableStateScope, worldSlug, realmSlug, pointerVersion}` bundle. Status: complete at the current bounded boundary.

##### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: Why This Slice Exists

`09.1` already converged queue-target selection, login-era repair, and durable replay-time session reads onto canonical session and pointer authority. One narrower ledger seam still drifted:

- `CommandServiceImpl` repaired or preserved command-row routing metadata from session shells;
- when the shell still claimed `playableStateScope` but current pointer authority was missing or incomplete, it could still persist that scope claim while dropping only `worldSlug`, `realmSlug`, and `pointerVersion`;
- that left durable command rows carrying partial routing truth instead of either one current canonical bundle or no routing identity at all.

This slice closes that ledger-side partial-truth seam.

##### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: Implementation Notes

- `CommandServiceImpl` now treats gameplay-scoped durable command routing metadata as all-or-none when it is proving from current pointer authority.
- Preserving an existing shell bundle now requires the current selector pointer to match the expected runtime target, pointer version, world/realm identity, and nonblank `stateScope`.
- Repairing from current pointer authority now requires a complete pointer bundle including nonblank `stateScope`; incomplete selector rows no longer backfill or preserve a bare `SHARED` or `ISOLATED` claim.
- When that proof is missing or incomplete, accepted gameplay-command rows now persist `playableStateScope="UNSPECIFIED"` with no world/realm/version bundle instead of a scope-only partial claim.

##### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: Scope

- local durable gameplay-command routing metadata persistence in `CommandServiceImpl`;
- focused unit proof for stale, partial, and incomplete-current-pointer cases;
- full Game Session validation, fresh bootstrap smoke, and doc hygiene.

##### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: Out of Scope

- remote followup or Automation routing-bundle handoff behavior;
- broader command-status or control-plane projection changes beyond the local command ledger row;
- selector lookup contract changes in pointer authority itself.

##### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: Locked Direction

- durable gameplay-command rows must persist either one current canonical routing bundle or no routing identity at all;
- current pointer rows missing `stateScope` are not sufficient proof for preserving gameplay-scoped routing metadata;
- session-shell scope claims do not remain authoritative once current pointer authority can no longer prove them.

##### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: Planned Work

###### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: 1. Command Ledger Authority Fence

- [x] Require complete current pointer authority before preserving or repairing gameplay-scoped routing metadata in `CommandServiceImpl`.
- [x] Collapse missing or incomplete authority back to `UNSPECIFIED` plus no routing bundle.

###### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: 2. Proof and Documentation

- [x] Update focused `CommandServiceImplTest` coverage for missing and incomplete-current-pointer cases.
- [x] Re-run full Game Session proof and fresh rebuilt smoke on the converged behavior.
- [x] Align parent/index docs with the new durable-command routing fence.

##### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: Acceptance Shape

- gameplay commands no longer persist `SHARED` or `ISOLATED` alone when current pointer authority cannot prove the rest of the bundle;
- incomplete current selector pointers do not count as proof for durable command-row routing metadata;
- accepted command rows still preserve the canonical bundle when current selector or runtime-target authority proves it;
- focused, broad, and fresh-bootstrap proof stay green after the ledger hardening.

##### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down -v --remove-orphans`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-27-task-list-fail-closed-durable-command-routing-metadata-without-current-pointer-authority-vertical-slice-1-74: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71

#### 09.1.28 Task List: Fail-Closed Remote Handoff Missing Durable IDs Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-71)

##### Preserved Source Text: source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71

<!-- migration-source path="design/project-management/vertical-slices/09.1.28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice.md" lines="1-71" sha256="f9323ddf49b67b3e9da4555f3002741d1614f9be559bc44759648633146941b0" heading-offset="3" -->
#### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: 09.1.28 Task List: Fail-Closed Remote Handoff Missing Durable IDs Vertical Slice

##### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: Goal and Status

Goal: ensure Automation only treats a remote gameplay-command handoff as successful when Game Session returns durable remote followup identifiers that make the scheduled work observable and retry-safe. Status: complete at the current bounded boundary.

##### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: Why This Slice Exists

`09.1` already made remote followup and command-routing metadata more canonical across durable rows and control-plane reads. One Automation ingress seam still failed open:

- `ScriptGameplayCommandHandoffServiceImpl` called `scheduleRemoteFollowup(...)` for remote target commands;
- if the response had no transport/application error, Automation marked the work item `HANDED_OFF` immediately;
- it did that even when the response omitted `coordinatorId` or `followupId`, which left a “successful” handoff without the durable identifiers needed for later inspection, retry tracking, or failure correlation.

That was a bounded success-classification gap rather than a routing-bundle gap, but it still let durable orchestration truth become incomplete.

##### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: Implementation Notes

- remote handoff success in `ScriptGameplayCommandHandoffServiceImpl` now requires both `!hasError()` and nonblank `coordinatorId` plus `followupId`.
- a remote response that omits either durable identifier now converts to a rejected handoff with `REMOTE_RESPONSE_INVALID`.
- the invalid response path flows through the existing dead-letter handling: the work item becomes `DEAD_LETTERED`, audit outcome becomes `handoff_failed`, and the handoff event records the rejected outcome instead of a false success.

##### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: Scope

- remote gameplay-command handoff classification in Automation;
- focused unit proof for blank-id remote responses;
- full Automation service validation and markdown/link hygiene.

##### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: Out of Scope

- remote followup execution on the Game Session side after scheduling succeeds;
- broader Automation work-item retry policy changes;
- additional remote handoff protocol fields beyond the durable identifiers already expected.

##### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: Locked Direction

- Automation must not mark a remote gameplay-command handoff successful unless the durable remote ids needed for later tracking are present;
- missing `coordinatorId` or `followupId` is an invalid remote response, not a soft success;
- durable operator/audit truth should prefer bounded rejection over success rows that cannot be correlated later.

##### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: Planned Work

###### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: 1. Remote Success Classification Fence

- [x] Require nonblank remote coordinator/followup ids before accepting a remote handoff result.
- [x] Reuse the existing dead-letter path for invalid success-shaped remote responses.

###### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: 2. Proof and Documentation

- [x] Add focused unit proof for blank remote ids.
- [x] Re-run full Automation validation on the converged behavior.
- [x] Align parent/index docs with the new remote-handoff fence.

##### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: Acceptance Shape

- remote handoff without durable ids no longer leaves a `HANDED_OFF` work item behind;
- invalid remote success-shaped responses become dead-lettered and auditable;
- valid remote scheduling responses still behave unchanged;
- focused, broad, and doc-hygiene proof stay green after the hardening.

##### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: Validation

- `./gradlew :automation-scripting-service:test --tests 'net.firedevops.firemud.automationscripting.service.impl.ScriptGameplayCommandHandoffServiceImplTest'`
- `./gradlew spotlessApply :automation-scripting-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-28-task-list-fail-closed-remote-handoff-missing-durable-ids-vertical-slice-1-71: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72

#### 09.1.29 Task List: Fail-Closed Remote Followup Scope-Only Routing Metadata Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-72)

##### Preserved Source Text: source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72

<!-- migration-source path="design/project-management/vertical-slices/09.1.29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice.md" lines="1-72" sha256="820a60620bcf5f22a32b3c54cc1a0368f29f0f4e6e678b603dd3f57353ceb7a0" heading-offset="3" -->
#### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: 09.1.29 Task List: Fail-Closed Remote Followup Scope-Only Routing Metadata Vertical Slice

##### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: Goal and Status

Goal: ensure remote followup coordinator, followup, and result rows persist gameplay routing identity only as one complete canonical bundle instead of preserving `playableStateScope` after world/realm/pointer metadata has already collapsed. Status: complete at the current bounded boundary.

##### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: Why This Slice Exists

`09.1` already converged command rows, automation handoff requests, and remote scheduling/read projections on all-or-none routing-bundle behavior for `worldSlug`, `realmSlug`, and `pointerVersion`. One narrower durable seam still drifted:

- `RemoteFollowupRuntimeServiceImpl` already collapsed partial world/realm/pointer input to no routing bundle;
- but it still preserved `playableStateScope` independently through request/command fallback and stored-value fallback;
- that allowed coordinator, followup, and result rows to keep a scope-only routing claim after the rest of the admitted bundle had already been dropped.

That was the same partial-truth class as the durable gameplay-command ledger seam, just in the remote followup substrate.

##### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: Implementation Notes

- `RemoteFollowupRuntimeServiceImpl` now derives remote followup routing metadata as one canonical shape: `{playableStateScope, worldSlug, realmSlug, pointerVersion}` is preserved only when all four values are present after request/command or stored-value normalization.
- Retry-time scheduling metadata equality now compares that same canonical collapsed shape, so a partial request that collapses to “no routing metadata” can still match a previously collapsed stored row.
- Coordinator/followup persistence and result projection now all reuse the same routing-metadata normalization, so legacy or partial stored rows no longer project `SHARED` or `ISOLATED` alone.

##### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: Scope

- Game Session remote followup runtime scheduling and result projection metadata;
- focused unit proof for partial scheduling input, retry equality, and partial stored result projection;
- full Game Session validation and doc hygiene.

##### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: Out of Scope

- remote followup execution semantics beyond routing metadata persistence;
- Automation producer-side routing metadata construction;
- broader operator/control-plane filtering changes outside the existing stored-row fields.

##### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: Locked Direction

- remote followup durable rows must persist either one complete admitted routing identity or no routing identity at all;
- `playableStateScope` alone is not authoritative once the rest of the routing bundle has collapsed;
- retry equality and later result projection must normalize the same canonical routing shape instead of comparing raw partially populated fields.

##### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: Planned Work

###### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: 1. Remote Followup Routing-Metadata Fence

- [x] Collapse scope-only remote scheduling metadata to absent alongside the routing bundle.
- [x] Reuse the same canonical collapse for retry equality and result projection.

###### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: 2. Proof and Documentation

- [x] Update focused `RemoteFollowupRuntimeServiceImplTest` coverage for partial scheduling and stored-row projection.
- [x] Re-run full Game Session validation on the converged behavior.
- [x] Align parent/index docs with the new remote followup routing fence.

##### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: Acceptance Shape

- coordinator/followup/result rows no longer preserve `SHARED` or `ISOLATED` when world/realm/pointer metadata is absent;
- retry-time remote scheduling still accepts a request that canonically collapses to the same “no routing metadata” shape as an existing row;
- complete routing metadata still persists unchanged when all required values are present;
- focused, broad, and doc-hygiene proof stay green after the hardening.

##### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.RemoteFollowupRuntimeServiceImplTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-29-task-list-fail-closed-remote-followup-scope-only-routing-metadata-vertical-slice-1-72: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68

#### Communication Routing Availability Follow-Through Vertical Slice - Realm-routing availability and target authority (source lines 1-14, 39-68)

##### Preserved Source Text: source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68

<!-- migration-source path="design/project-management/vertical-slices/09.1.3-task-list-communication-routing-availability-follow-through-vertical-slice.md" lines="1-14, 39-68" sha256="597b72f23a433f17b13cf971e67a7f487b8a992b68a9641815dc707044fd9681" heading-offset="3" -->
#### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68: Communication Routing Availability Follow-Through Vertical Slice

##### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68: Goal and Status

Goal: remove the next routing-fence leak in gameplay communication by making `TELL` availability checks respect admitted-session normalization instead of treating a raw gameplay-name Redis hit as authoritative proof that a target is still in-world. Status: complete at the current bounded boundary.

##### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68: Why This Slice Exists

`09.1`, `09.1.1`, and `09.1.2` already established one canonical routing truth for admitted gameplay sessions:

- persisted pointer authority plus admitted `{worldSlug, realmSlug, tenantId, gameInstanceId, pointerVersion, playableStateScope}`;
- stale-pointer normalization that collapses invalid gameplay bindings back to a non-gameplay shell;
- fail-closed command, replay, disconnect, and projection paths once that normalization removes gameplay state.

<!-- source-gap: lines 15-38 -->

##### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68: Locked Direction

- gameplay communication availability must respect the same stale-pointer shell normalization as later delivery and replay paths;
- raw Redis gameplay-name hits are advisory only until the resolved target survives routing normalization;
- dead local-catalog dependencies should not linger in post-admission gameplay handlers once pointer authority and admitted session state are already canonical.

##### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68: Planned Work

###### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68: 1. Availability Normalization

- [x] Normalize `TELL` target gameplay-name hits before treating the target as available.
- [x] Fail closed when the normalized target no longer has gameplay binding.

###### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68: 2. Dependency Cleanup and Proof

- [x] Remove the dead `GameplayWorldCatalog` dependency from `CommunicationCommandHandler`.
- [x] Add focused unit proof for stale-target normalization.

##### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68: Acceptance Shape

- `TELL` availability no longer trusts raw gameplay-name rows that would be cleared by routing normalization;
- communication availability and delivery now consume the same admitted-session fence;
- stale local-catalog dependency is gone from the in-scope communication handler.

##### source-09-1-3-task-list-communication-routing-availability-follow-through-vertical-slice-1-14-39-68: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72

#### 09.1.30 Task List: Fail-Closed Game Logic Outbound Attestation Normalization Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-72)

##### Preserved Source Text: source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72

<!-- migration-source path="design/project-management/vertical-slices/09.1.30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice.md" lines="1-72" sha256="668aa9f36a8dd621094032097346acd160ec8b55e397d6fc36b57d8ec59b8d86" heading-offset="3" -->
#### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: 09.1.30 Task List: Fail-Closed Game Logic Outbound Attestation Normalization Vertical Slice

##### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: Goal and Status

Goal: ensure Game Logic's packaged-stack blocking gameplay gRPC clients always attach internal-service outbound identity so downstream Entity Management gameplay reads fail closed only on real runtime issues, not on missing service attestation. Status: complete at the current bounded boundary.

##### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: Why This Slice Exists

`09.1` had already converged more Game Session session and routing reads on stricter admission-pointer authority, but fresh rebuilt smoke still exposed one remaining runtime seam:

- Game Logic gameplay look flows call Entity Management through blocking gRPC clients built in `GameLogicGrpcClientConfig`;
- those singleton stubs were resolving their customizer through `ObjectProvider.getIfAvailable(...)` at stub creation time;
- in the packaged stack that left a live path where the blocking stubs could be created without the internal-service auth customizer attached, and downstream gameplay entity RPCs then failed with missing or unsupported internal-service identity.

That left the runtime healthy enough to boot while still failing the first gameplay `LOOK` on a fresh stack.

##### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: Implementation Notes

- `GameLogicGrpcClientConfig` now depends directly on the resolved `BlockingGrpcStubCustomizer` bean instead of lazily asking an `ObjectProvider` for one during singleton stub creation.
- All packaged-stack blocking stubs now run through the same concrete outbound auth customizer path that `InternalGrpcClientAuthConfig` already publishes.
- The focused config proof was updated to construct the client config with a direct customizer so the test matches the runtime wiring.

##### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: Scope

- Game Logic packaged-stack blocking gRPC client configuration;
- focused config proof for blocking-stub customization;
- fresh rebuilt runtime smoke that exercises Game Logic -> Entity Management gameplay reads through WebSocket and Telnet.

##### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: Out of Scope

- broader gRPC auth policy changes outside the existing internal-service client customizer path;
- non-blocking stub families or unrelated service-to-service auth call paths;
- gameplay routing or admission-pointer semantics beyond the downstream attestation failure surfaced by `LOOK`.

##### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: Locked Direction

- packaged-stack gameplay gRPC clients must always attach internal-service outbound identity before calling downstream gameplay services;
- singleton blocking stubs must not retain a `noop` customization path when the runtime requires internal-service auth;
- fresh bootstrap smoke remains the proof of record for this attestation path because the failure only surfaced after rebuilding and booting the full stack.

##### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: Planned Work

###### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: 1. Game Logic Outbound Auth Fence

- [x] Remove the provider-based `noop` fallback from packaged-stack blocking stub creation.
- [x] Keep blocking stub construction on the canonical internal-service auth customizer path.

###### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: 2. Proof and Documentation

- [x] Update focused Game Logic config proof.
- [x] Re-run broad touched-service validation and markdown hygiene.
- [x] Re-run the canonical fresh bootstrap smoke against the rebuilt stack.

##### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: Acceptance Shape

- Game Logic blocking gRPC stubs always attach internal-service outbound identity in the packaged stack;
- gameplay `LOOK` no longer fails on missing internal-service identity after fresh bootstrap;
- WebSocket and Telnet bootstrap smoke both clear the gameplay item/container/equipment path on rebuilt images;
- focused, broad, smoke, and doc-hygiene proof stay green after the wiring hardening.

##### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: Validation

- `./gradlew :game-logic-service:test --tests 'unit.net.firedevops.firemud.gamelogic.config.GameLogicGrpcClientConfigTest' --tests 'unit.net.firedevops.firemud.gamelogic.config.InternalGrpcClientAuthConfigTest'`
- `./gradlew spotlessApply :game-logic-service:check :game-session-service:check -PfullCheck`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-30-task-list-fail-closed-game-logic-outbound-attestation-normalization-vertical-slice-1-72: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75

#### 09.1.31 Task List: Fail-Closed Session and First-Party Login Runtime-Target Authority Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-75)

##### Preserved Source Text: source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75

<!-- migration-source path="design/project-management/vertical-slices/09.1.31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice.md" lines="1-75" sha256="d94b27ba0c73f0e10b12bb1e7085daf6cd0da22e8bd87a1896a4c56f0abe0d25" heading-offset="3" -->
#### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: 09.1.31 Task List: Fail-Closed Session and First-Party Login Runtime-Target Authority Vertical Slice

##### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: Goal and Status

Goal: ensure normalized gameplay session reads and verified first-party `LOGIN` both validate routing against one singular complete current runtime-target pointer instead of treating selector lookup as sufficient authority after a runtime id is already known. Status: complete at the current bounded boundary.

##### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: Why This Slice Exists

`09.1` had already converged many gameplay and control-plane consumers onto singular complete runtime-target pointer authority, but one core session/auth seam still lagged:

- `SessionRoutingNormalizationService` still validated current gameplay binding through `findPointer(worldSlug, realmSlug)`;
- `LoginCommandHandler` still validated verified first-party connect context through the same selector lookup path;
- that left current-session normalization and first-party relogin flows able to trust one selector hit even when runtime-target authority was ambiguous, missing, or mismatched against the claimed selector bundle.

The broad Game Session proof immediately exposed that drift in session resumption, interpreter, login, and websocket first-party cutover coverage once the normalized session reader stopped using selector authority.

##### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: Implementation Notes

- `SessionRoutingNormalizationService` now reads current pointer authority through `listByRuntimeTarget(tenantId, gameInstanceId)` plus `GameplayAdmissionPointerSnapshots.singularCompletePointer(...)`.
- Normalized session gameplay binding now survives only when that authoritative runtime-target pointer still matches the stored `worldSlug`, `realmSlug`, and `pointerVersion`.
- Verified first-party `LOGIN` now validates the claimed runtime target the same way: the authoritative current pointer for `{tenantId, gameInstanceId}` must still match the claimed selector bundle and pointer version before the login is accepted.
- The broader session/login/interpreter/resumption test fixtures were updated to seed runtime-target pointer authority directly instead of stubbing selector lookup.

##### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: Scope

- Game Session normalized session routing reads;
- first-party verified login routing validation;
- related session/login/interpreter/resumption/websocket proof that depended on selector-based pointer fixtures.

##### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: Out of Scope

- broader admission-pointer mutation semantics;
- remote followup, command-routing, or other already-hardened runtime-target consumers outside the normalized session/login seam;
- new pointer helper behavior beyond using the existing singular-complete-pointer utility.

##### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: Locked Direction

- once a gameplay session or verified login already knows its runtime target, current routing authority must come from singular complete runtime-target pointer reads, not selector lookup;
- authoritative runtime-target validation may compare the claimed or stored selector bundle to the authoritative pointer row, but it must not ask selector lookup to mint that authority;
- ambiguous, missing, or mismatched current runtime-target authority must collapse gameplay binding or reject first-party login rather than preserving stale gameplay continuity.

##### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: Planned Work

###### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: 1. Session/Login Runtime-Target Fence

- [x] Move normalized session gameplay-binding validation to singular complete runtime-target authority.
- [x] Move verified first-party login routing validation to the same authoritative runtime-target path.

###### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: 2. Proof and Documentation

- [x] Update focused session-auth, login, interpreter, and resumption proofs for the converged authority source.
- [x] Re-run full Game Session validation on the broader affected surface.
- [x] Re-run the canonical fresh bootstrap smoke against the rebuilt stack.
- [x] Align parent/index docs with the new runtime-target authority fence.

##### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: Acceptance Shape

- normalized session reads no longer preserve gameplay binding merely because one selector row still points at the same runtime;
- verified first-party login no longer accepts a claimed selector bundle that disagrees with the authoritative current pointer for the claimed runtime target;
- session resumption, gameplay interpreter, and websocket first-party cutover proofs all stay green on the stricter runtime-target rule;
- focused, broad, smoke, and doc-hygiene proof stay green after the convergence.

##### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.SessionAuthenticationServiceTest' --tests 'net.firedevops.firemud.gamesession.command.text.LoginCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.command.text.SessionResumptionFlowTest' --tests 'net.firedevops.firemud.gamesession.command.text.TextCommandInterpreterTest'`
- `./gradlew :game-session-service:integrationTest --tests 'net.firedevops.firemud.gamesession.websocket.GameSessionWebSocketHandlerIntegrationTest.websocketFirstPartyPlayRejectsScopeMismatch'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-31-task-list-fail-closed-session-and-first-party-login-runtime-target-authority-vertical-slice-1-75: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70

#### 09.1.32 Task List: Authenticated TCP Proxy Disconnect Callbacks Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-70)

##### Preserved Source Text: source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70

<!-- migration-source path="design/project-management/vertical-slices/09.1.32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice.md" lines="1-70" sha256="57b58d9c742d6768f0998099f866ee20421bc8acdb3e5025c96007e18f13672e" heading-offset="3" -->
#### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: 09.1.32 Task List: Authenticated TCP Proxy Disconnect Callbacks Vertical Slice

##### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: Goal and Status

Goal: ensure trusted TCP proxy disconnect callbacks reach Game Session as authenticated internal-service traffic so transport teardown can clear session state deterministically instead of leaving stale Telnet or websocket bridge state behind. Status: complete at the current bounded boundary.

##### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: Why This Slice Exists

`09.1` had already hardened trusted TCP proxy bootstrap routing and fail-closed disconnect runtime-suspend authority, but one callback seam still lagged:

- `TcpProxyEventClient` still used a raw blocking stub without the canonical internal-service auth customizer;
- Game Session now requires an authenticated internal-service caller identity on that callback surface;
- failed disconnect callbacks left stale transport/session state behind, which polluted later Telnet smoke transcripts and made equipment-flow proof nondeterministic even when the gameplay handlers themselves were correct.

##### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: Implementation Notes

- `TcpProxyEventClient` now injects the canonical `BlockingGrpcStubCustomizer` and applies it when rebuilding its Game Session callback stub.
- `tcp-proxy-service` now forces internal-service outbound gRPC auth in service config so the callback seam always carries service identity instead of drifting with caller context.
- The focused TCP proxy client proof now verifies that channel reload customizes the outbound stub before disconnect notifications are issued.
- Canonical fresh-bootstrap smoke now re-proves that websocket and Telnet equipment flows both stay clean after logout or disconnect instead of inheriting stale session bridge state.

##### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: Scope

- trusted TCP proxy disconnect callback auth from TCP proxy into Game Session;
- focused client proof for the callback stub;
- fresh bootstrap runtime proof covering the previously polluted Telnet equipment loop.

##### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: Out of Scope

- gameplay admission routing rules already covered by earlier `09.1` seams;
- broader TCP proxy bootstrap header shaping beyond the already-landed hidden-routing normalization work;
- unrelated Game Session callback surfaces.

##### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: Locked Direction

- trusted TCP proxy callbacks into Game Session are internal service-to-service traffic and must use the canonical authenticated stub seam;
- transport teardown must fail closed on missing runtime metadata without also losing the ability to clear session state authoritatively;
- smoke-proof stability issues caused by stale disconnect state should be fixed at the callback/auth seam, not papered over in the smoke harness.

##### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: Planned Work

###### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: 1. Disconnect Callback Auth

- [x] Move `TcpProxyEventClient` onto the canonical blocking-stub auth customizer.
- [x] Force internal-service outbound auth for TCP proxy gRPC callbacks.

###### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: 2. Proof and Documentation

- [x] Add focused proof that reload-time stub creation uses the auth customizer.
- [x] Re-run canonical fresh bootstrap smoke on a clean rebuilt stack.
- [x] Align parent/index docs with the disconnect-callback hardening seam.

##### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: Acceptance Shape

- TCP proxy disconnect notifications no longer fail with `UNAUTHENTICATED: Missing token`;
- transport teardown no longer leaves stale Telnet or websocket bridge state behind after smoke or reconnect-style flows;
- websocket and Telnet item/container/equipment smoke both stay green on a fresh rebuilt stack after the callback hardening.

##### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: Validation

- `./gradlew :tcp-proxy-service:test --tests 'net.firedevops.firemud.tcpproxy.service.TcpProxyEventClientTest'`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- `./gradlew spotlessApply :game-session-service:check :tcp-proxy-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-32-task-list-authenticated-tcp-proxy-disconnect-callbacks-vertical-slice-1-70: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75

#### 09.1.33 Task List: Fail-Closed Login-Era Command Staging Runtime-Target Authority Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-75)

##### Preserved Source Text: source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75

<!-- migration-source path="design/project-management/vertical-slices/09.1.33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice.md" lines="1-75" sha256="d1cc2e188f40747626bd905bd9632e5366cf70278056bba5a99dba947d846ea8" heading-offset="3" -->
#### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: 09.1.33 Task List: Fail-Closed Login-Era Command Staging Runtime-Target Authority Vertical Slice

##### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: Goal and Status

Goal: ensure accepted login-era and pre-`PLAY` gameplay-command rows persist routing metadata only from singular complete current runtime-target authority once the target runtime is already known, instead of trusting selector lookup as if it were still the canonical source. Status: complete at the current bounded boundary.

##### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: Why This Slice Exists

`09.1` had already hardened normalized session reads, verified `LOGIN`, websocket bootstrap repair, logout runtime-stop policy, and related runtime-target consumers, but command staging still lagged:

- `CommandServiceImpl` still repaired or preserved routing metadata through `findPointer(worldSlug, realmSlug)` when staging accepted command rows;
- that let pre-`PLAY` command admission treat selector lookup as enough authority even after the bootstrap or gameplay runtime target was already known;
- ambiguous or missing current runtime-target authority therefore depended on whichever selector row still existed instead of collapsing or repairing routing metadata through the singular current runtime view.

##### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: Implementation Notes

- `CommandServiceImpl` now resolves accepted-command routing metadata through `listByRuntimeTarget(tenantId, gameInstanceId)` plus `GameplayAdmissionPointerSnapshots.singularCompletePointer(...)` whenever the staged shell already knows the bootstrap or gameplay runtime target.
- When a shell still retains both the pre-`PLAY` bootstrap runtime id and the active gameplay runtime id, accepted gameplay-bound command rows now treat the active gameplay runtime as the authoritative target instead of collapsing back onto the older bootstrap shell runtime.
- Existing command-shell routing bundles now survive only when that authoritative runtime-target pointer still matches the stored `playableStateScope`, `worldSlug`, `realmSlug`, and `pointerVersion`.
- When current runtime-target authority is singular and complete but the stored bundle is stale, accepted command rows now repair to the current authoritative pointer instead of preserving the stale bundle.
- When current runtime-target authority is missing, ambiguous, or incomplete, accepted command rows now collapse routing metadata back to `UNSPECIFIED` instead of minting it from selector lookup.

##### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: Scope

- `CommandServiceImpl` routing metadata persisted onto accepted command rows;
- focused Game Session unit proof for login-era and gameplay-bound staging cases;
- parent/index slice documentation for the new authority convergence seam.

##### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: Out of Scope

- later durable command replay/application seams already handled by earlier `09.1` batches;
- gameplay command execution semantics outside accepted-row routing metadata persistence;
- new admission-pointer helper behavior beyond the existing singular-complete runtime-target utility.

##### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: Locked Direction

- once accepted command staging already knows its bootstrap or gameplay runtime target, current routing authority must come from singular complete runtime-target pointer reads, not selector lookup;
- selector bundles carried on the session shell are freshness claims to validate against runtime-target authority, not enough authority on their own;
- ambiguous, missing, or incomplete current runtime-target authority must collapse staged routing metadata rather than persisting a best-effort world/realm identity.

##### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: Planned Work

###### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: 1. Login-Era Command Authority Fence

- [x] Move accepted-command routing repair/preservation onto singular current runtime-target authority.
- [x] Drop or repair stale login-era routing bundles based on that authoritative runtime-target pointer.

###### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: 2. Proof and Documentation

- [x] Update focused `CommandServiceImpl` proof for the converged authority source.
- [x] Align parent/index docs with the accepted-command runtime-target fence.
- [x] Re-run broader Game Session validation on the updated command-staging seam.

##### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: Acceptance Shape

- accepted login-era command rows no longer preserve routing metadata merely because one selector row still matches the old bundle;
- staged gameplay-command rows repair to the current authoritative pointer when the runtime target is still singular and current but the stored bundle is stale;
- missing, ambiguous, or incomplete current runtime-target authority now collapses accepted command routing metadata back to `UNSPECIFIED`.

##### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest'`
- `./gradlew :game-session-service:crossServiceTest --tests 'net.firedevops.firemud.gamesession.MultiplayerLoadProofCrossServiceTest.tenConcurrentPlayersCanLoginPlayAndLookAgainstRealCrossServiceStack'`
- `./gradlew :tcp-proxy-service:check -PfullCheck`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
- `bash dev-tools/verify-fresh-bootstrap.sh`

Validation note: the final bootstrap proof exposed and validated one adjacent packaged-stack fix outside the narrow command-staging seam itself. `tcp-proxy-service` now explicitly wires internal gRPC client auth through local `InternalGrpcClientAuthConfig` plus `common-security`, which restored fresh-bootstrap Telnet readiness and disconnect callback startup without reopening the `09.1.33` routing boundary.

##### source-09-1-33-task-list-fail-closed-login-era-command-staging-runtime-target-authority-vertical-slice-1-75: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65

#### 09.1.34 Task List: Control-Plane Cutover Validator Canonical Pointer Read Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-65)

##### Preserved Source Text: source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65

<!-- migration-source path="design/project-management/vertical-slices/09.1.34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice.md" lines="1-65" sha256="dbcaac32b16a437910d7fe8e510c7f0a7be4a6635c8a7ef2d9657fda6441ff30" heading-offset="3" -->
#### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: 09.1.34 Task List: Control-Plane Cutover Validator Canonical Pointer Read Vertical Slice

##### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: Goal and Status

Goal: keep prepared admission-pointer cutover validation on the same canonical current-pointer read shape as the live admission contract, so operator cutover checks do not fall back to selector-shaped `{worldSlug, realmSlug}` pointer probes once live admission has already converged onto direct singular pointer authority. Status: complete at the current bounded boundary.

##### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: Why This Slice Exists

`09.1` had already converged the public admission-pointer read contract, login/bootstrap consumers, and later runtime-target helper seams, but one operator mutation path still lagged:

- `GameSessionAdmissionPointerControlPlaneService` still fetched the current pointer for prepared cutover validation through `findPointer(worldSlug, realmSlug)`;
- that kept one selector-shaped control-plane authority read alive even though `GetAdmissionPointer` and the broader routing contract had already converged on direct singular pointer authority;
- prepared cutover validation could therefore drift back toward the old browse-selector authority shape instead of the then-current narrower singular admission-pointer read.

##### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: Implementation Notes

- `GameSessionAdmissionPointerControlPlaneService` now reads the current pointer for `setAdmissionPointer(...)` prepared-upgrade validation and `executePreparedVersionCutover(...)` through the same direct singular pointer-authority path that live admission used at the time; later `09.1.40` world-qualified that shared singular key.
- Existing grpc control-plane proof now stubs that direct singular read, so the focused tests fail if the old `worldSlug + realmSlug` lookup path reappears.

##### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: Scope

- prepared cutover validation inside `GameSessionAdmissionPointerControlPlaneService`;
- focused `GameSessionControlPlaneGrpcServiceTest` proof for `setAdmissionPointer(...)` and `executePreparedVersionCutover(...)`;
- parent/index slice documentation for the control-plane convergence seam.

##### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: Out of Scope

- broader admission-pointer mutation/list operator follow-through outside prepared cutover validation;
- runtime-target singular-proof read helpers already covered by earlier `09.1` batches;
- new cutover policy or prepared-upgrade compatibility rules beyond the canonical current-pointer read source.

##### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: Locked Direction

- once operator cutover validation already knows the singular current-pointer target, it must read the current admission pointer through the same direct authority contract as live admission instead of recreating selector-shaped `worldSlug + realmSlug` authority;
- control-plane cutover validation should stay aligned with the same admission-pointer contract exposed to first-party/runtime consumers rather than preserving a second lookup shape for operator flows.

##### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: Planned Work

###### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: 1. Control-Plane Pointer Read Convergence

- [x] Move prepared cutover validation onto the same direct singular current-pointer read as live admission.
- [x] Remove focused control-plane proof dependence on the old selector-shaped pointer lookup.

###### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: 2. Proof and Documentation

- [x] Update focused grpc control-plane proof for the converged read source.
- [x] Align parent/index docs with the control-plane cutover-validator seam.
- [x] Re-run broader Game Session validation on the updated control-plane path.

##### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: Acceptance Shape

- prepared admission-pointer mutation and cutover validation no longer depend on `findPointer(worldSlug, realmSlug)` when the live admission contract already exposes one direct singular current-pointer read;
- focused grpc control-plane proof fails if the old selector-shaped lookup path reappears.

##### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcServiceTest.*setAdmissionPointer*' --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcServiceTest.*executePreparedVersionCutover*'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-34-task-list-control-plane-cutover-validator-canonical-pointer-read-vertical-slice-1-65: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71

#### 09.1.35 Task List: Fail-Closed Tenant-Scoped Session Authority Reuse Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-71)

##### Preserved Source Text: source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71

<!-- migration-source path="design/project-management/vertical-slices/09.1.35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice.md" lines="1-71" sha256="2e06f00746d9503efa8f2a339f1867b470ad92c3b6cf69dd70fc76a42f184ca8" heading-offset="3" -->
#### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: 09.1.35 Task List: Fail-Closed Tenant-Scoped Session Authority Reuse Vertical Slice

##### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: Goal and Status

Goal: keep projected and unverified session reads on the canonical tenant-scoped session authority once the raw session index has identified a tenant, so session normalization and login flows do not revive a raw session shell after the `{tenantId, sessionId}` record is already missing. Status: complete at the current bounded boundary.

##### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: Why This Slice Exists

`09.1` had already converged most gameplay and reconnect-sensitive readers on normalized session truth, but one storage-layer shortcut still remained:

- `SessionRoutingNormalizationService.resolveProjectedSessionContext(...)` still fell back to `findBySessionId(sessionId)` after it had already derived a tenant id from the raw session index;
- `SessionAuthenticationService` still kept its own duplicate raw `findBySessionId(...)` tenant lookup instead of routing that through the shared normalization helper;
- that let login or projection paths keep reviving a raw session shell even when the canonical tenant-scoped session record was gone.

##### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: Implementation Notes

- `SessionRoutingNormalizationService.resolveProjectedSessionContext(...)` now fails closed once tenant discovery succeeds but `findByTenantAndSessionId(tenantId, sessionId)` is absent; it no longer falls back to the raw session row.
- `SessionAuthenticationService` now resolves tenant discovery through `SessionRoutingNormalizationService.findTenantId(...)` instead of keeping a second duplicate raw session lookup path locally.
- Focused auth and login proof now cover the raw-only session-shell case directly, so a future regression that revives `findBySessionId(...)` after tenant discovery will fail the targeted tests.

##### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: Scope

- `SessionRoutingNormalizationService` projected-session lookup behavior after tenant discovery;
- `SessionAuthenticationService` unverified/authenticated session lookup convergence onto the shared tenant-discovery helper;
- focused auth/login proof and parent/index slice documentation for the session-authority seam.

##### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: Out of Scope

- changing how the raw session index discovers tenant identity in the first place;
- gameplay-binding normalization semantics already covered by earlier `09.1` batches;
- websocket/bootstrap/read-model consumers that already go through these shared session readers.

##### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: Locked Direction

- once a session read has identified the tenant from the raw session index, authoritative session state must come from the canonical `{tenantId, sessionId}` record or fail closed;
- raw `findBySessionId(...)` is an index for tenant discovery, not a second authoritative session-shell read after tenant-scoped authority is known;
- shared session-normalization helpers should own tenant discovery and session projection behavior instead of duplicating raw storage lookups across higher-level readers.

##### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: Planned Work

###### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: 1. Tenant-Scoped Session Authority Fence

- [x] Remove raw projected-session fallback after tenant discovery succeeds.
- [x] Converge unverified session tenant discovery onto the shared normalization helper.

###### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: 2. Proof and Documentation

- [x] Add focused proof for raw-only session rows no longer reviving projected or unverified session authority.
- [x] Align parent/index docs with the tenant-scoped session authority seam.
- [x] Re-run broader Game Session validation on the updated session-authority path.

##### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: Acceptance Shape

- projected session reads no longer return a raw session shell when the tenant-scoped session record is missing;
- unverified session reads no longer authenticate or normalize through a duplicate raw session lookup after tenant discovery;
- login-era first-party fallback no longer revives a raw persisted session shell that has lost its canonical tenant-scoped authority row.

##### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.SessionAuthenticationServiceTest' --tests 'net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationServiceTest' --tests 'net.firedevops.firemud.gamesession.command.text.LoginCommandHandlerTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
- `bash dev-tools/verify-fresh-bootstrap.sh`

Validation note: the first fresh-bootstrap attempt reused stale packaged images on the `game-session-service -> game-logic-service -> entity-management-service` path and failed a Telnet container follow-through with `SESSION_ATTESTATION_INVALID`. Rebuilding those three service images with `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml build --no-cache game-session-service game-logic-service entity-management-service` before rerunning `bash dev-tools/verify-fresh-bootstrap.sh` produced a clean WebSocket and Telnet smoke pass.

##### source-09-1-35-task-list-fail-closed-tenant-scoped-session-authority-reuse-vertical-slice-1-71: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69

#### 09.1.36 Task List: Logout Runtime-Stop Selector Fallback Removal Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-69)

##### Preserved Source Text: source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69

<!-- migration-source path="design/project-management/vertical-slices/09.1.36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice.md" lines="1-69" sha256="560c4d4b60cd30fd2e5ae77f4c5f56a8687364a39dbf86fadc2cc97db177e19f" heading-offset="3" -->
#### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: 09.1.36 Task List: Logout Runtime-Stop Selector Fallback Removal Vertical Slice

##### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: Goal and Status

Goal: keep logout runtime-stop policy on canonical runtime-target authority only, so deliberate logout does not treat a selector-shaped `{worldSlug, realmSlug}` lookup as enough proof that the current runtime is shared once the runtime target is already known. Status: complete at the current bounded boundary.

##### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: Why This Slice Exists

Earlier `09.1` batches had already moved logout off preferred-row runtime lookup and onto singular complete current runtime-target proof, but one smaller fallback still survived:

- `LogoutCommandHandler` still checked `findPointer(worldSlug, realmSlug)` before singular runtime-target authority when `playableStateScope` was absent on the session shell;
- that kept one selector-shaped proof alive inside a lifecycle policy that already knew `{tenantId, gameInstanceId}`;
- the last production `findPointer(worldSlug, realmSlug)` caller in Game Session main code therefore lived on in logout runtime-stop decisions even after the rest of the slice had converged on canonical runtime-target truth.

##### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: Implementation Notes

- `LogoutCommandHandler` now decides isolated-versus-shared fallback only from explicit session `playableStateScope` or singular complete current runtime-target authority via `listByRuntimeTarget(tenantId, gameInstanceId)`.
- The selector-based `sharedRealmFromSelectors(...)` helper is gone, so logout no longer treats one `worldSlug + realmSlug` lookup as separate runtime-stop authority after the runtime target is already known.
- Focused logout proof now covers the shared-runtime case through runtime-target authority alone, so a future regression that reintroduces selector fallback will fail the bounded test surface.

##### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: Scope

- logout runtime-stop proof in `LogoutCommandHandler`;
- focused `LogoutCommandHandlerTest` proof for shared, isolated, unknown, and ambiguous runtime-target authority;
- parent/index slice documentation for the selector-fallback removal seam.

##### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: Out of Scope

- startup pointer bootstrap seeding from config when the pointer store is empty;
- broader reconnect or session-normalization consumers already covered by earlier `09.1` batches;
- any new logout UX or disconnect-side lifecycle behavior beyond runtime-stop authority.

##### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: Locked Direction

- once logout already knows `{tenantId, gameInstanceId}`, shared-versus-isolated proof must come from explicit session scope or canonical current runtime-target pointer authority, not a selector-shaped lookup;
- selector-shaped pointer reads are not a second lifecycle-policy authority path after runtime-target identity is already known;
- missing, ambiguous, or incomplete current runtime-target authority must keep logout fail-closed to “do not stop the runtime.”

##### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: Planned Work

###### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: 1. Logout Authority Convergence

- [x] Remove selector-shaped shared-runtime fallback from logout runtime-stop policy.
- [x] Keep shared-versus-isolated fallback on singular complete current runtime-target authority only.

###### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: 2. Proof and Documentation

- [x] Update focused logout proof for the runtime-target-only shared-runtime case.
- [x] Align parent/index docs with the selector-fallback removal seam.
- [x] Re-run broader Game Session validation on the updated logout path.

##### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: Acceptance Shape

- logout no longer calls `findPointer(worldSlug, realmSlug)` when deciding whether the current runtime should stop;
- shared-runtime logout fallback now depends only on explicit session scope or singular complete runtime-target authority;
- unknown, ambiguous, or incomplete runtime-target authority still keeps logout fail-closed to “keep the runtime running.”

##### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.command.text.LogoutCommandHandlerTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
- `bash dev-tools/verify-fresh-bootstrap.sh`

##### source-09-1-36-task-list-logout-runtime-stop-selector-fallback-removal-vertical-slice-1-69: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71

#### 09.1.37 Task List: Bootstrap Pointer Seed Model Isolation Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-71)

##### Preserved Source Text: source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71

<!-- migration-source path="design/project-management/vertical-slices/09.1.37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice.md" lines="1-71" sha256="35146723c303eff96875c9d4329d455bd1faacaaa7fece6a167cc3c50e44df71" heading-offset="3" -->
#### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: 09.1.37 Task List: Bootstrap Pointer Seed Model Isolation Vertical Slice

##### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: Goal and Status

Goal: remove the last production `GameplayCatalogProperties` dependency from Game Session by moving startup-only admission-pointer seeding onto a dedicated bootstrap pointer-seed model, while preserving the same bounded empty-store bootstrap behavior. Status: complete at the current bounded boundary.

##### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: Why This Slice Exists

Earlier `09.1` work had already moved live routing behavior onto persisted admission-pointer authority and removed `GameplayCatalogProperties` from runtime catalog and command paths. One production coupling still survived:

- `GameplayAdmissionPointerBootstrapInitializer` still iterated the live catalog property model to seed pointer rows when the authority store was empty;
- that left the startup-only seed path teaching the same config schema as if it were still the production routing model;
- Game Session therefore still had one production `GameplayCatalogProperties` dependency even after runtime readers and catalog projection had converged elsewhere.

##### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: Implementation Notes

- Added dedicated `GameplayAdmissionPointerBootstrapProperties` under the Game Session config package for startup-only pointer seed entries.
- `GameplayAdmissionPointerBootstrapInitializer` now reads those bootstrap seed entries directly instead of iterating `GameplayCatalogProperties.World` / `Realm`.
- The Game Session default config now declares explicit bootstrap pointer seeds rather than a live catalog tree.
- Focused initializer proof now covers seeding valid entries, skipping invalid entries, and no-op behavior when pointer authority already exists.

##### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: Scope

- startup-only pointer seed config and bootstrap wiring in Game Session;
- focused initializer proof for empty-store seeding and invalid-seed skipping;
- `09.1` parent/index documentation for the last production `GameplayCatalogProperties` dependency removal.

##### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: Out of Scope

- removing startup bootstrap seeding entirely in favor of an external control-plane or provisioning-only seed path;
- changing Account Service bootstrap/discovery config structure;
- broader cutover/reconnect follow-through already tracked elsewhere in `09.1`.

##### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: Locked Direction

- live runtime routing authority stays on persisted admission-pointer rows, not on a shared catalog config schema;
- startup-only seed behavior may remain bounded, but it should use an explicit seed model rather than the live gameplay catalog authority shape;
- `GameplayCatalogProperties` remains a test and fallback helper, not a production routing model inside Game Session.

##### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: Planned Work

###### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: 1. Bootstrap Model Isolation

- [x] Add a dedicated Game Session bootstrap pointer-seed properties model.
- [x] Move `GameplayAdmissionPointerBootstrapInitializer` onto that dedicated seed model.
- [x] Keep the existing empty-store bootstrap behavior unchanged in effect.

###### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: 2. Proof and Documentation

- [x] Add focused initializer proof for seed, skip, and no-op behavior.
- [x] Align `09.1` parent/index docs with the isolated bootstrap-seed model.
- [x] Re-run Game Session validation and fresh bootstrap proof.

##### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: Acceptance Shape

- Game Session production code no longer imports `GameplayCatalogProperties`;
- startup pointer bootstrap still seeds the same canonical demo/sandbox pointer rows when the authority store is empty;
- runtime routing authority remains unchanged and still comes from persisted admission-pointer rows after startup.

##### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.data.GameplayAdmissionPointerBootstrapInitializerTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
- `bash dev-tools/verify-fresh-bootstrap.sh`

##### source-09-1-37-task-list-bootstrap-pointer-seed-model-isolation-vertical-slice-1-71: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67

#### 09.1.38 Task List: Account Bootstrap Catalog Config Retirement Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-67)

##### Preserved Source Text: source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67

<!-- migration-source path="design/project-management/vertical-slices/09.1.38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice.md" lines="1-67" sha256="a518ae77714ab12cf31f6befe977b60bc5d2e397ac72f2db1a2816440b771d34" heading-offset="3" -->
#### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: 09.1.38 Task List: Account Bootstrap Catalog Config Retirement Vertical Slice

##### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: Goal and Status

Goal: retire the stale Account Service local gameplay catalog config copy now that bootstrap discovery, connect-token issuance, and public-production admission all consume Game Session routing authority directly. Status: complete at the current bounded boundary.

##### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: Why This Slice Exists

`09.1` had already moved Account bootstrap discovery and admission checks onto Game Session routing reads, but one dead local artifact still survived:

- `account-service` still shipped the old `firemud.gameplay.catalog` config block in `application.yml`;
- no production code or tests still consumed that block;
- leaving it in place kept advertising a second realm-catalog authority source on a service that had already converged away from it.

##### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: Implementation Notes

- Removed the stale `firemud.gameplay.catalog` block from `account-service` default config.
- Updated `09.1` slice docs to record that Account no longer ships a dead local gameplay catalog copy after the routing convergence work.
- Revalidated Account startup and the canonical fresh bootstrap smoke after the config removal.

##### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: Scope

- `account-service` default config cleanup for the dead gameplay catalog block;
- `09.1` parent/index documentation for the local config retirement;
- validation that bootstrap discovery and connect-token flows still work without that config copy.

##### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: Out of Scope

- removing Game Session's startup pointer seed behavior itself;
- changing Account bootstrap/discovery API behavior;
- broader reconnect/cutover routing follow-through outside this dead-config retirement.

##### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: Locked Direction

- once a service consumes canonical routing authority remotely, it should not keep a dead local catalog config copy that suggests independent realm-routing truth;
- bootstrap and connect-token behavior should depend on Game Session routing reads, not on unused local config residue.

##### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: Planned Work

###### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: 1. Dead Config Retirement

- [x] Remove the stale Account Service `firemud.gameplay.catalog` config block.
- [x] Keep bootstrap discovery and connect-token behavior unchanged.

###### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: 2. Proof and Documentation

- [x] Align `09.1` docs with the Account-side dead-config retirement.
- [x] Re-run Account validation.
- [x] Re-run fresh bootstrap smoke after the startup config cleanup.

##### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: Acceptance Shape

- `account-service` no longer ships a local gameplay catalog config copy;
- Account bootstrap discovery and admission behavior still succeed through Game Session routing authority;
- the repo no longer teaches Account local config as a plausible routing source after `09.1` convergence.

##### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: Validation

- `./gradlew :account-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
- `bash dev-tools/verify-fresh-bootstrap.sh`

##### source-09-1-38-task-list-account-bootstrap-catalog-config-retirement-vertical-slice-1-67: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70

#### 09.1.39 Task List: Admission-Pointer Selector Read API Removal Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-70)

##### Preserved Source Text: source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70

<!-- migration-source path="design/project-management/vertical-slices/09.1.39-task-list-admission-pointer-selector-read-api-removal-vertical-slice.md" lines="1-70" sha256="5150af50b348c99c9114e810a48c768e10a29bb2576a2ac0326542cd6a75a10f" heading-offset="3" -->
#### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: 09.1.39 Task List: Admission-Pointer Selector Read API Removal Vertical Slice

##### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: Goal and Status

Goal: remove the last public selector-shaped `worldSlug + realmSlug` admission-pointer read API from Game Session so live consumers and proof use one direct singular pointer lookup contract instead of keeping a second browse-shaped helper alive. Status: complete at the current bounded boundary.

##### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: Why This Slice Exists

`09.1.24` had already moved live gRPC admission-pointer lookup onto direct singular pointer authority, but one dead public helper still survived:

- `GameplayAdmissionPointerAuthorityService` still advertised `findPointer(worldSlug, realmSlug)`;
- the database implementation still exposed that overload even though production callers had already converged away from it;
- one integration proof still used that selector-shaped helper directly, which kept the old read shape alive at the public service boundary.

##### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: Implementation Notes

- Removed the public selector-shaped `findPointer(worldSlug, realmSlug)` overload from `GameplayAdmissionPointerAuthorityService`.
- Removed the matching dead implementation method from `DatabaseGameplayAdmissionPointerAuthorityService`.
- Updated the surviving integration proof to read the current pointer through `listPointers()` plus explicit selector filtering before applying the cutover mutation, so the dead singular selector helper stays removed without inventing false tenant-scoped uniqueness in the fixture.

##### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: Scope

- Game Session admission-pointer public authority interface;
- database authority implementation for the removed overload;
- integration proof and slice documentation for the canonical singular pointer-read contract.

##### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: Out of Scope

- changing persisted admission-pointer selector columns or mutation semantics;
- changing pointer audit listing, which still intentionally keys on selector identity;
- broader routing-bundle or reconnect/cutover consumer work outside the public read API contraction.

##### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: Locked Direction

- singular current admission-pointer reads should expose one canonical lookup shape to live consumers;
- once runtime and gRPC consumers have converged on tenant-scoped pointer lookup, dead selector-shaped public helpers should be removed instead of preserved for tests or convenience;
- selector identity may remain part of pointer payloads and audit history without remaining a live public lookup authority.

##### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: Planned Work

###### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: 1. Public API Contraction

- [x] Remove the selector-shaped singular read overload from the authority interface.
- [x] Remove the matching dead implementation method.
- [x] Keep pointer mutation and selector-keyed audit behavior unchanged.

###### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: 2. Proof and Documentation

- [x] Update the surviving integration proof to stop depending on the removed singular selector helper.
- [x] Align the `09.1` family docs and slice index with the contracted public API.
- [x] Re-run focused, service, docs, and smoke proof.

##### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: Acceptance Shape

- `GameplayAdmissionPointerAuthorityService` no longer exposes a singular `worldSlug + realmSlug` read helper;
- the dead selector-shaped helper is gone, leaving one direct singular current-pointer read contract for live consumers at that bounded step;
- gameplay bootstrap/cutover proof still passes after the integration test stops depending on the removed singular selector helper.

##### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.DatabaseGameplayAdmissionPointerAuthorityServiceTest'`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
- `bash dev-tools/verify-fresh-bootstrap.sh`

##### source-09-1-39-task-list-admission-pointer-selector-read-api-removal-vertical-slice-1-70: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63

#### Tick State Routing Authority Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-63)

##### Preserved Source Text: source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63

<!-- migration-source path="design/project-management/vertical-slices/09.1.4-task-list-tick-state-routing-authority-follow-through-vertical-slice.md" lines="1-63" sha256="309d02c46913e6a04e664a9aee7ef13d6cf8ac0e9ed745a52f12f27cfbc2cf1d" heading-offset="3" -->
#### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: Tick State Routing Authority Follow-Through Vertical Slice

##### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: Goal and Status

Goal: remove the remaining session-versus-runtime identity shortcut from tick-state introspection so `QueryState(sessionId)` resolves tenant authority only from the live session shell instead of guessing by treating a transport `sessionId` like a runtime `gameInstanceId`. Status: complete at the current bounded boundary.

##### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: Why This Slice Exists

The earlier `09.1` follow-through batches already moved reconnect, logout, presence, communication, replay, and durable execution onto the admitted routing fence. One smaller but real introspection seam still drifted:

- `TickQueueControlService.queryState(sessionId)` first tried the stored session shell, but then fell back to `gameInstanceRepository.findById(sessionId)` to derive a tenant;
- that reused the same raw numeric identifier as if a transport `sessionId` and a runtime `gameInstanceId` were interchangeable authority keys;
- `QueryState` is an operator/read-model surface over session state, so that fallback could read or project the wrong Redis key instead of failing closed when the session shell is absent.

This slice closes that leftover identity shortcut instead of leaving tick-state reads as the last session-versus-runtime authority exception in the routing family.

##### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: Implementation Notes

- `TickQueueControlService.queryState(sessionId)` now requires a live session shell with a positive tenant id before reading Redis state.
- Missing or tenantless session shells now fail closed with `IllegalArgumentException` instead of guessing via `gameInstanceRepository.findById(sessionId)`.
- Focused unit proof now covers both:
  - missing session context; and
  - stored session rows with no tenant authority.

##### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: Scope

- session-scoped tick state introspection and the remaining sessionId/runtimeId authority shortcut in that read path;
- focused unit proof for the fail-closed behavior.

##### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: Out of Scope

- broader tick-batch ownership, replay, or region-fencing work tracked under the `02.18.8` family;
- gameplay command queue admission or durable execution paths already closed in earlier `09.1` follow-through batches.

##### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: Locked Direction

- session-scoped operator/read-model queries must derive tenant authority from the live session shell, not from unrelated runtime ids that happen to share the same numeric type;
- when the session shell is absent or lacks tenant authority, the read must fail closed instead of projecting a guessed Redis key;
- routing-authority cleanup applies to introspection surfaces too, not only player-facing command and reconnect flows.

##### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: Planned Work

###### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: 1. Fail-Closed Session-State Read

- [x] Remove the `sessionId -> gameInstanceId` tenant fallback from `TickQueueControlService.queryState(...)`.
- [x] Require a positive tenant-bearing session shell before reading state.

###### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: 2. Focused Proof

- [x] Cover the missing-session case in unit tests.
- [x] Cover the tenantless-session case in unit tests.

##### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: Acceptance Shape

- `QueryState(sessionId)` no longer guesses tenant authority from runtime ids;
- missing or malformed session shells fail closed instead of reading a guessed Redis state key;
- tick-state introspection now follows the same routing-authority discipline as the other closed `09.1` follow-through seams.

##### source-09-1-4-task-list-tick-state-routing-authority-follow-through-vertical-slice-1-63: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75

#### 09.1.40 Task List: World-Qualified Admission-Pointer Contract Repair Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-75)

##### Preserved Source Text: source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75

<!-- migration-source path="design/project-management/vertical-slices/09.1.40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice.md" lines="1-75" sha256="c0c97034d0c64c6da153c3b8a8770d7360e71a4a550b17f33e0d7e21e04210f2" heading-offset="3" -->
#### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: 09.1.40 Task List: World-Qualified Admission-Pointer Contract Repair Vertical Slice

##### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: Goal and Status

Goal: repair the singular admission-pointer and public-production membership contract so current pointer authority is keyed by `{tenantId, worldSlug, realmSlug}` instead of the under-specified `{tenantId, realmSlug}` shape, which can collide when one tenant legitimately has the same realm slug in multiple worlds. Status: complete at the current bounded boundary.

##### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: Why This Slice Exists

`09.1.39` removed the last dead selector-shaped public pointer helper and left tenant-scoped `{tenantId, realmSlug}` lookup as the surviving singular read contract. Full validation then exposed that the integration fixture legitimately carries both `demo/production` and `sandbox/production` under one tenant.

That made the narrower contract incorrect for live consumers:

- `GetAdmissionPointer` could no longer identify one current pointer uniquely from `{tenantId, realmSlug}` alone;
- Account bootstrap character reads and public-production membership creation still relied on that under-specified singular lookup;
- prepared cutover validation had also inherited the same narrower singular read shape.

##### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: Implementation Notes

- `GetAdmissionPointerRequest` now requires `tenantId`, `worldSlug`, and `realmSlug`.
- Game Session pointer authority and repository lookup now resolve current pointers by `{tenantId, worldSlug, realmSlug}`.
- Account bootstrap admission and public-production membership creation now pass `worldSlug` explicitly instead of resolving one realm by tenant and slug alone.
- Public-production membership replay keys now include `worldSlug`, so duplicate realm slugs across worlds do not collide under one request id.
- Focused Account proof now covers duplicate `realmSlug` values across worlds and verifies bootstrap character reads use the world-qualified pointer lookup.

##### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: Scope

- Game Session singular current-pointer read contract and direct authority lookup;
- Account bootstrap/public-production consumers of that lookup;
- public-production membership replay identity where world-qualified selectors must stay distinct;
- parent/index architecture and slice docs for the repaired canonical contract.

##### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: Out of Scope

- pointer audit and mutation surfaces that already carry visible selector identity explicitly;
- non-public realm grant policy beyond keeping admission-pointer reads world-qualified;
- broader runtime-target ambiguity follow-through outside the singular selector contract repair.

##### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: Locked Direction

- singular current admission-pointer reads must use enough selector identity to remain correct when one tenant exposes duplicate realm slugs across different worlds;
- bootstrap discovery, character reads, public-production membership checks, and `PLAY`-adjacent admission must all consume the same world-qualified current-pointer contract;
- once the canonical singular key changes, replay/idempotency storage must key on the same selector shape instead of silently collapsing distinct worlds together.

##### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: Planned Work

###### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: 1. Contract Repair

- [x] Change the singular current-pointer read contract to `{tenantId, worldSlug, realmSlug}`.
- [x] Move Game Session direct authority lookup and gRPC reads onto the repaired contract.
- [x] Move Account bootstrap and public-production membership consumers onto the repaired contract.

###### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: 2. Replay and Proof

- [x] Make public-production membership replay identity world-qualified.
- [x] Add focused proof for duplicate realm slugs across worlds under one tenant.
- [x] Re-run docs and fresh-bootstrap proof for the repaired contract.

##### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: Acceptance Shape

- `GetAdmissionPointer` no longer treats `{tenantId, realmSlug}` as enough to identify one current pointer;
- Account bootstrap and public-production membership creation no longer risk reading or replaying the wrong world when realm slugs duplicate under one tenant;
- singular current-pointer consumers, docs, and proof all converge on `{tenantId, worldSlug, realmSlug}`.

##### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: Validation

- `./gradlew spotlessApply :account-service:check -PfullCheck :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
- `docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml build --no-cache game-session-service account-service`
- `bash dev-tools/verify-fresh-bootstrap.sh`

##### source-09-1-40-task-list-world-qualified-admission-pointer-contract-repair-vertical-slice-1-75: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72

#### 09.1.41 Task List: Operator Audit Pointer-Key Convergence Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-72)

##### Preserved Source Text: source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72

<!-- migration-source path="design/project-management/vertical-slices/09.1.41-task-list-operator-audit-pointer-key-convergence-vertical-slice.md" lines="1-72" sha256="5affeaf76ac6b10d9bcc39a2b93eab33baea02dcb884401c6dc60c4b3620f1c0" heading-offset="3" -->
#### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: 09.1.41 Task List: Operator Audit Pointer-Key Convergence Vertical Slice

##### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: Goal and Status

Goal: keep operator-facing admission-pointer audit reads on the same canonical `{tenantId, worldSlug, realmSlug}` key as live admission authority instead of inferring tenant ownership from `worldSlug + realmSlug` after the control-plane response arrives. Status: complete at the current bounded boundary.

##### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: Why This Slice Exists

`09.1.40` repaired the singular live admission-pointer contract to the world-qualified `{tenantId, worldSlug, realmSlug}` shape, but the operator audit path was still lagging behind:

- Logging & Admin exposed `GET /admission-pointers/{worldSlug}/{realmSlug}/audit`;
- Game Session control-plane audit reads still tried to rediscover the tenant by scanning current pointers for matching selector rows;
- Logging & Admin only enforced tenant access after it received the control-plane response and inspected the first returned row.

That left one read-model/operator seam on an under-specified selector contract even though the canonical admission authority key was already decided.

##### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: Implementation Notes

- `ListAdmissionPointerAuditRequest` now carries `tenantId` alongside `worldSlug` and `realmSlug`.
- Game Session control-plane audit reads now query pointer audit rows directly by `{tenantId, worldSlug, realmSlug}` and reject missing or non-numeric `tenantId` up front instead of scanning current pointers to infer one.
- Logging & Admin now exposes `GET /admission-pointers/{tenantId}/{worldSlug}/{realmSlug}/audit`.
- Logging & Admin now requires tenant access before issuing the control-plane audit read and treats mismatched response tenants as an internal control-plane contract violation instead of silently trusting the payload.
- Controller/service proof now explicitly seeds `SessionContext` for tenant-guard paths so the touched web tests validate the controller guard itself rather than depending on JWT filter behavior that is disabled in the `@WebMvcTest` harness.

##### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: Scope

- the Game Session control-plane admission-pointer audit request contract;
- Logging & Admin REST/client/service consumers of that contract;
- focused docs describing the operator-facing audit route and the parent `09.1` slice state.

##### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: Out of Scope

- pointer mutation and prepared-cutover write flows, which already carried `tenantId` explicitly;
- broader operator read-model expansion outside the admission-pointer audit seam;
- fresh gameplay/bootstrap runtime smoke, since this bounded batch changes the control-plane audit key and proof, not runtime boot wiring.

##### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: Locked Direction

- operator-facing admission-pointer reads must use the same canonical key as live admission authority;
- Logging & Admin must prove tenant access before reading audit history, not after receiving one guessed tenant from the response;
- Game Session control-plane readers must fail closed on missing canonical selector identity instead of scanning selector rows to infer authority.

##### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: Planned Work

###### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: 1. Contract Convergence

- [x] Add `tenantId` to the control-plane admission-pointer audit request contract.
- [x] Remove Game Session tenant-inference scanning from audit reads and query canonical pointer audit rows directly.
- [x] Move Logging & Admin audit ingress, client, and service logic onto the canonical tenant-qualified key.

###### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: 2. Proof and Docs

- [x] Add focused proof for the repaired tenant-qualified audit contract and response-tenant guard.
- [x] Update operator/API and parent-slice docs to describe the canonical audit route.
- [x] Re-run the touched-service full checks plus Markdown/link validation.

##### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: Acceptance Shape

- operator audit reads no longer rely on `worldSlug + realmSlug` alone to discover tenant authority;
- Logging & Admin rejects unauthorized audit access before the control-plane call;
- Game Session and Logging & Admin both describe and implement the same canonical `{tenantId, worldSlug, realmSlug}` admission-pointer audit key.

##### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcServiceTest.*listAdmissionPointerAudit*' :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.service.impl.AdmissionPointerServiceImplTest' --tests 'net.firedevops.firemud.loggingadmin.controller.AdmissionPointerControllerTest'`
- `./gradlew :game-session-service:check -PfullCheck :logging-admin-service:check -PfullCheck linkCheck lintMarkdown`

##### source-09-1-41-task-list-operator-audit-pointer-key-convergence-vertical-slice-1-72: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68

#### 09.1.42 Task List: Admission-Pointer Legacy Upsert Fallback Removal Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-68)

##### Preserved Source Text: source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68

<!-- migration-source path="design/project-management/vertical-slices/09.1.42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice.md" lines="1-68" sha256="1061db737df522e44a135141ad9f8047be38f8c09cb7783755d105a952f669bb" heading-offset="3" -->
#### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: 09.1.42 Task List: Admission-Pointer Legacy Upsert Fallback Removal Vertical Slice

##### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: Goal and Status

Goal: keep admission-pointer mutation on the same canonical `{tenantId, worldSlug, realmSlug}` contract as live reads by removing the last legacy non-tenant selector fallback from the Game Session authority service. Status: complete at the current bounded boundary.

##### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: Why This Slice Exists

Later `09.1` work already repaired the singular live admission-pointer contract to the world-qualified `{tenantId, worldSlug, realmSlug}` key, but one mutation seam was still lagging behind:

- `DatabaseGameplayAdmissionPointerAuthorityService.upsertPointer(...)` still fell back to `findByWorldSlugAndRealmSlug(worldSlug, realmSlug)` when the canonical tenant-qualified row was absent;
- that fallback was legacy adoption scaffolding from the pre-repair pointer model, not part of the now-canonical routing contract;
- leaving it in place kept one write path on under-specified non-tenant selector authority even though the rest of `09.1` had already converged.

##### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: Implementation Notes

- `DatabaseGameplayAdmissionPointerAuthorityService.upsertPointer(...)` now resolves existing rows only through `findByTenantIdAndWorldSlugAndRealmSlug(...)` and otherwise creates a fresh pointer row directly.
- `GameplayAdmissionPointerRepository` no longer exposes the dead non-tenant `findByWorldSlugAndRealmSlug(...)` helper.
- Focused pointer-authority proof continues to cover tenant-qualified create and compare-and-set behavior without preserving selector-shaped adoption scaffolding.

##### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: Scope

- Game Session admission-pointer mutation authority;
- the now-dead repository helper that existed only for the legacy non-tenant upsert fallback;
- focused docs and proof for this one canonical-contract cleanup seam.

##### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: Out of Scope

- broader admission-pointer read, audit, or bootstrap consumers, which already use the repaired canonical key;
- data migration for pre-canonical pointer rows;
- operator workflow expansion beyond keeping mutation on the canonical authority contract.

##### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: Locked Direction

- live admission-pointer writes must use the same canonical selector contract as live admission reads;
- once the canonical pointer key is `{tenantId, worldSlug, realmSlug}`, mutation must not silently reintroduce non-tenant selector authority;
- development-mode contract repair should prefer direct canonical behavior over retaining migration-style fallback logic.

##### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: Planned Work

###### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: 1. Canonical Mutation Convergence

- [x] Remove legacy non-tenant selector fallback from admission-pointer upsert.
- [x] Remove the now-dead repository helper that only served that fallback.
- [x] Keep focused proof on tenant-qualified create/update behavior.

###### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: 2. Slice Inventory Follow-Through

- [x] Update parent/index docs to record that this last legacy mutation fallback is gone.
- [x] Re-run focused proof, touched-service full check, and Markdown/link validation.

##### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: Acceptance Shape

- admission-pointer mutation no longer scans `worldSlug + realmSlug` without tenant scope;
- Game Session pointer authority exposes no dead non-tenant selector helper for live mutation;
- `09.1` docs describe one consistent canonical pointer key across both read and write authority.

##### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'DatabaseGameplayAdmissionPointerAuthorityServiceTest'`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-42-task-list-admission-pointer-legacy-upsert-fallback-removal-vertical-slice-1-68: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73

#### 09.1.43 Task List: Bootstrap Command Queue Target Convergence Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-73)

##### Preserved Source Text: source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73

<!-- migration-source path="design/project-management/vertical-slices/09.1.43-task-list-bootstrap-command-queue-target-convergence-vertical-slice.md" lines="1-73" sha256="00ed8fd67634ad927fa7aa98870c33f1ee2f6da5b6db9c1ee36b283fcf3ff22a" heading-offset="3" -->
#### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: 09.1.43 Task List: Bootstrap Command Queue Target Convergence Vertical Slice

##### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: Goal and Status

Goal: keep command admission on canonical bootstrap or gameplay runtime authority instead of falling back from a cleared gameplay binding to the raw transport `sessionId`. Status: complete at the current bounded boundary.

##### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: Why This Slice Exists

Earlier `09.1` work already removed the oldest `gameInstanceRepository.findById(sessionId)` queue-target guess from `CommandServiceImpl`, but one narrower routing drift remained:

- when a normalized session still had tenant authority but no admitted gameplay runtime, `resolveQueueTarget(...)` still fell back to the raw numeric `sessionId`;
- that meant stale-normalized shells could still stage commands against a transport-shaped id even after `SessionAuthenticationService` had cleared the gameplay binding;
- it also meant valid bootstrap shells ignored their explicit `bootstrapGameInstanceId`, which could silently target the wrong runtime whenever `sessionId` and bootstrap runtime diverged.

That left command admission on two inconsistent queue-target rules even though the canonical session shell already carries the correct bootstrap runtime and current bootstrap routing bundle.

##### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: Implementation Notes

- `CommandServiceImpl` now resolves queue targets in this order:
  - admitted gameplay runtime when `gameInstanceId > 0`;
  - otherwise explicit `bootstrapGameInstanceId` only when the session still carries a complete bootstrap routing bundle `{worldSlug, realmSlug, pointerVersion}`;
  - otherwise fail closed with `NOT_FOUND`.
- Stale normalized shells that lost their bootstrap routing bundle now stop at command admission instead of reusing raw `sessionId` as a surrogate runtime target.
- Bootstrap-shell command staging now respects the explicit bootstrap runtime even when it differs from the transport `sessionId`.

##### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: Scope

- Game Session command-admission queue-target resolution;
- focused `CommandServiceImpl` proof for bootstrap-shell and stale-normalized-session behavior;
- parent/index slice docs for the `09.1` routing family.

##### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: Out of Scope

- text-command stage gating, which already blocks built-in gameplay commands before they reach `CommandServiceImpl`;
- trusted TCP proxy disconnect authority, which lives in its separate `09.1.26` slice;
- broader reconnect or runtime-cutover consumers beyond this one command-admission queue-target seam.

##### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: Locked Direction

- command admission must choose runtime targets from canonical session authority, not transport id coincidence;
- bootstrap shells must use the explicit bootstrap runtime they already carry, not re-infer from `sessionId`;
- once normalization clears a stale gameplay binding and its bootstrap routing bundle, command admission must fail closed instead of reusing stale transport-shaped identity.

##### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: Planned Work

###### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: 1. Queue-Target Convergence

- [x] Replace raw `sessionId` fallback with canonical bootstrap-runtime targeting.
- [x] Fail closed when neither gameplay runtime authority nor intact bootstrap routing authority exists.
- [x] Keep admitted gameplay sessions unchanged.

###### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: 2. Proof and Inventory Follow-Through

- [x] Update focused `CommandServiceImpl` proof for explicit bootstrap-runtime targeting and stale-shell rejection.
- [x] Update parent/index docs to record the converged queue-target rule.

##### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: Acceptance Shape

- bootstrap/login-era command staging uses explicit bootstrap runtime authority instead of raw transport id fallback;
- stale normalized shells without a complete bootstrap routing bundle no longer stage commands;
- gameplay-bound command staging remains unchanged.

##### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: Validation

- `./gradlew spotlessApply :game-session-service:test --tests 'unit.net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest'`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-43-task-list-bootstrap-command-queue-target-convergence-vertical-slice-1-73: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71

#### 09.1.44 Task List: TCP Proxy Disconnect Envelope Authority Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-71)

##### Preserved Source Text: source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71

<!-- migration-source path="design/project-management/vertical-slices/09.1.44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice.md" lines="1-71" sha256="032ae2217dd9cceedcf53bbe99755a9189dc02b96c85c4249a3ab46460504210" heading-offset="3" -->
#### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: 09.1.44 Task List: TCP Proxy Disconnect Envelope Authority Follow-Through Vertical Slice

##### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: Goal and Status

Goal: keep TCP proxy disconnect notifications on explicit runtime metadata all the way through the proxy-side envelope instead of duplicating runtime id into advisory `sessionId` or falling back from missing `gameInstanceId` before the request leaves TCP Proxy. Status: complete at the current bounded boundary.

##### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: Why This Slice Exists

`09.1.26` already hardened the Game Session disconnect consumer to fail closed when explicit runtime metadata is absent, but one proxy-side follow-through seam remained:

- `TcpProxyEventClient.notifyDisconnect(...)` still copied the same runtime-scoped value into both `sessionId` and `gameInstanceId`;
- `TcpProxyGrpcService.notifyDisconnect(...)` still treated advisory `sessionId` as a fallback source for `gameInstanceId`;
- that kept the proxy-side envelope on a weaker mixed-identity rule even though the consumer-side authority fence had already converged.

This was the last disconnect-side place where advisory session identity could still be mistaken for runtime authority instead of remaining optional and non-authoritative.

##### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: Implementation Notes

- `TcpProxyEventClient` now sends explicit `gameInstanceId` only and leaves advisory `sessionId` unset when no separate session identifier exists.
- `TcpProxyGrpcService` now forwards only `request.getGameInstanceId()` to the event service and does not fall back from missing `gameInstanceId` to `sessionId`.
- Focused proxy-side proof now asserts both halves of that contract:
  - outbound client requests no longer duplicate runtime id into `sessionId`;
  - inbound gRPC handling no longer promotes advisory `sessionId` to runtime authority.

##### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: Scope

- TCP Proxy disconnect notification envelope handling on the proxy side;
- focused unit proof for the proxy event client and gRPC service;
- parent/index docs for the `09.1` routing family.

##### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: Out of Scope

- Game Session disconnect processing, which was already hardened by `09.1.26`;
- Telnet bootstrap discovery or gateway header propagation outside the disconnect envelope;
- later reconnect policy once Game Session has already received a disconnect hint.

##### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: Locked Direction

- advisory `sessionId` must stay advisory and must not be repurposed as runtime authority;
- explicit `gameInstanceId` is the only runtime-suspend key that should cross the disconnect envelope;
- if explicit runtime metadata is absent, the proxy-side envelope should preserve that absence instead of inventing it from another field.

##### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: Planned Work

###### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: 1. Proxy Envelope Convergence

- [x] Stop duplicating runtime id into advisory `sessionId` on the outbound proxy client.
- [x] Remove the proxy gRPC fallback from missing `gameInstanceId` to `sessionId`.

###### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: 2. Proof and Inventory Follow-Through

- [x] Update focused proxy-side proof for the converged disconnect envelope.
- [x] Update parent/index docs to record that proxy-side disconnect authority now matches the consumer-side fence.

##### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: Acceptance Shape

- TCP Proxy no longer writes runtime-target authority into both `sessionId` and `gameInstanceId`;
- TCP Proxy gRPC handling no longer promotes advisory `sessionId` into runtime authority when `gameInstanceId` is absent;
- disconnect handling stays explicitly missing-runtime-aware end to end instead of reintroducing a local fallback before Game Session sees the request.

##### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: Validation

- `./gradlew spotlessApply :tcp-proxy-service:test --tests 'net.firedevops.firemud.tcpproxy.service.TcpProxyEventClientTest' --tests 'net.firedevops.firemud.tcpproxy.service.impl.TcpProxyGrpcServiceTest'`
- `./gradlew :tcp-proxy-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-44-task-list-tcp-proxy-disconnect-envelope-authority-follow-through-vertical-slice-1-71: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72

#### 09.1.45 Task List: Fail-Closed Remote Control-Plane Partial Routing Filters Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-72)

##### Preserved Source Text: source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72

<!-- migration-source path="design/project-management/vertical-slices/09.1.45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice.md" lines="1-72" sha256="f20334701c92d51cb28a96a336a81676758d1d58675f559aa131308eacda5272" heading-offset="3" -->
#### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: 09.1.45 Task List: Fail-Closed Remote Control-Plane Partial Routing Filters Vertical Slice

##### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: Goal and Status

Goal: ensure remote control-plane scheduling and list filters reject partial `{worldSlug, realmSlug, pointerVersion}` input instead of silently widening the request back to an unscoped query or schedule. Status: complete at the current bounded boundary.

##### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: Why This Slice Exists

`09.1` already converged durable remote-followup rows and read projections on all-or-none routing-bundle persistence. One operator/control-plane ingress seam still drifted:

- `ScheduleRemoteFollowup` normalized partial request routing input to absent before handing off to the runtime scheduler;
- remote coordinator, followup, and result list filters did the same for partial query selectors;
- that meant malformed caller input silently widened into “no routing bundle” instead of failing closed at the entry seam.

That was no longer acceptable once the admitted routing bundle was already canonical elsewhere. Control-plane callers should not be able to broaden remote scheduling or inspection by omitting one field from a three-field routing selector.

##### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: Implementation Notes

- `GameSessionRemoteControlPlaneService` now requires remote control-plane request routing input to be either fully absent or fully present.
- `ScheduleRemoteFollowup` rejects partial routing bundle input with `INVALID_ARGUMENT` instead of delegating a widened no-routing schedule request.
- remote coordinator, followup, and result list filters now reject partial routing selectors with `INVALID_ARGUMENT` instead of querying as though no routing filter was provided.
- internal readback and stale-routing projection still collapse partial persisted/runtime authority to absent, because that remains a projection/read-model safety rule rather than an ingress-acceptance rule.

##### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: Scope

- Game Session remote control-plane scheduling ingress;
- Game Session remote coordinator/followup/result list-filter ingress;
- focused gRPC proof for invalid partial routing input plus Game Session validation and doc hygiene.

##### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: Out of Scope

- remote followup durable-row persistence semantics, which were already converged earlier in `09.1`;
- current-runtime stale-routing projection behavior for stored rows;
- broader Automation producer-side routing metadata construction.

##### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: Locked Direction

- control-plane callers must provide `worldSlug`, `realmSlug`, and `pointerVersion` together when they provide any of them;
- partial routing input at operator/read/schedule ingress is invalid input, not an invitation to broaden the request;
- fail-closed ingress validation should sit on the owning Game Session control-plane seam rather than on downstream repositories or scheduler code.

##### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: Planned Work

###### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: 1. Remote Control-Plane Ingress Fence

- [x] Reject partial routing bundle input for `ScheduleRemoteFollowup`.
- [x] Reject partial routing filter input for remote coordinator, followup, and result list queries.

###### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: 2. Proof and Documentation

- [x] Update focused `GameSessionControlPlaneGrpcServiceTest` coverage for invalid partial remote routing input.
- [x] Re-run Game Session validation on the converged behavior.
- [x] Align parent/index docs with the fail-closed ingress fence.

##### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: Acceptance Shape

- partial routing input no longer widens remote scheduling requests into unscoped scheduling;
- partial routing selectors no longer widen remote control-plane list queries into unfiltered reads;
- complete routing bundles still behave unchanged for scheduling and filtering;
- focused, broad, and doc-hygiene proof stay green after the hardening.

##### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcServiceTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-45-task-list-fail-closed-remote-control-plane-partial-routing-filters-vertical-slice-1-72: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89

#### 09.1.46 Task List: Operator Runtime-State Read Convergence Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-89)

##### Preserved Source Text: source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89

<!-- migration-source path="design/project-management/vertical-slices/09.1.46-task-list-operator-runtime-state-read-convergence-vertical-slice.md" lines="1-89" sha256="022e2114c91004d271803a30af5ef403f410d588ece0b8efa3a21dd6e6bd6cc9" heading-offset="3" -->
#### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: 09.1.46 Task List: Operator Runtime-State Read Convergence Vertical Slice

##### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: Goal and Status

Goal: expose one operator-facing runtime-state read in Logging & Admin that consumes the canonical Game Session `GetGameInstanceRuntimeState` contract directly, so current runtime routing, version/pin metadata, and current admission pointers are readable without inventing a second operator-local projection. Status: complete at the current bounded boundary.

##### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: Why This Slice Exists

`09.1` already converged live admission and control-plane runtime-state truth onto the canonical Game Session read surface, including explicit `currentAdmissionPointers[]` and fail-closed singular routing projection. One bounded operator gap remained:

- Logging & Admin exposed admission-pointer list, audit, prepared upgrade, and cutover routes, but not the matching current runtime-state read;
- operators still needed to drop to gRPC or infer current runtime state indirectly when validating cutover/readback behavior;
- without a bounded Logging & Admin consumer, the canonical runtime-state read stayed one transport seam short of the operator surface that already owned the adjacent admission-pointer workflows.

This slice closes that one operator-read gap without widening into broader admin redesign or new runtime-state synthesis.

##### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: Scope

- Logging & Admin client, service, controller, and DTO support for `GetGameInstanceRuntimeState`;
- tenant-guarded operator REST ingress for current runtime state by `{tenantId, gameInstanceId}`;
- focused proof for controller/service behavior against the canonical control-plane contract.

##### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: Out of Scope

- changes to Game Session runtime-state projection rules, which are already live and covered by focused gRPC proof;
- new region-scoped or search-style operator read APIs beyond the bounded `{tenantId, gameInstanceId}` route;
- broader operator UX or dashboard work.

##### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: Locked Direction

- Logging & Admin should read current runtime state from the canonical Game Session control-plane contract, not rebuild a second projection;
- operator runtime-state ingress must require tenant access before issuing the control-plane read;
- response tenant mismatch is a control-plane contract failure and must fail closed instead of being silently trusted.

##### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: Planned Work

###### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: 1. Operator Read Surface

- [x] Add a Logging & Admin control-plane client method for `GetGameInstanceRuntimeState`.
- [x] Add a tenant-qualified Logging & Admin service/controller route for runtime-state readback.
- [x] Map canonical Game Session runtime-state fields, including current admission pointers and publication metadata, onto a bounded operator DTO.

###### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: 2. Proof and Docs

- [x] Add focused Logging & Admin proof for successful runtime-state readback and tenant-guard failure paths.
- [x] Guard against mismatched response `tenantId` in the service layer.
- [x] Update the `09.1` parent/index/progress docs so operator runtime-state readback is tracked as a landed surface instead of hidden inside generic remaining work.

##### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: Acceptance Shape

- Logging & Admin exposes `GET /admission-pointers/runtime-state/{tenantId}/{gameInstanceId}`;
- the route returns the canonical Game Session runtime-state fields, including `currentAdmissionPointers[]`, without inventing a second operator-local routing model;
- unauthorized callers are rejected before the control-plane read;
- mismatched response-tenant payloads fail closed as internal contract violations.

##### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: Completion Notes

- `GameSessionControlPlaneClient` now exposes `getGameInstanceRuntimeState(long tenantId, long gameInstanceId)` for Logging & Admin.
- `AdmissionPointerController` now serves `GET /admission-pointers/runtime-state/{tenantId}/{gameInstanceId}` and enforces tenant access before delegating.
- `AdmissionPointerServiceImpl` now maps canonical Game Session runtime-state metadata, publication details, and current admission pointers onto `GameInstanceRuntimeStateDto`, returns `404` when the control-plane returns no runtime row, and rejects mismatched response tenants as an internal contract failure.
- Logging & Admin `openapi.yaml` now documents the runtime-state route and DTO shape so the published operator contract matches the landed endpoint.

##### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: Completion Evidence

- Logging & Admin implementation:
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/client/GameSessionControlPlaneClient.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/AdmissionPointerController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/AdmissionPointerService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/AdmissionPointerServiceImpl.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/GameInstanceRuntimeStateDto.java`
  - `services/logging-admin-service/src/main/resources/openapi.yaml`
- Focused Logging & Admin proof:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/AdmissionPointerControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/AdmissionPointerServiceImplTest.java`
- Existing Game Session runtime-state contract proof reused by this operator surface:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`

##### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: Validation

- `./gradlew :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.controller.AdmissionPointerControllerTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.AdmissionPointerServiceImplTest'`
- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-09-1-46-task-list-operator-runtime-state-read-convergence-vertical-slice-1-89: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76

#### Login Bootstrap Routing Authority Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-76)

##### Preserved Source Text: source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76

<!-- migration-source path="design/project-management/vertical-slices/09.1.5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice.md" lines="1-76" sha256="76badb7b5a4dd83c1dc11ac39049d3ba771f2afa6bdfa0510cc5a1358abae0d6" heading-offset="3" -->
#### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: Login Bootstrap Routing Authority Follow-Through Vertical Slice

##### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: Goal and Status

Goal: remove the remaining credential-login shortcut that treated a transport `sessionId` as if it were also a valid bootstrap `gameInstanceId`, so `LOGIN` only re-enters through session-backed bootstrap authority and fails closed when that pre-login shell is missing or no longer carries a runtime target. Status: complete at the current bounded boundary.

##### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: Why This Slice Exists

Earlier `09.1` follow-through batches already fenced reconnect, `PLAY`, logout, presence, communication, durable replay, and tick-state reads onto canonical session or pointer authority. One smaller login-era shortcut still remained:

- credential `LOGIN` resolved `bootstrapGameInstanceId` from the normalized session shell when present;
- but when that resolution returned nothing, it silently fell back to `sessionId`;
- that meant a missing bootstrap shell could still reopen gameplay admission if a real runtime happened to share the same numeric id as the transport session.

This slice closes that last credential-login cross-domain identity shortcut instead of leaving “no shell, but maybe the numbers match” as a hidden way around the routing fence.

##### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: Implementation Notes

- `LoginCommandHandler.resolveBootstrapGameInstanceId(...)` now returns absent authority as `0`, not `sessionId`.
- credential `LOGIN` now fails closed with `SESSION_NOT_FOUND` when no bootstrap runtime target survives current session-shell normalization.
- the in-memory login/resumption/interpreter fixtures and the generic WebSocket integration harness now all model a canonical bootstrap shell explicitly instead of depending on the retired `sessionId -> gameInstanceId` shortcut.
- focused proof now covers both:
  - the normal “use the bootstrapped game instance, not the transport id” path; and
  - the fail-closed case where a raw transport id collides numerically with a real runtime id.
- `LoginCommandHandlerTest` now defaults its happy-path fixture to a canonical bootstrap session shell instead of teaching the old fallback shortcut implicitly.

##### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: Scope

- credential `LOGIN` bootstrap target resolution and the remaining `sessionId -> gameInstanceId` fallback in that path;
- supporting in-memory and WebSocket integration fixtures that still assumed generic transport ids were also valid bootstrap runtime ids.

##### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: Out of Scope

- first-party verified `LOGIN` pointer freshness checks, already covered in earlier `09.1` work;
- broader websocket/bootstrap-shell refresh paths outside this one credential-login boundary.

##### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: Locked Direction

- credential login must derive its bootstrap runtime target from current session-shell authority, not from coincidental numeric overlap with runtime ids;
- when the pre-login shell is absent or no longer carries bootstrap routing authority, `LOGIN` must fail closed instead of guessing a runtime;
- unit fixtures for pre-login flows should model the canonical bootstrap shell explicitly rather than relying on hidden transport-id fallbacks.

##### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: Planned Work

###### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: 1. Fail-Closed Bootstrap Resolution

- [x] Remove the `sessionId` fallback from credential-login bootstrap target resolution.
- [x] Return `SESSION_NOT_FOUND` when no bootstrap target survives current session authority.

###### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: 2. Focused Proof

- [x] Keep the positive proof that `LOGIN` uses the session-backed bootstrap runtime target.
- [x] Add explicit proof that a transport-id/runtime-id collision does not reopen login without bootstrap authority.
- [x] Move the default happy-path test fixture onto a canonical bootstrap shell.
- [x] Realign the generic WebSocket/login integration harness with the canonical transport-session plus bootstrap-runtime header contract.

##### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: Acceptance Shape

- credential `LOGIN` no longer guesses bootstrap runtime authority from transport ids;
- missing or tenant-only pre-login shells fail closed at `SESSION_NOT_FOUND`;
- login proof now teaches explicit bootstrap-shell authority instead of the retired fallback shortcut.

##### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.command.text.LoginCommandHandlerTest'`
- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.command.text.SessionResumptionFlowTest' --tests 'net.firedevops.firemud.gamesession.command.text.TextCommandInterpreterTest' --tests 'net.firedevops.firemud.gamesession.command.text.LoginCommandHandlerTest' --tests 'net.firedevops.firemud.gamesession.GameSessionLoginIntegrationTest'`
- `./gradlew --no-configuration-cache :game-session-service:integrationTest --tests 'net.firedevops.firemud.gamesession.websocket.GameSessionWebSocketHandlerIntegrationTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`
- `bash services/game-session-service/websocket-login-look-smoke.sh`
- `bash services/tcp-proxy-service/telnet-login-look-smoke.sh`

##### source-09-1-5-task-list-login-bootstrap-routing-authority-follow-through-vertical-slice-1-76: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86

#### Runtime-State Routing Projection Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-86)

##### Preserved Source Text: source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86

<!-- migration-source path="design/project-management/vertical-slices/09.1.6-task-list-runtime-state-routing-projection-follow-through-vertical-slice.md" lines="1-86" sha256="a003d7f48952c9245b883491e95863d77912eef7ca64039add6d96974d48eeb6" heading-offset="3" -->
#### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: Runtime-State Routing Projection Follow-Through Vertical Slice

##### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: Goal and Status

Goal: remove the remaining reverse-projection shortcut from `GetGameInstanceRuntimeState` so current runtime-state reads stop pretending one runtime always has one canonical current routing bundle when multiple admission pointers can legitimately resolve to the same runtime target. Status: complete at the current bounded boundary.

##### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: Why This Slice Exists

Earlier `09.1` follow-through batches already established the canonical admitted routing bundle and removed stale routing shortcuts from login, logout, presence, communication, tick-state, and gameplay execution paths. One control-plane reverse-projection seam still drifted:

- Game Session runtime-state reads still collapsed pointer authority back to one singular `{playableStateScope, worldSlug, realmSlug, pointerVersion}` bundle by querying one sorted pointer row for a runtime target;
- that meant operator and Automation readers could silently inherit one arbitrary world/realm identity even when the current runtime was intentionally shared by multiple visible admission pointers;
- several Automation current-runtime consumers then treated those singular runtime-state fields as if they were always unambiguous routing truth.

This slice closes that reverse-projection shortcut instead of leaving current runtime-state reads as the last place where pointer multiplicity could be silently hidden behind sorted row order.

##### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: Implementation Notes

- `GetGameInstanceRuntimeState` now exposes `currentAdmissionPointers[]` as the explicit current pointer-authority view for one runtime target.
- The legacy singular runtime-state routing bundle (`playableStateScope`, `worldSlug`, `realmSlug`, `pointerVersion`) is still populated when exactly one current admission pointer exists.
- When current pointer authority does not prove one complete singular routing bundle, the singular runtime-state routing bundle now fails closed to empty/unspecified values instead of choosing one sorted or partial pointer row arbitrarily.
- `GameplayAdmissionPointerAuthorityService` now supports `listByRuntimeTarget(...)` so reverse projection reads can observe multiplicity directly instead of only asking for one preferred pointer.
- The immediate Automation runtime-state consumers now read routing via a shared helper that prefers the explicit `currentAdmissionPointers[]` surface and treats multi-pointer runtime state as “no singular routing bundle.”
- Game Session command-status and remote control-plane reads now use the same reverse-projection contract and likewise clear their current-runtime singular routing bundle when current authority is ambiguous or incomplete, while marking the persisted routing bundle stale instead of silently treating unknown current routing as clean.
- Focused proof now covers both:
  - the one-pointer runtime-state projection path; and
  - the fail-closed multi-pointer path where singular runtime-state routing fields are intentionally blank while the explicit pointer list remains available.

##### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: Scope

- Game Session runtime-state reverse projection from one runtime target back to current pointer authority;
- immediate Automation runtime-state consumers that currently interpret current routing scope from `GetGameInstanceRuntimeState`;
- Game Session operator/control-plane reads that still expose a current-runtime routing bundle derived from runtime target scope;
- docs/proto language that still implied runtime-state always projects one canonical current routing bundle.

##### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: Out of Scope

- broader region-first runtime ownership work under `02.18.9`;
- later routing-sensitive consumers that do not read `GetGameInstanceRuntimeState` today;
- public/bootstrap admission reads that already consume direct admission-pointer truth rather than runtime-state reverse projection.

##### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: Locked Direction

- admission-pointer authority is the source of truth for visible world/realm routing identity;
- reverse projection from a runtime target back to caller-visible routing identity must expose multiplicity honestly instead of choosing one sorted pointer as if it were canonical;
- singular runtime-state routing fields are only valid when current pointer authority is unambiguous;
- operator stale signaling must fail closed when current authority cannot prove one complete singular routing bundle;
- downstream consumers must fail closed when runtime-state cannot prove one singular routing bundle.

##### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: Planned Work

###### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: 1. Runtime-State Reverse Projection

- [x] Add an explicit current-admission-pointer list to `GetGameInstanceRuntimeState`.
- [x] Stop selecting one implicit current pointer row when multiple rows target the same runtime.
- [x] Keep the legacy singular routing fields only for the unambiguous one-pointer case.

###### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: 2. Immediate Consumer Follow-Through

- [x] Move in-scope Automation runtime-state readers onto a helper that prefers the explicit pointer list.
- [x] Make multi-pointer runtime-state reads fail closed for singular routing-bundle reconstruction.

###### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: 3. Proof and Documentation

- [x] Add focused Game Session proof for single-pointer and multi-pointer runtime-state projection.
- [x] Align slice/proto/service-contract docs with the explicit multi-pointer runtime-state contract.

##### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: Acceptance Shape

- `GetGameInstanceRuntimeState` no longer hides multi-pointer runtime routing behind sorted row order;
- callers can read the explicit current pointer list for a runtime target;
- singular runtime-state routing fields are only populated when the current routing identity is unambiguous;
- immediate Automation current-runtime readers fail closed instead of inheriting one arbitrary world/realm identity;
- command-status and remote control-plane reads no longer project one arbitrary “current” realm identity from ambiguous current authority, and they now mark persisted routing stale whenever the current singular routing bundle cannot be proven.

##### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcServiceTest'`
- `./gradlew :automation-scripting-service:test`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck :automation-scripting-service:check -PfullCheck linkCheck lintMarkdown check`

##### source-09-1-6-task-list-runtime-state-routing-projection-follow-through-vertical-slice-1-86: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76

#### Command-Staging Runtime Authority Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-76)

##### Preserved Source Text: source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76

<!-- migration-source path="design/project-management/vertical-slices/09.1.7-task-list-command-staging-runtime-authority-follow-through-vertical-slice.md" lines="1-76" sha256="7232da8e7f7f138b2ed76a945bd75cd2676905c16cadedf1e5bcea991545bf74" heading-offset="3" -->
#### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: Command-Staging Runtime Authority Follow-Through Vertical Slice

##### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: Goal and Status

Goal: remove the remaining pre-`PLAY` command-staging shortcut that reverse-mapped bootstrap or runtime-target ids back to routing identity by asking for one preferred pointer row, so staged gameplay-command routing metadata now reuses runtime-target authority only when it proves one complete current pointer bundle and otherwise fails closed. Status: complete at the current bounded boundary.

##### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: Why This Slice Exists

Earlier `09.1` follow-through batches already fenced live login, `PLAY`, logout, presence, communication, durable replay, and runtime-state reads onto canonical routing or pointer authority. One narrower staging seam still drifted:

- `CommandServiceImpl` already repaired partial routing metadata before persisting queued gameplay-command rows;
- but when session context lacked an explicit `{worldSlug, realmSlug, pointerVersion}` bundle, it still reverse-mapped runtime-target scope through `findByRuntimeTarget(...)`;
- after `09.1.6`, that meant staging could still project one arbitrary routing bundle even when the same runtime currently had multiple admission pointers.

This slice closes that last pre-`PLAY` staging shortcut instead of leaving queued command rows as a quiet place where ambiguous current pointer authority could still collapse back to one synthetic routing identity.

##### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: Implementation Notes

- `CommandServiceImpl.resolveRoutingPointer(...)` now reuses explicit `worldSlug + realmSlug` authority when present and only falls back to runtime-target authority through a new singular-proof helper.
- The new runtime-target helper reads `GameplayAdmissionPointerAuthorityService.listByRuntimeTarget(...)`, filters to complete `{worldSlug, realmSlug, pointerVersion}` bundles, and only returns a pointer when exactly one complete current candidate exists.
- When bootstrap or runtime-target authority is ambiguous, staged `GameplayCommand` rows now keep `playableStateScope` only when already present on the session shell and otherwise collapse routing metadata to the canonical absent bundle instead of choosing one pointer row arbitrarily.
- Positive proof now covers the bounded case where bootstrap runtime authority is still singular and staging repairs the command row from it.
- Negative proof now covers the ambiguous-runtime case where bootstrap runtime authority maps to multiple current pointers and staged command rows intentionally persist no routing bundle.

##### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: Scope

- pre-`PLAY` gameplay-command staging in `CommandServiceImpl`;
- runtime-target-to-routing reverse projection used only to repair or preserve staged command metadata before the command enters the durable queue;
- focused proof for singular versus ambiguous bootstrap runtime authority in login-era command staging.

##### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: Out of Scope

- live `LOGIN` bootstrap-runtime resolution, already closed in `09.1.5`;
- runtime-state reverse projection, already closed in `09.1.6`;
- later gameplay-command execution and remote-followup routing consumers, which already use the admitted routing bundle or the durable row contract.

##### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: Locked Direction

- queued gameplay-command rows must not synthesize one canonical routing bundle from an ambiguous runtime target;
- runtime-target reverse projection is only acceptable when current pointer authority proves one complete routing bundle;
- if session context and current pointer authority together cannot prove one full `{worldSlug, realmSlug, pointerVersion}` bundle, staging must persist no routing bundle rather than guessing.

##### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: Planned Work

###### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: 1. Singular Runtime-Target Proof

- [x] Replace runtime-target `findByRuntimeTarget(...)` staging repair with explicit singular current-pointer proof.
- [x] Ignore incomplete current pointer rows when deciding whether one canonical routing bundle exists.
- [x] Preserve the positive repair path for one singular bootstrap runtime target.

###### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: 2. Ambiguous Runtime Fail-Closed

- [x] Drop staged routing metadata when bootstrap or runtime-target authority maps to multiple complete current pointers.
- [x] Keep staged `playableStateScope` separate from routing-bundle persistence so ambiguous runtime identity does not silently mint a fake bundle.

###### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: 3. Proof and Documentation

- [x] Add focused Game Session proof for singular and ambiguous bootstrap-runtime staging.
- [x] Align the parent `09.1` slice and queue/index docs with this bounded fail-closed staging contract.

##### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: Acceptance Shape

- pre-`PLAY` gameplay-command staging no longer chooses one routing bundle from an ambiguous runtime target;
- bootstrap-runtime repair still works when exactly one complete current pointer bundle exists;
- ambiguous current runtime authority now yields a staged command row with no routing bundle rather than a guessed world/realm identity.

##### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest.loginCommandPersistsBootstrapRoutingMetadataFromUnambiguousRuntimeAuthority' --tests 'net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest.loginCommandDropsBootstrapRoutingMetadataWhenRuntimeAuthorityIsAmbiguous' --tests 'net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest.loginCommandPersistsBootstrapRoutingMetadataFromAuthority' --tests 'net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest.gameplayCommandRepairsPartialSessionRoutingMetadataFromAuthority' --tests 'net.firedevops.firemud.gamesession.service.impl.CommandServiceImplTest.gameplayCommandDropsPartialSessionRoutingMetadataWhenAuthorityCannotRepairIt'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`

##### source-09-1-7-task-list-command-staging-runtime-authority-follow-through-vertical-slice-1-76: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68

#### Logout Runtime-Stop Authority Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-68)

##### Preserved Source Text: source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68

<!-- migration-source path="design/project-management/vertical-slices/09.1.8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice.md" lines="1-68" sha256="7bcfe130f00912d8a11db79c67eebfd90c19d1cdcf0171960c5300a19deaf06c" heading-offset="3" -->
#### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: Logout Runtime-Stop Authority Follow-Through Vertical Slice

##### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: Goal and Status

Goal: remove the remaining logout lifecycle shortcut that still reverse-mapped a runtime target to one preferred admission pointer row when deciding whether a runtime was isolated enough to stop, so logout now stops runtimes only when current pointer authority proves one complete isolated pointer bundle. Status: complete at the current bounded boundary.

##### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: Why This Slice Exists

Earlier `09.1` follow-through batches already fenced login, `PLAY`, command staging, runtime-state reads, and control-plane stale signaling onto singular current-pointer proof. One smaller lifecycle seam still drifted:

- `LogoutCommandHandler` already avoided stopping runtimes when session selectors proved a shared realm;
- but when selectors were absent, it still asked `findByRuntimeTarget(...)` for one preferred pointer row;
- that meant logout could still treat a runtime as isolated from one arbitrary row even after multi-pointer runtime authority had become explicit elsewhere.

This slice closes that last logout-time reverse-projection shortcut instead of leaving runtime shutdown policy as a place where ambiguous runtime authority could still silently collapse to one chosen pointer.

##### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: Implementation Notes

- `LogoutCommandHandler` now derives runtime-stop authority from `GameplayAdmissionPointerAuthorityService.listByRuntimeTarget(...)`, not `findByRuntimeTarget(...)`.
- Logout only treats runtime-target authority as isolated when exactly one complete current pointer bundle exists and that pointer reports `stateScope=ISOLATED`.
- Missing, partial, or multi-pointer current authority now fails closed to “do not stop the runtime.”
- Focused proof now covers the new ambiguous-runtime case in addition to the existing shared-selector and unknown-authority cases.

##### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: Scope

- logout lifecycle policy for deciding whether to stop the current runtime after a deliberate player logout;
- runtime-target reverse projection used only for that isolated-versus-shared lifecycle decision.

##### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: Out of Scope

- gameplay-presence cleanup, already covered by earlier stale-shell and logout routing-fence work;
- broader runtime ownership and region fencing under `02.18.9`.

##### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: Locked Direction

- lifecycle policies must not infer isolated runtime ownership from one preferred pointer row when current authority is ambiguous;
- runtime shutdown decisions should stop only on positively confirmed isolated authority, not on missing or incomplete routing evidence;
- the same singular-pointer proof rule used by routing reads and command staging should apply to logout-time runtime-stop authority.

##### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: Planned Work

###### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: 1. Singular Runtime Authority

- [x] Replace logout-time runtime-target `findByRuntimeTarget(...)` lookups with explicit singular current-pointer proof.
- [x] Ignore incomplete runtime-target pointer rows when deciding whether one canonical isolated authority exists.

###### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: 2. Focused Proof

- [x] Keep the bounded proof that explicit shared selectors prevent runtime stop.
- [x] Keep the bounded proof that missing current authority does not stop the runtime.
- [x] Add explicit proof that ambiguous current runtime authority does not stop the runtime.

##### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: Acceptance Shape

- logout no longer stops a runtime based on one preferred runtime-target pointer row;
- only one complete isolated current pointer bundle can authorize runtime stop;
- missing, partial, or multi-pointer runtime authority leaves the runtime running.

##### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.command.text.LogoutCommandHandlerTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`

##### source-09-1-8-task-list-logout-runtime-stop-authority-follow-through-vertical-slice-1-68: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78

#### WebSocket Bootstrap Runtime Authority Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-78)

##### Preserved Source Text: source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78

<!-- migration-source path="design/project-management/vertical-slices/09.1.9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice.md" lines="1-78" sha256="b3906bbcdc78dba6d4e3e0002c2bc9c7990a0b83a153503b0c9ded1e0f70a52a" heading-offset="3" -->
#### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: WebSocket Bootstrap Runtime Authority Follow-Through Vertical Slice

##### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: Goal and Status

Goal: remove the remaining generic websocket-bootstrap shortcut that still persisted runtime-target-only bootstrap shells when routing bundle headers were absent, so websocket bootstrap now repairs its admitted `{worldSlug, realmSlug, pointerVersion}` bundle from singular current runtime authority and fails closed to no bundle when that authority is ambiguous. Status: complete at the current bounded boundary.

##### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: Why This Slice Exists

Earlier `09.1` follow-through batches already fenced login, `PLAY`, logout, command staging, and runtime-state reads onto singular current-pointer proof. One transport-edge seam still drifted:

- generic websocket bootstrap already persisted `tenantId + bootstrapGameInstanceId` on first attach and reconnect;
- but when trusted-proxy or generic websocket headers omitted the routing bundle, the bootstrap shell stayed runtime-target-only even if one current pointer bundle was already knowable for that runtime;
- reused websocket sessions therefore treated “same bootstrap runtime id” as enough to preserve an older bootstrap route until later login or `PLAY` normalization repaired it.

This slice closes that edge shortcut instead of leaving websocket bootstrap as a place where singular current pointer authority was available but not applied.

##### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: Implementation Notes

- `GameSessionWebSocketHandler` now injects `GameplayAdmissionPointerAuthorityService` and repairs generic bootstrap shells through singular current runtime-target authority.
- Generic websocket bootstrap now:
  - preserves a complete incoming routing bundle when it exists;
  - repairs the bundle from `listByRuntimeTarget(...)` when exactly one complete current pointer bundle exists; and
  - collapses to no routing bundle when current authority is missing, partial, or multi-pointer.
- Reused websocket transport sessions now benefit from that repaired incoming bootstrap shell too: when singular runtime authority changes pointer freshness, `sameBootstrapRoute(...)` sees the change and the handler clears stale authenticated/gameplay binding instead of preserving an older bootstrap route behind a matching runtime id alone.
- Focused proof now covers:
  - singular runtime-authority repair on first generic bootstrap;
  - ambiguous runtime-authority collapse back to no routing bundle; and
  - reused-session bootstrap refresh that clears old binding when repaired pointer authority changes.

##### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: Scope

- generic websocket bootstrap-shell persistence and refresh in `GameSessionWebSocketHandler`;
- singular runtime-target repair of bootstrap routing metadata for transport sessions that do not carry a first-party connect context.

##### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: Out of Scope

- first-party connect-context parsing and validation;
- later login or `PLAY` routing fences, which remain the next safety line even after websocket bootstrap repair.

##### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: Locked Direction

- websocket bootstrap should preserve the full routing bundle when it is already known;
- runtime-target-only bootstrap shells may be repaired from current pointer authority only when one complete current pointer bundle exists;
- reused websocket bootstrap sessions must not preserve an older bootstrap route just because the runtime id stayed the same.

##### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: Planned Work

###### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: 1. Generic Bootstrap Repair

- [x] Repair generic websocket bootstrap shells from singular current runtime-target authority.
- [x] Collapse ambiguous or incomplete runtime-target authority back to no routing bundle.

###### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: 2. Reused Session Refresh

- [x] Make repaired bootstrap shells participate in route-change detection for reused websocket sessions.
- [x] Clear stale authenticated/gameplay binding when repaired bootstrap routing changes under the same runtime id.

###### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: 3. Proof and Documentation

- [x] Add focused unit proof for singular repair, ambiguous collapse, and reused-session route refresh.
- [x] Align the parent `09.1` slice and queue/index docs with this bootstrap-edge follow-through.

##### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: Acceptance Shape

- generic websocket bootstrap no longer leaves a singular current route undiscovered when only runtime-target authority is available;
- ambiguous current runtime authority does not mint a synthetic routing bundle on the bootstrap shell;
- reused websocket sessions clear stale binding when repaired bootstrap pointer authority changes even if the bootstrap runtime id itself does not.

##### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: Validation

- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.websocket.GameSessionWebSocketHandlerTest'`
- `./gradlew spotlessApply :game-session-service:check -PfullCheck linkCheck lintMarkdown check`

##### source-09-1-9-task-list-websocket-bootstrap-runtime-authority-follow-through-vertical-slice-1-78: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77

#### Realm-Scoped Character and Playable State Policy Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-77)

##### Preserved Source Text: source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77

<!-- migration-source path="design/project-management/vertical-slices/09.3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice.md" lines="1-77" sha256="77662058617528538ba9237441b1dedbf48b192efe4f1b6cff7b242be2a5484d" heading-offset="3" -->
#### source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77: Realm-Scoped Character and Playable State Policy Vertical Slice

##### source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77: Goal and Status

Goal: lock the first executable policy for how character identity, durable progression, inventory, and other playable state behave across shared-state versus isolated-state realms so downstream gameplay systems stop assuming that tenant ownership automatically implies one shared runtime state namespace. Status: complete at the current boundary.

##### source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77: Why This Slice Exists

This is the most architecture-sensitive part of the multi-tenancy domain. The docs already say tenant ownership and playable state are different concerns, but the repo does not yet have one slice dedicated to making that boundary executable enough for inventory, progression, social, and future migration work.

##### source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77: Implementation Notes

The target-state contract is now sharper in the architecture docs:

- shared-state versus isolated-state is no longer just descriptive language; the docs now call out the minimum gameplay-state families that must follow realm policy first;
- `CHARS` is explicitly scoped to the resolved `{tenantId, gameInstanceId}` target rather than a tenant-wide superset;
- isolated realms are now explicitly prevented from silently reading from or writing to the tenant's normal live production roster when their policy is fork-local or otherwise isolated;
- `CHARS` is now required to expose one realm-local roster plus explicit creation policy, so clients do not infer isolated-realm rules from storage shape or tenant-wide comparisons.

What remains open is implementation and per-service rollout order, not whether the distinction matters.

The first implementation cut now exists:

- shared gameplay catalog data now carries explicit `stateScope` and `characterCreationPolicy` on each realm;
- bootstrap realm discovery, bootstrap character discovery, text `REALMS`, text `CHARS`, and `PLAY` all surface or enforce that policy directly;
- Entity Management now derives a canonical `playableStateKey` from the resolved gameplay target, so shared-state realms reuse one tenant-live namespace while isolated-state realms use an instance-local roster namespace;
- bootstrap discovery, text lobby browse, `PLAY`, and `TELL` now all resolve character lookup against that same scope-aware roster contract instead of using tenant-wide character list/name lookup seams;
- Entity Management character read surfaces now also echo the resolved `playableStateScope` back to gRPC and REST callers instead of forcing later consumers to infer realm policy from the request path or hidden storage key shape;
- inventory, equipment, container, and room-ground item runtime APIs now follow that same realm policy because they resolve the character through the shared scoped resolver before serving or mutating character-owned playable state; the REST and gRPC surfaces for those state families now carry the resolved `{gameInstanceId, playableStateScope}` target instead of accepting bare tenant-plus-character IDs;
- per-character friend links in Entity Management now also reject cross-playable-state joins, so isolated rosters cannot silently create social edges back into the tenant's shared live roster just because both characters belong to the same tenant;
- the Entity Management friend REST surface now also requires the resolved `{gameInstanceId, playableStateScope}` target instead of accepting bare tenant-plus-character IDs, so caller-visible API shape matches the same realm-sensitive roster contract that the service enforces underneath;
- scoped character lookup itself now has a shared Entity Management resolver service rather than duplicating ad hoc playable-state-key checks in each caller, so later `09.3` follow-through can reuse one canonical gameplay-target gate instead of re-inventing it per service.
- the first character progression/activity mutation seams now require the resolved `{tenantId, gameInstanceId, playableStateScope}` target before updating, so level/experience and login/activity-style character state no longer mutate by global character id alone.
- Automation Scripting faction reputation now also keys standings by the resolved playable-state namespace and requires `{tenantId, gameInstanceId, playableStateScope, characterId}` on its REST/service seam, so isolated realms cannot silently share faction standing with the tenant-wide live roster.
- Entity Management actor resources and active conditions now persist against the same derived playable-state namespace instead of storing raw `gameInstanceId`, so shared-state realms actually converge on shared actor-state rows while isolated realms stay fork-local.
- gameplay-session attestations carried into Entity Management now also preserve the attested `playableStateScope` from the admitted session bundle, and Entity Management validates that scope on gameplay-owned gRPC calls instead of trusting a narrower standalone request field.
- gameplay-originated Automation Scripting ingress now also carries the resolved `playableStateScope` as a first-class field instead of inferring it later from `gameInstanceId` conventions or payload JSON, so handler admission and durable trigger identity preserve the same shared-versus-isolated realm boundary as the gameplay services that emitted the event.
- Automation Scripting durable work-item, ingress-audit, handler-audit, handoff-event, dead-letter, schedule-instance, and pin-projection records now persist `playableStateScope`, and timer/materialized schedule follow-up work preserves that scope across runtime-state refresh and requeue paths instead of collapsing back to instance-only identity.
- Automation Scripting control-plane read surfaces now expose `playableStateScope` on dead-letter, handoff-event, and schedule-instance entries, so operators can see whether scripting work belongs to a shared live roster or an isolated realm fork without reconstructing that state indirectly from `gameInstanceId`.
- account-scoped gameplay presence in Game Session now also persists and exposes the admitted `playableStateScope`, and Social Groups friend-presence reads carry that scope through both live and recent-presence paths instead of surfacing world/realm/pointer metadata while hiding whether the presence belongs to shared live state or an isolated fork.

What remains open is the final roster/state substrate:

- downstream gameplay-state namespaces beyond the character/inventory/equipment/container/progression-resource namespace still need the same realm-policy rollout as they become real;
- the next honest follow-through is later loadout, ability, authored-effect, and richer resource-table families, not reopening the now-scoped roster, item, progression, faction, scripting, or presence seams already covered here.

##### source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77: Scope

- shared-state realm versus isolated-state realm policy
- character-selection semantics for a resolved `{tenantId, gameInstanceId}`
- minimum identity and state-keying consequences for downstream services
- copied-state or fresh-state realm expectations for playtest forks
- explicit statement of what remains tenant-scoped identity versus realm-/instance-scoped gameplay state

##### source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77: Out of Scope

- full migration/remap mechanics for replacement-instance upgrades
- detailed per-service schema work for every gameplay subsystem
- economy, guild, or combat-specific carry-forward rules

##### source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77: Locked Direction

- tenant membership and character ownership do not imply one shared runtime state namespace across all realms.
- downstream services must key playable state according to the resolved runtime target when the selected realm is isolated-state.
- shared-state and isolated-state realm modes are product-level contracts, not hidden implementation details.
- playtest forks are the canonical isolated-state example and must not silently mutate the tenant's live production state.

##### source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77: Current Remaining Work

- [ ] Keep later loadout, ability, authored-effect, resource-table, and other future gameplay-state families on the same resolved playable-state namespace rather than reintroducing tenant-wide shortcuts as those families land. The next bounded child slice is [`09.3.1`](../vertical-slices/09.3.1-task-list-playable-state-family-namespace-follow-through-vertical-slice.md).
- [ ] Keep future character creation and any new social/runtime consumers on the same scope-aware roster contract rather than letting tenant-wide lookup seams reappear in new surfaces.

##### source-09-3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice-1-77: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end at the current bounded runtime surface.
- [x] Verify the current live seams and move later-state-family follow-through into future implementation work instead of leaving the slice perpetually half-open.
<!-- /migration-source -->

### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95

#### 09.3.1 Task List: Playable-State Family Namespace Follow-Through Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-95)

##### Preserved Source Text: source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95

<!-- migration-source path="design/project-management/vertical-slices/09.3.1-task-list-playable-state-family-namespace-follow-through-vertical-slice.md" lines="1-95" sha256="1a4bf876a164959e37709f8d7f38a791b38a6a0baba415b04deb9f6bc17d41d7" heading-offset="3" -->
#### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: 09.3.1 Task List: Playable-State Family Namespace Follow-Through Vertical Slice

##### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: Goal and Status

Goal: carry the now-live `{tenantId, gameInstanceId, playableStateScope}` realm-policy contract into the next concrete gameplay-state families that still risk tenant-wide shortcuts after `09.3`. Status: complete at bounded target.

##### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: Why This Slice Exists

`09.3` closed the first major playable-state-policy boundary for roster, inventory, equipment, progression, faction reputation, scripting, and presence. This slice now carries the next bounded follow-through:

- later gameplay-state families can still drift back to tenant-wide or character-global shortcuts if they are not explicitly brought onto the same namespace contract;
- the current remaining work is no longer about proving the policy matters, only about applying it to the next real owning families;
- this is a good bounded continuation cut because it reuses the already-decided realm-policy model rather than reopening it.

##### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: Scope

- the next real gameplay-state family or closely related family set that still falls outside the current `09.3` namespace proof;
- service contracts, persistence keys, and focused reads/mutations for the touched family;
- focused proof that shared-state and isolated-state realms stay on the same resolved namespace contract for that family.

##### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: Out of Scope

- reopening already-converged `09.3` families such as roster, inventory, progression, faction reputation, scripting, or presence;
- full migration design for every future gameplay-state subsystem in one batch;
- broader realm-routing work already owned by `09.1`.

##### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: Locked Direction

- later gameplay-state families must consume the same resolved playable-state namespace as the already-converged `09.3` surfaces;
- isolated realms must not silently mutate tenant-live state through a family-specific shortcut;
- the follow-through should land on one concrete owning family boundary instead of staying as a broad reminder.

##### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: Planned Work

###### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: 1. Remaining Family Audit

- [x] Enumerate the still-live gameplay-state families beyond the current `09.3` boundary, especially loadout, ability, authored-effect, and richer resource-table seams where present.
- [x] Choose the smallest real owning family set that is live enough to converge now.
- [x] Skip already-converged or not-yet-real families.

###### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: 2. Namespace Contract Follow-Through

- [x] Move the touched family onto the canonical `{tenantId, gameInstanceId, playableStateScope}` resolution contract for reads, writes, and persistence.
- [x] Remove any touched tenant-wide or bare-character-id shortcuts that conflict with the realm-state policy.
- [x] Keep caller-visible API or read-model shape aligned with that same resolved namespace truth where relevant.

###### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: 3. Focused Proof and Docs

- [x] Add or refresh focused proof for shared-state versus isolated-state behavior on the touched family.
- [x] Update `09.3` docs/status so the remaining later-family tail is explicit after this cut.
- [x] Re-run touched validation and Markdown/link proof.

##### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: Completion Evidence

- `services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/CharacterServiceImpl.java`
- `services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/InventoryServiceImpl.java`
- `services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/EquipmentServiceImpl.java`
- `services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/FriendServiceImpl.java`
- `services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/ActorStateServiceImpl.java`
- `services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/ActorConditionMutationServiceImpl.java`
- `services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/impl/ContainerServiceImpl.java`
- `services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/ScopedCharacterResolver.java`
- `services/entity-management-service/src/main/java/net/firedevops/firemud/entitymanagement/service/PlayableStateKeyResolver.java`
- `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/CharacterServiceImplTest.java`
- `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/controller/InventoryControllerTest.java`
- `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/controller/EquipmentControllerTest.java`
- `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/service/impl/ActorStateServiceImplTest.java`
- `services/entity-management-service/src/test/java/unit/net/firedevops/firemud/entitymanagement/controller/FriendControllerTest.java`

##### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: Acceptance Shape

- the touched gameplay-state family no longer bypasses the resolved playable-state namespace contract;
- shared-state and isolated-state realms behave consistently with the canonical `09.3` policy for that family;
- focused proof covers both the namespace fence and the intended shared-versus-isolated behavior.

##### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: Spark Delegation Notes

- Start by auditing the next real post-`09.3` gameplay-state families, then pick the smallest still-live family set worth converging now.
- Do not reopen already-covered roster/item/progression/faction/scripting/presence seams.
- Return the exact family chosen, exact changed files, and exact validation commands run.

##### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: Suggested Starting Surfaces

- `services/entity-management-service`
- `services/game-session-service`
- `services/automation-scripting-service`
- `design/project-management/vertical-slices/09.3-task-list-realm-scoped-character-and-playable-state-policy-vertical-slice.md`

##### source-09-3-1-task-list-playable-state-family-namespace-follow-through-vertical-slice-1-95: Validation

- `./gradlew spotlessApply`
- `./gradlew :entity-management-service:check -PfullCheck`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew :automation-scripting-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64

#### Bootstrap Discovery and Connect-Scope Resolution Vertical Slice - Canonical realm-routing and playable-state source record (source lines 1-64)

##### Preserved Source Text: source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64

<!-- migration-source path="design/project-management/vertical-slices/09.4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice.md" lines="1-64" sha256="8e6a8b8bea608c7472c1adaec564ddac63392bf2201bb124c211c9ea63c0976b" heading-offset="3" -->
#### source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64: Bootstrap Discovery and Connect-Scope Resolution Vertical Slice

##### source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64: Goal and Status

Goal: make first-party bootstrap discovery and connect-token issuance resolve against the same authoritative tenant/realm routing and admission rules as text-client `PLAY`, so browser/mobile bootstrap does not become a parallel gameplay-selection model with weaker routing guarantees. Status: implemented at the current boundary.

##### source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64: Why This Slice Exists

The first-party bootstrap path is already partly real, but the broader discovery and connect-scope model is still mostly architectural. This slice keeps that work attached to the multi-tenancy and realm-routing domain rather than letting it drift into a frontend-only or reconnect-only bucket.

##### source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64: Implementation Notes

The target-state contract is now sharper in the architecture docs:

- bootstrap discovery is explicitly the client-facing projection of the same realm catalog, realm-visibility, and admission-pointer truth used by in-band lobby selection;
- `connectScopeId` is now documented as an opaque short-lived selector for one caller-visible realm target rather than a durable identifier clients may reinterpret;
- `/auth/connect-token` is explicitly required to re-check current pointer and visibility/grant truth at issuance time and fail closed when the earlier discovery target has gone stale.
- discovery responses now require an explicit freshness bundle (`pointerVersion`, `evaluatedAt`, `connectScopeExpiresAt`) instead of implying selectors are durable;
- connect-token issuance now treats `requestId` as a true idempotency key, so retries for the same selector/attempt return the same token payload or the same deterministic failure.

The first implementation cut now exists:

- `account-service` now exposes canonical bootstrap discovery endpoints for worlds, realms, and characters instead of treating first-party bootstrap as a caller-guessed target tuple;
- bootstrap discovery responses now carry the required selector freshness bundle (`pointerVersion`, `evaluatedAt`, `connectScopeExpiresAt`) and a short-lived opaque `connectScopeId`;
- `/auth/connect-token` now resolves and validates the selected realm target through the same Game Session routing truth used by text clients instead of trusting caller-supplied `tenantId` / `gameInstanceId`;
- connect-token issuance now treats `requestId` as an idempotency key and fails closed when the earlier selector has gone stale;
- same-`requestId` connect-token retries now replay the same token payload or the same deterministic failure while the selector is still live, instead of reminting fresh JWTs with drifting `issuedAt` / `expiresAt` under one logical attempt;
- stale or expired `connectScopeId` failures now surface deterministic rerun-discovery guidance instead of a generic auth miss: invalid scopes return `CONNECT_SCOPE_INVALID`, stale or cutover-mismatched scopes return `CONNECT_SCOPE_MISMATCH`, and both direct first-party clients back to fresh bootstrap discovery rather than local target fallback;
- the first-party gateway/game-session connect context now preserves and validates `worldSlug`, `realmSlug`, `connectScopeId`, `connectRequestId`, and `pointerVersion` end to end rather than reducing first-party connect to a minimal legacy gameplay target claim.
- Game Session now also persists that first-party selector identity on the durable bootstrap shell itself and reuses it as the reconnect/login/`PLAY` fallback when the transient registry entry is missing, so reconnect-style consumers stay on the same `connectScopeId` freshness contract instead of degrading back to world/realm-only hints.

What remains open is broader later-domain follow-through outside this slice, not the bootstrap/connect-scope contract itself.

##### source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64: Scope

- bootstrap world/realm/character discovery surfaces
- canonical `connectScopeId` or equivalent selector identity
- `/auth/connect-token` resolution against current realm routing
- relationship between bootstrap discovery, public-production membership creation, and runtime entitlement/membership checks
- parity of selection truth between first-party clients and text clients

##### source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64: Out of Scope

- generic reconnect handshake mechanics already covered by `02.4`
- broader web-app/frontend product-surface work
- runtime launch/activation internals already covered by `08`

##### source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64: Locked Direction

- bootstrap discovery is a client-facing projection of the same realm-routing and admission truth used by other gameplay entry paths.
- connect-token issuance must pin to the currently admissible instance for the selected realm, not a caller-guessed instance.
- first-party discovery must not invent hidden selection rules that diverge from `WORLDS` / `REALMS` / `CHARS` / `PLAY`.

##### source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64: Current Remaining Work

- [x] Keep future reconnect/bootstrap consumers on the same `connectScopeId` freshness contract instead of reintroducing direct target selection shortcuts.
- [x] Implement connect-scope invalidation behavior cleanly enough that clients rerun discovery on stale selector errors or selector expiry instead of inventing local fallback routing.
- [x] Prove `requestId`-keyed connect-token issuance idempotency together with stale-scope failure handling under broader first-party reconnect/cutover scenarios.

##### source-09-4-task-list-bootstrap-discovery-and-connect-scope-resolution-vertical-slice-1-64: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

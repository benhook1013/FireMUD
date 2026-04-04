# Temporary Audit Watchlist

This file tracks post-pre-`06` audit findings that still appear live in the current tree and should not get lost in chat.

## Fix Now

- [x] Add a real gRPC auth/authorization layer for mutable `game-session-service` RPCs, including `startSession`, `stopSession`, `restartSession`, command enqueue, tick pause/resume, and script-pinning control-plane methods.
- [x] Protect gateway route-management surfaces properly:
  - REST `/routes`
  - gRPC `GatewayManagementGrpcService`
- [x] Fix tenant-scoped HTTP authorization in admin-style MVC services so scoped roles cannot act on arbitrary tenant ids supplied in request params/bodies.
- [x] Fix `logging-admin-service` moderation session-stop behavior to target actual session ids instead of passing `accountId` into `stopSession`.
- [x] Add a real web auth layer for write-capable `game-design-service` REST endpoints.
- [x] Tighten request-supplied `tenantId` handling in `game-design-service` REST endpoints.
- [x] Add role-gating to admin-capable gRPC mutations in `logging-admin-service` and `game-design-service`.
- [x] Fix `logging-admin-service` Flyway migration `V4__add_tenant_id_to_feature_flag.sql` so it is safe on non-empty databases.

## Fix Soon

- [x] Make Redis lock release ownership-safe in entity-management and automation-scripting tick locks.
- [x] Make `RedisSessionContextService.save` fully atomic across its indexes.
- [x] Move remaining cross-service/network calls out of open DB transactions in places like `GuildServiceImpl` / saga orchestration paths.
- [x] Fix race-prone version numbering in `game-design-service` and add a DB uniqueness invariant for `(tenant_id, version_number)`.
- [x] Enforce same-tenant ownership in entity-management aggregate stitching, including inventory attachments and friendship links.
- [x] Cap or otherwise harden gateway gameplay bridge buffering during upstream reconnect/backpressure stalls.

## Refactor / Hygiene

- [x] Reconcile `@RequireAdminRole` semantics with MVC use; MVC controllers now use `SessionContext.requireTenantAccess` and `@RequireAdminRole` remains scoped to gRPC/global-role surfaces.
- [x] Make `social-groups-service` use `GrpcAppErrors` consistently instead of hand-building `ErrorDetail`.
- [x] Change `logging-admin` log query away from `GET` with request body.
- [x] Stop mutating active-saga metrics from `SagaDashboardServiceImpl` read paths.

## Already Accounted For

- [x] Shared after-commit side-effect infrastructure exists; the remaining issue is inconsistent use, not missing mechanism.
- [x] Gateway gameplay proxy-header spoofing/replay protection concerns were already materially improved earlier in this branch history.

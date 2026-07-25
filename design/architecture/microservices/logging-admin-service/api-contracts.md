# Logging & Admin Service API Contracts

This document defines the Logging & Admin Service REST and gRPC surfaces, authentication classes, and endpoint availability expectations.

## Implementation Notes

- Admission-pointer workflows, feature-flag toggles, and scoped tick-remediation pause/resume forwarding are live in the current service.
- The canonical `SetAdmissionPointerRequest` REST schema omits `actorPrincipal`. `AdmissionPointerServiceImpl` derives the actor from the authenticated operator session or internal workload and writes that derived value to the internal control-plane request; `actorPrincipal` is response/audit data, not client-supplied write authority.
- Quota-override ingress remains target-state until Account exposes the canonical owner-side override mutation contract.
- Broader tick-remediation `remediate` remains target-state until Game Session exposes the canonical owner-side remediation RPC.

## REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /reports` – submit an abuse or bug report.
- `POST /feature-flags/toggle` – enable or disable runtime flags.
- `POST /logs/query` – search stored logs.
- `POST /moderation/actions` – apply a moderation action.
- `GET /sagas` – list saga instances.
- `GET /sagas/{id}/steps` – inspect steps for a saga instance.
- `GET /admission-pointers` – list the caller-visible Game Session route records joined with their separately revisioned catalog/policy projection, including `OPEN`/`CLOSED`, the route version, and the explicit public-production policy used by admission and first-join membership creation.
- `GET /admission-pointers/{tenantId}/{worldSlug}/{realmSlug}/audit` – read the append-only cutover audit history for one gameplay realm pointer, using the same canonical `{tenantId, worldSlug, realmSlug}` key as admission authority itself instead of inferring tenant ownership from world/realm selectors after the read.
- `POST /admission-pointers` – operator-facing same-target route-state mutation that opens the existing `gameInstanceId` or moves it to `CLOSED`, forwards the required expected route version to Game Session's atomic compare-and-set control plane, and derives the actor from the authenticated session rather than trusting caller-supplied actor identity. Replacing one open `gameInstanceId` with another is not a generic mutation and is accepted only through `/admission-pointers/cutover`.
- Realm display, visibility, and public-production policy mutations use a separately revisioned catalog/policy control-plane surface; they must not be smuggled through a route mutation or invalidate active gameplay merely by changing presentation metadata.
- `POST /admission-pointers/cutover` – operator-facing canonical prepared-cutover operation that forwards one prepared-upgrade id, replacement instance id, and pointer CAS guard to Game Session so proof revalidation and pointer swap happen in one control-plane call.
- `POST /admission-pointers/version-upgrades` – operator-facing preparation call that persists the Game Session `PrepareVersionUpgrade` compatibility proof for a source instance and target version under a caller-supplied idempotency key.
- `GET /admission-pointers/version-upgrades/{tenantId}/{preparationId}` – read one durable prepared-version-upgrade proof, including participant attestations and execution state after a cutover has consumed the preparation.
- `POST /quota-overrides` – reserved future operator-facing quota override write ingress. This remains deferred until Account exposes the canonical owner-side override mutation contract.
- `DELETE /quota-overrides/{scopeType}/{scopeId}/{quotaKey}` – reserved future operator-facing quota override removal using the same owner-side contract as creation/update.
- `POST /tick-remediation/pause` – operator-facing scoped tick pause request that forwards to Game Session control-plane, records actor identity and reason, and never mutates Redis directly.
- `POST /tick-remediation/resume` – operator-facing scoped tick resume request that forwards to Game Session control-plane with the same audit requirements.
- `POST /tick-remediation/remediate` – reserved future operator-facing scoped remediation request for coordination/tick recovery. This remains deferred until Game Session exposes a canonical owner-side remediation RPC; Logging & Admin must not invent direct Redis mutation or a fake remediation contract in the meantime.

### Canonical Admission State Operations

`POST /admission-pointers` represents exactly one of two same-target Game Session control-plane operations for `{tenantId, worldSlug, realmSlug}`:

- `OpenAdmissionPointer` performs `CLOSED -> OPEN(target)`. The target must exist, belong to `tenantId`, and be the prepared/active runtime for the same world and realm. It must pass current runtime lifecycle and entitlement/admission checks; display names, slugs, or a caller-supplied instance id are not proof of that relationship. If validation is unavailable or ambiguous, the operation fails closed. `preparedVersionUpgradeId` is forbidden because this operation cannot replace an already open target.
- `CloseAdmissionPointer` performs `OPEN(target) -> CLOSED`. The request must identify the exact currently open `gameInstanceId`; a missing pointer, already closed pointer, or different current target fails without mutation. Closing is a safety operation and therefore does not require the target to remain lifecycle- or entitlement-admissible. It cannot select a replacement target, and `preparedVersionUpgradeId` is forbidden. The resulting pointer makes no `gameInstanceId` gameplay-admissible while preserving the former target in audit evidence.
- Both requests carry the complete routing identity, target `gameInstanceId`, expected route version (`expectedPointerVersion`), reason, and idempotent `controlPlaneRequestId`. The schema does not accept a client-supplied actor: the actor is derived server-side from the authenticated operator session or workload. The response and audit record expose that actor and the resulting monotonic `pointerVersion`. `pointerVersion` is the route version and CAS token for exactly `{tenantId, worldSlug, realmSlug}`. The write succeeds only when the stored version equals the expected version; otherwise it returns a conflict and leaves the route unchanged. An absent route record uses `expectedPointerVersion=0` for the first open rather than an unscoped blind write.
- Replacing or rolling back `OPEN(old)` to `OPEN(new)` is a prepared cutover, not a generic open. It must use `/admission-pointers/cutover` / `ExecutePreparedVersionCutover` with one durable `preparedVersionUpgradeId`; that operation revalidates the preparation, source and target identities, and CAS version before atomically swapping the route. Preparation is proof for cutover, not a substitute for route-version concurrency control.
- Game Session remains the sole routing-state writer and audits the transition under the same identity and CAS result. Logging & Admin authenticates and records operator intent but must not maintain a competing pointer or infer `tenantId` from `worldSlug` or `realmSlug`.

```bash
curl http://localhost:8080/ping
```

## gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`logging_admin_service.proto`](../../../../protos/logging-admin/v1/logging_admin_service.proto).
- `QueryLogs(QueryLogsRequest) returns (QueryLogsResponse)` – searches collected logs.
- `ApplyModerationAction(ApplyModerationActionRequest) returns (ApplyModerationActionResponse)` – records a moderation event.
- `CreateReport(CreateReportRequest) returns (CreateReportResponse)` – ingest a player report.
- `ToggleFeatureFlag(ToggleFeatureFlagRequest) returns (ToggleFeatureFlagResponse)` – enable or disable a feature flag.
- Tick-remediation is not a Logging & Admin-owned state-mutation gRPC surface; this service audits and forwards those operator actions to Game Session control-plane APIs instead of defining a competing remediation RPC.

```bash
grpcurl -plaintext localhost:6565 logging_admin.v1.LoggingAdminService/Ping

grpcurl -plaintext -d '{"tenant_id":1,"reporter_account_id":1,"target_account_id":2,"type":"BUG","description":"example"}' \
  localhost:6565 logging_admin.v1.ReportService/CreateReport
```

## Endpoint Authentication Classes

| Surface | Examples | Required auth path | Notes |
| --- | --- | --- | --- |
| Public/infra health | `GET /ping`, `Ping` | Internal network + platform health policy | Not a user-authenticated business operation. |
| Admin/operator APIs (HTTP) | `/logs/query`, `/moderation/actions`, `/feature-flags/toggle`, `/reports`, `/sagas*`, `/admission-pointers*`, `/quota-overrides*`, `/tick-remediation*` | JWT middleware (`AuthTokenInterceptor` + route classification) | External tools must enter via Gateway allowlisted routes. |
| Service-to-service control/ingest (gRPC internal) | Internal lifecycle/event ingestion and trusted backend calls | mTLS caller identity + explicit service authorization checks | Never exposed at public ingress; role claims are required only for user-scoped actions. |

## Availability Classes by Endpoint Family

| Endpoint family | Availability class | Required behavior during observability outage |
| --- | --- | --- |
| `/moderation/actions`, `/feature-flags/toggle`, `/reports`, `/sagas*`, `/admission-pointers*`, `/quota-overrides*`, `/tick-remediation*` | Core operator control plane | Remain available; may use local/PostgreSQL-backed audit state and downstream domain-service APIs only |
| `/logs/query`, embedded Kibana/Grafana/Jaeger/Alertmanager views | Observability-backed | May degrade, return explicit unavailable/read-only states, or be hidden behind degraded-state messaging |

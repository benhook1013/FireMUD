# Logging & Admin Service API Contracts

This document defines the Logging & Admin Service REST and gRPC surfaces, authentication classes, and endpoint availability expectations.

## Implementation Notes

- Admission-pointer workflows, feature-flag toggles, and scoped tick-remediation pause/resume forwarding are live in the current service.
- Quota-override ingress remains target-state until Account exposes the canonical owner-side override mutation contract.
- No generic tick-remediation payload is part of the target. Each future recovery operation requires a named typed contract implemented by its authoritative workflow owner.

## REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /reports` – submit an abuse or bug report.
- `POST /feature-flags/toggle` – enable or disable runtime flags.
- `POST /logs/query` – search stored logs.
- `POST /moderation/actions` – apply a moderation action.
- `POST /moderation/appeals` – Account-authenticated player submission of one eligible appeal against an exact restriction revision; repeated `requestId` or an already active appeal returns the same bounded result.
- `GET /moderation/appeals/{appealCaseId}` – caller-bound safe player status projection or authorized jurisdiction-scoped moderator view; player responses exclude protected evidence and reporter identity.
- `POST /moderation/appeals/{appealCaseId}/review` – authorized tenant- or platform-jurisdiction review that records one terminal decision and, for modification or reversal, sends a new monotonic digest-bound command to the enforcement owner.
- `GET /sagas` – list saga instances.
- `GET /sagas/{id}/steps` – inspect steps for a saga instance.
- `GET /admission-pointers` – list the caller-visible Game Session route records joined with their separately revisioned catalog/policy projection, including `OPEN`/`CLOSED`, the route version, and the explicit public-production policy used by admission and first-join membership creation.
- `GET /admission-pointers/{tenantId}/{worldSlug}/{realmSlug}/audit` – read the append-only cutover audit history for one gameplay realm pointer, using the same canonical `{tenantId, worldSlug, realmSlug}` key as admission authority itself instead of inferring tenant ownership from world/realm selectors after the read.
- `POST /admission-pointers` – operator-facing `OPEN(gameInstanceId)` / `CLOSED` mutation that forwards the required expected route version to Game Session's atomic compare-and-set control plane, derives the actor from the authenticated session rather than trusting caller-supplied actor identity, and requires `preparedVersionUpgradeId` whenever an open pointer is moved to a different `gameInstanceId`.
- Realm display, visibility, and public-production policy mutations use a separately revisioned catalog/policy control-plane surface; they must not be smuggled through a route mutation or invalidate active gameplay merely by changing presentation metadata.
- `POST /admission-pointers/cutover` – operator-facing canonical prepared-cutover operation that forwards one prepared-upgrade id, replacement instance id, and pointer CAS guard to Game Session so proof revalidation and pointer swap happen in one control-plane call.
- `POST /admission-pointers/version-upgrades` – operator-facing preparation call that persists the Game Session `PrepareVersionUpgrade` compatibility proof for a source instance and target version under a caller-supplied idempotency key.
- `GET /admission-pointers/version-upgrades/{tenantId}/{preparationId}` – read one durable prepared-version-upgrade proof, including participant attestations and execution state after a cutover has consumed the preparation.
- `POST /quota-overrides` – reserved future operator-facing quota override write ingress. This remains deferred until Account exposes the canonical owner-side override mutation contract.
- `DELETE /quota-overrides/{scopeType}/{scopeId}/{quotaKey}` – reserved future operator-facing quota override removal using the same owner-side contract as creation/update.
- `POST /tick-remediation/pause` – operator-facing scoped tick pause request that forwards to Game Session control-plane, records actor identity and reason, and never mutates Redis directly.
- `POST /tick-remediation/resume` – operator-facing scoped tick resume request that forwards to Game Session control-plane with the same audit requirements.
- Future coordination/tick recovery operations use a closed catalog of named typed actions. Logging & Admin may invoke the authoritative versioned maintenance owner/tool, but it does not expose a generic `remediate(action, payload)` endpoint, reveal internal recovery phases as public verbs, or hold Redis write credentials.

Every supported operator write follows [ADR 0048](../../decisions/adr-0048-durable-idempotent-operator-write-execution.md): Logging & Admin persists scope-complete intent before forwarding, uses one caller-reusable request ID bound to a payload digest, and reconciles uncertain outcomes; the owner atomically persists its mutation and idempotent result. Unsupported actions and scopes fail explicitly.

Quota overrides, when implemented, are Account-owned bounded overlays with exact key/scope/value, actor/reason, stable request identity, start/expiry/removal, platform-cap validation, and monotonic entitlement-sequence propagation. They do not rewrite subscription or billing-provider history.

```bash
curl http://localhost:8080/ping
```

## gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`logging_admin_service.proto`](../../../../protos/logging-admin/v1/logging_admin_service.proto).
- `QueryLogs(QueryLogsRequest) returns (QueryLogsResponse)` – searches collected logs.
- `ApplyModerationAction(ApplyModerationActionRequest) returns (ApplyModerationActionResponse)` – records a moderation event.
- Appeal case mutation/read contracts use the REST surfaces above until an internal owner-command handoff requires a dedicated generated contract; implementations must not overload `ApplyModerationAction` or rewrite its original history.
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
| Player appeal APIs (HTTP) | Appeal submission and caller-safe status under `/moderation/appeals*` | Account-authenticated exact subject binding, normally reached through the Account-owned browser handoff | Submission/status never grants moderator access or exposes protected evidence/reporter identity. |
| Admin/operator APIs (HTTP) | `/logs/query`, `/moderation/actions`, appeal review, `/feature-flags/toggle`, `/reports`, `/sagas*`, `/admission-pointers*`, `/quota-overrides*`, `/tick-remediation*` | JWT middleware (`AuthTokenInterceptor` + route classification) | Appeal review additionally requires exact tenant or platform jurisdiction. External tools must enter via Gateway allowlisted routes. |
| Service-to-service control/ingest (gRPC internal) | Internal lifecycle/event ingestion and trusted backend calls | mTLS caller identity + explicit service authorization checks | Never exposed at public ingress; role claims are required only for user-scoped actions. |

## Availability Classes by Endpoint Family

| Endpoint family | Availability class | Required behavior during observability outage |
| --- | --- | --- |
| Risk-reducing core writes | Add restrictions, close admission, pause ticks, disable features, lower quotas | Remain available during observability-only loss when authentication/scope, durable audit/intent, authoritative owner/fence, stable request identity, and durable acknowledgement are available; otherwise fail closed |
| Exposure-increasing or recovery core writes | Open/cut over admission, resume ticks, enable features, raise quotas/capacity, lift restrictions, remediation | Observability loss alone does not block them, but every normal compatibility, recovery, freshness, fence, audit, and owner-state gate remains mandatory |
| Core reads/cases | Reports, appeals, owner/control state, local saga state | Remain available from authoritative PostgreSQL/owner APIs; never present stale telemetry as owner truth |
| `/logs/query`, embedded Kibana/Grafana/Jaeger/Alertmanager views | Observability-backed | Degrade independently to explicit unavailable, read-only, or `unknown`; cached results are time-labelled and non-authoritative |

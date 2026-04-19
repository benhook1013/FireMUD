# Logging & Admin Service API Contracts

This document defines the Logging & Admin Service REST and gRPC surfaces, authentication classes, and endpoint availability expectations.

## REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /reports` – submit an abuse or bug report.
- `POST /feature-flags/toggle` – enable or disable runtime flags.
- `POST /logs/query` – search stored logs.
- `POST /moderation/actions` – apply a moderation action.
- `GET /sagas` – list saga instances.
- `GET /sagas/{id}/steps` – inspect steps for a saga instance.
- `POST /admin/tick-remediation/pause` – operator-facing pause request that audits intent and then calls the Game Session control-plane pause surface for the selected scope.
- `POST /admin/tick-remediation/resume` – operator-facing resume request that audits intent and then calls the Game Session control-plane resume surface for the selected scope.
- `GET /admin/admission-pointers` – list the caller-visible gameplay admission pointers exposed by Game Session control-plane.
- `GET /admin/admission-pointers/{worldSlug}/{realmSlug}/audit` – read the append-only cutover audit history for one gameplay realm pointer.
- `POST /admin/admission-pointers` – operator-facing cutover mutation that forwards to Game Session compare-and-set admission-pointer control plane and derives the actor from the authenticated session rather than trusting caller-supplied actor identity.

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
| Admin/operator APIs (HTTP) | `/logs/query`, `/moderation/actions`, `/feature-flags/toggle`, `/reports`, `/sagas*`, `/admin/tick-remediation/*`, `/admin/admission-pointers*` | JWT middleware (`AuthTokenInterceptor` + route classification) | External tools must enter via Gateway allowlisted routes. |
| Service-to-service control/ingest (gRPC internal) | Internal lifecycle/event ingestion and trusted backend calls | mTLS caller identity + explicit service authorization checks | Never exposed at public ingress; role claims are required only for user-scoped actions. |

## Availability Classes by Endpoint Family

| Endpoint family | Availability class | Required behavior during observability outage |
| --- | --- | --- |
| `/moderation/actions`, `/feature-flags/toggle`, `/reports`, `/sagas*`, tick-remediation APIs | Core operator control plane | Remain available; may use local/PostgreSQL-backed audit state and downstream domain-service APIs only |
| `/logs/query`, embedded Kibana/Grafana/Jaeger/Alertmanager views | Observability-backed | May degrade, return explicit unavailable/read-only states, or be hidden behind degraded-state messaging |

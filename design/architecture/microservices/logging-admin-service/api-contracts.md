# Logging & Admin Service API Contracts

This document defines the Logging & Admin Service REST and gRPC surfaces, authentication classes, and endpoint availability expectations.

## Implementation Notes

- Admission-pointer workflows, feature-flag toggles, and scoped tick-remediation pause/resume forwarding are live executable operator mutations in the current service.
- The live executable operator mutation surface is limited to those feature-flag, admission-pointer, and tick pause/resume families; moderation is currently persistence-only. This limitation does not mean that read, audit, log-search, saga, metrics, or dashboard/observability endpoints are absent; those are separate live read and investigation surfaces.
- Admission-pointer reads, audit, preparation, cutover, and same-target mutation endpoints are part of the core operator/control-plane availability contract and must not depend on observability backends.
- The canonical `SetAdmissionPointerRequest` REST schema omits `actorPrincipal`. `AdmissionPointerServiceImpl` derives the actor from the authenticated operator session or internal workload and writes that derived value to the internal control-plane request; `actorPrincipal` is response/audit data, not client-supplied write authority.
- The target `POST /reports` route is caller-bound to the `player-bootstrap` subject and live tenant membership. The current OpenAPI/controller accepts caller-supplied `tenantId` and `reporterAccountId`, so it is not an accepted external/player contract: exposed report submission must be disabled or fail closed until the caller-bound subject and membership checks are implemented.
- `ApplyModerationAction` currently persists moderation policy input and audit in Logging & Admin; no owner-side enforcement RPC is currently exposed. That missing owner call is target coverage, not an implemented downstream contract, and executable moderation routes are prohibited until the owning enforcement contract exists.
- Quota-override ingress is a hypothetical target family until Account exposes the canonical owner-side override mutation contract; no executable route exists today.
- Broader tick-remediation `remediate` is a hypothetical target family until Game Session exposes the canonical owner-side remediation RPC; no executable route exists today.

## REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /reports` – report persistence implementation only; external/player submission is disabled or fails closed while the current OpenAPI/controller accepts caller-supplied `tenantId` and `reporterAccountId`. The target player route derives reporter and tenant identity from the validated `player-bootstrap` session and evaluates routing and authorization under the dedicated player ingress/delegation policy.
- `POST /feature-flags/toggle` – live enable or disable runtime flags.
- `POST /logs/query` – search stored logs.
- `POST /moderation/actions` – persist moderation policy input and audit only (current); it does not forward or enforce a mutation, and executable moderation routes require a separate owner-propagation contract.
- `GET /sagas` – list saga instances.
- `GET /sagas/{id}/steps` – inspect steps for a saga instance.
- `GET /admission-pointers` – list the caller-visible Game Session route records joined with their separately revisioned catalog/policy projection, including `OPEN`/`CLOSED`, the route version, and the explicit public-production policy used by admission and first-join membership creation.
- `GET /admission-pointers/{tenantId}/{worldSlug}/{realmSlug}/audit` – read the append-only cutover audit history for one gameplay realm pointer, using the same canonical `{tenantId, worldSlug, realmSlug}` key as admission authority itself instead of inferring tenant ownership from world/realm selectors after the read.
- `POST /admission-pointers` – operator-facing same-target route-state mutation that opens the existing `gameInstanceId` or moves it to `CLOSED`, forwards the required expected route version to Game Session's atomic compare-and-set control plane, and derives the actor from the authenticated session rather than trusting caller-supplied actor identity. Replacing one open `gameInstanceId` with another is not a generic mutation and is accepted only through `/admission-pointers/cutover`.
- Realm display, visibility, and public-production policy mutations use a separately revisioned catalog/policy control-plane surface; they must not be smuggled through a route mutation or invalidate active gameplay merely by changing presentation metadata.
- `POST /admission-pointers/cutover` – operator-facing canonical prepared-cutover operation that forwards one prepared-upgrade id, replacement instance id, and pointer CAS guard to Game Session so proof revalidation and pointer swap happen in one control-plane call.
- `POST /admission-pointers/version-upgrades` – operator-facing preparation call that persists the Game Session `PrepareVersionUpgrade` compatibility proof for a source instance and target version under the caller-supplied `controlPlaneRequestId` and the canonical digest of the normalized preparation request. That identifier/digest pair is the canonical durable idempotency identity and is reused for exact retries; a reused identifier with a different digest conflicts.
- `GET /admission-pointers/version-upgrades/{tenantId}/{preparationId}` – read one durable prepared-version-upgrade proof, including participant attestations and execution state after a cutover has consumed the preparation.
- `POST /tick-remediation/pause` – live operator-facing scoped tick pause request that forwards to Game Session control-plane, records actor identity and reason, and never mutates Redis directly.
- `POST /tick-remediation/resume` – live operator-facing scoped tick resume request that forwards to Game Session control-plane with the same audit requirements.

### Hypothetical/Target Coverage

- Quota override is hypothetical target coverage: it has no current OpenAPI route and no current Account owner mutation contract. Do not add `/quota-overrides*` until the owner contract exists.
- Moderation enforcement is target coverage: it has no current Account, Game Session, or Social & Groups owner RPC or executable route; `POST /moderation/actions` records policy input and audit only.
- Scoped remediation beyond pause/resume is hypothetical target coverage: it has no current OpenAPI route and no current Game Session owner RPC. Do not add `/tick-remediation/remediate` or a direct Redis mutation path.

Supported executable operator mutations use Account's canonical [`IssueHumanOperatorAuthorizationReference`](../account-service/api-contracts.md#operator-authorization-references) contract after Logging & Admin authenticates the current `control-ui` actor and records durable intent. Unattended automation uses the typed Account `IssueAutomationOperatorAuthorizationReference` path with `exact_mtls_workload_plus_versioned_automation_policy`, bound to the exact Logging and Admin workload mTLS identity and current `automation_policy_id`/`automation_policy_version`; it carries no human identity or end-user token. Logging & Admin forwards the opaque bounded reference to the owning service only for those supported executable mutations. The persistence-only moderation route does not use this owner-forwarding flow. The owner recomputes the canonical digest of the normalized request and redeems the reference directly with Account; the request identity is the exact `controlPlaneRequestId` plus digest pair, and Logging & Admin never becomes a second authorization authority.

### Canonical Admission State Operations

`POST /admission-pointers` represents exactly one of two same-target Game Session control-plane operations for `{tenantId, worldSlug, realmSlug}`:

- `OpenAdmissionPointer` performs `CLOSED -> OPEN(target)`. The target must exist, belong to `tenantId`, and be the prepared/active runtime for the same world and realm. It must pass current runtime lifecycle and entitlement/admission checks; display names, slugs, or a caller-supplied instance id are not proof of that relationship. If validation is unavailable or ambiguous, the operation fails closed. `preparedVersionUpgradeId` is forbidden because this operation cannot replace an already open target.
- `CloseAdmissionPointer` performs `OPEN(target) -> CLOSED`. Game Session first checks the idempotency record for `controlPlaneRequestId`: an exact completed duplicate replays its original successful response and audit identity before current pointer-state validation. For a new request ID, the request must identify the exact currently open `gameInstanceId`; a missing pointer, already closed pointer, or different current target fails without mutation. Reusing a completed request ID with a different digest conflicts. Closing is a safety operation and therefore does not require the target to remain lifecycle- or entitlement-admissible. It cannot select a replacement target, and `preparedVersionUpgradeId` is forbidden. The resulting pointer makes no `gameInstanceId` gameplay-admissible while preserving the former target in audit evidence.
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
| Player-bootstrap report (HTTP) | `/reports` | Target only: dedicated player ingress/delegation route with exact `player-bootstrap` profile, caller-bound tenant membership, and membership-generation applicability | Until the target binding exists, exposed report submission is disabled or fails closed. The current OpenAPI/controller accepts caller-supplied `tenantId` and `reporterAccountId`, so it is not an operator authority path or an accepted player contract. |
| Operator APIs (HTTP) | `/logs/query`, `/moderation/actions`, `/feature-flags/toggle`, `/sagas*`, `/admission-pointers*`, `/tick-remediation/pause`, `/tick-remediation/resume` | Human: exact `control-ui` profile, current role, role-appropriate assurance, and route classification | External tools enter through Gateway-allowlisted routes. For supported executable families, Account issues the bounded human authorization reference before owner forwarding; `/moderation/actions` is persistence/audit only. Unattended automation uses its separate typed Account policy authorization rather than a human profile. |
| Owner mutation calls (gRPC internal) | Current feature flag, admission, and tick owner RPCs; target moderation, quota, and broader-remediation owner RPCs | Exact Logging & Admin mTLS identity plus Account redemption of the matching human or versioned automation authorization reference | The owner validates domain facts, fencing, and idempotency. Logging & Admin assertions alone are not authority. |
| Service-to-service control/ingest (gRPC internal) | Internal lifecycle/event ingestion and trusted backend calls | mTLS caller identity + explicit service authorization checks | Never exposed at public ingress; use an exact receiver-specific private delegation profile only where the route declares one. |

## Availability Classes by Endpoint Family

| Endpoint family | Availability class | Required behavior during observability outage |
| --- | --- | --- |
| `/moderation/actions`, `/feature-flags/toggle`, `/sagas*`, `/admission-pointers*`, `/tick-remediation/pause`, `/tick-remediation/resume` | Core operator/control plane | Remain available; may use local/PostgreSQL-backed audit state and downstream domain-service APIs only |
| `/reports` | Target player-bootstrap / player delegation | Until caller-bound subject and live tenant-membership binding are implemented, reject or disable the route. Once enabled, observability backends are not a prerequisite for durable report submission; local report persistence and required Account/player-delegation authority remain required, and an unavailable required dependency fails closed with an explicit unavailable result. |
| `/logs/query`, embedded Kibana/Grafana/Jaeger/Alertmanager views | Observability-backed | May degrade, return explicit unavailable/read-only states, or be hidden behind degraded-state messaging |

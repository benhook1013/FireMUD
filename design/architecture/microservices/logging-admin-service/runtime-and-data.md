# Logging & Admin Service Runtime and Data

This document defines the Logging & Admin Service runtime model, availability partitioning, control-plane responsibilities, and persistent data ownership.

## Architecture and Design Notes

Logging & Admin uses the common stack outlined in [Logging & Monitoring](../../system-architecture-logging-monitoring.md) and exposes admin endpoints for reviewing logs, managing coordination health, and coordinating gated moderation actions. It consumes Kibana and Grafana APIs to embed existing dashboards within the admin interface.

## Target-State Runtime Contract

Logging & Admin is the operator-facing policy, audit, and observability coordination plane. It owns moderation policy definitions, operator intent, audit history, the target/hypothetical quota and limit override UX, control-plane inspection, and the availability boundary between core operator actions and observability-backed experiences. It does not own gameplay coordination state, runtime feature-flag truth, entity state, or another service's persistence, and it must invoke those owning services through their canonical contracts.

The target service preserves two independently degradable partitions:

- core control-plane actions remain usable when search, metrics, tracing, dashboards, or alerting backends are unavailable;
- observability-backed searches, dashboards, traces, and alert investigations degrade explicitly without taking the core control plane down.

Tick and coordination remediation remains a Game Session responsibility. Logging & Admin may display health, choose an allowed operation, request a bounded remediation, and record audit evidence, but Game Session remains the authority for region leases, pause/resume, reset, and coordination mutation. The live pause/resume target is exactly one gameplay instance, identified by `<tenantId, gameInstanceId>`; regional pause/resume and regional remediation are target-only contracts, not live broad-scope controls. Any regional or aggregate operation must carry the owning service's explicit scope and fencing evidence rather than being inferred from an operator UI selection.

Target external session-lifecycle operator requests enter through Gateway and Logging & Admin. Logging & Admin validates the operator authorization reference at its receiving boundary, records the durable intent, and forwards the exact operation identity to Game Session owner RPCs; direct external exposure of Game Session's owner-local `/sessions*` hooks is not a substitute for this ingress family.

## Implementation Status

The current shipped scope is narrower than this target contract:

- The service exposes core admin/moderation, internal report-persistence, saga-inspection, and coordination-health surfaces, plus observability-backed integrations; it does not yet provide the complete target control-plane workflow for every recovery, regional, or aggregate operation.
- The moderation policy-input and audit persistence mutation is unavailable/gated through `POST /moderation/actions` and the internal `ApplyModerationAction` gRPC ingress; it currently does not persist `moderation_actions` records or apply Account, Game Session, or Social & Groups enforcement state. It remains unavailable until its action-family schema, shared cross-language `mutationDigest/v1` golden vectors, Account authorization-reference issuance, and Logging & Admin receiving-boundary validation/redemption are complete. Logging & Admin redeems the reference at its receiving boundary; the path does not require owner-side authorization-reference redemption or enforcement. The separate `EvaluateModerationPolicy` read remains live at the owner enforcement boundaries. Public administrative report persistence is unavailable: the HTTP controller and Gateway route were removed because canonical authorization and live reference validation are not implemented. The internal `CreateReport` gRPC ingress remains behind the existing mTLS/workload boundary; the caller-bound external/player submission route is also unavailable.
- Admission-pointer reads, audit, and prepared-upgrade proof reads are implemented and live. Version-upgrade preparation is an implemented operator-facing mutation but remains externally gated and unavailable pending its action-family schema, shared cross-language `mutationDigest/v1` golden vectors, and Account-issued authorization-reference issuance plus owner-side redemption; open/close/retarget CAS and cutover remain target-only and are not current endpoint capabilities.
- Per-instance tick pause/resume forwarding is implemented at the `<tenantId, gameInstanceId>` boundary, but the admin APIs and UI remain target/unavailable until the three-part mutation gate is complete: the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, and Account authorization-reference issuance plus owner-side redemption. Regional pause/resume and regional remediation remain target-only behavior, with no claim that the implemented instance control implicitly expands to a region or aggregate scope; they are tracked in [Game Session runtime and tick coordination](../../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#capability-status).
- Logging & Admin consumes Game Session health and requests Game Session-owned control APIs; it does not directly mutate Redis or runtime coordination state.
- Moderation policy input, evaluation, audit data, and operator history remain owned here, while Game Session and Social & Groups currently enforce the `GAMEPLAY_ADMISSION` and `CHAT_SEND` decisions for their respective scopes. Quota/limit override is hypothetical target UX only; no current Account owner mutation route exists.
- The moderation policy-input/audit mutation is unavailable/gated and currently does not persist policy input or audit evidence or represent a domain-owner enforcement mutation. `/moderation/actions` and `ApplyModerationAction` remain unavailable until the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, Account authorization-reference issuance, and Logging & Admin receiving-boundary validation/redemption exist. No owner-side authorization-reference redemption or enforcement is part of this path. Versioned snapshot/event propagation and broader enforcement remain gated or target work. Runtime owners call the live `EvaluateModerationPolicy` read at their enforcement boundaries.
- The core/observability availability split is a design contract; exact independent deployment and pool isolation remain implementation obligations rather than a claim that every backend integration is already isolated in production.

Operator write requests use one canonical logical `controlPlaneRequestId` across HTTP, Java/domain contracts, ADRs, and audit records. Protobuf contracts may use the wire spelling `control_plane_request_id`, which maps directly to that same identifier. Human requests use the typed Account `IssueHumanOperatorAuthorizationReference` path and require the current `control-ui` identity, the applicable tenant or target-tenant generation, and any required `privileged_control` predicate. Unattended automation uses the typed Account `IssueAutomationOperatorAuthorizationReference` path with `exact_mtls_workload_plus_versioned_automation_policy`, bound to the exact Logging and Admin workload mTLS identity, its `automation_policy_id`/`automation_policy_version`, and the exact tenant generation; the current automation branch is tenant-scoped and cannot authorize a global-role or `privileged_control` action family. Unattended authorization contains no user account, end-user token, or human identity, and neither path may substitute the other's identity.

Owner reconciliation for an expired `PENDING` claim or missing owner record is bounded and read-only: the current owner inspects the original request ID, digest, durable effect/outcome evidence, owner mutation marker, lease/fence, and target version/state within a fixed attempt and deadline. It does not call a business mutator, create new authorization, or replay a payload. A proven terminal commit may be recorded as committed; durable proof of no mutation may be `NOT_EXECUTED`; missing or conflicting evidence, including an absent Redis marker and absent terminal result, remains `PENDING`/indeterminate and non-replayable so recovery cannot double-apply a write.

In addition to log and moderation tooling, the service acts as a control-plane coordinator for tick and coordination health:

- Consumes metrics and health information published by the Game Session Service (for example, per-region status such as `HEALTHY`, `DEGRADED`, or `COORDINATION_UNTRUSTWORTHY`).
- Exposes admin APIs and UI controls to:
  - target pause or resume tick execution for the `<tenantId, gameInstanceId>` boundary; the admin APIs and UI remain unavailable until the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, and Account authorization-reference issuance plus owner-side redemption are all complete; regional pause/resume controls remain target-state behavior and are tracked against the current Game Session implementation status in [Game Session runtime and tick coordination](../../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#capability-status); and
  - request the scoped tick pause/resume operation through Game Session control APIs only after the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, and Account-issued authorization-reference issuance plus owner-side redemption are complete; broader reset/remediate actions are hypothetical target routes and remain unavailable until the owner API exists.
- Implements guarded automation that:
  - enumerates affected game instances from coordination-health signals and, once the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, and Account-issued authorization-reference issuance plus owner-side redemption are complete, calls Game Session `PauseTicksForScope`/`ResumeTicksForScope` for each exact `<tenantId, gameInstanceId>` scope; regional pause/resume and other regional mutations remain target-state only; and
  - may issue those conformant per-instance requests without requiring an operator to be present, while still emitting audit events for every action. Any broader safe remediation is target-state only. Unattended requests use the typed Account authorization path with the exact Logging and Admin mTLS identity plus the current versioned automation policy; they never synthesize a human `control-ui` token or operator identity.

Game Session remains the only service allowed to mutate gameplay coordination state or execute tick pause/resume behavior. Logging & Admin owns operator UX, automation policy, and audit only; it does not become the runtime state owner for remediation.

Operator and support diagnostics do not impersonate a player, attach to a live player session, or observe gameplay through a hidden actor. They use purpose-built, minimized support-safe reads, logs, dashboards, reports, moderation records, and explicit control-plane operations. Break-glass controls remain separately authorized and audited and must not create a player actor, gameplay session, or tenant-scoped gameplay capability.

- gRPC connections to this service require mTLS. JWT validation is required for admin or user-facing endpoints; internal gameplay and system calls are authenticated solely via mTLS.
- The security model uses JWT roles plus network-layer isolation: admin endpoints are reachable only through Gateway/internal management surfaces and namespace/network-policy controls, not direct public exposure.
- Canonical target-state admin APIs use role-based access control integrated with Account Service. Human operator writes require the current `control-ui` identity and Account's human authorization reference; unattended automation uses the exact Logging and Admin mTLS identity and Account's typed, versioned automation-policy authorization reference rather than a human token or identity. Current mutation paths that do not yet implement the applicable action-family/scope/digest and reference-redemption contract remain gated and unavailable rather than being treated as conformant because they perform a tenant-access check.
- Moderation data and log indices include a `tenantId` field so administrators only see information for the games they manage. Cross-tenant queries are rejected per the [Multi-Tenancy](../../system-architecture-multi-tenancy.md) strategy.
- The service utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Availability Partitioning

This service has two intentionally different availability classes:

- Core operator control plane: security-sensitive operator APIs whose availability must not depend on observability backends.
- Observability-backed experiences: embedded dashboards, log search, metric exploration, traces, and alert-centric investigations.

The core operator control plane must remain available when Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, or Alertmanager are degraded. Implementations should preserve this with independent readiness/degradation behavior, resource isolation, and defensive timeouts/circuit breakers around observability backends.

The architecture treats these as two runtime partitions even when they are delivered from one deployable:

- Currently live core control-plane endpoints include the synchronous `EvaluateModerationPolicy` read, saga inspection, and admission-pointer reads, audit, and prepared-upgrade proof reads. The moderation policy-input/audit mutation remains gated pending its action-family schema, shared cross-language `mutationDigest/v1` golden vectors, Account-issued authorization-reference issuance, and Logging & Admin receiving-boundary validation/redemption. Feature-flag mutation, admission open/close/retarget and cutover, version-upgrade preparation, and per-instance tick pause/resume remain gated or target-only pending their action-family schemas, shared cross-language `mutationDigest/v1` golden vectors, and Account authorization-reference issuance/redemption; regional or broader remediation remains target-only. When those gated paths become executable, they belong to the core control-plane partition and must not block on Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, or Alertmanager for request success. Public administrative `/reports` persistence is unavailable pending canonical checks; the internal gRPC report ingress is separate, and the target caller-bound player-submission surface is unavailable and is not a current player-facing data path. Quota override and versioned moderation propagation remain target families.
- Observability-backed endpoints include log search, embedded dashboards, traces, metric exploration, and alert investigation views. These paths may degrade independently or return explicit backend-unavailable states.
- Readiness and degradation reporting must distinguish these partitions so an observability outage does not mark the entire operator service unavailable.
- Thread pools, connection pools, and timeout budgets for observability integrations must be isolated from the core control plane so expensive search/dashboard failures cannot starve moderation or remediation requests.
- If a future implementation cannot preserve those guarantees inside one service boundary, the architecture should split the deployable into separate operator-control and observability surfaces rather than weakening the availability rule.

## Script Patch and Plugin Control-Plane Coordination

Logging & Admin provides the operator-facing audit and coordination layer around script patch and plugin changes:

- operators review automation or plugin state here and then invoke the owning control-plane APIs exposed by Game Design, Automation & Scripting, and Game Session;
- audit trails and operator intent for those changes are recorded here; and
- runtime mutation authority remains with the owning domain services rather than with Logging & Admin itself.

Logging & Admin does not write to Redis directly and does not define a competing script/plugin state-mutation API. It coordinates operator UX and audit around the documented service-owned APIs so operators can explain why automation behavior changed.

## Data Model

- `log_events` stores log data and is mirrored into Elasticsearch indexes for search.
- `moderation_actions` is the target durable record for accepted moderation policy input and audit evidence, with timestamps and a `tenant_id` column. The unavailable/gated `POST /moderation/actions` and `ApplyModerationAction` paths do not currently persist this record and remain unavailable pending the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, Account-issued authorization-reference issuance, and Logging & Admin receiving-boundary validation/redemption; owner-side authorization-reference redemption and enforcement records, snapshots, and propagation remain separate target work.
- `player_reports` stores abuse and bug reports submitted through the internal report-persistence seam, with a `tenant_id` column; public administrative and target caller-bound external/player submission remain unavailable.
- Runtime feature-flag truth is owned by Game Session. After the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, and Account-issued authorization-reference issuance plus owner-side redemption gate passes, Logging & Admin records operator intent and audit context for feature-flag requests, then forwards the mutation to Game Session rather than maintaining a competing `feature_flag` runtime table.

## Moderation Workflow

- Operators review flagged logs through the web UI.
- The unavailable/gated `POST /moderation/actions` path currently persists neither policy input nor audit evidence and does not invoke an owner-side enforcement RPC. It must not be described as the target ban/mute mutation until the action-family schema, shared cross-language `mutationDigest/v1` golden vectors, Account-issued authorization-reference issuance, and Logging & Admin receiving-boundary validation/redemption exist.
- A future target path propagates versioned moderation snapshots/events to the current enforcement owners, replacing repeated synchronous reads where appropriate without moving runtime enforcement authority out of Game Session or Social & Groups.
- Target-state enforcement follows the ban taxonomy:
  - `account_security_ban` events would be applied by Account Service.
  - `gameplay_ban` events would be enforced by Game Session Service.
  - `chat_mute` and `chat_ban` events would be enforced by Social & Groups Service.

When enabled, moderation actions are audit-recorded for compliance.

## Saga Dashboard

The service exposes `/sagas` and `/sagas/{id}/steps` endpoints for operators to inspect short synchronous orchestration coordinated via the shared Saga library. Durable long-running workflow inspection belongs on the corresponding Temporal adopter read surfaces, not on the shared saga dashboard. The dashboard reads from the `saga_instance` and `saga_step` tables and publishes a `sagas.active` Prometheus gauge.

See [Transaction Strategies](../../system-architecture-transactions.md) for an overview of Saga usage across FireMUD.

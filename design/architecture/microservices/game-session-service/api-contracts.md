# Game Session Service API Contracts

## Implementation Notes

This document mixes live and target-state control-plane surfaces. Current live behavior is narrower:

- the shipped pause/resume control path is `PauseTicksForScope` / `ResumeTicksForScope` at the current `{tenantId, gameInstanceId}` runtime boundary;
- the shipped owner/status read is `GetRuntimeOwnershipStatus`, not yet the fuller target-state `GetRegionTickStatus` surface described below;
- canonical region pause/status remains incomplete for maintenance, reset, migration, and future scoped recovery. Routine online PostgreSQL backup does not depend on this control; player-facing restore remains blocked instead on the environment-wide cold-start quarantine, convergence, hardening, and proof gaps.

Read the pause/status/recovery APIs below as the target-state contract unless the repo implementation or slice docs explicitly say they are already live.

## Service Interactions

Game Session communicates with other platform services exclusively via gRPC for gameplay-domain work. Player-delegated calls carry the typed unsigned `PlayerExecutionContext` defined in the authentication architecture, including the required gameplay scope for admission. Downstream services authenticate the concrete mTLS caller, enforce an exact RPC caller allowlist, validate context/request equality and domain scope, and reject client-supplied identity metadata. Routine gameplay calls do not use signed per-action attestations or a generic replay cache; mutations retain their owning command/effect/request idempotency.

It also communicates game lifecycle changes to other microservices over gRPC so they can react to game instances starting, stopping, or changing runtime configuration.

## Session Front-End and Lease-Owner Routing

Game Session deliberately separates socket ownership from region execution ownership:

- The pod holding a player's WebSocket or proxied Telnet bridge is the session front-end for that gameplay session.
- Region-scoped command execution belongs to the current lease owner for the target `<tenantId, gameInstanceId, regionId>`.
- Session front-ends may authenticate, normalize input, manage connection-local state, and stream results to the client.
- Session front-ends must not directly stage or commit tick-owned Redis mutations for regions they do not lease.
- In the target-state model, when a command or follow-up targets a region owned by another pod, the session front-end forwards the request over internal gRPC to the lease owner and returns the resulting output to the client.
- Current-live fallback: the existing `EnqueueCommand` path resolves the session's current `{tenantId, gameInstanceId}` queue target and runtime ownership, persists the command, and uses the local Game Session tick-queue path. It does not expose a dedicated cross-pod forwarding RPC; if runtime ownership is missing or paused, admission rejects the command rather than performing an unfenced handoff.

### Forwarding contract

The internal front-end to lease-owner path is a **target-state** fenced gameplay contract, not a best-effort proxy hop. The live proto and Game Session implementation do not yet expose this forwarding RPC; the current-live fallback is the existing local `EnqueueCommand` admission path described above. [GR-1.1](../../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md) tracks that gap.

- Forwarded requests include `tenantId`, `gameInstanceId`, `playableStateScope`, `sessionId`, `characterId`, target `regionId`, command/action identifier, and a monotonic per-session sequencing token.
- Forwarded requests include the current region lease/epoch fence. Lease owners reject stale or missing fences with an application-level stale-lease response rather than silently executing.
- The session front-end preserves per-connection FIFO when emitting forwarded work. Cross-connection ordering remains undefined during takeovers as described in the reconnection and protocol-bridging docs.
- If the lease owner rejects a stale fence before execution, the front-end refreshes ownership and may retry the request once against the new lease owner when the request is still valid.
- If forwarding fails after the executor may already have started, the front-end must treat the result as ambiguous and use the normal structured command-failure or reconnect path; it must not re-issue potentially mutating work without an idempotency guarantee.
- All forwarded execution attempts and stale-lease rejections must emit dedicated metrics and traces so operators can distinguish edge socket health from region-executor health.

## gRPC APIs

- `Ping` – basic connectivity check.
- `StartSession` – spins up a game instance from a template-driven launch descriptor. Despite the name, this operates on game instances, not player gameplay sessions; gameplay sessions are per-player contexts backed by `session:game:*` keys.
- `StopSession` – stops a running game instance.
- `RestartSession` – restarts a stopped game instance.
- `EnqueueCommand` – adds a player action to the next tick's queue.
- `QueryState` – retrieves condensed session or player state for monitoring.
- `ToggleFeatureFlag` – updates runtime flags for a tenant.
- `PauseTicksForScope` – current scoped control-plane RPC for halting tick execution at the `{tenantId, gameInstanceId}` runtime boundary. The legacy unscoped `PauseTicks` method is not the canonical operator path.
- `ResumeTicksForScope` – current scoped control-plane RPC for resuming tick execution at the `{tenantId, gameInstanceId}` runtime boundary. The legacy unscoped `ResumeTicks` method is not the canonical operator path.
- `GetRuntimeOwnershipStatus` – current owner/status read for the shipped runtime boundary. `GetRegionTickStatus` is the fuller target-state per-region surface for maintenance, reset tooling, topology changes, and future scoped-recovery gates.
- `ValidateInstanceCutoverCompatibility` – resolves the target replacement launch descriptor, freezes any approved `remapSetId`, checks target Game Design proof, and returns the canonical multi-participant cutover-preflight report for a source instance and target version.
- `PrepareVersionUpgrade` – persists one `prepared_version_upgrade` control-plane artifact containing the target launch-descriptor identity, frozen `remapSetId`, participant results, and checked-at timestamp for a source-instance -> target-version cutover attempt. The request must include `controlPlaneRequestId` so retries reuse the same durable preparation instead of creating duplicates.
- `ExecutePreparedVersionCutover` – executes the canonical prepared cutover workflow for one realm pointer. The request identifies the realm, replacement `gameInstanceId`, durable `preparedVersionUpgradeId`, and the expected pointer version; Game Session revalidates the durable preparation against the current pointer and target instance before performing a real database CAS. The pointer, audit event, request replay outcome, preparation execution state, source instance, unique `sourceDrainId`, resolved drain-policy version/duration, and absolute `sourceDrainDeadlineAt` commit atomically. Before any database CAS, Game Session resolves and validates the effective `firemud.game-session.cutover-drain.duration-ms` in the inclusive range `0..300000`; an invalid platform or tenant/game value rejects the request without changing the pointer. The duration defaults to five minutes and may be shortened or disabled by tenant/game policy. A disabled/zero drain persists `sourceDrainDeadlineAt=null`, marks the drain complete in the same logical outcome, and requests the idempotent `InstanceTermination` workflow immediately while retaining `sourceDrainId` and execution evidence for replay.
- `GetPreparedVersionUpgrade` returns execution state once a prepared cutover has run: the replacement and source `gameInstanceId` values, resulting pointer version, execution timestamp/request id, `sourceDrainId`, and nullable `sourceDrainDeadlineAt` are frozen onto the durable preparation artifact in the same logical commit as the route swap. Game Session exposes active drain state until early-empty completion or deadline-enforced transition to the idempotent `InstanceTermination` workflow; a disabled drain reports completed state and the immediate termination request instead.
- The prepared-version cutover commit must also persist one idempotent `InstanceTermination` outbox/work item, keyed by the stable source instance and `sourceDrainId`, in the same atomic database outcome as the pointer swap and zero-drain completion. A zero-drain cutover therefore completes the pointer and termination intent together; a non-zero drain records the work item as pending until pointer-driven drain completion.
- A dispatcher reconciles the durable work item after commit and retries delivery until the target instance acknowledges termination. Lost responses, dispatcher restarts, and duplicate deliveries are recovered by the stable work-item identity and acknowledgement state; the cutover result is not reported as fully completed merely because an in-memory termination request was sent.
- `EnqueueAutomationCommandIfAbsent` – internal Automation & Scripting handoff API that records or replays a durable automation dispatch row keyed by `(tenantId, gameInstanceId, regionId, regionEpoch, automationDispatchId)` before staging the generated command into the normal Game Session tick queue. Duplicate dispatches return `DUPLICATE_NOOP` with the existing `commandId`; new dispatches return `ENQUEUED` after the command ledger row is staged. New dispatches must first match Game Session's current durable runtime ownership row: missing ownership returns `OWNERSHIP_UNAVAILABLE`, stale `regionEpoch` returns `STALE_TIMELINE`, and paused ownership returns `RUNTIME_PAUSED` without staging into Redis. `dueTickId` is still carried for scheduler/timer-derived automation, but immediate event-driven automation may omit it and rely on immediate enqueue semantics.
- `GetGameplayCommandStatus` – returns the durable command status by `commandId`, including accepted/staged/completed timestamps, attempt count, failure code/message, source type, automation dispatch/work-item ids, script/plugin provenance, target entity, `regionId`, `regionEpoch`, `dueTickId`, and the full remote origin/target game-instance plus region scope when the command has entered the cross-region follow-up path. That same status read now also exposes the durable remote follow-up contract itself when present: follow-up status, payload kind, requested command, solo-tick requirement, origin-source tuple, target entity/effect/failure summary, and trigger-script-event identity. The automation handoff identity `(tenantId, gameInstanceId, regionId, regionEpoch, automationDispatchId)` is a target-state lookup; it is carried by the proto but is not implemented by the live status resolver.
- `ListRemoteCommandCoordinators` – returns bounded remote coordinator rows for a tenant, filterable by persisted origin/target game-instance and region scope, state, linked `followupId`, script/plugin/dispatch/command provenance, the admitted routing bundle (`playableStateScope`, `worldSlug`, `realmSlug`, `pointerVersion`), linked followup target-leg traits (`targetEntityId`, `effectKey`, `payloadKind`, `originSourceKind`, `automationWorkItemId`), and first-class trigger-event identity (`eventType`, `scriptEventId`) instead of requiring point lookup by one coordinator id.
- `ListRemoteFollowups` – returns bounded remote target-leg rows for a tenant, filterable by persisted target scope, origin scope, followup status/id, script/plugin/dispatch/command provenance, the admitted routing bundle, current live payload/source classifiers (`payloadKind`, `originSourceKind`), direct target-leg identity/terminal fields (`automationWorkItemId`, `targetEntityId`, `effectKey`, `failureCode`, `requiresSoloTick`), and first-class trigger-event identity (`eventType`, `scriptEventId`) instead of requiring operators to scrape one entire target-region stream.
- `ListRemoteFollowupResults` – returns bounded origin-addressed remote result rows for a tenant, filterable by coordinator/followup identity, origin/target game-instance and region scope, outcome, script/plugin/dispatch/command provenance, the admitted routing bundle, and durable result identity/failure fields (`resultErrorCode`, `resultCommandId`, `automationWorkItemId`) instead of requiring operators to know one exact coordinator id up front.
- `ScheduleRemoteFollowup` – now accepts first-class trigger-script-event authority too (`eventType`, `eventSchemaVersion`, `scriptEventId`, `triggerMode`, `readSnapshotToken`, `eventPayloadJson`) so that the `trigger_script_event` family persists and replays from durable columns instead of payload-blob-only identity.
- `GetPinnedScriptPatchVersion` now returns the persisted pin `controlPlaneRequestId` as well as the pinned patch, timestamp, and actor.
- `GetGameSessionPinConvergence` – reports the persisted Game Session-side pin observation for `(tenantId, gameInstanceId)`: `observedPinnedScriptPatchVersion`, `lastObservedControlPlaneRequestId`, and `observedAt`.
- `GetGameInstanceRuntimeState` now exposes the explicit `currentAdmissionPointers[]` list for the current runtime target. The legacy singular runtime routing fields (`playableStateScope`, `worldSlug`, `realmSlug`, `pointerVersion`) remain populated only when exactly one current admission pointer targets that runtime; when multiple pointers do, those singular fields intentionally fail closed to blank/unspecified values instead of implying one canonical reverse-mapped realm identity.
- `SetAdmissionPointer` represents `OPEN(gameInstanceId)` or `CLOSED`. Existing records require `expectedPointerVersion`; creation uses the equivalent of version `0`. A target change must carry `preparedVersionUpgradeId`, and Game Session rejects the swap unless the durable preparation is `COMPATIBLE` and still matches the current source instance plus the target instance's frozen `versionId`, `launchDescriptorId`, and `remapSetId`.
- Admission-pointer requests and read/audit responses carry the runtime route version separately from catalog/policy revision. Public-production admission and first-join membership creation consume current catalog visibility/public-production facts with entitlement checks instead of inferring behavior from `realmSlug`; display-only changes do not become runtime cutovers.
- Admission-pointer audit/list responses now also expose the `preparedVersionUpgradeId` used by a cutover write so operator history preserves the same proof identity that the mutation validated.

### ADR-0048 operator-write contract

**Target state:** every mutating operator RPC listed above (`StartSession`, `StopSession`, `RestartSession`, `ToggleFeatureFlag`, `PauseTicksForScope`, `ResumeTicksForScope`, `SetAdmissionPointer`, `ExecutePreparedVersionCutover`, and `PrepareVersionUpgrade`) requires the same durable request identity: `controlPlaneRequestId` plus the canonical `mutationDigest`. Account binds both values into the bounded operator authorization reference, and Game Session recomputes and validates the digest before execution. The owner durably records the request before execution and records a terminal result only after owner-specific commit proof; PostgreSQL-owned mutations commit domain state and result atomically, while Redis-backed projections use the durable claim, fenced mutation marker, and reconciliation contract in ADR 0048. A duplicate request carrying the same digest returns the previously committed result, while the same request identifier with a different digest returns `IDEMPOTENCY_CONFLICT` without applying either payload. A timeout must not create a new request identifier; Logging & Admin may use the same identifier for owner result lookup and, only while the original authorization remains valid, redelivery. After authorization expiry, reconciliation is read-only.

The current proto and admission-pointer mutation flow remain incomplete against this target. `StartSessionRequest`, `PauseTicksForScopeRequest`, `ResumeTicksForScopeRequest`, and `GameplayAdmissionPointerMutation` for `SetAdmissionPointer` carry `controlPlaneRequestId` but not `mutationDigest`; those operations must not be reported as ADR-0048-complete until the missing request-field propagation, authorization binding, owner-side digest verification, replay persistence, and focused proof converge together.

Service definitions reside in [../../../../protos/game-session/v1](../../../../protos/game-session/v1). Run `./gradlew generateProto` after modifying these files to regenerate stubs. The generated classes appear under `net.firedevops.firemud.gamesession.v1` in `build/generated/sources/proto/main/{grpc,java}` and are wired into `services/game-session-service/src/main/java/net/firedevops/firemud/service/impl/GameSessionGrpcService.java`.

### REST endpoints

- `GET /ping` – basic health check returning `"pong"`.
- `POST /sessions` – operator/bootstrap convenience for creating a new game instance from a template-driven launch attempt. This is not the canonical player gameplay-admission seam and does apply the same launch-descriptor preflight seam as the gRPC `StartSession` path.
- `POST /sessions/{id}/stop` – stop a running session.
- `POST /sessions/{id}/restart` – restart a stopped session.
- `POST /sessions/{id}/refresh-roles` – refresh the player's roles for an active session.

Use `/sessions/{id}/refresh-roles` after updating an account's privileges so the session reflects the latest role assignments.

#### External HTTP route classification

Game Session owns the `/api/session/**` Gateway family, but that family is not a blanket public-write contract. The current public gateway inventory exposes only `GET /api/session/ping`; the mutating `/sessions*` routes are legacy service-local REST hooks retained by the current OpenAPI surface, protected by privileged HTTP auth on the service itself, and not part of the public gateway allowlist. They are not the canonical external operator ingress or player-admission contract.

| Service-local route | External classification | Notes |
| --- | --- | --- |
| `GET /ping` | Infra/local health only | Not part of the external admin/product contract. |
| `POST /sessions` | `legacy_service_local_operator_hook`; exact `control-ui` profile plus `privileged_control_when_global_role` assurance at the current service-local boundary | Current OpenAPI hook only. Direct edge exposure is denied; the canonical external operator ingress is Logging & Admin, not this REST route, and this is not a player admission route. |
| `POST /sessions/{id}/stop` | `legacy_service_local_operator_hook`; exact `control-ui` profile plus `privileged_control_when_global_role` assurance at the current service-local boundary | Current OpenAPI hook only. Direct edge exposure is denied; the canonical external operator ingress is Logging & Admin. |
| `POST /sessions/{id}/restart` | `legacy_service_local_operator_hook`; exact `control-ui` profile plus `privileged_control_when_global_role` assurance at the current service-local boundary | Current OpenAPI hook only. Direct edge exposure is denied; the canonical external operator ingress is Logging & Admin. |
| `POST /sessions/{id}/refresh-roles` | `legacy_service_local_maintenance_hook`; exact `control-ui` profile at the current service-local boundary | Current OpenAPI maintenance hook only. Direct edge exposure is denied; it is not a canonical player or external operator route. |

If a future change wants any of the mutating `/sessions*` routes to be callable directly from external operator tools, the owning contract must explicitly mark that exact route as bypass-safe and explain its auth class, audit behavior, and lease-owner forwarding rules in the same change.

#### Target canonical external operator path

The canonical external operator flow below is target-state and is not currently routable. Current behavior remains the privileged service-local `/sessions*` hooks above with direct edge exposure denied; there is no fallback external Logging & Admin mutation route. Once enabled, external operator mutations enter through the Logging & Admin Service via Gateway. Logging & Admin authenticates the `control-ui` session, records the durable intent and audit context, and calls Account's canonical [`IssueHumanOperatorAuthorizationReference`](../account-service/api-contracts.md#operator-authorization-references) contract; the separately typed automation path is restricted as that Account contract defines. Logging & Admin then forwards the bounded reference and the structured non-authoritative reason, request identity, and target-scope fields to the Game Session owner RPC over its exact mTLS workload identity. If generation or predicate fields are forwarded unchanged, they are audit-only context: Game Session must exclude those forwarded copies from authorization decisions and the mutation digest, or omit them entirely. It may forward the end-user `control-ui` JWT to Account only for human reference issuance; it must never forward an end-user JWT or caller-asserted actor identity to Game Session. Game Session recomputes the mutation digest and calls Account's `RedeemOperatorAuthorization` contract with the exact owner, action, scope, request identity, and digest. Account's returned bounded actor or automation-policy authorization projection is the sole authorization source; Game Session uses that Account-derived projection for audit while independently validating target-domain ownership, runtime fences, and owner-side idempotency.

#### Owner-side operator RPC classification

The gRPC owner methods `StartSession`, `RestartSession`, `StopSession`, `ToggleFeatureFlag`, `PauseTicksForScope`, `ResumeTicksForScope`, `SetAdmissionPointer`, `ExecutePreparedVersionCutover`, and `PrepareVersionUpgrade` are the target `internal_workload` operator RPC family. They accept the exact Logging & Admin mTLS workload identity and one opaque, short-lived Account-issued operator authorization reference for the bounded request, redeemed with Account; they accept no end-user JWT or caller-asserted actor authority. Once converged, every method uses the ADR-0048 request identity and result-replay rules above. `StartSession`, `RestartSession`, and `StopSession` additionally require reason and their target scope; each other mutating method requires the equivalent method-specific scope and precondition fields. Every owner obtains the authoritative actor projection from Account redemption and uses it for audit. Game Session still validates current domain ownership, admission/CAS or runtime-fence facts, and durable request idempotency before committing the owner result. `RefreshRoles` is also `internal_workload`, but uses only its exact allowlisted workload identity plus current session and Account role state because it is a role-refresh operation rather than delegated operator authority.

```bash
curl http://localhost:8086/ping
```

```bash
curl http://localhost:8080/api/session/ping
```

The following direct REST example exercises the current legacy service-local hook for local development only; it is not the canonical external operator path:

To start a session via REST:

```bash
curl -X POST http://localhost:8086/sessions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <control-ui JWT with a current global privileged role and required assurance>' \
  -d '{"tenantId":42,"gameTemplateId":7,"controlPlaneRequestId":"cp-req-1001","ownerAccountId":1001}'
```

### gRPC examples

The plaintext commands below are current-contract, local-development examples only. Their numeric `tenantId`, `gameTemplateId`, and `ownerAccountId` values intentionally match the current DTO/OpenAPI contract; a future UUID-shaped example would be target-state only and must be labeled separately. Shared and player-facing environments must use the configured mTLS trust bundle and workload certificate, replacing `-plaintext` with `-cacert`, `-cert`, and `-key` arguments as shown in the [gRPC architecture](../../system-architecture-grpc.md).

```bash
grpcurl -plaintext localhost:6565 game_session.v1.GameSessionService/Ping
```

```bash
grpcurl -plaintext -d '{"tenantId":42,"gameTemplateId":7,"controlPlaneRequestId":"cp-req-1001","ownerAccountId":1001}' \
  localhost:6565 game_session.v1.GameSessionService/StartSession
```

`StartSession` now requires one control-plane launch attempt identity instead of trusting caller-supplied runtime-version and patch fields:

- request fields:
  - `tenantId`
  - `gameTemplateId`
  - `controlPlaneRequestId`
  - `ownerAccountId`
  - optional `clientIp`
- behavior:
  - Game Session must call Game Design `ResolveLaunchDescriptor(...)` before creating any `gameInstanceId` row
  - Game Session must then read `GetPublishedReleaseBundle(tenantId, versionId)` and fail closed if the attested bundle does not match the resolved descriptor
  - Game Session must then re-read authoritative `GetVersionState(tenantId, versionId)` and fail closed on non-activation-eligible state or `versionStateEpoch` mismatch
  - successful `game_instances` rows persist the resolved `gameTemplateId`, `launchDescriptorId`, `versionId`, `releaseBundleId`, `versionStateEpoch`, and `generationConfigRevision`

## Command Front Door Ownership

Game Session owns the gameplay session front door and the split between protocol-level system commands and queued gameplay work:

- System commands such as `LOGIN`, `LOGON`, `PING`, and lightweight state queries are fully owned by Game Session and may complete synchronously without enqueuing gameplay work.
- Gameplay commands such as `LOOK`, `SAY`, movement, and combat are validated and normalized by Game Session, then forwarded to the tick/gameplay path. Game Session does not re-implement gameplay rules for these commands.
- If a command would produce both immediate text and enqueue metadata, enqueue failure wins. Game Session returns a single `ERROR` response instead of reporting success and silently dropping gameplay work.

For the player-visible line protocol and examples, see [`protocols.md`](./protocols.md).

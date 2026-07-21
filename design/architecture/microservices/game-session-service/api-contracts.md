# Game Session Service API Contracts

## Implementation Notes

This document mixes live and target-state control-plane surfaces. Current live behavior is narrower:

- the shipped pause/resume control path is `PauseTicksForScope` / `ResumeTicksForScope` at the current `{tenantId, gameInstanceId}` runtime boundary;
- the shipped owner/status read is `GetRuntimeOwnershipStatus`, not yet the fuller target-state `GetRegionTickStatus` surface described below;
- canonical region pause/status remains incomplete for maintenance, reset, migration, and future scoped recovery. Routine online PostgreSQL backup does not depend on this control; player-facing restore remains blocked instead on the environment-wide cold-start quarantine, convergence, hardening, and proof gaps.

Read the pause/status/recovery APIs below as the target-state contract unless the repo implementation or slice docs explicitly say they are already live.

## Service Interactions

Game Session communicates with other platform services exclusively via gRPC for gameplay-domain work. Player-delegated calls carry the typed unsigned `PlayerExecutionContext` defined in the authentication architecture, including the required admitted gameplay scope. Downstream services authenticate the concrete mTLS caller, enforce an exact RPC caller allowlist, validate context/request equality and domain scope, and reject client-supplied identity metadata. Routine gameplay calls do not use signed per-action attestations or a generic replay cache; mutations retain their owning command/effect/request idempotency.

It also communicates game lifecycle changes to other microservices over gRPC so they can react to game instances starting, stopping, or changing runtime configuration.

## Session Front-End and Lease-Owner Routing

Game Session deliberately separates socket ownership from region execution ownership:

- The pod holding a player's WebSocket or proxied Telnet bridge is the session front-end for that gameplay session.
- Region-scoped command execution belongs to the current lease owner for the target `<tenantId, regionId>`.
- Session front-ends may authenticate, normalize input, manage connection-local state, and stream results to the client.
- Session front-ends must not directly stage or commit tick-owned Redis mutations for regions they do not lease.
- When a command or follow-up targets a region owned by another pod, the session front-end forwards the request over internal gRPC to the lease owner and returns the resulting output to the client.

### Forwarding contract

The internal front-end to lease-owner path is a fenced gameplay contract, not a best-effort proxy hop:

- Forwarded requests include `tenantId`, `gameInstanceId`, `sessionId`, active `frontEndGeneration`, `characterId`, target `regionId`, stable `commandId`, `sessionSequence`, and the expected executor fence.
- Owner-directory or lease-projection data is a routing hint. The receiving executor validates current ownership and fence at admission and rejects stale or missing authority rather than silently executing.
- `sessionSequence` preserves FIFO within one live connection generation only. `commandId` and effect/request identities provide dedupe and retry safety; sequencing is not an idempotency key or distributed transaction fence.
- A stale-owner rejection before admission may refresh routing and retry the same logical identity. If execution may have started, the front-end reconciles that same `commandId` through `GetGameplayCommandStatus` and never invents a replacement identity.
- Cross-region movement converges through the authoritative spatial/effect result. The front-end updates its execution-region pointer only after that durable result; it does not coordinate a separate source-release/destination-accept transaction.
- Asynchronous output targets the active `{sessionId, frontEndGeneration}` registration. Superseded generations cannot deliver to the replacement controller.
- Forwarding, in-flight work, retries, output buffering, and route refresh are bounded and expose saturation, timeout, stale-fence, ambiguous-result, and generation-mismatch metrics.

## gRPC APIs

- `Ping` – basic connectivity check.
- `StartSession` – spins up a game instance from a template-driven launch descriptor. Despite the name, this operates on game instances, not player gameplay sessions; gameplay sessions are per-player contexts backed by `session:game:*` keys.
- `StopSession` – stops a running game instance.
- `RestartSession` – restarts a stopped game instance.
- `EnqueueCommand` – adds a player action to the next tick's queue.
- `QueryState` – retrieves condensed session or player state for monitoring.
- `ToggleFeatureFlag` – updates runtime flags for a tenant.
- `PauseTicks` – temporarily halt tick execution for maintenance, reset, migration, or future scoped-recovery work.
- `ResumeTicks` – resume tick processing after the authorized maintenance workflow completes.
- `GetRegionTickStatus` – returns the canonical per-region pause/status surface for maintenance, reset tooling, topology changes, and future scoped-recovery gates.
- `ValidateInstanceCutoverCompatibility` – resolves the target replacement launch descriptor, freezes any approved `remapSetId`, checks target Game Design proof, and returns the canonical multi-participant cutover-preflight report for a source instance and target version.
- `PrepareVersionUpgrade` – persists one `prepared_version_upgrade` control-plane artifact containing the target launch-descriptor identity, frozen `remapSetId`, participant results, and checked-at timestamp for a source-instance -> target-version cutover attempt. The request must include `controlPlaneRequestId` so retries reuse the same durable preparation instead of creating duplicates.
- `ExecutePreparedVersionCutover` – executes the canonical prepared cutover workflow for one realm pointer. The request identifies the realm, replacement `gameInstanceId`, durable `preparedVersionUpgradeId`, and required expected pointer version; Game Session revalidates the durable preparation against the current pointer and target instance before performing a real database CAS. The pointer, audit event, request replay outcome, and preparation execution state commit atomically.
- `GetPreparedVersionUpgrade` returns execution state once a prepared cutover has run: the replacement `gameInstanceId`, resulting pointer version, execution timestamp, and execution request id are frozen onto the durable preparation artifact in the same logical commit as the route swap.
- `EnqueueAutomationCommandIfAbsent` – internal Automation & Scripting handoff API that records or replays a durable automation dispatch row keyed by `(tenantId, gameInstanceId, regionId, regionEpoch, automationDispatchId)` before staging the generated command into the normal Game Session tick queue. Duplicate dispatches return `DUPLICATE_NOOP` with the existing `commandId`; new dispatches return `ENQUEUED` after the command ledger row is staged. New dispatches must first match Game Session's current durable runtime ownership row: missing ownership returns `OWNERSHIP_UNAVAILABLE`, stale `regionEpoch` returns `STALE_TIMELINE`, and paused ownership returns `RUNTIME_PAUSED` without staging into Redis. `dueTickId` is still carried for scheduler/timer-derived automation, but immediate event-driven automation may omit it and rely on immediate enqueue semantics.
- `GetGameplayCommandStatus` – returns the durable command status either by `commandId` or by automation handoff identity `(tenantId, gameInstanceId, regionId, regionEpoch, automationDispatchId)`, including accepted/staged/completed timestamps, attempt count, failure code/message, source type, automation dispatch/work-item ids, script/plugin provenance, target entity, `regionId`, `regionEpoch`, `dueTickId`, and the full remote origin/target game-instance plus region scope when the command has entered the cross-region follow-up path. That same status read now also exposes the durable remote followup contract itself when present: followup status, payload kind, requested command, solo-tick requirement, origin-source tuple, target entity/effect/failure summary, and trigger-script-event identity.
- `ListRemoteCommandCoordinators` – returns bounded remote coordinator rows for a tenant, filterable by persisted origin/target game-instance and region scope, state, linked `followupId`, script/plugin/dispatch/command provenance, the admitted routing bundle (`playableStateScope`, `worldSlug`, `realmSlug`, `pointerVersion`), linked followup target-leg traits (`targetEntityId`, `effectKey`, `payloadKind`, `originSourceKind`, `automationWorkItemId`), and first-class trigger-event identity (`eventType`, `scriptEventId`) instead of requiring point lookup by one coordinator id.
- `ListRemoteFollowups` – returns bounded remote target-leg rows for a tenant, filterable by persisted target scope, origin scope, followup status/id, script/plugin/dispatch/command provenance, the admitted routing bundle, current live payload/source classifiers (`payloadKind`, `originSourceKind`), direct target-leg identity/terminal fields (`automationWorkItemId`, `targetEntityId`, `effectKey`, `failureCode`, `requiresSoloTick`), and first-class trigger-event identity (`eventType`, `scriptEventId`) instead of requiring operators to scrape one entire target-region stream.
- `ListRemoteFollowupResults` – returns bounded origin-addressed remote result rows for a tenant, filterable by coordinator/followup identity, origin/target game-instance and region scope, outcome, script/plugin/dispatch/command provenance, the admitted routing bundle, and durable result identity/failure fields (`resultErrorCode`, `resultCommandId`, `automationWorkItemId`) instead of requiring operators to know one exact coordinator id up front.
- `ScheduleRemoteFollowup` – now accepts first-class trigger-script-event authority too (`eventType`, `eventSchemaVersion`, `scriptEventId`, `triggerMode`, `readSnapshotToken`, `eventPayloadJson`) so that the `trigger_script_event` family persists and replays from durable columns instead of payload-blob-only identity.
- `GetPinnedScriptPatchVersion` returns the persisted exact `(scriptPatchVersion, scriptPinEpoch)`, `controlPlaneRequestId`, timestamp, and actor.
- `GetGameSessionPinConvergence` – reports the persisted Game Session-side exact pin observation for `(tenantId, gameInstanceId)`: `observedPinnedScriptPatchVersion`, `observedScriptPinEpoch`, `lastObservedControlPlaneRequestId`, and `observedAt`.
- `ListScriptPatchRolloutHistory` – returns bounded, paginated Game Session-authoritative pin, rollback, and repin history keyed idempotently by `controlPlaneRequestId`, including previous and resulting exact pin tuples, operation, actor/reason, outcome, and commit time.
- `GetGameInstanceRuntimeState` now exposes the explicit `currentAdmissionPointers[]` list for the current runtime target. The legacy singular runtime routing fields (`playableStateScope`, `worldSlug`, `realmSlug`, `pointerVersion`) remain populated only when exactly one current admission pointer targets that runtime; when multiple pointers do, those singular fields intentionally fail closed to blank/unspecified values instead of implying one canonical reverse-mapped realm identity.
- `SetAdmissionPointer` represents `OPEN(gameInstanceId)` or `CLOSED`. Existing records require `expectedPointerVersion`; creation uses the equivalent of version `0`. A target change must carry `preparedVersionUpgradeId`, and Game Session rejects the swap unless the durable preparation is `COMPATIBLE` and still matches the current source instance plus the target instance's frozen `versionId`, `launchDescriptorId`, and `remapSetId`.
- Admission-pointer requests and read/audit responses carry the runtime route version separately from catalog/policy revision. Public-production admission and first-join membership creation consume current catalog visibility/public-production facts with entitlement checks instead of inferring behavior from `realmSlug`; display-only changes do not become runtime cutovers.
- Admission-pointer audit/list responses now also expose the `preparedVersionUpgradeId` used by a cutover write so operator history preserves the same proof identity that the mutation validated.

Service definitions reside in [../../../../protos/game-session/v1](../../../../protos/game-session/v1). Run `./gradlew generateProto` after modifying these files to regenerate stubs. The generated classes appear under `net.firedevops.firemud.gamesession.v1` in `build/generated/sources/proto/main/{grpc,java}` and are wired into `services/game-session-service/src/main/java/net/firedevops/firemud/service/impl/GameSessionGrpcService.java`.

### REST endpoints

- `GET /ping` – basic health check returning `"pong"`.
- `POST /sessions` – operator/bootstrap convenience for creating a new game instance from a template-driven launch attempt. This is not the canonical player gameplay-admission seam and does apply the same launch-descriptor preflight seam as the gRPC `StartSession` path.
- `POST /sessions/{id}/stop` – stop a running session.
- `POST /sessions/{id}/restart` – restart a stopped session.
- `POST /sessions/{id}/refresh-roles` – refresh the player's roles for an active session.

Use `/sessions/{id}/refresh-roles` after updating an account's privileges so the session reflects the latest role assignments.

#### External HTTP route classification

Game Session owns the `/api/session/**` Gateway family, but that family is not a blanket public-write contract. The current public gateway inventory exposes only `GET /api/session/ping`; the mutating `/sessions*` routes remain owner-side operator hooks protected by privileged HTTP auth on the service itself and are not part of the public gateway allowlist.

| Service-local route | External classification | Notes |
| --- | --- | --- |
| `GET /ping` | Infra/local health only | Not part of the external admin/product contract. |
| `POST /sessions` | Internal-only or Logging & Admin-mediated operator write until a dedicated bypass-safe design says otherwise | This is a control-plane instance lifecycle mutation, not a player admission route. |
| `POST /sessions/{id}/stop` | Internal-only or Logging & Admin-mediated operator write | Stops runtime state and therefore follows the operator-write ingress policy by default. |
| `POST /sessions/{id}/restart` | Internal-only or Logging & Admin-mediated operator write | Same classification as stop/start lifecycle mutations. |
| `POST /sessions/{id}/refresh-roles` | Internal-only maintenance path | Used to refresh session/runtime auth context after account-role changes; not a documented external bypass-safe write. |

If a future change wants any of the mutating `/sessions*` routes to be callable directly from external operator tools, the owning contract must explicitly mark that exact route as bypass-safe and explain its auth class, audit behavior, and lease-owner forwarding rules in the same change.

```bash
curl http://localhost:8086/ping
```

```bash
curl http://localhost:8080/api/session/ping
```

To start a session via REST:

```bash
curl -X POST http://localhost:8086/sessions \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"1","gameTemplateId":"7","controlPlaneRequestId":"cp-req-1001","ownerAccountId":123}'
```

### gRPC examples

```bash
grpcurl -plaintext localhost:6565 game_session.v1.GameSessionService/Ping
```

```bash
grpcurl -plaintext -d '{"tenantId":"1","gameTemplateId":"7","controlPlaneRequestId":"cp-req-1001","ownerAccountId":"123"}' \
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
- Gameplay commands such as `LOOK`, `SAY`, movement, and combat are validated and normalized by Game Session, then forwarded to the tick/gameplay path. Game Session does not re-implement gameplay rules or resolve domain-owned facts for these commands.
- Line input and future structured-client input are adapters onto the same pinned registry, stage, capability, history, idempotency, and durable-admission boundary. A structured client may submit typed intention directly and need not round-trip through text.
- A verified empty authored registry is distinct from a required registry that is unavailable, corrupt, mismatched, colliding, or unsupported. The latter fails closed; it never activates a process-local built-in fallback.
- The immutable verified registry may be cached by admitted release identity. Downstream production services receive typed intentions and do not maintain another raw-text command authority.
- If a command would produce both immediate text and enqueue metadata, enqueue failure wins. Game Session returns a single `ERROR` response instead of reporting success and silently dropping gameplay work.

For the player-visible line protocol and examples, see [`protocols.md`](./protocols.md).

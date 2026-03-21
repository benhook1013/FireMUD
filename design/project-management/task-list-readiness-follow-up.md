# Readiness Follow-Up Task List

This temporary task list tracks the remaining follow-up work after the initial readiness tightening for the `connect -> LOGIN -> first LOOK` path.

The completed baseline is:

- `tcp-proxy` rejects new Telnet sessions while the gameplay path is unready.
- `spring-cloud-gateway`, `game-session-service`, and `game-logic-service` expose explicit readiness/liveness groups.
- readiness now uses dependency-aware checks on the critical path rather than only process-up or ping-up semantics.
- smoke and deployment probes consume canonical `/actuator/health/readiness` and `/actuator/health/liveness` endpoints.

This checklist is for the remaining quality pass, observability tightening, and blackbox validation improvements.

## Readiness Observability

- [x] Add readiness transition metrics for critical services so operators can see when readiness flips between accepting and refusing traffic.
- [x] Add structured logs on readiness state changes for `tcp-proxy`, `spring-cloud-gateway`, `game-session-service`, and `game-logic-service`, including the failing dependency name when readiness goes false.
- [x] Decide on a canonical metric or label set for dependency-aware readiness failures so dashboards and alerts can group by stable dependency names instead of parsing health payloads.
- [x] Document the readiness observability contract in the relevant architecture docs once the metric/log shape is finalized.

## Stronger Critical-Path Canaries

- [x] Tighten `game-session-service` readiness beyond downstream reachability by adding a bounded internal check for the session-state / command-enqueue path that first `LOOK` actually depends on.
- [x] Review whether `game-logic-service` should expose a single internal `resolveLook`-shaped canary helper instead of independently probing `GetRoomSnapshot` and `ListRoomEntities`.
- [x] Keep all readiness canaries side-effect free and bounded; if a candidate check would mutate durable state, do not use it for readiness.
  Resolution: local Redis probes now have explicit cleanup coverage on failure paths, and readiness-only gRPC canaries use short per-call deadlines rather than ambient channel behavior.
- [x] Revisit the synthetic probe identifiers used by the current canaries and confirm they are clearly reserved for readiness-only traffic and cannot collide with real gameplay state.

## Edge And Blackbox Verification

- [x] Add a blackbox or Compose-level test that proves the full external behavior:
  - before readiness, Telnet receives the explicit startup-unavailable disconnect
  - after readiness, first-attempt `LOGIN -> LOOK` succeeds without retries
- [x] Decide whether the same blackbox test should assert equivalent behavior for the direct WebSocket path or whether Telnet-only coverage is sufficient for this slice.
  Resolution: keep pre-readiness admission assertions at the Telnet boundary, and add direct WebSocket blackbox coverage only for post-readiness parity through `LOGIN -> LOOK`.
- [x] Review smoke coverage and make sure it is still verifying readiness semantics rather than merely waiting for eventual convergence.

## Payload And Contract Cleanup

- [x] Standardize dependency keys across readiness payloads so names such as `accountService`, `gameLogicService`, `gatewayGameplayPath`, `worldManagementService`, and `entityManagementService` remain stable and intentionally curated.
- [x] Review the shared readiness payload format and decide whether additional top-level fields are needed for operator use, such as `serviceContractVersion` or a short `admissionMeaning` string.
- [x] Confirm actuator health payloads remain concise enough for operators and CI logs and do not accumulate low-value implementation detail.
  Resolution: keep the current compact `contract`, `admissionMeaning`, `dependencies`, and conditional `failingDependency` shape, and lock it with unit tests so it does not silently grow.

## Wider Service Coverage

- [x] Prevent new services from drifting back to generic “process is up” readiness by updating service templates/checklists if needed.

## Final Review Pass

- [x] Do another repo-wide pass over the readiness changes and look specifically for simplifications, duplicated logic, weak assumptions, misleading docs, or test scaffolding that is compensating for behavior instead of verifying it.
  Resolution: shared readiness plumbing was consolidated, bounded readiness-only gRPC calls were added, and the `game-session-service` dev profile no longer downgrades readiness to `readinessState` only.
- [x] Update any design and architecture docs that drifted after the initial readiness/liveness documentation pass so the final documented model matches the implemented behavior and follow-up refinements.
  Resolution: infrastructure, smoke-workflow, and service-level docs now reflect bounded readiness-only canaries, reserved probe identifiers, direct WebSocket parity coverage scope, shared readiness payload/observability fields, and the `dev-isolated` exception without weakening the standard `dev` profile readiness contract.

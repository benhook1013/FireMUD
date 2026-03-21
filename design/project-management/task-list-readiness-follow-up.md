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
- [ ] Keep all readiness canaries side-effect free and bounded; if a candidate check would mutate durable state, do not use it for readiness.
- [x] Revisit the synthetic probe identifiers used by the current canaries and confirm they are clearly reserved for readiness-only traffic and cannot collide with real gameplay state.

## Edge And Blackbox Verification

- [x] Add a blackbox or Compose-level test that proves the full external behavior:
  - before readiness, Telnet receives the explicit startup-unavailable disconnect
  - after readiness, first-attempt `LOGIN -> LOOK` succeeds without retries
- [ ] Decide whether the same blackbox test should assert equivalent behavior for the direct WebSocket path or whether Telnet-only coverage is sufficient for this slice.
- [x] Review smoke coverage and make sure it is still verifying readiness semantics rather than merely waiting for eventual convergence.

## Payload And Contract Cleanup

- [x] Standardize dependency keys across readiness payloads so names such as `accountService`, `gameLogicService`, `gatewayGameplayPath`, `worldManagementService`, and `entityManagementService` remain stable and intentionally curated.
- [x] Review the shared readiness payload format and decide whether additional top-level fields are needed for operator use, such as `serviceContractVersion` or a short `admissionMeaning` string.
- [ ] Confirm actuator health payloads remain concise enough for operators and CI logs and do not accumulate low-value implementation detail.

## Wider Service Coverage

- [ ] Review other user-facing or near-user-facing services as they become real and explicitly decide whether each service needs:
  - local-only readiness
  - dependency-aware readiness
  - no special readiness work yet
- [x] Prevent new services from drifting back to generic “process is up” readiness by updating service templates/checklists if needed.

## Final Review Pass

- [ ] Do another repo-wide pass over the readiness changes and look specifically for simplifications, duplicated logic, weak assumptions, misleading docs, or test scaffolding that is compensating for behavior instead of verifying it.
- [ ] Update any design and architecture docs that drifted after the initial readiness/liveness documentation pass so the final documented model matches the implemented behavior and follow-up refinements.

# Game Session Service Operations

Player-facing controller transfer and reconnect behavior remain owned by [Session Behavior](../../system-architecture-session-behavior.md#namespace-scoped-controller-transfer-session-02) and [Reconnection](../../system-architecture-reconnection.md#client-reconnection-behaviour). Output schema/version and late rendering remain owned by [Input, Output, and Presentation](../../system-architecture-input-output-and-presentation.md#output-model); this operations document records the local readiness, scaling, and proof consequences only.

## Script transition operations (target-state contract)

Target state commits a successful exact script pin, its resulting monotonic epoch, and its immutable rollout-history entry in one transaction. Once a syntactically valid request is accepted, a deterministic validation or preparation failure atomically binds the normalized request digest, stores the failure result, and appends exactly one immutable unsuccessful history entry whose previous and resulting exact tuples are equal, without changing the pin or epoch; an equivalent uniqueness constraint must enforce the same single-identity guarantee if storage splits those records. An exact retry with the same request identity and digest returns the stored result without another history entry; a different digest under the same request identity is an idempotency conflict with no mutation. Operators and Logging & Admin compose this authoritative current/history read with Automation readiness and convergence; they must not select a winner from competing projections. Game Session owns the shared durable promotion/rollback workflow, including immutable `operationKind`, the convergence deadline, and the `PIN_CONVERGENCE_TIMEOUT` transition/event; the complete ordering, recovery, and timeout contract remains in [Scripting & Automation: Rollout and Rollback](../../system-architecture-scripting-rollout-and-rollback.md#pin-transition-orchestration-state-machine-required). The workflow pauses only new Automation admission for the affected scope through the serialized pin boundary, exact target-artifact observation, schedule reconciliation, and fresh convergence. Ordinary player commands and gameplay ticks continue throughout. Old script work is fenced at final effect application and may be canceled or purged asynchronously. A full routine tick pause is not part of the target contract and is exceptional only for a declared unfenced effect family, migration, or compatibility transition that cannot enforce the final `scriptPinEpoch` fence; the exceptional workflow identifies the smallest complete gameplay scope and proves quiescence before mutation.

## Implementation Status

Current script-transition observability is narrower: patch/request convergence reads, instance-scoped pause/resume, region-epoch fencing, and existing version-fence paths do not yet provide complete `scriptPinEpoch` coverage across logs, traces, and control-plane reads. See the [Game Session implementation status](./README.md#implementation-status) and [runtime and tick coordination tracker](../../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#active-gaps).

## Readiness and Liveness

- `liveness` is local-only and indicates that the process is alive and not wedged.
- `readiness` is gameplay admission safety for the commands this service currently exposes at the session front door. For the current implementation boundary, Game Session is ready only when:
  - local persistence required for login/session state is usable;
  - required Redis-backed session and tick infrastructure is usable;
  - the readiness-only local round-trip canaries for session-context storage and command-queue storage both succeed;
  - Account Service authentication is reachable through a bounded readiness-only authentication probe; and
  - Game Logic is reachable for the first gameplay command path through a bounded readiness-only `ResolveLook` probe, with Game Logic in turn proving readiness for the downstream services needed to satisfy the first `LOOK`.
- A successful `LOGIN` without a safe first `LOOK` is not sufficient readiness for new player traffic.
- Synthetic identifiers used by these canaries are explicitly reserved for readiness-only traffic so they cannot collide with real gameplay state.
- Readiness transition observability uses the shared contract from [Deployment Environments](../../infrastructure/deployment-environments.md): `firemud.readiness.current`, `firemud.readiness.transitions`, and structured logs keyed by the curated dependency names `accountService`, `sessionContextStore`, `commandQueueStore`, and `gameLogicService`.

## Operational Notes

- Game Session runs as a Kubernetes Deployment, or Docker Compose for local development, with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- Prometheus scrapes metrics from `/actuator/prometheus`.
- Target state: logs, traces, and control-plane reads expose the active exact `{scriptPatchVersion, scriptPinEpoch}` execution fence so operators can identify the hotfix revision and selection epoch during incident triage without adding either raw value as ordinary high-cardinality metric labels. Current coverage is summarized in [Implementation Status](#implementation-status).

## Scaling and Region Rebalancing

- Region-to-instance mapping is flexible and driven by a scheduler or consistent-hashing layer that assigns `<tenantId, gameInstanceId, regionId>` values to Game Session instances.
- **Target state:** replacement does not make the runtime mapping a durable playable-state identity. Game Session carries the catalog-derived `{playableStateNamespaceId, playableStateScope}` and, when applicable, the canonical private/playtest lifecycle proof tuple `{playtestLifecycleId, playtestStateGeneration}` with the active `{tenantId, gameInstanceId}` binding and persists the compatibility proof’s exact target World `PREPARING` state/epoch as the activation precondition. World alone CAS-activates that target to `ACTIVE`, then issues one equality-only `cutoverHoldId`/`cutoverHoldFence`; Game Session persists the resulting exact source/target `ACTIVE` state/epochs and hold identity in the execution result. Before the pointer CAS, Game Session commits the source admission/write fence, and every already-admitted durable source write must have authoritative `APPLIED` or `NOT_APPLIED` proof plus a durable retry/recovery record when not applied; vague flush-or-reconcile status is insufficient. It then performs fresh lifecycle, freshness, and hold revalidation; only matching evidence may proceed to that CAS. The request digest covers only known request/preparation inputs, while the idempotent execution result/readback binds the World-issued proofs, hold, namespace/scope tuple, and applicable lifecycle proof tuple. Game Session accepts the pointer move only after owner-local S1/S2/S3 compatibility evidence, exact source/target `ACTIVE` proofs, expected pointer version, hold binding, and idempotent source-cleanup work registration are in the same pointer/audit/prepared-execution/source-cleanup/drain-fence transaction. The post-swap reconciler obtains authoritative pointer/readback confirmation of that same lifecycle-proof-bound cleanup-work registration and source binding before asking World to finalize the hold and requesting World’s `TERMINATING` transition; this is not the all-owner terminal cleanup acknowledgement, which follows in World’s `TERMINATING` workflow before `TERMINATED`. Public production omits both playtest fields and rejects supplied values. A cleanup acknowledgement is not a pointer-CAS substitute. Temporal lifecycle status is coordination evidence; the World Management database row/epoch remains authoritative. The current wire, durable artifact/readback, digest coverage, hold record, and focused proof do not yet provide those complete replacement guarantees; see the [Game Session API contract](./api-contracts.md#world-lifecycle-and-admission-boundary) and [runtime and tick coordination tracker](../../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#active-gaps).
- To scale out, operators add more Game Session pods and allow the scheduler to assign regions to new instances; each instance acquires leases for its assigned regions.
- To rebalance load, an instance can stop renewing the lease for selected regions and drain in-flight work to a safe point; other instances then acquire those leases and continue tick processing from the existing Redis state.
- Combined with region sizing, splitting hot regions and merging cold ones, this lease-based ownership model allows FireMUD to scale horizontally without global downtime.
- The same externalized-state model supports ADR 0013's bounded ordinary restart recovery. A qualifying single-pod restart targets recovery within 10 seconds without client re-`LOGIN` or re-`PLAY`. If continuation authority cannot be established safely, hidden recovery must terminate immediately; otherwise 30 seconds is the hard maximum before falling back to `1013/backend_unavailable`. Operational proof must cover planned and abrupt real Game Session replacement, retained edge sockets, authority and presence convergence, input-buffer behavior, the early fail-closed authority cutoff, and the elapsed-time cutoff.

## Local Development Path

- Use the normal runtime configuration with the real local Postgres, Redis, Gateway, Account, and downstream gameplay-service topology.
- The maintained integration and ingress coverage now targets the real login/session flow described in [Player Access and Session implementation tracking](../../../project-management/implementation-tracking/player-access-and-session.md), and the canonical operator proof remains the repo smoke scripts under `dev-tools/`.

## Cross-Service Integration Tests

The maintained cross-service coverage for Game Session is now the current WebSocket/Telnet gameplay path, not the older GHCR-image placeholder style. Use the focused WebSocket regressions under `services/game-session-service/src/test/java/crossservice` plus the Telnet ingress parity coverage under `services/tcp-proxy-service/src/test/java/crossservice` when validating current gameplay behavior.

See [System Architecture Testing](../../system-architecture-testing.md) for the shared testing approach.

## Current Runtime Status

### Communication status

- **Live:** `SAY`, `WHISPER`, and `TELL` route through `CommunicationCommandHandler`, which enforces the shared session guard, forwards normalized payloads and target metadata to Game Logic's `SendCommunication`, and renders the canonical actor transcript while emitting `gamesession.command.say.*`, `gamesession.command.whisper.*`, and `gamesession.command.tell.*` meters documented in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- **Stubbed:** Delivery still relies on the Social & Groups Service regression stub used by the suites, which records `SendMessage` calls and returns success so WebSocket and Telnet regression runs observe deterministic sender-side transcripts and explicit recipient metadata.
- **Deferred:** First-party/MCP-aware recipient presentation, richer NPC roleplay responses, listening-area heuristics, and localized channel filters remain future work once the shared communication path proves stable and well instrumented.

### LOOK status

- **Live:** Data-driven `LOOK` flows route through Game Logic's `ResolveLook`; Game Session renders the canonical text, caches the last snapshot per session, and emits the instrumentation metrics/logs documented in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md) before replying over Telnet or WebSocket.
- **Stubbed:** Room/exit metadata and visible entities still derive from the deterministic LOOK test fixtures and the `firemud.look.rooms` entries so transcripts and regression tests stay stable while the cross-service WebSocket and Telnet flows rely on the shared stub utilities.
- **Deferred:** Dynamic lighting, line-of-sight filtering, and script-driven room prose remain future work once instrumentation, metrics, and cross-service regression coverage stabilize. **Target/deferred behavior:** reconnect does not replay cached room snapshots; after authorized reconstruction it obtains a fresh authoritative `LOOK` and emits exactly one reconnect prompt if and only if both effective `firemud.presentation.prompt.enabled` and `firemud.presentation.prompt.emit-after-reconnect-restore` are true, otherwise zero. No exact executable proof is claimed here; any bounded semantic recent context remains governed by the owner documents above.

## Out-of-Scope Note

The core FireMUD architecture assumes a single Kubernetes cluster per deployment, with horizontal scaling achieved via tick-region leasing and executor rebalancing inside Game Session. If multi-cluster gameplay sharding is introduced later, it must be captured as a dedicated design update for routing-key transport, trust model, and reconnection/backoff policy and must not conflict with the current edge contract, where close-and-reconnect remains the default.

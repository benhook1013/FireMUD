# Game Session Service Operations

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
- Logs, traces, and control-plane reads expose the active script patch version so operators can tell which hotfix revision is active during incident triage without reintroducing raw `script_patch_version` Prometheus labels.

## Scaling and Region Rebalancing

- Region-to-instance mapping is flexible and driven by a scheduler or consistent-hashing layer that assigns `<tenantId, regionId>` values to Game Session instances.
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
- **Deferred:** Dynamic lighting, line-of-sight filtering, script-driven room prose, and optional reconnection replay of cached snapshots remain future work once instrumentation, metrics, and cross-service regression coverage stabilize.

## Out-of-Scope Note

The core FireMUD architecture assumes a single Kubernetes cluster per deployment, with horizontal scaling achieved via tick-region leasing and executor rebalancing inside Game Session. If multi-cluster gameplay sharding is introduced later, it must be captured as a dedicated design update for routing-key transport, trust model, and reconnection/backoff policy and must not conflict with the current edge contract, where close-and-reconnect remains the default.

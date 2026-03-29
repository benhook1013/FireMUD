# Game Session Service Operations

## Readiness and Liveness

- `liveness` is local-only and indicates that the process is alive and not wedged.
- `readiness` is gameplay admission safety for the commands this service currently exposes at the session front door. For the currently implemented slice, Game Session is ready only when:
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
- Logs and metrics include `script_patch_version` context so operators can tell which hotfix revision is active during incident triage.

## Scaling and Region Rebalancing

- Region-to-instance mapping is flexible and driven by a scheduler or consistent-hashing layer that assigns `<tenantId, regionId>` values to Game Session instances.
- To scale out, operators add more Game Session pods and allow the scheduler to assign regions to new instances; each instance acquires leases for its assigned regions.
- To rebalance load, an instance can stop renewing the lease for selected regions and drain in-flight work to a safe point; other instances then acquire those leases and continue tick processing from the existing Redis state.
- Combined with region sizing, splitting hot regions and merging cold ones, this lease-based ownership model allows FireMUD to scale horizontally without global downtime.

## Dev-Isolated Mode

- Use `./gradlew :game-session-service:bootRunDevIsolated` or set `GAME_SESSION_DEV_ISOLATED=true` when you need to exercise Game Session without PostgreSQL, Redis, or downstream gRPC dependencies. The dev-isolated beans acknowledge commands and lifecycle requests while only recording informational logs instead of accessing external systems.
- `dev-isolated` is an explicit opt-in for dependency-free local development only. The standard `dev` profile used by Docker Compose and readiness-based smoke tests keeps the canonical readiness group rather than downgrading readiness to local `readinessState` only.
- The dependency-light dev-isolated path is still available for local experimentation, but the maintained integration and ingress coverage now targets the real login/session flow described in [`02.1-task-list-login-session-hardening-vertical-slice.md`](../../../project-management/vertical-slices/02.1-task-list-login-session-hardening-vertical-slice.md).

## Cross-Service Integration Tests

The maintained cross-service coverage for Game Session is now the current WebSocket/Telnet gameplay path, not the older GHCR-image placeholder style. Use the focused WebSocket regressions under `services/game-session-service/src/test/java/crossservice` plus the Telnet ingress parity coverage under `services/tcp-proxy-service/src/test/java/crossservice` when validating current gameplay behavior.

See [System Architecture Testing](../../system-architecture-testing.md) for the shared testing approach.

## Slice Status Notes

### Chat slice status

- **Live:** `SAY`/`YELL`/`WHISPER` commands route through `SayCommandHandler`, which enforces the shared session guard, forwards normalized payloads to Game Logic's `BroadcastSay`, and renders the canonical `OK SAY` transcript while emitting the `gamesession.command.say.*` meters documented in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md).
- **Stubbed:** Delivery relies on the Social & Groups Service stub used by the regression suites, which currently records webhook contexts and returns success so both Telnet and WebSocket regression runs observe deterministic `Delivered-To` lists.
- **Deferred:** Future slices will enrich the Social backend with NPC roleplay responses, listening-area heuristics, and localized channel filters once the core `BroadcastSay` path proves stable and well instrumented.

### LOOK slice status

- **Live:** Data-driven `LOOK` flows route through Game Logic's `ResolveLook`; Game Session renders the canonical text, caches the last snapshot per session, and emits the instrumentation metrics/logs documented in [`look-instrumentation.md`](../../../project-management/slice-support/look-instrumentation.md) before replying over Telnet or WebSocket.
- **Stubbed:** Room/exit metadata and visible entities still derive from the deterministic LOOK test fixtures and the `firemud.look.rooms` entries so transcripts and regression tests stay stable while the cross-service WebSocket and Telnet flows rely on the shared stub utilities.
- **Deferred:** Dynamic lighting, line-of-sight filtering, script-driven room prose, and optional reconnection replay of cached snapshots remain future work once instrumentation, metrics, and cross-service regression coverage stabilize.

## Out-of-Scope Note

The core FireMUD architecture assumes a single Kubernetes cluster per deployment, with horizontal scaling achieved via tick-region leasing and executor rebalancing inside Game Session. If multi-cluster gameplay sharding is introduced later, it must be captured as a dedicated design update for routing-key transport, trust model, and reconnection/backoff policy and must not conflict with the current edge contract, where close-and-reconnect remains the default.

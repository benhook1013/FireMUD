# Game Session Service API Contracts

## Service Interactions

Game Session communicates with other platform services exclusively via gRPC for gameplay-domain work. For gameplay-domain gRPC calls made on behalf of a player, it includes a signed `SessionAttestation` and rotates it on bounded TTL; downstream gameplay services must reject calls missing a valid attestation, or attestations whose destination service/method scope does not match the invoked RPC, even when mTLS is present.

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

- Forwarded requests include `tenantId`, `gameInstanceId`, `sessionId`, `characterId`, target `regionId`, command/action identifier, and a monotonic per-session sequencing token.
- Forwarded requests include the current region lease/epoch fence. Lease owners reject stale or missing fences with an application-level stale-lease response rather than silently executing.
- The session front-end preserves per-connection FIFO when emitting forwarded work. Cross-connection ordering remains undefined during takeovers as described in the reconnection and protocol-bridging docs.
- If the lease owner rejects a stale fence before execution, the front-end refreshes ownership and may retry the request once against the new lease owner when the request is still valid.
- If forwarding fails after the executor may already have started, the front-end must treat the result as ambiguous and use the normal structured command-failure or reconnect path; it must not re-issue potentially mutating work without an idempotency guarantee.
- All forwarded execution attempts and stale-lease rejections must emit dedicated metrics and traces so operators can distinguish edge socket health from region-executor health.

## gRPC APIs

- `Ping` – basic connectivity check.
- `StartSession` – spins up a game instance from a published version. Despite the name, this operates on game instances, not player gameplay sessions; gameplay sessions are per-player contexts backed by `session:game:*` keys.
- `StopSession` – stops a running game instance.
- `RestartSession` – restarts a stopped game instance.
- `EnqueueCommand` – adds a player action to the next tick's queue.
- `QueryState` – retrieves condensed session or player state for monitoring.
- `ToggleFeatureFlag` – updates runtime flags for a tenant.
- `PauseTicks` – temporarily halt tick execution before a backup.
- `ResumeTicks` – resume tick processing after the backup begins.
- `GetRegionTickStatus` – returns the canonical per-region pause/status surface for backup orchestration, reset tooling, and recovery gates.

Service definitions reside in [../../../../protos/game-session/v1](../../../../protos/game-session/v1). Run `./gradlew generateProto` after modifying these files to regenerate stubs. The generated classes appear under `net.firedevops.firemud.gamesession.v1` in `build/generated/sources/proto/main/{grpc,java}` and are wired into `services/game-session-service/src/main/java/net/firedevops/firemud/service/impl/GameSessionGrpcService.java`.

### REST endpoints

- `GET /ping` – basic health check returning `"pong"`.
- `POST /sessions` – create a new game instance from a published version.
- `POST /sessions/{id}/stop` – stop a running session.
- `POST /sessions/{id}/restart` – restart a stopped session.
- `POST /sessions/{id}/refresh-roles` – refresh the player's roles for an active session.

Use `/sessions/{id}/refresh-roles` after updating an account's privileges so the session reflects the latest role assignments.

```bash
curl http://localhost:8080/ping
```

To start a session via REST:

```bash
curl -X POST http://localhost:8080/sessions \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"demo","runtimeVersion":"v42","scriptPatchVersion":"v42-script.3"}'
```

### gRPC examples

```bash
grpcurl -plaintext localhost:6565 game_session.v1.GameSessionService/Ping
```

```bash
grpcurl -plaintext -d '{"tenantId":"demo","runtimeVersion":"v42","scriptPatchVersion":"v42-script.3"}' \
  localhost:6565 game_session.v1.GameSessionService/StartSession
```

## Command Front Door Ownership

Game Session owns the gameplay session front door and the split between protocol-level system commands and queued gameplay work:

- System commands such as `LOGIN`, `LOGON`, `PING`, and lightweight state queries are fully owned by Game Session and may complete synchronously without enqueuing gameplay work.
- Gameplay commands such as `LOOK`, `SAY`, movement, and combat are validated and normalized by Game Session, then forwarded to the tick/gameplay path. Game Session does not re-implement gameplay rules for these commands.
- If a command would produce both immediate text and enqueue metadata, enqueue failure wins. Game Session returns a single `ERROR` response instead of reporting success and silently dropping gameplay work.

For the player-visible line protocol and examples, see [`protocols.md`](./protocols.md).

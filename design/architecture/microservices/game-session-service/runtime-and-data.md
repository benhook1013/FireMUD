# Game Session Service Runtime and Data

## Runtime Model

Game Session coordinates with Redis to store volatile session state and command queues and with PostgreSQL to persist durable game-instance control-plane metadata. It provides a single point of truth for current tick and world time while exposing gameplay-session state to the protocol front door.

The service runtime model assumes replaceable workers, not authoritative in-process state:

- Redis and PostgreSQL hold the meaningful gameplay-session, tick-coordination, and control-plane state needed for takeover.
- A Game Session instance may cache or buffer transient transport-local details while it is healthy, but those details must never be the sole source of truth for reconnect, tick ownership, or gameplay admission.
- Ordinary non-edge Game Session restarts should therefore degrade to a short stall or upstream rebinding event, not a mandatory player-visible re-`LOGIN` / re-`PLAY` cycle.

- PostgreSQL stores `game_instances`, `game_manifest`, pinned runtime-version/script-patch selections, active runtime feature-flag overrides, and audit-relevant disconnect/remediation metadata.
- Redis stores gameplay session bindings, tick queues, timers, retries, and region leases.
- Game Session uses PostgreSQL for durable control-plane metadata and Redis as the coordination plane for gameplay-session bindings, tick queues, timers, retries, and region leases.
- Bootstrap session-context records are created as soon as a client connects. They remain unauthenticated until Account Service verifies credentials and issues a token, and gameplay commands must reject them as `LOGIN_REQUIRED` until `LOGIN` promotes the context to authenticated account scope.
- Game Session relies on the shared libraries for DTO definitions, logging interceptors, Micrometer metrics, and shared saga helpers rather than reimplementing those surfaces locally.
- Brute-force defense remains delegated to Account Service, which owns login-attempt monitoring, per-IP/account throttling, blacklisting, and notification behavior. Game Session consumes the resulting auth outcomes when binding gameplay sessions but does not implement separate credential-abuse logic.
- No single Game Session process may become the hidden source of truth for reconnectable gameplay state. If the service needs a value to survive non-edge restart or permit same-type takeover, that value must live in Redis or PostgreSQL-backed coordination rather than only in process memory.

## Redis Ownership and Coordination Rules

Game Session uses the gameplay layer’s session front-end plus lease-owner execution model:

- Connected sockets bind to a stable session front-end pod, while region-scoped tick execution remains fenced to the current `<tenantId, regionId>` lease owner.
- Session front-ends may forward work to lease owners over internal gRPC, but only lease owners may mutate region-scoped coordination state.
- Tick-related multi-key operations, including locks, pending state, queues, timers, and retry metadata, are performed exclusively via the shared Lua scripts described in [Redis Architecture](../../system-architecture-redis.md#atomicity-and-concurrency-control). Ad-hoc multi-key sequences against tick keys are not allowed outside these scripts.
- Because session bindings, leases, queues, timers, and retry markers are externalized, another Game Session instance of the same type must be able to assume session-front-end or lease-owner responsibility after failure. If a non-edge Game Session restart still forces a visible reconnect in practice, treat that as an implementation gap rather than an accepted contract.
- Lease ownership and session front-end routing are deliberately takeover-ready. Another same-type Game Session instance must be able to acquire the relevant lease or front-end responsibility from shared state after restart; visible reconnect is acceptable only when the edge transport itself was lost.

Game Session treats Redis Coordination and Cache/Rate-Limit roles as separate concerns:

- All tick, lock, timer, retry, and session coordination keys live on Coordination Redis and are accessed only via the Lua Script Registry and shared key builders.
- Game Session code that runs inside the tick engine, including the tick scheduler, staging/commit flows, and lease management, never reads or writes Cache/Rate-Limit Redis directly.
- Cache lookups and invalidations remain encapsulated inside domain services, which own correctness and treat caches as performance hints only.

### Redis role and prefix details

- Coordination prefixes are reset-tolerant in line with [Redis Reset & Recovery](../../system-architecture-redis-reset-and-recovery.md), except for reset-sensitive gameplay session bindings under `session:game:*`. Region-scoped resets preserve gameplay sessions by default; wider tenant or cluster resets may invalidate sessions according to the reset policy matrix. Game Session’s coordination design must therefore remain safe under the documented Redis tail-loss envelope rather than assuming perfect recovery of every transient coordination key.
- Coordination ownership includes registered Lua-script access to prefixes such as:
  - `tick:{tenantRegionTag}:queue:<entityId>`
  - `tick:{tenantRegionTag}:pending`
  - `tick:{tenantRegionTag}:lock:<entityId>`
  - `timer:{tenantRegionTag}`
  - `retry:{tenantRegionTag}`
  - `tick-executor-lease:{tenantRegionTag}`
  - `remote:<tenantId>:<entityId>` and related coordination prefixes listed in the [Redis Cheat Sheet](../../system-architecture-redis-cheatsheet.md)
- `remote:<tenantId>:<entityId>` remains a best-effort hint marker for cross-region follow-ups; durable follow-up state lives in PostgreSQL via the tick effect ledger and follow-up tables.
- Coordination keys must be constructed through the shared key builders and script-registry contracts rather than ad-hoc string concatenation in service code.
- Changes to Game Session Redis usage must also be reviewed against the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) in addition to the core Redis architecture docs.
- Game Session may rely on Cache/Rate-Limit Redis for read-side caches that help serve hot-path session views, most notably pre-rendered room LOOK aggregates under `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>` as defined in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md#cache-rate-limit-key-catalog).
- `view:room-look:*` entries are Class B, TTL-only caches. They may become stale briefly, but gameplay correctness still comes from authoritative reads and tick execution rather than cache contents. Underlying world or entity changes do not require synchronous invalidation of these keys; recomputation happens on cache miss or TTL expiry, and correctness-critical flows must not treat the cache as authoritative.
- Cache prefixes must never be silently repurposed onto Coordination Redis, and new cache usage should be introduced only through the central cache-key catalog rather than ad-hoc per-service naming.
- Game Session must not read or write Social & Groups history caches such as `chat:say:*`, `chat:tell:*`, `chat:guild:*`, or `chat:account:*`. Chat history ownership stays behind Social & Groups APIs.

Crash recovery replays ticks stored in Redis using AOF persistence and the idempotent replay rules described in [Tick System and Runtime Design](../../system-architecture-ticks.md#crash-recovery-and-replay). Replication remains asynchronous, and Redis is treated as a volatile coordination layer rather than a durable source of truth.

When Redis becomes slow or unavailable, Game Session applies the graceful degradation and halt behavior defined in [Redis Architecture – Graceful Degradation & Redis Outage Policy](../../system-architecture-redis.md#graceful-degradation--redis-outage-policy) instead of buffering authoritative commands only in memory. If coordination state must be cleared or repaired, operators follow the scoped reset flows in [Redis Operations & Migrations](../../system-architecture-redis-operations.md) rather than issuing ad-hoc key deletions.

## Session Keys and Indexes

Every gameplay session record includes a `tenantId` identifying the owning tenant and, through associated records, the `gameInstanceId` for the running world instance. Redis keys and database tables prefix this value so sessions from different games remain isolated. The platform may enforce per-tenant resource quotas at this level so one tenant cannot exhaust cluster capacity. See [Multi-Tenancy](../../system-architecture-multi-tenancy.md).

Session state needed for reconnect recovery is stored under `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`. These per-session keys, including session-scoped command queues or metadata, are removed when the corresponding session stops or expires.

Current Game Session code also maintains a `sessionctx:*` Redis-backed session-context family for bootstrap and implementation-local lookup indexes:

- `sessionctx:session:<sessionId>:context` maps a transport session id to its current context.
- `sessionctx:<tenantId>:<sessionId>:context` stores tenant-scoped session context.
- `sessionctx:<tenantId>:identity:<gameInstanceId>:<characterId>:context` and `sessionctx:<tenantId>:identity:<gameInstanceId>:name:<characterName>:context` support current takeover and reconnect lookups.

Unauthenticated `sessionctx:*` entries may exist before `LOGIN`; they are bootstrap context only and must not authorize gameplay commands. Authenticated gameplay semantics still follow the `session:game:*` / region-binding contract in the Redis architecture docs. If this implementation-local key family is renamed or collapsed into `session:game:*`, the pre-auth vs authenticated distinction remains mandatory.

Game Session must also maintain the bounded authoritative indexes defined in the authentication architecture so takeover, reconnect, and revocation do not require scans:

- `session:game:index:character:{tenantGameplayTag}:<gameInstanceId>:<characterId>` -> `sessionId`
- `session:game:index:account-tenant:{tenantGameplayTag}:<accountId>` -> active `sessionId` set
- `session:game:index:tenant:{tenantGameplayTag}` -> active `sessionId` set

Gameplay session creation/update plus these tenant-scoped index mutations are one shard-local Redis CAS/update flow under `{tenantGameplayTag}`. Region-local gameplay admission still uses the separate `tick:{tenantRegionTag}:session-binding:<entityId>` bridge contract and is not folded into the same Lua invocation.

Gameplay session bindings must include the server-side auth token identity used for backend calls on behalf of the session, such as `authTokenHash` and `authTokenIssuedAt`, plus authoritative membership freshness metadata such as `membershipVersion` so resume logic can validate current identity, current membership authority, and current revocation state before rebinding to a fresh backend token:

- current caller identity matches the stored gameplay binding subject;
- membership authority still allows gameplay admission for the tenant; and
- bulk revocation watermarks such as `session:auth:revoked_after:*` do not block the account or tenant.

These identity and revocation rules are defined in [Authentication & Authorization](../../system-architecture-authentication.md#session-and-identity-management).

## Tick Coordination and Lease Ownership

Game Session acts as the authoritative tick executor for each `<tenantId, regionId>` it owns:

- It participates in region leadership using the Redis lease key `tick-executor-lease:{tenantRegionTag}` described in [Redis Architecture](../../system-architecture-redis.md#region-leadership-and-tick-executor-lease).
- While it holds the lease for a region, it is the only instance allowed to consume commands from that region’s queues and timers, drive `tick:{tenantRegionTag}:pending`, and issue tick-scoped gRPC calls on behalf of that region’s commands.
- On crash or deliberate handoff, another instance acquires the lease and resumes tick processing from Redis using the epoch-scoped `(regionEpoch, tickId)` timeline and EffectId/effect-guard rules from the tick-system design.
- Tick execution correctness must not depend on process-local executor memory surviving restart. Lease acquisition, staged tick state, retry metadata, and resumable session bindings are all externalized specifically so another Game Session instance can continue work from shared state.
- The executor monitors `tick_execution_time_ms_p99` and `tick_lock_ttl_ms` for each region. If a region repeatedly produces over-TTL ticks according to the thresholds described in the Redis and Tick architecture docs, it marks that region as degraded, automatically reduces tick fan-out, emits explicit degraded-region metrics, and may halt new ticks and reject new commands for that region until operators intervene.
- Changing `tick_interval_ms` is not part of this in-place degradation path. Any live cadence change that alters timer ordering must run as an epoch-bumped maintenance operation with pause, timer re-derivation, and resume on the new `regionEpoch`, as defined in the tick and scaling docs.
- The staging Lua script only moves a bounded number of commands each tick, governed by `GAME_TICK_MAX_COMMANDS`, so one player cannot starve others. Commands marked `requiresSoloTick: true` are dequeued into isolated ticks so expensive operations such as runtime procedural generation do not share time with normal actions.
- If a command cannot acquire its required entity lock set, the executor fails the attempt, rolls back staged changes, and reschedules the command with bounded tick-based backoff, for example exponential backoff capped by `MAX_BACKOFF_TICKS`, while tracking a per-command retry counter and enforcing `MAX_RETRIES` before surfacing a player-visible error and logging a permanent failure.

These degraded and halt transitions follow the same thresholds and policies captured under [Redis Architecture – Operational SLOs & Alert Thresholds](../../system-architecture-redis.md#operational-slos--alert-thresholds) so operators and implementations share a single set of red lines for coordination health.

Tick coordination is region-scoped, not session-scoped. Tick queues, locks, timers, retry metadata, and `tick:{tenantRegionTag}:pending` use the `tick:{tenantRegionTag}:...` prefix described in [Redis Architecture](../../system-architecture-redis.md#tick-integration-resilience-locking-staging). Region keys follow region lifecycle and crash-recovery rules:

- `tick:{tenantRegionTag}:pending` is created without a TTL so it survives process crashes and failover.
- It is cleared only when the tick is successfully committed or an operator-driven recovery flow explicitly marks the tick as skipped/failed and removes the key.

Session shutdown therefore cleans up session keys but does not implicitly delete region-level tick coordination keys.

## Reconnection and Disconnect Handling

Game Session restores player sessions after disconnects and enforces single-session control as outlined in the [Reconnection Strategy](../../system-architecture-reconnection.md). For Telnet clients, it also consumes best-effort, at-least-once `NotifyDisconnect` events emitted by the TCP Proxy Service over an internal gRPC link and treats them as idempotent hints keyed by `<proxyConnectionId, disconnectSequence>` rather than a guaranteed source of truth.

Game Session persists the latest processed `disconnectSequence` per `<proxyConnectionId>` and ignores older or duplicate events so retry behavior at the proxy can remain simple while consumption stays idempotent. Any `{tenantId, gameInstanceId}` values coming from proxy-owned bootstrap metadata or future hidden MCP-carried smart-client hints remain advisory context that may be used for logging and audit, but they are not trusted as authorization claims or binding directives. Game Session still validates any game-instance ownership claims against Redis and its authenticated session state before rebinding the transport.

Recent/offline account-presence state should preserve the last admitted routing bundle too. When live presence drops to recent presence after transport loss, takeover, or logout, the bounded recent-presence record keeps the last admitted `gameInstanceId`, `worldSlug`, `realmSlug`, and `pointerVersion` so account-presence and friend-presence reads can continue to describe the last resolved realm target directly instead of collapsing immediately to a routing-less timestamp.

## Runtime Feature Flags

Feature flags are stored in the `feature_flag` table and can be toggled through the Logging & Admin Service. Game Session exposes a gRPC `ToggleFeatureFlag` method so administrators can enable or disable experimental behavior without restarting a session. See [Game Design Service Feature Flags](../game-design-service/feature-flags.md) for how definitions are created and published.

## Script Patch Version Pinning and Rollback

Each running game instance has a pinned `scriptPatchVersion` alongside its `runtimeVersion`:

- Event ingress to the Automation & Scripting Service includes the currently pinned `scriptPatchVersion` so script evaluation is tied to the active patch for the instance.
- Current Game Session emits `onCommand` events after durable player-command staging and immediate tick kick when a gameplay session context, pinned script patch, and current runtime ownership row are all available. The request uses the live `{tenantId, gameInstanceId}` boundary as the current region surrogate until true region partitioning is shipped, includes the current `regionEpoch`, and carries a producer-supplied `readSnapshotToken` derived from the command and ownership fence.
- Script-generated commands accepted from the Automation & Scripting Service must carry the originating `scriptPatchVersion`, `scriptId`, and `scriptEventId`.
- On execution, Game Session enforces a version fence: if a queued command’s `scriptPatchVersion` does not match the instance’s currently pinned value, it must not be executed and the drop must be observable for operators.

Control-plane operations that change the pinned patch are admin-only and idempotent. Their required request/response fields are specified in [Scripting Control Plane API](../../system-architecture-scripting-control-plane-api.md), the associated event contracts are specified in [Scripting Control Plane Events](../../system-architecture-scripting-control-plane-events.md), and the Game Session API surface is represented in `protos/game-session/v1/game_session_service.proto` under `GameSessionControlPlaneService`. The same control-plane surface now also exposes bounded runtime durability inspection, including durable gameplay-command status and the current owner-of-record runtime ownership row for a `{tenantId, gameInstanceId}` queue boundary.

For cross-service invariants, see [Scripting Contracts](../../system-architecture-scripting-contracts.md).

## Durable Command and Effect Execution

Game Session now has a real current-boundary durable gameplay execution seam instead of stopping at queue staging:

- accepted gameplay commands persist a durable command ledger row with both a human-safe sanitized text projection and the canonical raw command payload used for later execution;
- the tick runtime stages Redis queue work into durable `tick_batch` / `tick_effect` rows before drain commit, including the current-boundary selected-work manifest, expected effect count, and manifest digest on the batch row;
- after drain commit, Game Session resumes from any durable `DRAINED` effects for that queue boundary and executes the supported command families from the ledger itself rather than assuming Redis drain already implies terminal gameplay work;
- drained effects re-check the durable runtime owner row before application. If the batch fence is stale, unapplied drained effects are marked `ABANDONED`, their durable commands are requeued for a fresh fenced batch, and the old executor does not invoke the effect handler.

The first migrated command families are movement plus the first item/equipment/container mutation surface:

- built-in movement input now enqueues durably instead of performing its authoritative room mutation directly in the dispatch handler;
- movement execution reuses the shared move-planning logic to preserve player-visible semantics, but `MoveCommandHandler` no longer exposes a public synchronous session-write path and the authoritative room change now flows through one dedicated idempotent movement-apply seam keyed by `effectId`;
- duplicate movement effect application converges to replay/no-op rather than a second room mutation;
- player-visible movement output is delivered asynchronously through the same active-websocket plus screen-buffer-aware delivery path used for runtime recipient delivery;
- item/equipment/container mutation commands, currently `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, and `REMOVE`, enqueue durably from the player-facing command path and execute from the durable post-drain effect executor before their player-visible outputs are delivered;
- Game Session passes the durable `tick_effect.effectId` to Entity Management for those item/equipment/container mutations so duplicate downstream delivery can replay the stored domain response instead of applying the mutation again;
- `BLOCK` is the first transient action-state command on the same durable execution seam: Game Session stores/replays the durable effect, Game Logic routes `ApplyActorCondition`, and Entity Management persists the short-lived `blocking` active condition.
- read-only item views such as `INVENTORY`, `EQUIPMENT`, and `CONTAINER` remain direct view commands because they do not mutate authoritative gameplay state.

Other command families still need to migrate onto this same durable seam. Pure view/meta commands may remain direct until there is a concrete reason to queue them, but state-changing gameplay families should not introduce new synchronous bypasses. The next durability gap is pushing the same effect-idempotent replay/no-op pattern through later domain mutation boundaries, not merely routing more commands through the Game Session ledger.

### Automation Command Admission

Fairness-critical automation enters gameplay through the same durable command/admission boundary as player commands, not through direct Automation & Scripting writes to `tick:*` Redis keys.

- Automation & Scripting calls Game Session with a stable `automationDispatchId`, target `(tenantId, gameInstanceId, regionId, regionEpoch)`, optional `dueTickId` for due-point-aware automation, target `entityId`, and deterministic command payload.
- Game Session records or reads a durable `gameplay_command` admission row through `EnqueueAutomationCommandIfAbsent`, keyed by `(tenantId, gameInstanceId, regionId, regionEpoch, automationDispatchId)`, before mutating Redis. Existing dispatches return duplicate/no-op from that durable key. New dispatches are fenced against the current durable runtime owner row before Redis mutation: missing ownership, paused ownership, or mismatched `regionEpoch` returns a non-mutating rejection. The row stores optional `dueTickId`, target entity, script/work-item correlation fields, command payload, and current execution outcome.
- Duplicate requests for the same key return replay/no-op outcomes from the durable row. Conflicting payloads for the same key are validation failures and must not enqueue.
- Only after durable admission succeeds does Game Session invoke the region-lease Redis script that materializes the command into `tick:{tenantRegionTag}:queue:<entityId>`.
- If Redis enqueue fails after durable admission, Game Session retries materialization from the durable row or converges the row to a terminal non-applied outcome under the command-status rules. Redis queue contents are never the sole proof that the automation action existed.

## Saga Participation

Game startup and shutdown are coordinated using the shared `Saga` helpers from `firemud-common`. Each dependent service, including World Management, Entity Management, and Game Logic, confirms its part of the workflow before the session becomes active. Failures trigger compensating steps so session activation and shutdown remain consistent. See [Transaction Strategies](../../system-architecture-transactions.md) for the shared saga model.

## Runtime Data Notes

- Metrics emitted by this service feed the operator [Analytics Dashboards](../logging-admin-service/analytics-dashboards.md). Prometheus scrapes metrics from `/actuator/prometheus`.
- Logs and metrics include a `script_patch_version` label so operators can identify which hotfix revision is active.
- Built-in analytics for player behavior are a supported feature of the target service design.

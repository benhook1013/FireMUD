# Game Session Service Runtime and Data

## Implementation Status

The sections below define the target-state runtime contract. Current implementation and proof status is:

- Hidden same-type Game Session recovery within ADR 0013's qualifying conditions remains an implementation or proof gap; the target is to preserve the session through upstream rebind rather than require a player-visible re-`LOGIN` / re-`PLAY` cycle.
- Current `onCommand` ingress uses the live `{tenantId, gameInstanceId}` boundary as a region surrogate until true region partitioning is shipped; it carries the current `regionEpoch` and a producer-supplied `readSnapshotToken` derived from the command and ownership fence.
- Durable command/effect execution currently covers movement and `PUT`, `TAKE`, `WEAR`, and `REMOVE` fully at the existing boundary. `GET` and `DROP` also enqueue and execute on that durable command path, but their ADR 0054 spatial correctness remains incomplete until World `TargetingFactSnapshot` attestation/location-version propagation, the shared actor-lock/executor-fence critical section and durable barrier/handoff path, and stale-race proof are implemented. Current movement/item replay rows do not yet prove the ADR 0054 participant guard bound to root `EffectId`, typed operation, target, and request digest, or fail-closed changed reuse.
- `BLOCK` is also on the current durable effect replay/no-op seam; other state-changing command families still need to migrate onto it.
- Current Game Session code still defaults `FIREMUD_AUTH_SESSION_EXPIRATION_MS` to one hour and does not enforce the target five-minute continuity cap.
- Current Game Session code has not converged on the canonical bounded `session:game:*` index families or proved their repair protocol: it still uses transitional `sessionctx:*` and tenant/identity lookup records and does not implement/prove the generation-safe global `accountIndexMember` set, the partitioned global issuer index, or their cross-slot repair/acknowledgement flows. This is implementation/proof drift, not a transfer of ownership or a different member shape.

## Runtime Model

Game Session coordinates with Redis to store volatile session state and command queues and with PostgreSQL to persist durable game-instance control-plane metadata. It provides a single point of truth for current tick and world time while exposing gameplay-session state to the protocol front door.

The service runtime model assumes replaceable workers, not authoritative in-process state:

- Redis and PostgreSQL hold the meaningful gameplay-session, tick-coordination, and control-plane state needed for takeover.
- A Game Session instance may cache or buffer transient transport-local details while it is healthy, but those details must never be the sole source of truth for reconnect, tick ownership, or gameplay admission.
- Ordinary qualifying Game Session restarts use the bounded upstream rebind contract in [Reconnection Strategy](../../system-architecture-reconnection.md#bounded-non-edge-restart-recovery); this service retains the local replacement-worker and shared-state consequences rather than redefining the elapsed-time or authority contract.

- PostgreSQL stores `game_instances`, `game_manifest`, pinned runtime-version/script-patch selections, active runtime feature-flag overrides, and audit-relevant disconnect/remediation metadata.
- Redis stores gameplay session bindings, tick queues, timers, retries, and region leases.
- Game Session uses PostgreSQL for durable control-plane metadata and Redis as the coordination plane for gameplay-session bindings, tick queues, timers, retries, and region leases.
- Bootstrap session-context records are created as soon as a client connects. They remain unauthenticated until Account Service verifies credentials and issues a token, and gameplay commands must reject them as `LOGIN_REQUIRED` until `LOGIN` promotes the context to authenticated account scope.
- Game Session relies on the shared libraries for DTO definitions, logging interceptors, Micrometer metrics, and shared saga helpers rather than reimplementing those surfaces locally.
- Credential brute-force defense remains delegated to Account Service, which owns one login-attempt policy across transport paths using trusted source context. Game Session consumes the resulting auth outcomes when binding gameplay sessions but does not implement separate credential-abuse logic. After authentication, the current session front end owns an in-process command token bucket; it does not perform a Redis operation for every command solely for rate limiting. Before a restart or takeover initializes a replacement bucket, the new owner atomically reserves from the binding's shared bounded cumulative handoff budget, so retries or concurrent replacements cannot reset or double-consume the remaining allowance. Optional coarse shared abuse windows remain outside the per-command fast path as defined by [ADR 0034](../../decisions/adr-0034-layered-abuse-controls-without-attacker-triggered-account-locks.md).
- No single Game Session process may become the hidden source of truth for reconnectable gameplay state. If the service needs a value to survive non-edge restart or permit same-type takeover, that value must live in Redis or PostgreSQL-backed coordination rather than only in process memory.

## Redis Ownership and Coordination Rules

Game Session uses the gameplay layer’s session front-end plus lease-owner execution model:

- Connected sockets bind to a stable session front-end pod, while region-scoped tick execution remains fenced to the current `<tenantId, gameInstanceId, regionId>` lease owner.
- Session front-ends may forward work to lease owners over internal gRPC, but only lease owners may mutate region-scoped coordination state.
- Tick-related multi-key operations, including locks, pending state, queues, timers, and retry metadata, are performed exclusively via the shared Lua scripts described in [Redis Architecture](../../system-architecture-redis.md#atomicity-and-concurrency-control). Ad-hoc multi-key sequences against tick keys are not allowed outside these scripts.
- Because session bindings, leases, queues, timers, and retry markers are externalized, another Game Session instance of the same type must be able to assume session-front-end or lease-owner responsibility after an ordinary qualifying failure. Exhausted recovery, unavailable shared authority, unsafe ownership ambiguity, terminal session policy, or edge transport loss uses explicit fallback instead.
- Lease ownership and session front-end routing are deliberately takeover-ready. Another same-type Game Session instance must be able to acquire the relevant lease or front-end responsibility from shared state after restart. Replacement continues the current server-side session authority using the stable edge transport identity rather than requiring the original connect token to remain valid as fresh admission; current membership, entitlement, revocation, authorization, tenant/game scope, and fencing still apply. An exact same-binding, non-expanding reconnect or retained-edge continuation may use bounded last-known-good entitlement only for an unchanged public-production binding during an entitlement-only outage, after Account validates every other current security and authority predicate and commits the resume lease under ADR 0028. Missing, unavailable, stale, contradictory, or mismatched non-entitlement security or authority evidence fails closed; private/playtest bindings require the exact current Account-owned realm grant and `grantVersion`, with no entitlement fallback. A fresh gameplay binding remains a strict fresh-entitlement operation; retained-edge is the form that preserves the established edge without a new client transport.
- Closure of only the Gateway-to-Game-Session upstream is not authoritative player transport loss. Presence removal, disconnect lifecycle events, and gameplay-binding teardown must account for retained edge liveness and replacement registration so a successful hidden rebind neither publishes a false disconnect nor leaves the resumed player absent.

Game Session treats Redis Coordination and Cache/Rate-Limit roles as separate concerns:

- All tick, lock, timer, retry, and session coordination keys live on Coordination Redis and are accessed only via the Lua Script Registry and shared key builders.
- Game Session code that runs inside the tick engine, including the tick scheduler, staging/commit flows, and lease management, never reads or writes Cache/Rate-Limit Redis directly.
- Cache lookups and invalidations remain encapsulated inside domain services, which own correctness and treat caches as performance hints only.

### Redis role and prefix details

- Coordination prefixes are reset-tolerant in line with [Redis Reset & Recovery](../../system-architecture-redis-reset-and-recovery.md), except for reset-sensitive gameplay session bindings under `session:game:*`. That document owns reset lifecycle and session policy; Game Session retains only local consequences: the canonical session record is the policy-selected binding, while derived gameplay indexes reconcile independently under either policy. Account token and generation authority remains outside session policy. Game Session’s coordination design must therefore remain safe under the documented Redis tail-loss envelope rather than assuming perfect recovery of every transient coordination key.
- The canonical opaque `{tenantRegionTag}` is derived by the shared key builders from the full `<tenantId, gameInstanceId, regionId>` tuple. It is the region scope for lease, queue, timer, retry, pending, and lock keys below; key examples must retain `{tenantRegionTag}` rather than substituting a differently named or partial tag.
- Coordination ownership includes registered Lua-script access to prefixes such as:
  - `tick:{tenantRegionTag}:queue:<entityId>`
  - `tick:{tenantRegionTag}:pending`
  - `tick:{tenantRegionTag}:lock:<entityId>`
  - `timer:{tenantRegionTag}`
  - `retry:{tenantRegionTag}`
  - `tick-executor-lease:{tenantRegionTag}`
  - `remote:{tenantInstanceTag}:<entityId>` and related coordination prefixes listed in the [Redis Cheat Sheet](../../system-architecture-redis-cheatsheet.md)
- `remote:{tenantInstanceTag}:<entityId>` derives `{tenantInstanceTag}` from `<tenantId, gameInstanceId>` and remains a best-effort hint marker for cross-region follow-ups; durable follow-up state lives in PostgreSQL via the tick effect ledger and follow-up tables.
- During a cold start, Game Session must not recreate `tick:{tenantRegionTag}:meta` from the hot path unless the recovery boundary supplies explicit proof that the exact environment and deployment are a documented non-reset profile. Otherwise the scope remains fenced under [Redis Reset & Recovery](../../system-architecture-redis-reset-and-recovery.md#failover-vs-cold-start-vs-reset).
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

Session state needed for reconnect recovery is stored under `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`. This expiring record is not the sole liveness authority for a continuously connected player: the live transport plus its fenced region-local gameplay binding remain active while current authorization and the active-session lease remain valid. Passing `continuityBindingExpiresAt` atomically retires only reconnect eligibility and the expiring recovery record; it does not remove the live region binding or close an otherwise authorized socket. A later disconnect cannot recreate or resume that expired continuity episode. Every reconnect-record create/update/heartbeat/refresh uses an absolute, server-side expiry that preserves the earliest existing physical deadline and never exceeds `continuityBindingExpiresAt`; when the anchor has passed, the operation retires reconnect state and fails any resume attempt closed rather than issuing a zero-TTL success. The target split between active gameplay liveness and expiring reconnect state remains incomplete while current `sessionctx:*` storage conflates those concerns.

Current Game Session code also maintains a `sessionctx:*` Redis-backed session-context family for bootstrap and implementation-local lookup indexes:

- `sessionctx:session:<sessionId>:context` maps a transport session id to its current context.
- `sessionctx:<tenantId>:<sessionId>:context` stores tenant-scoped session context.
- `sessionctx:<tenantId>:identity:<gameInstanceId>:<characterId>:context` and `sessionctx:<tenantId>:identity:<gameInstanceId>:name:<characterName>:context` support current takeover and reconnect lookups.

Unauthenticated `sessionctx:*` entries may exist before `LOGIN`; they are bootstrap context only and must not authorize gameplay commands. Authenticated gameplay semantics still follow the `session:game:*` / region-binding contract in the Redis architecture docs. If this implementation-local key family is renamed or collapsed into `session:game:*`, the pre-auth vs authenticated distinction remains mandatory.

Game Session owns the live `sessionctx:*` family and applies only its local reset consequence: reset invalidates or rebuilds this bootstrap/lookup context and never preserves it. The canonical reset lifecycle and session-policy choice remain in [Redis Reset & Recovery](../../system-architecture-redis-reset-and-recovery.md#coordination-reset-model). Account owns the live legacy `session:auth:account:*` and `session:auth:tenant:*` projections; Game Session does not reset or repair them, and gameplay session policy does not change their Account-owned behavior.

Target-state Game Session maintains the bounded active-binding index families owned by [Session Behavior](../../system-architecture-session-behavior.md#session-and-identity-management). The local Redis key shapes are:

- `session:game:index:character:{tenantGameplayTag}:<gameInstanceId>:<characterId>` -> `sessionId`
- `session:game:index:account:<accountId>` -> one intentionally global, account-wide generation-safe set of tenant-qualified `accountIndexMember` values across all tenants; each target member contains the complete `bindingRef`, positive `bindingGeneration`, and `accountIndexFence` defined by [Session Behavior](../../system-architecture-session-behavior.md#global-account-active-binding-index)
- `session:game:index:account-tenant:{tenantGameplayTag}:<accountId>` -> active `sessionId` set
- `session:game:index:tenant:{tenantGameplayTag}` -> active `sessionId` set
- `session:game:index:realm-grant:{tenantGameplayTag}:<worldSlug>:<realmSlug>:<accountId>` -> active `sessionId` set for grant-gated realms
- `session:game:index:issuer:{issuerIndexLayoutTag}:<issuerId>:<partitionId>` -> partitioned global issuer active-binding hash, using the immutable layout and `bindingRef` fields defined by the [Redis issuer-index contract](../../system-architecture-redis.md#issuer-active-binding-index) across all tenants

These indexes are derived gameplay projections, not preserved-session state or Account authority. Game Session is the target-state local writer and reconciler for them under the reset consequences in [Redis Reset & Recovery](../../system-architecture-redis-reset-and-recovery.md); affected tenant-owned indexes may be dropped and rebuilt under either explicit session policy, while the global account index and partitioned global issuer index require their separate account-wide or issuer-wide owner-defined coverage before replacement. A region/tenant parent may repair only its local binding consequences and consume a complete issuer-wide child or standalone issuer-wide recovery result; it may not claim issuer coverage from a local snapshot. Binding transitions use the owner-defined generation/fence and repair protocol; this service must not use wildcard scans or treat index presence as authorization. Missing or ambiguous index/coordination state fails closed for admission and reconnect, while local cleanup remains idempotent.

Gameplay session bindings persist the local identity and receiver-validation fields required by the owner contract:

- `accountId`, `tenantId`, `gameInstanceId`, `characterId`, session identity, current authority/binding generation, membership/grant freshness, and the applicable revocation/lease evidence.
- Every authenticated gameplay binding must persist the complete canonical `schemaVersion=2` payload from [Session Behavior](../../system-architecture-session-behavior.md#session-and-identity-management), including exact token identity/times/generation, `issuanceFence`, the protected single-use Account `rebindHandle`, immutable continuity deadline, membership baseline, authority tuple, and complete outbox checkpoints. Shape validation is field-specific: scalar authentication fields retain their canonical scalar forms, while the membership baseline/maps, authority tuple, and checkpoint set require their exact object/collection shapes. A binding made non-resumable does not gain a reduced authenticated schema or become reusable evidence; fresh `LOGIN`/`PLAY` establishes a new complete binding.
- The raw `game-session-account-delegation` JWT remains process-local and is never persisted in a binding, log, metric, or trace. Gameplay-domain RPCs use concrete workload identity and typed `PlayerExecutionContext`, not a per-call player JWT.

Game Session validates the immediate Account response, binding identity, membership/grant scope, authority evidence, lease fence, and current routing/ownership before accepting or restoring a binding. The exact refresh, rebind, installation, logout, and revocation protocol is canonical in [Session Behavior](../../system-architecture-session-behavior.md#active-session-token-refresh-required); a failed or ambiguous replacement remains non-admissible and is cleaned up idempotently.

## Tick Coordination and Lease Ownership

Game Session acts as the authoritative tick executor for each `<tenantId, gameInstanceId, regionId>` region scope it owns:

- It participates in region leadership using the Redis lease key `tick-executor-lease:{tenantRegionTag}` described in [Redis Architecture](../../system-architecture-redis.md#region-leadership-and-tick-executor-lease).
- While it holds the lease for a region, it is the only instance allowed to consume commands from that region’s queues and timers, drive `tick:{tenantRegionTag}:pending`, and issue tick-scoped gRPC calls on behalf of that region’s commands.
- On crash or deliberate handoff, another instance acquires the lease and resumes tick processing from Redis using the epoch-scoped `(regionEpoch, tickId)` timeline and EffectId/effect-guard rules from the tick-system design.
- Tick execution correctness must not depend on process-local executor memory surviving restart. Lease acquisition, staged tick state, retry metadata, and resumable session bindings are all externalized specifically so another Game Session instance can continue work from shared state.
- The executor monitors `tick_execution_time_ms_p99` and `tick_lock_ttl_ms` for each region. If a region repeatedly produces over-TTL ticks according to the thresholds described in the Redis and Tick architecture docs, it marks that region as degraded, automatically reduces tick fan-out, emits explicit degraded-region metrics, and may halt new ticks and reject new commands for that region until operators intervene.
- Changing `tick_interval_ms` is not part of this in-place degradation path. Any live cadence change that alters timer ordering must run as an epoch-bumped maintenance operation with pause, timer re-derivation, and resume on the new `regionEpoch`, as defined in the tick and scaling docs.
- The staging Lua script only moves a bounded number of commands each tick, governed by `GAME_TICK_MAX_COMMANDS`, so one player cannot starve others. Commands marked `requiresSoloTick: true` are dequeued into isolated ticks so expensive operations such as runtime procedural generation do not share time with normal actions.
- If a command cannot acquire its required entity lock set, the executor fails the attempt, rolls back staged changes, and reschedules the command with bounded tick-based backoff, for example exponential backoff capped by `MAX_BACKOFF_TICKS`, while tracking a per-command retry counter and enforcing `MAX_RETRIES` before surfacing a player-visible error and logging a permanent failure.

These degraded and halt transitions follow the same thresholds and policies captured under [Redis Architecture – Operational SLOs & Alert Thresholds](../../system-architecture-redis.md#operational-slos--alert-thresholds) so operators and implementations share a single set of red lines for coordination health.

For `DROP` and `PICKUP`, Game Session acquires the actor lock keyed by the complete gameplay scope and `actorEntityId`, captures the current `(regionEpoch, executorFence)`, and durably records an in-flight barrier keyed by the existing scoped root identity `(tenantId, gameInstanceId, EffectId)` and bound to the actor, `RoomInstanceRef`, epoch, fence, and canonical immutable `requestDigest` before World validation. It owns lock/retry orchestration, renews the actor-lock lease through Entity commit acknowledgement, and invokes Game Logic to re-resolve stale evidence under the same root `EffectId`, preserving that `requestDigest`; Entity verifies the attestation, guard, and `requestDigest` at local commit. A changed request is rejected rather than reusing the root. Lock-renewal failure, owner crash, or fence change fences the old orchestrator and leaves the barrier reconciliation-required. Handoff/recovery may reconcile the exact root but MOVE admission must pass the same actor lock/barrier gate and cannot authorize a conflicting move merely because Redis lock state expired; a later valid `MOVE` is allowed only after terminal barrier evidence. The detailed local-transaction and proof contract is in [Transaction Strategies](../../system-architecture-transactions.md#drop-pickup-targeting-and-actor-fence-critical-section).

Tick coordination is region-scoped, not session-scoped. Tick queues, locks, timers, retry metadata, and `tick:{tenantRegionTag}:pending` use the `tick:{tenantRegionTag}:...` prefix described in [Redis Architecture](../../system-architecture-redis.md#tick-integration-resilience-locking-staging). Region keys follow region lifecycle and crash-recovery rules:

- `tick:{tenantRegionTag}:pending` is created without a TTL so it survives process crashes and failover.
- It is cleared only when the tick is successfully committed or an operator-driven recovery flow explicitly marks the tick as skipped/failed and removes the key.

Session shutdown therefore cleans up session keys but does not implicitly delete region-level tick coordination keys.

## Reconnection and Disconnect Handling

Game Session restores player sessions after disconnects and enforces single-session control as outlined in the [Reconnection Strategy](../../system-architecture-reconnection.md). For Telnet clients, it also consumes best-effort, at-least-once `NotifyDisconnect` events emitted by the TCP Proxy Service over an internal gRPC link and treats them as idempotent hints keyed by `<proxyConnectionId, disconnectSequence>` rather than a guaranteed source of truth.

Game Session persists the latest processed `disconnectSequence` per `<proxyConnectionId>` and ignores older or duplicate events so retry behavior at the proxy can remain simple while consumption stays idempotent. Any `{tenantId, gameInstanceId}` values coming from proxy-owned bootstrap metadata or future hidden MCP-carried smart-client hints remain advisory context that may be used for logging and audit, but they are not trusted as authorization claims or binding directives. Game Session still validates any game-instance ownership claims against Redis and its authenticated session state before rebinding the transport.

Recent/offline account-presence state should preserve the last admitted routing bundle too. When live presence drops to recent presence after transport loss, takeover, or logout, the bounded recent-presence record keeps the last admitted `gameInstanceId`, `worldSlug`, `realmSlug`, and `pointerVersion` so account-presence and friend-presence reads can continue to describe the last resolved realm target directly instead of collapsing immediately to a routing-less timestamp.

Deliberate logout remains a different lifecycle from transport loss. `LOGOUT` immediately makes the binding's private transcript non-replayable and removes the session's reconnect-oriented replay and restore eligibility; physical transcript deletion may complete asynchronously. It retires the live gameplay presence row, records bounded recent-presence disconnect disposition as deliberate logout, and routes gameplay-bound runtime shutdown through the shared termination seam instead of preserving a reconnect-suspended gameplay shell.

Game Session also owns the canonical live gameplay-presence substrate rather than treating authenticated session context as an online-presence proxy. The bounded live presence record contains tenant/game-instance/account/character identity, current role bucket for `WHO`, explicit-AFK state, accepted-command activity, meaningful-gameplay activity, and the admitted routing bundle used by later account/friend presence reads. It does not resolve, cache, or transport profile visibility policy. Player-facing `WHO` is scoped to the current game instance and reads this presence substrate directly, while later social consumers use the same substrate plus bounded recent-presence handoff and Account-owned policy instead of rebuilding online/offline truth from raw Redis session shells.

## Runtime Feature Flags

Feature flags are stored in the `feature_flag` table and can be toggled through the Logging & Admin Service. Game Session exposes a gRPC `ToggleFeatureFlag` method so administrators can enable or disable experimental behavior without restarting a session. See [Game Design Service Feature Flags](../game-design-service/feature-flags.md) for how definitions are created and published.

## Script Patch Version Pinning and Rollback

Each running game instance has a pinned `scriptPatchVersion` alongside its `runtimeVersion`:

- Event ingress to the Automation & Scripting Service includes the currently pinned `scriptPatchVersion` so script evaluation is tied to the active patch for the instance.
- Game Session emits `onCommand` events after durable player-command staging and immediate tick kick when a gameplay session context, pinned script patch, and current runtime ownership row are all available.
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

- built-in movement input now enqueues durably instead of performing the current Game Session room-binding/projection update directly in the dispatch handler; World-authoritative character location and occupancy mutation remains unimplemented;
- movement execution reuses the shared move-planning logic to preserve player-visible semantics, but `MoveCommandHandler` no longer exposes a public synchronous session-write path and the current Game Session room-binding/projection update now flows through one dedicated domain-local replay seam keyed by `effectId`; this is not the World-authoritative location/occupancy mutation and does not prove the ADR 0054 operation/target/request-digest participant guard;
- duplicate movement application at this current seam converges to replay/no-op rather than a second room-binding update; changed reuse of an `effectId` is not proven fail-closed;
- player-visible movement output is delivered asynchronously through the same active-websocket plus screen-buffer-aware delivery path used for runtime recipient delivery;
- item/equipment/container mutation commands, including `GET`, `DROP`, `PUT`, `TAKE`, `WEAR`, and `REMOVE`, enqueue durably from the player-facing command path and execute from the durable post-drain effect executor before their player-visible outputs are delivered; `PUT`, `TAKE`, `WEAR`, and `REMOVE` are current domain coverage, while `GET` and `DROP` remain partial/blocked for ADR 0054 spatial correctness pending World `TargetingFactSnapshot` attestation/location-version propagation, the shared actor-lock/executor-fence critical section and durable barrier/handoff path, and stale-race proof;
- Game Session passes the durable `tick_effect.effectId` to Entity Management for those item/equipment/container mutations so duplicate downstream delivery can replay the stored domain response instead of applying the mutation again. This current `{tenantId,effectId}` identity is domain-local replay only, not the ADR 0054 participant guard bound to root effect, typed operation, target, and request digest; changed reuse is not proven fail-closed;
- `BLOCK` is the first transient action-state command on the same durable execution seam: Game Session stores/replays the durable effect, Game Logic routes `ApplyActorCondition`, and Entity Management persists the short-lived `blocking` active condition.
- read-only item views such as `INVENTORY`, `EQUIPMENT`, and `CONTAINER` remain direct view commands because they do not mutate authoritative gameplay state.

Pure view/meta commands may remain direct until there is a concrete reason to queue them, but state-changing gameplay families should not introduce new synchronous bypasses.

### Automation Command Admission

Fairness-critical automation enters gameplay through the same durable command/admission boundary as player commands, not through direct Automation & Scripting writes to `tick:*` Redis keys.

- Automation & Scripting calls Game Session with a stable `automationDispatchId`, target `(tenantId, gameInstanceId, regionId, regionEpoch)`, optional `dueTickId` for due-point-aware automation, target `entityId`, and deterministic command payload.
- Game Session records or reads a durable `gameplay_command` admission row through `EnqueueAutomationCommandIfAbsent`, keyed by `(tenantId, gameInstanceId, regionId, regionEpoch, automationDispatchId)`, before mutating Redis. Existing dispatches return duplicate/no-op from that durable key. New dispatches are fenced against the current durable runtime owner row before Redis mutation: missing ownership, paused ownership, or mismatched `regionEpoch` returns a non-mutating rejection. The row stores optional `dueTickId`, target entity, script/work-item correlation fields, command payload, and current execution outcome. Target-state action execution persists a structured `GameplayActionOutcome` rather than relying on one free-form result string; Game Session renders its separate semantic presentation events without interpreting target-leg state itself.
- Duplicate requests for the same key return replay/no-op outcomes from the durable row. Conflicting payloads for the same key are validation failures and must not enqueue.
- Only after durable admission succeeds does Game Session invoke the region-lease Redis script that materializes the command into `tick:{tenantRegionTag}:queue:<entityId>`.
- If Redis enqueue fails after durable admission, Game Session retries materialization from the durable row or converges the row to a terminal non-applied outcome under the command-status rules. Redis queue contents are never the sole proof that the automation action existed.

## Saga Participation

Game startup and shutdown are coordinated using the shared `Saga` helpers from `firemud-common`. Each dependent service, including World Management, Entity Management, and Game Logic, confirms its part of the workflow before the session becomes active. Failures trigger compensating steps so session activation and shutdown remain consistent. See [Transaction Strategies](../../system-architecture-transactions.md) for the shared saga model.

## Runtime Data Notes

- Metrics emitted by this service feed the operator [Analytics Dashboards](../logging-admin-service/analytics-dashboards.md). Prometheus scrapes metrics from `/actuator/prometheus`.
- Logs, traces, and control-plane reads expose the active script patch version so operators can identify which hotfix revision is active without adding raw `script_patch_version` Prometheus labels.
- Built-in analytics for player behavior are a supported feature of the target service design.

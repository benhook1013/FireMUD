# FireMUD System Architecture: Redis (Hub)

This document is the **conceptual hub** for Redis in FireMUD. It explains the mental model, core invariants, key naming rules, and topology expectations, and then points you to more detailed, role‑specific Redis docs.

> 🔗 For detailed design and operations, see:
>
> - **Usage & Profiles** – how we use Coordination vs Cache/Rate‑Limit Redis and how environments are configured  
>   `system-architecture-redis-usage-and-profiles.md`
> - **Reset & Recovery** – coordination reset model, reset vs repair, incident scenarios  
>   `system-architecture-redis-reset-and-recovery.md`
> - **Design Checklist** – required checks before changing keys, scripts, or profiles  
>   `system-architecture-redis-design-checklist.md`
> - **Redis Lua Patterns** – script categories, idempotency rules, `schemaVersion` handling  
>   `system-architecture-redis-lua-patterns.md`
> - **Redis Operations & Migrations** – AOF profiles, coordination reset flows, and migrations  
>   `system-architecture-redis-operations.md`
> - **Coordination Redis Ops Access & Tooling** – ACLs, allowed commands, and ops tooling rules  
>   `system-architecture-redis-ops-access.md`
> - **Redis Cache & Rate Limiting** – cache/rate‑limit key design and correctness classes  
>   `system-architecture-redis-cache.md`
> - **Redis Cheat Sheet** – prefix/role table and “which doc to read” map  
>   `system-architecture-redis-cheatsheet.md`

---

## Table of Contents

- [How to Use This Hub](#how-to-use-this-hub)
- [Redis Coordination Invariants](#redis-coordination-invariants)
- [Redis Profiles](#redis-profiles)
- [Redis as a Volatile State Layer](#redis-as-a-volatile-state-layer)
- [Atomicity and Concurrency Control](#atomicity-and-concurrency-control)
- [Key Naming and Shard Discipline](#key-naming-and-shard-discipline)
- [Topology Compatibility Overview](#topology-compatibility-overview)
- [External Invariants Redis Depends On](#external-invariants-redis-depends-on)
- [Related Documentation](#related-documentation)

---

## How to Use This Hub

Different readers care about different aspects of Redis:

- **Service authors** – “Can I store this in Redis? Which role and prefix do I use? What invariants must I respect?”  
  - Start with this hub plus:
    - Usage & Profiles (`system-architecture-redis-usage-and-profiles.md`)
    - Redis Cache & Rate Limiting (`system-architecture-redis-cache.md`)
    - Redis Design Checklist (`system-architecture-redis-design-checklist.md`)
- **Lua script authors/reviewers** – “How do I write safe, idempotent coordination scripts?”  
  - Start here to understand invariants, then go to:
    - Redis Lua Patterns (`system-architecture-redis-lua-patterns.md`)
    - Redis Design Checklist (script section)
- **Operators/SREs** – “How do we run Redis safely and recover from incidents?”  
  - Start here for invariants and profiles, then go to:
    - Usage & Profiles
    - Redis Operations & Migrations
    - Redis Reset & Recovery
    - Coordination Redis Ops Access & Tooling

For quick answers about prefixes and which doc to open next, use the **Redis Cheat Sheet** (`system-architecture-redis-cheatsheet.md`).

When this hub introduces or sharpens canonical coordination contracts, keep the cheat sheet and the owning service Redis sections in sync with the same names and authority split. That applies especially to:

- `tick:{tenantRegionTag}:session-binding:<entityId>`
- `binding_generation`
- `automationDispatchId`

---

## Redis Coordination Invariants

FireMUD uses Redis as a **transient, high‑performance coordination layer**, not as a primary source of truth. The following invariants apply to all coordination designs:

### Log of Record vs Coordination Buffer

Redis coordination keys form a long-running, tail-loss-bounded **coordination buffer**, not the durable log of record for gameplay effects:

- Durable history for tick-driven outcomes (for example, “which effects were applied or abandoned for a given `(tenantId, gameInstanceId, regionId, region_epoch, tickId, effectKey)`”) lives in PostgreSQL via the tick effect ledger and domain idempotency tables described in `system-architecture-tick-failures-and-operations.md` and `system-architecture-transactions.md`.
- Coordination Redis holds volatile structures such as tick queues, `pending` sets, timers, region leases, tick event streams, and scheduler offsets; these structures are expected to be subject to bounded tail-loss and scoped resets as defined in this document and the Redis reset/runbook docs.
- Application and ops designs must not treat AOF contents or Redis key history as the primary log for audits, analytics, or long-term effect replay; those concerns belong in PostgreSQL-backed ledgers and domain stores.

- **Coordination timeline = `(regionEpoch, tickId)`**
- For each `<tenantId, gameInstanceId, regionId>` the canonical coordination timeline is the pair `(region_epoch, tickId)`:
  - `region_epoch` lives in PostgreSQL and is advanced by the tick control plane when a scoped coordination reset occurs (or when explicitly performing topology/maintenance operations that intentionally sever the old timeline for a region).
  - `tickId` is monotonic per `<tenantId, gameInstanceId, regionId>` within a given `region_epoch` and is carried on all tick‑driven calls and ledger entries.
- **Bootstrap vs stream**
  - The authoritative **baseline** for `(region_epoch, tickId)` comes from Game Session’s control/status surface (for example a `GetRegionTickStatus` API) backed by a PostgreSQL `RegionStatus`-style table; new consumers and operational tooling must obtain their initial view of the timeline from there rather than inferring it from Redis keys.
  - Long‑lived consumers then follow `StreamTickHeartbeats` as the authoritative progression of the timeline after that baseline; if a heartbeat disconnects or an epoch bump is observed, they reconcile using the control API plus durable domain state before resuming.
  - Redis coordination structures (including `tick:{tenantRegionTag}:*`, timers, retries, tick event streams, and scheduler offsets) are treated purely as volatile buffers; they may be partially lost or reset within the documented tail-loss envelope (`tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)` in `system-architecture-redis-operations.md`) and are never considered the primary source of truth for epoch or tick counters.
  - Tick-scoped Redis staging state (for example `tick:{tenantRegionTag}:pending`, effect batches, and other data created for one in-flight tick) and all corresponding PostgreSQL tick ledger rows conceptually belong to exactly one `(region_epoch, tickId)` on this timeline.
  - Region-scoped source structures such as `tick:{tenantRegionTag}:queue:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, `tick-executor-lease:{tenantRegionTag}`, tick event streams, and scheduler offsets are primarily epoch-scoped coordination state:
    - They belong to the current `region_epoch`.
    - They carry eligibility/order metadata that may later be materialized into a specific `(region_epoch, tickId)` when a batch is staged.
    - Reset/replay tooling and Lua validation must therefore distinguish epoch-scoped source state from tick-scoped staged state.
  - A single **per-region metadata key** captures the Redis-side view of this timeline for coordination scripts:
    - `tick:{tenantRegionTag}:meta` is a hash that stores at least:
      - `region_epoch` – the epoch currently considered valid for this `<tenantId, gameInstanceId, regionId>` in Redis.
      - `current_tick_id` – the highest **staged** `tickId` for this region as observed by the tick executor’s coordination scripts. It is a monotonic guard for Redis writes only; it is **not** the source of truth for “last committed tick”.
      - `current_tick_state` – the Redis-side execution state for `current_tick_id`. Allowed values are:
        - `STAGED` – Redis `pending`/queue state exists for this tick and hot-path scripts may continue to add idempotent entries for the same tick.
        - `RESOLVING` – durable domain/application work for this tick is in progress or being reconciled; no newer tick may be staged yet.
        - `APPLIED` – all required effects for this tick have reached a durable applied/no-op terminal outcome in PostgreSQL-backed handlers or the reconciliation backlog.
        - `ABANDONED` – the tick was intentionally terminated for the current epoch (for example due to reset/recovery) and no more work for that `(region_epoch, tickId)` may be staged through hot-path scripts.
      - `current_tick_terminal_at_ms` – caller-supplied timestamp marking when `current_tick_state` first entered `APPLIED` or `ABANDONED`; used for observability and bounded cleanup only, never for correctness decisions inside Lua.
    - Illustrative Redis hash contents:

      ```text
      HGETALL tick:{tenant-demo:region:starter-village}:meta
      region_epoch               14
      current_tick_id            9285
      current_tick_state         RESOLVING
      current_tick_terminal_at_ms
      ```

    - The canonical Redis-side state machine for a region is:
      - `missing meta` or `current_tick_state in {APPLIED, ABANDONED}` with no newer tick staged:
        - The next winning executor may initialize or advance the meta record to `current_tick_id = requestedTickId`, `current_tick_state = STAGED` if `requestedTickId` is exactly the scheduler/control-plane tick derived from PostgreSQL RegionStatus for that region.
      - `STAGED -> RESOLVING`:
        - The first script or caller that hands staged effects to durable domain/application processing flips the state to `RESOLVING`.
        - Replays for the same tick must treat `STAGED` and `RESOLVING` as the same logical in-flight tick and may only add idempotent effect entries for that same `current_tick_id`.
      - `RESOLVING -> APPLIED`:
        - Only after the durable tick ledger and/or reconciliation backlog for `(tenantId, gameInstanceId, regionId, region_epoch, tickId)` shows all required participants at applied/no-op terminal outcomes.
      - `RESOLVING -> ABANDONED`:
        - Only after the control plane or recovery tooling has made an explicit terminal decision to abandon the tick for the current epoch.
      - `APPLIED` or `ABANDONED` for tick `T`:
        - Cleanup may delete `pending`, retry markers, and other Redis-only remnants for tick `T`.
        - `APPLIED`/`ABANDONED` marks the durable terminal state only; it does not by itself prove the region is no longer in flight for scheduler purposes.
        - Staging for tick `T+1` is allowed only after tick `T` is both terminal in Redis meta and `coordination_cleared` under the scheduler/runtime rules in `system-architecture-ticks.md`.
    - Tick- and epoch-aware Lua scripts:
      - Read `region_epoch` (and when needed `current_tick_id`) from this key and compare it to the expected epoch/tick supplied from PostgreSQL/lease context.
      - Return non-mutating outcomes such as `"STALE_EPOCH"` when the stored epoch does not match the expected value, so callers can abandon work tied to an old epoch and reacquire leases under the new epoch.
      - Treat `current_tick_state` as the gate for hot-path progress:
        - Staging scripts may create or extend `pending` only when `requestedTickId == current_tick_id` and `current_tick_state in {STAGED, RESOLVING}`, or when they are initializing the next tick from a terminal prior state.
        - Hot-path scripts must never advance directly from `STAGED`/`RESOLVING` to a newer `current_tick_id`; only a terminal `APPLIED` or `ABANDONED` state plus separate scheduler-observed `coordination_cleared` unlocks the next tick.
    - Schedulers and operators:
      - Obtain their authoritative baseline for `(region_epoch, tickId)` from PostgreSQL RegionStatus/tick effect ledger and heartbeats, not from `current_tick_id`.
      - On a normal cold start with empty Coordination Redis, the next winning tick executor initializes or recreates `tick:{tenantRegionTag}:meta` during hot-path staging from PostgreSQL `RegionStatus`; schedulers and operators do not treat missing `meta` as a manual pre-seeding task.
      - Treat Redis `pending` contents as an implementation detail of the hot path, not as proof of durable convergence. The durable proof that a tick is safe to move past lives in PostgreSQL-ledger and reconciliation state, after which the caller records `APPLIED` or `ABANDONED` in `tick:{tenantRegionTag}:meta`.
      - Recovery after tail loss or reset does **not** reconstruct old ticks by silently restaging them through normal hot-path scripts. Recovery completes or abandons older work from durable manifests, ledger rows, and reconciliation backlog state, then records the resulting terminal meta state before allowing newer ticks to stage.
- Split‑brain detection, replay, and reset handling treat this timeline as the arbiter of “which work is valid”:
  - If multiple executors attempt to own the same `<tenantId, gameInstanceId, regionId>` with different `region_epoch` values, the highest epoch wins and lower epochs are treated as stale.
  - After a region‑ or tenant‑scoped reset, a new `region_epoch` is created and any surviving coordination state from older epochs is ignored or explicitly cleaned up by reset tooling.

- **Non‑authoritative for game data**
  - Canonical game state (accounts, entities, items, rooms, instances) lives in PostgreSQL and domain services.
  - Redis holds **volatile coordination state**: tick queues and locks, timers, session bindings, automation hints, retry metadata, and similar.
  - Losing coordination state within a bounded window must not create irreversible financial effects, cross‑tenant data leaks, or unfixable domain inconsistencies.

- **Tail‑loss envelope**
  - Coordination Redis is configured with AOF and sized so that **only a small tail** of recent coordination state per `<tenantId, gameInstanceId, regionId>` may be lost during failover or restart. In production‑like environments, the canonical envelope is:
    - `tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)` (see `system-architecture-redis-operations.md`).
  - Designs must tolerate the loss of a few ticks’ worth of:
    - Commands, staged effects, timers, and retry markers, and
    - Session liveness hints and other advisory metadata.
  - In terms of the coordination timeline:
    - A normal failover or bounded tail‑loss event may drop or replay the last `N` ticks on the timeline for a `<tenantId, gameInstanceId, regionId>`, where `N` corresponds to the configured tail‑loss SLOs in `system-architecture-redis-operations.md` (computed from `tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)`).
    - Tick effect ledger behavior and domain idempotency rules (see `system-architecture-tick-failures-and-operations.md`) must guarantee that those dropped/replayed ticks converge to a final state where each `(tenantId, gameInstanceId, regionId, region_epoch, tickId, effectKey)` is either durably applied or durably abandoned, never left indefinitely “half‑applied”.
  - Flows that **cannot** tolerate this tail‑loss (for example, real‑money purchases, cross‑tenant transfers, or unique external side effects) must use durable domain mechanisms and may only use Redis for optional coordination.

- **Idempotent replay and monotonic guards**
  - Tick and session scripts are designed so that:
    - AOF replay cannot double‑apply logical effects, and
    - Replays after failover respect monotonic guards such as `tickId`, `generation` counters, and lease/lock tokens.
  - Lua scripts must treat Redis as the single coordination state for their keys, re‑deriving their desired state from current contents and arguments rather than relying on in‑process history.

- **Region authority**
  - For each `<tenantId, gameInstanceId, regionId>` there is at most **one active tick executor** at a time, guarded by a region‑scoped lease key.
  - All tick queues, locks, timers, and pending sets for that region live in a single hash‑slot‑compatible keyspace (see [Key Naming and Shard Discipline](#key-naming-and-shard-discipline)).

- **Session binding**
  - Session keys in Redis bind player connections and tick participation to authenticated platform identities. They do not own actor cooldown state.
  - Session binding is monotonic: once a session is rebound or terminated, old bindings are not resurrected, even under replay or tail‑loss.
- Session keys are **not** the authoritative runtime record for region-local gameplay participation. To preserve shard locality in Redis Cluster:
  - `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` remains the authoritative record for connection identity, reconnect eligibility, auth/session CAS fields, and desired gameplay attachment generation.
  - Region-local gameplay attachment is authoritative under the region-scoped key family `tick:{tenantRegionTag}:session-binding:<entityId>`, owned by Game Session and mutated only by region-lease scripts.
  - Any region list or attachment summary mirrored inside `session:game:*` is advisory/reconnect metadata only and must not be used as the sole authority for gameplay admission or command routing.
- This split exists specifically so same-type non-edge workers can take over after restart. If a value is required to preserve gameplay binding, region participation, or resumable command eligibility across Game Session/Game Logic restart, it must be externalized through these authoritative Redis/PostgreSQL-backed structures rather than hidden in process memory.

The **Redis Design Checklist** (`system-architecture-redis-design-checklist.md`) turns these invariants into concrete review steps for new prefixes, scripts, and flows.

### Redis Availability, Consistency, and Safety Guarantees

From an application perspective:

- Coordination Redis is expected to be **highly available** within the limits of the chosen profile (for example, `production_clustered`).
- Tail-loss is bounded to a **small window** per `<tenantId, gameInstanceId, regionId>`; losing more than this window is treated as an incident and investigated using the metrics and alerts defined in `system-architecture-redis-operations.md`.
- Lua scripts and domain idempotency guarantees ensure that replay and partial loss of coordination keys do not:
  - Double-apply critical effects.
  - Violate cross-tenant isolation.
  - Break financial or security invariants.

Operational details (failover behavior, AOF expectations, and tail-loss observability) are expanded in `system-architecture-redis-operations.md` and `system-architecture-redis-reset-and-recovery.md`.

---

## Atomicity and Concurrency Control

Multi-key coordination operations in FireMUD must remain **shard-local, idempotent, and lease-guarded**:

- All mutating operations on coordination prefixes (tick queues, `pending` sets, timers, retry structures, locks, leases, and session keys) are performed via registered **Lua scripts** that:
  - Validate lease tokens, lock tokens, and monotonic guards (`tickId`, epochs) before writing.
  - Operate only on keys that share the same hash tag and cluster slot.
  - Are deterministic and idempotent with respect to their `KEYS`/`ARGV` arguments and current Redis state.
- Application code and maintenance tooling must **not** issue ad-hoc multi-key Redis commands (including `MULTI/EXEC`) over coordination prefixes.
- Cross-region workflows are implemented as per-region operations or higher-level sagas, not as cross-region Redis transactions.

The detailed scripting rules and categories (region-lease scripts, session-only scripts, automation scripts, maintenance scripts) are defined in `system-architecture-redis-lua-patterns.md`, and the concrete checks to apply during design review are captured in the Lua section of `system-architecture-redis-design-checklist.md`.

---

## Session Keys and Gameplay Binding

Game Session uses Redis for two related but distinct session concerns:

- **Bootstrap/pre-auth transport context** is created when a socket connects and before gameplay authentication completes. Current Game Session implementations store this context under the `sessionctx:*` key family, such as `sessionctx:session:<sessionId>:context` and `sessionctx:<tenantId>:<sessionId>:context`, with unauthenticated fields such as `accountId = 0`, no `authTokenHash`, no `membershipVersion`, and only bootstrap scope such as tenant, locale, or initial game-instance hints. Gameplay commands must treat these entries as unauthenticated until `LOGIN` succeeds.
- **Authenticated gameplay session state** is created or promoted after successful `LOGIN` and `PLAY`. The canonical target key family for this state is `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`.

Authenticated gameplay session keys capture:

- Socket binding metadata and transport details.
- Active `characterId` / `tenantId` context.
- Server-side auth token identity used for backend calls on behalf of this session (for example `authTokenHash` and `authTokenIssuedAt`), so resume and mid-session revocation checks can be performed without exposing JWTs to gameplay clients.
- Authoritative tenant membership freshness metadata (for example `membershipVersion`) so reconnect/resume can verify that gameplay admission authority still exists before rebinding.
- Tick-region participation metadata (for example active region bindings and reconnect context). Per-entity command queues remain under `tick:{tenantRegionTag}:queue:<entityId>` and are reset-tolerant coordination state, not durable session payload.
- Session-local coordination metadata (for example reconnect state, transport-level pacing, and other per-connection ephemeral fields).

The pre-auth `sessionctx:*` family is a bootstrap/session-context implementation surface, not region-local gameplay authority. It must not be used to admit commands, route tick participation, or bypass the authenticated `session:game:*` / `tick:{tenantRegionTag}:session-binding:<entityId>` contract. When the implementation converges key names, the same semantic split remains: pre-auth transport context may exist before `LOGIN`, but authenticated gameplay binding semantics begin only after successful authentication and gameplay admission.

### Session and Region-Binding Contract

Session and region participation updates are intentionally **two-phase and monotonic**, not a single cross-slot atomic Redis transaction:

1. A **session-only** script updates `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`:
   - It validates the session CAS fields and logical expiry.
   - It increments or verifies a monotonic `binding_generation`.
   - It records reconnect/takeover intent plus any advisory region summary fields.
2. A **region-lease** script for each affected region updates `tick:{tenantRegionTag}:session-binding:<entityId>`:
   - It validates the active region lease and expected `binding_generation` from the session contract carried in `ARGV`.
   - It binds or unbinds the session for gameplay participation within that region.
   - It rejects stale generations with a non-mutating outcome such as `"STALE_SESSION_GENERATION"`.
3. Gameplay authority reads region-local binding keys, not `session:game:*`, when deciding whether an entity may enqueue commands or continue tick participation in that region.
4. Disconnect/takeover reconciliation is generation-based:
   - If `session:game:*` says a newer generation is active, any older `tick:{tenantRegionTag}:session-binding:<entityId>` entry is stale and must be ignored or cleaned up by the next region-lease script.
   - If a region binding survives after the session key is deleted or expires, the next lease-holder treats it as stale and removes it as part of region-local cleanup.
5. Region reset reconciliation is also generation-based:
   - A region-scoped coordination reset clears the region-local binding keys for the affected `tick:{tenantRegionTag}:*` family but preserves `session:game:*` and bootstrap `sessionctx:*` entries by default.
   - Before normal command intake resumes for the affected region, Game Session must run a bounded rebind phase for preserved sessions that still intend to participate in that region. The rebind phase reads authenticated session context, validates current account/membership/revocation state, increments or verifies `binding_generation`, and invokes the same region-lease bridge script that normal `PLAY` / reconnect uses.
   - Until the rebind succeeds, gameplay admission for that entity/region must return a stage-aware non-applied outcome such as `"REGION_REBIND_REQUIRED"` or require the client to re-`PLAY`; it must not fall back to advisory `session:game:*` or `sessionctx:*` fields as gameplay authority.
   - If preserved session state cannot be validated during the rebind phase, the session remains connected but is no longer admitted to that region until fresh `LOGIN` / `PLAY` succeeds.

This contract keeps region-local correctness shard-safe while still letting reconnect and takeover flows carry session-wide intent. It also means the system tolerates brief mismatches between `session:game:*` and region-local bindings: the region-local binding key is authoritative for gameplay, while `session:game:*` remains authoritative for reconnect semantics.

Gameplay timers and cooldowns (combat cooldowns, regen ticks, delayed effects) are not “session state”: they are region/entity gameplay state. Durable actor timed-state records are authoritative; tick timer keys are reconstructible scheduling projections used to wake expiry or retry work. This lets cooldowns continue correctly in idle regions and across reconnects without treating Redis session keys as their source of truth.

Key properties:

- `sessionId` is an opaque, server-generated identifier (for example, a UUID or fixed-length hash) chosen so key length stays bounded and independent of the raw JWT or account token.
- Session entries use a **derived physical Redis TTL** computed from authentication settings (see `infrastructure/environment-and-secrets-catalog.md#authentication--jwt`):

  - `session_expiration_ms = FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`
  - `session_expiration_ms` derives the initial gameplay continuity-retention and cleanup horizon. It is not a JWT validity period or a cutoff for healthy uninterrupted play.
  - On successful gameplay admission at `admissionAt`, the session value stores an immutable logical expiry anchor:

    `continuityBindingExpiresAt = admissionAt + session_expiration_ms`

  - The Redis TTL for `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` is physical cleanup metadata and may be refreshed while the binding is active, but it must never move `continuityBindingExpiresAt`.
- Continuity-binding expiry is authoritative for resumption but does not itself end a continuously connected, currently authorized session. Game Session rejects reconnect/resume once `continuityBindingExpiresAt` has passed, even if the Redis key remains after delayed expiration, AOF replay, or failover drift. Physical deletion can remove the key earlier; a missing key is also non-resumable. Key presence and physical TTL are never permission to resume.
- For a binding disconnected or suspended at `disconnectAt`, the effective resume deadline is:

  `resumeDeadline = min(continuityBindingExpiresAt, disconnectAt + effective firemud.reconnection.policy.resume-window-ms)`

  Resume also requires current account identity, membership authority, entitlement, and revocation checks. The pair is immutable for that disconnection episode: failed reconnects, takeover attempts, and server-token rotation cannot change it. Successful resume consumes the episode; a later connected-to-disconnected transition creates a new pair bounded by the original continuity anchor. A genuinely fresh `PLAY` admission creates a new binding and anchor only after ordinary admission succeeds.
- JWT validity remains bounded by each token's own `exp` claim. Game Session may rotate `authTokenHash` and `authTokenIssuedAt` to obtain a currently valid backend token, but token rotation cannot extend continuity-binding expiry or resume eligibility.

Session design assumes **reasonably synchronized clocks** on Game Session nodes (for example, via NTP); large clock skew is treated as an infrastructure misconfiguration, not a normal edge case of the session protocol. The effective disconnected-resume window is the stricter of the remaining continuity-binding lifetime and `firemud.reconnection.policy.resume-window-ms`.

### Operational Trade-Offs for Session and Resume Lifetimes

`session_expiration_ms` is sized from configured JWT lifetime for initial continuity retention and cleanup, while active authorization, JWT validity, and disconnected resume remain separate policies:

- Each JWT remains unusable after its own `exp`, regardless of the Redis TTL or gameplay binding anchor.
- The immutable continuity anchor prevents token rotation from extending old-binding resume eligibility; it does not force a healthy connected player through fresh admission.
- Tenant/game continuity policy can choose a shorter resume window without changing credential or token validity semantics.

When changing authentication settings, keep these trade-offs in mind:

- **Shorter JWT lifetime**
  - Reducing `FIREMUD_AUTH_JWT_EXPIRATION_MS` changes the derived continuity-retention horizon and normal service-token lifetime. It does not make any JWT valid beyond its `exp`, move an existing continuity anchor, or shorten a healthy uninterrupted session by itself.
- **Different disconnected-resume policy**
  - Adjust `firemud.reconnection.policy.resume-window-ms` through the canonical effective-settings path. Increasing it never permits resume beyond the remaining logical gameplay binding lifetime.
- **Unsupported combinations**
  - Do not treat transcript retention, cache TTL, Redis key presence, or physical deletion timing as permission to resume.
  - Do not add another binding/session TTL outside the derived server-side ceiling and the canonical disconnected-resume policy.

For full details on how session keys integrate with reconnect and takeover flows, see:

- Authentication & Authorization (`system-architecture-authentication.md`)
- Reconnection Strategy (`system-architecture-reconnection.md`)
- Game Session Service Redis keys (`microservices/game-session-service/README.md#redis-keys`)

Session revocation actions (for example “kick all sessions for tenant X” on a billing suspension) must not be implemented by scanning the Redis keyspace in hot paths. Runtime revocation is performed by:

- Publishing a tenant-scoped billing/security event that Game Session consumes promptly, and
- Closing affected sockets and removing the corresponding per-session keys using in-memory registries and/or purpose-built, bounded indexes.

Authentication/session allowlist entries (`session:auth:<scope>:<tokenHash>`, for example `session:auth:account:<accountId>:<tokenHash>` and `session:auth:tenant:<tenantId>:<tokenHash>`) share the same TTL derivation and reset expectations as gameplay sessions, but are documented in detail in `system-architecture-authentication.md` and the Account Service design; they live on Coordination Redis so resets can force re-authentication in a controlled way.

---

## Redis Profiles

Redis deployments approximate one of a small set of **profiles**. These profiles describe the expected persistence, restart behavior, and SLO assumptions for Coordination Redis:

- **`dev_local`**
  - Used for individual developers and lightweight local stacks.
  - AOF enabled on Coordination Redis, primarily for debugging and replay during development.
  - Tail‑loss SLOs are relaxed, but invariants and key naming rules still apply.

- **`hobby_self_hosted`**
  - For small/self‑hosted player‑facing deployments.
  - Coordination Redis runs with AOF enabled and memory sizing tuned so restarts typically complete within **30–60 seconds**.
  - Tail‑loss envelopes and replay guarantees are expected to match production‑like behavior for a single tenant.

- **`production_clustered`**
  - For multi‑tenant or higher scale environments.
  - Coordination Redis runs as a cluster or carefully sized single‑node deployment with AOF, predictable restart times, and shard sizing aligned with tick workloads.
  - Tail‑loss SLOs, availability SLOs, and incident playbooks are evaluated against this profile.

Environments (local, CI, staging, production) are mapped to these profiles and their concrete settings in **Redis Usage & Profiles** (`system-architecture-redis-usage-and-profiles.md`).

---

## Redis as a Volatile State Layer

Redis is used **exclusively** for non‑authoritative, transient data, including:

- In‑flight command queues and tick staging structures.
- Tick locks and executor leases.
- Reconstructible timer, cooldown-expiry, and retry scheduling metadata (stored in milliseconds).
- Gameplay session state and live coordination (session bindings, queue participation).
- Best‑effort caches for hot‑path aggregates and chat history in Cache/Rate‑Limit Redis.
- Automation queues and coordination hints that can be reconstructed from durable domain state.

Implications:

- Losing keys within the tail‑loss envelope must behave like lost/reordered messages or delayed timers, not permanent data corruption.
- Designs must **never** put the only record of a critical effect (for example, a currency transfer) exclusively in Redis.
- Every new use of Redis must explicitly state:
  - Which role it targets (Coordination vs Cache/Rate‑Limit).
  - Whether it is reset‑tolerant, reset‑sensitive, or reset‑forbidden.
  - How it behaves under tail‑loss and during coordination resets.

The **Redis Usage & Profiles** and **Redis Cache & Rate Limiting** docs expand on role‑specific expectations, eviction behavior, and cache correctness classes.

---

## Key Naming and Shard Discipline

Redis keys follow strict naming conventions to ensure:

- Shard‑aware key locality and safe multi‑key Lua scripts.
- Clean separation between Coordination and Cache/Rate‑Limit workloads.
- Tenant and region isolation in shared clusters.

Key principles:

- **Tenant and region prefixes**
  - All coordination and cache keys include a `tenantId` component.
  - Many coordination keys also include a `regionId` so tick workloads and timers can be scoped per region.
  - Human‑readable values (character names, room titles) are **never** embedded directly in keys; only stable identifiers (numeric IDs, UUIDs) appear in key components.

- **`{tenantRegionTag}` hash tag**
  - Tick‑region coordination keys use a canonical hash tag placeholder `{tenantRegionTag}` derived from `<tenantId, gameInstanceId, regionId>`.
  - Properties:
    - The concrete string format is an implementation detail of shared key helpers; callers treat it as opaque.
    - All region‑scoped keys for a given region share the same `{tenantRegionTag}` and therefore land in the same Redis Cluster slot.
    - Multi‑key coordination scripts must only receive keys that share the same hash tag; CI and helpers enforce this.
  - Representative patterns:
    - `tick:{tenantRegionTag}:lock:<entityId>`
    - `tick:{tenantRegionTag}:pending`
    - `timer:{tenantRegionTag}`
    - `retry:{tenantRegionTag}`
    - `tick-executor-lease:{tenantRegionTag}`

- **`{tenantGameplayTag}` hash tag**
  - Session-only gameplay keys use a canonical hash tag placeholder `{tenantGameplayTag}` derived from `<tenantId>`.
  - Properties:
    - The concrete string format is an implementation detail of shared key helpers; callers treat it as opaque.
    - The session record plus its tenant-scoped uniqueness and reverse indexes for one tenant share the same `{tenantGameplayTag}` and therefore land in the same Redis Cluster slot.
    - Session-only Lua scripts may perform shard-local multi-key CAS/update flows across these keys, but they must not mix `{tenantGameplayTag}` session keys with `{tenantRegionTag}` tick-region keys in the same invocation.
  - Representative patterns:
    - `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`
    - `session:game:index:character:{tenantGameplayTag}:<gameInstanceId>:<characterId>`
    - `session:game:index:account-tenant:{tenantGameplayTag}:<accountId>`
    - `session:game:index:tenant:{tenantGameplayTag}`

- **Coordination vs cache prefixes**
  - Coordination prefixes (for example `tick:*`, `timer:*`, `retry:*`, `session:game:*`, `session:auth:*`, `tick-executor-lease:*`) live **only** on Coordination Redis.
  - Cache and rate‑limit prefixes (for example `inventory:*`, `view:room-look:*`, `ratelimit:*`) live **only** on Cache/Rate‑Limit Redis.
  - Coordination prefix ownership is normative: Game Session owns gameplay coordination prefixes, Automation & Scripting owns `automation:*`, and non-owner participation is contract-bound through approved helpers. See `design/architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md`.
  - New prefixes must be registered in the canonical catalogs:
    - Coordination prefixes and their reset policies belong in the reset policy matrix and any extended catalogs in `system-architecture-redis-reset-and-recovery.md`.
    - Cache/rate-limit prefixes belong in the Cache/Rate-Limit Key Catalog in `system-architecture-redis-cache.md`, including their correctness class and reset tolerance.
  - For each new or changed prefix, designs must record:
    - Role (Coordination vs Cache/Rate‑Limit).
    - Tail‑loss and reset behavior (reset‑tolerant, reset‑sensitive, or reset‑forbidden).
    - Expected owners and usage patterns, and links to the relevant service README sections.

The **Redis Cheat Sheet** maintains a representative prefix → role/owner mapping. The **Redis Design Checklist** includes concrete checks to run before adding or changing any prefix.

### Shard-Local Design Checklist (Quick Reference)

When designing or reviewing coordination flows, use this shard-local checklist:

- All mutating Lua scripts for coordination prefixes are either:
  - Single-key operations, or
  - Shard-local multi-key operations where all `KEYS` share the same `{tenantRegionTag}` or `{tenantGameplayTag}` hash tag and Redis Cluster slot.
- Cross-region behavior is implemented via per-region operations and durable follow-up records in PostgreSQL, **not** via cross-region multi-key scripts.
- Callers always construct keys via shared key helpers (for example, builders in `firemud-common`) so `{tenantRegionTag}`, `{tenantGameplayTag}`, prefixes, and slots remain consistent; scripts and callers must not hand-roll key strings with embedded hostnames, region names, or ad-hoc hash tags.
- CI and the Lua Script Registry:
  - Reject registry entries that claim shard-local multi-key semantics but declare keys that cannot share a hash tag.
  - Reject coordination scripts that reference cache/rate-limit prefixes or omit required reset/tail-loss metadata.

If a proposed coordination pattern cannot satisfy this checklist, it should be treated as an architectural change and captured first in design docs (Redis + tick) before any implementation work proceeds.

### Coordination Key Examples

This table lists representative coordination keys and their responsibilities. Full semantics live in service‑specific docs and Lua descriptors, but these examples provide a quick mental model:

| Redis Key | Description |
| --- | --- |
| `tick:{tenantRegionTag}:lock:<entityId>` | Lock for an entity during tick execution within a region. |
| `tick:{tenantRegionTag}:pending` | Staged results for a tick region (single in‑flight tick). |
| `tick:{tenantRegionTag}:queue:<entityId>` | Per‑entity command queue within a region. |
| `tick:{tenantRegionTag}:session-binding:<entityId>` | Region-authoritative gameplay session binding for an entity. Stores the currently admitted `sessionId` and `binding_generation` for region-local command admission and takeover cleanup. |
| `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` | Authenticated gameplay session record holding reconnect eligibility, auth/session CAS fields, and the latest desired gameplay binding generation for one tenant-scoped session. |
| `session:game:index:character:{tenantGameplayTag}:<gameInstanceId>:<characterId>` | Tenant-scoped uniqueness index from character to active gameplay `sessionId`. Updated atomically with the session record inside the session-only CAS flow. |
| `session:game:index:account-tenant:{tenantGameplayTag}:<accountId>` and `session:game:index:tenant:{tenantGameplayTag}` | Tenant-scoped reverse indexes used for bounded revocation, reconnect, and inspection without wildcard scans. |
| `retry:{tenantRegionTag}` | Retry queue for failed actions, keyed by `next_eligible_tick_id` on the target region timeline (not wall-clock due time). |
| `timer:{tenantRegionTag}` | Sorted set of timers for a region; score is expiration timestamp (ms), members encode entity/effect metadata. |
| `remote:<tenantId>:<entityId>` | Best‑effort, TTL-bounded hint marker for cross‑region follow‑ups (durable follow‑ups live in PostgreSQL). Default `remote_hint_ttl_ms = 60_000`; expiry/missing keys affect latency only. |
| `route:{tenantRegionTag}:gamesession` | Reserved for a potential future gameplay routing view for `<tenantId, gameInstanceId, regionId> → shardTarget`. Per ADR 0007, lease-aware edge admission and a client-visible shard handoff signal are not part of the current edge contract; do not implement Gateway consumption of this mapping without a dedicated sharding/routing design update. |
| `tick-events:{tenantRegionTag}` and `tick-events-offset:{tenantRegionTag}` | Best-effort per-region tick event stream and consumer offset (typically Redis Stream entry ID). Streams are retention-capped (default `tick_events_maxlen = 2048` per region); consumers treat trimmed history as normal truncation and bootstrap from committed heartbeats/RegionStatus. |
| `tick-events-lease:{tenantRegionTag}` | Best-effort lease to avoid duplicate tick-event consumption work by observers. Safe to drop; consumers reacquire after restarts/resets. |
| `automation:timer:{tenantRegionTag}` | Region-scoped Automation & Scripting timer index for `onInterval` and timer coordination. Stored entries remain instance-aware in payload and durable identity (`gameInstanceId`, and plugin identifiers when applicable) even though the Redis key is region-scoped for slotting and reset targeting. |
| `script-scheduler:{tenantRegionTag}:lastTickId` | Automation & Scripting scheduler checkpoint for “every N ticks” triggers; used to resume interval counting after leader changes. Durable automation schedules, quotas, and trigger-instance de-duplication live in PostgreSQL; this key is a coordination hint, not the source of truth for which scripts should eventually run or whether a due trigger was already emitted. |

Region‑scoped coordination keys share the same `{tenantRegionTag}` hash tag and therefore land in the same Redis Cluster slot. Tenant-scoped gameplay session keys share `{tenantGameplayTag}` for session-only CAS/index updates. Other tenant-scoped auth or single-key prefixes that are not mutated together may remain ordinary single-key operations, but they must still honour the coordination vs cache role split described above.

---

## Topology Compatibility Overview

Redis features and assumptions in FireMUD must work across both single‑instance and clustered deployments. This table summarizes what is supported; operational details and exact configuration live in **Redis Usage & Profiles** and **Redis Operations & Migrations**.

| Topology | Coordination Usage | Cache/Rate‑Limit Usage | Notes |
| --- | --- | --- | --- |
| Single‑node with AOF (coordination) | **Supported.** All coordination prefixes (`tick:*`, `timer:*`, `retry:*`, `session:game:*`, `session:auth:*`, `tick-executor-lease:*`, etc.) and shard‑local Lua patterns apply. Tail‑loss envelopes and AOF replay guarantees assume this profile or better. | **Supported** on a separate Cache/Rate‑Limit deployment (distinct process or container). | Recommended baseline for `dev_local` and `hobby_self_hosted` profiles. |
| Single‑node without AOF (ephemeral coordination) | **Supported only for explicitly ephemeral stacks** (preview/CI) that opt out of tail‑loss SLOs and replay guarantees. Coordination keys are disposable and must be reset‑tolerant. | **Supported** on a separate Cache/Rate‑Limit deployment; cache behavior is unchanged. | Not appropriate for environments where tick replay, tail‑loss SLOs, or long‑lived coordination logs are required. |
| Redis Cluster (coordination) | **Supported** provided all coordination Lua scripts obey shard‑local rules using `{tenantRegionTag}` for region/tick flows and `{tenantGameplayTag}` for session-only gameplay flows. Multi‑key coordination scripts must only touch keys that share one hash tag and slot. | **Supported** for cache/rate‑limit workloads; rate‑limit prefixes (`ratelimit:*`) are treated as single‑key operations without cross‑slot atomicity. | Cluster deployment requires strict adherence to hash‑tag and slotting rules described in [Key Naming and Shard Discipline](#key-naming-and-shard-discipline). |

In all topologies:

- Coordination Redis and Cache/Rate‑Limit Redis are **separate deployments** (distinct processes/containers) even when they share the same host or Kubernetes node.
- Application and tooling configuration **must not** point `FIREMUD_REDIS_COORD_*` and `FIREMUD_REDIS_CACHE_*` to the same endpoint in any non‑ephemeral environment.
- Shared configuration helpers should perform a best‑effort check (for example, comparing host/port pairs) and emit a clear log/health warning when both roles resolve to the same target.

---

## External Invariants Redis Depends On

Redis designs in FireMUD assume several invariants that are defined and enforced in other parts of the system. This section summarizes the most important ones so Redis reviews do not have to repeatedly re-derive them:

- **Region epoch and single-writer guarantees**
  - The tick system maintains a `region_epoch` per `<tenantId, gameInstanceId, regionId>` in PostgreSQL (see `system-architecture-tick-concepts-and-invariants.md` and related docs).
  - At any time, at most one executor is allowed to hold the active epoch for a region; Lua scripts validate epoch and lease tokens against this metadata by:
    - Carrying the expected `region_epoch` (directly or via an epoch-bearing lease token) in `ARGV`.
    - Comparing it against epoch metadata stored alongside region-scoped coordination keys such as `tick:{tenantRegionTag}:pending` before performing any writes.
    - Returning explicit non-mutating outcomes (for example `"STALE_EPOCH"`, `"STALE_LEASE"`) when the epoch or lease token no longer matches.
  - Redis designs may assume that “single writer per region + epoch” is upheld by the tick control plane and database, and must treat violations (for example, split-brain) as incidents that trigger resets, not normal control flow.
  - Normal executor rebalancing / lease handoff does **not** bump `region_epoch`. Lease tokens provide fencing between executors; `region_epoch` is reserved for resets and explicit “sever the old timeline” maintenance operations.
  - Scoped coordination resets are expected to interact with `region_epoch` as follows:
    - Region- or tenant-scoped resets normally bump `region_epoch` for the affected `<tenantId, gameInstanceId, regionId>` pairs and invalidate any pre-reset executor leases.
    - Cluster-scoped resets are accompanied by a coordinated epoch bump for all affected regions so that new executors cannot accidentally reuse stale coordination state.
- **Idempotent domain effects**
  - Domain-level effects (damage application, currency transfers, quest progress, etc.) are recorded via idempotent identifiers or transaction rows in PostgreSQL (see `system-architecture-transactions.md`).
  - Coordination keys such as `pending` entries and retries rely on these idempotency guards: re-running ticks or retries must not double-apply domain effects even if Redis state is replayed or partially lost within the tail-loss envelope (`tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)` in `system-architecture-redis-operations.md`).
- **Transactional boundaries**
  - Services that participate in ticks and coordination flows encapsulate their durable writes in transactions with clear boundaries and conflict detection (for example, optimistic locking or explicit version checks).
  - Redis designs may assume that “commit vs abandon/cleanup” is visible in domain state and must not introduce coordination patterns that require peeking into in-flight, uncommitted work.
- **Authentication and session semantics**
  - Session TTLs and reconnect windows follow the authentication settings described in `system-architecture-authentication.md` and `system-architecture-reconnection.md`.
  - Session-related Redis keys (`session:game:*`) are expected to enforce those logical expiry rules; Redis designs must not introduce independent notions of “session lifetime” that diverge from the documented auth contracts.

When changing any of these external invariants, update this section and the referenced docs together so Redis designs and reset/runbook assumptions remain aligned.

---

## Related Documentation

- `system-architecture-redis-usage-and-profiles.md` – detailed role usage, environment profiles, and wiring.
- `system-architecture-redis-reset-and-recovery.md` – reset model, recovery patterns, and incident scenarios.
- `system-architecture-redis-design-checklist.md` – required checks for prefixes, scripts, and ops changes.
- `system-architecture-redis-lua-patterns.md` – Lua scripting patterns and registry expectations.
- `system-architecture-redis-operations.md` – AOF management, resets, and migration runbooks.
- `system-architecture-redis-ops-access.md` – Redis ACLs, ops tooling, and CI checks for maintenance scripts.
- `system-architecture-redis-cache.md` – cache and rate‑limit design.
- `system-architecture-redis-cheatsheet.md` – quick reference for prefixes and doc routing.
- `system-architecture-ticks.md` – tick system and runtime design.
- `system-architecture-reconnection.md` – reconnection strategy and session behavior.

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
- [Implementation Status](#implementation-status)
- [Redis Coordination Invariants](#redis-coordination-invariants)
- [Redis Profiles](#redis-profiles)
- [Redis as a Volatile State Layer](#redis-as-a-volatile-state-layer)
- [Atomicity and Concurrency Control](#atomicity-and-concurrency-control)
- [Key Naming and Shard Discipline](#key-naming-and-shard-discipline)
- [Topology Compatibility Overview](#topology-compatibility-overview)
- [External Invariants Redis Depends On](#external-invariants-redis-depends-on)
- [Related Documentation](#related-documentation)

---

## Implementation Status

The target authenticated gameplay-session contract below is not fully implemented. Current pre-auth bootstrap and reconnect context remains in the implementation-local `sessionctx:*` family. Canonical gameplay bindings belong under `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`, with bounded gameplay lookup indexes under `session:game:index:*`; that complete binding schema, its index obligations, session-only CAS, and region-binding bridge have not yet converged end to end. The current runtime also derives Redis session TTL directly from `FIREMUD_AUTH_SESSION_EXPIRATION_MS`, with a `3,600,000` ms default, and does not enforce the target five-minute cap.

The target issued-token registry contract and Account-owned authority-generation enforcement are also incomplete and not fully proven. The `session:auth:token:<tokenHash>` registry, issuer/account/tenant/membership generation projections, freshness fences, and their fail-closed consumers must not be treated as live merely because the target key names and validation rules are documented; current runtime coverage and focused proof remain separate implementation gaps.

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

- Durable history for tick-driven outcomes (for example, “which effects were applied or abandoned for a given `(tenantId, gameInstanceId, playableStateScope, regionId, region_epoch, tickId, effectKey, targetAggregateType, targetAggregateId)`”) lives in PostgreSQL via the tick effect ledger and domain idempotency tables described in `system-architecture-tick-failures-and-operations.md` and `system-architecture-transactions.md`.
- Coordination Redis holds volatile structures such as tick queues, `pending` sets, timers, region leases, tick event streams, and scheduler offsets; these structures are expected to be subject to bounded tail-loss and scoped resets as defined in this document and the Redis reset/runbook docs.
- Application and ops designs must not treat AOF contents or Redis key history as the primary log for audits, analytics, or long-term effect replay; those concerns belong in PostgreSQL-backed ledgers and domain stores.
- `session:auth:token:<tokenHash>` is a narrow security exception: its exact-token registry record is authoritative for runtime protected admission and per-token revocation. A cryptographically valid JWT is denied and requires fresh authentication when its exact active, permitted registry record is reachable but absent, deleted, expired, or revoked; an unavailable registry is retryable `AUTH_UNAVAILABLE` and never permits reconstruction from the JWT or a local cache. This runtime authority is distinct from Account's durable issuer, account, tenant, and membership generations; those generations remain Account-owned. Redis and non-Account consumers cannot advance or recreate them. Account may physically delete a record before its natural expiry only after the durable Account `PENDING_LOGOUT`/revocation fence or equivalent tombstone intent has committed; deletion is cleanup and never the revocation proof. During reset recovery, Account alone may rebuild or re-project the exact Redis values from its durable authority under the documented scope, cutover, and verification gates.
- Target-state invariant, not a current runtime guarantee: Account issuer, account, tenant, and membership generation projections are an approved global/account exception to ordinary Coordination Redis reset handling. Account durable authority remains the sole writer and source of truth; Redis is only a required projection. Region- and tenant-scoped resets preserve these projections and re-project/verify exact generations, while a cluster reset may discard them only after the Account repair/reset cutover, then must rebuild and verify them before protected admission reopens. The implementation limitations near the top of this document remain controlling until the projection and reset workflows are implemented and proved end to end.
- Every issuer-generation projection, including the canonical `session:auth:generation:issuer:<issuerId>` projection and the Game Session `session:game:auth:issuer-generation:v1:<issuerId>` consumer projection, atomically stores `lastAppliedSourceOutboxSequence` (the accepted source `outboxSequence`) and `lastAppliedIssuerGeneration` for its exact canonical `outboxStreamKey`, plus `lastAppliedSourceEventId` and `lastAppliedSourceEventDigest` as derived source-checkpoint evidence for every nonzero sequence. These `lastAppliedSource*` fields are projection evidence associated with the canonical `outboxStreamKey`/`outboxSequence` and the applicable `outboxCheckpoints`; they are not authority-tuple fields or JWT claims. For sequence `0`, the two source-event fields are omitted from the projection, not serialized as JSON `null`, an empty string, a zero digest, or another sentinel; the Account-owned zero-sequence checkpoint is the only evidence. For every positive sequence, both source-event fields are required non-null values in their canonical formats. It may apply only the next contiguous sequence. A higher sequence is a gap and must quarantine the affected projection and reconcile from Account before advancing the watermark or generation. For each stream, the projection also retains bounded duplicate evidence for the most recent accepted sequences, at minimum `{outboxStreamKey, outboxSequence, eventId, eventDigest}`; each evidence entry has a required non-null `eventDigest`, and no entry represents the zero-sequence baseline. An equal or lower sequence is a no-op only when that bounded evidence matches or an authoritative Account lookup for the exact `(outboxStreamKey, outboxSequence)` proves the same event identity and digest; a high-water mark alone is never duplicate proof. If the evidence has aged out, the lookup is unavailable, or same-sequence evidence conflicts, the projection is quarantined. A valid next event advances the complete persisted checkpoint even when set-if-greater generation semantics leave the generation unchanged. The gameplay binding's separate `{outboxStreamKey, outboxSequence}` freshness set does not replace this projection-local replay evidence. Projection recovery fails closed while the checkpoint is missing, stale, regressed, ambiguous, or quarantined, including any invalid omitted/null/sentinel representation.
- An empty Game Session issuer projection is an uninstalled and quarantined state, not an implicit zero-authority state and not permission to consume live events. Game Session must initialize `session:game:auth:issuer-generation:v1:<issuerId>` from one Account-owned authoritative snapshot/reconciliation transaction for the exact `issuer/<issuerId>` scope. That transaction returns the exact checkpoint: `outboxStreamKey = account:auth-authority:v1:issuer/<issuerId>`, `lastAppliedSourceOutboxSequence` equal to the Account checkpoint's exact latest `outboxSequence`, and `issuerAuthGeneration` from the same snapshot. For a nonzero sequence it also returns the exact source `eventId` and `eventDigest`; for sequence `0`, permitted only when the stream has no committed event, `lastAppliedSourceEventId` and `lastAppliedSourceEventDigest` are absent, and the Account-owned zero-sequence checkpoint itself is the evidence. The result also carries the source transaction or immutable bundle version, the exact checkpoint evidence, and the reconciliation operation identity; Game Session must not invent bootstrap event IDs, digests, sequences, or authority values locally. Before consuming events, Game Session atomically persists that complete checkpoint and authority value, stores matching `{outboxStreamKey, outboxSequence, eventId, eventDigest}` duplicate evidence only for a nonzero checkpoint, and reads back the installed projection. For a zero-sequence baseline, duplicate evidence is empty and the first consumable event must be sequence `1`; for a nonzero baseline, the next consumable event must be exactly `lastAppliedSourceOutboxSequence + 1`. Until the atomic installation and exact readback succeed, or if any applicable snapshot, source-checkpoint, event-identity/digest, or authority value is missing, stale, regressed, contradictory, or ambiguous, the projection remains quarantined and issuer-gated admission, reconnect, and revocation consumption fail closed.
- Spring Cloud Gateway has one narrow Coordination Redis authority: one-use connect-token replay consumption under `gateway:connect-token:jti:*` plus its replay-readiness fence. The Gateway fails closed when that replay authority is unavailable, owns the key TTL and reset contract, and must not expand this exception into ownership of gameplay sessions, Account auth state, or general coordination policy.

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
        - `APPLIED` – every required participant for this tick has explicit durable terminal evidence: its participant outcome is `APPLIED` or `ABANDONED` in the PostgreSQL-backed ledger or an equivalent durable terminal record. The mere presence of a reconciliation-backlog item is not evidence of a terminal outcome. Handler-level replay/no-op results are recorded separately as `replay_ok` attempt outcomes and do not satisfy this requirement by themselves.
        - `ABANDONED` – the tick was intentionally terminated for the current epoch (for example due to reset/recovery) and no more work for that `(region_epoch, tickId)` may be staged through hot-path scripts.
      - `current_tick_terminal_at_ms` – caller-supplied timestamp marking when `current_tick_state` first entered `APPLIED` or `ABANDONED`; used for observability and bounded cleanup only, never for correctness decisions inside Lua.
    - Illustrative Redis hash contents:

      ```text
      HGETALL tick:{tenantRegionTag}:meta
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
        - Only after the durable tick ledger or equivalent durable terminal records for `(tenantId, gameInstanceId, playableStateScope, regionId, region_epoch, tickId)` contain explicit `APPLIED` or `ABANDONED` evidence for every required participant. A reconciliation-backlog item may identify work still needing reconciliation, but its presence alone cannot establish `APPLIED`; replay/no-op is a handler attempt outcome, recorded as `replay_ok`, not a ledger status.
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
      - Treat Redis `pending` contents as an implementation detail of the hot path, not as proof of durable convergence. The durable proof that a tick is safe to move past is explicit terminal `APPLIED` or `ABANDONED` evidence for every required participant in the PostgreSQL ledger or equivalent durable terminal records; reconciliation-backlog presence and `replay_ok` attempt outcomes are insufficient by themselves. Only after that proof does the caller record `APPLIED` or `ABANDONED` in `tick:{tenantRegionTag}:meta`.
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
    - Tick effect ledger behavior and domain idempotency rules (see `system-architecture-tick-failures-and-operations.md`) must guarantee that those dropped/replayed ticks converge to a final state where each `(tenantId, gameInstanceId, playableStateScope, regionId, region_epoch, tickId, effectKey, targetAggregateType, targetAggregateId)` is either durably `APPLIED` or durably `ABANDONED`, never left indefinitely “half‑applied”.
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

- All mutating operations on coordination prefixes (tick queues, `pending` sets, timers, retry structures, locks, leases, and session keys) always go through owned **typed key and mutation helpers**. Raw Redis commands are prohibited. Registered Lua is mandatory for atomic multi-key behavior and may be used for a single-key mutation only when an explicitly documented atomic guard or compare-and-set contract requires it; ordinary single-key mutations use typed helpers without Lua. Registered scripts:
  - Validate lease tokens, lock tokens, and monotonic guards (`tickId`, epochs) before writing.
  - Operate only on keys that share the same hash tag and cluster slot.
  - Are deterministic and idempotent with respect to their `KEYS`/`ARGV` arguments and current Redis state.
- Application code and maintenance tooling must **not** issue ad-hoc multi-key Redis commands (including `MULTI/EXEC`) over coordination prefixes.
- Cross-region workflows are implemented as per-region operations or higher-level sagas, not as cross-region Redis transactions.

The detailed scripting rules and categories (region-lease scripts, session-only scripts, automation scripts, maintenance scripts) are defined in `system-architecture-redis-lua-patterns.md`, and the concrete checks to apply during design review are captured in the Lua section of `system-architecture-redis-design-checklist.md`.

---

## Session Keys and Gameplay Binding

Game Session uses Redis for two related but distinct session concerns:

- **Bootstrap/pre-auth transport context** is created when a socket connects and before gameplay authentication completes. It contains only unauthenticated bootstrap scope such as tenant, locale, or initial game-instance hints. Gameplay commands must treat this context as unauthenticated until `LOGIN` succeeds.
- **Authenticated gameplay session state** is created or promoted after successful `LOGIN` and `PLAY`. The canonical target key family for this state is `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`.

The authenticated gameplay key family is the canonical session-state contract.

Authenticated gameplay session keys capture:

- Socket binding metadata and transport details.
- Active `characterId` / `tenantId` context.
- Server-side auth token identity used for Account and other control-plane calls on behalf of this session (`authTokenHash`, `authTokenIssuedAt`, `authTokenExpiresAt`, and `tokenGeneration`), so planned refresh, resume, and mid-session revocation checks can be performed without exposing JWTs to gameplay clients. For the exact active registry record, `authTokenIssuedAt == registry.iat`, `authTokenExpiresAt == registry.exp`, and `tokenGeneration == registry.tokenGeneration`; these are equality checks, not lower/upper-bound checks against Redis TTL or the current time.
- The protected `rebindHandle`, immutable continuity anchor `continuityBindingExpiresAt`, separate `membershipVersion`, and complete `authorityTuple` required to complete a restart or resume rotation and verify that gameplay admission authority still exists without exposing the private delegation JWT to gameplay clients. The exact nested tuple field is `authorityTuple.membershipAuthorityGeneration`; it is stored as one complete field and is never replaced by a standalone tuple member. A duplicate top-level `membershipAuthorityGeneration` field is forbidden; readers and CAS scripts reject it rather than merging it with or choosing precedence over the nested field. The Account-issued handle binds the exact token hash, signed `jti` and `nbf`, token lineage, gameplay binding, authority tuple, and exact `issuanceFence` captured for that issuance; only Account may open and validate it, and Game Session treats it as opaque.
- The binding also stores the complete canonical `outboxCheckpoints` set: one exact `{outboxStreamKey, outboxSequence}` entry for every applicable authority stream, canonicalized in stream-key order. It is independent freshness evidence, not an aggregate maximum or an authority replacement, and it is preserved unchanged by binding refresh, rebind, and token rotation.
- Tick-region participation metadata (for example active region bindings and reconnect context). Per-entity command queues remain under `tick:{tenantRegionTag}:queue:<entityId>` and are reset-tolerant coordination state, not durable session payload.
- Session-local coordination metadata (for example reconnect state, transport-level pacing, and other per-connection ephemeral fields).

The authenticated gameplay payload is versioned as a complete contract, not by individual fields. The common socket/binding, active character/tenant, tick-region participation, and session-local fields listed above remain required in every version. Existing or missing-version payloads are interpreted as `schemaVersion=1`; its authentication-specific required fields are exactly `authTokenHash` and `authTokenIssuedAt`. Version 1 does not contain the target authority, rebind, membership-freshness, or token-expiry fields. A `schemaVersion=1` record is storage-only and is never eligible for token refresh, resumable admission, or backend authorization; it must be re-established through fresh `LOGIN`/`PLAY` or an explicitly audited migration.

`schemaVersion=2` is the target authenticated-session contract and requires the same common fields plus exactly ten authentication-specific fields: `authTokenHash`, `authTokenIssuedAt`, `authTokenExpiresAt`, `tokenGeneration`, `issuanceFence`, `rebindHandle`, `continuityBindingExpiresAt`, `membershipVersion`, the complete `authorityTuple`, and the complete `outboxCheckpoints` set. Compared with version 1, eight of those authentication-specific fields are newly required; the version-1 token hash and issue time remain part of the complete version-2 set. Readers, refresh, resume, and admission must reject a version-2 record when any field is absent, malformed, or unavailable. `authorityTuple.membershipAuthorityGeneration` is required inside the complete tuple; a duplicate top-level `membershipAuthorityGeneration` is an invalid extra field and is rejected even when its value matches. Readers and CAS scripts that understand both complete versions must be deployed before writers emit version 2; unknown versions fail closed.

Token rotation summary (normative): Game Session may install a replacement only with a current token-identity fence covering the expected prior `authTokenHash`, `authTokenIssuedAt`, `authTokenExpiresAt`, `tokenGeneration`, and `issuanceFence`, the expected prior `membershipVersion`, complete `authorityTuple`, exact `outboxCheckpoints` set, and the binding/rebind lineage. At the first session-only CAS linearization point, the prior token's `authTokenExpiresAt` must equal the active registry `exp` and be later than the trusted current time; the prior `authTokenIssuedAt` and `tokenGeneration` must equal the same registry record's `iat` and `tokenGeneration`. An elapsed prior JWT is rejected even if its Redis session record remains. That CAS writes the replacement as a non-admissible `INSTALLING` candidate and atomically carries the replacement `authTokenHash`, `authTokenIssuedAt`, `authTokenExpiresAt`, `tokenGeneration`, `issuanceFence`, `rebindHandle`, refreshed `membershipVersion`, complete `authorityTuple`, exact `outboxCheckpoints` set, and installation identity. While the candidate is `INSTALLING`, Account may finish predecessor-authenticated calls admitted before the claim until `predecessorUsableUntil`, but rejects new predecessor-authenticated Account calls; Game Session issues no new Account calls and keeps the candidate non-admissible. After exact readback, the durable installation attempt may advance to `BINDING_INSTALLED`, but that state and the `INSTALLING` candidate remain non-admissible. Game Session then sends an acknowledgement bound to the installation identity and stable idempotency tuple/digest. Only a durably committed matching acknowledgement, including an exact retry after timeout-after-commit, permits the second fenced session-only CAS to mark the candidate `INSTALLED` and admit it for new backend calls. A stale, mismatched, unavailable, or ambiguous acknowledgement, or a failed second CAS, produces no admissible partial update and invokes the existing abort/retire path; rotation cannot extend `continuityBindingExpiresAt` or `resumeDeadline`.

Token rotation checkpoint preservation is mandatory at both session-only CAS stages and in every durable rotation record. The `TOKEN_ROTATION` operation, replacement lease, pending/active registry postcondition, installation claim, acknowledgement request and digest, binding candidate, readback evidence, and second CAS must each carry the exact stream-key-ordered `outboxCheckpoints` set. The first CAS compares the expected prior set and writes the replacement candidate with the exact unchanged set from the Account lease. The post-swap acknowledgement request and canonical digest include the complete set; Account validates it against the exact lease, operation evidence, current authority snapshot, and binding metadata before committing installation. The second fenced CAS compares that same acknowledged set before marking the candidate `INSTALLED`. Missing, extra, reordered-with-different-content, stale, regressed, or cross-scope checkpoints fail closed, including on an exact timeout-after-commit retry; neither CAS may retain only the latest checkpoint or an aggregate maximum.

Pre-auth transport context is not region-local gameplay authority. It must not be used to admit commands, route tick participation, or bypass the authenticated `session:game:*` / `tick:{tenantRegionTag}:session-binding:<entityId>` contract. Authenticated gameplay binding semantics begin only after successful authentication and gameplay admission.

### Global Account Active-Binding Index

`session:game:index:account:<accountId>` is the retained untagged global exception because account-wide logout, security revocation, and repair must enumerate active gameplay bindings across every tenant. It is not an authorization source: Account owns the durable authority and cutoffs, while Game Session owns the gameplay binding, this index, its repair obligations, and the termination/reconciliation workflow.

The current physical layout is one Redis set key per account, placed on the shard selected by the full untagged key hash. It is one logical account-wide partition, not a tenant-sharded index. Every member is a versioned, generation-safe `accountIndexMember` containing the complete tenant-qualified `bindingRef`, its positive `bindingGeneration`, and the `accountIndexFence` ordered by that generation. A bare `sessionId`, tenant-only member, member without generation/fence evidence, or Redis-derived tenant association is invalid. Any future split or partitioning must use a new versioned layout and preserve this complete identity contract; reset/reconciliation must not infer a partition scheme from key absence.

The tenant-qualified repair protocol is mandatory and the untagged account key is never included in tenant-local Lua or session-only CAS. Before the tenant-local session CAS can publish an active or provisional binding, Game Session commits a PostgreSQL repair obligation containing the expected prior generation, candidate `bindingGeneration`, unique transition identity, desired account-index add/remove, and `accountIndexFence`. The shard-local CAS may publish the candidate only when the expected prior generation and current session owner/fence still match; it records `accountIndexState=REPAIR_REQUIRED`, so the candidate is provisional and non-admissible. The separate account-key operation carries the same transition identity and fence, adds or retains the generation-qualified candidate before removing an old member, and refuses a removal when the authoritative binding or durable obligation has a newer generation/fence. A stale add may remain as an extra projection for repair, but a stale remove must never hide a newer binding. Game Session acknowledges only the exact fenced add/remove outcome and exact member readback, then uses a follow-up tenant-local CAS to set `accountIndexState=ACKNOWLEDGED` only when the same candidate generation/fence is still current. A missing, stale, conflicting, cross-tenant, or unavailable member leaves the binding non-admissible and the account scope fenced.

For account-wide recovery, logout-all, or reconciliation, Game Session enumerates the complete durable inventory for the account, including all tenants and unresolved obligations, under an immutable snapshot. Every post-fence transition and account-index acknowledgement must carry both the immutable operation fence and a later `coverageGeneration`; neither one is sufficient alone, and the original snapshot fence/generation cannot substitute for that pair. Narrower region- or tenant-scoped resets reconcile only affected tenant-qualified members and preserve unrelated tenants; a cluster reset rebuilds the complete account key before protected admission reopens. The workflow reads back the exact expected generation-qualified member set and fails closed on missing rows, partial coverage, duplicate identities, stale entries, or unresolved obligations; it never uses Redis `SCAN`, key absence, or the global index alone as proof.

Tenant isolation remains mandatory despite the global key: a tenant-scoped caller may operate only on binding references and authority for its exact tenant, every referenced binding is validated against its tenant-qualified session record and current Account evidence, and the global account index cannot authorize cross-tenant gameplay or substitute for tenant/membership/grant checks. Account-wide operations may enumerate all tenants only under the account-wide authority that authorizes that operation.

### Issuer Active-Binding Index

The issuer cutoff family is intentionally **global per Account environment issuer**, not tenant-scoped. `issuerId` identifies the Account issuer and its issuer-generation cutoff; the `issuer/<issuerId>` authority stream and this derived index cover active and provisional gameplay bindings across every tenant under that issuer. Account owns the durable issuer generation, cutoff event, and outbox/checkpoint authority. Game Session owns the gameplay binding records, issuer partitions, repair obligations, coverage evidence, and socket/admission termination. The index cannot advance issuer authority and is not an authority source.

Because this is a global Coordination Redis family, `session:game:index:issuer:{issuerIndexLayoutTag}:<issuerId>:<partitionId>` is an explicit exception to the general tenant/region key-prefix rule. `tenantId` remains in every value and in `bindingRef`, and every operation validates the referenced tenant-scoped binding identity. Tenant isolation is enforced by Game Session's owner-controlled access and binding validation; the global index is not a tenant-local read or mutation surface. Region- and tenant-scoped resets must not flush a whole global issuer partition: Game Session reconciles only affected binding references from the durable inventory and preserves or repairs global coverage. A cluster reset may discard the volatile index, but protected admission remains fenced until Game Session rebuilds every partition in the active layout and proves coverage; Account-owned issuer authority is rebuilt and verified under the reset contract.

The issuer cutoff family is a bounded active-binding index, not an authority source. Its exact Redis representation is:

```text
key:   session:game:index:issuer:{issuerIndexLayoutTag}:<issuerId>:<partitionId>
type:  HASH
field: <bindingRef>
value: {
  schemaVersion: 1,
  issuerId,
  accountId,
  tenantId,
  gameInstanceId,
  sessionId,
  bindingGeneration,
  issuerAuthGeneration,
  issuerIndexLayoutVersion
}
```

`bindingRef` is the versioned length-prefixed encoding of the complete `{tenantId, gameInstanceId, sessionId}` binding identity. `partitionId` is the deterministic hash of that exact encoded identity modulo the positive `issuerIndexPartitionCount` recorded by the immutable `issuerIndexLayoutVersion`; each partition is bounded by the positive `issuerIndexPartitionCapacity`; `{issuerIndexLayoutTag}` is the opaque Redis hash tag derived from `(issuerIndexLayoutVersion, issuerId, partitionId)`. A layout version owns one immutable partition count and capacity. The durable active-layout pointer names the version, count, capacity, and migration state; no reader infers them from Redis or changes the modulo in place. A cutoff reads exactly the finite partition range named by that pointer, with no wildcard keyspace scan. Every returned field is validated against the binding record before termination work is scheduled.

Changing `issuerIndexPartitionCount` is a layout migration, not a configuration refresh. Game Session creates a durable layout-transition operation under one `operationId` and `operationFence`, captures the expected active pointer as the pinned old layout `{issuerIndexLayoutVersion, issuerIndexPartitionCount, issuerIndexPartitionCapacity}`, and records the complete new layout as a separate pinned target. New binding/reconnect/resume admission and issuer cutoffs are fenced for the affected issuer, the complete durable inventory and unresolved obligations are snapshotted, and every new partition is rebuilt from that snapshot. The transition record covers both old and new layout descriptors, but migration is the only workflow allowed to read the old layout as a source and the new layout as a target; player, cutoff, and recovery readers never combine partitions from both layouts. It records and applies terminal transitions under the migration fence, proves per-partition capacity and exact readback for the new layout, and publishes durable per-layout coverage acknowledgements. Only a compare-and-set that still matches the pinned old pointer and `operationFence` may advance the active pointer to the pinned new version/count/capacity. Before that CAS, all ordinary readers and writers use only the old pointer; after it, they use only the new pointer. A crash leaves the old pointer active and resumes or abandons the recorded transition; an absent, changed, ambiguous, or mixed pointer fails closed. The old layout is retained read-only until the post-cutover drain/reconciliation window proves zero in-flight old-layout operations, then is removed by a separate fenced cleanup. A modulo change without this versioned build, pointer cutover, and old-layout drain is unsupported.

Before enumeration, Game Session establishes one issuer-wide admission `coverageFence` and immutable `inventorySnapshotRevision` under the durable `operationId`/`operationFence`, and pins the active layout pointer as `{issuerIndexLayoutVersion, issuerIndexPartitionCount, issuerIndexPartitionCapacity}` for that operation. The snapshot contains the exact `issuer/<issuerId>` cutoff checkpoint, issuer generation, `issuanceFence`, complete applicable `outboxCheckpoints`, and the pinned layout identity. `coverageFence` is the immutable admission/creation fence for that inventory snapshot and is distinct from the maintenance `operationFence`; `coverageGeneration` is a later generation used to account for every post-fence transition. New binding, reconnect, and resume admission is blocked against that fence while coverage is open; a post-fence binding can become admissible only when it carries both the operation fence and a later `coverageGeneration`, and any race that produces a non-admissible candidate, reservation, or repair obligation must be accounted for before acknowledgement. Game Session durably acknowledges each partition only with the complete issuer-partition acknowledgement tuple defined above: `operationId`, `issuerId`, both distinct fences, snapshot revision, later coverage generation, pinned layout version/count/capacity, `partitionId`, exact cutoff, reservation lifecycle identities, and local plus aggregate capacity evidence. A missing HASH key is an empty partition only when the read succeeded and the matching acknowledgement proves coverage for that exact issuer-wide snapshot, operation attempt, layout, cutoff, and fences. A missing, stale, or mismatched acknowledgement, cross-snapshot or cross-attempt evidence, an unavailable read, malformed value, conflicting duplicate, unresolved reservation, or unresolved repair obligation is ambiguous coverage; the cutoff fails closed and requires reconciliation/rebuild rather than inferring empty coverage from key absence or an aggregate proof. The coverage generation is index-maintenance evidence, not a second authority generation.

For every partition `p`, `N_issuer_partition(p)` counts distinct non-terminal durable reservation entries, with each binding transition counted once even when its reservation is attached to an active, provisional, or unresolved binding. Each `IssuerPartitionReservation` is Game Session-owned durable evidence with `reservationId`, `owner` (the complete `bindingRef` and owning session identity), exact `transitionId`, `issuerId`, `partitionId`, `issuerIndexLayoutVersion`, lifecycle (`RESERVED`, `BOUND`, `RELEASE_PENDING`, or terminal `RELEASED`), `reservationFence`, and `inventoryRevision`. Before any tenant-local binding CAS publishes a binding, Game Session commits the reservation and any repair obligation in the same durable transaction, then atomically reserves one unit in the target issuer partition through a single-key capacity compare-and-set keyed by the exact reservation and transition. That CAS compares the owner, transition, partition/layout, lifecycle, fence, and revision, is idempotent for the same reservation, and rejects or backpressures when the reservation would exceed `issuerIndexPartitionCapacity`. A binding cannot become admissible or a repair obligation be considered covered without the matching reservation. The reservation remains counted through `RESERVED`, `BOUND`, and `RELEASE_PENDING`; failed or ambiguous cleanup leaves it counted. Terminal cleanup first records `RELEASE_PENDING`, removes the binding field through the fenced CAS, removes/releases the capacity entry, reads back both absences and the exact terminal state, and only then records `RELEASED`; terminal evidence remains available for idempotent reconciliation. This reservation-before-binding protocol serializes concurrent admissions at the partition and prevents over-capacity races. At recovery, Game Session must enumerate the complete binding and reservation inventory, establish the same finite per-partition invariant for every `p`, and publish no coverage acknowledgement when any non-terminal reservation exceeds capacity. The aggregate proof is additionally required: `N_issuer = sum_p N_issuer_partition(p) <= issuerIndexPartitionCount * issuerIndexPartitionCapacity`. Aggregate capacity alone is insufficient.

Game Session writes and removes issuer fields through a single-key compare-and-set over the complete expected `bindingRef`, `bindingGeneration`, `issuerAuthGeneration`, and `issuerIndexLayoutVersion`, together with the exact reservation identity, owner, transition, partition/layout, lifecycle, fence, and revision. An identical value is an idempotent success; a stale or conflicting value leaves a durable repair obligation and reservation unresolved. The tenant-local binding CAS records `issuerIndexState=REPAIR_REQUIRED` and keeps the binding non-admissible until the index CAS, reservation capacity CAS, and obligation acknowledgement complete; a follow-up binding CAS records `issuerIndexState=ACKNOWLEDGED` only when the same candidate generation/fence remains current. Recovery rebuilds partitions from the complete durable active-binding and reservation inventory using the same expected-value compare-and-set, reads back every expected binding field and reservation entry, enforces the per-partition capacity before publishing coverage acknowledgements, and clears obligations only after all required acknowledgements carry both the operation fence and later `coverageGeneration` for the same layout.

The canonical durable active-binding inventory is a Game Session-owned PostgreSQL `GameplayBindingInventory` (or an equivalent durable control-store record with the same transaction and snapshot guarantees). It contains one row for every `ACTIVE`, `PROVISIONAL`, or unresolved account/issuer repair binding, keyed by the exact `bindingRef`, and records the tenant/game/session identity, `bindingGeneration`, issuer and layout identity, lifecycle state, index-obligation state, account-index transition identity and `accountIndexFence`, every matching `IssuerPartitionReservation` identity/owner/transition/lifecycle/fence/revision, and durable inventory revision. A reservation-only pre-binding row is also included until it reaches terminal `RELEASED`. It is the authority for inventory completeness, recovery enumeration, and cutoff capacity coverage; it is not a substitute for Account authority, the `session:game:*` reconnect record, or the region-local `tick:{tenantRegionTag}:session-binding:<entityId>` command-admission record.

Before a tenant-local binding CAS can publish an active or provisional record, Game Session commits the corresponding inventory row, reservation entry, and account/issuer repair obligations in one durable transaction. Every activation, replacement, terminalization, and removal advances the row and reservation through a fenced compare-and-set; ambiguous cleanup leaves both unresolved rather than deleting either. An issuer-wide recovery or issuer-cutoff operation establishes its `operationId`, `operationFence`, distinct `coverageFence`, immutable `inventorySnapshotRevision`, and pinned active layout before reading all binding and reservation rows and unresolved obligations at or before that revision under a repeatable snapshot. It accounts for every later transition with both the operation fence and a later `coverageGeneration`, deterministically maps every returned binding and reservation to the pinned layout, repairs or removes the exact Redis field and capacity entry with compare-and-set, reads back every expected partition and reservation entry, and records the complete per-partition acknowledgement tuple. Missing rows, an unavailable snapshot, a post-fence transition not represented by both fences and a later `coverageGeneration`, duplicate identities, cross-snapshot/attempt evidence, excess capacity, or any unresolved obligation keeps the scope blocked; Redis `SCAN`, a partial index, or an empty partition cannot establish inventory or capacity coverage.

For preserved-session region- or tenant-scoped recovery, the scoped recovery operation is the durable parent and its issuer-wide coverage operation is a separate durable child per affected issuer. The parent-child edge binds the exact `parentRecoveryOperationId`, `issuerCoverageOperationId`, resolved scope, `issuerId`, parent `operationFence`, child `issuerCoverageOperationFence`, distinct `coverageFence`, and link revision in one durable transaction or exact idempotent compare-and-set. The child is nested under the parent maintenance lock, cannot be reused across parents or parent-fence changes, and cannot authorize the parent by itself. The parent remains fenced until the linked child reaches its coverage-proven lifecycle; a paused, superseded, aborted, or terminally mismatched parent or child invalidates outstanding child work and prevents publication or admission.

An issuer-partition acknowledgement is a child-operation artifact, never a region/tenant parent artifact. Its publication transaction validates both exact operation identities and current lifecycles, the parent `operationFence`, the child `issuerCoverageOperationFence`, and the child `coverageFence` before recording the pinned issuer snapshot/layout/cutoff, later `coverageGeneration`, reservation identities, and partition-local plus issuer-wide capacity tuple. A stale or terminal parent, child, or fence fails closed even when the partition readback is complete; a parent may consume only a complete set of acknowledgements from its linked child.

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
   - A region-scoped coordination reset clears the region-local binding keys for the affected `tick:{tenantRegionTag}:*` family and must carry exactly one explicit session policy, `--preserve-sessions` or `--invalidate-sessions`; there is no implicit default. `--preserve-sessions` retains authenticated gameplay-session and bootstrap transport-context entries for bounded rebind, while `--invalidate-sessions` makes those records non-resumable and requires fresh admission.
   - Before normal command intake resumes for the affected region, Game Session must run a bounded rebind phase for preserved sessions that still intend to participate in that region. The rebind phase evaluates the complete canonical preserved-session rebind predicate below, increments or verifies `binding_generation`, and invokes the same region-lease bridge script that normal `PLAY` / reconnect uses.
   - Until a non-security region rebind succeeds, each gameplay command or admission command that is attempted after valid identity and command-record establishment must be durably terminal with `executionOutcome = REGION_REBIND_REQUIRED` and a non-applied gameplay result (the shared default is `NOT_APPLIED`), or the client must re-`PLAY`; it must not fall back to advisory session fields as gameplay authority. A revoked or invalid token authority follows the socket-close and resume-episode termination transition below, not this command outcome. Missing, invalid, or unauthenticated requests are rejected before command-record creation and must not synthesize either a command record or `REGION_REBIND_REQUIRED`.
   - `REGION_REBIND_REQUIRED` terminalizes the command/admission attempt, not the connected socket or gameplay-session record, but only after all Account identity, token, authority, membership, lease, and delegation-JWT predicates have passed, with entitlement satisfied by either a fresh read or the qualifying bounded fallback above, and the remaining failure is solely a non-security region rebind gap. In that case, if the recorded policy preserves sessions, the session remains connected but region-blocked and cannot admit further commands until rebind succeeds or fresh `LOGIN` / `PLAY` establishes a new binding. A reachable token-registry or Account-authority result of `AUTH_SESSION_REVOKED` or an invalid-token outcome instead marks the session auth-revoked, closes the socket, and ends the current resume episode; it is not a region rebind gap.
   - If preserved session state has a solely non-security region rebind gap after the Account predicates pass, the session remains connected but is no longer admitted to that region until fresh `LOGIN` / `PLAY` succeeds. Unavailable or ambiguous Account evidence remains fenced under the outage policy and is not treated as a region rebind gap.

The canonical preserved-session rebind predicate requires all of the following before the region bridge may recreate a binding:

- A complete target `schemaVersion=2` authenticated gameplay session payload, including `issuanceFence`, `rebindHandle`, `continuityBindingExpiresAt`, `membershipVersion`, `authorityTuple.membershipAuthorityGeneration`, and the complete canonical `outboxCheckpoints` set; a duplicate top-level `membershipAuthorityGeneration` is rejected, while a `schemaVersion=1` or incomplete record is storage-only and cannot be rebound.
- An exact `session:auth:token:<tokenHash>` registry record addressed by the payload's `authTokenHash` that is present, active, unrevoked, and unexpired. The binding's `authTokenIssuedAt`, `authTokenExpiresAt`, and `tokenGeneration` must equal the registry record's `iat`, `exp`, and `tokenGeneration` exactly; Redis TTL, current time, or a newer/older value cannot satisfy any of those fields. Account must validate the payload's opaque `rebindHandle` and prove that its bound token hash, signed `jti` and `nbf`, token lineage, account identity, profile, audience, and exact `issuanceFence` match that registry record and the gameplay binding exactly. The registry is authoritative for `jti`, `tokenGeneration`, `iat`, and `exp`; the Account-validated handle is authoritative for the original signed `nbf`, exact issuance fence, and expected exact-token identity used for comparison. Neither source may substitute for or repair the other, and Game Session must not parse or invent those handle values locally.
- Current Account authority for the exact account and tenant, including the applicable `issuerAuthGeneration`, `accountAuthorityGeneration`, `tenantAuthorityGeneration`, `authorityTuple.membershipAuthorityGeneration`, and private-realm `grantVersion` when applicable, plus current `membershipVersion`, entitlement, revocation, authority-freshness lease, and the complete exact `outboxCheckpoints` set for every applicable authority stream. Missing, extra, stale, regressed, or aggregate-only checkpoint evidence fails closed. For an entitlement read that is unavailable, only the same-binding continuity fallback below may apply; it never supplies current authority evidence.
- A valid `continuityBindingExpiresAt` and either the immutable `resumeDeadline` for a disconnected binding or a trusted connected-session fallback proof when the binding remains connected and `resumeDeadline` is absent. The proof is bound to the exact session/binding identity, `binding_generation`, `authTokenHash`, `issuanceFence`, connection episode with `connectionState=CONNECTED`, and positive Account entitlement evaluation; it carries `connectedSessionDeadline = evaluatedAt + 5 minutes`, using the Account-stamped `evaluatedAt` and the same synchronized-clock/skew rules as the entitlement continuity window. Socket liveness, Redis key presence/TTL, current time, reconnect attempts, and token rotation cannot create or extend that proof. The rebind does not extend any deadline, together with the current operation/region epoch and lease-fence evidence.
- A successful invocation of the region-lease bridge with the validated identity and generation; `session:game:*` and `sessionctx:*` are not authority substitutes.

Entitlement fallback is deliberately non-circular. New `PLAY`, a new binding, a target change, expansion, or any capacity-creating operation requires a fresh Account-authenticated `account-auth-evidence-bundle/v1`; if that bundle is unavailable, stale, contradictory, or gapped, the operation fails closed with `ENTITLEMENT_UNAVAILABLE` or the applicable stale-admission error. Only resume/rebind of the same unchanged binding may use a previously accepted positive bundle retained in a protected binding-continuity record. The fallback is permitted only for an entitlement-read-only outage, while every other current Account predicate succeeds, the exact activation lease is committed, and a fresh Account-authenticated delegation JWT check succeeds; it never supplies authority, revocation, checkpoint, lease, or token evidence. That record must contain the exact target, session/binding identity, `binding_generation`, connection episode and state, `bundleVersion`, `snapshotIdentity`, `evaluationIdentity`, Account-stamped `evaluatedAt`, immutable `connectedSessionDeadline = evaluatedAt + 5 minutes` when the binding is connected, `entitlementVersion`, `tenantBillingSequence`, applicable `tenantBillingCutoff`, complete `authorityTuple`, and complete `outboxCheckpoints` from one Account evaluation. A disconnected binding is eligible only when its immutable `resumeDeadline` is present; a connected binding is eligible without `resumeDeadline` only when the exact connected-session proof is present and its deadline has not passed. At `fallbackAt`, define `effectiveContinuityDeadline = min(continuityBindingExpiresAt, resumeDeadline)` when `resumeDeadline` is present, otherwise `effectiveContinuityDeadline = min(continuityBindingExpiresAt, connectedSessionDeadline)`. Then define `entitlementFallbackDeadline = min(effectiveContinuityDeadline, fallbackAt + 5 minutes, fallbackAt + session_expiration_ms)`, where `session_expiration_ms = min(FIREMUD_AUTH_SESSION_EXPIRATION_MS, 300000)`. The fallback is usable only before that deadline and only for the unchanged binding; when Account is reachable for the entitlement read, the live bundle is required and must equal those stored identity/version/checkpoint fields. An unavailable non-entitlement predicate, activation lease, or delegation-JWT check never qualifies for fallback. A missing fallback record or connected-session proof, missing `resumeDeadline` for a disconnected binding, known newer billing sequence or authority fence, expired grace window, target change, negative/revoked snapshot, or contradictory local evidence fails closed. The fallback never validates itself by treating its own version as current authority.

Rebind consumption is linearized by the durable Account operation, not by Redis key presence. A new operation with a handle bound to an older `issuanceFence` is stale and fails closed without consuming the handle or issuing a replacement. An exact retry matches the durable request ID, digest, workload, binding, handle identity, and issuance fence and replays the stored result without another consumption or mint; if the fence advanced before a credential can be replayed, the retry returns stable stale/failure metadata instead. Redis CAS or recovery must not override that outcome.

A failed non-security preserved-session predicate never implicitly changes the recorded session policy. The operation remains paused and fenced under its existing `operationId` and `maintenanceLockToken`; an explicit audited preserve-to-invalidate transition may compare-and-set the policy under that same lock, recording actor, reason, and immutable evidence before invalidation. If that same-lock transition is unavailable, the operator must complete audited abandonment and start an explicit new recover operation with `--invalidate-sessions`. The invalidation proof is not inferred from a non-security rebind failure; a revoked or invalid token authority follows the explicit socket-close and resume-episode termination transition above.

### Canonical Pre-Wipe Gates

For a destructive full-deployment or AOF reset, the canonical pre-wipe gates are named `scope_paused_and_locked`, `account_authority_token_cutover`, `replay_domain_quarantine_fence`, and `immutable_external_handoff_evidence`. These are internal evidence gates, not public commands; every gate must be durably bound to the same `operationId` and server-issued `maintenanceLockToken` before the external storage action occurs.

- `scope_paused_and_locked` proves canonical `PAUSED`, blocked command and batch intake, no in-flight executor work, and no old-epoch coordination writer.
- `account_authority_token_cutover` proves protected admission is closed and Account's durable authority/token identity cutover and required immutable evidence are complete for the operation's scope.
- `replay_domain_quarantine_fence` proves the shared replay domain is either verified untouched for a narrower reset or quarantined and fenced for the destructive reset, with its immutable fence evidence recorded.
- `immutable_external_handoff_evidence` contains only pre-wipe authorization and fencing facts: the old and intended new deployment, fenced endpoint, authorized operator and action, time, and tooling digest. It contains no post-wipe health or replacement observations.
- `post_reset_replacement_verification` is a separate evidence group recorded after the replacement starts; it is controller/trusted-attestor generated from a fresh challenge bound to the same `operationId`, resolved scope, and replacement deployment identity, never an operator assertion. The controller independently verifies endpoint identity, health, ACL/configuration, and empty keyspace before recovery continuation, and rejects replayed, expired, mismatched, or caller-supplied evidence.

This contract keeps region-local correctness shard-safe while still letting reconnect and takeover flows carry session-wide intent. It also means the system tolerates brief mismatches between `session:game:*` and region-local bindings: the region-local binding key is authoritative for gameplay, while `session:game:*` remains authoritative for reconnect semantics.

Gameplay timers and cooldowns (combat cooldowns, regen ticks, delayed effects) are not “session state”: they are region/entity gameplay state. Durable actor timed-state records are authoritative; tick timer keys are reconstructible scheduling projections used to wake expiry or retry work. This lets cooldowns continue correctly in idle regions and across reconnects without treating Redis session keys as their source of truth.

Key properties:

- `sessionId` is an opaque, server-generated identifier (for example, a UUID or fixed-length hash) chosen so key length stays bounded and independent of the raw JWT or account token.
- Session entries use a **derived physical Redis TTL** computed from authentication settings (see `infrastructure/environment-and-secrets-catalog.md#authentication--jwt`):

  - **Target state:** `session_expiration_ms = min(FIREMUD_AUTH_SESSION_EXPIRATION_MS, 300000)`.
  - `session_expiration_ms` derives the initial gameplay continuity-retention and cleanup horizon. It is not a JWT validity period or a cutoff for healthy uninterrupted play.
  - On successful gameplay admission at `admissionAt`, the session value stores an immutable logical expiry anchor:

    `continuityBindingExpiresAt = admissionAt + session_expiration_ms`

  - The Redis TTL for `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` is physical cleanup metadata. An active-binding refresh atomically preserves the earliest of its existing physical expiry, the requested refresh deadline, and `continuityBindingExpiresAt`; it must not recreate a missing/expired binding, extend an earlier cleanup deadline, or move `continuityBindingExpiresAt`.
- Continuity-binding expiry is authoritative for resumption but does not itself end a continuously connected, currently authorized session. Game Session rejects reconnect/resume once `continuityBindingExpiresAt` has passed, even if the Redis key remains after delayed expiration, AOF replay, or failover drift. Physical deletion can remove the key earlier; a missing key is also non-resumable. Key presence and physical TTL are never permission to resume.
- For a binding disconnected or suspended at `disconnectAt`, the effective resume deadline is:

  `resumeDeadline = min(continuityBindingExpiresAt, disconnectAt + effective firemud.reconnection.policy.resume-window-ms)`

  Resume also requires current account identity, membership authority, entitlement, and revocation checks. The pair is immutable for that disconnection episode: failed reconnects, takeover attempts, and server-token rotation cannot change it. Successful resume consumes the episode; a later connected-to-disconnected transition creates a new pair bounded by the original continuity anchor. A genuinely fresh `PLAY` admission creates a new binding and anchor only after ordinary admission succeeds.
- JWT validity remains bounded by each token's own `exp` claim. Game Session may atomically rotate the complete token identity and `rebindHandle` only after Account validates the current token-identity fence, token lineage generation, and current authority generations, but rotation cannot cross a blocking generation advance or extend continuity-binding expiry or resume eligibility.

Session design assumes **reasonably synchronized clocks** on Game Session nodes (for example, via NTP); large clock skew is treated as an infrastructure misconfiguration, not a normal edge case of the session protocol. The effective disconnected-resume window is the stricter of the remaining continuity-binding lifetime and `firemud.reconnection.policy.resume-window-ms`.

### Operational Trade-Offs for Session and Resume Lifetimes

`session_expiration_ms` is an independent gameplay-continuity setting derived only from `FIREMUD_AUTH_SESSION_EXPIRATION_MS` and capped at five minutes. Active authorization, JWT validity, and disconnected resume remain separate policies:

- Each JWT remains unusable after its own `exp`, regardless of the Redis TTL or gameplay binding anchor.
- The immutable continuity anchor prevents token rotation from extending old-binding resume eligibility; it does not force a healthy connected player through fresh admission.
- Tenant/game continuity policy can choose a shorter resume window without changing credential or token validity semantics.

When changing authentication settings, keep these trade-offs in mind:

- **Shorter JWT lifetime**
  - Reducing `FIREMUD_AUTH_JWT_EXPIRATION_MS` changes the lifetime of newly issued `control-ui` and private player-delegation JWTs. `player-bootstrap` JWTs use the separate `firemud.account.tokens.player-bootstrap-expiration-ms` setting (default `300000` ms; production may override it with `FIREMUD_ACCOUNT_TOKENS_PLAYER_BOOTSTRAP_EXPIRATION_MS`), so changing the global JWT setting does not change their lifetime. None of these settings changes `session_expiration_ms`, an already issued token's signed `exp`, moves an existing continuity anchor, or shortens a healthy uninterrupted session by itself.
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

Issued-token registry records (`session:auth:token:<tokenHash>`) use each record's actual JWT `exp` plus the cleanup margin rather than gameplay's global `session_expiration_ms` derivation. The exact-token record is authoritative for runtime admission and per-token revocation, but it is not the durable Account generation authority. A per-token logout or equivalent revocation may remove the Redis record before natural expiry only after the durable Account `PENDING_LOGOUT`/revocation fence or tombstone intent commits; the record deletion is not the fence, tombstone, or negative proof. A reachable missing record denies the JWT and requires reauthentication, while an unavailable registry remains `AUTH_UNAVAILABLE`. Their reset policy is independent of gameplay-session preservation: region- and tenant-scoped coordination resets preserve these Account-owned records, while a cluster reset must close protected admission, complete the Account repair/reset cutover and durable issuer-generation advance, drop the old records as physical cleanup, then rebuild and prove the current generation projection before any replacement records are issued or protected admission reopens. The `--preserve-sessions` option applies only to region- and tenant-scoped gameplay session and bootstrap-context records; it never preserves or deletes issued-token registry records, and cluster scope rejects it in favor of explicit `--invalidate-sessions`. The records are documented in detail in `system-architecture-jwt-and-token-contracts.md` and the Account Service design, and live on Coordination Redis so a cluster reset can force re-authentication in a controlled way.

### Gateway Connect-Token Replay Markers

Gateway connect-token replay state is a narrow security-critical exception to the general Redis coordination model. The accepted carrier, validation, consumption, readiness, and quarantine contract is defined by [Gateway Architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake) and [ADR 0029](./decisions/adr-0029-single-use-gameplay-connect-token-carriage.md). This section fixes its Redis lifetime and reset scope:

- Each accepted token creates at most one exact-`jti` marker at `gateway:connect-token:jti:<jti>` in the player-facing Coordination Redis deployment. The marker is written with an absolute expiry of `token.exp + firemud.gateway.connectTokenClockSkewMs`; equivalently, its initial relative TTL is `max(1, token.exp + clockSkewMs - now)`. It is never refreshed, extended from consumption time, or replaced with a fixed 30-second TTL. The deployed clock-skew setting is the single `FIREMUD_GATEWAY_CONNECT_TOKEN_CLOCK_SKEW_MS` value, default `5000` ms and bounded to `0..5000` ms.
- The current deployment has one shared replay-continuity domain, so replay markers and the shared `replayAdmissionFence` are not tenant- or region-partitioned. Region- and tenant-scoped coordination resets must leave the Gateway replay prefix and readiness record untouched and must not invalidate connect tokens solely because gameplay coordination in that narrower scope was reset. If either reset cannot prove that the shared replay domain was untouched and continuous, it escalates to the same shared quarantine rather than attempting a narrower fence.
- A cluster-scoped Coordination Redis reset, loss of marker continuity, uncertain failover, eviction, capacity breach, or durability-acknowledgement failure invalidates the shared replay domain. The reset drops or makes untrusted all replay markers and readiness state, advances the shared fence, rejects new first-party handshakes for at least the maximum gameplay-connect lifetime (`30 seconds`) plus two configured clock-skew intervals after the recorded detection cutoff, and reopens only after the configured disposable-marker plus `WAITAOF` proof establishes `DURABLE_REPLAY_CONSUME_ACK`. Existing WebSockets are not closed by replay-state reset alone; the broader cluster reset separately requires explicit gameplay-session invalidation.
- A future partitioned replay deployment may narrow quarantine to a proven marker/fence domain only after its topology and reset contract establish that isolation. It must not be inferred from a tenant or region label in the current shared deployment.

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

Redis is used primarily for non‑authoritative, transient data. The exact-token registry below is the narrow runtime security exception:

- In‑flight command queues and tick staging structures.
- Tick locks and executor leases.
- Reconstructible timer, cooldown-expiry, and retry scheduling metadata (stored in milliseconds).
- Gameplay session state and live coordination (session bindings, queue participation).
- Best‑effort caches for hot‑path aggregates and chat history in Cache/Rate‑Limit Redis.
- Automation queues and coordination hints that can be reconstructed from durable domain state.
- Exact-token runtime admission and per-token revocation records under `session:auth:token:<tokenHash>`; these are authoritative for that token only, while Account's durable issuer/account/tenant/membership generations remain authoritative for generation advancement.

Implications:

- Losing ordinary coordination keys within the tail‑loss envelope must behave like lost/reordered messages or delayed timers, not permanent data corruption. Losing an exact-token registry record fails closed for that token's protected runtime admission; it does not advance or replace Account's durable generations.
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
  - Region-scoped coordination keys include both `gameInstanceId` and `regionId` so tick workloads and timers are scoped to the complete tick-region identity.
  - Human‑readable values (character names, room titles) are **never** embedded directly in keys; only stable identifiers (numeric IDs, UUIDs) appear in key components.
  - Explicit exceptions: the shared Gateway replay domain (`gateway:connect-token:jti:<jti>` markers and its `replayAdmissionFence`), Account-owned exact-token registry records (`session:auth:token:<tokenHash>`), the Game Session-owned global account active-binding index (`session:game:index:account:<accountId>`), and the Game Session-owned global issuer active-binding index (`session:game:index:issuer:{issuerIndexLayoutTag}:<issuerId>:<partitionId>`) are intentionally not tenant- or region-tagged. Exact-token records remain outside tenant/region key-tagging rules and are untouched by region- or tenant-scoped coordination resets. The global account index is one full-key-hash-sharded key whose members are generation-safe tenant-qualified binding references; narrower resets reconcile only affected references rather than deleting unrelated tenants. The global issuer index is also outside those tags, but narrower resets reconcile only their affected binding references rather than flushing a whole issuer partition.

- **`{tenantRegionTag}` hash tag**
  - Tick‑region coordination keys use a canonical hash tag placeholder `{tenantRegionTag}` derived from the complete `<tenantId, gameInstanceId, regionId>` scope. The projection must remain collision-safe; callers treat the concrete encoding as opaque.
  - Properties:
    - The concrete string format is an implementation detail of shared key helpers; callers treat it as opaque.
    - All region‑scoped keys for a given `<tenantId, gameInstanceId, regionId>` share the same full-scope `{tenantRegionTag}` and therefore land in the same Redis Cluster slot.
    - Multi‑key coordination scripts must only receive keys that share the same hash tag; CI and helpers enforce this.
  - Representative patterns:
    - `tick:{tenantRegionTag}:lock:<entityId>`
    - `tick:{tenantRegionTag}:pending`
    - `timer:{tenantRegionTag}`
    - `retry:{tenantRegionTag}`
    - `tick-executor-lease:{tenantRegionTag}`

- **`{tenantInstanceTag}` hash tag**
  - Instance-scoped coordination or automation projection keys use a canonical opaque hash tag placeholder `{tenantInstanceTag}` derived from `<tenantId, gameInstanceId>`.
  - The concrete string format is an implementation detail of shared key helpers; callers must not replace it with a tenant-only tag or append a second raw instance identifier.

- **`{tenantGameplayTag}` hash tag**
  - Session-only gameplay keys use a canonical hash tag placeholder `{tenantGameplayTag}` derived from `<tenantId>`.
  - Properties:
    - The concrete string format is an implementation detail of shared key helpers; callers treat it as opaque.
    - The session record plus its tenant-scoped uniqueness and reverse indexes for one tenant share the same `{tenantGameplayTag}` and therefore land in the same Redis Cluster slot.
    - Session-only Lua scripts may perform shard-local multi-key CAS/update flows across these tenant-scoped keys, but they must not include the untagged global account index, mix `{tenantGameplayTag}` session keys with `{tenantRegionTag}` tick-region keys, or claim cross-slot account-index atomicity in the same invocation.
  - Representative patterns:
    - `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`
    - `session:game:index:character:{tenantGameplayTag}:<gameInstanceId>:<characterId>`
    - `session:game:index:account-tenant:{tenantGameplayTag}:<accountId>`
    - `session:game:index:tenant:{tenantGameplayTag}`
    - `session:game:index:realm-grant:{tenantGameplayTag}:<worldSlug>:<realmSlug>:<accountId>`

- **`{issuerIndexLayoutTag}` hash tag**
  - Issuer active-binding index partitions use the canonical opaque hash tag `{issuerIndexLayoutTag}` derived from `(issuerIndexLayoutVersion, issuerId, partitionId)`. Each partition is independently readable and writable; issuer partitions are not mixed with `{tenantGameplayTag}` keys in a shard-local Lua invocation.
  - Representative pattern: `session:game:index:issuer:{issuerIndexLayoutTag}:<issuerId>:<partitionId>`.

- **Coordination vs cache prefixes**
  - Coordination prefixes (for example `tick:*`, `timer:*`, `retry:*`, `session:game:*`, `session:auth:*`, `tick-executor-lease:*`) live **only** on Coordination Redis.
  - Cache and rate‑limit prefixes (for example `inventory:*`, `view:room-look:*`, `ratelimit:*`) live **only** on Cache/Rate‑Limit Redis.
  - Coordination prefix ownership is normative: Account owns the exact-token registry `session:auth:token:<tokenHash>` and all canonical `session:auth:generation:*` projections. Game Session owns only explicitly approved, separately named `session:auth` subprefixes; it never owns the Account token or generation families, and its current issuer-generation consumer projection is instead under its own namespace as `session:game:auth:issuer-generation:v1:<issuerId>`. No wildcard `session:auth:*` grant is implied. Game Session owns the gameplay coordination keyspaces `tick:*`, `timer:*`, `retry:*`, `session:game:*`, and `tick-executor-lease:*`; it does not own or mutate the Automation & Scripting families `automation:tick:*`, `automation:timer:*`, or `script-scheduler:*`. Automation & Scripting owns those automation families, and non-owner participation is contract-bound through approved helpers. See `design/architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md`.
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

- In normal application and routine runbook paths, all coordination mutations use owned typed key and mutation helpers; raw Redis commands are prohibited. The external infrastructure step of a fenced recovery/reset is permitted only after the durable operation and maintenance lock, protected Account authority/token and replay-domain cutover/fence, and immutable pre-wipe evidence are complete. After replacement starts, immutable `post_reset_replacement_verification` must be recorded and validated before `continueRecovery` or protected admission can reopen. Incident break-glass writes remain incident-only and must follow the documented reset and verification lifecycle; none of these exceptions authorize ordinary raw commands or bypass the lifecycle. Registered Lua is mandatory for atomic multi-key behavior and may be used for a single-key mutation only when an explicitly documented atomic guard or compare-and-set contract requires it; ordinary single-key mutations use typed helpers without Lua. Any Lua multi-key operation must be shard-local, with all `KEYS` sharing the same `{tenantRegionTag}`, `{tenantInstanceTag}`, `{tenantGameplayTag}`, or `{issuerIndexLayoutTag}` hash tag and Redis Cluster slot. An issuer-index invocation may contain only keys with one exact issuer layout/partition tag and must not mix issuer-index keys with tenant, instance, or region keys.
- Cross-region behavior is implemented via per-region operations and durable follow-up records in PostgreSQL, **not** via cross-region multi-key scripts.
- Callers always construct keys via shared key helpers (for example, builders in `firemud-common`) so `{tenantRegionTag}`, `{tenantInstanceTag}`, `{tenantGameplayTag}`, `{issuerIndexLayoutTag}`, prefixes, and slots remain consistent; scripts and callers must not hand-roll key strings with embedded hostnames, region names, or ad-hoc hash tags.
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
| `session:game:index:account:<accountId>` | Untagged global account-wide set of generation-safe tenant-qualified active-binding members. Game Session owns the index and its repair/reconciliation protocol; it is a deliberate cross-tenant exception for account-wide revocation and repair, not an authorization source and never a tenant-local Lua/CAS key. |
| `session:game:index:account-tenant:{tenantGameplayTag}:<accountId>` | Tenant-scoped account reverse index used for bounded revocation, reconnect, and inspection without wildcard scans. |
| `session:game:index:tenant:{tenantGameplayTag}` | Tenant-scoped reverse index used for bounded revocation, reconnect, and inspection without wildcard scans. |
| `session:game:index:realm-grant:{tenantGameplayTag}:<worldSlug>:<realmSlug>:<accountId>` | Tenant-scoped grant-gated realm index used for bounded realm admission and revocation; Account-owned grant state remains authoritative. |
| `session:game:index:issuer:{issuerIndexLayoutTag}:<issuerId>:<partitionId>` | Global-per-issuer bounded hash of active/provisional binding references and captured issuer generations across tenants, maintained by Game Session through the versioned-layout repair/CAS and durable coverage-ack protocol and swept across the finite configured partition range for issuer cutoffs. |
| `retry:{tenantRegionTag}` | Retry queue for failed actions, keyed by `next_eligible_tick_id` on the target region timeline (not wall-clock due time). |
| `timer:{tenantRegionTag}` | Sorted set of timers for a region; score is expiration timestamp (ms), members encode entity/effect metadata. |
| `remote:{tenantInstanceTag}:<entityId>` | Best‑effort, TTL-bounded hint marker scoped to the complete `<tenantId, gameInstanceId>` identity for cross‑region follow‑ups (durable follow‑ups live in PostgreSQL). Default `remote_hint_ttl_ms = 60_000`; expiry/missing keys affect latency only. Tenant/cluster reset tooling resolves game instances from the durable scope inventory and scans one `remote:{tenantInstanceTag}:*` pattern per instance; no tenant-only remote key family exists. |
| `route:{tenantRegionTag}:gamesession` | Reserved for a potential future gameplay routing view for `<tenantId, gameInstanceId, regionId> → shardTarget`. Per ADR 0007, lease-aware edge admission and a client-visible shard handoff signal are not part of the current edge contract; do not implement Gateway consumption of this mapping without a dedicated sharding/routing design update. |
| `tick-events:{tenantRegionTag}` and `tick-events-offset:{tenantRegionTag}:<consumerId>` | Best‑effort per-region tick event stream and per-consumer offset (typically Redis Stream entry ID). Each consumer's offset value must include `{tenantId, gameInstanceId, regionId, consumerId, regionEpoch, latestTickId, streamOffset}`; consumers compare `regionEpoch` with the current control-plane epoch and discard that consumer's value before reuse on mismatch. Streams are retention-capped (default `tick_events_maxlen = 2048` per region); consumers treat trimmed history as normal truncation and bootstrap from committed heartbeats/RegionStatus. |
| `tick-events-lease:{tenantRegionTag}` | Best-effort lease to avoid duplicate tick-event consumption work by observers. Safe to drop; consumers reacquire after restarts/resets. |
| `automation:timer:{tenantRegionTag}` | Region-scoped Automation & Scripting timer index for `onInterval` and timer coordination. Stored entries remain instance-aware in payload and durable identity (`gameInstanceId`, and plugin identifiers when applicable) even though the Redis key is region-scoped for slotting and reset targeting. |
| `script-scheduler:{tenantRegionTag}:lastTickId` | Automation & Scripting derived discovery hint for “every N ticks” triggers. Its value contains `{regionEpoch, latestTickId}` and is rejected/rebuilt when the stored epoch differs from the authoritative epoch. It never stores or owns `streamOffset`; `tick-events-offset:{tenantRegionTag}:<consumerId>` is the sole event-stream offset record for each consumer. Durable automation schedules, quotas, and trigger-instance de-duplication live in PostgreSQL; this key is not the source of truth for which scripts should eventually run or whether a due trigger was already emitted. |

Region‑scoped coordination metadata keys share the same full-scope `{tenantRegionTag}` hash tag and therefore carry `<tenantId, gameInstanceId, regionId>` while landing in the same Redis Cluster slot. Instance-scoped projections use `{tenantInstanceTag}` for `<tenantId, gameInstanceId>`, and tenant-scoped gameplay session keys share `{tenantGameplayTag}` for session-only CAS/index updates. Pre-auth transport context and tenant-only auth/quotas are intentionally outside the runtime region scope and must retain their documented lifecycle scope.

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
    - Region- or tenant-scoped resets normally bump `region_epoch` for the affected `<tenantId, gameInstanceId, regionId>` tuples and invalidate any pre-reset executor leases.
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

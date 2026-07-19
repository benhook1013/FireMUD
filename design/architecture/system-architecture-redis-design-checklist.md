# FireMUD Redis Design Checklist

This document consolidates the **required checks** before changing any Redis‑related behavior in FireMUD. It complements the conceptual hub (`system-architecture-redis.md`) and the detailed design docs for Lua, cache, and operations.

Use this checklist during design and review whenever you:

- Introduce or change a **coordination prefix**.
- Introduce or change a **cache or rate‑limit prefix**.
- Add or modify a **Lua script**.
- Change **Redis profiles, topology, or reset behavior**.

---

## Redis Design Change Workflow

Before applying the detailed checklists below, follow this high‑level workflow whenever you change Redis usage:

1. **Clarify intent and scope**
   - Identify whether the change affects coordination prefixes, cache/rate‑limit prefixes, Lua scripts, Redis profiles/topology/reset behavior, or some combination.
   - Decide which tenants/regions or services are in scope for the change and how it interacts with reset tolerance classes (reset‑tolerant, reset‑sensitive, reset‑forbidden) from `system-architecture-redis-reset-and-recovery.md`.
2. **Update canonical catalogs and routing docs**
   - For **coordination prefixes**:
     - Update the reset policy matrix in `system-architecture-redis-reset-and-recovery.md` (prefix naming, role, reset tolerance).
     - Ensure any new or changed prefixes appear in the **Redis Cheat Sheet** (`system-architecture-redis-cheatsheet.md`) as a routed, documented example.
     - Ensure the owning service README Redis sections mirror any authority split or bridge contract introduced here (for example `session:game:*` vs `tick:{tenantRegionTag}:session-binding:<entityId>`, `binding_generation`, or automation enqueue identities such as `automationDispatchId`).
   - For **cache/rate‑limit prefixes**:
     - Update the cache key catalog in `system-architecture-redis-cache.md` (prefix, role, owner, correctness class, reset tolerance).
     - Ensure the cheat sheet remains consistent with the cache catalog for any representative entries it lists.
3. **Update service‑specific docs and shared libraries**
   - Document prefix ownership, reset behavior, and Redis role (Coordination vs Cache/Rate‑Limit) in the relevant service README(s) under their Redis sections (for example, Game Session, Automation & Scripting, Game Logic, Gateway).
   - For coordination changes, update the Lua Script Registry descriptors and key‑builder helpers in `firemud-common` so services and ops tooling share the same key shapes and hash‑tag rules.
4. **Apply the detailed checklists**
   - Run through the relevant sections below:
     - **Coordination Prefix Checklist** for any coordination prefix changes.
     - **Cache / Rate‑Limit Prefix Checklist** for caches or rate limits.
     - **Lua Script Checklist** for any script changes.
     - **Profile / Topology / Reset Checklist** when changing profiles, reset behavior, or environment mappings.
5. **Align reset and incident runbooks**
   - Confirm that `system-architecture-redis-reset-and-recovery.md` describes safe reset scopes for any affected prefixes.
   - Update or validate the corresponding runbooks in `system-architecture-redis-operations.md` and the Redis incident runbook so operators have a clear path for resets and “accept loss” decisions.
6. **Wire observability and metrics**
   - Ensure required metrics and alerts for AOF size/growth, tail‑loss, prefix key counts, and script outcomes are covered or updated in the Redis metrics catalog in `system-architecture-redis-operations.md`.
   - When the change introduces new state-machine fields or outcome codes, ensure the operations docs and metrics catalog name them explicitly (for example `current_tick_state`, `STALE_SESSION_GENERATION`, and stale automation-dispatch outcomes) rather than relying on generic script-failure buckets.
   - Verify that dashboards and alerts referenced in service docs and the incident runbook line up with the new or changed prefixes/scripts.

Only after these workflow steps are accounted for should a change be considered “ready” to leave design review and move into implementation.

---

## Table of Contents

- [Redis Design Change Workflow](#redis-design-change-workflow)
- [Coordination Prefix Checklist](#coordination-prefix-checklist)
- [Cache / Rate-Limit Prefix Checklist](#cache--rate-limit-prefix-checklist)
- [Lua Script Checklist](#lua-script-checklist)
- [Profile / Topology / Reset Checklist](#profile--topology--reset-checklist)

---

## Coordination Prefix Checklist

Use this when adding or changing coordination prefixes (for example `tick:*`, `timer:*`, `retry:*`, `session:game:*`, `session:auth:*`, `tick-executor-lease:*`).

### Role and Scope

- [ ] Confirm the prefix lives on **Coordination Redis** only.
- [ ] Identify the owning service(s) and document them in the service README and Redis cheat sheet.
- [ ] Define the scope of the keys:
  - [ ] `tenantId` dimension.
  - [ ] `regionId` or equivalent region concept where applicable.

### Key Naming and Shard Discipline

- [ ] Key format uses `tenantId` (and `regionId` if applicable) as stable IDs, not user‑provided strings.
- [ ] Region‑scoped keys use `{tenantRegionTag}` (or an equivalent canonical hash tag) so all keys for a region land in a single cluster slot.
- [ ] Session-vs-region authority is explicit where applicable:
  - [ ] `session:game:*` remains session-authoritative for reconnect/CAS semantics.
  - [ ] Any region-local gameplay participation key (for example `tick:{tenantRegionTag}:session-binding:<entityId>`) is documented as region-authoritative and mutated only by region-lease scripts.
  - [ ] Monotonic bridge fields such as `binding_generation` are named and their stale-generation behavior is documented.
- [ ] Multi‑key scripts operating on this prefix:
  - [ ] Only touch keys that share the same hash tag and slot.
  - [ ] Do not mix coordination and cache prefixes in one invocation.
  - [ ] If the script acquires an entity lock, it acquires at most one entity-lock key; no piecemeal or multi-entity lock acquisition is permitted.
- [ ] If a coordination flow uses session-to-region bridge scripts, that category is called out explicitly in the Lua Script Registry and service docs rather than treated as an unnamed region-lease special case.
- [ ] For tick-region coordination, epoch/tick metadata is read and written through the canonical `tick:{tenantRegionTag}:meta` hash key defined in the Redis architecture doc, not via ad-hoc per-script metadata keys.

### Tail-Loss and Reset Behavior

- [ ] The design explicitly states:
  - [ ] Whether the prefix is **reset‑tolerant**, **reset‑sensitive**, or **reset‑forbidden**.
  - [ ] How the environment-measured unreplicated-write exposure affects each ADR 0058 work class and its player-visible outcome.
  - [ ] Whether coordination loss is acceptable for all flows that depend on this prefix and which durable intent or terminalization path applies when it is not.
- [ ] The design defines a hard growth bound for the prefix and how it is enforced (`TTL`, `MAXLEN`, max cardinality, or equivalent), including default values for new deployments.
- [ ] Flows that are **not** tail‑loss compatible (for example, real‑money or cross‑tenant transfers) use durable domain mechanisms and do not rely solely on Redis.
- [ ] The appropriate reset scope (region/tenant/cluster) is documented in the service design and referenced from **Redis Reset & Recovery**.

### Observability

- [ ] Metrics exist to track:
  - [ ] Key counts or approximate size for this prefix by tenant/region.
  - [ ] Error or outcome codes from relevant Lua scripts.
  - [ ] Any important watermarks (for example, tick IDs, backlog depths).
- [ ] For session-to-region bridge flows, metrics and alerts can distinguish stale-generation cleanup, successful region rebinds, and orphaned region bindings detected after session expiry.
- [ ] Dashboards and alerts consider this prefix when assessing tail‑loss SLOs.

---

## Cache / Rate-Limit Prefix Checklist

Use this when adding or changing cache or rate‑limit prefixes (for example `inventory:*`, `view:room-look:*`, `world-dynamic:*`, `ratelimit:*`, `automation:queue:*`).

### Role and Behavior

- [ ] Confirm the prefix lives on **Cache/Rate‑Limit Redis** only.
- [ ] Clearly state whether it is:
  - [ ] A **versioned** cache (strong validation via version/`lastModified`), or
  - [ ] A **TTL‑only** best‑effort cache that tolerates occasional stale reads.
- [ ] The cache’s correctness expectations (staleness tolerance, acceptable data loss) are documented in the relevant service design.

### Eviction and TTL

- [ ] Set explicit TTLs that match the data’s volatility and usage patterns.
- [ ] Confirm eviction is acceptable:
  - [ ] Cold caches should degrade into acceptable behavior (extra DB reads, recomputation).
  - [ ] Rate‑limit keys may be dropped without violating security or fairness guarantees.
- [ ] For versioned caches:
  - [ ] The authoritative store exposes a version or `lastModified` field.
  - [ ] Cache invalidation logic clearly ties updates to version changes.

### Key Shape and Multi-Tenant Behavior

- [ ] Keys include `tenantId` and any other isolation dimensions required (for example, per‑player or per‑guild).
- [ ] For rate‑limit prefixes:
  - [ ] Bucketing strategy is documented (per‑client vs hashed buckets).
  - [ ] Memory and key count growth per tenant/time window stays within budget.

### Redis Role Separation

- [ ] No coordination logic depends on these keys being present or accurate.
- [ ] Cache and rate‑limit keys are **never** written to Coordination Redis.

---

## Lua Script Checklist

Use this when adding or changing Lua scripts that operate on coordination or cache prefixes.

### Determinism

- [ ] Script behavior is a pure function of:
  - [ ] `KEYS[...]` and `ARGV[...]` arguments.
  - [ ] Current Redis state for the keys it reads.
- [ ] Script does **not** use:
  - [ ] `math.random` or other RNG to influence behavior.
  - [ ] `TIME` or other clock‑dependent primitives in control flow or key contents.
  - [ ] Global mutable state or side effects outside Redis.

### Key Handling and Hash Tags

- [ ] All keys are passed via `KEYS[...]`; no hard‑coded key concatenation in Lua.
- [ ] Multi‑key scripts only operate on keys that share a hash tag and cluster slot.
- [ ] Script category (tick lock, timer queue, session CAS, automation, etc.) is documented in the Lua Script Registry.

### Idempotency and Replay Safety

- [ ] Re‑running the script with the same `KEYS`/`ARGV` in the same state does not apply additional logical effects.
- [ ] Script uses set‑style semantics, version checks, or membership checks to avoid duplicate entries on replay.
- [ ] Error outcomes (`STALE_LEASE`, `STALE_LOCK`, `UNSUPPORTED_SCHEMA_VERSION`, etc.) are explicit and non‑mutating.
- [ ] Registry entry defines an explicit, low-cardinality outcome enum for the script and classifies each outcome as one of: success/applied, replay/no-op, stale-timeline, validation-failure, contention/capacity.
- [ ] Caller contract for outcomes is explicit:
  - [ ] Unknown outcomes are treated as fatal (log + metric + alert), not inferred as success.
  - [ ] Retryable outcomes are explicitly listed; callers do not blind-retry validation failures.

### Schema Versioning

- [ ] Structured payloads include an explicit `schemaVersion`.
- [ ] Script:
  - [ ] Treats missing `schemaVersion` as a defined default.
  - [ ] Supports at least the current and previous schema versions during rollout.
  - [ ] Returns an explicit non‑mutating outcome for unknown versions.

### Testing and Registry

- [ ] Script is registered in the Lua Script Registry with:
  - [ ] Name and file path.
  - [ ] Expected `KEYS`/`ARGV` ordering and allowed prefixes.
  - [ ] Category and reset‑tolerance assumptions.
- [ ] Tests cover:
  - [ ] Initial run from a clean state.
  - [ ] Pure replay with identical `KEYS`/`ARGV`.
  - [ ] Replay after partial success (keys pre‑populated).
  - [ ] For outcomes documented as non-mutating, tests prove zero writes (including no TTL refresh side effects).

---

## Profile / Topology / Reset Checklist

Use this when changing Redis profiles, topologies, or reset behavior.

### Profile and Environment Mapping

- [ ] Target profile (`dev_local`, `hobby_self_hosted`, `production_clustered`, or documented variant) is clearly defined.
- [ ] For each environment (local, CI, staging, prod):
  - [ ] Document which profile it approximates.
  - [ ] Record AOF, `maxmemory`, and clustering settings for each role.

### Topology Compatibility

- [ ] Verify that:
  - [ ] Coordination scripts and key patterns remain valid on the chosen topology (single‑node vs cluster).
  - [ ] `{tenantRegionTag}` and shard‑local rules are enforced for coordination in cluster mode.
- [ ] Ensure that:
  - [ ] Coordination and Cache/Rate‑Limit Redis remain separate deployments, even when co‑located on a host.
  - [ ] Configuration helpers and dashboards detect when roles accidentally point to the same endpoint.

### Reset Model

- [ ] Design changes explicitly state:
  - [ ] Which reset scopes are safe (region, tenant, cluster).
  - [ ] Whether reset or accept‑loss is the expected response to problems.
- [ ] Runbooks in `system-architecture-redis-operations.md` are updated or confirmed to cover new/reset behaviors.
- [ ] For workloads that are **reset-sensitive** rather than reset-tolerant:
  - [ ] The impact of a reset is documented (player-facing effects, operational steps).
  - [ ] The design justifies why Coordination Redis is still appropriate and why a more durable store (for example PostgreSQL or Kafka) is not required.
- [ ] Workloads classified as **reset-forbidden** are **not** placed on Coordination Redis:
  - [ ] Either they use a different store as primary, with Redis in a purely cache/index role, or
  - [ ] They introduce their own explicit deployment and runbooks outside the general Coordination Redis reset tooling.

### Observability and SLOs

- [ ] Tail‑loss SLOs remain meaningful under the new profile/topology.
- [ ] Metrics and alerts:
  - [ ] Reflect any changes in restart times, AOF growth, or memory usage.
  - [ ] Surface tail‑loss violations and coordination health for the affected flows.

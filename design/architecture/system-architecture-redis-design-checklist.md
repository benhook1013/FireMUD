# FireMUD Redis Design Checklist

This document consolidates the **required checks** before changing any Redis‑related behavior in FireMUD. It complements the conceptual hub (`system-architecture-redis.md`) and the detailed design docs for Lua, cache, and operations.

Use this checklist during design and review whenever you:

- Introduce or change a **coordination prefix**.
- Introduce or change a **cache or rate‑limit prefix**.
- Add or modify a **Lua script**.
- Change **Redis profiles, topology, or reset behavior**.

---

## Table of Contents

- [Coordination Prefix Checklist](#coordination-prefix-checklist)
- [Cache / Rate-Limit Prefix Checklist](#cache--rate-limit-prefix-checklist)
- [Lua Script Checklist](#lua-script-checklist)
- [Profile / Topology / Reset Checklist](#profile--topology--reset-checklist)

---

## Coordination Prefix Checklist

Use this when adding or changing coordination prefixes (for example `tick:*`, `timer:*`, `retry:*`, `session:*`, `tick-executor-lease:*`).

**Role and scope**

- [ ] Confirm the prefix lives on **Coordination Redis** only.
- [ ] Identify the owning service(s) and document them in the service README and Redis cheat sheet.
- [ ] Define the scope of the keys:
  - [ ] `tenantId` dimension.
  - [ ] `regionId` or equivalent region concept where applicable.

**Key naming and shard discipline**

- [ ] Key format uses `tenantId` (and `regionId` if applicable) as stable IDs, not user‑provided strings.
- [ ] Region‑scoped keys use `{tenantRegionTag}` (or an equivalent canonical hash tag) so all keys for a region land in a single cluster slot.
- [ ] Multi‑key scripts operating on this prefix:
  - [ ] Only touch keys that share the same hash tag and slot.
  - [ ] Do not mix coordination and cache prefixes in one invocation.

**Tail-loss and reset behavior**

- [ ] The design explicitly states:
  - [ ] Whether the prefix is **reset‑tolerant**, **reset‑sensitive**, or **reset‑forbidden**.
  - [ ] How losing up to **1–2 seconds** of entries per region affects gameplay.
  - [ ] Whether tail‑loss is acceptable for all flows that depend on this prefix.
- [ ] Flows that are **not** tail‑loss compatible (for example, real‑money or cross‑tenant transfers) use durable domain mechanisms and do not rely solely on Redis.
- [ ] The appropriate reset scope (region/tenant/cluster) is documented in the service design and referenced from **Redis Reset & Recovery**.

**Observability**

- [ ] Metrics exist or are planned to track:
  - [ ] Key counts or approximate size for this prefix by tenant/region.
  - [ ] Error or outcome codes from relevant Lua scripts.
  - [ ] Any important watermarks (for example, tick IDs, backlog depths).
- [ ] Dashboards and alerts consider this prefix when assessing tail‑loss SLOs.

---

## Cache / Rate-Limit Prefix Checklist

Use this when adding or changing cache or rate‑limit prefixes (for example `inventory:*`, `view:room-look:*`, `world-dynamic:*`, `ratelimit:*`, `automation:queue:*`).

**Role and behavior**

- [ ] Confirm the prefix lives on **Cache/Rate‑Limit Redis** only.
- [ ] Clearly state whether it is:
  - [ ] A **versioned** cache (strong validation via version/`lastModified`), or
  - [ ] A **TTL‑only** best‑effort cache that tolerates occasional stale reads.
- [ ] The cache’s correctness expectations (staleness tolerance, acceptable data loss) are documented in the relevant service design.

**Eviction and TTL**

- [ ] Set explicit TTLs that match the data’s volatility and usage patterns.
- [ ] Confirm eviction is acceptable:
  - [ ] Cold caches should degrade into acceptable behavior (extra DB reads, recomputation).
  - [ ] Rate‑limit keys may be dropped without violating security or fairness guarantees.
- [ ] For versioned caches:
  - [ ] The authoritative store exposes a version or `lastModified` field.
  - [ ] Cache invalidation logic clearly ties updates to version changes.

**Key shape and multi-tenant behavior**

- [ ] Keys include `tenantId` and any other isolation dimensions required (for example, per‑player or per‑guild).
- [ ] For rate‑limit prefixes:
  - [ ] Bucketing strategy is documented (per‑client vs hashed buckets).
  - [ ] Memory and key count growth per tenant/time window stays within budget.

**Redis role separation**

- [ ] No coordination logic depends on these keys being present or accurate.
- [ ] Cache and rate‑limit keys are **never** written to Coordination Redis.

---

## Lua Script Checklist

Use this when adding or changing Lua scripts that operate on coordination or cache prefixes.

**Determinism**

- [ ] Script behavior is a pure function of:
  - [ ] `KEYS[...]` and `ARGV[...]` arguments.
  - [ ] Current Redis state for the keys it reads.
- [ ] Script does **not** use:
  - [ ] `math.random` or other RNG to influence behavior.
  - [ ] `TIME` or other clock‑dependent primitives in control flow or key contents.
  - [ ] Global mutable state or side effects outside Redis.

**Key handling and hash tags**

- [ ] All keys are passed via `KEYS[...]`; no hard‑coded key concatenation in Lua.
- [ ] Multi‑key scripts only operate on keys that share a hash tag and cluster slot.
- [ ] Script category (tick lock, timer queue, session CAS, automation, etc.) is documented in the Lua Script Registry.

**Idempotency and replay safety**

- [ ] Re‑running the script with the same `KEYS`/`ARGV` in the same state does not apply additional logical effects.
- [ ] Script uses set‑style semantics, version checks, or membership checks to avoid duplicate entries on replay.
- [ ] Error outcomes (`STALE_LEASE`, `STALE_LOCK`, `UNSUPPORTED_SCHEMA_VERSION`, etc.) are explicit and non‑mutating.

**Schema versioning**

- [ ] Structured payloads include an explicit `schemaVersion`.
- [ ] Script:
  - [ ] Treats missing `schemaVersion` as a defined default.
  - [ ] Supports at least the current and previous schema versions during rollout.
  - [ ] Returns an explicit non‑mutating outcome for unknown versions.

**Testing and registry**

- [ ] Script is registered in the Lua Script Registry with:
  - [ ] Name and file path.
  - [ ] Expected `KEYS`/`ARGV` ordering and allowed prefixes.
  - [ ] Category and reset‑tolerance assumptions.
- [ ] Tests cover:
  - [ ] Initial run from a clean state.
  - [ ] Pure replay with identical `KEYS`/`ARGV`.
  - [ ] Replay after partial success (keys pre‑populated).

---

## Profile / Topology / Reset Checklist

Use this when changing Redis profiles, topologies, or reset behavior.

**Profile and environment mapping**

- [ ] Target profile (`dev_local`, `hobby_self_hosted`, `production_clustered`, or documented variant) is clearly defined.
- [ ] For each environment (local, CI, staging, prod):
  - [ ] Document which profile it approximates.
  - [ ] Record AOF, `maxmemory`, and clustering settings for each role.

**Topology compatibility**

- [ ] Verify that:
  - [ ] Coordination scripts and key patterns remain valid on the chosen topology (single‑node vs cluster).
  - [ ] `{tenantRegionTag}` and shard‑local rules are enforced for coordination in cluster mode.
- [ ] Ensure that:
  - [ ] Coordination and Cache/Rate‑Limit Redis remain separate deployments, even when co‑located on a host.
  - [ ] Configuration helpers and dashboards detect when roles accidentally point to the same endpoint.

**Reset model**

- [ ] Design changes explicitly state:
  - [ ] Which reset scopes are safe (region, tenant, cluster).
  - [ ] Whether repair, reset, or accept‑loss is the expected response to problems.
- [ ] Runbooks in `system-architecture-redis-operations.md` are updated or confirmed to cover new/reset behaviors.

**Observability and SLOs**

- [ ] Tail‑loss SLOs remain meaningful under the new profile/topology.
- [ ] Metrics and alerts:
  - [ ] Reflect any changes in restart times, AOF growth, or memory usage.
  - [ ] Surface tail‑loss violations and coordination health for the affected flows.


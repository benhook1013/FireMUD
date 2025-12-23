# TODO: Redis Design Follow-ups (Temp Tracking)

Purpose: track Redis-design consistency fixes so they don’t get lost between context summaries. This is design/doc/config alignment work (not implementation status).

Ground rules (from Ben):
- Coordination Redis and Cache/Rate-Limit Redis are always separate deployments; any mention of “same Redis is OK” is outdated and must be removed/rewritten.
- In examples, curly braces `{...}` are reserved for Redis Cluster hash tags only; placeholders elsewhere should use angle brackets `<...>`.
- `tenantId` should mean “a single game instance”. Any other meaning is incorrect and should be fixed.
- Keep “Implementation status” sections at the top of relevant service design docs while the work is pending (to avoid repeated review churn).

---

## 1) Redis Role Separation: Fix Outdated Docs + Local Defaults

Issue: multiple docs/configs still imply or default to one Redis instance for both roles, which contradicts the “always separate” rule.

### Update environment samples to enforce separation
- [x] Update `.env.sample` to use `FIREMUD_REDIS_COORD_*` and `FIREMUD_REDIS_CACHE_*` only.
- [x] Update `DEVELOPER_SETUP.md` `.env` snippet and Redis debugging guidance for two roles.
- [x] Update `k8s/base/README.md` env var example (`redis-coord` / `redis-cache`).
- [ ] Audit remaining docs for stale single-Redis examples (if any).

### Update Docker Compose to actually run two Redis services by default
- [x] Replace single `redis` service with `redis-coord` + `redis-cache` in `docker/docker-compose.yml`.
- [x] Publish Cache Redis on `localhost:6380` (Coordination remains `localhost:6379`).
- [x] Update `docker/docker-compose.override.yml` (RedisInsight) dependencies for both roles.

### Update “two Redis deployments” claims to match actual repo defaults
- [x] Ensure local Docker Compose matches the “two Redis roles” architecture.

---

## 2) Helm / Kubernetes: Separate Roles, Stop Unsafe AOF Reset Hook

Issue: current Helm template wipes AOF on every install/upgrade and is not role-aware.

### Make Redis role split explicit in Helm values and templates
- [x] Add `redisCoord` / `redisCache` blocks in chart values where applicable.
- [ ] Ensure Kubernetes Redis deployments are explicitly split by role (templates live outside this TODO unless already present).

### Fix / gate the AOF wipe job
- [x] Gate the AOF wipe job behind an explicit opt-in value (dev/ephemeral only).
- [x] Scope the job to Coordination Redis only (naming/labels/claim).
- [x] Wipe the data directory (not a single `appendonly.aof`) to handle Redis 7 multi-part AOF layouts.

### Align Backup/Recovery docs with actual Helm behavior
- [x] Update chart defaults so the reset job is opt-in (not always-on).
- [x] Update `design/architecture/system-architecture-backup-recovery.md` wording to match the “optional + ephemeral-only” posture.

---

## 3) Cold Start vs Failover: Clarify What “Repopulated From Postgres” Means

Ben question: “I thought `remote:` keys are protected by Postgres; do we not have/want this for coordination keys?”

Clarification to incorporate into docs:
- `remote:<tenantId>:<entityId>` is already explicitly best-effort and backed by durable Postgres follow-ups (latency hint only).
- Most coordination keys (`tick:*`, `timer:*`, `retry:*`, leases, locks, per-entity queues) are *runtime coordination machinery*, not durable business state:
  - Many represent ephemeral ownership/ordering (locks/leases), or scheduling state (timers/retry queues) that is derivable only if you also persist the full scheduling intent.
  - Mirroring “all coordination state” into Postgres would:
    - Increase write amplification and hot-path latency (every tick/lock/timer becomes a DB write).
    - Recreate a distributed transaction problem between Redis and Postgres.
    - Undermine the goal of Redis being the fast coordination layer.
- The design already relies on Postgres for the parts that must be durable for correctness:
  - Tick effect ledger / idempotency guards (Postgres) establish “what effects must apply” and prevent double-apply.
  - On failover, Redis `pending` + the ledger enable replay/abandon decisions within an accepted tail-loss envelope.
- For a true cold start where Coordination Redis is empty (volume lost / intentional reset):
  - Treat it as an explicit “coordination reset” event.
  - Rebuild coordination state from Postgres + new activity; accept that some purely-in-Redis scheduling state (timers/retries) may be dropped unless the intent is represented durably elsewhere.

Action items:
- [x] Remove/replace vague phrases like “Redis repopulates transient state from PostgreSQL on access” unless the scope is explicit.
- [ ] Add a short “Failover vs Cold Start vs Reset” subsection (single source of truth) in:
  - `design/architecture/system-architecture-redis.md`
  - `design/architecture/system-architecture-backup-recovery.md`
  - `design/architecture/system-architecture-runbooks.md`

---

## 4) Key Naming & Placeholder Consistency

### Enforce placeholder rules
- Use `{...}` only for Redis Cluster hash tags (literal braces in key names).
- Use `<...>` for example placeholders everywhere else (tenantId, regionId, entityId, etc.).

Concrete fixes:
- [x] Fix `automation_queue:{tenantId}:*` example to `automation_queue:<tenantId>:*`.
- Audit Redis examples across:
  - `design/architecture/system-architecture-redis.md`
  - `design/architecture/system-architecture-ticks.md`
  - `design/architecture/microservices/*/README.md`
  - `DEVELOPER_SETUP.md`
  - `design/architecture/system-architecture-gateway.md` (rate-limit key examples)

### Reduce prefix ambiguity / collisions
- Session prefix collision risk: `session:<tenantId>:<sessionId>` and `session:<tenantId>:<tokenHash>` share the same prefix family (`design/architecture/system-architecture-redis.md:740`).
  - Decide whether to split into `session:game:<tenantId>:<sessionId>` vs `session:auth:<tenantId>:<tokenHash>` (or similar) and update docs accordingly.
- Cache catalog mixes styles (`characterCache`, `worldDynamic`, `room:`) (`design/architecture/system-architecture-redis.md:754`).
  - Define a canonical naming convention (e.g. lowercase with `:` separators; no camelCase prefixes) and apply consistently.

---

## 5) Tenant Definition: Enforce “tenant == game instance”

Issue: docs currently allow tenant to mean “customer” while also having multiple game instances per tenant (`design/architecture/microservices/game-session-service/README.md:9` and `design/architecture/microservices/game-session-service/README.md:10`), which contradicts “tenant == game instance”.

Action items:
- [x] Update the Game Session Service terminology to enforce “tenant == game instance”.
- [x] Fix obvious “tenant launches a new game world” phrasing in world creation docs.
- [ ] Audit remaining docs for “tenant == customer” language and correct it.

---

## 6) Fix Broken Links (Emoji Anchor Fragments)

Issue: links reference emoji anchors that do not match actual Markdown heading IDs.

Examples:
- `design/architecture/system-architecture-ticks.md:300` links to `...redis.md#🔀-...`
- `design/architecture/system-architecture-reconnection.md:57` links to `...redis.md#🧠-...`
- `design/architecture/system-architecture-redis.md:723` links to `...ticks.md#📡-...`

Action items:
- [x] Remove emoji fragments from links and point to stable heading IDs.

---

## 7) Doc Hygiene (Keep Implementation Status Sections)

Per Ben: keep “Implementation status” blocks at top of relevant service design docs while pending.

Action items:
- [x] Fix duplicated bullet in `design/architecture/system-architecture-redis.md:113` (duplicate “Multi-key access” line).
- [x] Fix indentation/formatting errors and duplicated paragraphs in `design/architecture/system-architecture-redis-cache.md`.
- [ ] Add a short “Last reviewed” date to implementation-status blocks while the work is pending.

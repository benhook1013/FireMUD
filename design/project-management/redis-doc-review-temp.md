# Redis Design Docs – Review TODOs (Temp for Chat Session)

This temporary file captures all Redis-related design review issues and suggestions identified during this chat, so they can be implemented and tracked without losing context if summarization or truncation occurs.

## Core Redis Architecture (`design/architecture/system-architecture-redis.md`)

- Add a short, explicit **“Non-Redis-eligible flows”** subsection that lists concrete examples (payments, cross-service sagas, account changes, etc.) that must never rely on Redis coordination, so feature docs can link to that list instead of re-explaining the constraint in each service doc.
- Introduce a **single, normative definition of `{tenantRegionTag}` and hash-tag usage**, with 2–3 fully worked key examples (including exact brace placement for hash tags). All other docs should refer to this section to avoid drift in tag structure.
- Define a **profile matrix for AOF settings** (local dev, hobby/self-hosted, production) that ties specific AOF configuration (appendfsync, AOF/RDB mix, `aof-use-rdb-preamble`, etc.) to the tail-loss envelopes described in the text.
- Explicitly list **supported Redis deployment modes** (for example, standalone + Sentinel, Redis Cluster, managed providers) and call out which modes satisfy the single-writer/split-brain assumptions. Link the split-brain discussion to concrete guidance per deployment mode.
- Add a concise **“Reset vs surgery” decision table** to the coordination reset model that declares which operations require a reset vs which are safe without reset, so operators are not tempted to perform ad-hoc key edits.
- Make **forbidden data shapes in Coordination Redis** explicit (for example, no unbounded lists/sets, no general-purpose logs or large caches) so reviews of new coordination prefixes can apply clear rules.

## Cache & Rate Limiting (`design/architecture/system-architecture-redis-cache.md`)

- Introduce a small taxonomy that distinguishes **“strongly validated caches”** (versioned) from **“best-effort caches”** (TTL-only), and require each cacheable aggregate type to declare which class it uses.
- Clarify how rate-limit key patterns like `ratelimit:{tenantId}:{bucket}:{timeWindow}` and `ratelimit:{tenantId}:{bucket}:{timeWindow}:{shard}` interact with **Redis Cluster and hash tags** (for example, whether keys are intentionally cross-slot or must remain single-slot).
- Require each microservice README’s Redis section to **declare its chosen cache strategy** (which objects are cached, which validation mechanism is used, and which events drive invalidation), even when the initial state is “none”.
- Add **soft/hard budgets for cache prefixes** (per-tenant key counts, maximum list lengths, etc.), similar to the coordination budgets in `system-architecture-redis.md`, so cache growth remains predictable.

## Lua Patterns (`design/architecture/system-architecture-redis-lua-patterns.md`)

- Add a short, copy-pastable **“Checklist for new scripts”** (8–10 bullets) that reviewers can use directly in code reviews instead of mentally aggregating requirements from multiple sections.
- Provide or clearly link to a **script category registry** that maps “script name → category → required invariants” so authors do not mis-classify scripts and forget necessary validations (lease token, epoch, lock tokens, etc.).
- Define a **minimum test matrix per script** (for example: fresh run, pure replay, partial-success replay) so all Lua scripts have consistent idempotency and replay coverage expectations.

## Operations & Migrations (`design/architecture/system-architecture-redis-operations.md`)

- Explicitly map AOF and restart budgets to **concrete metrics and dashboards** (metric names, dashboard sections) so operators can verify that observability actually enforces the documented budgets.
- Add a mandatory **pre-reset validation checklist** (for example, tick effect ledger checks, saga state review, in-flight coordination verification) to the “Key Shape Mistakes and Coordination Resets” section so resets don’t create domain inconsistencies.
- Add a brief **“Registry location & ownership”** subsection that states where the Lua compatibility registry lives, who owns it, and require any new coordination script to be registered with an explicit compatibility policy (`compatible` vs `breaking_requires_reset`).

## Ops Access & Tooling (`design/architecture/system-architecture-redis-ops-access.md`)

- Clarify that **ops users in Coordination Redis must not have access to configuration-changing commands** (`CONFIG`, `SLAVEOF`/`REPLICAOF`, `CLUSTER`, etc.) in normal operation, to avoid accidental changes that violate architectural assumptions.
- Add guidance for **multi-role tools** (for example, health checks that touch both Coordination and Cache/Rate-Limit Redis) describing how they should structure configuration and logging so role mix-ups are easy to detect.
- Make the **`coordination_break_glass` audit event schema explicit** (required fields, storage location, retention expectations) so larger deployments can reliably integrate it with external incident tooling.

## Microservice Redis Sections

### Game Session Service (`design/architecture/microservices/game-session-service/README.md`)

- Add a small table of **session and tick prefixes** (e.g., `session:{tenantId}:{sessionId}`, tick-region keys) and explicitly state that the authoritative list of key formats lives in `system-architecture-redis.md`’s key format catalog.

### Automation & Scripting Service (`design/architecture/microservices/automation-scripting-service/README.md`)

- Add an explicit **“Ownership & durability” table** for Redis prefixes (`automation:tick:*`, `automation_queue:*`, etc.) that states for each key:
  - Which Redis role it lives on (Coordination vs Cache/Rate-Limit).
  - Whether it is reset-tolerant, reset-sensitive, or reset-forbidden.

### Social & Groups Service (`design/architecture/microservices/social-groups-service/README.md`)

- Explicitly state that chat cache prefixes (`chat:say:*`, `chat:tell:*`, `chat:guild:*`, `chat:account:*`, etc.) are part of the **Cache/Rate-Limit Redis Key Catalog** and must follow the size/TTL budgets and Redis Change Checklist from the central Redis docs.

### Project Management Task Lists (`design/project-management/*task-list-*.md`)

- Update task lists that mention Redis integration (for example, Game Session, Automation & Scripting, World Management, etc.) so any task that introduces or changes Redis usage includes a mandatory bullet such as **“Register prefixes and run Redis Change Checklist”** with an explicit link to `system-architecture-redis.md#redis-change-checklist`.


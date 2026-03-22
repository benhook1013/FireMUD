# Second-Tier Documentation Refactor Checklist

This checklist covers the next round of high-signal documentation refactors after the service-doc normalization pass. These targets are not the same kind of broad service README cleanup as the first pass; they are follow-up splits for docs that still mix distinct concerns even after the large refactors are complete.

## Scope

- [x] `design/observability/grafana/core-alerts-snippets.md`
- [x] `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- [x] `design/architecture/system-architecture-scripting-control-plane-api.md`
- [x] `design/architecture/system-architecture-redis-cache.md`

## Required Working Rules

- [x] Before refactoring a target, create an untouched backup copy in the same directory using `<original-name>.pre-doc-refactor-backup.md`.
- [x] Do not edit the backup copy during the refactor pass.
- [x] After the split is verified and committed, remove the temporary backup copy from the live docs tree.
- [x] For each target, use at least one subagent comparison pass against the backup and the new file set.
- [x] If the first comparison pass still finds meaningful omissions, fix them and run one more confirmation pass before considering the target complete.
- [x] Run `./gradlew linkCheck lintMarkdown` after each completed target or tightly related batch.

## Refactor Order

1. `core-alerts-snippets.md`
2. `system-architecture-scripting-dsl-reference-and-lifecycle.md`
3. `system-architecture-scripting-control-plane-api.md`
4. `system-architecture-redis-cache.md`

This order starts with the cleanest low-risk split, then handles the two scripting docs together while their boundaries are fresh, and finishes with the cache/reference separation.

## Target 1: Core Alerts Snippets

File:

- [x] `design/observability/grafana/core-alerts-snippets.md`

Why this still deserves refactor:

- [x] The file bundles unrelated alert domains into one long reference surface.
- [x] Redis coordination alerts, tick health alerts, backup alerts, player-experience SLO alerts, and observability-stack alerts do not need one shared owner file.
- [x] This is mostly reference material, so the split risk is low if links stay stable.

Proposed file map:

- [x] Keep `core-alerts-snippets.md` as a short parent index that explains the alert-reference purpose and links to sibling files.
- [x] Add `redis-alerts-snippets.md`.
- [x] Add `tick-alerts-snippets.md`.
- [x] Add `backup-alerts-snippets.md`.
- [x] Add `player-experience-alerts-snippets.md`.
- [x] Add `observability-stack-alerts-snippets.md`.

Move boundaries:

- [x] Move Redis coordination and Redis Lua alert examples into `redis-alerts-snippets.md`.
- [x] Move tick execution and tick ledger/replay alerts into `tick-alerts-snippets.md`.
- [x] Move backup pipeline and pause-budget alerts into `backup-alerts-snippets.md`.
- [x] Move login, command-latency, chat-delivery, and entry-path availability alerts into `player-experience-alerts-snippets.md`.
- [x] Move Alertmanager, Prometheus, Jaeger, Elasticsearch/Kibana, and Grafana health alerts into `observability-stack-alerts-snippets.md`.

Verification:

- [x] Subagent comparison pass confirms that no alert families or runbook annotations were dropped.
- [x] Confirm the parent file still gives an obvious entry point to the sibling alert docs.

## Target 2: Scripting DSL Reference And Lifecycle

File:

- [x] `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`

Why this still deserves refactor:

- [x] It still mixes DSL semantics with runtime execution behavior.
- [x] DSL/lifecycle ownership is different from execution, eventing, and runtime integration details.
- [x] The current shape makes it harder to identify the canonical home for determinism and runtime behavior rules.

Proposed file map:

- [x] Keep `system-architecture-scripting-dsl-reference-and-lifecycle.md` focused on DSL model, script lifecycle states, determinism rules, and author-facing semantics.
- [x] Add `system-architecture-scripting-runtime-execution.md`.

Move boundaries:

- [x] Keep DSL syntax/shape, lifecycle stages, publication semantics, and determinism guarantees in the parent doc.
- [x] Move runtime execution flow, event/outbox behavior, Redis/runtime integration, and execution-state ownership into `system-architecture-scripting-runtime-execution.md`.
- [x] Move any rollback or executor-behavior detail that is fundamentally about runtime behavior rather than DSL meaning into the runtime-execution sibling.

Verification:

- [x] Subagent comparison pass confirms that DSL invariants and execution invariants are both still fully represented.
- [x] Confirm the parent doc clearly points to the runtime execution sibling as the owner for executor behavior.

## Target 3: Scripting Control Plane API

File:

- [x] `design/architecture/system-architecture-scripting-control-plane-api.md`

Why this still deserves refactor:

- [x] It still blends API surface with control-plane operational workflows.
- [x] Pause, drain, purge, rollback, dead-letter, and operator/audit behaviors do not need to live inline with the core API contract.
- [x] The current shape makes “what is the API?” and “how do operators use it?” harder to distinguish.

Proposed file map:

- [x] Keep `system-architecture-scripting-control-plane-api.md` focused on the control-plane API surface, request/response contracts, canonical errors, and event ingress/admission rules that are part of the API itself.
- [x] Add `system-architecture-scripting-control-plane-operations.md`.

Move boundaries:

- [x] Keep API contract material, core command semantics, and machine-facing error behavior in the parent doc.
- [x] Move rollback, pause/drain/purge workflows, dead-letter/outbox operational handling, and operator/audit workflow material into `system-architecture-scripting-control-plane-operations.md`.
- [x] Leave only enough operational summary in the parent doc to direct readers to the sibling file.

Verification:

- [x] Subagent comparison pass confirms the parent still reads as the canonical API contract after operational material moves out.
- [x] Confirm no operator workflow details are silently dropped during the split.

## Target 4: Redis Cache

File:

- [x] `design/architecture/system-architecture-redis-cache.md`

Why this still deserves refactor:

- [x] It still combines canonical cache policy with reference-heavy material.
- [x] Key catalogs, worked examples, metrics, adoption notes, and future-work/testing content make the policy harder to scan.
- [x] This is a classic “policy plus appendix” candidate.

Proposed file map:

- [x] Keep `system-architecture-redis-cache.md` focused on cache ownership, invalidation rules, consistency expectations, and canonical cache policy.
- [x] Add `system-architecture-redis-cache-reference.md`.

Optional if the split still feels crowded:

- [ ] Split metrics/reference material again into `system-architecture-redis-cache-metrics.md`.

Move boundaries:

- [x] Keep cache model, invalidation rules, and correctness boundaries in the parent doc.
- [x] Move key catalogs, worked examples, reference tables, adoption checklist material, and future-work/testing/reference-heavy content into `system-architecture-redis-cache-reference.md`.
- [x] Only create a dedicated metrics sibling if the reference doc still ends up carrying too many unrelated sections.

Verification:

- [x] Subagent comparison pass confirms the cache policy remains complete and the moved reference material still contains all concrete examples and catalogs worth preserving.
- [x] Confirm links from Redis core docs still point at the correct canonical owner after the split.

## Completion Criteria

- [x] Each target has an untouched temporary backup created before edits.
- [x] Each target has at least one comparison pass and, when needed, one confirmation pass after fixes.
- [x] Temporary backup files are removed after the verified refactor is complete.
- [x] `./gradlew linkCheck lintMarkdown` passes after the final batch.
- [x] The remaining docs tree no longer has obvious mixed-concern second-tier targets from this shortlist.

# AI Task Prompt Library

This directory is the index for reusable FireMUD AI review prompts.

The prompt files under `architecture-review-prompts/` are grouped by theme so related reviews sort together.

## Audit Suite Review Status

Status: deferred and not started. The prompts are individually reusable, but the library has not yet been coverage-verified as one deliberate whole-application audit suite.

The future review must be run with the human to decide which suites cover architecture convergence, design-to-implementation alignment, code correctness, operational readiness, security, and product/user journeys. It must also decide whether exhaustive execution uses domain, service, invariant, or staged lanes; which prompts are periodic rather than one-time convergence tools; and what evidence, rereview, synthesis, and completion gates make a whole-app pass trustworthy.

Review work:

1. Inventory all 27 prompts and the orchestration plan by purpose, source documents, systems covered, and expected findings.
2. Map coverage across every service, frontend, shared module, protocol, persistence boundary, infrastructure surface, operator workflow, user journey, and cross-cutting invariant.
3. Find omissions, duplicate or contradictory scopes, stale links and terminology, and prompts that are too broad or narrow to execute reliably. Known cleanup candidates include the legacy `40-design-to-slice-translation-gap-review.md` filename and remaining `service-status` wording.
4. Verify that target-state design review and implementation/proof review remain distinct review modes.
5. Define the whole-app execution order, per-prompt coverage evidence, cross-lane reconciliation, rereview behavior, and incomplete-review gate.
6. Define how opportunistic discovery, exhaustive bounded review, independent verification, and main-thread synthesis divide across available agent roles without delegating human design decisions.
7. Run an independent coverage review before treating the revised library as the standard whole-app audit suite.

Completion requires every meaningful application and platform domain, service, and major cross-service boundary to have an explicit review home without unexplained duplication; current canonical sources throughout; distinct architecture, implementation, proof, operations, security, and product outcomes; and an orchestration plan that can produce one reconciled whole-app result.

## Review Setup

- [00-fresh-reread-issue-only-preamble.md](./architecture-review-prompts/00-fresh-reread-issue-only-preamble.md) — issue-only review preamble
- [01-domain-review-orchestration-plan.md](./architecture-review-prompts/01-domain-review-orchestration-plan.md) — multi-part review orchestration

## Core Architecture Domains

- [10-main-architecture-overview.md](./architecture-review-prompts/10-main-architecture-overview.md) — top-level architecture and doc contradictions
- [11-game-loop-and-tick-core.md](./architecture-review-prompts/11-game-loop-and-tick-core.md) — gameplay loop and tick model
- [12-redis-runtime-and-data-contracts.md](./architecture-review-prompts/12-redis-runtime-and-data-contracts.md) — Redis runtime/data contracts
- [13-redis-operations-and-recovery.md](./architecture-review-prompts/13-redis-operations-and-recovery.md) — Redis ops and recovery
- [14-networking-protocols-and-reconnection.md](./architecture-review-prompts/14-networking-protocols-and-reconnection.md) — networking and reconnect behavior
- [15-auth-sessions-and-multi-tenancy.md](./architecture-review-prompts/15-auth-sessions-and-multi-tenancy.md) — auth, sessions, and tenant boundaries
- [16-persistence-assets-and-migrations.md](./architecture-review-prompts/16-persistence-assets-and-migrations.md) — persistence, assets, and migrations
- [17-scripting-dsl-and-runtime.md](./architecture-review-prompts/17-scripting-dsl-and-runtime.md) — scripting runtime and DSL
- [18-designer-tooling-and-modding.md](./architecture-review-prompts/18-designer-tooling-and-modding.md) — tooling and modding surfaces
- [19-world-and-content-authoring.md](./architecture-review-prompts/19-world-and-content-authoring.md) — world/content authoring

## Platform, Operations, and Security

- [20-observability-contracts.md](./architecture-review-prompts/20-observability-contracts.md) — logs, metrics, traces, and observability contracts
- [21-operations-runbooks-and-recovery.md](./architecture-review-prompts/21-operations-runbooks-and-recovery.md) — operational runbooks and recovery
- [22-environments-and-secrets.md](./architecture-review-prompts/22-environments-and-secrets.md) — environment and secret handling
- [23-deployment-cicd-and-platform-security.md](./architecture-review-prompts/23-deployment-cicd-and-platform-security.md) — deployment, CI/CD, and platform security
- [24-saas-platform-and-product-coherence.md](./architecture-review-prompts/24-saas-platform-and-product-coherence.md) — product/platform coherence
- [25-monetization-and-account-lifecycle.md](./architecture-review-prompts/25-monetization-and-account-lifecycle.md) — monetization and account lifecycle
- [26-user-journeys-and-ux.md](./architecture-review-prompts/26-user-journeys-and-ux.md) — user-facing journey and UX review

## Audit Prompts

- [30-per-service-deep-dive-template.md](./architecture-review-prompts/30-per-service-deep-dive-template.md) — reusable service deep-dive template
- [31-system-cohesion-and-canonical-substrates.md](./architecture-review-prompts/31-system-cohesion-and-canonical-substrates.md) — substrate and system-cohesion review
- [32-cross-service-contract-consistency-review.md](./architecture-review-prompts/32-cross-service-contract-consistency-review.md) — cross-service contract consistency
- [33-race-replay-idempotency-and-crash-safety.md](./architecture-review-prompts/33-race-replay-idempotency-and-crash-safety.md) — race, replay, and crash-safety review
- [34-implemented-code-hygiene-and-durable-patterns.md](./architecture-review-prompts/34-implemented-code-hygiene-and-durable-patterns.md) — implementation-pattern hygiene
- [35-pre-v1-tech-debt-and-simplification-review.md](./architecture-review-prompts/35-pre-v1-tech-debt-and-simplification-review.md) — pre-v1 simplification, AI-heavy code drift, and unneeded compatibility debt
- [40-design-to-slice-translation-gap-review.md](./architecture-review-prompts/40-design-to-slice-translation-gap-review.md) — design-to-domain implementation-tracking gaps
- [41-test-infrastructure-harness-and-proof-convergence-review.md](./architecture-review-prompts/41-test-infrastructure-harness-and-proof-convergence-review.md) — test/proof infrastructure review

## Notes

- The prompt library now uses grouped thematic numbering:
  - `00–01` review setup
  - `10–19` core architecture domains
  - `20–29` platform, operations, and product/security
  - `30–49` audit prompts

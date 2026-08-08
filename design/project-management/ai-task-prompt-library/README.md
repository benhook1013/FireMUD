# AI Task Prompt Library

This directory contains reusable FireMUD review prompts for work that is broader than ordinary pull-request review.

The library is intentionally not a post-change checklist. Pull-request review owns change-level design, implementation, and proof review. Do not add a routine step that asks which library prompt should run after each change.

Use these prompts for:

- the deliberate whole-system review after the main target-state design is complete;
- a manually commissioned review of one service or one broad system concern;
- release or traffic-opening evidence checks; and
- occasional engineering-maintenance reviews that look for patterns accumulated across many changes.

## Using The Prompts

Every system or engineering review uses the [shared review contract](./system-review-prompts/00-shared-review-contract.md). The caller supplies the review scope and any permissions that differ from its read-only default.

The [orchestration guide](./system-review-prompts/01-review-orchestration.md) coordinates the one-time whole-system pass and explains how to combine findings. It does not create a recurring review process.

Review working notes and coverage tables are ephemeral unless the human explicitly requests a retained artifact. Accepted outcomes go to their existing owner:

- product outcomes go to `design/product/`;
- technical contracts go to `design/architecture/`;
- implementation and proof status go to the owning implementation tracker;
- consequential decisions use the established decision workflow; and
- release or operational evidence goes to the existing operations evidence surface.

Do not create a permanent second backlog, finding database, invariant registry, or per-service status collection for this library.

## Whole-System Review

These prompts form the deliberate post-design review suite.

### Shared Rules And Coordination

- [Shared review contract](./system-review-prompts/00-shared-review-contract.md) – Common authority, permission, evidence, output, and completion rules.
- [Review orchestration](./system-review-prompts/01-review-orchestration.md) – Execution and synthesis of a coordinated whole-system review.

### Post-Design Foundations

- [Authority and decision closure](./system-review-prompts/10-authority-and-decision-closure.md) – Checks that target contracts have one clear owner and unresolved decisions remain visible.
- [Capability, journey, status, and evidence census](./system-review-prompts/11-capability-journey-status-and-evidence-census.md) – Checks every product capability and persona journey against design, implementation tracking, and proof status.
- [Cross-service invariant and workflow closure](./system-review-prompts/12-cross-service-invariant-and-workflow-closure.md) – Follows important workflows and failure rules across service boundaries.

### Focused System Reviews

- [Service boundary review](./system-review-prompts/20-service-boundary-review.md) – Reusable review for one selected deployed service.
- [Identity, tenancy, account lifecycle, and data rights](./system-review-prompts/21-identity-tenancy-account-lifecycle-and-data-rights.md) – Accounts, admission, sessions, entitlements, commerce, and account data.
- [Runtime, persistence, concurrency, and recovery](./system-review-prompts/22-runtime-persistence-concurrency-and-recovery.md) – Gameplay mutation, Redis and SQL ownership, replay, crashes, and durable recovery.
- [Authoring, settings, activation, and automation](./system-review-prompts/23-authoring-settings-activation-and-automation.md) – Creator workflows from authoring through publishing, activation, scripting, and rollback.
- [Edge, protocol, reconnection, and client parity](./system-review-prompts/24-edge-protocol-reconnection-and-client-parity.md) – Browser, WebSocket, Telnet, Gateway, protocol translation, and reconnect behavior.
- [Security, trust, privacy, and abuse](./system-review-prompts/25-security-trust-privacy-and-abuse.md) – Security-contract review that keeps the deferred whole-platform threat model explicit.
- [Operations, delivery, recovery, and observability](./system-review-prompts/26-operations-delivery-recovery-and-observability.md) – Deployment, environments, monitoring, runbooks, backups, and restore design.
- [Persona journeys, UX, and accessibility](./system-review-prompts/27-persona-journeys-ux-and-accessibility.md) – Player, creator, and operator experience across implemented and target surfaces.

### Release And Environment Gates

- [Release readiness gate](./system-review-prompts/30-release-readiness-gate.md) – Assesses a declared release scope without claiming live-environment proof.
- [Traffic-open and restore gate](./system-review-prompts/31-traffic-open-and-restore-gate.md) – Assesses event-bound deployment, traffic-opening, and restore evidence.

## Engineering-Maintenance Reviews

These prompts are useful repository-wide maintenance jobs, but they do not contribute to a claim that the target-state design has been completely reviewed.

- [Shared code, tooling, and pattern consolidation](./engineering-review-prompts/10-shared-code-tooling-and-pattern-consolidation.md) – Finds repeated production, test, build, and validation solutions that should share one canonical implementation.
- [Pre-v1 simplification and deletion](./engineering-review-prompts/11-pre-v1-simplification-and-deletion.md) – Finds obsolete paths, compatibility baggage, unnecessary layers, and code that should be removed rather than consolidated.

Run these reviews only when the human commissions them, normally after a meaningful cluster of implementation work or when repository-wide drift becomes visible. They are not per-PR requirements or calendar-driven jobs.

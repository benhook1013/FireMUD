# Documentation Architecture Refactor Checklist

This checklist plans the next documentation-structure refactor across FireMUD architecture and service docs.

The goal is not to force every document under an arbitrary line count. The goal is to split mixed-concern documents into stable canonical parent docs plus focused subdocs, while standardizing service-doc shape across the repo so future growth is less chaotic.

## Current Progress

- [x] Refactor checklist created and adopted as the active guide for this workstream.
- [x] TCP Proxy Service selected as the pilot refactor target.
- [x] TCP Proxy untouched backup copy created: `README.pre-doc-refactor-backup.md`.
- [x] First TCP Proxy split created (`README.md`, `protocols.md`, `api-contracts.md`, `runtime-and-data.md`, `operations.md`, `configuration.md`).
- [x] First backup-vs-refactor subagent comparison pass completed and produced useful omission findings.
- [x] First TCP Proxy omission-repair pass complete and rechecked.
- [x] Second backup-vs-refactor subagent comparison pass completed and still produced useful omission findings.
- [x] Second TCP Proxy omission-repair pass complete and rechecked.
- [x] Third backup-vs-refactor subagent comparison pass completed and still produced useful omission findings.
- [x] Third TCP Proxy omission-repair pass complete and rechecked.
- [x] Fourth backup-vs-refactor subagent comparison pass completed and still produced useful omission findings.
- [x] Fourth TCP Proxy omission-repair pass complete and rechecked.
- [x] Fifth backup-vs-refactor subagent comparison pass completed and still produced useful omission findings.
- [x] Fifth TCP Proxy omission-repair pass complete and rechecked.
- [x] Sixth backup-vs-refactor subagent comparison pass completed and still produced useful omission findings.
- [x] Sixth TCP Proxy omission-repair pass complete and rechecked.
- [x] Seventh backup-vs-refactor subagent comparison pass completed.
- [x] TCP Proxy pilot accepted as converged enough to move on, even though a few low-value follow-ups could still be found with more passes.
- [ ] Optional later cleanup-only pass for waived low-level TCP Proxy follow-ups if they become worth the time.
- [x] Next active refactor target: Game Session Service.
- [x] Game Session untouched backup copy created: `README.pre-doc-refactor-backup.md`.
- [x] First Game Session split created (`README.md`, `api-contracts.md`, `runtime-and-data.md`, `operations.md`, `configuration.md`, `protocols.md`).
- [x] First Game Session backup-vs-refactor subagent comparison pass completed and produced useful omission findings.
- [x] First Game Session omission-repair pass complete and rechecked locally.
- [x] Second Game Session backup-vs-refactor subagent comparison pass completed and reduced to low-level but valid omissions.
- [x] Second Game Session omission-repair pass complete and rechecked locally.
- [x] Third Game Session backup-vs-refactor subagent comparison pass completed and found a final small set of useful omissions.
- [x] Third Game Session omission-repair pass complete and rechecked locally.
- [x] Fourth Game Session backup-vs-refactor subagent comparison pass completed.
- [x] Fourth Game Session verification pass was effectively clean; one reviewer reported no meaningful omissions and the other only repeated an already-restored plaintext-Telnet warning concern.
- [x] Game Session refactor accepted as converged enough to move on.

## Non-Negotiable Safety Rules

- [ ] Before refactoring any target document, create an untouched backup copy of the original file in the same directory or a clearly named backup subdirectory.
- [ ] Backup copies must preserve the exact pre-refactor content and must not be edited during the refactor pass.
- [ ] Refactor work should proceed from the copied source into new file sets; do not destructively rewrite the only copy and hope details survive.
- [ ] After each refactor pass, run a dedicated subagent comparison pass against the backup copy and the new split docs.
- [ ] The subagent pass must explicitly look for details, examples, constraints, and edge cases present in the backup but missing from the new file set.
- [ ] Any dropped-but-still-valid details found by the comparison pass must be restored before considering the refactor complete.
- [ ] After backup-diff review is clean, either retain the backup under an agreed archival convention or remove it in a separate cleanup pass once the human review confirms the refactor is complete.

## Standard Target Shape

Use this as the default service-doc shape unless a service genuinely does not need one of the subdocs yet.

- [ ] `README.md`
  - Service purpose, responsibilities, boundaries, dependencies, and links to subdocs.
- [ ] `api-contracts.md`
  - gRPC/HTTP/control APIs, canonical request/response semantics, canonical errors, and protocol ownership boundaries.
- [ ] `runtime-and-data.md`
  - Persistence, Redis usage, queues, lifecycle/state model, invariants, and ownership of durable vs transient state.
- [ ] `operations.md`
  - Readiness/liveness, failure modes, operator workflows, metrics, alerts, recovery hooks, and links to shared runbooks.
- [ ] `configuration.md`
  - Environment variables, secrets, deployment-specific bindings, and config invariants.
- [ ] `protocols.md` or `client-behavior.md` when needed
  - Wire-level protocols, text commands, session-envelope behavior, or client interaction details.
- [ ] `appendix-*.md` only when needed
  - Worked examples, payload catalogs, generated examples, or other supporting reference material that should not bloat the parent docs.

Rules for using the template:

- [ ] Keep one canonical parent doc per major concept; do not split invariants across many peers without a clear owner.
- [ ] Move catalogs, worked examples, cookbooks, and protocol appendices out of the parent doc first.
- [ ] Small services do not need every subdoc immediately, but every service directory should move toward the same shape so future growth has an obvious home.

## Phase 1: Define Conventions Before Moving Content

- [ ] Add a short service-documentation structure guide under `design/project-management` or `design/architecture/microservices/README.md`.
- [ ] Define naming conventions for backup copies, for example `README.pre-doc-refactor-backup.md` or `backup/README.2026-03-22.pre-refactor.md`.
- [ ] Define a required refactor checklist item for every moved section:
  - section moved
  - destination file
  - canonical owner after move
  - whether links/anchors need redirects or replacement links
- [ ] Define the standard post-refactor verification loop:
  - backup copy created
  - content moved
  - links updated
  - subagent backup-vs-new comparison
  - `./gradlew linkCheck lintMarkdown`
  - human review

## Phase 2: High-Priority Refactor Targets

These are the currently oversized or mixed-concern docs that should be tackled first.

### 1. TCP Proxy Service

Files:

- [x] Create untouched backup copy for [`design/architecture/microservices/tcp-proxy-service/README.md`](../architecture/microservices/tcp-proxy-service/README.md)
- [x] First split pass completed for [`design/architecture/microservices/tcp-proxy-service/README.md`](../architecture/microservices/tcp-proxy-service/README.md)
- [x] First backup-vs-refactor subagent review completed
- [x] Restore omissions found by the first review pass
- [x] Run second backup-vs-refactor subagent review
- [x] Second review still found useful omissions
- [x] Restore omissions found by the second review pass
- [x] Run third backup-vs-refactor subagent review
- [x] Third review still found useful omissions
- [x] Restore omissions found by the third review pass
- [x] Run fourth backup-vs-refactor subagent review
- [x] Fourth review still found useful omissions
- [x] Restore omissions found by the fourth review pass
- [x] Run fifth backup-vs-refactor subagent review
- [x] Fifth review still found useful omissions
- [x] Restore omissions found by the fifth review pass
- [x] Run sixth backup-vs-refactor subagent review
- [x] Sixth review still found useful omissions
- [x] Restore omissions found by the sixth review pass
- [x] Run seventh backup-vs-refactor subagent review
- [x] Seventh review was effectively clean enough to move on, with only low-value follow-ups remaining
- [x] Proceed to the next service instead of continuing TCP Proxy-only polish

Why:

- [ ] It currently mixes service overview, Telnet protocol contract, event metrics, operational notes, environment variables, proto references, and protocol appendices in one file.

Planned split:

- [ ] Keep `README.md` as service overview, responsibilities, boundaries, dependency map, and links.
- [ ] Create `protocols.md` for Telnet session envelope, MCP/Telnet handling rules, and bridge behavior.
- [ ] Create `api-contracts.md` for gRPC and externally visible service-facing contracts.
- [ ] Create `operations.md` for logging/correlation, readiness/liveness, abuse metrics, and operational notes.
- [ ] Create `configuration.md` for environment variables and TLS/bridge config.
- [ ] Run a subagent pass against the backup copy to verify no Telnet edge-case or metric rule was lost.

### 2. Game Session Service

Files:

- [x] Create untouched backup copy for [`design/architecture/microservices/game-session-service/README.md`](../architecture/microservices/game-session-service/README.md)
- [x] First split pass completed for [`design/architecture/microservices/game-session-service/README.md`](../architecture/microservices/game-session-service/README.md)
- [x] First backup-vs-refactor subagent review completed
- [x] Restore omissions found by the first review pass
- [x] Run second backup-vs-refactor subagent review
- [x] Second review still found low-level but useful omissions
- [x] Run third backup-vs-refactor subagent review
- [x] Third review still found a final small set of useful omissions
- [x] Run fourth backup-vs-refactor subagent review
- [x] Fourth review was effectively clean enough to move on

Why:

- [ ] It mixes service overview, runtime/data ownership, text command protocol, readiness/liveness, Redis usage, and extended appendices.

Planned split:

- [ ] Keep `README.md` as service overview and major responsibilities.
- [ ] Create `api-contracts.md` for gameplay/session/control APIs and command-front-door contract.
- [ ] Create `runtime-and-data.md` for Redis/PostgreSQL ownership, session state, tick coordination, and attestation/runtime rules.
- [ ] Create `operations.md` for readiness/liveness, failure modes, and operator-facing recovery notes.
- [ ] Create `protocols.md` or `client-behavior.md` for text command protocol and connection behavior that should not dominate the service overview.
- [ ] Run a subagent pass against the backup copy to verify no session-front-door, reconnect, or attestation rule was dropped.

### 3. World Management Service

Files:

- [ ] Refactor [`design/architecture/microservices/world-management-service/README.md`](../architecture/microservices/world-management-service/README.md)

Why:

- [ ] It currently behaves like a combined README, deep architecture spec, and procedural-generation API document.

Planned split:

- [ ] Keep `README.md` as ownership, responsibilities, and data/domain boundaries.
- [ ] Create `api-contracts.md` for design-time and runtime APIs.
- [ ] Create `runtime-and-data.md` for world data ownership, template/runtime separation, and read-fence behavior.
- [ ] Create `procedural-generation-control.md` for the procedural generation control API and its lifecycle.
- [ ] Create `operations.md` if operational material remains large enough to justify it.
- [ ] Run a subagent pass against the backup copy to verify no room-read fence, generation, or world/template rule was lost.

### 4. Backup and Recovery

Files:

- [ ] Refactor [`design/architecture/system-architecture-backup-recovery.md`](../architecture/system-architecture-backup-recovery.md)

Why:

- [ ] It mixes backup policy, restore workflow, secret hardening, credential rotation, and backup evidence/compliance reporting.

Planned split:

- [ ] Keep `system-architecture-backup-recovery.md` as canonical backup/restore and recovery model.
- [ ] Create `system-architecture-backup-evidence-and-compliance.md` for readiness evidence, traffic-open evidence, and compliance records.
- [ ] Create `system-architecture-secret-hardening-and-rotation.md` or a similarly named doc for post-restore hardening and rotation workflows.
- [ ] Keep summary links in the parent doc so the main recovery path remains easy to review.
- [ ] Run a subagent pass against the backup copy to verify no restore-evidence or credential-hardening rules were lost.

### 5. Redis Operations

Files:

- [ ] Refactor [`design/architecture/system-architecture-redis-operations.md`](../architecture/system-architecture-redis-operations.md)

Why:

- [ ] It mixes SLOs, metrics catalog, reset classes, compatibility rollout matrix, migration guidance, and incident recovery details.

Planned split:

- [ ] Keep `system-architecture-redis-operations.md` as the canonical operator model and SLO/budget doc.
- [ ] Create `system-architecture-redis-metrics-catalog.md` for the long metrics catalog.
- [ ] Create `system-architecture-redis-script-rollout-and-compatibility.md` for script compatibility and rollout matrix details.
- [ ] Keep reset/recovery concepts linked cleanly to the existing reset/recovery docs instead of duplicating them.
- [ ] Run a subagent pass against the backup copy to verify no metric definition, SLO, or rollout invariant was dropped.

### 6. Authentication and Authorization

Files:

- [ ] Refactor [`design/architecture/system-architecture-authentication.md`](../architecture/system-architecture-authentication.md)

Why:

- [ ] It mixes identity model, login/session flow, JWT format, authorization matrix concepts, trust boundaries, and multi-client/session behavior.

Planned split:

- [ ] Keep `system-architecture-authentication.md` as the canonical authn/authz model and end-to-end flow.
- [ ] Create `system-architecture-jwt-and-token-contracts.md` for JWT structure, validation rules, and token semantics.
- [ ] Create `system-architecture-session-behavior.md` for takeover, multi-client behavior, mid-session role updates, and related lifecycle rules if the parent doc remains too large after the first split.
- [ ] Run a subagent pass against the backup copy to verify no login-flow edge case, token-validation invariant, or session-takeover rule was dropped.

### 7. Scripting Control Plane API

Files:

- [ ] Refactor [`design/architecture/system-architecture-scripting-control-plane-api.md`](../architecture/system-architecture-scripting-control-plane-api.md)

Why:

- [ ] It mixes API contracts, control-plane events, rollout/rollback protocols, and auth/audit semantics.

Planned split:

- [ ] Keep the parent doc focused on control-plane APIs and responsibilities.
- [ ] Create `system-architecture-scripting-control-plane-events.md` for event contracts.
- [ ] Create `system-architecture-scripting-rollout-and-rollback.md` for rollout/rollback protocols.
- [ ] Keep authz/audit material either in the parent doc or in a short focused appendix depending on size after the move.
- [ ] Run a subagent pass against the backup copy to verify no event or rollout contract was dropped.

### 8. Scripting DSL Reference and Lifecycle

Files:

- [ ] Refactor [`design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`](../architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md)

Why:

- [ ] It mixes DSL semantics, lifecycle, event model, scheduler behavior, timers, failure modes, and rollback fencing.

Planned split:

- [ ] Keep the parent doc as the DSL and lifecycle owner.
- [ ] Create `system-architecture-scripting-events.md` if the event-model material remains large after cleanup.
- [ ] Create `system-architecture-scripting-scheduler-and-timers.md` for scheduler leadership, timer lifecycle, and hot reload/resume behavior if needed.
- [ ] Run a subagent pass against the backup copy to verify no determinism, dedup, timer, or rollback-safety rule was dropped.

### 9. Scripting Quotas and Operations

Files:

- [ ] Refactor [`design/architecture/system-architecture-scripting-quotas-and-operations.md`](../architecture/system-architecture-scripting-quotas-and-operations.md)

Why:

- [ ] It mixes steady-state quota model with operator cookbook and rollback/recovery cookbook material.

Planned split:

- [ ] Keep the parent doc focused on quotas, budgets, security, fairness, and metrics ownership.
- [ ] Create `system-architecture-scripting-operations-cookbook.md` for operator disable/throttle and rollback/recovery flows.
- [ ] Run a subagent pass against the backup copy to verify no quota or operational recovery detail was dropped.

## Phase 3: Standardize Service-Doc Shape Across All Service Directories

Even services that do not yet strictly need every subdoc should receive placeholder or light-weight splitouts where that will keep structure consistent and future-safe.

### Account Service

- [ ] Review [`design/architecture/microservices/account-service/README.md`](../architecture/microservices/account-service/README.md) against the standard target shape.
- [ ] Add `api-contracts.md` if API material is still embedded in the README.
- [ ] Add `configuration.md` if env/secret material is embedded in the README.
- [ ] Add `operations.md` if readiness, abuse controls, rotation notes, or operational details are large enough to justify separation.

### Automation & Scripting Service

- [ ] Review [`design/architecture/microservices/automation-scripting-service/README.md`](../architecture/microservices/automation-scripting-service/README.md) against the standard target shape.
- [ ] Add `api-contracts.md`.
- [ ] Add `runtime-and-data.md`.
- [ ] Add `configuration.md`.
- [ ] Add `operations.md` if operational material is already growing.

### Entity Management Service

- [ ] Review [`design/architecture/microservices/entity-management-service/README.md`](../architecture/microservices/entity-management-service/README.md) against the standard target shape.
- [ ] Add `api-contracts.md`.
- [ ] Add `runtime-and-data.md`.
- [ ] Add `configuration.md`.
- [ ] Add `operations.md` if rollback/cutover/readiness content keeps growing.

### Game Design Service

- [ ] Review [`design/architecture/microservices/game-design-service/README.md`](../architecture/microservices/game-design-service/README.md) plus sibling docs against the standard target shape.
- [ ] Keep `README.md` as the service index and owner map.
- [ ] Add or normalize `api-contracts.md` if control-plane and publish APIs are still spread across too many siblings without a clear owner.
- [ ] Add `configuration.md` if configuration material is not already cleanly housed elsewhere.
- [ ] Confirm sibling docs such as `asset-storage.md`, `modding-framework.md`, `version-control.md`, and `world-editing-tools.md` still have clear ownership and are linked from the parent index.

### Game Logic Service

- [ ] Review [`design/architecture/microservices/game-logic-service/README.md`](../architecture/microservices/game-logic-service/README.md) against the standard target shape.
- [ ] Add `api-contracts.md`.
- [ ] Add `runtime-and-data.md` if gameplay-state or effect-handling rules are currently packed into the README.
- [ ] Add `operations.md` if tick/error/replay behavior is large enough to justify separation.
- [ ] Add `configuration.md`.

### Logging & Admin Service

- [ ] Review [`design/architecture/microservices/logging-admin-service/README.md`](../architecture/microservices/logging-admin-service/README.md) against the standard target shape.
- [ ] Add `api-contracts.md`.
- [ ] Add `operations.md`.
- [ ] Add `configuration.md`.

### Social & Groups Service

- [ ] Review [`design/architecture/microservices/social-groups-service/README.md`](../architecture/microservices/social-groups-service/README.md) against the standard target shape.
- [ ] Add `api-contracts.md`.
- [ ] Add `runtime-and-data.md`.
- [ ] Add `configuration.md`.
- [ ] Add `operations.md` if moderation or messaging operations have enough weight to justify separation.

### Spring Cloud Gateway

- [ ] Review [`design/architecture/microservices/spring-cloud-gateway/README.md`](../architecture/microservices/spring-cloud-gateway/README.md) against the standard target shape.
- [ ] Add `api-contracts.md` for route and edge contract ownership.
- [ ] Add `operations.md` for ingress/readiness/rate-limit/operator-facing behavior.
- [ ] Add `configuration.md`.
- [ ] Add `client-behavior.md` only if browser/bootstrap/connect-token behavior is too detailed for the README.

### TCP Proxy, Game Session, World Management

- [ ] Standardize the new split docs created in Phase 2 so these services become the pilot examples for the rest of the repo.

## Phase 4: Cross-Cutting System-Doc Cleanup

- [ ] Review whether the same structure should be applied to long cross-cutting docs:
  - canonical contract doc
  - operations/runbook doc
  - reference/catalog doc
  - appendix/examples doc
- [ ] Apply that pattern to Authentication, Backup/Recovery, Redis Operations, and Scripting docs first.
- [ ] Avoid fragmenting core invariants from their canonical owner doc; move examples, catalogs, and cookbooks first.

## Required Verification Loop Per Refactor

For each individual refactor target:

- [ ] Create untouched backup copy.
- [ ] Draft new file map.
- [ ] Move sections into new files.
- [ ] Rewrite parent doc as an index plus canonical owner where appropriate.
- [ ] Update local links and references.
- [ ] Spawn subagents to compare:
  - backup doc vs refactored file set
  - old anchors/sections vs new destinations
  - canonical constraints/examples/error codes that may have been dropped
- [ ] Restore any missing-but-valid details found by subagents.
- [ ] Run `./gradlew linkCheck lintMarkdown`.
- [ ] Record follow-up cleanup items separately instead of bloating the finished refactor.

## Suggested Execution Order

- [ ] 1. Define the template and backup convention.
- [ ] 2. Pilot the process on TCP Proxy Service.
- [ ] 3. Apply the same shape to Game Session Service and World Management Service.
- [ ] 4. Split the three biggest cross-cutting docs: Backup/Recovery, Redis Operations, Authentication.
- [ ] 5. Tackle the scripting doc cluster.
- [ ] 6. Sweep remaining service directories for standard-shape placeholders and splitouts.
- [ ] 7. Run a final multi-lane subagent verification pass over the refactored doc set to catch dropped details and new drift.

## Done Criteria

- [ ] Each targeted long doc has a clear canonical parent plus focused subdocs.
- [ ] Each service directory has a recognizable standard documentation shape, even if some subdocs are intentionally lightweight.
- [ ] No refactor was performed without an untouched original backup copy.
- [ ] Each refactor received a backup-vs-new subagent comparison pass.
- [ ] `./gradlew linkCheck lintMarkdown` passes after each completed batch.
- [ ] The resulting structure reduces mixed-concern docs instead of simply moving the same sprawl into more files.

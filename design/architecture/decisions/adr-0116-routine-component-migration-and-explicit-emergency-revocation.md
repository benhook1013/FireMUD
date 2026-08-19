# ADR 0116: Routine Component Migration and Explicit Emergency Revocation

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-10`
- Primary capability: `AS-1.2` script sandbox and execution safety
- Affected capabilities: `AR-1.5`, `AS-1.6`, `PO-1.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of component migration, immutable ready and pinned patches, security containment urgency, rollback availability, fail-closed scope, and gameplay continuity
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `SCRIPT-10`

## Context

Component safety changes have two materially different meanings. A component may need routine migration because its contract is deprecated or being replaced. It may instead present an urgent security risk such as sandbox escape, arbitrary execution, or access to cross-tenant or private data.

Treating every routine reclassification as a mutable live rollout would silently change already reviewed and pinned behavior. Treating a critical exploit only as a future-publication concern would leave known vulnerable evaluation active until every affected operator completes a rollback.

## Decision

FireMUD separates routine component migration from emergency component revocation.

The existing `UNSAFE` classification is the routine **migration-required / new-use-blocked** class for core scripts:

- New publication and tenant-readiness transitions that reference the component fail closed with the canonical unsafe-component validation outcome.
- Creator tooling identifies the affected graph and requires migration before a replacement patch can publish or become `READY`.
- Reclassification does not mutate an already-`READY` artifact, move an existing pin, or silently disable evaluation already authorized by that pin.
- Routine reclassification is not an implicit runtime policy rollout.

An **emergency revocation** is a distinct, explicit, audited platform-security action reserved for critical risks such as sandbox escape, arbitrary execution, cross-tenant access, or private-data access.

Once accepted at the authoritative security-policy boundary:

- Automation blocks new affected evaluation, including evaluation under an otherwise `READY` or pinned patch.
- Evaluation crossing the revocation fence must recheck that fence before durable work-item persistence or handoff; its output is rejected after the fence. Potentially compromised evaluator workers are quarantined and replaced.
- The platform discovers affected published patches and active Automation scopes, pauses those scopes, and drives explicit disable or fenced rollback.
- Where no exact safe target exists, affected Automation remains fail closed while unrelated gameplay continues.

Emergency revocation does not redefine immutable published-patch contents or automatically reverse already-applied gameplay effects.

## Consequences

- Routine evolution preserves already reviewed and pinned behavior while preventing new use.
- Critical sandbox and data-boundary defects have a platform-security containment path.
- Implementation requires component-to-patch dependency discovery, active-scope discovery, evaluation fencing, durable audit, worker containment, and integration with pause/disable/rollback controls.
- The platform must prove that routine policy edits cannot invoke emergency authority accidentally.

## Alternatives Considered

### Dynamically Disable Every Reclassified Component

Rejected because routine migration or mistaken classification would become an unreviewed broad runtime rollout.

### Never Reclassify Already-Ready or Pinned Runtime Behavior

Rejected because critical sandbox or data-boundary risks could continue indefinitely.

### Stop All Automation or Gameplay During an Emergency

Rejected because affected Automation scopes can be fenced while unrelated gameplay remains available.

### Let Each Tenant Decide Whether to Contain a Critical Risk

Rejected because platform sandbox and isolation boundaries are platform-security responsibilities.

## Implementation and Proof Obligations

The current implementation lacks the core-script component policy, `UNSAFE` readiness path, affected-patch dependency index, emergency authority, active-scope discovery, and focused containment proof. The existing rollback RPC is not proof of the complete emergency workflow.

Proof must cover routine authoring/publication/readiness rejection, preservation of ready and pinned behavior, separation of routine and emergency authority, emergency authorization/audit, dependency and scope discovery, admission/evaluation/persistence/handoff races, worker quarantine, scoped pause, safe-target rollback or disable, no-safe-target fail-closed behavior, partial-containment recovery, unrelated gameplay continuity, and the fact that applied effects are not automatically reversed.

The exact component-policy storage owner, API/event names, security evidence workflow, and creator/operator UX remain implementation choices.

## Reversibility and Revisit Triggers

Component catalog representation, dependency indexing, workflow technology, API shapes, and UX may evolve while preserving the two-class authority split and scoped fail-closed containment. Revisit before broadening emergency criteria, permitting tenant override, making routine reclassification mutate live pins, or stopping unrelated gameplay.

## Required Documentation Alignment

- [Scripting quotas and operations](../system-architecture-scripting-quotas-and-operations.md)
- [Scripting DSL for designers](../system-architecture-scripting-dsl-for-designers.md)
- [Scripting control-plane operations](../system-architecture-scripting-control-plane-operations.md)

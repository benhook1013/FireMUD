# ADR 0121: Routine Component Migration and Explicit Emergency Revocation

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-10`
- Primary capability: `AS-1.2` script sandbox and execution safety
- Affected capabilities: `AR-1.5`, `AS-1.6`, `PO-1.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of component migration, immutable ready and pinned patches, security containment urgency, rollback availability, fail-closed scope, and gameplay continuity

## Context

Component safety policy changes can mean two materially different things. A component may need routine migration because its contract is deprecated, difficult to support safely in new content, or being replaced. It may instead present an urgent security risk such as sandbox escape, arbitrary execution, or access to cross-tenant or private data.

Treating every routine reclassification as a mutable live policy rollout would silently change already reviewed and pinned behavior, potentially disabling many tenants at once. Treating a critical exploit only as a future-publication concern would leave known vulnerable evaluation active until every affected operator notices and completes a rollback.

One `UNSAFE` label therefore cannot safely carry both ordinary authoring migration pressure and emergency runtime revocation authority.

## Decision

FireMUD separates routine component migration from emergency component revocation.

The existing `UNSAFE` classification is the routine **migration-required / new-use-blocked** class for core scripts:

- New publication and tenant-readiness transitions that reference the component fail closed with the canonical unsafe-component validation outcome.
- Creator tooling identifies the affected graph and requires migration before a replacement patch can publish or become `READY`.
- Reclassification does not mutate an already-`READY` artifact, move an existing pin, or silently disable evaluation already authorized by that pin.
- Routine reclassification is not an implicit runtime policy rollout.

An **emergency revocation** is a distinct, explicit, audited platform-security action. It is reserved for critical component risks such as sandbox escape, arbitrary execution, cross-tenant access, or private-data access; it is not inferred from every `UNSAFE` reclassification.

Once an emergency revocation is accepted at the authoritative security-policy boundary:

- Automation immediately blocks new affected evaluation, including evaluation under an otherwise `READY` or pinned patch.
- Evaluation already running at the revocation fence may be interrupted or finish computation, but it must recheck the emergency policy fence before durable work-item persistence or handoff; its output is rejected after the fence. If the defect may have compromised the evaluator process, incident containment quarantines and replaces affected workers rather than relying on cooperative cancellation.
- The platform discovers the affected published and active patches and their current Automation scopes rather than relying on individual tenants to identify exposure manually.
- The affected Automation scopes are paused through the normal audited admission controls while the platform drives an explicit disable or fenced rollback.
- Where an exact safe target exists, the ordinary preparation, pin, epoch, convergence, cancellation, purge, and rollback contracts remain authoritative.
- Where no safe target exists, affected Automation remains fail closed. Unrelated gameplay continues rather than turning component containment into an implicit whole-gameplay outage.

The emergency action changes runtime admission only through its explicit audited authority and containment workflow. It does not retroactively redefine the immutable contents of a published patch, claim that already-applied gameplay effects can be reversed automatically, or make routine migration classification a live mutable policy layer.

## Consequences

- Routine component evolution preserves predictable behavior for already reviewed and pinned releases while preventing new use.
- Critical sandbox and data-boundary defects have a platform-security containment path that does not wait for ordinary republishing.
- Emergency revocation needs an authoritative component-to-patch dependency index, active-scope discovery, immediate evaluation fencing, durable audit, and integration with existing pause, disable, and rollback controls.
- A post-evaluation persistence/handoff fence prevents work that crossed the revocation instant from becoming gameplay effects, while process-compromise incidents retain a worker-replacement path.
- Affected games may temporarily lose Automation behavior when no safe patch exists, but unrelated gameplay remains available.
- Operators and creators can distinguish migration work from a security incident instead of interpreting one ambiguous `UNSAFE` status.
- The platform must prove that a routine policy edit cannot accidentally exercise emergency runtime authority.

## Alternatives Considered

### Dynamically Disable Every Reclassified Component

Apply every `UNSAFE` change to all `READY` and pinned patches immediately. This is the strongest simple fail-closed rule, but it turns routine migration and mistaken classification into an unreviewed runtime rollout with potentially broad availability impact.

### Never Reclassify Already-Ready or Pinned Runtime Behavior

Block only future publication and require operators to republish voluntarily. Rejected for critical sandbox escape, arbitrary execution, cross-tenant, and private-data risks because known vulnerable evaluation could continue indefinitely.

### Stop All Automation or Gameplay During an Emergency

Pause the entire platform until every affected patch is replaced. Rejected because the platform can identify and fence affected Automation scopes while leaving unrelated gameplay operational.

### Let Each Tenant Decide Whether to Contain a Critical Risk

Notify creators and wait for tenant-local disable or rollback. Rejected because platform sandbox and isolation boundaries are platform-security responsibilities, and a delayed or absent tenant response could leave other tenants or private data exposed.

## Implementation and Proof Obligations

The current implementation does not provide the core-script component policy required by this decision. No core component registry, `UNSAFE` validation path, unsafe-component readiness failure, affected-patch dependency index, emergency revocation authority, active-scope discovery, or focused proof exists. Current script-definition intake validates event bindings, and patch readiness proceeds into `onLoad` without component-policy evaluation.

An admin-protected script-patch rollback RPC exists, but its current implementation directly changes the stored patch string and does not prove the complete target-state readiness, base-compatibility, pin-epoch, convergence, or emergency-containment workflow. Plugin component-policy reconciliation is a separate plugin lifecycle and is not proof of this core-script decision.

Implementation proof must cover routine unsafe classification at authoring, publication, and readiness; preservation of already-`READY` and pinned behavior under routine reclassification; deterministic separation between routine classification and emergency authority; authorization and durable audit of emergency revocation; complete component-to-patch and active-scope discovery; revocation races at admission, evaluation, work-item persistence, and handoff; immediate denial of new affected evaluation; rejection of output from evaluations that crossed the fence; worker quarantine/replacement for possible process compromise; scoped pause; safe-target disable or rollback; no-safe-target fail-closed behavior; retry and recovery of partial containment; unrelated gameplay continuity; and confirmation that already-applied effects are not represented as automatically reversed.

The exact component-policy storage owner, API and event names, evidence workflow used by platform security, and user-interface presentation remain implementation choices.

## Reversibility and Revisit Triggers

Component catalog representation, dependency indexing, workflow technology, API shapes, and creator/operator UX may evolve while preserving the two-class authority split and scoped fail-closed containment. Revisit this decision before broadening emergency criteria beyond critical sandbox or data-boundary risks, permitting tenant override of an active emergency revocation, making routine reclassification mutate live pins, or requiring an emergency to stop unrelated gameplay.

## Required Documentation Alignment

- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/system-architecture-scripting-dsl-for-designers.md`
- `design/architecture/system-architecture-scripting-control-plane-operations.md`

# ADR 0146: Event-Scoped Automated Tier A Credential Compliance

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `COMPLIANCE-01`
- Primary capability: `PO-3.2` environment, configuration, secret, certificate, and service-discovery delivery
- Affected capabilities: `PO-1.3`, `PO-3.1`, `PO-4.4`, `SF-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of measurable Tier A credential evidence, release and recovery gates, repair accessibility, unattended operation, self-hosting cost, and external secret-manager requirements

## Context

Tier A credentials include security-sensitive secrets and certificates whose age, provisioning, rotation, or loss can affect a player-facing environment. FireMUD needs measurable evidence that these credentials are suitable for the deployment or recovery event being authorized. A checked-in declaration alone does not demonstrate that provisioning or rotation occurred, and an expired or incomplete record must not silently satisfy production readiness.

The compliance boundary must also remain operable. A gate that prevents credential rotation, remediation, rollback, or quarantined recovery work would obstruct the actions required to restore compliance. Alerting on a running environment must not turn stale evidence into an automatic game shutdown. FireMUD may be operated by one person, and that operator may be unavailable when an automated restart or recovery begins, so routine compliance evidence cannot depend on an expert manually reconstructing a procedure during the event.

Requiring Vault or another external secret manager would not by itself prove correct credential use and would impose disproportionate complexity on hobby and small hosted deployments. The requirement is measurable, event-bound evidence and safe automation, not a particular secret-storage product.

## Decision

### Tier A Compliance Is Measurable and Event-Scoped

Every Tier A credential class declares its owner, applicable environment, measurable age or provisioning requirement, alerting behavior, and emergency handling procedure. Compliance evidence is scoped to the concrete provisioning, rotation, deployment, or recovery event it supports. It cannot be satisfied by a generic statement that the credential is managed or by an unrelated earlier event.

The evidence records the actual result of provisioning or rotation through content-addressed output. The recorded digest binds the compliance claim to that output so later checks can detect substitution or drift. A Git-tracked compliance record is an immutable audit index to this evidence; its existence, timestamp, or commit history is not self-proof that the underlying action occurred.

### Enforcement Is Limited to Readiness Claims

Tier A noncompliance hard-gates only:

- production promotion;
- production first-live or reopen;
- staging activity used as production-promotion or production-readiness evidence; and
- a disaster-recovery-readiness claim.

The gate does not prevent credential provisioning, rotation, emergency remediation, rollback, detached validation, quarantined recovery, or other work required to regain compliance. Those actions remain possible while player traffic or the affected readiness claim remains closed.

A `not-provisioned` result is valid only when the environment inventory independently corroborates that the relevant environment does not exist. It cannot be used to bypass evidence for an existing or partially provisioned environment.

Age or evidence failures alert immediately according to the declared Tier A policy. They do not automatically shut down an otherwise running player-facing environment. Operators handle the alert through the declared rotation or emergency procedure, and the failure blocks the next applicable promotion, first-live, reopen, staging-evidence, or disaster-recovery-readiness gate until corrected.

### Playbooks Generate Evidence Automatically

Provisioning, rotation, cold-start, and recovery playbooks perform their applicable Tier A checks and generate the content-addressed compliance evidence as part of the automated workflow. Tests exercise these playbooks and their fail-closed readiness behavior before they are trusted for startup or recovery.

The supported production workflow must be executable unattended once legitimately triggered. It must not depend on an operator being present to transcribe evidence, update timestamps, interpret routine pass conditions, or restart the process after each compliance step. If automation cannot establish compliance, it leaves the environment quarantined or traffic closed, emits an actionable diagnostic and alert, and preserves access to remediation and safe rerun paths.

This automation requirement supports single-operator production and future automatically triggered cold starts or recoveries. It does not authorize an unsafe automatic trigger or make recovery complete merely because the playbook ran; the applicable recovery and traffic-reopen decisions retain their own proof obligations.

### No External Secret Manager Is Required

FireMUD does not require Vault or another external secret-management service. Deployments may use one, but the same Tier A evidence, automation, alerting, emergency handling, and readiness gates apply regardless of the storage mechanism. A secret manager integration is not a substitute for event-scoped evidence that the intended credential was actually provisioned or rotated for the relevant environment.

## Consequences

- Production readiness and recovery claims rely on evidence from real credential operations rather than declarations or Git timestamps alone.
- Automated playbooks carry the routine evidence burden, allowing a single-operator production deployment to restart or recover without requiring continuous operator presence.
- A failed compliance check closes only the applicable readiness boundary; it does not lock operators or automation out of the repair path.
- A stale record raises an operational incident but does not by itself interrupt healthy active gameplay.
- Hobby and small hosted deployments can meet the evidence contract without operating an additional secret-management service.
- Content-addressed event output and automated evidence retention add implementation and storage work to provisioning, rotation, startup, and recovery workflows.

## Alternatives Considered

### Treat the Git Record as Proof

A committed record is useful for audit discovery and version history, but it can be written without performing the credential operation. It remains an index to content-addressed event output rather than proof by itself.

### Gate Every Operation on Current Compliance

This would fail closed aggressively but could prevent the remediation, rotation, rollback, or quarantined recovery needed to correct the failure. Enforcement is limited to player-facing readiness and formal evidence claims while repair paths remain available.

### Shut Down Running Games When Evidence Becomes Stale

Immediate shutdown could reduce the duration of a policy violation, but it creates availability risk from missed evidence refreshes and does not prove that the live credential is compromised. FireMUD alerts immediately and blocks subsequent readiness gates instead.

### Require an External Secret Manager

An external manager can improve storage and rotation workflows, but it adds deployment complexity and still cannot prove that the correct credential reached the intended event. It remains optional.

### Depend on a Manual Operator Checklist

Manual evidence collection is fragile during cold start or recovery and is unsuitable when a single operator is unavailable. Tested playbooks generate evidence and preserve fail-closed readiness behavior automatically.

## Implementation and Proof Obligations

Implementation must define the Tier A inventory and measurable rules; capture and retain content-addressed provisioning and rotation output; verify that Git audit records resolve to that output; corroborate `not-provisioned` against environment inventory; and enforce the limited promotion, first-live, reopen, staging-evidence, and disaster-recovery-readiness gates.

Focused proof must cover successful unattended evidence generation, missing and substituted event output, stale evidence, an existing environment falsely marked `not-provisioned`, alert delivery, repair operations while noncompliant, rerun after remediation, and continued healthy gameplay when evidence becomes stale. Cold-start and recovery proof must show that an unavailable operator is not required for routine compliance steps, while an unresolved failure keeps traffic closed or the environment quarantined with an actionable diagnostic.

No implementation or proof may infer compliance solely from a Git commit, record timestamp, successful playbook process exit, or the presence of a secret-manager integration.

## Reversibility and Revisit Triggers

Revisit the Tier A measurements when credential technology, threat models, or operating environments change. Revisit the automation boundary if real recovery exercises identify a step that cannot safely run unattended. Consider making an external secret manager part of a future deployment profile only when measured operational or security needs justify its additional dependency; it does not replace this evidence contract.

## Required Documentation Alignment

- `design/architecture/infrastructure/environment-and-secrets-overview.md`
- `design/architecture/infrastructure/environment-and-secrets-catalog.md`
- `design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md`
- `design/architecture/system-architecture-post-restore-hardening.md`
- `design/architecture/infrastructure/schedule.md`

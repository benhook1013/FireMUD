# ADR 0155: Automated Event-Classified Post-Restore Trust Reset

## Status

Accepted

## Implementation Status

This decision is not implemented. Event-classified credential dispositions, automated resumable hardening, fresh-boundary trust handling, and post-restore proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `RECOVERY-02`
- Decision date: 2026-07-20
- Decision key: `RECOVERY-02`
- Primary capability: `PO-3.4` Backup, restore, disaster recovery, and self-hosted recovery
- Affected capabilities: `SF-1.3`, `PO-4.4`, `GR-1.4`, `PO-2.1`, `PO-3.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of universal rewind safety, trust-material scope, unattended recovery, compromise handling, sanitization, reopen authority, and one-person production cost

## Context

[ADR 0015](./adr-0015-online-backup-and-environment-wide-cold-start-recovery.md), [ADR 0153](./adr-0153-measured-online-backup-rpo-and-future-pitr-trigger.md), and [ADR 0154](./adr-0154-automated-recovery-proof-and-differentiated-traffic-open-gates.md) establish environment-wide quarantined `cold_start_restore`, measured backup recovery points, automated recovery proof, operator-authorized destructive point selection, and crash-recoverable controlled reopen.

Every PostgreSQL rewind can place restored durable state behind newer sessions, leases, coordination, workflows, external effects, and trust generations. Those timeline conflicts require a universal authority reset. They do not prove that every credential outside PostgreSQL was restored or compromised. A same-boundary logical PostgreSQL rewind can retain current Kubernetes and provider trust material, while a fresh-boundary restore, restored Secret, unprovable credential lineage, or compromise event cannot safely make that assumption.

[ADR 0151](./adr-0151-event-scoped-automated-tier-a-credential-compliance.md) requires event-scoped credential evidence and unattended playbooks. [ADR 0152](./adr-0152-phased-environment-bound-deployment-preflight-and-expected-bindings.md) requires live environment and binding observation while retaining one administrative trust domain for current single-operator production. Post-restore trust reset therefore needs one universal recovery boundary plus an automated, evidence-backed disposition for each credential class rather than either a manual checklist or unconditional rotation without regard to what the restore changed.

## Decision

### Universal Controls Apply to Every Supported PostgreSQL Rewind

Every supported PostgreSQL rewind must complete all of these controls before the recovery can advance to its applicable traffic-open gate. For player-facing traffic, that downstream gate is the controlled-reopen state machine from ADR 0154:

- enter and retain full restore-safe quarantine for player ingress, normal background work, automation, outbound effects, publication, and other side-effecting paths;
- fence old owners and authority from the pre-restore timeline;
- start the restored boundary with empty Coordination Redis;
- invalidate gameplay and Account sessions from the restored timeline;
- advance or recreate every applicable gameplay epoch and fence;
- obtain a safe disposition for every declared durable participant and external-effect family;
- validate the target environment and its enabled internal and external bindings;
- complete required hardening and smoke validation; and
- reopen only through the idempotent, crash-recoverable controlled-reopen state machine from ADR 0154.

These controls are not conditional on whether credential material was restored. They address timeline and authority conflicts inherent in every database rewind.

### Every Credential Class Records One Event-Classified Disposition

Each applicable credential and certificate class records exactly one machine-checkable post-restore disposition:

- `rotated`: the credential value or key authority was replaced;
- `reissued`: a certificate or equivalent issued identity was replaced;
- `rebound`: the recovered environment was attached to a current, correct environment-owned credential or provider binding only after the class's trust boundary was safely re-established without reusing restored secrets, private keys, signing keys, or other unsafe trust material; the binding, material lineage, and rejection or replacement of unsafe restored material are proved. A class containing such material requires newly issued or rotated material and cannot qualify by merely reusing a binding; or
- `verified_not_restored`: live evidence proves the current trust material was outside the restore artifact, was not rolled back, and remains correctly bound to the target environment.

The disposition is part of the event-scoped evidence required by ADR 0151. A generic assertion, old timestamp, successful process exit, or checked-in record without the underlying operation and live binding evidence cannot establish it.

### Same-Boundary PostgreSQL-Only Rewind May Preserve Proved Current Trust

For a same-boundary restore that rewinds only PostgreSQL, a credential class may use `verified_not_restored` when automation proves that its current Kubernetes or provider trust material was outside the selected restore artifact, was not replaced by snapshot-era state, and still matches the live expected binding for that environment. For `postgres-application-credentials`, Kubernetes/provider Secret evidence alone is never sufficient: the proof must also include a read-only target-bound PostgreSQL role-verifier check showing that the restored role's current verifier accepts the current trusted application credential value and matches its non-secret credential lineage, without retaining the password. If that verifier/value/lineage proof is unavailable or mismatched, the credential requires rotation or a fully evidenced safe rebound before reopen. For JWT, that proof must include the current Account signing authority and JWKS remaining outside the restore, exact target binding, current issuer generation and keyset integrity, and validator acceptance of that unchanged current set. The universal Account authority/delegation and Game Session gameplay-binding/session invalidation still applies.

This does not preserve database-derived sessions, issuer generations, gameplay epochs, or other authority whose durable state was rewound. The universal session invalidation and old-authority fencing controls still apply.

`verified_not_restored` is conditional evidence, not a credential-rotation shortcut. In this same-boundary JWT branch it does not require a replacement `kid`, rejection of the still-current `kid`, or an issuer-generation advance solely as credential-rotation evidence. The `rotated`, `reissued`, and applicable `rebound` branches retain replacement-key evidence, rejection of every restored or old `kid`, and acceptance of the replacement `kid`; compromise scope always uses the full hard cutover.

If the automation cannot prove the current credential lineage and binding, `verified_not_restored` is unavailable and the credential must be rotated, reissued, or safely rebound only with the trust-boundary and material-lineage proof required above before reopen.

### Fresh Boundaries and Restored or Unprovable Trust Use Fresh Material

A fresh cluster, namespace, control-plane boundary, or replacement host must rotate or reissue unsafe trust material, or use `rebound` only for a class whose trust boundary was safely re-established without reusing that material and whose binding and lineage are proved. The same requirement applies when Secrets, certificate resources, trust stores, provider credentials, or their authoritative lineage were restored from the recovery source, or when automation cannot prove that current trust remained outside the rewind.

Snapshot-era trust material may be used only as a quarantined recovery input where necessary. It cannot remain the player-facing authority merely because it was present in the restored resources.

### Compromise Uses Full Hard Cutover

Known or suspected compromise uses the complete compromise-response hard cutover for every affected trust authority. Restored or compromised keys are removed from active trust, issuer authority and sessions are invalidated, replacements are distributed, and every declared validator or peer proves rejection of the old identity and acceptance of the replacement before reopen.

The narrower `verified_not_restored` disposition cannot be used for a credential class inside the suspected compromise scope.

### Recovery and Evidence Execution Are Automated and Resumable

Recovery automation classifies the restore event, inventories applicable credentials, gathers live binding and artifact-lineage evidence, executes each required disposition, records content-addressed results, validates convergence, and advances the recovery state only when every required control passes.

Workflow coordination, evidence writes, observations, and other owner-defined retry-safe phases are idempotent or replayable where their contract permits. Destructive restore and credential mutations are not presumed idempotent: they use durable operation or attempt identity and validate an ambiguous outcome before retry. A failed rotation, reissuance, rebind, validation, evidence write, or convergence check leaves the environment quarantined with an actionable diagnostic and a safe continuation or rollback path. Recovery and remediation remain executable while readiness is closed.

### Human Authority Is Narrow in the Single-Operator Model

The current single-operator model requires human authorization for:

- selecting and accepting the destructive `recoveryPointId` with its controller-owned `recoveryPointDecisionAt` and exact `effectiveDataLossWindow` approval under [Backup Recovery Evidence and Compliance](../system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record), unless a separately accepted automatic-DR policy pre-authorizes that same point/decision/window; and
- the final player-facing reopen after the automated recovery record reaches the proved ready state.

Routine classification, quarantine, trust disposition, convergence, validation, evidence generation, retries, and isolated drills do not require separate security, database, platform, or operations approvers. Those responsibilities are automated controls within the current single administrative trust domain, not independent human approval domains.

### Staging Sanitization Depends on Source Data

A staging restore requires production-data sanitization evidence only when the selected recovery source contains production-origin data. A staging restore from staging-origin or synthetic data does not manufacture sanitization evidence for a risk that is absent, while all universal rewind and applicable credential-disposition controls still apply.

## Consequences

- Every database rewind retains the strong quarantine, authority reset, convergence, and controlled-reopen boundary.
- Same-boundary PostgreSQL-only recovery avoids unnecessary JWT, database-password, and certificate churn when live evidence proves those authorities were not restored.
- Fresh-boundary, restored-secret, unprovable-lineage, and compromise events still fail closed on fresh trust material.
- Every credential class has an explicit, reviewable result rather than being silently assumed safe.
- Automated owner-defined retry-safe/idempotent phases reduce one-person recovery burden and prevent routine evidence transcription from extending recovery time; destructive restore and credential mutations instead require durable operation identity and ambiguous-outcome validation.
- Human judgment remains at the actions that knowingly accept player data loss and restore player traffic.
- Conditional trust preservation reduces avoidable rotation failure and recovery-time cost but requires reliable artifact-lineage and live-binding observation.
- Production-origin staging data remains subject to sanitization without imposing irrelevant proof on other staging restores.

## Alternatives Considered

### Rotate Every Credential After Every PostgreSQL Rewind

This is simple to state and safely covers fresh-boundary and restored-secret events, but it adds unnecessary rotation, rollout, convergence, and lockout risk when a same-boundary logical restore did not touch current external trust. Event-classified dispositions retain fail-closed proof without forcing unrelated trust churn.

### Preserve Every Credential Unless Compromise Is Confirmed

This minimizes recovery work but is unsafe when Secrets or trust resources were restored, the target is a fresh boundary, or lineage cannot be proved. Unprovable trust uses fresh material.

### Use an Operator Checklist Instead of Typed Dispositions

Manual judgment is fragile during recovery and unsuitable for unattended single-operator execution. Machine-checkable dispositions and content-addressed evidence make missing or uncertain credential classes fail closed.

### Require Independent Approvers for Every Hardening Step

Independent approval can be valuable across separate trust domains, but FireMUD currently has one operator and one administrative trust domain. Per-step human approval would add delay without creating independent assurance. The destructive recovery point and final reopen retain explicit human authority.

### Sanitize Every Staging Restore Regardless of Source

This creates procedural evidence without a production-data risk when the source is already staging or synthetic. Sanitization remains mandatory exactly when production-origin data is introduced.

## Implementation and Proof Obligations

Current implementation does not satisfy this decision. The existing cluster restore helper restarts workloads without enforcing quarantine; no resumable recovery controller or post-restore trust-disposition orchestrator exists; JWT and database credential rotation and certificate convergence are not automated; external-binding validation still consumes legacy evidence fields; and no production-equivalent proof exercises the complete boundary.

Select and report the required checks and evidence under the shared [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md) workflow; record execution results in PR/CI evidence or the owning implementation tracker rather than in this decision record.

Implementation must:

- represent restore classification, universal controls, per-credential dispositions, diagnostics, and controlled-reopen progress in durable monotonic recovery state;
- prove artifact coverage and live environment bindings before accepting `verified_not_restored`;
- automate resumable, owner-safe rotation, reissuance, rebind, and convergence checks for every applicable credential class; destructive mutations must use durable operation identity and ambiguous-outcome validation rather than assuming idempotency;
- persist the database-credential-rotation phase before password mutation, support crash recovery or rollback across the database, Secret, and consumer steps, and mark `rotated` or `rebound` only after every authoritative consumer is healthy and the resulting state is known consistent;
- retain content-addressed operation output and link it from event-scoped compliance and recovery evidence;
- preserve remediation access while readiness remains closed;
- apply staging sanitization only to production-origin recovery sources; and
- require the two human authorization points without adding manual routine evidence or per-step approval.

Focused proof must cover same-boundary PostgreSQL-only rewind with proved current trust; restored Secret and fresh-boundary recovery; missing or contradictory lineage; partial and retried rotation, reissuance, and rebind; validator and peer non-convergence; compromise hard cutover; disabled optional integrations; production-origin and non-production-origin staging restores; absent operator during routine automation; destructive-point rejection; reopen rejection; and successful controlled reopen after every universal control and applicable disposition passes.

The same-boundary proof must specifically show current Account signing authority/JWKS binding, current generation and keyset integrity, Account/Game Session invalidation, and validator acceptance of the unchanged current set. It must not fail merely because no replacement `kid`, still-current-`kid` rejection, or credential-rotation-only issuer-generation advance exists. Replacement dispositions must separately prove replacement-key acceptance and restored/old-`kid` rejection.

## Reversibility and Revisit Triggers

Revisit the disposition set when a new credential technology cannot be represented safely by rotation, reissuance, rebind, or proof that it was not restored. Revisit the human-approval boundary when FireMUD introduces independent administrative trust domains, regulatory separation of duties, or an accepted automatic-DR policy. Universal rewind quarantine and old-authority fencing remain in force unless a separately proved recovery mode replaces them.

## Required Documentation Alignment

- [`design/architecture/system-architecture-backup-recovery.md`](../system-architecture-backup-recovery.md)
- [`design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md`](../system-architecture-backup-recovery-evidence-and-compliance.md)
- [`design/architecture/system-architecture-post-restore-hardening.md`](../system-architecture-post-restore-hardening.md)
- [`design/architecture/system-architecture-jwt-compromise-runbook.md`](../system-architecture-jwt-compromise-runbook.md)
- [`design/architecture/system-architecture-deployment-runbook.md`](../system-architecture-deployment-runbook.md)
- [`design/architecture/system-architecture-deploy-preflight-policy.md`](../system-architecture-deploy-preflight-policy.md)
- [`design/architecture/infrastructure/environment-and-secrets-overview.md`](../infrastructure/environment-and-secrets-overview.md)
- [`design/architecture/infrastructure/environment-and-secrets-catalog.md`](../infrastructure/environment-and-secrets-catalog.md)
